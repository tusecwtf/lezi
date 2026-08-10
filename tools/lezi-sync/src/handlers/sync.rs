use std::collections::BTreeSet;
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::rejection::{JsonRejection, QueryRejection};
use axum::extract::{Path, Query, State};
use axum::http::HeaderMap;
use axum::Json;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use super::media::media_entity_is_pullable;
use crate::model::{
    validate_bundle_media_for_root, Entity, EntityValidationContext, RawEntity,
    MAX_BUNDLE_MEDIA_ENTITIES,
};
use crate::store::{
    CausalMediaItem, CausalMutation, ConflictDetailPage, ConflictDetailPageRequest,
    ConflictResolutionChoice, PullPage, PulledEntity, ReconcileResult, ReconcileUnit,
    ResolveConflictInput, StoreError,
};
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

// --- Causal protocol (wire §5–§8) -------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct CausalBatchRequest {
    /// Optional generation stamp (wire authority proof). When present, must match
    /// server generation; injected by some clients/helpers for CAS safety.
    #[serde(default)]
    generation: Option<String>,
    units: Vec<RawCausalMutation>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct RawCausalMutation {
    mutation_id: String,
    #[serde(default)]
    base_version: Option<String>,
    entity_type: String,
    client_uuid: String,
    root: Map<String, Value>,
    #[serde(default)]
    media: Vec<CausalMediaItem>,
    deleted: bool,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ResolveRequest {
    snapshot_token: String,
    resolution_mutation_id: String,
    choices: Vec<ConflictResolutionChoice>,
}

const MAX_RESOLVE_BODY_BYTES: usize = 128 * 1024;

fn terminal_resolve_rejection(mutation_id: Option<&str>, code: &str) -> Json<Value> {
    let mut value = json!({
        "status": "rejected",
        "error": { "code": code, "retryable": false },
    });
    if let Some(mutation_id) = mutation_id {
        value
            .as_object_mut()
            .expect("terminal envelope is an object")
            .insert(
                "resolution_mutation_id".to_owned(),
                Value::String(mutation_id.to_owned()),
            );
    }
    Json(value)
}

fn classify_resolve_request(raw: &[u8]) -> Result<ResolveRequest, Json<Value>> {
    if raw.len() > MAX_RESOLVE_BODY_BYTES {
        return Err(terminal_resolve_rejection(None, "wrong_type"));
    }
    let value: Value =
        serde_json::from_slice(raw).map_err(|_| terminal_resolve_rejection(None, "wrong_type"))?;
    let Some(object) = value.as_object() else {
        return Err(terminal_resolve_rejection(None, "wrong_type"));
    };
    let mutation_id = object
        .get("resolution_mutation_id")
        .and_then(Value::as_str)
        .map(str::to_owned);
    let allowed = ["snapshot_token", "resolution_mutation_id", "choices"];
    if object.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err(terminal_resolve_rejection(
            mutation_id.as_deref(),
            "unknown_field",
        ));
    }
    if allowed.iter().any(|key| !object.contains_key(*key)) {
        return Err(terminal_resolve_rejection(
            mutation_id.as_deref(),
            "missing_field",
        ));
    }
    if !object["snapshot_token"].is_string()
        || !object["resolution_mutation_id"].is_string()
        || !object["choices"].is_array()
    {
        return Err(terminal_resolve_rejection(
            mutation_id.as_deref(),
            "wrong_type",
        ));
    }
    for choice in object["choices"].as_array().expect("checked array") {
        let Some(choice) = choice.as_object() else {
            return Err(terminal_resolve_rejection(
                mutation_id.as_deref(),
                "wrong_type",
            ));
        };
        let choice_allowed = ["path", "choice_id"];
        if choice
            .keys()
            .any(|key| !choice_allowed.contains(&key.as_str()))
        {
            return Err(terminal_resolve_rejection(
                mutation_id.as_deref(),
                "unknown_field",
            ));
        }
        if choice_allowed.iter().any(|key| !choice.contains_key(*key)) {
            return Err(terminal_resolve_rejection(
                mutation_id.as_deref(),
                "missing_field",
            ));
        }
        if !choice["path"].is_string() || !choice["choice_id"].is_string() {
            return Err(terminal_resolve_rejection(
                mutation_id.as_deref(),
                "wrong_type",
            ));
        }
    }
    serde_json::from_value(value)
        .map_err(|_| terminal_resolve_rejection(mutation_id.as_deref(), "wrong_type"))
}

fn parse_causal_units(request: CausalBatchRequest) -> Result<Vec<CausalMutation>, ApiError> {
    let mut units = Vec::with_capacity(request.units.len());
    for raw in request.units {
        units.push(CausalMutation {
            mutation_id: raw.mutation_id,
            base_version: raw.base_version,
            entity_type: raw.entity_type,
            client_uuid: raw.client_uuid,
            root: raw.root,
            media: raw.media,
            deleted: raw.deleted,
        });
    }
    Ok(units)
}

fn causal_unit_json(result: crate::store::CausalUnitResult, generation: &str) -> Value {
    let mut value = serde_json::to_value(&result).unwrap_or_else(|_| json!({}));
    if let Some(obj) = value.as_object_mut() {
        obj.insert(
            "generation".to_owned(),
            Value::String(generation.to_owned()),
        );
    }
    value
}

pub(crate) async fn causal_reconcile(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<CausalBatchRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let rate_scope = format!(
        "causal_reconcile:{}:{}:{}",
        principal.family_id, principal.membership_id, principal.device_id,
    );
    if !state
        .reconcile_limiter
        .check_and_record(&rate_scope, state.now())
    {
        return Err(ApiError::too_many_requests(
            "Too many causal reconcile attempts; try again later",
        ));
    }
    let request = json_body(body)?;
    if let Some(gen) = request.generation.as_deref() {
        if gen != state.generation {
            return Err(ApiError::conflict_value(
                state
                    .recovery_detail(&principal.family_id, "generation_changed")
                    .await?,
            ));
        }
    }
    let units = parse_causal_units(request)?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let generation = state.generation.clone();
    let result = run_blocking(move || {
        blocking_state
            .store
            .causal_reconcile(&principal, units, blocking_state.now())
            .map_err(|error| match error {
                StoreError::InvalidReconcileBatch => {
                    ApiError::unprocessable("causal reconcile batch is invalid")
                }
                other => other.into(),
            })
    })
    .await?;
    Ok(Json(json!({
        "generation": generation,
        "cursor": result.cursor,
        "results": result.results
            .into_iter()
            .map(|unit| causal_unit_json(unit, &generation))
            .collect::<Vec<_>>(),
    })))
}

pub(crate) async fn causal_commit(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<CausalBatchRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let request = json_body(body)?;
    if let Some(gen) = request.generation.as_deref() {
        if gen != state.generation {
            return Err(ApiError::conflict_value(
                state
                    .recovery_detail(&principal.family_id, "generation_changed")
                    .await?,
            ));
        }
    }
    let units = parse_causal_units(request)?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let generation = state.generation.clone();
    let result = run_blocking(move || {
        // Causal commit preserves each fact; duplicate grouping is an explicit relation.
        blocking_state
            .store
            .causal_commit(&principal, units, blocking_state.now())
            .map_err(|error| match error {
                StoreError::InvalidReconcileBatch => {
                    ApiError::unprocessable("causal commit batch is invalid")
                }
                StoreError::CausalCommitSaturated(saturation) => {
                    ApiError::causal_commit_saturated(saturation)
                }
                StoreError::ForbiddenBaby
                | StoreError::ForbiddenRecord
                | StoreError::ForbiddenCarePlan
                | StoreError::ForbiddenCustomItem => ApiError::unprocessable(error.to_string()),
                other => other.into(),
            })
    })
    .await?;
    Ok(Json(json!({
        "generation": generation,
        "cursor": result.cursor,
        "results": result.results
            .into_iter()
            .map(|unit| causal_unit_json(unit, &generation))
            .collect::<Vec<_>>(),
    })))
}

pub(crate) async fn conflict_detail(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(conflict_id): Path<String>,
    query: Result<Query<ConflictDetailQuery>, QueryRejection>,
) -> Result<Json<ConflictDetailPage>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let query = query
        .map(|Query(value)| value)
        .map_err(|error| ApiError::unprocessable(error.body_text()))?;
    let page_request = match (query.snapshot_token, query.continuation) {
        (None, None) => ConflictDetailPageRequest::First,
        (Some(token), None) => ConflictDetailPageRequest::SnapshotToken(token),
        (Some(snapshot_token), Some(continuation)) => ConflictDetailPageRequest::Continuation {
            snapshot_token,
            continuation,
        },
        _ => {
            return Err(ApiError::unprocessable("snapshot token is invalid")
                .with_code("invalid_snapshot_token"))
        }
    };
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let detail = run_blocking(move || {
        blocking_state
            .store
            .conflict_detail_page(&principal, &conflict_id, page_request, blocking_state.now())
            .map_err(|error| match error {
                StoreError::ConflictNotFound => ApiError::not_found("conflict not found"),
                StoreError::InvalidSnapshotToken => {
                    ApiError::unprocessable("snapshot token is invalid")
                        .with_code("invalid_snapshot_token")
                }
                StoreError::SnapshotExpired => {
                    ApiError::gone("snapshot receipt expired").with_code("snapshot_expired")
                }
                StoreError::SnapshotStale => {
                    ApiError::conflict("conflict heads changed").with_code("snapshot_stale")
                }
                StoreError::ConflictSnapshotPageTooLarge => {
                    ApiError::payload_too_large("conflict detail exceeds the response budget")
                }
                other => other.into(),
            })
    })
    .await?;
    Ok(Json(detail))
}

#[derive(Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ConflictDetailQuery {
    snapshot_token: Option<String>,
    continuation: Option<String>,
}

pub(crate) async fn resolve_conflict(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(conflict_id): Path<String>,
    body: Bytes,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let request = match classify_resolve_request(&body) {
        Ok(request) => request,
        Err(rejection) => return Ok(rejection),
    };
    let input = ResolveConflictInput {
        snapshot_token: request.snapshot_token,
        resolution_mutation_id: request.resolution_mutation_id,
        choices: request.choices,
    };
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let result = run_blocking(move || {
        blocking_state
            .store
            .resolve_conflict(&principal, &conflict_id, input, blocking_state.now())
            .map_err(|error| match error {
                StoreError::ConflictNotFound => ApiError::not_found("conflict not found"),
                other => other.into(),
            })
    })
    .await?;
    Ok(Json(serde_json::to_value(result).map_err(|_| {
        ApiError::internal("failed to serialize resolve result")
    })?))
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

// --- Source relations (wire §12) --------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct DeclareSourceRelationRequest {
    mutation_id: String,
    record_client_uuid: String,
    equivalent_to_client_uuid: String,
    expected_record_version: String,
    expected_other_version: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct ResolveSourceRelationGroupRequest {
    mutation_id: String,
    member_client_uuids: Vec<String>,
    display_client_uuid: String,
    expected_versions: Map<String, Value>,
}

/// HTTP `POST /v1/source-relations/declare` — author equivalence (wire §12.1).
pub(crate) async fn declare_source_relation(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<DeclareSourceRelationRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let request = json_body(body)?;
    let input = crate::store::DeclareSourceRelationInput {
        mutation_id: request.mutation_id,
        record_client_uuid: request.record_client_uuid,
        equivalent_to_client_uuid: request.equivalent_to_client_uuid,
        expected_record_version: request.expected_record_version,
        expected_other_version: request.expected_other_version,
    };
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let result = run_blocking(move || {
        blocking_state
            .store
            .declare_source_relation(&principal, input, blocking_state.now())
            .map_err(map_source_relation_error)
    })
    .await?;
    Ok(Json(serde_json::to_value(result).map_err(|_| {
        ApiError::internal("failed to serialize source relation declare result")
    })?))
}

/// HTTP `POST /v1/source-relations/resolve-group` — Owner full-group resolve (wire §12.2).
pub(crate) async fn resolve_source_relation_group(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<ResolveSourceRelationGroupRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let request = json_body(body)?;
    let mut expected_versions = std::collections::BTreeMap::new();
    for (uuid, value) in request.expected_versions {
        let version = value.as_str().ok_or_else(|| {
            ApiError::unprocessable("expected_versions values must be version_id strings")
        })?;
        expected_versions.insert(uuid, version.to_owned());
    }
    let input = crate::store::ResolveSourceRelationGroupInput {
        mutation_id: request.mutation_id,
        member_client_uuids: request.member_client_uuids,
        display_client_uuid: request.display_client_uuid,
        expected_versions,
    };
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let result = run_blocking(move || {
        blocking_state
            .store
            .resolve_source_relation_group(&principal, input, blocking_state.now())
            .map_err(map_source_relation_error)
    })
    .await?;
    Ok(Json(serde_json::to_value(result).map_err(|_| {
        ApiError::internal("failed to serialize source relation resolve result")
    })?))
}

fn map_source_relation_error(error: StoreError) -> ApiError {
    match error {
        StoreError::ForbiddenRecord
        | StoreError::ForbiddenBaby
        | StoreError::ForbiddenCarePlan
        | StoreError::ForbiddenCustomItem => ApiError::unprocessable(error.to_string()),
        StoreError::InvalidSourceRelationRequest(message) => ApiError::unprocessable(message),
        other => other.into(),
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

#[derive(Serialize)]
struct PullResponse<E> {
    entities: E,
    cursor: i64,
    generation: String,
    has_more: bool,
    family_name: Option<String>,
}

fn pull_response_value(page: PullPage, generation: &str) -> Result<Value, serde_json::Error> {
    serde_json::to_value(PullResponse {
        entities: page.entities,
        cursor: page.cursor,
        generation: generation.to_owned(),
        has_more: page.has_more,
        family_name: page.family_name,
    })
}

fn pull_response_size(
    serialized_entity_bytes: usize,
    entity_count: usize,
    current: i64,
    family_name: &Option<String>,
    generation: &str,
) -> Result<usize, serde_json::Error> {
    let framing = serde_json::to_vec(&PullResponse {
        entities: Vec::<PulledEntity>::new(),
        cursor: current,
        generation: generation.to_owned(),
        // `false` is one byte longer than `true`, so it safely budgets either.
        has_more: false,
        family_name: family_name.clone(),
    })?
    .len();
    Ok(framing
        .saturating_add(serialized_entity_bytes)
        .saturating_add(entity_count.saturating_sub(1)))
}

fn media_owner_key(entity: &PulledEntity) -> Option<(&'static str, &str)> {
    if entity.entity_type != "media" || entity.deleted_at.is_some() {
        return None;
    }
    crate::store::media_association_owner(&entity.payload)
        .ok()
        .flatten()
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
    let page = run_blocking(move || {
        let generation = blocking_state.generation.clone();
        let mut page = match blocking_state.store.pull_with_final_envelope_size(
            &family_id,
            cursor,
            |serialized_entity_bytes, entity_count, current, family_name| {
                pull_response_size(
                    serialized_entity_bytes,
                    entity_count,
                    current,
                    family_name,
                    &generation,
                )
                .map_err(StoreError::from)
            },
        ) {
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
        // An owner root and its media are one publication group. Until every live media
        // member has durable public bytes, omit both the incomplete member and its owner.
        // Finalization advances their revisions so a later pull cannot strand the group
        // behind a cursor observed while filesystem promotion was pending.
        let media_ids = page
            .entities
            .iter()
            .filter(|entity| entity.entity_type == "media" && entity.deleted_at.is_none())
            .map(|entity| entity.client_uuid.clone())
            .collect::<BTreeSet<_>>();
        let published_media = blocking_state
            .store
            .published_media(&family_id, &media_ids)?;
        let pullable_media = page
            .entities
            .iter()
            .filter(|entity| entity.entity_type == "media" && entity.deleted_at.is_none())
            .filter(|entity| {
                media_entity_is_pullable(
                    blocking_state.as_ref(),
                    &family_id,
                    entity,
                    &published_media,
                )
            })
            .map(|entity| entity.client_uuid.clone())
            .collect::<BTreeSet<_>>();
        let blocked_owners = page
            .entities
            .iter()
            .filter(|entity| {
                entity.entity_type == "media"
                    && entity.deleted_at.is_none()
                    && !pullable_media.contains(&entity.client_uuid)
            })
            .filter_map(media_owner_key)
            .map(|(entity_type, client_uuid)| (entity_type.to_owned(), client_uuid.to_owned()))
            .collect::<BTreeSet<_>>();
        page.entities.retain(|entity| {
            if entity.entity_type == "media" && entity.deleted_at.is_none() {
                return pullable_media.contains(&entity.client_uuid);
            }
            !blocked_owners.contains(&(entity.entity_type.clone(), entity.client_uuid.clone()))
        });
        Ok(page)
    })
    .await?;
    Ok(Json(pull_response_value(page, &state.generation).map_err(
        |_| ApiError::internal("failed to serialize pull response"),
    )?))
}
