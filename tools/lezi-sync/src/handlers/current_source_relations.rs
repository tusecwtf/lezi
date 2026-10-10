use std::collections::BTreeSet;
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::rejection::BytesRejection;
use axum::extract::State;
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

use crate::store::current_source_relations::CurrentSourceRelationsError;
use crate::{authenticate, require_supported_client, run_blocking, ApiError, AppState};

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct CurrentSourceRelationsRequest {
    protocol_version: u16,
    family_id: String,
    generation: String,
    record_client_uuids: Vec<String>,
}

pub(crate) async fn current_source_relations(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Bytes, BytesRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let body = body.map_err(|_| limit_error())?;
    if body.len() > 64 * 1024 {
        return Err(limit_error());
    }
    let invalid = || {
        ApiError::unprocessable("Invalid current source-relation scope")
            .with_code("invalid_source_relation_scope")
    };
    let request: CurrentSourceRelationsRequest =
        serde_json::from_slice(&body).map_err(|_| invalid())?;
    if request.protocol_version != 1 {
        return Err(
            ApiError::unprocessable("Unsupported source-relation protocol")
                .with_code("source_relation_protocol_unsupported"),
        );
    }
    if request.family_id.is_empty()
        || request.family_id.len() > 128
        || request.generation.is_empty()
        || request.generation.len() > 128
        || request.record_client_uuids.is_empty()
        || request.record_client_uuids.len() > 64
    {
        return Err(invalid());
    }
    let mut requested = BTreeSet::new();
    for uuid in request.record_client_uuids {
        if !Uuid::parse_str(&uuid).is_ok_and(|parsed| parsed.hyphenated().to_string() == uuid)
            || !requested.insert(uuid)
        {
            return Err(invalid());
        }
    }
    if request.family_id != principal.family_id {
        return Err(ApiError::forbidden("Source-relation family mismatch")
            .with_code("source_relation_family_mismatch"));
    }
    if request.generation != state.generation {
        return Err(ApiError::conflict("Source-relation generation mismatch")
            .with_code("generation_mismatch"));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let current = authenticate(&state, &headers).await?;
    if current.family_id != principal.family_id
        || current.membership_id != principal.membership_id
        || current.device_id != principal.device_id
        || current.role != principal.role
    {
        return Err(ApiError::unauthorized());
    }
    let blocking_state = state.clone();
    let response = run_blocking(move || {
        blocking_state
            .store
            .current_source_relations(&principal.family_id, &blocking_state.generation, &requested)
            .map_err(|error| match error {
                CurrentSourceRelationsError::Store(error) => ApiError::from(error),
                CurrentSourceRelationsError::LimitExceeded => limit_error(),
                CurrentSourceRelationsError::InvalidProjection => ApiError::new(
                    StatusCode::CONFLICT,
                    "Current source-relation projection is invalid",
                )
                .with_code("source_relation_projection_invalid"),
            })
    })
    .await?;
    Ok(Json(response))
}
fn limit_error() -> ApiError {
    ApiError::payload_too_large("Current source-relation projection exceeds its bounded scope")
        .with_code("source_relation_projection_limit_exceeded")
}
