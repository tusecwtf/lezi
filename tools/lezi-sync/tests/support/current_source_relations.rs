#[tokio::test]
async fn current_source_relations_returns_complete_sorted_closure_without_advancing_head() {
    let rig = Rig::new();
    let owner = create_family(&rig.app, "current-sources-owner", "current-sources-create-00000000000001").await;
    let token = owner["access_token"].as_str().unwrap();
    let family = owner["family_id"].as_str().unwrap();
    let baby = seed_causal_baby(&rig.app, token).await;
    let first = Uuid::new_v4();
    let second = Uuid::new_v4();
    for (id, time) in [(first, 20), (second, 30)] {
        let (status, body) = causal_commit_units(&rig.app, token, vec![causal_unit(
            Uuid::new_v4(), None, "record", id,
            causal_formula_root(baby, "same observation", 80, time), vec![], false,
        )]).await;
        assert_eq!(status, StatusCode::OK, "{body}");
    }
    let missing = Uuid::new_v4();
    let request = json!({"protocol_version":1,"family_id":family,"generation":"generation-a","record_client_uuids":[missing,second]});
    let connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let before: i64 = connection.query_row("SELECT rev FROM family_meta WHERE family_id=?1", [family], |row| row.get(0)).unwrap();
    let (status, body) = json_request(&rig.app, Method::POST, "/v1/source-relations/current", Some(token), request.clone()).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["head_rev"], before);
    assert_eq!(body["source_relations"].as_array().unwrap().len(), 1);
    assert_eq!(body["source_relations"][0]["auto_aligned"], true);
    assert_eq!(body["source_relations"][0]["media_retained"], true);
    let records = body["records"].as_array().unwrap();
    assert_eq!(records.len(), 3);
    let ids = records.iter().map(|row| row["record_client_uuid"].as_str().unwrap()).collect::<Vec<_>>();
    assert!(ids.windows(2).all(|pair| pair[0] < pair[1]));
    let absent = records.iter().find(|row| row["record_client_uuid"] == missing.to_string()).unwrap();
    assert_eq!(absent["record_state"], "missing");
    assert!(absent["relation_id"].is_null());
    let again = json_request(&rig.app, Method::POST, "/v1/source-relations/current", Some(token), request.clone()).await;
    assert_eq!(again.1, body);
    let after: i64 = connection.query_row("SELECT rev FROM family_meta WHERE family_id=?1", [family], |row| row.get(0)).unwrap();
    assert_eq!(after, before);

    // Deleted membership of a group is distinct from group existence.
    connection.execute("UPDATE entities SET deleted_at=100 WHERE family_id=?1 AND entity_type='record' AND client_uuid=?2", params![family,first.to_string()]).unwrap();
    let deleted = json_request(&rig.app, Method::POST, "/v1/source-relations/current", Some(token), request.clone()).await;
    assert_eq!(deleted.0, StatusCode::OK, "{}", deleted.1);
    assert_eq!(deleted.1["records"].as_array().unwrap().iter().find(|row| row["record_client_uuid"] == first.to_string()).unwrap()["record_state"], "deleted");

    // A missing group member invalidates the whole closure, never a partial response.
    connection.execute("DELETE FROM entities WHERE family_id=?1 AND entity_type='record' AND client_uuid=?2", params![family,first.to_string()]).unwrap();
    let invalid = json_request(&rig.app, Method::POST, "/v1/source-relations/current", Some(token), request).await;
    assert_eq!(invalid.0, StatusCode::CONFLICT, "{}", invalid.1);
    assert_eq!(invalid.1["code"], "source_relation_projection_invalid");
}

#[tokio::test]
async fn current_source_relations_rejects_invalid_scopes_and_preserves_missing_proofs() {
    let rig = Rig::new();
    let owner = create_family(&rig.app, "current-scope-owner", "current-scope-create-000000000000001").await;
    let token = owner["access_token"].as_str().unwrap();
    let family = owner["family_id"].as_str().unwrap();
    let uuid = Uuid::new_v4();
    let valid = json!({"protocol_version":1,"family_id":family,"generation":"generation-a","record_client_uuids":[uuid]});
    let (status, body) = json_request(&rig.app, Method::POST, "/v1/source-relations/current", Some(token), valid.clone()).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["source_relations"], json!([]));
    assert_eq!(body["records"], json!([{"record_client_uuid":uuid,"record_state":"missing","relation_id":null}]));
    for (key, value, expected, code) in [
        ("protocol_version", json!(2), StatusCode::UNPROCESSABLE_ENTITY, "source_relation_protocol_unsupported"),
        ("family_id", json!(Uuid::new_v4()), StatusCode::FORBIDDEN, "source_relation_family_mismatch"),
        ("generation", json!("other-generation"), StatusCode::CONFLICT, "generation_mismatch"),
        ("record_client_uuids", json!([]), StatusCode::UNPROCESSABLE_ENTITY, "invalid_source_relation_scope"),
        ("record_client_uuids", json!([uuid,uuid]), StatusCode::UNPROCESSABLE_ENTITY, "invalid_source_relation_scope"),
        ("record_client_uuids", json!([uuid.to_string().to_uppercase()]), StatusCode::UNPROCESSABLE_ENTITY, "invalid_source_relation_scope"),
        ("record_client_uuids", json!((0..65).map(|_| Uuid::new_v4()).collect::<Vec<_>>()), StatusCode::UNPROCESSABLE_ENTITY, "invalid_source_relation_scope"),
        ("unknown", json!(true), StatusCode::UNPROCESSABLE_ENTITY, "invalid_source_relation_scope"),
    ] {
        let mut request = valid.clone(); request[key] = value;
        let (status, body) = json_request(&rig.app, Method::POST, "/v1/source-relations/current", Some(token), request).await;
        assert_eq!(status, expected, "{body}"); assert_eq!(body["code"], code);
    }
    let mut large = valid.clone(); large["generation"] = json!("x".repeat(64*1024));
    let (status, body) = json_request(&rig.app, Method::POST, "/v1/source-relations/current", Some(token), large).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE, "{body}");
    assert_eq!(body["code"], "source_relation_projection_limit_exceeded");
    let (status, _) = json_request(&rig.app, Method::POST, "/v1/source-relations/current", None, valid).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}
