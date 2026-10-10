use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::io::Read;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, Instant};

use axum::body::{Body, Bytes};
use axum::extract::ConnectInfo;
use axum::http::header::{ACCEPT_ENCODING, AUTHORIZATION, CONTENT_ENCODING, CONTENT_TYPE, VARY};
use axum::http::{Method, Request, StatusCode};
use axum::Router;
use flate2::read::GzDecoder;
use http_body_util::BodyExt;
use lezi_sync::{build_app, build_apps, build_server_apps, RateLimitConfig, ServerConfig, VERSION};
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tempfile::TempDir;
use tokio::sync::{oneshot, Barrier};
use tower::ServiceExt;
use uuid::Uuid;

struct Rig {
    directory: TempDir,
    app: Router,
    now: Arc<AtomicI64>,
}

type PrepareBlockingHook = Arc<dyn Fn(&'static str) + Send + Sync + 'static>;
type PrepareBlockingRelease = Arc<(Mutex<bool>, Condvar)>;

struct PrepareBlockingReleaseGuard {
    release: PrepareBlockingRelease,
}

impl PrepareBlockingReleaseGuard {
    fn release(&self) {
        let (released, condition) = &*self.release;
        *released
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner) = true;
        condition.notify_all();
    }
}

impl Drop for PrepareBlockingReleaseGuard {
    fn drop(&mut self) {
        self.release();
    }
}

fn blocked_prepare_hook(
    blocked_phase: &'static str,
) -> (
    PrepareBlockingHook,
    std::sync::mpsc::Receiver<&'static str>,
    PrepareBlockingReleaseGuard,
) {
    let (events_tx, events_rx) = std::sync::mpsc::channel();
    let release = Arc::new((Mutex::new(false), Condvar::new()));
    let hook_release = release.clone();
    let hook = Arc::new(move |phase: &'static str| {
        events_tx.send(phase).ok();
        if phase == blocked_phase {
            let (released, condition) = &*hook_release;
            let mut released = released
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            while !*released {
                let (next, timeout) = condition
                    .wait_timeout(released, Duration::from_secs(2))
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                released = next;
                if !*released && timeout.timed_out() {
                    drop(released);
                    panic!("causal media prepare test hook was not released");
                }
            }
        }
    });
    (hook, events_rx, PrepareBlockingReleaseGuard { release })
}

fn overlapping_expired_readiness_probe_hook() -> PrepareBlockingHook {
    let calls = Arc::new(AtomicUsize::new(0));
    let overlap = Arc::new((Mutex::new(0usize), Condvar::new()));
    Arc::new(move |phase: &'static str| {
        assert_eq!(phase, "probe");
        let call = calls.fetch_add(1, Ordering::SeqCst) + 1;
        if call == 1 {
            return;
        }
        let (in_flight, condition) = &*overlap;
        let mut in_flight = in_flight
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        *in_flight += 1;
        if *in_flight < 2 {
            while *in_flight < 2 {
                let (next, timeout) = condition
                    .wait_timeout(in_flight, Duration::from_secs(2))
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                in_flight = next;
                if timeout.timed_out() && *in_flight < 2 {
                    drop(in_flight);
                    panic!(
                        "expired readiness probes did not overlap; a second probe never started while the first was in flight"
                    );
                }
            }
        } else {
            condition.notify_all();
        }
    })
}

fn recording_commit_hook() -> (PrepareBlockingHook, std::sync::mpsc::Receiver<&'static str>) {
    let (events_tx, events_rx) = std::sync::mpsc::channel();
    let hook = Arc::new(move |phase: &'static str| {
        events_tx.send(phase).ok();
    });
    (hook, events_rx)
}

#[test]
fn blocked_prepare_hook_times_out_once_and_can_still_be_released() {
    let (hook, _events, release) = blocked_prepare_hook("before_verify");
    let started = std::time::Instant::now();

    let timed_out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        hook("before_verify");
    }));

    assert!(timed_out.is_err());
    assert!(started.elapsed() < Duration::from_secs(5));
    release.release();
    let after_release = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        hook("before_verify");
    }));
    assert!(after_release.is_ok());
}

#[derive(Clone)]
struct TestClientSession {
    generation: String,
}

fn test_client_sessions() -> &'static Mutex<HashMap<String, TestClientSession>> {
    static SESSIONS: OnceLock<Mutex<HashMap<String, TestClientSession>>> = OnceLock::new();
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn test_client_session(token: &str) -> Option<TestClientSession> {
    test_client_sessions().lock().unwrap().get(token).cloned()
}

impl Rig {
    fn new() -> Self {
        Self::with_config(|_config| {})
    }

    fn with_config(configure: impl FnOnce(&mut ServerConfig)) -> Self {
        let directory = TempDir::new().unwrap();
        let now = Arc::new(AtomicI64::new(1_753_418_400));
        let app = app_for(directory.path(), "generation-a", now.clone(), 8, configure);
        Self {
            directory,
            app,
            now,
        }
    }

    fn restart(&self, generation: &str) -> Router {
        app_for(
            self.directory.path(),
            generation,
            self.now.clone(),
            8,
            |_| {},
        )
    }

    fn restart_with_config(
        &self,
        generation: &str,
        configure: impl FnOnce(&mut ServerConfig),
    ) -> Router {
        app_for(
            self.directory.path(),
            generation,
            self.now.clone(),
            8,
            configure,
        )
    }

    fn with_unpinned_generation() -> Self {
        let directory = TempDir::new().unwrap();
        let now = Arc::new(AtomicI64::new(1_753_418_400));
        let app = app_for_optional_generation(directory.path(), None, now.clone(), 8, |_| {});
        Self {
            directory,
            app,
            now,
        }
    }

    fn restart_reusing_persisted_generation(&self) -> Router {
        app_for_optional_generation(self.directory.path(), None, self.now.clone(), 8, |_| {})
    }
}

fn app_for(
    directory: &Path,
    generation: &str,
    now: Arc<AtomicI64>,
    max_media_bytes: usize,
    configure: impl FnOnce(&mut ServerConfig),
) -> Router {
    app_for_optional_generation(directory, Some(generation), now, max_media_bytes, configure)
}

fn app_for_optional_generation(
    directory: &Path,
    generation: Option<&str>,
    now: Arc<AtomicI64>,
    max_media_bytes: usize,
    configure: impl FnOnce(&mut ServerConfig),
) -> Router {
    let clock = now.clone();
    let mut config = ServerConfig::new(directory);
    config.generation = generation.map(str::to_owned);
    config.max_media_bytes = max_media_bytes;
    // Integration tests create many instances within one process; keep this
    // limit high unless a test tightens it deliberately.
    config.create_rate_limit = RateLimitConfig {
        max_attempts: 10_000,
        window_seconds: 60,
    };
    configure(&mut config);
    config = config.with_clock(move || clock.load(Ordering::SeqCst));
    build_app(config).unwrap()
}

async fn request(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    body: Body,
    content_type: Option<&str>,
) -> axum::response::Response {
    request_with_headers(app, method, uri, token, body, content_type, &[]).await
}

async fn request_with_headers(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    body: Body,
    content_type: Option<&str>,
    extra_headers: &[(&str, &str)],
) -> axum::response::Response {
    request_with_source(
        app,
        method,
        uri,
        token,
        body,
        content_type,
        RequestTransport {
            extra_headers,
            source: SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 43210),
        },
    )
    .await
}

struct RequestTransport<'a> {
    extra_headers: &'a [(&'a str, &'a str)],
    source: SocketAddr,
}

async fn request_with_source(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    body: Body,
    content_type: Option<&str>,
    transport: RequestTransport<'_>,
) -> axum::response::Response {
    let mut builder = Request::builder().method(method).uri(uri);
    if !transport
        .extra_headers
        .iter()
        .any(|(name, _)| name.eq_ignore_ascii_case("x-lezi-client-version-code"))
    {
        builder = builder.header("x-lezi-client-version-code", "35");
    }
    if !transport
        .extra_headers
        .iter()
        .any(|(name, _)| name.eq_ignore_ascii_case("x-lezi-sync-capabilities"))
    {
        builder = builder.header(
            "x-lezi-sync-capabilities",
            if uri.starts_with("/v1/disaster-restore/") {
                "nursing_plan_intent_v1,restore_authority_v1"
            } else {
                "nursing_plan_intent_v1"
            },
        );
    }
    if let Some(token) = token {
        builder = builder.header(AUTHORIZATION, format!("Bearer {token}"));
    }
    if let Some(content_type) = content_type {
        builder = builder.header(CONTENT_TYPE, content_type);
    }
    for (name, value) in transport.extra_headers {
        builder = builder.header(*name, *value);
    }
    let mut request = builder.body(body).unwrap();
    request
        .extensions_mut()
        .insert(ConnectInfo(transport.source));
    app.clone().oneshot(request).await.unwrap()
}

async fn json_request(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    body: Value,
) -> (StatusCode, Value) {
    json_request_with_headers(app, method, uri, token, body, &[]).await
}

async fn json_request_with_headers(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    mut body: Value,
    extra_headers: &[(&str, &str)],
) -> (StatusCode, Value) {
    if uri.starts_with("/v1/disaster-restore/batches")
        && matches!(method, Method::POST | Method::PUT)
    {
        if let Some(object) = body.as_object_mut() {
            object
                .entry("restore_authority")
                .or_insert_with(|| json!("v1"));
            if uri.ends_with("/manifest") {
                object
                    .entry("source_relations")
                    .or_insert_with(|| json!([]));
            }
        }
    }
    if method == Method::POST {
        if let Some(session) = token.and_then(test_client_session) {
            if uri == "/v1/bundles" || uri.ends_with("/commit") {
                if let Some(object) = body.as_object_mut() {
                    object
                        .entry("generation")
                        .or_insert_with(|| Value::String(session.generation));
                }
            }
        }
    }

    let (status, value) =
        raw_json_request_with_headers(app, method, uri, token, body, extra_headers).await;
    if status.is_success()
        && matches!(
            uri,
            "/v1/family/create"
                | "/v1/owner/login"
                | "/v1/owner/takeover"
                | "/v1/member/requests/claim"
                | "/v1/member/login-grants/claim"
        )
    {
        if let (Some(token), Some(generation)) = (
            value.get("access_token").and_then(Value::as_str),
            value.get("generation").and_then(Value::as_str),
        ) {
            test_client_sessions().lock().unwrap().insert(
                token.to_owned(),
                TestClientSession {
                    generation: generation.to_owned(),
                },
            );
        }
    }
    (status, value)
}

async fn raw_json_request(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    body: Value,
) -> (StatusCode, Value) {
    raw_json_request_with_headers(app, method, uri, token, body, &[]).await
}

async fn raw_json_request_with_headers(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    body: Value,
    extra_headers: &[(&str, &str)],
) -> (StatusCode, Value) {
    let response = request_with_headers(
        app,
        method,
        uri,
        token,
        Body::from(body.to_string()),
        Some("application/json"),
        extra_headers,
    )
    .await;
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let value = serde_json::from_slice(&bytes).unwrap_or_else(|_| {
        panic!(
            "expected JSON response, got: {}",
            String::from_utf8_lossy(&bytes)
        )
    });
    (status, value)
}

async fn json_request_from(
    app: &Router,
    source: SocketAddr,
    uri: &str,
    body: Value,
) -> (StatusCode, Value) {
    let response = request_with_source(
        app,
        Method::POST,
        uri,
        None,
        Body::from(body.to_string()),
        Some("application/json"),
        RequestTransport {
            extra_headers: &[],
            source,
        },
    )
    .await;
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let value = serde_json::from_slice(&bytes).unwrap();
    (status, value)
}

async fn get_json(app: &Router, uri: &str, token: Option<&str>) -> (StatusCode, Value) {
    let mut current_uri = uri.to_owned();
    let query = current_uri.split_once('?').map(|(_, query)| query);
    let has_generation =
        query.is_some_and(|query| query.split('&').any(|part| part.starts_with("generation=")));
    let has_page_index =
        query.is_some_and(|query| query.split('&').any(|part| part.starts_with("page_index=")));
    if current_uri.starts_with("/v1/pull?") && !has_generation {
        if let Some(session) = token.and_then(test_client_session) {
            current_uri.push_str("&generation=");
            current_uri.push_str(&session.generation);
        }
    }
    if current_uri.starts_with("/v1/pull?") && !has_page_index {
        current_uri.push_str("&page_index=0");
    }
    json_request(app, Method::GET, &current_uri, token, json!({})).await
}

async fn create_family(app: &Router, device_id: &str, request_id: &str) -> Value {
    let (status, body) = json_request(
        app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "display_name": "妈妈",
            "device_name": device_id,
            "family_name": "测试家庭",
        }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    body
}

async fn create_family_with_root(
    app: &Router,
    device_id: &str,
    request_id: &str,
    root_password: &str,
) -> Value {
    let (status, body) = json_request_with_headers(
        app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "display_name": "妈妈",
            "device_name": device_id,
            "family_name": "测试家庭",
        }),
        &[("x-lezi-bootstrap-secret", root_password)],
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    body
}

fn baby_payload(nickname: &str, avatar_media_uuid: Option<&str>) -> Value {
    json!({
        "nickname": nickname,
        "sex": "female",
        "birthday": "2025-01-02",
        "avatar_media_uuid": avatar_media_uuid,
        "birth_weight_grams": 3200,
    })
}

fn record_payload(baby_id: &str) -> Value {
    json!({
        "baby_client_uuid": baby_id,
        "type": "formula",
        "custom_item_client_uuid": null,
        "timestamp": 100,
        "end_timestamp": null,
        "note": null,
        "payload_json": {"amount_ml": 120},
        "schema_version": 2,
    })
}

fn log_media_payload(record_id: &str) -> Value {
    json!({
        "kind": "log",
        "record_client_uuid": record_id,
        "mime": "image/jpeg",
        "byte_size": 3,
    })
}

async fn approve_new_member(app: &Router, owner_token: &str, device_id: &str) -> Value {
    let display_name = format!("成员-{device_id}");
    approve_new_member_named(app, owner_token, device_id, &display_name).await
}

async fn approve_new_member_named(
    app: &Router,
    owner_token: &str,
    device_id: &str,
    display_name: &str,
) -> Value {
    let (status, pending) = json_request(
        app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({
            "display_name": display_name,
            "device_name": device_id,
        }),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let request_id = pending["request_id"].as_str().unwrap();
    let pending_secret = pending["pending_secret"].as_str().unwrap();
    let (status, approval) = json_request(
        app,
        Method::POST,
        &format!("/v1/member/requests/{request_id}/approve-new"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{approval}");
    let (status, member) = json_request(
        app,
        Method::POST,
        "/v1/member/requests/claim",
        None,
        json!({"pending_secret": pending_secret}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member}");
    member
}

#[tokio::test]
async fn owner_can_reject_an_approved_unclaimed_request_and_release_its_name() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "approved-release-owner-device",
        "approved-release-owner-request-00001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let (request_status, pending) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({"display_name": "外婆", "device_name": "旧手机"}),
    )
    .await;
    assert_eq!(request_status, StatusCode::CREATED, "{pending}");
    let request_id = pending["request_id"].as_str().unwrap();
    let (approve_status, approved) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/member/requests/{request_id}/approve-new"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(approve_status, StatusCode::OK, "{approved}");

    // A previously installed APK may already report versionCode 12 while still using
    // the legacy pending-only model. It must not receive approved rows unless it opts
    // into the open-request response shape explicitly.
    let (legacy_status, legacy) =
        get_json(&rig.app, "/v1/member/requests", Some(owner_token)).await;
    assert_eq!(legacy_status, StatusCode::OK, "{legacy}");
    assert_eq!(legacy["requests"], json!([]));

    let (list_status, listed) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/member/requests",
        Some(owner_token),
        json!({}),
        &[("x-lezi-member-request-view", "open-v1")],
    )
    .await;
    assert_eq!(list_status, StatusCode::OK, "{listed}");
    let visible = listed["requests"].as_array().unwrap();
    assert_eq!(visible.len(), 1, "{listed}");
    assert_eq!(visible[0]["request_id"], request_id);
    assert_eq!(visible[0]["status"], "approved");

    let (reject_status, rejected) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/member/requests/{request_id}/reject"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(reject_status, StatusCode::OK, "{rejected}");

    let (list_status, listed) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/member/requests",
        Some(owner_token),
        json!({}),
        &[("x-lezi-member-request-view", "open-v1")],
    )
    .await;
    assert_eq!(list_status, StatusCode::OK, "{listed}");
    assert_eq!(listed["requests"], json!([]));

    let (replacement_status, replacement) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({"display_name": "外婆", "device_name": "新手机"}),
    )
    .await;
    assert_eq!(replacement_status, StatusCode::CREATED, "{replacement}");
    let replacement_id = replacement["request_id"].as_str().unwrap();
    let (replacement_approve_status, body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/member/requests/{replacement_id}/approve-new"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(replacement_approve_status, StatusCode::OK, "{body}");
}

fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

#[tokio::test]
async fn liveness_and_readiness_initialize_private_single_data_root() {
    let rig = Rig::new();
    let (status, body) = get_json(&rig.app, "/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["ok"], true);
    assert_eq!(body["version"], VERSION);
    assert_eq!(body["server_schema"], 13);
    assert_eq!(
        body["capabilities"],
        json!([
            "atomic_bundle",
            "record_membership_author",
            "device_disaster_restore_v1",
            "validated_deferred_fulfillment_v1",
            "causal_sync_v2",
            "causal_media_identity_v1",
            "nursing_plan_intent_v1",
            "restore_authority_v1"
        ])
    );
    let (ready_status, ready_body) = get_json(&rig.app, "/ready", None).await;
    assert_eq!(ready_status, StatusCode::OK);
    assert_eq!(
        ready_body,
        json!({"ok": true, "status": "ready", "version": VERSION}),
    );
    assert!(rig.directory.path().join("lezi.db").is_file());
    assert!(rig.directory.path().join("media").is_dir());
    assert!(rig.directory.path().join("server.secret").is_file());
    assert_eq!(
        rig.directory
            .path()
            .metadata()
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
    for path in [
        rig.directory.path().join("lezi.db"),
        rig.directory.path().join("server.secret"),
    ] {
        assert_eq!(path.metadata().unwrap().permissions().mode() & 0o777, 0o600);
    }
}

#[tokio::test]
async fn internal_router_exposes_only_health_and_readiness() {
    let directory = TempDir::new().unwrap();
    let (public, internal) = build_apps(ServerConfig::new(directory.path())).unwrap();

    assert_eq!(get_json(&internal, "/health", None).await.0, StatusCode::OK);
    assert_eq!(get_json(&internal, "/ready", None).await.0, StatusCode::OK);
    assert_eq!(
        request(
            &internal,
            Method::GET,
            "/v1/setup-status",
            None,
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::NOT_FOUND,
    );
    assert_eq!(
        get_json(&public, "/v1/setup-status", None).await.0,
        StatusCode::OK,
    );
}

#[tokio::test]
async fn schema_cutover_read_only_gate_keeps_health_open_and_rejects_mutations() {
    let directory = TempDir::new().unwrap();
    drop(build_app(ServerConfig::new(directory.path())).unwrap());
    let mut config = ServerConfig::new(directory.path());
    config.maintenance_read_only = true;
    let app = build_app(config).unwrap();

    assert_eq!(get_json(&app, "/health", None).await.0, StatusCode::OK);
    assert_eq!(
        get_json(&app, "/v1/setup-status", None).await.0,
        StatusCode::SERVICE_UNAVAILABLE,
    );
    let response = request(
        &app,
        Method::POST,
        "/v1/family/create",
        None,
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
    let body: Value =
        serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert_eq!(body["code"], json!("schema_cutover_read_only"));
}

#[test]
fn lan_apk_download_origin_rejects_everything_except_bare_http_port_8767() {
    let directory = TempDir::new().unwrap();
    for origin in [
        "https://192.168.77.10:8767",
        "http://user@192.168.77.10:8767",
        "http://192.168.77.10",
        "http://192.168.77.10:8765",
        "http://192.168.77.10:8767/",
        "http://192.168.77.10:8767/join",
        "http://192.168.77.10:8767?source=qr",
        "http://192.168.77.10:8767#invite",
        "http://:8767",
        "http://[2001:db8::1]:8767",
        "192.168.77.10:8767",
    ] {
        let mut config = ServerConfig::new(directory.path());
        config.lan_apk_download_origin = Some(origin.to_owned());
        assert!(
            build_app(config).is_err(),
            "accepted illegal origin {origin}"
        );
    }
}

#[tokio::test]
async fn lan_install_router_is_optional_and_exposes_no_sync_or_health_surface() {
    let directory = TempDir::new().unwrap();
    let mut config = ServerConfig::new(directory.path());
    config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
    let apps = build_server_apps(config).unwrap();
    let lan = apps
        .lan_apk_download
        .expect("configured LAN APK origin must build the third router");

    for path in ["/v1/setup-status", "/v1/app-update", "/health", "/ready"] {
        assert_eq!(
            request(&lan, Method::GET, path, None, Body::empty(), None)
                .await
                .status(),
            StatusCode::NOT_FOUND,
            "LAN install router leaked {path}",
        );
    }

    let disabled = TempDir::new().unwrap();
    assert!(build_server_apps(ServerConfig::new(disabled.path()))
        .unwrap()
        .lan_apk_download
        .is_none());
}

#[tokio::test]
async fn https_invite_origin_serves_join_and_apk_on_public_router_without_lan_listener() {
    let directory = TempDir::new().unwrap();
    let apk_bytes = b"verified-https-invite-apk";
    fs::write(directory.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        directory.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 12,
            "version_name": "0.4.3",
            "min_supported_version_code": 6,
            "sha256": hex::encode(Sha256::digest(apk_bytes)),
        })
        .to_string(),
    )
    .unwrap();
    let mut config = ServerConfig::new(directory.path());
    config.invite_install_origin = Some("https://invite.example.invalid".to_owned());
    let apps = build_server_apps(config).unwrap();
    assert!(
        apps.lan_apk_download.is_none(),
        "HTTPS invite origin must not create the 8767 listener"
    );

    let join = request(
        &apps.public,
        Method::GET,
        "/join",
        None,
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(join.status(), StatusCode::OK);
    let html = String::from_utf8(
        join.into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes()
            .to_vec(),
    )
    .unwrap();
    assert!(html.contains("href=\"/download/lezi.apk\""), "{html}");

    let download = request(
        &apps.public,
        Method::GET,
        "/download/lezi.apk",
        None,
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(download.status(), StatusCode::OK);
    assert_eq!(
        download.into_body().collect().await.unwrap().to_bytes(),
        apk_bytes.as_slice(),
    );
}

#[test]
fn https_invite_origin_rejects_anything_except_the_public_host() {
    let directory = TempDir::new().unwrap();
    for origin in [
        "http://invite.example.invalid",
        "https://example.com",
        "https://invite.example.invalid/",
        "https://invite.example.invalid/join",
        "https://user@invite.example.invalid",
        "https://invite.example.invalid:8443",
        "https://invite.example.invalid?source=qr",
        "https://invite.example.invalid#invite",
        "invite.example.invalid",
    ] {
        let mut config = ServerConfig::new(directory.path());
        config.invite_install_origin = Some(origin.to_owned());
        assert!(
            build_app(config).is_err(),
            "accepted illegal HTTPS invite origin {origin}"
        );
    }

    for origin in [
        "https://invite.example.invalid",
        "https://invite.example.invalid:443",
    ] {
        let mut config = ServerConfig::new(directory.path());
        config.invite_install_origin = Some(origin.to_owned());
        assert!(
            build_app(config).is_ok(),
            "rejected legal HTTPS invite origin {origin}"
        );
    }
}

#[test]
fn https_and_lan_invite_origins_cannot_both_be_set() {
    let directory = TempDir::new().unwrap();
    let mut config = ServerConfig::new(directory.path());
    config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
    config.invite_install_origin = Some("https://invite.example.invalid".to_owned());
    assert!(build_app(config).is_err());
}

#[tokio::test]
async fn neither_invite_origin_leaves_no_install_surface() {
    let directory = TempDir::new().unwrap();
    let apps = build_server_apps(ServerConfig::new(directory.path())).unwrap();
    assert!(apps.lan_apk_download.is_none());
    assert_eq!(
        request(
            &apps.public,
            Method::GET,
            "/join",
            None,
            Body::empty(),
            None
        )
        .await
        .status(),
        StatusCode::NOT_FOUND,
    );
    assert_eq!(
        request(
            &apps.public,
            Method::GET,
            "/download/lezi.apk",
            None,
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::NOT_FOUND,
    );
}

#[tokio::test]
async fn lan_invite_origin_stays_only_on_the_8767_router() {
    let directory = TempDir::new().unwrap();
    let mut config = ServerConfig::new(directory.path());
    config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
    let apps = build_server_apps(config).unwrap();
    let lan = apps
        .lan_apk_download
        .expect("LAN origin must still build the 8767 router");
    assert_eq!(
        request(&lan, Method::GET, "/join", None, Body::empty(), None)
            .await
            .status(),
        StatusCode::SERVICE_UNAVAILABLE,
    );
    assert_eq!(
        request(
            &apps.public,
            Method::GET,
            "/join",
            None,
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::NOT_FOUND,
    );
}

#[tokio::test]
async fn https_invite_page_hides_family_identity_and_matches_verified_apk_hash() {
    let directory = TempDir::new().unwrap();
    let apk_bytes = b"https-invite-verified-channel-apk";
    let sha256 = hex::encode(Sha256::digest(apk_bytes));
    fs::write(directory.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        directory.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 12,
            "version_name": "0.4.3",
            "min_supported_version_code": 6,
            "sha256": sha256,
        })
        .to_string(),
    )
    .unwrap();
    let now = Arc::new(AtomicI64::new(1_753_418_400));
    let app = app_for(directory.path(), "generation-a", now, 8, |config| {
        config.invite_install_origin = Some("https://invite.example.invalid".to_owned());
    });
    let owner = create_family(
        &app,
        "https-invite-identity-owner",
        "https-invite-identity-owner-request-0001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member_named(
        &app,
        owner_token,
        "https-invite-identity-member",
        "外婆阿珍",
    )
    .await;
    let (status, grant) = json_request(
        &app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{grant}");
    let grant_token = grant["grant"].as_str().unwrap();

    let join = request(&app, Method::GET, "/join", None, Body::empty(), None).await;
    assert_eq!(join.status(), StatusCode::OK);
    let html = String::from_utf8(
        join.into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes()
            .to_vec(),
    )
    .unwrap();
    assert!(!html.contains("测试家庭"), "{html}");
    assert!(!html.contains("外婆阿珍"), "{html}");
    assert!(!html.contains(grant_token), "{html}");

    let download = request(
        &app,
        Method::GET,
        "/download/lezi.apk",
        None,
        Body::empty(),
        None,
    )
    .await;
    let served = download.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(hex::encode(Sha256::digest(&served)), sha256);
}

#[tokio::test]
async fn member_login_grant_landing_url_uses_https_invite_origin() {
    let rig = Rig::with_config(|config| {
        config.invite_install_origin = Some("https://invite.example.invalid".to_owned());
    });
    let owner = create_family(
        &rig.app,
        "https-invite-grant-owner",
        "https-invite-grant-owner-request-01",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "https-invite-grant-member").await;
    let (status, grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{grant}");
    assert_eq!(grant["landing_url"], "https://invite.example.invalid/join");
}

#[tokio::test]
async fn lan_install_page_uses_only_verified_release_metadata_and_clears_the_invite_fragment() {
    let directory = TempDir::new().unwrap();
    let apk_bytes = b"signed-release-apk-fixture";
    let sha256 = hex::encode(Sha256::digest(apk_bytes));
    fs::write(directory.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        directory.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 12,
            "version_name": "0.3.5 <测试>",
            "min_supported_version_code": 6,
            "sha256": sha256,
            "release_notes": "邀请安装 & <script>不能执行</script>",
        })
        .to_string(),
    )
    .unwrap();
    let mut config = ServerConfig::new(directory.path());
    config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
    let lan = build_server_apps(config).unwrap().lan_apk_download.unwrap();

    let response = request(&lan, Method::GET, "/join", None, Body::empty(), None).await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers()["content-type"],
        "text/html; charset=utf-8"
    );
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert_eq!(response.headers()["x-content-type-options"], "nosniff");
    assert_eq!(response.headers()["x-frame-options"], "DENY");
    assert_eq!(response.headers()["referrer-policy"], "no-referrer");
    assert!(response.headers()["content-security-policy"]
        .to_str()
        .unwrap()
        .contains("default-src 'none'"));
    let html = String::from_utf8(
        response
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes()
            .to_vec(),
    )
    .unwrap();
    assert!(html.contains("下载乐记"), "{html}");
    assert!(html.contains("0.3.5 &lt;测试&gt;"), "{html}");
    assert!(
        html.contains("邀请安装 &amp; &lt;script&gt;不能执行&lt;/script&gt;"),
        "{html}"
    );
    assert!(html.contains("href=\"/download/lezi.apk\""), "{html}");
    assert!(html.contains("原地升级会保留本机记录和家庭配置"), "{html}");
    let scrub = "history.replaceState(null, \"\", location.pathname + location.search);";
    assert!(html.contains(scrub), "{html}");
    assert!(html.find(scrub).unwrap() < html.find("<body>").unwrap());
    assert!(!html.contains("src=\"http"), "{html}");
    assert!(!html.contains("href=\"http"), "{html}");
    assert!(!html.contains("<script>不能执行</script>"), "{html}");
}

#[tokio::test]
async fn lan_install_page_honestly_disables_download_for_missing_or_unverified_apk() {
    for apk_bytes in [None, Some(b"wrong-apk-bytes".as_slice())] {
        let directory = TempDir::new().unwrap();
        let expected_sha256 = hex::encode(Sha256::digest(b"expected-release-apk"));
        fs::write(
            directory.path().join("app-update.json"),
            json!({
                "package_name": "com.lezi.babylog",
                "version_code": 12,
                "version_name": "0.3.5",
                "min_supported_version_code": 6,
                "sha256": expected_sha256,
            })
            .to_string(),
        )
        .unwrap();
        if let Some(bytes) = apk_bytes {
            fs::write(directory.path().join("app-release.apk"), bytes).unwrap();
        }
        let mut config = ServerConfig::new(directory.path());
        config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
        let lan = build_server_apps(config).unwrap().lan_apk_download.unwrap();

        let response = request(&lan, Method::GET, "/join", None, Body::empty(), None).await;
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(response.headers()["cache-control"], "no-store");
        assert_eq!(response.headers()["x-content-type-options"], "nosniff");
        let html = String::from_utf8(
            response
                .into_body()
                .collect()
                .await
                .unwrap()
                .to_bytes()
                .to_vec(),
        )
        .unwrap();
        assert!(html.contains("乐记安装包暂不可用"), "{html}");
        assert!(html.contains("请联系管理员"), "{html}");
        assert!(!html.contains("href=\"/download/lezi.apk\""), "{html}");
        assert!(
            html.contains("history.replaceState(null, \"\", location.pathname + location.search);")
        );

        let download = request(
            &lan,
            Method::GET,
            "/download/lezi.apk",
            None,
            Body::empty(),
            None,
        )
        .await;
        assert_eq!(download.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(
            download.headers()["content-type"],
            "text/html; charset=utf-8"
        );
        let download_error = String::from_utf8(
            download
                .into_body()
                .collect()
                .await
                .unwrap()
                .to_bytes()
                .to_vec(),
        )
        .unwrap();
        assert!(download_error.contains("乐记安装包暂不可用"));
        assert!(!download_error.contains("href=\"/download/lezi.apk\""));
    }
}

#[tokio::test]
async fn lan_apk_download_is_anonymous_integrity_checked_and_non_cacheable() {
    let directory = TempDir::new().unwrap();
    let apk_bytes = b"verified-lan-release-apk";
    fs::write(directory.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        directory.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 12,
            "version_name": "0.3.5",
            "min_supported_version_code": 6,
            "sha256": hex::encode(Sha256::digest(apk_bytes)),
        })
        .to_string(),
    )
    .unwrap();
    let mut config = ServerConfig::new(directory.path());
    config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
    let lan = build_server_apps(config).unwrap().lan_apk_download.unwrap();

    let response = request(
        &lan,
        Method::GET,
        "/download/lezi.apk",
        None,
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.headers()["content-type"],
        "application/vnd.android.package-archive"
    );
    assert_eq!(
        response.headers()["content-disposition"],
        "attachment; filename=\"lezi.apk\""
    );
    assert_eq!(
        response.headers()["content-length"],
        apk_bytes.len().to_string()
    );
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert_eq!(response.headers()["x-content-type-options"], "nosniff");
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        apk_bytes.as_slice(),
    );
    assert_eq!(
        request(
            &lan,
            Method::POST,
            "/download/lezi.apk",
            None,
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::METHOD_NOT_ALLOWED,
    );
}

#[tokio::test]
async fn every_released_android_version_keeps_apk_recovery_independent_of_sync_floor() {
    let catalog: Value = serde_json::from_str(include_str!(
        "../../../config/android-release-compatibility.json"
    ))
    .unwrap();
    let package_name = catalog["application_id"].as_str().unwrap();
    let minimum_sync_version_code = catalog["minimum_sync_version_code"].as_u64().unwrap();
    let upgrade_target = &catalog["upgrade_target"];
    let target_version_code = upgrade_target["version_code"].as_u64().unwrap();
    let released_versions = catalog["released_versions"].as_array().unwrap();
    let directory = TempDir::new().unwrap();
    let apk_bytes = b"all-version-recovery-apk";
    fs::write(directory.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        directory.path().join("app-update.json"),
        json!({
            "package_name": package_name,
            "version_code": target_version_code,
            "version_name": upgrade_target["version_name"],
            "min_supported_version_code": minimum_sync_version_code,
            "sha256": hex::encode(Sha256::digest(apk_bytes)),
        })
        .to_string(),
    )
    .unwrap();
    let mut config = ServerConfig::new(directory.path());
    config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
    let apps = build_server_apps(config).unwrap();
    let public = apps.public;
    let lan = apps.lan_apk_download.unwrap();
    let owner = create_family(
        &public,
        "all-version-recovery-owner",
        "all-version-recovery-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();

    let lan_response = request(
        &lan,
        Method::GET,
        "/download/lezi.apk",
        None,
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(lan_response.status(), StatusCode::OK);
    assert_eq!(
        lan_response.into_body().collect().await.unwrap().to_bytes(),
        apk_bytes.as_slice(),
    );

    for release in released_versions {
        let source_version_code = release["version_code"].as_u64().unwrap().to_string();
        let client_header = [("x-lezi-client-version-code", source_version_code.as_str())];
        let (metadata_status, metadata) = raw_json_request_with_headers(
            &public,
            Method::GET,
            "/v1/app-update",
            Some(token),
            json!({}),
            &client_header,
        )
        .await;
        assert_eq!(
            metadata_status,
            StatusCode::OK,
            "source={source_version_code}"
        );
        assert_eq!(metadata["version_code"], json!(target_version_code));

        let (sync_status, sync_body) = raw_json_request_with_headers(
            &public,
            Method::GET,
            &format!("/v1/pull?cursor=0&generation={generation}&page_index=0"),
            Some(token),
            json!({}),
            &client_header,
        )
        .await;
        if release["version_code"].as_u64().unwrap() < minimum_sync_version_code {
            assert_eq!(
                sync_status,
                StatusCode::FORBIDDEN,
                "source={source_version_code}"
            );
            assert_eq!(sync_body["code"], json!("client_update_required"));
        } else {
            assert_eq!(sync_status, StatusCode::OK, "source={source_version_code}");
        }
    }
}

#[tokio::test]
async fn setup_status_exposes_only_the_empty_instance_contract() {
    let rig = Rig::new();

    let (status, body) = get_json(&rig.app, "/v1/setup-status", None).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body,
        json!({
            "protocol_version": 1,
            "capabilities": [
                "trusted_https_endpoint_v1",
                "device_sessions_v1",
                "membership_devices_v1",
                "atomic_bundle",
                "record_membership_author",
                "device_disaster_restore_v1",
                "validated_deferred_fulfillment_v1",
                "causal_sync_v2",
                "sync_heartbeat_v1",
                "causal_media_identity_v1",
                "nursing_plan_intent_v1",
                "restore_authority_v1",
            ],
            "family_state": "empty",
        })
    );
}

#[tokio::test]
async fn setup_status_switches_to_configured_without_exposing_family_metadata() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "setup-status-owner",
        "setup-status-owner-request-000001",
    )
    .await;
    assert!(created["family_id"].is_string());

    let (status, body) = get_json(&rig.app, "/v1/setup-status", None).await;

    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        body,
        json!({
            "protocol_version": 1,
            "capabilities": [
                "trusted_https_endpoint_v1",
                "device_sessions_v1",
                "membership_devices_v1",
                "atomic_bundle",
                "record_membership_author",
                "device_disaster_restore_v1",
                "validated_deferred_fulfillment_v1",
                "causal_sync_v2",
                "sync_heartbeat_v1",
                "causal_media_identity_v1",
                "nursing_plan_intent_v1",
                "restore_authority_v1",
            ],
            "family_state": "configured",
        })
    );
}

#[tokio::test]
async fn authenticated_sync_handshake_derives_principal_and_transport_contract() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "handshake-owner-device",
        "handshake-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/sync/handshake",
        Some(token),
        json!({
            "protocol_version": 1,
            "required_capabilities": ["causal_sync_v2", "nursing_plan_intent_v1"],
        }),
    )
    .await;

    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["protocol_version"], json!(1));
    assert_eq!(body["server_version"], json!(VERSION));
    assert_eq!(body["ready"], json!(true));
    assert_eq!(body["principal"]["membership_id"], owner["membership_id"]);
    assert_eq!(body["principal"]["device_id"], owner["device_id"]);
    assert_eq!(body["principal"]["role"], json!("owner"));
    assert!(body["directory_generation"]
        .as_str()
        .is_some_and(|value| value.len() == 64));
    assert_eq!(body["limits"]["pull_page_max_entities"], json!(200));
    assert_eq!(
        body["limits"]["pull_page_max_encoded_bytes"],
        json!(9 * 1024 * 1024)
    );
    assert_eq!(
        body["limits"]["pull_page_max_decoded_bytes"],
        json!(8 * 1024 * 1024)
    );
    assert_eq!(body["limits"]["pull_max_pages"], json!(500));
    assert_eq!(body["limits"]["commit_batch_max_units"], json!(64));
    assert_eq!(body["limits"]["media_max_bytes"], json!(8));
    assert_eq!(
        body["compression"]["pull_response"],
        json!(["gzip", "identity"]),
    );
    assert_eq!(body["retry_hints"]["retry_after"], json!(true));
    assert_eq!(
        body["capabilities"],
        json!(["causal_sync_v2", "nursing_plan_intent_v1"]),
    );
}

#[tokio::test]
async fn heartbeat_requires_a_live_device_token_like_other_sync_endpoints() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "heartbeat-auth-owner",
        "heartbeat-auth-request-0000000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();

    let (missing_status, missing) = get_json(&rig.app, "/v1/sync/heartbeat", None).await;
    assert_eq!(missing_status, StatusCode::UNAUTHORIZED, "{missing}");
    assert_eq!(missing["detail"], "Invalid or revoked token");
    let (bad_status, bad) =
        get_json(&rig.app, "/v1/sync/heartbeat", Some("not-a-device-token")).await;
    assert_eq!(bad_status, StatusCode::UNAUTHORIZED, "{bad}");

    // A joined member device probes fine until its device is revoked, then the
    // terminal code matches the other authenticated sync endpoints exactly.
    let member = approve_new_member(&rig.app, owner_token, "heartbeat-auth-member-phone").await;
    let member_token = member["access_token"].as_str().unwrap();
    assert_eq!(
        get_json(&rig.app, "/v1/sync/heartbeat", Some(member_token))
            .await
            .0,
        StatusCode::OK,
    );
    let revoke_path = format!(
        "/v1/family/devices/{}/revoke",
        member["device_id"].as_str().unwrap()
    );
    let (revoke_status, revoke) = json_request(
        &rig.app,
        Method::POST,
        &revoke_path,
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(revoke_status, StatusCode::OK, "{revoke}");
    let (removed_status, removed) =
        get_json(&rig.app, "/v1/sync/heartbeat", Some(member_token)).await;
    assert_eq!(removed_status, StatusCode::UNAUTHORIZED, "{removed}");
    assert_eq!(removed["code"], "device_removed");
}

#[tokio::test]
async fn heartbeat_returns_the_closed_three_key_probe_snapshot() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "heartbeat-owner",
        "heartbeat-owner-request-0000000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    let (status, before) = get_json(&rig.app, "/v1/sync/heartbeat", Some(token)).await;
    assert_eq!(status, StatusCode::OK, "{before}");
    // Closed key set: exactly {generation, head_rev, directory_generation}.
    let keys = before
        .as_object()
        .expect("heartbeat body is an object")
        .keys()
        .cloned()
        .collect::<BTreeSet<_>>();
    assert_eq!(
        keys,
        BTreeSet::from([
            "directory_generation".to_owned(),
            "generation".to_owned(),
            "head_rev".to_owned(),
        ])
    );
    assert_eq!(before["generation"], owner["generation"]);
    assert_eq!(before["directory_generation"].as_str().unwrap().len(), 64);
    let head_before = before["head_rev"].as_i64().unwrap();

    // Committing a family fact advances the watermark by exactly one.
    let baby_id = Uuid::new_v4();
    let (status, commit) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "baby",
            baby_id,
            causal_baby_root("年年", None, 10),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{commit}");

    let (status, after) = get_json(&rig.app, "/v1/sync/heartbeat", Some(token)).await;
    assert_eq!(status, StatusCode::OK, "{after}");
    assert_eq!(after["head_rev"].as_i64().unwrap(), head_before + 1);
    assert_eq!(after["generation"], owner["generation"]);

    // The directory digest is the same one the handshake advertises.
    let (_, handshake) = json_request(
        &rig.app,
        Method::POST,
        "/v1/sync/handshake",
        Some(token),
        json!({
            "protocol_version": 1,
            "required_capabilities": ["causal_sync_v2", "nursing_plan_intent_v1"],
        }),
    )
    .await;
    assert_eq!(
        handshake["directory_generation"],
        after["directory_generation"],
    );
}

#[tokio::test]
async fn heartbeat_wait_parameter_is_accepted_but_ignored_in_v1() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "heartbeat-wait-owner",
        "heartbeat-wait-request-0000000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    let (_, plain) = get_json(&rig.app, "/v1/sync/heartbeat", Some(token)).await;
    // Every wait value — including a would-be long-poll 30 — is treated as 0.
    for wait in ["0", "30", "120"] {
        let (status, body) = get_json(
            &rig.app,
            &format!("/v1/sync/heartbeat?wait={wait}"),
            Some(token),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "wait={wait}: {body}");
        assert_eq!(body, plain, "wait={wait} must behave exactly like wait=0");
    }
    let (malformed_status, _) =
        get_json(&rig.app, "/v1/sync/heartbeat?wait=soon", Some(token)).await;
    assert_eq!(malformed_status, StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn heartbeat_stays_responsive_while_commits_are_in_flight() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "heartbeat-lock-owner",
        "heartbeat-lock-request-0000000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();

    let (_, initial) = get_json(&rig.app, "/v1/sync/heartbeat", Some(&token)).await;
    let head_initial = initial["head_rev"].as_i64().unwrap();
    let baby_id = Uuid::new_v4();
    let (status, seed) = causal_commit_units(
        &rig.app,
        &token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "baby",
            baby_id,
            causal_baby_root("年年", None, 10),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{seed}");

    // Race a burst of single-unit commits against sequential probes on the same
    // family: the probe must never stall behind the whole storm — its family
    // lock hold is only the single-row watermark read plus directory digest.
    const COMMIT_BATCHES: usize = 12;
    let mut commits = Vec::new();
    for index in 0..COMMIT_BATCHES {
        let unit = causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            Uuid::new_v4(),
            causal_formula_root_at(baby_id, "concurrent", 60, 1_700_000_100, 100 + index as i64),
            vec![],
            false,
        );
        let commit_app = rig.app.clone();
        let commit_token = token.clone();
        commits.push(tokio::spawn(async move {
            causal_commit_units(&commit_app, &commit_token, vec![unit]).await
        }));
    }
    let probe_app = rig.app.clone();
    let probe_token = token.clone();
    let probes = tokio::spawn(async move {
        let mut observed = Vec::new();
        for _ in 0..COMMIT_BATCHES {
            let (status, body) =
                get_json(&probe_app, "/v1/sync/heartbeat", Some(&probe_token)).await;
            assert_eq!(status, StatusCode::OK, "{body}");
            assert_eq!(body.as_object().unwrap().len(), 3, "{body}");
            observed.push(body["head_rev"].as_i64().unwrap());
        }
        observed
    });
    let observed = probes.await.unwrap();
    for commit in commits {
        let (status, body) = commit.await.unwrap();
        assert_eq!(status, StatusCode::OK, "{body}");
    }

    // Every probe saw a watermark between the seed and the final head, and the
    // watermark never moved backwards between two probes of the same device.
    // The final head is only bounded from below: each accepted unit bumps the
    // watermark at least once, and auto near-neighbor alignment may add more.
    let (_, final_probe) = get_json(&rig.app, "/v1/sync/heartbeat", Some(&token)).await;
    let head_final = final_probe["head_rev"].as_i64().unwrap();
    assert!(
        head_final >= head_initial + 1 + COMMIT_BATCHES as i64,
        "watermark did not advance per accepted unit: {head_final}"
    );
    let mut previous = head_initial;
    for rev in &observed {
        assert!(rev >= &previous, "probe watermark regressed: {observed:?}");
        assert!(
            rev <= &head_final,
            "probe watermark above final: {observed:?}"
        );
        previous = *rev;
    }
}

#[tokio::test]
async fn heartbeat_rate_limit_is_dedicated_and_scoped_per_device() {
    let rig = Rig::with_config(|config| {
        config.heartbeat_rate_limit = RateLimitConfig {
            max_attempts: 2,
            window_seconds: 60,
        };
    });
    let owner = create_family(
        &rig.app,
        "heartbeat-limit-owner",
        "heartbeat-limit-request-000000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    assert_eq!(
        get_json(&rig.app, "/v1/sync/heartbeat", Some(owner_token))
            .await
            .0,
        StatusCode::OK,
    );
    assert_eq!(
        get_json(&rig.app, "/v1/sync/heartbeat", Some(owner_token))
            .await
            .0,
        StatusCode::OK,
    );
    let (limited, body) = get_json(&rig.app, "/v1/sync/heartbeat", Some(owner_token)).await;
    assert_eq!(limited, StatusCode::TOO_MANY_REQUESTS, "{body}");

    // The dedicated scope is per device: another joined device keeps probing,
    // and the exhausted device neither locks the family nor the create budget.
    let member = approve_new_member(&rig.app, owner_token, "heartbeat-limit-member-phone").await;
    let member_token = member["access_token"].as_str().unwrap();
    assert_eq!(
        get_json(&rig.app, "/v1/sync/heartbeat", Some(member_token))
            .await
            .0,
        StatusCode::OK,
    );
    assert_eq!(
        get_json(&rig.app, "/v1/sync/heartbeat", Some(owner_token))
            .await
            .0,
        StatusCode::TOO_MANY_REQUESTS,
    );
}

#[tokio::test]
async fn ordinary_pull_negotiates_equivalent_bounded_gzip_and_identity_pages() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "gzip-pull-owner-device",
        "gzip-pull-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let uri = format!("/v1/pull?cursor=0&generation={generation}&page_index=0");

    let identity = request_with_headers(
        &rig.app,
        Method::GET,
        &uri,
        Some(token),
        Body::empty(),
        None,
        &[(ACCEPT_ENCODING.as_str(), "identity")],
    )
    .await;
    assert_eq!(identity.status(), StatusCode::OK);
    assert!(identity.headers().get(CONTENT_ENCODING).is_none());
    assert_eq!(
        identity.headers()[VARY],
        "Accept-Encoding, X-Lezi-Media-Identity"
    );
    let identity_bytes = identity.into_body().collect().await.unwrap().to_bytes();

    let gzip = request_with_headers(
        &rig.app,
        Method::GET,
        &uri,
        Some(token),
        Body::empty(),
        None,
        &[(ACCEPT_ENCODING.as_str(), "gzip")],
    )
    .await;
    assert_eq!(gzip.status(), StatusCode::OK);
    assert_eq!(gzip.headers()[CONTENT_ENCODING], "gzip");
    assert_eq!(
        gzip.headers()[VARY],
        "Accept-Encoding, X-Lezi-Media-Identity"
    );
    let gzip_bytes = gzip.into_body().collect().await.unwrap().to_bytes();
    assert!(gzip_bytes.len() <= 9 * 1024 * 1024);
    let mut decoded = Vec::new();
    GzDecoder::new(gzip_bytes.as_ref())
        .read_to_end(&mut decoded)
        .unwrap();
    assert!(decoded.len() <= 8 * 1024 * 1024);
    assert_eq!(decoded, identity_bytes);
    let page: Value = serde_json::from_slice(&decoded).unwrap();
    assert_eq!(page["page_index"], 0);
}

#[tokio::test]
async fn pull_generation_drift_is_full_resync_not_empty_success() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "pull-gen-drift-owner",
        "pull-gen-drift-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();

    let (ok_status, ok_page) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor=0&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(ok_status, StatusCode::OK, "{ok_page}");
    let keys = ok_page
        .as_object()
        .unwrap()
        .keys()
        .cloned()
        .collect::<BTreeSet<_>>();
    assert_eq!(
        keys,
        BTreeSet::from([
            "entities".to_owned(),
            "cursor".to_owned(),
            "generation".to_owned(),
            "page_index".to_owned(),
            "has_more".to_owned(),
            "family_name".to_owned(),
        ])
    );
    assert_eq!(ok_page["entities"], json!([]));
    assert_eq!(ok_page["has_more"], false);
    let tip = ok_page["cursor"].as_i64().unwrap();

    let restarted = rig.restart("generation-b");
    let (drift_status, drift) = get_json(
        &restarted,
        &format!("/v1/pull?cursor={tip}&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(drift_status, StatusCode::CONFLICT, "{drift}");
    assert_eq!(drift["detail"]["code"], "generation_changed");
    assert_eq!(drift["detail"]["action"], "full_resync");
    assert_eq!(drift["detail"]["reset_cursor"], 0);
    assert_eq!(drift["detail"]["server_generation"], "generation-b");
    assert!(drift.get("entities").is_none(), "{drift}");
}

#[tokio::test]
async fn restart_reuses_persisted_generation_and_continues_cursor() {
    let rig = Rig::with_unpinned_generation();
    let owner = create_family(
        &rig.app,
        "persist-gen-owner",
        "persist-gen-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap().to_owned();
    let baby_id = seed_baby(&rig.app, token).await;

    let (ok_status, ok_page) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor=0&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(ok_status, StatusCode::OK, "{ok_page}");
    let tip = ok_page["cursor"].as_i64().unwrap();
    assert!(tip > 0, "{ok_page}");
    assert!(
        ok_page["entities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|entity| entity["client_uuid"] == baby_id),
        "{ok_page}"
    );

    let persisted = fs::read_to_string(rig.directory.path().join("generation")).unwrap();
    assert_eq!(persisted.trim(), generation);

    let restarted = rig.restart_reusing_persisted_generation();
    let (status, page) = get_json(
        &restarted,
        &format!("/v1/pull?cursor={tip}&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{page}");
    assert_eq!(page["generation"], generation);
    assert_eq!(page["cursor"], tip);
    assert_eq!(page["has_more"], false);
    assert_eq!(page["entities"], json!([]));

    let persisted_again = fs::read_to_string(rig.directory.path().join("generation")).unwrap();
    assert_eq!(persisted_again.trim(), generation);

    let restarted_again = rig.restart_reusing_persisted_generation();
    let (again_status, again) = get_json(
        &restarted_again,
        &format!("/v1/pull?cursor={tip}&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(again_status, StatusCode::OK, "{again}");
    assert_eq!(again["generation"], generation);
    assert_eq!(again["cursor"], tip);
}

#[tokio::test]
async fn pull_live_census_is_opt_in_and_legacy_envelope_stays_closed() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "census-envelope-owner-device",
        "census-envelope-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    seed_record_with_id(&rig.app, token, &record_id, 2, record_payload(&baby_id)).await;

    // Without the flag the envelope is exactly the 0.4.5/0.4.6 closed object;
    // old clients requireExactKeys this set, so no extra key may appear.
    let (status, page) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor=0&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{page}");
    let keys = page
        .as_object()
        .unwrap()
        .keys()
        .cloned()
        .collect::<BTreeSet<_>>();
    assert_eq!(
        keys,
        BTreeSet::from([
            "cursor".to_owned(),
            "entities".to_owned(),
            "family_name".to_owned(),
            "generation".to_owned(),
            "has_more".to_owned(),
            "page_index".to_owned(),
        ])
    );

    // Opting in adds exactly one top-level key with the seven-type census.
    let (census_status, census_page) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor=0&generation={generation}&include_live_census=true"),
        Some(token),
    )
    .await;
    assert_eq!(census_status, StatusCode::OK, "{census_page}");
    let census_keys = census_page
        .as_object()
        .unwrap()
        .keys()
        .cloned()
        .collect::<BTreeSet<_>>();
    let mut expected_keys = keys;
    expected_keys.insert("live_census".to_owned());
    assert_eq!(census_keys, expected_keys);
    let census = census_page["live_census"].as_object().unwrap();
    assert_eq!(census.len(), 7);
    for entity_type in [
        "baby",
        "record",
        "media",
        "care_plan",
        "custom_item",
        "fulfillment_candidate",
        "wake_observation",
    ] {
        assert!(census.contains_key(entity_type), "{census:?}");
    }
    assert_eq!(census["baby"]["count"], 1);
    assert_eq!(
        census["baby"]["key_digest"],
        hex::encode(Sha256::digest(baby_id.as_bytes()))
    );
    assert_eq!(census["record"]["count"], 1);
    assert_eq!(
        census["record"]["key_digest"],
        hex::encode(Sha256::digest(record_id.as_bytes()))
    );
    // Types without live rows digest the empty byte string.
    assert_eq!(
        census["care_plan"]["key_digest"],
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert!(
        census["baby"].get("keys").is_none(),
        "census without include_live_keys must omit keys"
    );

    let (keys_status, keys_page) = get_json(
        &rig.app,
        &format!(
            "/v1/pull?cursor=0&generation={generation}&include_live_census=true&include_live_keys=true&live_key_types=record"
        ),
        Some(token),
    )
    .await;
    assert_eq!(keys_status, StatusCode::OK, "{keys_page}");
    let keyed = keys_page["live_census"].as_object().unwrap();
    assert_eq!(keyed.len(), 7);
    assert_eq!(keyed["record"]["keys"], json!([record_id]));
    assert!(
        keyed["baby"].get("keys").is_none(),
        "unrequested types must omit keys"
    );
}

/// Captures `tower_http=debug` trace lines so the ignored timing test below
/// can quote per-request server-side latencies with zero production-code
/// instrumentation (W0 measurement discipline).
#[derive(Clone)]
struct CapturedTrace(Arc<Mutex<Vec<u8>>>);

impl std::io::Write for CapturedTrace {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.0
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .extend_from_slice(buf);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for CapturedTrace {
    type Writer = CapturedTrace;

    fn make_writer(&'a self) -> Self::Writer {
        self.clone()
    }
}

fn install_trace_capture() -> Arc<Mutex<Vec<u8>>> {
    static INSTALLED: OnceLock<Arc<Mutex<Vec<u8>>>> = OnceLock::new();
    INSTALLED
        .get_or_init(|| {
            let buffer = Arc::new(Mutex::new(Vec::new()));
            let subscriber = tracing_subscriber::fmt()
                .with_env_filter(tracing_subscriber::EnvFilter::new("tower_http=debug"))
                .with_writer(CapturedTrace(buffer.clone()))
                .finish();
            let _ = tracing::subscriber::set_global_default(subscriber);
            buffer
        })
        .clone()
}

/// Strips ANSI SGR sequences (`\x1b[…m`) that the fmt layer emits by default.
fn strip_ansi(raw: &str) -> String {
    let mut clean = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(ch) = chars.next() {
        if ch == '\u{1b}' {
            // Skip the escape introducer through the terminating letter.
            for tail in chars.by_ref() {
                if tail.is_ascii_alphabetic() {
                    break;
                }
            }
        } else {
            clean.push(ch);
        }
    }
    clean
}

/// Parses `latency=…` out of captured tower-http on_response lines for pull
/// requests, in emission order. Returns an empty vec when nothing parsed so
/// callers can fall back to wall-clock evidence instead of failing.
fn captured_pull_latencies(buffer: &Mutex<Vec<u8>>) -> Vec<Duration> {
    let raw = buffer
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
        .clone();
    let mut latencies = Vec::new();
    for line in String::from_utf8_lossy(&raw).lines() {
        let line = strip_ansi(line);
        if !line.contains("/v1/pull") || !line.contains("finished processing request") {
            continue;
        }
        let Some(latency_start) = line.find("latency=") else {
            continue;
        };
        let Some(duration) = parse_trace_latency(&line[latency_start + "latency=".len()..]) else {
            continue;
        };
        latencies.push(duration);
    }
    latencies
}

/// Parses a `tracing`-rendered duration such as `1.416 ms`, `583 µs`, or
/// `21ms` (the fmt layer separates value and unit with a space).
fn parse_trace_latency(raw: &str) -> Option<Duration> {
    let digits_end = raw.find(|c: char| !c.is_ascii_digit() && c != '.')?;
    if digits_end == 0 {
        return None;
    }
    let value: f64 = raw[..digits_end].parse().ok()?;
    let unit: String = raw[digits_end..]
        .chars()
        .skip_while(|c| c.is_whitespace())
        .take_while(|c| c.is_alphabetic())
        .collect();
    let multiplier = match unit.as_str() {
        "ns" => 1.0,
        "us" | "µs" => 1_000.0,
        "ms" => 1_000_000.0,
        "s" => 1_000_000_000.0,
        _ => return None,
    };
    Some(Duration::from_nanos((value * multiplier) as u64))
}

fn median_of(mut samples: Vec<Duration>) -> Duration {
    assert!(!samples.is_empty(), "no timing samples collected");
    samples.sort();
    samples[samples.len() / 2]
}

async fn timed_pull(app: &Router, uri: &str, token: &str) -> (Duration, StatusCode, Vec<u8>) {
    let started = Instant::now();
    let response = request(app, Method::GET, uri, Some(token), Body::empty(), None).await;
    let elapsed = started.elapsed();
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .unwrap()
        .to_bytes()
        .to_vec();
    (elapsed, status, bytes)
}

/// W0 timing evidence (ticket 01): steady-state quiet-round pull cost with
/// `include_live_census` on vs off, end-to-end through the real HTTP app over
/// a seeded 1k-row family. Ignored so default gates stay fast; the numbers
/// only count when taken in the shipped release profile on a quiet machine:
///
/// ```text
/// cargo test --release --test api pull_census_on_off_end_to_end_delta -- --ignored --nocapture
/// ```
#[tokio::test]
#[ignore = "timing evidence, not a gate: cargo test --release --test api pull_census_on_off_end_to_end_delta -- --ignored --nocapture"]
async fn pull_census_on_off_end_to_end_delta() {
    let trace_buffer = install_trace_capture();
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "census-timing-owner-device",
        "census-timing-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    seed_baby(&rig.app, token).await;

    // Seed 1k live rows directly into entities — the census is a pure read
    // over that table, so the seed shape is what the measurement controls.
    let rows = 1_000usize;
    let db_path = rig.directory.path().join("lezi.db");
    {
        let connection = Connection::open(&db_path).unwrap();
        let mut insert = connection
            .prepare_cached(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at,
                    deleted_at, payload_json, rev
                )
                SELECT families.id, 'record', ?1, 1700000000000, NULL, '{}', ?2
                FROM families LIMIT 1
                ",
            )
            .unwrap();
        connection.execute_batch("BEGIN IMMEDIATE").unwrap();
        for index in 0..rows {
            insert
                .execute(params![Uuid::new_v4().to_string(), (index + 1) as i64])
                .unwrap();
        }
        connection.execute_batch("COMMIT").unwrap();
    }

    // Quiet round: pull at head returns the empty page (plus the census when
    // opted in) — that is exactly where the current code pays the rebuild.
    let head: i64 = {
        let connection = Connection::open(&db_path).unwrap();
        connection
            .query_row("SELECT rev FROM family_meta LIMIT 1", [], |row| row.get(0))
            .unwrap()
    };
    let census_uri = format!(
        "/v1/pull?cursor={head}&generation={generation}&page_index=0&include_live_census=true"
    );
    let plain_uri = format!("/v1/pull?cursor={head}&generation={generation}&page_index=0");

    let iterations = 51usize;
    let warmups = 3usize;
    let mut cold_first: Option<Duration> = None;
    let mut census_wall = Vec::new();
    let mut plain_wall = Vec::new();
    let mut census_reference: Option<Vec<u8>> = None;
    let mut plain_reference: Option<Vec<u8>> = None;
    let mut issued_pulls = 0usize;

    for index in 0..(iterations + warmups) {
        let measuring = index >= warmups;
        let (census_elapsed, census_status, census_bytes) =
            timed_pull(&rig.app, &census_uri, token).await;
        assert_eq!(
            census_status,
            StatusCode::OK,
            "{}",
            String::from_utf8_lossy(&census_bytes)
        );
        issued_pulls += 1;
        if cold_first.is_none() {
            cold_first = Some(census_elapsed);
        }
        let (plain_elapsed, plain_status, plain_bytes) =
            timed_pull(&rig.app, &plain_uri, token).await;
        assert_eq!(
            plain_status,
            StatusCode::OK,
            "{}",
            String::from_utf8_lossy(&plain_bytes)
        );
        issued_pulls += 1;
        if measuring {
            census_wall.push(census_elapsed);
            plain_wall.push(plain_elapsed);
        }
        match &census_reference {
            None => census_reference = Some(census_bytes),
            Some(reference) => assert_eq!(
                reference, &census_bytes,
                "census-on quiet round must be byte-stable"
            ),
        }
        match &plain_reference {
            None => plain_reference = Some(plain_bytes),
            Some(reference) => assert_eq!(
                reference, &plain_bytes,
                "census-off quiet round must be byte-stable"
            ),
        }
    }

    let census_median = median_of(census_wall);
    let plain_median = median_of(plain_wall);
    println!(
        "pull_census_on_off_end_to_end_delta: rows={} iterations={} warmups={} \
         census_on_median={:.3?} census_off_median={:.3?} delta={:.3?} \
         cold_first_census_pull={:.3?}",
        rows + 1,
        iterations,
        warmups,
        census_median,
        plain_median,
        census_median.saturating_sub(plain_median),
        cold_first.unwrap(),
    );

    // Corroborate with the tower-http server-side latencies (zero production
    // instrumentation): the captured lines follow exact issue order — one
    // census-on request then one census-off request per round.
    let latencies = captured_pull_latencies(&trace_buffer);
    let trace_census: Vec<Duration> = latencies.iter().step_by(2).skip(warmups).copied().collect();
    let trace_plain: Vec<Duration> = latencies
        .iter()
        .skip(1)
        .step_by(2)
        .skip(warmups)
        .copied()
        .collect();
    if trace_census.len() == iterations && trace_plain.len() == iterations {
        // tower-http logs whole milliseconds by default (LatencyUnit::Millis),
        // so these lines only corroborate the wall-clock deltas at ms
        // granularity; the wall-clock medians above are the sub-ms evidence.
        let trace_census_median = median_of(trace_census);
        let trace_plain_median = median_of(trace_plain);
        println!(
            "pull_census_on_off_end_to_end_delta(trace, whole-ms): \
             census_on_median={:.3?} census_off_median={:.3?}",
            trace_census_median, trace_plain_median,
        );
    } else {
        let captured = trace_buffer
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let sample = strip_ansi(&String::from_utf8_lossy(&captured));
        let first_finished = sample
            .lines()
            .find(|line| line.contains("finished processing request"))
            .unwrap_or("");
        println!(
            "pull_census_on_off_end_to_end_delta(trace): captured {} pull lines \
             for {} issued pulls ({} captured bytes; first finished line: {:?}); \
             wall-clock evidence above stands alone",
            latencies.len(),
            issued_pulls,
            captured.len(),
            first_finished
                .get(..first_finished.len().min(300))
                .unwrap_or(""),
        );
    }

    // The timed requests are real, distinct envelopes: the census flag adds
    // exactly the live_census object and never changes anything else.
    let census_page: Value = serde_json::from_slice(&census_reference.unwrap()).unwrap();
    let plain_page: Value = serde_json::from_slice(&plain_reference.unwrap()).unwrap();
    assert_eq!(census_page["entities"], plain_page["entities"]);
    assert_eq!(census_page["cursor"], plain_page["cursor"]);
    let census = census_page["live_census"]
        .as_object()
        .expect("census present");
    assert_eq!(census["record"]["count"], rows as u64);
}

/// W1 golden envelope (ticket 02): with the per-(family, head) census cache
/// live, the same quiet-round request must return byte-identical responses
/// whether the census was freshly rebuilt (cache cold) or served from the
/// cache (cache hot), the census flag must add exactly the `live_census` key,
/// and a page served after a fresh write (new head, cache invalidated) stays
/// consistent with a full recompute.
#[tokio::test]
async fn pull_census_cache_keeps_envelopes_byte_identical() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "census-cache-owner-device",
        "census-cache-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    seed_record_with_id(&rig.app, token, &record_id, 2, record_payload(&baby_id)).await;

    let head: i64 = {
        let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
        connection
            .query_row("SELECT rev FROM family_meta LIMIT 1", [], |row| row.get(0))
            .unwrap()
    };
    let census_uri = format!(
        "/v1/pull?cursor={head}&generation={generation}&page_index=0&include_live_census=true"
    );
    let plain_uri = format!("/v1/pull?cursor={head}&generation={generation}&page_index=0");

    let (_, cold_status, cold_census) = timed_pull(&rig.app, &census_uri, token).await;
    assert_eq!(cold_status, StatusCode::OK);
    let (_, hot_status, hot_census) = timed_pull(&rig.app, &census_uri, token).await;
    assert_eq!(hot_status, StatusCode::OK);
    let (_, hot_plain_status, hot_plain) = timed_pull(&rig.app, &plain_uri, token).await;
    assert_eq!(hot_plain_status, StatusCode::OK);
    // Cache-hot serves exactly the cache-cold bytes (and vice versa).
    assert_eq!(cold_census, hot_census, "cache hit must be byte-identical");

    // The census flag adds exactly the live_census key and nothing else.
    let cold_page: Value = serde_json::from_slice(&cold_census).unwrap();
    let plain_page: Value = serde_json::from_slice(&hot_plain).unwrap();
    let mut census_keys: BTreeSet<_> = cold_page.as_object().unwrap().keys().cloned().collect();
    let plain_keys: BTreeSet<_> = plain_page.as_object().unwrap().keys().cloned().collect();
    assert!(census_keys.remove("live_census"));
    assert_eq!(census_keys, plain_keys);
    assert_eq!(cold_page["entities"], plain_page["entities"]);
    assert_eq!(cold_page["live_census"]["baby"]["count"], 1);
    assert_eq!(cold_page["live_census"]["record"]["count"], 1);

    // A new write invalidates the entry; the next census pull rebuilds at the
    // new head and is again byte-stable across repeated requests.
    seed_record_with_id(
        &rig.app,
        token,
        &Uuid::new_v4().to_string(),
        3,
        record_payload(&baby_id),
    )
    .await;
    let new_head: i64 = {
        let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
        connection
            .query_row("SELECT rev FROM family_meta LIMIT 1", [], |row| row.get(0))
            .unwrap()
    };
    assert!(new_head > head);
    let new_census_uri = format!(
        "/v1/pull?cursor={new_head}&generation={generation}&page_index=0&include_live_census=true"
    );
    let (_, _, rebuild) = timed_pull(&rig.app, &new_census_uri, token).await;
    let (_, _, hit) = timed_pull(&rig.app, &new_census_uri, token).await;
    assert_eq!(rebuild, hit, "post-write rebuild vs hit must be identical");
    let rebuilt_page: Value = serde_json::from_slice(&rebuild).unwrap();
    assert_eq!(rebuilt_page["live_census"]["record"]["count"], 2);
}

/// W1 residual-closure fallback (ticket 02, B6/C4): an empty-branch open
/// conflict left behind by pre-0.5 versions (seeded directly here) is closed
/// by the startup maintenance sweep of the restarted server, and the closure
/// keeps its delivery semantics: the caught-up pull-only device sees the head
/// move and receives the entity again without any conflict_summary.
#[tokio::test]
async fn startup_maintenance_closes_leftover_empty_open_conflicts() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "closure-owner-device",
        "closure-owner-request-000000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;

    let db_path = rig.directory.path().join("lezi.db");
    let synced_cursor: i64 = {
        let connection = Connection::open(&db_path).unwrap();
        connection
            .query_row("SELECT rev FROM family_meta LIMIT 1", [], |row| row.get(0))
            .unwrap()
    };

    // Seed the leftover the way an old server version or retention cleanup
    // can leave it: an open conflict whose branch set is already empty.
    let stable_version_id: String = {
        let connection = Connection::open(&db_path).unwrap();
        connection
            .query_row(
                "SELECT version_id FROM entity_stable_heads
                  WHERE entity_type = 'baby' AND client_uuid = ?1",
                rusqlite::params![baby_id],
                |row| row.get(0),
            )
            .unwrap()
    };
    {
        let connection = Connection::open(&db_path).unwrap();
        let family_id: String = connection
            .query_row("SELECT id FROM families LIMIT 1", [], |row| row.get(0))
            .unwrap();
        connection
            .execute(
                "INSERT INTO conflicts(
                    family_id, conflict_id, entity_type, client_uuid, base_version_id,
                    stable_version_id, status, kind, created_at, resolved_at
                 ) VALUES (?1, ?2, 'baby', ?3, NULL, ?4, 'open', 'tombstone_restore', 1, NULL)",
                rusqlite::params![
                    family_id,
                    Uuid::new_v4().to_string(),
                    baby_id,
                    stable_version_id
                ],
            )
            .unwrap();
    }

    // Restart on the same data root: the startup sweep fires immediately.
    let restarted = rig.restart(generation);
    let mut delivered = None;
    for _ in 0..200 {
        let (status, page) = get_json(
            &restarted,
            &format!("/v1/pull?cursor={synced_cursor}&generation={generation}"),
            Some(token),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{page}");
        let entities = page["entities"].as_array().unwrap();
        if !entities.is_empty() {
            delivered = Some(page);
            break;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    {
        let connection = Connection::open(&db_path).unwrap();
        let open_now: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM conflicts WHERE status = 'open'",
                [],
                |row| row.get(0),
            )
            .unwrap();
        eprintln!("DEBUG open conflicts after poll: {open_now}");
    }
    let page = delivered.expect("startup closure must advance the head and redeliver");
    assert_eq!(page["entities"].as_array().unwrap().len(), 1);
    let row = &page["entities"][0];
    assert_eq!(row["client_uuid"], baby_id);
    assert!(
        row.get("conflict_summary").is_none(),
        "closed leftover must not carry a conflict_summary: {row}"
    );

    // The conflict row itself is resolved in the database.
    let connection = Connection::open(&db_path).unwrap();
    let open_conflicts: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM conflicts WHERE status = 'open'",
            [],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(open_conflicts, 0);
}

#[tokio::test]
async fn ordinary_pull_rejects_unsupported_encoding_and_out_of_budget_page_index() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "gzip-boundary-owner-device",
        "gzip-boundary-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();

    for value in ["br", "gzip;q=0, identity;q=0", "gzip;q=bogus"] {
        let response = request_with_headers(
            &rig.app,
            Method::GET,
            &format!("/v1/pull?cursor=0&generation={generation}&page_index=0"),
            Some(token),
            Body::empty(),
            None,
            &[(ACCEPT_ENCODING.as_str(), value)],
        )
        .await;
        assert_eq!(
            response.status(),
            StatusCode::UNPROCESSABLE_ENTITY,
            "{value}"
        );
    }

    let last_allowed = request_with_headers(
        &rig.app,
        Method::GET,
        &format!("/v1/pull?cursor=0&generation={generation}&page_index=499"),
        Some(token),
        Body::empty(),
        None,
        &[(ACCEPT_ENCODING.as_str(), "identity")],
    )
    .await;
    assert_eq!(last_allowed.status(), StatusCode::OK);
    let last_allowed: Value =
        serde_json::from_slice(&last_allowed.into_body().collect().await.unwrap().to_bytes())
            .unwrap();
    assert_eq!(last_allowed["page_index"], 499);

    let over_budget = request_with_headers(
        &rig.app,
        Method::GET,
        &format!("/v1/pull?cursor=0&generation={generation}&page_index=500"),
        Some(token),
        Body::empty(),
        None,
        &[(ACCEPT_ENCODING.as_str(), "identity")],
    )
    .await;
    assert_eq!(over_budget.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let missing_page_index = request_with_headers(
        &rig.app,
        Method::GET,
        &format!("/v1/pull?cursor=0&generation={generation}"),
        Some(token),
        Body::empty(),
        None,
        &[(ACCEPT_ENCODING.as_str(), "identity")],
    )
    .await;
    assert_eq!(
        missing_page_index.status(),
        StatusCode::UNPROCESSABLE_ENTITY
    );
}

#[tokio::test]
async fn authenticated_sync_handshake_fails_closed_before_sync_work() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "handshake-fail-owner-device",
        "handshake-fail-owner-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    let (auth_status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/sync/handshake",
        None,
        json!({
            "protocol_version": 1,
            "required_capabilities": ["causal_sync_v2", "nursing_plan_intent_v1"],
        }),
    )
    .await;
    assert_eq!(auth_status, StatusCode::UNAUTHORIZED);

    for required_capabilities in [
        json!([]),
        json!(["causal_sync_v2"]),
        json!(["causal_versions", "source_relations", "wake_observation"]),
        json!(["causal_sync_v2", "future_extra"]),
        json!([
            "causal_versions",
            "source_relations",
            "wake_observation",
            "causal_sync_v2",
        ]),
    ] {
        let (mismatch_status, mismatch) = json_request(
            &rig.app,
            Method::POST,
            "/v1/sync/handshake",
            Some(token),
            json!({
                "protocol_version": 1,
                "required_capabilities": required_capabilities,
            }),
        )
        .await;
        assert_eq!(mismatch_status, StatusCode::CONFLICT, "{mismatch}");
        assert_eq!(mismatch["status"], json!("rejected"));
        assert_eq!(mismatch["error"]["code"], json!("capability_mismatch"));
        assert_eq!(mismatch["error"]["retryable"], json!(false));
    }

    fs::remove_dir_all(rig.directory.path().join("media")).unwrap();
    fs::write(rig.directory.path().join("media"), b"not-a-directory").unwrap();
    let (not_ready_status, not_ready) = json_request(
        &rig.app,
        Method::POST,
        "/v1/sync/handshake",
        Some(token),
        json!({
            "protocol_version": 1,
            "required_capabilities": ["causal_sync_v2", "nursing_plan_intent_v1"],
        }),
    )
    .await;
    assert_eq!(
        not_ready_status,
        StatusCode::SERVICE_UNAVAILABLE,
        "{not_ready}",
    );
    assert_eq!(not_ready["status"], json!("rejected"));
    assert_eq!(not_ready["error"]["code"], json!("not_ready"));
    assert_eq!(not_ready["error"]["retryable"], json!(false));
}

#[tokio::test]
async fn member_directory_generation_changes_only_with_directory_structure() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "handshake-generation-owner",
        "handshake-generation-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let handshake_body = json!({
        "protocol_version": 1,
        "required_capabilities": ["causal_sync_v2", "nursing_plan_intent_v1"],
    });

    let (_, first) = json_request(
        &rig.app,
        Method::POST,
        "/v1/sync/handshake",
        Some(token),
        handshake_body.clone(),
    )
    .await;
    rig.now.fetch_add(30, Ordering::SeqCst);
    let (_, after_authenticated_activity) = json_request(
        &rig.app,
        Method::POST,
        "/v1/sync/handshake",
        Some(token),
        handshake_body.clone(),
    )
    .await;
    assert_eq!(
        after_authenticated_activity["directory_generation"],
        first["directory_generation"],
    );

    approve_new_member(&rig.app, token, "handshake-generation-member").await;
    let (_, changed) = json_request(
        &rig.app,
        Method::POST,
        "/v1/sync/handshake",
        Some(token),
        handshake_body,
    )
    .await;
    assert_ne!(
        changed["directory_generation"],
        first["directory_generation"]
    );

    let (members_status, members) = get_json(&rig.app, "/v1/family/members", Some(token)).await;
    assert_eq!(members_status, StatusCode::OK, "{members}");
    assert_eq!(
        members["directory_generation"],
        changed["directory_generation"],
    );
}

#[tokio::test]
async fn disaster_restore_rejects_a_configured_server_even_with_the_root_password() {
    let root = "correct-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    create_family_with_root(
        &rig.app,
        "existing-owner",
        "existing-family-request-000000000001",
        root,
    )
    .await;

    let (status, body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-start-request-000000000001",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;

    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert!(body.to_string().contains("empty"), "{body}");
}

/// Seeds a verified app-update channel (min=8) on an empty server for restore version-gate tests.
fn seed_verified_app_update_on_empty(rig: &Rig, apk_bytes: &[u8]) {
    let sha256 = hex::encode(Sha256::digest(apk_bytes));
    fs::write(
        rig.directory.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 9,
            "version_name": "0.4.0",
            "min_supported_version_code": 8,
            "sha256": sha256,
        })
        .to_string(),
    )
    .unwrap();
    fs::write(rig.directory.path().join("app-release.apk"), apk_bytes).unwrap();
}

#[tokio::test]
async fn disaster_restore_write_paths_require_supported_client_version() {
    let root = "restore-version-gate-root-password";
    let apk_bytes = b"restore-version-gate-apk-bytes";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    seed_verified_app_update_on_empty(&rig, apk_bytes);

    let start_body = json!({
        "request_id": "restore-version-gate-start-000000000001",
        "family_id": Uuid::new_v4(),
        "family_name": "恢复家庭",
        "owner_display_name": "妈妈",
        "device_name": "恢复手机",
    });

    // Missing version header → same client_update_required as sync gate.
    let (missing_status, missing_body) = json_request_without_version(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        start_body.clone(),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(missing_status, StatusCode::FORBIDDEN, "{missing_body}");
    assert_eq!(missing_body["code"], json!("client_update_required"));

    // Below min → gate.
    let (low_status, low_body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        start_body.clone(),
        &[
            ("x-lezi-bootstrap-secret", root),
            ("x-lezi-client-version-code", "34"),
        ],
    )
    .await;
    assert_eq!(low_status, StatusCode::FORBIDDEN, "{low_body}");
    assert_eq!(low_body["code"], json!("client_update_required"));

    // At min → start allowed (empty family).
    let (ok_status, ok_body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        start_body,
        &[
            ("x-lezi-bootstrap-secret", root),
            ("x-lezi-client-version-code", "35"),
        ],
    )
    .await;
    assert!(
        ok_status == StatusCode::CREATED || ok_status == StatusCode::OK,
        "{ok_body}"
    );
    assert_ne!(ok_body["code"], json!("client_update_required"));
    let batch_id = ok_body["batch_id"].as_str().expect("batch_id");
    let recovery = ok_body["recovery_token"].as_str().expect("recovery_token");

    // Manifest write also gated (recovery token as bearer; low version still CUR).
    let (manifest_low_status, manifest_low_body) = json_request_with_headers(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/manifest"),
        Some(recovery),
        json!({
            "request_id": "restore-version-gate-manifest-00000001",
            "entities": [],
            "media": [],
        }),
        &[("x-lezi-client-version-code", "1")],
    )
    .await;
    assert_eq!(
        manifest_low_status,
        StatusCode::FORBIDDEN,
        "{manifest_low_body}"
    );
    assert_eq!(manifest_low_body["code"], json!("client_update_required"));

    // App-update routes remain ungated by min so force upgrade is not deadlocked
    // (no family session on empty server; version gate must not be the failure mode
    // for public health either).
    let (health_status, health_body) = get_json(&rig.app, "/health", None).await;
    assert_eq!(health_status, StatusCode::OK, "{health_body}");
}

#[tokio::test]
async fn disaster_restore_paired_generation_works_without_verified_channel() {
    // App35 with required capabilities works without optional update metadata.
    let root = "restore-failopen-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));

    let (status, body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-failopen-start-000000000001",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert!(
        status == StatusCode::CREATED || status == StatusCode::OK,
        "{body}"
    );
    assert_ne!(body["code"], json!("client_update_required"));
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn family_create_and_disaster_restore_have_exactly_one_provisioning_winner() {
    let root = "concurrent-provisioning-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let barrier = Arc::new(Barrier::new(2));

    let restore_app = rig.app.clone();
    let restore_barrier = barrier.clone();
    let restore = tokio::spawn(async move {
        restore_barrier.wait().await;
        json_request_with_headers(
            &restore_app,
            Method::POST,
            "/v1/disaster-restore/batches",
            None,
            json!({
                "request_id": "concurrent-restore-start-request-000001",
                "family_id": Uuid::new_v4(),
                "family_name": "恢复家庭",
                "owner_display_name": "妈妈",
                "device_name": "恢复手机",
            }),
            &[("x-lezi-bootstrap-secret", root)],
        )
        .await
        .0
    });
    let create_app = rig.app.clone();
    let create = tokio::spawn(async move {
        barrier.wait().await;
        json_request_with_headers(
            &create_app,
            Method::POST,
            "/v1/family/create",
            None,
            json!({
                "create_request_id": "concurrent-family-create-request-00001",
                "display_name": "另一位管理员",
                "device_name": "另一台手机",
                "family_name": "另一个家庭",
            }),
            &[("x-lezi-bootstrap-secret", root)],
        )
        .await
        .0
    });

    let statuses = [restore.await.unwrap(), create.await.unwrap()];
    assert_eq!(
        statuses
            .iter()
            .filter(|status| { **status == StatusCode::OK || **status == StatusCode::CREATED })
            .count(),
        1,
        "provisioning paths must have one winner: {statuses:?}",
    );
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == StatusCode::CONFLICT)
            .count(),
        1,
        "the losing provisioning path must fail closed: {statuses:?}",
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn concurrent_disaster_restore_starts_leave_one_complete_batch() {
    let root = "concurrent-restore-start-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let in_progress_sentinel = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(Uuid::new_v4().to_string());
    fs::create_dir_all(in_progress_sentinel.join("media")).unwrap();
    let in_progress_marker = in_progress_sentinel.join("media").join("partial-upload");
    fs::write(&in_progress_marker, b"still being written").unwrap();
    let barrier = Arc::new(Barrier::new(2));
    let mut tasks = Vec::new();
    for index in 0..2 {
        let app = rig.app.clone();
        let barrier = barrier.clone();
        tasks.push(tokio::spawn(async move {
            barrier.wait().await;
            json_request_with_headers(
                &app,
                Method::POST,
                "/v1/disaster-restore/batches",
                None,
                json!({
                    "request_id": format!("concurrent-restore-start-request-{index:08}"),
                    "family_id": Uuid::new_v4(),
                    "family_name": format!("恢复家庭{index}"),
                    "owner_display_name": "妈妈",
                    "device_name": format!("恢复手机{index}"),
                }),
                &[("x-lezi-bootstrap-secret", root)],
            )
            .await
        }));
    }
    let responses = [
        tasks.remove(0).await.unwrap(),
        tasks.remove(0).await.unwrap(),
    ];
    assert_eq!(
        responses
            .iter()
            .filter(|(status, _)| matches!(*status, StatusCode::OK | StatusCode::CREATED))
            .count(),
        1,
        "exactly one restore start should win: {responses:?}",
    );
    assert_eq!(
        responses
            .iter()
            .filter(|(status, _)| *status == StatusCode::CONFLICT)
            .count(),
        1,
        "the losing start should fail closed: {responses:?}",
    );
    let winner = responses
        .iter()
        .find(|(status, _)| matches!(*status, StatusCode::OK | StatusCode::CREATED))
        .unwrap();
    let batch_dir = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(winner.1["batch_id"].as_str().unwrap());
    assert!(batch_dir.join("journal.json").is_file());
    assert!(batch_dir.join("credential.sha256").is_file());
    assert!(
        in_progress_sentinel.is_dir(),
        "runtime start cleanup deleted a no-journal batch under construction",
    );
    assert_eq!(
        fs::read(in_progress_marker).unwrap(),
        b"still being written"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn concurrent_restore_manifest_replay_serializes_conflicting_content() {
    let root = "concurrent-restore-manifest-root";
    let family_id = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "concurrent-manifest-start-request-000001",
            "family_id": family_id,
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let batch_id = started["batch_id"].as_str().unwrap().to_owned();
    let recovery_token = started["recovery_token"].as_str().unwrap().to_owned();
    let barrier = Arc::new(Barrier::new(2));

    let mut tasks = Vec::new();
    for nickname in ["宝宝甲", "宝宝乙"] {
        let app = rig.app.clone();
        let barrier = barrier.clone();
        let batch_id = batch_id.clone();
        let recovery_token = recovery_token.clone();
        let baby_id = baby_id.clone();
        tasks.push(tokio::spawn(async move {
            barrier.wait().await;
            json_request(
                &app,
                Method::PUT,
                &format!("/v1/disaster-restore/batches/{batch_id}/manifest"),
                Some(&recovery_token),
                json!({
                    "request_id": "concurrent-manifest-put-request-0000001",
                    "entities": [{
                        "type": "baby",
                        "client_uuid": baby_id,
                        "updated_at": 1000,
                        "payload": baby_payload(nickname, None),
                    }],
                    "media": [],
                }),
            )
            .await
            .0
        }));
    }

    let statuses = [
        tasks.remove(0).await.unwrap(),
        tasks.remove(0).await.unwrap(),
    ];
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == StatusCode::OK)
            .count(),
        1,
        "one immutable manifest must win: {statuses:?}",
    );
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == StatusCode::CONFLICT)
            .count(),
        1,
        "conflicting replay must fail closed: {statuses:?}",
    );
}

#[tokio::test]
async fn disaster_restore_invalid_credentials_do_not_reveal_batch_existence() {
    let root = "restore-auth-oracle-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-auth-oracle-start-request-00001",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let existing_batch = started["batch_id"].as_str().expect("batch_id");
    let recovery_token = started["recovery_token"].as_str().expect("recovery_token");
    fs::remove_file(
        rig.directory
            .path()
            .join("disaster-restore")
            .join(existing_batch)
            .join("credential.sha256"),
    )
    .unwrap();
    let absent_batch = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let cases = [
        (Method::GET, "status".to_owned()),
        (Method::PUT, "manifest".to_owned()),
        (Method::PUT, format!("media/{media_id}")),
        (Method::POST, "cancel".to_owned()),
        (Method::POST, "commit".to_owned()),
    ];

    for (method, suffix) in cases {
        let (existing_status, existing_body) = json_request(
            &rig.app,
            method.clone(),
            &format!("/v1/disaster-restore/batches/{existing_batch}/{suffix}"),
            Some("invalid-recovery-token"),
            json!({}),
        )
        .await;
        let (absent_status, absent_body) = json_request(
            &rig.app,
            method,
            &format!("/v1/disaster-restore/batches/{absent_batch}/{suffix}"),
            Some("invalid-recovery-token"),
            json!({}),
        )
        .await;

        assert_eq!(
            existing_status,
            StatusCode::UNAUTHORIZED,
            "{suffix}: {existing_body}",
        );
        assert_eq!(absent_status, existing_status, "{suffix}: {absent_body}");
        assert_eq!(absent_body, existing_body, "{suffix}");
    }

    let (legacy_status, _) = get_json(
        &rig.app,
        &format!("/v1/disaster-restore/batches/{existing_batch}/status"),
        Some(recovery_token),
    )
    .await;
    assert_eq!(legacy_status, StatusCode::OK);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn one_hundred_thousand_valid_absent_restore_requests_leave_service_usable() {
    let root = "restore-cardinality-stress-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));

    for index in 1..=100_000 {
        let batch_id = Uuid::from_u128(index);
        let response = request(
            &rig.app,
            Method::GET,
            &format!("/v1/disaster-restore/batches/{batch_id}/status"),
            Some("invalid-recovery-token"),
            Body::empty(),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED, "{batch_id}");
    }

    let (start_status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-after-cardinality-stress-request-01",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(start_status, StatusCode::CREATED, "{started}");
    let (status, _) = get_json(
        &rig.app,
        &format!(
            "/v1/disaster-restore/batches/{}/status",
            started["batch_id"].as_str().unwrap(),
        ),
        started["recovery_token"].as_str(),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
}

#[tokio::test]
async fn disaster_restore_auth_hides_damaged_journals_from_invalid_credentials() {
    let root = "restore-damaged-journal-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-damaged-journal-start-request-01",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let batch_id = started["batch_id"].as_str().expect("batch_id");
    let recovery_token = started["recovery_token"].as_str().expect("recovery_token");
    let journal_path = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(batch_id)
        .join("journal.json");
    fs::write(&journal_path, b"{").unwrap();

    let path = format!("/v1/disaster-restore/batches/{batch_id}/status");
    let (invalid_status, invalid_body) =
        get_json(&rig.app, &path, Some("invalid-recovery-token")).await;
    let (absent_status, absent_body) = get_json(
        &rig.app,
        &format!("/v1/disaster-restore/batches/{}/status", Uuid::new_v4()),
        Some("invalid-recovery-token"),
    )
    .await;
    assert_eq!(invalid_status, StatusCode::UNAUTHORIZED, "{invalid_body}");
    assert_eq!((invalid_status, invalid_body), (absent_status, absent_body));

    let (authorized_status, _) = get_json(&rig.app, &path, Some(recovery_token)).await;
    assert_eq!(authorized_status, StatusCode::INTERNAL_SERVER_ERROR);
}

#[tokio::test]
async fn disaster_restore_auth_hides_incompatible_journals_from_invalid_credentials() {
    let root = "restore-incompatible-journal-root";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-incompatible-start-request-000001",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let batch_id = started["batch_id"].as_str().expect("batch_id");
    let recovery_token = started["recovery_token"].as_str().expect("recovery_token");
    let journal_path = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(batch_id)
        .join("journal.json");
    let mut journal: Value = serde_json::from_slice(&fs::read(&journal_path).unwrap()).unwrap();
    journal["protocol_version"] = json!(999);
    fs::write(&journal_path, serde_json::to_vec(&journal).unwrap()).unwrap();

    let path = format!("/v1/disaster-restore/batches/{batch_id}/status");
    let (invalid_status, invalid_body) =
        get_json(&rig.app, &path, Some("invalid-recovery-token")).await;
    let (absent_status, absent_body) = get_json(
        &rig.app,
        &format!("/v1/disaster-restore/batches/{}/status", Uuid::new_v4()),
        Some("invalid-recovery-token"),
    )
    .await;
    assert_eq!(invalid_status, StatusCode::UNAUTHORIZED, "{invalid_body}");
    assert_eq!((invalid_status, invalid_body), (absent_status, absent_body));

    let (authorized_status, _) = get_json(&rig.app, &path, Some(recovery_token)).await;
    assert_eq!(authorized_status, StatusCode::CONFLICT);
}

#[tokio::test]
async fn disaster_restore_auth_hides_journal_io_failures_from_invalid_credentials() {
    let root = "restore-unreadable-journal-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-unreadable-journal-start-request-01",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let batch_id = started["batch_id"].as_str().expect("batch_id");
    let recovery_token = started["recovery_token"].as_str().expect("recovery_token");
    let journal_path = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(batch_id)
        .join("journal.json");
    fs::remove_file(&journal_path).unwrap();
    fs::create_dir(&journal_path).unwrap();

    let path = format!("/v1/disaster-restore/batches/{batch_id}/status");
    let (invalid_status, invalid_body) =
        get_json(&rig.app, &path, Some("invalid-recovery-token")).await;
    let (absent_status, absent_body) = get_json(
        &rig.app,
        &format!("/v1/disaster-restore/batches/{}/status", Uuid::new_v4()),
        Some("invalid-recovery-token"),
    )
    .await;
    assert_eq!(invalid_status, StatusCode::UNAUTHORIZED, "{invalid_body}");
    assert_eq!((invalid_status, invalid_body), (absent_status, absent_body));

    let (authorized_status, _) = get_json(&rig.app, &path, Some(recovery_token)).await;
    assert_eq!(authorized_status, StatusCode::INTERNAL_SERVER_ERROR);
}

#[tokio::test]
async fn disaster_restore_auth_rejects_valid_shape_envelope_drift_without_an_oracle() {
    let root = "restore-envelope-drift-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-envelope-drift-start-request-0001",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let batch_id = started["batch_id"].as_str().expect("batch_id");
    let recovery_token = started["recovery_token"].as_str().expect("recovery_token");
    let attacker_token = "attacker-controlled-recovery-token";
    fs::write(
        rig.directory
            .path()
            .join("disaster-restore")
            .join(batch_id)
            .join("credential.sha256"),
        hex::encode(Sha256::digest(attacker_token.as_bytes())),
    )
    .unwrap();
    let path = format!("/v1/disaster-restore/batches/{batch_id}/status");

    let (drift_status, drift_body) = get_json(&rig.app, &path, Some(attacker_token)).await;
    let (absent_status, absent_body) = get_json(
        &rig.app,
        &format!("/v1/disaster-restore/batches/{}/status", Uuid::new_v4()),
        Some(attacker_token),
    )
    .await;
    assert_eq!(drift_status, StatusCode::UNAUTHORIZED, "{drift_body}");
    assert_eq!((drift_status, drift_body), (absent_status, absent_body));

    let (authorized_status, _) = get_json(&rig.app, &path, Some(recovery_token)).await;
    assert_eq!(authorized_status, StatusCode::INTERNAL_SERVER_ERROR);
}

#[tokio::test]
async fn disaster_restore_start_retry_repairs_the_journal_first_crash_window() {
    let root = "restore-journal-first-retry-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let start = json!({
        "request_id": "restore-journal-first-retry-request-0001",
        "family_id": Uuid::new_v4(),
        "family_name": "恢复家庭",
        "owner_display_name": "妈妈",
        "device_name": "恢复手机",
    });
    let (_, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        start.clone(),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let batch_id = started["batch_id"].as_str().expect("batch_id");
    let envelope = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(batch_id)
        .join("credential.sha256");
    fs::remove_file(&envelope).unwrap();

    let restarted = rig.restart_with_config("generation-journal-first-retry", |config| {
        config.bootstrap_secret = Some(root.to_owned());
    });
    let (retry_status, retry) = json_request_with_headers(
        &restarted,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        start,
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(retry["batch_id"], started["batch_id"]);
    assert!(envelope.is_file());
}

#[test]
fn disaster_restore_startup_removes_credential_only_orphans() {
    let rig = Rig::new();
    let orphan = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(Uuid::new_v4().to_string());
    fs::create_dir_all(&orphan).unwrap();
    fs::write(orphan.join("credential.sha256"), "0".repeat(64)).unwrap();

    let _restarted = rig.restart("generation-after-credential-orphan");

    assert!(!orphan.exists());
}

#[tokio::test]
async fn disaster_restore_is_staged_restart_safe_atomic_and_reauthors_history() {
    let root = "correct-root-password";
    let family_id = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bytes = vec![1_u8, 2, 3];
    let digest = hex::encode(Sha256::digest(&bytes));
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));

    let (start_status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-start-request-000000000002",
            "family_id": family_id,
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(start_status, StatusCode::CREATED, "{started}");
    let batch_id = started["batch_id"].as_str().unwrap();
    let recovery_token = started["recovery_token"].as_str().unwrap();
    assert!(!started.to_string().contains(root));
    let journal = fs::read_to_string(
        rig.directory
            .path()
            .join("disaster-restore")
            .join(batch_id)
            .join("journal.json"),
    )
    .unwrap();
    assert!(
        !journal.contains(root),
        "root password must never be journaled"
    );
    assert!(
        !journal.contains(recovery_token),
        "restore credential must be stored only as a hash"
    );

    let (manifest_status, manifest) = json_request(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/manifest"),
        Some(recovery_token),
        json!({
            "request_id": "restore-manifest-request-0000000001",
            "entities": [
                {
                    "type": "baby",
                    "client_uuid": baby_id,
                    "updated_at": 1000,
                    "payload": baby_payload("宝宝", None),
                },
                {
                    "type": "record",
                    "client_uuid": record_id,
                    "updated_at": 1001,
                    "payload": {
                        "baby_client_uuid": baby_id,
                        "type": "formula",
                        "custom_item_client_uuid": null,
                        "timestamp": 100,
                        "end_timestamp": null,
                        "note": null,
                        "payload_json": {"amount_ml": 120},
                        "schema_version": 2,
                        "created_by_membership_id": "old-owner-membership"
                    }
                },
                {
                    "type": "media",
                    "client_uuid": media_id,
                    "updated_at": 1002,
                    "payload": log_media_payload(&record_id),
                }
            ],
            "media": [{
                "client_uuid": media_id,
                "byte_size": bytes.len(),
                "sha256": digest,
            }]
        }),
    )
    .await;
    assert_eq!(manifest_status, StatusCode::OK, "{manifest}");

    // A staged batch has no family visibility and blocks ordinary family creation until it is
    // committed or cancelled, so normal join/sync cannot observe a partial dataset.
    let (create_during_restore_status, _) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "create-during-restore-request-000001",
            "display_name": "另一个管理员",
            "device_name": "另一台手机",
            "family_name": "另一个家庭",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(create_during_restore_status, StatusCode::CONFLICT);
    assert_eq!(
        get_json(&rig.app, "/v1/setup-status", None).await.1["family_state"],
        "empty"
    );

    let media_response = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/media/{media_id}"),
        Some(recovery_token),
        Body::from(bytes),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(media_response.status(), StatusCode::OK);

    let restarted = rig.restart_with_config("generation-restored", |config| {
        config.bootstrap_secret = Some(root.to_owned());
    });
    let (status_code, staged) = get_json(
        &restarted,
        &format!("/v1/disaster-restore/batches/{batch_id}/status"),
        Some(recovery_token),
    )
    .await;
    assert_eq!(status_code, StatusCode::OK, "{staged}");
    assert_eq!(staged["status"], "ready_to_commit");

    let (commit_status, committed) = json_request_with_headers(
        &restarted,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch_id}/commit"),
        Some(recovery_token),
        json!({"request_id": "restore-commit-request-00000000001"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");
    assert_eq!(committed["family_id"], family_id);
    assert_eq!(committed["role"], "owner");
    let new_owner = committed["membership_id"].as_str().unwrap();
    assert_ne!(new_owner, "old-owner-membership");

    let access = committed["access_token"].as_str().unwrap();
    test_client_sessions().lock().unwrap().insert(
        access.to_owned(),
        TestClientSession {
            generation: "generation-restored".to_owned(),
        },
    );
    let (pull_status, pulled) = get_json(&restarted, "/v1/pull?cursor=0", Some(access)).await;
    assert_eq!(pull_status, StatusCode::OK, "{pulled}");
    let record = pulled["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record")
        .unwrap();
    assert_eq!(record["payload"]["created_by_membership_id"], new_owner);
    assert_eq!(pulled["entities"].as_array().unwrap().len(), 3);

    let (retry_status, retry) = json_request_with_headers(
        &restarted,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch_id}/commit"),
        Some(recovery_token),
        json!({"request_id": "restore-commit-request-00000000001"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(retry["access_token"], committed["access_token"]);
}

#[tokio::test]
async fn disaster_restore_commits_a_wake_observation_and_its_wake_media() {
    let root = "correct-root-password";
    let family_id = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let sleep_id = Uuid::new_v4().to_string();
    let wake_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bytes = vec![9_u8, 8, 7];
    let digest = hex::encode(Sha256::digest(&bytes));
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (start_status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-wake-start-request-000000001",
            "family_id": family_id,
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(start_status, StatusCode::CREATED, "{started}");
    let batch_id = started["batch_id"].as_str().unwrap();
    let recovery_token = started["recovery_token"].as_str().unwrap();
    let (manifest_status, manifest) = json_request(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/manifest"),
        Some(recovery_token),
        json!({
            "request_id": "restore-wake-manifest-request-0000001",
            "entities": [
                {
                    "type": "baby",
                    "client_uuid": baby_id,
                    "updated_at": 1000,
                    "payload": baby_payload("宝宝", None),
                },
                {
                    "type": "record",
                    "client_uuid": sleep_id,
                    "updated_at": 1001,
                    "payload": {
                        "baby_client_uuid": baby_id,
                        "type": "sleep",
                        "custom_item_client_uuid": null,
                        "timestamp": 100,
                        "note": null,
                        "payload_json": {"anomaly_flag": false},
                        "schema_version": 2,
                        "effective_wake_observation_client_uuid": wake_id,
                    },
                },
                {
                    "type": "wake_observation",
                    "client_uuid": wake_id,
                    "updated_at": 1002,
                    "payload": {
                        "sleep_record_client_uuid": sleep_id,
                        "wake_timestamp": 200,
                        "note": null,
                        "withdrawn": false,
                        "observer_membership_id": "old-observer",
                    },
                },
                {
                    "type": "media",
                    "client_uuid": media_id,
                    "updated_at": 1003,
                    "payload": {
                        "kind": "wake",
                        "record_client_uuid": wake_id,
                        "mime": "image/jpeg",
                        "byte_size": bytes.len(),
                    },
                },
            ],
            "media": [{
                "client_uuid": media_id,
                "byte_size": bytes.len(),
                "sha256": digest,
            }],
        }),
    )
    .await;
    assert_eq!(manifest_status, StatusCode::OK, "{manifest}");
    let media_response = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/media/{media_id}"),
        Some(recovery_token),
        Body::from(bytes),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(media_response.status(), StatusCode::OK);
    let (commit_status, committed) = json_request_with_headers(
        &rig.app,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch_id}/commit"),
        Some(recovery_token),
        json!({"request_id": "restore-wake-commit-request-00000001"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");
    let new_owner = committed["membership_id"].as_str().unwrap();
    let access = committed["access_token"].as_str().unwrap();
    test_client_sessions().lock().unwrap().insert(
        access.to_owned(),
        TestClientSession {
            generation: committed["generation"].as_str().unwrap().to_owned(),
        },
    );
    let (pull_status, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(access)).await;
    assert_eq!(pull_status, StatusCode::OK, "{pulled}");
    let entities = pulled["entities"].as_array().unwrap();
    let wake = entities
        .iter()
        .find(|entity| entity["type"] == "wake_observation")
        .expect("wake observation restored");
    assert_eq!(wake["payload"]["sleep_record_client_uuid"], sleep_id);
    assert_eq!(wake["payload"]["observer_membership_id"], new_owner);
    let media = entities
        .iter()
        .find(|entity| entity["client_uuid"] == media_id)
        .expect("wake media restored");
    assert_eq!(media["payload"]["kind"], "wake");
    assert_eq!(media["payload"]["record_client_uuid"], wake_id);
}

#[tokio::test]
async fn disaster_restore_rejects_wake_media_that_does_not_name_a_wake_observation() {
    let root = "correct-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (start_status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-bad-wake-media-start-0000001",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(start_status, StatusCode::CREATED, "{started}");
    let batch_id = started["batch_id"].as_str().unwrap();
    let recovery_token = started["recovery_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let sleep_id = Uuid::new_v4().to_string();
    let wake_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    // The sleep record exists, so the old record-only reference check accepted
    // this. Commit rejects it: kind=wake record_client_uuid must be a wake.
    let (status, body) = json_request(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/manifest"),
        Some(recovery_token),
        json!({
            "request_id": "restore-bad-wake-media-manifest-00001",
            "entities": [
                {
                    "type": "baby",
                    "client_uuid": baby_id,
                    "updated_at": 1000,
                    "payload": baby_payload("宝宝", None),
                },
                {
                    "type": "record",
                    "client_uuid": sleep_id,
                    "updated_at": 1001,
                    "payload": {
                        "baby_client_uuid": baby_id,
                        "type": "sleep",
                        "custom_item_client_uuid": null,
                        "timestamp": 100,
                        "note": null,
                        "payload_json": {"anomaly_flag": false},
                        "schema_version": 2,
                        "effective_wake_observation_client_uuid": null,
                    },
                },
                {
                    "type": "wake_observation",
                    "client_uuid": wake_id,
                    "updated_at": 1002,
                    "payload": {
                        "sleep_record_client_uuid": sleep_id,
                        "wake_timestamp": 200,
                        "note": null,
                        "withdrawn": false,
                    },
                },
                {
                    "type": "media",
                    "client_uuid": media_id,
                    "updated_at": 1003,
                    "payload": {
                        "kind": "wake",
                        "record_client_uuid": sleep_id,
                        "mime": "image/jpeg",
                        "byte_size": 3,
                    },
                },
            ],
            "media": [{
                "client_uuid": media_id,
                "byte_size": 3,
                "sha256": "ab".repeat(32),
            }],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
    assert_eq!(
        body["detail"],
        "wake media record_client_uuid does not exist"
    );
}

#[tokio::test]
async fn disaster_restore_expires_after_twenty_four_hours_and_startup_cleans_staging() {
    let root = "correct-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-expiry-start-request-0000001",
            "family_id": Uuid::new_v4(),
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{started}");
    let batch_id = started["batch_id"].as_str().unwrap();
    let recovery_token = started["recovery_token"].as_str().unwrap();
    let batch_path = rig.directory.path().join("disaster-restore").join(batch_id);
    assert!(batch_path.is_dir());

    rig.now.fetch_add(24 * 60 * 60 + 1, Ordering::SeqCst);
    let (expired_status, expired) = get_json(
        &rig.app,
        &format!("/v1/disaster-restore/batches/{batch_id}/status"),
        Some(recovery_token),
    )
    .await;
    assert_eq!(expired_status, StatusCode::GONE, "{expired}");

    let _restarted = rig.restart_with_config("generation-after-expiry", |config| {
        config.bootstrap_secret = Some(root.to_owned());
    });
    assert!(!batch_path.exists());
}

#[tokio::test]
async fn runtime_restore_start_collects_an_uncontended_expired_batch() {
    let root = "expired-runtime-cleanup-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, first) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "expired-runtime-first-start-request-0001",
            "family_id": Uuid::new_v4(),
            "family_name": "旧恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "旧恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let first_batch_id = first["batch_id"].as_str().unwrap();
    let first_batch_dir = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(first_batch_id);
    rig.now.fetch_add(24 * 60 * 60 + 1, Ordering::SeqCst);

    let (second_status, second) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "expired-runtime-second-start-request-0001",
            "family_id": Uuid::new_v4(),
            "family_name": "新恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "新恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(second_status, StatusCode::CREATED, "{second}");
    assert!(!first_batch_dir.exists());
}

#[tokio::test]
async fn disaster_restore_commit_mints_access_expiry_from_commit_time() {
    let root = "correct-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let family_id = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let (start_status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-late-commit-start-request-0001",
            "family_id": family_id,
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(start_status, StatusCode::CREATED, "{started}");
    let batch_id = started["batch_id"].as_str().unwrap();
    let recovery_token = started["recovery_token"].as_str().unwrap();
    let (manifest_status, manifest) = json_request(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/manifest"),
        Some(recovery_token),
        json!({
            "request_id": "restore-late-commit-manifest-request-01",
            "entities": [{
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1000,
                "payload": baby_payload("宝宝", None),
            }],
            "media": [],
        }),
    )
    .await;
    assert_eq!(manifest_status, StatusCode::OK, "{manifest}");

    rig.now.fetch_add(24 * 60 * 60 - 60, Ordering::SeqCst);
    let commit_time = rig.now.load(Ordering::SeqCst);
    let (commit_status, committed) = json_request_with_headers(
        &rig.app,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch_id}/commit"),
        Some(recovery_token),
        json!({"request_id": "restore-late-commit-commit-request-0001"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");
    assert_eq!(committed["access_expires_at"], commit_time + 15 * 60);

    // Simulate power loss after SQLite activation but before the final journal
    // status replacement reached disk. The journal already contains the
    // request id and original commit-time expiry; retry must replay that exact
    // credential tuple instead of minting a response that disagrees with DB.
    let journal_path = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(batch_id)
        .join("journal.json");
    let mut journal: Value = serde_json::from_slice(&fs::read(&journal_path).unwrap()).unwrap();
    journal["status"] = json!("manifest_received");
    fs::write(&journal_path, serde_json::to_vec(&journal).unwrap()).unwrap();
    rig.now.fetch_add(30, Ordering::SeqCst);

    let (retry_status, retry) = json_request_with_headers(
        &rig.app,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch_id}/commit"),
        Some(recovery_token),
        json!({"request_id": "restore-late-commit-commit-request-0001"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(retry["access_token"], committed["access_token"]);
    assert_eq!(retry["refresh_token"], committed["refresh_token"]);
    assert_eq!(retry["access_expires_at"], committed["access_expires_at"]);
}

#[tokio::test]
async fn disaster_restore_rejects_manifest_tampering_without_activating_a_family() {
    let root = "correct-root-password";
    let family_id = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/disaster-restore/batches",
        None,
        json!({
            "request_id": "restore-tamper-start-request-000001",
            "family_id": family_id,
            "family_name": "恢复家庭",
            "owner_display_name": "妈妈",
            "device_name": "恢复手机",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{started}");
    let batch_id = started["batch_id"].as_str().unwrap();
    let recovery_token = started["recovery_token"].as_str().unwrap();
    let (status, body) = json_request(
        &rig.app,
        Method::PUT,
        &format!("/v1/disaster-restore/batches/{batch_id}/manifest"),
        Some(recovery_token),
        json!({
            "request_id": "restore-tamper-manifest-request-0001",
            "entities": [{
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1000,
                "payload": baby_payload("宝宝", None),
            }],
            "media": [],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let manifest_path = rig
        .directory
        .path()
        .join("disaster-restore")
        .join(batch_id)
        .join("manifest.json");
    let mut manifest = fs::read(&manifest_path).unwrap();
    manifest.push(b' ');
    fs::write(&manifest_path, manifest).unwrap();

    let (status, body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch_id}/commit"),
        Some(recovery_token),
        json!({"request_id": "restore-tamper-commit-request-00001"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    let (setup_status, setup) = get_json(&rig.app, "/v1/setup-status", None).await;
    assert_eq!(setup_status, StatusCode::OK);
    assert_eq!(setup["family_state"], "empty");
}

#[tokio::test]
async fn app_update_metadata_requires_session_and_returns_deploy_file() {
    let rig = Rig::new();
    let apk_bytes = b"app-update-metadata-route-apk-bytes";
    let sha256 = hex::encode(Sha256::digest(apk_bytes));
    let metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 7,
        "version_name": "0.3.1",
        "min_supported_version_code": 6,
        "sha256": sha256,
        "release_notes": "  修复同步  ",
    });
    fs::write(
        rig.directory.path().join("app-update.json"),
        metadata.to_string(),
    )
    .unwrap();
    // Metadata GET only after the APK verifies — no force floor without a package.
    fs::write(rig.directory.path().join("app-release.apk"), apk_bytes).unwrap();

    let (unauth_status, unauth_body) = get_json(&rig.app, "/v1/app-update", None).await;
    assert_eq!(unauth_status, StatusCode::UNAUTHORIZED, "{unauth_body}");

    let owner = create_family(
        &rig.app,
        "app-update-owner",
        "app-update-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let (status, body) = get_json(&rig.app, "/v1/app-update", Some(token)).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(
        body,
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 7,
            "version_name": "0.3.1",
            "min_supported_version_code": 6,
            "sha256": sha256,
            "release_notes": "修复同步",
        })
    );
}

#[tokio::test]
async fn app_update_metadata_missing_file_is_not_found_for_authenticated_session() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "app-update-missing-owner",
        "app-update-missing-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    let (status, body) = get_json(&rig.app, "/v1/app-update", Some(token)).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "{body}");
    assert_eq!(
        body["detail"],
        json!("App update metadata is not available")
    );
}

#[tokio::test]
async fn app_update_metadata_rejects_min_supported_above_version_code() {
    let rig = Rig::new();
    // Deadlock config: force floor above the package on the channel — must not serve as
    // a valid update channel (no 200 body clients would treat as installable latest).
    let apk_bytes = b"min-gt-version-deadlock-apk";
    let metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 7,
        "version_name": "0.3.1",
        "min_supported_version_code": 8,
        "sha256": hex::encode(Sha256::digest(apk_bytes)),
    });
    fs::write(
        rig.directory.path().join("app-update.json"),
        metadata.to_string(),
    )
    .unwrap();
    fs::write(rig.directory.path().join("app-release.apk"), apk_bytes).unwrap();

    let owner = create_family(
        &rig.app,
        "app-update-min-gt-owner",
        "app-update-min-gt-owner-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let (status, body) = get_json(&rig.app, "/v1/app-update", Some(token)).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{body}");
    assert_eq!(
        body["detail"],
        json!("App update metadata min_supported_version_code must not exceed version_code")
    );

    // Invalid channel also fail-opens the client version gate (do not brick sync forever).
    let (pull_status, pull_body) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(pull_status, StatusCode::OK, "{pull_body}");
}

#[tokio::test]
async fn app_update_apk_requires_session_and_matches_metadata_sha256() {
    let rig = Rig::new();
    let apk_bytes = b"fake-lezi-release-apk-bytes-for-test";
    let sha256 = hex::encode(Sha256::digest(apk_bytes));
    let metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 7,
        "version_name": "0.3.1",
        "min_supported_version_code": 6,
        "sha256": sha256,
    });
    fs::write(
        rig.directory.path().join("app-update.json"),
        metadata.to_string(),
    )
    .unwrap();
    fs::write(rig.directory.path().join("app-release.apk"), apk_bytes).unwrap();

    let response = request(
        &rig.app,
        Method::GET,
        "/v1/app-update/apk",
        None,
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    let owner = create_family(
        &rig.app,
        "app-update-apk-owner",
        "app-update-apk-owner-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let response = request(
        &rig.app,
        Method::GET,
        "/v1/app-update/apk",
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|value| value.to_str().ok()),
        Some("application/vnd.android.package-archive")
    );
    let body = response.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(body.as_ref(), apk_bytes.as_slice());
}

#[tokio::test]
async fn app_update_cache_invalidates_when_deploy_files_change() {
    let rig = Rig::new();
    let initial_apk = b"first-release-apk";
    let initial_metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 7,
        "version_name": "0.3.1",
        "min_supported_version_code": 6,
        "sha256": hex::encode(Sha256::digest(initial_apk)),
    });
    fs::write(
        rig.directory.path().join("app-update.json"),
        initial_metadata.to_string(),
    )
    .unwrap();
    fs::write(rig.directory.path().join("app-release.apk"), initial_apk).unwrap();

    let owner = create_family(
        &rig.app,
        "app-update-cache-owner",
        "app-update-cache-owner-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    assert_eq!(
        get_json(&rig.app, "/v1/app-update", Some(token)).await.1["version_code"],
        7,
    );
    let first_apk = request(
        &rig.app,
        Method::GET,
        "/v1/app-update/apk",
        Some(token),
        Body::empty(),
        None,
    )
    .await
    .into_body()
    .collect()
    .await
    .unwrap()
    .to_bytes();
    assert_eq!(first_apk.as_ref(), initial_apk);

    // Length changes make the deploy-file stamp differ even on filesystems
    // whose mtime resolution cannot distinguish two writes in one test tick.
    let replacement_apk = b"second-release-apk-with-a-different-length";
    let replacement_metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 8,
        "version_name": "0.3.2",
        "min_supported_version_code": 7,
        "sha256": hex::encode(Sha256::digest(replacement_apk)),
        "release_notes": "cache invalidation",
    });
    fs::write(
        rig.directory.path().join("app-update.json"),
        replacement_metadata.to_string(),
    )
    .unwrap();
    fs::write(
        rig.directory.path().join("app-release.apk"),
        replacement_apk,
    )
    .unwrap();

    let (_, metadata) = get_json(&rig.app, "/v1/app-update", Some(token)).await;
    assert_eq!(metadata["version_code"], 8);
    assert_eq!(metadata["version_name"], "0.3.2");
    let replacement = request(
        &rig.app,
        Method::GET,
        "/v1/app-update/apk",
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(replacement.status(), StatusCode::OK);
    let replacement = replacement.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(replacement.as_ref(), replacement_apk);
}

#[tokio::test]
async fn app_update_apk_rejects_sha256_mismatch() {
    let rig = Rig::new();
    let metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 7,
        "version_name": "0.3.1",
        "min_supported_version_code": 6,
        "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    });
    fs::write(
        rig.directory.path().join("app-update.json"),
        metadata.to_string(),
    )
    .unwrap();
    fs::write(
        rig.directory.path().join("app-release.apk"),
        b"bytes-that-do-not-match-sha256",
    )
    .unwrap();

    let owner = create_family(
        &rig.app,
        "app-update-apk-mismatch-owner",
        "app-update-apk-mismatch-req-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let (status, body) = get_json(&rig.app, "/v1/app-update/apk", Some(token)).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{body}");
    assert_eq!(
        body["detail"],
        json!("App update package integrity check failed")
    );
}

#[tokio::test]
async fn app_update_apk_missing_file_is_not_found() {
    let rig = Rig::new();
    let metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 7,
        "version_name": "0.3.1",
        "min_supported_version_code": 6,
        "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    });
    fs::write(
        rig.directory.path().join("app-update.json"),
        metadata.to_string(),
    )
    .unwrap();

    let owner = create_family(
        &rig.app,
        "app-update-apk-missing-owner",
        "app-update-apk-missing-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let (status, body) = get_json(&rig.app, "/v1/app-update/apk", Some(token)).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "{body}");
    assert_eq!(body["detail"], json!("App update package is not available"));
}

/// Seeds deploy app-update metadata with min_supported=8 / version_code=9 and creates a family.
/// Shared by pull / media / allowlist client_update gate tests.
async fn seed_client_update_gate(
    device_id: &str,
    request_id: &str,
    apk_bytes: &[u8],
    release_notes: Option<&str>,
) -> (Rig, String) {
    let rig = Rig::new();
    let sha256 = hex::encode(Sha256::digest(apk_bytes));
    let mut metadata = json!({
        "package_name": "com.lezi.babylog",
        "version_code": 9,
        "version_name": "0.4.0",
        "min_supported_version_code": 8,
        "sha256": sha256,
    });
    if let Some(notes) = release_notes {
        metadata
            .as_object_mut()
            .unwrap()
            .insert("release_notes".to_owned(), json!(notes));
    }
    fs::write(
        rig.directory.path().join("app-update.json"),
        metadata.to_string(),
    )
    .unwrap();
    fs::write(rig.directory.path().join("app-release.apk"), apk_bytes).unwrap();
    let owner = create_family(&rig.app, device_id, request_id).await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    (rig, token)
}

#[tokio::test]
async fn client_update_required_rejects_pull_when_version_header_missing_or_below_min() {
    let (rig, token) = seed_client_update_gate(
        "client-update-gate-owner",
        "client-update-gate-owner-request-00001",
        b"force-update-gate-apk-bytes",
        None,
    )
    .await;

    // Missing header → gate.
    let (missing_status, missing_body) = json_request_without_version(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0",
        Some(&token),
        json!({}),
        &[],
    )
    .await;
    assert_eq!(missing_status, StatusCode::FORBIDDEN, "{missing_body}");
    assert_eq!(missing_body["code"], json!("client_update_required"));

    // Below minSupported → gate.
    let (low_status, low_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a&page_index=0",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "34")],
    )
    .await;
    assert_eq!(low_status, StatusCode::FORBIDDEN, "{low_body}");
    assert_eq!(low_body["code"], json!("client_update_required"));

    // At minSupported → pull allowed.
    let (ok_status, ok_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a&page_index=0",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "35")],
    )
    .await;
    assert_eq!(ok_status, StatusCode::OK, "{ok_body}");
    assert!(ok_body.get("entities").is_some(), "{ok_body}");

    // Above minSupported → pull allowed.
    let (high_status, high_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a&page_index=0",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "36")],
    )
    .await;
    assert_eq!(high_status, StatusCode::OK, "{high_body}");

    // Invalid header → same gate (not a generic 422).
    let (invalid_status, invalid_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a&page_index=0",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "not-a-number")],
    )
    .await;
    assert_eq!(invalid_status, StatusCode::FORBIDDEN, "{invalid_body}");
    assert_eq!(invalid_body["code"], json!("client_update_required"));
}

#[tokio::test]
async fn client_update_required_rejects_media_get_when_version_header_missing_or_below_min() {
    let (rig, token) = seed_client_update_gate(
        "client-update-media-gate-owner",
        "client-update-media-gate-owner-req01",
        b"force-update-media-gate-apk-bytes",
        None,
    )
    .await;
    let media_path = format!("/v1/media/{}", Uuid::new_v4());

    // Missing header → gate before media lookup.
    let (missing_status, missing_body) = json_request_without_version(
        &rig.app,
        Method::GET,
        &media_path,
        Some(&token),
        json!({}),
        &[],
    )
    .await;
    assert_eq!(missing_status, StatusCode::FORBIDDEN, "{missing_body}");
    assert_eq!(missing_body["code"], json!("client_update_required"));

    // Below minSupported → same gate.
    let (low_status, low_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        &media_path,
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "34")],
    )
    .await;
    assert_eq!(low_status, StatusCode::FORBIDDEN, "{low_body}");
    assert_eq!(low_body["code"], json!("client_update_required"));

    // Invalid header → same gate as pull (not a generic 422).
    let (invalid_status, invalid_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        &media_path,
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "not-a-number")],
    )
    .await;
    assert_eq!(invalid_status, StatusCode::FORBIDDEN, "{invalid_body}");
    assert_eq!(invalid_body["code"], json!("client_update_required"));

    // At minSupported → not client_update_required (missing media is 404, not force-update).
    let (ok_status, ok_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        &media_path,
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "35")],
    )
    .await;
    assert_eq!(ok_status, StatusCode::NOT_FOUND, "{ok_body}");
    assert_ne!(ok_body["code"], json!("client_update_required"));

    // Above minSupported → same non-force path for unknown media.
    let (high_status, high_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        &media_path,
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "36")],
    )
    .await;
    assert_eq!(high_status, StatusCode::NOT_FOUND, "{high_body}");
    assert_ne!(high_body["code"], json!("client_update_required"));
}

#[tokio::test]
async fn client_update_required_still_allows_authenticated_app_update_download() {
    let apk_bytes = b"force-update-allowlist-apk-bytes";
    let (rig, token) = seed_client_update_gate(
        "client-update-allow-owner",
        "client-update-allow-owner-request-0001",
        apk_bytes,
        Some("破坏性同步合同"),
    )
    .await;
    let low_version = [("x-lezi-client-version-code", "1")];

    // Metadata and APK stay open for a below-min client so force-upgrade is not deadlocked.
    let (meta_status, meta_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/app-update",
        Some(&token),
        json!({}),
        &low_version,
    )
    .await;
    assert_eq!(meta_status, StatusCode::OK, "{meta_body}");
    assert_eq!(meta_body["version_code"], json!(9));
    assert_eq!(meta_body["min_supported_version_code"], json!(8));

    let apk_response = request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/app-update/apk",
        Some(&token),
        Body::empty(),
        None,
        &low_version,
    )
    .await;
    assert_eq!(apk_response.status(), StatusCode::OK);
    let body = apk_response.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(body.as_ref(), apk_bytes.as_slice());

    // Bundle stage is gated the same as pull (gate runs before body validation).
    let (bundle_status, bundle_body) = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(&token),
        json!({
            "generation": "generation-a",
            "bundle_id": "11111111-1111-4111-8111-111111111111",
            "root": {
                "client_uuid": "22222222-2222-4222-8222-222222222222",
                "entity_type": "record",
                "updated_at": 1,
                "deleted_at": null,
                "payload": {
                    "type": "formula",
                    "occurred_at": 1,
                    "baby_client_uuid": "33333333-3333-4333-8333-333333333333",
                    "amount_ml": 30
                }
            },
            "media": []
        }),
        &low_version,
    )
    .await;
    assert_eq!(bundle_status, StatusCode::FORBIDDEN, "{bundle_body}");
    assert_eq!(bundle_body["code"], json!("client_update_required"));
}

#[tokio::test]
async fn client_version_gate_accepts_paired_generation_without_app_update_metadata() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "client-update-failopen-owner",
        "client-update-failopen-owner-req-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    // Paired-generation clients remain usable without optional update metadata.
    let (status, body) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK, "{body}");
}

/// Shared seed for unverified-channel fail-open cases (metadata present; package broken).
async fn seed_unverified_channel_family(
    device_id: &str,
    request_id: &str,
    apk_bytes: Option<&[u8]>,
    metadata_sha256: &str,
) -> (Rig, String) {
    let rig = Rig::new();
    fs::write(
        rig.directory.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 9,
            "version_name": "0.4.0",
            "min_supported_version_code": 8,
            "sha256": metadata_sha256,
        })
        .to_string(),
    )
    .unwrap();
    if let Some(bytes) = apk_bytes {
        fs::write(rig.directory.path().join("app-release.apk"), bytes).unwrap();
    }
    let owner = create_family(&rig.app, device_id, request_id).await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    (rig, token)
}

/// Metadata alone is not a verified update channel: do not raise the version floor
/// into `client_update_required` when the APK is missing (nothing installable).
/// GET /v1/app-update must also refuse to advertise min_supported without a package.
#[tokio::test]
async fn client_version_gate_keeps_hard_floor_when_metadata_present_but_apk_missing() {
    let (rig, token) = seed_unverified_channel_family(
        "client-update-meta-only-owner",
        "client-update-meta-only-owner-req-001",
        None,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )
    .await;

    let (status, body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a&page_index=0",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "1")],
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    assert_eq!(body["code"], json!("client_update_required"));

    // Metadata-only must not return a 200 force floor clients would dual-tier on.
    let (meta_status, meta_body) = get_json(&rig.app, "/v1/app-update", Some(&token)).await;
    assert_ne!(meta_status, StatusCode::OK, "{meta_body}");
    assert!(
        meta_status == StatusCode::NOT_FOUND || meta_status.is_server_error(),
        "expected channel-broken status, got {meta_status}: {meta_body}"
    );
    assert!(
        meta_body.get("min_supported_version_code").is_none(),
        "must not advertise min_supported without installable package: {meta_body}"
    );
}

/// Integrity-failing APK is not a verified channel: same fail-open as missing package.
/// GET /v1/app-update must not return a 200 body clients treat as a force floor.
#[tokio::test]
async fn client_version_gate_keeps_hard_floor_when_apk_sha256_mismatches_metadata() {
    let (rig, token) = seed_unverified_channel_family(
        "client-update-bad-sha-owner",
        "client-update-bad-sha-owner-req-00001",
        Some(b"apk-bytes-that-do-not-match-metadata-sha256"),
        "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
    )
    .await;

    let (status, body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a&page_index=0",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "1")],
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    assert_eq!(body["code"], json!("client_update_required"));

    let (meta_status, meta_body) = get_json(&rig.app, "/v1/app-update", Some(&token)).await;
    assert_ne!(meta_status, StatusCode::OK, "{meta_body}");
    assert!(
        meta_status.is_server_error() || meta_status == StatusCode::NOT_FOUND,
        "expected channel-broken status, got {meta_status}: {meta_body}"
    );
    assert!(
        meta_body.get("min_supported_version_code").is_none(),
        "must not advertise min_supported for integrity-failing package: {meta_body}"
    );
}

#[tokio::test]
async fn legacy_invite_and_join_routes_are_absent() {
    let rig = Rig::new();

    for path in ["/v1/invite", "/v1/join"] {
        let response = request(
            &rig.app,
            Method::POST,
            path,
            None,
            Body::from("{}"),
            Some("application/json"),
        )
        .await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test]
async fn setup_status_reports_maintenance_without_readiness_or_family_details() {
    let rig = Rig::new();
    fs::write(
        rig.directory.path().join("lezi.db"),
        b"not a sqlite database",
    )
    .unwrap();

    let response = request(
        &rig.app,
        Method::GET,
        "/v1/setup-status",
        None,
        Body::empty(),
        None,
    )
    .await;
    let status = response.status();
    let body = response.into_body().collect().await.unwrap().to_bytes();

    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(body.is_empty());
}

#[tokio::test]
async fn current_schema_version_restarts_with_credentials_and_entities() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "schema-version-owner",
        "schema-version-owner-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let database_path = rig.directory.path().join("lezi.db");
    let connection = rusqlite::Connection::open(&database_path).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        13
    );
    drop(connection);

    let restarted = rig.restart("generation-b");
    let (pull_status, pull) = get_json(
        &restarted,
        "/v1/pull?cursor=0&generation=generation-b",
        Some(token),
    )
    .await;
    assert_eq!(pull_status, StatusCode::OK);
    assert!(
        pull["entities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|entity| entity["client_uuid"] == baby_id),
        "same-version restart lost persisted family entities"
    );
    let connection = rusqlite::Connection::open(database_path).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        13
    );
}

#[test]
fn future_database_schema_version_fails_closed_without_mutation() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    let connection = rusqlite::Connection::open(&database_path).unwrap();
    connection
        .execute_batch(
            "
            PRAGMA user_version = 14;
            CREATE TABLE future_sentinel(value TEXT NOT NULL);
            INSERT INTO future_sentinel(value) VALUES ('preserve-me');
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
    let before_directory_mode = directory.path().metadata().unwrap().permissions().mode() & 0o777;
    let before_database_mode = database_path.metadata().unwrap().permissions().mode() & 0o777;

    assert!(
        build_app(ServerConfig::new(directory.path())).is_err(),
        "a newer database schema must not be opened by an older server"
    );

    let after_entries = fs::read_dir(directory.path())
        .unwrap()
        .map(|entry| entry.unwrap().file_name())
        .collect::<std::collections::BTreeSet<_>>();
    assert_eq!(after_entries, before_entries);
    assert_eq!(
        directory.path().metadata().unwrap().permissions().mode() & 0o777,
        before_directory_mode
    );
    assert_eq!(
        database_path.metadata().unwrap().permissions().mode() & 0o777,
        before_database_mode
    );
    for unexpected in [
        "media",
        "server.secret",
        "lezi.db-wal",
        "lezi.db-shm",
        "lezi.db-journal",
    ] {
        assert!(
            !directory.path().join(unexpected).exists(),
            "future-schema preflight created {unexpected}"
        );
    }

    let connection = rusqlite::Connection::open_with_flags(
        database_path,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        14
    );
    assert_eq!(
        connection
            .query_row("SELECT value FROM future_sentinel", [], |row| {
                row.get::<_, String>(0)
            })
            .unwrap(),
        "preserve-me"
    );
}

#[tokio::test]
async fn readiness_reports_degraded_when_database_is_not_queryable() {
    let rig = Rig::new();
    fs::write(
        rig.directory.path().join("lezi.db"),
        b"not a sqlite database",
    )
    .unwrap();

    let (status, body) = get_json(&rig.app, "/ready", None).await;

    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        body,
        json!({"ok": false, "status": "degraded", "version": VERSION})
    );
}

#[tokio::test]
async fn readiness_reports_degraded_when_media_directory_is_not_writable() {
    let rig = Rig::new();
    let media_root = rig.directory.path().join("media");
    let mut permissions = media_root.metadata().unwrap().permissions();
    permissions.set_mode(0o500);
    fs::set_permissions(&media_root, permissions).unwrap();

    let (status, body) = get_json(&rig.app, "/ready", None).await;

    let mut restored = media_root.metadata().unwrap().permissions();
    restored.set_mode(0o700);
    fs::set_permissions(&media_root, restored).unwrap();
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        body,
        json!({"ok": false, "status": "degraded", "version": VERSION})
    );
}

#[tokio::test]
async fn public_health_is_cheap_while_readiness_is_cached_and_expires() {
    let rig = Rig::new();
    assert_eq!(get_json(&rig.app, "/ready", None).await.0, StatusCode::OK);
    fs::write(
        rig.directory.path().join("lezi.db"),
        b"not a sqlite database",
    )
    .unwrap();

    // Liveness performs no database or filesystem probes, and readiness reuses
    // its recent result to prevent a public endpoint from forcing fsync I/O.
    assert_eq!(get_json(&rig.app, "/health", None).await.0, StatusCode::OK);
    assert_eq!(get_json(&rig.app, "/ready", None).await.0, StatusCode::OK);

    rig.now.fetch_add(5, Ordering::SeqCst);
    assert_eq!(
        get_json(&rig.app, "/ready", None).await.0,
        StatusCode::SERVICE_UNAVAILABLE
    );
}

#[tokio::test]
async fn expired_readiness_probes_are_not_serialized_on_the_cache_lock() {
    let hook = overlapping_expired_readiness_probe_hook();
    let rig = Rig::with_config(|config| {
        config.readiness_probe_blocking_hook = Some(hook);
    });
    assert_eq!(get_json(&rig.app, "/ready", None).await.0, StatusCode::OK);

    rig.now.fetch_add(5, Ordering::SeqCst);
    let app_a = rig.app.clone();
    let app_b = rig.app.clone();
    let (left, right) = tokio::join!(
        get_json(&app_a, "/ready", None),
        get_json(&app_b, "/ready", None),
    );
    assert_eq!(left.0, StatusCode::OK, "{}", left.1);
    assert_eq!(right.0, StatusCode::OK, "{}", right.1);
    assert_eq!(
        left.1,
        json!({"ok": true, "status": "ready", "version": VERSION}),
    );
    assert_eq!(
        right.1,
        json!({"ok": true, "status": "ready", "version": VERSION}),
    );
}

#[tokio::test]
async fn family_create_is_strict_idempotent_and_restart_safe() {
    let rig = Rig::new();
    let (missing_status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({"device_id": "owner-device"}),
    )
    .await;
    let (short_status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "short",
            "device_id": "owner-device",
            "display_name": "妈妈",
        }),
    )
    .await;
    assert_eq!(missing_status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(short_status, StatusCode::UNPROCESSABLE_ENTITY);

    let request_id = "Qk7Uj6hTH1xbqa9nYs8FQ2c4e5w7r9tB";
    let first = create_family(&rig.app, "owner-device", request_id).await;
    assert!(first["membership_id"].as_str().unwrap().len() >= 32);
    assert!(first["device_id"].as_str().unwrap().len() >= 32);
    assert!(first["session_id"].as_str().unwrap().len() >= 32);
    assert_eq!(
        first["access_expires_at"],
        rig.now.load(Ordering::SeqCst) + 900
    );
    assert_ne!(first["access_token"], first["refresh_token"]);
    assert_eq!(first["reclaimed"], false);
    let restarted = rig.restart("generation-b");
    let retry = create_family(&restarted, "owner-device", request_id).await;
    assert_eq!(retry["family_id"], first["family_id"]);
    assert_eq!(retry["access_token"], first["access_token"]);
    assert_eq!(retry["refresh_token"], first["refresh_token"]);
    assert_eq!(retry["device_id"], first["device_id"]);
    assert_eq!(retry["membership_id"], first["membership_id"]);
    assert_eq!(retry["generation"], "generation-b");
    assert_eq!(retry["reclaimed"], false);

    let persisted = fs::read(rig.directory.path().join("lezi.db")).unwrap();
    assert!(!persisted
        .windows(request_id.len())
        .any(|window| window == request_id.as_bytes()));
    let token = first["access_token"].as_str().unwrap();
    assert!(!persisted
        .windows(token.len())
        .any(|window| window == token.as_bytes()));
    let refresh = first["refresh_token"].as_str().unwrap();
    assert!(!persisted
        .windows(refresh.len())
        .any(|window| window == refresh.as_bytes()));

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let membership_columns = connection
        .prepare("SELECT name FROM pragma_table_info('memberships')")
        .unwrap()
        .query_map([], |row| row.get::<_, String>(0))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    assert!(!membership_columns
        .iter()
        .any(|column| column == "device_id"));
    assert_eq!(
        connection
            .query_row("SELECT COUNT(*) FROM devices", [], |row| row
                .get::<_, i64>(0))
            .unwrap(),
        1,
    );
    assert_eq!(
        connection
            .query_row("SELECT COUNT(*) FROM device_sessions", [], |row| row
                .get::<_, i64>(0))
            .unwrap(),
        1,
    );
}

#[tokio::test]
async fn family_create_retry_never_reissues_credentials_after_session_rotation() {
    let rig = Rig::new();
    let request_id = "create-then-refresh-request-aaaa00000001";
    let created = create_family(&rig.app, "owner-device", request_id).await;
    let (_, rotated) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": created["refresh_token"]}),
    )
    .await;

    let (retry_status, retry) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "display_name": "妈妈",
            "device_name": "owner-device",
            "family_name": "测试家庭",
        }),
    )
    .await;

    assert_eq!(retry_status, StatusCode::CONFLICT, "{retry}");
    assert!(retry.get("access_token").is_none());
    assert!(retry.get("refresh_token").is_none());
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            rotated["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );
}

#[tokio::test]
async fn concurrent_owner_create_commits_exactly_one_family_membership_device_and_session() {
    let secret = "concurrent-root-secret";
    let rig = Rig::with_config(|config| {
        config.bootstrap_secret = Some(secret.to_owned());
    });
    let bootstrap_headers = [("x-lezi-bootstrap-secret", secret)];
    let request_a = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "concurrent-create-request-aaaa000001",
            "display_name": "妈妈",
            "device_name": "妈妈手机",
            "family_name": "乐乐一家",
        }),
        &bootstrap_headers,
    );
    let request_b = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "concurrent-create-request-bbbb000002",
            "display_name": "爸爸",
            "device_name": "爸爸手机",
            "family_name": "另一个家庭",
        }),
        &bootstrap_headers,
    );

    let (result_a, result_b) = tokio::join!(request_a, request_b);
    let statuses = [result_a.0, result_b.0];
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == StatusCode::CREATED)
            .count(),
        1,
    );
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == StatusCode::CONFLICT)
            .count(),
        1,
    );

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    for table in ["families", "memberships", "devices", "device_sessions"] {
        let count: i64 = connection
            .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 1, "unexpected {table} rows");
    }
    let database = fs::read(rig.directory.path().join("lezi.db")).unwrap();
    assert!(!database
        .windows(secret.len())
        .any(|window| window == secret.as_bytes()));
}

#[tokio::test]
async fn refresh_rotates_once_and_replay_revokes_only_the_presenting_device() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-phone",
        "refresh-owner-request-aaaa000000000001",
    )
    .await;
    let owner_access = owner["access_token"].as_str().unwrap();
    let owner_refresh = owner["refresh_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_access, "member-phone").await;
    let member_access = member["access_token"].as_str().unwrap();
    let sibling_device_id = "same-membership-sibling-device";
    let sibling_access = "same-membership-sibling-access";
    let sibling_refresh = "same-membership-sibling-refresh";
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "
            INSERT INTO devices(
                device_id, membership_id, device_name, device_name_key,
                status, created_at, last_used_at
            ) VALUES (?1, ?2, 'Owner tablet', 'owner tablet', 'active', ?3, ?3)
            ",
            rusqlite::params![
                sibling_device_id,
                owner["membership_id"].as_str().unwrap(),
                rig.now.load(Ordering::SeqCst),
            ],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO device_sessions(
                session_id, device_id, access_token_hash, access_expires_at, refresh_token_hash
            ) VALUES ('same-membership-sibling-session', ?1, ?2, ?3, ?4)
            ",
            rusqlite::params![
                sibling_device_id,
                token_hash(sibling_access),
                rig.now.load(Ordering::SeqCst) + 900,
                token_hash(sibling_refresh),
            ],
        )
        .unwrap();
    drop(connection);

    let refresh_a = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": owner_refresh}),
    );
    let refresh_b = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": owner_refresh}),
    );
    let (a, b) = tokio::join!(refresh_a, refresh_b);
    let (success, replay) = if a.0 == StatusCode::OK {
        (a, b)
    } else {
        (b, a)
    };

    assert_eq!(success.0, StatusCode::OK, "{}", success.1);
    assert_eq!(replay.0, StatusCode::UNAUTHORIZED, "{}", replay.1);
    assert_eq!(replay.1["code"], "refresh_replay");
    assert_ne!(success.1["access_token"], owner["access_token"]);
    assert_ne!(success.1["refresh_token"], owner["refresh_token"]);
    assert_eq!(
        success.1["access_expires_at"],
        rig.now.load(Ordering::SeqCst) + 900
    );
    assert_eq!(success.1["family_id"], owner["family_id"]);
    assert_eq!(success.1["membership_id"], owner["membership_id"]);
    assert_eq!(success.1["device_id"], owner["device_id"]);
    assert_eq!(success.1["role"], "owner");

    let rotated_access = success.1["access_token"].as_str().unwrap();
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(rotated_access))
            .await
            .0,
        StatusCode::UNAUTHORIZED,
        "a replay revokes the newly rotated lineage for that device",
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(member_access))
            .await
            .0,
        StatusCode::OK,
        "refresh replay must not revoke another membership or the family",
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(sibling_access))
            .await
            .0,
        StatusCode::OK,
        "refresh replay must not revoke another device on the same membership",
    );

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        connection
            .query_row(
                "SELECT status FROM devices WHERE device_id = ?1",
                [owner["device_id"].as_str().unwrap()],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
        "revoked",
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT status FROM devices WHERE device_id = ?1",
                [member["device_id"].as_str().unwrap()],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
        "active",
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT status FROM devices WHERE device_id = ?1",
                [sibling_device_id],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
        "active",
    );
}

#[tokio::test]
async fn refresh_request_id_replays_the_exact_rotation_after_client_crash() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "refresh-crash-owner-phone",
        "refresh-crash-owner-request-000001",
    )
    .await;
    let old_refresh = owner["refresh_token"].as_str().unwrap();
    let rotation_id = "refresh-rotation-request-000000001";

    let first = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": old_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(first.0, StatusCode::OK, "{}", first.1);

    // Simulate process death before the client durably saves either returned
    // token: it retries the old on-disk refresh with the same durable request id.
    let replay = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": old_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(replay.0, StatusCode::OK, "{}", replay.1);
    assert_eq!(replay.1["access_token"], first.1["access_token"]);
    assert_eq!(replay.1["refresh_token"], first.1["refresh_token"]);
    assert_eq!(replay.1["access_expires_at"], first.1["access_expires_at"]);

    let recovered_access = replay.1["access_token"].as_str().unwrap();
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(recovered_access))
            .await
            .0,
        StatusCode::OK,
    );
}

#[tokio::test]
async fn current_refresh_token_with_uncleared_request_id_does_not_rotate_twice() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "refresh-handoff-owner-phone",
        "refresh-handoff-owner-request-000001",
    )
    .await;
    let old_refresh = owner["refresh_token"].as_str().unwrap();
    let rotation_id = "refresh-handoff-rotation-request-001";
    let first = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": old_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(first.0, StatusCode::OK, "{}", first.1);

    // The client durably saved the new refresh token, then died before it
    // cleared its durable rotation id. Restart retries the current token with
    // the same id and must receive the same handoff without another rotation.
    let persisted_refresh = first.1["refresh_token"].as_str().unwrap();
    rig.now.fetch_add(30, Ordering::SeqCst);
    let replay = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": persisted_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(replay.0, StatusCode::OK, "{}", replay.1);
    assert_eq!(replay.1["access_token"], first.1["access_token"]);
    assert_eq!(replay.1["refresh_token"], first.1["refresh_token"]);
    assert_eq!(replay.1["access_expires_at"], first.1["access_expires_at"]);

    let replay_again = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": persisted_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(replay_again.0, StatusCode::OK, "{}", replay_again.1);
    assert_eq!(replay_again.1["access_token"], first.1["access_token"]);
    assert_eq!(replay_again.1["refresh_token"], first.1["refresh_token"]);
    assert_eq!(
        replay_again.1["access_expires_at"],
        first.1["access_expires_at"]
    );
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            first.1["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );
}

#[tokio::test]
async fn uncleared_request_id_after_access_expiry_extends_handoff_instead_of_500() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "refresh-expired-handoff-phone",
        "refresh-expired-handoff-request-0001",
    )
    .await;
    let old_refresh = owner["refresh_token"].as_str().unwrap();
    let rotation_id = "refresh-expired-handoff-rotation-00001";
    let first = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": old_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(first.0, StatusCode::OK, "{}", first.1);
    let persisted_refresh = first.1["refresh_token"].as_str().unwrap();
    let first_access = first.1["access_token"].as_str().unwrap();

    // Past the 15-minute access TTL while the client still holds the rotated
    // refresh token and the uncleared durable rotation id (crash after keystore
    // write / before request-id retirement, or a long offline window).
    rig.now.fetch_add(901, Ordering::SeqCst);
    let replay = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": persisted_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(replay.0, StatusCode::OK, "{}", replay.1);
    assert_eq!(replay.1["access_token"], first_access);
    assert_eq!(replay.1["refresh_token"], persisted_refresh);
    assert_eq!(
        replay.1["access_expires_at"],
        rig.now.load(Ordering::SeqCst) + 900,
    );
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            replay.1["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );

    // Crash-window retry of the pre-rotation refresh token after the same TTL
    // must also reconstruct the handoff with a usable access expiry.
    let history_replay = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": old_refresh}),
        &[("x-lezi-refresh-request-id", rotation_id)],
    )
    .await;
    assert_eq!(history_replay.0, StatusCode::OK, "{}", history_replay.1);
    assert_eq!(history_replay.1["access_token"], first_access);
    assert_eq!(history_replay.1["refresh_token"], persisted_refresh);
    assert_eq!(
        history_replay.1["access_expires_at"],
        rig.now.load(Ordering::SeqCst) + 900,
    );
}

#[tokio::test]
async fn concurrent_refreshes_with_the_same_request_id_return_one_rotation() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "refresh-concurrent-owner-phone",
        "refresh-concurrent-owner-request-001",
    )
    .await;
    let old_refresh = owner["refresh_token"].as_str().unwrap();
    let rotation_id = "refresh-concurrent-rotation-000001";
    let left_headers = [("x-lezi-refresh-request-id", rotation_id)];
    let right_headers = [("x-lezi-refresh-request-id", rotation_id)];

    let (left, right) = tokio::join!(
        raw_json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/session/refresh",
            None,
            json!({"refresh_token": old_refresh}),
            &left_headers,
        ),
        raw_json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/session/refresh",
            None,
            json!({"refresh_token": old_refresh}),
            &right_headers,
        ),
    );
    assert_eq!(left.0, StatusCode::OK, "{}", left.1);
    assert_eq!(right.0, StatusCode::OK, "{}", right.1);
    assert_eq!(left.1["access_token"], right.1["access_token"]);
    assert_eq!(left.1["refresh_token"], right.1["refresh_token"]);
    assert_eq!(left.1["access_expires_at"], right.1["access_expires_at"]);
}

#[tokio::test]
async fn replayed_refresh_with_a_different_request_id_still_revokes_the_device() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "refresh-mismatch-owner-phone",
        "refresh-mismatch-owner-request-00001",
    )
    .await;
    let old_refresh = owner["refresh_token"].as_str().unwrap();
    let first = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": old_refresh}),
        &[(
            "x-lezi-refresh-request-id",
            "refresh-rotation-request-aaaaaaaa01",
        )],
    )
    .await;
    assert_eq!(first.0, StatusCode::OK, "{}", first.1);

    let replay = raw_json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": old_refresh}),
        &[(
            "x-lezi-refresh-request-id",
            "refresh-rotation-request-bbbbbbbb02",
        )],
    )
    .await;
    assert_eq!(replay.0, StatusCode::UNAUTHORIZED, "{}", replay.1);
    assert_eq!(replay.1["code"], "refresh_replay");
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            first.1["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED,
    );
}

#[tokio::test]
async fn refresh_has_no_time_or_inactivity_expiry_but_invalid_values_fail_closed() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-phone",
        "refresh-lifetime-request-aaaa00000001",
    )
    .await;
    rig.now.fetch_add(10 * 365 * 24 * 60 * 60, Ordering::SeqCst);

    let (status, refreshed) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": owner["refresh_token"]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{refreshed}");
    assert_eq!(
        refreshed["access_expires_at"],
        rig.now.load(Ordering::SeqCst) + 900,
    );

    for body in [
        json!({}),
        json!({"refresh_token": ""}),
        json!({"refresh_token": "invalid"}),
    ] {
        let (status, _) =
            json_request(&rig.app, Method::POST, "/v1/session/refresh", None, body).await;
        assert!(status == StatusCode::UNPROCESSABLE_ENTITY || status == StatusCode::UNAUTHORIZED,);
    }
}

#[tokio::test]
async fn family_create_rejects_a_second_owner_claim_without_mutating_the_first() {
    let rig = Rig::new();
    let first = create_family(
        &rig.app,
        "owner-device-a",
        "create-owner-request-aaaa000000000001",
    )
    .await;
    let first_token = first["access_token"].as_str().unwrap();

    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "create-owner-request-bbbb000000000002",
            "display_name": "爸爸",
            "device_name": "另一台手机",
            "family_name": "另一个家庭",
        }),
    )
    .await;

    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    let (members_status, members) =
        get_json(&rig.app, "/v1/family/members", Some(first_token)).await;
    assert_eq!(members_status, StatusCode::OK, "{members}");
    assert_eq!(members["members"].as_array().unwrap().len(), 1);
    assert_eq!(members["members"][0]["role"], "owner");
}

#[tokio::test]
async fn owner_login_adds_one_device_to_the_existing_owner_and_retries_idempotently() {
    let root = "owner-login-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let create_body = json!({
        "create_request_id": "owner-login-create-request-aaaa000001",
        "display_name": "妈妈",
        "device_name": "旧手机",
        "family_name": "乐乐一家",
    });
    let (_, original) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        create_body.clone(),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let login_body = json!({
        "login_request_id": "owner-login-request-bbbb00000000001",
        "device_name": "管理员平板",
    });

    let (status, logged_in) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/owner/login",
        None,
        login_body.clone(),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let (retry_status, retry) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/owner/login",
        None,
        login_body,
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;

    assert_eq!(status, StatusCode::OK, "{logged_in}");
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(logged_in["membership_id"], original["membership_id"]);
    assert_ne!(logged_in["device_id"], original["device_id"]);
    assert_eq!(retry["device_id"], logged_in["device_id"]);
    assert_eq!(retry["access_token"], logged_in["access_token"]);
    assert_eq!(retry["refresh_token"], logged_in["refresh_token"]);
    let (create_retry_status, original_create_retry) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        create_body,
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(create_retry_status, StatusCode::CONFLICT);
    assert!(original_create_retry.get("access_token").is_none());
    assert!(original_create_retry.get("refresh_token").is_none());
    for token in [
        original["access_token"].as_str(),
        logged_in["access_token"].as_str(),
    ] {
        assert_eq!(
            get_json(&rig.app, "/v1/family/members", token).await.0,
            StatusCode::OK,
        );
    }
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        connection
            .query_row(
                "SELECT COUNT(*) FROM memberships WHERE role = 'owner' AND left_at IS NULL",
                [],
                |row| row.get::<_, i64>(0),
            )
            .unwrap(),
        1,
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT COUNT(*) FROM devices WHERE membership_id = ?1 AND status = 'active'",
                [original["membership_id"].as_str().unwrap()],
                |row| row.get::<_, i64>(0),
            )
            .unwrap(),
        2,
    );
}

#[tokio::test]
async fn owner_takeover_atomically_revokes_old_owner_devices_but_not_members() {
    let root = "owner-takeover-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, original) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "takeover-create-request-aaaa00000001",
            "display_name": "妈妈",
            "device_name": "旧手机",
            "family_name": "乐乐一家",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let member = approve_new_member(
        &rig.app,
        original["access_token"].as_str().unwrap(),
        "member-phone",
    )
    .await;
    let (_, second_owner) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/owner/login",
        None,
        json!({
            "login_request_id": "takeover-login-request-bbbb000000001",
            "device_name": "旧平板",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let takeover_body = json!({
        "login_request_id": "takeover-request-cccc00000000000001",
        "device_name": "找回控制的新手机",
    });

    let (status, takeover) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/owner/takeover",
        None,
        takeover_body.clone(),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let (retry_status, retry) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/owner/takeover",
        None,
        takeover_body,
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;

    assert_eq!(status, StatusCode::OK, "{takeover}");
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(retry["access_token"], takeover["access_token"]);
    assert_eq!(takeover["membership_id"], original["membership_id"]);
    for old in [&original, &second_owner] {
        assert_eq!(
            get_json(&rig.app, "/v1/family/members", old["access_token"].as_str(),)
                .await
                .0,
            StatusCode::UNAUTHORIZED,
        );
    }
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            takeover["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            member["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );
}

#[tokio::test]
async fn root_password_rotation_on_restart_revokes_only_owner_sessions() {
    let old_root = "old-owner-root-password";
    let new_root = "new-owner-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(old_root.to_owned()));
    let (_, owner) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "root-rotation-create-request-aaaa0001",
            "display_name": "妈妈",
            "device_name": "管理员手机",
            "family_name": "乐乐一家",
        }),
        &[("x-lezi-bootstrap-secret", old_root)],
    )
    .await;
    let member = approve_new_member(
        &rig.app,
        owner["access_token"].as_str().unwrap(),
        "member-phone",
    )
    .await;

    let same_root = rig.restart_with_config("generation-b", |config| {
        config.bootstrap_secret = Some(old_root.to_owned());
    });
    assert_eq!(
        get_json(
            &same_root,
            "/v1/family/members",
            owner["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );
    let changed_root = rig.restart_with_config("generation-c", |config| {
        config.bootstrap_secret = Some(new_root.to_owned());
    });
    assert_eq!(
        get_json(
            &changed_root,
            "/v1/family/members",
            owner["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED,
    );
    assert_eq!(
        get_json(
            &changed_root,
            "/v1/family/members",
            member["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );

    let login = json!({
        "login_request_id": "root-rotation-login-request-bbbb00001",
        "device_name": "新管理员手机",
    });
    let (wrong, wrong_body) = json_request_with_headers(
        &changed_root,
        Method::POST,
        "/v1/owner/login",
        None,
        login.clone(),
        &[("x-lezi-bootstrap-secret", old_root)],
    )
    .await;
    let (correct, recovered) = json_request_with_headers(
        &changed_root,
        Method::POST,
        "/v1/owner/login",
        None,
        login,
        &[("x-lezi-bootstrap-secret", new_root)],
    )
    .await;
    assert_eq!(wrong, StatusCode::UNAUTHORIZED, "{wrong_body}");
    assert!(!wrong_body.to_string().contains("乐乐一家"));
    assert_eq!(correct, StatusCode::OK, "{recovered}");
    assert_eq!(recovered["membership_id"], owner["membership_id"]);

    let database = fs::read(rig.directory.path().join("lezi.db")).unwrap();
    for secret in [old_root, new_root] {
        assert!(!database
            .windows(secret.len())
            .any(|window| window == secret.as_bytes()));
    }
}

#[tokio::test]
async fn member_request_has_no_family_authority_and_owner_approval_claims_once() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "member-request-owner-create-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();

    let (created, pending) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({"display_name": "爸爸", "device_name": "爸爸的手机"}),
    )
    .await;
    assert_eq!(created, StatusCode::CREATED, "{pending}");
    assert_eq!(pending["status"], "pending");
    let request_id = pending["request_id"].as_str().unwrap();
    let pending_secret = pending["pending_secret"].as_str().unwrap();
    assert!(pending_secret.len() >= 32);
    assert_ne!(request_id, pending_secret);

    // Neither a public id nor the pending capability is a family credential.
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(pending_secret))
            .await
            .0,
        StatusCode::UNAUTHORIZED,
    );
    let (public_status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests/status",
        None,
        json!({"pending_secret": request_id}),
    )
    .await;
    assert_eq!(public_status, StatusCode::NOT_FOUND);

    let (listed, list) = get_json(&rig.app, "/v1/member/requests", Some(owner_token)).await;
    assert_eq!(listed, StatusCode::OK, "{list}");
    assert_eq!(
        list,
        json!({"requests": [{
            "request_id": request_id,
            "display_name": "爸爸",
            "device_name": "爸爸的手机",
            "status": "pending",
            "created_at": rig.now.load(Ordering::SeqCst),
            "expires_at": rig.now.load(Ordering::SeqCst) + 24 * 60 * 60,
        }]})
    );

    let (approved, approval) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/member/requests/{request_id}/approve-new"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(approved, StatusCode::OK, "{approval}");
    assert_eq!(approval, json!({"ok": true, "status": "approved"}));

    let (_, status_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests/status",
        None,
        json!({"pending_secret": pending_secret}),
    )
    .await;
    assert_eq!(status_body, json!({"status": "approved"}));

    let (claimed, member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests/claim",
        None,
        json!({"pending_secret": pending_secret}),
    )
    .await;
    assert_eq!(claimed, StatusCode::OK, "{member}");
    assert_eq!(member["role"], "member");
    assert_eq!(member["family_id"], owner["family_id"]);
    assert_ne!(member["membership_id"], owner["membership_id"]);
    assert!(member["access_token"]
        .as_str()
        .is_some_and(|it| !it.is_empty()));
    assert!(member["refresh_token"]
        .as_str()
        .is_some_and(|it| !it.is_empty()));

    let (claimed_again, replayed_member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests/claim",
        None,
        json!({"pending_secret": pending_secret}),
    )
    .await;
    assert_eq!(claimed_again, StatusCode::OK);
    assert_eq!(replayed_member["device_id"], member["device_id"]);
    assert_eq!(replayed_member["access_token"], member["access_token"]);
    assert_eq!(replayed_member["refresh_token"], member["refresh_token"]);
    let (rotate_status, rotated_member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": member["refresh_token"]}),
    )
    .await;
    assert_eq!(rotate_status, StatusCode::OK, "{rotated_member}");
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/requests/claim",
            None,
            json!({"pending_secret": pending_secret}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );
    let (pull_status, pull_body) = get_json(
        &rig.app,
        &format!(
            "/v1/pull?cursor=0&generation={}",
            rotated_member["generation"].as_str().unwrap(),
        ),
        rotated_member["access_token"].as_str(),
    )
    .await;
    assert_eq!(pull_status, StatusCode::OK, "{pull_body}");

    let (_, duplicate) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({"display_name": "　爸爸　", "device_name": "第二台手机"}),
    )
    .await;
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!(
                "/v1/member/requests/{}/approve-new",
                duplicate["request_id"].as_str().unwrap(),
            ),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let database = fs::read(rig.directory.path().join("lezi.db")).unwrap();
    assert!(!database
        .windows(pending_secret.len())
        .any(|window| window == pending_secret.as_bytes()));
}

#[tokio::test]
async fn owner_explicitly_binds_a_pending_device_to_an_existing_member() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bind-existing-owner-device",
        "bind-existing-owner-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let first_device = approve_new_member(&rig.app, owner_token, "bind-existing-member-a").await;
    let other_member = approve_new_member(&rig.app, owner_token, "bind-existing-member-b").await;
    let first_token = first_device["access_token"].as_str().unwrap();
    let other_token = other_member["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            first_token,
            entity_wire(
                "care_plan",
                &plan_id,
                2,
                care_plan_payload(&baby_id, "bath"),
                None,
            ),
        )
        .await
        .0,
        StatusCode::OK,
    );
    let record_id = Uuid::new_v4().to_string();
    let media_ids = [
        Uuid::new_v4().to_string(),
        Uuid::new_v4().to_string(),
        Uuid::new_v4().to_string(),
    ];
    let (published, published_body) = publish_bundle_with_media(
        &rig.app,
        first_token,
        entity_wire("record", &record_id, 3, record_payload(&baby_id), None),
        media_ids
            .iter()
            .enumerate()
            .map(|(index, media_id)| {
                (
                    entity_wire(
                        "media",
                        media_id,
                        4 + index as i64,
                        log_media_payload(&record_id),
                        None,
                    ),
                    vec![b'a' + index as u8; 3],
                )
            })
            .collect(),
    )
    .await;
    assert_eq!(published, StatusCode::OK, "{published_body}");

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/requests",
            None,
            json!({
                "display_name": "成员-bind-existing-member-a",
                "device_name": "伪造身份的设备",
                "membership_id": owner["membership_id"],
                "role": "owner",
                "device_id": owner["device_id"],
            }),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY,
    );
    let (_, pending) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({
            "display_name": "成员-bind-existing-member-a",
            "device_name": "同一成员的平板",
        }),
    )
    .await;
    let request_id = pending["request_id"].as_str().unwrap();
    let pending_secret = pending["pending_secret"].as_str().unwrap();
    let bind_path = format!("/v1/member/requests/{request_id}/bind-existing");

    // A matching display name remains only a hint: claim is impossible until
    // an Owner explicitly selects an existing ordinary membership.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/requests/claim",
            None,
            json!({"pending_secret": pending_secret}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &bind_path,
            Some(owner_token),
            json!({
                "membership_id": first_device["membership_id"],
                "role": "owner",
                "device_id": "forged-device",
            }),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &bind_path,
            Some(first_token),
            json!({"membership_id": first_device["membership_id"]}),
        )
        .await
        .0,
        StatusCode::FORBIDDEN,
    );

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &bind_path,
            Some(owner_token),
            json!({"membership_id": owner["membership_id"]}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let (bound, body) = json_request(
        &rig.app,
        Method::POST,
        &bind_path,
        Some(owner_token),
        json!({"membership_id": first_device["membership_id"]}),
    )
    .await;
    assert_eq!(bound, StatusCode::OK, "{body}");
    assert_eq!(body, json!({"ok": true, "status": "approved"}));
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &bind_path,
            Some(owner_token),
            json!({"membership_id": first_device["membership_id"]}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &bind_path,
            Some(owner_token),
            json!({"membership_id": other_member["membership_id"]}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/member/requests/{request_id}/approve-new"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let (claimed, second_device) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests/claim",
        None,
        json!({"pending_secret": pending_secret}),
    )
    .await;
    assert_eq!(claimed, StatusCode::OK, "{second_device}");
    assert_eq!(second_device["role"], "member");
    assert_eq!(
        second_device["membership_id"],
        first_device["membership_id"]
    );
    assert_ne!(second_device["device_id"], first_device["device_id"]);
    assert_ne!(second_device["access_token"], first_device["access_token"]);
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(first_token))
            .await
            .0,
        StatusCode::OK,
    );
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            second_device["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );
    let second_token = second_device["access_token"].as_str().unwrap();
    let (_, history) = get_json(&rig.app, "/v1/pull?cursor=0", Some(second_token)).await;
    for expected_id in std::iter::once(&record_id)
        .chain(std::iter::once(&plan_id))
        .chain(media_ids.iter())
    {
        assert!(
            history["entities"]
                .as_array()
                .unwrap()
                .iter()
                .any(|entity| entity["client_uuid"] == *expected_id),
            "new device did not receive complete history entity {expected_id}: {history}",
        );
    }
    for media_id in &media_ids {
        assert_eq!(
            request(
                &rig.app,
                Method::GET,
                &format!("/v1/media/{media_id}"),
                Some(second_token),
                Body::empty(),
                None,
            )
            .await
            .status(),
            StatusCode::OK,
        );
    }

    // ACL derives from the authenticated membership, not the physical device.
    let mut same_member_edit = care_plan_payload(&baby_id, "bath");
    same_member_edit["note"] = json!("平板编辑");
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            second_token,
            entity_wire("care_plan", &plan_id, 10, same_member_edit.clone(), None),
        )
        .await
        .0,
        StatusCode::OK,
    );
    same_member_edit["note"] = json!("其它成员越权");
    let (forbidden_status, forbidden_body) = publish_root_bundle(
        &rig.app,
        other_token,
        entity_wire("care_plan", &plan_id, 11, same_member_edit, None),
    )
    .await;
    assert_eq!(forbidden_status, StatusCode::OK, "{forbidden_body}");
    assert_eq!(forbidden_body["status"], "rejected");
    assert_eq!(forbidden_body["error"]["code"], "forbidden");

    // Deleting the selected membership also deletes the already-bound request;
    // it cannot silently change the Owner's decision to "new".
    let (_, doomed_request) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({
            "display_name": "绝不能意外新建",
            "device_name": "等待目标删除的设备",
        }),
    )
    .await;
    let doomed_request_id = doomed_request["request_id"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/member/requests/{doomed_request_id}/bind-existing"),
            Some(owner_token),
            json!({"membership_id": other_member["membership_id"]}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/members/remove",
            Some(owner_token),
            json!({"membership_id": other_member["membership_id"]}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/requests/claim",
            None,
            json!({"pending_secret": doomed_request["pending_secret"]}),
        )
        .await
        .0,
        StatusCode::NOT_FOUND,
    );
    let (_, after_deleted_target) =
        get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert!(!after_deleted_target["members"]
        .as_array()
        .unwrap()
        .iter()
        .any(|member| member["display_name"] == "绝不能意外新建"));
    let (replay_status, replayed_second_device) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests/claim",
        None,
        json!({"pending_secret": pending_secret}),
    )
    .await;
    assert_eq!(replay_status, StatusCode::OK, "{replayed_second_device}");
    assert_eq!(
        replayed_second_device["device_id"],
        second_device["device_id"]
    );
    assert_eq!(
        replayed_second_device["access_token"],
        second_device["access_token"]
    );
}

#[tokio::test]
async fn owner_member_login_grant_is_ten_minutes_single_use_and_target_bound() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "member-grant-owner-device",
        "member-grant-owner-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "member-grant-phone").await;
    let member_token = member["access_token"].as_str().unwrap();
    let other = approve_new_member(&rig.app, owner_token, "member-grant-other").await;

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants",
            Some(owner_token),
            json!({
                "membership_id": member["membership_id"],
                "role": "owner",
                "device_id": owner["device_id"],
            }),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants",
            Some(member_token),
            json!({"membership_id": member["membership_id"]}),
        )
        .await
        .0,
        StatusCode::FORBIDDEN,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants",
            Some(owner_token),
            json!({"membership_id": owner["membership_id"]}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let (created, login_grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    assert_eq!(created, StatusCode::CREATED, "{login_grant}");
    assert_eq!(
        login_grant["expires_at"],
        rig.now.load(Ordering::SeqCst) + 600
    );
    assert_eq!(login_grant["family_name"], "测试家庭");
    assert_eq!(
        login_grant["member_display_name"],
        "成员-member-grant-phone",
    );
    let grant = login_grant["grant"].as_str().unwrap();
    assert!(grant.len() >= 43);
    assert!(login_grant.get("access_token").is_none());
    assert!(login_grant.get("refresh_token").is_none());
    assert!(login_grant.get("root_password").is_none());
    let database = fs::read(rig.directory.path().join("lezi.db")).unwrap();
    assert!(!database
        .windows(grant.len())
        .any(|window| window == grant.as_bytes()));

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({
                "grant": grant,
                "device_name": "伪造字段设备",
                "membership_id": owner["membership_id"],
                "role": "owner",
            }),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY,
    );
    let (claimed, new_device) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants/claim",
        None,
        json!({"grant": grant, "device_name": "妈妈的新平板"}),
    )
    .await;
    assert_eq!(claimed, StatusCode::OK, "{new_device}");
    assert_eq!(new_device["role"], "member");
    assert_eq!(new_device["membership_id"], member["membership_id"]);
    assert_ne!(new_device["device_id"], member["device_id"]);
    assert_ne!(new_device["access_token"], member["access_token"]);
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            new_device["access_token"].as_str(),
        )
        .await
        .0,
        StatusCode::OK,
    );
    let (replay_status, replayed_device) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants/claim",
        None,
        json!({"grant": grant, "device_name": "妈妈的新平板"}),
    )
    .await;
    assert_eq!(replay_status, StatusCode::OK, "{replayed_device}");
    assert_eq!(replayed_device["device_id"], new_device["device_id"]);
    assert_eq!(replayed_device["access_token"], new_device["access_token"]);
    assert_eq!(
        replayed_device["refresh_token"],
        new_device["refresh_token"]
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({"grant": grant, "device_name": "重放设备"}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );
    json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": new_device["refresh_token"]}),
    )
    .await;
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({"grant": grant, "device_name": "妈妈的新平板"}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let (_, expiring) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    rig.now.fetch_add(600, Ordering::SeqCst);
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({"grant": expiring["grant"], "device_name": "过期设备"}),
        )
        .await
        .0,
        StatusCode::GONE,
    );

    let (_, deleted_target) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": other["membership_id"]}),
    )
    .await;
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/members/remove",
            Some(owner_token),
            json!({"membership_id": other["membership_id"]}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({"grant": deleted_target["grant"], "device_name": "已删除成员设备"}),
        )
        .await
        .0,
        StatusCode::NOT_FOUND,
    );

    let deleted_family_rig = Rig::new();
    let deleted_family_owner = create_family(
        &deleted_family_rig.app,
        "deleted-family-owner",
        "deleted-family-owner-request-0001",
    )
    .await;
    let deleted_family_member = approve_new_member(
        &deleted_family_rig.app,
        deleted_family_owner["access_token"].as_str().unwrap(),
        "deleted-family-member",
    )
    .await;
    let (_, deleted_family_grant) = json_request(
        &deleted_family_rig.app,
        Method::POST,
        "/v1/member/login-grants",
        deleted_family_owner["access_token"].as_str(),
        json!({"membership_id": deleted_family_member["membership_id"]}),
    )
    .await;
    let connection = Connection::open(deleted_family_rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute_batch("PRAGMA foreign_keys = ON; DELETE FROM families;")
        .unwrap();
    assert_eq!(
        json_request(
            &deleted_family_rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({
                "grant": deleted_family_grant["grant"],
                "device_name": "已删除家庭设备",
            }),
        )
        .await
        .0,
        StatusCode::NOT_FOUND,
    );
}

#[tokio::test]
async fn member_login_grant_advertises_only_the_configured_lan_install_page() {
    let rig = Rig::with_config(|config| {
        config.lan_apk_download_origin = Some("http://192.168.77.10:8767".to_owned());
    });
    let owner = create_family(
        &rig.app,
        "member-grant-landing-owner",
        "member-grant-landing-owner-request-01",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "member-grant-landing-member").await;

    let (status, grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;

    assert_eq!(status, StatusCode::CREATED, "{grant}");
    assert_eq!(grant["landing_url"], "http://192.168.77.10:8767/join",);

    let without_landing = Rig::new();
    let owner = create_family(
        &without_landing.app,
        "member-grant-no-landing-owner",
        "member-grant-no-landing-request-0001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(
        &without_landing.app,
        owner_token,
        "member-grant-no-landing-member",
    )
    .await;
    let (_, grant) = json_request(
        &without_landing.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    assert!(grant.get("landing_url").is_none(), "{grant}");
}

#[tokio::test]
async fn rejected_cancelled_and_expired_member_requests_create_no_identity() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "member-request-terminal-owner-00001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();

    let mut requests = Vec::new();
    for (display_name, device_name) in [
        ("被拒绝", "设备甲"),
        ("主动取消", "设备乙"),
        ("已经过期", "设备丙"),
    ] {
        let (_, body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/member/requests",
            None,
            json!({"display_name": display_name, "device_name": device_name}),
        )
        .await;
        requests.push(body);
    }

    let (rejected, _) = json_request(
        &rig.app,
        Method::POST,
        &format!(
            "/v1/member/requests/{}/reject",
            requests[0]["request_id"].as_str().unwrap(),
        ),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(rejected, StatusCode::OK);
    let (cancelled, cancelled_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests/cancel",
        None,
        json!({"pending_secret": requests[1]["pending_secret"]}),
    )
    .await;
    assert_eq!(cancelled, StatusCode::OK);
    assert_eq!(cancelled_body, json!({"ok": true, "status": "cancelled"}));
    rig.now.fetch_add(24 * 60 * 60, Ordering::SeqCst);

    for (request, expected) in requests.iter().zip(["rejected", "cancelled", "expired"]) {
        let (status, body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/member/requests/status",
            None,
            json!({"pending_secret": request["pending_secret"]}),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{body}");
        assert_eq!(body, json!({"status": expected}));
        assert_eq!(
            json_request(
                &rig.app,
                Method::POST,
                "/v1/member/requests/claim",
                None,
                json!({"pending_secret": request["pending_secret"]}),
            )
            .await
            .0,
            if expected == "expired" {
                StatusCode::GONE
            } else {
                StatusCode::CONFLICT
            },
        );
    }

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    assert_eq!(
        connection
            .query_row("SELECT COUNT(*) FROM memberships", [], |row| row
                .get::<_, i64>(0))
            .unwrap(),
        1,
    );
    assert_eq!(
        connection
            .query_row("SELECT COUNT(*) FROM devices", [], |row| row
                .get::<_, i64>(0))
            .unwrap(),
        1,
    );
    assert_eq!(
        connection
            .query_row("SELECT COUNT(*) FROM device_sessions", [], |row| row
                .get::<_, i64>(0))
            .unwrap(),
        1,
    );
}

#[tokio::test]
async fn member_requests_are_source_limited_and_family_pending_is_bounded() {
    let rig = Rig::with_config(|config| {
        config.member_request_rate_limit = RateLimitConfig {
            max_attempts: 1,
            window_seconds: 60,
        };
        config.max_pending_member_requests = 1;
    });
    create_family(
        &rig.app,
        "owner-device",
        "member-request-limits-owner-000001",
    )
    .await;
    let first_source = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 50, 21)), 50001);
    let second_source = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 50, 22)), 50002);

    assert_eq!(
        json_request_from(
            &rig.app,
            first_source,
            "/v1/member/requests",
            json!({"display_name": "成员甲", "device_name": "设备甲"}),
        )
        .await
        .0,
        StatusCode::CREATED,
    );
    assert_eq!(
        json_request_from(
            &rig.app,
            first_source,
            "/v1/member/requests",
            json!({"display_name": "成员乙", "device_name": "设备乙"}),
        )
        .await
        .0,
        StatusCode::TOO_MANY_REQUESTS,
    );
    // A different source passes its own limiter, then hits the family-wide pending cap.
    assert_eq!(
        json_request_from(
            &rig.app,
            second_source,
            "/v1/member/requests",
            json!({"display_name": "成员丙", "device_name": "设备丙"}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );
}

#[tokio::test]
async fn ordinary_members_cannot_list_or_decide_pending_member_requests() {
    let rig = Rig::new();
    let owner = create_family(&rig.app, "owner-device", "member-request-acl-owner-0000001").await;
    let member = approve_new_member(
        &rig.app,
        owner["access_token"].as_str().unwrap(),
        "legacy-member-device",
    )
    .await;
    let (_, pending) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({"display_name": "新成员", "device_name": "新设备"}),
    )
    .await;
    let member_token = member["access_token"].as_str().unwrap();

    assert_eq!(
        get_json(&rig.app, "/v1/member/requests", Some(member_token))
            .await
            .0,
        StatusCode::FORBIDDEN,
    );
    for action in ["approve-new", "reject"] {
        assert_eq!(
            json_request(
                &rig.app,
                Method::POST,
                &format!(
                    "/v1/member/requests/{}/{action}",
                    pending["request_id"].as_str().unwrap(),
                ),
                Some(member_token),
                json!({}),
            )
            .await
            .0,
            StatusCode::FORBIDDEN,
        );
    }
}
#[tokio::test]
async fn family_create_requires_the_root_password_and_never_reopens_configured_setup() {
    let secret = "sixteen-chars!!!!";
    let rig = Rig::with_config(|config| {
        config.bootstrap_secret = Some(secret.to_owned());
    });
    let request = json!({
        "create_request_id": "bootstrap-create-request-aaaa00000001",
        "display_name": "妈妈",
        "device_name": "妈妈的手机",
        "family_name": "乐乐一家",
    });

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/create",
            None,
            request.clone(),
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED,
    );
    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/family/create",
            None,
            request.clone(),
            &[("x-lezi-bootstrap-secret", "wrong-secret!!!!!!")],
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED,
    );
    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/family/create",
            None,
            request,
            &[("x-lezi-bootstrap-secret", secret)],
        )
        .await
        .0,
        StatusCode::CREATED,
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(secret))
            .await
            .0,
        StatusCode::UNAUTHORIZED,
        "the one-time root password must never become a daily Bearer credential",
    );

    let configured_request = json!({
        "create_request_id": "bootstrap-create-request-bbbb00000002",
        "display_name": "爸爸",
        "device_name": "爸爸的手机",
        "family_name": "第二家庭",
    });
    let (second_status, second_body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        configured_request.clone(),
        &[("x-lezi-bootstrap-secret", secret)],
    )
    .await;
    assert_eq!(second_status, StatusCode::CONFLICT);
    for headers in [
        Vec::new(),
        vec![("x-lezi-bootstrap-secret", "wrong-secret!!!!!!")],
    ] {
        let (status, body) = json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/family/create",
            None,
            configured_request.clone(),
            &headers,
        )
        .await;
        assert_eq!(status, second_status);
        assert_eq!(body, second_body);
    }
}
#[tokio::test]
async fn current_record_and_care_plan_wire_rejects_obsolete_or_untyped_payloads() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "strict-payload-owner",
        "strict-payload-owner-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;

    for invalid_schema in [Value::Null, json!(1), json!(3), json!("2")] {
        let mut record = record_payload(&baby_id);
        record["schema_version"] = invalid_schema.clone();
        let (record_status, record_body) = stage_bundle_with_media(
            &rig.app,
            token,
            entity_wire("record", &Uuid::new_v4().to_string(), 2, record, None),
            vec![],
        )
        .await;
        assert_eq!(
            record_status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "{record_body}"
        );

        let mut plan = care_plan_payload(&baby_id, "formula");
        plan["schema_version"] = invalid_schema;
        let (plan_status, plan_body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(token),
            json!({
                "bundle_id": Uuid::new_v4(),
                "root": entity_wire(
                    "care_plan",
                    &Uuid::new_v4().to_string(),
                    2,
                    plan,
                    None,
                ),
                "media": [],
            }),
        )
        .await;
        assert_eq!(plan_status, StatusCode::UNPROCESSABLE_ENTITY, "{plan_body}");
    }

    for root_type in ["record", "care_plan"] {
        let mut payload = if root_type == "record" {
            record_payload(&baby_id)
        } else {
            care_plan_payload(&baby_id, "formula")
        };
        payload.as_object_mut().unwrap().remove("schema_version");
        let (status, body) = stage_bundle_with_media(
            &rig.app,
            token,
            entity_wire(root_type, &Uuid::new_v4().to_string(), 2, payload, None),
            vec![],
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
    }

    for (record_type, nested) in [
        ("memo", json!({})),
        ("other", json!({})),
        ("unknown", json!({})),
        ("formula", json!({})),
        ("formula", json!({"amount_ml": "120"})),
        ("formula", json!({"amount_ml": 120, "legacy_amount": 120})),
        ("temperature", json!({"value": 36.7})),
        ("diary", json!({"body": "日记", "photos": ["/data/a.jpg"]})),
        ("custom", json!({"title": "抚触", "custom_item_id": 1})),
    ] {
        let mut record = record_payload(&baby_id);
        record["type"] = json!(record_type);
        record["payload_json"] = nested.clone();
        let (record_status, record_body) = stage_bundle_with_media(
            &rig.app,
            token,
            entity_wire("record", &Uuid::new_v4().to_string(), 2, record, None),
            vec![],
        )
        .await;
        assert_eq!(
            record_status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "{record_body}"
        );

        let mut plan = care_plan_payload(&baby_id, "formula");
        plan["type"] = json!(record_type);
        plan["payload_json"] = nested;
        let (plan_status, plan_body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(token),
            json!({
                "bundle_id": Uuid::new_v4(),
                "root": entity_wire(
                    "care_plan",
                    &Uuid::new_v4().to_string(),
                    2,
                    plan,
                    None,
                ),
                "media": [],
            }),
        )
        .await;
        assert_eq!(plan_status, StatusCode::UNPROCESSABLE_ENTITY, "{plan_body}");
    }

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(pull["entities"].as_array().unwrap().len(), 1);
    assert_eq!(pull["entities"][0]["type"], "baby");
}

#[tokio::test]
async fn family_members_are_authenticated_isolated_stable_and_redacted() {
    let rig = Rig::new();
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", None).await.0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some("not-a-token"))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );

    let owner = create_family(
        &rig.app,
        "owner-sensitive-device-id",
        "member-list-owner-request-0000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member_named(
        &rig.app,
        owner_token,
        "member-sensitive-device-id",
        "　 陈爸爸 🌿  ",
    )
    .await;
    let member_token = member["access_token"].as_str().unwrap();

    let owner_membership_id = owner["membership_id"].as_str().unwrap();
    let member_membership_id = member["membership_id"].as_str().unwrap();
    let (owner_status, owner_view) =
        get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(owner_status, StatusCode::OK);
    let now = rig.now.load(Ordering::SeqCst);
    let directory_generation = owner_view["directory_generation"].clone();
    assert_eq!(
        directory_generation.as_str().map(str::len),
        Some(64),
        "{owner_view}"
    );
    assert_eq!(
        owner_view,
        json!({
            "directory_generation": directory_generation,
            "members":[
            {
                "display_name":"妈妈",
                "role":"owner",
                "is_self":true,
                "membership_id": owner_membership_id,
                "last_sync_at": now,
                "devices":[{
                    "device_id":owner["device_id"],
                    "device_name":"owner-sensitive-device-id",
                    "last_used_at": now,
                    "is_current":true,
                }],
            },
            {
                "display_name":"陈爸爸 🌿",
                "role":"member",
                "is_self":false,
                "membership_id": member_membership_id,
                "last_sync_at": now,
                "devices":[{
                    "device_id":member["device_id"],
                    "device_name":"member-sensitive-device-id",
                    "last_used_at": now,
                    "is_current":false,
                }],
            },
            ],
        })
    );
    let (member_status, member_view) =
        get_json(&rig.app, "/v1/family/members", Some(member_token)).await;
    assert_eq!(member_status, StatusCode::OK);
    assert_eq!(
        member_view,
        json!({
            "directory_generation": directory_generation,
            "members":[
            {
                "display_name":"妈妈",
                "role":"owner",
                "is_self":false,
                "membership_id": owner_membership_id,
                "last_sync_at": now,
            },
            {
                "display_name":"陈爸爸 🌿",
                "role":"member",
                "is_self":true,
                "membership_id": member_membership_id,
                "last_sync_at": now,
                "devices":[{
                    "device_id":member["device_id"],
                    "device_name":"member-sensitive-device-id",
                    "last_used_at": now,
                    "is_current":true,
                }],
            },
            ],
        })
    );
    let visible_members = |body: &Value| {
        body["members"]
            .as_array()
            .unwrap()
            .iter()
            .map(|member| {
                (
                    member["display_name"].clone(),
                    member["role"].clone(),
                    member["membership_id"].clone(),
                )
            })
            .collect::<Vec<_>>()
    };
    assert_eq!(visible_members(&owner_view), visible_members(&member_view));

    let serialized = owner_view.to_string();
    // Tokens, token hashes, credential state and transport details must never appear.
    // Authorized device and membership IDs are stable action keys and may appear.
    for secret in [
        owner_token,
        member_token,
        &token_hash(owner_token),
        &token_hash(member_token),
    ] {
        assert!(
            !serialized.contains(secret),
            "leaked {secret}: {serialized}"
        );
    }
    for row in owner_view["members"].as_array().unwrap() {
        let fields = row.as_object().unwrap();
        // display_name, role, is_self, membership_id, last_sync_at, devices
        assert_eq!(fields.len(), 6);
        assert!(fields.contains_key("membership_id"));
        assert!(fields.contains_key("devices"));
        assert!(fields.contains_key("last_sync_at"));
        assert!(!fields.contains_key("token_hash"));
        assert!(!fields.contains_key("token"));
        assert!(!fields.contains_key("family_id"));
        for device in row["devices"].as_array().unwrap() {
            let device_fields = device.as_object().unwrap();
            assert_eq!(device_fields.len(), 4);
            assert!(!device_fields.contains_key("status"));
            assert!(!device_fields.contains_key("session_id"));
            assert!(!device_fields.contains_key("access_token_hash"));
            assert!(!device_fields.contains_key("refresh_token_hash"));
            assert!(!device_fields.contains_key("ip"));
            assert!(!device_fields.contains_key("port"));
        }
    }
    assert!(!member_view["members"][0]
        .as_object()
        .unwrap()
        .contains_key("devices"));

    let isolated_family_id = Uuid::new_v4().to_string();
    let isolated_token = "isolated-family-owner-token";
    let isolated_membership_id = Uuid::new_v4().to_string();
    let isolated_device_id = Uuid::new_v4().to_string();
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "INSERT INTO families(id, created_at) VALUES (?1, ?2)",
            rusqlite::params![isolated_family_id, rig.now.load(Ordering::SeqCst)],
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, 0)",
            rusqlite::params![isolated_family_id],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, display_name, display_name_key
            ) VALUES (?1, ?2, 'owner', '隔离家庭', '隔离家庭')
            ",
            rusqlite::params![isolated_membership_id, isolated_family_id],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO devices(
                device_id, membership_id, device_name, device_name_key,
                status, created_at, last_used_at
            ) VALUES (?1, ?2, '隔离设备', '隔离设备', 'active', ?3, ?3)
            ",
            rusqlite::params![
                isolated_device_id,
                isolated_membership_id,
                rig.now.load(Ordering::SeqCst)
            ],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO device_sessions(
                session_id, device_id, access_token_hash, access_expires_at, refresh_token_hash
            ) VALUES (?1, ?2, ?3, ?4, ?5)
            ",
            rusqlite::params![
                Uuid::new_v4().to_string(),
                isolated_device_id,
                token_hash(isolated_token),
                rig.now.load(Ordering::SeqCst) + 900,
                token_hash("isolated-refresh-token"),
            ],
        )
        .unwrap();
    drop(connection);

    let (_, isolated_view) = get_json(&rig.app, "/v1/family/members", Some(isolated_token)).await;
    assert_eq!(isolated_view["members"].as_array().unwrap().len(), 1);
    assert_eq!(isolated_view["members"][0]["display_name"], "隔离家庭");
    assert_eq!(isolated_view["members"][0]["role"], "owner");
    assert_eq!(isolated_view["members"][0]["is_self"], true);
    assert!(isolated_view["members"][0].get("device_id").is_none());
    assert!(
        isolated_view["members"][0]["membership_id"]
            .as_str()
            .unwrap()
            .len()
            >= 32
    );
    let (_, owner_after_isolated_insert) =
        get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(owner_after_isolated_insert, owner_view);
    assert!(!owner_after_isolated_insert.to_string().contains("隔离家庭"));
    assert!(!isolated_view.to_string().contains("妈妈"));
    // Cross-family isolation: owner view must not list the isolated device.
    assert!(!owner_after_isolated_insert
        .to_string()
        .contains("isolated-raw-device-id"));

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/leave",
            Some(member_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, after_leave) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(after_leave["members"].as_array().unwrap().len(), 1);
    assert_eq!(after_leave["members"][0]["display_name"], "妈妈");
    assert_eq!(after_leave["members"][0]["role"], "owner");
    assert_eq!(after_leave["members"][0]["is_self"], true);
    assert!(after_leave["members"][0].get("device_id").is_none());
    assert_eq!(
        after_leave["members"][0]["membership_id"],
        owner_membership_id
    );
}

#[tokio::test]
async fn owner_hard_deletes_member_anonymizes_shared_facts_and_releases_name() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "remove-owner-device",
        "remove-member-owner-request-001-xxxxxxxx",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let owner_membership_id = owner["membership_id"].as_str().unwrap().to_owned();

    let joined =
        approve_new_member_named(&rig.app, owner_token, "remove-member-device", "爸爸").await;
    let member_token = joined["access_token"].as_str().unwrap();
    let member_refresh_token = joined["refresh_token"].as_str().unwrap();
    let member_membership_id = joined["membership_id"].as_str().unwrap().to_owned();
    assert_eq!(joined["role"], "member");

    let (_, second_device_grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member_membership_id}),
    )
    .await;
    let (second_device_status, second_device) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants/claim",
        None,
        json!({
            "grant": second_device_grant["grant"],
            "device_name": "爸爸的平板",
        }),
    )
    .await;
    assert_eq!(second_device_status, StatusCode::OK, "{second_device}");
    let second_device_token = second_device["access_token"].as_str().unwrap();
    let second_device_refresh = second_device["refresh_token"].as_str().unwrap();

    let (_, unused_grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member_membership_id}),
    )
    .await;
    assert!(unused_grant["grant"].is_string());
    let (rename_status, rename_request) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/display-name",
        Some(member_token),
        json!({"display_name": "爸爸待改名"}),
    )
    .await;
    assert_eq!(rename_status, StatusCode::ACCEPTED, "{rename_request}");

    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let (published_status, published_body) = publish_bundle_with_media(
        &rig.app,
        member_token,
        entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
        vec![(
            entity_wire("media", &media_id, 2, log_media_payload(&record_id), None),
            b"log".to_vec(),
        )],
    )
    .await;
    assert_eq!(published_status, StatusCode::OK, "{published_body}");

    // Member cannot remove anyone.
    let (member_remove, member_remove_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/members/remove",
        Some(member_token),
        json!({ "membership_id": owner_membership_id }),
    )
    .await;
    assert_eq!(member_remove, StatusCode::FORBIDDEN);
    assert!(member_remove_body["detail"]
        .as_str()
        .unwrap_or("")
        .to_lowercase()
        .contains("owner"));

    // Owner cannot remove self.
    let (self_remove, self_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/members/remove",
        Some(owner_token),
        json!({ "membership_id": owner_membership_id }),
    )
    .await;
    assert_eq!(self_remove, StatusCode::FORBIDDEN);
    assert!(self_body["detail"]
        .as_str()
        .unwrap_or("")
        .to_lowercase()
        .contains("yourself"));

    // Owner hard-deletes the member and every identity-bearing child row.
    let (ok_status, ok_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/members/remove",
        Some(owner_token),
        json!({ "membership_id": member_membership_id }),
    )
    .await;
    assert_eq!(ok_status, StatusCode::OK);
    assert_eq!(ok_body["ok"], true);
    assert_eq!(ok_body["membership_id"], member_membership_id);

    // The terminal reason is durable: an offline device can reconnect after a
    // server restart and still distinguish membership deletion from a generic 401.
    let restarted = rig.restart("generation-after-member-delete");
    for token in [member_token, second_device_token] {
        let (status, body) = get_json(&restarted, "/v1/family/members", Some(token)).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["code"], "membership_deleted");
    }
    for refresh in [member_refresh_token, second_device_refresh] {
        let (status, body) = json_request(
            &restarted,
            Method::POST,
            "/v1/session/refresh",
            None,
            json!({"refresh_token": refresh}),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["code"], "membership_deleted");
    }

    let (_, after) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(after["members"].as_array().unwrap().len(), 1);
    assert_eq!(after["members"][0]["membership_id"], owner_membership_id);

    // Every old access and refresh credential gets a stable terminal reason without
    // retaining a membership/device identity record.
    for token in [member_token, second_device_token] {
        let (status, body) = get_json(&rig.app, "/v1/family/members", Some(token)).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["code"], "membership_deleted");
    }
    for refresh in [member_refresh_token, second_device_refresh] {
        let (status, body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/session/refresh",
            None,
            json!({"refresh_token": refresh}),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["code"], "membership_deleted");
    }

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["client_uuid"] == record_id)
        .expect("member record remains a family fact");
    assert_eq!(record["payload"]["created_by_membership_id"], Value::Null);
    assert!(pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == media_id));
    let downloaded = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(owner_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(downloaded.status(), StatusCode::OK);
    assert_eq!(
        downloaded.into_body().collect().await.unwrap().to_bytes(),
        "log",
    );

    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    for (table, column) in [
        ("memberships", "membership_id"),
        ("member_login_grants", "membership_id"),
        ("member_rename_requests", "membership_id"),
        ("member_login_requests", "membership_id"),
    ] {
        let count: i64 = connection
            .query_row(
                &format!("SELECT COUNT(*) FROM {table} WHERE {column} = ?1"),
                [&member_membership_id],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(count, 0, "{table} retained deleted membership identity");
    }
    let device_count: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM devices WHERE membership_id = ?1",
            [&member_membership_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(device_count, 0);
    let entity_identity_count: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM entities WHERE payload_json LIKE '%' || ?1 || '%'",
            [&member_membership_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(entity_identity_count, 0);
    let bundle_identity_count: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM sync_bundles WHERE staged_membership_id = ?1 OR root_payload_json LIKE '%' || ?1 || '%'",
            [&member_membership_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(bundle_identity_count, 0);
    drop(connection);

    // The normalized family display name is immediately reusable, but it creates a
    // new identity and cannot recover authorship of the anonymous record.
    let replacement = approve_new_member_named(
        &rig.app,
        owner_token,
        "replacement-member-device",
        "  爸爸  ",
    )
    .await;
    assert_ne!(replacement["membership_id"], member_membership_id);
    let (_, replacement_pull) = get_json(
        &rig.app,
        "/v1/pull?cursor=0",
        replacement["access_token"].as_str(),
    )
    .await;
    let replacement_record = replacement_pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["client_uuid"] == record_id)
        .unwrap();
    assert_eq!(
        replacement_record["payload"]["created_by_membership_id"],
        Value::Null,
    );

    // Idempotent-ish: removing again is not found.
    let (again, again_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/members/remove",
        Some(owner_token),
        json!({ "membership_id": member_membership_id }),
    )
    .await;
    assert_eq!(again, StatusCode::NOT_FOUND);
    assert!(again_body["detail"]
        .as_str()
        .unwrap_or("")
        .to_lowercase()
        .contains("not found"));
}

#[tokio::test]
async fn member_leave_hard_deletes_self_but_owner_cannot_leave() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "self-delete-owner-device",
        "self-delete-owner-request-000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "self-delete-member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let member_refresh = member["refresh_token"].as_str().unwrap();

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/leave",
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::FORBIDDEN,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/leave",
            Some(member_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(member_token))
            .await
            .1["code"],
        "membership_deleted",
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/session/refresh",
            None,
            json!({"refresh_token": member_refresh}),
        )
        .await
        .1["code"],
        "membership_deleted",
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(owner_token))
            .await
            .0,
        StatusCode::OK,
    );
}

#[tokio::test]
async fn membership_id_is_stable_across_restart_and_rejects_role_forgery() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "stable-owner-device",
        "stable-membership-owner-request-001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let owner_membership_id = owner["membership_id"].as_str().unwrap().to_owned();
    assert_eq!(owner["role"], "owner");

    let member =
        approve_new_member_named(&rig.app, owner_token, "stable-member-device", "成员").await;
    let member_token = member["access_token"].as_str().unwrap();
    let member_membership_id = member["membership_id"].as_str().unwrap().to_owned();
    assert_eq!(member["role"], "member");
    assert_ne!(member_membership_id, owner_membership_id);

    // Restart must not remint membership identities.
    let restarted = rig.restart("generation-membership-stable");
    let (_, owner_members) = get_json(&restarted, "/v1/family/members", Some(owner_token)).await;
    let (_, member_members) = get_json(&restarted, "/v1/family/members", Some(member_token)).await;
    assert_eq!(
        owner_members["members"][0]["membership_id"],
        owner_membership_id
    );
    assert_eq!(
        owner_members["members"][1]["membership_id"],
        member_membership_id
    );
    assert_eq!(
        member_members["members"][0]["membership_id"],
        owner_membership_id
    );
    assert_eq!(
        member_members["members"][1]["membership_id"],
        member_membership_id
    );

    // Role and writer authority come only from the authenticated principal.
    let (forged_rename, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/family/name",
        Some(member_token),
        json!({"family_name": "伪造家庭"}),
    )
    .await;
    assert_eq!(forged_rename, StatusCode::FORBIDDEN);

    // Retired push rejects every caller before inspecting legacy device fields.
    let (forged_device_status, forged_device_body) = json_request(
        &restarted,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({
            "device_id": "stable-owner-device",
            "entities": [],
        }),
    )
    .await;
    assert_eq!(forged_device_status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(
        forged_device_body,
        json!({"detail":"ordinary push is retired; publish an atomic bundle"})
    );
}

#[tokio::test]
async fn family_members_normalize_unicode_and_reject_unsafe_request_names() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "name-owner-device",
        "member-name-owner-request-000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();

    approve_new_member_named(&rig.app, owner_token, "unicode-member", "　李爸爸 👨‍🍼　").await;

    for (device_id, bad_name) in [
        ("empty-name-member", json!("　  ")),
        ("missing-name-member", json!(null)),
        ("placeholder-member", json!("我（本机）")),
        ("newline-member", json!("名字\n伪装")),
        ("bidi-member", json!("成员\u{202e}renwo")),
        ("long-member", json!("名".repeat(129))),
    ] {
        let (status, _) = json_request(
            &rig.app,
            Method::POST,
            "/v1/member/requests",
            None,
            json!({"display_name": bad_name, "device_name": device_id}),
        )
        .await;
        assert_eq!(
            status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "expected 422 for {device_id}"
        );
    }

    let (omitted_status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({"device_name": "omitted-name-member"}),
    )
    .await;
    assert_eq!(omitted_status, StatusCode::UNPROCESSABLE_ENTITY);

    let (_, members) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(members["members"].as_array().unwrap().len(), 2);
    assert!(members["members"].as_array().unwrap().iter().any(|member| {
        member["display_name"] == "李爸爸 👨‍🍼" && member["role"] == "member"
    }));
}

#[tokio::test]
async fn family_create_uses_the_same_display_name_normalization_as_member_requests() {
    let unsafe_rig = Rig::new();
    let (unsafe_status, _) = json_request(
        &unsafe_rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "unsafe-owner-request-000000000001",
            "display_name": "管理员\u{202e}renwo",
            "device_name": "unsafe-owner-device",
            "family_name": "测试家庭",
        }),
    )
    .await;
    assert_eq!(unsafe_status, StatusCode::UNPROCESSABLE_ENTITY);

    for (request_id, display_name) in [
        ("blank-owner-request-000000000000001", json!("　  ")),
        ("null-owner-request-0000000000000001", json!(null)),
        ("placeholder-owner-request-000000001", json!("我（本机）")),
    ] {
        let blank_rig = Rig::new();
        let (status, _) = json_request(
            &blank_rig.app,
            Method::POST,
            "/v1/family/create",
            None,
            json!({
                "create_request_id": request_id,
                "display_name": display_name,
                "device_name": "blank-owner-device",
                "family_name": "测试家庭",
            }),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
    }

    let normalized_rig = Rig::new();
    let (create_status, owner) = json_request(
        &normalized_rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "trimmed-owner-request-00000000001",
            "display_name": "　妈妈　",
            "device_name": "trimmed-owner-device",
            "family_name": "测试家庭",
        }),
    )
    .await;
    assert_eq!(create_status, StatusCode::CREATED);
    let (_, members) = get_json(
        &normalized_rig.app,
        "/v1/family/members",
        owner["access_token"].as_str(),
    )
    .await;
    assert_eq!(members["members"][0]["display_name"], "妈妈");
    assert!(members["members"][0].get("device_id").is_none());
}

#[tokio::test]
async fn member_name_change_waits_for_owner_and_owner_manages_member_and_device_names() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "rename-owner-device",
        "rename-owner-request-00000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member =
        approve_new_member_named(&rig.app, owner_token, "rename-member-device", "爸爸").await;
    let member_token = member["access_token"].as_str().unwrap();

    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/display-name",
        Some(member_token),
        json!({"display_name": "　干　 爹　"}),
    )
    .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
    assert_eq!(body["status"], "pending");
    assert_eq!(body["requested_display_name"], "干 爹");
    assert_eq!(body["current_display_name"], "爸爸");
    let rename_request_id = body["request_id"].as_str().unwrap();

    let (_, members) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let member_row = members["members"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["is_self"] == false && row["role"] == "member")
        .unwrap();
    assert_eq!(member_row["display_name"], "爸爸");
    assert!(member_row.get("device_id").is_none());

    let (member_pending_status, _) =
        get_json(&rig.app, "/v1/family/rename-requests", Some(member_token)).await;
    assert_eq!(member_pending_status, StatusCode::FORBIDDEN);
    let (pending_status, pending) =
        get_json(&rig.app, "/v1/family/rename-requests", Some(owner_token)).await;
    assert_eq!(pending_status, StatusCode::OK, "{pending}");
    assert_eq!(pending["requests"].as_array().unwrap().len(), 1);
    assert_eq!(pending["requests"][0]["request_id"], rename_request_id);
    assert_eq!(pending["requests"][0]["current_display_name"], "爸爸");
    assert_eq!(pending["requests"][0]["requested_display_name"], "干 爹");
    assert!(pending["requests"][0].get("device_id").is_none());

    let (approve_status, approve) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/family/rename-requests/{rename_request_id}/approve"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(approve_status, StatusCode::OK, "{approve}");
    assert_eq!(approve["display_name"], "干 爹");

    // Owner self-renames immediately and ordinary members cannot reserve a
    // normalized name that is already active in the family.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/display-name",
            Some(owner_token),
            json!({"display_name": "妈妈新称呼"}),
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, after) = get_json(&rig.app, "/v1/family/members", Some(member_token)).await;
    assert!(after["members"].as_array().unwrap().iter().any(|row| {
        row["role"] == "owner" && row["display_name"] == "妈妈新称呼" && row["is_self"] == false
    }));
    assert!(after["members"].as_array().unwrap().iter().any(|row| {
        row["role"] == "member" && row["display_name"] == "干 爹" && row["is_self"] == true
    }));
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/display-name",
            Some(member_token),
            json!({"display_name": "妈妈新称呼"}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    // Owner can create an intentionally device-less member and later target
    // that same membership with the existing one-time login QR contract.
    let (add_status, added) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/members",
        Some(owner_token),
        json!({"display_name": "　奶 奶　"}),
    )
    .await;
    assert_eq!(add_status, StatusCode::CREATED, "{added}");
    assert_eq!(added["display_name"], "奶 奶");
    let added_membership_id = added["membership_id"].as_str().unwrap();
    let (_, owner_projection) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let device_less = owner_projection["members"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["membership_id"] == added_membership_id)
        .unwrap();
    assert_eq!(device_less["devices"], json!([]));
    let (grant_status, grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": added_membership_id}),
    )
    .await;
    assert_eq!(grant_status, StatusCode::CREATED, "{grant}");

    // Owner can directly rename any membership with the same canonical
    // family-wide uniqueness rule.
    let (owner_rename_status, owner_rename) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/family/members/{added_membership_id}/display-name"),
        Some(owner_token),
        json!({"display_name": "外婆"}),
    )
    .await;
    assert_eq!(owner_rename_status, StatusCode::OK, "{owner_rename}");
    assert_eq!(owner_rename["display_name"], "外婆");
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/members/{added_membership_id}/display-name"),
            Some(owner_token),
            json!({"display_name": "妈妈新称呼"}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    // Device names are unique only inside one membership. A member can rename
    // their own device, not the Owner's; the Owner may use the same name on a
    // different membership.
    let (_, owner_devices) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let member_device_id = owner_devices["members"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["membership_id"] == member["membership_id"])
        .unwrap()["devices"][0]["device_id"]
        .as_str()
        .unwrap();
    let owner_device_id = owner_devices["members"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["role"] == "owner")
        .unwrap()["devices"][0]["device_id"]
        .as_str()
        .unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/devices/{member_device_id}/display-name"),
            Some(member_token),
            json!({"device_name": "共享 设备"}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/devices/{owner_device_id}/display-name"),
            Some(member_token),
            json!({"device_name": "越权"}),
        )
        .await
        .0,
        StatusCode::FORBIDDEN,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/devices/{owner_device_id}/display-name"),
            Some(owner_token),
            json!({"device_name": "共享 设备"}),
        )
        .await
        .0,
        StatusCode::OK,
    );

    // A second device for the same membership cannot silently reuse the
    // canonical name. The single-use grant remains retryable with a different
    // user-confirmed name because the failed claim made no mutation.
    let (_, second_device_grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    let second_device_grant = second_device_grant["grant"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({"grant": second_device_grant, "device_name": "共享　设备"}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/member/login-grants/claim",
            None,
            json!({"grant": second_device_grant, "device_name": "成员平板"}),
        )
        .await
        .0,
        StatusCode::OK,
    );

    // Blank / placeholder still 422 on update.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/display-name",
            Some(member_token),
            json!({"display_name": "我（本机）"}),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/display-name",
            Some(member_token),
            json!({"display_name": "  "}),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
}

#[tokio::test]
async fn device_revoke_and_current_logout_are_idempotent_isolated_and_report_device_removed() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "device-revoke-owner",
        "device-revoke-owner-request-0000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "device-revoke-member-phone").await;
    let member_token = member["access_token"].as_str().unwrap();

    let (_, grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    let (_, second_device) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants/claim",
        None,
        json!({"grant": grant["grant"], "device_name": "成员平板"}),
    )
    .await;
    let second_token = second_device["access_token"].as_str().unwrap();
    let first_device_id = member["device_id"].as_str().unwrap();
    let revoke_path = format!("/v1/family/devices/{first_device_id}/revoke");

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &revoke_path,
            Some(second_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::FORBIDDEN,
    );
    for _ in 0..2 {
        let (status, body) = json_request(
            &rig.app,
            Method::POST,
            &revoke_path,
            Some(owner_token),
            json!({}),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{body}");
        assert_eq!(body, json!({"ok": true}));
    }

    let (removed_access_status, removed_access) =
        get_json(&rig.app, "/v1/family/members", Some(member_token)).await;
    assert_eq!(removed_access_status, StatusCode::UNAUTHORIZED);
    assert_eq!(removed_access["code"], "device_removed");
    let (removed_refresh_status, removed_refresh) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": member["refresh_token"]}),
    )
    .await;
    assert_eq!(removed_refresh_status, StatusCode::UNAUTHORIZED);
    assert_eq!(removed_refresh["code"], "device_removed");
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(second_token))
            .await
            .0,
        StatusCode::OK,
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(owner_token))
            .await
            .0,
        StatusCode::OK,
    );
    let (_, projected) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let member_devices = projected["members"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["membership_id"] == member["membership_id"])
        .unwrap()["devices"]
        .as_array()
        .unwrap();
    assert_eq!(member_devices.len(), 1);
    assert_eq!(member_devices[0]["device_id"], second_device["device_id"]);

    // A removed device is a new binding when it returns; no old credential is inherited.
    let (_, rebound_grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    let (rebound_status, rebound) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants/claim",
        None,
        json!({"grant": rebound_grant["grant"], "device_name": "重新绑定手机"}),
    )
    .await;
    assert_eq!(rebound_status, StatusCode::OK, "{rebound}");
    assert_ne!(rebound["device_id"], member["device_id"]);
    assert_ne!(rebound["refresh_token"], member["refresh_token"]);

    // Current-device logout is valid for an ordinary member and does not remove
    // their membership, the sibling device, or the Owner.
    let (logout_status, logout_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/device/logout",
        rebound["access_token"].as_str(),
        json!({}),
    )
    .await;
    assert_eq!(logout_status, StatusCode::OK, "{logout_body}");
    assert_eq!(logout_body, json!({"ok": true}));
    assert_eq!(
        get_json(
            &rig.app,
            "/v1/family/members",
            rebound["access_token"].as_str(),
        )
        .await
        .1["code"],
        "device_removed",
    );
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(second_token))
            .await
            .0,
        StatusCode::OK,
    );
}

#[tokio::test]
async fn member_rename_reject_cancel_expiry_and_concurrent_conflict_preserve_identity() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "rename-state-owner",
        "rename-state-owner-request-000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let owner_refresh_token = owner["refresh_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "rename-state-member").await;
    let member_token = member["access_token"].as_str().unwrap();
    let member_refresh_token = member["refresh_token"].as_str().unwrap();
    let membership_id = member["membership_id"].as_str().unwrap();
    let original_name = "成员-rename-state-member";

    let request_name = |name: &str| {
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/display-name",
            Some(member_token),
            json!({"display_name": name}),
        )
    };
    let (_, rejected_request) = request_name("被拒绝").await;
    let rejected_id = rejected_request["request_id"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/rename-requests/{rejected_id}/reject"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/rename-requests/{rejected_id}/approve"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let (_, cancelled_request) = request_name("已撤回").await;
    let cancelled_id = cancelled_request["request_id"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/rename-requests/cancel",
            Some(member_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/rename-requests/{cancelled_id}/approve"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let (_, expiring_request) = request_name("已过期").await;
    let expiring_id = expiring_request["request_id"].as_str().unwrap();
    rig.now.fetch_add(7 * 24 * 60 * 60 + 1, Ordering::SeqCst);
    let (_, refreshed_owner) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": owner_refresh_token}),
    )
    .await;
    let owner_token = refreshed_owner["access_token"].as_str().unwrap();
    let (_, refreshed_member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": member_refresh_token}),
    )
    .await;
    let member_token = refreshed_member["access_token"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/rename-requests/{expiring_id}/approve"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::GONE,
    );

    // A name can become unavailable after the request was created; approval
    // rechecks inside the same transaction and leaves the old name in force.
    let (_, stale_request) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/display-name",
        Some(member_token),
        json!({"display_name": "并发目标"}),
    )
    .await;
    let stale_id = stale_request["request_id"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/members",
            Some(owner_token),
            json!({"display_name": "并发目标"}),
        )
        .await
        .0,
        StatusCode::CREATED,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/family/rename-requests/{stale_id}/approve"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );

    let (_, projection) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let identity_rows = projection["members"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|row| row["membership_id"] == membership_id)
        .collect::<Vec<_>>();
    assert_eq!(identity_rows.len(), 1);
    assert_eq!(identity_rows[0]["display_name"], original_name);

    // Two Owner maintenance calls racing for one normalized key commit exactly
    // one winner; the losing membership remains a distinct unchanged identity.
    let (_, first) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/members",
        Some(owner_token),
        json!({"display_name": "候选甲"}),
    )
    .await;
    let (_, second) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/members",
        Some(owner_token),
        json!({"display_name": "候选乙"}),
    )
    .await;
    let first_path = format!(
        "/v1/family/members/{}/display-name",
        first["membership_id"].as_str().unwrap(),
    );
    let second_path = format!(
        "/v1/family/members/{}/display-name",
        second["membership_id"].as_str().unwrap(),
    );
    let first_call = json_request(
        &rig.app,
        Method::POST,
        &first_path,
        Some(owner_token),
        json!({"display_name": "唯一　目标"}),
    );
    let second_call = json_request(
        &rig.app,
        Method::POST,
        &second_path,
        Some(owner_token),
        json!({"display_name": "唯一 目标"}),
    );
    let (first_result, second_result) = tokio::join!(first_call, second_call);
    let statuses = [first_result.0, second_result.0];
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == StatusCode::OK)
            .count(),
        1,
    );
    assert_eq!(
        statuses
            .iter()
            .filter(|status| **status == StatusCode::CONFLICT)
            .count(),
        1,
    );
}

#[tokio::test]
async fn shared_family_name_persists_on_create_member_login_and_owner_rename() {
    let rig = Rig::new();
    let request_id = "family-name-create-request-000000000001";
    let (create_status, owner) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "display_name": "妈妈",
            "device_name": "family-name-owner",
            "family_name": "  乐乐一家  ",
        }),
    )
    .await;
    assert_eq!(create_status, StatusCode::CREATED, "{owner}");
    assert_eq!(owner["family_name"], "乐乐一家");
    let owner_token = owner["access_token"].as_str().unwrap();

    // Idempotent retry must match the same family_name (like display_name).
    let (retry_ok, retry_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "display_name": "妈妈",
            "device_name": "family-name-owner",
            "family_name": "乐乐一家",
        }),
    )
    .await;
    assert_eq!(retry_ok, StatusCode::CREATED, "{retry_body}");
    assert_eq!(retry_body["family_id"], owner["family_id"]);
    assert_eq!(retry_body["family_name"], "乐乐一家");

    let (retry_conflict, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "display_name": "妈妈",
            "device_name": "family-name-owner",
            "family_name": "别的名字",
        }),
    )
    .await;
    assert_eq!(retry_conflict, StatusCode::CONFLICT);

    // Create requires a non-blank family name.
    let blank_rig = Rig::new();
    let (blank_status, blank_owner) = json_request(
        &blank_rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "family-name-blank-request-00000000001",
            "display_name": "妈妈",
            "device_name": "blank-name-owner",
            "family_name": "   ",
        }),
    )
    .await;
    assert_eq!(
        blank_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{blank_owner}"
    );

    // An approved member login returns the current shared name.
    let member_login =
        approve_new_member_named(&rig.app, owner_token, "family-name-member", "爸爸").await;
    assert_eq!(member_login["family_name"], "乐乐一家");
    let member_token = member_login["access_token"].as_str().unwrap();

    // Owner may rename; member may not.
    let (rename_status, rename_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/name",
        Some(owner_token),
        json!({"family_name": "  年年的家庭  "}),
    )
    .await;
    assert_eq!(rename_status, StatusCode::OK, "{rename_body}");
    assert_eq!(rename_body["ok"], true);
    assert_eq!(rename_body["family_name"], "年年的家庭");

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/name",
            Some(member_token),
            json!({"family_name": "偷改"}),
        )
        .await
        .0,
        StatusCode::FORBIDDEN
    );

    // After rename, a fresh approved member login sees the new name.
    let second_member_login =
        approve_new_member_named(&rig.app, owner_token, "family-name-member-2", "姥姥").await;
    assert_eq!(second_member_login["family_name"], "年年的家庭");

    // Control characters rejected.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/name",
            Some(owner_token),
            json!({"family_name": "坏\n名字"}),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );

    // The current trusted protocol keeps a non-empty canonical name so the
    // destructive family-name confirmation can never become unreachable.
    let (clear_status, clear_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/name",
        Some(owner_token),
        json!({"family_name": ""}),
    )
    .await;
    assert_eq!(
        clear_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{clear_body}"
    );
}

#[tokio::test]
async fn zero_entity_pull_keeps_non_empty_family_name_across_restart() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "pull-family-name-owner",
        "pull-family-name-owner-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "pull-family-name-member").await;
    let member_token = member["access_token"].as_str().unwrap();

    let (rename_status, rename_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/name",
        Some(owner_token),
        json!({"family_name": "  小星星一家  "}),
    )
    .await;
    assert_eq!(rename_status, StatusCode::OK, "{rename_body}");
    let (value_status, value_pull) =
        get_json(&rig.app, "/v1/pull?cursor=0", Some(member_token)).await;
    assert_eq!(value_status, StatusCode::OK, "{value_pull}");
    assert_eq!(value_pull["entities"], json!([]));
    assert_eq!(value_pull["family_name"], "小星星一家");

    let restarted = rig.restart("generation-b");
    let (restart_value_status, restart_value_pull) = get_json(
        &restarted,
        "/v1/pull?cursor=0&generation=generation-b",
        Some(member_token),
    )
    .await;
    assert_eq!(restart_value_status, StatusCode::OK, "{restart_value_pull}");
    assert_eq!(restart_value_pull["entities"], json!([]));
    assert_eq!(restart_value_pull["family_name"], "小星星一家");

    let (clear_status, clear_body) = json_request(
        &restarted,
        Method::POST,
        "/v1/family/name",
        Some(owner_token),
        json!({"family_name": null}),
    )
    .await;
    assert_eq!(
        clear_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{clear_body}"
    );
    let (null_status, null_pull) = get_json(
        &restarted,
        "/v1/pull?cursor=0&generation=generation-b",
        Some(member_token),
    )
    .await;
    assert_eq!(null_status, StatusCode::OK, "{null_pull}");
    assert_eq!(null_pull["entities"], json!([]));
    assert_eq!(null_pull["family_name"], "小星星一家", "{null_pull}");

    let restarted_again = rig.restart("generation-c");
    let (restart_null_status, restart_null_pull) = get_json(
        &restarted_again,
        "/v1/pull?cursor=0&generation=generation-c",
        Some(member_token),
    )
    .await;
    assert_eq!(restart_null_status, StatusCode::OK, "{restart_null_pull}");
    assert_eq!(restart_null_pull["entities"], json!([]));
    assert_eq!(restart_null_pull["family_name"], "小星星一家");
}

#[tokio::test]
async fn family_rename_advances_watermark_and_next_pull_delivers_new_name() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "rename-watermark-owner",
        "rename-watermark-owner-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    // A second device represents any foreground device's ordinary pull round.
    let member = approve_new_member(&rig.app, owner_token, "rename-watermark-member").await;
    let member_token = member["access_token"].as_str().unwrap();

    // Baseline: the member device is caught up at the pre-rename tip.
    let (baseline_status, baseline) =
        get_json(&rig.app, "/v1/pull?cursor=0", Some(member_token)).await;
    assert_eq!(baseline_status, StatusCode::OK, "{baseline}");
    let pre_rename_tip = baseline["cursor"].as_i64().unwrap();

    // Owner renames the family; the rename itself creates no entity and does
    // not change the directory digest.
    let (rename_status, rename_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/name",
        Some(owner_token),
        json!({"family_name": "新名字家庭"}),
    )
    .await;
    assert_eq!(rename_status, StatusCode::OK, "{rename_body}");

    // The next ordinary pull round observes the advanced cursor (an empty
    // page — the rename carries no entity) and delivers the new shared name
    // through the same pull `family_name` channel every page already uses.
    let (pull_status, page) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor={pre_rename_tip}"),
        Some(member_token),
    )
    .await;
    assert_eq!(pull_status, StatusCode::OK, "{page}");
    assert_eq!(page["entities"], json!([]));
    let post_rename_tip = page["cursor"].as_i64().unwrap();
    assert!(
        post_rename_tip > pre_rename_tip,
        "rename must advance the pull cursor: {page}"
    );
    assert_eq!(page["family_name"], "新名字家庭");

    // The advanced cursor is stable: a follow-up round sees no further change.
    let (follow_status, follow) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor={post_rename_tip}"),
        Some(member_token),
    )
    .await;
    assert_eq!(follow_status, StatusCode::OK, "{follow}");
    assert_eq!(follow["entities"], json!([]));
    assert_eq!(follow["cursor"], post_rename_tip);
    assert_eq!(follow["family_name"], "新名字家庭");
}

#[tokio::test]
async fn family_members_project_canonical_memberships_without_role_promotion() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "duplicate-owner-device",
        "duplicate-owner-request-00000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let duplicate_membership_id = Uuid::new_v4().to_string();
    connection
        .execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, display_name, display_name_key
            ) VALUES (?1, ?2, 'member', '成员', '成员')
            ",
            rusqlite::params![duplicate_membership_id, family_id],
        )
        .unwrap();
    // A display collision with the Owner's device label remains a member row;
    // only the authenticated device session carries canonical role authority.
    let role_collision_membership_id = Uuid::new_v4().to_string();
    connection
        .execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, display_name, display_name_key
            ) VALUES (?1, ?2, 'member', '伪装管理员', '伪装管理员')
            ",
            rusqlite::params![role_collision_membership_id, family_id],
        )
        .unwrap();
    drop(connection);

    let (_, members) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let rows = members["members"].as_array().unwrap();
    assert_eq!(rows.len(), 3);
    // Structural projection without hard-coding minted membership_ids.
    let projection: Vec<_> = rows
        .iter()
        .map(|row| {
            json!({
                "display_name": row["display_name"],
                "role": row["role"],
                "is_self": row["is_self"],
            })
        })
        .collect();
    assert_eq!(
        projection,
        vec![
            json!({
                "display_name":"妈妈",
                "role":"owner",
                "is_self":true,
            }),
            json!({
                "display_name":"伪装管理员",
                "role":"member",
                "is_self":false,
            }),
            json!({
                "display_name":"成员",
                "role":"member",
                "is_self":false,
            }),
        ]
    );
    // Self uses the create-time canonical membership id.
    assert_eq!(rows[0]["membership_id"], owner["membership_id"]);
    let member_rows: Vec<_> = rows
        .iter()
        .filter(|row| row["display_name"] == "成员")
        .collect();
    assert_eq!(member_rows.len(), 1);
    assert!(member_rows[0]["membership_id"].as_str().unwrap().len() >= 32);
    for row in rows {
        assert!(row["membership_id"].as_str().unwrap().len() >= 32);
        // display_name, role, is_self, membership_id, devices, optional last_sync_at
        let field_count = row.as_object().unwrap().len();
        assert!(
            field_count == 5 || field_count == 6,
            "unexpected member fields: {}",
            row
        );
        assert!(!row.as_object().unwrap().contains_key("device_id"));
        assert!(row["devices"].is_array());
    }
    let serialized = members.to_string();
    assert!(!serialized.contains("member-token"));
    assert!(!serialized.contains("owner-device-collision"));
    assert!(!serialized.contains("same-device"));
}
#[tokio::test]
async fn pull_pages_large_bootstrap_without_skipping_the_remaining_entities() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "paged-pull-owner-request-00000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let (rename_status, rename_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/name",
        Some(token),
        json!({"family_name": "分页家庭"}),
    )
    .await;
    assert_eq!(rename_status, StatusCode::OK, "{rename_body}");
    let entities = (0..201)
        .map(|index| {
            json!({
                "type":"baby",
                "client_uuid":Uuid::new_v4(),
                "updated_at":index + 1,
                "payload":baby_payload(&format!("宝宝{index}"), None)
            })
        })
        .collect::<Vec<_>>();
    for (index, entity) in entities.into_iter().enumerate() {
        if index > 0 && index % 60 == 0 {
            rig.now.fetch_add(61, Ordering::SeqCst);
        }
        let (publish_status, publish_body) = publish_root_bundle(&rig.app, token, entity).await;
        assert_eq!(publish_status, StatusCode::OK, "{publish_body}");
    }

    // The rename advances the family watermark once before any entity exists,
    // so the 201 seeded entities occupy revs 2..=202 and every cursor below
    // shifts by exactly one page tip.
    let (first_status, first) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(first_status, StatusCode::OK);
    assert_eq!(first["entities"].as_array().unwrap().len(), 200);
    assert_eq!(first["cursor"], 201);
    assert_eq!(first["has_more"], true);
    assert_eq!(first["family_name"], "分页家庭");

    let (second_status, second) = get_json(&rig.app, "/v1/pull?cursor=201", Some(token)).await;
    assert_eq!(second_status, StatusCode::OK);
    assert_eq!(second["entities"].as_array().unwrap().len(), 1);
    assert_eq!(second["cursor"], 202);
    assert_eq!(second["has_more"], false);
    assert_eq!(second["family_name"], first["family_name"]);
}

#[tokio::test]
async fn paged_full_resync_includes_dependencies_that_have_a_later_revision() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "paged-reference-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_ids = (0..201)
        .map(|_| Uuid::new_v4().to_string())
        .collect::<Vec<_>>();
    let (push_status, push_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("初始宝宝", None), None),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");
    for (index, record_id) in record_ids.iter().enumerate() {
        if index > 0 && index % 60 == 0 {
            rig.now.fetch_add(61, Ordering::SeqCst);
        }
        // Space timestamps past the 30-minute suspected-duplicate window so
        // this test observes plain paged record revisions; identical stamps
        // would chain every seed into one auto-align component and re-bump
        // member revisions on each commit (0.4.8 near-neighbor alignment).
        let mut payload = record_payload(&baby_id);
        payload["timestamp"] = json!(index as i64 * (30 * 60 * 1000 + 1));
        seed_record_with_id(&rig.app, token, record_id, (index + 1) as i64, payload).await;
    }
    let (update_status, update_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "baby",
            &baby_id,
            10_000,
            baby_payload("更新宝宝", None),
            None,
        ),
    )
    .await;
    assert_eq!(update_status, StatusCode::OK, "{update_body}");

    let mut cursor = 0;
    let mut pulled_records = std::collections::BTreeSet::new();
    let mut page_count = 0;
    loop {
        let (status, page) =
            get_json(&rig.app, &format!("/v1/pull?cursor={cursor}"), Some(token)).await;
        assert_eq!(status, StatusCode::OK);
        let entities = page["entities"].as_array().unwrap();
        assert!(entities.iter().any(|entity| entity["type"] == "baby"));
        pulled_records.extend(
            entities
                .iter()
                .filter(|entity| entity["type"] == "record")
                .map(|entity| entity["client_uuid"].as_str().unwrap().to_owned()),
        );
        page_count += 1;
        cursor = page["cursor"].as_i64().unwrap();
        if page["has_more"] == false {
            break;
        }
    }

    assert!(page_count >= 2);
    assert_eq!(pulled_records.len(), record_ids.len());
    assert_eq!(cursor, 203);
}
#[tokio::test]
async fn full_pull_includes_deleted_baby_dependency_before_retained_record() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "deleted-baby-owner-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();

    let (seeded, body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(seeded, StatusCode::OK, "{body}");
    seed_record_with_id(&rig.app, token, &record_id, 1, record_payload(&baby_id)).await;

    let (deleted, body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 2, baby_payload("年年", None), Some(2)),
    )
    .await;
    assert_eq!(deleted, StatusCode::OK, "{body}");

    let (status, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK, "{pulled}");
    assert_eq!(pulled["has_more"], false);
    let entities = pulled["entities"].as_array().unwrap();
    // Full resync co-emits the soft-deleted baby dependency before its retained record.
    assert_eq!(entities.len(), 2);
    assert_eq!(entities[0]["type"], "baby");
    assert_eq!(entities[0]["client_uuid"], baby_id);
    assert!(entities[0]["deleted_at"].is_number());
    assert_eq!(entities[1]["type"], "record");
    assert_eq!(entities[1]["client_uuid"], record_id);
    assert!(entities[1]["deleted_at"].is_null());
}

#[tokio::test]
async fn incremental_fulfillment_candidate_pull_reemits_live_plan_record_and_media_dependencies() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "fc-cogroup-owner-device",
        "fc-cogroup-owner-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let plan_media_id = Uuid::new_v4().to_string();
    let record_media_id = Uuid::new_v4().to_string();

    let (plan_status, plan_body) = publish_bundle_with_media(
        &rig.app,
        token,
        entity_wire(
            "care_plan",
            &plan_id,
            2,
            care_plan_payload(&baby_id, "formula"),
            None,
        ),
        vec![(
            entity_wire(
                "media",
                &plan_media_id,
                2,
                json!({
                    "kind": "log",
                    "care_plan_client_uuid": plan_id,
                    "mime": "image/jpeg",
                    "byte_size": 3,
                }),
                None,
            ),
            b"img".to_vec(),
        )],
    )
    .await;
    assert_eq!(plan_status, StatusCode::OK, "{plan_body}");
    let (record_status, record_body) = publish_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 3, record_payload(&baby_id), None),
        vec![(
            entity_wire(
                "media",
                &record_media_id,
                3,
                log_media_payload(&record_id),
                None,
            ),
            b"img".to_vec(),
        )],
    )
    .await;
    assert_eq!(record_status, StatusCode::OK, "{record_body}");

    let (_, before_candidate) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let cursor = before_candidate["cursor"].as_i64().unwrap();
    let candidate_id = Uuid::new_v4().to_string();
    let (candidate_status, candidate_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "fulfillment_candidate",
            &candidate_id,
            4,
            json!({
                "care_plan_client_uuid": plan_id,
                "record_client_uuid": record_id,
                "actual_timestamp": 1_700_000_000_000i64,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(candidate_status, StatusCode::OK, "{candidate_body}");

    let (pull_status, pulled) =
        get_json(&rig.app, &format!("/v1/pull?cursor={cursor}"), Some(token)).await;
    assert_eq!(pull_status, StatusCode::OK, "{pulled}");
    let entities = pulled["entities"].as_array().unwrap();
    let keys = entities
        .iter()
        .map(|entity| {
            (
                entity["type"].as_str().unwrap().to_owned(),
                entity["client_uuid"].as_str().unwrap().to_owned(),
            )
        })
        .collect::<BTreeSet<_>>();
    assert!(keys.contains(&("care_plan".to_owned(), plan_id)));
    assert!(keys.contains(&("record".to_owned(), record_id)));
    assert!(keys.contains(&("media".to_owned(), plan_media_id)));
    assert!(keys.contains(&("media".to_owned(), record_media_id)));
    assert!(keys.contains(&("fulfillment_candidate".to_owned(), candidate_id)));
}
#[tokio::test]
async fn media_metadata_rejects_zero_declared_byte_size() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "zero-media-owner-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let mut media_payload = log_media_payload(&record_id);
    media_payload["byte_size"] = json!(0);

    let (baby_push, baby_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
    let (status, _) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
        vec![entity_wire("media", &media_id, 2, media_payload, None)],
    )
    .await;

    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
}
#[tokio::test]
async fn family_delete_requires_owner_name_and_root_and_persists_terminal_reason() {
    let root = "family-delete-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let (_, owner) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "family-delete-confirm-request-000001",
            "display_name": "妈妈",
            "device_name": "妈妈手机",
            "family_name": "乐乐一家",
        }),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    let owner_access = owner["access_token"].as_str().unwrap();
    let owner_refresh = owner["refresh_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_access, "爸爸手机").await;
    let member_access = member["access_token"].as_str().unwrap();
    let member_refresh = member["refresh_token"].as_str().unwrap();
    let confirmation = json!({"family_name": "  乐乐一家  "});

    let (member_status, _) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/delete",
        Some(member_access),
        confirmation.clone(),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(member_status, StatusCode::FORBIDDEN);

    for headers in [
        Vec::<(&str, &str)>::new(),
        vec![("x-lezi-bootstrap-secret", "wrong-family-delete-root")],
    ] {
        let (status, _) = json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/family/delete",
            Some(owner_access),
            confirmation.clone(),
            &headers,
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(
            get_json(&rig.app, "/v1/family/members", Some(owner_access))
                .await
                .0,
            StatusCode::OK,
        );
    }

    let (name_mismatch, mismatch_body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/delete",
        Some(owner_access),
        json!({"family_name": "另一个家庭"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(name_mismatch, StatusCode::CONFLICT, "{mismatch_body}");
    assert_eq!(
        get_json(&rig.app, "/v1/family/members", Some(owner_access))
            .await
            .0,
        StatusCode::OK,
    );

    let (deleted, deleted_body) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/delete",
        Some(owner_access),
        confirmation,
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(deleted, StatusCode::OK, "{deleted_body}");

    let restarted = rig.restart_with_config("generation-family-deleted", |config| {
        config.bootstrap_secret = Some(root.to_owned());
    });
    for access in [owner_access, member_access] {
        let (status, body) = get_json(&restarted, "/v1/family/members", Some(access)).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["code"], "family_deleted");
    }
    for refresh in [owner_refresh, member_refresh] {
        let (status, body) = json_request(
            &restarted,
            Method::POST,
            "/v1/session/refresh",
            None,
            json!({"refresh_token": refresh}),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(body["code"], "family_deleted");
    }

    let database = fs::read(rig.directory.path().join("lezi.db")).unwrap();
    assert!(!database
        .windows(root.len())
        .any(|bytes| bytes == root.as_bytes()));
}

#[tokio::test]
async fn owner_delete_cleans_family_media_and_allows_replacement() {
    let root = "owner-delete-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let owner = create_family_with_root(
        &rig.app,
        "owner-device",
        "delete-owner-request-0000000000001",
        root,
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    {
        let (baby_push, baby_body) = publish_root_bundle(
            &rig.app,
            token,
            entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
        )
        .await;
        assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
        seed_record_then_log_media(&rig.app, token, &record_id, &media_id, &baby_id, 1).await;
    }
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/media/{media_id}"),
            Some(token),
            Body::from("log"),
            None,
        )
        .await
        .status(),
        StatusCode::UNPROCESSABLE_ENTITY
    );
    let (deleted, _) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/delete",
        Some(token),
        json!({"family_name": "测试家庭"}),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(deleted, StatusCode::OK);
    assert!(!rig.directory.path().join("media").join(family_id).exists());
    assert_eq!(
        get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await.0,
        StatusCode::UNAUTHORIZED
    );
    let replacement = create_family_with_root(
        &rig.app,
        "replacement-owner",
        "replacement-owner-request-00000001",
        root,
    )
    .await;
    assert_ne!(replacement["family_id"], family_id);
}

#[tokio::test]
async fn family_delete_keeps_media_when_database_deletion_fails() {
    let root = "failure-delete-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let owner = create_family_with_root(
        &rig.app,
        "delete-failure-owner",
        "delete-failure-owner-request-00001",
        root,
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    {
        let (baby_push, baby_body) = publish_root_bundle(
            &rig.app,
            token,
            entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
        )
        .await;
        assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
        seed_record_then_log_media(&rig.app, token, &record_id, &media_id, &baby_id, 1).await;
    }
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/media/{media_id}"),
            Some(token),
            Body::from("log"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::UNPROCESSABLE_ENTITY
    );

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute_batch(
            "
            CREATE TRIGGER fail_family_delete
            BEFORE DELETE ON families
            BEGIN
                SELECT RAISE(FAIL, 'injected family deletion failure');
            END;
            ",
        )
        .unwrap();
    drop(connection);

    let failed = request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/delete",
        Some(token),
        Body::from(r#"{"family_name":"测试家庭"}"#),
        Some("application/json"),
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(failed.status(), StatusCode::INTERNAL_SERVER_ERROR);
    assert!(rig.directory.path().join("media").join(family_id).is_dir());
    assert_eq!(
        get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await.0,
        StatusCode::OK
    );
    let media_response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(media_response.status(), StatusCode::OK);
    assert_eq!(
        media_response
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes(),
        "log"
    );

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute("DROP TRIGGER fail_family_delete", [])
        .unwrap();
    drop(connection);
    let restarted = rig.restart_with_config("generation-b", |config| {
        config.bootstrap_secret = Some(root.to_owned());
    });
    assert_eq!(
        json_request_with_headers(
            &restarted,
            Method::POST,
            "/v1/family/delete",
            Some(token),
            json!({"family_name": "测试家庭"}),
            &[("x-lezi-bootstrap-secret", root)],
        )
        .await
        .0,
        StatusCode::OK
    );
    assert!(!rig.directory.path().join("media").join(family_id).exists());
}

#[tokio::test]
async fn restart_collects_only_uuid_orphan_family_media_directories() {
    let root = "orphan-delete-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let deleted = create_family_with_root(
        &rig.app,
        "orphan-cleanup-owner",
        "orphan-cleanup-owner-request-000001",
        root,
    )
    .await;
    let deleted_token = deleted["access_token"].as_str().unwrap();
    let deleted_family_id = deleted["family_id"].as_str().unwrap();
    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/family/delete",
            Some(deleted_token),
            json!({"family_name": "测试家庭"}),
            &[("x-lezi-bootstrap-secret", root)],
        )
        .await
        .0,
        StatusCode::OK
    );

    // Model a crash or remove_dir_all failure after the database deletion:
    // the UUID family directory remains, but no family row references it.
    let media_root = rig.directory.path().join("media");
    let orphan = media_root.join(deleted_family_id);
    fs::create_dir_all(&orphan).unwrap();
    fs::write(orphan.join("orphan-bytes"), b"orphan").unwrap();

    let active = create_family_with_root(
        &rig.app,
        "active-cleanup-owner",
        "active-cleanup-owner-request-000001",
        root,
    )
    .await;
    let active_dir = media_root.join(active["family_id"].as_str().unwrap());
    fs::create_dir_all(&active_dir).unwrap();
    fs::write(active_dir.join("active-marker"), b"active").unwrap();
    let operational = media_root.join(".operator-owned");
    fs::create_dir_all(&operational).unwrap();
    fs::write(operational.join("keep"), b"keep").unwrap();

    let _restarted = rig.restart_with_config("generation-b", |config| {
        config.bootstrap_secret = Some(root.to_owned());
    });

    assert!(
        !orphan.exists(),
        "restart did not collect orphan family media"
    );
    assert_eq!(
        fs::read(active_dir.join("active-marker")).unwrap(),
        b"active",
        "restart removed media for a live family"
    );
    assert_eq!(
        fs::read(operational.join("keep")).unwrap(),
        b"keep",
        "startup GC must ignore non-UUID operator entries"
    );
}

#[tokio::test]
async fn bootstrap_secret_gates_family_create_when_configured() {
    let secret = "production-bootstrap-secret";
    let rig = Rig::with_config(|config| {
        config.bootstrap_secret = Some(secret.to_owned());
        config.create_rate_limit = RateLimitConfig {
            max_attempts: 1,
            window_seconds: 60,
        };
    });
    let body = json!({
        "create_request_id": "bootstrap-owner-request-000000000001",
        "display_name": "妈妈",
        "device_name": "owner-device",
        "family_name": "测试家庭",
    });
    let (missing, detail) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        body.clone(),
    )
    .await;
    assert_eq!(missing, StatusCode::UNAUTHORIZED, "{detail}");
    let (wrong, _) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        body.clone(),
        &[("x-lezi-bootstrap-secret", "wrong-bootstrap-secret!!")],
    )
    .await;
    assert_eq!(wrong, StatusCode::TOO_MANY_REQUESTS);
    let (blocked_correct, _) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        body.clone(),
        &[("x-lezi-bootstrap-secret", secret)],
    )
    .await;
    assert_eq!(blocked_correct, StatusCode::TOO_MANY_REQUESTS);
    rig.now.fetch_add(61, Ordering::SeqCst);
    let (created, family) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        body.clone(),
        &[("x-lezi-bootstrap-secret", secret)],
    )
    .await;
    assert_eq!(created, StatusCode::CREATED, "{family}");
    assert_eq!(family["role"], "owner");

    let (limited, _) = json_request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "bootstrap-owner-request-000000000002",
            "display_name": "妈妈",
            "device_name": "owner-device",
            "family_name": "测试家庭",
        }),
        &[("x-lezi-bootstrap-secret", secret)],
    )
    .await;
    assert_eq!(limited, StatusCode::CONFLICT);
}

#[tokio::test]
async fn root_password_admission_is_shared_and_cannot_be_bypassed_by_valid_passwords() {
    let root = "production-root-password";
    let verifications = Arc::new(AtomicUsize::new(0));
    let rig = Rig::with_config(|config| {
        config.bootstrap_secret = Some(root.to_owned());
        let calls = verifications.clone();
        config.root_password_verification_hook = Some(Arc::new(move || {
            calls.fetch_add(1, Ordering::SeqCst);
        }));
        config.create_rate_limit = RateLimitConfig {
            max_attempts: 3,
            window_seconds: 60,
        };
    });
    let owner = create_family_with_root(
        &rig.app,
        "owner-device",
        "root-rate-owner-request-0000000001",
        root,
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let login = json!({
        "login_request_id": "root-rate-login-request-0000000001",
        "device_name": "second owner device",
    });

    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/owner/login",
            None,
            login.clone(),
            &[("x-lezi-bootstrap-secret", "wrong-root-password")],
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED,
    );
    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/owner/takeover",
            None,
            login.clone(),
            &[("x-lezi-bootstrap-secret", "wrong-root-password")],
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED,
    );
    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/family/delete",
            Some(owner_token),
            json!({"confirmed_family_name": "测试家庭"}),
            &[("x-lezi-bootstrap-secret", "wrong-root-password")],
        )
        .await
        .0,
        StatusCode::TOO_MANY_REQUESTS,
    );
    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/owner/login",
            None,
            login.clone(),
            &[("x-lezi-bootstrap-secret", root)],
        )
        .await
        .0,
        StatusCode::TOO_MANY_REQUESTS,
        "valid passwords must not bypass exhausted admission",
    );
    assert_eq!(
        verifications.load(Ordering::SeqCst),
        3,
        "exhausted requests must not verify either password"
    );
    rig.now.fetch_add(61, Ordering::SeqCst);
    assert_eq!(
        json_request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/owner/login",
            None,
            login,
            &[("x-lezi-bootstrap-secret", root)]
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(verifications.load(Ordering::SeqCst), 4);
}

#[tokio::test]
async fn disaster_restore_start_and_commit_share_root_password_admission() {
    let root = "synthetic-restore-admission-password";
    let verifications = Arc::new(AtomicUsize::new(0));
    let rig = Rig::with_config(|config| {
        config.bootstrap_secret = Some(root.to_owned());
        let calls = verifications.clone();
        config.root_password_verification_hook = Some(Arc::new(move || {
            calls.fetch_add(1, Ordering::SeqCst);
        }));
        config.create_rate_limit = RateLimitConfig {
            max_attempts: 3,
            window_seconds: 60,
        };
    });
    let family_id = Uuid::new_v4().to_string();
    let start_uri = "/v1/disaster-restore/batches";
    let start_body = json!({
        "request_id": "restore-admission-start-request-0001",
        "family_id": family_id,
        "family_name": "恢复测试家庭",
        "owner_display_name": "测试管理员",
        "device_name": "restore-admission-device",
    });
    // Empty-server create, owner login, and restore start consume one shared window.
    for (uri, request_body) in [
        (
            "/v1/family/create",
            json!({
                "create_request_id": "restore-admission-create-request-001",
                "display_name": "测试管理员",
                "device_name": "create-admission-device",
                "family_name": "测试家庭",
            }),
        ),
        (
            "/v1/owner/login",
            json!({
                "login_request_id": "restore-admission-login-request-0001",
                "device_name": "login-admission-device",
            }),
        ),
        (start_uri, start_body.clone()),
    ] {
        let (status, body) = json_request_with_headers(
            &rig.app,
            Method::POST,
            uri,
            None,
            request_body,
            &[("x-lezi-bootstrap-secret", "wrong-root-password")],
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "{uri}: {body}");
    }
    for password in [root, "wrong-root-password"] {
        let (status, body) = json_request_with_headers(
            &rig.app,
            Method::POST,
            start_uri,
            None,
            start_body.clone(),
            &[("x-lezi-bootstrap-secret", password)],
        )
        .await;
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{body}");
    }
    assert_eq!(verifications.load(Ordering::SeqCst), 3);
    rig.now.fetch_add(61, Ordering::SeqCst);
    let (status, started) = json_request_with_headers(
        &rig.app,
        Method::POST,
        start_uri,
        None,
        start_body,
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{started}");
    assert_eq!(verifications.load(Ordering::SeqCst), 4);
    let batch_id = started["batch_id"].as_str().unwrap();
    let recovery = started["recovery_token"].as_str().unwrap();
    let commit_uri = format!("{start_uri}/{batch_id}/commit");
    let commit_body = json!({"request_id": "restore-admission-commit-request-0001"});
    let (status, manifest) = json_request(
        &rig.app,
        Method::PUT,
        &format!("{start_uri}/{batch_id}/manifest"),
        Some(recovery),
        json!({
            "request_id": "restore-admission-manifest-request-01",
            "entities": [{
                "type": "baby",
                "client_uuid": Uuid::new_v4(),
                "updated_at": 1000,
                "payload": baby_payload("测试宝宝", None),
            }],
            "media": [],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{manifest}");
    // Start used the first slot; takeover and authenticated commit use the other two.
    for (uri, token, request_body) in [
        (
            "/v1/owner/takeover",
            None,
            json!({
                "login_request_id": "restore-admission-takeover-request-01",
                "device_name": "takeover-admission-device",
            }),
        ),
        (commit_uri.as_str(), Some(recovery), commit_body.clone()),
    ] {
        let (status, body) = json_request_with_headers(
            &rig.app,
            Method::POST,
            uri,
            token,
            request_body,
            &[("x-lezi-bootstrap-secret", "wrong-root-password")],
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "{uri}: {body}");
    }
    for password in [root, "wrong-root-password"] {
        let (status, body) = json_request_with_headers(
            &rig.app,
            Method::POST,
            &commit_uri,
            Some(recovery),
            commit_body.clone(),
            &[("x-lezi-bootstrap-secret", password)],
        )
        .await;
        assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{body}");
    }
    assert_eq!(verifications.load(Ordering::SeqCst), 6);
    assert_eq!(
        get_json(&rig.app, "/v1/setup-status", None).await.1["family_state"],
        "empty"
    );
    rig.now.fetch_add(61, Ordering::SeqCst);
    let (status, committed) = json_request_with_headers(
        &rig.app,
        Method::POST,
        &commit_uri,
        Some(recovery),
        commit_body,
        &[("x-lezi-bootstrap-secret", root)],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(verifications.load(Ordering::SeqCst), 7);
    assert_eq!(committed["family_id"], family_id);
}

#[tokio::test]
async fn create_limit_is_scoped_without_losing_global_protection() {
    let rig = Rig::with_config(|config| {
        config.create_rate_limit = RateLimitConfig {
            max_attempts: 2,
            window_seconds: 60,
        };
    });
    create_family(
        &rig.app,
        "owner-device",
        "rate-limit-owner-request-0000000001",
    )
    .await;

    let second_body = json!({
        "create_request_id": "rate-limit-owner-request-0000000002",
        "display_name": "妈妈",
        "device_name": "other-device",
        "family_name": "第二家庭",
    });
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/create",
            None,
            second_body.clone(),
        )
        .await
        .0,
        StatusCode::CONFLICT,
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/create",
            None,
            second_body,
        )
        .await
        .0,
        StatusCode::TOO_MANY_REQUESTS,
    );
}
#[tokio::test]
async fn ordinary_media_upload_retry_is_retired_even_when_bytes_exist() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "retry-media-owner-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();

    let (baby_push, baby_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
    seed_record_then_log_media(&rig.app, token, &record_id, &media_id, &baby_id, 1).await;
    let (_, after_seed) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let seed_cursor = after_seed["cursor"].as_i64().unwrap();

    // Even an existing target cannot turn the retired PUT into a publication path.
    let media_directory = rig.directory.path().join("media").join(family_id);
    fs::create_dir_all(&media_directory).unwrap();
    fs::write(media_directory.join(&media_id), b"old").unwrap();

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::from("log"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(uploaded.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let (status, ready) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor={seed_cursor}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(ready["cursor"], seed_cursor);
    assert!(ready["entities"].as_array().unwrap().is_empty());
}
#[test]
fn protocol_cutover_requires_a_verified_forced_update_channel() {
    let missing = TempDir::new().unwrap();
    let mut missing_config = ServerConfig::new(missing.path());
    missing_config.require_protocol_cutover_release = true;
    assert!(
        build_app(missing_config).is_err(),
        "0.5.0 production startup accepted a missing forced-update channel",
    );

    let valid = TempDir::new().unwrap();
    let apk_bytes = b"verified-0.4.0-release-channel";
    fs::write(valid.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        valid.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 35,
            "version_name": "0.5.5",
            "min_supported_version_code": 35,
            "sha256": hex::encode(Sha256::digest(apk_bytes)),
        })
        .to_string(),
    )
    .unwrap();
    let mut valid_config = ServerConfig::new(valid.path());
    valid_config.require_protocol_cutover_release = true;
    assert!(build_app(valid_config).is_ok());

    // Floor 16 is not a valid 0.5.0 production cutover channel.
    let too_low = TempDir::new().unwrap();
    let low_apk = b"stale-0.3.9-floor-must-fail";
    fs::write(too_low.path().join("app-release.apk"), low_apk).unwrap();
    fs::write(
        too_low.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 16,
            "version_name": "0.3.9",
            "min_supported_version_code": 16,
            "sha256": hex::encode(Sha256::digest(low_apk)),
        })
        .to_string(),
    )
    .unwrap();
    let mut low_config = ServerConfig::new(too_low.path());
    low_config.require_protocol_cutover_release = true;
    assert!(
        build_app(low_config).is_err(),
        "0.5.0 production must reject min_supported/version_code below 35"
    );
}
fn entity_wire(
    entity_type: &str,
    client_uuid: &str,
    updated_at: i64,
    payload: Value,
    deleted_at: Option<i64>,
) -> Value {
    let mut value = json!({
        "type": entity_type,
        "client_uuid": client_uuid,
        "updated_at": updated_at,
        "payload": payload,
    });
    if let Some(deleted_at) = deleted_at {
        value["deleted_at"] = json!(deleted_at);
    }
    value
}

fn care_plan_payload(baby_id: &str, plan_type: &str) -> Value {
    let payload_json = match plan_type {
        "formula" => json!({"amount_ml": 120}),
        "pee" => json!({"pee_amount": 2}),
        "custom" => json!({"title": "抚触"}),
        "bath" => json!({}),
        _ => panic!("missing API care-plan fixture for {plan_type}"),
    };
    json!({
        "baby_client_uuid": baby_id,
        "type": plan_type,
        "custom_item_client_uuid": null,
        "scheduled_at": 1_700_000_000_000i64,
        "scheduled_zone_id": "Asia/Shanghai",
        "status": "pending",
        "payload_json": payload_json,
        "schema_version": 2,
        "note": null,
        "created_by_membership_id": "spoofed-membership",
        "fulfilled_record_client_uuid": null,
        "fulfilled_at": null,
        "source_record_client_uuid": null,
    })
}

async fn seed_baby(app: &Router, token: &str) -> String {
    let baby_id = Uuid::new_v4().to_string();
    let (status, body) = publish_root_bundle(
        app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    baby_id
}

async fn seed_record_with_id(
    app: &Router,
    token: &str,
    record_id: &str,
    updated_at: i64,
    payload: Value,
) {
    let (status, body) = publish_root_bundle(
        app,
        token,
        entity_wire("record", record_id, updated_at, payload, None),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
}

/// Publish a record and its log-media bytes as one atomic package.
async fn seed_record_then_log_media(
    app: &Router,
    token: &str,
    record_id: &str,
    media_id: &str,
    baby_id: &str,
    updated_at: i64,
) {
    let (status, body) = publish_bundle_with_media(
        app,
        token,
        entity_wire(
            "record",
            record_id,
            updated_at,
            record_payload(baby_id),
            None,
        ),
        vec![(
            entity_wire(
                "media",
                media_id,
                updated_at + 1,
                log_media_payload(record_id),
                None,
            ),
            b"log".to_vec(),
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
}

async fn publish_root_bundle(app: &Router, token: &str, root: Value) -> (StatusCode, Value) {
    if root["type"] == "fulfillment_candidate" {
        let bundle_id = Uuid::new_v4().to_string();
        let (status, body) = json_request(
            app,
            Method::POST,
            "/v1/bundles",
            Some(token),
            json!({"bundle_id": bundle_id, "root": root, "media": []}),
        )
        .await;
        if status != StatusCode::OK {
            return (status, body);
        }
        return json_request(
            app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(token),
            json!({}),
        )
        .await;
    }
    publish_causal_fixture(app, token, root, Vec::new()).await
}

async fn publish_causal_fixture(
    app: &Router,
    token: &str,
    root: Value,
    media: Vec<(Value, Vec<u8>)>,
) -> (StatusCode, Value) {
    let entity_type = root["type"].as_str().expect("fixture root type");
    let client_uuid = Uuid::parse_str(
        root["client_uuid"]
            .as_str()
            .expect("fixture root client_uuid"),
    )
    .expect("fixture root UUID");
    let mut causal_root = root["payload"].clone();
    causal_root["updated_at"] = root["updated_at"].clone();
    let deleted = root.get("deleted_at").is_some_and(Value::is_number);
    let fixture_key = (
        token.to_owned(),
        entity_type.to_owned(),
        client_uuid.to_string(),
    );
    let base_version = causal_fixture_versions()
        .lock()
        .unwrap()
        .get(&fixture_key)
        .cloned();
    let mut causal_media = Vec::with_capacity(media.len());
    for (entity, bytes) in &media {
        let media_uuid = Uuid::parse_str(
            entity["client_uuid"]
                .as_str()
                .expect("fixture media client_uuid"),
        )
        .expect("fixture media UUID");
        let (status, body) = put_causal_media_bytes(app, token, media_uuid, bytes).await;
        if status != StatusCode::OK {
            return (status, body);
        }
        let payload = &entity["payload"];
        let role = match entity_type {
            "baby" => "avatar",
            "record" => "log",
            "care_plan" => "plan",
            other => panic!("unsupported fixture media root: {other}"),
        };
        causal_media.push(json!({
            "media_uuid": media_uuid,
            "role": role,
            "sha256": hex::encode(Sha256::digest(bytes)),
            "byte_size": bytes.len(),
            "mime": payload.get("mime").cloned().unwrap_or_else(|| json!("image/jpeg")),
            "width": payload.get("width").cloned().unwrap_or_else(|| json!(1)),
            "height": payload.get("height").cloned().unwrap_or_else(|| json!(1)),
        }));
    }
    let response = causal_commit_units(
        app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            base_version.as_deref(),
            entity_type,
            client_uuid,
            causal_root,
            causal_media,
            deleted,
        )],
    )
    .await;
    if let Some(version_id) = response
        .1
        .pointer("/results/0/stable/version_id")
        .and_then(Value::as_str)
    {
        causal_fixture_versions()
            .lock()
            .unwrap()
            .insert(fixture_key, version_id.to_owned());
    }
    response
}

type FixtureVersions = HashMap<(String, String, String), String>;

fn causal_fixture_versions() -> &'static Mutex<FixtureVersions> {
    static VERSIONS: OnceLock<Mutex<FixtureVersions>> = OnceLock::new();
    VERSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

async fn stage_bundle_with_media(
    app: &Router,
    token: &str,
    root: Value,
    media: Vec<Value>,
) -> (StatusCode, Value) {
    json_request(
        app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": Uuid::new_v4().to_string(),
            "root": root,
            "media": media,
        }),
    )
    .await
}

async fn publish_bundle_with_media(
    app: &Router,
    token: &str,
    root: Value,
    media: Vec<(Value, Vec<u8>)>,
) -> (StatusCode, Value) {
    publish_causal_fixture(app, token, root, media).await
}

#[tokio::test]
async fn ordinary_entity_and_media_publication_routes_are_retired_fail_closed() {
    let rig = Rig::new();

    let (push_status, push_body) = raw_json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        None,
        json!({"this": "must never be parsed or applied"}),
    )
    .await;
    assert_eq!(push_status, StatusCode::UNPROCESSABLE_ENTITY, "{push_body}");
    assert_eq!(
        push_body["detail"],
        "ordinary push is retired; publish an atomic bundle"
    );

    let media_id = Uuid::new_v4();
    let response = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{media_id}"),
        None,
        Body::from("must not be stored"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let value: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(
        value["detail"],
        "ordinary media upload is retired; upload media through an atomic bundle"
    );
}

#[tokio::test]
async fn ordinary_reconcile_routes_are_absent() {
    let rig = Rig::new();

    for path in [
        "/v1/reconcile",
        "/v1/causal/reconcile",
        "/v1/bundles/00000000-0000-4000-8000-000000000001/media/00000000-0000-4000-8000-000000000002",
    ] {
        let response = request(
            &rig.app,
            Method::POST,
            path,
            None,
            Body::from("{}"),
            Some("application/json"),
        )
        .await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{path}");
    }
}
#[tokio::test]
async fn mutable_roots_use_causal_commit_while_fulfillment_remains_atomic() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "all-bundle-roots-owner",
        "all-bundle-roots-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    let baby_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
        )
        .await
        .0,
        StatusCode::OK,
    );

    let custom_item_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire(
                "custom_item",
                &custom_item_id,
                2,
                json!({"name": "抚触", "icon_slot": 2}),
                None,
            ),
        )
        .await
        .0,
        StatusCode::OK,
    );

    let record_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire("record", &record_id, 3, record_payload(&baby_id), None),
        )
        .await
        .0,
        StatusCode::OK,
    );

    let plan_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire(
                "care_plan",
                &plan_id,
                4,
                care_plan_payload(&baby_id, "bath"),
                None,
            ),
        )
        .await
        .0,
        StatusCode::OK,
    );

    let candidate_id = Uuid::new_v4().to_string();
    let (candidate_status, candidate_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "fulfillment_candidate",
            &candidate_id,
            5,
            json!({
                "care_plan_client_uuid": plan_id,
                "record_client_uuid": record_id,
                "actual_timestamp": 1_700_000_000_100i64,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(candidate_status, StatusCode::OK, "{candidate_body}");
}
#[tokio::test]
async fn health_advertises_atomic_bundle_capability() {
    let rig = Rig::new();
    let (status, body) = get_json(&rig.app, "/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["ok"], true);
    assert!(body["capabilities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|c| c == "atomic_bundle"));
}

#[tokio::test]
async fn health_advertises_record_membership_author_capability() {
    let rig = Rig::new();
    let (status, body) = get_json(&rig.app, "/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["capabilities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|capability| capability == "record_membership_author"));
}
#[tokio::test]
async fn second_device_on_the_same_membership_may_manage_its_record_and_log_media() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "record-acl-second-device-owner",
        "record-acl-second-device-request-001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "record-acl-first-device").await;
    let first_token = member["access_token"].as_str().unwrap();
    let (grant_status, grant) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants",
        Some(owner_token),
        json!({"membership_id": member["membership_id"]}),
    )
    .await;
    assert_eq!(grant_status, StatusCode::CREATED, "{grant}");
    let (claim_status, second) = json_request(
        &rig.app,
        Method::POST,
        "/v1/member/login-grants/claim",
        None,
        json!({
            "grant": grant["grant"],
            "device_name": "record-acl-second-device",
        }),
    )
    .await;
    assert_eq!(claim_status, StatusCode::OK, "{second}");
    assert_eq!(second["membership_id"], member["membership_id"]);
    let second_token = second["access_token"].as_str().unwrap();

    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let (created, body) = publish_root_bundle(
        &rig.app,
        first_token,
        entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
    )
    .await;
    assert_eq!(created, StatusCode::OK, "{body}");
    let base_version = body["results"][0]["stable"]["version_id"]
        .as_str()
        .expect("created record stable version");

    let media_id = Uuid::new_v4();
    let bytes = b"log";
    let (prepare_status, prepare_body) =
        put_causal_media_bytes(&rig.app, second_token, media_id, bytes).await;
    assert_eq!(prepare_status, StatusCode::OK, "{prepare_body}");
    let mut root = record_payload(&baby_id);
    root["updated_at"] = json!(3);
    let (commit_status, commit_body) = causal_commit_units(
        &rig.app,
        second_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(base_version),
            "record",
            Uuid::parse_str(&record_id).unwrap(),
            root,
            vec![causal_media_item(
                media_id,
                "log",
                &hex::encode(Sha256::digest(bytes)),
                bytes.len(),
            )],
            false,
        )],
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");
    assert_eq!(commit_body["results"][0]["status"], "accepted");
}
#[tokio::test]
async fn care_plan_rejected_on_ordinary_push() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "ordinary-plan-device",
        "ordinary-plan-request-000000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4().to_string();
    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "device_id": "ordinary-plan-device",
            "entities": [entity_wire(
                "care_plan",
                &plan_id,
                1,
                care_plan_payload(&baby_id, "formula"),
                None
            )]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
}

#[tokio::test]
async fn ordinary_push_rejects_record_roots() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "record-ordinary-reject-owner",
        "record-ordinary-reject-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let (push_status, push_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire("record", &record_id, 2, record_payload(&baby_id), None)]
        }),
    )
    .await;
    assert_eq!(push_status, StatusCode::UNPROCESSABLE_ENTITY, "{push_body}");
    assert!(
        push_body["detail"]
            .as_str()
            .unwrap_or_default()
            .contains("atomic bundle"),
        "{push_body}"
    );
}

#[tokio::test]
async fn fulfillment_candidate_stamps_submitter_and_rejects_bad_refs() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "fulfill-owner",
        "fulfill-cand-request-000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "fulfill-member").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    let (plan_status, plan_body) = publish_root_bundle(
        &rig.app,
        owner_token,
        entity_wire(
            "care_plan",
            &plan_id,
            1,
            care_plan_payload(&baby_id, "bath"),
            None,
        ),
    )
    .await;
    assert_eq!(plan_status, StatusCode::OK, "{plan_body}");

    let record_id = Uuid::new_v4().to_string();
    seed_record_with_id(
        &rig.app,
        member_token,
        &record_id,
        2,
        record_payload(&baby_id),
    )
    .await;

    let cand_id = Uuid::new_v4().to_string();
    let (ok, _) = publish_root_bundle(
        &rig.app,
        member_token,
        entity_wire(
            "fulfillment_candidate",
            &cand_id,
            3,
            json!({
                "care_plan_client_uuid": plan_id,
                "record_client_uuid": record_id,
                "actual_timestamp": 1_700_000_000_100i64,
                "submitter_membership_id": "forged",
                "submitter_role": "owner",
                "confirmed_at": 1,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(ok, StatusCode::OK);
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let cand = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == cand_id)
        .expect("fulfillment_candidate visible");
    assert_eq!(
        cand["payload"]["submitter_membership_id"],
        member["membership_id"]
    );
    assert_eq!(cand["payload"]["submitter_role"], "member");
    assert_ne!(cand["payload"]["confirmed_at"], 1);

    // Bad plan ref is rejected atomically.
    let bad_id = Uuid::new_v4().to_string();
    let (bad, body) = publish_root_bundle(
        &rig.app,
        member_token,
        entity_wire(
            "fulfillment_candidate",
            &bad_id,
            4,
            json!({
                "care_plan_client_uuid": Uuid::new_v4().to_string(),
                "record_client_uuid": record_id,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(bad, StatusCode::CONFLICT, "{body}");
}

#[tokio::test]
async fn fulfillment_candidate_freeze_is_idempotent_and_arrival_order_independent() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "fulfill-freeze-owner",
        "fulfill-freeze-request-00000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "fulfill-freeze-member").await;
    let member_token = member["access_token"].as_str().unwrap();
    let peer = approve_new_member(&rig.app, owner_token, "fulfill-freeze-peer").await;
    let peer_token = peer["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    let (plan_status, plan_body) = publish_root_bundle(
        &rig.app,
        owner_token,
        entity_wire(
            "care_plan",
            &plan_id,
            1,
            care_plan_payload(&baby_id, "bath"),
            None,
        ),
    )
    .await;
    assert_eq!(plan_status, StatusCode::OK, "{plan_body}");

    let member_record = Uuid::new_v4().to_string();
    let owner_record = Uuid::new_v4().to_string();
    seed_record_with_id(
        &rig.app,
        member_token,
        &member_record,
        2,
        record_payload(&baby_id),
    )
    .await;
    seed_record_with_id(
        &rig.app,
        owner_token,
        &owner_record,
        3,
        record_payload(&baby_id),
    )
    .await;

    let member_cand = Uuid::new_v4().to_string();
    let owner_cand = Uuid::new_v4().to_string();
    // Member first, owner second — both accepted; stamps frozen per first accept.
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            member_token,
            entity_wire(
                "fulfillment_candidate",
                &member_cand,
                10,
                json!({
                    "care_plan_client_uuid": plan_id,
                    "record_client_uuid": member_record,
                    "submitter_membership_id": "forged-member",
                    "submitter_role": "owner",
                    "confirmed_at": 1,
                }),
                None
            ),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            owner_token,
            entity_wire(
                "fulfillment_candidate",
                &owner_cand,
                11,
                json!({
                    "care_plan_client_uuid": plan_id,
                    "record_client_uuid": owner_record,
                    "submitter_membership_id": "forged-owner",
                    "submitter_role": "member",
                    "confirmed_at": 2,
                }),
                None
            ),
        )
        .await
        .0,
        StatusCode::OK
    );

    let (_, pull1) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let entities1 = pull1["entities"].as_array().unwrap();
    let m1 = entities1
        .iter()
        .find(|e| e["client_uuid"] == member_cand)
        .expect("member candidate");
    let o1 = entities1
        .iter()
        .find(|e| e["client_uuid"] == owner_cand)
        .expect("owner candidate");
    assert_eq!(
        m1["payload"]["submitter_membership_id"],
        member["membership_id"]
    );
    assert_eq!(m1["payload"]["submitter_role"], "member");
    assert_ne!(m1["payload"]["confirmed_at"], 1);
    assert_eq!(
        o1["payload"]["submitter_membership_id"],
        owner["membership_id"]
    );
    assert_eq!(o1["payload"]["submitter_role"], "owner");
    assert_ne!(o1["payload"]["confirmed_at"], 2);
    let frozen_member_at = m1["payload"]["confirmed_at"].clone();
    let frozen_owner_at = o1["payload"]["confirmed_at"].clone();
    let member_rev = m1["rev"].as_i64().unwrap();
    let cursor_after_first = pull1["cursor"].as_i64().unwrap();

    // Exact evidence replay by original submitter, peer Member, and Owner — all
    // idempotent with unchanged rev/cursor/stamps/business fields.
    for (token, updated_at, forged_submitter) in [
        (member_token, 99i64, "replay-forged"),
        (peer_token, 110, "peer-forged"),
        (owner_token, 120, "owner-forged"),
    ] {
        assert_eq!(
            publish_root_bundle(
                &rig.app,
                token,
                entity_wire(
                    "fulfillment_candidate",
                    &member_cand,
                    updated_at,
                    json!({
                        "care_plan_client_uuid": plan_id,
                        "record_client_uuid": member_record,
                        "submitter_membership_id": forged_submitter,
                        "submitter_role": "owner",
                        "confirmed_at": 999_999,
                    }),
                    None
                ),
            )
            .await
            .0,
            StatusCode::OK
        );
        let (_, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
        assert_eq!(pulled["cursor"].as_i64().unwrap(), cursor_after_first);
        let candidate = pulled["entities"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["client_uuid"] == member_cand)
            .expect("member candidate after exact replay");
        assert_eq!(candidate["rev"].as_i64().unwrap(), member_rev);
        assert_eq!(
            candidate["payload"]["submitter_membership_id"],
            member["membership_id"]
        );
        assert_eq!(candidate["payload"]["submitter_role"], "member");
        assert_eq!(candidate["payload"]["confirmed_at"], frozen_member_at);
        assert_eq!(candidate["payload"]["record_client_uuid"], member_record);
        assert_eq!(candidate["payload"]["care_plan_client_uuid"], plan_id);
        assert_eq!(
            pulled["entities"]
                .as_array()
                .unwrap()
                .iter()
                .find(|e| e["client_uuid"] == owner_cand)
                .unwrap()["payload"]["confirmed_at"],
            frozen_owner_at
        );
    }
    let cursor_after_first_replays = cursor_after_first;

    // Attack: cannot leave original submitter stamps + rewritten business fields.
    let immutable_detail = "Fulfillment candidate evidence is immutable";
    for payload in [
        json!({
            "care_plan_client_uuid": plan_id,
            "record_client_uuid": owner_record,
            "actual_timestamp": 1_700_000_000_100i64,
            "submitter_membership_id": member["membership_id"],
            "submitter_role": "member",
            "confirmed_at": frozen_member_at,
        }),
        json!({
            "care_plan_client_uuid": Uuid::new_v4().to_string(),
            "record_client_uuid": member_record,
            "submitter_membership_id": member["membership_id"],
            "submitter_role": "member",
            "confirmed_at": frozen_member_at,
        }),
        json!({
            "care_plan_client_uuid": plan_id,
            "record_client_uuid": member_record,
            "actual_timestamp": 9_999_999_999i64,
            "submitter_membership_id": member["membership_id"],
            "submitter_role": "member",
            "confirmed_at": frozen_member_at,
        }),
    ] {
        let (status, body) = publish_root_bundle(
            &rig.app,
            owner_token,
            entity_wire("fulfillment_candidate", &member_cand, 200, payload, None),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert_eq!(body, json!({"detail": immutable_detail}));
    }
    let (_, pull_after_attack) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    assert_eq!(
        pull_after_attack["cursor"].as_i64().unwrap(),
        cursor_after_first_replays
    );
    let still = pull_after_attack["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == member_cand)
        .unwrap();
    assert_eq!(still["payload"]["record_client_uuid"], member_record);
    assert_eq!(
        still["payload"]["submitter_membership_id"],
        member["membership_id"]
    );
    assert_eq!(still["payload"]["confirmed_at"], frozen_member_at);

    // Tombstone keeps evidence; resurrection fails closed.
    let (tombstone_status, tombstone_body) = publish_root_bundle(
        &rig.app,
        owner_token,
        entity_wire(
            "fulfillment_candidate",
            &member_cand,
            210,
            json!({
                "care_plan_client_uuid": plan_id,
                "record_client_uuid": member_record,
            }),
            Some(210),
        ),
    )
    .await;
    assert_eq!(tombstone_status, StatusCode::OK, "{tombstone_body}");
    let (resurrect_status, resurrect_body) = publish_root_bundle(
        &rig.app,
        owner_token,
        entity_wire(
            "fulfillment_candidate",
            &member_cand,
            220,
            json!({
                "care_plan_client_uuid": plan_id,
                "record_client_uuid": member_record,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(resurrect_status, StatusCode::CONFLICT, "{resurrect_body}");
    assert_eq!(
        resurrect_body,
        json!({"detail": "Deleted fulfillment candidate cannot be resurrected"})
    );

    // Unknown plan/record refs stay family-scoped conflict (no cross-family leak).
    let foreign_cand = Uuid::new_v4().to_string();
    let (bad_ref_status, bad_ref_body) = publish_root_bundle(
        &rig.app,
        member_token,
        entity_wire(
            "fulfillment_candidate",
            &foreign_cand,
            300,
            json!({
                "care_plan_client_uuid": Uuid::new_v4().to_string(),
                "record_client_uuid": member_record,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(bad_ref_status, StatusCode::CONFLICT, "{bad_ref_body}");
    assert_eq!(
        bad_ref_body["detail"],
        "fulfillment_candidate care_plan_client_uuid does not exist"
    );

    // Same-family cross-baby plan/record pair is CONFLICT; cursor unchanged.
    let baby_b = seed_baby(&rig.app, owner_token).await;
    let plan_on_a = plan_id;
    let record_on_b = Uuid::new_v4().to_string();
    seed_record_with_id(
        &rig.app,
        owner_token,
        &record_on_b,
        50,
        record_payload(&baby_b),
    )
    .await;
    let (_, pull_before_cross) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let cursor_before_cross = pull_before_cross["cursor"].as_i64().unwrap();
    let cross_cand = Uuid::new_v4().to_string();
    let (cross_status, cross_body) = publish_root_bundle(
        &rig.app,
        member_token,
        entity_wire(
            "fulfillment_candidate",
            &cross_cand,
            60,
            json!({
                "care_plan_client_uuid": plan_on_a,
                "record_client_uuid": record_on_b,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(cross_status, StatusCode::CONFLICT, "{cross_body}");
    assert_eq!(
        cross_body["detail"],
        "fulfillment_candidate record baby does not match care_plan baby"
    );
    let (_, pull_after_cross) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    assert_eq!(
        pull_after_cross["cursor"].as_i64().unwrap(),
        cursor_before_cross
    );
}
#[tokio::test(flavor = "current_thread")]
async fn causal_protocol_smoke_create_pull_branch_and_resolve() {
    // Isolated in-process server: create → pull version_id → concurrent branch → resolve.
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "causal-smoke-owner",
        "causal-smoke-request-000000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();

    let (status, baby_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({
            "units": [{
                "mutation_id": Uuid::new_v4().to_string(),
                "base_version": null,
                "entity_type": "baby",
                "client_uuid": baby_id,
                "root": {
                    "nickname": "年年",
                    "sex": "female",
                    "birthday": "2025-01-02",
                    "avatar_media_uuid": null,
                    "updated_at": 10
                },
                "media": [],
                "deleted": false
            }]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{baby_body}");
    assert_eq!(baby_body["results"][0]["status"], "accepted");

    let record_mutation_id = Uuid::new_v4().to_string();
    let record_unit = json!({
        "mutation_id": record_mutation_id,
        "base_version": null,
        "entity_type": "record",
        "client_uuid": record_id,
        "root": {
            "baby_client_uuid": baby_id,
            "type": "formula",
            "custom_item_client_uuid": null,
            "timestamp": 100,
            "end_timestamp": null,
            "note": "a",
            "payload_json": {"amount_ml": 100},
            "schema_version": 2,
            "updated_at": 20
        },
        "media": [],
        "deleted": false
    });
    let (status, rec_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({
            "units": [record_unit.clone()]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{rec_body}");
    assert_eq!(rec_body["results"][0]["status"], "accepted");
    let v1 = rec_body["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    // Lost HTTP response retry: exact frozen no-media envelope replays the same
    // terminal version; mutation-id reuse with payload drift fails closed.
    let (status, replay) = json_request(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({"units": [record_unit.clone()]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["results"][0]["status"], "accepted");
    assert_eq!(replay["results"][0]["stable"]["version_id"], v1);
    assert_eq!(
        replay["results"][0]["request_hash"],
        rec_body["results"][0]["request_hash"]
    );

    let mut drift = record_unit;
    drift["root"]["note"] = json!("payload-drift");
    let (status, rejected) = json_request(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({"units": [drift]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "content_drift");

    let generation = created["generation"].as_str().unwrap_or("generation-a");
    let (status, pull) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor=0&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{pull}");
    let pulled_record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == record_id.to_string())
        .expect("record in pull");
    assert_eq!(pulled_record["version_id"], v1);

    // Side A advances note.
    let (status, left) = json_request(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({
            "units": [{
                "mutation_id": Uuid::new_v4().to_string(),
                "base_version": v1,
                "entity_type": "record",
                "client_uuid": record_id,
                "root": {
                    "baby_client_uuid": baby_id,
                    "type": "formula",
                    "custom_item_client_uuid": null,
                    "timestamp": 100,
                    "end_timestamp": null,
                    "note": "b",
                    "payload_json": {"amount_ml": 100},
                    "schema_version": 2,
                    "updated_at": 30
                },
                "media": [],
                "deleted": false
            }]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{left}");
    assert_eq!(left["results"][0]["status"], "accepted");
    let v2 = left["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    // Side B concurrent note → branched.
    let (status, right) = json_request(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({
            "units": [{
                "mutation_id": Uuid::new_v4().to_string(),
                "base_version": v1,
                "entity_type": "record",
                "client_uuid": record_id,
                "root": {
                    "baby_client_uuid": baby_id,
                    "type": "formula",
                    "custom_item_client_uuid": null,
                    "timestamp": 100,
                    "end_timestamp": null,
                    "note": "c",
                    "payload_json": {"amount_ml": 100},
                    "schema_version": 2,
                    "updated_at": 40
                },
                "media": [],
                "deleted": false
            }]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{right}");
    assert_eq!(right["results"][0]["status"], "branched");
    let conflict_id = right["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    assert_eq!(detail["contract"], "conflict_snapshot_v2");
    assert_eq!(detail["stable"]["version_id"], v2);
    assert!(detail.get("stable_root").is_none());
    assert!(detail.get("conflicting_paths").is_none());
    assert_eq!(
        detail
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect::<BTreeSet<_>>(),
        BTreeSet::from([
            "auto_merged",
            "branches",
            "client_uuid",
            "complete",
            "conflict_id",
            "conflicting",
            "continuation",
            "contract",
            "entity_type",
            "expires_at",
            "page_index",
            "snapshot_token",
            "stable",
        ]),
    );
    let version_keys = BTreeSet::from([
        "actor_id",
        "base_version",
        "deleted",
        "device_id",
        "media",
        "mutation_id",
        "received_at",
        "root",
        "version_id",
    ]);
    assert_eq!(
        detail["stable"]
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect::<BTreeSet<_>>(),
        version_keys,
    );
    assert!(detail["conflicting"]
        .as_array()
        .unwrap()
        .iter()
        .any(|item| item["path"] == "/note"));
    let note = detail["conflicting"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["path"] == "/note")
        .unwrap();
    assert!(note["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .all(|candidate| {
            candidate["choice_id"]
                .as_str()
                .is_some_and(|id| id.len() >= 16)
                && candidate["outcome"]["op"] == "set"
                && candidate["sources"].as_array().is_some_and(|sources| {
                    !sources.is_empty()
                        && sources.iter().all(|source| {
                            source["version_id"].is_string()
                                && source["mutation_id"].is_string()
                                && source["actor_id"].is_string()
                                && source["device_id"].is_string()
                                && source["received_at"].is_number()
                        })
                })
        }));

    let resolution_mutation_id = Uuid::new_v4().to_string();
    let resolve_body = json!({
        "snapshot_token": detail["snapshot_token"],
        "resolution_mutation_id": resolution_mutation_id.clone(),
        "choices": [conflict_set_choice(&detail, "/note", json!("c"))]
    });
    let valid_choice = conflict_set_choice(&detail, "/note", json!("c"));
    let malformed_id = Uuid::new_v4().to_string();
    let malformed_cases = [
        (
            json!({
                "expected_stable_version": v2,
                "expected_branch_versions": [],
                "resolved_root": {},
                "resolved_media": [],
                "resolution_mutation_id": malformed_id,
                "conflict_choices": {}
            }),
            "unknown_field",
        ),
        (
            json!({
                "snapshot_token": detail["snapshot_token"],
                "resolution_mutation_id": malformed_id,
            }),
            "missing_field",
        ),
        (
            json!({
                "snapshot_token": detail["snapshot_token"],
                "resolution_mutation_id": malformed_id,
                "choices": {},
            }),
            "wrong_type",
        ),
        (
            json!({
                "snapshot_token": detail["snapshot_token"],
                "resolution_mutation_id": malformed_id,
                "choices": [{"path": "/note", "choice_id": valid_choice["choice_id"], "value": "c"}],
            }),
            "unknown_field",
        ),
        (
            json!({
                "snapshot_token": detail["snapshot_token"],
                "resolution_mutation_id": malformed_id,
                "choices": [{"path": "/note"}],
            }),
            "missing_field",
        ),
        (
            json!({
                "snapshot_token": detail["snapshot_token"],
                "resolution_mutation_id": malformed_id,
                "choices": [{"path": 7, "choice_id": valid_choice["choice_id"]}],
            }),
            "wrong_type",
        ),
    ];
    for (malformed, expected_code) in malformed_cases {
        let (status, rejected) = json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/conflicts/{conflict_id}/resolve"),
            Some(token),
            malformed,
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{rejected}");
        assert_eq!(rejected["status"], "rejected");
        assert_eq!(rejected["resolution_mutation_id"], malformed_id);
        assert_eq!(
            rejected["error"],
            json!({"code": expected_code, "retryable": false})
        );
        assert_eq!(
            rejected
                .as_object()
                .unwrap()
                .keys()
                .map(String::as_str)
                .collect::<BTreeSet<_>>(),
            BTreeSet::from(["error", "resolution_mutation_id", "status"]),
        );
        let (detail_status, still_open) = get_json(
            &rig.app,
            &format!("/v1/conflicts/{conflict_id}"),
            Some(token),
        )
        .await;
        assert_eq!(detail_status, StatusCode::OK, "{still_open}");
        assert_eq!(still_open["conflict_id"], conflict_id);
    }
    for rejected_body in [
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": vec![valid_choice.clone(); 65],
        }),
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [{
                "path": format!("/{}", "x".repeat(1_024)),
                "choice_id": valid_choice["choice_id"],
            }],
        }),
    ] {
        let (status, rejected) = json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/conflicts/{conflict_id}/resolve"),
            Some(token),
            rejected_body,
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{rejected}");
        assert_eq!(rejected["error"]["code"], "non_canonical_value");
    }
    let (status, resolved) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(token),
        resolve_body.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{resolved}");
    assert_eq!(resolved["status"], "accepted");
    assert_eq!(resolved["replay"], false);
    assert_eq!(resolved["stable_root"]["note"], "c");

    let database_path = rig.directory.path().join("lezi.db");
    let connection = Connection::open(&database_path).unwrap();
    let valid_snapshot_receipt: String = connection
        .query_row(
            "SELECT receipt_json FROM mutation_receipts
              WHERE membership_id = '__conflict_snapshot_v2__' AND conflict_id = ?1",
            [&conflict_id],
            |row| row.get(0),
        )
        .unwrap();
    // This current-thread test observes the existing post-GC warning locally.
    // A retained row count alone cannot prove a failed detached sweep ran.
    #[derive(Default)]
    struct RetentionEventFields {
        message: String,
        error: String,
    }
    impl tracing::field::Visit for RetentionEventFields {
        fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
            match field.name() {
                "message" => self.message = format!("{value:?}"),
                "error" => self.error = format!("{value:?}"),
                _ => {}
            }
        }
    }
    struct RetentionFailures(tokio::sync::mpsc::UnboundedSender<String>);
    impl<S: tracing::Subscriber> tracing_subscriber::Layer<S> for RetentionFailures {
        fn on_event(
            &self,
            event: &tracing::Event<'_>,
            _context: tracing_subscriber::layer::Context<'_, S>,
        ) {
            if event.metadata().level() != &tracing::Level::WARN
                || event.metadata().target() != "lezi_sync::handlers::sync"
            {
                return;
            }
            let mut fields = RetentionEventFields::default();
            event.record(&mut fields);
            if fields.message == "bounded conflict retention sweep failed" {
                let _ = self.0.send(fields.error);
            }
        }
    }
    use tracing_subscriber::layer::SubscriberExt;
    let (failure_events, mut observed_failures) = tokio::sync::mpsc::unbounded_channel();
    // Keep a thread-local guard across awaits on this explicitly current-thread
    // runtime. Do not install a global subscriber or depend on blocking-pool logs.
    let _retention_trace = tracing::subscriber::set_default(
        tracing_subscriber::registry().with(RetentionFailures(failure_events)),
    );
    const RETENTION_OBSERVATION_BUDGET: Duration = Duration::from_secs(5);

    connection
        .execute(
            "UPDATE mutation_receipts SET receipt_json = '[]'
              WHERE membership_id = '__conflict_snapshot_v2__' AND conflict_id = ?1",
            [&conflict_id],
        )
        .unwrap();
    drop(connection);
    let resolution_time = rig.now.fetch_add(24 * 60 * 60, Ordering::SeqCst);
    let retention_time = resolution_time + 24 * 60 * 60;
    Connection::open(&database_path)
        .unwrap()
        .execute(
            "UPDATE device_sessions SET access_expires_at = ?1",
            [retention_time + 60],
        )
        .unwrap();
    let (status, replay) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(token),
        resolve_body.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["status"], "accepted");
    assert_eq!(replay["replay"], true);
    assert_eq!(replay["stable_version_id"], resolved["stable_version_id"]);
    // Any delayed sweep for this one-conflict Rig may observe the corruption;
    // the event proves one actually failed, not which replay scheduled it.
    let failed_sweep = tokio::time::timeout(RETENTION_OBSERVATION_BUDGET, observed_failures.recv())
        .await
        .expect("corrupt snapshot did not produce a completed failed retention sweep")
        .expect("retention failure observer closed before a sweep completed");
    assert!(
        failed_sweep.contains("code: Some(\"invalid_stored_payload\")"),
        "unexpected retention failure: {failed_sweep}",
    );
    let connection = Connection::open(&database_path).unwrap();
    let retained_after_gc_failure: (i64, i64) = connection
        .query_row(
            "SELECT
                (SELECT COUNT(*) FROM conflict_branches WHERE conflict_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts
                  WHERE membership_id = '__conflict_snapshot_v2__' AND conflict_id = ?1)",
            [&conflict_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(retained_after_gc_failure, (1, 1));
    connection
        .execute(
            "UPDATE mutation_receipts SET receipt_json = ?1
              WHERE membership_id = '__conflict_snapshot_v2__' AND conflict_id = ?2",
            (&valid_snapshot_receipt, &conflict_id),
        )
        .unwrap();
    drop(connection);
    let (status, retry_after_gc_failure) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(token),
        resolve_body.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{retry_after_gc_failure}");
    assert_eq!(retry_after_gc_failure["replay"], true);

    let compacted = tokio::time::timeout(RETENTION_OBSERVATION_BUDGET, async {
        loop {
            // All three values come from one read snapshot. Drop the connection
            // before yielding so the asynchronous sweep can acquire its writer.
            let observed: (i64, i64, i64) = {
                let connection = Connection::open(&database_path).unwrap();
                connection
                    .query_row(
                        "SELECT
                            (SELECT COUNT(*) FROM conflict_branches WHERE conflict_id = ?1),
                            (SELECT COUNT(*) FROM mutation_receipts
                              WHERE membership_id = '__conflict_snapshot_v2__' AND conflict_id = ?1),
                            (SELECT COUNT(*) FROM mutation_receipts
                              WHERE membership_id = '__conflict_retention_v2__' AND conflict_id = ?1
                                AND json_extract(receipt_json, '$.phase') = 'complete')",
                        [&conflict_id],
                        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                    )
                    .unwrap()
            };
            if observed == (0, 0, 1) {
                break (observed.0, observed.1);
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .expect("detached retention sweep did not commit complete compaction");
    assert_eq!(compacted, (0, 0));

    let restarted = rig.restart("generation-a");
    let (status, restarted_replay) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(token),
        resolve_body,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{restarted_replay}");
    assert_eq!(restarted_replay["replay"], true);
    assert_eq!(
        restarted_replay["stable_version_id"],
        resolved["stable_version_id"]
    );

    let (status, drift) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(token),
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": resolution_mutation_id,
            "choices": [conflict_set_choice(&detail, "/note", json!("b"))]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{drift}");
    assert_eq!(drift["error"]["code"], "content_drift");
    assert_eq!(
        drift
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect::<BTreeSet<_>>(),
        BTreeSet::from(["error", "resolution_mutation_id", "status"]),
    );
}

#[tokio::test]
async fn causal_commit_success_response_is_closed_contracted_and_marks_exact_replay() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "causal-commit-shape-owner",
        "causal-commit-shape-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "baby",
        Uuid::new_v4(),
        causal_baby_root("contracted", None, 100),
        vec![],
        false,
    );

    let (status, first) = causal_commit_units(&rig.app, token, vec![mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{first}");
    assert_eq!(
        first
            .as_object()
            .unwrap()
            .keys()
            .cloned()
            .collect::<BTreeSet<_>>(),
        BTreeSet::from(["generation".to_owned(), "results".to_owned()]),
    );
    let first_unit = first["results"][0].as_object().unwrap();
    assert_eq!(
        first_unit.keys().cloned().collect::<BTreeSet<_>>(),
        BTreeSet::from([
            "mutation_id".to_owned(),
            "replay".to_owned(),
            "request_hash".to_owned(),
            "stable".to_owned(),
            "status".to_owned(),
        ]),
    );
    assert_eq!(first_unit["status"], "accepted");
    assert_eq!(first_unit["replay"], false);
    assert_eq!(
        first_unit["stable"]
            .as_object()
            .unwrap()
            .keys()
            .cloned()
            .collect::<BTreeSet<_>>(),
        BTreeSet::from([
            "deleted".to_owned(),
            "deleted_at".to_owned(),
            "media".to_owned(),
            "root".to_owned(),
            "version_id".to_owned(),
        ]),
    );

    let (status, replay) = causal_commit_units(&rig.app, token, vec![mutation]).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(
        replay["results"][0]["status"],
        first["results"][0]["status"]
    );
    assert_eq!(
        replay["results"][0]["stable"],
        first["results"][0]["stable"]
    );
    assert_eq!(replay["results"][0]["replay"], true);
}

#[tokio::test]
async fn causal_commit_rejection_rolls_back_the_whole_batch_and_uses_terminal_envelope() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "causal-commit-atomic-owner",
        "causal-commit-atomic-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let accepted = causal_unit(
        Uuid::new_v4(),
        None,
        "baby",
        Uuid::new_v4(),
        causal_baby_root("must-roll-back", None, 100),
        vec![],
        false,
    );
    let invalid_base = Uuid::new_v4().to_string();
    let invalid = causal_unit(
        Uuid::new_v4(),
        Some(&invalid_base),
        "baby",
        Uuid::new_v4(),
        causal_baby_root("invalid-base", None, 101),
        vec![],
        false,
    );

    let (status, rejected) =
        causal_commit_units(&rig.app, token, vec![accepted.clone(), invalid.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(
        rejected
            .as_object()
            .unwrap()
            .keys()
            .cloned()
            .collect::<BTreeSet<_>>(),
        BTreeSet::from([
            "error".to_owned(),
            "mutation_id".to_owned(),
            "status".to_owned(),
        ]),
    );
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["mutation_id"], invalid["mutation_id"]);
    assert_eq!(
        rejected["error"],
        json!({"code": "invalid_domain", "retryable": false})
    );

    let (status, retried) = causal_commit_units(&rig.app, token, vec![accepted]).await;
    assert_eq!(status, StatusCode::OK, "{retried}");
    assert_eq!(retried["results"][0]["status"], "accepted");
    assert_eq!(retried["results"][0]["replay"], false);
}

#[tokio::test]
async fn causal_commit_maps_auth_generation_and_closed_request_failures_to_terminal_envelopes() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "causal-commit-terminal-owner",
        "causal-commit-terminal-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "baby",
        Uuid::new_v4(),
        causal_baby_root("terminal", None, 100),
        vec![],
        false,
    );
    let valid = json!({"generation": generation, "units": [mutation.clone()]});

    let cases = [
        (
            None,
            valid.clone(),
            StatusCode::UNAUTHORIZED,
            "unauthenticated",
        ),
        (
            Some(token),
            json!({"generation": generation, "units": [mutation.clone()], "future": true}),
            StatusCode::OK,
            "unknown_field",
        ),
        (
            Some(token),
            json!({"generation": generation}),
            StatusCode::OK,
            "missing_field",
        ),
        (
            Some(token),
            json!({"generation": generation, "units": "bad"}),
            StatusCode::OK,
            "wrong_type",
        ),
        (
            Some(token),
            json!({"generation": generation, "units": []}),
            StatusCode::OK,
            "invalid_domain",
        ),
        (
            Some(token),
            json!({"generation": "other-generation", "units": [mutation.clone()]}),
            StatusCode::CONFLICT,
            "capability_mismatch",
        ),
    ];
    for (token, body, expected_status, expected_code) in cases {
        let (status, response) =
            json_request(&rig.app, Method::POST, "/v1/causal/commit", token, body).await;
        assert_eq!(status, expected_status, "{response}");
        assert_eq!(response["status"], "rejected");
        assert_eq!(
            response["error"],
            json!({"code": expected_code, "retryable": false})
        );
    }

    let mutation_client_uuid = mutation["client_uuid"].as_str().unwrap();
    let root = mutation["root"].to_string();
    let duplicate_root = root.replacen('{', r#"{"nickname":"duplicate","#, 1);
    let nested_duplicate = mutation.to_string().replace(&root, &duplicate_root);
    let duplicate_requests = [
        format!(r#"{{"generation":"{generation}","units":[],"units":[]}}"#),
        format!(r#"{{"generation":"{generation}","units":[{nested_duplicate}]}}"#),
    ];
    for duplicate_request in duplicate_requests {
        let response = request_with_headers(
            &rig.app,
            Method::POST,
            "/v1/causal/commit",
            Some(token),
            Body::from(duplicate_request),
            Some("application/json"),
            &[],
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let body: Value =
            serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes())
                .unwrap();
        assert_eq!(body["status"], "rejected");
        assert_eq!(body["error"]["code"], "non_canonical_value");
    }
    let duplicate_writes: i64 = Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM entities WHERE client_uuid = ?1",
            [mutation_client_uuid],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(duplicate_writes, 0);

    let mut unknown_root = mutation;
    unknown_root["root"]["future"] = json!(true);
    let (status, response) = causal_commit_units(&rig.app, token, vec![unknown_root]).await;
    assert_eq!(status, StatusCode::OK, "{response}");
    assert_eq!(response["error"]["code"], "unknown_field");
}

#[tokio::test]
async fn causal_commit_keeps_authentication_store_failures_retryable() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "causal-commit-auth-store-owner",
        "causal-commit-auth-store-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .execute(
            "ALTER TABLE device_sessions RENAME TO broken_device_sessions",
            [],
        )
        .unwrap();

    let (status, body) = raw_json_request(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({"generation": owner["generation"], "units": []}),
    )
    .await;

    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{body}");
    assert_eq!(body, json!({"detail": "Internal server error"}));
}

#[tokio::test]
async fn causal_commit_stored_version_reload_failure_returns_invalid_stored_payload_code() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "stored-reload-failure-owner",
        "stored-reload-failure-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let created = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        record_id,
        causal_formula_root(baby_id, "stored-reload-base", 80, 20),
        vec![],
        false,
    );
    let (status, created) = causal_commit_units(&rig.app, token, vec![created]).await;
    assert_eq!(status, StatusCode::OK, "{created}");
    assert_eq!(created["results"][0]["status"], "accepted", "{created}");
    let stable_version = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .execute(
            "UPDATE entity_versions SET content_hash = ?1
             WHERE family_id = ?2 AND version_id = ?3",
            rusqlite::params!["0".repeat(64), family_id, stable_version],
        )
        .unwrap();

    let edit = causal_unit(
        Uuid::new_v4(),
        Some(&stable_version),
        "record",
        record_id,
        causal_formula_root(baby_id, "stored-reload-edit", 80, 30),
        vec![],
        false,
    );
    let (status, body) = causal_commit_units(&rig.app, token, vec![edit]).await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert_eq!(body["code"], "invalid_stored_payload");
    assert_ne!(body, json!({"detail": "Internal server error"}));

    let (health_status, _) = get_json(&rig.app, "/health", None).await;
    let (ready_status, _) = get_json(&rig.app, "/ready", None).await;
    assert_eq!(health_status, StatusCode::OK);
    assert_eq!(ready_status, StatusCode::OK);
}

// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// Ticket 09 — two joined clients on isolated real lezi-sync (causal cutover)
// Public seams: /v1/causal/commit, /v1/pull, /v1/conflicts/*, PUT causal media,
// verified min_supported floor from android-release-compatibility.json.
// ---------------------------------------------------------------------------

fn release_catalog() -> Value {
    serde_json::from_str(include_str!(
        "../../../config/android-release-compatibility.json"
    ))
    .expect("android-release-compatibility.json")
}

fn causal_formula_root_at(
    baby_id: Uuid,
    note: &str,
    amount_ml: i64,
    timestamp: i64,
    updated_at: i64,
) -> Value {
    json!({
        "baby_client_uuid": baby_id,
        "type": "formula",
        "custom_item_client_uuid": null,
        "timestamp": timestamp,
        "end_timestamp": null,
        "note": note,
        "payload_json": {"amount_ml": amount_ml},
        "schema_version": 2,
        "updated_at": updated_at
    })
}

fn causal_formula_root(baby_id: Uuid, note: &str, amount_ml: i64, updated_at: i64) -> Value {
    causal_formula_root_at(baby_id, note, amount_ml, 1_700_000_100, updated_at)
}

fn causal_baby_root(nickname: &str, avatar_media_uuid: Option<Uuid>, updated_at: i64) -> Value {
    json!({
        "nickname": nickname,
        "sex": "female",
        "birthday": "2025-01-02",
        "avatar_media_uuid": avatar_media_uuid,
        "updated_at": updated_at
    })
}

fn causal_sleep_root(baby_id: Uuid, start: i64, updated_at: i64) -> Value {
    json!({
        "baby_client_uuid": baby_id,
        "type": "sleep",
        "custom_item_client_uuid": null,
        "timestamp": start,
        "note": null,
        "payload_json": {"anomaly_flag": false, "is_nap": false},
        "schema_version": 2,
        "updated_at": updated_at,
        "effective_wake_observation_client_uuid": null
    })
}

fn causal_media_item(media_uuid: Uuid, role: &str, sha256: &str, byte_size: usize) -> Value {
    json!({
        "media_uuid": media_uuid,
        "role": role,
        "sha256": sha256,
        "byte_size": byte_size,
        "mime": "image/jpeg",
        "width": 1,
        "height": 1
    })
}

#[derive(Debug, PartialEq, Eq)]
struct CausalReceiptWriteState {
    revision: i64,
    staging_status: Option<String>,
    version_rows: i64,
    terminal_rows: i64,
    record_rows: i64,
    media_rows: i64,
    publication_rows: i64,
}

fn causal_receipt_write_state(
    database_path: &Path,
    write_family_id: &str,
    receipt_family_id: &str,
    mutation_id: Uuid,
    record_id: Uuid,
    media_id: Uuid,
) -> CausalReceiptWriteState {
    Connection::open(database_path)
        .unwrap()
        .query_row(
            "SELECT
                (SELECT rev FROM family_meta WHERE family_id = ?1),
                (SELECT status FROM causal_media_staging
                  WHERE family_id = ?2 AND media_uuid = ?5),
                (SELECT COUNT(*) FROM entity_versions
                  WHERE family_id = ?1 AND mutation_id = ?3),
                (SELECT COUNT(*) FROM mutation_receipts
                  WHERE family_id = ?1 AND mutation_id = ?3),
                (SELECT COUNT(*) FROM entities
                  WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?4),
                (SELECT COUNT(*) FROM entities
                  WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?5),
                (SELECT COUNT(*) FROM media_publications
                  WHERE family_id = ?1 AND media_uuid = ?5)",
            rusqlite::params![
                write_family_id,
                receipt_family_id,
                mutation_id.to_string(),
                record_id.to_string(),
                media_id.to_string(),
            ],
            |row| {
                Ok(CausalReceiptWriteState {
                    revision: row.get(0)?,
                    staging_status: row.get(1)?,
                    version_rows: row.get(2)?,
                    terminal_rows: row.get(3)?,
                    record_rows: row.get(4)?,
                    media_rows: row.get(5)?,
                    publication_rows: row.get(6)?,
                })
            },
        )
        .unwrap()
}

fn conflict_set_choice(detail: &Value, path: &str, expected: Value) -> Value {
    let choice_id = detail["conflicting"]
        .as_array()
        .and_then(|paths| paths.iter().find(|item| item["path"] == path))
        .and_then(|item| item["candidates"].as_array())
        .and_then(|candidates| {
            candidates.iter().find(|candidate| {
                candidate["outcome"]["op"] == "set" && candidate["outcome"]["value"] == expected
            })
        })
        .and_then(|candidate| candidate["choice_id"].as_str())
        .unwrap_or_else(|| panic!("missing {path} conflict choice"));
    json!({"path": path, "choice_id": choice_id})
}

fn causal_unit(
    mutation_id: Uuid,
    base: Option<&str>,
    entity_type: &str,
    client_uuid: Uuid,
    root: Value,
    media: Vec<Value>,
    deleted: bool,
) -> Value {
    json!({
        "mutation_id": mutation_id,
        "base_version": base,
        "entity_type": entity_type,
        "client_uuid": client_uuid,
        "root": root,
        "media": media,
        "deleted": deleted
    })
}

async fn causal_commit_units(app: &Router, token: &str, units: Vec<Value>) -> (StatusCode, Value) {
    json_request(
        app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({ "units": units }),
    )
    .await
}

async fn put_causal_media_bytes(
    app: &Router,
    token: &str,
    media_uuid: Uuid,
    bytes: &[u8],
) -> (StatusCode, Value) {
    let response = put_causal_media_raw(app, token, media_uuid, bytes).await;
    let status = response.status();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let value: Value = serde_json::from_slice(&body).unwrap_or(json!({}));
    (status, value)
}

async fn put_causal_media_raw(
    app: &Router,
    token: &str,
    media_uuid: Uuid,
    bytes: &[u8],
) -> axum::response::Response {
    let sha = hex::encode(Sha256::digest(bytes));
    request_with_headers(
        app,
        Method::PUT,
        &format!("/v1/causal/media/{media_uuid}"),
        Some(token),
        Body::from(bytes.to_vec()),
        Some("application/octet-stream"),
        &[("x-lezi-media-sha256", sha.as_str())],
    )
    .await
}

async fn put_causal_media_empty(
    app: &Router,
    token: &str,
    media_uuid: Uuid,
    sha256: &str,
) -> (StatusCode, Value) {
    let response = request_with_headers(
        app,
        Method::PUT,
        &format!("/v1/causal/media/{media_uuid}"),
        Some(token),
        Body::empty(),
        Some("application/octet-stream"),
        &[("x-lezi-media-sha256", sha256), ("content-length", "0")],
    )
    .await;
    let status = response.status();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    let value: Value = serde_json::from_slice(&body).unwrap_or(json!({}));
    (status, value)
}

fn spawn_causal_media_put(
    app: &Router,
    token: &str,
    media_uuid: Uuid,
) -> tokio::task::JoinHandle<axum::response::Response> {
    let app = app.clone();
    let token = token.to_owned();
    let sha = hex::encode(Sha256::digest(b"xy"));
    tokio::spawn(async move {
        request_with_headers(
            &app,
            Method::PUT,
            &format!("/v1/causal/media/{media_uuid}"),
            Some(&token),
            Body::from(b"xy".to_vec()),
            Some("application/octet-stream"),
            &[("x-lezi-media-sha256", sha.as_str())],
        )
        .await
    })
}

async fn seed_causal_baby(app: &Router, token: &str) -> Uuid {
    let baby_id = Uuid::new_v4();
    let (status, body) = causal_commit_units(
        app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "baby",
            baby_id,
            json!({
                "nickname": "年年",
                "sex": "female",
                "birthday": "2025-01-02",
                "avatar_media_uuid": null,
                "updated_at": 10
            }),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["results"][0]["status"], "accepted", "{body}");
    baby_id
}

async fn commit_causal_record(
    app: &Router,
    token: &str,
    baby_id: Uuid,
    record_id: Uuid,
    base: Option<&str>,
    note: &str,
) -> (StatusCode, Value, Value) {
    let mutation = causal_unit(
        Uuid::new_v4(),
        base,
        "record",
        record_id,
        causal_formula_root(baby_id, note, 100, 20),
        vec![],
        false,
    );
    let (status, body) = causal_commit_units(app, token, vec![mutation.clone()]).await;
    (status, body, mutation)
}

#[tokio::test]
async fn provider_roots_commit_before_referenced_record_and_replay_exactly() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "provider-commit-first-owner",
        "provider-commit-first-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4();
    let custom_item_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let baby_mutation_id = Uuid::new_v4();
    let custom_mutation_id = Uuid::new_v4();
    let record_mutation_id = Uuid::new_v4();
    let baby = causal_unit(
        baby_mutation_id,
        None,
        "baby",
        baby_id,
        json!({
            "nickname": "年年",
            "sex": "female",
            "birthday": "2025-01-02",
            "birth_weight_grams": null,
            "avatar_media_uuid": null,
            "updated_at": 10,
        }),
        vec![],
        false,
    );
    let custom_item = causal_unit(
        custom_mutation_id,
        None,
        "custom_item",
        custom_item_id,
        json!({
            "name": "抚触",
            "icon_slot": 2,
            "updated_at": 11,
        }),
        vec![],
        false,
    );
    let record = causal_unit(
        record_mutation_id,
        None,
        "record",
        record_id,
        json!({
            "baby_client_uuid": baby_id,
            "type": "custom",
            "custom_item_client_uuid": custom_item_id,
            "timestamp": 1_700_000_100,
            "end_timestamp": null,
            "note": null,
            "payload_json": {"title": "抚触", "detail": "十分钟", "icon_slot": 2},
            "schema_version": 2,
            "updated_at": 12,
        }),
        vec![],
        false,
    );
    let frozen = vec![baby.clone(), custom_item.clone(), record.clone()];

    let (status, committed) = causal_commit_units(&rig.app, token, frozen.clone()).await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(
        committed["results"]
            .as_array()
            .unwrap()
            .iter()
            .map(|result| result["mutation_id"].as_str().unwrap().to_owned())
            .collect::<Vec<_>>(),
        vec![
            baby_mutation_id.to_string(),
            custom_mutation_id.to_string(),
            record_mutation_id.to_string(),
        ],
    );
    assert!(committed["results"]
        .as_array()
        .unwrap()
        .iter()
        .all(|result| result["status"] == "accepted"));
    assert_eq!(
        committed["results"][2]["stable"]["root"]["baby_client_uuid"],
        baby_id.to_string(),
    );
    assert_eq!(
        committed["results"][2]["stable"]["root"]["custom_item_client_uuid"],
        custom_item_id.to_string(),
    );

    let (status, replayed) = causal_commit_units(&rig.app, token, frozen).await;
    assert_eq!(status, StatusCode::OK, "{replayed}");
    for index in 0..3 {
        assert_eq!(
            replayed["results"][index]["stable"]["version_id"],
            committed["results"][index]["stable"]["version_id"],
        );
        assert_eq!(
            replayed["results"][index]["request_hash"],
            committed["results"][index]["request_hash"],
        );
    }

    let mut baby_drift = baby;
    baby_drift["root"]["nickname"] = json!("漂移宝宝");
    let mut custom_drift = custom_item;
    custom_drift["root"]["name"] = json!("漂移项目");
    let (status, rejected) =
        causal_commit_units(&rig.app, token, vec![baby_drift, custom_drift]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "content_drift");
}

#[tokio::test]
async fn no_media_care_plan_commits_after_fulfilled_record_replays_and_tombstones() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "care-plan-commit-first-owner",
        "care-plan-commit-first-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let plan_id = Uuid::new_v4();
    let baby = causal_unit(
        Uuid::new_v4(),
        None,
        "baby",
        baby_id,
        json!({
            "nickname": "年年",
            "sex": "female",
            "birthday": "2025-01-02",
            "birth_weight_grams": null,
            "avatar_media_uuid": null,
            "updated_at": 10,
        }),
        vec![],
        false,
    );
    let record = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        record_id,
        causal_formula_root(baby_id, "按计划完成", 100, 20),
        vec![],
        false,
    );
    let plan_mutation_id = Uuid::new_v4();
    let mut plan_root = care_plan_payload(&baby_id.to_string(), "formula");
    plan_root["status"] = json!("completed");
    plan_root["fulfilled_record_client_uuid"] = json!(record_id);
    plan_root["fulfilled_at"] = json!(1_700_000_100_i64);
    plan_root["updated_at"] = json!(30);
    let plan = causal_unit(
        plan_mutation_id,
        None,
        "care_plan",
        plan_id,
        plan_root,
        vec![],
        false,
    );

    let frozen = vec![baby, record, plan.clone()];
    let (status, committed) = causal_commit_units(&rig.app, token, frozen.clone()).await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(
        committed["results"]
            .as_array()
            .unwrap()
            .iter()
            .map(|result| result["status"].as_str().unwrap())
            .collect::<Vec<_>>(),
        vec!["accepted", "accepted", "accepted"],
        "{committed}",
    );
    assert_eq!(
        committed["results"][2]["stable"]["root"]["fulfilled_record_client_uuid"],
        record_id.to_string(),
    );
    let stable_plan = committed["results"][2]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, replayed) = causal_commit_units(&rig.app, token, frozen).await;
    assert_eq!(status, StatusCode::OK, "{replayed}");
    assert_eq!(
        replayed["results"][2]["stable"]["version_id"],
        committed["results"][2]["stable"]["version_id"],
    );
    assert_eq!(
        replayed["results"][2]["request_hash"],
        committed["results"][2]["request_hash"],
    );

    let mut drift = plan.clone();
    drift["root"]["note"] = json!("漂移计划");
    let (status, rejected) = causal_commit_units(&rig.app, token, vec![drift]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "content_drift");

    let mut tombstone_root = plan["root"].clone();
    tombstone_root["updated_at"] = json!(40);
    let tombstone = causal_unit(
        Uuid::new_v4(),
        Some(&stable_plan),
        "care_plan",
        plan_id,
        tombstone_root,
        vec![],
        true,
    );
    let (status, deleted) = causal_commit_units(&rig.app, token, vec![tombstone]).await;
    assert_eq!(status, StatusCode::OK, "{deleted}");
    assert_eq!(deleted["results"][0]["status"], "accepted", "{deleted}");
    let generation = owner["generation"].as_str().unwrap();
    let (status, pull) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor=0&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{pull}");
    let deleted_plan = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| {
            entity["type"] == "care_plan" && entity["client_uuid"] == plan_id.to_string()
        })
        .expect("care_plan tombstone in pull");
    assert!(deleted_plan["deleted_at"].is_number());
}

#[tokio::test]
async fn care_plan_three_attachments_publish_only_after_all_receipts_and_replay_exactly() {
    let rig = Rig::with_config(|config| config.max_media_bytes = 64 * 1024);
    let owner = create_family(
        &rig.app,
        "care-plan-media-owner",
        "care-plan-media-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4();
    let mutation_id = Uuid::new_v4();
    let media = [
        (Uuid::new_v4(), b"plan-photo-one".as_slice()),
        (Uuid::new_v4(), b"plan-photo-two-longer".as_slice()),
        (Uuid::new_v4(), b"plan-photo-three-exact".as_slice()),
    ];
    for (media_id, bytes) in media.iter().take(2) {
        let (status, prepared) = put_causal_media_bytes(&rig.app, token, *media_id, bytes).await;
        assert_eq!(status, StatusCode::OK, "{prepared}");
    }
    let mut plan_root = care_plan_payload(&baby_id.to_string(), "formula");
    plan_root["updated_at"] = json!(30);
    let mutation = causal_unit(
        mutation_id,
        None,
        "care_plan",
        plan_id,
        plan_root,
        media
            .iter()
            .map(|(media_id, bytes)| {
                causal_media_item(
                    *media_id,
                    "plan",
                    &hex::encode(Sha256::digest(bytes)),
                    bytes.len(),
                )
            })
            .collect(),
        false,
    );

    let (status, incomplete) = causal_commit_units(&rig.app, token, vec![mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{incomplete}");
    assert_eq!(incomplete["status"], "rejected");
    assert_eq!(incomplete["error"]["code"], "missing_media_bytes");
    let pull = pull_entities(&rig.app, token, generation).await;
    assert!(pull["entities"].as_array().unwrap().iter().all(|row| {
        row["client_uuid"] != plan_id.to_string()
            && media
                .iter()
                .all(|(media_id, _)| row["client_uuid"] != media_id.to_string())
    }));

    let (status, prepared) = put_causal_media_bytes(&rig.app, token, media[2].0, media[2].1).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let (status, committed) = causal_commit_units(&rig.app, token, vec![mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(committed["results"][0]["status"], "accepted");
    assert_eq!(
        committed["results"][0]["stable"]["media"]
            .as_array()
            .unwrap()
            .len(),
        3,
    );

    let (status, replayed) = causal_commit_units(&rig.app, token, vec![mutation]).await;
    assert_eq!(status, StatusCode::OK, "{replayed}");
    assert_eq!(
        replayed["results"][0]["stable"]["version_id"],
        committed["results"][0]["stable"]["version_id"],
    );
    assert_eq!(
        replayed["results"][0]["request_hash"],
        committed["results"][0]["request_hash"],
    );
    for (media_id, expected) in media {
        let response = request(
            &rig.app,
            Method::GET,
            &format!("/v1/media/{media_id}"),
            Some(token),
            Body::empty(),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            response.into_body().collect().await.unwrap().to_bytes(),
            expected,
        );
    }
}

#[tokio::test]
async fn care_plan_attachment_waits_for_fact_respects_acl_and_retains_tombstoned_fact() {
    let rig = Rig::with_config(|config| config.max_media_bytes = 64 * 1024);
    let (owner, member) =
        two_joined_clients(&rig.app, "care-plan-acl-owner", "care-plan-acl-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4();
    let plan_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let bytes = b"dependency-bound-plan-photo";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, owner_token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let mut plan_root = care_plan_payload(&baby_id.to_string(), "formula");
    plan_root["status"] = json!("completed");
    plan_root["fulfilled_record_client_uuid"] = json!(record_id);
    plan_root["fulfilled_at"] = json!(1_700_000_100_i64);
    plan_root["updated_at"] = json!(30);
    let plan = causal_unit(
        Uuid::new_v4(),
        None,
        "care_plan",
        plan_id,
        plan_root,
        vec![causal_media_item(media_id, "plan", &sha, bytes.len())],
        false,
    );

    let (status, blocked) = causal_commit_units(&rig.app, owner_token, vec![plan.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{blocked}");
    assert_eq!(blocked["status"], "rejected");
    assert_eq!(blocked["error"]["code"], "invalid_domain");
    let pull = pull_entities(&rig.app, owner_token, generation).await;
    assert!(pull["entities"].as_array().unwrap().iter().all(|row| {
        row["client_uuid"] != plan_id.to_string() && row["client_uuid"] != media_id.to_string()
    }));

    let (status, record) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "fulfilled", 120, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{record}");
    let record_version = record["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, accepted) = causal_commit_units(&rig.app, owner_token, vec![plan]).await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");
    let stable_version = accepted["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let mut foreign_edit = care_plan_payload(&baby_id.to_string(), "formula");
    foreign_edit["note"] = json!("foreign edit");
    foreign_edit["updated_at"] = json!(40);
    let (status, forbidden) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&stable_version),
            "care_plan",
            plan_id,
            foreign_edit,
            vec![causal_media_item(media_id, "plan", &sha, bytes.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{forbidden}");
    assert_eq!(forbidden["status"], "rejected");
    assert_eq!(forbidden["error"]["code"], "forbidden");
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(member_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        bytes.as_slice(),
    );

    let (status, record_tombstone) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&record_version),
            "record",
            record_id,
            causal_formula_root(baby_id, "fulfilled", 120, 50),
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{record_tombstone}");
    assert_eq!(record_tombstone["results"][0]["status"], "accepted");
    let mut plan_tombstone = care_plan_payload(&baby_id.to_string(), "formula");
    plan_tombstone["status"] = json!("completed");
    plan_tombstone["fulfilled_record_client_uuid"] = json!(record_id);
    plan_tombstone["fulfilled_at"] = json!(1_700_000_100_i64);
    plan_tombstone["updated_at"] = json!(60);
    let (status, deleted_plan) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&stable_version),
            "care_plan",
            plan_id,
            plan_tombstone,
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{deleted_plan}");
    assert_eq!(deleted_plan["results"][0]["status"], "accepted");
}

#[tokio::test]
async fn deleted_care_plan_keeps_attachment_branch_bytes_auditable_but_unreachable() {
    let rig = Rig::with_config(|config| config.max_media_bytes = 64 * 1024);
    let owner = create_family(
        &rig.app,
        "care-plan-branch-owner",
        "care-plan-branch-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let bytes = b"care-plan-branch-exact-bytes";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let mut live_root = care_plan_payload(&baby_id.to_string(), "formula");
    live_root["updated_at"] = json!(20);
    let media = causal_media_item(media_id, "plan", &sha, bytes.len());
    let (status, created) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "care_plan",
            plan_id,
            live_root.clone(),
            vec![media.clone()],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap();
    let mut tombstone_root = live_root.clone();
    tombstone_root["updated_at"] = json!(30);
    let (status, deleted) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(v1),
            "care_plan",
            plan_id,
            tombstone_root,
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{deleted}");
    assert_eq!(deleted["results"][0]["status"], "accepted");

    live_root["note"] = json!("stale attachment edit");
    live_root["updated_at"] = json!(40);
    let (status, branched) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(v1),
            "care_plan",
            plan_id,
            live_root,
            vec![media],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{branched}");
    assert_eq!(branched["results"][0]["status"], "branched", "{branched}");
    let conflict_id = branched["results"][0]["conflict_id"].as_str().unwrap();
    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    assert_eq!(detail["stable"]["deleted"], true);
    assert_eq!(detail["stable"]["media"], json!([]));
    assert!(detail["branches"].as_array().unwrap().iter().any(|branch| {
        branch["deleted"] == false
            && branch["media"][0]["media_uuid"] == media_id.to_string()
            && branch["media"][0]["sha256"] == sha
            && branch["media"][0]["byte_size"] == bytes.len()
    }));
    let pull = pull_entities(&rig.app, token, generation).await;
    assert!(!find_entity(&pull, plan_id)["deleted_at"].is_null());
    assert!(!find_entity(&pull, media_id)["deleted_at"].is_null());
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn wake_observation_commits_replays_branches_tombstones_and_pulls() {
    let rig = Rig::new();
    let (owner, member) = two_joined_clients(
        &rig.app,
        "wake-commit-first-owner",
        "wake-commit-first-member",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let sleep_id = Uuid::new_v4();
    let wake_id = Uuid::new_v4();
    let wake_time = 1_700_303_600_000_i64;

    let (status, sleep) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            sleep_id,
            causal_sleep_root(baby_id, 1_700_300_000_000, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{sleep}");
    assert_eq!(sleep["results"][0]["status"], "accepted", "{sleep}");

    let wake_mutation_id = Uuid::new_v4();
    let wake_root = json!({
        "sleep_record_client_uuid": sleep_id,
        "wake_timestamp": wake_time,
        "note": "first observation",
        "withdrawn": false,
        "updated_at": 30,
    });
    let wake = causal_unit(
        wake_mutation_id,
        None,
        "wake_observation",
        wake_id,
        wake_root.clone(),
        vec![],
        false,
    );
    let (status, accepted) = causal_commit_units(&rig.app, owner_token, vec![wake.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted", "{accepted}");
    assert_eq!(
        accepted["results"][0]["stable"]["root"]["sleep_record_client_uuid"],
        sleep_id.to_string(),
    );
    assert_eq!(
        accepted["results"][0]["stable"]["root"]["observer_membership_id"],
        owner["membership_id"],
    );
    let first_version = accepted["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, replayed) = causal_commit_units(&rig.app, owner_token, vec![wake.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{replayed}");
    assert_eq!(
        replayed["results"][0]["stable"]["version_id"],
        accepted["results"][0]["stable"]["version_id"],
    );
    assert_eq!(
        replayed["results"][0]["request_hash"],
        accepted["results"][0]["request_hash"],
    );

    let mut drift = wake;
    drift["root"]["note"] = json!("same mutation drift");
    let (status, rejected) = causal_commit_units(&rig.app, owner_token, vec![drift]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "content_drift");

    let mut stable_edit_root = wake_root.clone();
    stable_edit_root["note"] = json!("stable edit");
    stable_edit_root["updated_at"] = json!(40);
    let (status, stable_edit) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&first_version),
            "wake_observation",
            wake_id,
            stable_edit_root,
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{stable_edit}");
    assert_eq!(stable_edit["results"][0]["status"], "accepted");
    let stable_version = stable_edit["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let mut branch_root = wake_root.clone();
    branch_root["note"] = json!("concurrent owner observation");
    branch_root["updated_at"] = json!(41);
    let (status, branched) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&first_version),
            "wake_observation",
            wake_id,
            branch_root,
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{branched}");
    assert_eq!(branched["results"][0]["status"], "branched", "{branched}");
    assert_eq!(
        branched["results"][0]["stable"]["root"]["note"],
        "stable edit",
    );
    assert_eq!(
        branched["results"][0]["stable"]["root"]["sleep_record_client_uuid"],
        sleep_id.to_string(),
    );

    let mut tombstone_root = wake_root;
    tombstone_root["note"] = Value::Null;
    tombstone_root["updated_at"] = json!(50);
    let (status, deleted) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&stable_version),
            "wake_observation",
            wake_id,
            tombstone_root,
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{deleted}");
    assert_eq!(deleted["results"][0]["status"], "accepted", "{deleted}");

    let pull = pull_entities(&rig.app, member_token, generation).await;
    let deleted_wake = find_entity(&pull, wake_id);
    assert_eq!(deleted_wake["type"], "wake_observation");
    assert_eq!(
        deleted_wake["payload"]["sleep_record_client_uuid"],
        sleep_id.to_string(),
    );
    assert!(deleted_wake["deleted_at"].is_number());
}

#[tokio::test]
async fn choice_only_router_rebuilds_media_and_concurrent_tombstone() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "choice-media-owner",
        "choice-media-create-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let removed_id = Uuid::new_v4();
    let added_id = Uuid::new_v4();
    let removed_bytes = vec![1_u8; 4];
    let added_bytes = vec![2_u8; 5];
    assert_eq!(
        put_causal_media_bytes(&rig.app, token, removed_id, &removed_bytes)
            .await
            .0,
        StatusCode::OK,
    );
    let removed_media = causal_media_item(
        removed_id,
        "log",
        &hex::encode(Sha256::digest(&removed_bytes)),
        removed_bytes.len(),
    );
    let (status, created) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "base", 100, 20),
            vec![removed_media],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let base = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap();
    assert_eq!(
        put_causal_media_bytes(&rig.app, token, added_id, &added_bytes)
            .await
            .0,
        StatusCode::OK,
    );
    let added_media = causal_media_item(
        added_id,
        "log",
        &hex::encode(Sha256::digest(&added_bytes)),
        added_bytes.len(),
    );
    let (status, live) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(base),
            "record",
            record_id,
            causal_formula_root(baby_id, "stable-live", 100, 30),
            vec![added_media.clone()],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{live}");
    let live_version = live["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, tombstone) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(base),
            "record",
            record_id,
            causal_formula_root(baby_id, "offline-delete", 100, 40),
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{tombstone}");
    assert_eq!(tombstone["results"][0]["status"], "branched");
    let tombstone_version = tombstone["results"][0]["branch_version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let conflict_id = tombstone["results"][0]["conflict_id"].as_str().unwrap();
    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    assert!(detail["branches"]
        .as_array()
        .unwrap()
        .iter()
        .any(|branch| branch["deleted"] == true));
    let mut choices = detail["conflicting"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| {
            let candidate = item["candidates"]
                .as_array()
                .unwrap()
                .iter()
                .find(|candidate| {
                    if item["path"] == "/_mutation.deleted" {
                        candidate["outcome"] == json!({"op": "remove"})
                    } else {
                        candidate["sources"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .any(|source| source["version_id"] == tombstone_version)
                    }
                })
                .unwrap();
            json!({"path": item["path"], "choice_id": candidate["choice_id"]})
        })
        .collect::<Vec<_>>();
    choices.sort_by(|left, right| {
        left["path"]
            .as_str()
            .unwrap()
            .cmp(right["path"].as_str().unwrap())
    });
    let resolve = json!({
        "snapshot_token": detail["snapshot_token"],
        "resolution_mutation_id": Uuid::new_v4().to_string(),
        "choices": choices,
    });
    let (status, accepted) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(token),
        resolve.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["status"], "accepted");
    assert_eq!(accepted["stable_root"]["note"], "offline-delete");
    assert!(accepted.get("stable_media").is_none());
    let resolved_version = accepted["stable_version_id"].as_str().unwrap();
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let (deleted_at, parent_count, parent): (Option<i64>, i64, String) = connection
        .query_row(
            "SELECT v.deleted_at, COUNT(p.parent_version_id), MIN(p.parent_version_id)
             FROM entity_versions v
             JOIN entity_version_parents p
               ON p.family_id = v.family_id AND p.version_id = v.version_id
             WHERE v.version_id = ?1 GROUP BY v.family_id, v.version_id",
            [resolved_version],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    assert!(deleted_at.is_some());
    assert_eq!(parent_count, 1);
    assert_eq!(parent, live_version);
    let provenance: Value = serde_json::from_str(
        &connection
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                 WHERE membership_id = '__version_provenance_v2__' AND mutation_id = ?1",
                [resolved_version],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();
    assert_eq!(provenance["actor_id"], owner["membership_id"]);
    assert_eq!(provenance["device_id"], owner["device_id"]);
    let (status, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK, "{pull}");
    let entities = pull["entities"].as_array().unwrap();
    let record = entities
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id.to_string())
        .unwrap();
    assert!(record["deleted_at"].is_number());
    assert_eq!(record["version_id"], accepted["stable_version_id"]);
    assert!(entities.iter().any(|entity| {
        entity["type"] == "media"
            && entity["client_uuid"] == added_id.to_string()
            && entity["deleted_at"].is_number()
    }));
    assert!(entities.iter().any(|entity| {
        entity["type"] == "media"
            && entity["client_uuid"] == removed_id.to_string()
            && entity["deleted_at"].is_number()
    }));
    let (status, replay) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(token),
        resolve,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["replay"], true);
    assert_eq!(replay["stable_root"], accepted["stable_root"]);
    assert!(replay.get("stable_media").is_none());
}

#[tokio::test]
async fn two_clients_restore_only_the_tombstones_complete_direct_base_across_restart() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "direct-restore-owner", "direct-restore-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let media_bytes = b"restore";
    let media_sha = hex::encode(Sha256::digest(media_bytes));
    assert_eq!(
        put_causal_media_bytes(&rig.app, member_token, media_id, media_bytes)
            .await
            .0,
        StatusCode::OK,
    );
    let media = causal_media_item(media_id, "log", &media_sha, media_bytes.len());
    let (status, created) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "direct-base", 90, 20),
            vec![media.clone()],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let base_version = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let base_root = created["results"][0]["stable"]["root"].clone();
    let (status, deleted) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&base_version),
            "record",
            record_id,
            causal_formula_root(baby_id, "delete-envelope", 90, 30),
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{deleted}");
    assert_eq!(deleted["results"][0]["status"], "accepted");
    assert!(
        deleted["results"][0].get("conflict_id").is_none(),
        "stable delete without branches is not a sync conflict: {deleted}"
    );
    let tombstone_version = deleted["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let family_id = owner["family_id"].as_str().unwrap();
    let conflict_id = Uuid::new_v4().to_string();
    let restarted = rig.restart("generation-a");
    // 0.5: the startup empty-open closure sweep resolves exactly this kind of
    // synthetic leftover row, so the fixture is grafted AFTER the sweep has
    // passed — proven by a canary leftover the sweep must resolve. The sweep
    // path itself is covered by
    // startup_maintenance_closes_leftover_empty_open_conflicts.
    let canary_conflict = Uuid::new_v4().to_string();
    {
        let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
        connection
            .execute(
                "INSERT INTO conflicts(
                    family_id, conflict_id, entity_type, client_uuid, base_version_id,
                    stable_version_id, status, kind, created_at, resolved_at
                 ) VALUES (?1, ?2, 'record', ?3, NULL, ?4, 'open', 'tombstone_restore', 1, NULL)",
                rusqlite::params![
                    family_id,
                    canary_conflict,
                    record_id.to_string(),
                    tombstone_version,
                ],
            )
            .unwrap();
    }
    let mut sweep_passed = false;
    for _ in 0..400 {
        let status: Option<String> = Connection::open(rig.directory.path().join("lezi.db"))
            .unwrap()
            .query_row(
                "SELECT status FROM conflicts WHERE conflict_id = ?1",
                [&canary_conflict],
                |row| row.get(0),
            )
            .optional()
            .unwrap();
        if status.as_deref() == Some("resolved") {
            sweep_passed = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
    assert!(sweep_passed, "startup sweep must close the canary leftover");
    Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .execute(
            "INSERT INTO conflicts(
                family_id, conflict_id, entity_type, client_uuid, base_version_id,
                stable_version_id, status, kind, created_at, resolved_at
             ) VALUES (?1, ?2, 'record', ?3, NULL, ?4, 'open', 'tombstone_restore', ?5, NULL)",
            rusqlite::params![
                family_id,
                conflict_id,
                record_id.to_string(),
                tombstone_version,
                1_700_000_001_i64,
            ],
        )
        .unwrap();
    let (status, detail) = get_json(
        &restarted,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(member_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    assert!(detail["branches"].as_array().unwrap().is_empty());
    let restore_path = detail["conflicting"]
        .as_array()
        .unwrap()
        .iter()
        .find(|item| item["path"] == "/_mutation.deleted")
        .unwrap();
    let restore_candidate = restore_path["candidates"]
        .as_array()
        .unwrap()
        .iter()
        .find(|candidate| candidate["outcome"] == json!({"op": "set", "value": false}))
        .unwrap();
    assert_eq!(restore_candidate["sources"].as_array().unwrap().len(), 1);
    assert_eq!(restore_candidate["sources"][0]["version_id"], base_version,);
    let resolution_mutation_id = Uuid::new_v4().to_string();
    let resolve = json!({
        "snapshot_token": detail["snapshot_token"],
        "resolution_mutation_id": resolution_mutation_id,
        "choices": [{
            "path": "/_mutation.deleted",
            "choice_id": restore_candidate["choice_id"],
        }],
    });
    // A crash artifact can make post-commit promotion fail after the accepted
    // resolution is durable. Exact replay must retry that repair rather than
    // returning the receipt while media publication is still absent.
    let staged_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(owner["family_id"].as_str().unwrap())
        .join(media_id.to_string());
    fs::create_dir_all(&staged_path).unwrap();
    let (status, failed_after_commit) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(member_token),
        resolve.clone(),
    )
    .await;
    assert_eq!(
        status,
        StatusCode::INTERNAL_SERVER_ERROR,
        "{failed_after_commit}"
    );
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let (conflict_status, publication_count): (String, i64) = connection
        .query_row(
            "SELECT c.status,
                    (SELECT COUNT(*) FROM media_publications p
                      WHERE p.family_id = c.family_id AND p.media_uuid = ?1)
               FROM conflicts c WHERE c.conflict_id = ?2",
            [media_id.to_string(), conflict_id.clone()],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(conflict_status, "resolved");
    assert_eq!(publication_count, 0);
    drop(connection);
    let unavailable = request(
        &restarted,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(owner_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(unavailable.status(), StatusCode::NOT_FOUND);
    let unrelated_media_dir = rig
        .directory
        .path()
        .join("media")
        .join(owner["family_id"].as_str().unwrap());
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    for _ in 0..32 {
        let unrelated_id = Uuid::new_v4().to_string();
        connection
            .execute(
                "INSERT INTO causal_media_staging(
                    family_id, membership_id, media_uuid, sha256, byte_size,
                    created_at, expires_at, status, consumed_at
                 ) VALUES (?1, ?2, ?3, ?4, 1, 1, 2, 'consumed', 1)",
                rusqlite::params![
                    owner["family_id"].as_str().unwrap(),
                    owner["membership_id"].as_str().unwrap(),
                    unrelated_id,
                    "0".repeat(64),
                ],
            )
            .unwrap();
        fs::write(unrelated_media_dir.join(unrelated_id), [1_u8]).unwrap();
    }
    drop(connection);
    fs::remove_dir(&staged_path).unwrap();
    let (status, restored) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(member_token),
        resolve.clone(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{restored}");
    assert_eq!(restored["status"], "accepted");
    assert_eq!(restored["replay"], true);
    let unrelated_count: i64 = Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
              WHERE family_id = ?1 AND media_uuid != ?2 AND status = 'consumed'",
            [
                owner["family_id"].as_str().unwrap(),
                media_id.to_string().as_str(),
            ],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(unrelated_count, 32);
    assert_eq!(restored["stable_root"], base_root);
    assert_eq!(restored["stable_media"], json!([media]));
    let resolved_version = restored["stable_version_id"].as_str().unwrap();
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let parent: String = connection
        .query_row(
            "SELECT parent_version_id FROM entity_version_parents WHERE version_id = ?1",
            [resolved_version],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(parent, tombstone_version);
    let provenance: Value = serde_json::from_str(
        &connection
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                  WHERE membership_id = '__version_provenance_v2__' AND mutation_id = ?1",
                [resolved_version],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();
    assert_eq!(provenance["actor_id"], member["membership_id"]);
    assert_eq!(provenance["device_id"], member["device_id"]);
    drop(connection);
    let download = request(
        &restarted,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(owner_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(download.status(), StatusCode::OK);
    assert_eq!(
        download.into_body().collect().await.unwrap().to_bytes(),
        Bytes::from_static(media_bytes),
    );
    for token in [owner_token, member_token] {
        let pull = pull_entities(&restarted, token, generation).await;
        let record = find_entity(&pull, record_id);
        assert!(record["deleted_at"].is_null());
        assert_eq!(record["payload"]["note"], "direct-base");
        conflict_summary_is_closed(record);
    }
    let (status, replay) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(member_token),
        resolve,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["replay"], true);
    assert_eq!(replay["stable_version_id"], restored["stable_version_id"]);
}

#[tokio::test]
async fn causal_commit_empty_batch_rejects_without_mutation_id() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "causal-empty-batch-owner",
        "causal-empty-batch-create-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let (status, body) = causal_commit_units(&rig.app, token, vec![]).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["status"], "rejected");
    assert_eq!(body["error"]["code"], "invalid_domain");
    assert_eq!(body["error"]["retryable"], false);
    assert!(body.get("mutation_id").is_none(), "{body}");
}

#[tokio::test]
async fn causal_commit_http_returns_typed_saturation_and_allows_exact_replay() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "causal-admission-owner",
        "causal-admission-create-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let (status, first, mutation) =
        commit_causal_record(&rig.app, token, baby_id, record_id, None, "within-budget").await;
    assert_eq!(status, StatusCode::OK, "{first}");
    assert_eq!(first["results"][0]["status"], "accepted");

    for index in 2..119 {
        let (status, body, _) = commit_causal_record(
            &rig.app,
            token,
            baby_id,
            Uuid::new_v4(),
            None,
            &format!("within-budget-{index}"),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "attempt {index}: {body}");
    }

    let oversized = (0..65)
        .map(|index| {
            causal_unit(
                Uuid::new_v4(),
                None,
                "record",
                Uuid::new_v4(),
                causal_formula_root(baby_id, &format!("oversized-{index}"), 100, 20),
                vec![],
                false,
            )
        })
        .collect();
    let (oversized_status, oversized_body) = causal_commit_units(&rig.app, token, oversized).await;
    assert_eq!(oversized_status, StatusCode::OK, "{oversized_body}");
    assert_eq!(oversized_body["status"], "rejected");
    assert_eq!(oversized_body["error"]["code"], "invalid_domain");
    assert!(
        oversized_body.get("mutation_id").is_none(),
        "{oversized_body}"
    );

    let saturated_unit = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        Uuid::new_v4(),
        causal_formula_root(baby_id, "over-budget", 100, 20),
        vec![],
        false,
    );
    let response = request_with_headers(
        &rig.app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        Body::from(json!({ "units": [saturated_unit] }).to_string()),
        Some("application/json"),
        &[],
    )
    .await;
    let status = response.status();
    assert_eq!(response.headers()["retry-after"], "60");
    let saturated: Value =
        serde_json::from_slice(&response.into_body().collect().await.unwrap().to_bytes()).unwrap();
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{saturated}");
    assert_eq!(saturated["code"], "causal_commit_principal_rate_limited");
    assert_eq!(saturated["detail"]["scope"], "principal");
    assert_eq!(saturated["detail"]["retryable"], true);

    let (status, replay) = causal_commit_units(&rig.app, token, vec![mutation]).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(
        replay["results"][0]["status"],
        first["results"][0]["status"]
    );
    assert_eq!(
        replay["results"][0]["stable"],
        first["results"][0]["stable"]
    );
    assert_eq!(replay["results"][0]["replay"], true);
}

#[tokio::test]
async fn causal_commit_http_maps_branch_capacity_without_hiding_the_conflict() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "causal-branch-cap-owner",
        "causal-branch-cap-create-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let (status, created, _) =
        commit_causal_record(&rig.app, token, baby_id, record_id, None, "base").await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let base = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap();
    let (status, accepted, _) =
        commit_causal_record(&rig.app, token, baby_id, record_id, Some(base), "stable").await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    let mut first_branch = None;
    let mut branched = Value::Null;
    for index in 1..=64 {
        let (status, body, mutation) = commit_causal_record(
            &rig.app,
            token,
            baby_id,
            record_id,
            Some(base),
            &format!("branch-{index}"),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "branch {index}: {body}");
        assert_eq!(body["results"][0]["status"], "branched");
        if index == 1 {
            first_branch = Some(mutation);
            branched = body;
        }
    }

    let (status, saturated, _) =
        commit_causal_record(&rig.app, token, baby_id, record_id, Some(base), "branch-65").await;
    assert_eq!(status, StatusCode::TOO_MANY_REQUESTS, "{saturated}");
    assert_eq!(saturated["code"], "causal_open_branch_limit_reached");
    assert_eq!(saturated["detail"]["scope"], "root");

    let conflict_id = branched["results"][0]["conflict_id"].as_str().unwrap();
    let mut next = format!("/v1/conflicts/{conflict_id}");
    let mut branch_ids = Vec::new();
    let mut snapshot_token = None;
    let mut first_body = None;
    let mut replay_url = None;
    let mut replay_body = None;
    for expected_page in 0..4 {
        let response = request(
            &rig.app,
            Method::GET,
            &next,
            Some(token),
            Body::empty(),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        assert!(bytes.len() <= 128 * 1024, "page bytes={}", bytes.len());
        let detail: Value = serde_json::from_slice(&bytes).unwrap();
        if expected_page == 0 {
            first_body = Some(bytes.clone());
        }
        assert_eq!(detail["page_index"], expected_page);
        assert!(detail["branches"].as_array().unwrap().len() <= 16);
        branch_ids.extend(
            detail["branches"]
                .as_array()
                .unwrap()
                .iter()
                .map(|branch| branch["version_id"].as_str().unwrap().to_owned()),
        );
        match &snapshot_token {
            Some(value) => assert_eq!(detail["snapshot_token"], *value),
            None => snapshot_token = detail["snapshot_token"].as_str().map(str::to_owned),
        }
        if expected_page == 1 {
            replay_url = Some(next.clone());
            replay_body = Some(bytes.clone());
        }
        if detail["complete"] == true {
            assert!(detail["continuation"].is_null());
            break;
        }
        let continuation = detail["continuation"].as_str().unwrap();
        next = format!(
            "/v1/conflicts/{conflict_id}?snapshot_token={}&continuation={continuation}",
            snapshot_token.as_deref().unwrap(),
        );
    }
    assert_eq!(branch_ids.len(), 64);
    assert!(branch_ids.windows(2).all(|pair| pair[0] < pair[1]));

    let replay_response = request(
        &rig.app,
        Method::GET,
        replay_url.as_deref().unwrap(),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(replay_response.status(), StatusCode::OK);
    assert_eq!(
        replay_response
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes(),
        replay_body.unwrap(),
    );

    let snapshot_token = snapshot_token.unwrap();
    let restarted = rig.restart("generation-b");
    let replay_first = request(
        &restarted,
        Method::GET,
        &format!("/v1/conflicts/{conflict_id}?snapshot_token={snapshot_token}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(replay_first.status(), StatusCode::OK);
    assert_eq!(
        replay_first.into_body().collect().await.unwrap().to_bytes(),
        first_body.unwrap(),
    );

    let current_stable = accepted["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap();
    let (stable_status, stable_changed, _) = commit_causal_record(
        &rig.app,
        token,
        baby_id,
        record_id,
        Some(current_stable),
        "stable-after-snapshot",
    )
    .await;
    assert_eq!(stable_status, StatusCode::OK, "{stable_changed}");
    let family_id = owner["family_id"].as_str().unwrap().to_owned();
    let database_path = rig.directory.path().join("lezi.db");
    let durable_state = || {
        let connection = Connection::open(&database_path).unwrap();
        let conflict = connection
            .query_row(
                "SELECT stable_version_id, status, kind, resolved_at
                 FROM conflicts WHERE family_id = ?1 AND conflict_id = ?2",
                rusqlite::params![family_id, conflict_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, Option<i64>>(3)?,
                    ))
                },
            )
            .unwrap();
        let branches = connection
            .prepare(
                "SELECT branch_version_id FROM conflict_branches
                 WHERE family_id = ?1 AND conflict_id = ?2 ORDER BY branch_version_id",
            )
            .unwrap()
            .query_map(rusqlite::params![family_id, conflict_id], |row| {
                row.get::<_, String>(0)
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        let receipt = connection
            .query_row(
                "SELECT content_hash, stable_version_id, conflict_id, receipt_json, created_at
                 FROM mutation_receipts
                 WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'",
                rusqlite::params![family_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, Option<String>>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, i64>(4)?,
                    ))
                },
            )
            .unwrap();
        let revision = connection
            .query_row(
                "SELECT rev FROM family_meta WHERE family_id = ?1",
                rusqlite::params![family_id],
                |row| row.get::<_, i64>(0),
            )
            .unwrap();
        let version_count = connection
            .query_row(
                "SELECT COUNT(*) FROM entity_versions
                 WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2",
                rusqlite::params![family_id, record_id.to_string()],
                |row| row.get::<_, i64>(0),
            )
            .unwrap();
        json!({
            "conflict": conflict,
            "branches": branches,
            "receipt": receipt,
            "revision": revision,
            "version_count": version_count,
        })
    };
    let before_stale = durable_state();
    let (stale_status, stale) = get_json(
        &restarted,
        &format!("/v1/conflicts/{conflict_id}?snapshot_token={snapshot_token}"),
        Some(token),
    )
    .await;
    assert_eq!(stale_status, StatusCode::CONFLICT);
    assert_eq!(stale["code"], "snapshot_stale");
    assert_eq!(durable_state(), before_stale);

    let mut tampered_url = replay_url.unwrap();
    let last = tampered_url.pop().unwrap();
    tampered_url.push(if last == 'a' { 'b' } else { 'a' });
    let before_tamper = durable_state();
    let (tampered_status, tampered) = get_json(&restarted, &tampered_url, Some(token)).await;
    assert_eq!(tampered_status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(tampered["code"], "invalid_snapshot_token");
    assert_eq!(durable_state(), before_tamper);

    rig.now.fetch_add(10 * 60, Ordering::SeqCst);
    let before_expiry = durable_state();
    let (expired_status, expired) = get_json(
        &restarted,
        &format!("/v1/conflicts/{conflict_id}?snapshot_token={snapshot_token}"),
        Some(token),
    )
    .await;
    assert_eq!(expired_status, StatusCode::GONE);
    assert_eq!(expired["code"], "snapshot_expired");
    assert_eq!(durable_state(), before_expiry);

    let (replay_status, replay) =
        causal_commit_units(&rig.app, token, vec![first_branch.unwrap()]).await;
    assert_eq!(replay_status, StatusCode::OK, "{replay}");
    assert_eq!(
        replay["results"][0]["status"],
        branched["results"][0]["status"]
    );
    assert_eq!(
        replay["results"][0]["stable"],
        branched["results"][0]["stable"]
    );
    assert_eq!(
        replay["results"][0]["branch_version_id"],
        branched["results"][0]["branch_version_id"]
    );
    assert_eq!(
        replay["results"][0]["conflict_id"],
        branched["results"][0]["conflict_id"]
    );
    assert_eq!(replay["results"][0]["replay"], true);
}

#[tokio::test]
async fn causal_ingress_api_uses_the_canonical_store_validation_codes() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "canonical-ingress-owner",
        "canonical-ingress-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let diary_id = Uuid::new_v4();
    let (status, accepted) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            diary_id,
            json!({
                "baby_client_uuid": baby_id,
                "type": "diary",
                "custom_item_client_uuid": null,
                "timestamp": 100,
                "end_timestamp": null,
                "note": null,
                "payload_json": {"body": "今天第一次翻身"},
                "schema_version": 2,
                "updated_at": 20
            }),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted", "{accepted}");

    let invalid_value_id = Uuid::new_v4();
    let dangling_id = Uuid::new_v4();
    let (status, rejected) = causal_commit_units(
        &rig.app,
        token,
        vec![
            causal_unit(
                Uuid::new_v4(),
                None,
                "record",
                invalid_value_id,
                causal_formula_root(baby_id, "negative", -1, 30),
                vec![],
                false,
            ),
            causal_unit(
                Uuid::new_v4(),
                None,
                "record",
                dangling_id,
                causal_formula_root(Uuid::new_v4(), "dangling", 100, 30),
                vec![],
                false,
            ),
        ],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "invalid_domain");

    let pull = pull_entities(&rig.app, token, owner["generation"].as_str().unwrap()).await;
    assert!(pull["entities"].as_array().unwrap().iter().all(|row| {
        row["client_uuid"] != invalid_value_id.to_string()
            && row["client_uuid"] != dangling_id.to_string()
    }));
}

#[tokio::test]
async fn causal_media_commit_rejects_same_size_different_digest_before_publication() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-manifest-owner",
        "causal-media-manifest-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let uploaded = b"digest-a";
    let declared = b"digest-b";
    assert_eq!(uploaded.len(), declared.len());

    let (status, staged) = put_causal_media_bytes(&rig.app, token, media_id, uploaded).await;
    assert_eq!(status, StatusCode::OK, "{staged}");

    let declared_sha = hex::encode(Sha256::digest(declared));
    let (status, rejected) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "wrong-digest", 80, 20),
            vec![causal_media_item(
                media_id,
                "log",
                &declared_sha,
                declared.len(),
            )],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "media_sha256_mismatch");

    let pull = pull_entities(&rig.app, token, generation).await;
    assert!(pull["entities"].as_array().unwrap().iter().all(|row| {
        row["client_uuid"] != record_id.to_string() && row["client_uuid"] != media_id.to_string()
    }));
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn causal_media_commit_claims_only_the_preparing_principals_open_receipt() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let (owner, member) =
        two_joined_clients(&rig.app, "receipt-claim-owner", "receipt-claim-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let bytes = b"principal-bound-commit";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, owner_token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    assert_eq!(prepared["status"], "staged");

    let foreign = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        record_id,
        causal_formula_root(baby_id, "foreign-receipt", 80, 20),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );
    let (status, rejected) = causal_commit_units(&rig.app, member_token, vec![foreign]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "media_membership_mismatch");

    let accepted_mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        record_id,
        causal_formula_root(baby_id, "owned-receipt", 80, 30),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );
    let (status, accepted) =
        causal_commit_units(&rig.app, owner_token, vec![accepted_mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");
    let stable_version = accepted["results"][0]["stable"]["version_id"].clone();
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(member_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        bytes.as_slice()
    );

    let (status, replay) =
        causal_commit_units(&rig.app, owner_token, vec![accepted_mutation]).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["results"][0]["status"], "accepted");
    assert_eq!(replay["results"][0]["stable"]["version_id"], stable_version);
    let version_count: i64 = Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM entity_versions
              WHERE entity_type = 'record' AND client_uuid = ?1",
            [record_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(version_count, 1);
}

#[tokio::test]
async fn causal_media_receipt_family_length_and_expiry_matrix_is_zero_write() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "receipt-matrix-owner",
        "receipt-matrix-family-request-000001",
    )
    .await;
    let family_id = owner["family_id"].as_str().unwrap().to_owned();
    let mut token = owner["access_token"].as_str().unwrap().to_owned();
    let baby_id = seed_causal_baby(&rig.app, &token).await;
    let database_path = rig.directory.path().join("lezi.db");

    // Exact byte length is receipt authority. A semantic rejection leaves no
    // terminal/version/projection/publication and the same mutation can retry
    // with the receipt's canonical metadata.
    let length_media_id = Uuid::new_v4();
    let length_bytes = b"receipt-length";
    let length_sha = hex::encode(Sha256::digest(length_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, &token, length_media_id, length_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let length_mutation_id = Uuid::new_v4();
    let length_record_id = Uuid::new_v4();
    let before_length = causal_receipt_write_state(
        &database_path,
        &family_id,
        &family_id,
        length_mutation_id,
        length_record_id,
        length_media_id,
    );
    let wrong_length = causal_unit(
        length_mutation_id,
        None,
        "record",
        length_record_id,
        causal_formula_root(baby_id, "wrong-length", 80, 20),
        vec![causal_media_item(
            length_media_id,
            "log",
            &length_sha,
            length_bytes.len() + 1,
        )],
        false,
    );
    let (status, rejected) = causal_commit_units(&rig.app, &token, vec![wrong_length]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "media_byte_size_mismatch");
    assert_eq!(
        causal_receipt_write_state(
            &database_path,
            &family_id,
            &family_id,
            length_mutation_id,
            length_record_id,
            length_media_id,
        ),
        before_length
    );
    assert_eq!(before_length.staging_status.as_deref(), Some("staged"));
    let correct_length = causal_unit(
        length_mutation_id,
        None,
        "record",
        length_record_id,
        causal_formula_root(baby_id, "wrong-length", 80, 20),
        vec![causal_media_item(
            length_media_id,
            "log",
            &length_sha,
            length_bytes.len(),
        )],
        false,
    );
    let (status, accepted) = causal_commit_units(&rig.app, &token, vec![correct_length]).await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");

    // The same durable row under another family key is invisible to this
    // authenticated family. Restoring the fixture binding proves rejection did
    // not terminalize the mutation or consume the receipt.
    let family_media_id = Uuid::new_v4();
    let family_bytes = b"receipt-family";
    let family_sha = hex::encode(Sha256::digest(family_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, &token, family_media_id, family_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let foreign_family_id = Uuid::new_v4().to_string();
    let connection = Connection::open(&database_path).unwrap();
    connection
        .execute_batch("PRAGMA foreign_keys = OFF")
        .unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging SET family_id = ?1
             WHERE family_id = ?2 AND media_uuid = ?3",
            rusqlite::params![foreign_family_id, family_id, family_media_id.to_string()],
        )
        .unwrap();
    drop(connection);
    let family_mutation_id = Uuid::new_v4();
    let family_record_id = Uuid::new_v4();
    let before_family = causal_receipt_write_state(
        &database_path,
        &family_id,
        &foreign_family_id,
        family_mutation_id,
        family_record_id,
        family_media_id,
    );
    let family_mutation = causal_unit(
        family_mutation_id,
        None,
        "record",
        family_record_id,
        causal_formula_root(baby_id, "wrong-family", 80, 30),
        vec![causal_media_item(
            family_media_id,
            "log",
            &family_sha,
            family_bytes.len(),
        )],
        false,
    );
    let (status, rejected) =
        causal_commit_units(&rig.app, &token, vec![family_mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    assert_eq!(rejected["error"]["code"], "missing_media_bytes");
    assert_eq!(
        causal_receipt_write_state(
            &database_path,
            &family_id,
            &foreign_family_id,
            family_mutation_id,
            family_record_id,
            family_media_id,
        ),
        before_family
    );
    assert_eq!(before_family.staging_status.as_deref(), Some("staged"));
    let connection = Connection::open(&database_path).unwrap();
    connection
        .execute_batch("PRAGMA foreign_keys = OFF")
        .unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging SET family_id = ?1
             WHERE family_id = ?2 AND media_uuid = ?3",
            rusqlite::params![family_id, foreign_family_id, family_media_id.to_string()],
        )
        .unwrap();
    drop(connection);
    let (status, accepted) = causal_commit_units(&rig.app, &token, vec![family_mutation]).await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");

    // TTL is half-open: now == expires_at is already expired. Startup then
    // collects that open receipt, allowing a fresh receipt and the same
    // non-terminalized mutation to succeed.
    let expiry_media_id = Uuid::new_v4();
    let expiry_bytes = b"receipt-expiry-equality";
    let expiry_sha = hex::encode(Sha256::digest(expiry_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, &token, expiry_media_id, expiry_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let expires_at = prepared["expires_at"].as_i64().unwrap();
    rig.now.store(expires_at, Ordering::SeqCst);
    let (refresh_status, refreshed) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": owner["refresh_token"]}),
    )
    .await;
    assert_eq!(refresh_status, StatusCode::OK, "{refreshed}");
    token = refreshed["access_token"].as_str().unwrap().to_owned();
    let expiry_mutation_id = Uuid::new_v4();
    let expiry_record_id = Uuid::new_v4();
    let expiry_mutation = causal_unit(
        expiry_mutation_id,
        None,
        "record",
        expiry_record_id,
        causal_formula_root(baby_id, "expiry-equality", 80, 40),
        vec![causal_media_item(
            expiry_media_id,
            "log",
            &expiry_sha,
            expiry_bytes.len(),
        )],
        false,
    );
    let before_expiry = causal_receipt_write_state(
        &database_path,
        &family_id,
        &family_id,
        expiry_mutation_id,
        expiry_record_id,
        expiry_media_id,
    );
    let (status, rejected) =
        causal_commit_units(&rig.app, &token, vec![expiry_mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    let expiry_code = rejected["error"]["code"].as_str().unwrap_or_default();
    assert!(
        expiry_code == "media_preimage_expired" || expiry_code == "missing_media_bytes",
        "expired receipt must stay a media claim code, got {expiry_code}"
    );
    let after_expiry = causal_receipt_write_state(
        &database_path,
        &family_id,
        &family_id,
        expiry_mutation_id,
        expiry_record_id,
        expiry_media_id,
    );
    // At now == expires_at, detached H24 maintenance may independently mark or
    // remove the expired staging row. The rejected commit must still be a zero
    // write for family revision, immutable/terminal facts, and publication.
    assert_eq!(after_expiry.revision, before_expiry.revision);
    assert_eq!(after_expiry.version_rows, before_expiry.version_rows);
    assert_eq!(after_expiry.terminal_rows, before_expiry.terminal_rows);
    assert_eq!(after_expiry.record_rows, before_expiry.record_rows);
    assert_eq!(after_expiry.media_rows, before_expiry.media_rows);
    assert_eq!(
        after_expiry.publication_rows,
        before_expiry.publication_rows
    );

    let restarted = rig.restart_with_config("generation-a", |config| {
        config.max_media_bytes = 64 * 1024;
    });
    let (status, reprepared) =
        put_causal_media_bytes(&restarted, &token, expiry_media_id, expiry_bytes).await;
    assert_eq!(status, StatusCode::OK, "{reprepared}");
    assert_eq!(reprepared["status"], "staged");
    let (status, accepted) = causal_commit_units(&restarted, &token, vec![expiry_mutation]).await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");
}

#[tokio::test]
async fn causal_media_corrupt_stored_receipt_returns_5xx_without_terminal_writes() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let session = create_family(
        &rig.app,
        "corrupt-receipt-owner",
        "corrupt-receipt-family-request-0001",
    )
    .await;
    let token = session["access_token"].as_str().unwrap();
    let family_id = session["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let bytes = b"corrupt-stored-receipt";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute_batch("PRAGMA ignore_check_constraints = ON")
        .unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging SET status = 'corrupt'
              WHERE family_id = ?1 AND media_uuid = ?2",
            [family_id, &media_id.to_string()],
        )
        .unwrap();
    drop(connection);

    let mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        record_id,
        causal_formula_root(baby_id, "corrupt-receipt", 80, 20),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );
    let (status, failed) = causal_commit_units(&rig.app, token, vec![mutation]).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{failed}");
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let (version_count, receipt_count): (i64, i64) = connection
        .query_row(
            "SELECT
                (SELECT COUNT(*) FROM entity_versions
                  WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2),
                (SELECT COUNT(*) FROM mutation_receipts
                  WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2)",
            [family_id, &record_id.to_string()],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!((version_count, receipt_count), (0, 0));
}

#[tokio::test]
async fn causal_media_corrupt_stored_entity_returns_invalid_stored_payload_without_update_writes() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let session = create_family(
        &rig.app,
        "corrupt-media-entity-owner",
        "corrupt-media-entity-family-request-01",
    )
    .await;
    let token = session["access_token"].as_str().unwrap();
    let family_id = session["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let bytes = b"corrupt-stored-media-entity";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let created = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        record_id,
        causal_formula_root(baby_id, "media-base", 80, 20),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );
    let (status, created) = causal_commit_units(&rig.app, token, vec![created]).await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let stable_version = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "UPDATE entities SET payload_json = '{'
              WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
            [family_id, &media_id.to_string()],
        )
        .unwrap();
    let before: (i64, i64, i64) = connection
        .query_row(
            "SELECT
                (SELECT rev FROM family_meta WHERE family_id = ?1),
                (SELECT COUNT(*) FROM entity_versions WHERE family_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts WHERE family_id = ?1)",
            [family_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    drop(connection);

    let update = causal_unit(
        Uuid::new_v4(),
        Some(&stable_version),
        "record",
        record_id,
        causal_formula_root(baby_id, "media-update", 80, 30),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );
    let (status, failed) = causal_commit_units(&rig.app, token, vec![update]).await;
    assert_eq!(status, StatusCode::CONFLICT, "{failed}");
    assert_eq!(failed["code"], "invalid_stored_payload");
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let after: (i64, i64, i64) = connection
        .query_row(
            "SELECT
                (SELECT rev FROM family_meta WHERE family_id = ?1),
                (SELECT COUNT(*) FROM entity_versions WHERE family_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts WHERE family_id = ?1)",
            [family_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    assert_eq!(after, before);
}

#[tokio::test]
async fn causal_media_commit_ignores_unrelated_corrupt_consumed_receipt() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let session = create_family(
        &rig.app,
        "bounded-promotion-owner",
        "bounded-promotion-family-request-001",
    )
    .await;
    let token = session["access_token"].as_str().unwrap();
    let family_id = session["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let unrelated_media = Uuid::parse_str("00000000-0000-4000-8000-000000000001").unwrap();
    let target_media = Uuid::parse_str("ffffffff-ffff-4fff-bfff-ffffffffffff").unwrap();
    let unrelated_bytes = b"unrelated-consumed-bytes";
    let target_bytes = b"target-receipt-bytes";
    let unrelated_sha = hex::encode(Sha256::digest(unrelated_bytes));
    let target_sha = hex::encode(Sha256::digest(target_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, token, unrelated_media, unrelated_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let unrelated_mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        Uuid::new_v4(),
        causal_formula_root(baby_id, "unrelated", 80, 20),
        vec![causal_media_item(
            unrelated_media,
            "log",
            &unrelated_sha,
            unrelated_bytes.len(),
        )],
        false,
    );
    let (status, committed) = causal_commit_units(&rig.app, token, vec![unrelated_mutation]).await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    let unrelated_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(unrelated_media.to_string());
    fs::write(&unrelated_path, b"corrupt").unwrap();

    let (status, prepared) =
        put_causal_media_bytes(&rig.app, token, target_media, target_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let target_record = Uuid::new_v4();
    let target_mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        target_record,
        causal_formula_root(baby_id, "target", 80, 20),
        vec![causal_media_item(
            target_media,
            "log",
            &target_sha,
            target_bytes.len(),
        )],
        false,
    );
    let (status, accepted) =
        causal_commit_units(&rig.app, token, vec![target_mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");
    let version_id = accepted["results"][0]["stable"]["version_id"].clone();
    let target_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(target_media.to_string());
    assert_eq!(fs::read(&target_path).unwrap(), target_bytes);

    let (status, replay) = causal_commit_units(&rig.app, token, vec![target_mutation]).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["results"][0]["stable"]["version_id"], version_id);
    let version_count: i64 = Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM entity_versions
              WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2",
            [family_id, &target_record.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(version_count, 1);
}

#[tokio::test]
async fn causal_media_prepare_rejects_declared_length_drift_without_a_receipt() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-length-owner",
        "causal-media-length-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let media_id = Uuid::new_v4();
    let bytes = b"length-bound-preimage";
    let sha = hex::encode(Sha256::digest(bytes));
    let declared = (bytes.len() + 1).to_string();

    let response = request_with_headers(
        &rig.app,
        Method::PUT,
        &format!("/v1/causal/media/{media_id}"),
        Some(token),
        Body::from(bytes.to_vec()),
        Some("application/octet-stream"),
        &[
            ("x-lezi-media-sha256", sha.as_str()),
            ("content-length", declared.as_str()),
        ],
    )
    .await;

    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(rows, 0, "length drift minted a durable receipt");
    let upload_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM causal_media_uploads WHERE family_id = ?1",
            rusqlite::params![family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(upload_rows, 0, "handled length error leaked upload quota");
    assert!(!rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id)
        .join(media_id.to_string())
        .exists());
}

#[tokio::test]
async fn causal_media_prepare_rejects_digest_drift_without_a_receipt() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-digest-owner",
        "causal-media-digest-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let media_id = Uuid::new_v4();
    let bytes = b"digest-bound-preimage";
    let wrong_sha = hex::encode(Sha256::digest(b"different-preimage"));

    let response = request_with_headers(
        &rig.app,
        Method::PUT,
        &format!("/v1/causal/media/{media_id}"),
        Some(token),
        Body::from(bytes.to_vec()),
        Some("application/octet-stream"),
        &[("x-lezi-media-sha256", wrong_sha.as_str())],
    )
    .await;

    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(rows, 0, "digest drift minted a durable receipt");
    let upload_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM causal_media_uploads WHERE family_id = ?1",
            rusqlite::params![family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(upload_rows, 0, "handled digest error leaked upload quota");
    let staging_dir = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id);
    let leftovers = fs::read_dir(staging_dir)
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .collect::<Vec<_>>();
    assert!(leftovers.is_empty(), "digest drift left incoming bytes");
}

#[tokio::test]
async fn causal_media_prepare_binds_receipt_to_membership_and_family() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let (owner, member) = two_joined_clients(
        &rig.app,
        "causal-media-binding-owner",
        "causal-media-binding-member",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let owner_membership_id = owner["membership_id"].as_str().unwrap();
    let media_id = Uuid::new_v4();
    let bytes = b"principal-bound-preimage";

    let (status, staged) = put_causal_media_bytes(&rig.app, owner_token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{staged}");
    let (status, conflict) = put_causal_media_bytes(&rig.app, member_token, media_id, bytes).await;
    assert_eq!(status, StatusCode::CONFLICT, "{conflict}");

    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let receipt_membership: String = connection
        .query_row(
            "SELECT membership_id FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(receipt_membership, owner_membership_id);

    let other_rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let other = create_family(
        &other_rig.app,
        "causal-media-binding-other-owner",
        "causal-media-binding-other-request-00001",
    )
    .await;
    let other_token = other["access_token"].as_str().unwrap();
    let (status, independent) =
        put_causal_media_bytes(&other_rig.app, other_token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{independent}");
    assert_ne!(other["family_id"], owner["family_id"]);
}

#[tokio::test]
async fn successful_causal_media_prepare_detaches_bounded_expired_family_gc() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-prepare-gc-owner",
        "causal-media-prepare-gc-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let expired_media = Uuid::new_v4();
    let (status, first) = put_causal_media_bytes(&rig.app, token, expired_media, b"expired").await;
    assert_eq!(status, StatusCode::OK, "{first}");

    Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .execute(
            "UPDATE causal_media_staging SET expires_at = ?1
              WHERE family_id = ?2 AND media_uuid = ?3",
            rusqlite::params![
                rig.now.load(Ordering::SeqCst) - 1,
                family_id,
                expired_media.to_string(),
            ],
        )
        .unwrap();
    let (status, second) =
        put_causal_media_bytes(&rig.app, token, Uuid::new_v4(), b"trigger").await;
    assert_eq!(status, StatusCode::OK, "{second}");

    let database_path = rig.directory.path().join("lezi.db");
    let expired_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id)
        .join(expired_media.to_string());
    for _ in 0..100 {
        let rows: i64 = Connection::open(&database_path)
            .unwrap()
            .query_row(
                "SELECT COUNT(*) FROM causal_media_staging
                  WHERE family_id = ?1 AND media_uuid = ?2",
                rusqlite::params![family_id, expired_media.to_string()],
                |row| row.get(0),
            )
            .unwrap();
        if rows == 0 {
            assert!(!expired_path.exists());
            return;
        }
        tokio::task::yield_now().await;
    }
    panic!("successful prepare did not trigger detached expired staging GC");
}

#[tokio::test]
async fn slow_causal_media_prepare_streams_to_temp_without_blocking_a_small_commit() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-slow-owner",
        "causal-media-slow-request-0000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let family_id = owner["family_id"].as_str().unwrap().to_owned();
    let baby_id = seed_causal_baby(&rig.app, &token).await;
    let media_id = Uuid::new_v4();
    let bytes = b"slow-streamed-preimage";
    let sha = hex::encode(Sha256::digest(bytes));
    let (first_written_tx, first_written_rx) = oneshot::channel();
    let (release_tx, release_rx) = oneshot::channel();
    let upload_stream = futures_util::stream::unfold(
        (0, Some(first_written_tx), Some(release_rx)),
        |(index, mut first_written, mut release)| async move {
            match index {
                0 => Some((
                    Ok::<_, std::io::Error>(Bytes::from_static(b"slow-")),
                    (1, first_written, release),
                )),
                1 => {
                    first_written.take().unwrap().send(()).ok();
                    release.take().unwrap().await.ok();
                    Some((
                        Ok(Bytes::from_static(b"streamed-preimage")),
                        (2, first_written, release),
                    ))
                }
                _ => None,
            }
        },
    );
    let upload_app = rig.app.clone();
    let upload_token = token.clone();
    let upload = tokio::spawn(async move {
        request_with_headers(
            &upload_app,
            Method::PUT,
            &format!("/v1/causal/media/{media_id}"),
            Some(&upload_token),
            Body::from_stream(upload_stream),
            Some("application/octet-stream"),
            &[("x-lezi-media-sha256", sha.as_str())],
        )
        .await
    });
    first_written_rx.await.unwrap();

    let staging_dir = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(&family_id);
    let incoming = fs::read_dir(&staging_dir)
        .unwrap()
        .filter_map(Result::ok)
        .find(|entry| {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            name.starts_with(".upload-") && name.ends_with(".tmp")
        })
        .expect("first chunk was streamed to a server-owned incoming file");
    tokio::time::timeout(Duration::from_secs(1), async {
        loop {
            if incoming.metadata().unwrap().len() == 5 {
                break;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("first streamed chunk was not flushed to the incoming file");
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let receipt_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(receipt_rows, 0, "partial body minted a durable receipt");

    let commit = tokio::time::timeout(
        Duration::from_millis(500),
        commit_causal_record(
            &rig.app,
            &token,
            baby_id,
            Uuid::new_v4(),
            None,
            "small-during-slow-upload",
        ),
    )
    .await
    .expect("small causal commit waited on a slow media body");
    assert_eq!(commit.0, StatusCode::OK, "{}", commit.1);

    release_tx.send(()).unwrap();
    assert_eq!(upload.await.unwrap().status(), StatusCode::OK);
    let staged_path = staging_dir.join(media_id.to_string());
    assert_eq!(fs::read(staged_path).unwrap(), bytes);
    assert!(!incoming.path().exists());
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn causal_media_promotion_runs_outside_the_same_family_commit_lock() {
    let (hook, events, release) = blocked_prepare_hook("before_promotion");
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
        config.causal_media_commit_blocking_hook = Some(hook);
    });
    let owner = create_family(
        &rig.app,
        "causal-media-promotion-owner",
        "causal-media-promotion-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let baby_id = seed_causal_baby(&rig.app, &token).await;
    let media_id = Uuid::new_v4();
    let bytes = b"blocked-promotion-bytes";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, &token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        Uuid::new_v4(),
        causal_formula_root(baby_id, "media-promotion", 80, 20),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );
    let media_app = rig.app.clone();
    let media_token = token.clone();
    let media_commit =
        tokio::spawn(
            async move { causal_commit_units(&media_app, &media_token, vec![mutation]).await },
        );
    tokio::task::spawn_blocking(move || {
        assert_eq!(
            events.recv_timeout(Duration::from_secs(2)).unwrap(),
            "before_promotion"
        );
    })
    .await
    .unwrap();

    let small_commit = tokio::time::timeout(
        Duration::from_millis(500),
        commit_causal_record(
            &rig.app,
            &token,
            baby_id,
            Uuid::new_v4(),
            None,
            "small-during-media-promotion",
        ),
    )
    .await
    .expect("media promotion retained the same-family commit lock");
    assert_eq!(small_commit.0, StatusCode::OK, "{}", small_commit.1);

    release.release();
    let (status, committed) = media_commit.await.unwrap();
    assert_eq!(status, StatusCode::OK, "{committed}");
}

fn drain_commit_publication_events(
    events: &std::sync::mpsc::Receiver<&'static str>,
) -> Vec<&'static str> {
    let mut phases = Vec::new();
    while let Ok(phase) = events.try_recv() {
        phases.push(phase);
    }
    phases
}

#[tokio::test]
async fn causal_commit_without_media_skips_publication_second_stage() {
    let (hook, events) = recording_commit_hook();
    let rig = Rig::with_config(|config| {
        config.causal_media_commit_blocking_hook = Some(hook);
    });
    let owner = create_family(
        &rig.app,
        "no-media-skip-publish-owner",
        "no-media-skip-publish-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let _ = drain_commit_publication_events(&events);
    let (status, committed) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            Uuid::new_v4(),
            causal_formula_root(baby_id, "formula-no-photo", 80, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(committed["results"][0]["status"], "accepted", "{committed}");
    assert!(
        !drain_commit_publication_events(&events).contains(&"publication"),
        "empty-manifest publish still emits publication"
    );
}

#[tokio::test]
async fn causal_commit_with_media_unit_still_publishes() {
    let (hook, events) = recording_commit_hook();
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
        config.causal_media_commit_blocking_hook = Some(hook);
    });
    let owner = create_family(
        &rig.app,
        "media-still-publish-owner",
        "media-still-publish-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let _ = drain_commit_publication_events(&events);
    let media_id = Uuid::new_v4();
    let bytes = b"still-publish-bytes";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let (status, committed) = causal_commit_units(
        &rig.app,
        token,
        vec![
            causal_unit(
                Uuid::new_v4(),
                None,
                "record",
                Uuid::new_v4(),
                causal_formula_root(baby_id, "text-only", 80, 20),
                vec![],
                false,
            ),
            causal_unit(
                Uuid::new_v4(),
                None,
                "record",
                Uuid::new_v4(),
                causal_formula_root(baby_id, "with-photo", 80, 21),
                vec![causal_media_item(media_id, "log", &sha, bytes.len())],
                false,
            ),
        ],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(committed["results"][0]["status"], "accepted", "{committed}");
    assert_eq!(committed["results"][1]["status"], "accepted", "{committed}");
    assert_eq!(
        drain_commit_publication_events(&events),
        ["before_promotion", "publication", "after_promotion"]
    );
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let publication_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM media_publications
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(publication_rows, 1);
}

#[tokio::test]
async fn causal_commit_exact_replay_without_media_returns_receipt_only() {
    let (hook, events) = recording_commit_hook();
    let rig = Rig::with_config(|config| {
        config.causal_media_commit_blocking_hook = Some(hook);
    });
    let owner = create_family(
        &rig.app,
        "replay-no-media-owner",
        "replay-no-media-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        Uuid::new_v4(),
        causal_formula_root(baby_id, "replay-formula", 80, 20),
        vec![],
        false,
    );
    let (status, first) = causal_commit_units(&rig.app, token, vec![mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{first}");
    assert_eq!(first["results"][0]["status"], "accepted", "{first}");
    let version = first["results"][0]["stable"]["version_id"]
        .as_str()
        .expect("accepted no-media commit has a stable version")
        .to_owned();
    let _ = drain_commit_publication_events(&events);
    let (status, replay) = causal_commit_units(&rig.app, token, vec![mutation]).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["results"][0]["status"], "accepted", "{replay}");
    assert_eq!(replay["results"][0]["replay"], true, "{replay}");
    assert_eq!(
        replay["results"][0]["stable"]["version_id"].as_str(),
        Some(version.as_str()),
        "{replay}"
    );
    assert!(
        !drain_commit_publication_events(&events).contains(&"publication"),
        "no-media replay entered publication"
    );
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let publication_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM media_publications WHERE family_id = ?1",
            rusqlite::params![family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(publication_rows, 0);
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn causal_media_branch_commit_and_resolution_serialize_one_publication() {
    let (hook, events, release) = blocked_prepare_hook("before_promotion");
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
        config.causal_media_commit_blocking_hook = Some(hook);
    });
    let (owner, member) = two_joined_clients(
        &rig.app,
        "publication-race-owner",
        "publication-race-member",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap().to_owned();
    let member_token = member["access_token"].as_str().unwrap().to_owned();
    let generation = owner["generation"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, &owner_token).await;
    let record_id = Uuid::new_v4();
    let (status, created) = causal_commit_units(
        &rig.app,
        &member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "publication-base", 80, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let base_version = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, stable) = causal_commit_units(
        &rig.app,
        &owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&base_version),
            "record",
            record_id,
            causal_formula_root(baby_id, "publication-stable", 80, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{stable}");

    let media_id = Uuid::new_v4();
    let bytes = b"serialized-branch-publication";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, prepared) = put_causal_media_bytes(&rig.app, &member_token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let branch_mutation_id = Uuid::new_v4();
    let branch_mutation = causal_unit(
        branch_mutation_id,
        Some(&base_version),
        "record",
        record_id,
        causal_formula_root(baby_id, "publication-branch", 80, 40),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );
    let branch_app = rig.app.clone();
    let branch_token = member_token.clone();
    let branch_request = branch_mutation.clone();
    let branch_commit = tokio::spawn(async move {
        causal_commit_units(&branch_app, &branch_token, vec![branch_request]).await
    });
    tokio::task::spawn_blocking(move || {
        assert_eq!(
            events.recv_timeout(Duration::from_secs(2)).unwrap(),
            "before_promotion"
        );
    })
    .await
    .unwrap();

    let pull = pull_entities(&rig.app, &owner_token, generation).await;
    let conflict_id = find_entity(&pull, record_id)["conflict_summary"]["conflict_id"]
        .as_str()
        .expect("durable branch is visible while publication waits")
        .to_owned();
    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(&owner_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    let branch_version = detail["branches"][0]["version_id"]
        .as_str()
        .expect("branch version")
        .to_owned();
    let choices = detail["conflicting"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| {
            let candidate = item["candidates"]
                .as_array()
                .unwrap()
                .iter()
                .find(|candidate| {
                    candidate["sources"].as_array().is_some_and(|sources| {
                        sources
                            .iter()
                            .any(|source| source["version_id"] == branch_version)
                    })
                })
                .unwrap_or_else(|| panic!("branch choice missing: {item}"));
            json!({
                "path": item["path"],
                "choice_id": candidate["choice_id"]
            })
        })
        .collect::<Vec<_>>();
    let resolve_app = rig.app.clone();
    let resolve_token = owner_token.clone();
    let resolve_path = format!("/v1/conflicts/{conflict_id}/resolve");
    let resolution_mutation_id = Uuid::new_v4();
    let resolve_mutation_id = resolution_mutation_id;
    let mut resolve = tokio::spawn(async move {
        json_request(
            &resolve_app,
            Method::POST,
            &resolve_path,
            Some(&resolve_token),
            json!({
                "snapshot_token": detail["snapshot_token"],
                "resolution_mutation_id": resolve_mutation_id.to_string(),
                "choices": choices
            }),
        )
        .await
    });
    let database_path = rig.directory.path().join("lezi.db");
    let resolved_family = family_id.to_owned();
    let resolved_conflict = conflict_id.clone();
    tokio::task::spawn_blocking(move || {
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        loop {
            let connection = Connection::open(&database_path).unwrap();
            let (status, terminal_rows): (String, i64) = connection
                .query_row(
                    "SELECT c.status,
                            (SELECT COUNT(*) FROM conflict_resolutions r
                              WHERE r.family_id = c.family_id
                                AND r.conflict_id = c.conflict_id
                                AND r.resolution_mutation_id = ?3)
                       FROM conflicts c
                      WHERE c.family_id = ?1 AND c.conflict_id = ?2",
                    rusqlite::params![
                        resolved_family,
                        resolved_conflict,
                        resolution_mutation_id.to_string(),
                    ],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            if status == "resolved" && terminal_rows == 1 {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "resolution did not durably commit before publication"
            );
            std::thread::yield_now();
        }
    })
    .await
    .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(100), &mut resolve)
            .await
            .is_err(),
        "resolution bypassed the Store-owned publication serialization"
    );

    release.release();
    let (branch_status, branch) = branch_commit.await.unwrap();
    assert_eq!(branch_status, StatusCode::OK, "{branch}");
    assert_eq!(branch["results"][0]["status"], "branched", "{branch}");
    assert_eq!(
        branch["results"][0]["branch_version_id"], branch_version,
        "{branch}"
    );
    let (resolve_status, resolved) = resolve.await.unwrap();
    assert_eq!(resolve_status, StatusCode::OK, "{resolved}");
    assert_eq!(resolved["status"], "accepted", "{resolved}");
    assert_eq!(
        resolved["stable_media"][0]["media_uuid"],
        media_id.to_string()
    );

    let (replay_status, replay) =
        causal_commit_units(&rig.app, &member_token, vec![branch_mutation]).await;
    assert_eq!(replay_status, StatusCode::OK, "{replay}");
    assert_eq!(replay["results"][0]["status"], "branched", "{replay}");
    assert_eq!(replay["results"][0]["branch_version_id"], branch_version);
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(&owner_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let published = response.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(published.as_ref(), bytes);

    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let version_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM entity_versions
             WHERE family_id = ?1 AND mutation_id = ?2",
            rusqlite::params![family_id, branch_mutation_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    let publication_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM media_publications
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(version_rows, 1);
    assert_eq!(publication_rows, 1);
}

#[tokio::test]
async fn causal_media_prepare_inflight_admission_bounds_and_releases_cancelled_streams() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-admission-owner",
        "causal-media-admission-request-0000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let family_id = owner["family_id"].as_str().unwrap();
    let sha = hex::encode(Sha256::digest(b"xy"));
    let mut uploads = Vec::new();
    let mut releases = Vec::new();
    for _ in 0..2 {
        let media_id = Uuid::new_v4();
        let (started_tx, started_rx) = oneshot::channel();
        let (release_tx, release_rx) = oneshot::channel();
        let stream = futures_util::stream::unfold(
            (0, Some(started_tx), Some(release_rx)),
            |(index, mut started, mut release)| async move {
                match index {
                    0 => Some((
                        Ok::<_, std::io::Error>(Bytes::from_static(b"x")),
                        (1, started, release),
                    )),
                    1 => {
                        started.take().unwrap().send(()).ok();
                        release.take().unwrap().await.ok();
                        Some((Ok(Bytes::from_static(b"y")), (2, started, release)))
                    }
                    _ => None,
                }
            },
        );
        let app = rig.app.clone();
        let upload_token = token.clone();
        let upload_sha = sha.clone();
        uploads.push(tokio::spawn(async move {
            request_with_headers(
                &app,
                Method::PUT,
                &format!("/v1/causal/media/{media_id}"),
                Some(&upload_token),
                Body::from_stream(stream),
                Some("application/octet-stream"),
                &[("x-lezi-media-sha256", upload_sha.as_str())],
            )
            .await
        }));
        releases.push(release_tx);
        started_rx.await.unwrap();
    }

    let rejected_id = Uuid::new_v4();
    let rejected = tokio::time::timeout(
        Duration::from_millis(500),
        request_with_headers(
            &rig.app,
            Method::PUT,
            &format!("/v1/causal/media/{rejected_id}"),
            Some(&token),
            Body::from(Bytes::from_static(b"xy")),
            Some("application/octet-stream"),
            &[("x-lezi-media-sha256", sha.as_str())],
        ),
    )
    .await
    .expect("saturated admission waited for a slow body");
    assert_eq!(rejected.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(rejected.headers()["retry-after"], "1");

    uploads[0].abort();
    assert!(uploads.remove(0).await.unwrap_err().is_cancelled());
    drop(releases.remove(0));
    let staging_dir = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id);
    assert_eq!(
        fs::read_dir(&staging_dir)
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                name.starts_with(".upload-") && name.ends_with(".tmp")
            })
            .count(),
        1,
        "cancelled upload kept its temp reservation"
    );
    let durable_inflight_uploads: i64 = Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_uploads WHERE family_id = ?1",
            rusqlite::params![family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(
        durable_inflight_uploads, 2,
        "cancelled and still-active bodies must both remain GC-addressable"
    );

    let replacement_id = Uuid::new_v4();
    let replacement = request_with_headers(
        &rig.app,
        Method::PUT,
        &format!("/v1/causal/media/{replacement_id}"),
        Some(&token),
        Body::from(Bytes::from_static(b"xy")),
        Some("application/octet-stream"),
        &[("x-lezi-media-sha256", sha.as_str())],
    )
    .await;
    assert_eq!(replacement.status(), StatusCode::OK);

    releases.remove(0).send(()).unwrap();
    assert_eq!(uploads.remove(0).await.unwrap().status(), StatusCode::OK);
    let durable_cancelled_uploads: i64 = Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM causal_media_uploads WHERE family_id = ?1",
            rusqlite::params![family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(durable_cancelled_uploads, 1);
}

#[tokio::test]
async fn cancelled_prepare_keeps_admission_and_temp_owned_until_verification_finishes() {
    let (hook, events, release) = blocked_prepare_hook("before_verify");
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
        config.causal_media_prepare_blocking_hook = Some(hook);
    });
    let owner = create_family(
        &rig.app,
        "causal-media-verify-cancel-owner",
        "causal-media-verify-cancel-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let family_id = owner["family_id"].as_str().unwrap();
    let media_ids = [Uuid::new_v4(), Uuid::new_v4()];
    let mut uploads = media_ids
        .iter()
        .map(|media_id| spawn_causal_media_put(&rig.app, &token, *media_id))
        .collect::<Vec<_>>();
    let events = tokio::task::spawn_blocking(move || {
        for _ in 0..2 {
            assert_eq!(
                events.recv_timeout(Duration::from_secs(2)).unwrap(),
                "before_verify"
            );
        }
        events
    })
    .await
    .unwrap();

    for upload in &uploads {
        upload.abort();
    }
    for upload in uploads.drain(..) {
        assert!(upload.await.unwrap_err().is_cancelled());
    }
    let staging_dir = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id);
    assert_eq!(
        fs::read_dir(&staging_dir)
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                name.starts_with(".upload-") && name.ends_with(".tmp")
            })
            .count(),
        2,
        "cancel detached the verifier from its temp owner"
    );
    let sha = hex::encode(Sha256::digest(b"xy"));
    let saturated = tokio::time::timeout(
        Duration::from_millis(500),
        request_with_headers(
            &rig.app,
            Method::PUT,
            &format!("/v1/causal/media/{}", Uuid::new_v4()),
            Some(&token),
            Body::from(b"xy".to_vec()),
            Some("application/octet-stream"),
            &[("x-lezi-media-sha256", sha.as_str())],
        ),
    )
    .await
    .expect("verification cancellation released admission into the blocked hook");
    assert_eq!(saturated.status(), StatusCode::TOO_MANY_REQUESTS);

    release.release();
    tokio::task::spawn_blocking(move || {
        let mut completed = 0;
        while completed < 2 {
            if events.recv_timeout(Duration::from_secs(2)).unwrap() == "after_store" {
                completed += 1;
            }
        }
    })
    .await
    .unwrap();
    let replacement_id = Uuid::new_v4();
    assert_eq!(
        spawn_causal_media_put(&rig.app, &token, replacement_id)
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        fs::read_dir(&staging_dir)
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                name.starts_with(".upload-") && name.ends_with(".tmp")
            })
            .count(),
        0
    );
}

#[tokio::test]
async fn cancelled_prepare_keeps_family_lock_and_admission_until_store_finishes() {
    let (hook, events, release) = blocked_prepare_hook("before_store");
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
        config.causal_media_prepare_blocking_hook = Some(hook);
    });
    let owner = create_family(
        &rig.app,
        "causal-media-store-cancel-owner",
        "causal-media-store-cancel-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let family_id = owner["family_id"].as_str().unwrap().to_owned();
    let baby_id = seed_causal_baby(&rig.app, &token).await;
    let media_ids = [Uuid::new_v4(), Uuid::new_v4()];
    let mut uploads = media_ids
        .iter()
        .map(|media_id| spawn_causal_media_put(&rig.app, &token, *media_id))
        .collect::<Vec<_>>();
    let events = tokio::task::spawn_blocking(move || {
        let mut verifying = 0;
        let mut storing = 0;
        while verifying < 2 || storing < 1 {
            match events.recv_timeout(Duration::from_secs(2)).unwrap() {
                "before_verify" => verifying += 1,
                "before_store" => storing += 1,
                _ => {}
            }
        }
        events
    })
    .await
    .unwrap();

    for upload in &uploads {
        upload.abort();
    }
    for upload in uploads.drain(..) {
        assert!(upload.await.unwrap_err().is_cancelled());
    }
    let sha = hex::encode(Sha256::digest(b"xy"));
    let saturated = tokio::time::timeout(
        Duration::from_millis(500),
        request_with_headers(
            &rig.app,
            Method::PUT,
            &format!("/v1/causal/media/{}", Uuid::new_v4()),
            Some(&token),
            Body::from(b"xy".to_vec()),
            Some("application/octet-stream"),
            &[("x-lezi-media-sha256", sha.as_str())],
        ),
    )
    .await
    .expect("Store cancellation released admission into the blocked hook");
    assert_eq!(saturated.status(), StatusCode::TOO_MANY_REQUESTS);
    let commit_app = rig.app.clone();
    let commit_token = token.clone();
    let mut commit = tokio::spawn(async move {
        commit_causal_record(
            &commit_app,
            &commit_token,
            baby_id,
            Uuid::new_v4(),
            None,
            "small-during-cancelled-store",
        )
        .await
    });
    assert!(
        tokio::time::timeout(Duration::from_millis(100), &mut commit)
            .await
            .is_err(),
        "cancelled handler released the family lock before Store finished"
    );

    release.release();
    tokio::task::spawn_blocking(move || {
        let mut completed = 0;
        while completed < 2 {
            if events.recv_timeout(Duration::from_secs(2)).unwrap() == "after_store" {
                completed += 1;
            }
        }
    })
    .await
    .unwrap();
    let committed = commit.await.unwrap();
    assert_eq!(committed.0, StatusCode::OK, "{}", committed.1);
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let staged: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
             WHERE family_id = ?1 AND status = 'staged'",
            rusqlite::params![family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(staged, 2, "detached Store work split receipt/file state");
    for media_id in media_ids {
        assert_eq!(
            fs::read(
                rig.directory
                    .path()
                    .join("media/.causal-stage")
                    .join(&family_id)
                    .join(media_id.to_string())
            )
            .unwrap(),
            b"xy"
        );
    }
}

#[tokio::test]
async fn causal_media_prepare_exact_replay_repairs_same_size_staged_corruption() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-corruption-owner",
        "causal-media-corruption-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let media_id = Uuid::new_v4();
    let bytes = b"verified-preimage";
    let corrupt = b"corrupt-preimage!";
    assert_eq!(bytes.len(), corrupt.len());
    let (status, first) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{first}");
    let staged_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id)
        .join(media_id.to_string());
    fs::write(&staged_path, corrupt).unwrap();

    let (status, replay) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;

    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay, first, "exact replay changed its receipt semantics");
    assert_eq!(fs::read(staged_path).unwrap(), bytes);
}

#[tokio::test]
async fn causal_media_empty_put_binds_new_uuid_to_consumed_family_blob() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-empty-bind-owner",
        "causal-media-empty-bind-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let donor_id = Uuid::new_v4();
    let bytes = b"family-empty-bind";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, staged) = put_causal_media_bytes(&rig.app, token, donor_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{staged}");
    let donor_record = Uuid::new_v4();
    let (status, committed) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            donor_record,
            causal_formula_root(baby_id, "donor", 80, 20),
            vec![causal_media_item(donor_id, "log", &sha, bytes.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(committed["results"][0]["status"], "accepted");

    let replay = put_causal_media_empty(&rig.app, token, donor_id, &sha).await;
    assert_eq!(replay.0, StatusCode::OK, "{}", replay.1);
    assert_eq!(replay.1["media_uuid"], donor_id.to_string());
    assert_eq!(replay.1["status"], "consumed");
    assert_eq!(replay.1["sha256"], sha);
    assert_eq!(replay.1["byte_size"], bytes.len());

    let bound_id = Uuid::new_v4();
    let (status, bound) = put_causal_media_empty(&rig.app, token, bound_id, &sha).await;
    assert_eq!(status, StatusCode::OK, "{bound}");
    assert_eq!(bound["media_uuid"], bound_id.to_string());
    assert_eq!(bound["status"], "staged");
    assert_eq!(bound["sha256"], sha);
    assert_eq!(bound["byte_size"], bytes.len());
    let donor_published = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(donor_id.to_string());
    let bound_staged = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id)
        .join(bound_id.to_string());
    assert_eq!(fs::read(&donor_published).unwrap(), bytes);
    assert_eq!(fs::read(&bound_staged).unwrap(), bytes);
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        assert_eq!(
            fs::metadata(&donor_published).unwrap().ino(),
            fs::metadata(&bound_staged).unwrap().ino()
        );
    }

    let clone_record = Uuid::new_v4();
    let (status, accepted) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            clone_record,
            causal_formula_root(baby_id, "clone", 90, 30),
            vec![causal_media_item(bound_id, "log", &sha, bytes.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{bound_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        bytes.as_slice()
    );
}

#[tokio::test]
async fn causal_media_empty_put_miss_then_full_put_commits() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-empty-miss-owner",
        "causal-media-empty-miss-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let media_id = Uuid::new_v4();
    let bytes = b"absent-family-blob";
    let sha = hex::encode(Sha256::digest(bytes));

    let (status, body) = put_causal_media_empty(&rig.app, token, media_id, &sha).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
    assert_ne!(body["status"], "staged");
    assert_ne!(body["status"], "consumed");

    let (status, staged) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{staged}");
    assert_eq!(staged["status"], "staged");
    assert_eq!(staged["sha256"], sha);
    assert_eq!(staged["byte_size"], bytes.len());

    let record_id = Uuid::new_v4();
    let (status, accepted) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "first-put", 80, 20),
            vec![causal_media_item(media_id, "log", &sha, bytes.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    assert_eq!(accepted["results"][0]["status"], "accepted");
}

#[tokio::test]
async fn causal_media_prepare_restart_replay_preserves_the_exact_durable_receipt() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-restart-owner",
        "causal-media-restart-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let media_id = Uuid::new_v4();
    let bytes = b"restart-durable-preimage";
    let first = put_causal_media_raw(&rig.app, token, media_id, bytes).await;
    assert_eq!(first.status(), StatusCode::OK);
    let first_bytes = first.into_body().collect().await.unwrap().to_bytes();
    let database_path = rig.directory.path().join("lezi.db");
    let durable_receipt = || {
        let connection = Connection::open(&database_path).unwrap();
        connection
            .query_row(
                "SELECT COUNT(*), membership_id, sha256, byte_size,
                        created_at, expires_at, status
                 FROM causal_media_staging
                 WHERE family_id = ?1 AND media_uuid = ?2",
                rusqlite::params![family_id, media_id.to_string()],
                |row| {
                    Ok((
                        row.get::<_, i64>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, i64>(4)?,
                        row.get::<_, i64>(5)?,
                        row.get::<_, String>(6)?,
                    ))
                },
            )
            .unwrap()
    };
    let before_restart = durable_receipt();
    assert_eq!(before_restart.0, 1);
    assert_eq!(before_restart.6, "staged");
    let staged_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id)
        .join(media_id.to_string());
    let published_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(media_id.to_string());
    assert_eq!(fs::read(&staged_path).unwrap(), bytes);
    assert!(!published_path.exists());

    let restarted = rig.restart_with_config("generation-a", |config| {
        config.max_media_bytes = 64 * 1024;
    });
    let replay = put_causal_media_raw(&restarted, token, media_id, bytes).await;
    assert_eq!(replay.status(), StatusCode::OK);
    let replay_bytes = replay.into_body().collect().await.unwrap().to_bytes();

    assert_eq!(replay_bytes, first_bytes);
    assert_eq!(durable_receipt(), before_restart);
    assert_eq!(fs::read(staged_path).unwrap(), bytes);
    assert!(!published_path.exists());
}

#[tokio::test]
async fn causal_media_startup_and_commit_gc_expired_staging_without_failing_family_commit() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-gc-owner",
        "causal-media-gc-request-000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let media_id = Uuid::new_v4();
    assert_eq!(
        put_causal_media_raw(&rig.app, token, media_id, b"expired")
            .await
            .status(),
        StatusCode::OK
    );
    let staged_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id)
        .join(media_id.to_string());
    fs::remove_file(&staged_path).unwrap();
    fs::create_dir(&staged_path).unwrap();
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging SET expires_at = ?1
             WHERE family_id = ?2 AND media_uuid = ?3",
            rusqlite::params![
                rig.now.load(Ordering::SeqCst),
                family_id,
                media_id.to_string()
            ],
        )
        .unwrap();
    drop(connection);

    let (status, body, _) = commit_causal_record(
        &rig.app,
        token,
        baby_id,
        Uuid::new_v4(),
        None,
        "gc-failure-does-not-fail-commit",
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let pending = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
            let status: String = connection
                .query_row(
                    "SELECT status FROM causal_media_staging
                     WHERE family_id = ?1 AND media_uuid = ?2",
                    rusqlite::params![family_id, media_id.to_string()],
                    |row| row.get(0),
                )
                .unwrap();
            if status == "gc_pending" {
                break status;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("detached bounded GC did not mark its expired candidate");
    assert_eq!(pending, "gc_pending");

    fs::remove_dir(&staged_path).unwrap();
    fs::write(&staged_path, b"expired").unwrap();
    let restarted = rig.restart_with_config("generation-a", |config| {
        config.max_media_bytes = 64 * 1024;
    });
    assert!(!staged_path.exists());
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let remaining: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, media_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(remaining, 0);
    assert_eq!(
        request(
            &restarted,
            Method::GET,
            "/health",
            None,
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::OK
    );
}

#[tokio::test]
async fn causal_media_preimage_replay_conflict_and_restart_publication_are_stable() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-lifecycle-owner",
        "causal-media-lifecycle-request-0000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let bytes = b"durable-preimage";
    let sha = hex::encode(Sha256::digest(bytes));

    for _ in 0..2 {
        let (status, staged) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
        assert_eq!(status, StatusCode::OK, "{staged}");
        assert_eq!(staged["status"], "staged");
        assert_eq!(staged["sha256"], sha);
    }
    let final_path = rig
        .directory
        .path()
        .join("media")
        .join(owner["family_id"].as_str().unwrap())
        .join(media_id.to_string());
    let staged_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(owner["family_id"].as_str().unwrap())
        .join(media_id.to_string());
    assert!(
        !final_path.exists(),
        "unaccepted bytes reached published path"
    );
    assert_eq!(fs::read(&staged_path).unwrap(), bytes);

    let different = b"durable-preimagf";
    assert_eq!(different.len(), bytes.len());
    let (status, conflict) = put_causal_media_bytes(&rig.app, token, media_id, different).await;
    assert_eq!(status, StatusCode::CONFLICT, "{conflict}");
    assert_eq!(fs::read(&staged_path).unwrap(), bytes);

    let (status, committed) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "published", 80, 20),
            vec![causal_media_item(media_id, "log", &sha, bytes.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(committed["results"][0]["status"], "accepted");
    assert_eq!(fs::read(&final_path).unwrap(), bytes);
    assert!(
        !staged_path.exists(),
        "consumed staging bytes were not removed"
    );

    // Crash fixture: the DB consume is durable, while filesystem promotion did
    // not finish. Startup must converge before exposing public routes.
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "DELETE FROM media_publications
              WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![owner["family_id"].as_str().unwrap(), media_id.to_string()],
        )
        .unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging SET publication_confirmed = 0
              WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'consumed'",
            rusqlite::params![owner["family_id"].as_str().unwrap(), media_id.to_string()],
        )
        .unwrap();
    drop(connection);
    fs::create_dir_all(staged_path.parent().unwrap()).unwrap();
    fs::rename(&final_path, &staged_path).unwrap();
    assert!(!final_path.exists());
    let restarted = rig.restart_with_config("generation-a", |config| {
        config.max_media_bytes = 64 * 1024;
    });
    assert_eq!(fs::read(&final_path).unwrap(), bytes);
    assert!(!staged_path.exists());
    let response = request(
        &restarted,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        bytes.as_slice()
    );
}

#[tokio::test]
async fn causal_media_receipt_claim_survives_promotion_fault_and_replays_one_version() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-promotion-owner",
        "causal-media-promotion-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let bytes = b"promotion-preimage";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, staged) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{staged}");

    let final_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(media_id.to_string());
    let staged_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(family_id)
        .join(media_id.to_string());
    fs::create_dir_all(&final_path).unwrap();
    let mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "record",
        record_id,
        causal_formula_root(baby_id, "promotion-pending", 80, 20),
        vec![causal_media_item(media_id, "log", &sha, bytes.len())],
        false,
    );

    let (status, failed) = causal_commit_units(&rig.app, token, vec![mutation.clone()]).await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR, "{failed}");
    assert_eq!(fs::read(&staged_path).unwrap(), bytes);
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let (durable_version, version_count, consumed_count): (String, i64, i64) = connection
        .query_row(
            "SELECT r.stable_version_id,
                    (SELECT COUNT(*) FROM entity_versions v
                      WHERE v.family_id = r.family_id
                        AND v.entity_type = r.entity_type
                        AND v.client_uuid = r.client_uuid),
                    (SELECT COUNT(*) FROM causal_media_staging s
                      WHERE s.family_id = r.family_id AND s.media_uuid = ?1
                        AND s.status = 'consumed')
               FROM mutation_receipts r
              WHERE r.mutation_id = ?2",
            [
                media_id.to_string(),
                mutation["mutation_id"].as_str().unwrap().to_owned(),
            ],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    assert_eq!(version_count, 1);
    assert_eq!(consumed_count, 1);
    drop(connection);
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let pending_pull = pull_entities(&rig.app, token, generation).await;
    assert!(pending_pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .all(|row| {
            row["client_uuid"] != record_id.to_string()
                && row["client_uuid"] != media_id.to_string()
        }));

    fs::remove_dir(&final_path).unwrap();
    let (status, prepared_replay) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared_replay}");
    assert_eq!(prepared_replay["status"], "consumed");
    let (status, committed) = causal_commit_units(&rig.app, token, vec![mutation]).await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(committed["results"][0]["status"], "accepted");
    assert_eq!(
        committed["results"][0]["stable"]["version_id"],
        durable_version
    );
    assert_eq!(fs::read(&final_path).unwrap(), bytes);
    assert!(!staged_path.exists());
    let version_count: i64 = Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM entity_versions
              WHERE entity_type = 'record' AND client_uuid = ?1",
            [record_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(version_count, 1);
    let converged_pull = pull_entities(&rig.app, token, generation).await;
    let converged_ids = converged_pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|row| row["client_uuid"].as_str())
        .collect::<BTreeSet<_>>();
    assert!(converged_ids.contains(record_id.to_string().as_str()));
    assert!(converged_ids.contains(media_id.to_string().as_str()));
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        bytes.as_slice()
    );
}

#[tokio::test]
async fn expired_causal_media_preimage_is_rejected_and_collected_on_restart() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-media-expiry-owner",
        "causal-media-expiry-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, token).await;
    let media_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let bytes = b"expiring-preimage";
    let sha = hex::encode(Sha256::digest(bytes));
    let (status, staged) = put_causal_media_bytes(&rig.app, token, media_id, bytes).await;
    assert_eq!(status, StatusCode::OK, "{staged}");
    let staged_path = rig
        .directory
        .path()
        .join("media/.causal-stage")
        .join(owner["family_id"].as_str().unwrap())
        .join(media_id.to_string());
    assert!(staged_path.exists());

    rig.now.fetch_add(24 * 60 * 60 + 1, Ordering::SeqCst);
    let (refresh_status, refreshed) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": owner["refresh_token"]}),
    )
    .await;
    assert_eq!(refresh_status, StatusCode::OK, "{refreshed}");
    let token = refreshed["access_token"].as_str().unwrap();
    let (status, rejected) = causal_commit_units(
        &rig.app,
        token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "expired", 80, 20),
            vec![causal_media_item(media_id, "log", &sha, bytes.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected");
    let expired_code = rejected["error"]["code"].as_str().unwrap_or_default();
    assert!(
        expired_code == "media_preimage_expired" || expired_code == "missing_media_bytes",
        "expired receipt must stay a media claim code, got {expired_code}"
    );

    let _restarted = rig.restart_with_config("generation-a", |config| {
        config.max_media_bytes = 64 * 1024;
    });
    assert!(!staged_path.exists());
    assert!(!rig
        .directory
        .path()
        .join("media")
        .join(owner["family_id"].as_str().unwrap())
        .join(media_id.to_string())
        .exists());
}

async fn two_joined_clients(
    app: &Router,
    owner_device: &str,
    member_device: &str,
) -> (Value, Value) {
    let owner = create_family(
        app,
        owner_device,
        &format!("{owner_device}-create-request-000000000001"),
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(app, owner_token, member_device).await;
    (owner, member)
}

#[tokio::test]
async fn two_clients_incrementally_pull_lossless_sidecar_pages_across_restart() {
    async fn pull_page_bytes(
        app: &Router,
        token: &str,
        generation: &str,
        cursor: i64,
    ) -> (Bytes, Value) {
        let response = request(
            app,
            Method::GET,
            &format!("/v1/pull?cursor={cursor}&generation={generation}&page_index=0"),
            Some(token),
            Body::empty(),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        assert!(
            bytes.len() <= 8 * 1024 * 1024,
            "final pull envelope exceeded byte budget: {}",
            bytes.len()
        );
        let value = serde_json::from_slice(&bytes).unwrap();
        (bytes, value)
    }

    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "sidecar-page-owner", "sidecar-page-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let records = (0..33).map(|_| Uuid::new_v4()).collect::<Vec<_>>();

    let (status, created) = causal_commit_units(
        &rig.app,
        member_token,
        records
            .iter()
            .enumerate()
            .map(|(index, record_id)| {
                causal_unit(
                    Uuid::new_v4(),
                    None,
                    "record",
                    *record_id,
                    causal_formula_root(
                        baby_id,
                        &format!("base-{index}"),
                        50 + index as i64,
                        20 + index as i64,
                    ),
                    vec![],
                    false,
                )
            })
            .collect(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let base_versions = created["results"]
        .as_array()
        .unwrap()
        .iter()
        .map(|result| {
            assert_eq!(result["status"], "accepted", "{result}");
            result["stable"]["version_id"].as_str().unwrap().to_owned()
        })
        .collect::<Vec<_>>();

    let (status, accepted) = causal_commit_units(
        &rig.app,
        owner_token,
        records
            .iter()
            .enumerate()
            .map(|(index, record_id)| {
                causal_unit(
                    Uuid::new_v4(),
                    Some(&base_versions[index]),
                    "record",
                    *record_id,
                    causal_formula_root(
                        baby_id,
                        &format!("stable-{index}"),
                        50 + index as i64,
                        100 + index as i64,
                    ),
                    vec![],
                    false,
                )
            })
            .collect(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{accepted}");
    let stable_versions = accepted["results"]
        .as_array()
        .unwrap()
        .iter()
        .map(|result| {
            assert_eq!(result["status"], "accepted", "{result}");
            result["stable"]["version_id"].as_str().unwrap().to_owned()
        })
        .collect::<Vec<_>>();
    let owner_display = Uuid::new_v4();
    let (status, owner_display_created) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            owner_display,
            causal_formula_root(baby_id, "owner-display", 50, 99),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_display_created}");
    let owner_display_version = owner_display_created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (_, baseline) = pull_page_bytes(&rig.app, owner_token, generation, 0).await;
    let baseline_cursor = baseline["cursor"].as_i64().unwrap();

    let (status, branched) = causal_commit_units(
        &rig.app,
        member_token,
        records
            .iter()
            .enumerate()
            .map(|(index, record_id)| {
                causal_unit(
                    Uuid::new_v4(),
                    Some(&base_versions[index]),
                    "record",
                    *record_id,
                    causal_formula_root(
                        baby_id,
                        &format!("branch-{index}"),
                        50 + index as i64,
                        200 + index as i64,
                    ),
                    vec![],
                    false,
                )
            })
            .collect(),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{branched}");
    assert!(branched["results"]
        .as_array()
        .unwrap()
        .iter()
        .all(|result| result["status"] == "branched"));
    let mut relation_members = records.iter().map(Uuid::to_string).collect::<Vec<_>>();
    relation_members.push(owner_display.to_string());
    let mut relation_versions = records
        .iter()
        .zip(&stable_versions)
        .map(|(record_id, version)| (record_id.to_string(), version.clone()))
        .collect::<std::collections::BTreeMap<_, _>>();
    relation_versions.insert(owner_display.to_string(), owner_display_version);
    let (status, relation) = json_request(
        &rig.app,
        Method::POST,
        "/v1/source-relations/resolve-group",
        Some(owner_token),
        json!({
            "mutation_id": "sidecar-page-relation",
            "member_client_uuids": relation_members,
            "display_client_uuid": owner_display,
            "expected_versions": relation_versions
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{relation}");
    assert_eq!(relation["status"], "accepted");

    let (first_bytes, first) =
        pull_page_bytes(&rig.app, owner_token, generation, baseline_cursor).await;
    assert_eq!(first["has_more"], true, "{first}");
    let (retry_bytes, retry) =
        pull_page_bytes(&rig.app, owner_token, generation, baseline_cursor).await;
    assert_eq!(
        retry_bytes, first_bytes,
        "page restart must be deterministic"
    );
    assert_eq!(retry, first);

    let restarted = rig.restart(generation);
    let first_cursor = first["cursor"].as_i64().unwrap();
    let (_, second) = pull_page_bytes(&restarted, owner_token, generation, first_cursor).await;
    assert_eq!(second["has_more"], false, "{second}");
    assert!(second["cursor"].as_i64().unwrap() > first_cursor);

    let entities = first["entities"]
        .as_array()
        .unwrap()
        .iter()
        .chain(second["entities"].as_array().unwrap())
        .collect::<Vec<_>>();
    let summaries = entities
        .iter()
        .filter_map(|entity| {
            entity
                .get("conflict_summary")
                .map(|_| entity["client_uuid"].as_str().unwrap().to_owned())
        })
        .collect::<BTreeSet<_>>();
    assert_eq!(summaries.len(), records.len());
    assert!(records
        .iter()
        .all(|record_id| summaries.contains(record_id.to_string().as_str())));
    for relation_member in [owner_display, records[32]] {
        let entity = entities
            .iter()
            .find(|entity| entity["client_uuid"] == relation_member.to_string())
            .expect("relation member was not re-emitted");
        assert!(entity.get("source_relation_summary").is_some());
    }
}

async fn pull_entities(app: &Router, token: &str, generation: &str) -> Value {
    let (status, body) = get_json(
        app,
        &format!("/v1/pull?cursor=0&generation={generation}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    body
}

fn find_entity(pull: &Value, client_uuid: Uuid) -> &Value {
    pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == client_uuid.to_string())
        .unwrap_or_else(|| panic!("entity {client_uuid} missing from pull: {pull}"))
}

fn conflict_summary_is_closed(row: &Value) {
    // Wire: after resolution, ordinary pull must not surface an open conflict_summary.
    match row.get("conflict_summary") {
        None => {}
        Some(Value::Null) => {}
        Some(other) => panic!("open conflict_summary after resolve: {other}"),
    }
}

fn conflict_summary_branch_ids(row: &Value) -> Vec<String> {
    row["conflict_summary"]["branch_version_ids"]
        .as_array()
        .unwrap_or_else(|| panic!("conflict_summary.branch_version_ids missing: {row}"))
        .iter()
        .map(|id| {
            id.as_str()
                .unwrap_or_else(|| panic!("branch_version_id not a string: {id}"))
                .to_owned()
        })
        .collect()
}

async fn withdraw_conflict_branches(
    app: &Router,
    token: &str,
    conflict_id: &str,
    expected_stable_version_id: &str,
    expected_branch_version_ids: &[String],
) -> (StatusCode, Value) {
    json_request(
        app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/withdraw"),
        Some(token),
        json!({
            "withdrawal_mutation_id": Uuid::new_v4().to_string(),
            "expected_stable_version_id": expected_stable_version_id,
            "expected_branch_version_ids": expected_branch_version_ids,
        }),
    )
    .await
}

async fn seed_member_formula_and_owner_stable(
    app: &Router,
    owner_token: &str,
    member_token: &str,
) -> (Uuid, Uuid, String, String) {
    let baby_id = seed_causal_baby(app, owner_token).await;
    let record_id = Uuid::new_v4();
    let (status, created) = causal_commit_units(
        app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "stable-note", 90, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, owner_edit) = causal_commit_units(
        app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "house-stable", 90, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_edit}");
    assert_eq!(
        owner_edit["results"][0]["status"], "accepted",
        "{owner_edit}"
    );
    let v_stable = owner_edit["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    (baby_id, record_id, v1, v_stable)
}

/// Owner + member: disjoint field auto-merge; same-field branch; peer summary; CAS resolve.
#[tokio::test]
async fn causal_two_client_disjoint_merge_and_same_field_branch() {
    let rig = Rig::new();
    let (owner, member) = two_joined_clients(
        &rig.app,
        "two-client-merge-owner",
        "two-client-merge-member",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();

    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4();
    // Member authors so both owner and author may edit.
    let (status, created) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "base", 100, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    assert_eq!(created["results"][0]["status"], "accepted");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, left) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-note", 100, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{left}");
    assert_eq!(left["results"][0]["status"], "accepted", "{left}");

    let (status, right) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "base", 180, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{right}");
    assert_eq!(right["results"][0]["status"], "merged", "{right}");
    assert_eq!(right["results"][0]["stable"]["root"]["note"], "owner-note");
    assert_eq!(
        right["results"][0]["stable"]["root"]["payload_json"]["amount_ml"],
        180
    );
    let v_merged = right["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_note) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v_merged),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-wins-candidate", 180, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_note}");
    assert_eq!(owner_note["results"][0]["status"], "accepted");
    let v_owner = owner_note["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v_merged),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-branch-note", 180, 60),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    assert_eq!(member_branch["results"][0]["status"], "branched");
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_eq!(
        member_branch["results"][0]["stable"]["version_id"].as_str(),
        Some(v_owner.as_str())
    );

    let owner_pull = pull_entities(&rig.app, owner_token, generation).await;
    let row = find_entity(&owner_pull, record_id);
    assert_eq!(row["version_id"], v_owner);
    assert_eq!(row["payload"]["note"], "owner-wins-candidate");
    let summary = row
        .get("conflict_summary")
        .expect("owner must see peer branch summary object");
    assert!(
        !summary.is_null(),
        "conflict_summary must be non-null: {row}"
    );
    assert_eq!(summary["conflict_id"], conflict_id);

    let (status, first_detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(owner_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{first_detail}");
    let first_choice = conflict_set_choice(&first_detail, "/note", json!("member-branch-note"));
    let outsider = approve_new_member(&rig.app, owner_token, "two-client-merge-outsider").await;
    let (status, forbidden) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(outsider["access_token"].as_str().unwrap()),
        json!({
            "snapshot_token": first_detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [first_choice.clone()]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{forbidden}");
    assert_eq!(forbidden["error"]["code"], "forbidden");

    // A new branch after detail invalidates the receipt-bound full set.
    let (status, late_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v_merged),
            "record",
            record_id,
            causal_formula_root(baby_id, "late-branch", 180, 65),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{late_branch}");
    assert_eq!(late_branch["results"][0]["status"], "branched");

    let (status, stale_resolve) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(owner_token),
        json!({
            "snapshot_token": first_detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [first_choice]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{stale_resolve}");
    assert_eq!(stale_resolve["status"], "rejected", "{stale_resolve}");
    assert_eq!(stale_resolve["error"]["code"], "snapshot_stale");

    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(owner_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    let member_choice = conflict_set_choice(&detail, "/note", json!("member-branch-note"));

    let (status, resolved) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(owner_token),
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [member_choice]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{resolved}");
    assert_eq!(resolved["status"], "accepted");
    assert_eq!(resolved["stable_root"]["note"], "member-branch-note");

    // A different resolution mutation cannot overwrite the closed conflict.
    let (status, race) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(member_token),
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [conflict_set_choice(&detail, "/note", json!("owner-wins-candidate"))]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{race}");
    assert_eq!(race["status"], "rejected");
    assert_eq!(race["error"]["code"], "snapshot_stale");

    let member_pull = pull_entities(&rig.app, member_token, generation).await;
    let settled = find_entity(&member_pull, record_id);
    assert_eq!(settled["payload"]["note"], "member-branch-note");
    conflict_summary_is_closed(settled);
}

/// Branch author withdraws their last open branch; family pull drops the summary
/// and the stable snapshot bytes stay the same (ticket 02).
#[tokio::test]
async fn author_withdraw_own_last_branch_closes_conflict_without_changing_stable() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "withdraw-own-owner", "withdraw-own-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4();

    let (status, created) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "stable-note", 90, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_edit) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "house-stable", 90, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_edit}");
    assert_eq!(owner_edit["results"][0]["status"], "accepted");
    let v_stable = owner_edit["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    assert_eq!(
        member_branch["results"][0]["status"], "branched",
        "{member_branch}"
    );
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(before["version_id"], v_stable);
    assert_eq!(before["payload"]["note"], "house-stable");
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 1, "{before}");

    let (status, withdrawn) =
        withdraw_conflict_branches(&rig.app, member_token, &conflict_id, &v_stable, &branch_ids)
            .await;
    assert_eq!(status, StatusCode::OK, "{withdrawn}");
    assert_eq!(withdrawn["status"], "accepted", "{withdrawn}");
    assert_eq!(withdrawn["stable_version_id"], v_stable);
    assert_eq!(withdrawn["conflict_status"], "resolved");
    assert_eq!(withdrawn["remaining_branch_version_ids"], json!([]));

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"], before["payload"]);
    assert_eq!(after["payload"]["note"], "house-stable");
    conflict_summary_is_closed(&after);
}

/// Withdrawing one author's branches leaves the peer branch open and does not
/// promote it to stable (ticket 02).
#[tokio::test]
async fn author_withdraw_own_branch_keeps_peer_branch_open_and_stable_unchanged() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "withdraw-peer-owner", "withdraw-peer-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    assert_eq!(
        member_branch["results"][0]["status"], "branched",
        "{member_branch}"
    );
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-draft", 90, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_branch}");
    assert_eq!(
        owner_branch["results"][0]["status"], "branched",
        "{owner_branch}"
    );
    let owner_branch_id = owner_branch["results"][0]["branch_version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 2, "{before}");

    let (status, withdrawn) =
        withdraw_conflict_branches(&rig.app, member_token, &conflict_id, &v_stable, &branch_ids)
            .await;
    assert_eq!(status, StatusCode::OK, "{withdrawn}");
    assert_eq!(withdrawn["status"], "accepted", "{withdrawn}");
    assert_eq!(withdrawn["conflict_status"], "open");
    assert_eq!(
        withdrawn["remaining_branch_version_ids"],
        json!([owner_branch_id])
    );
    assert_eq!(withdrawn["stable_version_id"], v_stable);

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"]["note"], "house-stable");
    assert_eq!(conflict_summary_branch_ids(&after), vec![owner_branch_id]);
}

/// Spec sequence: 甲 withdraws, conflict stays open with 乙; 乙 then withdraws
/// and the same stable snapshot is resolved (ticket 02).
#[tokio::test]
async fn sequential_authors_withdraw_own_branches_then_resolve() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "withdraw-seq-owner", "withdraw-seq-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-draft", 90, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_branch}");
    let owner_branch_id = owner_branch["results"][0]["branch_version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let first_set = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    let both_ids = conflict_summary_branch_ids(&first_set);
    assert_eq!(both_ids.len(), 2, "{first_set}");

    let (status, first) =
        withdraw_conflict_branches(&rig.app, member_token, &conflict_id, &v_stable, &both_ids)
            .await;
    assert_eq!(status, StatusCode::OK, "{first}");
    assert_eq!(first["conflict_status"], "open", "{first}");
    assert_eq!(
        first["remaining_branch_version_ids"],
        json!([owner_branch_id])
    );

    let mid = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(mid["version_id"], v_stable);
    assert_eq!(mid["payload"]["note"], "house-stable");
    let remaining = conflict_summary_branch_ids(&mid);
    assert_eq!(remaining, vec![owner_branch_id.clone()]);

    let (status, second) =
        withdraw_conflict_branches(&rig.app, owner_token, &conflict_id, &v_stable, &remaining)
            .await;
    assert_eq!(status, StatusCode::OK, "{second}");
    assert_eq!(second["status"], "accepted", "{second}");
    assert_eq!(second["conflict_status"], "resolved", "{second}");
    assert_eq!(second["remaining_branch_version_ids"], json!([]));
    assert_eq!(second["stable_version_id"], v_stable);

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"]["note"], "house-stable");
    conflict_summary_is_closed(&after);
}

/// A membership that did not submit a branch cannot withdraw someone else's
/// (ticket 02).
#[tokio::test]
async fn outsider_cannot_withdraw_another_memberships_conflict_branches() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "withdraw-forbid-owner", "withdraw-forbid-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let outsider = approve_new_member(&rig.app, owner_token, "withdraw-forbid-outsider").await;
    let outsider_token = outsider["access_token"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    assert_eq!(
        member_branch["results"][0]["status"], "branched",
        "{member_branch}"
    );
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 1, "{before}");

    let (status, forbidden) = withdraw_conflict_branches(
        &rig.app,
        outsider_token,
        &conflict_id,
        &v_stable,
        &branch_ids,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{forbidden}");
    assert_eq!(forbidden["status"], "rejected", "{forbidden}");
    assert_eq!(forbidden["error"]["code"], "forbidden");
    assert!(
        forbidden.get("remaining_branch_version_ids").is_none(),
        "{forbidden}"
    );

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["payload"], before["payload"]);
    assert_eq!(conflict_summary_branch_ids(&after), branch_ids);
}

/// One withdraw removes every open branch submitted by that membership
/// (ticket 02).
#[tokio::test]
async fn same_membership_withdraws_all_own_open_branches_at_once() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "withdraw-multi-owner", "withdraw-multi-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, first) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft-a", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{first}");
    assert_eq!(first["results"][0]["status"], "branched", "{first}");
    let conflict_id = first["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, second) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft-b", 120, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{second}");
    assert_eq!(second["results"][0]["status"], "branched", "{second}");

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 2, "{before}");

    let (status, withdrawn) =
        withdraw_conflict_branches(&rig.app, member_token, &conflict_id, &v_stable, &branch_ids)
            .await;
    assert_eq!(status, StatusCode::OK, "{withdrawn}");
    assert_eq!(withdrawn["status"], "accepted", "{withdrawn}");
    assert_eq!(withdrawn["conflict_status"], "resolved");
    assert_eq!(withdrawn["remaining_branch_version_ids"], json!([]));

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"]["note"], "house-stable");
    conflict_summary_is_closed(&after);
}

/// Two authors withdrawing their own branches converge; the later request is
/// success or idempotent resolved, and the stable snapshot is unchanged
/// (ticket 02).
#[tokio::test]
async fn concurrent_own_withdrawals_converge_to_stable_without_promoting() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "withdraw-race-owner", "withdraw-race-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-draft", 90, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_branch}");
    assert_eq!(
        owner_branch["results"][0]["status"], "branched",
        "{owner_branch}"
    );

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 2, "{before}");

    let (member_result, owner_result) = tokio::join!(
        withdraw_conflict_branches(&rig.app, member_token, &conflict_id, &v_stable, &branch_ids,),
        withdraw_conflict_branches(&rig.app, owner_token, &conflict_id, &v_stable, &branch_ids,),
    );
    assert_eq!(member_result.0, StatusCode::OK, "{}", member_result.1);
    assert_eq!(owner_result.0, StatusCode::OK, "{}", owner_result.1);
    assert_eq!(member_result.1["status"], "accepted", "{}", member_result.1);
    assert_eq!(owner_result.1["status"], "accepted", "{}", owner_result.1);

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"]["note"], "house-stable");
    conflict_summary_is_closed(&after);
}

/// Family admin withdraws another membership's last open branch; the conflict
/// resolves and the stable snapshot bytes stay the same (ticket 03).
#[tokio::test]
async fn admin_withdraws_any_last_branch_and_closes_without_changing_stable() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "withdraw-admin-owner", "withdraw-admin-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    assert_eq!(
        member_branch["results"][0]["status"], "branched",
        "{member_branch}"
    );
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(before["version_id"], v_stable);
    assert_eq!(before["payload"]["note"], "house-stable");
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 1, "{before}");

    let (status, withdrawn) =
        withdraw_conflict_branches(&rig.app, owner_token, &conflict_id, &v_stable, &branch_ids)
            .await;
    assert_eq!(status, StatusCode::OK, "{withdrawn}");
    assert_eq!(withdrawn["status"], "accepted", "{withdrawn}");
    assert_eq!(withdrawn["stable_version_id"], v_stable);
    assert_eq!(withdrawn["conflict_status"], "resolved");
    assert_eq!(withdrawn["remaining_branch_version_ids"], json!([]));

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"], before["payload"]);
    assert_eq!(after["payload"]["note"], "house-stable");
    conflict_summary_is_closed(&after);
}

/// Family admin can empty every remaining open branch in one withdraw
/// (ticket 03).
#[tokio::test]
async fn admin_withdraws_every_open_branch_in_one_request() {
    let rig = Rig::new();
    let (owner, member) = two_joined_clients(
        &rig.app,
        "withdraw-admin-all-owner",
        "withdraw-admin-all-member",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-draft", 90, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_branch}");
    assert_eq!(
        owner_branch["results"][0]["status"], "branched",
        "{owner_branch}"
    );

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 2, "{before}");

    let (status, withdrawn) =
        withdraw_conflict_branches(&rig.app, owner_token, &conflict_id, &v_stable, &branch_ids)
            .await;
    assert_eq!(status, StatusCode::OK, "{withdrawn}");
    assert_eq!(withdrawn["status"], "accepted", "{withdrawn}");
    assert_eq!(withdrawn["conflict_status"], "resolved");
    assert_eq!(withdrawn["remaining_branch_version_ids"], json!([]));
    assert_eq!(withdrawn["stable_version_id"], v_stable);

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"]["note"], "house-stable");
    conflict_summary_is_closed(&after);
}

/// Record author cannot choice-only adopt while a peer branch is still open;
/// the stable snapshot and branch set stay put (ticket 03).
#[tokio::test]
async fn author_cannot_adopt_live_fork_while_peer_branch_remains() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "adopt-forbid-owner", "adopt-forbid-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-draft", 90, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_branch}");
    assert_eq!(
        owner_branch["results"][0]["status"], "branched",
        "{owner_branch}"
    );

    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(member_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    let member_choice = conflict_set_choice(&detail, "/note", json!("member-draft"));

    let (status, rejected) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(member_token),
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [member_choice]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["status"], "rejected", "{rejected}");
    assert_eq!(rejected["error"]["code"], "forbidden");

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"]["note"], "house-stable");
    assert_eq!(conflict_summary_branch_ids(&after).len(), 2, "{after}");
}

/// Family admin choice-only adopt still writes a new household stable snapshot
/// (ticket 03).
#[tokio::test]
async fn admin_choice_only_adopt_writes_new_stable_snapshot() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "adopt-admin-owner", "adopt-admin-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let (baby_id, record_id, v1, v_stable) =
        seed_member_formula_and_owner_stable(&rig.app, owner_token, member_token).await;

    let (status, member_branch) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "member-draft", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member_branch}");
    let conflict_id = member_branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, owner_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "owner-draft", 90, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{owner_branch}");
    assert_eq!(
        owner_branch["results"][0]["status"], "branched",
        "{owner_branch}"
    );

    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(owner_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    let member_choice = conflict_set_choice(&detail, "/note", json!("member-draft"));

    let (status, resolved) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(owner_token),
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [member_choice]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{resolved}");
    assert_eq!(resolved["status"], "accepted", "{resolved}");
    assert_eq!(resolved["stable_root"]["note"], "member-draft");
    let new_stable = resolved["stable_version_id"].as_str().unwrap();
    assert_ne!(new_stable, v_stable);

    let after = find_entity(
        &pull_entities(&rig.app, member_token, generation).await,
        record_id,
    )
    .clone();
    assert_eq!(after["version_id"], new_stable);
    assert_eq!(after["payload"]["note"], "member-draft");
    conflict_summary_is_closed(&after);
}

/// Baby-profile conflicts stay family-admin only: a member cannot withdraw or
/// adopt, and the open set is unchanged (ticket 03).
#[tokio::test]
async fn member_cannot_withdraw_or_adopt_baby_conflict() {
    let rig = Rig::new();
    let (owner, member) = two_joined_clients(&rig.app, "baby-acl-owner", "baby-acl-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = Uuid::new_v4();
    let (status, created) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "baby",
            baby_id,
            causal_baby_root("年年", None, 10),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, stable_edit) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "baby",
            baby_id,
            causal_baby_root("岁岁", None, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{stable_edit}");
    assert_eq!(
        stable_edit["results"][0]["status"], "accepted",
        "{stable_edit}"
    );
    let v_stable = stable_edit["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "baby",
            baby_id,
            causal_baby_root("果果", None, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{branch}");
    assert_eq!(branch["results"][0]["status"], "branched", "{branch}");
    let conflict_id = branch["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let before = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        baby_id,
    )
    .clone();
    let branch_ids = conflict_summary_branch_ids(&before);
    assert_eq!(branch_ids.len(), 1, "{before}");
    assert_eq!(before["version_id"], v_stable);
    assert_eq!(before["payload"]["nickname"], "岁岁");

    let (status, withdrawn) =
        withdraw_conflict_branches(&rig.app, member_token, &conflict_id, &v_stable, &branch_ids)
            .await;
    assert_eq!(status, StatusCode::OK, "{withdrawn}");
    assert_eq!(withdrawn["status"], "rejected", "{withdrawn}");
    assert_eq!(withdrawn["error"]["code"], "forbidden");

    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(member_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    let branch_choice = conflict_set_choice(&detail, "/nickname", json!("果果"));
    let (status, adopted) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/conflicts/{conflict_id}/resolve"),
        Some(member_token),
        json!({
            "snapshot_token": detail["snapshot_token"],
            "resolution_mutation_id": Uuid::new_v4().to_string(),
            "choices": [branch_choice]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{adopted}");
    assert_eq!(adopted["status"], "rejected", "{adopted}");
    assert_eq!(adopted["error"]["code"], "forbidden");

    let after = find_entity(
        &pull_entities(&rig.app, owner_token, generation).await,
        baby_id,
    )
    .clone();
    assert_eq!(after["version_id"], v_stable);
    assert_eq!(after["payload"]["nickname"], "岁岁");
    assert_eq!(conflict_summary_branch_ids(&after), branch_ids);
}

/// Delete-then-edit and edit-then-delete arrival orders + idempotent replay + stale reject.
#[tokio::test]
async fn causal_two_client_delete_edit_both_arrival_orders() {
    let rig = Rig::new();
    let (owner, member) = two_joined_clients(
        &rig.app,
        "two-client-delete-owner",
        "two-client-delete-member",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;

    // --- Order A: delete first, concurrent edit from common base → branched tombstone stable.
    let record_a = Uuid::new_v4();
    let create_mut = Uuid::new_v4();
    let (status, created) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            create_mut,
            None,
            "record",
            record_a,
            causal_formula_root(baby_id, "live", 90, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, replay) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            create_mut,
            None,
            "record",
            record_a,
            causal_formula_root(baby_id, "live", 90, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["results"][0]["stable"]["version_id"], v1);

    let (status, deleted) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_a,
            causal_formula_root(baby_id, "live", 90, 30),
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{deleted}");
    assert_eq!(deleted["results"][0]["status"], "accepted");
    let tombstone_v = deleted["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, concurrent_edit) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_a,
            causal_formula_root(baby_id, "offline-edit", 90, 40),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{concurrent_edit}");
    assert_eq!(concurrent_edit["results"][0]["status"], "branched");
    assert_eq!(
        concurrent_edit["results"][0]["stable"]["version_id"].as_str(),
        Some(tombstone_v.as_str())
    );
    let pull = pull_entities(&rig.app, owner_token, generation).await;
    let row = find_entity(&pull, record_a);
    assert!(!row["deleted_at"].is_null(), "{row}");
    assert!(
        row.get("conflict_summary").is_some() && !row["conflict_summary"].is_null(),
        "{row}"
    );

    let (status, stale) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&Uuid::new_v4().to_string()),
            "record",
            record_a,
            causal_formula_root(baby_id, "stale-resurrect", 90, 99),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{stale}");
    assert_eq!(stale["status"], "rejected");
    assert_eq!(stale["error"]["code"].as_str(), Some("invalid_domain"));

    // --- Order B: edit first, then delete from common base → branched; stable is live edit.
    let record_b = Uuid::new_v4();
    let (status, created_b) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_b,
            causal_formula_root(baby_id, "live-b", 50, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created_b}");
    let vb1 = created_b["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, edited) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&vb1),
            "record",
            record_b,
            causal_formula_root(baby_id, "member-edit-first", 50, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{edited}");
    assert_eq!(edited["results"][0]["status"], "accepted");
    let vb2 = edited["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, delete_late) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&vb1),
            "record",
            record_b,
            causal_formula_root(baby_id, "live-b", 50, 40),
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{delete_late}");
    assert_eq!(
        delete_late["results"][0]["status"], "branched",
        "{delete_late}"
    );
    assert_eq!(
        delete_late["results"][0]["stable"]["version_id"].as_str(),
        Some(vb2.as_str())
    );
    let pull_b = pull_entities(&rig.app, member_token, generation).await;
    let row_b = find_entity(&pull_b, record_b);
    assert!(
        row_b["deleted_at"].is_null(),
        "stable remains live edit: {row_b}"
    );
    assert_eq!(row_b["payload"]["note"], "member-edit-first");
    assert!(
        row_b.get("conflict_summary").is_some() && !row_b["conflict_summary"].is_null(),
        "delete branch must surface conflict_summary: {row_b}"
    );
}

#[tokio::test]
async fn causal_member_cannot_commit_baby_avatar_root() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let (_owner, member) =
        two_joined_clients(&rig.app, "causal-avatar-owner", "causal-avatar-member").await;
    let member_token = member["access_token"].as_str().unwrap();

    let denied_baby = Uuid::new_v4();
    let denied_avatar = Uuid::new_v4();
    let denied_bytes = b"member-avatar-must-not-publish";
    let denied_sha = hex::encode(Sha256::digest(denied_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, member_token, denied_avatar, denied_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let (status, denied) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "baby",
            denied_baby,
            causal_baby_root("禁止创建", Some(denied_avatar), 10),
            vec![causal_media_item(
                denied_avatar,
                "avatar",
                &denied_sha,
                denied_bytes.len(),
            )],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{denied}");
    assert_eq!(denied["status"], "rejected", "{denied}");
    assert_eq!(denied["error"]["code"], "forbidden", "{denied}");
}

#[tokio::test]
async fn causal_baby_avatar_exact_commit_replay_keeps_stable_version_and_bytes() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-avatar-replay-owner",
        "causal-avatar-replay-create-request-0001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();

    let baby_id = Uuid::new_v4();
    let avatar_id = Uuid::new_v4();
    let avatar_bytes = b"owner-avatar-exact-bytes";
    let avatar_sha = hex::encode(Sha256::digest(avatar_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, owner_token, avatar_id, avatar_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let mutation = causal_unit(
        Uuid::new_v4(),
        None,
        "baby",
        baby_id,
        causal_baby_root("年年", Some(avatar_id), 20),
        vec![causal_media_item(
            avatar_id,
            "avatar",
            &avatar_sha,
            avatar_bytes.len(),
        )],
        false,
    );
    let (status, created) =
        causal_commit_units(&rig.app, owner_token, vec![mutation.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{created}");
    assert_eq!(created["results"][0]["status"], "accepted", "{created}");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, replay) = causal_commit_units(&rig.app, owner_token, vec![mutation]).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay["results"][0]["status"], "accepted", "{replay}");
    assert_eq!(replay["results"][0]["stable"]["version_id"], v1);

    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{avatar_id}"),
        Some(owner_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response.into_body().collect().await.unwrap().to_bytes(),
        avatar_bytes.as_slice()
    );
}

#[tokio::test]
async fn causal_deleted_baby_stable_keeps_avatar_branch_evidence_auditable() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-avatar-branch-owner",
        "causal-avatar-branch-create-request-0001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = Uuid::new_v4();
    let avatar_id = Uuid::new_v4();
    let avatar_bytes = b"owner-avatar-branch-bytes";
    let avatar_sha = hex::encode(Sha256::digest(avatar_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, owner_token, avatar_id, avatar_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let (status, created) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "baby",
            baby_id,
            causal_baby_root("年年", Some(avatar_id), 20),
            vec![causal_media_item(
                avatar_id,
                "avatar",
                &avatar_sha,
                avatar_bytes.len(),
            )],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, deleted) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "baby",
            baby_id,
            causal_baby_root("年年", None, 30),
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{deleted}");
    assert_eq!(deleted["results"][0]["status"], "accepted", "{deleted}");

    let (status, avatar_branch) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "baby",
            baby_id,
            causal_baby_root("岁岁", Some(avatar_id), 40),
            vec![causal_media_item(
                avatar_id,
                "avatar",
                &avatar_sha,
                avatar_bytes.len(),
            )],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{avatar_branch}");
    assert_eq!(
        avatar_branch["results"][0]["status"], "branched",
        "{avatar_branch}"
    );
    let conflict_id = avatar_branch["results"][0]["conflict_id"].as_str().unwrap();
    let (status, detail) = get_json(
        &rig.app,
        &format!("/v1/conflicts/{conflict_id}"),
        Some(owner_token),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    assert_eq!(detail["stable"]["deleted"], true);
    assert_eq!(detail["stable"]["media"], json!([]));
    assert!(detail["branches"]
        .as_array()
        .unwrap()
        .iter()
        .any(|branch| branch["deleted"] == false
            && branch["root"]["avatar_media_uuid"] == avatar_id.to_string()
            && branch["media"][0]["media_uuid"] == avatar_id.to_string()
            && branch["media"][0]["sha256"] == avatar_sha
            && branch["media"][0]["byte_size"] == avatar_bytes.len()));

    let pull = pull_entities(&rig.app, owner_token, generation).await;
    let baby = find_entity(&pull, baby_id);
    assert!(
        !baby["deleted_at"].is_null(),
        "stable stays deleted: {baby}"
    );
    assert!(baby["payload"]["avatar_media_uuid"].is_null());
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{avatar_id}"),
        Some(owner_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn causal_deleted_baby_clears_avatar_reachability() {
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let owner = create_family(
        &rig.app,
        "causal-avatar-delete-owner",
        "causal-avatar-delete-create-request-0001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();

    let deleted_baby = Uuid::new_v4();
    let orphan_avatar = Uuid::new_v4();
    let orphan_bytes = b"deleted-baby-avatar-evidence";
    let orphan_sha = hex::encode(Sha256::digest(orphan_bytes));
    let (status, prepared) =
        put_causal_media_bytes(&rig.app, owner_token, orphan_avatar, orphan_bytes).await;
    assert_eq!(status, StatusCode::OK, "{prepared}");
    let (status, created) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "baby",
            deleted_baby,
            causal_baby_root("待删除", Some(orphan_avatar), 50),
            vec![causal_media_item(
                orphan_avatar,
                "avatar",
                &orphan_sha,
                orphan_bytes.len(),
            )],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    let delete_base = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap();
    let (status, deleted) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(delete_base),
            "baby",
            deleted_baby,
            causal_baby_root("待删除", None, 60),
            vec![],
            true,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{deleted}");
    assert_eq!(deleted["results"][0]["status"], "accepted", "{deleted}");
    let pull = pull_entities(&rig.app, owner_token, generation).await;
    let tombstone = find_entity(&pull, deleted_baby);
    assert!(!tombstone["deleted_at"].is_null(), "{tombstone}");
    assert!(tombstone["payload"]["avatar_media_uuid"].is_null());
    let media_tombstone = find_entity(&pull, orphan_avatar);
    assert!(
        !media_tombstone["deleted_at"].is_null(),
        "{media_tombstone}"
    );
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{orphan_avatar}"),
        Some(owner_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

/// Independent media additions merge; same-media delete/edit branches; bytes retained.
#[tokio::test]
async fn causal_two_client_media_merge_and_delete_edit_branch() {
    // Default Rig max_media_bytes is tiny (8); media E2E needs room for preimages.
    let rig = Rig::with_config(|config| {
        config.max_media_bytes = 64 * 1024;
    });
    let (owner, member) = two_joined_clients(
        &rig.app,
        "two-client-media-owner",
        "two-client-media-member",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;

    let record_id = Uuid::new_v4();
    let m1 = Uuid::new_v4();
    let bytes1 = b"photo-one-bytes";
    let sha1 = hex::encode(Sha256::digest(bytes1));
    let (status, put1) = put_causal_media_bytes(&rig.app, member_token, m1, bytes1).await;
    assert_eq!(status, StatusCode::OK, "{put1}");
    let (status, created) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record_id,
            causal_formula_root(baby_id, "with-photo", 80, 20),
            vec![causal_media_item(m1, "log", &sha1, bytes1.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{created}");
    assert_eq!(created["results"][0]["status"], "accepted");
    let v1 = created["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    // Owner removes m1; member independently adds m2 from v1 → merge keeps m2 only.
    let (status, left) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "with-photo", 80, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{left}");
    assert_eq!(left["results"][0]["status"], "accepted", "{left}");

    let m2 = Uuid::new_v4();
    let bytes2 = b"photo-two-bytes-longer";
    let sha2 = hex::encode(Sha256::digest(bytes2));
    let (status, _) = put_causal_media_bytes(&rig.app, member_token, m2, bytes2).await;
    assert_eq!(status, StatusCode::OK);
    let (status, right) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&v1),
            "record",
            record_id,
            causal_formula_root(baby_id, "with-photo", 80, 40),
            vec![
                causal_media_item(m1, "log", &sha1, bytes1.len()),
                causal_media_item(m2, "log", &sha2, bytes2.len()),
            ],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{right}");
    assert_eq!(right["results"][0]["status"], "merged", "{right}");
    let media = right["results"][0]["stable"]["media"]
        .as_array()
        .cloned()
        .unwrap_or_default();
    assert_eq!(media.len(), 1, "{media:?}");
    assert_eq!(media[0]["media_uuid"], m2.to_string());

    // Bytes for m2 remain downloadable after independent-media merge.
    let response = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{m2}"),
        Some(member_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let got = response.into_body().collect().await.unwrap().to_bytes();
    assert_eq!(got.as_ref(), bytes2.as_slice());

    // Same-media delete vs edit branches.
    let record2 = Uuid::new_v4();
    let m3 = Uuid::new_v4();
    let bytes3 = b"shared-photo";
    let sha3 = hex::encode(Sha256::digest(bytes3));
    let (status, _) = put_causal_media_bytes(&rig.app, member_token, m3, bytes3).await;
    assert_eq!(status, StatusCode::OK);
    let (status, c2) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            record2,
            causal_formula_root(baby_id, "media-conflict", 10, 50),
            vec![causal_media_item(m3, "log", &sha3, bytes3.len())],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{c2}");
    let base = c2["results"][0]["stable"]["version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, del_media) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&base),
            "record",
            record2,
            causal_formula_root(baby_id, "media-conflict", 10, 51),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{del_media}");
    assert_eq!(del_media["results"][0]["status"], "accepted");

    // Same UUID cannot claim different bytes; exercise the legitimate
    // delete-vs-descriptive-manifest-edit conflict with the original preimage.
    let mut edited_manifest = causal_media_item(m3, "log", &sha3, bytes3.len());
    edited_manifest["width"] = json!(2);
    let (status, edit_media) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            Some(&base),
            "record",
            record2,
            causal_formula_root(baby_id, "media-conflict", 10, 52),
            vec![edited_manifest],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{edit_media}");
    assert_eq!(
        edit_media["results"][0]["status"], "branched",
        "{edit_media}"
    );
    let pull = pull_entities(&rig.app, owner_token, generation).await;
    let row = find_entity(&pull, record2);
    assert!(
        row.get("conflict_summary").is_some() && !row["conflict_summary"].is_null(),
        "media delete/edit must open conflict_summary: {row}"
    );
}

#[tokio::test]
async fn causal_two_client_wake_observations_and_near_duplicates_retained() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "two-client-wake-owner", "two-client-wake-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;
    let sleep_id = Uuid::new_v4();
    let sleep_start = 1_700_100_000_i64;
    let (status, sleep_body) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            sleep_id,
            causal_sleep_root(baby_id, sleep_start, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{sleep_body}");
    assert_eq!(sleep_body["results"][0]["status"], "accepted");

    let wake_owner = Uuid::new_v4();
    let wake_member = Uuid::new_v4();
    let (status, w1) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "wake_observation",
            wake_owner,
            json!({
                "sleep_record_client_uuid": sleep_id,
                "wake_timestamp": sleep_start + 3_600_000,
                "note": "owner saw wake",
                "withdrawn": false,
                "updated_at": 30
            }),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{w1}");
    assert_eq!(w1["results"][0]["status"], "accepted", "{w1}");
    let (status, w2) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "wake_observation",
            wake_member,
            json!({
                "sleep_record_client_uuid": sleep_id,
                "wake_timestamp": sleep_start + 3_900_000,
                "note": "member saw later",
                "withdrawn": false,
                "updated_at": 40
            }),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{w2}");
    assert_eq!(w2["results"][0]["status"], "accepted", "{w2}");

    let pull = pull_entities(&rig.app, member_token, generation).await;
    let wakes: Vec<_> = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|e| {
            e["type"] == "wake_observation"
                && (e["client_uuid"] == wake_owner.to_string()
                    || e["client_uuid"] == wake_member.to_string())
                && e["deleted_at"].is_null()
        })
        .collect();
    assert_eq!(wakes.len(), 2, "both wake observations must remain: {pull}");

    let r1 = Uuid::new_v4();
    let r2 = Uuid::new_v4();
    let ts = 1_700_200_000_i64;
    let (status, a) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            r1,
            causal_formula_root_at(baby_id, "a", 100, ts, 50),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{a}");
    let (status, b) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            r2,
            causal_formula_root_at(baby_id, "b", 110, ts + 10 * 60 * 1000, 51),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{b}");
    assert_eq!(b["results"][0]["status"], "accepted", "{b}");
    let pull2 = pull_entities(&rig.app, owner_token, generation).await;
    let live: Vec<_> = pull2["entities"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|e| {
            e["type"] == "record"
                && (e["client_uuid"] == r1.to_string() || e["client_uuid"] == r2.to_string())
                && e["deleted_at"].is_null()
        })
        .collect();
    assert_eq!(
        live.len(),
        2,
        "near-duplicate records must stay live: {pull2}"
    );
}

/// Server-only shape: two independent creates without prior pull; peer visible on full pull.
/// Does **not** prove Android LocalWrite no-pull cursor semantics (engine/unit residual).
#[tokio::test]
async fn causal_two_client_independent_creates_visible_on_peer_full_pull() {
    let rig = Rig::new();
    let (owner, member) =
        two_joined_clients(&rig.app, "two-client-lw-owner", "two-client-lw-member").await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_token = member["access_token"].as_str().unwrap();
    let generation = owner["generation"].as_str().unwrap();
    let baby_id = seed_causal_baby(&rig.app, owner_token).await;

    let peer_record = Uuid::new_v4();
    let (status, peer) = causal_commit_units(
        &rig.app,
        member_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            peer_record,
            causal_formula_root(baby_id, "peer-only", 70, 20),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{peer}");
    assert_eq!(peer["results"][0]["status"], "accepted");

    let owner_record = Uuid::new_v4();
    let (status, mine) = causal_commit_units(
        &rig.app,
        owner_token,
        vec![causal_unit(
            Uuid::new_v4(),
            None,
            "record",
            owner_record,
            causal_formula_root(baby_id, "owner-localwrite", 80, 30),
            vec![],
            false,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{mine}");
    assert_eq!(mine["results"][0]["status"], "accepted");

    let full = pull_entities(&rig.app, owner_token, generation).await;
    find_entity(&full, owner_record);
    assert_eq!(
        find_entity(&full, peer_record)["payload"]["note"],
        "peer-only"
    );
}

/// Verified app-update floor from shared catalog blocks clients below minimum_sync_version_code.
#[tokio::test]
async fn causal_forced_min_supported_from_catalog_blocks_legacy_client() {
    let catalog = release_catalog();
    let min_supported = catalog["minimum_sync_version_code"].as_u64().unwrap();
    let target_code = catalog["upgrade_target"]["version_code"].as_u64().unwrap();
    let target_name = catalog["upgrade_target"]["version_name"]
        .as_str()
        .unwrap()
        .to_owned();
    let package_name = catalog["application_id"].as_str().unwrap().to_owned();
    assert!(
        min_supported >= 20 && target_code >= min_supported,
        "catalog floor/target must be causal cutover: min={min_supported} target={target_code}"
    );
    let legacy_code = min_supported.saturating_sub(1);
    assert!(legacy_code < min_supported);

    let apk_bytes = b"lezi-catalog-forced-cutover-release-apk-bytes";
    let sha = hex::encode(Sha256::digest(apk_bytes));
    let rig = Rig::new();
    fs::write(rig.directory.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        rig.directory.path().join("app-update.json"),
        json!({
            "package_name": package_name,
            "version_code": target_code,
            "version_name": target_name,
            "min_supported_version_code": min_supported,
            "sha256": sha,
            "release_notes": "catalog-driven forced cutover"
        })
        .to_string(),
    )
    .unwrap();
    let app = rig.restart("generation-a");

    let owner = create_family(
        &app,
        "min-supported-owner",
        "min-supported-request-000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4();
    let unit = causal_unit(
        Uuid::new_v4(),
        None,
        "baby",
        baby_id,
        json!({
            "nickname": "年年",
            "sex": "female",
            "birthday": "2025-01-02",
            "avatar_media_uuid": null,
            "updated_at": 10
        }),
        vec![],
        false,
    );

    let legacy_header = legacy_code.to_string();
    let legacy = [("x-lezi-client-version-code", legacy_header.as_str())];
    let (status, body) = json_request_with_headers(
        &app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({ "units": [unit.clone()] }),
        &legacy,
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert_eq!(body["status"], json!("rejected"), "{body}");
    assert_eq!(
        body["error"]["code"],
        json!("capability_mismatch"),
        "{body}"
    );

    let (pull_status, pull_body) = raw_json_request_with_headers(
        &app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a&page_index=0",
        Some(token),
        json!({}),
        &legacy,
    )
    .await;
    assert_eq!(pull_status, StatusCode::FORBIDDEN, "{pull_body}");
    assert_eq!(
        pull_body["code"],
        json!("client_update_required"),
        "{pull_body}"
    );

    let modern_header = target_code.to_string();
    let modern = [("x-lezi-client-version-code", modern_header.as_str())];
    let (status, ok) = json_request_with_headers(
        &app,
        Method::POST,
        "/v1/causal/commit",
        Some(token),
        json!({ "units": [unit] }),
        &modern,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{ok}");
    assert_eq!(ok["results"][0]["status"], "accepted", "{ok}");
}

async fn json_request_without_version(
    app: &Router,
    method: Method,
    uri: &str,
    token: Option<&str>,
    body: Value,
    headers: &[(&str, &str)],
) -> (StatusCode, Value) {
    let mut builder = Request::builder()
        .method(method)
        .uri(uri)
        .header(CONTENT_TYPE, "application/json")
        .header(
            "x-lezi-sync-capabilities",
            "nursing_plan_intent_v1,restore_authority_v1",
        );
    if let Some(token) = token {
        builder = builder.header(AUTHORIZATION, format!("Bearer {token}"));
    }
    for (name, value) in headers {
        builder = builder.header(*name, *value);
    }
    let mut request = builder.body(Body::from(body.to_string())).unwrap();
    request.extensions_mut().insert(ConnectInfo(SocketAddr::new(
        IpAddr::V4(Ipv4Addr::LOCALHOST),
        43210,
    )));
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}

#[tokio::test]
async fn hard_floor_and_nursing_capability_apply_without_update_metadata() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "hard-floor-owner",
        "hard-floor-create-request-000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let path = "/v1/pull?cursor=0&generation=generation-a&page_index=0";
    for version in ["1", "34", "", "-1"] {
        let (status, body) = raw_json_request_with_headers(
            &rig.app,
            Method::GET,
            path,
            Some(token),
            json!({}),
            &[("x-lezi-client-version-code", version)],
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
        assert_eq!(body["code"], "client_update_required");
    }
    for capability in [
        "",
        "restore_authority_v1",
        "future_unknown",
        "nursing_plan_intent_v1,nursing_plan_intent_v1",
    ] {
        let (status, body) = raw_json_request_with_headers(
            &rig.app,
            Method::GET,
            path,
            Some(token),
            json!({}),
            &[
                ("x-lezi-client-version-code", "999"),
                ("x-lezi-sync-capabilities", capability),
            ],
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert_eq!(body["code"], "capability_mismatch");
    }
    assert_eq!(
        get_json(&rig.app, path, Some(token)).await.0,
        StatusCode::OK
    );
}

include!("support/current_source_relations.rs");
