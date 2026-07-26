mod model;
mod store;

use std::collections::{BTreeMap, HashMap, VecDeque};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
#[cfg(unix)]
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
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
use model::{
    normalize_display_name, EmptyRequest, FamilyCreateRequest, InviteRequest, JoinRequest,
    PushRequest,
};
use rand::distributions::{Distribution, Uniform};
use rand::rngs::OsRng;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use store::{Principal, Store, StoreError};
use tokio::sync::Mutex;
use tower_http::trace::TraceLayer;
use uuid::Uuid;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
pub const DEFAULT_INVITE_TTL_HOURS: u16 = 24;
pub const DEFAULT_MAX_MEDIA_BYTES: usize = 10 * 1024 * 1024;
pub const DEFAULT_CREATE_RATE_LIMIT: u32 = 20;
pub const DEFAULT_JOIN_RATE_LIMIT: u32 = 60;
pub const DEFAULT_RATE_LIMIT_WINDOW_SECONDS: i64 = 60;
const GLOBAL_RATE_LIMIT_MULTIPLIER: u32 = 10;
const READINESS_CACHE_SECONDS: i64 = 5;
const MAX_ENTITY_FUTURE_SKEW_MILLIS: i64 = 24 * 60 * 60 * 1_000;
const LOCAL_DEVICE_DISPLAY_NAME: &str = "我（本机）";
pub(crate) const PULL_PAGE_ENTITY_LIMIT: usize = 200;
pub(crate) const PULL_PAGE_TARGET_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const PULL_ENTITY_TARGET_BYTES: usize = PULL_PAGE_TARGET_BYTES / 3;
const BOOTSTRAP_SECRET_HEADER: HeaderName = HeaderName::from_static("x-lezi-bootstrap-secret");

#[cfg(unix)]
static PERMISSION_HARDENING_DISABLED: AtomicBool = AtomicBool::new(false);

type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;
type InviteCodeFactory = Arc<dyn Fn() -> String + Send + Sync>;

#[derive(Clone, Debug)]
pub struct RateLimitConfig {
    pub max_attempts: u32,
    pub window_seconds: i64,
}

impl Default for RateLimitConfig {
    fn default() -> Self {
        Self {
            max_attempts: DEFAULT_CREATE_RATE_LIMIT,
            window_seconds: DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
        }
    }
}

#[derive(Clone)]
pub struct ServerConfig {
    pub data_dir: PathBuf,
    pub version: String,
    pub max_media_bytes: usize,
    pub invite_ttl_hours: u16,
    pub server_secret: Option<Vec<u8>>,
    pub generation: Option<String>,
    /// When set (non-empty), POST /v1/family/create requires matching
    /// `X-Lezi-Bootstrap-Secret`. Empty/None keeps create open for Android
    /// wire compatibility; production docs require setting this fail-closed.
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

struct RateLimiter {
    scoped_max_attempts: u32,
    global_max_attempts: u32,
    window_seconds: i64,
    windows: StdMutex<RateLimitWindows>,
}

#[derive(Default)]
struct RateLimitWindows {
    global: VecDeque<i64>,
    scoped: HashMap<String, VecDeque<i64>>,
    last_cleanup: Option<i64>,
}

impl RateLimiter {
    fn new(config: RateLimitConfig) -> Self {
        Self {
            scoped_max_attempts: config.max_attempts,
            global_max_attempts: config
                .max_attempts
                .saturating_mul(GLOBAL_RATE_LIMIT_MULTIPLIER),
            window_seconds: config.window_seconds,
            windows: StdMutex::new(RateLimitWindows::default()),
        }
    }

    fn check_and_record(&self, scope: &str, now: i64) -> bool {
        let Ok(mut windows) = self.windows.lock() else {
            return false;
        };

        prune_rate_limit_window(&mut windows.global, now, self.window_seconds);
        if windows
            .last_cleanup
            .is_none_or(|last| now.saturating_sub(last) >= self.window_seconds || now < last)
        {
            windows.scoped.retain(|_, queue| {
                prune_rate_limit_window(queue, now, self.window_seconds);
                !queue.is_empty()
            });
            windows.last_cleanup = Some(now);
        }

        if windows.global.len() as u32 >= self.global_max_attempts {
            return false;
        }
        let queue = windows.scoped.entry(scope.to_owned()).or_default();
        prune_rate_limit_window(queue, now, self.window_seconds);
        if queue.len() as u32 >= self.scoped_max_attempts {
            return false;
        }
        queue.push_back(now);
        windows.global.push_back(now);
        true
    }
}

fn prune_rate_limit_window(queue: &mut VecDeque<i64>, now: i64, window_seconds: i64) {
    while queue.front().is_some_and(|timestamp| {
        now < *timestamp || now.saturating_sub(*timestamp) >= window_seconds
    }) {
        queue.pop_front();
    }
}

#[derive(Clone, Copy)]
struct CachedReadiness {
    checked_at: i64,
    healthy: bool,
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
}

pub fn build_app(config: ServerConfig) -> Result<Router, ApiError> {
    config.validate().map_err(ApiError::internal)?;
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
    let state = AppState {
        store: Store::open(config.data_dir.join("lezi.db"))?,
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
        .route("/v1/invite", post(create_invite))
        .route("/v1/join", post(join))
        .route("/v1/leave", post(leave))
        .route("/v1/family/delete", post(delete_family))
        .route("/v1/push", post(push_entities))
        .route("/v1/pull", get(pull_entities))
        .route("/v1/media/{client_uuid}", put(put_media).get(get_media))
        .layer(DefaultBodyLimit::max(body_limit))
        .layer(TraceLayer::new_for_http())
        .with_state(Arc::new(state)))
}

async fn health(State(state): State<Arc<AppState>>) -> Json<Value> {
    Json(json!({"ok": true, "version": state.version}))
}

async fn readiness(State(state): State<Arc<AppState>>) -> (StatusCode, Json<Value>) {
    let now = state.now();
    let mut cache = state.readiness_cache.lock().await;
    let healthy = if let Some(cached) = *cache {
        if now >= cached.checked_at
            && now.saturating_sub(cached.checked_at) < READINESS_CACHE_SECONDS
        {
            cached.healthy
        } else {
            refresh_readiness(&state, now, &mut cache).await
        }
    } else {
        refresh_readiness(&state, now, &mut cache).await
    };
    readiness_response(&state.version, healthy)
}

async fn refresh_readiness(
    state: &AppState,
    now: i64,
    cache: &mut Option<CachedReadiness>,
) -> bool {
    let store = state.store.clone();
    let data_root = state.data_root.clone();
    let media_root = state.media_root.clone();
    let result = tokio::task::spawn_blocking(move || {
        store
            .health_check()
            .map_err(|error| error.to_string())
            .and_then(|()| probe_directory_writable(&data_root).map_err(|error| error.to_string()))
            .and_then(|()| probe_directory_writable(&media_root).map_err(|error| error.to_string()))
    })
    .await;
    let healthy = match result {
        Ok(Ok(())) => true,
        Ok(Err(error)) => {
            tracing::error!(%error, "readiness check failed");
            false
        }
        Err(error) => {
            tracing::error!(%error, "readiness worker failed");
            false
        }
    };
    *cache = Some(CachedReadiness {
        checked_at: now,
        healthy,
    });
    healthy
}

fn readiness_response(version: &str, healthy: bool) -> (StatusCode, Json<Value>) {
    if healthy {
        (
            StatusCode::OK,
            Json(json!({"ok": true, "version": version})),
        )
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({
                "ok": false,
                "status": "degraded",
                "version": version,
            })),
        )
    }
}

fn probe_directory_writable(directory: &Path) -> std::io::Result<()> {
    let path = directory.join(format!(
        ".lezi-health-{}-{}",
        std::process::id(),
        Uuid::new_v4()
    ));
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)?;
        file.write_all(b"ok")?;
        file.sync_all()
    })();
    let cleanup = if path.exists() {
        fs::remove_file(path)
    } else {
        Ok(())
    };
    result.and(cleanup).and_then(|()| sync_directory(directory))
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
    request.validate()?;
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
        request.display_name.as_deref(),
        move |request_hash, family_id| signing_state.owner_token(request_hash, family_id),
    );
    let (family_id, token) = match result {
        Ok(value) => value,
        Err(StoreError::FamilyAlreadyExists) => {
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
            "generation": state.generation,
        })),
    ))
}

async fn create_invite(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<InviteRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    let principal = require_owner(&state, &headers)?;
    let request = json_body(body)?;
    if request
        .family_id
        .is_some_and(|id| id.to_string() != principal.family_id)
    {
        return Err(ApiError::forbidden("family_id does not match token"));
    }
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
        display_name.as_deref(),
        state.now(),
        move |code_hash, device_id| signing_state.member_token(code_hash, device_id),
    );
    let (family_id, token) = match result {
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
        "entities": [],
        "cursor": 0,
        "generation": state.generation,
    })))
}

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
struct MembersResponse {
    members: Vec<MemberView>,
}

async fn list_family_members(
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
        let display_name = member_display_name_for_view(membership.display_name.as_deref());
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
        member_role_rank(&left.role)
            .cmp(&member_role_rank(&right.role))
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
fn member_display_name_for_view(value: Option<&str>) -> Option<String> {
    normalize_display_name(value)
        .ok()
        .flatten()
        .filter(|name| name != LOCAL_DEVICE_DISPLAY_NAME)
}

fn member_role_rank(role: &str) -> u8 {
    if role == "owner" {
        0
    } else {
        1
    }
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
    state.store.revoke(&principal.token_hash, state.now())?;
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
    if family_media.exists() {
        fs::remove_dir_all(&family_media)?;
    }
    state.store.delete_family(&principal.family_id)?;
    Ok(Json(json!({"ok": true})))
}

async fn push_entities(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<PushRequest>, JsonRejection>,
) -> Result<Json<store::PushResult>, ApiError> {
    let principal = authenticate(&state, &headers)?;
    let request = json_body(body)?.validate(state.max_media_bytes)?;
    if request
        .device_id
        .as_deref()
        .is_some_and(|device_id| device_id != principal.device_id)
    {
        return Err(ApiError::forbidden("device_id does not match token"));
    }
    if request
        .generation
        .as_deref()
        .is_some_and(|generation| generation != state.generation)
    {
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
    let result = match state.store.push(
        &principal.family_id,
        &principal.role,
        request.entities,
        max_updated_at,
    ) {
        Ok(value) => value,
        Err(StoreError::ForbiddenAvatar) => {
            return Err(ApiError::forbidden("Only owner may change avatar"))
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
    #[serde(default)]
    cursor: i64,
    #[serde(default)]
    generation: Option<String>,
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
    if query
        .generation
        .as_deref()
        .is_some_and(|generation| generation != state.generation)
    {
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
    let entities = page
        .entities
        .into_iter()
        .filter(|entity| media_entity_is_pullable(state.as_ref(), &principal.family_id, entity))
        .collect::<Vec<_>>();
    Ok(Json(json!({
        "entities": entities,
        "cursor": page.cursor,
        "generation": state.generation,
        "has_more": page.has_more,
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
    state
        .store
        .republish_media(&principal.family_id, &client_uuid.to_string())?;
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
) -> bool {
    if entity.entity_type != "media" || entity.deleted_at.is_some() {
        return true;
    }
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
        Ok(path) => media_file_is_ready(&path, declared_size, &entity.client_uuid),
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
    fn tokens_match_legacy_hmac_contract() {
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
    fn rate_limiter_is_scoped_and_keeps_a_global_fallback() {
        let config = RateLimitConfig {
            max_attempts: 2,
            window_seconds: 60,
        };
        let scoped = RateLimiter::new(config.clone());
        assert!(scoped.check_and_record("scope-a", 100));
        assert!(scoped.check_and_record("scope-a", 100));
        assert!(!scoped.check_and_record("scope-a", 100));
        assert!(scoped.check_and_record("scope-b", 100));

        let global = RateLimiter::new(config);
        for index in 0..(2 * GLOBAL_RATE_LIMIT_MULTIPLIER) {
            assert!(global.check_and_record(&format!("rotated-{index}"), 100));
        }
        assert!(!global.check_and_record("rotated-overflow", 100));
        assert!(global.check_and_record("rotated-overflow", 160));
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
