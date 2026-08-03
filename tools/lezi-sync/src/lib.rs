mod handlers;
mod members;
mod model;
/// Private offline v3→current migration contract (crate-internal; not a public server API).
pub(crate) mod offline_migrate;
mod rate_limit;
mod readiness;
mod store;

/// Ops entry for `lezi-sync offline-migrate …` (ticket 05).
///
/// Not a product/server API — private family-NAS offline pipeline only.
/// Returns a process exit status byte (`0` ok, `1` migrate/validate fail, `2` usage).
pub fn offline_migrate_main(args: &[String]) -> u8 {
    offline_migrate::cli::main_from_args(args)
}

use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
#[cfg(unix)]
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::extract::rejection::JsonRejection;
use axum::extract::DefaultBodyLimit;
use axum::http::header::{AUTHORIZATION, WWW_AUTHENTICATE};
use axum::http::{HeaderMap, HeaderName, HeaderValue, StatusCode, Uri};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use handlers::{app_update, disaster_restore, health, identity, lan_apk, media, sync};
use hmac::{Hmac, Mac};
use members::{
    add_family_member, approve_member_rename_request, cancel_my_member_rename_request,
    list_family_members, list_member_rename_requests, reject_member_rename_request,
    remove_family_member, rename_family_device, rename_family_member, revoke_family_device,
    update_my_display_name,
};
use rand::rngs::OsRng;
use rand::RngCore;
pub use rate_limit::RateLimitConfig;
use rate_limit::RateLimiter;
use readiness::{readiness, CachedReadiness};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use store::{Principal, Store, StoreError};
use tokio::sync::Mutex;
use tower_http::timeout::TimeoutLayer;
use tower_http::trace::TraceLayer;
use uuid::Uuid;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
pub const DEFAULT_MAX_MEDIA_BYTES: usize = 10 * 1024 * 1024;
pub const DEFAULT_CREATE_RATE_LIMIT: u32 = 20;
pub const DEFAULT_MEMBER_REQUEST_RATE_LIMIT: u32 = 10;
pub const DEFAULT_MEMBER_REQUEST_TTL_HOURS: u16 = 24;
pub const DEFAULT_MAX_PENDING_MEMBER_REQUESTS: usize = 32;
pub const MEMBER_LOGIN_GRANT_TTL_SECONDS: i64 = 10 * 60;
pub(crate) const OPEN_STAGING_BUNDLE_TTL_SECONDS: i64 = 24 * 60 * 60;
const HTTP_REQUEST_TIMEOUT_SECONDS: u64 = 5 * 60;
pub const DEFAULT_RATE_LIMIT_WINDOW_SECONDS: i64 = 60;
/// Advertised on `/health` so clients can refuse metadata-first fallbacks.
pub const CAPABILITY_ATOMIC_BUNDLE: &str = "atomic_bundle";
/// Advertised on `/health` so clients only send the additive server-owned author field
/// to servers that accept and canonicalize it.
pub const CAPABILITY_RECORD_MEMBERSHIP_AUTHOR: &str = "record_membership_author";
pub const CAPABILITY_DISASTER_RESTORE: &str = "device_disaster_restore_v1";
pub(crate) const PROVISIONING_LOCK_KEY: &str = "__server_provisioning__";
pub const SETUP_PROTOCOL_VERSION: u16 = 1;
pub const CAPABILITY_TRUSTED_HTTPS_ENDPOINT: &str = "trusted_https_endpoint_v1";
pub const CAPABILITY_DEVICE_SESSIONS: &str = "device_sessions_v1";
pub const CAPABILITY_MEMBERSHIP_DEVICES: &str = "membership_devices_v1";
/// Minimum length for `LEZI_BOOTSTRAP_SECRET` when set, and for the offline
/// migrator's new root password (same product rule).
pub(crate) const MIN_BOOTSTRAP_SECRET_LEN: usize = 16;
/// On-disk `server.secret` byte length (HMAC signing material).
pub(crate) const SERVER_SECRET_BYTES: usize = 32;
pub(crate) const MAX_ENTITY_FUTURE_SKEW_MILLIS: i64 = 24 * 60 * 60 * 1_000;
pub(crate) const PULL_PAGE_ENTITY_LIMIT: usize = 200;
pub(crate) const PULL_PAGE_TARGET_BYTES: usize = 8 * 1024 * 1024;
pub(crate) const PULL_ENTITY_TARGET_BYTES: usize = PULL_PAGE_TARGET_BYTES / 3;
const BOOTSTRAP_SECRET_HEADER: HeaderName = HeaderName::from_static("x-lezi-bootstrap-secret");
/// Integer versionCode from authenticated clients; used to gate minSupported on sync paths.
const CLIENT_VERSION_CODE_HEADER: HeaderName =
    HeaderName::from_static("x-lezi-client-version-code");
/// Stable wire code when the client is below min_supported_version_code (or omits the header).
pub const CLIENT_UPDATE_REQUIRED_CODE: &str = "client_update_required";

#[cfg(unix)]
static PERMISSION_HARDENING_DISABLED: AtomicBool = AtomicBool::new(false);

type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;
#[derive(Clone)]
pub struct ServerConfig {
    pub data_dir: PathBuf,
    pub version: String,
    pub max_media_bytes: usize,
    pub server_secret: Option<Vec<u8>>,
    pub generation: Option<String>,
    /// When set (non-empty), POST /v1/family/create requires matching
    /// `X-Lezi-Bootstrap-Secret`. Empty/None keeps local development open;
    /// production docs require setting this fail-closed.
    pub bootstrap_secret: Option<String>,
    pub create_rate_limit: RateLimitConfig,
    pub member_request_rate_limit: RateLimitConfig,
    pub member_request_ttl_hours: u16,
    pub max_pending_member_requests: usize,
    /// Deploy-readable app-update metadata JSON (`app-update.json` by default).
    /// When unset, defaults to `{data_dir}/app-update.json`.
    pub app_update_metadata_path: Option<PathBuf>,
    /// Deploy-readable release APK (`app-release.apk` by default).
    /// When unset, defaults to `{data_dir}/app-release.apk`.
    pub app_update_apk_path: Option<PathBuf>,
    /// Optional LAN-only HTTP origin that serves the first-install APK page.
    pub lan_apk_download_origin: Option<String>,
    clock: Clock,
}

impl ServerConfig {
    pub fn new(data_dir: impl Into<PathBuf>) -> Self {
        Self {
            data_dir: data_dir.into(),
            version: VERSION.to_owned(),
            max_media_bytes: DEFAULT_MAX_MEDIA_BYTES,
            server_secret: None,
            generation: None,
            bootstrap_secret: None,
            create_rate_limit: RateLimitConfig {
                max_attempts: DEFAULT_CREATE_RATE_LIMIT,
                window_seconds: DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
            },
            member_request_rate_limit: RateLimitConfig {
                max_attempts: DEFAULT_MEMBER_REQUEST_RATE_LIMIT,
                window_seconds: DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
            },
            member_request_ttl_hours: DEFAULT_MEMBER_REQUEST_TTL_HOURS,
            max_pending_member_requests: DEFAULT_MAX_PENDING_MEMBER_REQUESTS,
            app_update_metadata_path: None,
            app_update_apk_path: None,
            lan_apk_download_origin: None,
            clock: Arc::new(system_epoch_seconds),
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
        config.bootstrap_secret = match std::env::var("LEZI_BOOTSTRAP_SECRET") {
            Ok(value) if !value.is_empty() => Some(value),
            Ok(_) | Err(std::env::VarError::NotPresent) => {
                return Err("LEZI_BOOTSTRAP_SECRET is required".to_owned())
            }
            Err(error) => return Err(format!("LEZI_BOOTSTRAP_SECRET: {error}")),
        };
        config.create_rate_limit.max_attempts =
            parse_env("LEZI_CREATE_RATE_LIMIT", DEFAULT_CREATE_RATE_LIMIT)?;
        config.member_request_rate_limit.max_attempts = parse_env(
            "LEZI_MEMBER_REQUEST_RATE_LIMIT",
            DEFAULT_MEMBER_REQUEST_RATE_LIMIT,
        )?;
        config.member_request_ttl_hours = parse_env(
            "LEZI_MEMBER_REQUEST_TTL_HOURS",
            DEFAULT_MEMBER_REQUEST_TTL_HOURS,
        )?;
        config.max_pending_member_requests = parse_env(
            "LEZI_MAX_PENDING_MEMBER_REQUESTS",
            DEFAULT_MAX_PENDING_MEMBER_REQUESTS,
        )?;
        config.app_update_metadata_path = std::env::var_os("LEZI_APP_UPDATE_METADATA_PATH")
            .map(PathBuf::from)
            .filter(|path| !path.as_os_str().is_empty());
        config.app_update_apk_path = std::env::var_os("LEZI_APP_UPDATE_APK_PATH")
            .map(PathBuf::from)
            .filter(|path| !path.as_os_str().is_empty());
        config.lan_apk_download_origin = match std::env::var("LEZI_LAN_APK_DOWNLOAD_ORIGIN") {
            Ok(value) if !value.is_empty() => Some(value),
            Ok(_) | Err(std::env::VarError::NotPresent) => None,
            Err(error) => return Err(format!("LEZI_LAN_APK_DOWNLOAD_ORIGIN: {error}")),
        };
        let window = parse_env(
            "LEZI_RATE_LIMIT_WINDOW_SECONDS",
            DEFAULT_RATE_LIMIT_WINDOW_SECONDS,
        )?;
        config.create_rate_limit.window_seconds = window;
        config.member_request_rate_limit.window_seconds = window;
        config.validate()?;
        Ok(config)
    }

    pub fn with_clock(mut self, clock: impl Fn() -> i64 + Send + Sync + 'static) -> Self {
        self.clock = Arc::new(clock);
        self
    }

    fn validate(&self) -> Result<(), String> {
        if self.max_media_bytes == 0 {
            return Err("LEZI_MAX_MEDIA_BYTES must be greater than zero".to_owned());
        }
        if self.create_rate_limit.max_attempts == 0
            || self.member_request_rate_limit.max_attempts == 0
        {
            return Err("rate limit max_attempts must be greater than zero".to_owned());
        }
        if self.create_rate_limit.window_seconds <= 0
            || self.member_request_rate_limit.window_seconds <= 0
        {
            return Err("LEZI_RATE_LIMIT_WINDOW_SECONDS must be greater than zero".to_owned());
        }
        if self
            .bootstrap_secret
            .as_ref()
            .is_some_and(|secret| secret.len() < MIN_BOOTSTRAP_SECRET_LEN)
        {
            return Err(format!(
                "LEZI_BOOTSTRAP_SECRET must be at least {MIN_BOOTSTRAP_SECRET_LEN} characters when set"
            ));
        }
        if self.member_request_ttl_hours != 24 {
            return Err("LEZI_MEMBER_REQUEST_TTL_HOURS must be exactly 24".to_owned());
        }
        if self.max_pending_member_requests == 0 {
            return Err("LEZI_MAX_PENDING_MEMBER_REQUESTS must be greater than zero".to_owned());
        }
        if let Some(origin) = &self.lan_apk_download_origin {
            validate_lan_apk_download_origin(origin)?;
        }
        Ok(())
    }
}

fn validate_lan_apk_download_origin(origin: &str) -> Result<(), String> {
    let uri: Uri = origin.parse().map_err(|_| {
        "LEZI_LAN_APK_DOWNLOAD_ORIGIN must be an absolute http origin on port 8767".to_owned()
    })?;
    let authority = uri.authority().ok_or_else(|| {
        "LEZI_LAN_APK_DOWNLOAD_ORIGIN must be an absolute http origin on port 8767".to_owned()
    })?;
    if uri.scheme_str() != Some("http")
        || authority.host().is_empty()
        || authority.host().contains(':')
        || authority.as_str().contains('@')
        || authority.port_u16() != Some(8767)
        || origin != format!("http://{authority}")
    {
        return Err(
            "LEZI_LAN_APK_DOWNLOAD_ORIGIN must be an absolute http origin on port 8767 without userinfo, path, query, or fragment"
                .to_owned(),
        );
    }
    Ok(())
}

#[derive(Clone)]
struct AppState {
    store: Store,
    data_root: PathBuf,
    media_root: PathBuf,
    version: String,
    max_media_bytes: usize,
    signing_secret: Arc<Vec<u8>>,
    generation: String,
    clock: Clock,
    family_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
    bootstrap_secret: Option<Arc<str>>,
    owner_root_fingerprint: Option<Arc<str>>,
    create_limiter: Arc<RateLimiter>,
    root_auth_limiter: Arc<RateLimiter>,
    member_request_limiter: Arc<RateLimiter>,
    member_request_ttl_seconds: i64,
    max_pending_member_requests: usize,
    readiness_cache: Arc<Mutex<Option<CachedReadiness>>>,
    app_update_metadata_path: PathBuf,
    app_update_apk_path: PathBuf,
    app_update_cache: Arc<app_update::AppUpdateCache>,
    lan_apk_landing_url: Option<Arc<str>>,
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

    fn owner_tokens(
        &self,
        create_request_hash: &str,
        family_id: &str,
        device_id: &str,
    ) -> (String, String) {
        (
            derive_token(
                &self.signing_secret,
                &format!("owner-access:{create_request_hash}:{family_id}:{device_id}"),
            ),
            derive_token(
                &self.signing_secret,
                &format!("owner-refresh:{create_request_hash}:{family_id}:{device_id}"),
            ),
        )
    }

    fn owner_login_tokens(
        &self,
        request_hash: &str,
        family_id: &str,
        device_id: &str,
    ) -> (String, String) {
        (
            derive_token(
                &self.signing_secret,
                &format!("owner-login-access:{request_hash}:{family_id}:{device_id}"),
            ),
            derive_token(
                &self.signing_secret,
                &format!("owner-login-refresh:{request_hash}:{family_id}:{device_id}"),
            ),
        )
    }

    fn member_request_tokens(
        &self,
        request_hash: &str,
        family_id: &str,
        device_id: &str,
    ) -> (String, String) {
        (
            derive_token(
                &self.signing_secret,
                &format!("member-request-access:{request_hash}:{family_id}:{device_id}"),
            ),
            derive_token(
                &self.signing_secret,
                &format!("member-request-refresh:{request_hash}:{family_id}:{device_id}"),
            ),
        )
    }

    fn member_login_grant_tokens(
        &self,
        grant_hash: &str,
        family_id: &str,
        device_id: &str,
    ) -> (String, String) {
        (
            derive_token(
                &self.signing_secret,
                &format!("member-grant-access:{grant_hash}:{family_id}:{device_id}"),
            ),
            derive_token(
                &self.signing_secret,
                &format!("member-grant-refresh:{grant_hash}:{family_id}:{device_id}"),
            ),
        )
    }

    fn refresh_request_tokens(
        &self,
        request_id: &str,
        family_id: &str,
        device_id: &str,
    ) -> (String, String) {
        (
            derive_token(
                &self.signing_secret,
                &format!("refresh-request-access:{request_id}:{family_id}:{device_id}"),
            ),
            derive_token(
                &self.signing_secret,
                &format!("refresh-request-refresh:{request_id}:{family_id}:{device_id}"),
            ),
        )
    }

    async fn recovery_detail(
        self: &Arc<Self>,
        family_id: &str,
        code: &'static str,
    ) -> Result<Value, ApiError> {
        let store = self.store.clone();
        let family_id = family_id.to_owned();
        let generation = self.generation.clone();
        run_blocking(move || {
            Ok(json!({
                "code": code,
                "action": "full_resync",
                "reset_cursor": 0,
                "server_cursor": store.current_revision(&family_id)?,
                "server_generation": generation,
            }))
        })
        .await
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

pub struct ServerApps {
    pub public: Router,
    pub internal: Router,
    pub lan_apk_download: Option<Router>,
}

pub fn build_apps(config: ServerConfig) -> Result<(Router, Router), ApiError> {
    build_server_apps(config).map(|apps| (apps.public, apps.internal))
}

pub fn build_app(config: ServerConfig) -> Result<Router, ApiError> {
    build_server_apps(config).map(|apps| apps.public)
}

pub fn build_server_apps(config: ServerConfig) -> Result<ServerApps, ApiError> {
    config.validate().map_err(ApiError::internal)?;
    let root_auth_rate_limit = config.create_rate_limit.clone();
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
    let owner_root_fingerprint = bootstrap_secret
        .as_deref()
        .map(|secret| owner_root_fingerprint(&signing_secret, secret));
    if bootstrap_secret.is_none() {
        tracing::warn!(
            "LEZI_BOOTSTRAP_SECRET is unset; POST /v1/family/create is open to the LAN until a family exists (set a secret for production)"
        );
    }
    let store = Store::open(database_path)?;
    store.reconcile_owner_root_fingerprint((config.clock)(), owner_root_fingerprint.as_deref())?;
    let restore_family_ids = disaster_restore::prepare_startup(&config.data_dir, (config.clock)())?;
    media::collect_orphan_family_media(&store, &media_root, &restore_family_ids)?;
    media::retry_committed_pending_bundle_media_cleanup(&store, &media_root)?;
    let app_update_metadata_path = config
        .app_update_metadata_path
        .unwrap_or_else(|| config.data_dir.join("app-update.json"));
    let app_update_apk_path = config
        .app_update_apk_path
        .unwrap_or_else(|| config.data_dir.join("app-release.apk"));
    let app_update_cache = Arc::new(app_update::AppUpdateCache::default());
    if app_update_metadata_path.is_file() && app_update_apk_path.is_file() {
        if let Err(error) =
            app_update_cache.load_verified(&app_update_metadata_path, &app_update_apk_path)
        {
            // App-update publication is optional and remains fail-open for
            // ordinary sync. Prewarming must not make the whole family server
            // unavailable when deploy artifacts are incomplete or invalid.
            tracing::warn!(detail = %error.detail, "failed to prewarm app update package cache");
        }
    }
    let lan_apk_landing_url = config
        .lan_apk_download_origin
        .map(|origin| Arc::from(format!("{origin}/join").into_boxed_str()));
    let state = AppState {
        store,
        data_root: config.data_dir,
        media_root,
        version: config.version,
        max_media_bytes: config.max_media_bytes,
        signing_secret: Arc::new(signing_secret),
        generation: config.generation.unwrap_or_else(secure_generation),
        clock: config.clock,
        family_locks: Arc::new(Mutex::new(HashMap::new())),
        bootstrap_secret: bootstrap_secret.map(|value| Arc::from(value.into_boxed_str())),
        owner_root_fingerprint: owner_root_fingerprint
            .map(|value| Arc::from(value.into_boxed_str())),
        create_limiter: Arc::new(RateLimiter::new(config.create_rate_limit)),
        root_auth_limiter: Arc::new(RateLimiter::new(root_auth_rate_limit)),
        member_request_limiter: Arc::new(RateLimiter::new(config.member_request_rate_limit)),
        member_request_ttl_seconds: i64::from(config.member_request_ttl_hours) * 60 * 60,
        max_pending_member_requests: config.max_pending_member_requests,
        readiness_cache: Arc::new(Mutex::new(None)),
        app_update_metadata_path,
        app_update_apk_path,
        app_update_cache,
        lan_apk_landing_url,
    };
    let body_limit = state.max_media_bytes.max(16 * 1024 * 1024);
    let state = Arc::new(state);
    let public = Router::new()
        .route("/health", get(health::health))
        .route("/ready", get(readiness))
        .route("/v1/setup-status", get(identity::setup_status))
        .route("/v1/app-update", get(app_update::get_app_update))
        .route("/v1/app-update/apk", get(app_update::get_app_update_apk))
        .route("/v1/family/create", post(identity::create_family))
        .route(
            "/v1/disaster-restore/batches",
            post(disaster_restore::start),
        )
        .route(
            "/v1/disaster-restore/batches/{batch_id}/manifest",
            put(disaster_restore::put_manifest),
        )
        .route(
            "/v1/disaster-restore/batches/{batch_id}/media/{client_uuid}",
            put(disaster_restore::put_media),
        )
        .route(
            "/v1/disaster-restore/batches/{batch_id}/status",
            get(disaster_restore::status),
        )
        .route(
            "/v1/disaster-restore/batches/{batch_id}/commit",
            post(disaster_restore::commit),
        )
        .route(
            "/v1/disaster-restore/batches/{batch_id}/cancel",
            post(disaster_restore::cancel),
        )
        .route("/v1/owner/login", post(identity::owner_login_device))
        .route("/v1/owner/takeover", post(identity::owner_takeover))
        .route(
            "/v1/member/requests",
            get(identity::list_member_login_requests).post(identity::create_member_login_request),
        )
        .route(
            "/v1/member/requests/status",
            post(identity::member_login_request_status),
        )
        .route(
            "/v1/member/requests/cancel",
            post(identity::cancel_member_login_request),
        )
        .route(
            "/v1/member/requests/claim",
            post(identity::claim_member_login_request),
        )
        .route(
            "/v1/member/requests/{request_id}/approve-new",
            post(identity::approve_new_member_login_request),
        )
        .route(
            "/v1/member/requests/{request_id}/bind-existing",
            post(identity::bind_existing_member_login_request),
        )
        .route(
            "/v1/member/requests/{request_id}/reject",
            post(identity::reject_member_login_request),
        )
        .route(
            "/v1/member/login-grants",
            post(identity::create_member_login_grant),
        )
        .route(
            "/v1/member/login-grants/claim",
            post(identity::claim_member_login_grant),
        )
        .route("/v1/session/refresh", post(identity::refresh_session))
        .route(
            "/v1/family/members",
            get(list_family_members).post(add_family_member),
        )
        .route(
            "/v1/family/members/{membership_id}/display-name",
            post(rename_family_member),
        )
        .route(
            "/v1/family/devices/{device_id}/display-name",
            post(rename_family_device),
        )
        .route(
            "/v1/family/devices/{device_id}/revoke",
            post(revoke_family_device),
        )
        .route(
            "/v1/family/rename-requests",
            get(list_member_rename_requests),
        )
        .route(
            "/v1/family/rename-requests/cancel",
            post(cancel_my_member_rename_request),
        )
        .route(
            "/v1/family/rename-requests/{request_id}/approve",
            post(approve_member_rename_request),
        )
        .route(
            "/v1/family/rename-requests/{request_id}/reject",
            post(reject_member_rename_request),
        )
        .route("/v1/family/members/remove", post(remove_family_member))
        .route("/v1/family/display-name", post(update_my_display_name))
        .route("/v1/family/name", post(identity::rename_family))
        .route("/v1/leave", post(identity::leave))
        .route("/v1/device/logout", post(identity::logout_current_device))
        .route("/v1/family/delete", post(identity::delete_family))
        .route("/v1/push", post(sync::retired_ordinary_push))
        .route("/v1/pull", get(sync::pull_entities))
        .route(
            "/v1/media/{client_uuid}",
            put(media::retired_ordinary_media_upload).get(media::get_media),
        )
        .route("/v1/bundles", post(media::stage_bundle))
        .route("/v1/bundles/{bundle_id}", get(media::get_bundle))
        .route(
            "/v1/bundles/{bundle_id}/media/{client_uuid}",
            put(media::put_bundle_media),
        )
        .route("/v1/bundles/{bundle_id}/commit", post(media::commit_bundle))
        .layer(DefaultBodyLimit::max(body_limit))
        .layer(TimeoutLayer::with_status_code(
            StatusCode::REQUEST_TIMEOUT,
            Duration::from_secs(HTTP_REQUEST_TIMEOUT_SECONDS),
        ))
        .layer(TraceLayer::new_for_http())
        .with_state(state.clone());
    let lan_apk_download = state.lan_apk_landing_url.is_some().then(|| {
        Router::new()
            .route("/join", get(lan_apk::join))
            .route("/download/lezi.apk", get(lan_apk::download))
            .layer(TraceLayer::new_for_http())
            .with_state(state.clone())
    });
    let internal = Router::new()
        .route("/health", get(health::health))
        .route("/ready", get(readiness))
        .layer(TraceLayer::new_for_http())
        .with_state(state);
    Ok(ServerApps {
        public,
        internal,
        lan_apk_download,
    })
}

async fn authenticate(state: &Arc<AppState>, headers: &HeaderMap) -> Result<Principal, ApiError> {
    let raw = headers
        .get(AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .ok_or_else(ApiError::unauthorized)?;
    let (scheme, token) = raw.split_once(' ').ok_or_else(ApiError::unauthorized)?;
    if !scheme.eq_ignore_ascii_case("bearer") || token.is_empty() {
        return Err(ApiError::unauthorized());
    }
    let token = token.to_owned();
    let store = state.store.clone();
    let now = state.now();
    let principal = run_blocking(move || {
        Ok(match store.authenticate(&token, now)? {
            Some(principal) => principal,
            None => match store.revoked_access_reason(&token)?.as_deref() {
                Some("device_removed") => {
                    return Err(ApiError::unauthorized_code(
                        "device_removed",
                        "This device was removed from the family",
                    ));
                }
                Some("membership_deleted") => {
                    return Err(ApiError::unauthorized_code(
                        "membership_deleted",
                        "This family membership was deleted",
                    ));
                }
                Some("family_deleted") => {
                    return Err(ApiError::unauthorized_code(
                        "family_deleted",
                        "This family was deleted",
                    ));
                }
                _ => return Err(ApiError::unauthorized()),
            },
        })
    })
    .await?;
    if principal.device_id.is_empty() {
        return Err(ApiError::unauthorized());
    }
    Ok(principal)
}

/// Reject authoritative sync write/pull when the client omits or is below minSupported.
///
/// Fail-open when deploy metadata is missing so an unfinished app-update channel does not
/// brick an otherwise healthy family server. App-update metadata/APK routes never call this.
async fn require_supported_client(
    state: &Arc<AppState>,
    headers: &HeaderMap,
) -> Result<(), ApiError> {
    let blocking_state = state.clone();
    let min_supported = match run_blocking(move || {
        blocking_state
            .app_update_cache
            .load_metadata(&blocking_state.app_update_metadata_path)
    })
    .await
    {
        Ok(metadata) => metadata
            .get("min_supported_version_code")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        // No deploy package / unreadable metadata → do not gate sync.
        Err(_) => return Ok(()),
    };
    let client_version = match parse_client_version_code(headers) {
        Some(value) => value,
        None => {
            return Err(ApiError::client_update_required(
                "Client version is missing or invalid; update the app to continue sync",
            ));
        }
    };
    if client_version < min_supported {
        return Err(ApiError::client_update_required(
            "Client version is below the minimum supported by this family server",
        ));
    }
    Ok(())
}

fn parse_client_version_code(headers: &HeaderMap) -> Option<u64> {
    let raw = headers
        .get(CLIENT_VERSION_CODE_HEADER)?
        .to_str()
        .ok()?
        .trim();
    if raw.is_empty() {
        return None;
    }
    // Strict decimal integer only; reject signs, decimals, and overflow noise.
    if !raw.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    raw.parse::<u64>().ok().filter(|value| *value > 0)
}

/// Execute synchronous SQLite/filesystem work away from Tokio's async worker
/// threads while preserving the handler's ordinary [`ApiError`] mapping.
pub(crate) async fn run_blocking<T, F>(operation: F) -> Result<T, ApiError>
where
    T: Send + 'static,
    F: FnOnce() -> Result<T, ApiError> + Send + 'static,
{
    tokio::task::spawn_blocking(operation)
        .await
        .map_err(|error| {
            tracing::error!(%error, "blocking server task failed to join");
            ApiError::internal("Internal server error")
        })?
}

fn require_bootstrap_secret(
    state: &AppState,
    headers: &HeaderMap,
    source: SocketAddr,
) -> Result<(), ApiError> {
    let Some(expected) = state.bootstrap_secret.as_deref() else {
        return Ok(());
    };
    let provided = headers
        .get(BOOTSTRAP_SECRET_HEADER)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    if !constant_time_eq(provided.as_bytes(), expected.as_bytes()) {
        return Err(root_auth_rejection(
            state,
            source,
            "Bootstrap secret required or invalid",
        ));
    }
    Ok(())
}

fn require_owner_root_password(
    state: &AppState,
    headers: &HeaderMap,
    source: SocketAddr,
) -> Result<(), ApiError> {
    let Some(expected) = state.bootstrap_secret.as_deref() else {
        return Err(root_auth_rejection(
            state,
            source,
            "Administrator authentication failed",
        ));
    };
    let provided = headers
        .get(BOOTSTRAP_SECRET_HEADER)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("");
    if !constant_time_eq(provided.as_bytes(), expected.as_bytes()) {
        return Err(root_auth_rejection(
            state,
            source,
            "Administrator authentication failed",
        ));
    }
    Ok(())
}

fn root_auth_rejection(state: &AppState, source: SocketAddr, detail: &str) -> ApiError {
    let scope = format!("root-auth-source:{}", source.ip());
    if state
        .root_auth_limiter
        .check_and_record(&scope, state.now())
    {
        ApiError::unauthorized_detail(detail)
    } else {
        ApiError::too_many_requests("Too many administrator authentication attempts")
    }
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    let left = Sha256::digest(left);
    let right = Sha256::digest(right);
    left.iter()
        .zip(right.iter())
        .fold(0u8, |acc, (a, b)| acc | (a ^ b))
        == 0
}

async fn require_owner(state: &Arc<AppState>, headers: &HeaderMap) -> Result<Principal, ApiError> {
    let principal = authenticate(state, headers).await?;
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

/// Keyed fingerprint of the deployment root password.
///
/// Matches startup [`Store::reconcile_owner_root_fingerprint`] derivation so the
/// offline migrator can pre-set `families.owner_root_fingerprint` together with a
/// regenerated `server.secret` (ticket 04).
pub(crate) fn owner_root_fingerprint(signing_secret: &[u8], root_password: &str) -> String {
    derive_token(signing_secret, &format!("owner-root:{root_password}"))
}

fn secure_generation() -> String {
    let mut bytes = [0u8; 24];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn secure_session_token() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

fn system_epoch_seconds() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i64)
        .unwrap_or(0)
}

fn load_or_create_server_secret(data_dir: &Path) -> Result<Vec<u8>, ApiError> {
    let path = data_dir.join("server.secret");
    match fs::read(&path) {
        Ok(secret) => {
            secure_file(&path)?;
            if secret.len() < SERVER_SECRET_BYTES {
                return Err(ApiError::internal(format!(
                    "server.secret must contain at least {SERVER_SECRET_BYTES} bytes"
                )));
            }
            Ok(secret)
        }
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let mut secret = vec![0u8; SERVER_SECRET_BYTES];
            OsRng.fill_bytes(&mut secret);
            write_server_secret(data_dir, &secret)?;
            Ok(secret)
        }
        Err(error) => Err(error.into()),
    }
}

/// Atomically write `{data_dir}/server.secret` with 0o600 permissions.
///
/// Temp name is `.server.secret.{uuid}.tmp` so abort cleanup (migrator media
/// failure) can wipe incomplete signing material alongside the final path.
pub(crate) fn write_server_secret(data_dir: &Path, secret: &[u8]) -> Result<(), std::io::Error> {
    if secret.len() < SERVER_SECRET_BYTES {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            format!("server.secret must contain at least {SERVER_SECRET_BYTES} bytes"),
        ));
    }
    let path = data_dir.join("server.secret");
    let temporary = data_dir.join(format!(".server.secret.{}.tmp", Uuid::new_v4().simple()));
    write_private_bytes(&path, secret, &temporary)
}

/// Temp → sync → secure(0o600) → rename → secure → parent fsync.
/// On error, best-effort remove `temporary` (caller may also wipe path).
fn write_private_bytes(
    path: &Path,
    content: &[u8],
    temporary: &Path,
) -> Result<(), std::io::Error> {
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(temporary)?;
        file.write_all(content)?;
        file.sync_all()?;
        secure_file(temporary)?;
        fs::rename(temporary, path)?;
        secure_file(path)?;
        if let Some(parent) = path.parent() {
            sync_directory(parent)?;
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(temporary);
    }
    result
}

fn write_private_file(path: &Path, content: &[u8]) -> Result<(), ApiError> {
    let temporary = path.with_extension(format!("{}.tmp", Uuid::new_v4()));
    write_private_bytes(path, content, &temporary).map_err(Into::into)
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
    code: Option<&'static str>,
}

impl ApiError {
    fn new(status: StatusCode, detail: impl Into<Value>) -> Self {
        Self {
            status,
            detail: detail.into(),
            authenticate: false,
            code: None,
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
            code: None,
        }
    }

    fn unauthorized_detail(detail: impl Into<Value>) -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            detail: detail.into(),
            authenticate: false,
            code: None,
        }
    }

    fn unauthorized_code(code: &'static str, detail: impl Into<Value>) -> Self {
        Self {
            status: StatusCode::UNAUTHORIZED,
            detail: detail.into(),
            authenticate: true,
            code: Some(code),
        }
    }

    fn forbidden(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::FORBIDDEN, detail)
    }

    /// Authenticated client is too old for authoritative sync; update endpoints stay open.
    fn client_update_required(detail: impl Into<Value>) -> Self {
        Self {
            status: StatusCode::FORBIDDEN,
            detail: detail.into(),
            authenticate: false,
            code: Some(CLIENT_UPDATE_REQUIRED_CODE),
        }
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

    fn request_timeout(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::REQUEST_TIMEOUT, detail)
    }

    pub(crate) fn internal(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, detail)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = match self.code {
            Some(code) => json!({"code": code, "detail": self.detail}),
            None => json!({"detail": self.detail}),
        };
        let mut response = (self.status, Json(body)).into_response();
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
        let token = derive_token(&secret, "session:abc:device");
        assert_eq!(token, derive_token(&secret, "session:abc:device"));
        assert_ne!(token, derive_token(&secret, "session:abc:other-device"));
    }

    #[test]
    fn config_rejects_invalid_limits() {
        let mut config = ServerConfig::new("/tmp/lezi-unused");
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

    #[test]
    fn write_server_secret_is_private_and_leaves_no_temporary() {
        let directory = tempfile::tempdir().unwrap();
        let secret = vec![b'S'; SERVER_SECRET_BYTES];
        write_server_secret(directory.path(), &secret).unwrap();
        let path = directory.path().join("server.secret");
        assert_eq!(fs::read(&path).unwrap(), secret);
        // Only the final name remains (no .server.secret.*.tmp).
        assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 1);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(path.metadata().unwrap().permissions().mode() & 0o777, 0o600);
        }
        assert!(write_server_secret(directory.path(), &[0u8; 8]).is_err());
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
                PRAGMA user_version = 12;
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
