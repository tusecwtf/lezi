use std::collections::BTreeSet;
use std::io::Write;
use std::sync::Arc;

use axum::body::{Body, Bytes};
use axum::extract::rejection::{JsonRejection, QueryRejection};
use axum::extract::{Path, Query, State};
use axum::http::header::{ACCEPT_ENCODING, CONTENT_ENCODING, CONTENT_TYPE, VARY};
use axum::http::{HeaderMap, HeaderValue};
use axum::response::Response;
use axum::Json;
use flate2::write::GzEncoder;
use flate2::Compression;
use serde::de::{self, MapAccess, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::{json, Map, Value};

use super::media::media_entity_is_pullable;
use crate::model::{
    validate_bundle_media_for_root, Entity, EntityValidationContext, RawEntity,
    MAX_BUNDLE_MEDIA_ENTITIES,
};
use crate::readiness::is_ready;
use crate::store::{
    CausalMediaItem, CausalMutation, ConflictDetailPage, ConflictDetailPageRequest,
    ConflictResolutionChoice, PullPage, PulledEntity, ReconcileResult, ReconcileUnit,
    ResolveConflictInput, StoreError, MAX_CAUSAL_UNITS,
};
use crate::{
    authenticate, json_body, require_supported_client, run_blocking, ApiError, AppState,
    MAX_ENTITY_FUTURE_SKEW_MILLIS, PULL_MAX_PAGES, PULL_PAGE_ENTITY_LIMIT,
    PULL_PAGE_MAX_ENCODED_BYTES, PULL_PAGE_TARGET_BYTES, SETUP_PROTOCOL_VERSION,
    SOURCE_SYNC_HANDSHAKE_CAPABILITIES,
};

const MAX_RECONCILE_UNITS: usize = 64;
const MAX_RECONCILE_REQUEST_BYTES: usize = 1024 * 1024;
const MAX_CAUSAL_COMMIT_REQUEST_BYTES: usize = 1024 * 1024;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct AuthenticatedHandshakeRequest {
    protocol_version: u16,
    required_capabilities: Vec<String>,
}

fn terminal_handshake_rejection(code: &str) -> Value {
    json!({
        "status": "rejected",
        "error": { "code": code, "retryable": false },
    })
}

/// One authenticated sync preflight after endpoint trust and session setup.
/// Operational health/setup routes remain available but are not prerequisites
/// for ordinary client synchronization.
pub(crate) async fn authenticated_handshake(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<AuthenticatedHandshakeRequest>, JsonRejection>,
) -> Result<(axum::http::StatusCode, Json<Value>), ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let request = json_body(body)?;
    let advertised = SOURCE_SYNC_HANDSHAKE_CAPABILITIES
        .iter()
        .copied()
        .collect::<BTreeSet<_>>();
    let required = request
        .required_capabilities
        .iter()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    if request.protocol_version != SETUP_PROTOCOL_VERSION
        || required.len() != request.required_capabilities.len()
        || required != advertised
    {
        return Ok((
            axum::http::StatusCode::CONFLICT,
            Json(terminal_handshake_rejection("capability_mismatch")),
        ));
    }
    if !is_ready(&state).await {
        return Ok((
            axum::http::StatusCode::SERVICE_UNAVAILABLE,
            Json(terminal_handshake_rejection("not_ready")),
        ));
    }
    let store = state.store.clone();
    let family_id = principal.family_id.clone();
    let membership_id = principal.membership_id.clone();
    let viewer_is_owner = principal.role == "owner";
    let directory_generation = run_blocking(move || {
        Ok(store
            .family_directory_snapshot(&family_id, &membership_id, viewer_is_owner)?
            .generation)
    })
    .await?;
    Ok((
        axum::http::StatusCode::OK,
        Json(json!({
            "protocol_version": SETUP_PROTOCOL_VERSION,
            "server_version": state.version,
            "ready": true,
            "capabilities": SOURCE_SYNC_HANDSHAKE_CAPABILITIES,
            "principal": {
                "membership_id": principal.membership_id,
                "device_id": principal.device_id,
                "role": principal.role,
            },
            "directory_generation": directory_generation,
            "limits": {
                "pull_page_max_entities": PULL_PAGE_ENTITY_LIMIT,
                "pull_page_max_encoded_bytes": PULL_PAGE_MAX_ENCODED_BYTES,
                "pull_page_max_decoded_bytes": PULL_PAGE_TARGET_BYTES,
                "pull_max_pages": PULL_MAX_PAGES,
                "commit_batch_max_units": MAX_CAUSAL_UNITS,
                "media_max_bytes": state.max_media_bytes,
            },
            "compression": { "pull_response": ["gzip", "identity"] },
            "retry_hints": { "retry_after": true },
        })),
    ))
}

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

fn terminal_commit_rejection(mutation_id: Option<&str>, code: &str) -> Json<Value> {
    let mut value = json!({
        "status": "rejected",
        "error": { "code": code, "retryable": false },
    });
    if let Some(mutation_id) = mutation_id {
        value
            .as_object_mut()
            .expect("terminal envelope is an object")
            .insert(
                "mutation_id".to_owned(),
                Value::String(mutation_id.to_owned()),
            );
    }
    Json(value)
}

struct UniqueJsonMembers;

impl<'de> Deserialize<'de> for UniqueJsonMembers {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        deserializer.deserialize_any(UniqueJsonMembersVisitor)
    }
}

struct UniqueJsonMembersVisitor;

impl<'de> Visitor<'de> for UniqueJsonMembersVisitor {
    type Value = UniqueJsonMembers;

    fn expecting(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("JSON with unique object members")
    }

    fn visit_map<A>(self, mut map: A) -> Result<Self::Value, A::Error>
    where
        A: MapAccess<'de>,
    {
        let mut keys = BTreeSet::new();
        while let Some(key) = map.next_key::<String>()? {
            if !keys.insert(key) {
                return Err(de::Error::custom("duplicate JSON member"));
            }
            map.next_value::<UniqueJsonMembers>()?;
        }
        Ok(UniqueJsonMembers)
    }

    fn visit_seq<A>(self, mut sequence: A) -> Result<Self::Value, A::Error>
    where
        A: SeqAccess<'de>,
    {
        while sequence.next_element::<UniqueJsonMembers>()?.is_some() {}
        Ok(UniqueJsonMembers)
    }

    fn visit_bool<E>(self, _value: bool) -> Result<Self::Value, E> {
        Ok(UniqueJsonMembers)
    }

    fn visit_i64<E>(self, _value: i64) -> Result<Self::Value, E> {
        Ok(UniqueJsonMembers)
    }

    fn visit_u64<E>(self, _value: u64) -> Result<Self::Value, E> {
        Ok(UniqueJsonMembers)
    }

    fn visit_f64<E>(self, _value: f64) -> Result<Self::Value, E> {
        Ok(UniqueJsonMembers)
    }

    fn visit_str<E>(self, _value: &str) -> Result<Self::Value, E> {
        Ok(UniqueJsonMembers)
    }

    fn visit_none<E>(self) -> Result<Self::Value, E> {
        Ok(UniqueJsonMembers)
    }

    fn visit_unit<E>(self) -> Result<Self::Value, E> {
        Ok(UniqueJsonMembers)
    }
}

fn causal_commit_decode_error_code(error: &serde_json::Error) -> &'static str {
    let message = error.to_string();
    if message.starts_with("unknown field") {
        "unknown_field"
    } else if message.starts_with("missing field") {
        "missing_field"
    } else if message.starts_with("duplicate field") || message.starts_with("duplicate JSON member")
    {
        "non_canonical_value"
    } else {
        "wrong_type"
    }
}

fn classify_causal_commit_request(raw: &[u8]) -> Result<CausalBatchRequest, Json<Value>> {
    if raw.len() > MAX_CAUSAL_COMMIT_REQUEST_BYTES {
        return Err(terminal_commit_rejection(None, "wrong_type"));
    }
    serde_json::from_slice::<UniqueJsonMembers>(raw).map_err(|error| {
        terminal_commit_rejection(None, causal_commit_decode_error_code(&error))
    })?;
    let request = serde_json::from_slice::<CausalBatchRequest>(raw).map_err(|error| {
        terminal_commit_rejection(None, causal_commit_decode_error_code(&error))
    })?;
    if request.units.is_empty() {
        return Err(terminal_commit_rejection(None, "invalid_domain"));
    }
    Ok(request)
}

fn causal_unit_json(result: crate::store::CausalUnitResult, generation: &str) -> Value {
    let mut value = serde_json::to_value(&result).unwrap_or_else(|_| json!({}));
    if let Some(obj) = value.as_object_mut() {
        obj.remove("replay");
        obj.remove("stable_deleted_at");
        obj.insert(
            "generation".to_owned(),
            Value::String(generation.to_owned()),
        );
    }
    value
}

fn causal_commit_unit_json(result: crate::store::CausalUnitResult) -> Value {
    let mut unit = serde_json::Map::from_iter([
        ("status".to_owned(), Value::String(result.status)),
        ("mutation_id".to_owned(), Value::String(result.mutation_id)),
        (
            "request_hash".to_owned(),
            Value::String(result.request_hash),
        ),
        ("replay".to_owned(), Value::Bool(result.replay)),
        (
            "stable".to_owned(),
            json!({
                "version_id": result.stable_version_id,
                "root": result.stable_root,
                "media": result.stable_media,
                "deleted": result.stable_deleted_at.is_some(),
                "deleted_at": result.stable_deleted_at,
            }),
        ),
    ]);
    if let Some(branch_version_id) = result.branch_version_id {
        unit.insert(
            "branch_version_id".to_owned(),
            Value::String(branch_version_id),
        );
    }
    if let Some(conflict_id) = result.conflict_id {
        unit.insert("conflict_id".to_owned(), Value::String(conflict_id));
    }
    Value::Object(unit)
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
    body: Bytes,
) -> Result<(axum::http::StatusCode, Json<Value>), ApiError> {
    enum Dispatch {
        Commit(crate::store::DurableCausalCommit),
        Rejected {
            mutation_id: Option<String>,
            code: String,
        },
    }
    let principal = match authenticate(&state, &headers).await {
        Ok(principal) => principal,
        Err(error) if error.is_authentication_terminal() => {
            return Ok((
                axum::http::StatusCode::UNAUTHORIZED,
                terminal_commit_rejection(None, "unauthenticated"),
            ))
        }
        Err(error) => return Err(error),
    };
    match require_supported_client(&state, &headers).await {
        Ok(()) => {}
        Err(error) if error.is_client_update_terminal() => {
            return Ok((
                axum::http::StatusCode::CONFLICT,
                terminal_commit_rejection(None, "capability_mismatch"),
            ));
        }
        Err(error) => return Err(error),
    }
    if !is_ready(&state).await {
        return Ok((
            axum::http::StatusCode::SERVICE_UNAVAILABLE,
            terminal_commit_rejection(None, "not_ready"),
        ));
    }
    let request = match classify_causal_commit_request(&body) {
        Ok(request) => request,
        Err(rejection) => return Ok((axum::http::StatusCode::OK, rejection)),
    };
    if let Some(gen) = request.generation.as_deref() {
        if gen != state.generation {
            return Ok((
                axum::http::StatusCode::CONFLICT,
                terminal_commit_rejection(None, "capability_mismatch"),
            ));
        }
    }
    let units = parse_causal_units(request)?;
    let first_mutation_id = units.first().map(|unit| unit.mutation_id.clone());
    let family_lock = state.family_lock(&principal.family_id).await;
    let guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let generation = state.generation.clone();
    let family_id = principal.family_id.clone();
    let dispatch = run_blocking(move || {
        // Causal commit preserves each fact; duplicate grouping is an explicit relation.
        blocking_state
            .store
            .causal_commit_durable(&principal, units, blocking_state.now())
            .map(Dispatch::Commit)
            .or_else(|error| match error {
                StoreError::CausalCommitRejected { mutation_id, code } => Ok(Dispatch::Rejected {
                    mutation_id: Some(mutation_id),
                    code,
                }),
                StoreError::InvalidReconcileBatch => Ok(Dispatch::Rejected {
                    mutation_id: first_mutation_id,
                    code: "invalid_domain".to_owned(),
                }),
                StoreError::CausalCommitSaturated(saturation) => {
                    Err(ApiError::causal_commit_saturated(saturation))
                }
                StoreError::ForbiddenBaby
                | StoreError::ForbiddenRecord
                | StoreError::ForbiddenCarePlan
                | StoreError::ForbiddenCustomItem => {
                    Err(ApiError::unprocessable(error.to_string()))
                }
                other => Err(other.into()),
            })
    })
    .await?;
    let commit = match dispatch {
        Dispatch::Commit(commit) => commit,
        Dispatch::Rejected { mutation_id, code } => {
            return Ok((
                axum::http::StatusCode::OK,
                terminal_commit_rejection(mutation_id.as_deref(), &code),
            ));
        }
    };
    // The durable receipt/version transaction is complete. Exact-manifest
    // publication may hash/copy/fsync large objects and must not retain the
    // same-family commit mutex while the response waits for that repair.
    drop(guard);
    let requires_media_promotion = commit.requires_media_promotion();
    let blocking_state = state.clone();
    let blocking_hook = state.causal_media_commit_blocking_hook.clone();
    let result = run_blocking(move || {
        let result = blocking_state
            .store
            .publish_causal_commit_with_hook(commit, blocking_hook.as_deref())?;
        if requires_media_promotion {
            if let Some(hook) = &blocking_hook {
                hook("after_promotion");
            }
        }
        Ok(result)
    })
    .await?;
    let committed_any = result
        .results
        .iter()
        .any(|unit| matches!(unit.status.as_str(), "accepted" | "merged" | "branched"));
    if committed_any {
        state.schedule_causal_media_gc_for_family(family_id);
    }
    Ok((
        axum::http::StatusCode::OK,
        Json(json!({
            "generation": generation,
            "results": result.results
                .into_iter()
                .map(causal_commit_unit_json)
                .collect::<Vec<_>>(),
        })),
    ))
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
                StoreError::MissingRestoreBase => {
                    ApiError::conflict("tombstone restore base is missing")
                        .with_code("missing_restore_base")
                }
                StoreError::IncompleteRestoreBase => {
                    ApiError::conflict("tombstone restore base is incomplete")
                        .with_code("incomplete_restore_base")
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
    let guard = family_lock.lock().await;
    let family_id = principal.family_id.clone();
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
    drop(guard);
    let retention_state = state.clone();
    let retention = run_blocking(move || {
        retention_state
            .store
            .gc_conflict_metadata_for_family(&family_id, retention_state.now())
            .map(|_| ())
            .map_err(ApiError::from)
    })
    .await;
    if let Err(error) = retention {
        tracing::warn!(?error, "bounded conflict retention sweep failed");
    }
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
    // H16 adds the monotonic page proof additively while source capability remains active.
    #[serde(default)]
    page_index: usize,
}

#[derive(Serialize)]
struct PullResponse<E> {
    entities: E,
    cursor: i64,
    generation: String,
    page_index: usize,
    has_more: bool,
    family_name: Option<String>,
}

fn pull_response_value(
    page: PullPage,
    generation: &str,
    page_index: usize,
) -> Result<Value, serde_json::Error> {
    serde_json::to_value(PullResponse {
        entities: page.entities,
        cursor: page.cursor,
        generation: generation.to_owned(),
        page_index,
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
        // Budget the largest accepted page index for every response.
        page_index: PULL_MAX_PAGES - 1,
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

#[derive(Clone, Copy)]
enum PullResponseEncoding {
    Gzip,
    Identity,
}

fn negotiated_pull_response_encoding(
    headers: &HeaderMap,
) -> Result<PullResponseEncoding, ApiError> {
    let Some(value) = headers.get(ACCEPT_ENCODING) else {
        return Ok(PullResponseEncoding::Identity);
    };
    let raw = value
        .to_str()
        .map_err(|_| ApiError::unprocessable("pull Accept-Encoding must be valid header text"))?;
    let mut gzip_quality = None;
    let mut identity_quality = None;
    let mut wildcard_quality = None;
    for item in raw.split(',') {
        let mut parts = item.split(';');
        let token = parts.next().unwrap_or_default().trim().to_ascii_lowercase();
        if token.is_empty() {
            return Err(ApiError::unprocessable(
                "pull Accept-Encoding contains an empty coding",
            ));
        }
        let mut quality = 1.0_f32;
        for parameter in parts {
            let (name, raw_value) = parameter.trim().split_once('=').ok_or_else(|| {
                ApiError::unprocessable("pull Accept-Encoding parameter is invalid")
            })?;
            if !name.trim().eq_ignore_ascii_case("q") {
                return Err(ApiError::unprocessable(
                    "pull Accept-Encoding parameter is unsupported",
                ));
            }
            quality = raw_value
                .trim()
                .parse::<f32>()
                .map_err(|_| ApiError::unprocessable("pull Accept-Encoding quality is invalid"))?;
            if !quality.is_finite() || !(0.0..=1.0).contains(&quality) {
                return Err(ApiError::unprocessable(
                    "pull Accept-Encoding quality is invalid",
                ));
            }
        }
        match token.as_str() {
            "gzip" => gzip_quality = Some(quality),
            "identity" => identity_quality = Some(quality),
            "*" => wildcard_quality = Some(quality),
            _ => {}
        }
    }
    let gzip = gzip_quality.or(wildcard_quality).unwrap_or(0.0);
    let identity = identity_quality.or(wildcard_quality).unwrap_or(0.0);
    if gzip > 0.0 && gzip >= identity {
        Ok(PullResponseEncoding::Gzip)
    } else if identity > 0.0 {
        Ok(PullResponseEncoding::Identity)
    } else {
        Err(ApiError::unprocessable(
            "pull Accept-Encoding must allow gzip or identity",
        ))
    }
}

/// HTTP route entrypoint — see module visibility rule on `handlers`.
pub(crate) async fn pull_entities(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    query: Result<Query<PullQuery>, QueryRejection>,
) -> Result<Response, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let query = query
        .map(|Query(value)| value)
        .map_err(|error| ApiError::unprocessable(error.body_text()))?;
    if query.cursor < 0 {
        return Err(ApiError::unprocessable("cursor must be non-negative"));
    }
    if query.page_index >= PULL_MAX_PAGES {
        return Err(ApiError::unprocessable(
            "page_index exceeds negotiated pull budget",
        ));
    }
    if query.generation != state.generation {
        return Err(ApiError::conflict_value(
            state
                .recovery_detail(&principal.family_id, "generation_changed")
                .await?,
        ));
    }
    let response_encoding = negotiated_pull_response_encoding(&headers)?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let guard = family_lock.lock().await;
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
    drop(guard);
    let generation = state.generation.clone();
    let page_index = query.page_index;
    let (encoded, is_gzip) = run_blocking(move || {
        let value = pull_response_value(page, &generation, page_index)
            .map_err(|_| ApiError::internal("failed to serialize pull response"))?;
        let decoded = serde_json::to_vec(&value)
            .map_err(|_| ApiError::internal("failed to serialize pull response"))?;
        if decoded.len() > PULL_PAGE_TARGET_BYTES {
            return Err(ApiError::internal(
                "pull decoded page exceeded negotiated budget",
            ));
        }
        let (encoded, is_gzip) = match response_encoding {
            PullResponseEncoding::Gzip => {
                let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
                encoder
                    .write_all(&decoded)
                    .map_err(|_| ApiError::internal("failed to encode pull response"))?;
                (
                    encoder
                        .finish()
                        .map_err(|_| ApiError::internal("failed to encode pull response"))?,
                    true,
                )
            }
            PullResponseEncoding::Identity => (decoded, false),
        };
        if encoded.len() > PULL_PAGE_MAX_ENCODED_BYTES {
            return Err(ApiError::internal(
                "pull encoded page exceeded negotiated budget",
            ));
        }
        Ok((encoded, is_gzip))
    })
    .await?;
    let mut response = Response::new(Body::from(encoded));
    response.headers_mut().insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/json; charset=utf-8"),
    );
    response
        .headers_mut()
        .insert(VARY, HeaderValue::from_static("Accept-Encoding"));
    if is_gzip {
        response
            .headers_mut()
            .insert(CONTENT_ENCODING, HeaderValue::from_static("gzip"));
    }
    Ok(response)
}
