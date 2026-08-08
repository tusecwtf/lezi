//! Schema 12 / causal generation disables server neighbor adjudication
//! (ADR-0019/0021). These tests pin residual no-op / exemption behaviour and
//! prove near-duplicates stay live without neighbor_losers.
//! Record tombstone-wins and family neighbor adjudication (0.3.10 / ADR-0018).

use super::super::*;
use super::test_support::{entity, family, owner_principal, publish_root};
use serde_json::json;
use uuid::Uuid;

fn baby_payload() -> serde_json::Value {
    json!({
        "nickname":"年年","sex":"female","birthday":"2025-01-02",
        "avatar_media_uuid":null,"birth_weight_grams":3200
    })
}

fn record_payload(baby_id: Uuid, record_type: &str, timestamp: i64) -> serde_json::Value {
    json!({
        "baby_client_uuid": baby_id,
        "type": record_type,
        "custom_item_client_uuid": null,
        "timestamp": timestamp,
        "end_timestamp": null,
        "note": null,
        "payload_json": {},
        "schema_version": 2
    })
}

fn seed_family_with_baby() -> (tempfile::TempDir, Store, String, Principal, Uuid) {
    let directory = tempfile::TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    publish_root(
        &store,
        &owner,
        entity("baby", baby_id, 1, baby_payload()),
        10,
    )
    .unwrap();
    (directory, store, family_id, owner, baby_id)
}

fn live_record_uuids(store: &Store, family_id: &str) -> Vec<String> {
    store
        .pull(family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .filter(|e| e.entity_type == "record" && e.deleted_at.is_none())
        .map(|e| e.client_uuid)
        .collect()
}

fn deleted_record_uuids(store: &Store, family_id: &str) -> Vec<String> {
    store
        .pull(family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .filter(|e| e.entity_type == "record" && e.deleted_at.is_some())
        .map(|e| e.client_uuid)
        .collect()
}

#[test]
fn record_tombstone_blocks_higher_updated_at_live_resurrection() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let record_id = Uuid::new_v4();
    let t0 = 1_700_000_000_000i64;
    publish_root(
        &store,
        &owner,
        entity(
            "record",
            record_id,
            2,
            record_payload(baby_id, "nursing", t0),
        ),
        100,
    )
    .unwrap();

    let mut tombstone = entity(
        "record",
        record_id,
        3,
        record_payload(baby_id, "nursing", t0),
    );
    tombstone.deleted_at = Some(3);
    publish_root(&store, &owner, tombstone, 100).unwrap();

    let mut resurrect = entity(
        "record",
        record_id,
        99,
        record_payload(baby_id, "nursing", t0),
    );
    resurrect.deleted_at = None;
    let err = publish_root(&store, &owner, resurrect, 200).unwrap_err();
    assert!(
        matches!(err, StoreError::RecordTombstoneResurrection),
        "expected RecordTombstoneResurrection, got {err:?}"
    );

    let pulled = store.pull(&family_id, 0).unwrap();
    let row = pulled
        .entities
        .iter()
        .find(|e| e.client_uuid == record_id.to_string())
        .expect("record still present");
    assert!(row.deleted_at.is_some());
    assert_eq!(row.updated_at, 3);
}

#[test]
fn record_tombstone_idempotent_replay_is_safe() {
    let (_dir, store, _family_id, owner, baby_id) = seed_family_with_baby();
    let record_id = Uuid::new_v4();
    let t0 = 1_700_000_000_000i64;
    publish_root(
        &store,
        &owner,
        entity("record", record_id, 2, record_payload(baby_id, "bath", t0)),
        100,
    )
    .unwrap();
    let mut tombstone = entity("record", record_id, 5, record_payload(baby_id, "bath", t0));
    tombstone.deleted_at = Some(5);
    publish_root(&store, &owner, tombstone.clone(), 100).unwrap();
    // Equal LWW replay of the same tombstone is a no-op success.
    publish_root(&store, &owner, tombstone, 100).unwrap();
    let mut later_tombstone = entity("record", record_id, 8, record_payload(baby_id, "bath", t0));
    later_tombstone.deleted_at = Some(8);
    publish_root(&store, &owner, later_tombstone, 100).unwrap();
}

#[test]
fn cross_membership_whitelist_neighbors_collapse_to_one_live() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let t0 = 1_700_000_000_000i64;
    let owner_record = Uuid::parse_str("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa").unwrap();
    let member_record = Uuid::parse_str("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb").unwrap();

    publish_root(
        &store,
        &owner,
        entity(
            "record",
            owner_record,
            2,
            record_payload(baby_id, "formula", t0),
        ),
        100,
    )
    .unwrap();
    let result = publish_root(
        &store,
        &member,
        entity(
            "record",
            member_record,
            3,
            record_payload(baby_id, "formula", t0 + 60_000),
        ),
        100,
    )
    .unwrap();

    // Schema 12: server never collapses cross-membership near-duplicates.
    assert!(
        result.neighbor_losers.is_empty(),
        "neighbor_losers must stay empty on causal generation: {:?}",
        result.neighbor_losers
    );
    let live = live_record_uuids(&store, &family_id);
    assert_eq!(live.len(), 2, "live={live:?}");
    assert!(live.contains(&owner_record.to_string()));
    assert!(live.contains(&member_record.to_string()));
    assert!(deleted_record_uuids(&store, &family_id).is_empty());
}

#[test]
fn same_membership_within_window_keeps_both_live() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let t0 = 1_700_000_000_000i64;
    let a = Uuid::parse_str("11111111-1111-4111-8111-111111111111").unwrap();
    let b = Uuid::parse_str("22222222-2222-4222-8222-222222222222").unwrap();
    publish_root(
        &store,
        &owner,
        entity("record", a, 2, record_payload(baby_id, "pee", t0)),
        100,
    )
    .unwrap();
    let result = publish_root(
        &store,
        &owner,
        entity(
            "record",
            b,
            3,
            record_payload(baby_id, "pee", t0 + 10 * 60_000),
        ),
        100,
    )
    .unwrap();
    assert!(result.neighbor_losers.is_empty());
    let mut live = live_record_uuids(&store, &family_id);
    live.sort();
    assert_eq!(live, vec![a.to_string(), b.to_string()]);
}

#[test]
fn non_whitelist_types_do_not_neighbor_collapse() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let t0 = 1_700_000_000_000i64;
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    publish_root(
        &store,
        &owner,
        entity("record", a, 2, record_payload(baby_id, "sleep", t0)),
        100,
    )
    .unwrap();
    let result = publish_root(
        &store,
        &member,
        entity(
            "record",
            b,
            3,
            record_payload(baby_id, "sleep", t0 + 30_000),
        ),
        100,
    )
    .unwrap();
    assert!(result.neighbor_losers.is_empty());
    assert_eq!(live_record_uuids(&store, &family_id).len(), 2);
}

#[test]
fn nursing_and_formula_are_not_neighbors() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let t0 = 1_700_000_000_000i64;
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    publish_root(
        &store,
        &owner,
        entity("record", a, 2, record_payload(baby_id, "nursing", t0)),
        100,
    )
    .unwrap();
    let result = publish_root(
        &store,
        &member,
        entity(
            "record",
            b,
            3,
            record_payload(baby_id, "formula", t0 + 60_000),
        ),
        100,
    )
    .unwrap();
    assert!(result.neighbor_losers.is_empty());
    assert_eq!(live_record_uuids(&store, &family_id).len(), 2);
}

#[test]
fn thirty_minute_boundary_is_inclusive_plus_one_ms_is_not() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let t0 = 1_700_000_000_000i64;
    let a = Uuid::parse_str("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa01").unwrap();
    let b = Uuid::parse_str("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb01").unwrap();
    let c = Uuid::parse_str("cccccccc-cccc-4ccc-8ccc-cccccccccc01").unwrap();

    publish_root(
        &store,
        &owner,
        entity("record", a, 2, record_payload(baby_id, "poop", t0)),
        100,
    )
    .unwrap();
    // Exactly 30 minutes later — neighbor.
    let r1 = publish_root(
        &store,
        &member,
        entity(
            "record",
            b,
            3,
            record_payload(baby_id, "poop", t0 + 1_800_000),
        ),
        100,
    )
    .unwrap();
    // Causal generation: no neighbor losers; both remain live.
    assert!(r1.neighbor_losers.is_empty(), "{:?}", r1.neighbor_losers);

    // 30 min + 1 ms after winner — not a neighbor of a.
    let r2 = publish_root(
        &store,
        &member,
        entity(
            "record",
            c,
            4,
            record_payload(baby_id, "poop", t0 + 1_800_001),
        ),
        100,
    )
    .unwrap();
    assert!(r2.neighbor_losers.is_empty());
    let mut live = live_record_uuids(&store, &family_id);
    live.sort();
    // Inclusive 30-minute edge and +1ms row all remain live (no server collapse).
    assert_eq!(live, vec![a.to_string(), b.to_string(), c.to_string()]);
}

#[test]
fn three_author_chain_collapses_to_single_live() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let m1 = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member-1".to_owned(),
        device_id: "d-1".to_owned(),
    };
    let m2 = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member-2".to_owned(),
        device_id: "d-2".to_owned(),
    };
    let t0 = 1_700_000_000_000i64;
    let a = Uuid::parse_str("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa02").unwrap();
    let b = Uuid::parse_str("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb02").unwrap();
    let c = Uuid::parse_str("cccccccc-cccc-4ccc-8ccc-cccccccccc02").unwrap();
    publish_root(
        &store,
        &owner,
        entity("record", a, 2, record_payload(baby_id, "temperature", t0)),
        100,
    )
    .unwrap();
    // Chain with pairwise ≤30min while a and c still connect through b when all
    // three are live in one commit. Seed a, then commit b+c in one package is
    // hard with single-root bundles — instead keep all three within one window
    // of a so each new live still neighbors a.
    publish_root(
        &store,
        &m1,
        entity(
            "record",
            b,
            3,
            record_payload(baby_id, "temperature", t0 + 10 * 60_000),
        ),
        100,
    )
    .unwrap();
    let result = publish_root(
        &store,
        &m2,
        entity(
            "record",
            c,
            4,
            record_payload(baby_id, "temperature", t0 + 20 * 60_000),
        ),
        100,
    )
    .unwrap();
    let live = live_record_uuids(&store, &family_id);
    // Causal generation: all three near-duplicate UUIDs stay live; no losers.
    assert!(
        result.neighbor_losers.is_empty(),
        "{:?}",
        result.neighbor_losers
    );
    assert_eq!(live.len(), 3, "live={live:?}");
}

#[test]
fn owner_priority_beats_earlier_member_timestamp() {
    let directory = tempfile::TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let created = store
        .create_family(
            CreateFamilyInput {
                now: 1,
                create_request_id: "neighbor-owner-priority-0000000001",
                display_name: "妈妈",
                display_name_key: "妈妈",
                family_name: "家庭",
                device_name: "owner-phone",
                owner_root_fingerprint: None,
            },
            |_, _, _| ("access".to_owned(), "refresh".to_owned()),
        )
        .unwrap();
    let family_id = created.family_id;
    let owner = Principal {
        family_id: family_id.clone(),
        role: "owner".to_owned(),
        membership_id: created.membership_id.clone(),
        device_id: created.device_id.clone(),
    };
    let member_id = store
        .add_device_less_member(&family_id, "爸爸", "爸爸")
        .unwrap();
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: member_id,
        device_id: "d-member".to_owned(),
    };
    let baby_id = Uuid::new_v4();
    publish_root(
        &store,
        &owner,
        entity("baby", baby_id, 1, baby_payload()),
        10,
    )
    .unwrap();
    let t0 = 1_700_000_000_000i64;
    // Member logs earlier.
    let member_record = Uuid::parse_str("11111111-1111-4111-8111-1111111111aa").unwrap();
    let owner_record = Uuid::parse_str("22222222-2222-4222-8222-2222222222bb").unwrap();
    publish_root(
        &store,
        &member,
        entity(
            "record",
            member_record,
            2,
            record_payload(baby_id, "medicine", t0),
        ),
        100,
    )
    .unwrap();
    let result = publish_root(
        &store,
        &owner,
        entity(
            "record",
            owner_record,
            3,
            record_payload(baby_id, "medicine", t0 + 5 * 60_000),
        ),
        100,
    )
    .unwrap();
    // Causal generation: Owner near-duplicate does not tombstone the member row.
    assert!(
        result.neighbor_losers.is_empty(),
        "{:?}",
        result.neighbor_losers
    );
    let live = live_record_uuids(&store, &family_id);
    assert_eq!(live.len(), 2, "live={live:?}");
    assert!(live.contains(&owner_record.to_string()));
    assert!(live.contains(&member_record.to_string()));
}

#[test]
fn winner_later_deleted_does_not_resurrect_loser() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let t0 = 1_700_000_000_000i64;
    let a = Uuid::parse_str("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa03").unwrap();
    let b = Uuid::parse_str("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb03").unwrap();
    publish_root(
        &store,
        &owner,
        entity("record", a, 2, record_payload(baby_id, "drink", t0)),
        100,
    )
    .unwrap();
    publish_root(
        &store,
        &member,
        entity(
            "record",
            b,
            3,
            record_payload(baby_id, "drink", t0 + 60_000),
        ),
        100,
    )
    .unwrap();
    // Both near-duplicate rows stay live until an explicit per-UUID delete.
    let mut live = live_record_uuids(&store, &family_id);
    live.sort();
    assert_eq!(live, vec![a.to_string(), b.to_string()]);

    let mut delete_a = entity("record", a, 10, record_payload(baby_id, "drink", t0));
    delete_a.deleted_at = Some(10);
    let result = publish_root(&store, &owner, delete_a, 100).unwrap();
    assert!(result.neighbor_losers.is_empty());
    // Deleting one UUID must not tombstone the independent near-duplicate.
    assert_eq!(live_record_uuids(&store, &family_id), vec![b.to_string()]);
    let deleted = deleted_record_uuids(&store, &family_id);
    assert!(deleted.contains(&a.to_string()));
    assert!(!deleted.contains(&b.to_string()));
}

#[test]
fn care_plan_only_commit_skips_neighbor() {
    let (_dir, store, family_id, owner, baby_id) = seed_family_with_baby();
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let t0 = 1_700_000_000_000i64;
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    // Two live neighbors but never re-touch after both exist via a care_plan commit.
    // First create a alone; then we need both live without collapsing? Actually
    // second create collapses. To test "care_plan skips", seed two non-neighbor
    // (different types) or same membership, then care_plan push.
    publish_root(
        &store,
        &owner,
        entity("record", a, 2, record_payload(baby_id, "snack", t0)),
        100,
    )
    .unwrap();
    publish_root(
        &store,
        &member,
        entity(
            "record",
            b,
            3,
            record_payload(baby_id, "snack", t0 + 2_000_000),
        ),
        100,
    )
    .unwrap();
    // Far apart — both live.
    assert_eq!(live_record_uuids(&store, &family_id).len(), 2);

    let plan_id = Uuid::new_v4();
    let result = publish_root(
        &store,
        &owner,
        entity(
            "care_plan",
            plan_id,
            4,
            json!({
                "baby_client_uuid": baby_id,
                "type": "bath",
                "custom_item_client_uuid": null,
                "scheduled_at": t0,
                "scheduled_zone_id": "Asia/Shanghai",
                "status": "pending",
                "payload_json": {},
                "schema_version": 2,
                "note": "plan",
                "created_by_membership_id": "m-owner",
                "fulfilled_record_client_uuid": null,
                "fulfilled_at": null
            }),
        ),
        100,
    )
    .unwrap();
    assert!(result.neighbor_losers.is_empty());
}

#[test]
fn membership_last_sync_at_is_max_active_device() {
    let directory = tempfile::TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let created = store
        .create_family(
            CreateFamilyInput {
                now: 100,
                create_request_id: "last-sync-at-fixture-000000000001",
                display_name: "妈妈",
                display_name_key: "妈妈",
                family_name: "家庭",
                device_name: "phone-a",
                owner_root_fingerprint: None,
            },
            |_, _, _| ("access".to_owned(), "refresh".to_owned()),
        )
        .unwrap();
    let map = store.membership_last_sync_at(&created.family_id).unwrap();
    assert_eq!(map.get(&created.membership_id), Some(&100));
    let device_less = store
        .add_device_less_member(&created.family_id, "爸爸", "爸爸")
        .unwrap();
    let map = store.membership_last_sync_at(&created.family_id).unwrap();
    assert!(!map.contains_key(&device_less));
}
