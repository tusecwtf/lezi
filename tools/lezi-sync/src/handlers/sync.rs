use std::collections::BTreeSet;
use std::sync::Arc;

use axum::extract::rejection::{JsonRejection, QueryRejection};
use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};

use super::media::media_entity_is_pullable;
use crate::model::{
    validate_bundle_media_for_root, Entity, EntityValidationContext, RawEntity,
    MAX_BUNDLE_MEDIA_ENTITIES,
};
use crate::store::{ReconcileResult, ReconcileUnit, StoreError};
use crate::{
    authenticate, json_body, require_supported_client, run_blocking, ApiError, AppState,
    MAX_ENTITY_FUTURE_SKEW_MILLIS,
};

const MAX_RECONCILE_UNITS: usize = 64;
const MAX_RECONCILE_REQUEST_BYTES: usize = 1024 * 1024;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ReconcileRequest {
    generation: String,
    units: Vec<RawReconcileUnit>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct RawReconcileUnit {
    root: RawEntity,
    #[serde(default)]
    media: Vec<RawEntity>,
    content_hash: String,
}

fn validate_reconcile_request(
    request: ReconcileRequest,
    max_media_bytes: usize,
) -> Result<(String, Vec<ReconcileUnit>), ApiError> {
    if request.generation.trim().is_empty() || request.generation.len() > 128 {
        return Err(ApiError::unprocessable("generation is invalid"));
    }
    if request.units.is_empty() || request.units.len() > MAX_RECONCILE_UNITS {
        return Err(ApiError::unprocessable(format!(
            "reconcile units must contain 1..={MAX_RECONCILE_UNITS} items"
        )));
    }
    let mut keys = BTreeSet::new();
    let mut units = Vec::with_capacity(request.units.len());
    for raw in request.units {
        if raw.content_hash.trim().is_empty() || raw.content_hash.len() > 128 {
            return Err(ApiError::unprocessable("content_hash is invalid"));
        }
        if raw.media.len() > MAX_BUNDLE_MEDIA_ENTITIES {
            return Err(ApiError::unprocessable(
                "reconcile media manifest is too large",
            ));
        }
        let root = raw
            .root
            .validate_as(max_media_bytes, EntityValidationContext::AtomicBundleRoot)?;
        if !keys.insert((root.entity_type.clone(), root.client_uuid.clone())) {
            return Err(ApiError::unprocessable(
                "reconcile unit keys must be unique",
            ));
        }
        let mut media = Vec::with_capacity(raw.media.len());
        let mut media_keys = BTreeSet::new();
        for value in raw.media {
            let entity =
                value.validate_as(max_media_bytes, EntityValidationContext::AtomicBundleMedia)?;
            if !media_keys.insert(entity.client_uuid.clone()) {
                return Err(ApiError::unprocessable(
                    "reconcile media client_uuid values must be unique",
                ));
            }
            media.push(entity);
        }
        validate_bundle_media_for_root(&root, &media)?;
        units.push(ReconcileUnit {
            root,
            media,
            content_hash: raw.content_hash,
        });
    }
    if serde_json::to_vec(&units.iter().map(|unit| {
        json!({"root": unit.root, "media": unit.media, "content_hash": unit.content_hash})
    }).collect::<Vec<_>>())
    .map_err(|_| ApiError::internal("failed to size reconcile request"))?
    .len() > MAX_RECONCILE_REQUEST_BYTES
    {
        return Err(ApiError::unprocessable("reconcile request is too large"));
    }
    Ok((request.generation, units))
}

fn reconcile_response_fits(response: &Value, max_bytes: usize) -> Result<bool, ApiError> {
    let response_size = serde_json::to_vec(&response)
        .map_err(|_| ApiError::internal("failed to size reconcile response"))?
        .len();
    Ok(response_size <= max_bytes)
}

fn reconcile_entity_value(entity: Entity) -> Value {
    json!({
        "type": entity.entity_type,
        "client_uuid": entity.client_uuid,
        "updated_at": entity.updated_at,
        "deleted_at": entity.deleted_at,
        "payload": entity.payload,
    })
}

fn reconcile_result_value(result: ReconcileResult) -> Value {
    json!({
        "entity_type": result.entity_type,
        "client_uuid": result.client_uuid,
        "request_content_hash": result.request_content_hash,
        "disposition": result.disposition,
        "reason": result.reason,
        "remote_content_hash": result.remote_content_hash,
        "remote_root": result.remote_root.map(reconcile_entity_value),
        "remote_media": result.remote_media
            .into_iter()
            .map(reconcile_entity_value)
            .collect::<Vec<_>>(),
    })
}

pub(crate) async fn reconcile_entities(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<ReconcileRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let rate_scope = format!(
        "reconcile:{}:{}:{}",
        principal.family_id, principal.membership_id, principal.device_id,
    );
    if !state
        .reconcile_limiter
        .check_and_record(&rate_scope, state.now())
    {
        return Err(ApiError::too_many_requests(
            "Too many authoritative reconcile attempts; try again later",
        ));
    }
    let (generation, units) = validate_reconcile_request(json_body(body)?, state.max_media_bytes)?;
    if generation != state.generation {
        return Err(ApiError::conflict_value(
            state
                .recovery_detail(&principal.family_id, "generation_changed")
                .await?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let family_id = principal.family_id.clone();
    let blocking_state = state.clone();
    let result = run_blocking(move || {
        let now = blocking_state.now();
        blocking_state
            .store
            .reconcile_units(
                &principal,
                units,
                now.saturating_mul(1_000)
                    .saturating_add(MAX_ENTITY_FUTURE_SKEW_MILLIS),
                now,
            )
            .map_err(|error| match error {
                StoreError::InvalidReconcileBatch => {
                    ApiError::unprocessable("authoritative reconcile batch is invalid")
                }
                StoreError::TimestampOutOfRange => {
                    ApiError::unprocessable("updated_at is outside the accepted server time window")
                }
                other => other.into(),
            })
    })
    .await?;
    let response = json!({
        "generation": state.generation,
        "cursor": result.cursor,
        "results": result.results
            .into_iter()
            .map(reconcile_result_value)
            .collect::<Vec<_>>(),
    });
    if !reconcile_response_fits(&response, state.max_reconcile_response_bytes)? {
        return Err(ApiError::conflict_value(
            state
                .recovery_detail(&family_id, "authority_response_too_large")
                .await?,
        ));
    }
    Ok(Json(response))
}

/// HTTP route entrypoint — `pub(crate)` so crate-root `build_apps` can bind via
/// domain path (`handlers::sync::…`). See `handlers` module docs for the rule.
pub(crate) async fn retired_ordinary_push() -> Result<Json<Value>, ApiError> {
    Err(ApiError::unprocessable(
        "ordinary push is retired; publish an atomic bundle",
    ))
}

#[cfg(test)]
mod reconcile_bounds_tests {
    use super::*;

    #[test]
    fn reconcile_response_serialization_is_bounded_at_the_byte_limit() {
        assert!(reconcile_response_fits(&json!({"ok": true}), 64).unwrap());
        assert!(!reconcile_response_fits(&json!({"payload": "x".repeat(65)}), 64).unwrap());
    }
}

/// Query DTO for [`pull_entities`]. Must be at least as visible as that handler
/// (`private_interfaces`); not a cross-module seam.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PullQuery {
    cursor: i64,
    generation: String,
}

/// HTTP route entrypoint — see module visibility rule on `handlers`.
pub(crate) async fn pull_entities(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    query: Result<Query<PullQuery>, QueryRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let query = query
        .map(|Query(value)| value)
        .map_err(|error| ApiError::unprocessable(error.body_text()))?;
    if query.cursor < 0 {
        return Err(ApiError::unprocessable("cursor must be non-negative"));
    }
    if query.generation != state.generation {
        return Err(ApiError::conflict_value(
            state
                .recovery_detail(&principal.family_id, "generation_changed")
                .await?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let family_id = principal.family_id.clone();
    let cursor = query.cursor;
    let mut page = run_blocking(move || {
        let mut page = match blocking_state.store.pull(&family_id, cursor) {
            Ok(result) => result,
            Err(StoreError::CursorAhead(server_cursor)) => {
                return Err(ApiError::conflict_value(json!({
                    "code": "cursor_ahead",
                    "action": "full_resync",
                    "reset_cursor": 0,
                    "server_cursor": server_cursor,
                    "server_generation": blocking_state.generation,
                })))
            }
            Err(error) => return Err(error.into()),
        };
        // Incomplete media (metadata without bytes) is omitted so clients can advance
        // the pull cursor without GET /media 404 loops. Successful PUT republishes.
        let media_ids = page
            .entities
            .iter()
            .filter(|entity| entity.entity_type == "media" && entity.deleted_at.is_none())
            .map(|entity| entity.client_uuid.clone())
            .collect::<BTreeSet<_>>();
        let published_media = blocking_state
            .store
            .published_media(&family_id, &media_ids)?;
        page.entities.retain(|entity| {
            media_entity_is_pullable(
                blocking_state.as_ref(),
                &family_id,
                entity,
                &published_media,
            )
        });
        Ok(page)
    })
    .await?;
    let entities = std::mem::take(&mut page.entities);
    Ok(Json(json!({
        "entities": entities,
        "cursor": page.cursor,
        "generation": state.generation,
        "has_more": page.has_more,
        "family_name": page.family_name,
    })))
}
