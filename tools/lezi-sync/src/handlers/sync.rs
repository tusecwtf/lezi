use std::collections::BTreeSet;
use std::sync::Arc;

use axum::extract::rejection::QueryRejection;
use axum::extract::{Query, State};
use axum::http::HeaderMap;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};

use super::media::media_entity_is_pullable;
use crate::store::StoreError;
use crate::{authenticate, require_supported_client, run_blocking, ApiError, AppState};

/// HTTP route entrypoint — `pub(crate)` so crate-root `build_apps` can bind via
/// domain path (`handlers::sync::…`). See `handlers` module docs for the rule.
pub(crate) async fn retired_ordinary_push() -> Result<Json<Value>, ApiError> {
    Err(ApiError::unprocessable(
        "ordinary push is retired; publish an atomic bundle",
    ))
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
