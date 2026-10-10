//! Full-manifest fixtures shared by semantic regressions and the opt-in timing probe.
use super::*;

fn id(value: usize) -> String {
    Uuid::from_u128(value as u128).to_string()
}

fn manifest(pairs: usize, with_wakes: bool) -> Value {
    let mut entities = vec![json!({
        "type": "baby", "client_uuid": id(1), "updated_at": 1000,
        "payload": {"nickname": "宝宝", "sex": "female", "birthday": "2025-01-02",
                    "avatar_media_uuid": null, "birth_weight_grams": 3200}
    })];
    for n in 0..pairs {
        entities.push(json!({
            "type": "record", "client_uuid": id(2 + n), "updated_at": 1001,
            "payload": {"baby_client_uuid": id(1), "type": "sleep",
                "custom_item_client_uuid": null, "timestamp": 100, "note": null,
                "payload_json": {"anomaly_flag": false}, "schema_version": 2,
                "effective_wake_observation_client_uuid": with_wakes.then(|| id(2 + pairs + n))}
        }));
    }
    if with_wakes {
        for n in 0..pairs {
            entities.push(json!({
                "type": "wake_observation", "client_uuid": id(2 + pairs + n), "updated_at": 1002,
                "payload": {"sleep_record_client_uuid": id(2 + n), "wake_timestamp": 200,
                    "note": null, "withdrawn": false, "observer_membership_id": "old-observer"}
            }));
        }
    }
    json!({"restore_authority": "v1", "request_id": "restore-reference-test-request-0001",
        "entities": entities, "source_relations": [], "media": []})
}

fn validate(value: Value) -> Result<ValidatedRestoreManifest, ApiError> {
    validate_manifest(serde_json::from_value(value).unwrap(), 1024)
}

fn assert_error(value: Value, detail: &str) {
    let error = validate(value).unwrap_err();
    assert_eq!(error.status, StatusCode::UNPROCESSABLE_ENTITY);
    assert_eq!(error.code, None);
    assert_eq!(error.detail, json!(detail));
}

#[test]
fn wake_reference_manifest_preserves_types_order_nullable_and_history() {
    for with_wakes in [false, true] {
        let input = manifest(2, with_wakes);
        let output = validate(input.clone()).unwrap();
        let expected_entities: Vec<Entity> = input["entities"]
            .as_array()
            .unwrap()
            .iter()
            .map(|raw| {
                serde_json::from_value::<RawEntity>(raw.clone())
                    .unwrap()
                    .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                    .unwrap()
            })
            .collect();
        assert_eq!(
            serde_json::to_value(output).unwrap(),
            json!({
                "request_id": input["request_id"], "entities": expected_entities,
                "source_relations": [], "media": []
            })
        );
    }
    // A UUID shared across types must not select the baby instead of the sleep.
    let mut same_id = manifest(1, true);
    same_id["entities"][0]["client_uuid"] = json!(id(2));
    same_id["entities"][1]["payload"]["baby_client_uuid"] = json!(id(2));
    validate(same_id).unwrap();

    let mut wake_media = manifest(1, true);
    wake_media["entities"].as_array_mut().unwrap().push(json!({
        "type": "media", "client_uuid": id(10), "updated_at": 1003,
        "payload": {"kind": "wake", "record_client_uuid": id(3),
            "mime": "image/jpeg", "byte_size": 3}
    }));
    wake_media["media"] = json!([{"client_uuid": id(10), "byte_size": 3,
        "sha256": "00".repeat(32)}]);
    let output = validate(wake_media).unwrap();
    assert_eq!(output.entities.len(), 4);
    assert_eq!(output.media.len(), 1);

    // Retained historical sleep/wake tombstones remain valid reference targets.
    let mut historical = manifest(2, true);
    historical["source_relations"] = json!([{"relation_id": id(100),
        "display_client_uuid": id(2), "source_client_uuids": [id(3)], "auto_aligned": false}]);
    historical["entities"][2]["deleted_at"] = json!(1100);
    historical["entities"][4]["deleted_at"] = json!(1100);
    validate(historical).unwrap();
}

#[test]
fn wake_reference_manifest_keeps_missing_and_wrong_target_errors() {
    let mut missing_sleep = manifest(1, true);
    missing_sleep["entities"].as_array_mut().unwrap().remove(1);
    assert_error(
        missing_sleep,
        "wake_observation sleep_record_client_uuid does not exist",
    );

    let mut wrong_sleep = manifest(1, true);
    wrong_sleep["entities"][1]["payload"] = json!({"baby_client_uuid": id(1),
        "type": "formula", "custom_item_client_uuid": null, "timestamp": 100,
        "end_timestamp": null, "note": null, "payload_json": {"amount_ml": 120}, "schema_version": 2});
    assert_error(
        wrong_sleep,
        "wake_observation must reference a live sleep record",
    );

    let mut missing_wake = manifest(1, true);
    missing_wake["entities"].as_array_mut().unwrap().pop();
    assert_error(missing_wake, "effective WakeObservation does not exist");

    let mut wrong_wake = manifest(2, true);
    wrong_wake["entities"][1]["payload"]["effective_wake_observation_client_uuid"] = json!(id(5));
    assert_error(
        wrong_wake,
        "effective WakeObservation is not valid for this sleep",
    );

    for target in [id(99), id(2)] {
        let mut missing_media_wake = manifest(1, true);
        missing_media_wake["entities"]
            .as_array_mut()
            .unwrap()
            .push(json!({
                "type": "media", "client_uuid": id(10), "updated_at": 1003,
                "payload": {"kind": "wake", "record_client_uuid": target,
                    "mime": "image/jpeg", "byte_size": 3}
            }));
        // Missing media specs must not mask the earlier reference failure.
        assert_error(
            missing_media_wake,
            "wake media record_client_uuid does not exist",
        );
    }
}

#[test]
fn wake_reference_manifest_keeps_first_error_precedence() {
    let mut duplicate = manifest(1, true);
    let first = duplicate["entities"][0].clone();
    duplicate["entities"]
        .as_array_mut()
        .unwrap()
        .insert(1, first);
    duplicate["entities"][2]["updated_at"] = json!(-1);
    assert_error(duplicate, "restore entity keys must be unique");

    // Raw UUID deserialization canonicalizes case before duplicate detection.
    let mut normalized_duplicate = manifest(1, true);
    normalized_duplicate["entities"][0]["client_uuid"] = json!(id(0xabcdef));
    let mut same_key = normalized_duplicate["entities"][0].clone();
    same_key["client_uuid"] = json!(id(0xabcdef).to_uppercase());
    normalized_duplicate["entities"]
        .as_array_mut()
        .unwrap()
        .push(same_key);
    assert_error(normalized_duplicate, "restore entity keys must be unique");

    // All raw entities and duplicates are checked before even the first reference.
    let mut late_duplicate = manifest(1, true);
    late_duplicate["entities"][1]["payload"]["effective_wake_observation_client_uuid"] =
        json!(id(99));
    let duplicate = late_duplicate["entities"][2].clone();
    late_duplicate["entities"]
        .as_array_mut()
        .unwrap()
        .push(duplicate);
    assert_error(late_duplicate, "restore entity keys must be unique");

    let mut invalid_duplicate = manifest(1, true);
    let mut first = invalid_duplicate["entities"][0].clone();
    first["updated_at"] = json!(-1);
    invalid_duplicate["entities"]
        .as_array_mut()
        .unwrap()
        .insert(1, first);
    assert_error(
        invalid_duplicate,
        "updated_at and deleted_at must be non-negative",
    );

    let mut references = manifest(1, true);
    references["entities"][1]["payload"]["effective_wake_observation_client_uuid"] = json!(id(99));
    references["entities"][2]["payload"]["sleep_record_client_uuid"] = json!(id(98));
    assert_error(
        references.clone(),
        "effective WakeObservation does not exist",
    );
    references["entities"].as_array_mut().unwrap().swap(1, 2);
    assert_error(
        references.clone(),
        "wake_observation sleep_record_client_uuid does not exist",
    );
    references["entities"][0]["deleted_at"] = json!(1100);
    assert_error(
        references.clone(),
        "restore tombstone is not retained source-relation history",
    );
    references["source_relations"] = json!([{"relation_id": "invalid",
        "display_client_uuid": id(2), "source_client_uuids": [id(99)], "auto_aligned": false}]);
    assert_error(
        references,
        "restore source relations are not complete and canonical",
    );
}

#[test]
#[ignore = "opt-in full-manifest timing; run only in an approved build window"]
fn wake_reference_full_manifest_timing() {
    use std::hint::black_box;
    use std::time::Instant;
    for pairs in [1, 16, 100, 1000] {
        for with_wakes in [false, true] {
            // Match total entity counts in the control, not only sleep counts.
            let input = manifest(if with_wakes { pairs } else { pairs * 2 }, with_wakes);
            let body = serde_json::to_vec(&input).unwrap();
            assert!(body.len() < 16 * 1024 * 1024);
            let output = validate(input.clone()).unwrap();
            if let Ok(directory) = std::env::var("LEZI_REFERENCE_SNAPSHOT_DIR") {
                fs::write(
                    Path::new(&directory).join(format!("{pairs}-{with_wakes}.json")),
                    serde_json::to_vec(&output).unwrap(),
                )
                .unwrap();
            }
            let iterations = if pairs >= 1000 { 50 } else { 200 };
            let requests = (0..iterations)
                .map(|_| serde_json::from_slice::<RestoreManifestRequest>(&body).unwrap())
                .collect::<Vec<_>>();
            let start = Instant::now();
            for request in requests {
                black_box(validate_manifest(black_box(request), 1024).unwrap());
            }
            eprintln!("pairs={pairs} wakes={with_wakes} entities={} body_bytes={} iterations={iterations} elapsed_us={}",
                output.entities.len(), body.len(), start.elapsed().as_micros());
        }
    }
}
