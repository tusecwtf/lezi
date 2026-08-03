use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::rejection::JsonRejection;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::Json;
use serde::Serialize;
use serde_json::{json, Value};

use crate::model::{
    normalized_device_name_key, normalized_display_name_key, EmptyRequest, RemoveMemberRequest,
    UpdateDeviceNameRequest, UpdateDisplayNameRequest,
};
use crate::store::StoreError;
use crate::{authenticate, json_body, require_owner, run_blocking, ApiError, AppState};

const MEMBER_RENAME_REQUEST_TTL_SECONDS: i64 = 7 * 24 * 60 * 60;

#[derive(Debug, Serialize)]
struct MemberView {
    display_name: String,
    role: String,
    is_self: bool,
    /// Server-minted immutable membership identity. Safe public key for ACL.
    membership_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    devices: Option<Vec<DeviceView>>,
}

#[derive(Debug, Serialize)]
struct DeviceView {
    /// Opaque action key; never rendered as account copy.
    device_id: String,
    device_name: String,
    last_used_at: i64,
    is_current: bool,
}

#[derive(Debug, Serialize)]
pub(super) struct MembersResponse {
    members: Vec<MemberView>,
}

pub(super) async fn list_family_members(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<MembersResponse>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    let viewer_is_owner = principal.role == "owner";
    let store = state.store.clone();
    let family_id = principal.family_id.clone();
    let membership_id = principal.membership_id.clone();
    let (memberships, devices) = run_blocking(move || {
        Ok((
            store.active_memberships(&family_id)?,
            store.visible_active_devices(&family_id, &membership_id, viewer_is_owner)?,
        ))
    })
    .await?;
    let mut devices_by_membership = HashMap::<String, Vec<DeviceView>>::new();
    for device in devices {
        let is_current = device.device_id == principal.device_id;
        devices_by_membership
            .entry(device.membership_id)
            .or_default()
            .push(DeviceView {
                device_id: device.device_id,
                device_name: device.device_name,
                last_used_at: device.last_used_at,
                is_current,
            });
    }

    // Runtime projection is one row per server-minted membership.
    let mut members = memberships
        .into_iter()
        .map(|membership| {
            let is_self = membership.membership_id == principal.membership_id;
            let devices = if viewer_is_owner || is_self {
                Some(
                    devices_by_membership
                        .remove(&membership.membership_id)
                        .unwrap_or_default(),
                )
            } else {
                None
            };
            MemberView {
                display_name: membership.display_name,
                role: membership.role,
                is_self,
                membership_id: membership.membership_id,
                devices,
            }
        })
        .collect::<Vec<_>>();
    members.sort_by(|left, right| {
        role_rank(&left.role)
            .cmp(&role_rank(&right.role))
            .then_with(|| left.display_name.cmp(&right.display_name))
            .then_with(|| left.membership_id.cmp(&right.membership_id))
    });
    Ok(Json(MembersResponse { members }))
}

/// Owner self-renames immediately. An ordinary member creates an approval
/// request while the currently approved name remains authoritative.
pub(super) async fn update_my_display_name(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<UpdateDisplayNameRequest>, JsonRejection>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let principal = authenticate(&state, &headers).await?;
    let request = json_body(body)?;
    let display_name = request.validate()?;
    let display_name_key = normalized_display_name_key(&display_name);
    let store = state.store.clone();
    let family_id = principal.family_id.clone();
    let membership_id = principal.membership_id.clone();
    let now = state.now();
    if principal.role == "owner" {
        let blocking_display_name = display_name.clone();
        let blocking_display_name_key = display_name_key.clone();
        run_blocking(move || {
            store
                .rename_active_membership(
                    &family_id,
                    &membership_id,
                    &blocking_display_name,
                    &blocking_display_name_key,
                    now,
                )
                .map_err(map_name_lifecycle_error)
        })
        .await?;
        return Ok((
            StatusCode::OK,
            Json(json!({
                "status": "updated",
                "display_name": display_name,
            })),
        ));
    }
    let blocking_display_name = display_name.clone();
    let request = run_blocking(move || {
        store
            .create_member_rename_request(
                &family_id,
                &membership_id,
                &blocking_display_name,
                &display_name_key,
                now,
                MEMBER_RENAME_REQUEST_TTL_SECONDS,
            )
            .map_err(map_name_lifecycle_error)
    })
    .await?;
    Ok((
        StatusCode::ACCEPTED,
        Json(json!({
            "status": "pending",
            "request_id": request.request_id,
            "current_display_name": request.current_display_name,
            "requested_display_name": request.requested_display_name,
            "created_at": request.created_at,
            "expires_at": request.expires_at,
        })),
    ))
}

#[derive(Debug, Serialize)]
pub(super) struct RenameRequestsResponse {
    requests: Vec<crate::store::PendingMemberRenameRequest>,
}

pub(super) async fn list_member_rename_requests(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<RenameRequestsResponse>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let now = state.now();
    let requests =
        run_blocking(move || Ok(store.pending_member_rename_requests(&family_id, now)?)).await?;
    Ok(Json(RenameRequestsResponse { requests }))
}

pub(super) async fn approve_member_rename_request(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(request_id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let now = state.now();
    let display_name = run_blocking(move || {
        store
            .approve_member_rename_request(&family_id, &request_id, now)
            .map_err(map_name_lifecycle_error)
    })
    .await?;
    Ok(Json(json!({"ok": true, "display_name": display_name})))
}

pub(super) async fn reject_member_rename_request(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(request_id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let now = state.now();
    run_blocking(move || {
        store
            .reject_member_rename_request(&family_id, &request_id, now)
            .map_err(map_name_lifecycle_error)
    })
    .await?;
    Ok(Json(json!({"ok": true})))
}

pub(super) async fn cancel_my_member_rename_request(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    if principal.role != "member" {
        return Err(ApiError::forbidden(
            "Only an ordinary family member has a pending rename request",
        ));
    }
    let store = state.store.clone();
    let family_id = principal.family_id;
    let membership_id = principal.membership_id;
    let now = state.now();
    run_blocking(move || {
        store
            .cancel_own_member_rename_request(&family_id, &membership_id, now)
            .map_err(map_name_lifecycle_error)
    })
    .await?;
    Ok(Json(json!({"ok": true})))
}

pub(super) async fn add_family_member(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<UpdateDisplayNameRequest>, JsonRejection>,
) -> Result<(StatusCode, Json<Value>), ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let display_name = json_body(body)?.validate()?;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let blocking_display_name = display_name.clone();
    let display_name_key = normalized_display_name_key(&display_name);
    let membership_id = run_blocking(move || {
        store
            .add_device_less_member(&family_id, &blocking_display_name, &display_name_key)
            .map_err(map_name_lifecycle_error)
    })
    .await?;
    Ok((
        StatusCode::CREATED,
        Json(json!({
            "membership_id": membership_id,
            "display_name": display_name,
            "role": "member",
        })),
    ))
}

pub(super) async fn rename_family_member(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(membership_id): Path<String>,
    body: Result<Json<UpdateDisplayNameRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let display_name = json_body(body)?.validate()?;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let blocking_membership_id = membership_id.clone();
    let blocking_display_name = display_name.clone();
    let display_name_key = normalized_display_name_key(&display_name);
    let now = state.now();
    run_blocking(move || {
        store
            .rename_active_membership(
                &family_id,
                &blocking_membership_id,
                &blocking_display_name,
                &display_name_key,
                now,
            )
            .map_err(map_name_lifecycle_error)
    })
    .await?;
    Ok(Json(json!({
        "ok": true,
        "membership_id": membership_id,
        "display_name": display_name,
    })))
}

pub(super) async fn rename_family_device(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(device_id): Path<String>,
    body: Result<Json<UpdateDeviceNameRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    let is_owner = principal.role == "owner";
    let device_name = json_body(body)?.validate()?;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let membership_id = principal.membership_id;
    let blocking_device_id = device_id.clone();
    let blocking_device_name = device_name.clone();
    let device_name_key = normalized_device_name_key(&device_name);
    run_blocking(move || {
        if !is_owner {
            let owns_target = store
                .visible_active_devices(&family_id, &membership_id, false)?
                .iter()
                .any(|device| device.device_id == blocking_device_id);
            if !owns_target {
                return Err(ApiError::forbidden("Cannot manage another member's device"));
            }
        }
        store
            .rename_active_device(
                &family_id,
                &membership_id,
                is_owner,
                &blocking_device_id,
                &blocking_device_name,
                &device_name_key,
            )
            .map_err(map_name_lifecycle_error)
    })
    .await?;
    Ok(Json(json!({
        "ok": true,
        "device_id": device_id,
        "device_name": device_name,
    })))
}

pub(super) async fn revoke_family_device(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(device_id): Path<String>,
    body: Result<Json<EmptyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let _ = json_body(body)?;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let now = state.now();
    run_blocking(move || {
        store
            .revoke_family_device(&family_id, &device_id, now)
            .map_err(|error| match error {
                StoreError::DeviceNotFound => ApiError::not_found("Device not found"),
                other => other.into(),
            })
    })
    .await?;
    Ok(Json(json!({"ok": true})))
}

fn map_name_lifecycle_error(error: StoreError) -> ApiError {
    match error {
        StoreError::DisplayNameConflict => {
            ApiError::conflict("Family display name is already in use")
        }
        StoreError::DeviceNameConflict => {
            ApiError::conflict("Device name is already in use for this family member")
        }
        StoreError::MembershipNotFound => ApiError::not_found("Family member not found"),
        StoreError::DeviceNotFound => ApiError::not_found("Family device not found"),
        StoreError::RenameRequestNotFound => ApiError::not_found("Rename request not found"),
        StoreError::RenameRequestExpired => ApiError::gone("Rename request expired"),
        StoreError::RenameRequestStateConflict => {
            ApiError::conflict("Rename request is no longer pending")
        }
        other => other.into(),
    }
}

/// Owner hard-deletes another active **member** (not self, not the owner role).
/// The store atomically anonymizes shared facts and removes the identity tree.
pub(super) async fn remove_family_member(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<RemoveMemberRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers).await?;
    let request = json_body(body)?;
    let target_id = request.validate()?;
    if target_id == principal.membership_id {
        return Err(ApiError::forbidden(
            "Cannot remove yourself; delete the family to stop sharing as admin",
        ));
    }
    let store = state.store.clone();
    let family_id = principal.family_id;
    let blocking_target_id = target_id.clone();
    let now = state.now();
    run_blocking(move || {
        let memberships = store.active_memberships(&family_id)?;
        let target = memberships
            .iter()
            .find(|membership| membership.membership_id == blocking_target_id)
            .ok_or_else(|| ApiError::not_found("Member not found or already left"))?;
        if target.role == "owner" {
            return Err(ApiError::forbidden("Cannot remove the family admin"));
        }
        store.hard_delete_membership(&family_id, &blocking_target_id, now)?;
        Ok(())
    })
    .await?;
    Ok(Json(json!({
        "ok": true,
        "membership_id": target_id,
    })))
}

fn role_rank(role: &str) -> u8 {
    if role == "owner" {
        0
    } else {
        1
    }
}
