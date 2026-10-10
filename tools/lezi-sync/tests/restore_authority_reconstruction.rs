//! Reconstructed contracts exercised through production HTTP and persistent SQLite/files.
use axum::{
    body::Body,
    extract::ConnectInfo,
    http::{Method, Request, StatusCode},
    Router,
};
use http_body_util::BodyExt;
use lezi_sync::{build_app, RateLimitConfig, ServerConfig};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs,
    net::{Ipv4Addr, SocketAddr},
    path::Path,
};
use tempfile::TempDir;
use tower::ServiceExt;
use uuid::Uuid;

const ROOT: &str = "synthetic-reconstruction-root-password";
fn app(path: &Path) -> Router {
    app_at(path, 1_800_000_000)
}
fn app_at(path: &Path, now: i64) -> Router {
    let mut config = ServerConfig::new(path);
    config.bootstrap_secret = Some(ROOT.to_owned());
    config.generation = Some("reconstructed-generation".to_owned());
    config.create_rate_limit = RateLimitConfig {
        max_attempts: 10000,
        window_seconds: 60,
    };
    build_app(config.with_clock(move || now)).unwrap()
}
async fn send(
    app: &Router,
    method: Method,
    path: &str,
    token: Option<&str>,
    body: Value,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("content-type", "application/json")
        .header("x-lezi-bootstrap-secret", ROOT)
        .header("x-lezi-client-version-code", "35")
        .header(
            "x-lezi-sync-capabilities",
            "nursing_plan_intent_v1,restore_authority_v1",
        )
        .header("x-lezi-media-identity", "v1");
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    let mut request = request.body(Body::from(body.to_string())).unwrap();
    request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 32111))));
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}
fn baby(id: &str) -> Value {
    json!({"type":"baby","client_uuid":id,"updated_at":1000,"deleted_at":null,"payload":{"nickname":"合成宝宝","sex":"female","birthday":"2025-01-02","birth_weight_grams":null,"avatar_media_uuid":null}})
}
fn record(id: &str, baby: &str, note: &str) -> Value {
    json!({"type":"record","client_uuid":id,"updated_at":1000,"deleted_at":null,"payload":{"baby_client_uuid":baby,"type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":note,"payload_json":{"amount_ml":100},"schema_version":2}})
}
async fn start(app: &Router, family: &str) -> (String, String) {
    let(status,result)=send(app,Method::POST,"/v1/disaster-restore/batches",None,json!({"restore_authority":"v1","request_id":Uuid::new_v4(),"family_id":family,"family_name":"合成家庭","owner_display_name":"合成照护者","device_name":"合成恢复端"})).await;
    assert_eq!(status, StatusCode::CREATED, "{result}");
    (
        result["batch_id"].as_str().unwrap().to_owned(),
        result["recovery_token"].as_str().unwrap().to_owned(),
    )
}
async fn manifest(
    app: &Router,
    batch: &str,
    token: &str,
    entities: Vec<Value>,
    relations: Value,
    media: Value,
) -> (StatusCode, Value) {
    send(app,Method::PUT,&format!("/v1/disaster-restore/batches/{batch}/manifest"),Some(token),json!({"restore_authority":"v1","request_id":"synthetic-manifest-request-00000001","entities":entities,"source_relations":relations,"media":media})).await
}
async fn commit(app: &Router, batch: &str, token: &str) -> (StatusCode, Value) {
    send(
        app,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch}/commit"),
        Some(token),
        json!({"restore_authority":"v1","request_id":"synthetic-commit-request-000000001"}),
    )
    .await
}
async fn check_restored_relations_creators_and_causal_baselines(auto_aligned: bool) {
    let dir = TempDir::new().unwrap();
    let app1 = app(dir.path());
    let family = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let one = Uuid::new_v4().to_string();
    let two = Uuid::new_v4().to_string();
    let relation = Uuid::new_v4().to_string();
    let (batch, token) = start(&app1, &family).await;
    let(status,value)=manifest(&app1,&batch,&token,vec![baby(&baby_id),record(&one,&baby_id,"a"),record(&two,&baby_id,"b")],json!([{"relation_id":relation,"display_client_uuid":two,"source_client_uuids":[one],"auto_aligned":auto_aligned}]),json!([])).await;
    assert_eq!(status, StatusCode::OK, "{value}");
    let (status, receipt) = commit(&app1, &batch, &token).await;
    assert_eq!(status, StatusCode::OK, "{receipt}");
    assert_eq!(receipt["restore_authority"], "v1");
    let batch_dir = dir.path().join("disaster-restore").join(&batch);
    assert!(!batch_dir.join("manifest.json").exists());
    let journal: Value =
        serde_json::from_slice(&fs::read(batch_dir.join("journal.json")).unwrap()).unwrap();
    assert_eq!(journal["protocol_version"], 2);
    assert!(journal["manifest_hash"].is_null());
    assert_eq!(journal["owner_display_name"], "");
    let app2 = app(dir.path());
    let (status, replay) = commit(&app2, &batch, &token).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(replay, receipt);
    let access = receipt["access_token"].as_str().unwrap();
    let (status, pull) = send(
        &app2,
        Method::GET,
        "/v1/pull?cursor=0&page_index=0&generation=reconstructed-generation",
        Some(access),
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{pull}");
    let entities = pull["entities"].as_array().unwrap();
    let restored_baby = entities
        .iter()
        .find(|e| e["client_uuid"] == baby_id)
        .unwrap();
    assert_eq!(
        restored_baby["payload"]["created_by_membership_id"],
        receipt["membership_id"]
    );
    assert!(restored_baby["version_id"].is_string());
    for (id, role) in [(&one, "source"), (&two, "display")] {
        let row = entities.iter().find(|e| e["client_uuid"] == *id).unwrap();
        assert_eq!(row["source_relation_summary"]["role"], role);
        assert_eq!(
            row["source_relation_summary"]["auto_aligned"]
                .as_bool()
                .unwrap_or(false),
            auto_aligned
        );
    }
    let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
    let objects: i64 = db
        .query_row(
            "SELECT COUNT(*) FROM sqlite_schema WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(objects, 53);
    let versions: i64 = db
        .query_row("SELECT COUNT(*) FROM entity_versions", [], |r| r.get(0))
        .unwrap();
    assert_eq!(versions, 3);
    let parents: i64 = db
        .query_row("SELECT COUNT(*) FROM entity_version_parents", [], |r| {
            r.get(0)
        })
        .unwrap();
    assert_eq!(parents, 0);
    let coverage:i64=db.query_row("SELECT COUNT(*) FROM mutation_receipts WHERE membership_id='__restore_source_coverage_v1__'",[],|r|r.get(0)).unwrap();
    assert_eq!(coverage, 1);
}
#[tokio::test]
async fn raw_mime_outside_canonical_domain_is_preserved_and_rejected_before_upload() {
    for mime in [
        Value::Null,
        json!(""),
        json!("x".repeat(129)),
        json!("图".repeat(100)),
    ] {
        let dir = TempDir::new().unwrap();
        let app = app(dir.path());
        let family = Uuid::new_v4().to_string();
        let baby_id = Uuid::new_v4().to_string();
        let record_id = Uuid::new_v4().to_string();
        let media = Uuid::new_v4().to_string();
        let (batch, token) = start(&app, &family).await;
        let photo = json!({"type":"media","client_uuid":media,"updated_at":1000,"payload":{"kind":"log","record_client_uuid":record_id,"baby_client_uuid":null,"care_plan_client_uuid":null,"mime":mime,"width":null,"height":null,"byte_size":4}});
        let(status,error)=manifest(&app,&batch,&token,vec![baby(&baby_id),record(&record_id,&baby_id,"source"),photo],json!([]),json!([{"client_uuid":media,"byte_size":4,"sha256":hex::encode(Sha256::digest(b"test"))}])).await;
        assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
        assert_eq!(error["code"], "restore_lossless_unsupported");
        assert!(!dir
            .path()
            .join("disaster-restore")
            .join(batch)
            .join("manifest.json")
            .exists());
        let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM families", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            0
        );
    }
}
#[tokio::test]
async fn missing_relation_coverage_is_not_silently_empty() {
    let dir = TempDir::new().unwrap();
    let app = app(dir.path());
    let (batch, token) = start(&app, &Uuid::new_v4().to_string()).await;
    let(status,_)=send(&app,Method::PUT,&format!("/v1/disaster-restore/batches/{batch}/manifest"),Some(&token),json!({"restore_authority":"v1","request_id":"synthetic-incomplete-manifest-00001","entities":[baby(&Uuid::new_v4().to_string())],"media":[]})).await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY);
}

async fn upload(
    app: &Router,
    batch: &str,
    token: &str,
    media: &str,
    bytes: &[u8],
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(Method::PUT)
        .uri(format!(
            "/v1/disaster-restore/batches/{batch}/media/{media}"
        ))
        .header("authorization", format!("Bearer {token}"))
        .header("x-lezi-client-version-code", "35")
        .header(
            "x-lezi-sync-capabilities",
            "nursing_plan_intent_v1,restore_authority_v1",
        )
        .body(Body::from(bytes.to_vec()))
        .unwrap();
    request
        .extensions_mut()
        .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 32111))));
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&bytes).unwrap())
}
#[tokio::test]
async fn wake_original_bytes_are_bound_to_restored_head_and_pull_after_restart() {
    let dir = TempDir::new().unwrap();
    let app1 = app(dir.path());
    let family = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let sleep = Uuid::new_v4().to_string();
    let wake = Uuid::new_v4().to_string();
    let media = Uuid::new_v4().to_string();
    let (batch, token) = start(&app1, &family).await;
    let sleep_row = json!({"type":"record","client_uuid":sleep,"updated_at":1000,"payload":{"baby_client_uuid":baby_id,"type":"sleep","custom_item_client_uuid":null,"timestamp":2000,"note":null,"payload_json":{"anomaly_flag":false,"is_nap":false},"schema_version":2,"effective_wake_observation_client_uuid":wake}});
    // A later root edit can put an existing independent observation before the current start.
    let wake_row = json!({"type":"wake_observation","client_uuid":wake,"updated_at":1000,"payload":{"sleep_record_client_uuid":sleep,"wake_timestamp":1500,"note":"retained observation","withdrawn":true,"observer_membership_id":null}});
    let original = b"synthetic-original-image-byte-identity";
    let hash = hex::encode(Sha256::digest(original));
    let photo = json!({"type":"media","client_uuid":media,"updated_at":1000,"payload":{"kind":"wake","record_client_uuid":wake,"baby_client_uuid":null,"care_plan_client_uuid":null,"mime":"image/jpeg","width":128,"height":256,"byte_size":original.len()}});
    let (status, value) = manifest(
        &app1,
        &batch,
        &token,
        vec![baby(&baby_id), sleep_row, wake_row, photo],
        json!([]),
        json!([{"client_uuid":media,"byte_size":original.len(),"sha256":hash}]),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{value}");
    let (status, value) = upload(&app1, &batch, &token, &media, original).await;
    assert_eq!(status, StatusCode::OK, "{value}");
    assert_eq!(value["status"], "ready_to_commit");
    let (status, receipt) = commit(&app1, &batch, &token).await;
    assert_eq!(status, StatusCode::OK, "{receipt}");
    assert_eq!(
        fs::read(dir.path().join("media").join(&family).join(&media)).unwrap(),
        original
    );
    let app2 = app(dir.path());
    let access = receipt["access_token"].as_str().unwrap();
    let (status, pull) = send(
        &app2,
        Method::GET,
        "/v1/pull?cursor=0&page_index=0&generation=reconstructed-generation",
        Some(access),
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{pull}");
    let photo = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == media)
        .unwrap();
    assert_eq!(
        photo["media_identity"],
        json!({"media_uuid":media,"role":"wake","sha256":hash,"byte_size":original.len()})
    );
    let (status, value) = send(
        &app2,
        Method::POST,
        "/v1/family/delete",
        Some(access),
        json!({"family_name":"合成家庭"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{value}");
    assert!(!dir.path().join("disaster-restore").join(&batch).exists());
    assert!(!dir.path().join("media").join(&family).exists());
    let (status, _) = commit(&app2, &batch, &token).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    let app3 = app(dir.path());
    let (status, _) = commit(&app3, &batch, &token).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}
#[tokio::test]
async fn restored_baby_supports_edits_then_conflict_with_adopting_owner_authorship() {
    let dir = TempDir::new().unwrap();
    let app = app(dir.path());
    let baby_id = Uuid::new_v4().to_string();
    let (batch, token) = start(&app, &Uuid::new_v4().to_string()).await;
    assert_eq!(
        manifest(
            &app,
            &batch,
            &token,
            vec![baby(&baby_id)],
            json!([]),
            json!([])
        )
        .await
        .0,
        StatusCode::OK
    );
    let (status, receipt) = commit(&app, &batch, &token).await;
    assert_eq!(status, StatusCode::OK, "{receipt}");
    let access = receipt["access_token"].as_str().unwrap();
    let (_, pull) = send(
        &app,
        Method::GET,
        "/v1/pull?cursor=0&page_index=0&generation=reconstructed-generation",
        Some(access),
        Value::Null,
    )
    .await;
    let row = pull["entities"]
        .as_array()
        .unwrap()
        .iter()
        .find(|e| e["client_uuid"] == baby_id)
        .unwrap();
    let base = row["version_id"].clone();
    let mut branch = Value::Null;
    for (nickname, updated) in [("first edit", 2000), ("second edit", 3000)] {
        let mut root = row["payload"].clone();
        root["nickname"] = json!(nickname);
        root["updated_at"] = json!(updated);
        let(status,result)=send(&app,Method::POST,"/v1/causal/commit",Some(access),json!({"generation":"reconstructed-generation","units":[{"mutation_id":Uuid::new_v4(),"base_version":base,"entity_type":"baby","client_uuid":baby_id,"root":root,"media":[],"deleted":false}]})).await;
        assert_eq!(status, StatusCode::OK, "{result}");
        assert!(result["results"].is_array(), "{result}");
        branch = result;
    }
    assert_eq!(branch["results"][0]["status"], "branched");
    let conflict = branch["results"][0]["conflict_id"].as_str().unwrap();
    let (status, detail) = send(
        &app,
        Method::GET,
        &format!("/v1/conflicts/{conflict}"),
        Some(access),
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{detail}");
    assert_eq!(
        detail["stable"]["root"]["created_by_membership_id"],
        receipt["membership_id"]
    );
    for branch in detail["branches"].as_array().unwrap() {
        assert_eq!(
            branch["root"]["created_by_membership_id"],
            receipt["membership_id"]
        );
        assert_eq!(branch["actor_id"], receipt["membership_id"]);
    }
}

#[tokio::test]
async fn restored_relations_creators_and_causal_baselines_survive_restart_and_replay() {
    check_restored_relations_creators_and_causal_baselines(false).await;
    check_restored_relations_creators_and_causal_baselines(true).await;
}
#[tokio::test]
async fn cross_baby_restore_fails_before_identity_or_facts_become_visible() {
    let dir = TempDir::new().unwrap();
    let app = app(dir.path());
    let a = Uuid::new_v4().to_string();
    let b = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let plan = Uuid::new_v4().to_string();
    let (batch, token) = start(&app, &Uuid::new_v4().to_string()).await;
    let plan = json!({"type":"care_plan","client_uuid":plan,"updated_at":1000,"payload":{"baby_client_uuid":a,"type":"formula","custom_item_client_uuid":null,"scheduled_at":1000,"scheduled_zone_id":"UTC","note":null,"status":"completed","payload_json":{"amount_ml":100},"schema_version":2,"created_by_membership_id":null,"fulfilled_record_client_uuid":record_id,"fulfilled_at":1000,"source_record_client_uuid":null}});
    let (status, result) = manifest(
        &app,
        &batch,
        &token,
        vec![
            baby(&a),
            baby(&b),
            record(&record_id, &b, "different baby"),
            plan,
        ],
        json!([]),
        json!([]),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{result}");
    let (status, result) = commit(&app, &batch, &token).await;
    assert_eq!(status, StatusCode::CONFLICT, "{result}");
    let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
    for table in [
        "families",
        "memberships",
        "devices",
        "device_sessions",
        "entities",
        "entity_versions",
        "entity_stable_heads",
    ] {
        assert_eq!(
            db.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0,
            "{table}"
        );
    }
    let _restarted = app_for_restart_check(dir.path());
}
fn app_for_restart_check(path: &Path) -> Router {
    app(path)
}

#[tokio::test]
async fn activated_restore_is_recovered_before_expiry_then_compacted_and_exactly_replayed() {
    let dir = TempDir::new().unwrap();
    let first = app(dir.path());
    let family = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4().to_string();
    let (batch, token) = start(&first, &family).await;
    let (status, response) = manifest(
        &first,
        &batch,
        &token,
        vec![baby(&baby_id)],
        json!([]),
        json!([]),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{response}");
    let batch_dir = dir.path().join("disaster-restore").join(&batch);
    let manifest_bytes = fs::read(batch_dir.join("manifest.json")).unwrap();
    let mut prepared: Value =
        serde_json::from_slice(&fs::read(batch_dir.join("journal.json")).unwrap()).unwrap();
    let (status, committed) = commit(&first, &batch, &token).await;
    assert_eq!(status, StatusCode::OK, "{committed}");
    // Recreate the precise durable interval: SQLite committed, but the journal
    // has only the commit identity saved immediately before activation.
    prepared["commit_request_id"] = json!("synthetic-commit-request-000000001");
    prepared["access_expires_at"] = committed["access_expires_at"].clone();
    fs::write(
        batch_dir.join("journal.json"),
        serde_json::to_vec(&prepared).unwrap(),
    )
    .unwrap();
    fs::write(batch_dir.join("manifest.json"), &manifest_bytes).unwrap();
    fs::write(
        batch_dir.join("obsolete-temporary-payload"),
        b"synthetic private historical payload",
    )
    .unwrap();
    drop(first);
    let restarted = app_at(dir.path(), 1_800_000_000 + 2 * 86_400);
    assert!(!batch_dir.join("manifest.json").exists());
    assert!(!batch_dir.join("obsolete-temporary-payload").exists());
    let (status, replay) = commit(&restarted, &batch, &token).await;
    assert_eq!(status, StatusCode::OK, "{replay}");
    assert_eq!(
        replay, committed,
        "expiry must not mint new credentials for a committed batch"
    );
    let (status, cancelled) = send(
        &restarted,
        Method::POST,
        &format!("/v1/disaster-restore/batches/{batch}/cancel"),
        Some(&token),
        Value::Null,
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT, "{cancelled}");
    let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM families", [], |r| r.get::<_, i64>(0))
            .unwrap(),
        1
    );
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM entity_versions", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        1
    );
}

#[tokio::test]
async fn preactivation_final_media_survives_restart_and_authenticated_cancel_retires_it() {
    for cancel in [false, true] {
        let dir = TempDir::new().unwrap();
        let first = app(dir.path());
        let family = Uuid::new_v4().to_string();
        let baby_id = Uuid::new_v4().to_string();
        let record_id = Uuid::new_v4().to_string();
        let media = Uuid::new_v4().to_string();
        let original = b"original synthetic image";
        let hash = hex::encode(Sha256::digest(original));
        let photo = json!({"type":"media","client_uuid":media,"updated_at":1000,"payload":{"kind":"log","record_client_uuid":record_id,"baby_client_uuid":null,"care_plan_client_uuid":null,"mime":"image/jpeg","width":null,"height":null,"byte_size":original.len()}});
        let (batch, token) = start(&first, &family).await;
        let (status, response) = manifest(
            &first,
            &batch,
            &token,
            vec![
                baby(&baby_id),
                record(&record_id, &baby_id, "retained until outcome"),
                photo,
            ],
            json!([]),
            json!([{"client_uuid":media,"byte_size":original.len(),"sha256":hash}]),
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{response}");
        assert_eq!(
            upload(&first, &batch, &token, &media, original).await.0,
            StatusCode::OK
        );
        let batch_dir = dir.path().join("disaster-restore").join(&batch);
        let final_dir = dir.path().join("media").join(&family);
        fs::create_dir_all(&final_dir).unwrap();
        fs::rename(batch_dir.join("media").join(&media), final_dir.join(&media)).unwrap();
        drop(first);
        let restarted = app(dir.path());
        assert_eq!(fs::read(final_dir.join(&media)).unwrap(), original);
        if cancel {
            let (status, response) = send(
                &restarted,
                Method::POST,
                &format!("/v1/disaster-restore/batches/{batch}/cancel"),
                Some(&token),
                Value::Null,
            )
            .await;
            assert_eq!(status, StatusCode::OK, "{response}");
            assert_eq!(commit(&restarted, &batch, &token).await.0, StatusCode::GONE);
            drop(restarted);
            let _expired = app_at(dir.path(), 1_800_000_000 + 2 * 86_400);
            assert!(!batch_dir.exists());
            assert!(!final_dir.exists());
            let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
            assert_eq!(
                db.query_row("SELECT COUNT(*) FROM families", [], |r| r.get::<_, i64>(0))
                    .unwrap(),
                0
            );
        } else {
            let (status, response) = commit(&restarted, &batch, &token).await;
            assert_eq!(status, StatusCode::OK, "{response}");
            assert_eq!(fs::read(final_dir.join(&media)).unwrap(), original);
            assert!(!batch_dir.join("manifest.json").exists());
        }
    }
}

#[tokio::test]
async fn restore_rejects_wrong_avatar_candidate_and_over_cap_graphs_atomically() {
    for case in ["avatar_owner", "candidate_baby", "media_cap"] {
        let dir = TempDir::new().unwrap();
        let server = app(dir.path());
        let family = Uuid::new_v4().to_string();
        let a = Uuid::new_v4().to_string();
        let b = Uuid::new_v4().to_string();
        let record_id = Uuid::new_v4().to_string();
        let plan = Uuid::new_v4().to_string();
        let photo_id = Uuid::new_v4().to_string();
        let original = b"four";
        let hash = hex::encode(Sha256::digest(original));
        let mut roots = vec![baby(&a), baby(&b), record(&record_id, &b, "synthetic")];
        let mut specs = Vec::new();
        if case == "candidate_baby" {
            roots.push(json!({"type":"care_plan","client_uuid":plan,"updated_at":1000,"payload":{"baby_client_uuid":a,"type":"formula","custom_item_client_uuid":null,"scheduled_at":1000,"scheduled_zone_id":"UTC","note":null,"status":"pending","payload_json":{"amount_ml":100},"schema_version":2,"created_by_membership_id":null,"fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null}}));
            roots.push(json!({"type":"fulfillment_candidate","client_uuid":Uuid::new_v4(),"updated_at":1000,"payload":{"care_plan_client_uuid":plan,"record_client_uuid":record_id,"actual_timestamp":1000,"submitter_membership_id":null,"submitter_role":"owner","confirmed_at":1000}}));
        } else {
            let count = if case == "media_cap" { 4 } else { 1 };
            if case == "avatar_owner" {
                roots[0]["payload"]["avatar_media_uuid"] = json!(photo_id);
            }
            for index in 0..count {
                let media = if index == 0 {
                    photo_id.clone()
                } else {
                    Uuid::new_v4().to_string()
                };
                let payload = if case == "avatar_owner" {
                    json!({"kind":"avatar","baby_client_uuid":b,"record_client_uuid":null,"care_plan_client_uuid":null,"mime":"image/jpeg","width":1,"height":1,"byte_size":original.len()})
                } else {
                    json!({"kind":"log","baby_client_uuid":null,"record_client_uuid":record_id,"care_plan_client_uuid":null,"mime":"image/jpeg","width":1,"height":1,"byte_size":original.len()})
                };
                roots.push(
                    json!({"type":"media","client_uuid":media,"updated_at":1000,"payload":payload}),
                );
                specs.push(json!({"client_uuid":media,"byte_size":original.len(),"sha256":hash}));
            }
        }
        let (batch, token) = start(&server, &family).await;
        let (status, response) =
            manifest(&server, &batch, &token, roots, json!([]), json!(specs)).await;
        assert_eq!(status, StatusCode::OK, "{case}: {response}");
        for spec in &specs {
            let (status, response) = upload(
                &server,
                &batch,
                &token,
                spec["client_uuid"].as_str().unwrap(),
                original,
            )
            .await;
            assert_eq!(status, StatusCode::OK, "{case}: {response}");
        }
        let (status, response) = commit(&server, &batch, &token).await;
        assert_eq!(status, StatusCode::CONFLICT, "{case}: {response}");
        let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
        for table in [
            "families",
            "memberships",
            "devices",
            "device_sessions",
            "entities",
            "entity_versions",
            "entity_version_media",
            "entity_stable_heads",
            "media_publications",
            "mutation_receipts",
        ] {
            assert_eq!(
                db.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r
                    .get::<_, i64>(0))
                    .unwrap(),
                0,
                "{case}: {table}"
            );
        }
        drop(server);
        let restarted = app(dir.path());
        let (status, response) = send(
            &restarted,
            Method::GET,
            "/v1/setup-status",
            None,
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{case}: {response}");
        assert_eq!(response["family_state"], "empty");
    }
}

#[cfg(unix)]
#[tokio::test]
async fn retirement_permission_failure_never_reports_success_and_restart_retries_owned_files() {
    use std::os::unix::fs::PermissionsExt;
    if unsafe { libc::geteuid() } == 0 {
        eprintln!("permission-denial probe requires an unprivileged test process");
        return;
    }
    struct RestoreMode(std::path::PathBuf);
    impl Drop for RestoreMode {
        fn drop(&mut self) {
            let _ = fs::set_permissions(&self.0, fs::Permissions::from_mode(0o700));
        }
    }
    for delete_family in [false, true] {
        let dir = TempDir::new().unwrap();
        let first = app(dir.path());
        let family = Uuid::new_v4().to_string();
        let (batch, token) = start(&first, &family).await;
        assert_eq!(
            manifest(
                &first,
                &batch,
                &token,
                vec![baby(&Uuid::new_v4().to_string())],
                json!([]),
                json!([])
            )
            .await
            .0,
            StatusCode::OK
        );
        let committed = if delete_family {
            let (status, receipt) = commit(&first, &batch, &token).await;
            assert_eq!(status, StatusCode::OK, "{receipt}");
            Some(receipt)
        } else {
            None
        };
        let batch_dir = dir.path().join("disaster-restore").join(&batch);
        let denied = batch_dir.join("retirement-permission-probe");
        fs::create_dir(&denied).unwrap();
        fs::write(
            denied.join("synthetic-payload"),
            b"private synthetic copied content",
        )
        .unwrap();
        let mode = RestoreMode(denied.clone());
        fs::set_permissions(&denied, fs::Permissions::from_mode(0o000)).unwrap();
        let (status, response) = if let Some(receipt) = &committed {
            send(
                &first,
                Method::POST,
                "/v1/family/delete",
                receipt["access_token"].as_str(),
                json!({"family_name":"合成家庭"}),
            )
            .await
        } else {
            commit(&first, &batch, &token).await
        };
        assert_eq!(
            status,
            StatusCode::INTERNAL_SERVER_ERROR,
            "{delete_family}: {response}"
        );
        let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM families", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            i64::from(!delete_family)
        );
        assert!(
            denied.exists(),
            "durable cleanup evidence must remain after failure"
        );
        drop(first);
        let mut blocked_config = ServerConfig::new(dir.path());
        blocked_config.bootstrap_secret = Some(ROOT.to_owned());
        blocked_config.generation = Some("reconstructed-generation".to_owned());
        assert!(
            build_app(blocked_config.with_clock(|| 1_800_000_000)).is_err(),
            "startup cannot claim readiness while required retirement still fails"
        );
        fs::set_permissions(&denied, fs::Permissions::from_mode(0o700)).unwrap();
        drop(mode);
        let restarted = app(dir.path());
        assert!(!denied.exists());
        let (status, replay) = commit(&restarted, &batch, &token).await;
        if delete_family {
            assert_eq!(status, StatusCode::UNAUTHORIZED, "{replay}");
            assert!(!batch_dir.exists());
        } else {
            assert_eq!(status, StatusCode::OK, "{replay}");
            assert_eq!(commit(&restarted, &batch, &token).await.1, replay);
            assert!(!batch_dir.join("manifest.json").exists());
        }
    }
}

#[tokio::test]
async fn new_member_cannot_overwrite_a_restored_record_by_claiming_it_is_new() {
    let dir = TempDir::new().unwrap();
    let server = app(dir.path());
    let baby_id = Uuid::new_v4().to_string();
    let record_id = Uuid::new_v4().to_string();
    let (batch, token) = start(&server, &Uuid::new_v4().to_string()).await;
    assert_eq!(
        manifest(
            &server,
            &batch,
            &token,
            vec![
                baby(&baby_id),
                record(&record_id, &baby_id, "original care")
            ],
            json!([]),
            json!([])
        )
        .await
        .0,
        StatusCode::OK
    );
    let (status, owner) = commit(&server, &batch, &token).await;
    assert_eq!(status, StatusCode::OK, "{owner}");
    let (status, pending) = send(
        &server,
        Method::POST,
        "/v1/member/requests",
        None,
        json!({"display_name":"合成成员","device_name":"合成新设备"}),
    )
    .await;
    assert_eq!(status, StatusCode::CREATED, "{pending}");
    let (status, approval) = send(
        &server,
        Method::POST,
        &format!(
            "/v1/member/requests/{}/approve-new",
            pending["request_id"].as_str().unwrap()
        ),
        owner["access_token"].as_str(),
        json!({}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{approval}");
    let (status, member) = send(
        &server,
        Method::POST,
        "/v1/member/requests/claim",
        None,
        json!({"pending_secret":pending["pending_secret"]}),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{member}");
    let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
    let before_receipts: i64 = db
        .query_row(
            "SELECT COUNT(*) FROM mutation_receipts WHERE client_uuid=?1",
            [&record_id],
            |r| r.get(0),
        )
        .unwrap();
    let before: String = db
        .query_row(
            "SELECT payload_json FROM entities WHERE entity_type='record' AND client_uuid=?1",
            [&record_id],
            |r| r.get(0),
        )
        .unwrap();
    let mut root: Value = serde_json::from_str(&before).unwrap();
    root["note"] = json!("must not replace original");
    root["updated_at"] = json!(2000);
    let (status, rejection) = send(&server, Method::POST, "/v1/causal/commit", member["access_token"].as_str(), json!({"generation":"reconstructed-generation","units":[{"mutation_id":Uuid::new_v4(),"base_version":null,"entity_type":"record","client_uuid":record_id,"root":root,"media":[],"deleted":false}]})).await;
    assert_eq!(status, StatusCode::OK, "{rejection}");
    assert_eq!(rejection["status"], "rejected");
    assert_eq!(rejection["error"]["code"], "forbidden");
    assert_eq!(
        db.query_row(
            "SELECT payload_json FROM entities WHERE entity_type='record' AND client_uuid=?1",
            [&record_id],
            |r| r.get::<_, String>(0)
        )
        .unwrap(),
        before
    );
    assert_eq!(
        db.query_row(
            "SELECT COUNT(*) FROM entity_versions WHERE client_uuid=?1",
            [&record_id],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        1
    );
    assert_eq!(
        db.query_row(
            "SELECT COUNT(*) FROM mutation_receipts WHERE client_uuid=?1",
            [&record_id],
            |r| r.get::<_, i64>(0)
        )
        .unwrap(),
        before_receipts
    );
}

// US-030: separate publication paths; each cell closes every Router before reopening
// the durable TempDir. This does not exercise family deletion or retirement races.
#[derive(Clone, Copy, Debug, PartialEq)]
enum WakeRestartCase {
    Text,
    Photo,
    Withdrawn,
    MediaTombstone,
}

async fn accepted_wake_unit(
    server: &Router,
    access: &str,
    row: &Value,
    base: Value,
    media: Value,
) -> Value {
    let mut root = row["payload"].clone();
    root["updated_at"] = row["updated_at"].clone();
    let (status, result) = send(
        server,
        Method::POST,
        "/v1/causal/commit",
        Some(access),
        json!({
            "generation":"reconstructed-generation",
            "units":[{"mutation_id":Uuid::new_v4(),"base_version":base,
              "entity_type":row["type"],"client_uuid":row["client_uuid"],
              "root":root,"media":media,"deleted":false}]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{result}");
    assert_eq!(result["results"][0]["status"], "accepted", "{result}");
    result["results"][0]["stable"]["version_id"].clone()
}

async fn check_wake_restart_matrix(restoring: bool) {
    for case in [
        WakeRestartCase::Text,
        WakeRestartCase::Photo,
        WakeRestartCase::Withdrawn,
        WakeRestartCase::MediaTombstone,
    ] {
        let dir = TempDir::new().unwrap();
        let server = app(dir.path());
        let baby_id = Uuid::new_v4().to_string();
        let sleep_id = Uuid::new_v4().to_string();
        let wake_id = Uuid::new_v4().to_string();
        let media_id = Uuid::new_v4().to_string();
        let bytes = b"synthetic-wake-restart-original";
        let hash = hex::encode(Sha256::digest(bytes));
        let with_photo = case != WakeRestartCase::Text;
        let tombstone = case == WakeRestartCase::MediaTombstone;
        let sleep = json!({"type":"record","client_uuid":sleep_id,"updated_at":1000,"payload":{
            "baby_client_uuid":baby_id,"type":"sleep","custom_item_client_uuid":null,
            "timestamp":100,"note":null,"payload_json":{"anomaly_flag":false,"is_nap":false},
            "schema_version":2,"effective_wake_observation_client_uuid":null}});
        let mut wake = json!({"type":"wake_observation","client_uuid":wake_id,"updated_at":1001,"payload":{
            "sleep_record_client_uuid":sleep_id,"wake_timestamp":200,"note":"restart evidence",
            "withdrawn":case == WakeRestartCase::Withdrawn}});
        let photo = json!({"type":"media","client_uuid":media_id,"updated_at":1002,
            "deleted_at":null, "payload":{
            "kind":"wake","record_client_uuid":wake_id,"baby_client_uuid":null,"care_plan_client_uuid":null,
            "mime":"image/jpeg","width":1,"height":1,"byte_size":bytes.len()}});
        let (access, family, owner) = if restoring {
            let family = Uuid::new_v4().to_string();
            let (batch, token) = start(&server, &family).await;
            let mut rows = vec![baby(&baby_id), sleep, wake.clone()];
            if with_photo {
                rows.push(photo);
            }
            let media = if with_photo {
                json!([{"client_uuid":media_id,"byte_size":bytes.len(),"sha256":hash}])
            } else {
                json!([])
            };
            let (status, result) = manifest(&server, &batch, &token, rows, json!([]), media).await;
            assert_eq!(status, StatusCode::OK, "restore {case:?}: {result}");
            if with_photo {
                let (status, result) = upload(&server, &batch, &token, &media_id, bytes).await;
                assert_eq!(status, StatusCode::OK, "{result}");
            }
            let (status, receipt) = commit(&server, &batch, &token).await;
            assert_eq!(status, StatusCode::OK, "restore {case:?}: {receipt}");
            if tombstone {
                // Direct media tombstone import is deliberately unsupported. Establish
                // the restored baseline, then remove its photo through ordinary CAS.
                let access = receipt["access_token"].as_str().unwrap();
                let (status, pulled) = send(
                    &server,
                    Method::GET,
                    "/v1/pull?cursor=0&page_index=0&generation=reconstructed-generation",
                    Some(access),
                    Value::Null,
                )
                .await;
                assert_eq!(status, StatusCode::OK, "{pulled}");
                let mut restored = pulled["entities"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .find(|row| row["client_uuid"] == wake_id)
                    .unwrap()
                    .clone();
                restored["updated_at"] = json!(1003);
                accepted_wake_unit(
                    &server,
                    access,
                    &restored,
                    restored["version_id"].clone(),
                    json!([]),
                )
                .await;
            }
            (
                receipt["access_token"].as_str().unwrap().to_owned(),
                family,
                receipt["membership_id"].clone(),
            )
        } else {
            let (status, session) = send(
                &server,
                Method::POST,
                "/v1/family/create",
                None,
                json!({
                "create_request_id":Uuid::new_v4(),"display_name":"Owner",
                "device_name":"synthetic restart","family_name":"synthetic restart"}),
            )
            .await;
            assert_eq!(status, StatusCode::CREATED, "{session}");
            let access = session["access_token"].as_str().unwrap().to_owned();
            accepted_wake_unit(&server, &access, &baby(&baby_id), Value::Null, json!([])).await;
            accepted_wake_unit(&server, &access, &sleep, Value::Null, json!([])).await;
            let mut attachments = json!([]);
            if with_photo {
                let mut request = Request::builder()
                    .method(Method::PUT)
                    .uri(format!("/v1/causal/media/{media_id}"))
                    .header("authorization", format!("Bearer {access}"))
                    .header("x-lezi-client-version-code", "35")
                    .header(
                        "x-lezi-sync-capabilities",
                        "nursing_plan_intent_v1,restore_authority_v1",
                    )
                    .header("x-lezi-media-sha256", &hash)
                    .body(Body::from(bytes.to_vec()))
                    .unwrap();
                request
                    .extensions_mut()
                    .insert(ConnectInfo(SocketAddr::from((Ipv4Addr::LOCALHOST, 32111))));
                let response = server.clone().oneshot(request).await.unwrap();
                let status = response.status();
                let body = response.into_body().collect().await.unwrap().to_bytes();
                assert_eq!(status, StatusCode::OK, "{}", String::from_utf8_lossy(&body));
                attachments = json!([{"media_uuid":media_id,"role":"wake","sha256":hash,
                    "byte_size":bytes.len(),"mime":"image/jpeg","width":1,"height":1}]);
            }
            let mut initial_wake = wake.clone();
            initial_wake["payload"]["withdrawn"] = json!(false);
            let base = accepted_wake_unit(
                &server,
                &access,
                &initial_wake,
                Value::Null,
                attachments.clone(),
            )
            .await;
            if tombstone || case == WakeRestartCase::Withdrawn {
                wake["updated_at"] = json!(1002);
                accepted_wake_unit(
                    &server,
                    &access,
                    &wake,
                    base,
                    if tombstone { json!([]) } else { attachments },
                )
                .await;
            }
            (
                access,
                session["family_id"].as_str().unwrap().to_owned(),
                session["membership_id"].clone(),
            )
        };
        drop(server);
        let reopened = app(dir.path());
        let (status, ready) = send(&reopened, Method::GET, "/ready", None, Value::Null).await;
        assert_eq!(
            status,
            StatusCode::OK,
            "restore={restoring} {case:?}: {ready}"
        );
        let (status, pulled) = send(
            &reopened,
            Method::GET,
            "/v1/pull?cursor=0&page_index=0&generation=reconstructed-generation",
            Some(&access),
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{pulled}");
        let entities = pulled["entities"].as_array().unwrap();
        let row = entities
            .iter()
            .find(|row| row["client_uuid"] == wake_id)
            .unwrap();
        assert_eq!(row["payload"]["sleep_record_client_uuid"], sleep_id);
        assert_eq!(row["payload"]["note"], "restart evidence");
        assert_eq!(
            row["payload"]["withdrawn"],
            case == WakeRestartCase::Withdrawn
        );
        assert_eq!(row["payload"]["observer_membership_id"], owner);
        assert!(row["version_id"].is_string());
        if with_photo {
            let photo = entities
                .iter()
                .find(|row| row["client_uuid"] == media_id)
                .unwrap();
            assert_eq!(photo["payload"]["kind"], "wake");
            assert_eq!(photo["payload"]["record_client_uuid"], wake_id);
            assert_eq!(photo["deleted_at"].is_number(), tombstone);
            if !tombstone {
                assert_eq!(
                    photo["media_identity"],
                    json!({"media_uuid":media_id,"role":"wake","sha256":hash,"byte_size":bytes.len()})
                );
                assert_eq!(
                    fs::read(dir.path().join("media").join(&family).join(&media_id)).unwrap(),
                    bytes
                );
            }
        }
        if case == WakeRestartCase::Photo {
            // Corrupt only this synthetic persisted projection after proving its
            // legal owner succeeds. Startup must still reject unknown/wrong owners.
            drop(reopened);
            let db = rusqlite::Connection::open(dir.path().join("lezi.db")).unwrap();
            let original: String = db.query_row(
                "SELECT payload_json FROM entities WHERE entity_type='media' AND client_uuid=?1",
                [&media_id], |row| row.get(0)).unwrap();
            for invalid in ["unknown_kind", "wrong_parent_type", "missing_parent"] {
                let mut payload: Value = serde_json::from_str(&original).unwrap();
                match invalid {
                    "unknown_kind" => payload["kind"] = json!("unknown"),
                    "wrong_parent_type" => payload["record_client_uuid"] = json!(sleep_id),
                    "missing_parent" => payload["record_client_uuid"] = json!(Uuid::new_v4()),
                    _ => unreachable!(),
                }
                db.execute("UPDATE entities SET payload_json=?1 WHERE entity_type='media' AND client_uuid=?2",
                    rusqlite::params![payload.to_string(), media_id]).unwrap();
                let mut config = ServerConfig::new(dir.path());
                config.bootstrap_secret = Some(ROOT.to_owned());
                config.generation = Some("reconstructed-generation".to_owned());
                assert!(
                    build_app(config).is_err(),
                    "restore={restoring}: {invalid} admitted at startup"
                );
            }
        }
    }
}

#[tokio::test]
async fn ordinary_wake_text_photo_withdrawal_and_media_tombstone_restart_ready_pull() {
    check_wake_restart_matrix(false).await;
}

#[tokio::test]
async fn restored_wake_text_photo_withdrawal_and_media_tombstone_restart_ready_pull() {
    check_wake_restart_matrix(true).await;
}

#[tokio::test]
async fn direct_restore_media_tombstones_fail_historical_context_validation() {
    let dir = TempDir::new().unwrap();
    let server = app(dir.path());
    let baby_id = Uuid::new_v4().to_string();
    let sleep_id = Uuid::new_v4().to_string();
    let wake_id = Uuid::new_v4().to_string();
    let media_id = Uuid::new_v4().to_string();
    let (batch, token) = start(&server, &Uuid::new_v4().to_string()).await;
    let sleep = json!({"type":"record","client_uuid":sleep_id,"updated_at":1000,"payload":{
        "baby_client_uuid":baby_id,"type":"sleep","custom_item_client_uuid":null,
        "timestamp":100,"note":null,"payload_json":{"anomaly_flag":false,"is_nap":false},
        "schema_version":2,"effective_wake_observation_client_uuid":null}});
    let wake = json!({"type":"wake_observation","client_uuid":wake_id,"updated_at":1001,"payload":{
        "sleep_record_client_uuid":sleep_id,"wake_timestamp":200,"note":null,"withdrawn":false}});
    let media = json!({"type":"media","client_uuid":media_id,"updated_at":1002,"deleted_at":1002,"payload":{
        "kind":"wake","record_client_uuid":wake_id,"baby_client_uuid":null,"care_plan_client_uuid":null,
        "mime":"image/jpeg","width":1,"height":1,"byte_size":32}});
    let (status, error) = manifest(
        &server,
        &batch,
        &token,
        vec![baby(&baby_id), sleep, wake, media],
        json!([]),
        json!([]),
    )
    .await;
    assert_eq!(status, StatusCode::UNPROCESSABLE_ENTITY, "{error}");
    assert_eq!(
        error["detail"],
        "restore tombstone is not retained source-relation history"
    );
    assert!(error.get("code").is_none());
    assert!(!dir
        .path()
        .join("disaster-restore")
        .join(batch)
        .join("manifest.json")
        .exists());
    drop(server);
    let reopened = app(dir.path());
    assert_eq!(
        send(&reopened, Method::GET, "/ready", None, Value::Null)
            .await
            .0,
        StatusCode::OK
    );
}
