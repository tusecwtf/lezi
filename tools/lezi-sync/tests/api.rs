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
    assert_eq!(body["ok"], true);
    assert_eq!(body["version"], VERSION);
    assert_eq!(
        body["capabilities"],
        json!(["atomic_bundle", "record_membership_author"])
    );
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
async fn database_schema_version_is_explicit_and_legacy_zero_upgrade_preserves_data() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "schema-version-owner",
        "schema-version-owner-request-000001",
    )
    .await;
    let token = owner["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let database_path = rig.directory.path().join("lezi.db");
    let connection = rusqlite::Connection::open(&database_path).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        2
    );
    connection
        .execute(
            "ALTER TABLE sync_bundle_media DROP COLUMN staged_sha256",
            [],
        )
        .unwrap();
    connection.pragma_update(None, "user_version", 0).unwrap();
    drop(connection);

    let restarted = rig.restart("generation-b");
    let (pull_status, pull) = get_json(&restarted, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(
        pull_status,
        StatusCode::OK,
        "legacy version-zero upgrade lost the existing family credentials"
    );
    assert!(
        pull["entities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|entity| entity["client_uuid"] == baby_id),
        "legacy version-zero upgrade lost persisted family entities"
    );
    let connection = rusqlite::Connection::open(database_path).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        2
    );
    assert!(connection
        .prepare("PRAGMA table_info(sync_bundle_media)")
        .unwrap()
        .query_map([], |row| row.get::<_, String>(1))
        .unwrap()
        .collect::<Result<std::collections::BTreeSet<_>, _>>()
        .unwrap()
        .contains("staged_sha256"));
}

#[test]
fn future_database_schema_version_fails_closed_without_mutation() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    let connection = rusqlite::Connection::open(&database_path).unwrap();
    connection
        .execute_batch(
            "
            PRAGMA user_version = 3;
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
        3
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
    let restarted = rig.restart("generation-b");
    let retry = create_family(&restarted, "owner-device", request_id).await;
    assert_eq!(retry["family_id"], first["family_id"]);
    assert_eq!(retry["token"], first["token"]);
    assert_eq!(retry["membership_id"], first["membership_id"]);
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
    assert!(member["membership_id"].as_str().unwrap().len() >= 32);
    assert_ne!(member["membership_id"], owner["membership_id"]);
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
    assert_eq!(retry["membership_id"], member["membership_id"]);
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

    let owner_membership_id = owner["membership_id"].as_str().unwrap();
    let member_membership_id = member["membership_id"].as_str().unwrap();
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
                "membership_id": owner_membership_id,
            },
            {
                "display_name":"陈爸爸 🌿",
                "role":"member",
                "is_self":false,
                "device_id":"member-sensitive-device-id",
                "membership_id": member_membership_id,
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
                "membership_id": owner_membership_id,
            },
            {
                "display_name":"陈爸爸 🌿",
                "role":"member",
                "is_self":true,
                "device_id":"member-sensitive-device-id",
                "membership_id": member_membership_id,
            },
        ]})
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
    // Tokens / token hashes must never appear; device_id is the intentional
    // client link key for created_by_device_id → 称呼 resolution.
    // membership_id is a public stable identity and may appear.
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
        assert_eq!(fields.len(), 5);
        assert!(fields.contains_key("device_id"));
        assert!(fields.contains_key("membership_id"));
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
            INSERT INTO memberships(
                membership_id, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, 'owner', 'isolated-raw-device-id', '隔离家庭')
            ",
            rusqlite::params![Uuid::new_v4().to_string(), isolated_family_id],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO membership_credentials(token_hash, membership_id)
            SELECT ?1, membership_id
            FROM memberships
            WHERE family_id = ?2 AND device_id = 'isolated-raw-device-id'
            ",
            rusqlite::params![token_hash(isolated_token), isolated_family_id],
        )
        .unwrap();
    drop(connection);

    let (_, isolated_view) = get_json(&rig.app, "/v1/family/members", Some(isolated_token)).await;
    assert_eq!(isolated_view["members"].as_array().unwrap().len(), 1);
    assert_eq!(isolated_view["members"][0]["display_name"], "隔离家庭");
    assert_eq!(isolated_view["members"][0]["role"], "owner");
    assert_eq!(isolated_view["members"][0]["is_self"], true);
    assert_eq!(
        isolated_view["members"][0]["device_id"],
        "isolated-raw-device-id"
    );
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
    assert_eq!(
        after_leave["members"][0]["device_id"],
        "owner-sensitive-device-id"
    );
    assert_eq!(
        after_leave["members"][0]["membership_id"],
        owner_membership_id
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
    let owner_token = owner["token"].as_str().unwrap();
    let owner_membership_id = owner["membership_id"].as_str().unwrap().to_owned();
    assert_eq!(owner["role"], "owner");

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
            "device_id": "stable-member-device",
            "display_name": "成员",
        }),
    )
    .await;
    assert_eq!(join_status, StatusCode::OK, "{member}");
    let member_token = member["token"].as_str().unwrap();
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
    // A member cannot mint invites (owner-only) even if a client claims owner.
    let (forged_invite, forged_body) = json_request(
        &restarted,
        Method::POST,
        "/v1/invite",
        Some(member_token),
        json!({}),
    )
    .await;
    assert_eq!(forged_invite, StatusCode::FORBIDDEN);
    assert_eq!(forged_body, json!({"detail":"Owner role required"}));
    let (forged_rename, _) = json_request(
        &restarted,
        Method::POST,
        "/v1/family/name",
        Some(member_token),
        json!({"family_name": "伪造家庭"}),
    )
    .await;
    assert_eq!(forged_rename, StatusCode::FORBIDDEN);

    // Push with a forged device_id (impersonating another membership) is rejected.
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
    assert_eq!(forged_device_status, StatusCode::FORBIDDEN);
    assert_eq!(
        forged_device_body,
        json!({"detail":"device_id does not match token"})
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
async fn zero_entity_pull_reports_family_name_value_and_null_across_restart() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "pull-family-name-owner",
        "pull-family-name-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "pull-family-name-member").await;
    let member_token = member["token"].as_str().unwrap();

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
    let (restart_value_status, restart_value_pull) =
        get_json(&restarted, "/v1/pull?cursor=0", Some(member_token)).await;
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
    assert_eq!(clear_status, StatusCode::OK, "{clear_body}");
    let (null_status, null_pull) =
        get_json(&restarted, "/v1/pull?cursor=0", Some(member_token)).await;
    assert_eq!(null_status, StatusCode::OK, "{null_pull}");
    assert_eq!(null_pull["entities"], json!([]));
    assert!(
        null_pull.as_object().unwrap().contains_key("family_name"),
        "new NAS must distinguish explicit null from an omitted legacy field: {null_pull}"
    );
    assert!(null_pull["family_name"].is_null(), "{null_pull}");

    let restarted_again = rig.restart("generation-c");
    let (restart_null_status, restart_null_pull) =
        get_json(&restarted_again, "/v1/pull?cursor=0", Some(member_token)).await;
    assert_eq!(restart_null_status, StatusCode::OK, "{restart_null_pull}");
    assert_eq!(restart_null_pull["entities"], json!([]));
    assert!(
        restart_null_pull
            .as_object()
            .unwrap()
            .contains_key("family_name"),
        "restarted NAS omitted explicit null: {restart_null_pull}"
    );
    assert!(restart_null_pull["family_name"].is_null());
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
    let owner_token = owner["token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let duplicate_membership_id = Uuid::new_v4().to_string();
    connection
        .execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, 'member', 'same-legacy-device', '  历史成员  ')
            ",
            rusqlite::params![duplicate_membership_id, family_id],
        )
        .unwrap();
    for token in ["legacy-member-token-a", "legacy-member-token-b"] {
        connection
            .execute(
                "
                INSERT INTO membership_credentials(token_hash, membership_id)
                VALUES (?1, ?2)
                ",
                rusqlite::params![token_hash(token), duplicate_membership_id],
            )
            .unwrap();
    }
    // A role collision is kept separate: device_id is a client claim, not
    // authentication evidence, so it must never promote a member row to owner.
    let role_collision_membership_id = Uuid::new_v4().to_string();
    connection
        .execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, 'member', 'duplicate-owner-device', '伪装管理员')
            ",
            rusqlite::params![role_collision_membership_id, family_id],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO membership_credentials(token_hash, membership_id)
            VALUES (?1, ?2)
            ",
            rusqlite::params![
                token_hash("owner-device-collision"),
                role_collision_membership_id
            ],
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
        let membership_id = Uuid::new_v4().to_string();
        connection
            .execute(
                "
                INSERT INTO memberships(
                    membership_id, family_id, role, device_id, display_name
                ) VALUES (?1, ?2, 'member', ?3, ?4)
                ",
                rusqlite::params![membership_id, family_id, device_id, display_name],
            )
            .unwrap();
        connection
            .execute(
                "
                INSERT INTO membership_credentials(token_hash, membership_id)
                VALUES (?1, ?2)
                ",
                rusqlite::params![token_hash(token), membership_id],
            )
            .unwrap();
    }
    drop(connection);

    let (_, members) = get_json(&rig.app, "/v1/family/members", Some(owner_token)).await;
    let rows = members["members"].as_array().unwrap();
    assert_eq!(rows.len(), 6);
    // Structural projection without hard-coding minted membership_ids.
    let projection: Vec<_> = rows
        .iter()
        .map(|row| {
            json!({
                "display_name": row["display_name"],
                "role": row["role"],
                "is_self": row["is_self"],
                "device_id": row["device_id"],
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
                "device_id":"duplicate-owner-device",
            }),
            json!({
                "display_name":"伪装管理员",
                "role":"member",
                "is_self":false,
                "device_id":"duplicate-owner-device",
            }),
            json!({
                "display_name":"历史成员",
                "role":"member",
                "is_self":false,
                "device_id":"same-legacy-device",
            }),
            json!({
                "display_name":null,
                "role":"member",
                "is_self":false,
                "device_id":"legacy-empty-device",
            }),
            json!({
                "display_name":null,
                "role":"member",
                "is_self":false,
                "device_id":"legacy-local-placeholder-device",
            }),
            json!({
                "display_name":null,
                "role":"member",
                "is_self":false,
                "device_id":"legacy-null-device",
            }),
        ]
    );
    // Self uses create-time membership_id; two credentials project one membership.
    assert_eq!(rows[0]["membership_id"], owner["membership_id"]);
    let same_device_rows: Vec<_> = rows
        .iter()
        .filter(|row| row["device_id"] == "same-legacy-device")
        .collect();
    assert_eq!(same_device_rows.len(), 1);
    assert!(same_device_rows[0]["membership_id"].as_str().unwrap().len() >= 32);
    for row in rows {
        assert!(row["membership_id"].as_str().unwrap().len() >= 32);
        assert_eq!(row.as_object().unwrap().len(), 5);
    }
    let serialized = members.to_string();
    assert!(!serialized.contains("我（本机）"));
    assert!(!serialized.contains("legacy-member-token"));
    assert!(!serialized.contains("owner-device-collision"));
}

#[tokio::test]
async fn legacy_duplicate_credentials_migrate_to_one_membership_principal() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    let family_id = Uuid::new_v4().to_string();
    let owner_token = "legacy-normalization-owner-token";
    let duplicate_tokens = [
        "legacy-normalization-member-token-a",
        "legacy-normalization-member-token-b",
    ];
    let mut ordered_duplicates = duplicate_tokens
        .into_iter()
        .map(|token| (token_hash(token), token))
        .collect::<Vec<_>>();
    ordered_duplicates.sort();
    let canonical_membership_id = Uuid::new_v4().to_string();
    let aliased_membership_id = Uuid::new_v4().to_string();
    let owner_membership_id = Uuid::new_v4().to_string();
    let role_collision_membership_id = Uuid::new_v4().to_string();
    let connection = rusqlite::Connection::open(&database_path).unwrap();
    connection
        .execute_batch(
            "
            PRAGMA foreign_keys = ON;
            CREATE TABLE families (
                id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                create_request_hash TEXT,
                name TEXT
            );
            CREATE TABLE memberships (
                token_hash TEXT PRIMARY KEY,
                family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
                device_id TEXT NOT NULL,
                display_name TEXT,
                revoked_at INTEGER,
                membership_id TEXT
            );
            ",
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO families(id, created_at, name) VALUES (?1, ?2, ?3)",
            rusqlite::params![family_id, 1_753_418_400_i64, "迁移家庭"],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO memberships(
                token_hash, family_id, role, device_id, display_name, membership_id
            ) VALUES (?1, ?2, 'owner', 'shared-device', '妈妈', ?3)
            ",
            rusqlite::params![token_hash(owner_token), family_id, owner_membership_id],
        )
        .unwrap();
    connection
        .execute(
            "
            INSERT INTO memberships(
                token_hash, family_id, role, device_id, display_name, membership_id
            ) VALUES (?1, ?2, 'member', 'shared-device', '伪装管理员', ?3)
            ",
            rusqlite::params![
                token_hash("legacy-normalization-role-collision"),
                family_id,
                role_collision_membership_id
            ],
        )
        .unwrap();
    for (index, (hash, _)) in ordered_duplicates.iter().enumerate() {
        connection
            .execute(
                "
                INSERT INTO memberships(
                    token_hash, family_id, role, device_id, display_name, membership_id
                ) VALUES (?1, ?2, 'member', 'duplicate-device', ?3, ?4)
                ",
                rusqlite::params![
                    hash,
                    family_id,
                    if index == 0 { "" } else { "保留称呼" },
                    if index == 0 {
                        &canonical_membership_id
                    } else {
                        &aliased_membership_id
                    }
                ],
            )
            .unwrap();
    }
    connection
        .execute(
            "
            INSERT INTO memberships(
                token_hash, family_id, role, device_id, display_name, membership_id
            ) VALUES (?1, ?2, 'member', 'missing-id-device', '旧成员', NULL)
            ",
            rusqlite::params![
                token_hash("legacy-normalization-missing-id-token"),
                family_id
            ],
        )
        .unwrap();
    drop(connection);

    let now = Arc::new(AtomicI64::new(1_753_418_400));
    let app = app_for(
        directory.path(),
        "membership-normalization-a",
        now.clone(),
        8,
        |_| {},
    );

    let (_, first_view) = get_json(&app, "/v1/family/members", Some(ordered_duplicates[0].1)).await;
    let (_, second_view) =
        get_json(&app, "/v1/family/members", Some(ordered_duplicates[1].1)).await;
    let duplicate_id = |body: &Value| {
        body["members"]
            .as_array()
            .unwrap()
            .iter()
            .find(|member| member["device_id"] == "duplicate-device")
            .unwrap()["membership_id"]
            .as_str()
            .unwrap()
            .to_owned()
    };
    assert_eq!(duplicate_id(&first_view), canonical_membership_id);
    assert_eq!(duplicate_id(&second_view), canonical_membership_id);
    let migrated_duplicate = first_view["members"]
        .as_array()
        .unwrap()
        .iter()
        .find(|member| member["membership_id"] == canonical_membership_id)
        .unwrap();
    assert_eq!(migrated_duplicate["display_name"], "保留称呼");

    let (rename_status, rename_body) = json_request(
        &app,
        Method::POST,
        "/v1/family/display-name",
        Some(ordered_duplicates[1].1),
        json!({"display_name": "统一新称呼"}),
    )
    .await;
    assert_eq!(rename_status, StatusCode::OK, "{rename_body}");
    for token in [
        ordered_duplicates[0].1,
        ordered_duplicates[1].1,
        owner_token,
    ] {
        let (_, body) = get_json(&app, "/v1/family/members", Some(token)).await;
        let duplicates = body["members"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|member| member["device_id"] == "duplicate-device")
            .collect::<Vec<_>>();
        assert_eq!(duplicates.len(), 1);
        assert_eq!(duplicates[0]["membership_id"], canonical_membership_id);
        assert_eq!(duplicates[0]["display_name"], "统一新称呼");
    }

    // Existing author references keep the historical id, while ACL resolves
    // the durable alias back to the canonical authenticated membership.
    let legacy_item_id = Uuid::new_v4().to_string();
    let connection = rusqlite::Connection::open(&database_path).unwrap();
    connection
        .execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, 1)",
            rusqlite::params![family_id],
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
                family_id,
                legacy_item_id,
                json!({
                    "name": "旧项目",
                    "icon_slot": 1,
                    "created_by_membership_id": aliased_membership_id,
                })
                .to_string()
            ],
        )
        .unwrap();
    drop(connection);
    let (alias_edit_status, alias_edit_body) = json_request(
        &app,
        Method::POST,
        "/v1/push",
        Some(ordered_duplicates[0].1),
        json!({
            "entities": [entity_wire(
                "custom_item",
                &legacy_item_id,
                2,
                json!({"name": "迁移后项目", "icon_slot": 2}),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(alias_edit_status, StatusCode::OK, "{alias_edit_body}");
    let (_, after_alias_edit) =
        get_json(&app, "/v1/pull?cursor=0", Some(ordered_duplicates[1].1)).await;
    let legacy_item = after_alias_edit["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["client_uuid"] == legacy_item_id)
        .unwrap();
    assert_eq!(legacy_item["payload"]["name"], "迁移后项目");
    assert_eq!(
        legacy_item["payload"]["created_by_membership_id"],
        aliased_membership_id
    );

    let restarted = app_for(
        directory.path(),
        "membership-normalization-b",
        now,
        8,
        |_| {},
    );
    let (_, after_restart) = get_json(
        &restarted,
        "/v1/family/members",
        Some(ordered_duplicates[0].1),
    )
    .await;
    assert_eq!(duplicate_id(&after_restart), canonical_membership_id);
    let missing_id = |body: &Value| {
        body["members"]
            .as_array()
            .unwrap()
            .iter()
            .find(|member| member["device_id"] == "missing-id-device")
            .unwrap()["membership_id"]
            .as_str()
            .unwrap()
            .to_owned()
    };
    assert!(missing_id(&first_view).len() >= 32);
    assert_eq!(missing_id(&after_restart), missing_id(&first_view));
    assert!(after_restart["members"]
        .as_array()
        .unwrap()
        .iter()
        .any(|row| {
            row["role"] == "owner"
                && row["device_id"] == "shared-device"
                && row["membership_id"] == owner_membership_id
        }));
    assert!(after_restart["members"]
        .as_array()
        .unwrap()
        .iter()
        .any(|row| {
            row["role"] == "member"
                && row["device_id"] == "shared-device"
                && row["membership_id"] == role_collision_membership_id
        }));

    let (_, invitation) = json_request(
        &restarted,
        Method::POST,
        "/v1/invite",
        Some(owner_token),
        json!({}),
    )
    .await;
    let (attacker_status, attacker) = json_request(
        &restarted,
        Method::POST,
        "/v1/join",
        None,
        json!({
            "code": invitation["code"],
            "device_id": "duplicate-device",
            "display_name": "新加入者",
        }),
    )
    .await;
    assert_eq!(attacker_status, StatusCode::OK, "{attacker}");
    assert_ne!(attacker["membership_id"], canonical_membership_id);
    let (_, with_attacker) = get_json(&restarted, "/v1/family/members", Some(owner_token)).await;
    assert_eq!(
        with_attacker["members"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|member| member["device_id"] == "duplicate-device")
            .count(),
        2
    );

    // Credential rotation/revocation is credential-scoped and does not revoke
    // the canonical membership while another credential remains active.
    let rotation_connection = rusqlite::Connection::open(&database_path).unwrap();
    rotation_connection
        .execute(
            "
            UPDATE membership_credentials
            SET revoked_at = ?1
            WHERE token_hash = ?2
            ",
            rusqlite::params![1_753_418_401_i64, token_hash(ordered_duplicates[0].1)],
        )
        .unwrap();
    drop(rotation_connection);
    assert_eq!(
        get_json(
            &restarted,
            "/v1/family/members",
            Some(ordered_duplicates[0].1)
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let (remaining_status, remaining_view) = get_json(
        &restarted,
        "/v1/family/members",
        Some(ordered_duplicates[1].1),
    )
    .await;
    assert_eq!(remaining_status, StatusCode::OK);
    assert!(remaining_view["members"]
        .as_array()
        .unwrap()
        .iter()
        .any(|member| {
            member["membership_id"] == canonical_membership_id && member["is_self"] == true
        }));

    assert_eq!(
        json_request(
            &restarted,
            Method::POST,
            "/v1/leave",
            Some(ordered_duplicates[1].1),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );
    for token in [ordered_duplicates[0].1, ordered_duplicates[1].1] {
        assert_eq!(
            get_json(&restarted, "/v1/family/members", Some(token))
                .await
                .0,
            StatusCode::UNAUTHORIZED
        );
    }
    let (_, owner_after_leave) =
        get_json(&restarted, "/v1/family/members", Some(owner_token)).await;
    assert!(!owner_after_leave["members"]
        .as_array()
        .unwrap()
        .iter()
        .any(|member| member["membership_id"] == canonical_membership_id));

    let connection = rusqlite::Connection::open(database_path).unwrap();
    assert_eq!(
        connection
            .query_row(
                "SELECT canonical_membership_id FROM membership_aliases WHERE alias_membership_id = ?1",
                rusqlite::params![aliased_membership_id],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
        canonical_membership_id
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT COUNT(*) FROM membership_credentials WHERE membership_id = ?1",
                rusqlite::params![canonical_membership_id],
                |row| row.get::<_, i64>(0),
            )
            .unwrap(),
        2
    );
    assert_eq!(
        connection
            .query_row(
                "
                SELECT COUNT(*)
                FROM membership_credentials
                WHERE membership_id = ?1 AND revoked_at IS NOT NULL
                ",
                rusqlite::params![canonical_membership_id],
                |row| row.get::<_, i64>(0),
            )
            .unwrap(),
        2
    );
    assert_eq!(
        connection
            .query_row(
                "
                SELECT COUNT(*)
                FROM memberships
                WHERE membership_id = ?1 AND left_at IS NOT NULL
                ",
                rusqlite::params![canonical_membership_id],
                |row| row.get::<_, i64>(0),
            )
            .unwrap(),
        1
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT name FROM families WHERE id = ?1",
                rusqlite::params![family_id],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
        "迁移家庭"
    );
    let membership_columns = connection
        .prepare("PRAGMA table_info(memberships)")
        .unwrap()
        .query_map([], |row| row.get::<_, String>(1))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    assert!(!membership_columns
        .iter()
        .any(|column| column == "token_hash"));
    assert_eq!(
        connection
            .query_row("SELECT COUNT(*) FROM pragma_foreign_key_check", [], |row| {
                row.get::<_, i64>(0)
            })
            .unwrap(),
        0
    );
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
    assert_eq!(
        applied,
        json!({"applied":1,"skipped":0,"cursor":1,"record_authors":[]})
    );
    assert_eq!(
        retry,
        json!({"applied":0,"skipped":1,"cursor":1,"record_authors":[]})
    );
    assert_eq!(
        older,
        json!({"applied":0,"skipped":1,"cursor":1,"record_authors":[]})
    );
    assert_eq!(
        newer,
        json!({"applied":1,"skipped":0,"cursor":2,"record_authors":[]})
    );
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
async fn ordinary_push_stamps_record_author_from_authenticated_membership() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "record-author-owner-device",
        "record-author-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "record-author-member-device").await;
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let mut forged_payload = record_payload(&baby_id);
    forged_payload["created_by_device_id"] = json!("record-author-member-device");
    forged_payload["created_by_membership_id"] = member["membership_id"].clone();

    let (push_status, push_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire("record", &record_id, 2, forged_payload, None)]
        }),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");
    assert_eq!(
        push_body["record_authors"],
        json!([{
            "client_uuid": record_id,
            "created_by_membership_id": owner["membership_id"],
        }])
    );

    let mut retry_payload = record_payload(&baby_id);
    retry_payload["created_by_membership_id"] = member["membership_id"].clone();
    let (retry_status, retry_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire("record", &record_id, 2, retry_payload, None)]
        }),
    )
    .await;
    assert_eq!(retry_status, StatusCode::OK, "{retry_body}");
    assert_eq!(retry_body["applied"], 0);
    assert_eq!(retry_body["skipped"], 1);
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
        .expect("record is visible after push");
    assert_eq!(
        record["payload"]["created_by_membership_id"],
        owner["membership_id"]
    );
    assert_eq!(
        record["payload"]["created_by_device_id"],
        "record-author-owner-device"
    );
}

#[tokio::test]
async fn ordinary_push_freezes_first_record_author_across_edit_delete_and_restore() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "record-freeze-owner-device",
        "record-freeze-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "record-freeze-member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();

    let (create_status, create_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(create_status, StatusCode::OK, "{create_body}");

    for (updated_at, deleted_at, note) in [
        (3, None, "成员编辑"),
        (4, Some(4), "成员删除"),
        (5, None, "成员恢复"),
    ] {
        let mut member_payload = record_payload(&baby_id);
        member_payload["note"] = json!(note);
        member_payload["created_by_device_id"] = json!("record-freeze-member-device");
        member_payload["created_by_membership_id"] = member["membership_id"].clone();
        let (status, body) = json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(member_token),
            json!({
                "entities": [entity_wire(
                    "record",
                    &record_id,
                    updated_at,
                    member_payload,
                    deleted_at,
                )]
            }),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{body}");

        let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(member_token)).await;
        let record = pull["entities"]
            .as_array()
            .unwrap()
            .iter()
            .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
            .expect("record version remains visible");
        assert_eq!(record["payload"]["note"], note);
        assert_eq!(
            record["payload"]["created_by_membership_id"],
            owner["membership_id"]
        );
        assert_eq!(
            record["payload"]["created_by_device_id"],
            "record-freeze-owner-device"
        );
    }
}

#[tokio::test]
async fn legacy_record_without_membership_author_keeps_device_fallback_on_edit() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "legacy-author-owner-device",
        "legacy-author-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "legacy-author-member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let (create_status, create_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire(
                "record",
                &record_id,
                2,
                record_payload(&baby_id),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(create_status, StatusCode::OK, "{create_body}");

    // Simulate a record persisted by the legacy protocol before membership authors
    // existed. Database mutation is fixture setup; behavior is still asserted via HTTP.
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let stored_payload: String = connection
        .query_row(
            "
            SELECT payload_json
            FROM entities
            WHERE entity_type = 'record' AND client_uuid = ?1
            ",
            rusqlite::params![record_id],
            |row| row.get(0),
        )
        .unwrap();
    let mut legacy_payload: Value = serde_json::from_str(&stored_payload).unwrap();
    legacy_payload
        .as_object_mut()
        .unwrap()
        .remove("created_by_membership_id");
    connection
        .execute(
            "
            UPDATE entities
            SET payload_json = ?1
            WHERE entity_type = 'record' AND client_uuid = ?2
            ",
            rusqlite::params![serde_json::to_string(&legacy_payload).unwrap(), record_id],
        )
        .unwrap();
    drop(connection);

    let mut forged_edit = record_payload(&baby_id);
    forged_edit["note"] = json!("成员编辑旧记录");
    forged_edit["created_by_device_id"] = json!("legacy-author-member-device");
    forged_edit["created_by_membership_id"] = member["membership_id"].clone();
    let (edit_status, edit_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({
            "entities": [entity_wire("record", &record_id, 3, forged_edit, None)]
        }),
    )
    .await;
    assert_eq!(edit_status, StatusCode::OK, "{edit_body}");

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(member_token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
        .expect("legacy record remains visible after edit");
    assert_eq!(record["payload"]["note"], "成员编辑旧记录");
    assert_eq!(
        record["payload"]["created_by_device_id"],
        "legacy-author-owner-device"
    );
    assert!(record["payload"].get("created_by_membership_id").is_none());
}

#[tokio::test]
async fn restart_hydrates_only_unique_legacy_record_authors_and_advances_cursor_once() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "shared-legacy-device",
        "hydrate-record-author-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let _same_device_member = invite_and_join(&rig.app, owner_token, "shared-legacy-device").await;
    let _second_same_device_member =
        invite_and_join(&rig.app, owner_token, "shared-legacy-device").await;
    let unique_member = invite_and_join(&rig.app, owner_token, "unique-legacy-device").await;
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let unique_record_id = Uuid::new_v4().to_string();
    let ambiguous_record_id = Uuid::new_v4().to_string();
    let unknown_record_id = Uuid::new_v4().to_string();
    let empty_record_id = Uuid::new_v4().to_string();

    let mut unique_payload = record_payload(&baby_id);
    unique_payload["note"] = json!("唯一映射");
    let mut ambiguous_payload = record_payload(&baby_id);
    ambiguous_payload["note"] = json!("重复设备");
    let mut unknown_payload = record_payload(&baby_id);
    unknown_payload["note"] = json!("未知设备");
    let mut empty_payload = record_payload(&baby_id);
    empty_payload["note"] = json!("空设备");
    let (push_status, push_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [
                entity_wire("record", &unique_record_id, 2, unique_payload, None),
                entity_wire("record", &ambiguous_record_id, 3, ambiguous_payload, None),
                entity_wire("record", &unknown_record_id, 4, unknown_payload, None),
                entity_wire("record", &empty_record_id, 5, empty_payload, None),
            ]
        }),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");
    let old_cursor = push_body["cursor"].as_i64().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();

    // Simulate rows written before membership authors existed. The migration must
    // use only the legacy device link and must not guess when that link is absent
    // or shared by multiple historical memberships.
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    for (record_id, legacy_device_id) in [
        (&unique_record_id, "unique-legacy-device"),
        (&ambiguous_record_id, "shared-legacy-device"),
        (&unknown_record_id, "unknown-legacy-device"),
        (&empty_record_id, ""),
    ] {
        let stored_payload: String = connection
            .query_row(
                "
                SELECT payload_json
                FROM entities
                WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
                ",
                rusqlite::params![family_id, record_id],
                |row| row.get(0),
            )
            .unwrap();
        let mut legacy_payload: Value = serde_json::from_str(&stored_payload).unwrap();
        let object = legacy_payload.as_object_mut().unwrap();
        object.remove("created_by_membership_id");
        object.insert("created_by_device_id".to_owned(), json!(legacy_device_id));
        connection
            .execute(
                "
                UPDATE entities
                SET payload_json = ?1
                WHERE family_id = ?2 AND entity_type = 'record' AND client_uuid = ?3
                ",
                rusqlite::params![
                    serde_json::to_string(&legacy_payload).unwrap(),
                    family_id,
                    record_id
                ],
            )
            .unwrap();
    }
    drop(connection);

    let restarted = rig.restart("generation-b");
    let (pull_status, pull_body) = get_json(
        &restarted,
        &format!("/v1/pull?cursor={old_cursor}"),
        Some(owner_token),
    )
    .await;
    assert_eq!(pull_status, StatusCode::OK, "{pull_body}");
    let hydrated_cursor = pull_body["cursor"].as_i64().unwrap();
    assert!(hydrated_cursor > old_cursor, "{pull_body}");
    let hydrated_records = pull_body["entities"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|entity| entity["type"] == "record")
        .collect::<Vec<_>>();
    assert_eq!(hydrated_records.len(), 1, "{pull_body}");
    assert_eq!(hydrated_records[0]["client_uuid"], unique_record_id);
    assert_eq!(hydrated_records[0]["updated_at"], 2);
    assert_eq!(hydrated_records[0]["payload"]["note"], "唯一映射");
    assert_eq!(
        hydrated_records[0]["payload"]["created_by_membership_id"],
        unique_member["membership_id"]
    );
    assert_eq!(
        hydrated_records[0]["payload"]["created_by_device_id"],
        "unique-legacy-device"
    );

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    for record_id in [&ambiguous_record_id, &unknown_record_id, &empty_record_id] {
        let stored_payload: String = connection
            .query_row(
                "
                SELECT payload_json
                FROM entities
                WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
                ",
                rusqlite::params![family_id, record_id],
                |row| row.get(0),
            )
            .unwrap();
        let payload: Value = serde_json::from_str(&stored_payload).unwrap();
        assert!(
            payload.get("created_by_membership_id").is_none(),
            "ambiguous/unknown author was guessed for {record_id}: {payload}"
        );
    }
    let stored_family_rev: i64 = connection
        .query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            rusqlite::params![family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(stored_family_rev, hydrated_cursor);
    drop(connection);

    let restarted_again = rig.restart("generation-c");
    let (second_pull_status, second_pull) = get_json(
        &restarted_again,
        &format!("/v1/pull?cursor={hydrated_cursor}"),
        Some(owner_token),
    )
    .await;
    assert_eq!(second_pull_status, StatusCode::OK, "{second_pull}");
    assert_eq!(second_pull["cursor"], hydrated_cursor);
    assert_eq!(second_pull["entities"], json!([]));
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
async fn family_delete_keeps_media_when_database_deletion_fails() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "delete-failure-owner",
        "delete-failure-owner-request-00001",
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
            CREATE TRIGGER fail_family_delete
            BEFORE DELETE ON families
            BEGIN
                SELECT RAISE(FAIL, 'injected family deletion failure');
            END;
            ",
        )
        .unwrap();
    drop(connection);

    let failed = request(
        &rig.app,
        Method::POST,
        "/v1/family/delete",
        Some(token),
        Body::from("{}"),
        Some("application/json"),
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
    let restarted = rig.restart("generation-b");
    assert_eq!(
        json_request(
            &restarted,
            Method::POST,
            "/v1/family/delete",
            Some(token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );
    assert!(!rig.directory.path().join("media").join(family_id).exists());
}

#[tokio::test]
async fn restart_collects_only_uuid_orphan_family_media_directories() {
    let rig = Rig::new();
    let deleted = create_family(
        &rig.app,
        "orphan-cleanup-owner",
        "orphan-cleanup-owner-request-000001",
    )
    .await;
    let deleted_token = deleted["token"].as_str().unwrap();
    let deleted_family_id = deleted["family_id"].as_str().unwrap();
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/family/delete",
            Some(deleted_token),
            json!({}),
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

    let active = create_family(
        &rig.app,
        "active-cleanup-owner",
        "active-cleanup-owner-request-000001",
    )
    .await;
    let active_dir = media_root.join(active["family_id"].as_str().unwrap());
    fs::create_dir_all(&active_dir).unwrap();
    fs::write(active_dir.join("active-marker"), b"active").unwrap();
    let operational = media_root.join(".operator-owned");
    fs::create_dir_all(&operational).unwrap();
    fs::write(operational.join("keep"), b"keep").unwrap();

    let _restarted = rig.restart("generation-b");

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

// ---------------------------------------------------------------------------
// Atomic bundle protocol
// ---------------------------------------------------------------------------

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
    let (status, body) = json_request(
        app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire("baby", &baby_id, 1, baby_payload("年年", None), None)]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    baby_id
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
async fn atomic_bundle_commit_rejects_malformed_or_wrong_shape_json() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-json-owner-device",
        "bundle-json-owner-request-0000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
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
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "bundle-author-member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();

    for (token, updated_at, deleted_at, note) in [
        (owner_token, 2, None, "创建"),
        (member_token, 3, None, "成员编辑"),
        (member_token, 4, Some(4), "成员删除"),
        (member_token, 5, None, "成员恢复"),
    ] {
        let bundle_id = Uuid::new_v4().to_string();
        let mut forged_payload = record_payload(&baby_id);
        forged_payload["note"] = json!(note);
        forged_payload["created_by_device_id"] = json!("bundle-author-member-device");
        forged_payload["created_by_membership_id"] = member["membership_id"].clone();
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
            Some(token),
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
            Some(token),
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

        let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
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
        assert_eq!(
            record["payload"]["created_by_device_id"],
            "bundle-author-owner-device"
        );
    }
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
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "bundle-stager-member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let mut forged_payload = record_payload(&baby_id);
    forged_payload["created_by_device_id"] = json!("bundle-stager-member-device");
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
    assert_eq!(
        record["payload"]["created_by_device_id"],
        "bundle-stager-owner-device"
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
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "media-member-device").await;
    let member_token = member["token"].as_str().unwrap();
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
async fn ambiguous_legacy_bundle_rejects_media_before_writing() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "ambiguous-media-device",
        "ambiguous-media-owner-request-001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "ambiguous-media-device").await;
    let member_token = member["token"].as_str().unwrap();
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
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "ALTER TABLE sync_bundles DROP COLUMN staged_membership_id",
            [],
        )
        .unwrap();
    drop(connection);
    let restarted = rig.restart("generation-b");
    let staged_path = rig
        .directory
        .path()
        .join("media")
        .join(family_id)
        .join(".stage")
        .join(&bundle_id)
        .join(&media_id);

    for token in [owner_token, member_token] {
        let response = request(
            &restarted,
            Method::PUT,
            &format!("/v1/bundles/{bundle_id}/media/{media_id}"),
            Some(token),
            Body::from("img"),
            Some("image/jpeg"),
        )
        .await;
        assert_eq!(response.status(), StatusCode::CONFLICT);
        assert!(!staged_path.exists());
    }
}

#[tokio::test]
async fn equal_ordinary_push_before_commit_repairs_the_committed_bundle_package() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "bundle-race-owner-device",
        "bundle-race-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "bundle-race-member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let mut staged_payload = record_payload(&baby_id);
    staged_payload["note"] = json!("A 暂存");
    let staged_root = entity_wire("record", &record_id, 2, staged_payload, None);

    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": staged_root.clone(),
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    let mut ordinary_payload = record_payload(&baby_id);
    ordinary_payload["note"] = json!("B 先发布");
    let (push_status, push_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({
            "entities": [entity_wire(
                "record",
                &record_id,
                2,
                ordinary_payload,
                None,
            )]
        }),
    )
    .await;
    assert_eq!(push_status, StatusCode::OK, "{push_body}");
    assert_eq!(
        push_body["record_authors"],
        json!([{
            "client_uuid": record_id,
            "created_by_membership_id": member["membership_id"],
        }])
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
    assert_eq!(
        committed["record_authors"],
        json!([{
            "client_uuid": record_id,
            "created_by_membership_id": member["membership_id"],
        }])
    );
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
    assert_eq!(retry["record_authors"], committed["record_authors"]);

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
        .expect("ordinary winner remains the published record");
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
            "bundle_id": bundle_id,
            "root": staged_root,
            "media": [],
        }),
    )
    .await;
    assert_eq!(restage_status, StatusCode::OK, "{restaged}");
    assert_eq!(restaged["status"], "committed");
}

#[tokio::test]
async fn legacy_staged_root_author_is_canonicalized_before_committed_hash() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "legacy-root-owner-device",
        "legacy-root-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let root = entity_wire("record", &record_id, 2, record_payload(&baby_id), None);
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": root.clone(),
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let stored_payload: String = connection
        .query_row(
            "
            SELECT root_payload_json
            FROM sync_bundles
            WHERE family_id = ?1 AND bundle_id = ?2
            ",
            rusqlite::params![owner["family_id"].as_str().unwrap(), bundle_id],
            |row| row.get(0),
        )
        .unwrap();
    let mut legacy_payload: Value = serde_json::from_str(&stored_payload).unwrap();
    legacy_payload
        .as_object_mut()
        .unwrap()
        .remove("created_by_membership_id");
    connection
        .execute(
            "
            UPDATE sync_bundles
            SET root_payload_json = ?1
            WHERE family_id = ?2 AND bundle_id = ?3
            ",
            rusqlite::params![
                serde_json::to_string(&legacy_payload).unwrap(),
                owner["family_id"].as_str().unwrap(),
                bundle_id
            ],
        )
        .unwrap();
    drop(connection);

    let (commit_status, commit_body) = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(commit_status, StatusCode::OK, "{commit_body}");
    let (restage_status, restaged) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": root,
            "media": [],
        }),
    )
    .await;
    assert_eq!(restage_status, StatusCode::OK, "{restaged}");
    assert_eq!(restaged["status"], "committed");
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
        .expect("legacy staged root is published with a canonical author");
    assert_eq!(
        record["payload"]["created_by_membership_id"],
        owner["membership_id"]
    );
}

#[tokio::test]
async fn legacy_committed_bundle_retry_acks_the_migrated_entity_author() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "legacy-committed-owner-device",
        "legacy-committed-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let family_id = owner["family_id"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let root = entity_wire("record", &record_id, 2, record_payload(&baby_id), None);
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": root.clone(),
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

    // Simulate a bundle committed before membership authors existed: entity,
    // committed snapshot, and the snapshot-derived content hash are all legacy.
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let stored_payload: String = connection
        .query_row(
            "
            SELECT payload_json
            FROM entities
            WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
            ",
            rusqlite::params![family_id, record_id],
            |row| row.get(0),
        )
        .unwrap();
    let mut legacy_payload: Value = serde_json::from_str(&stored_payload).unwrap();
    legacy_payload
        .as_object_mut()
        .unwrap()
        .remove("created_by_membership_id");
    let legacy_payload_json = serde_json::to_string(&legacy_payload).unwrap();
    let legacy_content_hash = hex::encode(Sha256::digest(
        serde_json::to_vec(&json!({
            "root": {
                "entity_type": "record",
                "client_uuid": record_id,
                "updated_at": 2,
                "deleted_at": null,
                "payload": legacy_payload,
            },
            "media": [],
        }))
        .unwrap(),
    ));
    connection
        .execute(
            "
            UPDATE entities
            SET payload_json = ?1
            WHERE family_id = ?2 AND entity_type = 'record' AND client_uuid = ?3
            ",
            rusqlite::params![legacy_payload_json, family_id, record_id],
        )
        .unwrap();
    connection
        .execute(
            "
            UPDATE sync_bundles
            SET root_payload_json = ?1, content_hash = ?2
            WHERE family_id = ?3 AND bundle_id = ?4
            ",
            rusqlite::params![
                legacy_payload_json,
                legacy_content_hash,
                family_id,
                bundle_id
            ],
        )
        .unwrap();
    drop(connection);

    let restarted = rig.restart("generation-b");
    let (_, pull) = get_json(&restarted, "/v1/pull?cursor=0", Some(owner_token)).await;
    let migrated_record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
        .expect("migration keeps the committed record visible");
    assert_eq!(
        migrated_record["payload"]["created_by_membership_id"],
        owner["membership_id"]
    );

    let (restage_status, restage_body) = json_request(
        &restarted,
        Method::POST,
        "/v1/bundles",
        Some(owner_token),
        json!({
            "bundle_id": bundle_id,
            "root": root,
            "media": [],
        }),
    )
    .await;
    assert_eq!(restage_status, StatusCode::OK, "{restage_body}");
    assert_eq!(restage_body["status"], "committed");

    let (retry_status, retry_body) = json_request(
        &restarted,
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
}

#[tokio::test]
async fn legacy_staging_bundle_backfills_only_its_unique_membership() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "legacy-bundle-owner-device",
        "legacy-bundle-owner-request-000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "legacy-bundle-member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
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
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    // Simulate a pre-migration staging row. Restart must derive a membership only
    // when the stored family/device pair has exactly one active membership.
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "ALTER TABLE sync_bundles DROP COLUMN staged_membership_id",
            [],
        )
        .unwrap();
    drop(connection);
    let restarted = rig.restart("generation-b");

    let (foreign_status, foreign_body) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(member_token),
        json!({}),
    )
    .await;
    assert_eq!(foreign_status, StatusCode::CONFLICT, "{foreign_body}");

    let (owner_status, owner_commit) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(owner_token),
        json!({}),
    )
    .await;
    assert_eq!(owner_status, StatusCode::OK, "{owner_commit}");
    let (_, pull) = get_json(&restarted, "/v1/pull?cursor=0", Some(owner_token)).await;
    let record = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|entity| entity["type"] == "record" && entity["client_uuid"] == record_id)
        .expect("safely backfilled legacy bundle commits for its original membership");
    assert_eq!(
        record["payload"]["created_by_membership_id"],
        owner["membership_id"]
    );
}

#[tokio::test]
async fn ambiguous_legacy_staging_membership_fails_closed() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "ambiguous-legacy-device",
        "ambiguous-legacy-owner-request-001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "ambiguous-legacy-device").await;
    let member_token = member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
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
            "media": [],
        }),
    )
    .await;
    assert_eq!(stage_status, StatusCode::OK, "{stage_body}");

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "ALTER TABLE sync_bundles DROP COLUMN staged_membership_id",
            [],
        )
        .unwrap();
    drop(connection);
    let restarted = rig.restart("generation-b");

    for token in [owner_token, member_token] {
        let (status, body) = json_request(
            &restarted,
            Method::POST,
            &format!("/v1/bundles/{bundle_id}/commit"),
            Some(token),
            json!({}),
        )
        .await;
        assert_eq!(status, StatusCode::CONFLICT, "{body}");
        assert_eq!(
            body["detail"],
            "legacy staging bundle has no verifiable membership"
        );
    }
    let (_, pull) = get_json(&restarted, "/v1/pull?cursor=0", Some(owner_token)).await;
    assert!(!pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == record_id));
}

#[tokio::test]
async fn legacy_staging_backfill_counts_left_memberships_before_same_device_rejoin() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "legacy-rejoin-owner-device",
        "legacy-rejoin-owner-request-00001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let first_member = invite_and_join(&rig.app, owner_token, "legacy-rejoin-shared-device").await;
    let first_token = first_member["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, owner_token).await;
    let record_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();
    let (stage_status, stage_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(first_token),
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
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/leave",
            Some(first_token),
            json!({}),
        )
        .await
        .0,
        StatusCode::OK
    );
    let second_member = invite_and_join(&rig.app, owner_token, "legacy-rejoin-shared-device").await;
    let second_token = second_member["token"].as_str().unwrap();

    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute(
            "ALTER TABLE sync_bundles DROP COLUMN staged_membership_id",
            [],
        )
        .unwrap();
    drop(connection);
    let restarted = rig.restart("generation-b");

    let (status, body) = json_request(
        &restarted,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(second_token),
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{body}");
    assert_eq!(
        body["detail"],
        "legacy staging bundle has no verifiable membership"
    );
    let (_, pull) = get_json(&restarted, "/v1/pull?cursor=0", Some(owner_token)).await;
    assert!(!pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == record_id));
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
    let token = created["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = "11111111-2222-3333-8444-555555555555";
    let legacy_bundle_id = format!("record:{record_id}:2");

    let (legacy_status, legacy_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/bundles",
        Some(token),
        json!({
            "bundle_id": legacy_bundle_id,
            "root": entity_wire("record", record_id, 2, record_payload(&baby_id), None),
            "media": []
        }),
    )
    .await;
    assert_eq!(
        legacy_status,
        StatusCode::UNPROCESSABLE_ENTITY,
        "{legacy_body}"
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
    let token = created["token"].as_str().unwrap();
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
    let token = created["token"].as_str().unwrap();
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
        Body::from("{}"),
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
        json!({}),
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
    let token = created["token"].as_str().unwrap();
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
    let unverifiable_legacy_retry = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_ne!(
        unverifiable_legacy_retry.0,
        StatusCode::OK,
        "legacy committed retry trusted published bytes without a digest or staged source: {}",
        unverifiable_legacy_retry.1
    );

    fs::create_dir_all(&stage_dir).unwrap();
    fs::write(stage_dir.join(&media_id), b"img").unwrap();
    let recoverable_legacy_retry = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_eq!(
        recoverable_legacy_retry.0,
        StatusCode::OK,
        "legacy committed retry could not pin a digest from matching staged bytes: {}",
        recoverable_legacy_retry.1
    );
    assert!(!stage_dir.exists(), "legacy retry left staging bytes");
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let pinned_sha256: String = connection
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
    assert_eq!(pinned_sha256, hex::encode(Sha256::digest(b"img")));
    drop(connection);

    fs::write(&published, b"bad").unwrap();
    let pinned_legacy_corrupt_retry = json_request(
        &rig.app,
        Method::POST,
        &format!("/v1/bundles/{bundle_id}/commit"),
        Some(token),
        json!({}),
    )
    .await;
    assert_ne!(
        pinned_legacy_corrupt_retry.0,
        StatusCode::OK,
        "pinned legacy digest accepted same-size corrupt bytes: {}",
        pinned_legacy_corrupt_retry.1
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
    let token = created["token"].as_str().unwrap();
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
    let token = created["token"].as_str().unwrap();
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
    let token = created["token"].as_str().unwrap();
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
    json!({
        "baby_client_uuid": baby_id,
        "type": plan_type,
        "scheduled_at": 1_700_000_000_000i64,
        "scheduled_zone_id": "Asia/Shanghai",
        "status": "pending",
        "payload_json": {},
        "schema_version": 1,
        "note": null,
        // Forgery attempt — server must overwrite with authenticated membership.
        "created_by_membership_id": "spoofed-membership",
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
    let token = created["token"].as_str().unwrap();
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
    let owner_token = owner["token"].as_str().unwrap();
    let member_a = invite_and_join(&rig.app, owner_token, "care-plan-member-a").await;
    let member_a_token = member_a["token"].as_str().unwrap();
    let member_b = invite_and_join(&rig.app, owner_token, "care-plan-member-b").await;
    let member_b_token = member_b["token"].as_str().unwrap();
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
async fn care_plan_rejected_on_legacy_push() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "legacy-plan-device",
        "legacy-plan-request-0000000000001",
    )
    .await;
    let token = created["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let plan_id = Uuid::new_v4().to_string();
    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
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
async fn fulfillment_candidate_stamps_submitter_and_rejects_bad_refs() {
    let rig = Rig::new();
    let owner = create_family(
        &rig.app,
        "fulfill-owner",
        "fulfill-cand-request-000000000001",
    )
    .await;
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "fulfill-member").await;
    let member_token = member["token"].as_str().unwrap();
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
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(member_token),
            json!({
                "entities": [entity_wire(
                    "record",
                    &record_id,
                    2,
                    record_payload(&baby_id),
                    None
                )]
            }),
        )
        .await
        .0,
        StatusCode::OK
    );

    let cand_id = Uuid::new_v4().to_string();
    let (ok, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({
            "entities": [entity_wire(
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
                None
            )]
        }),
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
    let (bad, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({
            "entities": [entity_wire(
                "fulfillment_candidate",
                &bad_id,
                4,
                json!({
                    "care_plan_client_uuid": Uuid::new_v4().to_string(),
                    "record_client_uuid": record_id,
                }),
                None
            )]
        }),
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
    let owner_token = owner["token"].as_str().unwrap();
    let member = invite_and_join(&rig.app, owner_token, "fulfill-freeze-member").await;
    let member_token = member["token"].as_str().unwrap();
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
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(member_token),
            json!({
                "entities": [entity_wire(
                    "record",
                    &member_record,
                    2,
                    record_payload(&baby_id),
                    None
                )]
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
            "/v1/push",
            Some(owner_token),
            json!({
                "entities": [entity_wire(
                    "record",
                    &owner_record,
                    3,
                    record_payload(&baby_id),
                    None
                )]
            }),
        )
        .await
        .0,
        StatusCode::OK
    );

    let member_cand = Uuid::new_v4().to_string();
    let owner_cand = Uuid::new_v4().to_string();
    // Member first, owner second — both accepted; stamps frozen per first accept.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(member_token),
            json!({
                "entities": [entity_wire(
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
                )]
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
            "/v1/push",
            Some(owner_token),
            json!({
                "entities": [entity_wire(
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
                )]
            }),
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

    // Idempotent replay with higher updated_at and forged stamps must not rewrite.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(member_token),
            json!({
                "entities": [entity_wire(
                    "fulfillment_candidate",
                    &member_cand,
                    99,
                    json!({
                        "care_plan_client_uuid": plan_id,
                        "record_client_uuid": member_record,
                        "submitter_membership_id": "replay-forged",
                        "submitter_role": "owner",
                        "confirmed_at": 999_999,
                    }),
                    None
                )]
            }),
        )
        .await
        .0,
        StatusCode::OK
    );
    let (_, pull2) = get_json(&rig.app, "/v1/pull?cursor=0", Some(owner_token)).await;
    let m2 = pull2["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == member_cand)
        .expect("member candidate after replay");
    assert_eq!(
        m2["payload"]["submitter_membership_id"],
        member["membership_id"]
    );
    assert_eq!(m2["payload"]["submitter_role"], "member");
    assert_eq!(m2["payload"]["confirmed_at"], frozen_member_at);
    assert_eq!(
        pull2["entities"]
            .as_array()
            .unwrap()
            .iter()
            .find(|e| e["client_uuid"] == owner_cand)
            .unwrap()["payload"]["confirmed_at"],
        frozen_owner_at
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
    let owner_token = owner["token"].as_str().unwrap();
    let creator = invite_and_join(&rig.app, owner_token, "care-plan-leave-creator").await;
    let creator_token = creator["token"].as_str().unwrap();
    let peer = invite_and_join(&rig.app, owner_token, "care-plan-leave-peer").await;
    let peer_token = peer["token"].as_str().unwrap();
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
    assert_eq!(
        plan["payload"]["created_by_membership_id"],
        creator["membership_id"]
    );
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
    let owner_token = owner["token"].as_str().unwrap();
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
    let (ok, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire(
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
                None
            )]
        }),
    )
    .await;
    assert_eq!(ok, StatusCode::OK);

    // Unknown care_plan_client_uuid is rejected.
    let media_bad_plan = Uuid::new_v4().to_string();
    let (bad_plan, body_plan) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire(
                "media",
                &media_bad_plan,
                3,
                json!({
                    "kind": "log",
                    "care_plan_client_uuid": Uuid::new_v4().to_string(),
                    "mime": "image/jpeg",
                    "byte_size": 3,
                }),
                None
            )]
        }),
    )
    .await;
    assert_eq!(bad_plan, StatusCode::CONFLICT, "{body_plan}");

    // Baby mismatch vs care_plan baby is rejected.
    let media_bad_baby = Uuid::new_v4().to_string();
    let (bad_baby, body_baby) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire(
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
                None
            )]
        }),
    )
    .await;
    assert_eq!(bad_baby, StatusCode::CONFLICT, "{body_baby}");
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
    let token = created["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();

    // Legacy path publishes a newer record.
    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire(
                "record",
                &record_id,
                100,
                record_payload(&baby_id),
                None
            )]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

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
async fn stale_bundle_bytes_are_not_claimed_by_legacy_metadata_after_restart() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-stale-media-owner",
        "bundle-stale-media-request-00001",
    )
    .await;
    let token = created["token"].as_str().unwrap();
    let family_id = created["family_id"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    let (newer_status, newer_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire(
                "record",
                &record_id,
                100,
                record_payload(&baby_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(newer_status, StatusCode::OK, "{newer_body}");

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
    // pre-published final-path file for a historical legacy upload.
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

    let (legacy_status, legacy_body) = json_request(
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
    assert_eq!(legacy_status, StatusCode::OK, "{legacy_body}");

    for app in [&rig.app, &rig.restart("generation-b")] {
        let (_, pull) = get_json(app, "/v1/pull?cursor=0", Some(token)).await;
        assert!(
            !pull["entities"]
                .as_array()
                .unwrap()
                .iter()
                .any(|entity| entity["client_uuid"] == media_id),
            "metadata-only legacy push claimed bytes from the rejected bundle: {pull}"
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
    let token = created["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let stale_bundle_id = Uuid::new_v4().to_string();

    let (published_status, published_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire(
                "record",
                &record_id,
                100,
                record_payload(&baby_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(published_status, StatusCode::OK, "{published_body}");

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
    assert_eq!(metadata_status, StatusCode::OK, "{metadata_body}");

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
    assert_eq!(commit_body["applied"], 1);

    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert!(
        !pull["entities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|entity| entity["client_uuid"] == media_id),
        "losing tombstone published another bundle's quarantined bytes: {pull}"
    );
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
async fn v1_staging_bytes_stay_quarantined_after_upgrade_and_manifest_refine() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "v1-staging-media-owner",
        "v1-staging-media-request-000000001",
    )
    .await;
    let token = created["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let bundle_id = Uuid::new_v4().to_string();

    let (newer_status, newer_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire(
                "record",
                &record_id,
                100,
                record_payload(&baby_id),
                None,
            )],
        }),
    )
    .await;
    assert_eq!(newer_status, StatusCode::OK, "{newer_body}");
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
    let (legacy_status, legacy_body) = json_request(
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
    assert_eq!(legacy_status, StatusCode::OK, "{legacy_body}");

    // Simulate the persisted v1 state: final-path bytes and staging metadata
    // existed before publication ownership was introduced.
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute_batch(
            "
            DROP TABLE media_publications;
            PRAGMA user_version = 1;
            ",
        )
        .unwrap();
    drop(connection);

    let upgraded = rig.restart("generation-b");
    let (_, hidden_after_upgrade) = get_json(&upgraded, "/v1/pull?cursor=0", Some(token)).await;
    assert!(!hidden_after_upgrade["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == media_id));

    // Refining the same staging bundle removes its old media manifest. The
    // upgrade must already have persisted quarantine ownership independently.
    let (refine_status, refine_body) = json_request(
        &upgraded,
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

    let restarted = rig.restart("generation-c");
    let (_, pull) = get_json(&restarted, "/v1/pull?cursor=0", Some(token)).await;
    assert!(
        !pull["entities"]
            .as_array()
            .unwrap()
            .iter()
            .any(|entity| entity["client_uuid"] == media_id),
        "v1 pre-published bytes were misclassified as legacy-owned: {pull}"
    );
    assert_eq!(
        request(
            &restarted,
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
async fn legacy_push_pull_media_remain_available_alongside_bundles() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-legacy-owner",
        "bundle-legacy-request-0000000001",
    )
    .await;
    let token = created["token"].as_str().unwrap();
    let baby_id = seed_baby(&rig.app, token).await;
    let record_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let (status, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [
                entity_wire("record", &record_id, 2, record_payload(&baby_id), None),
                entity_wire("media", &media_id, 2, log_media_payload(&record_id), None),
            ]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
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
        StatusCode::OK
    );
    let (_, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert!(pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|e| e["client_uuid"] == media_id));

    // Upgrade a genuine pre-v2 legacy upload: the new ownership table did not
    // exist, but its final-path bytes must remain available after migration.
    let connection = rusqlite::Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    connection
        .execute_batch(
            "
            DROP TABLE media_publications;
            PRAGMA user_version = 1;
            ",
        )
        .unwrap();
    drop(connection);
    let restarted = rig.restart("generation-b");
    let (_, migrated_pull) = get_json(&restarted, "/v1/pull?cursor=0", Some(token)).await;
    assert!(migrated_pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == media_id));
    let migrated_bytes = request(
        &restarted,
        Method::GET,
        &format!("/v1/media/{media_id}"),
        Some(token),
        Body::empty(),
        None,
    )
    .await;
    assert_eq!(migrated_bytes.status(), StatusCode::OK);
    assert_eq!(
        migrated_bytes
            .into_body()
            .collect()
            .await
            .unwrap()
            .to_bytes(),
        "log"
    );
}

#[tokio::test]
async fn legacy_put_cannot_overwrite_committed_bundle_media() {
    let rig = Rig::new();
    let created = create_family(
        &rig.app,
        "bundle-owned-media-owner",
        "bundle-owned-media-request-0000001",
    )
    .await;
    let token = created["token"].as_str().unwrap();
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
    assert_eq!(overwrite.status(), StatusCode::CONFLICT);

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
// Custom item family definitions (legacy push + ACL)
// ---------------------------------------------------------------------------

fn custom_item_payload(name: &str, icon_slot: i64) -> Value {
    json!({
        "name": name,
        "icon_slot": icon_slot,
    })
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
    let owner_token = owner["token"].as_str().unwrap();
    let owner_membership = owner["membership_id"].as_str().unwrap().to_owned();
    let member = invite_and_join(&rig.app, owner_token, "member-device").await;
    let member_token = member["token"].as_str().unwrap();
    let member_membership = member["membership_id"].as_str().unwrap().to_owned();
    let item_id = Uuid::new_v4().to_string();

    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_token),
        json!({
            "entities": [entity_wire(
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
            )]
        }),
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
    let owner_token = owner["token"].as_str().unwrap();
    let member_a = invite_and_join(&rig.app, owner_token, "member-a").await;
    let member_a_token = member_a["token"].as_str().unwrap();
    let member_b = invite_and_join(&rig.app, owner_token, "member-b").await;
    let member_b_token = member_b["token"].as_str().unwrap();
    let item_id = Uuid::new_v4().to_string();

    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(member_a_token),
            json!({
                "entities": [entity_wire(
                    "custom_item",
                    &item_id,
                    1,
                    custom_item_payload("药", 1),
                    None,
                )]
            }),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Creator may rename.
    let (ok_rename, _) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_a_token),
        json!({
            "entities": [entity_wire(
                "custom_item",
                &item_id,
                2,
                custom_item_payload("用药", 1),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(ok_rename, StatusCode::OK);

    // Peer member cannot rename.
    let (denied, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(member_b_token),
        json!({
            "entities": [entity_wire(
                "custom_item",
                &item_id,
                3,
                custom_item_payload("篡改", 0),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(denied, StatusCode::FORBIDDEN, "{body}");

    // Owner may rename and soft-delete.
    assert_eq!(
        json_request(
            &rig.app,
            Method::POST,
            "/v1/push",
            Some(owner_token),
            json!({
                "entities": [entity_wire(
                    "custom_item",
                    &item_id,
                    4,
                    custom_item_payload("管理员改名", 3),
                    None,
                )]
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
            "/v1/push",
            Some(owner_token),
            json!({
                "entities": [entity_wire(
                    "custom_item",
                    &item_id,
                    5,
                    custom_item_payload("管理员改名", 3),
                    Some(5),
                )]
            }),
        )
        .await
        .0,
        StatusCode::OK
    );

    // Tombstone cannot be resurrected (even by owner).
    let (resurrect, resurrect_body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(owner_token),
        json!({
            "entities": [entity_wire(
                "custom_item",
                &item_id,
                6,
                custom_item_payload("管理员改名", 3),
                None,
            )]
        }),
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
    let token = owner["token"].as_str().unwrap();
    let item_id = Uuid::new_v4().to_string();
    let (status, body) = json_request(
        &rig.app,
        Method::POST,
        "/v1/push",
        Some(token),
        json!({
            "entities": [entity_wire(
                "custom_item",
                &item_id,
                1,
                json!({
                    "name": "抚触",
                    "icon_slot": 0,
                    "sort_order": 3,
                }),
                None,
            )]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{body}");
}
