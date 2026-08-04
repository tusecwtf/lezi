//! Authoritative head reconciliation through the public Store seam.

use super::super::*;
use super::test_support::*;
use serde_json::json;
use tempfile::TempDir;
use uuid::Uuid;

fn stored_entity(value: &PulledEntity) -> Entity {
    Entity {
        entity_type: value.entity_type.clone(),
        client_uuid: value.client_uuid.clone(),
        updated_at: value.updated_at,
        deleted_at: value.deleted_at,
        payload: value.payload.clone(),
    }
}

fn unit(root: Entity) -> ReconcileUnit {
    ReconcileUnit {
        content_hash: format!("local:{}", root.client_uuid),
        root,
        media: Vec::new(),
    }
}

#[test]
fn authoritative_reconcile_distinguishes_exact_publish_remote_winner_and_permanent_acl() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let absent_record_id = Uuid::new_v4();
    let member_baby_id = Uuid::new_v4();
    let baby_payload = json!({
        "nickname":"年年","sex":"female","birthday":"2025-01-02",
        "avatar_media_uuid":null,"birth_weight_grams":3200
    });
    let record_payload = |amount: i64| {
        json!({
            "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
            "end_timestamp":null,"note":null,"payload_json":{"amount_ml":amount},
            "schema_version":2
        })
    };
    publish_root(
        &store,
        &owner,
        entity("baby", baby_id, 1, baby_payload.clone()),
        10,
    )
    .unwrap();
    publish_root(
        &store,
        &owner,
        entity("record", record_id, 2, record_payload(120)),
        10,
    )
    .unwrap();

    let published = store.pull(&family_id, 0).unwrap();
    let canonical_record = stored_entity(
        published
            .entities
            .iter()
            .find(|value| value.client_uuid == record_id.to_string())
            .unwrap(),
    );
    let mut equal_revision_different_body = canonical_record.clone();
    equal_revision_different_body.payload["payload_json"] = json!({"amount_ml": 90});
    let mut newer_local = canonical_record.clone();
    newer_local.updated_at = 3;
    newer_local.payload["payload_json"] = json!({"amount_ml": 150});

    let result = store
        .reconcile_units(
            &owner,
            vec![
                unit(canonical_record),
                unit(entity("record", absent_record_id, 2, record_payload(80))),
            ],
            10,
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(result.cursor, published.cursor);
    assert_eq!(
        result.results[0].disposition,
        ReconcileDisposition::Confirmed
    );
    assert_eq!(result.results[1].disposition, ReconcileDisposition::Publish);

    let equal_conflict = store
        .reconcile_units(
            &owner,
            vec![unit(equal_revision_different_body)],
            10,
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(
        equal_conflict.results[0].disposition,
        ReconcileDisposition::AdoptRemote
    );
    assert!(equal_conflict.results[0].remote_root.is_some());

    let local_winner = store
        .reconcile_units(&owner, vec![unit(newer_local)], 10, 1_700_000_000)
        .unwrap();
    assert_eq!(
        local_winner.results[0].disposition,
        ReconcileDisposition::Publish
    );

    let rejected = store
        .reconcile_units(
            &member,
            vec![unit(entity("baby", member_baby_id, 1, baby_payload))],
            10,
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(
        rejected.results[0].disposition,
        ReconcileDisposition::RemoteAbsentRejected,
    );
    assert_eq!(rejected.results[0].reason, "forbidden_baby");
}

#[test]
fn authoritative_reconcile_uses_only_the_latest_bounded_atomic_manifest() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let baby_payload = json!({
        "nickname":"年年","sex":"female","birthday":"2025-01-02",
        "avatar_media_uuid":null,"birth_weight_grams":3200
    });
    let record_payload = json!({
        "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
        "end_timestamp":null,"note":null,"payload_json":{"amount_ml":120},
        "schema_version":2
    });
    publish_root(
        &store,
        &owner,
        entity("baby", baby_id, 1, baby_payload),
        100,
    )
    .unwrap();

    let mut latest_media = Vec::new();
    for index in 0..12 {
        let root = entity("record", record_id, 3 + index, record_payload.clone());
        let mut tombstone = entity(
            "media",
            Uuid::new_v4(),
            3 + index,
            json!({
                "kind":"log","record_client_uuid":record_id,
                "mime":"image/jpeg","byte_size":3
            }),
        );
        tombstone.deleted_at = Some(3 + index);
        latest_media = vec![tombstone];
        publish_bundle(&store, &owner, root, latest_media.clone(), 100).unwrap();
    }
    let latest_root = stored_entity(
        store
            .pull(&family_id, 0)
            .unwrap()
            .entities
            .iter()
            .find(|value| value.client_uuid == record_id.to_string())
            .unwrap(),
    );

    let result = store
        .reconcile_units(
            &owner,
            vec![ReconcileUnit {
                content_hash: "latest-record-package".to_owned(),
                root: latest_root,
                media: latest_media.clone(),
            }],
            100,
            1_700_000_000,
        )
        .unwrap();

    assert_eq!(result.results[0].remote_media, latest_media);
    assert_eq!(
        result.results[0].disposition,
        ReconcileDisposition::Confirmed
    );
}

#[test]
fn authoritative_reconcile_confirms_removed_avatar_and_publishes_an_absent_root_tombstone() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let avatar_id = Uuid::new_v4();
    let absent_record_id = Uuid::new_v4();
    let removed_baby = entity(
        "baby",
        baby_id,
        3,
        json!({
            "nickname":"年年","sex":"female","birthday":"2025-01-02",
            "avatar_media_uuid":null,"birth_weight_grams":3200
        }),
    );
    let mut removed_avatar = entity(
        "media",
        avatar_id,
        3,
        json!({
            "kind":"avatar","baby_client_uuid":baby_id,
            "mime":"image/jpeg","byte_size":3
        }),
    );
    removed_avatar.deleted_at = Some(3);
    publish_bundle(
        &store,
        &owner,
        removed_baby.clone(),
        vec![removed_avatar.clone()],
        100,
    )
    .unwrap();

    let mut redundant_record = entity(
        "record",
        absent_record_id,
        4,
        json!({
            "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
            "end_timestamp":null,"note":null,"payload_json":{"amount_ml":80},
            "schema_version":2
        }),
    );
    redundant_record.deleted_at = Some(4);
    let result = store
        .reconcile_units(
            &owner,
            vec![
                ReconcileUnit {
                    content_hash: "removed-avatar".to_owned(),
                    root: removed_baby,
                    media: vec![removed_avatar.clone()],
                },
                unit(redundant_record),
            ],
            100,
            1_700_000_000,
        )
        .unwrap();

    assert_eq!(
        result.results[0].disposition,
        ReconcileDisposition::Confirmed
    );
    assert_eq!(result.results[0].remote_media, vec![removed_avatar]);
    assert_eq!(result.results[1].disposition, ReconcileDisposition::Publish,);
    assert_eq!(result.results[1].reason, "authoritative_tombstone");
}

#[test]
fn authoritative_reconcile_reconstructs_cumulative_live_media_across_delta_bundles() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let record_payload = json!({
        "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
        "end_timestamp":null,"note":null,"payload_json":{"amount_ml":120},
        "schema_version":2
    });
    publish_root(
        &store,
        &owner,
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
    let media = |id: Uuid, updated_at: i64| {
        entity(
            "media",
            id,
            updated_at,
            json!({
                "kind":"log","record_client_uuid":record_id,
                "mime":"image/jpeg","byte_size":3
            }),
        )
    };
    let first = media(Uuid::new_v4(), 2);
    publish_bundle(
        &store,
        &owner,
        entity("record", record_id, 2, record_payload.clone()),
        vec![first.clone()],
        100,
    )
    .unwrap();
    let second = media(Uuid::new_v4(), 3);
    let published_root = entity("record", record_id, 3, record_payload);
    publish_bundle(&store, &owner, published_root, vec![second.clone()], 100).unwrap();
    let current_root = stored_entity(
        store
            .pull(&family_id, 0)
            .unwrap()
            .entities
            .iter()
            .find(|value| value.client_uuid == record_id.to_string())
            .unwrap(),
    );
    let mut complete_manifest = vec![first, second];
    complete_manifest.sort_by(|left, right| left.client_uuid.cmp(&right.client_uuid));

    let result = store
        .reconcile_units(
            &owner,
            vec![ReconcileUnit {
                content_hash: "complete-current-head".to_owned(),
                root: current_root,
                media: complete_manifest.clone(),
            }],
            100,
            1_700_000_000,
        )
        .unwrap();

    assert_eq!(result.results[0].remote_media, complete_manifest);
    assert_eq!(
        result.results[0].disposition,
        ReconcileDisposition::Confirmed
    );
}

#[test]
fn authoritative_reconcile_adjudicates_a_disaster_restored_head_without_bundle_history() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = Uuid::new_v4().to_string();
    let membership_id = Uuid::new_v4().to_string();
    let device_id = Uuid::new_v4().to_string();
    let baby_id = Uuid::new_v4();
    let record_id = Uuid::new_v4();
    let media_id = Uuid::new_v4();
    let baby = entity(
        "baby",
        baby_id,
        1,
        json!({
            "nickname":"年年","sex":"female","birthday":"2025-01-02",
            "avatar_media_uuid":null,"birth_weight_grams":3200
        }),
    );
    let record = entity(
        "record",
        record_id,
        2,
        json!({
            "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
            "end_timestamp":null,"note":null,"payload_json":{"amount_ml":120},
            "schema_version":2
        }),
    );
    let media = entity(
        "media",
        media_id,
        2,
        json!({
            "kind":"log","record_client_uuid":record_id,
            "mime":"image/jpeg","byte_size":3
        }),
    );
    store
        .activate_disaster_restore(
            DisasterRestoreIdentityInput {
                now: 1,
                family_id: &family_id,
                family_name: "家庭",
                owner_membership_id: &membership_id,
                owner_display_name: "妈妈",
                owner_display_name_key: "妈妈",
                device_id: &device_id,
                device_name: "owner",
                session_id: "restore-session",
                access_token: "restore-access",
                access_expires_at: 10_000,
                refresh_token: "restore-refresh",
                owner_root_fingerprint: None,
            },
            vec![baby, record.clone(), media.clone()],
        )
        .unwrap();
    let owner = Principal {
        family_id,
        role: "owner".to_owned(),
        membership_id,
        device_id,
    };

    let result = store
        .reconcile_units(
            &owner,
            vec![ReconcileUnit {
                content_hash: "restored-head".to_owned(),
                root: record,
                media: vec![media.clone()],
            }],
            100,
            1_700_000_000,
        )
        .unwrap();

    assert_eq!(result.results[0].remote_media, vec![media]);
    assert_eq!(
        result.results[0].disposition,
        ReconcileDisposition::Confirmed
    );
}
