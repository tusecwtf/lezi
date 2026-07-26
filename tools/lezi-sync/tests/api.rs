use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::sync::Arc;

use axum::body::{Body, Bytes};
use axum::http::header::{AUTHORIZATION, CONTENT_TYPE};
use axum::http::{Method, Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use lezi_sync::{build_app, RateLimitConfig, ServerConfig, VERSION};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tempfile::TempDir;
use tokio::sync::oneshot;
use tower::ServiceExt;
use uuid::Uuid;

struct Rig {
    directory: TempDir,
    app: Router,
    now: Arc<AtomicI64>,
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
}

fn app_for(
    directory: &Path,
    generation: &str,
    now: Arc<AtomicI64>,
    max_media_bytes: usize,
    configure: impl FnOnce(&mut ServerConfig),
) -> Router {
    static INVITE_COUNTER: AtomicU64 = AtomicU64::new(1);
    let clock = now.clone();
    let mut config = ServerConfig::new(directory);
    config.generation = Some(generation.to_owned());
    config.max_media_bytes = max_media_bytes;
    // Integration tests create/join many times within one process; keep limits high
    // unless a test tightens them deliberately.
    config.create_rate_limit = RateLimitConfig {
        max_attempts: 10_000,
        window_seconds: 60,
    };
    config.join_rate_limit = RateLimitConfig {
        max_attempts: 10_000,
        window_seconds: 60,
    };
    configure(&mut config);
    config = config
        .with_clock(move || clock.load(Ordering::SeqCst))
        .with_invite_code_factory(|| {
            format!("CODE{:08}", INVITE_COUNTER.fetch_add(1, Ordering::SeqCst))
        });
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
    let mut builder = Request::builder().method(method).uri(uri);
    if let Some(token) = token {
        builder = builder.header(AUTHORIZATION, format!("Bearer {token}"));
    }
    if let Some(content_type) = content_type {
        builder = builder.header(CONTENT_TYPE, content_type);
    }
    for (name, value) in extra_headers {
        builder = builder.header(*name, *value);
    }
    app.clone()
        .oneshot(builder.body(body).unwrap())
        .await
        .unwrap()
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

async fn get_json(app: &Router, uri: &str, token: Option<&str>) -> (StatusCode, Value) {
    json_request(app, Method::GET, uri, token, json!({})).await
}

async fn create_family(app: &Router, device_id: &str, request_id: &str) -> Value {
    let (status, body) = json_request(
        app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "device_id": device_id,
            "display_name": "妈妈",
        }),
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
        "due_date": null,
        "avatar_media_uuid": avatar_media_uuid,
        "birth_weight_grams": 3200,
    })
}

fn record_payload(baby_id: &str) -> Value {
    json!({
        "baby_client_uuid": baby_id,
        "type": "formula",
        "timestamp": 100,
        "end_timestamp": null,
        "note": null,
        "payload_json": {"amount_ml": 120},
        "schema_version": 1,
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

async fn invite_and_join(app: &Router, owner_token: &str, device_id: &str) -> Value {
    let (status, invitation) = json_request(
        app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    let (status, member) = json_request(
        app,
        Method::POST,
        "/v1/join",
        None,
        json!({
            "code": invitation["code"],
            "device_id": device_id,
            "display_name": "成员",
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member}");
    member
}

fn token_hash(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

#[tokio::test]
async fn liveness_and_readiness_initialize_private_single_data_root() {
    let rig = Rig::new();
    let (status, body) = get_json(&rig.app, "/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"ok": true, "version": VERSION}));
    let (ready_status, ready_body) = get_json(&rig.app, "/ready", None).await;
    assert_eq!(ready_status, StatusCode::OK);
    assert_eq!(ready_body, json!({"ok": true, "version": VERSION}));
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
    let restarted = rig.restart("generation-b");
    let retry = create_family(&restarted, "owner-device", request_id).await;
    assert_eq!(retry["family_id"], first["family_id"]);
    assert_eq!(retry["token"], first["token"]);
    assert_eq!(retry["generation"], "generation-b");
    let (conflict, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "Bv4Na1mK9sQ8pR7tU6wX5yZ3cD2eF0gH",
            "device_id": "owner-device",
            "display_name": "妈妈",
        }),
    )
    .await;
    assert_eq!(conflict, StatusCode::CONFLICT);

    let persisted = fs::read(rig.directory.path().join("lezi.db")).unwrap();
    assert!(!persisted
        .windows(request_id.len())
        .any(|window| window == request_id.as_bytes()));
    let token = first["token"].as_str().unwrap();
    assert!(!persisted
        .windows(token.len())
        .any(|window| window == token.as_bytes()));
}

#[tokio::test]
async fn invite_join_roles_expiry_restart_and_leave_match_contract() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "invite-owner-request-0000000000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let (status, invitation) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(
        invitation["expires_at"],
        rig.now.load(Ordering::SeqCst) + 24 * 60 * 60
    );
    let code = invitation["code"].as_str().unwrap();
    assert!(code
        .bytes()
        .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit()));
    let (status, member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({"code": code, "device_id": "member-device", "display_name": "成员"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(member["role"], "member");
    let restarted = rig.restart("generation-b");
    let (retry_status, retry) = json_request(
        &restarted,
        Method::POST,
        "/v1/join",
        None,
        json!({"code": code, "device_id": "member-device", "display_name": "成员"}),
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK);
    assert_eq!(retry["token"], member["token"]);
    assert_eq!(retry["generation"], "generation-b");
    let (replay_status, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/join",
        None,
        json!({"code": code, "device_id": "other-device", "display_name": "成员"}),
    )
    .await;
    assert_eq!(replay_status, StatusCode::CONFLICT);
    let member_token = member["token"].as_str().unwrap();
    let (member_invite, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/invite",
        Some(member_token),
        json!({}),
    )
    .await;
    assert_eq!(member_invite, StatusCode::FORBIDDEN);
    let (owner_leave, owner_leave_body) = json_request(
        &restarted,
        Method::POST,
        "/v1/leave",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(owner_leave, StatusCode::FORBIDDEN);
    assert_eq!(
        owner_leave_body,
        json!({"detail":"Owner must delete the family instead of leaving"})
    );
    let (leave_status, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/leave",
        Some(member_token),
        json!({}),
    )
    .await;
    assert_eq!(leave_status, StatusCode::OK);
    assert_eq!(
        get_json(&restarted, "/v1/pull?cursor=0", Some(member_token))
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );

    let (status, expiring) = json_request(
        &restarted,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED);
    rig.now.fetch_add(25 * 60 * 60, Ordering::SeqCst);
    let (expired, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/join",
        None,
        json!({"code": expiring["code"], "device_id": "late-device", "display_name": "成员"}),
    )
    .await;
    assert_eq!(expired, StatusCode::GONE);
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
    let owner_token = owner["token"].as_str().unwrap();
    let (invite_status, invitation) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(invite_status, StatusCode::CREATED);
    let (join_status, member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({
            "code": invitation["code"],
            "device_id": "member-sensitive-device-id",
            "display_name": "　 陈爸爸 🌿  ",
        }),
    )
    .await;
    assert_eq!(join_status, StatusCode::OK, "{member}");
    let member_token = member["token"].as_str().unwrap();

    let (owner_status, owner_view) =
        get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(owner_status, StatusCode::OK);
    assert_eq!(
        owner_view,
        json!({"members":[
            {
                "display_name":"妈妈",
                "role":"owner",
                "is_self":true,
                "device_id":"owner-sensitive-device-id",
            },
            {
                "display_name":"陈爸爸 🌿",
                "role":"member",
                "is_self":false,
                "device_id":"member-sensitive-device-id",
            },
        ]})
    );
    let (member_status, member_view) =
        get_json(&rig.app, "/v1/family/members", Some(member_token)).await;
    assert_eq!(member_status, StatusCode::OK);
    assert_eq!(
        member_view,
        json!({"members":[
            {
                "display_name":"妈妈",
                "role":"owner",
                "is_self":false,
                "device_id":"owner-sensitive-device-id",
            },
            {
                "display_name":"陈爸爸 🌿",
                "role":"member",
                "is_self":true,
                "device_id":"member-sensitive-device-id",
            },
        ]})
    );
    let visible_members = |body: &Value| {
        body["members"]
            .as_array()
            .unwrap()
            .iter()
            .map(|member| (member["display_name"].clone(), member["role"].clone()))
            .collect::<Vec<_>>()
    };
    assert_eq!(visible_members(&owner_view), visible_members(&member_view));

    let serialized = owner_view.to_string();
    // Tokens / token hashes must never appear; device_id is the intentional
    // client link key for created_by_device_id → 称呼 resolution.
    for secret in [owner_token, member_token, &token_hash(owner_token), &token_hash(member_token)] {
        assert!(
            !serialized.contains(secret),
            "leaked {secret}: {serialized}"
        );
    }
    for row in owner_view["members"].as_array().unwrap() {
        let fields = row.as_object().unwrap();
        assert_eq!(fields.len(), 4);
        assert!(fields.contains_key("device_id"));
        assert!(!fields.contains_key("token_hash"));
        assert!(!fields.contains_key("token"));
        assert!(!fields.contains_key("family_id"));
    }

    let isolated_family_id = Uuid::new_v4().to_string();
    let isolated_token = "isolated-family-owner-token";
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
            INSERT INTO memberships(token_hash, family_id, role, device_id, display_name)
            VALUES (?1, ?2, 'owner', 'isolated-raw-device-id', '隔离家庭')
            ",
            rusqlite::params![token_hash(isolated_token), isolated_family_id],
        )
        .unwrap();
    drop(connection);

    let (_, isolated_view) = get_json(&rig.app, "/v1/family/members", Some(isolated_token)).await;
    assert_eq!(
        isolated_view,
        json!({"members":[
            {
                "display_name":"隔离家庭",
                "role":"owner",
                "is_self":true,
                "device_id":"isolated-raw-device-id",
            },
        ]})
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
    assert_eq!(
        after_leave["members"][0]["device_id"],
        "owner-sensitive-device-id"
    );
}

#[tokio::test]
async fn family_members_normalize_unicode_and_empty_names_and_reject_unsafe_join_names() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "name-owner-device",
        "member-name-owner-request-000000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();

    let join = |code: Value, device_id: &str, display_name: Value| {
        json!({
            "code": code,
            "device_id": device_id,
            "display_name": display_name,
        })
    };
    let (_, unicode_invite) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/join",
            None,
            join(
                unicode_invite["code"].clone(),
                "unicode-member",
                json!("　李爸爸 👨‍🍼　"),
            ),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Blank / placeholder / unsafe names are hard-rejected (no silent null).
    for (device_id, bad_name) in [
        ("empty-name-member", json!("　  ")),
        ("missing-name-member", json!(null)),
        ("placeholder-member", json!("我（本机）")),
        ("newline-member", json!("名字\n伪装")),
        ("bidi-member", json!("成员\u{202e}renwo")),
        ("long-member", json!("名".repeat(129))),
    ] {
        let (_, invitation) = json_request(
            &rig.app,
            Method::POST,
            "/v1/invite",
            Some(owner_token),
            json!({}),
        )
        .await;
        let (status, _) = json_request(
            &rig.app,
            Method::POST,
            "/v1/join",
            None,
            join(invitation["code"].clone(), device_id, bad_name),
        )
        .await;
        assert_eq!(
            status,
            StatusCode::UNPROCESSABLE_ENTITY,
            "expected 422 for {device_id}"
        );
    }

    // Omitted display_name field also fails.
    let (_, omitted_invite) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/join",
            None,
            json!({
                "code": omitted_invite["code"],
                "device_id": "omitted-name-member",
            }),
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );

    let (_, members) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(members["members"].as_array().unwrap().len(), 2);
    assert_eq!(members["members"][0]["role"], "owner");
    assert!(members["members"].as_array().unwrap().iter().any(|member| {
        member["display_name"] == "李爸爸 👨‍🍼" // placeholder replaced below
            && member["role"] == "member"
            && member["device_id"] == "unicode-member"
    }));
}

#[tokio::test]
async fn family_create_uses_the_same_display_name_normalization_as_join() {
    let unsafe_rig = Rig::new();
    let (unsafe_status, _) = json_request(
        &unsafe_rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "unsafe-owner-request-000000000001",
            "device_id": "unsafe-owner-device",
            "display_name": "管理员\u{202e}renwo",
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
                "device_id": "blank-owner-device",
                "display_name": display_name,
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
            "device_id": "trimmed-owner-device",
            "display_name": "　妈妈　",
        }),
    )
    .await;
    assert_eq!(create_status, StatusCode::CREATED);
    let (_, members) = get_json(
        &normalized_rig.app,
        "/v1/family/members",
        owner["token"].as_str(),
    )
    .await;
    assert_eq!(members["members"][0]["display_name"], "妈妈");
    assert_eq!(members["members"][0]["device_id"], "trimmed-owner-device");
}

#[tokio::test]
async fn member_can_update_own_display_name_only() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "rename-owner-device",
        "rename-owner-request-00000000000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let (_, invitation) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    let (join_status, member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({
            "code": invitation["code"],
            "device_id": "rename-member-device",
            "display_name": "爸爸",
        }),
    )
    .await;
    assert_eq!(join_status, StatusCode::OK, "{member}");
    let member_token = member["token"].as_str().unwrap();

    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/display-name",
        Some(member_token),
        json!({"display_name": "　干爹　"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["display_name"], "干爹");

    let (_, members) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let member_row = members["members"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["is_self"] == false && row["role"] == "member")
        .unwrap();
    assert_eq!(member_row["display_name"], "干爹");
    assert_eq!(member_row["device_id"], "rename-member-device");

    // Owner rename still only touches self.
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
        row["role"] == "member" && row["display_name"] == "干爹" && row["is_self"] == true
    }));

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
async fn shared_family_name_persists_on_create_join_and_owner_rename() {
    let rig = Rig::new();
    let request_id = "family-name-create-request-000000000001";
    let (create_status, owner) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "device_id": "family-name-owner",
            "display_name": "妈妈",
            "family_name": "  乐乐一家  ",
        }),
    )
    .await;
    assert_eq!(create_status, StatusCode::CREATED, "{owner}");
    assert_eq!(owner["family_name"], "乐乐一家");
    let owner_token = owner["token"].as_str().unwrap();

    // Idempotent retry must match the same family_name (like display_name).
    let (retry_ok, retry_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": request_id,
            "device_id": "family-name-owner",
            "display_name": "妈妈",
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
            "device_id": "family-name-owner",
            "display_name": "妈妈",
            "family_name": "别的名字",
        }),
    )
    .await;
    assert_eq!(retry_conflict, StatusCode::CONFLICT);

    // Blank/omitted family_name is accepted and stored as null.
    let blank_rig = Rig::new();
    let (blank_status, blank_owner) = json_request(
        &blank_rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "family-name-blank-request-00000000001",
            "device_id": "blank-name-owner",
            "display_name": "妈妈",
            "family_name": "   ",
        }),
    )
    .await;
    assert_eq!(blank_status, StatusCode::CREATED, "{blank_owner}");
    assert!(blank_owner["family_name"].is_null());

    // Join returns the current shared name.
    let (_, invitation) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    let (join_status, join_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({
            "code": invitation["code"],
            "device_id": "family-name-member",
            "display_name": "爸爸",
        }),
    )
    .await;
    assert_eq!(join_status, StatusCode::OK, "{join_body}");
    assert_eq!(join_body["family_name"], "乐乐一家");
    let member_token = join_body["token"].as_str().unwrap();

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

    // After rename, a fresh invite/join sees the new name.
    let (_, invite2) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    let (_, join2) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({
            "code": invite2["code"],
            "device_id": "family-name-member-2",
            "display_name": "姥姥",
        }),
    )
    .await;
    assert_eq!(join2["family_name"], "年年的家庭");

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

    // Clear name with empty string.
    let (clear_status, clear_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/name",
        Some(owner_token),
        json!({"family_name": ""}),
    )
    .await;
    assert_eq!(clear_status, StatusCode::OK, "{clear_body}");
    assert!(clear_body["family_name"].is_null());
}

#[tokio::test]
async fn family_members_coalesce_legacy_duplicate_member_tokens_without_role_promotion() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "duplicate-owner-device",
        "duplicate-owner-request-00000000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    for token in ["legacy-member-token-a", "legacy-member-token-b"] {
        connection
            .execute(
                "
                INSERT INTO memberships(token_hash, family_id, role, device_id, display_name)
                VALUES (?1, ?2, 'member', 'same-legacy-device', '  历史成员  ')
                ",
                rusqlite::params![token_hash(token), family_id],
            )
            .unwrap();
    }
    // A role collision is kept separate: device_id is a client claim, not
    // authentication evidence, so it must never promote a member row to owner.
    connection
        .execute(
            "
            INSERT INTO memberships(token_hash, family_id, role, device_id, display_name)
            VALUES (?1, ?2, 'member', 'duplicate-owner-device', '伪装管理员')
            ",
            rusqlite::params![token_hash("owner-device-collision"), family_id],
        )
        .unwrap();
    for (token, device_id, display_name) in [
        ("legacy-empty-token", "legacy-empty-device", Some("")),
        (
            "legacy-local-placeholder-token",
            "legacy-local-placeholder-device",
            Some("我（本机）"),
        ),
        ("legacy-null-token", "legacy-null-device", None),
    ] {
        connection
            .execute(
                "
                INSERT INTO memberships(token_hash, family_id, role, device_id, display_name)
                VALUES (?1, ?2, 'member', ?3, ?4)
                ",
                rusqlite::params![token_hash(token), family_id, device_id, display_name],
            )
            .unwrap();
    }
    drop(connection);

    let (_, members) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(
        members,
        json!({"members":[
            {
                "display_name":"妈妈",
                "role":"owner",
                "is_self":true,
                "device_id":"duplicate-owner-device",
            },
            {
                "display_name":"伪装管理员",
                "role":"member",
                "is_self":false,
                "device_id":"duplicate-owner-device",
            },
            {
                "display_name":"历史成员",
                "role":"member",
                "is_self":false,
                "device_id":"same-legacy-device",
            },
            {
                "display_name":null,
                "role":"member",
                "is_self":false,
                "device_id":"legacy-empty-device",
            },
            {
                "display_name":null,
                "role":"member",
                "is_self":false,
                "device_id":"legacy-local-placeholder-device",
            },
            {
                "display_name":null,
                "role":"member",
                "is_self":false,
                "device_id":"legacy-null-device",
            },
        ]})
    );
    let serialized = members.to_string();
    assert!(!serialized.contains("我（本机）"));
    assert!(!serialized.contains("legacy-member-token"));
    assert!(!serialized.contains("owner-device-collision"));
}

#[tokio::test]
async fn push_lww_cursor_and_generation_recovery_are_wire_compatible() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "lww-owner-request-0000000000000001",
    )
    .await;
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let first = json!({
        "type":"baby","client_uuid":baby_id,"updated_at":100,
        "payload":baby_payload("年年", None)
    });
    let (_, applied) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[first.clone()]}),
    )
    .await;
    let (_, retry) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[first.clone()]}),
    )
    .await;
    let (_, older) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby","client_uuid":baby_id,"updated_at":99,
            "payload":baby_payload("旧", None)
        }]}),
    )
    .await;
    let (_, newer) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby","client_uuid":baby_id,"updated_at":101,"deleted_at":101,
            "payload":baby_payload("年年", None)
        }]}),
    )
    .await;
    assert_eq!(applied, json!({"applied":1,"skipped":0,"cursor":1}));
    assert_eq!(retry, json!({"applied":0,"skipped":1,"cursor":1}));
    assert_eq!(older, json!({"applied":0,"skipped":1,"cursor":1}));
    assert_eq!(newer, json!({"applied":1,"skipped":0,"cursor":2}));
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
    let (stale_push, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"generation":"generation-a","entities":[]}),
    )
    .await;
    assert_eq!(stale_push, StatusCode::CONFLICT);
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
    let token = owner["token"].as_str().unwrap();
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
    let (push_status, push_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":entities}),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");

    let (first_status, first) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(first_status, StatusCode::OK);
    assert_eq!(first["entities"].as_array().unwrap().len(), 200);
    assert_eq!(first["cursor"], 200);
    assert_eq!(first["has_more"], true);

    let (second_status, second) = get_json(&rig.app, "/v1/pull?cursor=200", Some(token)).await;
    assert_eq!(second_status, StatusCode::OK);
    assert_eq!(second["entities"].as_array().unwrap().len(), 1);
    assert_eq!(second["cursor"], 201);
    assert_eq!(second["has_more"], false);
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
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_ids = (0..201)
        .map(|_| Uuid::new_v4().to_string())
        .collect::<Vec<_>>();
    let records = record_ids.iter().enumerate().map(|(index, record_id)| {
        json!({
            "type":"record",
            "client_uuid":record_id,
            "updated_at":index + 1,
            "payload":record_payload(&baby_id)
        })
    });
    let (push_status, push_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":std::iter::once(json!({
            "type":"baby",
            "client_uuid":baby_id,
            "updated_at":1,
            "payload":baby_payload("初始宝宝", None)
        })).chain(records).collect::<Vec<_>>() }),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");
    let (update_status, update_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby",
            "client_uuid":baby_id,
            "updated_at":10_000,
            "payload":baby_payload("更新宝宝", None)
        }]}),
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
    let token = owner["token"].as_str().unwrap();

    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby",
            "client_uuid":Uuid::new_v4(),
            "updated_at":i64::MAX,
            "payload":baby_payload("年年", None)
        }]}),
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
    let token = owner["token"].as_str().unwrap();
    let server_now_millis = rig.now.load(Ordering::SeqCst) * 1_000;

    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby",
            "client_uuid":Uuid::new_v4(),
            "updated_at":server_now_millis + 86_400_001,
            "payload":baby_payload("年年", None)
        }]}),
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
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();

    let nickname_20 = "年".repeat(20);
    let (accepted, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby","client_uuid":baby_id,"updated_at":1,
            "payload":baby_payload(&nickname_20, None)
        }]}),
    )
    .await;
    assert_eq!(accepted, StatusCode::OK, "{body}");

    let nickname_21 = "年".repeat(21);
    let (rejected, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby","client_uuid":baby_id,"updated_at":2,
            "payload":baby_payload(&nickname_21, None)
        }]}),
    )
    .await;
    assert_eq!(rejected, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
}

#[tokio::test]
async fn strict_entity_contract_orders_same_batch_references_and_strips_sort_order() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "strict-owner-request-0000000000001",
    )
    .await;
    let token = owner["token"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            None,
            json!({"entities":[]}),
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let (unknown_type, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"settings","client_uuid":Uuid::new_v4(),"updated_at":1,"payload":{}
        }]}),
    )
    .await;
    assert_eq!(unknown_type, StatusCode::UNPROCESSABLE_ENTITY);

    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let (unresolved, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"record","client_uuid":record_id,"updated_at":1,
            "payload":record_payload(&baby_id)
        }]}),
    )
    .await;
    assert_eq!(unresolved, StatusCode::CONFLICT);
    let mut baby = baby_payload("年年", None);
    baby["sort_order"] = json!(99);
    let (same_batch, result) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"media","client_uuid":media_id,"updated_at":1,
             "payload":log_media_payload(&record_id)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"baby","client_uuid":baby_id,"updated_at":1,"payload":baby}
        ]}),
    )
    .await;
    assert_eq!(same_batch, StatusCode::OK, "{result}");
    let (_, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let types = pulled["entities"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entity| entity["type"].as_str().unwrap())
        .collect::<Vec<_>>();
    // Media without durable bytes is omitted so clients can advance past half-uploads.
    assert_eq!(types, ["baby", "record"]);
    assert!(pulled["entities"][0]["payload"]
        .as_object()
        .unwrap()
        .get("sort_order")
        .is_none());
    assert_eq!(pulled["cursor"], 3);

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::from("log"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(uploaded.status(), StatusCode::OK);
    let (_, after_bytes) = get_json(&rig.app, "/v1/pull?cursor=3", Some(token)).await;
    let media_types = after_bytes["entities"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entity| entity["type"].as_str().unwrap())
        .collect::<Vec<_>>();
    assert_eq!(media_types, ["media"]);
    assert_eq!(after_bytes["entities"][0]["client_uuid"], media_id);
    assert_eq!(after_bytes["cursor"], 4);
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
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();

    let (seeded, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)}
        ]}),
    )
    .await;
    assert_eq!(seeded, StatusCode::OK, "{body}");
    assert_eq!(body["cursor"], 2);

    let (deleted, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[{
            "type":"baby","client_uuid":baby_id,"updated_at":2,"deleted_at":2,
            "payload":baby_payload("年年", None)
        }]}),
    )
    .await;
    assert_eq!(deleted, StatusCode::OK, "{body}");
    assert_eq!(body["cursor"], 3);

    let (status, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK, "{pulled}");
    assert_eq!(pulled["cursor"], 3);
    assert_eq!(pulled["has_more"], false);
    let entities = pulled["entities"].as_array().unwrap();
    assert_eq!(entities.len(), 2);
    assert_eq!(entities[0]["type"], "baby");
    assert_eq!(entities[0]["client_uuid"], baby_id);
    assert_eq!(entities[0]["deleted_at"], 2);
    assert_eq!(entities[1]["type"], "record");
    assert_eq!(entities[1]["client_uuid"], record_id);
    assert!(entities[1]["deleted_at"].is_null());
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
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let second_baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let log_id = Uuid::new_v4().to_string();
    let avatar_id = Uuid::new_v4().to_string();
    let (seeded, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", Some(&avatar_id))},
            {"type":"baby","client_uuid":second_baby_id,"updated_at":1,
             "payload":baby_payload("二宝", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"media","client_uuid":log_id,"updated_at":2,
             "payload":log_media_payload(&record_id)},
            {"type":"media","client_uuid":avatar_id,"updated_at":3,
             "payload":avatar_media_payload(&baby_id)}
        ]}),
    )
    .await;
    assert_eq!(seeded, StatusCode::OK, "{body}");

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{log_id}"),
        Some(member_token),
        Body::from("log"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(uploaded.status(), StatusCode::OK);
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
    assert_eq!(oversized.status(), StatusCode::PAYLOAD_TOO_LARGE);
    let denied_avatar = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{avatar_id}"),
        Some(member_token),
        Body::from("avatar"),
        None,
    )
    .await;
    assert_eq!(denied_avatar.status(), StatusCode::FORBIDDEN);
    let accepted_avatar = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{avatar_id}"),
        Some(owner_token),
        Body::from("avatar"),
        None,
    )
    .await;
    assert_eq!(accepted_avatar.status(), StatusCode::OK);

    let (member_bypass, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({"entities":[{
            "type":"media","client_uuid":avatar_id,"updated_at":4,
            "payload":log_media_payload(&record_id)
        }]}),
    )
    .await;
    assert_eq!(member_bypass, StatusCode::FORBIDDEN);
    let (owner_reassociate, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({"entities":[{
            "type":"media","client_uuid":avatar_id,"updated_at":4,
            "payload":avatar_media_payload(&second_baby_id)
        }]}),
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
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let mut media_payload = log_media_payload(&record_id);
    media_payload["byte_size"] = json!(0);

    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"media","client_uuid":media_id,"updated_at":1,
             "payload":media_payload}
        ]}),
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
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let boundary_media_id = Uuid::new_v4().to_string();
    let mut boundary_payload = log_media_payload(&record_id);
    boundary_payload["byte_size"] = json!(8);
    boundary_payload["width"] = json!(i32::MAX);
    boundary_payload["height"] = json!(i32::MAX);

    let (accepted, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"media","client_uuid":boundary_media_id,"updated_at":1,
             "payload":boundary_payload}
        ]}),
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
        let (status, body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(token),
            json!({"entities":[{
                "type":"media","client_uuid":Uuid::new_v4(),"updated_at":2,
                "payload":payload
            }]}),
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
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"media","client_uuid":media_id,"updated_at":1,
             "payload":log_media_payload(&record_id)}
        ]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        Some("application/octet-stream"),
    )
    .await;

    assert_eq!(uploaded.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let mismatched = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{media_id}"),
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
async fn member_can_edit_baby_but_not_avatar_pointer() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "avatar-owner-request-0000000000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let avatar_id = Uuid::new_v4().to_string();
    let replacement_id = Uuid::new_v4().to_string();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(owner_token),
            json!({"entities":[
                {"type":"media","client_uuid":avatar_id,"updated_at":1,
                 "payload":avatar_media_payload(&baby_id)},
                {"type":"baby","client_uuid":baby_id,"updated_at":1,
                 "payload":baby_payload("年年", Some(&avatar_id))},
                {"type":"media","client_uuid":replacement_id,"updated_at":1,
                 "payload":avatar_media_payload(&baby_id)}
            ]}),
        )
        .await
        .0,
        StatusCode::OK
    );
    let (nickname, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({"entities":[{
            "type":"baby","client_uuid":baby_id,"updated_at":2,
            "payload":baby_payload("成员可改昵称", Some(&avatar_id))
        }]}),
    )
    .await;
    assert_eq!(nickname, StatusCode::OK);
    for payload in [
        baby_payload("禁止清空", None),
        baby_payload("禁止替换", Some(&replacement_id)),
    ] {
        let (status, _) = json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(member_token),
            json!({"entities":[{
                "type":"baby","client_uuid":baby_id,"updated_at":3,"payload":payload
            }]}),
        )
        .await;
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
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let first_avatar_id = Uuid::new_v4().to_string();
    let current_avatar_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let (seeded, seeded_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({"entities":[
            {"type":"media","client_uuid":first_avatar_id,"updated_at":100,
             "payload":avatar_media_payload(&baby_id)},
            {"type":"baby","client_uuid":baby_id,"updated_at":100,
             "payload":baby_payload("年年", Some(&first_avatar_id))},
            {"type":"media","client_uuid":current_avatar_id,"updated_at":300,
             "payload":avatar_media_payload(&baby_id)},
            {"type":"baby","client_uuid":baby_id,"updated_at":300,
             "payload":baby_payload("年年", Some(&current_avatar_id))}
        ]}),
    )
    .await;
    assert_eq!(seeded, StatusCode::OK, "{seeded_body}");

    let (merged, merged_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":200,
             "payload":baby_payload("旧快照", Some(&first_avatar_id))},
            {"type":"record","client_uuid":record_id,"updated_at":400,
             "payload":record_payload(&baby_id)}
        ]}),
    )
    .await;
    assert_eq!(merged, StatusCode::OK, "{merged_body}");
    assert_eq!(merged_body["applied"], 1);
    assert_eq!(merged_body["skipped"], 1);
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
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "stream-owner-request-00000000000001",
    )
    .await;
    let token = owner["token"].as_str().unwrap().to_owned();
    let family_id = owner["family_id"].as_str().unwrap().to_owned();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(&token),
            json!({"entities":[
                {"type":"baby","client_uuid":baby_id,"updated_at":1,
                 "payload":baby_payload("年年", None)},
                {"type":"record","client_uuid":record_id,"updated_at":1,
                 "payload":record_payload(&baby_id)},
                {"type":"media","client_uuid":media_id,"updated_at":1,
                 "payload":log_media_payload(&record_id)}
            ]}),
        )
        .await
        .0,
        StatusCode::OK
    );

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
        &format!("/v1/media/{media_id}"),
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
    let upload_uri = format!("/v1/media/{media_id}");
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
        request(
            &delete_app,
            Method::POST,
            "/v1/family/delete",
            Some(&delete_token),
            Body::from("{}"),
            Some("application/json"),
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
async fn owner_delete_cleans_family_media_and_allows_replacement() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "delete-owner-request-0000000000001",
    )
    .await;
    let token = owner["token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(token),
            json!({"entities":[
                {"type":"baby","client_uuid":baby_id,"updated_at":1,
                 "payload":baby_payload("年年", None)},
                {"type":"record","client_uuid":record_id,"updated_at":1,
                 "payload":record_payload(&baby_id)},
                {"type":"media","client_uuid":media_id,"updated_at":1,
                 "payload":log_media_payload(&record_id)}
            ]}),
        )
        .await
        .0,
        StatusCode::OK
    );
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
        StatusCode::OK
    );
    let (deleted, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/delete",
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(deleted, StatusCode::OK);
    assert!(!rig.directory.path().join("media").join(family_id).exists());
    assert_eq!(
        get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await.0,
        StatusCode::UNAUTHORIZED
    );
    let replacement = create_family(
        &rig.app,
        "replacement-owner",
        "replacement-owner-request-00000001",
    )
    .await;
    assert_ne!(replacement["family_id"], family_id);
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
        "device_id": "owner-device",
        "display_name": "妈妈",
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
    assert_eq!(wrong, StatusCode::UNAUTHORIZED);
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
            "device_id": "owner-device",
            "display_name": "妈妈",
        }),
        &[("x-lezi-bootstrap-secret", secret)],
    )
    .await;
    assert_eq!(limited, StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn create_and_join_limits_are_scoped_without_losing_global_protection() {
    let rig = Rig::with_config(|config| {
        config.create_rate_limit = RateLimitConfig {
            max_attempts: 2,
            window_seconds: 60,
        };
        config.join_rate_limit = RateLimitConfig {
            max_attempts: 2,
            window_seconds: 60,
        };
    });
    let first = create_family(
        &rig.app,
        "owner-device",
        "rate-limit-owner-request-0000000001",
    )
    .await;
    let owner_token = first["token"].as_str().unwrap();
    // A different device has its own scoped allowance, but remains subject to
    // the limiter's process-wide fallback.
    let (second, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "rate-limit-owner-request-0000000002",
            "device_id": "other-device",
            "display_name": "妈妈",
        }),
    )
    .await;
    assert_eq!(second, StatusCode::CONFLICT);
    let (third, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "rate-limit-owner-request-0000000003",
            "device_id": "other-device",
            "display_name": "妈妈",
        }),
    )
    .await;
    assert_eq!(third, StatusCode::CONFLICT);
    let (limited, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "rate-limit-owner-request-0000000004",
            "device_id": "other-device",
            "display_name": "妈妈",
        }),
    )
    .await;
    assert_eq!(limited, StatusCode::TOO_MANY_REQUESTS, "{body}");

    let (invite_status, invitation) = json_request(
        &rig.app,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(invite_status, StatusCode::CREATED);
    for device in ["attacker-a", "attacker-b"] {
        let (status, _) = json_request(
            &rig.app,
            Method::POST,
            "/v1/join",
            None,
            json!({"code": "WRONGCODE001", "device_id": device, "display_name": "成员"}),
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
    let (join_limited, join_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({"code": "WRONGCODE001", "device_id": "attacker-c", "display_name": "成员"}),
    )
    .await;
    assert_eq!(join_limited, StatusCode::TOO_MANY_REQUESTS, "{join_body}");

    // Exhausting one guessed invite code must not consume the valid invite's
    // scoped budget.
    let code = invitation["code"].as_str().unwrap();
    let (joined, member) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({"code": code, "device_id": "member-device", "display_name": "成员"}),
    )
    .await;
    assert_eq!(joined, StatusCode::OK, "{member}");
}

#[tokio::test]
async fn pull_omits_incomplete_media_until_bytes_are_uploaded() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "integrity-owner-request-00000000001",
    )
    .await;
    let token = owner["token"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let (pushed, push_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"media","client_uuid":media_id,"updated_at":1,
             "payload":log_media_payload(&record_id)}
        ]}),
    )
    .await;
    assert_eq!(pushed, StatusCode::OK, "{push_body}");
    assert_eq!(push_body["cursor"], 3);

    let (status, pulled) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(pulled["cursor"], 3);
    let entities = pulled["entities"].as_array().unwrap();
    assert_eq!(entities.len(), 2);
    assert!(entities.iter().all(|entity| entity["type"] != "media"));

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
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::from("log"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(uploaded.status(), StatusCode::OK);

    let (status, ready) = get_json(&rig.app, "/v1/pull?cursor=3", Some(token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(ready["cursor"], 4);
    assert_eq!(ready["entities"].as_array().unwrap().len(), 1);
    assert_eq!(ready["entities"][0]["type"], "media");
    assert_eq!(ready["entities"][0]["client_uuid"], media_id);

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
async fn media_upload_retry_republishes_when_bytes_already_exist() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "owner-device",
        "retry-media-owner-request-000000001",
    )
    .await;
    let token = owner["token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();

    let (pushed, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"media","client_uuid":media_id,"updated_at":1,
             "payload":log_media_payload(&record_id)}
        ]}),
    )
    .await;
    assert_eq!(pushed, StatusCode::OK, "{body}");
    assert_eq!(body["cursor"], 3);

    // Reproduce a process failure after durable file replacement but before the
    // metadata revision is republished. A retry must heal this state through
    // the public PUT/pull contract even though the target file already exists.
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
    assert_eq!(uploaded.status(), StatusCode::OK);

    let (status, ready) = get_json(&rig.app, "/v1/pull?cursor=3", Some(token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(ready["cursor"], 4);
    assert_eq!(ready["entities"].as_array().unwrap().len(), 1);
    assert_eq!(ready["entities"][0]["type"], "media");
    assert_eq!(ready["entities"][0]["client_uuid"], media_id);
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
    let token = owner["token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let zero_media_id = Uuid::new_v4().to_string();
    let mismatched_media_id = Uuid::new_v4().to_string();

    let (pushed, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({"entities":[
            {"type":"baby","client_uuid":baby_id,"updated_at":1,
             "payload":baby_payload("年年", None)},
            {"type":"record","client_uuid":record_id,"updated_at":1,
             "payload":record_payload(&baby_id)},
            {"type":"media","client_uuid":zero_media_id,"updated_at":1,
             "payload":log_media_payload(&record_id)},
            {"type":"media","client_uuid":mismatched_media_id,"updated_at":1,
             "payload":log_media_payload(&record_id)}
        ]}),
    )
    .await;
    assert_eq!(pushed, StatusCode::OK, "{body}");
    assert_eq!(body["cursor"], 4);

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

    let uploaded = request(
        &rig.app,
        Method::PUT,
        &format!("/v1/media/{zero_media_id}"),
        Some(token),
        Body::from("log"),
        Some("application/octet-stream"),
    )
    .await;
    assert_eq!(uploaded.status(), StatusCode::OK);
    let (_, ready) = get_json(&rig.app, "/v1/pull?cursor=4", Some(token)).await;
    assert_eq!(ready["cursor"], 5);
    assert_eq!(ready["entities"].as_array().unwrap().len(), 1);
    assert_eq!(ready["entities"][0]["client_uuid"], zero_media_id);
}
