mod members;
mod model;
mod rate_limit;
mod readiness;
mod store;

use std::collections::{BTreeSet, HashMap};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
#[cfg(unix)]
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::body::Body;
use axum::extract::rejection::{JsonRejection, QueryRejection};
use axum::extract::{DefaultBodyLimit, Path as AxumPath, Query, Request, State};
use axum::http::header::{AUTHORIZATION, CONTENT_LENGTH, CONTENT_TYPE, WWW_AUTHENTICATE};
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use futures_util::StreamExt;
use hmac::{Hmac, Mac};
use members::{list_family_members, remove_family_member, update_my_display_name};
use model::{
    BundleCommitRequest, BundleStageRequest, EmptyRequest, FamilyCreateRequest, InviteRequest,
    JoinRequest, PushRequest, RenameFamilyRequest,
};
use rand::distributions::{Distribution, Uniform};
use rand::rngs::OsRng;
use rand::RngCore;
pub use rate_limit::RateLimitConfig;
use rate_limit::RateLimiter;
use readiness::{readiness, CachedReadiness};
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use store::{CommittedPendingBundleMedia, Principal, Store, StoreError};
use tokio::sync::Mutex;
use tower_http::trace::TraceLayer;
use uuid::Uuid;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
pub const DEFAULT_INVITE_TTL_HOURS: u16 = 24;
pub const DEFAULT_MAX_MEDIA_BYTES: usize = 10 * 1024 * 1024;
pub const DEFAULT_CREATE_RATE_LIMIT: u32 = 20;
pub const DEFAULT_JOIN_RATE_LIMIT: u32 = 60;
pub const DEFAULT_RATE_LIMIT_WINDOW_SECONDS: i64 = 60;
/// Advertised on `/health` so clients can refuse metadata-first fallbacks.
pub const CAPABILITY_ATOMIC_BUNDLE: &str = "atomic_bundle";
/// Advertised on `/health` so clients only send the additive server-owned author field
/// to servers that accept and canonicalize it.
pub const CAPABILITY_RECORD_MEMBERSHIP_AUTHOR: &str = "record_membership_author";
const MAX_ENTITY_FUTURE_SKEW_MILLIS: i64 = 24 * 60 * 60 * 1_000;
pub(crate) const PULL_PAGE_ENTITY_LIMIT: usize = 200;
pub(crate) const PULL_PAGE_TARGET_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const PULL_ENTITY_TARGET_BYTES: usize = PULL_PAGE_TARGET_BYTES / 3;
const BOOTSTRAP_SECRET_HEADER: HeaderName = HeaderName::from_static("x-lezi-bootstrap-secret");

#[cfg(unix)]
static PERMISSION_HARDENING_DISABLED: AtomicBool = AtomicBool::new(false);

type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;
type InviteCodeFactory = Arc<dyn Fn() -> String + Send + Sync>;

#[derive(Clone)]
pub struct ServerConfig {
    pub data_dir: PathBuf,
    pub version: String,
    pub max_media_bytes: usize,
    pub invite_ttl_hours: u16,
    pub server_secret: Option<Vec<u8>>,
    pub generation: Option<String>,
    /// When set (non-empty), POST /v1/family/create requires matching
    /// `X-Lezi-Bootstrap-Secret`. Empty/None keeps local development open;
    /// production docs require setting this fail-closed.
    pub bootstrap_secret: Option<String>,
    pub create_rate_limit: RateLimitConfig,
    pub join_rate_limit: RateLimitConfig,
    clock: Clock,
    invite_code_factory: InviteCodeFactory,
}

impl ServerConfig {
    pub fn new(data_dir: impl Into<PathBuf>) -> Self {
        Self {
            data_dir: data_dir.into(),
            version: VERSION.to_owned(),
            max_media_bytes: DEFAULT_MAX_MEDIA_BYTES,
            invite_ttl_hours: DEFAULT_INVITE_TTL_HOURS,
            server_secret: None,
            generation: None,
            bootstrap_secret: None,
            create_rate_limit: RateLimitConfig {
                max_attempts: DEFAULT_CREATE_RATE_LIMIT,
                window_seconds: DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
            },
            join_rate_limit: RateLimitConfig {
                max_attempts: DEFAULT_JOIN_RATE_LIMIT,
                window_seconds: DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
            },
            clock: Arc::new(system_epoch_seconds),
            invite_code_factory: Arc::new(secure_invite_code),
        }
    }

    pub fn from_env() -> Result<Self, String> {
        let mut config = Self::new(
            std::env::var_os("LEZI_DATA_DIR")
                .map(PathBuf::from)
                .unwrap_or_else(|| PathBuf::from("/data")),
        );
        config.version = std::env::var("LEZI_SYNC_VERSION").unwrap_or_else(|_| VERSION.to_owned());
        config.max_media_bytes = parse_env("LEZI_MAX_MEDIA_BYTES", DEFAULT_MAX_MEDIA_BYTES)?;
        config.invite_ttl_hours = parse_env("LEZI_INVITE_TTL_HOURS", DEFAULT_INVITE_TTL_HOURS)?;
        config.bootstrap_secret = match std::env::var("LEZI_BOOTSTRAP_SECRET") {
            Ok(value) if !value.is_empty() => Some(value),
            Ok(_) | Err(std::env::VarError::NotPresent) => None,
            Err(error) => return Err(format!("LEZI_BOOTSTRAP_SECRET: {error}")),
        };
        config.create_rate_limit.max_attempts =
            parse_env("LEZI_CREATE_RATE_LIMIT", DEFAULT_CREATE_RATE_LIMIT)?;
        config.join_rate_limit.max_attempts =
            parse_env("LEZI_JOIN_RATE_LIMIT", DEFAULT_JOIN_RATE_LIMIT)?;
        let window = parse_env(
            "LEZI_RATE_LIMIT_WINDOW_SECONDS",
            DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
        )?;
        config.create_rate_limit.window_seconds = window;
        config.join_rate_limit.window_seconds = window;
        config.validate()?;
        Ok(config)
    }

    pub fn with_clock(mut self, clock: impl Fn() -> i64 + Send + Sync + 'static) -> Self {
        self.clock = Arc::new(clock);
        self
    }

    pub fn with_invite_code_factory(
        mut self,
        factory: impl Fn() -> String + Send + Sync + 'static,
    ) -> Self {
        self.invite_code_factory = Arc::new(factory);
        self
    }

    fn validate(&self) -> Result<(), String> {
        if !(1..=168).contains(&self.invite_ttl_hours) {
            return Err("LEZI_INVITE_TTL_HOURS must be between 1 and 168".to_owned());
        }
        if self.max_media_bytes == 0 {
            return Err("LEZI_MAX_MEDIA_BYTES must be greater than zero".to_owned());
        }
        if self.create_rate_limit.max_attempts == 0 || self.join_rate_limit.max_attempts == 0 {
            return Err("rate limit max_attempts must be greater than zero".to_owned());
        }
        if self.create_rate_limit.window_seconds <= 0 || self.join_rate_limit.window_seconds <= 0 {
            return Err("LEZI_RATE_LIMIT_WINDOW_SECONDS must be greater than zero".to_owned());
        }
        if self
            .bootstrap_secret
            .as_ref()
            .is_some_and(|secret| secret.len() < 16)
        {
            return Err("LEZI_BOOTSTRAP_SECRET must be at least 16 characters when set".to_owned());
        }
        Ok(())
    }
}

#[derive(Clone)]
struct AppState {
    store: Store,
    data_root: PathBuf,
    media_root: PathBuf,
    version: String,
    max_media_bytes: usize,
    invite_ttl_seconds: i64,
    signing_secret: Arc<Vec<u8>>,
    generation: String,
    clock: Clock,
    invite_code_factory: InviteCodeFactory,
    family_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    bootstrap_secret: Option<Arc<str>>,
    create_limiter: Arc<RateLimiter>,
    join_limiter: Arc<RateLimiter>,
    readiness_cache: Arc<Mutex<Option<CachedReadiness>>>,
}

impl AppState {
    async fn family_lock(&self, family_id: &str) -> Arc<Mutex<()>> {
        let mut locks = self.family_locks.lock().await;
        locks
            .entry(family_id.to_owned())
            .or_insert_with(|| Arc::new(Mutex::new(())))
            .clone()
    }

    fn now(&self) -> i64 {
        (self.clock)()
    }

    fn owner_token(&self, create_request_hash: &str, family_id: &str) -> String {
        derive_token(
            &self.signing_secret,
            &format!("owner:{create_request_hash}:{family_id}"),
        )
    }

    fn member_token(&self, code_hash: &str, device_id: &str) -> String {
        derive_token(
            &self.signing_secret,
            &format!("join:{code_hash}:{device_id}"),
        )
    }

    fn recovery_detail(&self, family_id: &str, code: &str) -> Result<Value, ApiError> {
        Ok(json!({
            "code": code,
            "action": "full_resync",
            "reset_cursor": 0,
            "server_cursor": self.store.current_revision(family_id)?,
            "server_generation": self.generation,
        }))
    }

    fn media_path(&self, family_id: &str, client_uuid: Uuid) -> Result<PathBuf, ApiError> {
        let family_id = Uuid::parse_str(family_id)
            .map_err(|_| ApiError::internal("stored family id is invalid"))?
            .to_string();
        Ok(self
            .media_root
            .join(family_id)
            .join(client_uuid.to_string()))
    }

    fn safe_family_id(&self, family_id: &str) -> Result<String, ApiError> {
        Uuid::parse_str(family_id)
            .map(|id| id.to_string())
            .map_err(|_| ApiError::internal("stored family id is invalid"))
    }

    fn bundle_stage_dir(&self, family_id: &str, bundle_id: &Uuid) -> Result<PathBuf, ApiError> {
        let family_id = self.safe_family_id(family_id)?;
        Ok(self
            .media_root
            .join(family_id)
            .join(".stage")
            .join(bundle_id.to_string()))
    }

    fn bundle_media_path(
        &self,
        family_id: &str,
        bundle_id: &Uuid,
        media_uuid: &Uuid,
    ) -> Result<PathBuf, ApiError> {
        Ok(self
            .bundle_stage_dir(family_id, bundle_id)?
            .join(media_uuid.to_string()))
    }
}

pub fn build_app(config: ServerConfig) -> Result<Router, ApiError> {
    config.validate().map_err(ApiError::internal)?;
    let database_path = config.data_dir.join("lezi.db");
    Store::preflight_existing_schema(&database_path)?;
    set_private_umask();
    fs::create_dir_all(&config.data_dir)?;
    secure_directory(&config.data_dir)?;
    let media_root = config.data_dir.join("media");
    fs::create_dir_all(&media_root)?;
    secure_directory(&media_root)?;
    let signing_secret = match config.server_secret {
        Some(secret) if secret.len() >= 32 => secret,
        Some(_) => {
            return Err(ApiError::internal(
                "server secret must be at least 32 bytes",
            ))
        }
        None => load_or_create_server_secret(&config.data_dir)?,
    };
    let bootstrap_secret = config.bootstrap_secret.filter(|value| !value.is_empty());
    if bootstrap_secret.is_none() {
        tracing::warn!(
            "LEZI_BOOTSTRAP_SECRET is unset; POST /v1/family/create is open to the LAN until a family exists (set a secret for production)"
        );
    }
    let store = Store::open(database_path)?;
    collect_orphan_family_media(&store, &media_root)?;
    retry_committed_pending_bundle_media_cleanup(&store, &media_root)?;
    let state = AppState {
        store,
        data_root: config.data_dir,
        media_root,
        version: config.version,
        max_media_bytes: config.max_media_bytes,
        invite_ttl_seconds: i64::from(config.invite_ttl_hours) * 60 * 60,
        signing_secret: Arc::new(signing_secret),
        generation: config.generation.unwrap_or_else(secure_generation),
        clock: config.clock,
        invite_code_factory: config.invite_code_factory,
        family_locks: Arc::new(Mutex::new(HashMap::new())),
        bootstrap_secret: bootstrap_secret.map(|value| Arc::from(value.into_boxed_str())),
        create_limiter: Arc::new(RateLimiter::new(config.create_rate_limit)),
        join_limiter: Arc::new(RateLimiter::new(config.join_rate_limit)),
        readiness_cache: Arc::new(Mutex::new(None)),
    };
    let body_limit = state.max_media_bytes.max(16 * 1024 * 1024);
    Ok(Router::new()
        .route("/health", get(health))
        .route("/ready", get(readiness))
        .route("/v1/family/create", post(create_family))
        .route("/v1/family/members", get(list_family_members))
        .route("/v1/family/members/remove", post(remove_family_member))
        .route("/v1/family/display-name", post(update_my_display_name))
        .route("/v1/family/name", post(rename_family))
        .route("/v1/invite", post(create_invite))
        .route("/v1/join", post(join))
        .route("/v1/leave", post(leave))
        .route("/v1/family/delete", post(delete_family))
        .route("/v1/push", post(push_entities))
        .route("/v1/pull", get(pull_entities))
        .route("/v1/media/{client_uuid}", put(put_media).get(get_media))
        .route("/v1/bundles", post(stage_bundle))
        .route("/v1/bundles/{bundle_id}", get(get_bundle))
        .route(
            "/v1/bundles/{bundle_id}/media/{client_uuid}",
            put(put_bundle_media),
        )
        .route("/v1/bundles/{bundle_id}/commit", post(commit_bundle))
        .layer(DefaultBodyLimit::max(body_limit))
        .layer(TraceLayer::new_for_http())
        .with_state(Arc::new(state)))
}

async fn health(State(state): State<Arc<AppState>>) -> Json<Value> {
    // Additive capabilities keep the body small so client health probes
    // (64 KiB bound) and redirect rejection assumptions stay valid.
    Json(json!({
        "ok": true,
        "version": state.version,
        "capabilities": [
            CAPABILITY_ATOMIC_BUNDLE,
            CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
        ],
    }))
}

async fn create_family(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<FamilyCreateRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    // Invalid bootstrap credentials and malformed requests must not consume the
    // allowance reserved for callers that can actually create a family.
    require_bootstrap_secret(&state, &headers)?;
    let request = json_body(body)?;
    let (display_name, family_name) = request.validate()?;
    let scope = format!("device:{}", hash_secret(&request.device_id));
    if !state.create_limiter.check_and_record(&scope, state.now()) {
        return Err(ApiError::too_many_requests(
            "Too many family create attempts; try again later",
        ));
    }
    let signing_state = state.clone();
    let result = state.store.create_family(
        state.now(),
        &request.create_request_id,
        &request.device_id,
        &display_name,
        family_name.as_deref(),
        move |request_hash, family_id| signing_state.owner_token(request_hash, family_id),
    );
    let (family_id, token, membership_id, stored_family_name, reclaimed) = match result {
        Ok(value) => value,
        Err(StoreError::FamilyAlreadyExists) => {
            // Only reached when the same create_request_id is reused with a
            // conflicting device_id / display_name — not for a second family.
            return Err(ApiError::conflict("Family already exists"))
        }
        Err(error) => return Err(error.into()),
    };
    Ok((
        StatusCode::CREATED,
        Json(json!({
            "family_id": family_id,
            "token": token,
            "role": "owner",
            "membership_id": membership_id,
            "generation": state.generation,
            "family_name": stored_family_name,
            "reclaimed": reclaimed,
        })),
    ))
}

/// Owner-only rename of the shared family name.
///
/// Body: `{"family_name": "…"}` — blank/null clears the name (client applies
/// fallback). Response: `{"ok": true, "family_name": …}`.
async fn rename_family(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<RenameFamilyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers)?;
    let request = json_body(body)?;
    let family_name = request.validate()?;
    state
        .store
        .rename_family(&principal.family_id, family_name.as_deref())?;
    Ok(Json(json!({
        "ok": true,
        "family_name": family_name,
    })))
}

async fn create_invite(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<InviteRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    let principal = require_owner(&state, &headers)?;
    let _ = json_body(body)?;
    let code = (state.invite_code_factory)();
    if !(8..=32).contains(&code.len())
        || !code
            .bytes()
            .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit())
    {
        return Err(ApiError::internal(
            "invite code factory returned an invalid code",
        ));
    }
    let expires_at = state.store.create_invite(
        &principal.family_id,
        state.now(),
        state.invite_ttl_seconds,
        &code,
    )?;
    Ok((
        StatusCode::CREATED,
        Json(json!({"code": code, "expires_at": expires_at})),
    ))
}

async fn join(
    State(state): State<Arc<AppState>>,
    body: Result<Json<JoinRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let request = json_body(body)?;
    let display_name = request.validate()?;
    let scope = format!("invite:{}", hash_secret(&request.code));
    if !state.join_limiter.check_and_record(&scope, state.now()) {
        return Err(ApiError::too_many_requests(
            "Too many join attempts; try again later",
        ));
    }
    let signing_state = state.clone();
    let result = state.store.join_family(
        &request.code,
        &request.device_id,
        &display_name,
        state.now(),
        move |code_hash, device_id| signing_state.member_token(code_hash, device_id),
    );
    let (family_id, token, membership_id, family_name) = match result {
        Ok(value) => value,
        Err(StoreError::InviteNotFound) => return Err(ApiError::not_found("Invitation not found")),
        Err(StoreError::InviteExpired) => return Err(ApiError::gone("Invitation expired")),
        Err(StoreError::InviteAlreadyUsed) => {
            return Err(ApiError::conflict(
                "Invitation already used by another device",
            ))
        }
        Err(error) => return Err(error.into()),
    };
    Ok(Json(json!({
        "family_id": family_id,
        "token": token,
        "role": "member",
        "membership_id": membership_id,
        "entities": [],
        "cursor": 0,
        "generation": state.generation,
        "family_name": family_name,
    })))
}

async fn leave(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<EmptyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let _ = json_body(body)?;
    if principal.role == "owner" {
        return Err(ApiError::forbidden(
            "Owner must delete the family instead of leaving",
        ));
    }
    state
        .store
        .leave_membership(&principal.membership_id, state.now())?;
    Ok(Json(json!({"ok": true})))
}

async fn delete_family(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<EmptyRequest>, JsonRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = require_owner(&state, &headers)?;
    let _ = json_body(body)?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let family_media = state.media_root.join(&principal.family_id);
    state.store.delete_family(&principal.family_id)?;
    if family_media.exists() {
        match fs::remove_dir_all(&family_media) {
            Ok(()) => {
                if let Err(error) = sync_directory(&state.media_root) {
                    tracing::error!(
                        family_id = %principal.family_id,
                        path = %state.media_root.display(),
                        %error,
                        "family metadata was deleted; media-root sync will be retried by startup cleanup"
                    );
                }
            }
            Err(error) => {
                tracing::error!(
                    family_id = %principal.family_id,
                    path = %family_media.display(),
                    %error,
                    "family metadata was deleted; orphan media cleanup will retry on startup"
                );
            }
        }
    }
    Ok(Json(json!({"ok": true})))
}

async fn push_entities(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<PushRequest>, JsonRejection>,
) -> Result<Json<store::PushResult>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let request = json_body(body)?.validate(state.max_media_bytes)?;
    if request.device_id != principal.device_id {
        return Err(ApiError::forbidden("device_id does not match token"));
    }
    if request.generation != state.generation {
        return Err(ApiError::conflict_value(
            state.recovery_detail(&principal.family_id, "generation_changed")?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let max_updated_at = state
        .now()
        .saturating_mul(1_000)
        .saturating_add(MAX_ENTITY_FUTURE_SKEW_MILLIS);
    let result = match state
        .store
        .push(&principal, request.entities, max_updated_at, state.now())
    {
        Ok(value) => value,
        Err(StoreError::ForbiddenBaby) => {
            return Err(ApiError::forbidden("Only owner may manage baby profiles"))
        }
        Err(StoreError::ForbiddenAvatar) => {
            return Err(ApiError::forbidden("Only owner may change avatar"))
        }
        Err(StoreError::ForbiddenCustomItem) => {
            return Err(ApiError::forbidden(
                "Only the creator or family owner may change this custom item",
            ))
        }
        Err(StoreError::ForbiddenCarePlan) => {
            return Err(ApiError::forbidden(
                "Only the creator or family owner may change this care plan",
            ))
        }
        Err(StoreError::CustomItemTombstoneResurrection) => {
            return Err(ApiError::conflict(
                "Deleted custom item cannot be resurrected",
            ))
        }
        Err(StoreError::CarePlanTombstoneResurrection) => {
            return Err(ApiError::conflict(
                "Deleted care plan cannot be resurrected",
            ))
        }
        Err(StoreError::ImmutableMediaAssociation) => {
            return Err(ApiError::conflict(
                "Media kind and association are immutable",
            ))
        }
        Err(StoreError::TimestampOutOfRange) => {
            return Err(ApiError::unprocessable(
                "updated_at is outside the accepted server time window",
            ))
        }
        Err(StoreError::PullEntityTooLarge) => {
            return Err(ApiError::unprocessable(
                "entity payload is too large for bounded sync pull",
            ))
        }
        Err(StoreError::UnresolvedReference(message)) => return Err(ApiError::conflict(message)),
        Err(error) => return Err(error.into()),
    };
    Ok(Json(result))
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct PullQuery {
    cursor: i64,
    generation: String,
}

async fn pull_entities(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    query: Result<Query<PullQuery>, QueryRejection>,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let query = query
        .map(|Query(value)| value)
        .map_err(|error| ApiError::unprocessable(error.body_text()))?;
    if query.cursor < 0 {
        return Err(ApiError::unprocessable("cursor must be non-negative"));
    }
    if query.generation != state.generation {
        return Err(ApiError::conflict_value(
            state.recovery_detail(&principal.family_id, "generation_changed")?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let page = match state.store.pull(&principal.family_id, query.cursor) {
        Ok(result) => result,
        Err(StoreError::CursorAhead(server_cursor)) => {
            return Err(ApiError::conflict_value(json!({
                "code": "cursor_ahead",
                "action": "full_resync",
                "reset_cursor": 0,
                "server_cursor": server_cursor,
                "server_generation": state.generation,
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
    let published_media = state
        .store
        .published_media(&principal.family_id, &media_ids)?;
    let entities = page
        .entities
        .into_iter()
        .filter(|entity| {
            media_entity_is_pullable(
                state.as_ref(),
                &principal.family_id,
                entity,
                &published_media,
            )
        })
        .collect::<Vec<_>>();
    Ok(Json(json!({
        "entities": entities,
        "cursor": page.cursor,
        "generation": state.generation,
        "has_more": page.has_more,
        "family_name": page.family_name,
    })))
}

async fn put_media(
    State(state): State<Arc<AppState>>,
    AxumPath(client_uuid): AxumPath<Uuid>,
    request: Request,
) -> Result<Json<Value>, ApiError> {
    let principal = authenticate(&state, request.headers())?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let metadata = state
        .store
        .media_metadata(&principal.family_id, &client_uuid.to_string())?
        .ok_or_else(|| ApiError::not_found("Media metadata not found"))?;
    if state
        .store
        .is_media_bundle_owned(&principal.family_id, &client_uuid.to_string())?
    {
        return Err(ApiError::conflict(
            "Atomic bundle media cannot be replaced via ordinary upload",
        ));
    }
    if metadata.kind == "avatar" && principal.role != "owner" {
        return Err(ApiError::forbidden("Only owner may change avatar"));
    }
    if let Some(length) = request.headers().get(CONTENT_LENGTH) {
        let length = length
            .to_str()
            .ok()
            .and_then(|value| value.parse::<usize>().ok())
            .ok_or_else(|| ApiError::bad_request("Invalid Content-Length"))?;
        if length > state.max_media_bytes {
            return Err(ApiError::payload_too_large("Media is too large"));
        }
    }

    let mut content = Vec::new();
    let mut stream = request.into_body().into_data_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| ApiError::bad_request(error.to_string()))?;
        if content.len() + chunk.len() > state.max_media_bytes {
            return Err(ApiError::payload_too_large("Media is too large"));
        }
        content.extend_from_slice(&chunk);
    }
    if content.is_empty() {
        return Err(ApiError::unprocessable("Media body must not be empty"));
    }
    if metadata
        .byte_size
        .is_some_and(|declared_size| declared_size != content.len())
    {
        return Err(ApiError::unprocessable(
            "Media body size does not match declared byte_size",
        ));
    }
    let path = state.media_path(&principal.family_id, client_uuid)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
        secure_directory(parent)?;
    }
    write_private_file(&path, &content)?;
    // Metadata may already have been pulled and skipped. Republish after every
    // durable PUT so retrying a request also heals a process failure between
    // the file replacement and this database transaction.
    let republished = state
        .store
        .publish_ordinary_media(&principal.family_id, &client_uuid.to_string())?;
    if !republished {
        return Err(ApiError::conflict(
            "Atomic bundle media cannot be replaced via ordinary upload",
        ));
    }
    Ok(Json(json!({"ok": true, "size": content.len()})))
}

async fn get_media(
    State(state): State<Arc<AppState>>,
    AxumPath(client_uuid): AxumPath<Uuid>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let metadata = state
        .store
        .media_metadata(&principal.family_id, &client_uuid.to_string())?
        .ok_or_else(|| ApiError::not_found("Media metadata not found"))?;
    if !state
        .store
        .is_media_published(&principal.family_id, &client_uuid.to_string())?
    {
        return Err(ApiError::not_found("Media bytes are not published"));
    }
    let path = state.media_path(&principal.family_id, client_uuid)?;
    if !media_file_is_ready(&path, metadata.byte_size, &client_uuid.to_string()) {
        return Err(ApiError::not_found("Media bytes incomplete or invalid"));
    }
    let bytes = fs::read(path)?;
    let mut response = Response::new(Body::from(bytes));
    response.headers_mut().insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    Ok(response)
}

async fn stage_bundle(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<BundleStageRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let request = json_body(body)?.validate(state.max_media_bytes)?;
    if request.generation != state.generation {
        return Err(ApiError::conflict_value(
            state.recovery_detail(&principal.family_id, "generation_changed")?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let status = match state.store.stage_bundle(
        &principal,
        &request.bundle_id,
        request.root,
        request.media,
        state.now(),
    ) {
        Ok(value) => value,
        Err(StoreError::ForbiddenAvatar) => {
            return Err(ApiError::forbidden("Only owner may change avatar"))
        }
        Err(StoreError::ForbiddenCarePlan) => {
            return Err(ApiError::forbidden(
                "Only the creator or family owner may change this care plan",
            ))
        }
        Err(StoreError::CarePlanTombstoneResurrection) => {
            return Err(ApiError::conflict(
                "Deleted care plan cannot be resurrected",
            ))
        }
        Err(StoreError::ImmutableMediaAssociation) => {
            return Err(ApiError::conflict(
                "Media kind and association are immutable",
            ))
        }
        Err(StoreError::BundleContentConflict) => {
            return Err(ApiError::conflict(
                "bundle_id already committed with different content",
            ))
        }
        Err(StoreError::BundleMembershipMismatch) => {
            return Err(ApiError::conflict(
                "bundle belongs to another family membership",
            ))
        }
        Err(StoreError::BundleStagingLimit) => {
            return Err(ApiError::unprocessable(
                "too many open staging bundles; commit or wait for cleanup",
            ))
        }
        Err(StoreError::UnresolvedReference(message)) => return Err(ApiError::conflict(message)),
        Err(StoreError::PullEntityTooLarge) => {
            return Err(ApiError::unprocessable(
                "entity payload is too large for bounded sync pull",
            ))
        }
        Err(error) => return Err(error.into()),
    };
    Ok(Json(status))
}

async fn get_bundle(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(bundle_id): AxumPath<Uuid>,
) -> Result<Json<store::BundleStageStatus>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    state
        .store
        .bundle_status(&principal.family_id, &bundle_id.to_string())?
        .map(Json)
        .ok_or_else(|| ApiError::not_found("Bundle not found"))
}

async fn put_bundle_media(
    State(state): State<Arc<AppState>>,
    AxumPath((bundle_id, client_uuid)): AxumPath<(Uuid, Uuid)>,
    request: Request,
) -> Result<Json<store::BundleStageStatus>, ApiError> {
    let principal = authenticate(&state, request.headers())?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let bundle = state
        .store
        .load_bundle(&principal.family_id, &bundle_id.to_string())?
        .ok_or_else(|| ApiError::not_found("Bundle not found"))?;
    if bundle.status != "staging" {
        return Err(ApiError::conflict(
            "committed bundle does not accept staged media",
        ));
    }
    if bundle.staged_membership_id != principal.membership_id {
        return Err(ApiError::conflict(
            "bundle belongs to another family membership",
        ));
    }
    if !bundle
        .media
        .iter()
        .any(|entity| entity.client_uuid == client_uuid.to_string())
    {
        return Err(ApiError::unprocessable(
            "media is not listed in the bundle manifest",
        ));
    }
    let media_entity = bundle
        .media
        .iter()
        .find(|entity| entity.client_uuid == client_uuid.to_string())
        .expect("checked above");
    if media_entity.deleted_at.is_some() {
        return Err(ApiError::unprocessable(
            "tombstone media does not accept bytes",
        ));
    }
    if let Some(kind) = media_entity.payload.get("kind").and_then(|v| v.as_str()) {
        if kind == "avatar" && principal.role != "owner" {
            return Err(ApiError::forbidden("Only owner may change avatar"));
        }
    }
    if let Some(length) = request.headers().get(CONTENT_LENGTH) {
        let length = length
            .to_str()
            .ok()
            .and_then(|value| value.parse::<usize>().ok())
            .ok_or_else(|| ApiError::bad_request("Invalid Content-Length"))?;
        if length > state.max_media_bytes {
            return Err(ApiError::payload_too_large("Media is too large"));
        }
    }

    let mut content = Vec::new();
    let mut stream = request.into_body().into_data_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|error| ApiError::bad_request(error.to_string()))?;
        if content.len() + chunk.len() > state.max_media_bytes {
            return Err(ApiError::payload_too_large("Media is too large"));
        }
        content.extend_from_slice(&chunk);
    }
    if content.is_empty() {
        return Err(ApiError::unprocessable("Media body must not be empty"));
    }
    if let Some(declared) = media_entity
        .payload
        .get("byte_size")
        .and_then(|value| value.as_u64())
        .and_then(|size| usize::try_from(size).ok())
    {
        if declared != content.len() {
            return Err(ApiError::unprocessable(
                "Media body size does not match declared byte_size",
            ));
        }
    }

    let path = state.bundle_media_path(&principal.family_id, &bundle_id, &client_uuid)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
        secure_directory(parent)?;
    }
    write_private_file(&path, &content)?;
    let staged_sha256 = hex::encode(Sha256::digest(&content));

    let status = match state.store.mark_bundle_media_staged(
        &principal,
        &bundle_id.to_string(),
        &client_uuid.to_string(),
        content.len(),
        &staged_sha256,
        state.now(),
    ) {
        Ok(value) => value,
        Err(StoreError::BundleMediaNotInManifest) => {
            return Err(ApiError::unprocessable(
                "media is not listed in the bundle manifest",
            ))
        }
        Err(StoreError::BundleMediaIncomplete) => {
            return Err(ApiError::unprocessable(
                "Media body size does not match declared byte_size",
            ))
        }
        Err(StoreError::BundleMediaUploadClosed) => {
            return Err(ApiError::conflict(
                "committed bundle does not accept staged media",
            ))
        }
        Err(StoreError::BundleMembershipMismatch) => {
            return Err(ApiError::conflict(
                "bundle belongs to another family membership",
            ))
        }
        Err(StoreError::BundleNotFound) => return Err(ApiError::not_found("Bundle not found")),
        Err(error) => return Err(error.into()),
    };
    Ok(Json(status))
}

async fn commit_bundle(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(bundle_id): AxumPath<Uuid>,
    body: Result<Json<BundleCommitRequest>, JsonRejection>,
) -> Result<Json<store::BundleCommitResult>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let request = json_body(body)?;
    request.validate()?;
    if request.generation != state.generation {
        return Err(ApiError::conflict_value(
            state.recovery_detail(&principal.family_id, "generation_changed")?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let bundle = state
        .store
        .load_bundle(&principal.family_id, &bundle_id.to_string())?
        .ok_or_else(|| ApiError::not_found("Bundle not found"))?;

    // Preflight authenticated ownership before inspecting or changing any
    // final-path media. Store::commit_bundle repeats this check as the
    // transactional authority; this early guard prevents rejected principals
    // from leaving claimable filesystem state.
    if bundle.staged_membership_id != principal.membership_id {
        return Err(ApiError::conflict(
            "bundle belongs to another family membership",
        ));
    }

    let mut media_ready = std::collections::BTreeMap::new();
    let mut staged_publications = Vec::new();
    for media_uuid in &bundle.required_media {
        let media_id = Uuid::parse_str(media_uuid)
            .map_err(|_| ApiError::internal("stored media uuid is invalid"))?;
        let integrity = bundle
            .media_integrity
            .get(media_uuid)
            .ok_or_else(|| ApiError::internal("stored bundle media integrity is missing"))?;
        if bundle.status == "committed" {
            let final_path = state.media_path(&principal.family_id, media_id)?;
            let digest = integrity
                .staged_sha256
                .as_deref()
                .and_then(|expected_sha256| {
                    media_file_integrity_sha256(
                        &final_path,
                        integrity.declared_byte_size,
                        Some(expected_sha256),
                        media_uuid,
                    )
                });
            if digest.is_some() {
                sync_published_media_file(&final_path)?;
            }
            media_ready.insert(media_uuid.clone(), digest.is_some());
            continue;
        }
        let staged_path = state.bundle_media_path(&principal.family_id, &bundle_id, &media_id)?;
        let digest = integrity
            .staged_sha256
            .as_deref()
            .and_then(|expected_sha256| {
                media_file_integrity_sha256(
                    &staged_path,
                    integrity.declared_byte_size,
                    Some(expected_sha256),
                    media_uuid,
                )
            });
        if digest.is_some() {
            staged_publications.push((media_uuid.clone(), media_id, staged_path));
        }
        media_ready.insert(media_uuid.clone(), digest.is_some());
    }

    // Validate the complete staging manifest before the first final-path
    // change. Incomplete/corrupt later entries must not leave earlier files
    // pre-published. The Store still returns the canonical 422 below.
    if bundle.status == "staging" && media_ready.values().all(|ready| *ready) {
        for (media_uuid, media_id, staged_path) in staged_publications {
            let final_path = state.media_path(&principal.family_id, media_id)?;
            prepare_published_media_file(&staged_path, &final_path)?;
            match state.store.mark_bundle_media_prepared(
                &principal,
                &bundle_id.to_string(),
                &media_uuid,
            ) {
                Ok(()) => {}
                Err(StoreError::BundleMembershipMismatch) => {
                    return Err(ApiError::conflict(
                        "bundle belongs to another family membership",
                    ))
                }
                Err(StoreError::BundleNotFound) => {
                    return Err(ApiError::not_found("Bundle not found"))
                }
                Err(error) => return Err(error.into()),
            }
        }
    }

    let max_updated_at = state
        .now()
        .saturating_mul(1_000)
        .saturating_add(MAX_ENTITY_FUTURE_SKEW_MILLIS);
    let (result, _package) = match state.store.commit_bundle(
        &principal,
        &bundle_id.to_string(),
        &media_ready,
        max_updated_at,
        state.now(),
    ) {
        Ok(value) => value,
        Err(StoreError::BundleMediaIncomplete) => {
            return Err(ApiError::unprocessable("bundle media bytes are incomplete"))
        }
        Err(StoreError::BundleRootNotNewer) => {
            return Err(ApiError::conflict(
                "bundle root is not newer than the published version",
            ))
        }
        Err(StoreError::BundleMembershipMismatch) => {
            return Err(ApiError::conflict(
                "bundle belongs to another family membership",
            ))
        }
        Err(StoreError::ForbiddenAvatar) => {
            return Err(ApiError::forbidden("Only owner may change avatar"))
        }
        Err(StoreError::ForbiddenCarePlan) => {
            return Err(ApiError::forbidden(
                "Only the creator or family owner may change this care plan",
            ))
        }
        Err(StoreError::CarePlanTombstoneResurrection) => {
            return Err(ApiError::conflict(
                "Deleted care plan cannot be resurrected",
            ))
        }
        Err(StoreError::ImmutableMediaAssociation) => {
            return Err(ApiError::conflict(
                "Media kind and association are immutable",
            ))
        }
        Err(StoreError::TimestampOutOfRange) => {
            return Err(ApiError::unprocessable(
                "updated_at is outside the accepted server time window",
            ))
        }
        Err(StoreError::PullEntityTooLarge) => {
            return Err(ApiError::unprocessable(
                "entity payload is too large for bounded sync pull",
            ))
        }
        Err(StoreError::UnresolvedReference(message)) => return Err(ApiError::conflict(message)),
        Err(StoreError::BundleNotFound) => return Err(ApiError::not_found("Bundle not found")),
        Err(error) => return Err(error.into()),
    };
    cleanup_committed_pending_bundle_media_for_bundle(
        &state.store,
        &state.media_root,
        &principal.family_id,
        &bundle_id.to_string(),
    )?;
    // Best-effort staging cleanup; failed/abandoned dirs are bounded by open-bundle limits.
    let _ = fs::remove_dir_all(state.bundle_stage_dir(&principal.family_id, &bundle_id)?);

    Ok(Json(result))
}

fn authenticate(state: &AppState, headers: &HeaderMap) -> Result<Principal, ApiError> {
    let raw = headers
        .get(AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .ok_or_else(ApiError::unauthorized)?;
    let (scheme, token) = raw.split_once(' ').ok_or_else(ApiError::unauthorized)?;
    if !scheme.eq_ignore_ascii_case("bearer") || token.is_empty() {
        return Err(ApiError::unauthorized());
    }
    state
        .store
        .authenticate(token)?
        .ok_or_else(ApiError::unauthorized)
}

fn require_bootstrap_secret(state: &AppState, headers: &HeaderMap) -> Result<(), ApiError> {
    let Some(expected) = state.bootstrap_secret.as_deref() else {
        return Ok(());
    };
    let provided = headers
        .get(BOOTSTRAP_SECRET_HEADER)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    if !constant_time_eq(provided.as_bytes(), expected.as_bytes()) {
        return Err(ApiError::unauthorized_detail(
            "Bootstrap secret required or invalid",
        ));
    }
    Ok(())
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    left.iter()
        .zip(right.iter())
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

fn media_entity_is_pullable(
    state: &AppState,
    family_id: &str,
    entity: &store::PulledEntity,
    published_media: &BTreeSet<String>,
) -> bool {
    if entity.entity_type != "media" || entity.deleted_at.is_some() {
        return true;
    }
    let is_published = published_media.contains(&entity.client_uuid);
    let Ok(client_uuid) = Uuid::parse_str(&entity.client_uuid) else {
        return false;
    };
    let declared_size = match entity.payload.get("byte_size") {
        None => None,
        Some(value) => match value.as_u64().and_then(|size| usize::try_from(size).ok()) {
            Some(size) => Some(size),
            None => {
                tracing::error!(
                    client_uuid = %entity.client_uuid,
                    "stored media byte_size is invalid; omitting media from pull"
                );
                return false;
            }
        },
    };
    match state.media_path(family_id, client_uuid) {
        Ok(path) => media_file_is_ready(&path, declared_size, &entity.client_uuid) && is_published,
        Err(_) => false,
    }
}

fn media_file_is_ready(path: &Path, declared_size: Option<usize>, client_uuid: &str) -> bool {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return false,
        Err(error) => {
            tracing::error!(
                %client_uuid,
                path = %path.display(),
                %error,
                "cannot inspect media bytes; omitting media"
            );
            return false;
        }
    };
    let actual_size = metadata.len();
    let expected_matches = declared_size.is_none_or(|expected| {
        u64::try_from(expected).is_ok_and(|expected| expected == actual_size)
    });
    if metadata.file_type().is_file() && actual_size > 0 && expected_matches {
        return true;
    }

    tracing::warn!(
        %client_uuid,
        path = %path.display(),
        actual_size,
        ?declared_size,
        "media bytes are invalid; omitting and repairing incomplete state"
    );
    if metadata.file_type().is_file() || metadata.file_type().is_symlink() {
        match fs::remove_file(path) {
            Ok(()) => {
                if let Some(parent) = path.parent() {
                    if let Err(error) = sync_directory(parent) {
                        tracing::error!(
                            %client_uuid,
                            path = %parent.display(),
                            %error,
                            "failed to sync media directory after corrupt-file cleanup"
                        );
                    }
                }
            }
            Err(error) => {
                tracing::error!(
                    %client_uuid,
                    path = %path.display(),
                    %error,
                    "failed to remove invalid media bytes"
                );
            }
        }
    }
    false
}

fn media_file_integrity_sha256(
    path: &Path,
    declared_size: Option<usize>,
    expected_sha256: Option<&str>,
    client_uuid: &str,
) -> Option<String> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return None,
        Err(error) => {
            tracing::error!(
                %client_uuid,
                path = %path.display(),
                %error,
                "cannot inspect atomic media integrity"
            );
            return None;
        }
    };
    if !metadata.file_type().is_file() || metadata.len() == 0 {
        return None;
    }
    if declared_size.is_some_and(|expected| u64::try_from(expected) != Ok(metadata.len())) {
        return None;
    }
    match fs::read(path) {
        Ok(bytes) => {
            let actual = hex::encode(Sha256::digest(bytes));
            if expected_sha256.is_none_or(|expected| expected == actual) {
                Some(actual)
            } else {
                None
            }
        }
        Err(error) => {
            tracing::error!(
                %client_uuid,
                path = %path.display(),
                %error,
                "cannot hash atomic media"
            );
            None
        }
    }
}

fn require_owner(state: &AppState, headers: &HeaderMap) -> Result<Principal, ApiError> {
    let principal = authenticate(state, headers)?;
    if principal.role != "owner" {
        return Err(ApiError::forbidden("Owner role required"));
    }
    Ok(principal)
}

fn json_body<T>(body: Result<Json<T>, JsonRejection>) -> Result<T, ApiError> {
    body.map(|Json(value)| value)
        .map_err(|error| ApiError::unprocessable(error.body_text()))
}

fn derive_token(secret: &[u8], message: &str) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(secret).expect("HMAC accepts any key length");
    mac.update(message.as_bytes());
    URL_SAFE_NO_PAD.encode(mac.finalize().into_bytes())
}

fn secure_generation() -> String {
    let mut bytes = [0u8; 24];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn secure_invite_code() -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let range = Uniform::from(0..ALPHABET.len());
    let mut rng = OsRng;
    (0..12)
        .map(|_| ALPHABET[range.sample(&mut rng)] as char)
        .collect()
}

fn system_epoch_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i64)
        .unwrap_or(0)
}

fn load_or_create_server_secret(data_dir: &Path) -> Result<Vec<u8>, ApiError> {
    let path = data_dir.join("server.secret");
    let secret = match OpenOptions::new().write(true).create_new(true).open(&path) {
        Ok(mut file) => {
            let mut secret = vec![0u8; 32];
            OsRng.fill_bytes(&mut secret);
            file.write_all(&secret)?;
            file.sync_all()?;
            secret
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => fs::read(&path)?,
        Err(error) => return Err(error.into()),
    };
    secure_file(&path)?;
    if secret.len() < 32 {
        return Err(ApiError::internal(
            "server.secret must contain at least 32 bytes",
        ));
    }
    Ok(secret)
}

fn write_private_file(path: &Path, content: &[u8]) -> Result<(), ApiError> {
    let temporary = path.with_extension(format!("{}.tmp", Uuid::new_v4()));
    let result = (|| -> Result<(), ApiError> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        file.write_all(content)?;
        file.sync_all()?;
        secure_file(&temporary)?;
        fs::rename(&temporary, path)?;
        secure_file(path)?;
        let parent = path
            .parent()
            .ok_or_else(|| ApiError::internal("media path has no parent directory"))?;
        sync_directory(parent)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn collect_orphan_family_media(store: &Store, media_root: &Path) -> Result<(), ApiError> {
    let family_ids = store.family_ids()?;
    let mut removed_any = false;
    for entry in fs::read_dir(media_root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Ok(family_id) = Uuid::parse_str(&name) else {
            continue;
        };
        if family_ids.contains(&family_id.to_string()) {
            continue;
        }
        match fs::remove_dir_all(entry.path()) {
            Ok(()) => {
                removed_any = true;
                tracing::info!(
                    family_id = %family_id,
                    "removed orphan family media left by an interrupted deletion"
                );
            }
            Err(error) => {
                tracing::error!(
                    family_id = %family_id,
                    path = %entry.path().display(),
                    %error,
                    "failed to remove orphan family media; startup will retry"
                );
            }
        }
    }
    if removed_any {
        sync_directory(media_root)?;
    }
    Ok(())
}

fn cleanup_committed_pending_bundle_media_for_bundle(
    store: &Store,
    media_root: &Path,
    family_id: &str,
    bundle_id: &str,
) -> Result<(), ApiError> {
    let pending = store.committed_pending_bundle_media_for_bundle(family_id, bundle_id)?;
    for entry in pending {
        cleanup_committed_pending_bundle_media(store, media_root, &entry)?;
    }
    Ok(())
}

fn cleanup_committed_pending_bundle_media(
    store: &Store,
    media_root: &Path,
    pending: &CommittedPendingBundleMedia,
) -> Result<(), ApiError> {
    let family_id = Uuid::parse_str(&pending.family_id)
        .map_err(|_| ApiError::internal("stored pending media family uuid is invalid"))?;
    let media_id = Uuid::parse_str(&pending.media_uuid)
        .map_err(|_| ApiError::internal("stored pending media uuid is invalid"))?;
    let published = media_root
        .join(family_id.to_string())
        .join(media_id.to_string());
    cleanup_committed_pending_media_with_ops(
        &published,
        |path| fs::remove_file(path),
        sync_directory,
        || {
            store.finalize_committed_pending_bundle_media(pending)?;
            Ok(())
        },
    )
}

fn cleanup_committed_pending_media_with_ops(
    published: &Path,
    remove: impl FnOnce(&Path) -> std::io::Result<()>,
    sync: impl Fn(&Path) -> std::io::Result<()>,
    finalize: impl FnOnce() -> Result<(), ApiError>,
) -> Result<(), ApiError> {
    match remove(published) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    // NotFound can mean an earlier attempt removed the file but crashed before
    // its directory fsync. Re-sync both directory levels on every retry before
    // deleting the durable SQLite cleanup evidence.
    sync_published_media_directories(published, &sync)?;
    finalize()
}

fn retry_committed_pending_bundle_media_cleanup(
    store: &Store,
    media_root: &Path,
) -> Result<(), ApiError> {
    for pending in store.committed_pending_bundle_media()? {
        if let Err(error) = cleanup_committed_pending_bundle_media(store, media_root, &pending) {
            tracing::error!(
                family_id = %pending.family_id,
                bundle_id = %pending.bundle_id,
                media_uuid = %pending.media_uuid,
                ?error,
                "failed to finish committed bundle media cleanup; startup will retry"
            );
        }
    }
    Ok(())
}

fn prepare_published_media_file(staged: &Path, published: &Path) -> Result<(), ApiError> {
    prepare_published_media_file_with_link(staged, published, |source, destination| {
        fs::hard_link(source, destination)
    })
}

fn prepare_published_media_file_with_link(
    staged: &Path,
    published: &Path,
    link: impl FnOnce(&Path, &Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    prepare_published_media_file_with_link_and_sync(staged, published, link, |directory| {
        sync_directory(directory)
    })
}

fn prepare_published_media_file_with_link_and_sync(
    staged: &Path,
    published: &Path,
    link: impl FnOnce(&Path, &Path) -> std::io::Result<()>,
    sync_parent: impl Fn(&Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    let parent = published
        .parent()
        .ok_or_else(|| ApiError::internal("media path has no parent directory"))?;
    fs::create_dir_all(parent)?;
    secure_directory(parent)?;

    match link(staged, published) {
        Ok(()) => {
            secure_file(published)?;
            fs::File::open(published)?.sync_all()?;
            sync_published_media_directories(published, &sync_parent)?;
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            verify_existing_published_media(staged, published, &sync_parent)
        }
        Err(error) if hard_link_fallback_allowed(&error) => {
            match atomic_copy_media_noreplace(staged, published) {
                Ok(()) => {
                    sync_published_media_directories(published, &sync_parent)?;
                    Ok(())
                }
                Err(copy_error) if copy_error.kind() == std::io::ErrorKind::AlreadyExists => {
                    verify_existing_published_media(staged, published, &sync_parent)
                }
                Err(copy_error) => Err(copy_error.into()),
            }
        }
        Err(error) => Err(error.into()),
    }
}

fn verify_existing_published_media(
    staged: &Path,
    published: &Path,
    sync_parent: &impl Fn(&Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    let metadata = fs::symlink_metadata(published)?;
    if !metadata.file_type().is_file() || fs::read(staged)? != fs::read(published)? {
        return Err(ApiError::conflict(
            "published media bytes conflict with staged bundle",
        ));
    }
    secure_file(published)?;
    fs::File::open(published)?.sync_all()?;
    sync_published_media_directories(published, sync_parent)?;
    Ok(())
}

fn sync_published_media_file(published: &Path) -> Result<(), ApiError> {
    secure_file(published)?;
    fs::File::open(published)?.sync_all()?;
    sync_published_media_directories(published, &|directory| sync_directory(directory))?;
    Ok(())
}

fn sync_published_media_directories(
    published: &Path,
    sync: &impl Fn(&Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    let family_directory = published
        .parent()
        .ok_or_else(|| ApiError::internal("media path has no family directory"))?;
    let media_root = family_directory
        .parent()
        .ok_or_else(|| ApiError::internal("media path has no media root"))?;
    sync(family_directory)?;
    sync(media_root)?;
    Ok(())
}

fn hard_link_fallback_allowed(error: &std::io::Error) -> bool {
    if error.kind() == std::io::ErrorKind::Unsupported {
        return true;
    }
    #[cfg(unix)]
    {
        matches!(
            error.raw_os_error(),
            Some(code)
                if code == libc::EXDEV
                    || code == libc::EPERM
                    || code == libc::EACCES
                    || code == libc::EOPNOTSUPP
        )
    }
    #[cfg(not(unix))]
    false
}

fn atomic_copy_media_noreplace(staged: &Path, published: &Path) -> std::io::Result<()> {
    let temporary = staged.with_extension(format!("{}.publish.tmp", Uuid::new_v4()));
    let result = (|| -> std::io::Result<()> {
        let mut source = fs::File::open(staged)?;
        let mut destination = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        std::io::copy(&mut source, &mut destination)?;
        destination.sync_all()?;
        secure_file(&temporary)?;
        rename_noreplace(&temporary, published)?;
        secure_file(published)?;
        fs::File::open(published)?.sync_all()?;
        let published_parent = published.parent().ok_or_else(|| {
            std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "media path has no parent directory",
            )
        })?;
        sync_directory(published_parent)?;
        if let Some(staging_parent) = temporary.parent() {
            if staging_parent != published_parent {
                sync_directory(staging_parent)?;
            }
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(target_os = "linux")]
fn rename_noreplace(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;

    let source_c = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    let destination_c = CString::new(destination.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    // SAFETY: both C strings are NUL-terminated and remain alive for the call.
    let result = unsafe {
        libc::renameat2(
            libc::AT_FDCWD,
            source_c.as_ptr(),
            libc::AT_FDCWD,
            destination_c.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result == 0 {
        return Ok(());
    }
    let error = std::io::Error::last_os_error();
    if matches!(
        error.raw_os_error(),
        Some(code) if code == libc::ENOSYS || code == libc::EINVAL || code == libc::EOPNOTSUPP
    ) {
        return rename_noreplace_single_process(source, destination);
    }
    Err(error)
}

#[cfg(target_os = "linux")]
fn rename_noreplace_single_process(source: &Path, destination: &Path) -> std::io::Result<()> {
    if destination.try_exists()? {
        return Err(std::io::Error::from(std::io::ErrorKind::AlreadyExists));
    }
    // The supported deployment is a single server process, so after the
    // exclusive family lock this fallback has no competing publisher.
    fs::rename(source, destination)
}

#[cfg(not(target_os = "linux"))]
fn rename_noreplace(source: &Path, destination: &Path) -> std::io::Result<()> {
    if destination.try_exists()? {
        return Err(std::io::Error::from(std::io::ErrorKind::AlreadyExists));
    }
    fs::rename(source, destination)
}

fn sync_directory(path: &Path) -> std::io::Result<()> {
    fs::File::open(path)?.sync_all()
}

pub(crate) fn hash_secret(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

pub(crate) fn secure_directory(path: &Path) -> Result<(), std::io::Error> {
    set_mode(path, 0o700)
}

pub(crate) fn secure_file(path: &Path) -> Result<(), std::io::Error> {
    set_mode(path, 0o600)
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) -> Result<(), std::io::Error> {
    use std::os::unix::fs::PermissionsExt;

    if PERMISSION_HARDENING_DISABLED.load(Ordering::Acquire) {
        return Ok(());
    }

    match fs::set_permissions(path, fs::Permissions::from_mode(mode)) {
        Ok(()) => Ok(()),
        Err(error) if is_compatible_permission_hardening_error(&error) => {
            // Fail closed unless a NAS filesystem that cannot chmod is explicitly
            // allowed via LEZI_ALLOW_PERMISSION_HARDENING_SKIP=1.
            if permission_hardening_skip_allowed() {
                if !PERMISSION_HARDENING_DISABLED.swap(true, Ordering::AcqRel) {
                    tracing::warn!(
                        path = %path.display(),
                        requested_mode = format_args!("{mode:o}"),
                        %error,
                        "filesystem does not allow chmod; continuing with NAS-managed permissions (LEZI_ALLOW_PERMISSION_HARDENING_SKIP=1)"
                    );
                }
                Ok(())
            } else {
                tracing::error!(
                    path = %path.display(),
                    requested_mode = format_args!("{mode:o}"),
                    %error,
                    "filesystem does not allow chmod; set LEZI_ALLOW_PERMISSION_HARDENING_SKIP=1 to continue with NAS-managed permissions"
                );
                Err(error)
            }
        }
        Err(error) => Err(error),
    }
}

#[cfg(unix)]
fn permission_hardening_skip_allowed() -> bool {
    matches!(
        std::env::var("LEZI_ALLOW_PERMISSION_HARDENING_SKIP").as_deref(),
        Ok("1") | Ok("true") | Ok("TRUE") | Ok("yes") | Ok("YES")
    )
}

#[cfg(unix)]
fn is_compatible_permission_hardening_error(error: &std::io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(code)
            if code == libc::EPERM
                || code == libc::EACCES
                || code == libc::EOPNOTSUPP
    )
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) -> Result<(), std::io::Error> {
    Ok(())
}

#[cfg(unix)]
fn set_private_umask() {
    // SAFETY: setting the process umask is process-global and is done once while
    // the server is being built, before request tasks are created.
    unsafe {
        libc::umask(0o077);
    }
}

#[cfg(not(unix))]
fn set_private_umask() {}

fn parse_env<T>(key: &str, default: T) -> Result<T, String>
where
    T: std::str::FromStr,
{
    match std::env::var(key) {
        Ok(value) => value
            .parse()
            .map_err(|_| format!("{key} contains an invalid value")),
        Err(std::env::VarError::NotPresent) => Ok(default),
        Err(error) => Err(format!("{key}: {error}")),
    }
}

#[derive(Debug)]
pub struct ApiError {
    status: StatusCode,
    detail: Value,
    authenticate: bool,
}

impl ApiError {
    fn new(status: StatusCode, detail: impl Into<Value>) -> Self {
        Self {
            status,
            detail: detail.into(),
            authenticate: false,
        }
    }

    fn bad_request(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, detail)
    }

    fn unauthorized() -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            detail: Value::String("Invalid or revoked token".to_owned()),
            authenticate: true,
        }
    }

    fn unauthorized_detail(detail: impl Into<Value>) -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            detail: detail.into(),
            authenticate: false,
        }
    }

    fn forbidden(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::FORBIDDEN, detail)
    }

    fn too_many_requests(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::TOO_MANY_REQUESTS, detail)
    }

    fn not_found(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::NOT_FOUND, detail)
    }

    fn conflict(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::CONFLICT, detail)
    }

    fn conflict_value(detail: Value) -> Self {
        Self::new(StatusCode::CONFLICT, detail)
    }

    fn gone(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::GONE, detail)
    }

    pub(crate) fn unprocessable(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, detail)
    }

    fn payload_too_large(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::PAYLOAD_TOO_LARGE, detail)
    }

    pub(crate) fn internal(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, detail)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let mut response = (self.status, Json(json!({"detail": self.detail}))).into_response();
        if self.authenticate {
            response
                .headers_mut()
                .insert(WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
        }
        response
    }
}

impl From<StoreError> for ApiError {
    fn from(error: StoreError) -> Self {
        tracing::error!(%error, "sync store failure");
        Self::internal("Internal server error")
    }
}

impl From<std::io::Error> for ApiError {
    fn from(error: std::io::Error) -> Self {
        tracing::error!(%error, "sync filesystem failure");
        Self::internal("Internal server error")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_match_hmac_contract() {
        let secret = vec![b'S'; 32];
        assert_eq!(
            derive_token(&secret, "join:abc:device"),
            "sLQvpn4ydfRjk66ShZFi8JAWU-pYkN3FV0VWlHBNAuA"
        );
    }

    #[test]
    fn config_rejects_invalid_limits() {
        let mut config = ServerConfig::new("/tmp/lezi-unused");
        config.invite_ttl_hours = 0;
        assert!(config.validate().is_err());
        config.invite_ttl_hours = 24;
        config.max_media_bytes = 0;
        assert!(config.validate().is_err());
        config.max_media_bytes = DEFAULT_MAX_MEDIA_BYTES;
        config.bootstrap_secret = Some("short".to_owned());
        assert!(config.validate().is_err());
        config.bootstrap_secret = Some("sixteen-chars!!!!".to_owned());
        assert!(config.validate().is_ok());
        config.create_rate_limit.max_attempts = 0;
        assert!(config.validate().is_err());
    }

    #[test]
    fn constant_time_eq_rejects_mismatched_secrets() {
        assert!(constant_time_eq(b"same-secret-value", b"same-secret-value"));
        assert!(!constant_time_eq(b"same-secret-value", b"other-secret-val"));
        assert!(!constant_time_eq(b"short", b"longer-value"));
    }

    #[test]
    fn private_file_replacement_is_complete_and_leaves_no_temporary_file() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("media-id");
        write_private_file(&path, b"old").unwrap();
        write_private_file(&path, b"new-content").unwrap();

        assert_eq!(fs::read(&path).unwrap(), b"new-content");
        assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 1);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(path.metadata().unwrap().permissions().mode() & 0o777, 0o600);
        }
    }

    #[cfg(unix)]
    #[test]
    fn future_schema_store_preflight_does_not_mutate_parent_or_create_sidecars() {
        use std::os::unix::fs::PermissionsExt;

        let directory = tempfile::tempdir().unwrap();
        let database_path = directory.path().join("lezi.db");
        let connection = rusqlite::Connection::open(&database_path).unwrap();
        connection
            .execute_batch(
                "
                PRAGMA user_version = 4;
                CREATE TABLE future_sentinel(value TEXT NOT NULL);
                ",
            )
            .unwrap();
        drop(connection);
        let mut directory_permissions = directory.path().metadata().unwrap().permissions();
        directory_permissions.set_mode(0o751);
        fs::set_permissions(directory.path(), directory_permissions).unwrap();
        let mut database_permissions = database_path.metadata().unwrap().permissions();
        database_permissions.set_mode(0o640);
        fs::set_permissions(&database_path, database_permissions).unwrap();
        let before_entries = fs::read_dir(directory.path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect::<std::collections::BTreeSet<_>>();

        assert!(
            Store::open(&database_path).is_err(),
            "Store::open accepted a newer database schema"
        );

        assert_eq!(
            fs::read_dir(directory.path())
                .unwrap()
                .map(|entry| entry.unwrap().file_name())
                .collect::<std::collections::BTreeSet<_>>(),
            before_entries
        );
        assert_eq!(
            directory.path().metadata().unwrap().permissions().mode() & 0o777,
            0o751
        );
        assert_eq!(
            database_path.metadata().unwrap().permissions().mode() & 0o777,
            0o640
        );
        for unexpected in ["lezi.db-wal", "lezi.db-shm", "lezi.db-journal"] {
            assert!(!directory.path().join(unexpected).exists());
        }
    }

    #[cfg(unix)]
    #[test]
    fn atomic_media_publish_falls_back_when_hard_links_are_unsupported() {
        let directory = tempfile::tempdir().unwrap();
        let staged = directory.path().join("staged");
        let published = directory.path().join("published");
        write_private_file(&staged, b"image-bytes").unwrap();

        prepare_published_media_file_with_link(&staged, &published, |_from, _to| {
            Err(std::io::Error::from_raw_os_error(libc::EOPNOTSUPP))
        })
        .unwrap();

        assert_eq!(fs::read(&published).unwrap(), b"image-bytes");
        write_private_file(&staged, b"different-bytes").unwrap();
        let conflict = prepare_published_media_file_with_link(&staged, &published, |_from, _to| {
            Err(std::io::Error::from_raw_os_error(libc::EOPNOTSUPP))
        })
        .unwrap_err();
        assert_eq!(conflict.status, StatusCode::CONFLICT);
        assert_eq!(fs::read(&published).unwrap(), b"image-bytes");
        assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 2);
    }

    #[test]
    fn atomic_media_publication_syncs_family_and_media_root_directories() {
        let directory = tempfile::tempdir().unwrap();
        let staged = directory.path().join("staged");
        let media_root = directory.path().join("media");
        let family_directory = media_root.join("11111111-2222-4333-8444-555555555555");
        fs::create_dir_all(&family_directory).unwrap();
        let published = family_directory.join("published");
        write_private_file(&staged, b"same-bytes").unwrap();
        let synced = std::cell::RefCell::new(std::collections::BTreeSet::new());

        prepare_published_media_file_with_link_and_sync(
            &staged,
            &published,
            |from, to| fs::hard_link(from, to),
            |path| {
                synced.borrow_mut().insert(path.to_path_buf());
                Ok(())
            },
        )
        .unwrap();

        assert_eq!(fs::read(&published).unwrap(), b"same-bytes");
        assert_eq!(
            synced.into_inner(),
            std::collections::BTreeSet::from([family_directory, media_root])
        );
    }

    #[test]
    fn existing_atomic_media_retry_syncs_family_and_media_root_directories() {
        let directory = tempfile::tempdir().unwrap();
        let staged = directory.path().join("staged");
        let media_root = directory.path().join("media");
        let family_directory = media_root.join("11111111-2222-4333-8444-555555555555");
        fs::create_dir_all(&family_directory).unwrap();
        let published = family_directory.join("published");
        write_private_file(&staged, b"same-bytes").unwrap();
        write_private_file(&published, b"same-bytes").unwrap();
        let synced = std::cell::RefCell::new(std::collections::BTreeSet::new());

        prepare_published_media_file_with_link_and_sync(
            &staged,
            &published,
            |_from, _to| Err(std::io::Error::from(std::io::ErrorKind::AlreadyExists)),
            |path| {
                synced.borrow_mut().insert(path.to_path_buf());
                Ok(())
            },
        )
        .unwrap();

        assert_eq!(
            synced.into_inner(),
            std::collections::BTreeSet::from([family_directory, media_root])
        );
    }

    #[test]
    fn pending_media_cleanup_retries_directory_sync_before_finalizing_evidence() {
        let directory = tempfile::tempdir().unwrap();
        let media_root = directory.path().join("media");
        let family_directory = media_root.join("11111111-2222-4333-8444-555555555555");
        fs::create_dir_all(&family_directory).unwrap();
        let published = family_directory.join("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        write_private_file(&published, b"discarded").unwrap();
        let fail_sync = std::cell::Cell::new(true);
        let finalized = std::cell::Cell::new(0);

        let attempt = || {
            cleanup_committed_pending_media_with_ops(
                &published,
                |path| fs::remove_file(path),
                |_directory| {
                    if fail_sync.get() {
                        Err(std::io::Error::other("injected directory sync failure"))
                    } else {
                        Ok(())
                    }
                },
                || {
                    finalized.set(finalized.get() + 1);
                    Ok(())
                },
            )
        };

        assert!(attempt().is_err());
        assert!(!published.exists());
        assert_eq!(finalized.get(), 0);
        // The second remove observes NotFound, but another fsync failure must
        // still retain the durable publication/manifest evidence.
        assert!(attempt().is_err());
        assert_eq!(finalized.get(), 0);

        fail_sync.set(false);
        attempt().unwrap();
        assert_eq!(finalized.get(), 1);
    }

    #[cfg(unix)]
    #[test]
    fn nas_permission_hardening_errors_are_compatible_but_read_only_is_fatal() {
        for raw_os_error in [libc::EPERM, libc::EACCES, libc::EOPNOTSUPP] {
            let error = std::io::Error::from_raw_os_error(raw_os_error);
            assert!(is_compatible_permission_hardening_error(&error));
        }

        let read_only = std::io::Error::from_raw_os_error(libc::EROFS);
        assert!(!is_compatible_permission_hardening_error(&read_only));
    }

    #[cfg(unix)]
    #[test]
    fn permission_hardening_skip_is_opt_in() {
        // Ensure the helper reads the env flag; do not leave a sticky value for other tests.
        let key = "LEZI_ALLOW_PERMISSION_HARDENING_SKIP";
        let previous = std::env::var_os(key);
        std::env::remove_var(key);
        assert!(!permission_hardening_skip_allowed());
        std::env::set_var(key, "1");
        assert!(permission_hardening_skip_allowed());
        std::env::set_var(key, "0");
        assert!(!permission_hardening_skip_allowed());
        match previous {
            Some(value) => std::env::set_var(key, value),
            None => std::env::remove_var(key),
        }
    }
}
