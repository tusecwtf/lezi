//! Identity / family setup, login, session, and leave/delete route handlers.
//!
//! Entry points are `pub(crate)` so crate-root `build_apps` can bind them via
//! domain paths (`handlers::identity::create_family`, …). Nested under `handlers`,
//! so this is not the same `pub(super)` surface as flat [`crate::members`].

use std::fs;
use std::net::SocketAddr;
use std::sync::Arc;

use axum::extract::rejection::JsonRejection;
use axum::extract::{ConnectInfo, Path as AxumPath, State};
use axum::http::{HeaderMap, HeaderName, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::{json, Value};
use uuid::Uuid;

use crate::handlers::disaster_restore;
use crate::model::{
    normalized_display_name_key, BindExistingMemberRequest, ClaimMemberLoginGrantRequest,
    CreateMemberLoginGrantRequest, DeleteFamilyRequest, EmptyRequest, FamilyCreateRequest,
    MemberLoginRequest, OwnerLoginRequest, PendingSecretRequest, RefreshSessionRequest,
    RenameFamilyRequest,
};
use crate::readiness::is_ready;
use crate::store::{CreateFamilyInput, CreateMemberLoginRequestInput, StoreError};
use crate::{
    authenticate, json_body, require_bootstrap_secret, require_owner, require_owner_root_password,
    run_blocking, secure_session_token, sync_directory, ApiError, AppState,
    CAPABILITY_ATOMIC_BUNDLE, CAPABILITY_CAUSAL_MEDIA_IDENTITY_V1, CAPABILITY_CAUSAL_SYNC_V2,
    CAPABILITY_DEVICE_SESSIONS, CAPABILITY_DISASTER_RESTORE, CAPABILITY_MEMBERSHIP_DEVICES,
    CAPABILITY_NURSING_PLAN_INTENT_V1, CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
    CAPABILITY_RESTORE_AUTHORITY_V1, CAPABILITY_SYNC_HEARTBEAT_V1,
    CAPABILITY_TRUSTED_HTTPS_ENDPOINT, CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT,
    MEMBER_LOGIN_GRANT_TTL_SECONDS, SETUP_PROTOCOL_VERSION,
};

const REFRESH_REQUEST_ID_HEADER: HeaderName = HeaderName::from_static("x-lezi-refresh-request-id");
const MEMBER_REQUEST_VIEW_HEADER: HeaderName =
    HeaderName::from_static("x-lezi-member-request-view");
const OPEN_MEMBER_REQUEST_VIEW: &str = "open-v1";

pub(crate) async fn setup_status(State(state): State<Arc<AppState>>) -> Result<Response, ApiError> {
    if !is_ready(&state).await {
        return Ok(StatusCode::SERVICE_UNAVAILABLE.into_response());
    }
    let store = state.store.clone();
    let family_ids = run_blocking(move || Ok(store.family_ids()?)).await?;
    let family_state = if family_ids.is_empty() {
        "empty"
    } else {
        "configured"
    };
    Ok(Json(json!({
        "protocol_version": SETUP_PROTOCOL_VERSION,
        "capabilities": [
            CAPABILITY_TRUSTED_HTTPS_ENDPOINT,
            CAPABILITY_DEVICE_SESSIONS,
            CAPABILITY_MEMBERSHIP_DEVICES,
            CAPABILITY_ATOMIC_BUNDLE,
            CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            CAPABILITY_DISASTER_RESTORE,
            CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT,
            CAPABILITY_CAUSAL_SYNC_V2,
            // Incremental broadcast only (wire §1): newer generations may append
            // here; the handshake capability key set stays byte-exact.
            CAPABILITY_SYNC_HEARTBEAT_V1,
            CAPABILITY_CAUSAL_MEDIA_IDENTITY_V1,
            CAPABILITY_NURSING_PLAN_INTENT_V1,
            CAPABILITY_RESTORE_AUTHORITY_V1,
        ],
        "family_state": family_state,
    }))
    .into_response())
}

pub(crate) async fn create_family(
    State(state): State<Arc<AppState>>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Result<Json<FamilyCreateRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    // Once configured, every create request has the same response regardless
    // of whether the caller guessed the administrator secret.
    let store = state.store.clone();
    let family_is_configured = run_blocking(move || Ok(!store.family_ids()?.is_empty())).await?;
    if state.bootstrap_secret.is_some() && family_is_configured {
        return Err(ApiError::conflict("Family already exists"));
    }
    let data_root = state.data_root.clone();
    let now = state.now();
    if run_blocking(move || disaster_restore::has_active_batch(&data_root, now)).await? {
        return Err(ApiError::conflict(
            "Family creation is unavailable while disaster restore is active",
        ));
    }
    require_bootstrap_secret(&state, &headers, source)?;
    let request = json_body(body)?;
    let (display_name, family_name, device_name) = request.validate()?;
    let display_name_key = normalized_display_name_key(&display_name);
    let scope = format!("family-create-source:{}", source.ip());
    if !state.create_limiter.check_and_record(&scope, state.now()) {
        return Err(ApiError::too_many_requests(
            "Too many family create attempts; try again later",
        ));
    }
    // Family creation and empty-server disaster restore are the two mutually exclusive
    // provisioning paths. Recheck both predicates under one process-wide lock so concurrent
    // requests cannot both pass their initial read-only gates.
    let provisioning_lock = state.family_lock(crate::PROVISIONING_LOCK_KEY).await;
    let _provisioning_guard = provisioning_lock.lock().await;
    // Do not preempt Store's strict create_request_id replay here: a retry after a lost
    // response must still receive its original credentials. Store atomically rejects any
    // different request once a family exists.
    let store = state.store.clone();
    let data_root = state.data_root.clone();
    let now = state.now();
    let owner_root_fingerprint = state.owner_root_fingerprint.as_deref().map(str::to_owned);
    let signing_state = state.clone();
    let result = run_blocking(move || {
        if disaster_restore::has_active_batch(&data_root, now)? {
            return Err(ApiError::conflict(
                "Family creation is unavailable while disaster restore is active",
            ));
        }
        Ok(store.create_family(
            CreateFamilyInput {
                now,
                create_request_id: &request.create_request_id,
                display_name: &display_name,
                display_name_key: &display_name_key,
                family_name: &family_name,
                device_name: &device_name,
                owner_root_fingerprint: owner_root_fingerprint.as_deref(),
            },
            move |request_hash, family_id, device_id| {
                signing_state.owner_tokens(request_hash, family_id, device_id)
            },
        ))
    })
    .await?;
    let issued = match result {
        Ok(value) => value,
        Err(StoreError::FamilyAlreadyExists) => {
            return Err(ApiError::conflict("Family already exists"));
        }
        Err(error) => return Err(error.into()),
    };
    Ok((
        StatusCode::CREATED,
        Json(json!({
            "family_id": issued.family_id,
            "access_token": issued.access_token,
            "access_expires_at": issued.access_expires_at,
            "refresh_token": issued.refresh_token,
            "role": "owner",
            "membership_id": issued.membership_id,
            "device_id": issued.device_id,
            "session_id": issued.session_id,
            "generation": state.generation,
            "family_name": issued.family_name,
            "reclaimed": false,
        })),
    ))
}

pub(crate) async fn owner_login_device(
    State(state): State<Arc<AppState>>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Result<Json<OwnerLoginRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    issue_owner_device(state, source, headers, body, false).await
}

pub(crate) async fn owner_takeover(
    State(state): State<Arc<AppState>>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Result<Json<OwnerLoginRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    issue_owner_device(state, source, headers, body, true).await
}

async fn issue_owner_device(
    state: Arc<AppState>,
    source: SocketAddr,
    headers: HeaderMap,
    body: Result<Json<OwnerLoginRequest>, JsonRejection>,
    takeover: bool,
) -> Result<Json<Value>, ApiError> {
    require_owner_root_password(&state, &headers, source)?;
    let request = json_body(body)?;
    let (login_request_id, device_name) = request.validate()?;
    let store = state.store.clone();
    let now = state.now();
    let signing_state = state.clone();
    let result = run_blocking(move || {
        Ok(store.owner_login(
            now,
            &login_request_id,
            &device_name,
            takeover,
            move |request_hash, family_id, device_id| {
                signing_state.owner_login_tokens(request_hash, family_id, device_id)
            },
        ))
    })
    .await?;
    let issued = match result {
        Ok(value) => value,
        Err(StoreError::FamilyNotConfigured) => {
            return Err(ApiError::conflict("Owner login is unavailable"));
        }
        Err(StoreError::OwnerLoginRequestConflict) => {
            return Err(ApiError::conflict("Owner login request cannot be replayed"));
        }
        Err(StoreError::DeviceNameConflict) => {
            return Err(ApiError::conflict(
                "Device name is already in use for this family member",
            ));
        }
        Err(error) => return Err(error.into()),
    };
    Ok(Json(json!({
        "family_id": issued.family_id,
        "membership_id": issued.membership_id,
        "device_id": issued.device_id,
        "session_id": issued.session_id,
        "role": "owner",
        "access_token": issued.access_token,
        "access_expires_at": issued.access_expires_at,
        "refresh_token": issued.refresh_token,
        "generation": state.generation,
        "family_name": issued.family_name,
    })))
}

pub(crate) async fn create_member_login_request(
    State(state): State<Arc<AppState>>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    body: Result<Json<MemberLoginRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    let request = json_body(body)?;
    let (display_name, display_name_key, device_name) = request.validate()?;
    let scope = format!("member-request-source:{}", source.ip());
    if !state
        .member_request_limiter
        .check_and_record(&scope, state.now())
    {
        return Err(ApiError::too_many_requests(
            "Too many member login requests; try again later",
        ));
    }
    let pending_secret = secure_session_token();
    let store = state.store.clone();
    let now = state.now();
    let ttl_seconds = state.member_request_ttl_seconds;
    let max_pending = state.max_pending_member_requests;
    let blocking_pending_secret = pending_secret.clone();
    let pending = run_blocking(move || {
        store
            .create_member_login_request(CreateMemberLoginRequestInput {
                now,
                ttl_seconds,
                max_pending,
                display_name: &display_name,
                display_name_key: &display_name_key,
                device_name: &device_name,
                pending_secret: &blocking_pending_secret,
            })
            .map_err(map_member_request_error)
    })
    .await?;
    Ok((
        StatusCode::CREATED,
        Json(json!({
            "request_id": pending.request_id,
            "pending_secret": pending_secret,
            "status": "pending",
            "expires_at": pending.expires_at,
        })),
    ))
}

pub(crate) async fn member_login_request_status(
    State(state): State<Arc<AppState>>,
    body: Result<Json<PendingSecretRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let request = json_body(body)?;
    let pending_secret = request.validate()?.to_owned();
    let store = state.store.clone();
    let now = state.now();
    let status = run_blocking(move || {
        store
            .member_login_request_status(&pending_secret, now)
            .map_err(map_member_request_error)
    })
    .await?;
    Ok(Json(json!({"status": status})))
}

pub(crate) async fn cancel_member_login_request(
    State(state): State<Arc<AppState>>,
    body: Result<Json<PendingSecretRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let request = json_body(body)?;
    let pending_secret = request.validate()?.to_owned();
    let store = state.store.clone();
    let now = state.now();
    let status = run_blocking(move || {
        store
            .cancel_member_login_request(&pending_secret, now)
            .map_err(map_member_request_error)
    })
    .await?;
    Ok(Json(json!({"ok": true, "status": status})))
}

pub(crate) async fn list_member_login_requests(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    // Some pre-release APKs already used versionCode 12 without understanding an
    // approved row. Require an explicit shape capability instead of guessing from
    // versionCode; legacy clients retain the pending-only response they understand.
    let include_approved = headers
        .get(MEMBER_REQUEST_VIEW_HEADER)
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value == OPEN_MEMBER_REQUEST_VIEW);
    let store = state.store.clone();
    let now = state.now();
    let mut requests =
        run_blocking(move || Ok(store.pending_member_login_requests(&principal, now)?)).await?;
    if !include_approved {
        requests.retain(|request| request.status == "pending");
    }
    Ok(Json(json!({"requests": requests})))
}

pub(crate) async fn approve_new_member_login_request(
    State(state): State<Arc<AppState>>,
    AxumPath(request_id): AxumPath<Uuid>,
    headers: HeaderMap,
    body: Result<Json<EmptyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let _ = json_body(body)?;
    let store = state.store.clone();
    let request_id = request_id.to_string();
    let now = state.now();
    run_blocking(move || {
        store
            .approve_new_member_login_request(&principal, &request_id, now)
            .map_err(map_member_request_error)
    })
    .await?;
    Ok(Json(json!({"ok": true, "status": "approved"})))
}

pub(crate) async fn bind_existing_member_login_request(
    State(state): State<Arc<AppState>>,
    AxumPath(request_id): AxumPath<Uuid>,
    headers: HeaderMap,
    body: Result<Json<BindExistingMemberRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let request = json_body(body)?;
    let membership_id = request.validate()?;
    let store = state.store.clone();
    let request_id = request_id.to_string();
    let now = state.now();
    run_blocking(move || {
        store
            .bind_existing_member_login_request(&principal, &request_id, &membership_id, now)
            .map_err(map_member_request_error)
    })
    .await?;
    Ok(Json(json!({"ok": true, "status": "approved"})))
}

pub(crate) async fn reject_member_login_request(
    State(state): State<Arc<AppState>>,
    AxumPath(request_id): AxumPath<Uuid>,
    headers: HeaderMap,
    body: Result<Json<EmptyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let _ = json_body(body)?;
    let store = state.store.clone();
    let request_id = request_id.to_string();
    let now = state.now();
    run_blocking(move || {
        store
            .reject_member_login_request(&principal, &request_id, now)
            .map_err(map_member_request_error)
    })
    .await?;
    Ok(Json(json!({"ok": true, "status": "rejected"})))
}

pub(crate) async fn claim_member_login_request(
    State(state): State<Arc<AppState>>,
    body: Result<Json<PendingSecretRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let request = json_body(body)?;
    let pending_secret = request.validate()?.to_owned();
    let store = state.store.clone();
    let now = state.now();
    let signing_state = state.clone();
    let issued = run_blocking(move || {
        store
            .claim_member_login_request(&pending_secret, now, move |hash, family, device| {
                signing_state.member_request_tokens(hash, family, device)
            })
            .map_err(map_member_request_error)
    })
    .await?;
    Ok(Json(json!({
        "family_id": issued.family_id,
        "membership_id": issued.membership_id,
        "device_id": issued.device_id,
        "session_id": issued.session_id,
        "role": "member",
        "access_token": issued.access_token,
        "access_expires_at": issued.access_expires_at,
        "refresh_token": issued.refresh_token,
        "generation": state.generation,
        "family_name": issued.family_name,
    })))
}

pub(crate) async fn create_member_login_grant(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<CreateMemberLoginGrantRequest>, JsonRejection>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let request = json_body(body)?;
    let membership_id = request.validate()?;
    let grant = secure_session_token();
    let store = state.store.clone();
    let blocking_grant = grant.clone();
    let now = state.now();
    let created = run_blocking(move || {
        store
            .create_member_login_grant(
                &principal,
                &membership_id,
                &blocking_grant,
                now,
                MEMBER_LOGIN_GRANT_TTL_SECONDS,
            )
            .map_err(map_member_login_grant_error)
    })
    .await?;
    let mut response = json!({
        "grant": grant,
        "family_name": created.family_name,
        "member_display_name": created.member_display_name,
        "expires_at": created.expires_at,
    });
    if let Some(landing_url) = &state.lan_apk_landing_url {
        response
            .as_object_mut()
            .expect("member login grant response is an object")
            .insert(
                "landing_url".to_owned(),
                Value::String(landing_url.to_string()),
            );
    }
    Ok((StatusCode::CREATED, Json(response)))
}

pub(crate) async fn claim_member_login_grant(
    State(state): State<Arc<AppState>>,
    body: Result<Json<ClaimMemberLoginGrantRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let request = json_body(body)?;
    let (grant, device_name) = request.validate()?;
    let grant = grant.to_owned();
    let store = state.store.clone();
    let now = state.now();
    let signing_state = state.clone();
    let issued = run_blocking(move || {
        store
            .claim_member_login_grant(&grant, &device_name, now, move |hash, family, device| {
                signing_state.member_login_grant_tokens(hash, family, device)
            })
            .map_err(map_member_login_grant_error)
    })
    .await?;
    Ok(Json(json!({
        "family_id": issued.family_id,
        "membership_id": issued.membership_id,
        "device_id": issued.device_id,
        "session_id": issued.session_id,
        "role": "member",
        "access_token": issued.access_token,
        "access_expires_at": issued.access_expires_at,
        "refresh_token": issued.refresh_token,
        "generation": state.generation,
        "family_name": issued.family_name,
    })))
}

fn map_member_login_grant_error(error: StoreError) -> ApiError {
    match error {
        StoreError::MemberLoginGrantNotFound => ApiError::not_found("Member login grant not found"),
        StoreError::MemberLoginGrantExpired => ApiError::gone("Member login grant expired"),
        StoreError::MemberLoginGrantAlreadyUsed => {
            ApiError::conflict("Member login grant was already used")
        }
        StoreError::MembershipNotFound => ApiError::conflict("Target family member is unavailable"),
        StoreError::DeviceNameConflict => {
            ApiError::conflict("Device name is already in use for this family member")
        }
        other => other.into(),
    }
}

fn map_member_request_error(error: StoreError) -> ApiError {
    match error {
        StoreError::MemberRequestNotFound => ApiError::not_found("Member request not found"),
        StoreError::MemberRequestExpired => ApiError::gone("Member request expired"),
        StoreError::MemberRequestStateConflict => {
            ApiError::conflict("Member request is not available for this action")
        }
        StoreError::MemberRequestLimit => ApiError::conflict("Too many pending member requests"),
        StoreError::DisplayNameConflict => {
            ApiError::conflict("Family display name is already in use")
        }
        StoreError::MembershipNotFound => ApiError::conflict("Target family member is unavailable"),
        StoreError::FamilyNotConfigured => ApiError::conflict("Family is not configured"),
        StoreError::DeviceNameConflict => {
            ApiError::conflict("Device name is already in use for this family member")
        }
        other => other.into(),
    }
}

pub(crate) async fn refresh_session(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<RefreshSessionRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let request = json_body(body)?;
    let refresh_token = request.validate()?.to_owned();
    let refresh_request_id = parse_refresh_request_id(&headers)?;
    let fallback_access = secure_session_token();
    let fallback_refresh = secure_session_token();
    let store = state.store.clone();
    let signing_state = state.clone();
    let now = state.now();
    let refresh_result = run_blocking(move || {
        let derivation_request_id = refresh_request_id.clone();
        Ok(store.refresh_session(
            now,
            &refresh_token,
            refresh_request_id.as_deref(),
            move |family_id, device_id| {
                derivation_request_id.as_deref().map_or_else(
                    || (fallback_access.clone(), fallback_refresh.clone()),
                    |request_id| {
                        signing_state.refresh_request_tokens(request_id, family_id, device_id)
                    },
                )
            },
        ))
    })
    .await?;
    let refreshed = match refresh_result {
        Ok(value) => value,
        Err(StoreError::InvalidRefreshToken) => {
            return Err(ApiError::unauthorized_code(
                "invalid_refresh",
                "Refresh token is invalid or revoked",
            ));
        }
        Err(StoreError::RefreshTokenReplay) => {
            return Err(ApiError::unauthorized_code(
                "refresh_replay",
                "Refresh token replay revoked this device",
            ));
        }
        Err(StoreError::DeviceRemoved) => {
            return Err(ApiError::unauthorized_code(
                "device_removed",
                "This device was removed from the family",
            ));
        }
        Err(StoreError::MembershipDeleted) => {
            return Err(ApiError::unauthorized_code(
                "membership_deleted",
                "This family membership was deleted",
            ));
        }
        Err(StoreError::FamilyDeleted) => {
            return Err(ApiError::unauthorized_code(
                "family_deleted",
                "This family was deleted",
            ));
        }
        Err(error) => return Err(error.into()),
    };
    Ok(Json(json!({
        "family_id": refreshed.family_id,
        "membership_id": refreshed.membership_id,
        "device_id": refreshed.device_id,
        "session_id": refreshed.session_id,
        "role": refreshed.role,
        "access_token": refreshed.access_token,
        "access_expires_at": refreshed.access_expires_at,
        "refresh_token": refreshed.refresh_token,
        "generation": state.generation,
        "family_name": refreshed.family_name,
    })))
}

fn parse_refresh_request_id(headers: &HeaderMap) -> Result<Option<String>, ApiError> {
    let Some(value) = headers.get(&REFRESH_REQUEST_ID_HEADER) else {
        return Ok(None);
    };
    let value = value
        .to_str()
        .map_err(|_| ApiError::unprocessable("Refresh request id is invalid"))?
        .trim();
    if !(16..=128).contains(&value.len())
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
    {
        return Err(ApiError::unprocessable(
            "Refresh request id must be 16-128 URL-safe characters",
        ));
    }
    Ok(Some(value.to_owned()))
}

/// Owner-only rename of the shared family name.
///
/// Body: `{"family_name": "…"}` — current wire requires a non-empty name so
/// destructive family-name confirmation remains reachable.
pub(crate) async fn rename_family(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<RenameFamilyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let request = json_body(body)?;
    let family_name = request.validate()?;
    let store = state.store.clone();
    let blocking_family_name = family_name.clone();
    run_blocking(move || Ok(store.rename_family(&principal, &blocking_family_name)?)).await?;
    Ok(Json(json!({
        "ok": true,
        "family_name": family_name,
    })))
}

pub(crate) async fn leave(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<EmptyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    let _ = json_body(body)?;
    if principal.role == "owner" {
        return Err(ApiError::forbidden(
            "Owner must delete the family instead of leaving",
        ));
    }
    let store = state.store.clone();
    let membership_id = principal.membership_id.clone();
    let now = state.now();
    run_blocking(move || Ok(store.hard_delete_membership(&principal, &membership_id, now)?))
        .await?;
    Ok(Json(json!({"ok": true})))
}

pub(crate) async fn logout_current_device(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<EmptyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    let _ = json_body(body)?;
    let store = state.store.clone();
    let device_id = principal.device_id.clone();
    let now = state.now();
    run_blocking(move || Ok(store.revoke_family_device(&principal, &device_id, now)?)).await?;
    Ok(Json(json!({"ok": true})))
}

pub(crate) async fn delete_family(
    State(state): State<Arc<AppState>>,
    ConnectInfo(source): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    body: Result<Json<DeleteFamilyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    require_owner_root_password(&state, &headers, source)?;
    let confirmed_family_name = json_body(body)?.validate()?;
    let provisioning = state.family_lock(crate::PROVISIONING_LOCK_KEY).await;
    let _provisioning_guard = provisioning.lock().await;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let principal = require_owner(&state, &headers).await?;
    let blocking_state = state.clone();
    run_blocking(move || {
        let family_media = blocking_state.media_root.join(&principal.family_id);
        match blocking_state
            .store
            .delete_family(&principal, &confirmed_family_name)
        {
            Ok(()) => {}
            Err(StoreError::FamilyNameMismatch) => {
                return Err(ApiError::conflict(
                    "Family name confirmation does not match",
                ));
            }
            Err(error) => return Err(error.into()),
        }
        super::disaster_restore::retire_family(&blocking_state.data_root, &principal.family_id)?;
        if family_media.exists() {
            fs::remove_dir_all(&family_media)?;
            sync_directory(&blocking_state.media_root)?;
        }
        Ok(())
    })
    .await?;
    Ok(Json(json!({"ok": true})))
}
