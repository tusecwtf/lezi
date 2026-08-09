//! Versioned Owner-device disaster restore for an empty server.
//!
//! The recovery credential is a deterministic high-entropy HMAC capability whose hash alone is
//! journaled. Root authentication occurs only at start and final commit. Until Store activation,
//! the staged manifest and media have no family row and are invisible to normal APIs.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::rejection::JsonRejection;
use axum::extract::{ConnectInfo, Path as AxumPath, State};
use axum::http::header::AUTHORIZATION;
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::model::{
    normalize_family_name, normalized_display_name_key, require_device_name, require_display_name,
    Entity, EntityValidationContext, RawEntity,
};
use crate::store::{DisasterRestoreIdentityInput, StoreError};
use crate::{
    constant_time_eq, derive_token, json_body, require_owner_root_password,
    require_supported_client, run_blocking, secure_directory, secure_file, sync_directory,
    write_private_file, ApiError, AppState,
};

const RESTORE_PROTOCOL_VERSION: u16 = 1;
const RESTORE_TTL_SECONDS: i64 = 24 * 60 * 60;
const ACCESS_TOKEN_TTL_SECONDS: i64 = 15 * 60;
const RESTORE_DIR: &str = "disaster-restore";
const RESTORE_CREDENTIAL_HASH_FILE: &str = "credential.sha256";

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct StartRestoreRequest {
    request_id: String,
    family_id: String,
    family_name: String,
    owner_display_name: String,
    device_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct RestoreManifestRequest {
    request_id: String,
    entities: Vec<RawEntity>,
    media: Vec<RestoreMediaSpec>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct RestoreMediaSpec {
    client_uuid: String,
    byte_size: usize,
    sha256: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct RestoreCommitRequest {
    request_id: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct RestoreJournal {
    protocol_version: u16,
    batch_id: String,
    start_request_id: String,
    family_id: String,
    family_name: String,
    owner_display_name: String,
    device_name: String,
    owner_membership_id: String,
    device_id: String,
    session_id: String,
    access_expires_at: i64,
    recovery_token_hash: String,
    created_at: i64,
    expires_at: i64,
    status: String,
    manifest_request_id: Option<String>,
    manifest_hash: Option<String>,
    commit_request_id: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
struct ValidatedRestoreManifest {
    request_id: String,
    entities: Vec<Entity>,
    media: Vec<RestoreMediaSpec>,
}

#[derive(Deserialize)]
struct RestoreCredentialProjection {
    recovery_token_hash: String,
}

pub(crate) async fn start(
    State(state): State<Arc<AppState>>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Result<Json<StartRestoreRequest>, JsonRejection>,
) -> Result<Response, ApiError> {
    // Same honest client version floor as authoritative sync when a verified channel exists.
    require_supported_client(&state, &headers).await?;
    let store = state.store.clone();
    if run_blocking(move || Ok(!store.family_ids()?.is_empty())).await? {
        return Err(ApiError::conflict(
            "Disaster restore requires an empty family server",
        ));
    }
    require_owner_root_password(&state, &headers, source)?;
    cleanup_expired_runtime(
        state.data_root.clone(),
        state.restore_locks.clone(),
        state.now(),
    )
    .await?;
    let request = json_body(body)?;
    validate_request_id(&request.request_id)?;
    let family_id = Uuid::parse_str(request.family_id.trim())
        .map_err(|_| ApiError::unprocessable("family_id must be a UUID"))?
        .to_string();
    let family_name = normalize_family_name(Some(&request.family_name))?
        .ok_or_else(|| ApiError::unprocessable("family_name is required"))?;
    let owner_display_name = require_display_name(Some(&request.owner_display_name))?;
    let device_name = require_device_name(&request.device_name)?;

    let provisioning_lock = state.family_lock(crate::PROVISIONING_LOCK_KEY).await;
    let _provisioning_guard = provisioning_lock.lock().await;
    let blocking_state = state.clone();
    run_blocking(move || {
        if !blocking_state.store.family_ids()?.is_empty() {
            return Err(ApiError::conflict(
                "Disaster restore requires an empty family server",
            ));
        }
        fs::create_dir_all(restore_root(&blocking_state.data_root))?;
        secure_directory(&restore_root(&blocking_state.data_root))?;
        if let Some(journal) =
            find_by_start_request(&blocking_state.data_root, &request.request_id)?
        {
            if journal.family_id != family_id
                || journal.family_name != family_name
                || journal.owner_display_name != owner_display_name
                || journal.device_name != device_name
            {
                return Err(ApiError::conflict(
                    "restore request_id conflicts with stored batch",
                ));
            }
            ensure_credential_envelope(&blocking_state.data_root, &journal)?;
            return Ok(start_response(&blocking_state, &journal, StatusCode::OK));
        }

        if has_active_batch(&blocking_state.data_root, blocking_state.now())? {
            return Err(ApiError::conflict(
                "Another disaster restore batch is already active",
            ));
        }
        let batch_id = Uuid::new_v4().to_string();
        let recovery_token = recovery_token(&blocking_state, &request.request_id, &batch_id);
        let batch_dir = batch_dir(&blocking_state.data_root, &batch_id)?;
        fs::create_dir_all(batch_dir.join("media"))?;
        secure_directory(&batch_dir)?;
        secure_directory(&batch_dir.join("media"))?;
        let journal = RestoreJournal {
            protocol_version: RESTORE_PROTOCOL_VERSION,
            batch_id,
            start_request_id: request.request_id,
            family_id,
            family_name,
            owner_display_name,
            device_name,
            owner_membership_id: Uuid::new_v4().to_string(),
            device_id: Uuid::new_v4().to_string(),
            session_id: Uuid::new_v4().to_string(),
            access_expires_at: blocking_state.now() + ACCESS_TOKEN_TTL_SECONDS,
            recovery_token_hash: crate::hash_secret(&recovery_token),
            created_at: blocking_state.now(),
            expires_at: blocking_state.now() + RESTORE_TTL_SECONDS,
            status: "started".to_owned(),
            manifest_request_id: None,
            manifest_hash: None,
            commit_request_id: None,
        };
        save_journal(&blocking_state.data_root, &journal)?;
        ensure_credential_envelope(&blocking_state.data_root, &journal)?;
        Ok(start_response(
            &blocking_state,
            &journal,
            StatusCode::CREATED,
        ))
    })
    .await
}

pub(crate) async fn put_manifest(
    State(state): State<Arc<AppState>>,
    AxumPath(batch_id): AxumPath<String>,
    headers: HeaderMap,
    body: Result<Json<RestoreManifestRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    require_supported_client(&state, &headers).await?;
    let batch_id = canonical_batch_id(&batch_id)?;
    let restore_locks = state.restore_locks.clone();
    let blocking_state = state.clone();
    restore_locks
        .run_serialized(batch_id.clone(), move || {
            let mut journal = load_authorized(&blocking_state, &batch_id, &headers)?;
            require_open(&journal)?;
            let request = json_body(body)?;
            validate_request_id(&request.request_id)?;
            let manifest = validate_manifest(request, blocking_state.max_media_bytes)?;
            let encoded = serde_json::to_vec(&manifest).map_err(restore_json_error)?;
            let hash = hex::encode(Sha256::digest(&encoded));
            if let Some(stored_hash) = journal.manifest_hash.as_deref() {
                if journal.manifest_request_id.as_deref() != Some(manifest.request_id.as_str())
                    || stored_hash != hash
                {
                    return Err(ApiError::conflict(
                        "manifest request_id or content conflicts with stored batch",
                    ));
                }
                return Ok(Json(batch_status(&blocking_state, &journal)?));
            }
            write_private_file(
                &manifest_path(&blocking_state.data_root, &batch_id)?,
                &encoded,
            )?;
            journal.status = "manifest_received".to_owned();
            journal.manifest_request_id = Some(manifest.request_id.clone());
            journal.manifest_hash = Some(hash);
            save_journal(&blocking_state.data_root, &journal)?;
            Ok(Json(batch_status(&blocking_state, &journal)?))
        })
        .await
}

pub(crate) async fn put_media(
    State(state): State<Arc<AppState>>,
    AxumPath((batch_id, client_uuid)): AxumPath<(String, String)>,
    headers: HeaderMap,
    bytes: Bytes,
) -> Result<Json<Value>, ApiError> {
    require_supported_client(&state, &headers).await?;
    let batch_id = canonical_batch_id(&batch_id)?;
    let restore_locks = state.restore_locks.clone();
    let blocking_state = state.clone();
    restore_locks
        .run_serialized(batch_id.clone(), move || {
            let journal = load_authorized(&blocking_state, &batch_id, &headers)?;
            require_open(&journal)?;
            let media_uuid = Uuid::parse_str(client_uuid.trim())
                .map_err(|_| ApiError::unprocessable("media client_uuid must be a UUID"))?
                .to_string();
            let manifest = load_verified_manifest(&blocking_state.data_root, &journal)?;
            let spec = manifest
                .media
                .iter()
                .find(|spec| spec.client_uuid == media_uuid)
                .ok_or_else(|| ApiError::not_found("media is not listed in restore manifest"))?;
            if bytes.len() != spec.byte_size || hex::encode(Sha256::digest(&bytes)) != spec.sha256 {
                return Err(ApiError::unprocessable(
                    "restore media size or sha256 does not match manifest",
                ));
            }
            let path = staged_media_path(&blocking_state.data_root, &batch_id, &media_uuid)?;
            write_private_file(&path, &bytes)?;
            Ok(Json(batch_status(&blocking_state, &journal)?))
        })
        .await
}

pub(crate) async fn status(
    State(state): State<Arc<AppState>>,
    AxumPath(batch_id): AxumPath<String>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    let batch_id = canonical_batch_id(&batch_id)?;
    let restore_locks = state.restore_locks.clone();
    let blocking_state = state.clone();
    restore_locks
        .run_serialized(batch_id.clone(), move || {
            let journal = load_authorized(&blocking_state, &batch_id, &headers)?;
            Ok(Json(batch_status(&blocking_state, &journal)?))
        })
        .await
}

pub(crate) async fn cancel(
    State(state): State<Arc<AppState>>,
    AxumPath(batch_id): AxumPath<String>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    let batch_id = canonical_batch_id(&batch_id)?;
    let restore_locks = state.restore_locks.clone();
    let blocking_state = state.clone();
    restore_locks
        .run_serialized(batch_id.clone(), move || {
            let mut journal = load_authorized(&blocking_state, &batch_id, &headers)?;
            if journal.status == "committed" {
                return Err(ApiError::conflict(
                    "committed restore batch cannot be cancelled",
                ));
            }
            journal.status = "cancelled".to_owned();
            save_journal(&blocking_state.data_root, &journal)?;
            let media_dir = batch_dir(&blocking_state.data_root, &batch_id)?.join("media");
            if media_dir.exists() {
                fs::remove_dir_all(&media_dir)?;
            }
            Ok(Json(batch_status(&blocking_state, &journal)?))
        })
        .await
}

pub(crate) async fn commit(
    State(state): State<Arc<AppState>>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    AxumPath(batch_id): AxumPath<String>,
    headers: HeaderMap,
    body: Result<Json<RestoreCommitRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    require_supported_client(&state, &headers).await?;
    let batch_id = canonical_batch_id(&batch_id)?;
    let restore_locks = state.restore_locks.clone();
    let blocking_state = state.clone();
    restore_locks
        .run_serialized(batch_id.clone(), move || {
            let mut journal = load_authorized(&blocking_state, &batch_id, &headers)?;
            require_owner_root_password(&blocking_state, &headers, source)?;
            let request = json_body(body)?;
            validate_request_id(&request.request_id)?;
            if journal.status == "committed" {
                if journal.commit_request_id.as_deref() != Some(request.request_id.as_str()) {
                    return Err(ApiError::conflict(
                        "commit request_id conflicts with stored batch",
                    ));
                }
                return Ok(Json(commit_response(&blocking_state, &journal)));
            }
            require_open(&journal)?;
            if journal
                .commit_request_id
                .as_deref()
                .is_some_and(|stored| stored != request.request_id)
            {
                return Err(ApiError::conflict(
                    "commit request_id conflicts with stored batch",
                ));
            }
            let manifest = load_verified_manifest(&blocking_state.data_root, &journal)?;
            ensure_all_media_ready(&blocking_state.data_root, &journal, &manifest)?;
            install_media_before_activation(&blocking_state, &journal, &manifest)?;

            // The batch may have been open for almost its full 24-hour upload window.
            // Persist the commit-time session expiry and idempotency key before SQLite
            // activation so a crash after activation can replay exactly the credential
            // tuple already stored in `device_sessions`.
            if journal.commit_request_id.is_none() {
                journal.access_expires_at = blocking_state.now() + ACCESS_TOKEN_TTL_SECONDS;
                journal.commit_request_id = Some(request.request_id.clone());
                save_journal(&blocking_state.data_root, &journal)?;
            }

            let access_token = restore_access_token(&blocking_state, &journal);
            let refresh_token = restore_refresh_token(&blocking_state, &journal);
            let result = blocking_state.store.activate_disaster_restore(
                DisasterRestoreIdentityInput {
                    now: blocking_state.now(),
                    family_id: &journal.family_id,
                    family_name: &journal.family_name,
                    owner_membership_id: &journal.owner_membership_id,
                    owner_display_name: &journal.owner_display_name,
                    owner_display_name_key: &normalized_display_name_key(
                        &journal.owner_display_name,
                    ),
                    device_id: &journal.device_id,
                    device_name: &journal.device_name,
                    session_id: &journal.session_id,
                    access_token: &access_token,
                    access_expires_at: journal.access_expires_at,
                    refresh_token: &refresh_token,
                    owner_root_fingerprint: blocking_state.owner_root_fingerprint.as_deref(),
                },
                manifest.entities,
            );
            match result {
                Ok(_) => {}
                Err(StoreError::FamilyAlreadyExists)
                    if blocking_state.store.family_ids()?
                        == BTreeSet::from([journal.family_id.clone()]) =>
                {
                    // SQLite activation committed before a lost response/journal write. All response
                    // credentials are deterministic and can be safely replayed.
                }
                Err(StoreError::FamilyAlreadyExists) => {
                    return Err(ApiError::conflict(
                        "Disaster restore requires an empty family server",
                    ));
                }
                Err(error) => return Err(error.into()),
            }
            journal.status = "committed".to_owned();
            save_journal(&blocking_state.data_root, &journal)?;
            Ok(Json(commit_response(&blocking_state, &journal)))
        })
        .await
}

pub(crate) fn has_active_batch(data_root: &Path, now: i64) -> Result<bool, ApiError> {
    let root = restore_root(data_root);
    if !root.exists() {
        return Ok(false);
    }
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        if let Ok(journal) = load_journal_path(&entry.path().join("journal.json")) {
            if journal.expires_at > now
                && !matches!(journal.status.as_str(), "cancelled" | "committed")
            {
                return Ok(true);
            }
        }
    }
    Ok(false)
}

/// Runs the durable restore-batch startup lifecycle and returns family media roots that belong
/// to an unexpired batch. A commit may have moved bytes into the final media tree immediately
/// before SQLite activation; preserving those roots makes the same idempotent commit resumable
/// after a process or power loss.
pub(crate) fn prepare_startup(data_root: &Path, now: i64) -> Result<BTreeSet<String>, ApiError> {
    cleanup_expired_and_incomplete(data_root, now)?;
    let root = restore_root(data_root);
    if !root.exists() {
        return Ok(BTreeSet::new());
    }
    let mut protected = BTreeSet::new();
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        if let Ok(journal) = load_journal_path(&entry.path().join("journal.json")) {
            if journal.expires_at > now
                && !matches!(journal.status.as_str(), "cancelled" | "expired")
            {
                protected.insert(journal.family_id);
            }
        }
    }
    Ok(protected)
}

fn validate_manifest(
    request: RestoreManifestRequest,
    max_media_bytes: usize,
) -> Result<ValidatedRestoreManifest, ApiError> {
    validate_request_id(&request.request_id)?;
    if request.entities.is_empty() {
        return Err(ApiError::unprocessable(
            "restore manifest entities must not be empty",
        ));
    }
    let mut entities = Vec::with_capacity(request.entities.len());
    let mut keys = BTreeSet::new();
    for raw in request.entities {
        if raw.deleted_at.is_some() {
            return Err(ApiError::unprocessable(
                "restore manifest must not contain tombstones",
            ));
        }
        let context = if raw.entity_type == "media" {
            EntityValidationContext::AtomicBundleMedia
        } else {
            EntityValidationContext::AtomicBundleRoot
        };
        let entity = raw.validate_as(max_media_bytes, context)?;
        if !keys.insert((entity.entity_type.clone(), entity.client_uuid.clone())) {
            return Err(ApiError::unprocessable(
                "restore entity keys must be unique",
            ));
        }
        entities.push(entity);
    }
    validate_restore_references(&entities, &keys)?;

    let mut media_specs = BTreeMap::new();
    for mut spec in request.media {
        spec.client_uuid = Uuid::parse_str(spec.client_uuid.trim())
            .map_err(|_| ApiError::unprocessable("media client_uuid must be a UUID"))?
            .to_string();
        if spec.byte_size == 0 || spec.byte_size > max_media_bytes {
            return Err(ApiError::unprocessable(
                "restore media byte_size is invalid",
            ));
        }
        spec.sha256 = spec.sha256.trim().to_ascii_lowercase();
        if spec.sha256.len() != 64 || !spec.sha256.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(ApiError::unprocessable(
                "restore media sha256 must be 64 hex characters",
            ));
        }
        if media_specs.insert(spec.client_uuid.clone(), spec).is_some() {
            return Err(ApiError::unprocessable(
                "restore media specs must be unique",
            ));
        }
    }
    let live_media = entities
        .iter()
        .filter(|entity| entity.entity_type == "media")
        .map(|entity| {
            let size = entity
                .payload
                .get("byte_size")
                .and_then(Value::as_u64)
                .unwrap_or(0);
            (entity.client_uuid.clone(), size as usize)
        })
        .collect::<BTreeMap<_, _>>();
    if live_media.len() != media_specs.len()
        || live_media.iter().any(|(uuid, size)| {
            media_specs
                .get(uuid)
                .is_none_or(|spec| spec.byte_size != *size)
        })
    {
        return Err(ApiError::unprocessable(
            "restore media specs must exactly match live media entities",
        ));
    }
    Ok(ValidatedRestoreManifest {
        request_id: request.request_id,
        entities,
        media: media_specs.into_values().collect(),
    })
}

fn validate_restore_references(
    entities: &[Entity],
    keys: &BTreeSet<(String, String)>,
) -> Result<(), ApiError> {
    for entity in entities {
        for (field, entity_type) in [
            ("baby_client_uuid", "baby"),
            ("record_client_uuid", "record"),
            ("care_plan_client_uuid", "care_plan"),
            ("custom_item_client_uuid", "custom_item"),
            ("avatar_media_uuid", "media"),
            ("fulfilled_record_client_uuid", "record"),
        ] {
            let Some(reference) = entity.payload.get(field).and_then(Value::as_str) else {
                continue;
            };
            if !keys.contains(&(entity_type.to_owned(), reference.to_owned())) {
                return Err(ApiError::unprocessable(format!(
                    "restore entity has unresolved {field}",
                )));
            }
        }
    }
    Ok(())
}

fn batch_status(state: &AppState, journal: &RestoreJournal) -> Result<Value, ApiError> {
    let status = if journal.status == "manifest_received" {
        let manifest = load_verified_manifest(&state.data_root, journal)?;
        if all_media_ready(&state.data_root, journal, &manifest)? {
            "ready_to_commit"
        } else {
            "manifest_received"
        }
    } else {
        journal.status.as_str()
    };
    Ok(json!({
        "protocol_version": RESTORE_PROTOCOL_VERSION,
        "batch_id": journal.batch_id,
        "status": status,
        "expires_at": journal.expires_at,
    }))
}

fn commit_response(state: &AppState, journal: &RestoreJournal) -> Value {
    json!({
        "protocol_version": RESTORE_PROTOCOL_VERSION,
        "batch_id": journal.batch_id,
        "status": "committed",
        "family_id": journal.family_id,
        "family_name": journal.family_name,
        "membership_id": journal.owner_membership_id,
        "device_id": journal.device_id,
        "session_id": journal.session_id,
        "role": "owner",
        "access_token": restore_access_token(state, journal),
        "access_expires_at": journal.access_expires_at,
        "refresh_token": restore_refresh_token(state, journal),
        "generation": state.generation,
    })
}

fn start_response(state: &AppState, journal: &RestoreJournal, status: StatusCode) -> Response {
    (
        status,
        Json(json!({
            "protocol_version": RESTORE_PROTOCOL_VERSION,
            "batch_id": journal.batch_id,
            "status": journal.status,
            "expires_at": journal.expires_at,
            "recovery_token": recovery_token(
                state,
                &journal.start_request_id,
                &journal.batch_id,
            ),
        })),
    )
        .into_response()
}

fn load_authorized(
    state: &AppState,
    batch_id: &str,
    headers: &HeaderMap,
) -> Result<RestoreJournal, ApiError> {
    authenticate_restore_batch(state, batch_id, headers)?;
    let mut journal = load_journal(&state.data_root, batch_id)?;
    if journal.expires_at <= state.now() && journal.status != "committed" {
        journal.status = "expired".to_owned();
        save_journal(&state.data_root, &journal)?;
        return Err(ApiError::gone("restore batch expired"));
    }
    if matches!(journal.status.as_str(), "cancelled" | "expired") {
        return Err(ApiError::gone("restore batch is no longer active"));
    }
    Ok(journal)
}

fn authenticate_restore_batch(
    state: &AppState,
    batch_id: &str,
    headers: &HeaderMap,
) -> Result<(), ApiError> {
    let provided_hash = crate::hash_secret(bearer(headers).unwrap_or(""));
    let journal_hash = load_journal_credential_hash(&state.data_root, batch_id);
    match fs::read(credential_hash_path(&state.data_root, batch_id)?) {
        Ok(bytes) => match decode_credential_hash(&bytes) {
            Ok(envelope_hash) => {
                if let Some(journal_hash) = journal_hash.as_deref() {
                    if !constant_time_eq(envelope_hash.as_bytes(), journal_hash.as_bytes()) {
                        if constant_time_eq(provided_hash.as_bytes(), journal_hash.as_bytes()) {
                            return Err(ApiError::internal(
                                "restore credential envelope does not match journal",
                            ));
                        }
                        return Err(ApiError::unauthorized_detail("Invalid restore credential"));
                    }
                }
                if !constant_time_eq(provided_hash.as_bytes(), envelope_hash.as_bytes()) {
                    return Err(ApiError::unauthorized_detail("Invalid restore credential"));
                }
            }
            Err(error) => {
                require_journal_credential(&provided_hash, journal_hash.as_deref())?;
                return Err(error);
            }
        },
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            require_journal_credential(&provided_hash, journal_hash.as_deref())?;
        }
        Err(error) => {
            require_journal_credential(&provided_hash, journal_hash.as_deref())?;
            return Err(error.into());
        }
    }
    Ok(())
}

fn require_journal_credential(
    provided_hash: &str,
    journal_hash: Option<&str>,
) -> Result<(), ApiError> {
    if journal_hash
        .is_some_and(|expected| constant_time_eq(provided_hash.as_bytes(), expected.as_bytes()))
    {
        Ok(())
    } else {
        Err(ApiError::unauthorized_detail("Invalid restore credential"))
    }
}

fn decode_credential_hash(bytes: &[u8]) -> Result<String, ApiError> {
    let hash = std::str::from_utf8(bytes)
        .map_err(|_| ApiError::internal("restore credential envelope is invalid"))?;
    if hash.len() != 64
        || !hash
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(ApiError::internal("restore credential envelope is invalid"));
    }
    Ok(hash.to_owned())
}

fn load_journal_credential_hash(data_root: &Path, batch_id: &str) -> Option<String> {
    let path = journal_path(data_root, batch_id).ok()?;
    let bytes = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return None,
        Err(error) => {
            tracing::warn!(%error, batch_id, "restore journal auth projection is unreadable");
            return None;
        }
    };
    let projection: RestoreCredentialProjection = match serde_json::from_slice(&bytes) {
        Ok(projection) => projection,
        Err(error) => {
            tracing::warn!(%error, batch_id, "restore journal auth projection is invalid");
            return None;
        }
    };
    decode_credential_hash(projection.recovery_token_hash.as_bytes())
        .map_err(|_| tracing::warn!(batch_id, "restore journal credential hash is invalid"))
        .ok()
}

fn require_open(journal: &RestoreJournal) -> Result<(), ApiError> {
    if journal.status == "committed" {
        return Err(ApiError::conflict("restore batch is already committed"));
    }
    Ok(())
}

fn bearer(headers: &HeaderMap) -> Option<&str> {
    let raw = headers.get(AUTHORIZATION)?.to_str().ok()?;
    let (scheme, token) = raw.split_once(' ')?;
    (scheme.eq_ignore_ascii_case("bearer") && !token.is_empty()).then_some(token)
}

fn validate_request_id(value: &str) -> Result<(), ApiError> {
    let value = value.trim();
    if !(32..=128).contains(&value.len())
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        return Err(ApiError::unprocessable(
            "request_id must be 32-128 URL-safe characters",
        ));
    }
    Ok(())
}

fn restore_root(data_root: &Path) -> PathBuf {
    data_root.join(RESTORE_DIR)
}

fn batch_dir(data_root: &Path, batch_id: &str) -> Result<PathBuf, ApiError> {
    let batch_id = Uuid::parse_str(batch_id)
        .map_err(|_| ApiError::not_found("restore batch not found"))?
        .to_string();
    Ok(restore_root(data_root).join(batch_id))
}

fn canonical_batch_id(batch_id: &str) -> Result<String, ApiError> {
    let batch_id = Uuid::parse_str(batch_id)
        .map_err(|_| ApiError::not_found("restore batch not found"))?
        .to_string();
    Ok(batch_id)
}

fn journal_path(data_root: &Path, batch_id: &str) -> Result<PathBuf, ApiError> {
    Ok(batch_dir(data_root, batch_id)?.join("journal.json"))
}

fn credential_hash_path(data_root: &Path, batch_id: &str) -> Result<PathBuf, ApiError> {
    Ok(batch_dir(data_root, batch_id)?.join(RESTORE_CREDENTIAL_HASH_FILE))
}

fn ensure_credential_envelope(data_root: &Path, journal: &RestoreJournal) -> Result<(), ApiError> {
    let path = credential_hash_path(data_root, &journal.batch_id)?;
    match fs::read(&path) {
        Ok(bytes) => {
            let stored = decode_credential_hash(&bytes)?;
            if !constant_time_eq(stored.as_bytes(), journal.recovery_token_hash.as_bytes()) {
                return Err(ApiError::internal(
                    "restore credential envelope does not match journal",
                ));
            }
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            write_private_file(&path, journal.recovery_token_hash.as_bytes())
        }
        Err(error) => Err(error.into()),
    }
}

fn manifest_path(data_root: &Path, batch_id: &str) -> Result<PathBuf, ApiError> {
    Ok(batch_dir(data_root, batch_id)?.join("manifest.json"))
}

fn staged_media_path(
    data_root: &Path,
    batch_id: &str,
    media_id: &str,
) -> Result<PathBuf, ApiError> {
    let media_id = Uuid::parse_str(media_id)
        .map_err(|_| ApiError::unprocessable("media client_uuid must be a UUID"))?
        .to_string();
    Ok(batch_dir(data_root, batch_id)?.join("media").join(media_id))
}

fn save_journal(data_root: &Path, journal: &RestoreJournal) -> Result<(), ApiError> {
    let encoded = serde_json::to_vec(journal).map_err(restore_json_error)?;
    write_private_file(&journal_path(data_root, &journal.batch_id)?, &encoded)
}

fn load_journal(data_root: &Path, batch_id: &str) -> Result<RestoreJournal, ApiError> {
    let path = journal_path(data_root, batch_id)?;
    let bytes = fs::read(&path)?;
    decode_journal(&bytes)
}

fn load_journal_path(path: &Path) -> Result<RestoreJournal, ApiError> {
    let bytes = fs::read(path)?;
    decode_journal(&bytes)
}

fn decode_journal(bytes: &[u8]) -> Result<RestoreJournal, ApiError> {
    let journal: RestoreJournal = serde_json::from_slice(bytes).map_err(restore_json_error)?;
    if journal.protocol_version != RESTORE_PROTOCOL_VERSION {
        return Err(ApiError::conflict("restore batch protocol is incompatible"));
    }
    Ok(journal)
}

fn load_verified_manifest(
    data_root: &Path,
    journal: &RestoreJournal,
) -> Result<ValidatedRestoreManifest, ApiError> {
    let path = manifest_path(data_root, &journal.batch_id)?;
    let bytes = fs::read(&path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            ApiError::conflict("restore manifest has not been uploaded")
        } else {
            error.into()
        }
    })?;
    let expected = journal
        .manifest_hash
        .as_deref()
        .ok_or_else(|| ApiError::conflict("restore manifest has not been uploaded"))?;
    let actual = hex::encode(Sha256::digest(&bytes));
    if !constant_time_eq(expected.as_bytes(), actual.as_bytes()) {
        return Err(ApiError::conflict(
            "restore manifest integrity check failed",
        ));
    }
    serde_json::from_slice(&bytes).map_err(restore_json_error)
}

fn restore_json_error(error: serde_json::Error) -> ApiError {
    ApiError::internal(format!("disaster restore journal is invalid: {error}"))
}

fn find_by_start_request(
    data_root: &Path,
    request_id: &str,
) -> Result<Option<RestoreJournal>, ApiError> {
    let root = restore_root(data_root);
    if !root.exists() {
        return Ok(None);
    }
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        if entry.file_type()?.is_dir() {
            if let Ok(journal) = load_journal_path(&entry.path().join("journal.json")) {
                if journal.start_request_id == request_id {
                    return Ok(Some(journal));
                }
            }
        }
    }
    Ok(None)
}

fn cleanup_expired_and_incomplete(data_root: &Path, now: i64) -> Result<(), ApiError> {
    let root = restore_root(data_root);
    if !root.exists() {
        return Ok(());
    }
    for entry in fs::read_dir(&root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let Some(batch_id) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if Uuid::parse_str(&batch_id)
            .map(|uuid| uuid.to_string() != batch_id)
            .unwrap_or(true)
        {
            continue;
        }
        let journal_path = entry.path().join("journal.json");
        if !journal_path.try_exists()? {
            fs::remove_dir_all(entry.path())?;
            continue;
        }
        if let Ok(journal) = load_journal_path(&journal_path) {
            if journal.expires_at <= now && journal.status != "committed" {
                fs::remove_dir_all(entry.path())?;
            }
        }
    }
    Ok(())
}

async fn cleanup_expired_runtime(
    data_root: PathBuf,
    restore_locks: crate::restore_locks::RestoreLockPool,
    now: i64,
) -> Result<(), ApiError> {
    let candidate_root = data_root.clone();
    let candidates = run_blocking(move || restore_batch_ids(&candidate_root)).await?;
    for batch_id in candidates {
        let batch_root = data_root.clone();
        restore_locks
            .run_serialized(batch_id.clone(), move || {
                let path = journal_path(&batch_root, &batch_id)?;
                let Ok(journal) = load_journal_path(&path) else {
                    return Ok(());
                };
                if journal.expires_at <= now && journal.status != "committed" {
                    fs::remove_dir_all(batch_dir(&batch_root, &batch_id)?)?;
                }
                Ok(())
            })
            .await?;
    }
    Ok(())
}

fn restore_batch_ids(data_root: &Path) -> Result<Vec<String>, ApiError> {
    let root = restore_root(data_root);
    if !root.exists() {
        return Ok(Vec::new());
    }
    let mut batch_ids = Vec::new();
    for entry in fs::read_dir(root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let Some(batch_id) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if Uuid::parse_str(&batch_id).is_ok_and(|uuid| uuid.to_string() == batch_id) {
            batch_ids.push(batch_id);
        }
    }
    Ok(batch_ids)
}

fn recovery_token(state: &AppState, request_id: &str, batch_id: &str) -> String {
    derive_token(
        &state.signing_secret,
        &format!("disaster-restore:{request_id}:{batch_id}"),
    )
}

fn restore_access_token(state: &AppState, journal: &RestoreJournal) -> String {
    derive_token(
        &state.signing_secret,
        &format!(
            "restore-access:{}:{}:{}",
            journal.batch_id, journal.family_id, journal.device_id
        ),
    )
}

fn restore_refresh_token(state: &AppState, journal: &RestoreJournal) -> String {
    derive_token(
        &state.signing_secret,
        &format!(
            "restore-refresh:{}:{}:{}",
            journal.batch_id, journal.family_id, journal.device_id
        ),
    )
}

fn all_media_ready(
    data_root: &Path,
    journal: &RestoreJournal,
    manifest: &ValidatedRestoreManifest,
) -> Result<bool, ApiError> {
    for spec in &manifest.media {
        let staged = staged_media_path(data_root, &journal.batch_id, &spec.client_uuid)?;
        let final_path = data_root
            .join("media")
            .join(&journal.family_id)
            .join(&spec.client_uuid);
        let path = if staged.exists() { staged } else { final_path };
        if !path.exists() {
            return Ok(false);
        }
        let bytes = fs::read(path)?;
        if bytes.len() != spec.byte_size || hex::encode(Sha256::digest(&bytes)) != spec.sha256 {
            return Ok(false);
        }
    }
    Ok(true)
}

fn ensure_all_media_ready(
    data_root: &Path,
    journal: &RestoreJournal,
    manifest: &ValidatedRestoreManifest,
) -> Result<(), ApiError> {
    if !all_media_ready(data_root, journal, manifest)? {
        return Err(ApiError::unprocessable(
            "restore media is missing or does not match the manifest",
        ));
    }
    Ok(())
}

fn install_media_before_activation(
    state: &AppState,
    journal: &RestoreJournal,
    manifest: &ValidatedRestoreManifest,
) -> Result<(), ApiError> {
    let family_dir = state.media_root.join(&journal.family_id);
    fs::create_dir_all(&family_dir)?;
    secure_directory(&family_dir)?;
    for spec in &manifest.media {
        let staged = staged_media_path(&state.data_root, &journal.batch_id, &spec.client_uuid)?;
        let final_path = family_dir.join(&spec.client_uuid);
        if final_path.exists() {
            continue;
        }
        fs::rename(&staged, &final_path)?;
        secure_file(&final_path)?;
    }
    sync_directory(&family_dir)?;
    sync_directory(&state.media_root)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::restore_locks::RestoreLockPool;
    use std::sync::mpsc;
    use std::time::Duration;
    use tempfile::TempDir;
    use tokio::sync::oneshot;

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn runtime_cleanup_waits_for_batch_work_and_rechecks_status() {
        let directory = TempDir::new().unwrap();
        let batch_id = Uuid::new_v4().to_string();
        let batch = batch_dir(directory.path(), &batch_id).unwrap();
        fs::create_dir_all(batch.join("media")).unwrap();
        let journal = RestoreJournal {
            protocol_version: RESTORE_PROTOCOL_VERSION,
            batch_id: batch_id.clone(),
            start_request_id: "runtime-cleanup-race-request-0001".to_owned(),
            family_id: Uuid::new_v4().to_string(),
            family_name: "家庭".to_owned(),
            owner_display_name: "妈妈".to_owned(),
            device_name: "手机".to_owned(),
            owner_membership_id: Uuid::new_v4().to_string(),
            device_id: Uuid::new_v4().to_string(),
            session_id: Uuid::new_v4().to_string(),
            access_expires_at: 1,
            recovery_token_hash: "0".repeat(64),
            created_at: 0,
            expires_at: 1,
            status: "started".to_owned(),
            manifest_request_id: None,
            manifest_hash: None,
            commit_request_id: None,
        };
        save_journal(directory.path(), &journal).unwrap();
        let locks = RestoreLockPool::default();
        let holder_locks = locks.clone();
        let holder_root = directory.path().to_owned();
        let holder_batch_id = batch_id.clone();
        let (holder_entered_tx, holder_entered_rx) = oneshot::channel();
        let (release_holder_tx, release_holder_rx) = mpsc::channel();
        let holder = tokio::spawn(async move {
            holder_locks
                .run_serialized(holder_batch_id.clone(), move || {
                    holder_entered_tx.send(()).unwrap();
                    release_holder_rx.recv().unwrap();
                    let mut journal = load_journal(&holder_root, &holder_batch_id)?;
                    journal.status = "committed".to_owned();
                    save_journal(&holder_root, &journal)
                })
                .await
        });
        holder_entered_rx.await.unwrap();

        let mut cleanup = Box::pin(cleanup_expired_runtime(
            directory.path().to_owned(),
            locks,
            2,
        ));
        assert!(
            tokio::time::timeout(Duration::from_millis(50), &mut cleanup)
                .await
                .is_err(),
            "cleanup crossed a live batch holder",
        );
        assert!(batch.is_dir());

        release_holder_tx.send(()).unwrap();
        holder.await.unwrap().unwrap();
        cleanup.await.unwrap();
        assert!(batch.is_dir(), "cleanup did not recheck committed status");
    }
}
