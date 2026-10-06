//! Pull pagination and dependency-order Store tests.

use std::collections::BTreeSet;

use super::super::*;
use super::test_support::*;
use crate::PULL_PAGE_TARGET_BYTES;
use serde_json::json;
use sha2::{Digest, Sha256};
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

    let mut seen = first
        .entities
        .iter()
        .map(|entity| entity.client_uuid.clone())
        .collect::<BTreeSet<_>>();
    let mut cursor = first.cursor;
    let mut page = first;
    while page.has_more {
        page = store.pull(&family_id, cursor).unwrap();
        assert!(page.cursor > cursor);
        let page_bytes = page
            .entities
            .iter()
            .map(|entity| serde_json::to_vec(entity).unwrap().len() + 1)
            .sum::<usize>();
        assert!(page_bytes <= PULL_PAGE_TARGET_BYTES);
        for entity in &page.entities {
            assert!(
                seen.insert(entity.client_uuid.clone()),
                "entity repeated across pull pages"
            );
        }
        cursor = page.cursor;
    }
    assert_eq!(seen.len(), 11);
}

#[test]
fn live_keys_shrink_a_full_page_so_the_decoded_envelope_stays_in_budget() {
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
                "avatar_media_uuid":null,"birth_weight_grams":3200
            }),
        ),
        100,
    )
    .unwrap();
    for index in 0..8 {
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
    // Live keys are the whole family, not the page. Rows past the head rev
    // stay out of this page and exist only to make the key array larger than
    // the slack a byte-full page would otherwise leave.
    store
        .with_connection(|connection| {
            let tx = connection
                .unchecked_transaction()
                .map_err(StoreError::from)?;
            let mut insert = tx
                .prepare(
                    "INSERT INTO entities(
                        family_id, entity_type, client_uuid, updated_at,
                        deleted_at, payload_json, rev
                    ) VALUES (?1, 'custom_item', ?2, 1, NULL, '{}', ?3)",
                )
                .map_err(StoreError::from)?;
            for index in 0..32_000 {
                let uuid = format!("{index:08x}-0000-4000-8000-{index:012x}");
                insert
                    .execute(rusqlite::params![family_id.as_str(), uuid, 50_000 + index])
                    .map_err(StoreError::from)?;
            }
            drop(insert);
            tx.commit().map_err(StoreError::from)?;
            Ok::<(), StoreError>(())
        })
        .unwrap();

    let key_types = BTreeSet::from(["custom_item"]);
    let page = store
        .pull_with_final_envelope_size(&family_id, 0, true, &key_types, |bytes, count, _, _| {
            Ok(bytes.saturating_add(count.saturating_sub(1)))
        })
        .unwrap();
    let overhead = page
        .live_census
        .as_ref()
        .expect("keyed census")
        .budget_overhead_against_placeholder()
        .unwrap();
    let entity_bytes = page
        .entities
        .iter()
        .map(|entity| serde_json::to_vec(entity).unwrap().len())
        .sum::<usize>()
        .saturating_add(page.entities.len().saturating_sub(1));
    assert!(overhead > 1024 * 1024, "key overhead {overhead}");
    assert!(
        entity_bytes + overhead <= PULL_PAGE_TARGET_BYTES,
        "entity bytes {entity_bytes} plus keys {overhead} exceed the pull budget",
    );
    let unkeyed = store.pull(&family_id, 0).unwrap();
    assert!(
        page.entities.len() < unkeyed.entities.len(),
        "reserving key bytes should leave at least one large record for the next page",
    );
}

#[test]
fn caught_up_pull_returns_header_only_empty_envelope() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
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

    let tip = store.current_revision(&family_id).unwrap();
    let first = store.pull(&family_id, 0).unwrap();
    assert_eq!(first.cursor, tip);
    assert!(!first.entities.is_empty());

    begin_statement_trace(&family_id);
    let page = store.pull(&family_id, tip).unwrap();
    let (statement_count, statements) = finish_statement_trace(&family_id);

    assert!(page.entities.is_empty());
    assert!(!page.has_more);
    assert_eq!(page.cursor, tip);
    assert_eq!(page.family_name.as_deref(), Some("家庭"));
    // 0.5 moved the empty-open conflict closure off the pull read path (it
    // now rides the writing transactions plus startup/periodic maintenance),
    // so a caught-up pull only resolves the head.
    assert_eq!(statement_count, 1, "{statements:?}");
    assert!(
        statements.iter().any(|sql| sql.contains("family_meta")),
        "{statements:?}"
    );
    assert!(
        statements
            .iter()
            .all(|sql| !sql.contains("status = 'open'") && !sql.contains("conflict_branches")),
        "{statements:?}"
    );
    assert!(
        statements
            .iter()
            .all(|sql| !sql.contains("WITH base") && !sql.contains("FROM entities")),
        "{statements:?}"
    );
}

#[test]
fn pull_skips_the_media_dependency_scan_when_the_window_has_no_owners() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
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

    begin_statement_trace(&family_id);
    let page = store.pull(&family_id, 0).unwrap();
    let (_, statements) = finish_statement_trace(&family_id);

    assert_eq!(page.entities.len(), 1);
    assert_eq!(page.entities[0].client_uuid, baby_id.to_string());
    assert!(
        statements
            .iter()
            .all(|sql| !sql.contains("scoped_media") && !sql.contains("$.kind")),
        "{statements:?}"
    );
}

#[test]
fn lagging_cursor_pull_keeps_record_and_log_media_on_one_page() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
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
    let media = stage_log_media(&store, &principal, media_id, &[1u8; 16]);
    publish_root_with_media(
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
                "payload_json":{"body":"photo"},
                "schema_version":2
            }),
        ),
        vec![media],
        10,
    )
    .unwrap();

    begin_statement_trace(&family_id);
    let page = store.pull(&family_id, 0).unwrap();
    let (statement_count, statements) = finish_statement_trace(&family_id);
    let keys = page
        .entities
        .iter()
        .map(|entity| (entity.entity_type.clone(), entity.client_uuid.clone()))
        .collect::<BTreeSet<_>>();

    assert!(
        statement_count > 1 && statements.iter().any(|sql| sql.contains("WITH base")),
        "{statements:?}"
    );
    assert_eq!(
        keys,
        BTreeSet::from([
            ("baby".to_owned(), baby_id.to_string()),
            ("record".to_owned(), record_id.to_string()),
            ("media".to_owned(), media_id.to_string()),
        ])
    );
    assert!(!page.has_more);
    assert_eq!(page.cursor, store.current_revision(&family_id).unwrap());
}

#[test]
fn lagging_cursor_pull_keeps_wake_and_wake_media_on_one_page() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let sleep_id = Uuid::new_v4();
    let wake_id = Uuid::new_v4();
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
    publish_root(
        &store,
        &principal,
        entity(
            "record",
            sleep_id,
            2,
            json!({
                "baby_client_uuid":baby_id,
                "type":"sleep",
                "custom_item_client_uuid":null,
                "timestamp":100,
                "note":null,
                "payload_json":{"anomaly_flag":false,"is_nap":false},
                "schema_version":2,
                "effective_wake_observation_client_uuid":null
            }),
        ),
        10,
    )
    .unwrap();
    let media = stage_wake_media(&store, &principal, media_id, &[1u8; 16]);
    publish_root_with_media(
        &store,
        &principal,
        entity(
            "wake_observation",
            wake_id,
            3,
            json!({
                "sleep_record_client_uuid":sleep_id,
                "wake_timestamp":200,
                "note":null,
                "withdrawn":false
            }),
        ),
        vec![media],
        10,
    )
    .unwrap();

    let page = store.pull(&family_id, 0).unwrap();
    let keys = page
        .entities
        .iter()
        .map(|entity| (entity.entity_type.clone(), entity.client_uuid.clone()))
        .collect::<BTreeSet<_>>();

    assert_eq!(
        keys,
        BTreeSet::from([
            ("baby".to_owned(), baby_id.to_string()),
            ("record".to_owned(), sleep_id.to_string()),
            ("wake_observation".to_owned(), wake_id.to_string()),
            ("media".to_owned(), media_id.to_string()),
        ])
    );
    assert!(!page.has_more);
    assert_eq!(page.cursor, store.current_revision(&family_id).unwrap());
}

#[test]
fn wake_group_reemits_current_sleep_even_when_sleep_rev_is_later() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let sleep_id = Uuid::new_v4();
    let wake_id = Uuid::new_v4();
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
    let sleep_payload = json!({
        "baby_client_uuid":baby_id,
        "type":"sleep",
        "custom_item_client_uuid":null,
        "timestamp":100,
        "note":null,
        "payload_json":{"anomaly_flag":false,"is_nap":false},
        "schema_version":2,
        "updated_at":10,
        "effective_wake_observation_client_uuid":null
    });
    let sleep_created = store
        .causal_commit(
            &principal,
            vec![CausalMutation {
                mutation_id: Uuid::new_v4().to_string(),
                base_version: None,
                entity_type: "record".to_owned(),
                client_uuid: sleep_id.to_string(),
                root: sleep_payload.as_object().unwrap().clone(),
                media: vec![],
                deleted: false,
            }],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(sleep_created.results[0].status, "accepted");
    let sleep_base = sleep_created.results[0]
        .stable_version_id
        .clone()
        .expect("accepted sleep has a stable version");
    for index in 0..10 {
        publish_root(
            &store,
            &principal,
            entity(
                "record",
                Uuid::new_v4(),
                index + 20,
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
    publish_root(
        &store,
        &principal,
        entity(
            "wake_observation",
            wake_id,
            40,
            json!({
                "sleep_record_client_uuid":sleep_id,
                "wake_timestamp":200,
                "note":null,
                "withdrawn":false
            }),
        ),
        50,
    )
    .unwrap();
    let mut sleep_update = sleep_payload.as_object().unwrap().clone();
    sleep_update.insert("note".to_owned(), json!("later sleep edit"));
    sleep_update.insert("updated_at".to_owned(), json!(80));
    let sleep_updated = store
        .causal_commit(
            &principal,
            vec![CausalMutation {
                mutation_id: Uuid::new_v4().to_string(),
                base_version: Some(sleep_base),
                entity_type: "record".to_owned(),
                client_uuid: sleep_id.to_string(),
                root: sleep_update,
                media: vec![],
                deleted: false,
            }],
            1_700_000_001,
        )
        .unwrap();
    assert_eq!(sleep_updated.results[0].status, "accepted");

    let mut cursor = 0i64;
    let mut saw_wake = false;
    let wake_key = ("wake_observation".to_owned(), wake_id.to_string());
    let sleep_key = ("record".to_owned(), sleep_id.to_string());
    for _ in 0..8 {
        let page = store.pull(&family_id, cursor).unwrap();
        let keys = page
            .entities
            .iter()
            .map(|entity| (entity.entity_type.clone(), entity.client_uuid.clone()))
            .collect::<BTreeSet<_>>();
        if keys.contains(&wake_key) {
            assert!(
                keys.contains(&sleep_key),
                "wake type completeness requires its parent sleep on the same page: {keys:?}"
            );
            saw_wake = true;
        }
        if !page.has_more {
            break;
        }
        assert!(page.cursor > cursor);
        cursor = page.cursor;
    }
    assert!(saw_wake, "wake must appear on some pull page");
}

#[test]
fn open_sleep_pulls_without_any_wake() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let sleep_id = Uuid::new_v4();
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
            sleep_id,
            2,
            json!({
                "baby_client_uuid":baby_id,
                "type":"sleep",
                "custom_item_client_uuid":null,
                "timestamp":100,
                "note":null,
                "payload_json":{"anomaly_flag":false,"is_nap":false},
                "schema_version":2,
                "effective_wake_observation_client_uuid":null
            }),
        ),
        10,
    )
    .unwrap();

    let page = store.pull(&family_id, 0).unwrap();
    let types = page
        .entities
        .iter()
        .map(|entity| entity.entity_type.as_str())
        .collect::<BTreeSet<_>>();
    assert_eq!(types, BTreeSet::from(["baby", "record"]));
    assert!(!page.has_more);
}

#[test]
fn pull_projects_missing_care_plan_source_as_explicit_null() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let store = Store::open(&db_path).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let plan_id = Uuid::new_v4();
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
            "care_plan",
            plan_id,
            1,
            json!({
                "baby_client_uuid":baby_id,
                "type":"bath",
                "custom_item_client_uuid":null,
                "scheduled_at":1_700_000_000_000i64,
                "scheduled_zone_id":"Asia/Shanghai",
                "note":null,
                "status":"pending",
                "payload_json":{},
                "schema_version":2,
                "created_by_membership_id":"m-owner",
                "fulfilled_record_client_uuid":null,
                "fulfilled_at":null,
                "source_record_client_uuid":null
            }),
        ),
        10,
    )
    .unwrap();
    let connection = rusqlite::Connection::open(&db_path).unwrap();
    connection
        .execute_batch(
            "
            UPDATE entities
            SET payload_json = json_remove(payload_json, '$.source_record_client_uuid')
            WHERE entity_type = 'care_plan';
            UPDATE entity_versions
            SET payload_json = json_remove(payload_json, '$.source_record_client_uuid')
            WHERE entity_type = 'care_plan';
            ",
        )
        .unwrap();
    drop(connection);

    let page = store.pull(&family_id, 0).unwrap();
    let plan = page
        .entities
        .iter()
        .find(|entity| entity.entity_type == "care_plan")
        .expect("care plan stays on the page");
    assert!(
        plan.payload.contains_key("source_record_client_uuid"),
        "missing source must be projected, not skipped: {:?}",
        plan.payload.keys().collect::<Vec<_>>()
    );
    assert!(plan.payload["source_record_client_uuid"].is_null());
    assert!(
        page.entities
            .iter()
            .any(|entity| entity.entity_type == "baby"),
        "projecting source must not drop the rest of the page"
    );
}

#[test]
fn pull_without_census_flag_keeps_page_without_live_census() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let store = Store::open(&db_path).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
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

    let page = store.pull(&family_id, 0).unwrap();
    assert!(page.live_census.is_none(), "default pull carries no census");

    // The census is the full-family live set at head, so a caught-up (empty
    // page) pull still reports the live baby; all seven types are present.
    let caught_up = store.pull_with_census(&family_id, page.cursor).unwrap();
    assert!(
        caught_up.entities.is_empty(),
        "pull at head returns no rows"
    );
    let census = serde_json::to_value(caught_up.live_census.unwrap()).unwrap();
    let census = census.as_object().unwrap();
    assert_eq!(census.len(), 7, "all seven types are always reported");
    for entity_type in LIVE_CENSUS_ENTITY_TYPES {
        assert!(census.contains_key(*entity_type), "{census:?}");
    }
    assert_eq!(census["baby"]["count"], json!(1));
    assert_eq!(
        census["baby"]["key_digest"],
        json!(hex::encode(Sha256::digest(baby_id.to_string().as_bytes())))
    );
    assert_eq!(census["record"]["count"], json!(0));
    assert_eq!(
        census["record"]["key_digest"],
        json!("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    );
    assert!(
        census["baby"].get("keys").is_none(),
        "default census must not carry live keys"
    );
}

#[test]
fn live_census_keys_attach_only_for_requested_types() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let store = Store::open(&db_path).unwrap();
    let family_id = family(&store);
    let page = store.pull_with_census(&family_id, 0).unwrap();
    let census = page.live_census.expect("census flag attaches the census");
    let attached = store
        .attach_live_keys_to_census(
            &family_id,
            census,
            &std::collections::BTreeSet::from(["baby"]),
        )
        .unwrap();
    let value = serde_json::to_value(attached).unwrap();
    assert!(value["baby"]["keys"].is_array());
    assert!(
        value["record"].get("keys").is_none(),
        "unrequested types must omit keys"
    );
}

#[test]
fn live_census_counts_live_keys_excludes_tombstones_and_digests_them() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let store = Store::open(&db_path).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let first_item = Uuid::new_v4();
    let second_item = Uuid::new_v4();
    let deleted_item = Uuid::new_v4();
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
        100,
    )
    .unwrap();
    publish_root(
        &store,
        &principal,
        entity(
            "record",
            record_id,
            1,
            json!({
                "baby_client_uuid":baby_id,
                "type":"diary",
                "custom_item_client_uuid":null,
                "timestamp":100,
                "end_timestamp":null,
                "note":null,
                "payload_json":{"body":"普查"},
                "schema_version":2
            }),
        ),
        100,
    )
    .unwrap();
    for item in [first_item, second_item] {
        publish_root(
            &store,
            &principal,
            entity(
                "custom_item",
                item,
                1,
                json!({"name":"抚触","icon_slot":2,"created_by_membership_id":null}),
            ),
            100,
        )
        .unwrap();
    }
    publish_root(
        &store,
        &principal,
        entity(
            "custom_item",
            deleted_item,
            1,
            json!({"name":"删除项","icon_slot":3,"created_by_membership_id":null}),
        ),
        100,
    )
    .unwrap();
    // Census reads `deleted_at IS NULL`; stamp the tombstone directly so the
    // fixture does not depend on a second causal commit's base_version.
    rusqlite::Connection::open(&db_path)
        .unwrap()
        .execute(
            "UPDATE entities SET deleted_at = 2
             WHERE entity_type = 'custom_item' AND client_uuid = ?1",
            rusqlite::params![deleted_item.to_string()],
        )
        .unwrap();

    let page = store.pull_with_census(&family_id, 0).unwrap();
    let census = serde_json::to_value(page.live_census.expect("flag attaches the census")).unwrap();
    let census = census.as_object().unwrap();
    assert_eq!(census.len(), 7);
    for entity_type in LIVE_CENSUS_ENTITY_TYPES {
        assert!(census.contains_key(*entity_type), "{census:?}");
    }

    assert_eq!(census["baby"]["count"], json!(1));
    assert_eq!(
        census["baby"]["key_digest"],
        json!(hex::encode(Sha256::digest(baby_id.to_string().as_bytes())))
    );
    assert_eq!(census["record"]["count"], json!(1));
    assert_eq!(
        census["record"]["key_digest"],
        json!(hex::encode(Sha256::digest(
            record_id.to_string().as_bytes()
        )))
    );

    let mut live_items = [first_item.to_string(), second_item.to_string()];
    live_items.sort();
    assert_eq!(
        census["custom_item"]["count"],
        json!(2),
        "tombstone excluded"
    );
    assert_eq!(
        census["custom_item"]["key_digest"],
        json!(hex::encode(Sha256::digest(
            live_items.join("\n").as_bytes()
        )))
    );

    for entity_type in [
        "media",
        "care_plan",
        "fulfillment_candidate",
        "wake_observation",
    ] {
        assert_eq!(census[entity_type]["count"], json!(0), "{entity_type}");
        assert_eq!(
            census[entity_type]["key_digest"],
            json!("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        );
    }
}

#[test]
fn live_census_digests_distinguish_same_count_different_keys() {
    let build_store = |custom_item: Uuid| -> (TempDir, Store, String) {
        let directory = TempDir::new().unwrap();
        let store = Store::open(directory.path().join("lezi.db")).unwrap();
        let family_id = family(&store);
        let principal = owner_principal(&family_id);
        publish_root(
            &store,
            &principal,
            entity(
                "custom_item",
                custom_item,
                1,
                json!({"name":"抚触","icon_slot":2,"created_by_membership_id":null}),
            ),
            10,
        )
        .unwrap();
        (directory, store, family_id)
    };
    let (left_directory, left, left_family) = build_store(Uuid::new_v4());
    let (right_directory, right, right_family) = build_store(Uuid::new_v4());

    let left_census = serde_json::to_value(
        left.pull_with_census(&left_family, 0)
            .unwrap()
            .live_census
            .unwrap(),
    )
    .unwrap();
    let right_census = serde_json::to_value(
        right
            .pull_with_census(&right_family, 0)
            .unwrap()
            .live_census
            .unwrap(),
    )
    .unwrap();

    assert_eq!(left_census["custom_item"]["count"], json!(1));
    assert_eq!(
        right_census["custom_item"]["count"],
        json!(1),
        "same counts"
    );
    assert_ne!(
        left_census["custom_item"]["key_digest"], right_census["custom_item"]["key_digest"],
        "same count but different live keys must digest differently"
    );
    drop((left_directory, right_directory));
}

#[test]
fn live_census_pull_is_a_pure_read() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let store = Store::open(&db_path).unwrap();
    let family_id = family(&store);
    let principal = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let deleted_item = Uuid::new_v4();
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
            deleted_item,
            1,
            json!({"name":"删除项","icon_slot":3,"created_by_membership_id":null}),
        ),
        10,
    )
    .unwrap();
    rusqlite::Connection::open(&db_path)
        .unwrap()
        .execute(
            "UPDATE entities SET deleted_at = 2
             WHERE entity_type = 'custom_item' AND client_uuid = ?1",
            rusqlite::params![deleted_item.to_string()],
        )
        .unwrap();

    let live_row_fingerprint = || -> String {
        let connection = rusqlite::Connection::open(&db_path).unwrap();
        let mut statement = connection
            .prepare(
                "
                SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                FROM entities
                WHERE family_id = ?1
                ORDER BY entity_type COLLATE BINARY, client_uuid COLLATE BINARY
                ",
            )
            .unwrap();
        let mut hasher = Sha256::new();
        let mut rows = statement.query(rusqlite::params![family_id]).unwrap();
        while let Some(row) = rows.next().unwrap() {
            let entity_type: String = row.get(0).unwrap();
            let client_uuid: String = row.get(1).unwrap();
            let updated_at: i64 = row.get(2).unwrap();
            let deleted_at: Option<i64> = row.get(3).unwrap();
            let payload_json: String = row.get(4).unwrap();
            let rev: i64 = row.get(5).unwrap();
            hasher.update(entity_type.as_bytes());
            hasher.update([0]);
            hasher.update(client_uuid.as_bytes());
            hasher.update([0]);
            hasher.update(updated_at.to_be_bytes());
            hasher.update([0]);
            hasher.update(payload_json.as_bytes());
            hasher.update([0]);
            hasher.update(rev.to_be_bytes());
            hasher.update([0]);
            hasher.update(deleted_at.map_or([0u8; 8], |stamp| stamp.to_be_bytes()));
        }
        hex::encode(hasher.finalize())
    };

    let before = live_row_fingerprint();
    let page = store.pull_with_census(&family_id, 0).unwrap();
    assert!(page.live_census.is_some());
    let after = live_row_fingerprint();
    assert_eq!(before, after, "census pull must not rewrite any entity row");
}
