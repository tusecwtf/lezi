use std::fs;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use axum::body::{Body, Bytes};
use axum::extract::State;
use axum::http::header::CONTENT_TYPE;
use axum::http::{HeaderMap, HeaderValue};
use axum::response::Response;
use axum::Json;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::{authenticate, run_blocking, ApiError, AppState};

#[derive(Debug, Clone, PartialEq, Eq)]
struct FileStamp {
    modified: SystemTime,
    len: u64,
}

#[derive(Clone)]
struct CachedMetadata {
    stamp: FileStamp,
    value: Value,
}

/// Positive or negative release-channel cache entry, keyed by mtime+len stamps.
/// Negative entries avoid re-reading/re-hashing a permanently half-deployed APK
/// on every gated pull/media/bundle request.
#[derive(Clone)]
enum CachedRelease {
    Verified {
        metadata_stamp: FileStamp,
        apk_stamp: FileStamp,
        value: VerifiedAppUpdate,
    },
    /// Integrity/content failure for this stamp pair. Do not re-hash until stamps change.
    Unverified {
        metadata_stamp: FileStamp,
        apk_stamp: FileStamp,
        status: axum::http::StatusCode,
        detail: Arc<str>,
    },
}

/// Policy floor from a successfully verified package pair (no APK payload).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct VerifiedChannelFloor {
    pub(crate) min_supported_version_code: u64,
    pub(crate) version_code: u64,
}

/// Per-process deploy artifact cache. Files are revalidated by mtime+length so
/// an atomic package replacement becomes visible without restarting the NAS
/// service, while steady-state requests avoid full JSON reads and APK hashing.
///
/// Negative (unverified) stamp pairs are cached so a permanent half-deploy does
/// not re-stat + re-read + re-hash the full APK on every request. A last-known-good
/// floor is retained across mid-promote windows (new APK + old metadata) so the
/// version gate keeps enforcing the previous verified floor until the new pair
/// verifies; fail-open only when no prior verified pair exists or the channel
/// files are gone.
#[derive(Default)]
pub(crate) struct AppUpdateCache {
    metadata: Mutex<Option<CachedMetadata>>,
    release: Mutex<Option<CachedRelease>>,
    last_good_floor: Mutex<Option<VerifiedChannelFloor>>,
    /// Stamp pair for which we already emitted a gate fail-open / retain warn.
    gate_logged: Mutex<Option<(FileStamp, FileStamp)>>,
}

impl AppUpdateCache {
    pub(crate) fn load_metadata(&self, path: &Path) -> Result<Value, ApiError> {
        let stamp = metadata_stamp(path)?;
        if let Some(cached) = self
            .metadata
            .lock()
            .map_err(|_| ApiError::internal("App update metadata cache is unavailable"))?
            .as_ref()
            .filter(|cached| cached.stamp == stamp)
        {
            return Ok(cached.value.clone());
        }
        let value = load_app_update_metadata(path)?;
        *self
            .metadata
            .lock()
            .map_err(|_| ApiError::internal("App update metadata cache is unavailable"))? =
            Some(CachedMetadata {
                stamp,
                value: value.clone(),
            });
        Ok(value)
    }

    pub(crate) fn load_verified(
        &self,
        metadata_path: &Path,
        apk_path: &Path,
    ) -> Result<VerifiedAppUpdate, ApiError> {
        let metadata_stamp = metadata_stamp(metadata_path)?;
        let apk_stamp = apk_stamp(apk_path)?;
        if let Some(cached) = self
            .release
            .lock()
            .map_err(|_| ApiError::internal("App update package cache is unavailable"))?
            .as_ref()
        {
            match cached {
                CachedRelease::Verified {
                    metadata_stamp: ms,
                    apk_stamp: as_,
                    value,
                } if *ms == metadata_stamp && *as_ == apk_stamp => {
                    return Ok(value.clone());
                }
                CachedRelease::Unverified {
                    metadata_stamp: ms,
                    apk_stamp: as_,
                    status,
                    detail,
                } if *ms == metadata_stamp && *as_ == apk_stamp => {
                    return Err(ApiError::new(*status, detail.as_ref()));
                }
                _ => {}
            }
        }
        let metadata = self.load_metadata(metadata_path)?;
        match load_verified_app_update_from_metadata(metadata, apk_path) {
            Ok(value) => {
                let floor = value.floor();
                *self
                    .last_good_floor
                    .lock()
                    .map_err(|_| ApiError::internal("App update package cache is unavailable"))? =
                    Some(floor);
                *self
                    .release
                    .lock()
                    .map_err(|_| ApiError::internal("App update package cache is unavailable"))? =
                    Some(CachedRelease::Verified {
                        metadata_stamp,
                        apk_stamp,
                        value: value.clone(),
                    });
                // Clear fail-open log latch so a later breakage re-warns.
                *self
                    .gate_logged
                    .lock()
                    .map_err(|_| ApiError::internal("App update package cache is unavailable"))? =
                    None;
                Ok(value)
            }
            Err(error) => {
                // Cache integrity/content failures for this stamp pair only.
                // NotFound on missing files is handled before stamps exist; here
                // both files are present so negative cache is safe and correct.
                *self
                    .release
                    .lock()
                    .map_err(|_| ApiError::internal("App update package cache is unavailable"))? =
                    Some(CachedRelease::Unverified {
                        metadata_stamp,
                        apk_stamp,
                        status: error.status,
                        detail: detail_arc(&error.detail),
                    });
                Err(error)
            }
        }
    }

    /// Version-gate seam: enforce min_supported only when a verified channel is
    /// available, or retain the last successfully verified floor during a brief
    /// mid-promote integrity miss. Returns `None` to fail-open (never verified,
    /// or channel files absent). Logs once per negative stamp pair.
    pub(crate) fn min_supported_if_verified(
        &self,
        metadata_path: &Path,
        apk_path: &Path,
    ) -> Option<u64> {
        match self.load_verified(metadata_path, apk_path) {
            Ok(verified) => Some(verified.min_supported_version_code),
            Err(error) => {
                // Channel files absent → no floor (and drop last-good so a removed
                // channel cannot keep bricking clients without an install path).
                if error.status == axum::http::StatusCode::NOT_FOUND {
                    if let Ok(mut last) = self.last_good_floor.lock() {
                        if last.is_some() {
                            tracing::warn!(
                                detail = %error.detail,
                                "app-update channel gone; clearing last-known-good version floor (gate fail-open)"
                            );
                            *last = None;
                        }
                    }
                    let _ = self.gate_logged.lock().map(|mut g| *g = None);
                    return None;
                }

                // Integrity / I/O failure with stamps present: retain last-good if any.
                let stamps = match (metadata_stamp(metadata_path).ok(), apk_stamp(apk_path).ok()) {
                    (Some(ms), Some(as_)) => Some((ms, as_)),
                    _ => None,
                };

                let retained = self
                    .last_good_floor
                    .lock()
                    .ok()
                    .and_then(|g| g.as_ref().copied());

                if let Some((ms, as_)) = stamps {
                    self.log_gate_once(ms, as_, &error, retained);
                } else {
                    tracing::warn!(
                        detail = %error.detail,
                        retained_min = retained.map(|f| f.min_supported_version_code),
                        "app-update channel unverified; gate fail-open or retaining last-known-good floor"
                    );
                }

                retained.map(|f| f.min_supported_version_code)
            }
        }
    }

    fn log_gate_once(
        &self,
        metadata_stamp: FileStamp,
        apk_stamp: FileStamp,
        error: &ApiError,
        retained: Option<VerifiedChannelFloor>,
    ) {
        let Ok(mut logged) = self.gate_logged.lock() else {
            return;
        };
        let pair = (metadata_stamp, apk_stamp);
        if logged.as_ref() == Some(&pair) {
            return;
        }
        *logged = Some(pair);
        match retained {
            Some(floor) => {
                tracing::warn!(
                    detail = %error.detail,
                    retained_min = floor.min_supported_version_code,
                    retained_version = floor.version_code,
                    "app-update channel unverified; retaining last-known-good version floor until a new pair verifies"
                );
            }
            None => {
                tracing::warn!(
                    detail = %error.detail,
                    "app-update channel unverified; gate not enforcing min_supported (fail-open, never verified)"
                );
            }
        }
    }
}

/// Authenticated app-update metadata for already-joined family devices.
/// Served only when the on-disk APK verifies against metadata sha256 so clients
/// never receive a force-floor without an installable package. Missing metadata
/// or missing APK → 404; unreadable I/O, invalid JSON, structural illegal metadata,
/// or hash mismatch → 500 as server misconfiguration.
///
/// HTTP route entrypoint — `pub(crate)` for crate-root domain-path assembly.
pub(crate) async fn get_app_update(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    let _principal = authenticate(&state, &headers).await?;
    let blocking_state = state.clone();
    let verified = run_blocking(move || {
        blocking_state.app_update_cache.load_verified(
            &blocking_state.app_update_metadata_path,
            &blocking_state.app_update_apk_path,
        )
    })
    .await?;
    Ok(Json(verified.metadata))
}

/// Authenticated release APK download for already-joined family devices.
/// Bytes are served only when the on-disk APK sha256 matches deploy metadata.
///
/// HTTP route entrypoint — `pub(crate)` for crate-root domain-path assembly.
pub(crate) async fn get_app_update_apk(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let _principal = authenticate(&state, &headers).await?;
    let blocking_state = state.clone();
    let verified = run_blocking(move || {
        blocking_state.app_update_cache.load_verified(
            &blocking_state.app_update_metadata_path,
            &blocking_state.app_update_apk_path,
        )
    })
    .await?;
    let mut response = Response::new(Body::from(verified.bytes));
    response.headers_mut().insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/vnd.android.package-archive"),
    );
    Ok(response)
}

#[derive(Clone)]
pub(crate) struct VerifiedAppUpdate {
    pub(crate) metadata: Value,
    pub(crate) bytes: Bytes,
    pub(crate) min_supported_version_code: u64,
    pub(crate) version_code: u64,
}

impl VerifiedAppUpdate {
    fn floor(&self) -> VerifiedChannelFloor {
        VerifiedChannelFloor {
            min_supported_version_code: self.min_supported_version_code,
            version_code: self.version_code,
        }
    }
}

fn load_verified_app_update_from_metadata(
    metadata: Value,
    apk_path: &Path,
) -> Result<VerifiedAppUpdate, ApiError> {
    let expected_sha256 = metadata
        .get("sha256")
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::internal("App update metadata is missing sha256"))?;
    let min_supported_version_code = metadata
        .get("min_supported_version_code")
        .and_then(Value::as_u64)
        .ok_or_else(|| {
            ApiError::internal("App update metadata is missing min_supported_version_code")
        })?;
    let version_code = metadata
        .get("version_code")
        .and_then(Value::as_u64)
        .ok_or_else(|| ApiError::internal("App update metadata is missing version_code"))?;
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
        // Logged once per stamp pair via negative cache (caller stores Unverified).
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
    Ok(VerifiedAppUpdate {
        metadata,
        bytes: Bytes::from(bytes),
        min_supported_version_code,
        version_code,
    })
}

fn detail_arc(detail: &Value) -> Arc<str> {
    match detail {
        Value::String(s) => Arc::from(s.as_str()),
        other => Arc::from(other.to_string()),
    }
}

fn metadata_stamp(path: &Path) -> Result<FileStamp, ApiError> {
    let metadata = match fs::metadata(path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::not_found("App update metadata is not available"));
        }
        Err(error) => {
            tracing::error!(path = %path.display(), %error, "failed to stat app update metadata");
            return Err(ApiError::internal("Failed to read app update metadata"));
        }
    };
    let modified = metadata.modified().map_err(|error| {
        tracing::error!(path = %path.display(), %error, "failed to read app update metadata mtime");
        ApiError::internal("Failed to read app update metadata")
    })?;
    Ok(FileStamp {
        modified,
        len: metadata.len(),
    })
}

fn apk_stamp(path: &Path) -> Result<FileStamp, ApiError> {
    let metadata = match fs::metadata(path) {
        Ok(value) => value,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Err(ApiError::not_found("App update package is not available"));
        }
        Err(error) => {
            tracing::error!(path = %path.display(), %error, "failed to stat app update package");
            return Err(ApiError::internal("Failed to read app update package"));
        }
    };
    let modified = metadata.modified().map_err(|error| {
        tracing::error!(path = %path.display(), %error, "failed to read app update package mtime");
        ApiError::internal("Failed to read app update package")
    })?;
    Ok(FileStamp {
        modified,
        len: metadata.len(),
    })
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
    use std::time::{Duration, UNIX_EPOCH};

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

    #[test]
    fn negative_release_cache_skips_rehash_for_same_stamp_pair() {
        let dir = tempfile::tempdir().unwrap();
        let meta_path = dir.path().join("app-update.json");
        let apk_path = dir.path().join("app-release.apk");
        let apk_bytes = b"negative-cache-apk-bytes";
        // Wrong sha so verify fails.
        let metadata = json!({
            "package_name": "com.lezi.babylog",
            "version_code": 9,
            "version_name": "0.4.0",
            "min_supported_version_code": 8,
            "sha256": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        });
        fs::write(&meta_path, metadata.to_string()).unwrap();
        fs::write(&apk_path, apk_bytes).unwrap();

        let cache = AppUpdateCache::default();
        let first = match cache.load_verified(&meta_path, &apk_path) {
            Ok(_) => panic!("hash mismatch must fail verify"),
            Err(error) => error,
        };
        assert_eq!(first.status, StatusCode::INTERNAL_SERVER_ERROR);

        // Second call must hit negative cache (same stamps) without needing a
        // file change; still Err with the same integrity detail.
        let second = match cache.load_verified(&meta_path, &apk_path) {
            Ok(_) => panic!("cached unverified must still fail"),
            Err(error) => error,
        };
        assert_eq!(second.status, StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            second.detail,
            json!("App update package integrity check failed")
        );

        // Gate fail-open with no prior verified floor.
        assert_eq!(cache.min_supported_if_verified(&meta_path, &apk_path), None);
    }

    #[test]
    fn last_good_floor_retained_across_integrity_miss() {
        let dir = tempfile::tempdir().unwrap();
        let meta_path = dir.path().join("app-update.json");
        let apk_path = dir.path().join("app-release.apk");
        let good_apk = b"good-verified-apk-v1";
        let good_meta = json!({
            "package_name": "com.lezi.babylog",
            "version_code": 9,
            "version_name": "0.4.0",
            "min_supported_version_code": 8,
            "sha256": hex::encode(Sha256::digest(good_apk)),
        });
        fs::write(&meta_path, good_meta.to_string()).unwrap();
        fs::write(&apk_path, good_apk).unwrap();

        let cache = AppUpdateCache::default();
        let verified = cache.load_verified(&meta_path, &apk_path).unwrap();
        assert_eq!(verified.min_supported_version_code, 8);
        assert_eq!(
            cache.min_supported_if_verified(&meta_path, &apk_path),
            Some(8)
        );

        // Mid-promote-ish: replace APK with bytes that do not match still-old metadata.
        // Length change ensures stamp differs on coarse mtime filesystems.
        let bad_apk = b"bad-apk-bytes-different-length-xx";
        fs::write(&apk_path, bad_apk).unwrap();
        // Bump mtime if needed so stamps always change even on same-length FS quirks.
        filetime_touch(&apk_path);

        assert!(cache.load_verified(&meta_path, &apk_path).is_err());
        // Retain last-known-good floor rather than fail-open to zero.
        assert_eq!(
            cache.min_supported_if_verified(&meta_path, &apk_path),
            Some(8)
        );
    }

    fn filetime_touch(path: &Path) {
        // Prefer setting mtime into the future so stamps differ even when the
        // filesystem has 1s resolution and the write is in the same second.
        let later = UNIX_EPOCH
            + Duration::from_secs(
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs()
                    + 5,
            );
        let _ = fs::File::options()
            .write(true)
            .open(path)
            .and_then(|f| f.set_modified(later));
    }
}
