use std::sync::Arc;

use axum::extract::rejection::JsonRejection;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use serde::Serialize;
use serde_json::{json, Value};

use crate::model::{normalize_display_name, UpdateDisplayNameRequest, LOCAL_DEVICE_DISPLAY_NAME};
use crate::{authenticate, json_body, ApiError, AppState};

#[derive(Debug, Serialize)]
struct MemberView {
    display_name: Option<String>,
    role: String,
    is_self: bool,
    /// Client-only link key for mapping record `created_by_device_id` → 称呼.
    /// Never show this value in product UI (see sync-home-lan §9.5).
    device_id: String,
    /// Server-minted immutable membership identity. Safe public key for ACL.
    membership_id: String,
}

#[derive(Debug, Serialize)]
pub(super) struct MembersResponse {
    members: Vec<MemberView>,
}

pub(super) async fn list_family_members(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
) -> Result<Json<MembersResponse>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let memberships = state.store.active_memberships(&principal.family_id)?;

    // Legacy duplicate credentials are normalized once during migration.
    // Runtime projection is one row per canonical membership; device_id never
    // decides identity, so a new join that repeats a device claim stays distinct.
    let mut members = memberships
        .into_iter()
        .map(|membership| MemberView {
            display_name: display_name_for_view(membership.display_name.as_deref()),
            role: membership.role,
            is_self: membership.membership_id == principal.membership_id,
            device_id: membership.device_id,
            membership_id: membership.membership_id,
        })
        .collect::<Vec<_>>();
    members.sort_by(|left, right| {
        role_rank(&left.role)
            .cmp(&role_rank(&right.role))
            .then_with(|| {
                left.display_name
                    .is_none()
                    .cmp(&right.display_name.is_none())
            })
            .then_with(|| left.display_name.cmp(&right.display_name))
            .then_with(|| left.device_id.cmp(&right.device_id))
            .then_with(|| left.membership_id.cmp(&right.membership_id))
    });
    Ok(Json(MembersResponse { members }))
}

/// Self-only update of the caller's membership 家庭称呼.
pub(super) async fn update_my_display_name(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<UpdateDisplayNameRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let request = json_body(body)?;
    let display_name = request.validate()?;
    state
        .store
        .update_membership_display_name(&principal.membership_id, &display_name)?;
    Ok(Json(json!({
        "ok": true,
        "display_name": display_name,
    })))
}

/// Historical Android clients persisted their device-local fallback label as
/// a shared member name. It is meaningful only to the originating device, so
/// never project it to another family member. `is_self` lets each client apply
/// its own local fallback after the privacy-safe response is received.
fn display_name_for_view(value: Option<&str>) -> Option<String> {
    normalize_display_name(value)
        .ok()
        .flatten()
        .filter(|name| name != LOCAL_DEVICE_DISPLAY_NAME)
}

fn role_rank(role: &str) -> u8 {
    if role == "owner" {
        0
    } else {
        1
    }
}
