use std::sync::Arc;

use axum::extract::rejection::JsonRejection;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use serde::Serialize;
use serde_json::{json, Value};

use crate::model::{RemoveMemberRequest, UpdateDisplayNameRequest};
use crate::{authenticate, json_body, require_owner, ApiError, AppState};

#[derive(Debug, Serialize)]
struct MemberView {
    display_name: String,
    role: String,
    is_self: bool,
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

    // Runtime projection is one row per server-minted membership.
    let mut members = memberships
        .into_iter()
        .map(|membership| MemberView {
            display_name: membership.display_name,
            role: membership.role,
            is_self: membership.membership_id == principal.membership_id,
            membership_id: membership.membership_id,
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

/// Owner removes another active **member** (not self, not the owner role).
/// Reuses leave semantics: mark membership left + revoke all credentials.
/// Historical records keep their `created_by_membership_id`; only access ends.
pub(super) async fn remove_family_member(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<RemoveMemberRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers)?;
    let request = json_body(body)?;
    let target_id = request.validate()?;
    if target_id == principal.membership_id {
        return Err(ApiError::forbidden(
            "Cannot remove yourself; delete the family to stop sharing as admin",
        ));
    }
    let memberships = state.store.active_memberships(&principal.family_id)?;
    let target = memberships
        .iter()
        .find(|m| m.membership_id == target_id)
        .ok_or_else(|| ApiError::not_found("Member not found or already left"))?;
    if target.role == "owner" {
        // Single-owner product: never demote/remove the admin this way.
        return Err(ApiError::forbidden("Cannot remove the family admin"));
    }
    state
        .store
        .leave_membership(&target_id, state.now())?;
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
