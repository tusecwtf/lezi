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
        json!({"code": invitation["code"], "device_id": device_id}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member}");
    member
}

#[tokio::test]
async fn health_initializes_private_single_data_root() {
    let rig = Rig::new();
    let (status, body) = get_json(&rig.app, "/health", None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body, json!({"ok": true, "version": VERSION}));
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
        json!({"create_request_id": "short", "device_id": "owner-device"}),
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
            "device_id": "owner-device"
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
        json!({"code": code, "device_id": "member-device"}),
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
        json!({"code": code, "device_id": "member-device"}),
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
        json!({"code": code, "device_id": "other-device"}),
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
        json!({"code": expiring["code"], "device_id": "late-device"}),
    )
    .await;
    assert_eq!(expired, StatusCode::GONE);
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
        body,
        &[("x-lezi-bootstrap-secret", secret)],
    )
    .await;
    assert_eq!(created, StatusCode::CREATED, "{family}");
    assert_eq!(family["role"], "owner");
}

#[tokio::test]
async fn create_and_join_are_rate_limited() {
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
    // Second create (different request id) still counts against the window even
    // though it conflicts with the single-family stack.
    let (second, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "rate-limit-owner-request-0000000002",
            "device_id": "other-device",
        }),
    )
    .await;
    assert_eq!(second, StatusCode::CONFLICT);
    let (limited, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/family/create",
        None,
        json!({
            "create_request_id": "rate-limit-owner-request-0000000003",
            "device_id": "other-device",
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
    let code = invitation["code"].as_str().unwrap();
    for device in ["member-a", "member-b"] {
        let (status, _) = json_request(
            &rig.app,
            Method::POST,
            "/v1/join",
            None,
            json!({"code": code, "device_id": device}),
        )
        .await;
        // First join may succeed; second device may conflict or 429 after budget.
        assert!(
            matches!(
                status,
                StatusCode::OK | StatusCode::CONFLICT | StatusCode::TOO_MANY_REQUESTS
            ),
            "{status}"
        );
    }
    let (join_limited, join_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/join",
        None,
        json!({"code": code, "device_id": "member-c"}),
    )
    .await;
    assert_eq!(join_limited, StatusCode::TOO_MANY_REQUESTS, "{join_body}");
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
    assert!(entities
        .iter()
        .all(|entity| entity["type"] != "media"));

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
