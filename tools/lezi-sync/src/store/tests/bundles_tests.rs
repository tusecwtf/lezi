//! Domain store tests.

use super::super::*;
use super::test_support::*;
use crate::model::MAX_BUNDLE_MEDIA_ENTITIES;
use serde_json::json;
use tempfile::TempDir;
use uuid::Uuid;

#[test]
fn atomic_root_rejects_a_ninth_cumulative_live_media_item() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let record_payload = || {
        json!({
            "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
            "end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},
            "schema_version":2
        })
    };
    publish_root(
        &store,
        &principal,
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        10,
    )
    .unwrap();
    let initial_media = (0..MAX_BUNDLE_MEDIA_ENTITIES)
        .map(|_| {
            entity(
                "media",
                Uuid::new_v4(),
                2,
                json!({
                    "kind":"log","record_client_uuid":record_id,
                    "mime":"image/jpeg","byte_size":3
                }),
            )
        })
        .collect::<Vec<_>>();
    publish_bundle(
        &store,
        &principal,
        entity("record", record_id, 2, record_payload()),
        initial_media,
        10,
    )
    .unwrap();

    let ninth_bundle_id = Uuid::new_v4().to_string();
    let ninth = entity(
        "media",
        Uuid::new_v4(),
        3,
        json!({
            "kind":"log","record_client_uuid":record_id,
            "mime":"image/jpeg","byte_size":3
        }),
    );
    let result = store.stage_bundle(
        &principal,
        &ninth_bundle_id,
        entity("record", record_id, 3, record_payload()),
        vec![ninth],
        1_700_000_000,
    );

    assert!(matches!(
        result,
        Err(StoreError::UnresolvedReference(message))
            if message == "atomic root supports at most 8 live media items"
    ));
    let pulled = store.pull(&family_id, 0).unwrap();
    assert_eq!(
        pulled
            .entities
            .iter()
            .filter(|entity| entity.entity_type == "media" && entity.deleted_at.is_none())
            .count(),
        MAX_BUNDLE_MEDIA_ENTITIES
    );
}

#[test]
fn family_rejects_an_eleventh_live_custom_item() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);

    for index in 0..10 {
        publish_root(
            &store,
            &principal,
            entity(
                "custom_item",
                Uuid::new_v4(),
                index + 1,
                json!({
                    "name": format!("自定义{index}"),
                    "icon_slot": index % 8,
                    "created_by_membership_id": null
                }),
            ),
            10,
        )
        .unwrap();
    }

    let result = publish_root(
        &store,
        &principal,
        entity(
            "custom_item",
            Uuid::new_v4(),
            11,
            json!({
                "name": "第十一个",
                "icon_slot": 0,
                "created_by_membership_id": null
            }),
        ),
        10,
    );

    assert!(matches!(
        result,
        Err(StoreError::UnresolvedReference(message))
            if message == "family supports at most 10 live custom items"
    ));
    assert_eq!(
        store
            .pull(&family_id, 0)
            .unwrap()
            .entities
            .iter()
            .filter(|entity| entity.entity_type == "custom_item" && entity.deleted_at.is_none())
            .count(),
        10,
    );
}

#[test]
fn lww_and_reference_validation_share_one_transaction() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let baby = json!({
        "nickname":"年年","sex":"female","birthday":"2025-01-02",
        "avatar_media_uuid":null,"birth_weight_grams":3200
    });
    let record = json!({
        "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
        "end_timestamp":null,"note":null,"payload_json":{"amount_ml":120},
        "schema_version":2
    });
    let principal = owner_principal(&family_id);
    assert_eq!(
        publish_root(
            &store,
            &principal,
            entity("baby", baby_id, 1, baby.clone()),
            10,
        )
        .unwrap()
        .applied,
        1
    );
    assert_eq!(
        publish_root(
            &store,
            &principal,
            entity("record", record_id, 1, record),
            10,
        )
        .unwrap()
        .applied,
        1
    );
    assert_eq!(
        publish_root(&store, &principal, entity("baby", baby_id, 1, baby), 10,)
            .unwrap()
            .applied,
        0
    );
    assert_eq!(store.pull(&family_id, 0).unwrap().entities.len(), 2);
}
#[test]
fn care_plan_stage_requires_a_type_consistent_custom_item_reference() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let custom_item_id = Uuid::new_v4();
    publish_root(
        &store,
        &principal,
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity(
            "custom_item",
            custom_item_id,
            1,
            json!({
                "name":"抚触","icon_slot":2,"created_by_membership_id":null
            }),
        ),
        10,
    )
    .unwrap();

    let base_payload = || {
        json!({
            "baby_client_uuid": baby_id,
            "type": "custom",
            "scheduled_at": 1_700_000_000_000i64,
            "scheduled_zone_id": "Asia/Shanghai",
            "status": "pending",
            "payload_json": {},
            "schema_version": 2,
            "note": null,
        })
    };
    let missing_reference = base_payload();
    let mut null_reference = base_payload();
    null_reference["custom_item_client_uuid"] = Value::Null;
    let mut unexpected_reference = base_payload();
    unexpected_reference["type"] = json!("bath");
    unexpected_reference["custom_item_client_uuid"] = json!(custom_item_id);

    for (payload, expected_message) in [
        (
            missing_reference,
            "care_plan type custom requires custom_item_client_uuid",
        ),
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
        let result = store.stage_bundle(
            &principal,
            &bundle_id,
            entity("care_plan", Uuid::new_v4(), 2, payload),
            vec![],
            2,
        );
        assert!(matches!(
            result,
            Err(StoreError::UnresolvedReference(message)) if message == expected_message
        ));
        assert!(store
            .bundle_status(&family_id, &bundle_id)
            .unwrap()
            .is_none());
    }

    let valid_bundle_id = Uuid::new_v4().to_string();
    let mut valid_payload = base_payload();
    valid_payload["custom_item_client_uuid"] = json!(custom_item_id);
    assert_eq!(
        store
            .stage_bundle(
                &principal,
                &valid_bundle_id,
                entity("care_plan", Uuid::new_v4(), 2, valid_payload),
                vec![],
                2,
            )
            .unwrap()
            .status,
        "staging"
    );
}
#[test]
fn atomic_record_bundle_requires_a_live_type_consistent_custom_item_reference() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let live_custom_item_id = Uuid::new_v4();
    let deleted_custom_item_id = Uuid::new_v4();
    for root in [
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        entity(
            "custom_item",
            live_custom_item_id,
            1,
            json!({
                "name":"抚触","icon_slot":2,"created_by_membership_id":null
            }),
        ),
        entity(
            "custom_item",
            deleted_custom_item_id,
            1,
            json!({
                "name":"旧项目","icon_slot":3,"created_by_membership_id":null
            }),
        ),
    ] {
        publish_root(&store, &principal, root, 10).unwrap();
    }
    let deleted_payload = json!({
        "name":"旧项目","icon_slot":3,"created_by_membership_id":"m-owner"
    });
    let mut deleted_entity = entity("custom_item", deleted_custom_item_id, 2, deleted_payload);
    deleted_entity.deleted_at = Some(2);
    publish_root(&store, &principal, deleted_entity, 10).unwrap();

    let record_payload = |record_type: &str, custom_item_id: Option<Uuid>| {
        json!({
            "baby_client_uuid": baby_id,
            "type": record_type,
            "custom_item_client_uuid": custom_item_id,
            "timestamp": 100,
            "end_timestamp": null,
            "note": null,
            "payload_json": {},
            "schema_version": 2,
        })
    };
    for (payload, expected_message) in [
        (
            record_payload("custom", None),
            "record type custom requires custom_item_client_uuid",
        ),
        (
            record_payload("bath", Some(live_custom_item_id)),
            "record custom_item_client_uuid is only valid for type custom",
        ),
        (
            record_payload("custom", Some(Uuid::new_v4())),
            "record custom_item_client_uuid does not exist",
        ),
        (
            record_payload("custom", Some(deleted_custom_item_id)),
            "record custom_item_client_uuid is deleted and cannot be selected for a new item",
        ),
    ] {
        let result = publish_root(
            &store,
            &principal,
            entity("record", Uuid::new_v4(), 3, payload),
            10,
        );
        assert!(matches!(
            result,
            Err(StoreError::UnresolvedReference(message)) if message == expected_message
        ));
    }

    assert_eq!(
        publish_root(
            &store,
            &principal,
            entity(
                "record",
                Uuid::new_v4(),
                3,
                record_payload("custom", Some(live_custom_item_id)),
            ),
            10,
        )
        .unwrap()
        .applied,
        1
    );
}
#[test]
fn tombstoned_custom_item_supports_only_persisted_history_and_its_fulfillment() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    let store = Store::open(&database_path).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let custom_item_id = Uuid::new_v4();
    let historical_record_id = Uuid::new_v4();
    let historical_plan_id = Uuid::new_v4();
    let fulfilled_record_id = Uuid::new_v4();
    let rebound_record_id = Uuid::new_v4();
    let record_payload = |note: &str| {
        json!({
            "baby_client_uuid":baby_id,"type":"custom",
            "custom_item_client_uuid":custom_item_id,"timestamp":100,
            "end_timestamp":null,"note":note,"payload_json":{"title":"抚触"},
            "schema_version":2
        })
    };
    let plan_payload = |status: &str, fulfilled_record: Option<Uuid>| {
        json!({
            "baby_client_uuid":baby_id,"type":"custom",
            "custom_item_client_uuid":custom_item_id,
            "scheduled_at":1_700_000_000_000i64,
            "scheduled_zone_id":"Asia/Shanghai","status":status,
            "payload_json":{"title":"抚触"},"schema_version":2,"note":null,
            "created_by_membership_id":"m-owner",
            "fulfilled_record_client_uuid":fulfilled_record,"fulfilled_at":
                fulfilled_record.map(|_| 1_700_000_100_000i64)
        })
    };

    for root in [
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        entity(
            "custom_item",
            custom_item_id,
            1,
            json!({
                "name":"抚触","icon_slot":2,"created_by_membership_id":null
            }),
        ),
        entity(
            "record",
            historical_record_id,
            2,
            record_payload("历史记录"),
        ),
        entity(
            "care_plan",
            historical_plan_id,
            2,
            plan_payload("pending", None),
        ),
    ] {
        publish_root(&store, &principal, root, 10).unwrap();
    }
    let mut tombstone = entity(
        "custom_item",
        custom_item_id,
        3,
        json!({
            "name":"抚触","icon_slot":2,"created_by_membership_id":"m-owner"
        }),
    );
    tombstone.deleted_at = Some(3);
    publish_root(&store, &principal, tombstone, 10).unwrap();

    // Reopen the database so acceptance cannot depend on staging memory.
    let restarted = Store::open(&database_path).unwrap();
    assert_eq!(
        publish_root(
            &restarted,
            &principal,
            entity(
                "record",
                historical_record_id,
                4,
                record_payload("历史记录已编辑"),
            ),
            10,
        )
        .unwrap()
        .applied,
        1
    );

    for root in [
        entity("record", Uuid::new_v4(), 4, record_payload("伪造新事实")),
        entity(
            "care_plan",
            Uuid::new_v4(),
            4,
            plan_payload("pending", None),
        ),
    ] {
        assert!(matches!(
            publish_root(&restarted, &principal, root, 10),
            Err(StoreError::UnresolvedReference(_))
        ));
    }

    assert_eq!(
        publish_root(
            &restarted,
            &principal,
            entity(
                "care_plan",
                historical_plan_id,
                5,
                plan_payload("completed", Some(fulfilled_record_id)),
            ),
            10,
        )
        .unwrap()
        .applied,
        1
    );
    assert_eq!(
        publish_root(
            &restarted,
            &principal,
            entity(
                "record",
                fulfilled_record_id,
                5,
                record_payload("显式履行事实"),
            ),
            10,
        )
        .unwrap()
        .applied,
        1
    );

    let rebind_result = publish_root(
        &restarted,
        &principal,
        entity(
            "care_plan",
            historical_plan_id,
            6,
            plan_payload("completed", Some(rebound_record_id)),
        ),
        10,
    );
    assert!(matches!(
        rebind_result,
        Err(StoreError::ImmutableCarePlanFulfillmentBinding)
    ));
    assert!(matches!(
        publish_root(
            &restarted,
            &principal,
            entity(
                "record",
                rebound_record_id,
                6,
                record_payload("伪造改绑事实"),
            ),
            10,
        ),
        Err(StoreError::UnresolvedReference(_))
    ));
    let pulled = restarted.pull(&family_id, 0).unwrap();
    let persisted_plan = pulled
        .entities
        .iter()
        .find(|entity| {
            entity.entity_type == "care_plan"
                && entity.client_uuid == historical_plan_id.to_string()
        })
        .unwrap();
    assert_eq!(
        persisted_plan.payload["fulfilled_record_client_uuid"],
        json!(fulfilled_record_id)
    );
    assert!(pulled
        .entities
        .iter()
        .all(|entity| entity.client_uuid != rebound_record_id.to_string()));
}
#[test]
fn completed_care_plan_fulfillment_pair_is_immutable_and_exact_replay_is_idempotent() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let plan_id = Uuid::new_v4();
    let first_record_id = Uuid::new_v4();
    let rebound_record_id = Uuid::new_v4();
    let fulfilled_at = 1_700_000_100_000i64;
    let plan_payload = |record_id: Option<Uuid>, at: Option<i64>, note: &str| {
        json!({
            "baby_client_uuid":baby_id,"type":"bath",
            "custom_item_client_uuid":null,
            "scheduled_at":1_700_000_000_000i64,
            "scheduled_zone_id":"Asia/Shanghai",
            "status":if record_id.is_some() { "completed" } else { "pending" },
            "payload_json":{},"schema_version":2,"note":note,
            "created_by_membership_id":"m-owner",
            "fulfilled_record_client_uuid":record_id,"fulfilled_at":at
        })
    };
    let record_payload = || {
        json!({
            "baby_client_uuid":baby_id,"type":"bath",
            "custom_item_client_uuid":null,"timestamp":1_700_000_100_000i64,
            "end_timestamp":null,"note":null,"payload_json":{},"schema_version":2
        })
    };
    publish_root(
        &store,
        &principal,
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity("care_plan", plan_id, 1, plan_payload(None, None, "pending")),
        10,
    )
    .unwrap();

    let first_bundle_id = Uuid::new_v4().to_string();
    store
        .stage_bundle(
            &principal,
            &first_bundle_id,
            entity(
                "care_plan",
                plan_id,
                2,
                plan_payload(Some(first_record_id), Some(fulfilled_at), "first"),
            ),
            vec![],
            1_700_000_000,
        )
        .unwrap();
    let first_commit = store
        .commit_bundle(
            &principal,
            &first_bundle_id,
            &BTreeMap::new(),
            10,
            1_700_000_000,
        )
        .unwrap()
        .0;
    let exact_commit_retry = store
        .commit_bundle(
            &principal,
            &first_bundle_id,
            &BTreeMap::new(),
            10,
            1_700_000_001,
        )
        .unwrap()
        .0;
    assert_eq!(exact_commit_retry, first_commit);
    publish_root(
        &store,
        &principal,
        entity("record", first_record_id, 2, record_payload()),
        10,
    )
    .unwrap();

    assert_eq!(
        publish_root(
            &store,
            &principal,
            entity(
                "care_plan",
                plan_id,
                3,
                plan_payload(Some(first_record_id), Some(fulfilled_at), "edited"),
            ),
            10,
        )
        .unwrap()
        .applied,
        1
    );
    let baseline = store.pull(&family_id, 0).unwrap();

    for payload in [
        plan_payload(Some(rebound_record_id), Some(fulfilled_at), "rebound"),
        plan_payload(None, Some(fulfilled_at), "cleared"),
        plan_payload(Some(first_record_id), Some(fulfilled_at + 1), "retimed"),
    ] {
        assert!(matches!(
            publish_root(
                &store,
                &principal,
                entity("care_plan", plan_id, 4, payload),
                10,
            ),
            Err(StoreError::ImmutableCarePlanFulfillmentBinding)
        ));
        assert_eq!(store.pull(&family_id, 0).unwrap().cursor, baseline.cursor);
    }

    let persisted_plan = store
        .pull(&family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .find(|entity| {
            entity.entity_type == "care_plan" && entity.client_uuid == plan_id.to_string()
        })
        .unwrap();
    assert_eq!(persisted_plan.payload["note"], "edited");
    assert_eq!(
        persisted_plan.payload["fulfilled_record_client_uuid"],
        json!(first_record_id)
    );
    assert_eq!(persisted_plan.payload["fulfilled_at"], fulfilled_at);
}
#[test]
fn staged_care_plan_rebind_cannot_commit_after_first_binding_wins() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let plan_id = Uuid::new_v4();
    let first_record_id = Uuid::new_v4();
    let staged_record_id = Uuid::new_v4();
    let plan_payload = |record_id: Option<Uuid>| {
        json!({
            "baby_client_uuid":baby_id,"type":"bath",
            "custom_item_client_uuid":null,
            "scheduled_at":1_700_000_000_000i64,
            "scheduled_zone_id":"Asia/Shanghai",
            "status":if record_id.is_some() { "completed" } else { "pending" },
            "payload_json":{},"schema_version":2,"note":null,
            "created_by_membership_id":"m-owner",
            "fulfilled_record_client_uuid":record_id,
            "fulfilled_at":record_id.map(|_| 1_700_000_100_000i64)
        })
    };
    let record_payload = || {
        json!({
            "baby_client_uuid":baby_id,"type":"bath",
            "custom_item_client_uuid":null,"timestamp":1_700_000_100_000i64,
            "end_timestamp":null,"note":null,"payload_json":{},"schema_version":2
        })
    };
    publish_root(
        &store,
        &principal,
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity("care_plan", plan_id, 1, plan_payload(None)),
        10,
    )
    .unwrap();

    let staged_bundle_id = Uuid::new_v4().to_string();
    assert_eq!(
        store
            .stage_bundle(
                &principal,
                &staged_bundle_id,
                entity(
                    "care_plan",
                    plan_id,
                    3,
                    plan_payload(Some(staged_record_id))
                ),
                vec![],
                1_700_000_000,
            )
            .unwrap()
            .status,
        "staging"
    );
    publish_root(
        &store,
        &principal,
        entity("care_plan", plan_id, 2, plan_payload(Some(first_record_id))),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity("record", first_record_id, 2, record_payload()),
        10,
    )
    .unwrap();
    let before_failed_commit = store.pull(&family_id, 0).unwrap();

    assert!(matches!(
        store.commit_bundle(
            &principal,
            &staged_bundle_id,
            &BTreeMap::new(),
            10,
            1_700_000_001,
        ),
        Err(StoreError::ImmutableCarePlanFulfillmentBinding)
    ));
    let after_failed_commit = store.pull(&family_id, 0).unwrap();
    assert_eq!(after_failed_commit.cursor, before_failed_commit.cursor);
    let persisted_plan = after_failed_commit
        .entities
        .iter()
        .find(|entity| {
            entity.entity_type == "care_plan" && entity.client_uuid == plan_id.to_string()
        })
        .unwrap();
    assert_eq!(
        persisted_plan.payload["fulfilled_record_client_uuid"],
        json!(first_record_id)
    );
    assert_eq!(
        store
            .bundle_status(&family_id, &staged_bundle_id)
            .unwrap()
            .unwrap()
            .status,
        "staging"
    );
}
#[test]
fn completed_care_plan_requires_full_fulfillment_pair_on_push() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let other_baby_id = Uuid::new_v4();
    let plan_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let other_record_id = Uuid::new_v4();
    let fulfilled_at = 1_700_000_100_000i64;
    let plan_payload = |status: &str, record: Option<Uuid>, at: Option<i64>, baby: Uuid| {
        json!({
            "baby_client_uuid":baby,"type":"bath",
            "custom_item_client_uuid":null,
            "scheduled_at":1_700_000_000_000i64,
            "scheduled_zone_id":"Asia/Shanghai",
            "status":status,
            "payload_json":{},"schema_version":2,"note":null,
            "created_by_membership_id":"m-owner",
            "fulfilled_record_client_uuid":record,"fulfilled_at":at
        })
    };
    let record_payload = |baby: Uuid| {
        json!({
            "baby_client_uuid":baby,"type":"bath",
            "custom_item_client_uuid":null,"timestamp":1_700_000_100_000i64,
            "end_timestamp":null,"note":null,"payload_json":{},"schema_version":2
        })
    };
    let baby_payload = json!({
        "nickname":"年年","sex":"female","birthday":"2025-01-02",
        "avatar_media_uuid":null,"birth_weight_grams":3200
    });
    publish_root(
        &store,
        &principal,
        entity("baby", baby_id, 1, baby_payload.clone()),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity("baby", other_baby_id, 1, baby_payload),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity(
            "care_plan",
            plan_id,
            1,
            plan_payload("pending", None, None, baby_id),
        ),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity("record", record_id, 1, record_payload(baby_id)),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity("record", other_record_id, 1, record_payload(other_baby_id)),
        10,
    )
    .unwrap();

    // completed without full pair — schema shape → InvalidCarePlanFulfillmentPair.
    for (record, at) in [
        (None, None),
        (Some(record_id), None),
        (None, Some(fulfilled_at)),
    ] {
        assert!(
            matches!(
                publish_root(
                    &store,
                    &principal,
                    entity(
                        "care_plan",
                        plan_id,
                        2,
                        plan_payload("completed", record, at, baby_id),
                    ),
                    10,
                ),
                Err(StoreError::InvalidCarePlanFulfillmentPair(_))
            ),
            "completed without full pair must reject as pair shape, not unresolved ref"
        );
    }

    // Partial or full pair on non-completed — fail closed as pair shape.
    for status in ["pending", "missed", "skipped"] {
        assert!(
            matches!(
                publish_root(
                    &store,
                    &principal,
                    entity(
                        "care_plan",
                        plan_id,
                        2,
                        plan_payload(status, Some(record_id), Some(fulfilled_at), baby_id),
                    ),
                    10,
                ),
                Err(StoreError::InvalidCarePlanFulfillmentPair(_))
            ),
            "{status} must not carry fulfillment pair"
        );
    }

    // Cross-baby existing record — true reference conflict.
    assert!(matches!(
        publish_root(
            &store,
            &principal,
            entity(
                "care_plan",
                plan_id,
                2,
                plan_payload(
                    "completed",
                    Some(other_record_id),
                    Some(fulfilled_at),
                    baby_id,
                ),
            ),
            10,
        ),
        Err(StoreError::UnresolvedReference(message))
            if message.contains("fulfilled record baby")
    ));

    // Same-baby existing record — accept and freeze.
    assert_eq!(
        publish_root(
            &store,
            &principal,
            entity(
                "care_plan",
                plan_id,
                2,
                plan_payload("completed", Some(record_id), Some(fulfilled_at), baby_id),
            ),
            10,
        )
        .unwrap()
        .applied,
        1
    );

    // Forward-ref completed plan (record not yet published) remains valid.
    let forward_plan = Uuid::new_v4();
    let forward_record = Uuid::new_v4();
    publish_root(
        &store,
        &principal,
        entity(
            "care_plan",
            forward_plan,
            1,
            plan_payload("pending", None, None, baby_id),
        ),
        10,
    )
    .unwrap();
    assert_eq!(
        publish_root(
            &store,
            &principal,
            entity(
                "care_plan",
                forward_plan,
                2,
                plan_payload(
                    "completed",
                    Some(forward_record),
                    Some(fulfilled_at),
                    baby_id,
                ),
            ),
            10,
        )
        .unwrap()
        .applied,
        1
    );
    assert_eq!(
        publish_root(
            &store,
            &principal,
            entity("record", forward_record, 2, record_payload(baby_id)),
            10,
        )
        .unwrap()
        .applied,
        1
    );
}
#[test]
fn fulfillment_candidate_first_accept_stamps_submitter_evidence() {
    let fx = FulfillmentCandidateFixture::seed();
    let (_cursor, _rev, confirmed_at) = fx.publish_first_accept();
    let frozen = fx.pull_candidate();
    assert_eq!(frozen.payload["submitter_membership_id"], "m-member");
    assert_eq!(frozen.payload["submitter_role"], "member");
    assert_ne!(confirmed_at, json!(1));
    assert_eq!(frozen.payload["care_plan_client_uuid"], json!(fx.plan_id));
    assert_eq!(frozen.payload["record_client_uuid"], json!(fx.record_id));
    assert_eq!(frozen.payload["actual_timestamp"], fx.actual_timestamp);
    assert_eq!(frozen.payload["confirmed_at"], confirmed_at);
}
#[test]
fn fulfillment_candidate_exact_replay_is_idempotent_for_submitter_peer_and_owner() {
    let fx = FulfillmentCandidateFixture::seed();
    let (baseline_cursor, frozen_rev, frozen_confirmed_at) = fx.publish_first_accept();

    for (principal, updated_at) in [(&fx.member, 30i64), (&fx.peer, 40), (&fx.owner, 50)] {
        assert_eq!(
            publish_root(
                &fx.store,
                principal,
                entity(
                    "fulfillment_candidate",
                    fx.candidate_id,
                    updated_at,
                    fx.exact_candidate_payload(),
                ),
                100,
            )
            .unwrap()
            .applied,
            0
        );
        let pulled = fx.store.pull(&fx.family_id, 0).unwrap();
        assert_eq!(pulled.cursor, baseline_cursor);
        let candidate = pulled
            .entities
            .iter()
            .find(|entity| entity.client_uuid == fx.candidate_id.to_string())
            .unwrap();
        assert_eq!(candidate.rev, frozen_rev);
        assert_eq!(candidate.payload["submitter_membership_id"], "m-member");
        assert_eq!(candidate.payload["submitter_role"], "member");
        assert_eq!(candidate.payload["confirmed_at"], frozen_confirmed_at);
        assert_eq!(
            candidate.payload["care_plan_client_uuid"],
            json!(fx.plan_id)
        );
        assert_eq!(candidate.payload["record_client_uuid"], json!(fx.record_id));
        assert_eq!(candidate.payload["actual_timestamp"], fx.actual_timestamp);
    }
}
#[test]
fn fulfillment_candidate_owner_cannot_rewrite_frozen_business_fields() {
    let fx = FulfillmentCandidateFixture::seed();
    let (baseline_cursor, _, _) = fx.publish_first_accept();

    for payload in [
        fx.candidate_payload(fx.other_plan_id, fx.record_id, Some(fx.actual_timestamp)),
        fx.candidate_payload(fx.plan_id, fx.other_record_id, Some(fx.actual_timestamp)),
        fx.candidate_payload(fx.plan_id, fx.record_id, Some(fx.actual_timestamp + 1)),
        fx.candidate_payload(fx.plan_id, fx.record_id, None),
    ] {
        assert!(matches!(
            publish_root(
                &fx.store,
                &fx.owner,
                entity("fulfillment_candidate", fx.candidate_id, 60, payload),
                100,
            ),
            Err(StoreError::ImmutableFulfillmentCandidateEvidence)
        ));
        assert_eq!(
            fx.store.pull(&fx.family_id, 0).unwrap().cursor,
            baseline_cursor
        );
    }
}
#[test]
fn fulfillment_candidate_stage_and_commit_re_run_freeze() {
    let fx = FulfillmentCandidateFixture::seed();
    let (baseline_cursor, _, _) = fx.publish_first_accept();

    // Stage re-runs freeze: rewrite package is rejected before commit.
    let staged_rewrite_bundle = Uuid::new_v4().to_string();
    assert!(matches!(
        fx.store.stage_bundle(
            &fx.owner,
            &staged_rewrite_bundle,
            entity(
                "fulfillment_candidate",
                fx.candidate_id,
                65,
                fx.candidate_payload(fx.other_plan_id, fx.record_id, Some(fx.actual_timestamp)),
            ),
            vec![],
            1_700_000_000,
        ),
        Err(StoreError::ImmutableFulfillmentCandidateEvidence)
    ));

    // Exact staged replay commits as no-op (applied=0, cursor unchanged).
    let staged_exact_bundle = Uuid::new_v4().to_string();
    assert_eq!(
        fx.store
            .stage_bundle(
                &fx.owner,
                &staged_exact_bundle,
                entity(
                    "fulfillment_candidate",
                    fx.candidate_id,
                    66,
                    fx.exact_candidate_payload(),
                ),
                vec![],
                1_700_000_000,
            )
            .unwrap()
            .status,
        "staging"
    );
    let exact_commit = fx
        .store
        .commit_bundle(
            &fx.owner,
            &staged_exact_bundle,
            &BTreeMap::new(),
            100,
            1_700_000_001,
        )
        .unwrap()
        .0;
    assert_eq!(exact_commit.applied, 0);
    assert_eq!(
        fx.store.pull(&fx.family_id, 0).unwrap().cursor,
        baseline_cursor
    );

    // Commit re-runs freeze: a package staged before first accept cannot
    // overwrite the winning evidence when it finally commits.
    let raced_candidate_id = Uuid::new_v4();
    let raced_bundle = Uuid::new_v4().to_string();
    assert_eq!(
        fx.store
            .stage_bundle(
                &fx.peer,
                &raced_bundle,
                entity(
                    "fulfillment_candidate",
                    raced_candidate_id,
                    10,
                    fx.candidate_payload(
                        fx.other_plan_id,
                        fx.other_record_id,
                        Some(fx.actual_timestamp + 5)
                    ),
                ),
                vec![],
                1_700_000_000,
            )
            .unwrap()
            .status,
        "staging"
    );
    assert_eq!(
        publish_root(
            &fx.store,
            &fx.member,
            entity(
                "fulfillment_candidate",
                raced_candidate_id,
                5,
                fx.exact_candidate_payload(),
            ),
            100,
        )
        .unwrap()
        .applied,
        1
    );
    let raced_baseline = fx.store.pull(&fx.family_id, 0).unwrap().cursor;
    assert!(matches!(
        fx.store.commit_bundle(
            &fx.peer,
            &raced_bundle,
            &BTreeMap::new(),
            100,
            1_700_000_002,
        ),
        Err(StoreError::ImmutableFulfillmentCandidateEvidence)
    ));
    assert_eq!(
        fx.store.pull(&fx.family_id, 0).unwrap().cursor,
        raced_baseline
    );
    let raced_frozen = fx
        .store
        .pull(&fx.family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .find(|entity| entity.client_uuid == raced_candidate_id.to_string())
        .unwrap();
    assert_eq!(raced_frozen.payload["submitter_membership_id"], "m-member");
    assert_eq!(
        raced_frozen.payload["care_plan_client_uuid"],
        json!(fx.plan_id)
    );
    assert_eq!(
        raced_frozen.payload["record_client_uuid"],
        json!(fx.record_id)
    );
    assert_eq!(
        raced_frozen.payload["actual_timestamp"],
        fx.actual_timestamp
    );
}
#[test]
fn fulfillment_candidate_tombstone_keeps_evidence_and_blocks_resurrection() {
    let fx = FulfillmentCandidateFixture::seed();
    fx.publish_first_accept();

    let mut tombstone = entity(
        "fulfillment_candidate",
        fx.candidate_id,
        70,
        fx.exact_candidate_payload(),
    );
    tombstone.deleted_at = Some(70);
    assert_eq!(
        publish_root(&fx.store, &fx.owner, tombstone, 100)
            .unwrap()
            .applied,
        1
    );
    let after_tombstone = fx.store.pull(&fx.family_id, 0).unwrap();
    let tombstoned = after_tombstone
        .entities
        .iter()
        .find(|entity| entity.client_uuid == fx.candidate_id.to_string())
        .unwrap();
    assert_eq!(tombstoned.deleted_at, Some(70));
    assert_eq!(tombstoned.payload["submitter_membership_id"], "m-member");
    assert_eq!(
        tombstoned.payload["care_plan_client_uuid"],
        json!(fx.plan_id)
    );
    assert_eq!(
        tombstoned.payload["record_client_uuid"],
        json!(fx.record_id)
    );
    assert_eq!(tombstoned.payload["actual_timestamp"], fx.actual_timestamp);
    let tombstone_cursor = after_tombstone.cursor;

    let mut resurrect = entity(
        "fulfillment_candidate",
        fx.candidate_id,
        80,
        fx.exact_candidate_payload(),
    );
    resurrect.deleted_at = None;
    assert!(matches!(
        publish_root(&fx.store, &fx.owner, resurrect, 100),
        Err(StoreError::FulfillmentCandidateTombstoneResurrection)
    ));
    assert_eq!(
        fx.store.pull(&fx.family_id, 0).unwrap().cursor,
        tombstone_cursor
    );

    let mut rewrite_via_tombstone = entity(
        "fulfillment_candidate",
        fx.candidate_id,
        90,
        fx.candidate_payload(
            fx.other_plan_id,
            fx.other_record_id,
            Some(fx.actual_timestamp + 9),
        ),
    );
    rewrite_via_tombstone.deleted_at = Some(90);
    assert!(matches!(
        publish_root(&fx.store, &fx.owner, rewrite_via_tombstone, 100),
        Err(StoreError::ImmutableFulfillmentCandidateEvidence)
    ));
    assert_eq!(
        fx.store.pull(&fx.family_id, 0).unwrap().cursor,
        tombstone_cursor
    );
}
#[test]
fn fulfillment_candidate_rejects_cross_baby_plan_record_pair() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_a = Uuid::new_v4();
    let baby_b = Uuid::new_v4();
    let plan_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let candidate_id = Uuid::new_v4();
    let baby_payload = |nickname: &str| {
        json!({
            "nickname":nickname,"sex":"female","birthday":"2025-01-02",
            "avatar_media_uuid":null,"birth_weight_grams":3200
        })
    };
    publish_root(
        &store,
        &owner,
        entity("baby", baby_a, 1, baby_payload("年年")),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &owner,
        entity("baby", baby_b, 1, baby_payload("豆豆")),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &owner,
        entity(
            "care_plan",
            plan_id,
            1,
            json!({
                "baby_client_uuid":baby_a,"type":"bath",
                "custom_item_client_uuid":null,
                "scheduled_at":1_700_000_000_000i64,
                "scheduled_zone_id":"Asia/Shanghai",
                "status":"pending","payload_json":{},"schema_version":2,
                "note":null,"created_by_membership_id":"m-owner",
                "fulfilled_record_client_uuid":null,"fulfilled_at":null
            }),
        ),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &owner,
        entity(
            "record",
            record_id,
            2,
            json!({
                "baby_client_uuid":baby_b,"type":"bath",
                "custom_item_client_uuid":null,"timestamp":1_700_000_000_100i64,
                "end_timestamp":null,"note":null,"payload_json":{},"schema_version":2
            }),
        ),
        10,
    )
    .unwrap();
    let before = store.pull(&family_id, 0).unwrap().cursor;
    assert!(matches!(
        publish_root(
            &store,
            &owner,
            entity(
                "fulfillment_candidate",
                candidate_id,
                3,
                json!({
                    "care_plan_client_uuid": plan_id,
                    "record_client_uuid": record_id,
                    "actual_timestamp": 1_700_000_000_100i64,
                    "submitter_membership_id": "forged",
                    "submitter_role": "owner",
                    "confirmed_at": 1,
                }),
            ),
            10,
        ),
        Err(StoreError::UnresolvedReference(message))
            if message == "fulfillment_candidate record baby does not match care_plan baby"
    ));
    assert_eq!(store.pull(&family_id, 0).unwrap().cursor, before);
}
#[test]
fn atomic_bundle_rejects_entities_past_the_timestamp_limit_without_writing() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let baby = json!({
        "nickname":"年年","sex":"female","birthday":"2025-01-02",
        "avatar_media_uuid":null,"birth_weight_grams":3200
    });

    let result = publish_root(
        &store,
        &owner_principal(&family_id),
        entity("baby", Uuid::new_v4(), 11, baby),
        10,
    );

    assert!(matches!(result, Err(StoreError::TimestampOutOfRange)));
    assert!(store.pull(&family_id, 0).unwrap().entities.is_empty());
}
