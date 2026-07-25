use std::path::PathBuf;
use std::sync::Arc;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Deserialize;

use crate::db::{Store, StoreError};
use crate::models::{
    ErrorBody, HealthResponse, InviteRequest, InviteResponse, JoinRequest, JoinResponse,
    PullResponse, PushRequest, PushResponse,
};

pub struct AppState {
    pub store: Store,
    pub data_dir: PathBuf,
    pub version: String,
}

pub type SharedState = Arc<AppState>;

struct ApiError {
    status: StatusCode,
    message: String,
}

impl From<StoreError> for ApiError {
    fn from(e: StoreError) -> Self {
        match e {
            StoreError::FamilyRequired => ApiError {
                status: StatusCode::BAD_REQUEST,
                message: "family_id required".into(),
            },
            StoreError::InvalidInvite => ApiError {
                status: StatusCode::NOT_FOUND,
                message: "invalid code".into(),
            },
            StoreError::ExpiredInvite => ApiError {
                status: StatusCode::GONE,
                message: "expired".into(),
            },
            other => ApiError {
                status: StatusCode::INTERNAL_SERVER_ERROR,
                message: other.to_string(),
            },
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (
            self.status,
            Json(ErrorBody {
                error: self.message,
            }),
        )
            .into_response()
    }
}

pub async fn health(State(state): State<SharedState>) -> Json<HealthResponse> {
    Json(HealthResponse {
        ok: true,
        version: state.version.clone(),
        data_dir: state.data_dir.display().to_string(),
    })
}

pub async fn push(
    State(state): State<SharedState>,
    Json(body): Json<PushRequest>,
) -> Result<Json<PushResponse>, ApiError> {
    let applied = state.store.push(&body.family_id, &body.entities)?;
    Ok(Json(PushResponse { applied }))
}

#[derive(Debug, Deserialize)]
pub struct PullQuery {
    family_id: Option<String>,
    cursor: Option<i64>,
}

pub async fn pull(
    State(state): State<SharedState>,
    Query(q): Query<PullQuery>,
) -> Result<Json<PullResponse>, ApiError> {
    let family_id = q.family_id.unwrap_or_default();
    if family_id.is_empty() {
        return Err(StoreError::FamilyRequired.into());
    }
    let cursor = q.cursor.unwrap_or(0);
    let (entities, cursor) = state.store.pull(&family_id, cursor)?;
    Ok(Json(PullResponse { entities, cursor }))
}

pub async fn invite(
    State(state): State<SharedState>,
    Json(body): Json<InviteRequest>,
) -> Result<Json<InviteResponse>, ApiError> {
    let (code, expires_at) = state.store.create_invite(&body.family_id)?;
    Ok(Json(InviteResponse { code, expires_at }))
}

pub async fn join(
    State(state): State<SharedState>,
    Json(body): Json<JoinRequest>,
) -> Result<Json<JoinResponse>, ApiError> {
    let (family_id, entities, cursor) = state.store.join(&body.code)?;
    Ok(Json(JoinResponse {
        family_id,
        entities,
        cursor,
    }))
}
