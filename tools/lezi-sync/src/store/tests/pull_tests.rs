//! Pull pagination and dependency-order Store tests.

use super::super::*;
use super::test_support::*;
use crate::PULL_PAGE_TARGET_BYTES;
use serde_json::json;
use tempfile::TempDir;
use uuid::Uuid;

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
