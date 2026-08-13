//! Domain store tests.

use super::super::*;
use super::test_support::*;
use serde_json::json;
use tempfile::TempDir;
use uuid::Uuid;

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
