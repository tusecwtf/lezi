//! Domain store tests.

use super::super::*;
use super::test_support::*;
use crate::{PULL_PAGE_ENTITY_LIMIT, PULL_PAGE_TARGET_BYTES};
use serde_json::json;
use tempfile::TempDir;
use uuid::Uuid;

#[test]
fn legacy_incomplete_fulfillment_is_deferred_without_blocking_pull() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let unrelated_record_id = Uuid::new_v4();
    let deferred_plan_id = Uuid::new_v4();
    let missing_record_id = Uuid::new_v4();
    let deferred_media_id = Uuid::new_v4();

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
            "record",
            unrelated_record_id,
            2,
            json!({
                "baby_client_uuid":baby_id,"type":"formula",
                "custom_item_client_uuid":null,"timestamp":100,
                "end_timestamp":null,"note":"保留的干净历史",
                "payload_json":{"amount_ml":80},"schema_version":2
            }),
        ),
        10,
    )
    .unwrap();
    publish_bundle(
        &store,
        &principal,
        entity(
            "care_plan",
            deferred_plan_id,
            3,
            json!({
                "baby_client_uuid":baby_id,"type":"formula",
                "custom_item_client_uuid":null,"scheduled_at":90,
                "scheduled_zone_id":"Asia/Shanghai","status":"completed",
                "payload_json":{"amount_ml":80},"schema_version":2,"note":null,
                "created_by_membership_id":"m-owner",
                "fulfilled_record_client_uuid":missing_record_id,"fulfilled_at":100
            }),
        ),
        vec![entity(
            "media",
            deferred_media_id,
            3,
            json!({
                "kind":"log","record_client_uuid":null,
                "care_plan_client_uuid":deferred_plan_id,"baby_client_uuid":null,
                "mime":"image/jpeg","width":1,"height":1,"byte_size":3
            }),
        )],
        10,
    )
    .unwrap();

    let validation = store
        .validate_authority_graph(10 * 1024 * 1024, |_, _, _| Ok(true))
        .unwrap();
    assert_eq!(validation.family_count, 1);
    assert_eq!(validation.entity_count, 4);
    assert_eq!(validation.deferred_fulfillment_count, 1);

    let pulled = store.pull(&family_id, 0).unwrap();

    assert_eq!(pulled.cursor, 4);
    assert!(pulled
        .entities
        .iter()
        .any(|entity| entity.client_uuid == unrelated_record_id.to_string()));
    assert!(pulled
        .entities
        .iter()
        .all(|entity| entity.client_uuid != deferred_plan_id.to_string()));
    assert!(pulled
        .entities
        .iter()
        .all(|entity| entity.client_uuid != deferred_media_id.to_string()));
    assert!(pulled
        .entities
        .iter()
        .all(|entity| entity.client_uuid != missing_record_id.to_string()));

    publish_root(
        &store,
        &principal,
        entity(
            "record",
            missing_record_id,
            4,
            json!({
                "baby_client_uuid":baby_id,"type":"formula",
                "custom_item_client_uuid":null,"timestamp":100,
                "end_timestamp":null,"note":"后来回补的履行事实",
                "payload_json":{"amount_ml":80},"schema_version":2
            }),
        ),
        10,
    )
    .unwrap();

    let resolved = store.pull(&family_id, pulled.cursor).unwrap();
    let resolved_ids = resolved
        .entities
        .iter()
        .map(|entity| entity.client_uuid.clone())
        .collect::<BTreeSet<_>>();
    assert_eq!(
        resolved_ids,
        BTreeSet::from([
            baby_id.to_string(),
            missing_record_id.to_string(),
            deferred_plan_id.to_string(),
            deferred_media_id.to_string(),
        ]),
    );
}

#[test]
fn full_pull_emits_custom_item_dependency_before_custom_record() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let custom_item_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
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
            record_id,
            1,
            json!({
                "baby_client_uuid":baby_id,
                "type":"custom",
                "custom_item_client_uuid":custom_item_id,
                "timestamp":100,
                "end_timestamp":null,
                "note":null,
                "payload_json":{"title":"抚触"},
                "schema_version":2
            }),
        ),
    ] {
        publish_root(&store, &principal, root, 10).unwrap();
    }
    publish_root(
        &store,
        &principal,
        entity(
            "custom_item",
            custom_item_id,
            2,
            json!({
                "name":"睡前抚触","icon_slot":2,"created_by_membership_id":"m-owner"
            }),
        ),
        10,
    )
    .unwrap();

    let page = store.pull(&family_id, 0).unwrap();
    let ordered_keys = page
        .entities
        .iter()
        .map(|entity| (entity.entity_type.clone(), entity.client_uuid.clone()))
        .collect::<Vec<_>>();
    assert_eq!(
        ordered_keys,
        vec![
            ("baby".to_owned(), baby_id.to_string()),
            ("custom_item".to_owned(), custom_item_id.to_string()),
            ("record".to_owned(), record_id.to_string()),
        ]
    );
}
#[test]
fn pull_parent_co_groups_later_log_media_at_page_boundary() {
    for parent_type in ["record", "care_plan"] {
        let directory = TempDir::new().unwrap();
        let store = Store::open(directory.path().join("lezi.db")).unwrap();
        let family_id = family(&store);
        let principal = owner_principal(&family_id);
        let baby_id = Uuid::new_v4();
        let parent_id = Uuid::new_v4();
        let media_id = Uuid::new_v4();
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
        let parent_payload = if parent_type == "record" {
            json!({
                "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
                "end_timestamp":null,"note":null,"payload_json":{"amount_ml":120},
                "schema_version":2
            })
        } else {
            json!({
                "baby_client_uuid":baby_id,"type":"formula",
                "custom_item_client_uuid":null,
                "scheduled_at":1_700_000_000_000i64,
                "scheduled_zone_id":"Asia/Shanghai","status":"pending",
                "payload_json":{"amount_ml":120},"schema_version":2,"note":null,
                "fulfilled_record_client_uuid":null,"fulfilled_at":null
            })
        };
        publish_root(
            &store,
            &principal,
            entity(parent_type, parent_id, 2, parent_payload.clone()),
            10,
        )
        .unwrap();

        let filler: Vec<_> = (0..199)
            .map(|index| {
                entity(
                    "baby",
                    Uuid::new_v4(),
                    3,
                    json!({
                        "nickname":format!("填充{index}"),"sex":"female",
                        "birthday":"2025-01-02","avatar_media_uuid":null,
                        "birth_weight_grams":3200
                    }),
                )
            })
            .collect();
        for root in filler {
            publish_root(&store, &principal, root, 10).unwrap();
        }
        let media_payload = if parent_type == "record" {
            json!({
                "kind":"log","record_client_uuid":parent_id,
                "mime":"image/jpeg","byte_size":3
            })
        } else {
            json!({
                "kind":"log","care_plan_client_uuid":parent_id,
                "mime":"image/jpeg","byte_size":3
            })
        };
        publish_bundle(
            &store,
            &principal,
            entity(parent_type, parent_id, 4, parent_payload),
            vec![entity("media", media_id, 4, media_payload)],
            10,
        )
        .unwrap();

        let first_page = store.pull(&family_id, 1).unwrap();
        assert!(first_page.has_more, "{parent_type}");
        assert_eq!(first_page.entities.len(), PULL_PAGE_ENTITY_LIMIT - 1);
        let second_page = store.pull(&family_id, first_page.cursor).unwrap();
        let parent_index = second_page
            .entities
            .iter()
            .position(|entity| entity.client_uuid == parent_id.to_string())
            .unwrap();
        let media_index = second_page
            .entities
            .iter()
            .position(|entity| entity.client_uuid == media_id.to_string())
            .unwrap();
        assert_eq!(media_index + 1, parent_index, "{parent_type}");
    }
}
#[test]
fn pull_page_is_bounded_by_serialized_bytes_as_well_as_entity_count() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let baby_id = Uuid::new_v4();
    let baby = json!({
        "nickname":"年年","sex":"female","birthday":"2025-01-02",
        "avatar_media_uuid":null,"birth_weight_grams":3200
    });
    let principal = owner_principal(&family_id);
    publish_root(&store, &principal, entity("baby", baby_id, 1, baby), 100).unwrap();
    for index in 0..10 {
        publish_root(
            &store,
            &principal,
            entity(
                "record",
                Uuid::new_v4(),
                index + 2,
                json!({
                    "baby_client_uuid":baby_id,
                    "type":"diary",
                    "custom_item_client_uuid":null,
                    "timestamp":100,
                    "end_timestamp":null,
                    "note":null,
                    "payload_json":{"body":"x".repeat(1024 * 1024)},
                    "schema_version":2
                }),
            ),
            100,
        )
        .unwrap();
    }

    let first = store.pull(&family_id, 0).unwrap();
    let serialized_bytes = first
        .entities
        .iter()
        .map(|entity| serde_json::to_vec(entity).unwrap().len() + 1)
        .sum::<usize>();

    assert!(first.has_more);
    assert!(first.cursor < 11);
    assert!(serialized_bytes <= PULL_PAGE_TARGET_BYTES);
}
#[test]
fn push_rejects_an_entity_that_cannot_fit_on_a_bounded_pull_page() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let baby_id = Uuid::new_v4();
    let principal = owner_principal(&family_id);
    publish_root(
        &store,
        &principal,
        entity(
            "baby",
            baby_id,
            1,
            json!({
                "nickname":"年年","sex":"female","birthday":"2025-01-02",
                "avatar_media_uuid":null
            }),
        ),
        100,
    )
    .unwrap();
    let record_id = Uuid::new_v4();
    let result = publish_root(
        &store,
        &principal,
        entity(
            "record",
            record_id,
            2,
            json!({
                "baby_client_uuid":baby_id,
                "type":"diary",
                "custom_item_client_uuid":null,
                "timestamp":100,
                "end_timestamp":null,
                "note":null,
                "payload_json":{"body":"x".repeat(PULL_PAGE_TARGET_BYTES)},
                "schema_version":2
            }),
        ),
        100,
    );

    assert!(matches!(result, Err(StoreError::PullEntityTooLarge)));
    assert!(store
        .pull(&family_id, 0)
        .unwrap()
        .entities
        .iter()
        .all(|entity| entity.client_uuid != record_id.to_string()));
}
