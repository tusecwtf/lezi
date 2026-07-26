use std::collections::BTreeMap;
use std::sync::Arc;

use axum::extract::rejection::JsonRejection;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use serde::Serialize;
use serde_json::{json, Value};

use crate::model::{
    normalize_display_name, UpdateDisplayNameRequest, LOCAL_DEVICE_DISPLAY_NAME,
};
use crate::{authenticate, constant_time_eq, json_body, ApiError, AppState};

#[derive(Debug)]
struct MemberCandidate {
    device_id: String,
    display_name: Option<String>,
    role: String,
    is_self: bool,
    membership_id: String,
}

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

    // Historical databases can contain more than one active token for one
    // device after repeated invitations. Coalesce rows with the same role and
    // device for presentation without treating the unauthenticated device_id
    // claim as authority or rotating another token. An owner/member collision
    // remains two rows so the view never promotes a member to owner.
    //
    // When duplicates carry distinct membership_ids, prefer the caller's own
    // membership_id when `is_self`, else the lexicographically smallest id so
    // the projected identity is deterministic across restarts.
    let mut coalesced = BTreeMap::<(String, String), MemberCandidate>::new();
    for membership in memberships {
        let display_name = display_name_for_view(membership.display_name.as_deref());
        let is_self = constant_time_eq(
            membership.token_hash.as_bytes(),
            principal.token_hash.as_bytes(),
        );
        // Self identity is always the authenticated principal's membership_id.
        let membership_id = if is_self {
            principal.membership_id.clone()
        } else {
            membership.membership_id
        };
        let key = (membership.role.clone(), membership.device_id.clone());
        coalesced
            .entry(key)
            .and_modify(|candidate| {
                // Prefer authenticated self membership_id; else keep lexicographically
                // smaller id for stable multi-session coalescing of the same device.
                if is_self || (!candidate.is_self && membership_id < candidate.membership_id) {
                    candidate.membership_id = membership_id.clone();
                }
                candidate.is_self |= is_self;
                if candidate.display_name.is_none() {
                    candidate.display_name = display_name.clone();
                }
            })
            .or_insert(MemberCandidate {
                device_id: membership.device_id,
                display_name,
                role: membership.role,
                is_self,
                membership_id,
            });
    }

    let mut members = coalesced.into_values().collect::<Vec<_>>();
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
    Ok(Json(MembersResponse {
        members: members
            .into_iter()
            .map(|member| MemberView {
                display_name: member.display_name,
                role: member.role,
                is_self: member.is_self,
                device_id: member.device_id,
                membership_id: member.membership_id,
            })
            .collect(),
    }))
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
        .update_membership_display_name(&principal.token_hash, &display_name)?;
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
