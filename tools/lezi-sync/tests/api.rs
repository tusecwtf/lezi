use std::collections::{BTreeSet, HashMap};
use std::fs;
use std::io::Read;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::Duration;

use axum::body::{Body, Bytes};
use axum::extract::ConnectInfo;
use axum::http::header::{ACCEPT_ENCODING, AUTHORIZATION, CONTENT_ENCODING, CONTENT_TYPE, VARY};
use axum::http::{Method, Request, StatusCode};
use axum::Router;
use flate2::read::GzDecoder;
use http_body_util::BodyExt;
use lezi_sync::{build_app, build_apps, build_server_apps, RateLimitConfig, ServerConfig, VERSION};
use rusqlite::Connection;
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
}

fn app_for(
    directory: &Path,
    generation: &str,
    now: Arc<AtomicI64>,
    max_media_bytes: usize,
    configure: impl FnOnce(&mut ServerConfig),
) -> Router {
    let clock = now.clone();
    let mut config = ServerConfig::new(directory);
    config.generation = Some(generation.to_owned());
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
    let has_generation = uri
        .split_once('?')
        .is_some_and(|(_, query)| query.split('&').any(|part| part.starts_with("generation=")));
    if uri.starts_with("/v1/pull?") && !has_generation {
        if let Some(session) = token.and_then(test_client_session) {
            current_uri.push_str("&generation=");
            current_uri.push_str(&session.generation);
        }
    }
    json_request(app, Method::GET, &current_uri, token, json!({})).await
}

async fn raw_get_json(app: &Router, uri: &str, token: Option<&str>) -> (StatusCode, Value) {
    raw_json_request(app, Method::GET, uri, token, json!({})).await
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

fn avatar_media_payload(baby_id: &str) -> Value {
    json!({
        "kind": "avatar",
        "baby_client_uuid": baby_id,
        "mime": "image/jpeg",
        "byte_size": 6,
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
    assert_eq!(
        body["capabilities"],
        json!([
            "atomic_bundle",
            "record_membership_author",
            "device_disaster_restore_v1",
            "authoritative_reconcile_v1",
            "validated_deferred_fulfillment_v1",
            "causal_versions",
            "wake_observation",
            "source_relations"
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

#[test]
fn lan_apk_download_origin_rejects_everything_except_bare_http_port_8767() {
    let directory = TempDir::new().unwrap();
    for origin in [
        "https://192.168.50.4:8767",
        "http://user@192.168.50.4:8767",
        "http://192.168.50.4",
        "http://192.168.50.4:8765",
        "http://192.168.50.4:8767/",
        "http://192.168.50.4:8767/join",
        "http://192.168.50.4:8767?source=qr",
        "http://192.168.50.4:8767#invite",
        "http://:8767",
        "http://[2001:db8::1]:8767",
        "192.168.50.4:8767",
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
    config.lan_apk_download_origin = Some("http://192.168.50.4:8767".to_owned());
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
    config.lan_apk_download_origin = Some("http://192.168.50.4:8767".to_owned());
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
        config.lan_apk_download_origin = Some("http://192.168.50.4:8767".to_owned());
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
    config.lan_apk_download_origin = Some("http://192.168.50.4:8767".to_owned());
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
    config.lan_apk_download_origin = Some("http://192.168.50.4:8767".to_owned());
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
            &format!("/v1/pull?cursor=0&generation={generation}"),
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
                "authoritative_reconcile_v1",
                "validated_deferred_fulfillment_v1",
                "causal_versions",
                "wake_observation",
                "source_relations",
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
                "authoritative_reconcile_v1",
                "validated_deferred_fulfillment_v1",
                "causal_versions",
                "wake_observation",
                "source_relations",
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
            "required_capabilities": [
                "causal_versions",
                "source_relations",
                "wake_observation",
            ],
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
        json!(["causal_versions", "wake_observation", "source_relations"]),
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
    assert_eq!(identity.headers()[VARY], "Accept-Encoding");
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
    assert_eq!(gzip.headers()[VARY], "Accept-Encoding");
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
            "required_capabilities": [
                "causal_versions", "source_relations", "wake_observation"
            ],
        }),
    )
    .await;
    assert_eq!(auth_status, StatusCode::UNAUTHORIZED);

    for required_capabilities in [
        json!([]),
        json!(["causal_versions"]),
        json!([
            "causal_versions",
            "source_relations",
            "wake_observation",
            "future_extra",
        ]),
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
            "required_capabilities": [
                "causal_versions", "source_relations", "wake_observation"
            ],
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
        "required_capabilities": [
            "causal_versions", "source_relations", "wake_observation"
        ],
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
    let (missing_status, missing_body) = json_request_with_headers(
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
            ("x-lezi-client-version-code", "7"),
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
            ("x-lezi-client-version-code", "8"),
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
async fn disaster_restore_write_paths_fail_open_without_verified_channel() {
    // No app-update pair → same fail-open as sync version gate.
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
    let (missing_status, missing_body) =
        get_json(&rig.app, "/v1/pull?cursor=0", Some(&token)).await;
    assert_eq!(missing_status, StatusCode::FORBIDDEN, "{missing_body}");
    assert_eq!(missing_body["code"], json!("client_update_required"));

    // Below minSupported → gate.
    let (low_status, low_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "7")],
    )
    .await;
    assert_eq!(low_status, StatusCode::FORBIDDEN, "{low_body}");
    assert_eq!(low_body["code"], json!("client_update_required"));

    // At minSupported → pull allowed.
    let (ok_status, ok_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "8")],
    )
    .await;
    assert_eq!(ok_status, StatusCode::OK, "{ok_body}");
    assert!(ok_body.get("entities").is_some(), "{ok_body}");

    // Above minSupported → pull allowed.
    let (high_status, high_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "9")],
    )
    .await;
    assert_eq!(high_status, StatusCode::OK, "{high_body}");

    // Invalid header → same gate (not a generic 422).
    let (invalid_status, invalid_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a",
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
    let (missing_status, missing_body) = get_json(&rig.app, &media_path, Some(&token)).await;
    assert_eq!(missing_status, StatusCode::FORBIDDEN, "{missing_body}");
    assert_eq!(missing_body["code"], json!("client_update_required"));

    // Below minSupported → same gate.
    let (low_status, low_body) = raw_json_request_with_headers(
        &rig.app,
        Method::GET,
        &media_path,
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "7")],
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
        &[("x-lezi-client-version-code", "8")],
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
        &[("x-lezi-client-version-code", "9")],
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
async fn client_version_gate_fail_open_without_app_update_metadata() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "client-update-failopen-owner",
        "client-update-failopen-owner-req-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    // No app-update.json → do not brick sync for older deploys without an update channel.
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
async fn client_version_gate_fail_open_when_metadata_present_but_apk_missing() {
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
        "/v1/pull?cursor=0&generation=generation-a",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "1")],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_ne!(body["code"], json!("client_update_required"));
    assert!(body.get("entities").is_some(), "{body}");

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
async fn client_version_gate_fail_open_when_apk_sha256_mismatches_metadata() {
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
        "/v1/pull?cursor=0&generation=generation-a",
        Some(&token),
        json!({}),
        &[("x-lezi-client-version-code", "1")],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_ne!(body["code"], json!("client_update_required"));
    assert!(body.get("entities").is_some(), "{body}");

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
        12
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
        12
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
            PRAGMA user_version = 13;
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
        13
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
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            other_token,
            entity_wire("care_plan", &plan_id, 11, same_member_edit, None),
        )
        .await
        .0,
        StatusCode::FORBIDDEN,
    );

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
        config.lan_apk_download_origin = Some("http://192.168.50.4:8767".to_owned());
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
    assert_eq!(grant["landing_url"], "http://192.168.50.4:8767/join",);

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
async fn current_wire_rejects_removed_compatibility_fields() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "strict-wire-owner",
        "strict-wire-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let (pull_status, pull_body) = get_json(&rig.app, "/v1/pull", Some(token)).await;
    assert_eq!(pull_status, StatusCode::UNPROCESSABLE_ENTITY, "{pull_body}");

    let obsolete_baby_id = Uuid::new_v4().to_string();
    let (due_date_status, due_date_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "baby",
            &obsolete_baby_id,
            1,
            json!({
                "nickname": "年年",
                "sex": "female",
                "birthday": "2025-01-02",
                "due_date": null,
                "avatar_media_uuid": null,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(
        due_date_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{due_date_body}"
    );

    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let mut obsolete_record = record_payload(&baby_id);
    obsolete_record["created_by_device_id"] = json!("strict-wire-owner");
    let (device_author_status, device_author_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("record", &record_id, 2, obsolete_record, None),
    )
    .await;
    assert_eq!(
        device_author_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{device_author_body}"
    );
}

#[tokio::test]
async fn current_sync_requests_require_generation_envelopes_and_keep_push_retired() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "strict-envelope-owner",
        "strict-envelope-owner-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    for body in [
        json!({"entities": []}),
        json!({"device_id": "strict-envelope-owner", "entities": []}),
        json!({"generation": "generation-a", "entities": []}),
    ] {
        let (status, response) =
            raw_json_request(&rig.app, Method::POST, "/v1/push", Some(token), body).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{response}");
    }

    let (pull_status, pull_body) = raw_get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(pull_status, StatusCode::UNPROCESSABLE_ENTITY, "{pull_body}");

    let baby_id = Uuid::new_v4().to_string();
    let (baby_status, baby_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(baby_status, StatusCode::OK, "{baby_body}");
    let bundle_id = Uuid::new_v4().to_string();
    let root_id = Uuid::new_v4().to_string();
    let payload = json!({
        "baby_client_uuid": baby_id,
        "type": "formula",
        "custom_item_client_uuid": null,
        "timestamp": 1,
        "end_timestamp": null,
        "note": null,
        "payload_json": {"amount_ml": 120},
        "schema_version": 2,
    });
    let (stage_status, stage_body) = raw_json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &root_id, 2, payload.clone(), None),
            "media": [],
        }),
    )
    .await;
    assert_eq!(
        stage_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{stage_body}"
    );

    let (valid_stage_status, valid_stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &root_id, 2, payload, None),
            "media": [],
            "generation": "generation-a",
        }),
    )
    .await;
    assert_eq!(valid_stage_status, StatusCode::OK, "{valid_stage_body}");

    let (commit_status, commit_body) = raw_json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(
        commit_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{commit_body}"
    );
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
async fn atomic_bundle_lww_cursor_and_generation_recovery_match_current_protocol() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "lww-owner-request-0000000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let first = json!({
        "type":"baby","client_uuid":baby_id,"updated_at":100,
        "payload":baby_payload("年年", None)
    });
    let (applied_status, applied) = publish_root_bundle(&rig.app, token, first.clone()).await;
    let (retry_status, retry) = publish_root_bundle(&rig.app, token, first.clone()).await;
    let (older_status, older) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 99, baby_payload("旧", None), None),
    )
    .await;
    let (newer_status, newer) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 101, baby_payload("年年", None), Some(101)),
    )
    .await;
    assert_eq!(applied_status, StatusCode::OK, "{applied}");
    assert_eq!(applied["applied"], 1);
    assert_eq!(applied["cursor"], 1);
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(retry["applied"], 0);
    assert_eq!(retry["cursor"], 1);
    assert_eq!(older_status, StatusCode::CONFLICT, "{older}");
    assert_eq!(newer_status, StatusCode::OK, "{newer}");
    assert_eq!(newer["applied"], 1);
    assert_eq!(newer["cursor"], 2);
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=1", Some(token)).await;
    assert_eq!(pull["cursor"], 2);
    assert_eq!(pull["entities"][0]["rev"], 2);
    assert_eq!(pull["entities"][0]["deleted_at"], 101);
    let (ahead, detail) = get_json(&rig.app, "/v1/pull?cursor=3", Some(token)).await;
    assert_eq!(ahead, StatusCode::CONFLICT);
    assert_eq!(detail["detail"]["code"], "cursor_ahead");

    let restarted = rig.restart("generation-b");
    let (generation_status, generation_body) = get_json(
        &restarted,
        "/v1/pull?cursor=2&generation=generation-a",
        Some(token),
    )
    .await;
    assert_eq!(generation_status, StatusCode::CONFLICT);
    assert_eq!(generation_body["detail"]["code"], "generation_changed");
    let (stale_stage, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": Uuid::new_v4(),
            "generation":"generation-a",
            "root": entity_wire("baby", &Uuid::new_v4().to_string(), 200, baby_payload("圆圆", None), None),
            "media": []
        }),
    )
    .await;
    assert_eq!(stale_stage, StatusCode::CONFLICT);
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
    for entity in entities {
        let (publish_status, publish_body) = publish_root_bundle(&rig.app, token, entity).await;
        assert_eq!(publish_status, StatusCode::OK, "{publish_body}");
    }

    let (first_status, first) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(first_status, StatusCode::OK);
    assert_eq!(first["entities"].as_array().unwrap().len(), 200);
    assert_eq!(first["cursor"], 200);
    assert_eq!(first["has_more"], true);
    assert_eq!(first["family_name"], "分页家庭");

    let (second_status, second) = get_json(&rig.app, "/v1/pull?cursor=200", Some(token)).await;
    assert_eq!(second_status, StatusCode::OK);
    assert_eq!(second["entities"].as_array().unwrap().len(), 1);
    assert_eq!(second["cursor"], 201);
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
        seed_record_with_id(
            &rig.app,
            token,
            record_id,
            (index + 1) as i64,
            record_payload(&baby_id),
        )
        .await;
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
async fn push_rejects_terminal_updated_at() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "timestamp-owner-request-00000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();

    let (status, _) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "baby",
            &Uuid::new_v4().to_string(),
            i64::MAX,
            baby_payload("年年", None),
            None,
        ),
    )
    .await;

    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn push_rejects_updated_at_more_than_24_hours_ahead() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "future-time-owner-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let server_now_millis = rig.now.load(Ordering::SeqCst) * 1_000;

    let (status, _) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "baby",
            &Uuid::new_v4().to_string(),
            server_now_millis + 86_400_001,
            baby_payload("年年", None),
            None,
        ),
    )
    .await;

    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn baby_nickname_accepts_20_unicode_chars_and_rejects_21() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "nickname-owner-request-0000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();

    let nickname_20 = "年".repeat(20);
    let (accepted, body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload(&nickname_20, None), None),
    )
    .await;
    assert_eq!(accepted, StatusCode::OK, "{body}");

    let nickname_21 = "年".repeat(21);
    let (rejected, body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 2, baby_payload(&nickname_21, None), None),
    )
    .await;
    assert_eq!(rejected, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
}

#[tokio::test]
async fn strict_atomic_entity_contract_rejects_legacy_fields_and_orders_dependencies() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "strict-owner-request-0000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    assert_eq!(
        json_request(&rig.app, Method::POST, "/v1/bundles", None, json!({}),)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    let (unknown_type, _) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("settings", &Uuid::new_v4().to_string(), 1, json!({}), None),
        vec![],
    )
    .await;
    assert_eq!(unknown_type, StatusCode::UNPROCESSABLE_ENTITY);

    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let (unresolved, unresolved_body) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 1, record_payload(&baby_id), None),
        vec![],
    )
    .await;
    assert_eq!(unresolved, StatusCode::CONFLICT, "{unresolved_body}");
    assert!(
        unresolved_body["detail"]
            .as_str()
            .unwrap_or_default()
            .contains("baby_client_uuid does not exist"),
        "{unresolved_body}"
    );
    let mut legacy_baby = baby_payload("年年", None);
    legacy_baby["sort_order"] = json!(99);
    let (legacy_status, legacy_result) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, legacy_baby, None),
    )
    .await;
    assert_eq!(
        legacy_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{legacy_result}"
    );

    let baby = baby_payload("年年", None);
    let (baby_push, baby_result) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby, None),
    )
    .await;
    assert_eq!(baby_push, StatusCode::OK, "{baby_result}");
    seed_record_then_log_media(&rig.app, token, &record_id, &media_id, &baby_id, 1).await;
    let (_, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let types = pulled["entities"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entity| entity["type"].as_str().unwrap())
        .collect::<Vec<_>>();
    assert_eq!(types, ["baby", "media", "record"]);
    assert!(pulled["entities"][0]["payload"]
        .as_object()
        .unwrap()
        .get("sort_order")
        .is_none());
    let seed_cursor = pulled["cursor"].as_i64().unwrap();

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
    let (_, after_bytes) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor={seed_cursor}"),
        Some(token),
    )
    .await;
    assert!(after_bytes["entities"].as_array().unwrap().is_empty());
    let canonical_media = pulled["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["client_uuid"] == media_id)
        .unwrap()["payload"]
        .as_object()
        .unwrap();
    assert_eq!(
        canonical_media
            .keys()
            .map(String::as_str)
            .collect::<BTreeSet<_>>(),
        BTreeSet::from([
            "baby_client_uuid",
            "byte_size",
            "care_plan_client_uuid",
            "height",
            "kind",
            "mime",
            "record_client_uuid",
            "width",
        ]),
    );
    assert!(canonical_media["baby_client_uuid"].is_null());
    assert!(canonical_media["care_plan_client_uuid"].is_null());
    assert!(canonical_media["width"].is_null());
    assert!(canonical_media["height"].is_null());
    assert_eq!(after_bytes["cursor"], seed_cursor);
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
    assert_eq!(entities[0]["deleted_at"], 2);
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
async fn media_bytes_size_acl_and_immutable_association_are_enforced() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "media-owner-request-00000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let second_baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let log_id = Uuid::new_v4().to_string();
    let avatar_id = Uuid::new_v4().to_string();
    let (seeded, body) = publish_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire(
            "baby",
            &baby_id,
            1,
            baby_payload("年年", Some(&avatar_id)),
            None,
        ),
        vec![(
            entity_wire("media", &avatar_id, 3, avatar_media_payload(&baby_id), None),
            b"avatar".to_vec(),
        )],
    )
    .await;
    assert_eq!(seeded, StatusCode::OK, "{body}");
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            owner_token,
            entity_wire("baby", &second_baby_id, 1, baby_payload("二宝", None), None,),
        )
        .await
        .0,
        StatusCode::OK
    );
    seed_record_then_log_media(&rig.app, owner_token, &record_id, &log_id, &baby_id, 1).await;

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{log_id}"),
        Some(member_token),
        Body::from("log"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(uploaded.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let downloaded = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{log_id}"),
        Some(member_token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(downloaded.status(), StatusCode::OK);
    assert_eq!(
        downloaded.into_body().collect().await.unwrap().to_bytes(),
        "log"
    );
    let oversized = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{log_id}"),
        Some(member_token),
        Body::from("123456789"),
        None,
    )
    .await;
    assert_eq!(oversized.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let denied_avatar = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{avatar_id}"),
        Some(member_token),
        Body::from("avatar"),
        None,
    )
    .await;
    assert_eq!(denied_avatar.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let accepted_avatar = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{avatar_id}"),
        Some(owner_token),
        Body::from("avatar"),
        None,
    )
    .await;
    assert_eq!(accepted_avatar.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let (member_bypass, _) = publish_bundle_with_media(
        &rig.app,
        member_token,
        entity_wire(
            "baby",
            &baby_id,
            4,
            baby_payload("年年", Some(&avatar_id)),
            None,
        ),
        vec![(
            entity_wire("media", &avatar_id, 4, avatar_media_payload(&baby_id), None),
            b"avatar".to_vec(),
        )],
    )
    .await;
    assert_eq!(member_bypass, StatusCode::FORBIDDEN);
    let (owner_reassociate, _) = publish_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire(
            "baby",
            &second_baby_id,
            4,
            baby_payload("二宝", Some(&avatar_id)),
            None,
        ),
        vec![(
            entity_wire(
                "media",
                &avatar_id,
                4,
                avatar_media_payload(&second_baby_id),
                None,
            ),
            b"avatar".to_vec(),
        )],
    )
    .await;
    assert_eq!(owner_reassociate, StatusCode::CONFLICT);
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
async fn media_metadata_enforces_server_byte_limit_and_android_dimension_bounds() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "bounded-media-owner-request-0000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let boundary_media_id = Uuid::new_v4().to_string();
    let mut boundary_payload = log_media_payload(&record_id);
    boundary_payload["byte_size"] = json!(8);
    boundary_payload["width"] = json!(i32::MAX);
    boundary_payload["height"] = json!(i32::MAX);

    let (baby_push, baby_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
    let (accepted, body) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
        vec![entity_wire(
            "media",
            &boundary_media_id,
            2,
            boundary_payload,
            None,
        )],
    )
    .await;
    assert_eq!(accepted, StatusCode::OK, "{body}");

    for (field, invalid_value) in [
        ("byte_size", json!(9)),
        ("width", json!(i64::from(i32::MAX) + 1)),
        ("height", json!(i64::from(i32::MAX) + 1)),
    ] {
        let mut payload = log_media_payload(&record_id);
        payload[field] = invalid_value;
        let (status, body) = stage_bundle_with_media(
            &rig.app,
            token,
            entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
            vec![entity_wire(
                "media",
                &Uuid::new_v4().to_string(),
                2,
                payload,
                None,
            )],
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{field}: {body}");
    }
}

#[tokio::test]
async fn media_upload_rejects_empty_or_mismatched_body() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "empty-media-owner-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (baby_push, baby_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
            "media": [entity_wire("media", &media_id, 2, log_media_payload(&record_id), None)],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(token),
        Body::empty(),
        Some("application/octet-stream"),
    )
    .await;

    assert_eq!(uploaded.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let mismatched = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(token),
        Body::from("no"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(mismatched.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let downloaded = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(downloaded.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn member_cannot_create_update_or_tombstone_baby() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "avatar-owner-request-0000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let avatar_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_bundle_with_media(
            &rig.app,
            owner_token,
            entity_wire(
                "baby",
                &baby_id,
                1,
                baby_payload("年年", Some(&avatar_id)),
                None,
            ),
            vec![(
                entity_wire("media", &avatar_id, 1, avatar_media_payload(&baby_id), None,),
                b"avatar".to_vec(),
            )],
        )
        .await
        .0,
        StatusCode::OK
    );
    for entity in [
        json!({
            "type":"baby","client_uuid":Uuid::new_v4().to_string(),"updated_at":2,
            "payload":baby_payload("禁止新建", None)
        }),
        json!({
            "type":"baby","client_uuid":baby_id,"updated_at":3,
            "payload":baby_payload("禁止改名", Some(&avatar_id))
        }),
        json!({
            "type":"baby","client_uuid":baby_id,"updated_at":4,"deleted_at":4,
            "payload":baby_payload("禁止删除", Some(&avatar_id))
        }),
        json!({
            "type":"baby","client_uuid":baby_id,"updated_at":5,
            "payload":baby_payload("禁止清空", None)
        }),
    ] {
        let (status, _) = publish_root_bundle(&rig.app, member_token, entity).await;
        assert_eq!(status, StatusCode::FORBIDDEN);
    }
}

#[tokio::test]
async fn stale_member_avatar_snapshot_does_not_block_newer_record() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "stale-avatar-owner-request-0000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let first_avatar_id = Uuid::new_v4().to_string();
    let current_avatar_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let (first_status, first_body) = publish_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire(
            "baby",
            &baby_id,
            100,
            baby_payload("年年", Some(&first_avatar_id)),
            None,
        ),
        vec![(
            entity_wire(
                "media",
                &first_avatar_id,
                100,
                avatar_media_payload(&baby_id),
                None,
            ),
            b"avatar".to_vec(),
        )],
    )
    .await;
    assert_eq!(first_status, StatusCode::OK, "{first_body}");
    let (seeded, seeded_body) = publish_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire(
            "baby",
            &baby_id,
            300,
            baby_payload("年年", Some(&current_avatar_id)),
            None,
        ),
        vec![(
            entity_wire(
                "media",
                &current_avatar_id,
                300,
                avatar_media_payload(&baby_id),
                None,
            ),
            b"avatar".to_vec(),
        )],
    )
    .await;
    assert_eq!(seeded, StatusCode::OK, "{seeded_body}");

    // Even a stale member Baby snapshot is forbidden; a following Record is unaffected.
    let (stale_baby, stale_body) = publish_root_bundle(
        &rig.app,
        member_token,
        entity_wire(
            "baby",
            &baby_id,
            200,
            baby_payload("旧快照", Some(&first_avatar_id)),
            None,
        ),
    )
    .await;
    assert_eq!(stale_baby, StatusCode::FORBIDDEN, "{stale_body}");
    seed_record_with_id(
        &rig.app,
        member_token,
        &record_id,
        400,
        record_payload(&baby_id),
    )
    .await;
    let (_, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let entities = pulled["entities"].as_array().unwrap();
    let baby = entities
        .iter()
        .find(|entity| entity["client_uuid"] == baby_id)
        .unwrap();
    let record = entities
        .iter()
        .find(|entity| entity["client_uuid"] == record_id)
        .unwrap();
    assert_eq!(
        baby["payload"]["avatar_media_uuid"],
        current_avatar_id.as_str()
    );
    assert_eq!(record["payload"]["baby_client_uuid"], baby_id.as_str());
}

#[tokio::test]
async fn media_limit_stops_stream_and_family_delete_waits_for_upload() {
    let root = "stream-delete-root-password";
    let rig = Rig::with_config(|config| config.bootstrap_secret = Some(root.to_owned()));
    let owner = create_family_with_root(
        &rig.app,
        "owner-device",
        "stream-owner-request-00000000000001",
        root,
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let family_id = owner["family_id"].as_str().unwrap().to_owned();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    {
        let (baby_push, baby_body) = publish_root_bundle(
            &rig.app,
            &token,
            entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
        )
        .await;
        assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
        let (stage_status, stage_body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(&token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
                "media": [entity_wire("media", &media_id, 2, log_media_payload(&record_id), None)],
            }),
        )
        .await;
        assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    }

    let oversized_stream = futures_util::stream::unfold(0, |index| async move {
        match index {
            0 => Some((Ok::<_, std::io::Error>(Bytes::from_static(b"12345")), 1)),
            1 => Some((Ok(Bytes::from_static(b"6789")), 2)),
            _ => panic!("server consumed beyond the configured media limit"),
        }
    });
    let oversized = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(&token),
        Body::from_stream(oversized_stream),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);

    let (started_tx, started_rx) = oneshot::channel();
    let (release_tx, release_rx) = oneshot::channel();
    let upload_stream = futures_util::stream::unfold(
        (0, Some(started_tx), Some(release_rx)),
        |(index, mut started, mut release)| async move {
            match index {
                0 => {
                    started.take().unwrap().send(()).ok();
                    Some((
                        Ok::<_, std::io::Error>(Bytes::from_static(b"lo")),
                        (1, started, release),
                    ))
                }
                1 => {
                    release.take().unwrap().await.ok();
                    Some((Ok(Bytes::from_static(b"g")), (2, started, release)))
                }
                _ => None,
            }
        },
    );
    let upload_app = rig.app.clone();
    let upload_token = token.clone();
    let upload_uri = format!("/v1/bundles/{bundle_id}/media/{media_id}");
    let upload = tokio::spawn(async move {
        request(
            &upload_app,
            Method::PUT,
            &upload_uri,
            Some(&upload_token),
            Body::from_stream(upload_stream),
            Some("application/octet-stream"),
        )
        .await
    });
    started_rx.await.unwrap();

    let delete_app = rig.app.clone();
    let delete_token = token.clone();
    let deletion = tokio::spawn(async move {
        request_with_headers(
            &delete_app,
            Method::POST,
            "/v1/family/delete",
            Some(&delete_token),
            Body::from(r#"{"family_name":"测试家庭"}"#),
            Some("application/json"),
            &[("x-lezi-bootstrap-secret", root)],
        )
        .await
    });
    tokio::task::yield_now().await;
    assert!(!deletion.is_finished());
    release_tx.send(()).unwrap();
    assert_eq!(upload.await.unwrap().status(), StatusCode::OK);
    assert_eq!(deletion.await.unwrap().status(), StatusCode::OK);
    assert!(!rig.directory.path().join("media").join(family_id).exists());
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
async fn failed_root_passwords_share_a_per_source_budget_across_admin_endpoints() {
    let root = "production-root-password";
    let rig = Rig::with_config(|config| {
        config.bootstrap_secret = Some(root.to_owned());
        config.create_rate_limit = RateLimitConfig {
            max_attempts: 2,
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
            login,
            &[("x-lezi-bootstrap-secret", root)],
        )
        .await
        .0,
        StatusCode::OK,
        "a correct password is not locked out by failed-guess throttling",
    );
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
async fn pull_omits_the_entire_atomic_bundle_until_media_bytes_are_committed() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "integrity-owner-request-00000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (baby_push, baby_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(baby_push, StatusCode::OK, "{baby_body}");
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
            "media": [entity_wire("media", &media_id, 2, log_media_payload(&record_id), None)],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    let (status, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK);
    let seed_cursor = pulled["cursor"].as_i64().unwrap();
    let entities = pulled["entities"].as_array().unwrap();
    assert_eq!(entities.len(), 1);
    assert_eq!(entities[0]["type"], "baby");

    let missing = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(token),
        Body::from("log"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(uploaded.status(), StatusCode::OK);

    let (still_hidden_status, still_hidden) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor={seed_cursor}"),
        Some(token),
    )
    .await;
    assert_eq!(still_hidden_status, StatusCode::OK);
    assert_eq!(still_hidden["cursor"], seed_cursor);
    assert!(still_hidden["entities"].as_array().unwrap().is_empty());

    let (commit_status, commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");

    let (status, ready) = get_json(
        &rig.app,
        &format!("/v1/pull?cursor={seed_cursor}"),
        Some(token),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(ready["cursor"].as_i64().unwrap() > seed_cursor);
    let ready_entities = ready["entities"].as_array().unwrap();
    assert_eq!(ready_entities.len(), 2);
    assert!(ready_entities
        .iter()
        .any(|entity| entity["client_uuid"] == media_id));

    let downloaded = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(downloaded.status(), StatusCode::OK);
    assert_eq!(
        downloaded.into_body().collect().await.unwrap().to_bytes(),
        "log"
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

#[tokio::test]
async fn corrupt_ready_media_is_removed_and_not_advertised_until_reuploaded() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "corrupt-media-owner-request-0000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let zero_media_id = Uuid::new_v4().to_string();
    let mismatched_media_id = Uuid::new_v4().to_string();

    let (pushed, body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(pushed, StatusCode::OK, "{body}");
    let (media_push, media_body) = publish_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
        vec![
            (
                entity_wire(
                    "media",
                    &zero_media_id,
                    2,
                    log_media_payload(&record_id),
                    None,
                ),
                b"log".to_vec(),
            ),
            (
                entity_wire(
                    "media",
                    &mismatched_media_id,
                    2,
                    log_media_payload(&record_id),
                    None,
                ),
                b"log".to_vec(),
            ),
        ],
    )
    .await;
    assert_eq!(media_push, StatusCode::OK, "{media_body}");

    let media_directory = rig.directory.path().join("media").join(family_id);
    fs::create_dir_all(&media_directory).unwrap();
    let zero_path = media_directory.join(&zero_media_id);
    let mismatched_path = media_directory.join(&mismatched_media_id);
    fs::write(&zero_path, []).unwrap();
    fs::write(&mismatched_path, b"four").unwrap();

    let (status, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(pulled["cursor"], 4);
    assert!(pulled["entities"]
        .as_array()
        .unwrap()
        .iter()
        .all(|entity| entity["type"] != "media"));
    assert!(!zero_path.exists());
    assert!(!mismatched_path.exists());

    for media_id in [&zero_media_id, &mismatched_media_id] {
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

    let (uploaded_status, uploaded_body) = publish_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 3, record_payload(&baby_id), None),
        vec![(
            entity_wire(
                "media",
                &zero_media_id,
                3,
                log_media_payload(&record_id),
                None,
            ),
            b"log".to_vec(),
        )],
    )
    .await;
    assert_eq!(uploaded_status, StatusCode::OK, "{uploaded_body}");
    let (_, ready) = get_json(&rig.app, "/v1/pull?cursor=4", Some(token)).await;
    assert_eq!(ready["cursor"], 6);
    assert!(ready["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == zero_media_id));
}

// ---------------------------------------------------------------------------
// Atomic bundle protocol
// ---------------------------------------------------------------------------

#[tokio::test]
async fn protocol_cutover_validates_defers_and_resolves_legacy_fulfillment_for_peer() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "cutover-owner",
        "cutover-owner-request-000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let peer = approve_new_member(&rig.app, owner_token, "cutover-peer").await;
    let peer_token = peer["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let clean_record_id = seed_record(&rig.app, owner_token, &baby_id).await;
    let plan_id = Uuid::new_v4().to_string();
    let missing_record_id = Uuid::new_v4().to_string();
    let plan_media_id = Uuid::new_v4().to_string();
    let mut completed_plan = care_plan_payload(&baby_id, "formula");
    completed_plan["status"] = json!("completed");
    completed_plan["fulfilled_record_client_uuid"] = json!(missing_record_id);
    completed_plan["fulfilled_at"] = json!(100);
    let (plan_status, plan_body) = publish_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire("care_plan", &plan_id, 3, completed_plan, None),
        vec![(
            entity_wire(
                "media",
                &plan_media_id,
                3,
                json!({
                    "kind":"log","record_client_uuid":null,
                    "care_plan_client_uuid":plan_id,"baby_client_uuid":null,
                    "mime":"image/jpeg","width":1,"height":1,"byte_size":3
                }),
                None,
            ),
            b"img".to_vec(),
        )],
    )
    .await;
    assert_eq!(plan_status, StatusCode::OK, "{plan_body}");

    let (_, before_restart) = get_json(&rig.app, "/v1/pull?cursor=0", Some(peer_token)).await;
    let before_cursor = before_restart["cursor"].as_i64().unwrap();
    assert!(before_restart["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == clean_record_id));
    assert!(before_restart["entities"]
        .as_array()
        .unwrap()
        .iter()
        .all(|entity| entity["client_uuid"] != plan_id && entity["client_uuid"] != plan_media_id));

    let upgraded = rig.restart("generation-0.3.9");
    let (_, health) = get_json(&upgraded, "/health", None).await;
    assert!(health["capabilities"]
        .as_array()
        .unwrap()
        .contains(&json!("validated_deferred_fulfillment_v1")));
    let (generation_status, generation_body) = get_json(
        &upgraded,
        &format!("/v1/pull?cursor={before_cursor}&generation=generation-a"),
        Some(peer_token),
    )
    .await;
    assert_eq!(generation_status, StatusCode::CONFLICT, "{generation_body}");
    assert_eq!(generation_body["detail"]["action"], "full_resync");
    for token in [owner_token, peer_token] {
        test_client_sessions()
            .lock()
            .unwrap()
            .get_mut(token)
            .unwrap()
            .generation = "generation-0.3.9".to_owned();
    }

    let (_, deferred) = get_json(&upgraded, "/v1/pull?cursor=0", Some(peer_token)).await;
    let deferred_cursor = deferred["cursor"].as_i64().unwrap();
    assert!(deferred["entities"]
        .as_array()
        .unwrap()
        .iter()
        .all(|entity| entity["client_uuid"] != plan_id && entity["client_uuid"] != plan_media_id));

    let record_bundle_id = Uuid::new_v4().to_string();
    let (record_stage_status, record_stage_body) = json_request(
        &upgraded,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": record_bundle_id,
            "root": entity_wire(
                "record",
                &missing_record_id,
                4,
                record_payload(&baby_id),
                None,
            ),
            "media": [],
        }),
    )
    .await;
    assert_eq!(record_stage_status, StatusCode::OK, "{record_stage_body}");
    let plan_media_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(&plan_media_id);
    fs::write(&plan_media_path, b"bad").unwrap();
    let corrupt_resolution = json_request(
        &upgraded,
        Method::POST,
        &format!("/v1/bundles/{record_bundle_id}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(
        corrupt_resolution.0,
        StatusCode::UNPROCESSABLE_ENTITY,
        "deferred plan media corruption must block resolution: {}",
        corrupt_resolution.1,
    );
    fs::write(&plan_media_path, b"img").unwrap();
    let repaired_resolution = json_request(
        &upgraded,
        Method::POST,
        &format!("/v1/bundles/{record_bundle_id}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(
        repaired_resolution.0,
        StatusCode::OK,
        "{repaired_resolution:?}"
    );
    let (_, resolved) = get_json(
        &upgraded,
        &format!("/v1/pull?cursor={deferred_cursor}"),
        Some(peer_token),
    )
    .await;
    let resolved_ids = resolved["entities"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entity| entity["client_uuid"].as_str().unwrap())
        .collect::<BTreeSet<_>>();
    assert!(resolved_ids.contains(missing_record_id.as_str()));
    assert!(resolved_ids.contains(plan_id.as_str()));
    assert!(resolved_ids.contains(plan_media_id.as_str()));
}

#[test]
fn protocol_cutover_requires_a_verified_forced_update_channel() {
    let missing = TempDir::new().unwrap();
    let mut missing_config = ServerConfig::new(missing.path());
    missing_config.require_protocol_cutover_release = true;
    assert!(
        build_app(missing_config).is_err(),
        "0.3.13 production startup accepted a missing forced-update channel",
    );

    let valid = TempDir::new().unwrap();
    let apk_bytes = b"verified-0.3.13-release-channel";
    fs::write(valid.path().join("app-release.apk"), apk_bytes).unwrap();
    fs::write(
        valid.path().join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 20,
            "version_name": "0.3.13",
            "min_supported_version_code": 20,
            "sha256": hex::encode(Sha256::digest(apk_bytes)),
        })
        .to_string(),
    )
    .unwrap();
    let mut valid_config = ServerConfig::new(valid.path());
    valid_config.require_protocol_cutover_release = true;
    assert!(build_app(valid_config).is_ok());

    // Floor 16 is not a valid 0.3.13 production cutover channel.
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
        "0.3.13 production must reject min_supported/version_code below 20"
    );
}

#[tokio::test]
async fn protocol_cutover_rejects_candidate_without_server_stamped_evidence() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "cutover-candidate-owner",
        "cutover-candidate-request-000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = seed_record(&rig.app, token, &baby_id).await;
    let plan_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
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
    let candidate_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire(
                "fulfillment_candidate",
                &candidate_id,
                3,
                json!({
                    "care_plan_client_uuid": plan_id,
                    "record_client_uuid": record_id,
                    "actual_timestamp": 100,
                }),
                None,
            ),
        )
        .await
        .0,
        StatusCode::OK,
    );
    assert!(
        build_app(ServerConfig::new(rig.directory.path())).is_ok(),
        "valid server-stamped candidate must pass startup validation",
    );
    Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .execute(
            "UPDATE entities SET payload_json = json_remove(payload_json, '$.confirmed_at') WHERE entity_type = 'fulfillment_candidate' AND client_uuid = ?1",
            [&candidate_id],
        )
        .unwrap();

    assert!(
        build_app(ServerConfig::new(rig.directory.path())).is_err(),
        "candidate without immutable server stamp must block startup",
    );
}

#[tokio::test]
async fn protocol_cutover_refuses_ready_when_deferred_fulfillment_evidence_is_missing() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "cutover-invalid-owner",
        "cutover-invalid-request-0000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4().to_string();
    let missing_record_id = Uuid::new_v4().to_string();
    let mut completed_plan = care_plan_payload(&baby_id, "bath");
    completed_plan["status"] = json!("completed");
    completed_plan["fulfilled_record_client_uuid"] = json!(missing_record_id);
    completed_plan["fulfilled_at"] = json!(100);
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire("care_plan", &plan_id, 2, completed_plan, None),
        )
        .await
        .0,
        StatusCode::OK,
    );
    Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .execute(
            "DELETE FROM sync_bundles WHERE root_type = 'care_plan' AND root_client_uuid = ?1",
            [&plan_id],
        )
        .unwrap();

    let mut config = ServerConfig::new(rig.directory.path());
    config.generation = Some("generation-invalid-cutover".to_owned());
    config.max_media_bytes = 8;
    let result = build_app(config);

    assert!(
        result.is_err(),
        "invalid authority graph must never become ready"
    );
}

#[tokio::test]
async fn protocol_cutover_refuses_deferred_bundle_with_missing_manifest_entity() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "cutover-manifest-owner",
        "cutover-manifest-request-00000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4().to_string();
    let missing_record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let mut completed_plan = care_plan_payload(&baby_id, "bath");
    completed_plan["status"] = json!("completed");
    completed_plan["fulfilled_record_client_uuid"] = json!(missing_record_id);
    completed_plan["fulfilled_at"] = json!(100);
    assert_eq!(
        publish_bundle_with_media(
            &rig.app,
            token,
            entity_wire("care_plan", &plan_id, 2, completed_plan, None),
            vec![(
                entity_wire(
                    "media",
                    &media_id,
                    2,
                    json!({
                        "kind":"log","record_client_uuid":null,
                        "care_plan_client_uuid":plan_id,"baby_client_uuid":null,
                        "mime":"image/jpeg","width":1,"height":1,"byte_size":3
                    }),
                    None,
                ),
                b"img".to_vec(),
            )],
        )
        .await
        .0,
        StatusCode::OK,
    );
    Connection::open(rig.directory.path().join("lezi.db"))
        .unwrap()
        .execute(
            "DELETE FROM entities WHERE entity_type = 'media' AND client_uuid = ?1",
            [&media_id],
        )
        .unwrap();

    assert!(
        build_app(ServerConfig::new(rig.directory.path())).is_err(),
        "missing canonical media from retained bundle manifest must block startup",
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

/// Publish a Record via atomic bundle (current wire: records never use ordinary push).
async fn seed_record(app: &Router, token: &str, baby_id: &str) -> String {
    seed_record_at(app, token, baby_id, 1, record_payload(baby_id)).await
}

async fn seed_record_with_id(
    app: &Router,
    token: &str,
    record_id: &str,
    updated_at: i64,
    payload: Value,
) {
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", record_id, updated_at, payload, None),
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    let (commit_status, commit_body) = json_request(
        app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");
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

async fn seed_record_at(
    app: &Router,
    token: &str,
    _baby_id: &str,
    updated_at: i64,
    payload: Value,
) -> String {
    let record_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &record_id, updated_at, payload, None),
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    let (commit_status, commit_body) = json_request(
        app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");
    record_id
}

async fn publish_root_bundle(app: &Router, token: &str, root: Value) -> (StatusCode, Value) {
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": root,
            "media": [],
        }),
    )
    .await;
    if stage_status != StatusCode::OK {
        return (stage_status, stage_body);
    }
    json_request(
        app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await
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
    let bundle_id = Uuid::new_v4().to_string();
    let manifest = media
        .iter()
        .map(|(entity, _)| entity.clone())
        .collect::<Vec<_>>();
    let (stage_status, stage_body) = json_request(
        app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": root,
            "media": manifest,
        }),
    )
    .await;
    if stage_status != StatusCode::OK {
        return (stage_status, stage_body);
    }
    for (entity, bytes) in media {
        let media_id = entity["client_uuid"]
            .as_str()
            .expect("media fixture has client_uuid");
        let response = request(
            app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(token),
            Body::from(bytes),
            Some("application/octet-stream"),
        )
        .await;
        let status = response.status();
        if status != StatusCode::OK {
            let body = response.into_body().collect().await.unwrap().to_bytes();
            return (
                status,
                serde_json::from_slice(&body).unwrap_or_else(|_| json!({})),
            );
        }
    }
    json_request(
        app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await
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
async fn expired_open_staging_bundles_are_collected_before_enforcing_the_family_cap() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "staging-gc-owner-device",
        "staging-gc-owner-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let refresh_token = owner["refresh_token"].as_str().unwrap().to_owned();
    let baby_id = seed_baby(&rig.app, token).await;
    let first_bundle_id = Uuid::new_v4().to_string();

    for index in 0..64 {
        let bundle_id = if index == 0 {
            first_bundle_id.clone()
        } else {
            Uuid::new_v4().to_string()
        };
        let (status, body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "record",
                    &Uuid::new_v4().to_string(),
                    2 + index,
                    record_payload(&baby_id),
                    None,
                ),
                "media": [],
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "stage {index}: {body}");
    }

    rig.now.fetch_add(24 * 60 * 60 + 1, Ordering::SeqCst);
    let (refresh_status, refreshed) = json_request(
        &rig.app,
        Method::POST,
        "/v1/session/refresh",
        None,
        json!({"refresh_token": refresh_token}),
    )
    .await;
    assert_eq!(refresh_status, StatusCode::OK, "{refreshed}");
    let token = refreshed["access_token"].as_str().unwrap();
    let (replacement_status, replacement) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": Uuid::new_v4(),
            "generation": "generation-a",
            "root": entity_wire(
                "record",
                &Uuid::new_v4().to_string(),
                100,
                record_payload(&baby_id),
                None,
            ),
            "media": [],
        }),
    )
    .await;
    assert_eq!(replacement_status, StatusCode::OK, "{replacement}");
    let (expired_status, expired) = get_json(
        &rig.app,
        &format!("/v1/bundles/{first_bundle_id}"),
        Some(token),
    )
    .await;
    assert_eq!(expired_status, StatusCode::NOT_FOUND, "{expired}");
}

#[tokio::test]
async fn stalled_bundle_body_does_not_hold_the_family_lock_against_pull() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "slow-upload-owner-device",
        "slow-upload-owner-request-000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let baby_id = seed_baby(&rig.app, &token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(&token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
            "media": [entity_wire(
                "media",
                &media_id,
                2,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    let (body_polled_tx, body_polled_rx) = oneshot::channel();
    let (release_body_tx, release_body_rx) = oneshot::channel();
    let body_stream = futures_util::stream::once(async move {
        let _ = body_polled_tx.send(());
        let _ = release_body_rx.await;
        Ok::<Bytes, std::convert::Infallible>(Bytes::from_static(b"img"))
    });
    let upload_app = rig.app.clone();
    let upload_token = token.clone();
    let upload_path = format!("/v1/bundles/{bundle_id}/media/{media_id}");
    let upload = tokio::spawn(async move {
        request(
            &upload_app,
            Method::PUT,
            &upload_path,
            Some(&upload_token),
            Body::from_stream(body_stream),
            Some("image/jpeg"),
        )
        .await
    });
    body_polled_rx.await.unwrap();

    let pull = tokio::time::timeout(
        Duration::from_millis(500),
        get_json(&rig.app, "/v1/pull?cursor=0", Some(&token)),
    )
    .await;
    assert!(
        pull.is_ok(),
        "pull waited on an upload body that had not arrived"
    );

    release_body_tx.send(()).unwrap();
    assert_eq!(upload.await.unwrap().status(), StatusCode::OK);
}

#[tokio::test]
async fn every_current_entity_root_can_publish_only_through_atomic_bundles() {
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
async fn atomic_bundle_media_must_match_its_root_kind_and_identity() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-media-matrix-owner",
        "bundle-media-matrix-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire("baby", &baby_id, 1, baby_payload("岁岁", None), None),
        )
        .await
        .0,
        StatusCode::OK,
    );

    let record_id = Uuid::new_v4().to_string();
    let wrong_record_id = Uuid::new_v4().to_string();
    let (wrong_parent_status, wrong_parent_body) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
        vec![entity_wire(
            "media",
            &Uuid::new_v4().to_string(),
            2,
            log_media_payload(&wrong_record_id),
            None,
        )],
    )
    .await;
    assert_eq!(
        wrong_parent_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{wrong_parent_body}"
    );

    let (wrong_kind_status, wrong_kind_body) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 3, baby_payload("岁岁", None), None),
        vec![entity_wire(
            "media",
            &Uuid::new_v4().to_string(),
            3,
            log_media_payload(&record_id),
            None,
        )],
    )
    .await;
    assert_eq!(
        wrong_kind_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{wrong_kind_body}"
    );

    let (custom_status, custom_body) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire(
            "custom_item",
            &Uuid::new_v4().to_string(),
            4,
            json!({"name": "抚触", "icon_slot": 2}),
            None,
        ),
        vec![entity_wire(
            "media",
            &Uuid::new_v4().to_string(),
            4,
            avatar_media_payload(&baby_id),
            None,
        )],
    )
    .await;
    assert_eq!(
        custom_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{custom_body}"
    );

    let (record_status, record_body) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("record", &record_id, 5, record_payload(&baby_id), None),
        vec![entity_wire(
            "media",
            &Uuid::new_v4().to_string(),
            5,
            log_media_payload(&record_id),
            None,
        )],
    )
    .await;
    assert_eq!(record_status, StatusCode::OK, "{record_body}");

    let avatar_id = Uuid::new_v4().to_string();
    let mut baby_with_avatar = baby_payload("岁岁", Some(&avatar_id));
    baby_with_avatar["birth_weight_grams"] = json!(3250);
    let (baby_status, baby_body) = stage_bundle_with_media(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 6, baby_with_avatar, None),
        vec![entity_wire(
            "media",
            &avatar_id,
            6,
            avatar_media_payload(&baby_id),
            None,
        )],
    )
    .await;
    assert_eq!(baby_status, StatusCode::OK, "{baby_body}");
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
async fn authoritative_reconcile_is_authenticated_bounded_and_generation_scoped() {
    let rig = Rig::new();
    let joined = create_family(
        &rig.app,
        "reconcile-owner-device",
        "reconcile-owner-request-000000001",
    )
    .await;
    let token = joined["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let unit = json!({
        "content_hash": "frozen-local-hash",
        "root": {
            "type": "baby",
            "client_uuid": baby_id,
            "updated_at": 1_753_418_400_000_i64,
            "deleted_at": null,
            "payload": baby_payload("年年", None),
        },
        "media": [],
    });

    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({"generation": "generation-a", "units": [unit.clone()]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["generation"], "generation-a");
    assert_eq!(body["results"][0]["disposition"], "publish");
    assert_eq!(body["results"][0]["reason"], "authoritative_absence");
    assert_eq!(
        body["results"][0]["request_content_hash"],
        "frozen-local-hash"
    );

    let (published_status, published) =
        publish_root_bundle(&rig.app, token, unit["root"].clone()).await;
    assert_eq!(published_status, StatusCode::OK, "{published}");
    let (adopt_status, adopt) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({
            "generation": "generation-a",
            "units": [{
                "content_hash": "stale-local-hash",
                "root": {
                    "type": "baby",
                    "client_uuid": baby_id,
                    "updated_at": 1_753_418_399_999_i64,
                    "deleted_at": null,
                    "payload": baby_payload("旧本机副本", None),
                },
                "media": [],
            }],
        }),
    )
    .await;
    assert_eq!(adopt_status, StatusCode::OK, "{adopt}");
    assert_eq!(adopt["results"][0]["disposition"], "adopt_remote");
    assert_eq!(adopt["results"][0]["remote_root"]["type"], "baby");
    assert!(adopt["results"][0]["remote_root"]
        .get("entity_type")
        .is_none());

    let (future_timestamp, future_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({
            "generation": "generation-a",
            "units": [{
                "content_hash": "future-timestamp-hash",
                "root": {
                    "type": "baby",
                    "client_uuid": Uuid::new_v4(),
                    "updated_at": 1_753_504_800_001_i64,
                    "deleted_at": null,
                    "payload": baby_payload("小宝", None),
                },
                "media": [],
            }],
        }),
    )
    .await;
    assert_eq!(
        future_timestamp,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{future_body}"
    );

    let (unauthenticated, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        None,
        json!({"generation": "generation-a", "units": [unit.clone()]}),
    )
    .await;
    assert_eq!(unauthenticated, StatusCode::UNAUTHORIZED);

    let (duplicate, duplicate_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({"generation": "generation-a", "units": [unit.clone(), unit]}),
    )
    .await;
    assert_eq!(
        duplicate,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{duplicate_body}"
    );

    let (drift, drift_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({"generation": "generation-old", "units": [{
            "content_hash": "other",
            "root": {
                "type": "baby",
                "client_uuid": Uuid::new_v4(),
                "updated_at": 1000,
                "deleted_at": null,
                "payload": baby_payload("小宝", None),
            },
            "media": [],
        }]}),
    )
    .await;
    assert_eq!(drift, StatusCode::CONFLICT, "{drift_body}");
    assert_eq!(drift_body["detail"]["code"], "generation_changed");
}

#[tokio::test]
async fn authoritative_reconcile_is_rate_limited_per_authenticated_device() {
    let rig = Rig::with_config(|config| {
        config.reconcile_rate_limit = RateLimitConfig {
            max_attempts: 1,
            window_seconds: 60,
        };
    });
    let joined = create_family(
        &rig.app,
        "reconcile-rate-device",
        "reconcile-rate-request-0000000001",
    )
    .await;
    let token = joined["access_token"].as_str().unwrap();
    let request_body = json!({
        "generation": "generation-a",
        "units": [{
            "content_hash": "rate-limited-frozen-hash",
            "root": {
                "type": "baby",
                "client_uuid": Uuid::new_v4(),
                "updated_at": 1000,
                "deleted_at": null,
                "payload": baby_payload("年年", None),
            },
            "media": [],
        }],
    });

    let (first, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        request_body.clone(),
    )
    .await;
    assert_eq!(first, StatusCode::OK);
    let (limited, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        request_body,
    )
    .await;
    assert_eq!(limited, StatusCode::TOO_MANY_REQUESTS, "{body}");
}

#[tokio::test]
async fn oversized_authoritative_reconcile_returns_a_structured_full_resync_checkpoint() {
    let rig = Rig::with_config(|config| config.max_reconcile_response_bytes = 64);
    let joined = create_family(
        &rig.app,
        "reconcile-size-device",
        "reconcile-size-request-0000000001",
    )
    .await;
    let token = joined["access_token"].as_str().unwrap();

    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({
            "generation": "generation-a",
            "units": [{
                "content_hash": "response-too-large",
                "root": {
                    "type": "baby",
                    "client_uuid": Uuid::new_v4(),
                    "updated_at": 1000,
                    "deleted_at": null,
                    "payload": baby_payload("年年", None),
                },
                "media": [],
            }],
        }),
    )
    .await;

    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert_eq!(body["detail"]["code"], "authority_response_too_large");
    assert_eq!(body["detail"]["action"], "full_resync");
    assert_eq!(body["detail"]["reset_cursor"], 0);
    assert_eq!(body["detail"]["server_generation"], "generation-a");
}

#[tokio::test]
async fn authoritative_reconcile_dependency_cycle_confirms_and_is_visible_to_peer() {
    let rig = Rig::new();
    let joined = create_family(
        &rig.app,
        "reconcile-cycle-owner",
        "reconcile-cycle-request-00000001",
    )
    .await;
    let token = joined["access_token"].as_str().unwrap();
    let membership_id = joined["membership_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let baby_root = entity_wire("baby", &baby_id, 100, baby_payload("年年", None), None);
    let mut record_body = record_payload(&baby_id);
    record_body["created_by_membership_id"] = json!(membership_id);
    let record_root = entity_wire("record", &record_id, 120, record_body, None);
    let unit = |hash: &str, root: Value| json!({"content_hash": hash, "root": root, "media": []});

    let (first_status, first) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({
            "generation": "generation-a",
            "units": [
                unit("baby-hash", baby_root.clone()),
                unit("record-hash", record_root.clone()),
            ],
        }),
    )
    .await;
    assert_eq!(first_status, StatusCode::OK, "{first}");
    assert_eq!(first["results"][0]["disposition"], "publish");
    assert_eq!(first["results"][1]["disposition"], "retry_authority");
    assert_eq!(first["results"][1]["reason"], "dependency_unresolved");

    let (baby_status, baby_commit) = publish_root_bundle(&rig.app, token, baby_root.clone()).await;
    assert_eq!(baby_status, StatusCode::OK, "{baby_commit}");

    let (second_status, second) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({
            "generation": "generation-a",
            "units": [unit("record-hash", record_root.clone())],
        }),
    )
    .await;
    assert_eq!(second_status, StatusCode::OK, "{second}");
    assert_eq!(second["results"][0]["disposition"], "publish");

    let (record_status, record_commit) =
        publish_root_bundle(&rig.app, token, record_root.clone()).await;
    assert_eq!(record_status, StatusCode::OK, "{record_commit}");

    let (confirmed_status, confirmed) = json_request(
        &rig.app,
        Method::POST,
        "/v1/reconcile",
        Some(token),
        json!({
            "generation": "generation-a",
            "units": [
                unit("baby-hash", baby_root),
                unit("record-hash", record_root),
            ],
        }),
    )
    .await;
    assert_eq!(confirmed_status, StatusCode::OK, "{confirmed}");
    assert!(confirmed["results"]
        .as_array()
        .unwrap()
        .iter()
        .all(|result| result["disposition"] == "confirmed"));

    let peer = approve_new_member(&rig.app, token, "reconcile-cycle-peer").await;
    let peer_token = peer["access_token"].as_str().unwrap();
    let (pull_status, pulled) = get_json(
        &rig.app,
        "/v1/pull?cursor=0&generation=generation-a",
        Some(peer_token),
    )
    .await;
    assert_eq!(pull_status, StatusCode::OK, "{pulled}");
    let visible = pulled["entities"].as_array().unwrap();
    assert!(visible
        .iter()
        .any(|entity| entity["type"] == "baby" && entity["client_uuid"] == baby_id));
    assert!(visible
        .iter()
        .any(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id));
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
async fn atomic_bundles_wait_for_baby_without_leaving_staging_rows() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-missing-baby-owner",
        "bundle-missing-baby-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let cases = [
        (
            "record",
            Uuid::new_v4().to_string(),
            Uuid::new_v4().to_string(),
            record_payload(&baby_id),
        ),
        (
            "care_plan",
            Uuid::new_v4().to_string(),
            Uuid::new_v4().to_string(),
            care_plan_payload(&baby_id, "bath"),
        ),
    ];

    for (root_type, root_id, bundle_id, payload) in &cases {
        let (missing_status, missing_body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(root_type, root_id, 2, payload.clone(), None),
                "media": [],
            }),
        )
        .await;
        assert_eq!(missing_status, StatusCode::CONFLICT, "{missing_body}");
        assert_eq!(
            missing_body["detail"],
            format!("{root_type} baby_client_uuid does not exist")
        );
        let (lookup_status, lookup_body) =
            get_json(&rig.app, &format!("/v1/bundles/{bundle_id}"), Some(token)).await;
        assert_eq!(lookup_status, StatusCode::NOT_FOUND, "{lookup_body}");
    }

    let (push_status, push_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");

    for (root_type, root_id, bundle_id, payload) in cases {
        let (ready_status, ready_body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(root_type, &root_id, 2, payload, None),
                "media": [],
            }),
        )
        .await;
        assert_eq!(ready_status, StatusCode::OK, "{ready_body}");
    }
}

#[tokio::test]
async fn atomic_care_plan_waits_for_its_custom_item_definition() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-custom-plan-owner",
        "bundle-custom-plan-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let custom_item_id = Uuid::new_v4().to_string();
    let other_family_id = Uuid::new_v4().to_string();
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "INSERT INTO families(id, created_at) VALUES (?1, ?2)",
            rusqlite::params![other_family_id, rig.now.load(Ordering::SeqCst)],
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, 1)",
            rusqlite::params![other_family_id],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at,
                deleted_at, payload_json, rev
            ) VALUES (?1, 'custom_item', ?2, 1, NULL, ?3, 1)
            ",
            rusqlite::params![
                other_family_id,
                custom_item_id,
                json!({
                    "name": "跨家庭定义",
                    "icon_slot": 3,
                    "created_by_membership_id": Uuid::new_v4().to_string(),
                })
                .to_string(),
            ],
        )
        .unwrap();
    drop(connection);
    let plan_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let mut plan_payload = care_plan_payload(&baby_id, "custom");
    plan_payload["custom_item_client_uuid"] = json!(custom_item_id);

    let (missing_status, missing_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("care_plan", &plan_id, 2, plan_payload.clone(), None),
            "media": [],
        }),
    )
    .await;
    assert_eq!(missing_status, StatusCode::CONFLICT, "{missing_body}");
    assert_eq!(
        missing_body["detail"],
        "care_plan custom_item_client_uuid does not exist"
    );
    let (lookup_status, lookup_body) =
        get_json(&rig.app, &format!("/v1/bundles/{bundle_id}"), Some(token)).await;
    assert_eq!(lookup_status, StatusCode::NOT_FOUND, "{lookup_body}");

    let (push_status, push_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "custom_item",
            &custom_item_id,
            1,
            json!({
                "name": "抚触",
                "icon_slot": 2,
                "created_by_membership_id": null,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");

    let (ready_status, ready_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("care_plan", &plan_id, 2, plan_payload, None),
            "media": [],
        }),
    )
    .await;
    assert_eq!(ready_status, StatusCode::OK, "{ready_body}");
}

#[tokio::test]
async fn atomic_care_plan_requires_a_type_consistent_custom_item_reference() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-custom-shape-owner",
        "bundle-custom-shape-request-0001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let custom_item_id = Uuid::new_v4().to_string();
    let (custom_status, custom_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "custom_item",
            &custom_item_id,
            1,
            json!({
                "name": "抚触",
                "icon_slot": 2,
                "created_by_membership_id": null,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(custom_status, StatusCode::OK, "{custom_body}");

    let mut missing_reference = care_plan_payload(&baby_id, "custom");
    missing_reference
        .as_object_mut()
        .unwrap()
        .remove("custom_item_client_uuid");
    let mut null_reference = care_plan_payload(&baby_id, "custom");
    null_reference["custom_item_client_uuid"] = Value::Null;
    let mut unexpected_reference = care_plan_payload(&baby_id, "bath");
    unexpected_reference["custom_item_client_uuid"] = json!(custom_item_id);

    let missing_bundle_id = Uuid::new_v4().to_string();
    let (missing_status, missing_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": missing_bundle_id,
            "root": entity_wire(
                "care_plan",
                &Uuid::new_v4().to_string(),
                2,
                missing_reference,
                None,
            ),
            "media": [],
        }),
    )
    .await;
    assert_eq!(
        missing_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{missing_body}"
    );
    assert_eq!(
        missing_body["detail"],
        "custom_item_client_uuid is required"
    );

    for (payload, expected_detail) in [
        (
            null_reference,
            "care_plan type custom requires custom_item_client_uuid",
        ),
        (
            unexpected_reference,
            "care_plan custom_item_client_uuid is only valid for type custom",
        ),
    ] {
        let bundle_id = Uuid::new_v4().to_string();
        let (status, body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "care_plan",
                    &Uuid::new_v4().to_string(),
                    2,
                    payload,
                    None,
                ),
                "media": [],
            }),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert_eq!(body["detail"], expected_detail);

        let (lookup_status, lookup_body) =
            get_json(&rig.app, &format!("/v1/bundles/{bundle_id}"), Some(token)).await;
        assert_eq!(lookup_status, StatusCode::NOT_FOUND, "{lookup_body}");
    }

    let valid_bundle_id = Uuid::new_v4().to_string();
    let mut valid_payload = care_plan_payload(&baby_id, "custom");
    valid_payload["custom_item_client_uuid"] = json!(custom_item_id);
    let (valid_status, valid_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": valid_bundle_id,
            "root": entity_wire(
                "care_plan",
                &Uuid::new_v4().to_string(),
                2,
                valid_payload,
                None,
            ),
            "media": [],
        }),
    )
    .await;
    assert_eq!(valid_status, StatusCode::OK, "{valid_body}");
}

#[tokio::test]
async fn atomic_bundle_commit_rejects_malformed_or_wrong_shape_json() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-json-owner-device",
        "bundle-json-owner-request-0000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let bundle_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            ),
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    for body in [Body::from("{"), Body::from(r#"{"generation":42}"#)] {
        let response = request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(owner_token),
            body,
            Some("application/json"),
        )
        .await;
        assert!(
            response.status().is_client_error(),
            "invalid commit JSON was accepted with {}",
            response.status()
        );
    }

    let (_, before_commit) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    assert!(!before_commit["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == record_id));
}

#[tokio::test]
async fn atomic_bundle_stamps_and_freezes_first_record_author() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-author-owner-device",
        "bundle-author-owner-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "bundle-author-member-device").await;
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();

    for (updated_at, deleted_at, note) in [
        (2, None, "创建"),
        (3, None, "管理员编辑"),
        (4, Some(4), "管理员删除"),
    ] {
        let bundle_id = Uuid::new_v4().to_string();
        let mut forged_payload = record_payload(&baby_id);
        forged_payload["note"] = json!(note);
        forged_payload["created_by_membership_id"] = member["membership_id"].clone();
        let (stage_status, stage_body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(owner_token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "record",
                    &record_id,
                    updated_at,
                    forged_payload,
                    deleted_at,
                ),
                "media": [],
            }),
        )
        .await;
        assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
        let (commit_status, commit_body) = json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await;
        assert_eq!(commit_status, StatusCode::OK, "{commit_body}");
        assert_eq!(
            commit_body["record_authors"],
            json!([{
                "client_uuid": record_id,
                "created_by_membership_id": owner["membership_id"],
            }])
        );

        let (retry_status, retry_body) = json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await;
        assert_eq!(retry_status, StatusCode::OK, "{retry_body}");
        assert_eq!(
            retry_body["record_authors"],
            json!([{
                "client_uuid": record_id,
                "created_by_membership_id": owner["membership_id"],
            }])
        );

        let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
        let record = pull["entities"]
            .as_array()
            .unwrap()
            .iter()
            .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
            .expect("committed record version remains visible");
        assert_eq!(record["updated_at"], updated_at);
        assert_eq!(
            record["deleted_at"],
            deleted_at.map_or(Value::Null, Value::from)
        );
        assert_eq!(record["payload"]["note"], note);
        assert_eq!(
            record["payload"]["created_by_membership_id"],
            owner["membership_id"]
        );
        assert!(!record["payload"]
            .as_object()
            .unwrap()
            .contains_key("created_by_device_id"));
    }

    // 0.3.10 record tombstone wins: higher live cannot clear deleted_at.
    let restore_bundle = Uuid::new_v4().to_string();
    let mut restore_payload = record_payload(&baby_id);
    restore_payload["note"] = json!("管理员恢复");
    restore_payload["created_by_membership_id"] = owner["membership_id"].clone();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": restore_bundle,
            "root": entity_wire("record", &record_id, 99, restore_payload, None),
            "media": [],
        }),
    )
    .await;
    // Stage may succeed (validation deferred) or fail; commit must refuse.
    if stage_status == StatusCode::OK {
        let (commit_status, commit_body) = json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{restore_bundle}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await;
        assert_eq!(commit_status, StatusCode::CONFLICT, "{commit_body}");
        assert!(
            commit_body["detail"]
                .as_str()
                .unwrap_or_default()
                .contains("cannot be resurrected"),
            "{commit_body}"
        );
    } else {
        assert_eq!(stage_status, StatusCode::CONFLICT, "{stage_body}");
    }
}

#[tokio::test]
async fn member_record_manage_requires_the_effective_creator_or_owner() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "record-acl-owner-device",
        "record-acl-owner-request-000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "record-acl-member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();

    let (created, body) = publish_root_bundle(
        &rig.app,
        owner_token,
        entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
    )
    .await;
    assert_eq!(created, StatusCode::OK, "{body}");

    for (updated_at, deleted_at, note) in [
        (3, None, "foreign edit"),
        (4, Some(4), "foreign delete"),
        (5, None, "foreign restore"),
    ] {
        let mut payload = record_payload(&baby_id);
        payload["note"] = json!(note);
        payload["created_by_membership_id"] = member["membership_id"].clone();
        let (status, body) = publish_root_bundle(
            &rig.app,
            member_token,
            entity_wire("record", &record_id, updated_at, payload, deleted_at),
        )
        .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
        assert_eq!(
            body,
            json!({"detail": "Only the record creator or family owner may change this record"})
        );
    }

    // The same creator check applies when the rejected foreign-record update
    // carries log media in its atomic bundle.
    let foreign_media_id = Uuid::new_v4().to_string();
    let (status, body) = stage_bundle_with_media(
        &rig.app,
        member_token,
        entity_wire("record", &record_id, 6, record_payload(&baby_id), None),
        vec![entity_wire(
            "media",
            &foreign_media_id,
            6,
            log_media_payload(&record_id),
            None,
        )],
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    assert_eq!(
        body,
        json!({"detail": "Only the record creator or family owner may change this record"})
    );
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

    let media_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = stage_bundle_with_media(
        &rig.app,
        second_token,
        entity_wire("record", &record_id, 3, record_payload(&baby_id), None),
        vec![entity_wire(
            "media",
            &media_id,
            3,
            log_media_payload(&record_id),
            None,
        )],
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
}

#[tokio::test]
async fn atomic_bundle_commit_is_bound_to_the_staging_membership() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-stager-owner-device",
        "bundle-stager-owner-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "bundle-stager-member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let mut forged_payload = record_payload(&baby_id);
    forged_payload["created_by_membership_id"] = member["membership_id"].clone();

    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &record_id, 2, forged_payload, None),
            "media": [entity_wire(
                "media",
                &media_id,
                2,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(owner_token),
            Body::from("img"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );

    let (foreign_commit_status, foreign_commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(member_token),
        json!({}),
    )
    .await;
    assert_eq!(
        foreign_commit_status,
        StatusCode::CONFLICT,
        "{foreign_commit_body}"
    );
    let (_, before_owner_commit) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    assert!(!before_owner_commit["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == record_id));
    assert_eq!(
        request(
            &rig.app,
            Method::GET,
            &format!("/v1/media/{media_id}"),
            Some(owner_token),
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );

    let (commit_status, committed) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");
    let (retry_status, retry) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(retry["cursor"], committed["cursor"]);
    assert_eq!(retry["applied"], committed["applied"]);

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
        .expect("owner-staged record is visible after owner commit");
    assert_eq!(
        record["payload"]["created_by_membership_id"],
        owner["membership_id"]
    );
    assert!(!record["payload"]
        .as_object()
        .unwrap()
        .contains_key("created_by_device_id"));
}

#[tokio::test]
async fn foreign_bundle_commit_cannot_leave_claimable_final_bytes() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "foreign-commit-owner-device",
        "foreign-commit-owner-request-00001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "foreign-commit-member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    seed_record_with_id(
        &rig.app,
        owner_token,
        &record_id,
        1,
        record_payload(&baby_id),
    )
    .await;
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                2,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(owner_token),
            Body::from("old"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );

    let (foreign_status, foreign_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(member_token),
        json!({}),
    )
    .await;
    assert_eq!(foreign_status, StatusCode::CONFLICT, "{foreign_body}");

    let (metadata_status, metadata_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire(
                "media",
                &media_id,
                3,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(
        metadata_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{metadata_body}"
    );

    let (refine_status, refine_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            ),
            "media": [],
        }),
    )
    .await;
    assert_eq!(refine_status, StatusCode::OK, "{refine_body}");

    let restarted = rig.restart("generation-b");
    let (_, pull) = get_json(
        &restarted,
        "/v1/pull?cursor=0&generation=generation-b",
        Some(owner_token),
    )
    .await;
    assert!(
        !pull["entities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|entity| entity["client_uuid"] == media_id),
        "foreign commit left final bytes that restart claimed as published media: {pull}"
    );
    assert_eq!(
        request(
            &restarted,
            Method::GET,
            &format!("/v1/media/{media_id}"),
            Some(owner_token),
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn bundle_media_upload_requires_stager_membership_and_open_status() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "media-owner-device",
        "media-owner-request-000000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "media-member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                2,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    let staged_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(".stage")
        .join(&bundle_id)
        .join(&media_id);

    let foreign_upload = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(member_token),
        Body::from("bad"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(foreign_upload.status(), StatusCode::CONFLICT);
    assert!(!staged_path.exists());

    let owner_upload = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(owner_token),
        Body::from("img"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(owner_upload.status(), StatusCode::OK);
    assert_eq!(fs::read(&staged_path).unwrap(), b"img");
    let (commit_status, commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");
    assert!(!staged_path.exists());
    let published_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(&media_id);
    assert_eq!(fs::read(&published_path).unwrap(), b"img");

    let committed_upload = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(owner_token),
        Body::from("new"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(committed_upload.status(), StatusCode::CONFLICT);
    assert!(!staged_path.exists());
    assert_eq!(fs::read(published_path).unwrap(), b"img");
}

#[tokio::test]
async fn equal_atomic_publish_before_commit_repairs_the_committed_bundle_package() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-race-owner-device",
        "bundle-race-owner-request-000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member = approve_new_member(&rig.app, owner_token, "bundle-race-member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let owner_bundle = Uuid::new_v4().to_string();
    let mut staged_payload = record_payload(&baby_id);
    staged_payload["note"] = json!("A 暂存");
    let staged_root = entity_wire("record", &record_id, 2, staged_payload, None);

    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": owner_bundle,
            "root": staged_root.clone(),
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    // Member publishes the same root first via a competing atomic package.
    let mut winner_payload = record_payload(&baby_id);
    winner_payload["note"] = json!("B 先发布");
    let member_bundle = Uuid::new_v4().to_string();
    let (member_stage, member_stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(member_token),
        json!({
            "bundle_id": member_bundle,
            "root": entity_wire("record", &record_id, 2, winner_payload, None),
            "media": [],
        }),
    )
    .await;
    assert_eq!(member_stage, StatusCode::OK, "{member_stage_body}");
    let (member_commit, member_commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{member_bundle}/commit"),
        Some(member_token),
        json!({}),
    )
    .await;
    assert_eq!(member_commit, StatusCode::OK, "{member_commit_body}");
    assert_eq!(
        member_commit_body["record_authors"],
        json!([{
            "client_uuid": record_id,
            "created_by_membership_id": member["membership_id"],
        }])
    );

    let (commit_status, committed) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{owner_bundle}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");
    assert_eq!(
        committed["record_authors"],
        json!([{
            "client_uuid": record_id,
            "created_by_membership_id": member["membership_id"],
        }])
    );

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
        .expect("first atomic winner remains the published record");
    assert_eq!(record["payload"]["note"], "B 先发布");
    assert_eq!(
        record["payload"]["created_by_membership_id"],
        member["membership_id"]
    );

    let (restage_status, restaged) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": owner_bundle,
            "root": staged_root,
            "media": [],
        }),
    )
    .await;
    assert_eq!(restage_status, StatusCode::OK, "{restaged}");
    assert_eq!(restaged["status"], "committed");
}

#[tokio::test]
async fn atomic_bundle_requires_uuid_bundle_id_in_body_and_paths() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-uuid-owner",
        "bundle-uuid-owner-request-00000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = "11111111-2222-3333-8444-555555555555";
    let invalid_bundle_id = format!("record:{record_id}:2");

    let (invalid_status, invalid_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": invalid_bundle_id,
            "root": entity_wire("record", record_id, 2, record_payload(&baby_id), None),
            "media": []
        }),
    )
    .await;
    assert_eq!(
        invalid_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{invalid_body}"
    );

    let invalid_path = request(
        &rig.app,
        Method::GET,
        "/v1/bundles/not-a-uuid",
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(invalid_path.status(), StatusCode::BAD_REQUEST);

    // Android's deterministic UUID for
    // lezi.atomic-bundle.v1:record:<record_id>:2 is accepted end to end.
    let bundle_id = "e4c2d0cf-4967-347c-b3bd-af9dae2b34f4";
    let (stage_status, staged) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", record_id, 2, record_payload(&baby_id), None),
            "media": []
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{staged}");
    assert_eq!(staged["bundle_id"], bundle_id);

    let (get_status, status) =
        get_json(&rig.app, &format!("/v1/bundles/{bundle_id}"), Some(token)).await;
    assert_eq!(get_status, StatusCode::OK, "{status}");
    assert_eq!(status["bundle_id"], bundle_id);
}

#[tokio::test]
async fn atomic_bundle_is_invisible_until_commit_and_publishes_atomically() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-owner",
        "bundle-owner-request-000000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_a = Uuid::new_v4().to_string();
    let media_b = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    let mut media_a_payload = log_media_payload(&record_id);
    media_a_payload["byte_size"] = json!(3);
    let mut media_b_payload = log_media_payload(&record_id);
    media_b_payload["byte_size"] = json!(4);

    let (status, staged) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                10,
                record_payload(&baby_id),
                None
            ),
            "media": [
                entity_wire("media", &media_a, 10, media_a_payload, None),
                entity_wire("media", &media_b, 10, media_b_payload, None),
            ]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{staged}");
    assert_eq!(staged["status"], "staging");
    assert_eq!(
        staged["missing_media"].as_array().unwrap().len(),
        2,
        "{staged}"
    );

    // Pre-commit: root and media must not appear on ordinary pull.
    let (_, pull_before) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let types_before: Vec<_> = pull_before["entities"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["type"].as_str().unwrap().to_owned())
        .collect();
    assert_eq!(types_before, vec!["baby"]);
    assert!(!pull_before["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["client_uuid"] == record_id));

    // Interrupt after first photo: still invisible; commit rejected.
    let upload_a = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_a}"),
        Some(token),
        Body::from("img"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(upload_a.status(), StatusCode::OK);
    let (commit_early, early_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(
        commit_early,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{early_body}"
    );
    let (_, still_hidden) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert!(!still_hidden["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["client_uuid"] == record_id));

    let upload_b = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_b}"),
        Some(token),
        Body::from("jpeg"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(upload_b.status(), StatusCode::OK);

    let (commit_status, committed) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");
    assert_eq!(committed["status"], "committed");
    assert_eq!(committed["applied"], 3);

    // Duplicate commit is idempotent (lost response safe).
    let (retry_status, retry) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK, "{retry}");
    assert_eq!(retry["status"], "committed");
    assert_eq!(retry["cursor"], committed["cursor"]);

    let (_, pull_after) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let uuids: std::collections::BTreeSet<_> = pull_after["entities"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| e["client_uuid"].as_str().unwrap().to_owned())
        .collect();
    assert!(uuids.contains(&record_id));
    assert!(uuids.contains(&media_a));
    assert!(uuids.contains(&media_b));

    for media_id in [&media_a, &media_b] {
        let response = request(
            &rig.app,
            Method::GET,
            &format!("/v1/media/{media_id}"),
            Some(token),
            Body::empty(),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK, "{media_id}");
    }
}

#[tokio::test]
async fn atomic_bundle_prepares_durable_media_before_database_publication() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-durability-owner",
        "bundle-durability-request-000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let family_id = created["family_id"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                2,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(token),
            Body::from("img"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute_batch(
            "
            CREATE TRIGGER fail_bundle_publish
            BEFORE UPDATE OF status ON sync_bundles
            WHEN NEW.status = 'committed'
            BEGIN
                SELECT RAISE(FAIL, 'injected bundle publish failure');
            END;
            ",
        )
        .unwrap();
    drop(connection);

    let failed = request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        Body::from(r#"{"generation":"generation-a"}"#),
        Some("application/json"),
    )
    .await;
    assert_eq!(failed.status(), StatusCode::INTERNAL_SERVER_ERROR);

    let final_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(&media_id);
    assert_eq!(
        fs::read(&final_path).unwrap(),
        b"img",
        "durable bytes must exist before the database can expose their metadata"
    );
    let (_, hidden) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert!(!hidden["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == record_id || entity["client_uuid"] == media_id));
    assert_eq!(
        request(
            &rig.app,
            Method::GET,
            &format!("/v1/media/{media_id}"),
            Some(token),
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute("DROP TRIGGER fail_bundle_publish", [])
        .unwrap();
    drop(connection);
    let restarted = rig.restart("generation-b");
    let (retry_status, retry_body) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({"generation": "generation-b"}),
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK, "{retry_body}");
    let media_response = request(
        &restarted,
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
        "img"
    );
}

#[tokio::test]
async fn committed_bundle_retry_revalidates_published_media_after_stage_cleanup() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-retry-media-owner",
        "bundle-retry-media-request-000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let family_id = created["family_id"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                2,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(token),
            Body::from("img"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let persisted_sha256: String = connection
        .query_row(
            "
            SELECT staged_sha256
            FROM sync_bundle_media
            WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
            ",
            rusqlite::params![family_id, bundle_id, media_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(
        persisted_sha256,
        hex::encode(Sha256::digest(b"img")),
        "bundle upload did not persist its exact digest"
    );
    drop(connection);
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

    let family_media = rig.directory.path().join("media").join(family_id);
    let stage_dir = family_media.join(".stage").join(&bundle_id);
    let published = family_media.join(&media_id);
    assert!(!stage_dir.exists(), "successful commit left staging bytes");
    fs::remove_file(&published).unwrap();
    let missing_retry = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_ne!(
        missing_retry.0,
        StatusCode::OK,
        "committed retry accepted missing published bytes: {}",
        missing_retry.1
    );

    fs::write(&published, b"bad").unwrap();
    let corrupt_retry = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_ne!(
        corrupt_retry.0,
        StatusCode::OK,
        "committed retry accepted same-size corrupt bytes: {}",
        corrupt_retry.1
    );

    fs::write(&published, b"img").unwrap();
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "
            UPDATE sync_bundle_media
            SET staged_sha256 = NULL
            WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
            ",
            rusqlite::params![family_id, bundle_id, media_id],
        )
        .unwrap();
    drop(connection);
    let missing_digest_retry = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_ne!(
        missing_digest_retry.0,
        StatusCode::OK,
        "committed retry trusted published bytes without a digest: {}",
        missing_digest_retry.1
    );
}

#[tokio::test]
async fn atomic_bundle_rejects_manifest_mismatch_and_oversized_media() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-mismatch-owner",
        "bundle-mismatch-request-00000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let mut media_payload = log_media_payload(&record_id);
    media_payload["byte_size"] = json!(3);

    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
            "media": [entity_wire("media", &media_id, 2, media_payload, None)],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

    let unknown = Uuid::new_v4();
    let mismatch = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{unknown}"),
        Some(token),
        Body::from("img"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(mismatch.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let wrong_size = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
        Some(token),
        Body::from("toolong"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(wrong_size.status(), StatusCode::UNPROCESSABLE_ENTITY);

    // Live media without byte_size is rejected at stage time.
    let (no_size_status, no_size_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": Uuid::new_v4(),
            "root": entity_wire(
                "record",
                &Uuid::new_v4().to_string(),
                3,
                record_payload(&baby_id),
                None
            ),
            "media": [entity_wire(
                "media",
                &Uuid::new_v4().to_string(),
                3,
                json!({
                    "kind": "log",
                    "record_client_uuid": record_id,
                    "mime": "image/jpeg"
                }),
                None
            )],
        }),
    )
    .await;
    assert_eq!(
        no_size_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{no_size_body}"
    );
}

#[tokio::test]
async fn atomic_bundle_edit_keeps_old_published_version_until_commit() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-edit-owner",
        "bundle-edit-request-000000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_v1 = Uuid::new_v4().to_string();
    let bundle_v1 = Uuid::new_v4().to_string();
    let mut media_payload = log_media_payload(&record_id);
    media_payload["byte_size"] = json!(3);

    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_v1,
            "root": entity_wire("record", &record_id, 5, record_payload(&baby_id), None),
            "media": [entity_wire("media", &media_v1, 5, media_payload.clone(), None)],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_v1}/media/{media_v1}"),
            Some(token),
            Body::from("old"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    let (commit_status, _) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_v1}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK);

    // Stage a newer version with different media; until commit, pull keeps v1.
    let media_v2 = Uuid::new_v4().to_string();
    let bundle_v2 = Uuid::new_v4().to_string();
    let mut media_v2_payload = log_media_payload(&record_id);
    media_v2_payload["byte_size"] = json!(4);
    let mut record_v2 = record_payload(&baby_id);
    record_v2["note"] = json!("edited");
    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_v2,
            "root": entity_wire("record", &record_id, 20, record_v2, None),
            "media": [entity_wire("media", &media_v2, 20, media_v2_payload, None)],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

    let (_, mid) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let record = mid["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == record_id)
        .unwrap();
    assert_eq!(record["updated_at"], 5);
    assert_eq!(record["payload"]["note"], Value::Null);
    assert!(mid["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["client_uuid"] == media_v1));
    assert!(!mid["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["client_uuid"] == media_v2));

    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_v2}/media/{media_v2}"),
            Some(token),
            Body::from("new!"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    let (commit2, _) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_v2}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit2, StatusCode::OK);

    let (_, after) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let record = after["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == record_id)
        .unwrap();
    assert_eq!(record["updated_at"], 20);
    assert_eq!(record["payload"]["note"], "edited");
}

#[tokio::test]
async fn atomic_bundle_tombstone_publishes_without_media_bytes() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-tomb-owner",
        "bundle-tomb-request-000000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let create_bundle = Uuid::new_v4().to_string();
    let mut media_payload = log_media_payload(&record_id);
    media_payload["byte_size"] = json!(3);

    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": create_bundle,
            "root": entity_wire("record", &record_id, 1, record_payload(&baby_id), None),
            "media": [entity_wire("media", &media_id, 1, media_payload.clone(), None)],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{create_bundle}/media/{media_id}"),
            Some(token),
            Body::from("img"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{create_bundle}/commit"),
            Some(token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

    media_payload["byte_size"] = json!(0);
    let tomb_bundle = Uuid::new_v4().to_string();
    let (status, staged) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": tomb_bundle,
            "root": entity_wire("record", &record_id, 50, record_payload(&baby_id), Some(50)),
            "media": [entity_wire("media", &media_id, 50, media_payload, Some(50))],
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{staged}");
    assert_eq!(staged["missing_media"].as_array().unwrap().len(), 0);
    let (commit_status, committed) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{tomb_bundle}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == record_id)
        .unwrap();
    assert_eq!(record["deleted_at"], 50);
    let media = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == media_id)
        .unwrap();
    assert_eq!(media["deleted_at"], 50);
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
        // Forgery attempt — server must overwrite with authenticated membership.
        "created_by_membership_id": "spoofed-membership",
        "fulfilled_record_client_uuid": null,
        "fulfilled_at": null,
    })
}

#[tokio::test]
async fn atomic_bundle_supports_generic_care_plan_root() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-plan-owner",
        "bundle-plan-request-000000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let owner_membership = created["membership_id"].as_str().unwrap().to_owned();
    let baby_id = seed_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    let (status, staged) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "care_plan",
                &plan_id,
                7,
                care_plan_payload(&baby_id, "formula"),
                None
            ),
            "media": []
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{staged}");
    assert_eq!(staged["missing_media"], json!([]));

    let (_, hidden) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert!(!hidden["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["client_uuid"] == plan_id));

    let (commit_status, committed) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{committed}");
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let plan = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == plan_id)
        .expect("care_plan visible after commit");
    assert_eq!(plan["type"], "care_plan");
    assert_eq!(
        plan["payload"]["created_by_membership_id"],
        owner_membership
    );
    assert_ne!(
        plan["payload"]["created_by_membership_id"],
        "spoofed-membership"
    );
}

#[tokio::test]
async fn care_plan_member_acl_and_owner_override() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "care-plan-acl-owner",
        "care-plan-acl-request-00000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_a = approve_new_member(&rig.app, owner_token, "care-plan-member-a").await;
    let member_a_token = member_a["access_token"].as_str().unwrap();
    let member_b = approve_new_member(&rig.app, owner_token, "care-plan-member-b").await;
    let member_b_token = member_b["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    // Member A creates a plan via atomic bundle.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(member_a_token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "care_plan",
                    &plan_id,
                    1,
                    care_plan_payload(&baby_id, "pee"),
                    None
                ),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(member_a_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Member B cannot edit A's plan.
    let bundle_b = Uuid::new_v4().to_string();
    let mut payload = care_plan_payload(&baby_id, "pee");
    payload["note"] = json!("篡改");
    let (forbid, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(member_b_token),
        json!({
            "bundle_id": bundle_b,
            "root": entity_wire("care_plan", &plan_id, 2, payload.clone(), None),
            "media": []
        }),
    )
    .await;
    assert_eq!(forbid, StatusCode::FORBIDDEN, "{body}");

    // The same ACL applies when the unauthorized plan edit carries media.
    let foreign_media_id = Uuid::new_v4().to_string();
    let (foreign_media, foreign_media_body) = stage_bundle_with_media(
        &rig.app,
        member_b_token,
        entity_wire("care_plan", &plan_id, 2, payload.clone(), None),
        vec![entity_wire(
            "media",
            &foreign_media_id,
            2,
            json!({
                "kind": "log",
                "record_client_uuid": null,
                "care_plan_client_uuid": plan_id,
                "baby_client_uuid": baby_id,
                "mime": "image/jpeg",
                "width": null,
                "height": null,
                "byte_size": 3,
            }),
            None,
        )],
    )
    .await;
    assert_eq!(foreign_media, StatusCode::FORBIDDEN, "{foreign_media_body}");

    // Owner may edit any plan.
    let bundle_o = Uuid::new_v4().to_string();
    payload["note"] = json!("管理员改");
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(owner_token),
            json!({
                "bundle_id": bundle_o,
                "root": entity_wire("care_plan", &plan_id, 3, payload, None),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_o}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let plan = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == plan_id)
        .unwrap();
    assert_eq!(plan["payload"]["note"], "管理员改");
    // Creator frozen to member A even after owner edit.
    assert_eq!(
        plan["payload"]["created_by_membership_id"],
        member_a["membership_id"]
    );
}

#[tokio::test]
async fn completed_care_plan_fulfillment_binding_is_frozen_for_creator_and_owner() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "fulfillment-binding-owner",
        "fulfillment-binding-request-0000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let creator = approve_new_member(&rig.app, owner_token, "fulfillment-binding-creator").await;
    let creator_token = creator["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    let first_record_id = Uuid::new_v4().to_string();
    let rebound_record_id = Uuid::new_v4().to_string();
    let fulfilled_at = 1_700_000_100_000i64;

    let pending_plan = care_plan_payload(&baby_id, "bath");
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            creator_token,
            entity_wire("care_plan", &plan_id, 1, pending_plan.clone(), None),
        )
        .await
        .0,
        StatusCode::OK
    );

    let staged_rebind_bundle = Uuid::new_v4().to_string();
    let mut staged_rebind = pending_plan.clone();
    staged_rebind["status"] = json!("completed");
    staged_rebind["fulfilled_record_client_uuid"] = json!(rebound_record_id);
    staged_rebind["fulfilled_at"] = json!(fulfilled_at);
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(creator_token),
            json!({
                "bundle_id": staged_rebind_bundle,
                "root": entity_wire("care_plan", &plan_id, 3, staged_rebind, None),
                "media": [],
            }),
        )
        .await
        .0,
        StatusCode::OK
    );

    let mut first_completion = pending_plan.clone();
    first_completion["status"] = json!("completed");
    first_completion["fulfilled_record_client_uuid"] = json!(first_record_id);
    first_completion["fulfilled_at"] = json!(fulfilled_at);
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            creator_token,
            entity_wire("care_plan", &plan_id, 2, first_completion.clone(), None,),
        )
        .await
        .0,
        StatusCode::OK
    );
    seed_record_with_id(
        &rig.app,
        creator_token,
        &first_record_id,
        2,
        record_payload(&baby_id),
    )
    .await;

    let immutable_detail = "Completed care plan fulfillment binding is immutable";
    let (raced_status, raced_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{staged_rebind_bundle}/commit"),
        Some(creator_token),
        json!({}),
    )
    .await;
    assert_eq!(raced_status, StatusCode::CONFLICT, "{raced_body}");
    assert_eq!(raced_body["detail"], immutable_detail);

    let mut exact_pair_update = first_completion.clone();
    exact_pair_update["note"] = json!("binding preserved");
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            creator_token,
            entity_wire("care_plan", &plan_id, 4, exact_pair_update, None),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Full-pair rebind/retime: wire-valid, freeze at store → 409.
    let mut rebound = first_completion.clone();
    rebound["fulfilled_record_client_uuid"] = json!(rebound_record_id);
    let mut retimed = first_completion.clone();
    retimed["fulfilled_at"] = json!(fulfilled_at + 1);
    for (token, updated_at, payload) in [(creator_token, 5, rebound), (owner_token, 7, retimed)] {
        let (status, body) = publish_root_bundle(
            &rig.app,
            token,
            entity_wire("care_plan", &plan_id, updated_at, payload, None),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert_eq!(body, json!({"detail": immutable_detail}));
    }

    // Partial rewrite / clear: fail closed at model boundary (422), not freeze (409).
    let mut partial_clear = first_completion.clone();
    partial_clear["fulfilled_record_client_uuid"] = Value::Null;
    let mut completed_without_pair = first_completion.clone();
    completed_without_pair["fulfilled_record_client_uuid"] = Value::Null;
    completed_without_pair["fulfilled_at"] = Value::Null;
    for (updated_at, payload, detail) in [
        (
            6i64,
            partial_clear,
            "fulfilled_record_client_uuid and fulfilled_at must both be set or both null",
        ),
        (
            8,
            completed_without_pair,
            "completed care plan requires fulfilled_record_client_uuid and fulfilled_at",
        ),
    ] {
        let (status, body) = publish_root_bundle(
            &rig.app,
            creator_token,
            entity_wire("care_plan", &plan_id, updated_at, payload, None),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
        assert_eq!(body, json!({"detail": detail}));
    }

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let entities = pull["entities"].as_array().unwrap();
    let plan = entities
        .iter()
        .find(|entity| entity["type"] == "care_plan" && entity["client_uuid"] == plan_id)
        .unwrap();
    assert_eq!(
        plan["payload"]["fulfilled_record_client_uuid"],
        first_record_id
    );
    assert_eq!(plan["payload"]["fulfilled_at"], fulfilled_at);
    assert_eq!(plan["payload"]["note"], "binding preserved");
    assert!(entities
        .iter()
        .all(|entity| entity["client_uuid"] != rebound_record_id));
}

/// First atomic publish of a completed root without a full pair (or a
/// non-completed root carrying a full pair) must 422 at the model/API boundary
/// with the known detail — not only on rewrite of an already-frozen plan.
#[tokio::test]
async fn first_publish_care_plan_fulfillment_pair_is_required_at_api_boundary() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "pair-first-publish-owner",
        "pair-first-publish-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let fulfilled_at = 1_700_000_100_000i64;

    let completed_missing =
        "completed care plan requires fulfilled_record_client_uuid and fulfilled_at";
    let partial = "fulfilled_record_client_uuid and fulfilled_at must both be set or both null";
    let non_completed =
        "only completed care plans may carry fulfilled_record_client_uuid and fulfilled_at";

    // First root: pending→completed missing either/both fields → 422.
    for (record, at, detail) in [
        (Value::Null, Value::Null, completed_missing),
        (json!(record_id), Value::Null, partial),
        (Value::Null, json!(fulfilled_at), partial),
    ] {
        let plan_id = Uuid::new_v4().to_string();
        let mut payload = care_plan_payload(&baby_id, "bath");
        payload["status"] = json!("completed");
        payload["fulfilled_record_client_uuid"] = record;
        payload["fulfilled_at"] = at;
        let (status, body) = publish_root_bundle(
            &rig.app,
            token,
            entity_wire("care_plan", &plan_id, 1, payload, None),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
        assert_eq!(body, json!({"detail": detail}));
    }

    // First root: non-completed carrying a full pair → 422.
    for status_value in ["pending", "missed", "skipped"] {
        let plan_id = Uuid::new_v4().to_string();
        let mut payload = care_plan_payload(&baby_id, "bath");
        payload["status"] = json!(status_value);
        payload["fulfilled_record_client_uuid"] = json!(record_id);
        payload["fulfilled_at"] = json!(fulfilled_at);
        let (status, body) = publish_root_bundle(
            &rig.app,
            token,
            entity_wire("care_plan", &plan_id, 1, payload, None),
        )
        .await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
        assert_eq!(body, json!({"detail": non_completed}));
    }

    // Control: first completed root with full pair (forward-ref record) accepts.
    let ok_plan_id = Uuid::new_v4().to_string();
    let mut ok_payload = care_plan_payload(&baby_id, "bath");
    ok_payload["status"] = json!("completed");
    ok_payload["fulfilled_record_client_uuid"] = json!(record_id);
    ok_payload["fulfilled_at"] = json!(fulfilled_at);
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            token,
            entity_wire("care_plan", &ok_plan_id, 1, ok_payload, None),
        )
        .await
        .0,
        StatusCode::OK
    );
}

#[tokio::test]
async fn concurrent_member_next_feed_create_keeps_nas_winner_without_forbidden() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "next-feed-race-owner",
        "next-feed-race-request-0000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let member_a = approve_new_member(&rig.app, owner_token, "next-feed-race-a").await;
    let member_b = approve_new_member(&rig.app, owner_token, "next-feed-race-b").await;
    let member_a_token = member_a["access_token"].as_str().unwrap();
    let member_b_token = member_b["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    let winner_media_id = Uuid::new_v4().to_string();
    let attacker_media_id = Uuid::new_v4().to_string();

    let mut winner_payload = care_plan_payload(&baby_id, "formula");
    winner_payload["scheduled_at"] = json!(1_700_000_060_000i64);
    winner_payload["note"] = json!("[[lezi:next-feed:v1]]");
    winner_payload["payload_json"] = json!({"amount_ml": 0});
    winner_payload["created_by_membership_id"] = member_a["membership_id"].clone();
    let winner_bundle = Uuid::new_v4().to_string();
    let (winner_stage, winner_stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(member_a_token),
        json!({
            "bundle_id": winner_bundle,
            "root": entity_wire("care_plan", &plan_id, 2, winner_payload, None),
            "media": [entity_wire(
                "media",
                &winner_media_id,
                2,
                json!({
                    "kind": "log",
                    "record_client_uuid": null,
                    "care_plan_client_uuid": plan_id,
                    "baby_client_uuid": baby_id,
                    "mime": "image/jpeg",
                    "width": null,
                    "height": null,
                    "byte_size": 3,
                }),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(winner_stage, StatusCode::OK, "{winner_stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{winner_bundle}/media/{winner_media_id}"),
            Some(member_a_token),
            Body::from("img"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    let mut loser_payload = care_plan_payload(&baby_id, "formula");
    loser_payload["scheduled_at"] = json!(1_700_000_120_000i64);
    loser_payload["note"] = json!("[[lezi:next-feed:v1]]");
    loser_payload["payload_json"] = json!({"amount_ml": 0});
    loser_payload["created_by_membership_id"] = member_b["membership_id"].clone();
    let loser_bundle = Uuid::new_v4().to_string();
    let (loser_stage, loser_stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(member_b_token),
        json!({
            "bundle_id": loser_bundle,
            // An offline loser can carry an older timestamp and must not fall
            // back to the ordinary edit ACL after the NAS winner publishes.
            "root": entity_wire("care_plan", &plan_id, 1, loser_payload, None),
            "media": [
                entity_wire(
                    "media",
                    &winner_media_id,
                    2,
                    json!({
                        "kind": "log",
                        "record_client_uuid": null,
                        "care_plan_client_uuid": plan_id,
                        "baby_client_uuid": baby_id,
                        "mime": "image/jpeg",
                        "width": null,
                        "height": null,
                        "byte_size": 3,
                    }),
                    Some(2),
                ),
                entity_wire(
                    "media",
                    &attacker_media_id,
                    1,
                    json!({
                        "kind": "log",
                        "record_client_uuid": null,
                        "care_plan_client_uuid": plan_id,
                        "baby_client_uuid": baby_id,
                        "mime": "image/jpeg",
                        "width": null,
                        "height": null,
                        "byte_size": 3,
                    }),
                    None,
                ),
            ]
        }),
    )
    .await;
    assert_eq!(loser_stage, StatusCode::OK, "{loser_stage_body}");
    assert_eq!(
        loser_stage_body["missing_media"],
        json!([attacker_media_id])
    );
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{loser_bundle}/media/{attacker_media_id}"),
            Some(member_b_token),
            Body::from("bad"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    // Both members staged against an empty family view. A wins publication;
    // B must become an exact whole-package no-op when it commits afterward.
    let (winner_commit, winner_commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{winner_bundle}/commit"),
        Some(member_a_token),
        json!({}),
    )
    .await;
    assert_eq!(winner_commit, StatusCode::OK, "{winner_commit_body}");
    let (loser_commit, loser_commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{loser_bundle}/commit"),
        Some(member_b_token),
        json!({}),
    )
    .await;
    assert_eq!(loser_commit, StatusCode::OK, "{loser_commit_body}");

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let plans = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|entity| entity["type"] == "care_plan" && entity["client_uuid"] == plan_id)
        .collect::<Vec<_>>();
    assert_eq!(plans.len(), 1);
    assert_eq!(
        plans[0]["payload"]["created_by_membership_id"],
        member_a["membership_id"]
    );
    assert_eq!(plans[0]["payload"]["scheduled_at"], 1_700_000_060_000i64);
    let winner_media = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["client_uuid"] == winner_media_id)
        .expect("winner media remains published");
    assert!(winner_media["deleted_at"].is_null());
    assert!(!pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == attacker_media_id));

    let family_media = rig.directory.path().join("media").join(family_id);
    assert!(family_media.join(&winner_media_id).is_file());
    assert!(!family_media.join(&attacker_media_id).exists());
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let attacker_publications: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM media_publications WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, attacker_media_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(attacker_publications, 0);
    let attacker_manifest_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM sync_bundle_media WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3",
            rusqlite::params![family_id, loser_bundle, attacker_media_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(attacker_manifest_rows, 0);
    let winner_publications: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM media_publications WHERE family_id = ?1 AND media_uuid = ?2 AND source = 'bundle'",
            rusqlite::params![family_id, winner_media_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(winner_publications, 1);

    // A crash after the SQLite commit but before final-path cleanup leaves a
    // durable pending row. Startup must finish that exact deletion and clear
    // both ownership and manifest evidence.
    let recovery_media_id = Uuid::new_v4().to_string();
    let recovery_path = family_media.join(&recovery_media_id);
    fs::write(&recovery_path, b"old").unwrap();
    connection
        .execute(
            "INSERT INTO sync_bundle_media(family_id, bundle_id, media_uuid, declared_byte_size) VALUES (?1, ?2, ?3, 3)",
            rusqlite::params![family_id, loser_bundle, recovery_media_id],
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO media_publications(family_id, media_uuid, source, bundle_id) VALUES (?1, ?2, 'bundle_pending', ?3)",
            rusqlite::params![family_id, recovery_media_id, loser_bundle],
        )
        .unwrap();
    drop(connection);

    let _restarted = rig.restart("generation-a");
    assert!(!recovery_path.exists());
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let recovery_publications: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM media_publications WHERE family_id = ?1 AND media_uuid = ?2",
            rusqlite::params![family_id, recovery_media_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(recovery_publications, 0);
    let recovery_manifest_rows: i64 = connection
        .query_row(
            "SELECT COUNT(*) FROM sync_bundle_media WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3",
            rusqlite::params![family_id, loser_bundle, recovery_media_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(recovery_manifest_rows, 0);
}

#[tokio::test]
async fn later_staged_member_next_feed_create_replays_nas_winner_as_noop() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "next-feed-late-owner",
        "next-feed-late-request-00000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let member_a = approve_new_member(&rig.app, owner_token, "next-feed-late-a").await;
    let member_b = approve_new_member(&rig.app, owner_token, "next-feed-late-b").await;
    let member_a_token = member_a["access_token"].as_str().unwrap();
    let member_b_token = member_b["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();

    let mut winner_payload = care_plan_payload(&baby_id, "formula");
    winner_payload["scheduled_at"] = json!(1_700_000_060_000i64);
    winner_payload["note"] = json!("[[lezi:next-feed:v1]]");
    winner_payload["payload_json"] = json!({"amount_ml": 0});
    winner_payload["created_by_membership_id"] = member_a["membership_id"].clone();
    let winner_bundle = Uuid::new_v4().to_string();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(member_a_token),
            json!({
                "bundle_id": winner_bundle,
                "root": entity_wire("care_plan", &plan_id, 2, winner_payload, None),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{winner_bundle}/commit"),
            Some(member_a_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

    // B starts only after A is already published. Stage canonicalizes B's
    // deterministic collision to the exact NAS winner and discards all media;
    // commit must recognize that durable canonical replay as the same no-op.
    let attacker_media_id = Uuid::new_v4().to_string();
    let mut loser_payload = care_plan_payload(&baby_id, "formula");
    loser_payload["scheduled_at"] = json!(1_700_000_120_000i64);
    loser_payload["note"] = json!("[[lezi:next-feed:v1]]");
    loser_payload["payload_json"] = json!({"amount_ml": 0});
    loser_payload["created_by_membership_id"] = member_b["membership_id"].clone();
    let loser_bundle = Uuid::new_v4().to_string();
    let (loser_stage, loser_stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(member_b_token),
        json!({
            "bundle_id": loser_bundle,
            "root": entity_wire("care_plan", &plan_id, 1, loser_payload, None),
            "media": [entity_wire(
                "media",
                &attacker_media_id,
                1,
                json!({
                    "kind": "log",
                    "record_client_uuid": null,
                    "care_plan_client_uuid": plan_id,
                    "baby_client_uuid": baby_id,
                    "mime": "image/jpeg",
                    "width": null,
                    "height": null,
                    "byte_size": 3,
                }),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(loser_stage, StatusCode::OK, "{loser_stage_body}");
    assert_eq!(loser_stage_body["missing_media"], json!([]));
    let (loser_commit, loser_commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{loser_bundle}/commit"),
        Some(member_b_token),
        json!({}),
    )
    .await;
    assert_eq!(loser_commit, StatusCode::OK, "{loser_commit_body}");
    assert_eq!(loser_commit_body["applied"], 0);

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let plan = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "care_plan" && entity["client_uuid"] == plan_id)
        .unwrap();
    assert_eq!(
        plan["payload"]["created_by_membership_id"],
        member_a["membership_id"]
    );
    assert_eq!(plan["payload"]["scheduled_at"], 1_700_000_060_000i64);
    assert!(!rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(attacker_media_id)
        .exists());
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
    let bundle_id = Uuid::new_v4().to_string();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(owner_token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "care_plan",
                    &plan_id,
                    1,
                    care_plan_payload(&baby_id, "bath"),
                    None
                ),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

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
    let bundle_id = Uuid::new_v4().to_string();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(owner_token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "care_plan",
                    &plan_id,
                    1,
                    care_plan_payload(&baby_id, "bath"),
                    None
                ),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

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

#[tokio::test]
async fn care_plan_creator_leave_admin_still_manages_member_does_not() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "care-plan-leave-owner",
        "care-plan-leave-request-000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let creator = approve_new_member(&rig.app, owner_token, "care-plan-leave-creator").await;
    let creator_token = creator["access_token"].as_str().unwrap();
    let peer = approve_new_member(&rig.app, owner_token, "care-plan-leave-peer").await;
    let peer_token = peer["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(creator_token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "care_plan",
                    &plan_id,
                    1,
                    care_plan_payload(&baby_id, "formula"),
                    None
                ),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(creator_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Creator leaves the family.
    let (leave_status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/leave",
        Some(creator_token),
        json!({}),
    )
    .await;
    assert_eq!(leave_status, StatusCode::OK);

    // Peer member still cannot manage the departed creator's plan.
    let mut payload = care_plan_payload(&baby_id, "formula");
    payload["note"] = json!("peer-edit");
    let (forbid, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(peer_token),
        json!({
            "bundle_id": Uuid::new_v4().to_string(),
            "root": entity_wire("care_plan", &plan_id, 2, payload.clone(), None),
            "media": []
        }),
    )
    .await;
    assert_eq!(forbid, StatusCode::FORBIDDEN, "{body}");

    // Owner/admin can still manage after creator leave.
    payload["note"] = json!("owner-after-leave");
    let owner_bundle = Uuid::new_v4().to_string();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(owner_token),
            json!({
                "bundle_id": owner_bundle,
                "root": entity_wire(
                    "care_plan",
                    &plan_id,
                    rig.now.load(Ordering::SeqCst) * 1_000 + 1,
                    payload,
                    None,
                ),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{owner_bundle}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let plan = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == plan_id)
        .expect("care_plan still visible");
    assert_eq!(plan["payload"]["note"], "owner-after-leave");
    assert_eq!(plan["payload"]["created_by_membership_id"], Value::Null);
}

#[tokio::test]
async fn care_plan_media_integrity_rejects_bad_refs_and_baby_mismatch() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "care-plan-media-owner",
        "care-plan-media-request-00000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let other_baby = seed_baby(&rig.app, owner_token).await;
    let plan_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/bundles",
            Some(owner_token),
            json!({
                "bundle_id": bundle_id,
                "root": entity_wire(
                    "care_plan",
                    &plan_id,
                    1,
                    care_plan_payload(&baby_id, "bath"),
                    None
                ),
                "media": []
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(owner_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Happy path: plan media with matching baby.
    let media_ok = Uuid::new_v4().to_string();
    let (ok, _) = stage_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire(
            "care_plan",
            &plan_id,
            2,
            care_plan_payload(&baby_id, "bath"),
            None,
        ),
        vec![entity_wire(
            "media",
            &media_ok,
            2,
            json!({
                "kind": "log",
                "care_plan_client_uuid": plan_id,
                "baby_client_uuid": baby_id,
                "mime": "image/jpeg",
                "byte_size": 3,
            }),
            None,
        )],
    )
    .await;
    assert_eq!(ok, StatusCode::OK);

    // Unknown care_plan_client_uuid is rejected.
    let media_bad_plan = Uuid::new_v4().to_string();
    let (bad_plan, body_plan) = stage_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire(
            "care_plan",
            &plan_id,
            3,
            care_plan_payload(&baby_id, "bath"),
            None,
        ),
        vec![entity_wire(
            "media",
            &media_bad_plan,
            3,
            json!({
                "kind": "log",
                "care_plan_client_uuid": Uuid::new_v4().to_string(),
                "mime": "image/jpeg",
                "byte_size": 3,
            }),
            None,
        )],
    )
    .await;
    assert_eq!(bad_plan, StatusCode::UNPROCESSABLE_ENTITY, "{body_plan}");

    // Baby mismatch vs care_plan baby is rejected.
    let media_bad_baby = Uuid::new_v4().to_string();
    let (bad_baby, body_baby) = stage_bundle_with_media(
        &rig.app,
        owner_token,
        entity_wire(
            "care_plan",
            &plan_id,
            4,
            care_plan_payload(&baby_id, "bath"),
            None,
        ),
        vec![entity_wire(
            "media",
            &media_bad_baby,
            4,
            json!({
                "kind": "log",
                "care_plan_client_uuid": plan_id,
                "baby_client_uuid": other_baby,
                "mime": "image/jpeg",
                "byte_size": 3,
            }),
            None,
        )],
    )
    .await;
    assert_eq!(bad_baby, StatusCode::UNPROCESSABLE_ENTITY, "{body_baby}");
}

#[tokio::test]
async fn atomic_bundle_rejects_stale_root_when_published_is_newer() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-stale-owner",
        "bundle-stale-request-00000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();

    // Ordinary push publishes a newer record.
    seed_record_with_id(&rig.app, token, &record_id, 100, record_payload(&baby_id)).await;

    let bundle_id = Uuid::new_v4().to_string();
    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire("record", &record_id, 50, record_payload(&baby_id), None),
            "media": []
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (commit_status, body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::CONFLICT, "{body}");
}

#[tokio::test]
async fn pending_bundle_bytes_are_not_claimed_by_ordinary_metadata_or_put_across_restart() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-stale-media-owner",
        "bundle-stale-media-request-00001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let family_id = created["family_id"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    seed_record_with_id(&rig.app, token, &record_id, 100, record_payload(&baby_id)).await;

    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                50,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                50,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(token),
            Body::from("old"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    let (commit_status, commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::CONFLICT, "{commit_body}");

    let final_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(&media_id);
    assert_eq!(
        fs::read(&final_path).unwrap(),
        b"old",
        "bundle bytes must be durable before the SQLite publish attempt"
    );

    // A same-id refinement removes the old staging manifest. Quarantine
    // ownership must outlive that row, otherwise startup could mistake the
    // pre-published final-path file for an ordinary upload.
    let (refine_status, refine_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                50,
                record_payload(&baby_id),
                None,
            ),
            "media": [],
        }),
    )
    .await;
    assert_eq!(refine_status, StatusCode::OK, "{refine_body}");

    let (ordinary_status, ordinary_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire(
                "media",
                &media_id,
                101,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(
        ordinary_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{ordinary_body}"
    );

    let ordinary_put = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::from("new"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(
        ordinary_put.status(),
        StatusCode::UNPROCESSABLE_ENTITY,
        "retired ordinary PUT claimed media bytes reserved by an incomplete bundle"
    );
    assert_eq!(
        fs::read(&final_path).unwrap(),
        b"old",
        "rejected ordinary PUT replaced quarantined bundle bytes"
    );

    let restarted = rig.restart("generation-b");
    for (app, generation) in [(&rig.app, "generation-a"), (&restarted, "generation-b")] {
        let (_, pull) = get_json(
            app,
            &format!("/v1/pull?cursor=0&generation={generation}"),
            Some(token),
        )
        .await;
        assert!(
            !pull["entities"]
                .as_array()
                .unwrap()
                .iter()
                .any(|entity| entity["client_uuid"] == media_id),
            "retired ordinary push claimed bytes from the rejected bundle: {pull}"
        );
        let response = request(
            app,
            Method::GET,
            &format!("/v1/media/{media_id}"),
            Some(token),
            Body::empty(),
            None,
        )
        .await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
}

#[tokio::test]
async fn losing_bundle_media_tombstone_does_not_publish_quarantined_bytes() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-losing-media-owner",
        "bundle-losing-media-request-000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let stale_bundle_id = Uuid::new_v4().to_string();

    seed_record_with_id(&rig.app, token, &record_id, 100, record_payload(&baby_id)).await;

    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": stale_bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                50,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                50,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{stale_bundle_id}/media/{media_id}"),
            Some(token),
            Body::from("old"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    let (stale_status, stale_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{stale_bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(stale_status, StatusCode::CONFLICT, "{stale_body}");

    let (metadata_status, metadata_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire(
                "media",
                &media_id,
                100,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(
        metadata_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{metadata_body}"
    );

    let winner_bundle_id = Uuid::new_v4().to_string();
    let (winner_stage_status, winner_stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": winner_bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                101,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                99,
                log_media_payload(&record_id),
                Some(99),
            )],
        }),
    )
    .await;
    assert_eq!(winner_stage_status, StatusCode::OK, "{winner_stage_body}");
    let (commit_status, commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{winner_bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");
    assert_eq!(commit_body["applied"], 2);

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let media_tombstone = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["client_uuid"] == media_id)
        .expect("winning bundle publishes the media tombstone metadata");
    assert_eq!(media_tombstone["deleted_at"], 99, "{pull}");
    assert_eq!(
        request(
            &rig.app,
            Method::GET,
            &format!("/v1/media/{media_id}"),
            Some(token),
            Body::empty(),
            None,
        )
        .await
        .status(),
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn atomic_bundle_supports_baby_and_avatar_media() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "ordinary-media-owner",
        "ordinary-media-request-0000000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let avatar_id = Uuid::new_v4().to_string();
    let (status, body) = publish_bundle_with_media(
        &rig.app,
        token,
        entity_wire(
            "baby",
            &baby_id,
            2,
            baby_payload("年年", Some(&avatar_id)),
            None,
        ),
        vec![(
            entity_wire(
                "media",
                &avatar_id,
                2,
                json!({
                    "kind": "avatar",
                    "record_client_uuid": null,
                    "baby_client_uuid": baby_id,
                    "care_plan_client_uuid": null,
                    "mime": "image/jpeg",
                    "width": 64,
                    "height": 64,
                    "byte_size": 3,
                }),
                None,
            ),
            b"img".to_vec(),
        )],
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let record_id = seed_record(&rig.app, token, &baby_id).await;
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let entities = pull["entities"].as_array().unwrap();
    assert!(entities.iter().any(|e| e["client_uuid"] == avatar_id));
    assert!(entities.iter().any(|e| e["client_uuid"] == record_id));
    let bytes = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{avatar_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(bytes.status(), StatusCode::OK);
    assert_eq!(bytes.into_body().collect().await.unwrap().to_bytes(), "img");
}

#[tokio::test]
async fn ordinary_put_cannot_overwrite_committed_bundle_media() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-owned-media-owner",
        "bundle-owned-media-request-0000001",
    )
    .await;
    let token = created["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": bundle_id,
            "root": entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            ),
            "media": [entity_wire(
                "media",
                &media_id,
                2,
                log_media_payload(&record_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");
    assert_eq!(
        request(
            &rig.app,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(token),
            Body::from("img"),
            Some("image/jpeg"),
        )
        .await
        .status(),
        StatusCode::OK
    );
    let (commit_status, commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");

    let overwrite = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::from("bad"),
        Some("image/jpeg"),
    )
    .await;
    assert_eq!(overwrite.status(), StatusCode::UNPROCESSABLE_ENTITY);

    let preserved = request(
        &rig.app,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(preserved.status(), StatusCode::OK);
    assert_eq!(
        preserved.into_body().collect().await.unwrap().to_bytes(),
        "img"
    );
}

// ---------------------------------------------------------------------------
// Custom item family definitions (atomic bundle + ACL)
// ---------------------------------------------------------------------------

fn custom_item_payload(name: &str, icon_slot: i64) -> Value {
    json!({
        "name": name,
        "icon_slot": icon_slot,
    })
}

#[tokio::test]
async fn tombstoned_custom_item_supports_history_and_fulfillment_but_not_new_roots() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "custom-history-owner",
        "custom-history-owner-request-00001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let custom_item_id = Uuid::new_v4().to_string();
    let historical_record_id = Uuid::new_v4().to_string();
    let historical_plan_id = Uuid::new_v4().to_string();
    let fulfilled_record_id = Uuid::new_v4().to_string();
    let rebound_record_id = Uuid::new_v4().to_string();
    let custom_record = |note: &str| {
        json!({
            "baby_client_uuid": baby_id,
            "type": "custom",
            "custom_item_client_uuid": custom_item_id,
            "timestamp": 1_700_000_000_000i64,
            "end_timestamp": null,
            "note": note,
            "payload_json": {"title": "抚触"},
            "schema_version": 2,
        })
    };
    let mut custom_plan = care_plan_payload(&baby_id, "custom");
    custom_plan["custom_item_client_uuid"] = json!(custom_item_id);

    for root in [
        entity_wire(
            "custom_item",
            &custom_item_id,
            2,
            custom_item_payload("抚触", 2),
            None,
        ),
        entity_wire(
            "record",
            &historical_record_id,
            3,
            custom_record("历史记录"),
            None,
        ),
        entity_wire(
            "care_plan",
            &historical_plan_id,
            3,
            custom_plan.clone(),
            None,
        ),
    ] {
        let (status, body) = publish_root_bundle(&rig.app, token, root).await;
        assert_eq!(status, StatusCode::OK, "{body}");
    }
    let (deleted_status, deleted_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "custom_item",
            &custom_item_id,
            4,
            custom_item_payload("抚触", 2),
            Some(4),
        ),
    )
    .await;
    assert_eq!(deleted_status, StatusCode::OK, "{deleted_body}");

    let (edit_status, edit_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "record",
            &historical_record_id,
            5,
            custom_record("历史记录已编辑"),
            None,
        ),
    )
    .await;
    assert_eq!(edit_status, StatusCode::OK, "{edit_body}");

    for root in [
        entity_wire(
            "record",
            &Uuid::new_v4().to_string(),
            5,
            custom_record("伪造新事实"),
            None,
        ),
        entity_wire(
            "care_plan",
            &Uuid::new_v4().to_string(),
            5,
            custom_plan.clone(),
            None,
        ),
    ] {
        let (status, body) = publish_root_bundle(&rig.app, token, root).await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert!(
            body["detail"]
                .as_str()
                .unwrap_or_default()
                .contains("is deleted and cannot be selected"),
            "{body}"
        );
    }

    custom_plan["status"] = json!("completed");
    custom_plan["fulfilled_record_client_uuid"] = json!(fulfilled_record_id);
    custom_plan["fulfilled_at"] = json!(1_700_000_100_000i64);
    let (plan_status, plan_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "care_plan",
            &historical_plan_id,
            6,
            custom_plan.clone(),
            None,
        ),
    )
    .await;
    assert_eq!(plan_status, StatusCode::OK, "{plan_body}");
    let (record_status, record_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "record",
            &fulfilled_record_id,
            7,
            custom_record("显式履行事实"),
            None,
        ),
    )
    .await;
    assert_eq!(record_status, StatusCode::OK, "{record_body}");

    let (candidate_status, candidate_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "fulfillment_candidate",
            &Uuid::new_v4().to_string(),
            8,
            json!({
                "care_plan_client_uuid": historical_plan_id,
                "record_client_uuid": fulfilled_record_id,
                "actual_timestamp": 1_700_000_000_000i64,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(candidate_status, StatusCode::OK, "{candidate_body}");

    let mut rebound_plan = custom_plan.clone();
    rebound_plan["fulfilled_record_client_uuid"] = json!(rebound_record_id);
    let (rebind_status, rebind_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire("care_plan", &historical_plan_id, 9, rebound_plan, None),
    )
    .await;
    assert_eq!(rebind_status, StatusCode::CONFLICT, "{rebind_body}");
    assert_eq!(
        rebind_body,
        json!({"detail": "Completed care plan fulfillment binding is immutable"})
    );
    let (rebound_record_status, rebound_record_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "record",
            &rebound_record_id,
            10,
            custom_record("伪造改绑事实"),
            None,
        ),
    )
    .await;
    assert_eq!(
        rebound_record_status,
        StatusCode::CONFLICT,
        "{rebound_record_body}"
    );

    let (record_delete_status, record_delete_body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "record",
            &historical_record_id,
            9,
            custom_record("历史记录已编辑"),
            Some(9),
        ),
    )
    .await;
    assert_eq!(record_delete_status, StatusCode::OK, "{record_delete_body}");

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let entities = pull["entities"].as_array().unwrap();
    assert_eq!(
        entities
            .iter()
            .find(|entity| entity["client_uuid"] == custom_item_id)
            .unwrap()["deleted_at"],
        4
    );
    assert!(entities
        .iter()
        .any(|entity| entity["client_uuid"] == fulfilled_record_id));
    assert!(entities
        .iter()
        .all(|entity| entity["client_uuid"] != rebound_record_id));
    assert_eq!(
        entities
            .iter()
            .find(|entity| entity["client_uuid"] == historical_plan_id)
            .unwrap()["payload"]["fulfilled_record_client_uuid"],
        fulfilled_record_id
    );
}

#[tokio::test]
async fn custom_item_create_stamps_creator_and_syncs_to_peer() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "custom-item-owner-request-00000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let owner_membership = owner["membership_id"].as_str().unwrap().to_owned();
    let member = approve_new_member(&rig.app, owner_token, "member-device").await;
    let member_token = member["access_token"].as_str().unwrap();
    let member_membership = member["membership_id"].as_str().unwrap().to_owned();
    let item_id = Uuid::new_v4().to_string();

    let (status, body) = publish_root_bundle(
        &rig.app,
        member_token,
        entity_wire(
            "custom_item",
            &item_id,
            10,
            // Client-supplied creator must be ignored in favor of principal.
            json!({
                "name": "抚触",
                "icon_slot": 2,
                "created_by_membership_id": "spoofed-id",
            }),
            None,
        ),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["applied"], 1);

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let entities = pull["entities"].as_array().unwrap();
    let item = entities
        .iter()
        .find(|e| e["client_uuid"] == item_id)
        .expect("custom_item visible to owner");
    assert_eq!(item["type"], "custom_item");
    assert_eq!(item["payload"]["name"], "抚触");
    assert_eq!(item["payload"]["icon_slot"], 2);
    assert_eq!(
        item["payload"]["created_by_membership_id"],
        member_membership
    );
    assert_ne!(
        item["payload"]["created_by_membership_id"],
        owner_membership
    );
    // Layout fields must never be accepted on the wire.
    assert!(item["payload"].get("sort_order").is_none());
    assert!(item["payload"].get("hidden").is_none());
}

#[tokio::test]
async fn custom_item_member_acl_rename_delete_and_owner_override() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "custom-item-acl-request-0000000001",
    )
    .await;
    let owner_token = owner["access_token"].as_str().unwrap();
    let member_a = approve_new_member(&rig.app, owner_token, "member-a").await;
    let member_a_token = member_a["access_token"].as_str().unwrap();
    let member_b = approve_new_member(&rig.app, owner_token, "member-b").await;
    let member_b_token = member_b["access_token"].as_str().unwrap();
    let item_id = Uuid::new_v4().to_string();

    assert_eq!(
        publish_root_bundle(
            &rig.app,
            member_a_token,
            entity_wire(
                "custom_item",
                &item_id,
                1,
                custom_item_payload("药", 1),
                None
            ),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Creator may rename.
    let (ok_rename, _) = publish_root_bundle(
        &rig.app,
        member_a_token,
        entity_wire(
            "custom_item",
            &item_id,
            2,
            custom_item_payload("用药", 1),
            None,
        ),
    )
    .await;
    assert_eq!(ok_rename, StatusCode::OK);

    // Peer member cannot rename.
    let (denied, body) = publish_root_bundle(
        &rig.app,
        member_b_token,
        entity_wire(
            "custom_item",
            &item_id,
            3,
            custom_item_payload("篡改", 0),
            None,
        ),
    )
    .await;
    assert_eq!(denied, StatusCode::FORBIDDEN, "{body}");

    // Owner may rename and soft-delete.
    assert_eq!(
        publish_root_bundle(
            &rig.app,
            owner_token,
            entity_wire(
                "custom_item",
                &item_id,
                4,
                custom_item_payload("管理员改名", 3),
                None,
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
                "custom_item",
                &item_id,
                5,
                custom_item_payload("管理员改名", 3),
                Some(5),
            ),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Tombstone cannot be resurrected (even by owner).
    let (resurrect, resurrect_body) = publish_root_bundle(
        &rig.app,
        owner_token,
        entity_wire(
            "custom_item",
            &item_id,
            6,
            custom_item_payload("管理员改名", 3),
            None,
        ),
    )
    .await;
    assert_eq!(resurrect, StatusCode::CONFLICT, "{resurrect_body}");
}

#[tokio::test]
async fn custom_item_rejects_layout_fields_on_wire() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "custom-item-layout-request-0000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    let item_id = Uuid::new_v4().to_string();
    let (status, body) = publish_root_bundle(
        &rig.app,
        token,
        entity_wire(
            "custom_item",
            &item_id,
            1,
            json!({
                "name": "抚触",
                "icon_slot": 0,
                "sort_order": 3,
            }),
            None,
        ),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
}

#[tokio::test]
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
    let v1 = rec_body["results"][0]["stable_version_id"]
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
    assert_eq!(replay["results"][0]["stable_version_id"], v1);
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
    assert_eq!(rejected["results"][0]["status"], "rejected");
    assert_eq!(rejected["results"][0]["code"], "content_drift");

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
    let v2 = left["results"][0]["stable_version_id"]
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

    let compacted: (i64, i64) = Connection::open(&database_path)
        .unwrap()
        .query_row(
            "SELECT
                (SELECT COUNT(*) FROM conflict_branches WHERE conflict_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts
                  WHERE membership_id = '__conflict_snapshot_v2__' AND conflict_id = ?1)",
            [&conflict_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
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
        committed["results"][2]["stable_root"]["baby_client_uuid"],
        baby_id.to_string(),
    );
    assert_eq!(
        committed["results"][2]["stable_root"]["custom_item_client_uuid"],
        custom_item_id.to_string(),
    );

    let (status, replayed) = causal_commit_units(&rig.app, token, frozen).await;
    assert_eq!(status, StatusCode::OK, "{replayed}");
    for index in 0..3 {
        assert_eq!(
            replayed["results"][index]["stable_version_id"],
            committed["results"][index]["stable_version_id"],
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
    assert!(rejected["results"]
        .as_array()
        .unwrap()
        .iter()
        .all(|result| { result["status"] == "rejected" && result["code"] == "content_drift" }));
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
        committed["results"][2]["stable_root"]["fulfilled_record_client_uuid"],
        record_id.to_string(),
    );
    let stable_plan = committed["results"][2]["stable_version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, replayed) = causal_commit_units(&rig.app, token, frozen).await;
    assert_eq!(status, StatusCode::OK, "{replayed}");
    assert_eq!(
        replayed["results"][2]["stable_version_id"],
        committed["results"][2]["stable_version_id"],
    );
    assert_eq!(
        replayed["results"][2]["request_hash"],
        committed["results"][2]["request_hash"],
    );

    let mut drift = plan.clone();
    drift["root"]["note"] = json!("漂移计划");
    let (status, rejected) = causal_commit_units(&rig.app, token, vec![drift]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["results"][0]["status"], "rejected");
    assert_eq!(rejected["results"][0]["code"], "content_drift");

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
        accepted["results"][0]["stable_root"]["sleep_record_client_uuid"],
        sleep_id.to_string(),
    );
    assert_eq!(
        accepted["results"][0]["stable_root"]["observer_membership_id"],
        owner["membership_id"],
    );
    let first_version = accepted["results"][0]["stable_version_id"]
        .as_str()
        .unwrap()
        .to_owned();

    let (status, replayed) = causal_commit_units(&rig.app, owner_token, vec![wake.clone()]).await;
    assert_eq!(status, StatusCode::OK, "{replayed}");
    assert_eq!(
        replayed["results"][0]["stable_version_id"],
        accepted["results"][0]["stable_version_id"],
    );
    assert_eq!(
        replayed["results"][0]["request_hash"],
        accepted["results"][0]["request_hash"],
    );

    let mut drift = wake;
    drift["root"]["note"] = json!("same mutation drift");
    let (status, rejected) = causal_commit_units(&rig.app, owner_token, vec![drift]).await;
    assert_eq!(status, StatusCode::OK, "{rejected}");
    assert_eq!(rejected["results"][0]["status"], "rejected");
    assert_eq!(rejected["results"][0]["code"], "content_drift");

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
    let stable_version = stable_edit["results"][0]["stable_version_id"]
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
    assert_eq!(branched["results"][0]["stable_root"]["note"], "stable edit",);
    assert_eq!(
        branched["results"][0]["stable_root"]["sleep_record_client_uuid"],
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
    let base = created["results"][0]["stable_version_id"].as_str().unwrap();
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
    let live_version = live["results"][0]["stable_version_id"]
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
    let base_version = created["results"][0]["stable_version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let base_root = created["results"][0]["stable_root"].clone();
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
    let tombstone_version = deleted["results"][0]["stable_version_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let conflict_id = deleted["results"][0]["conflict_id"]
        .as_str()
        .unwrap()
        .to_owned();
    let (status, detail) = get_json(
        &rig.app,
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
    let restarted = rig.restart("generation-a");
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
    assert_eq!(
        oversized_status,
        StatusCode::UNPROCESSABLE_ENTITY,
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
    assert_eq!(replay["results"], first["results"]);
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
    let base = created["results"][0]["stable_version_id"].as_str().unwrap();
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

    let current_stable = accepted["results"][0]["stable_version_id"]
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
    assert_eq!(replay["results"], branched["results"]);
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
    assert_eq!(rejected["results"][0]["status"], "rejected");
    assert_eq!(rejected["results"][0]["code"], "invalid_entity_value");
    assert_eq!(rejected["results"][1]["status"], "rejected");
    assert_eq!(rejected["results"][1]["code"], "invalid_reference");

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
    assert_eq!(rejected["results"][0]["status"], "rejected");
    assert_eq!(rejected["results"][0]["code"], "media_sha256_mismatch");

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
        .find(|entry| entry.file_name().to_string_lossy().ends_with(".upload.tmp"))
        .expect("first chunk was streamed to a server-owned incoming file");
    assert_eq!(incoming.metadata().unwrap().len(), 5);
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
            .filter(|entry| entry.file_name().to_string_lossy().ends_with(".upload.tmp"))
            .count(),
        1,
        "cancelled upload kept its temp reservation"
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
            .filter(|entry| entry.file_name().to_string_lossy().ends_with(".upload.tmp"))
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
            .filter(|entry| entry.file_name().to_string_lossy().ends_with(".upload.tmp"))
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
async fn causal_media_published_path_conflict_rejects_before_projection_and_retry_converges() {
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
    assert_eq!(status, StatusCode::OK, "{failed}");
    assert_eq!(failed["results"][0]["status"], "rejected");
    assert_eq!(failed["results"][0]["code"], "media_uuid_conflict");
    assert_eq!(fs::read(&staged_path).unwrap(), bytes);
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
    let (status, committed) = causal_commit_units(&rig.app, token, vec![mutation]).await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    assert_eq!(committed["results"][0]["status"], "accepted");
    assert_eq!(fs::read(&final_path).unwrap(), bytes);
    assert!(!staged_path.exists());
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
    assert_eq!(rejected["results"][0]["status"], "rejected");
    assert_eq!(rejected["results"][0]["code"], "media_preimage_expired");

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
            &format!("/v1/pull?cursor={cursor}&generation={generation}"),
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
            result["stable_version_id"].as_str().unwrap().to_owned()
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
            result["stable_version_id"].as_str().unwrap().to_owned()
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
    let owner_display_version = owner_display_created["results"][0]["stable_version_id"]
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
    let v1 = created["results"][0]["stable_version_id"]
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
    assert_eq!(right["results"][0]["stable_root"]["note"], "owner-note");
    assert_eq!(
        right["results"][0]["stable_root"]["payload_json"]["amount_ml"],
        180
    );
    let v_merged = right["results"][0]["stable_version_id"]
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
    let v_owner = owner_note["results"][0]["stable_version_id"]
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
        member_branch["results"][0]["stable_version_id"].as_str(),
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
    let v1 = created["results"][0]["stable_version_id"]
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
    assert_eq!(replay["results"][0]["stable_version_id"], v1);

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
    let tombstone_v = deleted["results"][0]["stable_version_id"]
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
        concurrent_edit["results"][0]["stable_version_id"].as_str(),
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
    assert_eq!(stale["results"][0]["status"], "rejected");
    assert_eq!(
        stale["results"][0]["code"].as_str(),
        Some("stale_live_over_tombstone")
    );

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
    let vb1 = created_b["results"][0]["stable_version_id"]
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
    let vb2 = edited["results"][0]["stable_version_id"]
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
        delete_late["results"][0]["stable_version_id"].as_str(),
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
    let v1 = created["results"][0]["stable_version_id"]
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
    let media = right["results"][0]["stable_media"]
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
    let base = c2["results"][0]["stable_version_id"]
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
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    assert_eq!(body["code"], json!("client_update_required"), "{body}");

    let (pull_status, pull_body) = raw_json_request_with_headers(
        &app,
        Method::GET,
        "/v1/pull?cursor=0&generation=generation-a",
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
