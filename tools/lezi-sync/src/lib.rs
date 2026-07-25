mod model;
mod store;

use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::body::Body;
use axum::extract::rejection::{JsonRejection, QueryRejection};
use axum::extract::{DefaultBodyLimit, Path as AxumPath, Query, Request, State};
use axum::http::header::{AUTHORIZATION, CONTENT_LENGTH, CONTENT_TYPE, WWW_AUTHENTICATE};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use futures_util::StreamExt;
use hmac::{Hmac, Mac};
use model::{EmptyRequest, FamilyCreateRequest, InviteRequest, JoinRequest, PushRequest};
use rand::distributions::{Distribution, Uniform};
use rand::rngs::OsRng;
use rand::RngCore;
use serde::Deserialize;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use store::{Principal, Store, StoreError};
use tokio::sync::Mutex;
use tower_http::trace::TraceLayer;
use uuid::Uuid;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
pub const DEFAULT_INVITE_TTL_HOURS: u16 = 24;
pub const DEFAULT_MAX_MEDIA_BYTES: usize = 10 * 1024 * 1024;

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
        Ok(())
    }
}

#[derive(Clone)]
struct AppState {
    store: Store,
    media_root: PathBuf,
    version: String,
    max_media_bytes: usize,
    invite_ttl_seconds: i64,
    signing_secret: Arc<Vec<u8>>,
    generation: String,
    clock: Clock,
    invite_code_factory: InviteCodeFactory,
    family_locks: Arc<Mutex<HashMap<String, Arc<Mutex<()>>>>>,
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
    let state = AppState {
        store: Store::open(config.data_dir.join("lezi.db"))?,
        media_root,
        version: config.version,
        max_media_bytes: config.max_media_bytes,
        invite_ttl_seconds: i64::from(config.invite_ttl_hours) * 60 * 60,
        signing_secret: Arc::new(signing_secret),
        generation: config.generation.unwrap_or_else(secure_generation),
        clock: config.clock,
        invite_code_factory: config.invite_code_factory,
        family_locks: Arc::new(Mutex::new(HashMap::new())),
    };
    let body_limit = state.max_media_bytes.max(16 * 1024 * 1024);
    Ok(Router::new()
        .route("/health", get(health))
        .route("/v1/family/create", post(create_family))
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

async fn create_family(
    State(state): State<Arc<AppState>>,
    body: Result<Json<FamilyCreateRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    let request = json_body(body)?;
    request.validate()?;
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
    request.validate()?;
    let signing_state = state.clone();
    let result = state.store.join_family(
        &request.code,
        &request.device_id,
        request.display_name.as_deref(),
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
    let request = json_body(body)?.validate()?;
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
    let result = match state
        .store
        .push(&principal.family_id, &principal.role, request.entities)
    {
        Ok(value) => value,
        Err(StoreError::ForbiddenAvatar) => {
            return Err(ApiError::forbidden("Only owner may change avatar"))
        }
        Err(StoreError::ImmutableMediaAssociation) => {
            return Err(ApiError::conflict(
                "Media kind and association are immutable",
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
    let (entities, cursor) = match state.store.pull(&principal.family_id, query.cursor) {
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
    Ok(Json(json!({
        "entities": entities,
        "cursor": cursor,
        "generation": state.generation,
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
    let kind = state
        .store
        .media_kind(&principal.family_id, &client_uuid.to_string())?
        .ok_or_else(|| ApiError::not_found("Media metadata not found"))?;
    if kind == "avatar" && principal.role != "owner" {
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
    let path = state.media_path(&principal.family_id, client_uuid)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
        secure_directory(parent)?;
    }
    write_private_file(&path, &content)?;
    Ok(Json(json!({"ok": true, "size": content.len()})))
}

async fn get_media(
    State(state): State<Arc<AppState>>,
    AxumPath(client_uuid): AxumPath<Uuid>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let principal = authenticate(&state, &headers)?;
    if state
        .store
        .media_kind(&principal.family_id, &client_uuid.to_string())?
        .is_none()
    {
        return Err(ApiError::not_found("Media metadata not found"));
    }
    let path = state.media_path(&principal.family_id, client_uuid)?;
    if !path.is_file() {
        return Err(ApiError::not_found("Media bytes not found"));
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
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
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
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
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

    fn forbidden(detail: impl Into<Value>) -> Self {
        Self::new(StatusCode::FORBIDDEN, detail)
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
    }
}
