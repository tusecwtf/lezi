use std::fs;
use std::path::Path;
use std::sync::Arc;

use axum::body::Body;
use axum::extract::State;
use axum::http::header::CONTENT_TYPE;
use axum::http::{HeaderMap, HeaderValue};
use axum::response::Response;
use axum::Json;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::{authenticate, ApiError, AppState};

/// Authenticated app-update metadata for already-joined family devices.
/// Loads deploy-readable JSON from [AppState::app_update_metadata_path]
/// (default `{data_dir}/app-update.json`). Unauthenticated callers receive 401.
/// Missing file → 404 so clients can fail honestly; unreadable I/O, invalid JSON,
/// or structurally illegal metadata (including `min_supported > version_code`) → 500
/// as server misconfiguration (not a valid update channel).
///
/// HTTP route entrypoint — `pub(crate)` for crate-root domain-path assembly.
pub(crate) async fn get_app_update(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    let _principal = authenticate(&state, &headers)?;
    Ok(Json(load_app_update_metadata(
        &state.app_update_metadata_path,
    )?))
}

/// Authenticated release APK download for already-joined family devices.
/// Bytes are served only when the on-disk APK sha256 matches deploy metadata.
///
/// HTTP route entrypoint — `pub(crate)` for crate-root domain-path assembly.
pub(crate) async fn get_app_update_apk(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let _principal = authenticate(&state, &headers)?;
    let verified =
        load_verified_app_update(&state.app_update_metadata_path, &state.app_update_apk_path)?;
    let mut response = Response::new(Body::from(verified.bytes));
    response.headers_mut().insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/vnd.android.package-archive"),
    );
    Ok(response)
}

pub(crate) struct VerifiedAppUpdate {
    pub(crate) metadata: Value,
    pub(crate) bytes: Vec<u8>,
}

pub(crate) fn load_verified_app_update(
    metadata_path: &Path,
    apk_path: &Path,
) -> Result<VerifiedAppUpdate, ApiError> {
    let metadata = load_app_update_metadata(metadata_path)?;
    let expected_sha256 = metadata
        .get("sha256")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::internal("App update metadata is missing sha256"))?;
    let bytes = match fs::read(apk_path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::not_found("App update package is not available"));
        }
        Err(_) => {
            return Err(ApiError::internal("Failed to read app update package"));
        }
    };
    if bytes.is_empty() {
        return Err(ApiError::internal("App update package is empty"));
    }
    let actual_sha256 = hex_sha256(&bytes);
    if actual_sha256 != expected_sha256 {
        tracing::error!(
            expected = %expected_sha256,
            actual = %actual_sha256,
            path = %apk_path.display(),
            "app update APK sha256 does not match metadata"
        );
        return Err(ApiError::internal(
            "App update package integrity check failed",
        ));
    }
    Ok(VerifiedAppUpdate { metadata, bytes })
}

fn hex_sha256(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

/// Cross-module seam: also used by crate-root `require_supported_client`.
pub(crate) fn load_app_update_metadata(path: &Path) -> Result<Value, ApiError> {
    let raw = match fs::read_to_string(path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::not_found("App update metadata is not available"));
        }
        Err(error) => {
            tracing::error!(
                path = %path.display(),
                error = %error,
                "failed to read app update metadata"
            );
            return Err(ApiError::internal("Failed to read app update metadata"));
        }
    };
    let parsed: Value = match serde_json::from_str(&raw) {
        Ok(value) => value,
        Err(error) => {
            tracing::error!(
                path = %path.display(),
                error = %error,
                "app update metadata is invalid JSON"
            );
            return Err(ApiError::internal("App update metadata is invalid JSON"));
        }
    };
    match normalize_app_update_metadata(&parsed) {
        Ok(body) => Ok(body),
        Err(error) => {
            // Hand-edited /data/app-update.json misconfig (deadlock min>version, bad
            // package_name, out-of-range codes, …) must show up in server logs.
            tracing::error!(
                path = %path.display(),
                detail = %error.detail,
                "app update metadata rejected"
            );
            Err(error)
        }
    }
}

fn normalize_app_update_metadata(value: &Value) -> Result<Value, ApiError> {
    let object = value
        .as_object()
        .ok_or_else(|| ApiError::internal("App update metadata must be a JSON object"))?;
    let package_name = required_metadata_string(object, "package_name")?;
    if package_name != "com.lezi.babylog" {
        return Err(ApiError::internal(
            "App update metadata package_name must be com.lezi.babylog",
        ));
    }
    let version_code = required_metadata_u64(object, "version_code")?;
    if version_code == 0 || version_code > i32::MAX as u64 {
        return Err(ApiError::internal(
            "App update metadata version_code must be a positive 32-bit integer",
        ));
    }
    let version_name = required_metadata_string(object, "version_name")?;
    let min_supported_version_code = required_metadata_u64(object, "min_supported_version_code")?;
    if min_supported_version_code > i32::MAX as u64 {
        return Err(ApiError::internal(
            "App update metadata min_supported_version_code is out of range",
        ));
    }
    // Force floor above the package on the channel deadlocks clients: they must upgrade
    // yet the "latest" APK cannot satisfy min_supported. Reject at load/normalize.
    if min_supported_version_code > version_code {
        return Err(ApiError::internal(
            "App update metadata min_supported_version_code must not exceed version_code",
        ));
    }
    let sha256 = required_metadata_string(object, "sha256")?;
    if !is_sha256_hex(&sha256) {
        return Err(ApiError::internal(
            "App update metadata sha256 must be 64 lowercase hex characters",
        ));
    }
    let mut body = json!({
        "package_name": package_name,
        "version_code": version_code,
        "version_name": version_name,
        "min_supported_version_code": min_supported_version_code,
        "sha256": sha256,
    });
    if let Some(notes) = object.get("release_notes") {
        match notes {
            Value::Null => {}
            Value::String(text) => {
                let trimmed = text.trim();
                if !trimmed.is_empty() {
                    body.as_object_mut()
                        .expect("app update body is object")
                        .insert(
                            "release_notes".to_owned(),
                            Value::String(trimmed.to_owned()),
                        );
                }
            }
            _ => {
                return Err(ApiError::internal(
                    "App update metadata release_notes must be a string when present",
                ));
            }
        }
    }
    Ok(body)
}

fn required_metadata_string(
    object: &serde_json::Map<String, Value>,
    key: &str,
) -> Result<String, ApiError> {
    match object.get(key) {
        Some(Value::String(value)) => {
            let trimmed = value.trim();
            if trimmed.is_empty() {
                Err(ApiError::internal(format!(
                    "App update metadata {key} must be a non-empty string"
                )))
            } else {
                Ok(trimmed.to_owned())
            }
        }
        _ => Err(ApiError::internal(format!(
            "App update metadata {key} must be a non-empty string"
        ))),
    }
}

fn required_metadata_u64(
    object: &serde_json::Map<String, Value>,
    key: &str,
) -> Result<u64, ApiError> {
    match object.get(key) {
        Some(Value::Number(number)) => number.as_u64().ok_or_else(|| {
            ApiError::internal(format!(
                "App update metadata {key} must be a non-negative integer"
            ))
        }),
        _ => Err(ApiError::internal(format!(
            "App update metadata {key} must be a non-negative integer"
        ))),
    }
}

fn is_sha256_hex(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::http::StatusCode;
    use serde_json::json;

    fn sample_app_update_metadata(version_code: u64, min_supported: u64) -> Value {
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": version_code,
            "version_name": "0.3.1",
            "min_supported_version_code": min_supported,
            "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        })
    }

    #[test]
    fn normalize_app_update_metadata_rejects_min_supported_above_version_code() {
        let error = normalize_app_update_metadata(&sample_app_update_metadata(7, 8))
            .expect_err("min_supported > version_code must not load");
        assert_eq!(error.status, StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            error.detail,
            json!("App update metadata min_supported_version_code must not exceed version_code")
        );
    }

    #[test]
    fn normalize_app_update_metadata_allows_min_supported_equal_to_version_code() {
        let body = normalize_app_update_metadata(&sample_app_update_metadata(7, 7))
            .expect("min_supported == version_code is a legal force floor");
        assert_eq!(body["version_code"], json!(7));
        assert_eq!(body["min_supported_version_code"], json!(7));
    }
}
