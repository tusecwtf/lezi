use std::collections::BTreeMap;
use std::sync::Arc;

use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use serde::Serialize;

use crate::model::normalize_display_name;
use crate::{authenticate, constant_time_eq, ApiError, AppState};

/// This device-local fallback is also defined once on the Android client.
/// It must never be projected to another family member.
pub(crate) const LOCAL_DEVICE_DISPLAY_NAME: &str = "我（本机）";

#[derive(Debug)]
struct MemberCandidate {
    device_id: String,
    display_name: Option<String>,
    role: String,
    is_self: bool,
}

#[derive(Debug, Serialize)]
struct MemberView {
    display_name: Option<String>,
    role: String,
    is_self: bool,
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
    let mut coalesced = BTreeMap::<(String, String), MemberCandidate>::new();
    for membership in memberships {
        let display_name = display_name_for_view(membership.display_name.as_deref());
        let is_self = constant_time_eq(
            membership.token_hash.as_bytes(),
            principal.token_hash.as_bytes(),
        );
        let key = (membership.role.clone(), membership.device_id.clone());
        coalesced
            .entry(key)
            .and_modify(|candidate| {
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
    });
    Ok(Json(MembersResponse {
        members: members
            .into_iter()
            .map(|member| MemberView {
                display_name: member.display_name,
                role: member.role,
                is_self: member.is_self,
            })
            .collect(),
    }))
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
