//! Source-relation declare / Owner resolve — Store façade seams (ticket 07).

use super::super::*;
use super::test_support::*;
use serde_json::{json, Map, Value};
use tempfile::TempDir;
use uuid::Uuid;

fn map(v: Value) -> Map<String, Value> {
    v.as_object().unwrap().clone()
}

fn record_root(baby: Uuid, note: &str, amount: i64, updated_at: i64) -> Map<String, Value> {
    record_root_at(baby, "formula", 100, note, amount, updated_at)
}

fn record_root_at(
    baby: Uuid,
    record_type: &str,
    timestamp: i64,
    note: &str,
    amount: i64,
    updated_at: i64,
) -> Map<String, Value> {
    let payload = match record_type {
        "walk" => json!({}),
        _ => json!({"amount_ml": amount}),
    };
    map(json!({
        "baby_client_uuid": baby,
        "type": record_type,
        "custom_item_client_uuid": null,
        "timestamp": timestamp,
        "end_timestamp": null,
        "note": note,
        "payload_json": payload,
        "schema_version": 2,
        "updated_at": updated_at,
    }))
}

fn baby_root(nick: &str, updated_at: i64) -> Map<String, Value> {
    map(json!({
        "nickname": nick,
        "sex": "female",
        "birthday": "2025-01-02",
        "avatar_media_uuid": null,
        "updated_at": updated_at,
    }))
}

fn mut_unit(
    entity_type: &str,
    client_uuid: Uuid,
    base: Option<&str>,
    root: Map<String, Value>,
    deleted: bool,
) -> CausalMutation {
    CausalMutation {
        mutation_id: Uuid::new_v4().to_string(),
        base_version: base.map(|s| s.to_owned()),
        entity_type: entity_type.to_owned(),
        client_uuid: client_uuid.to_string(),
        root,
        media: vec![],
        deleted,
    }
}

struct Fx {
    store: Store,
    _dir: TempDir,
    family_id: String,
    owner: Principal,
    member: Principal,
    baby_id: Uuid,
}

impl Fx {
    fn new() -> Self {
        let dir = TempDir::new().unwrap();
        let store = Store::open_with_causal_admission(
            dir.path().join("lezi.db"),
            CausalAdmissionConfig {
                principal_commit_limit: u32::MAX,
                family_commit_limit: u32::MAX,
                ..CausalAdmissionConfig::default()
            },
        )
        .unwrap();
        let family_id = family(&store);
        let owner = owner_principal(&family_id);
        let baby_id = Uuid::new_v4();
        let create = mut_unit("baby", baby_id, None, baby_root("年年", 10), false);
        store
            .causal_commit(&owner, vec![create], 1_700_000_000)
            .unwrap();
        let member = Principal {
            family_id: family_id.clone(),
            role: "member".to_owned(),
            membership_id: "m-member".to_owned(),
            device_id: "d-member".to_owned(),
        };
        Self {
            store,
            _dir: dir,
            family_id,
            owner,
            member,
            baby_id,
        }
    }

    fn commit_record(&self, principal: &Principal, uuid: Uuid, note: &str, _amount: i64) -> String {
        self.commit_record_at(principal, uuid, self.baby_id, "formula", 100, note)
    }

    fn commit_record_at(
        &self,
        principal: &Principal,
        uuid: Uuid,
        baby: Uuid,
        record_type: &str,
        timestamp: i64,
        note: &str,
    ) -> String {
        let unit = mut_unit(
            "record",
            uuid,
            None,
            record_root_at(baby, record_type, timestamp, note, 100, 20),
            false,
        );
        let result = self
            .store
            .causal_commit(principal, vec![unit], 1_700_000_000)
            .unwrap();
        assert_eq!(result.results[0].status, "accepted");
        result.results[0].stable_version_id.clone().unwrap()
    }
}

fn owner_resolve(
    fx: &Fx,
    mutation_id: &str,
    members: &[Uuid],
    display: Uuid,
    versions: &[String],
) -> SourceRelationReceipt {
    fx.store
        .resolve_source_relation_group(
            &fx.owner,
            ResolveSourceRelationGroupInput {
                mutation_id: mutation_id.to_owned(),
                member_client_uuids: members.iter().map(Uuid::to_string).collect(),
                display_client_uuid: display.to_string(),
                expected_versions: members
                    .iter()
                    .zip(versions)
                    .map(|(uuid, version)| (uuid.to_string(), version.clone()))
                    .collect(),
            },
            1_700_000_200,
        )
        .unwrap()
}

#[test]
fn independent_nearby_sources_remain_live_before_explicit_relation() {
    let fx = Fx::new();
    let r1 = Uuid::new_v4();
    let r2 = Uuid::new_v4();
    fx.commit_record(&fx.owner, r1, "a", 100);
    fx.commit_record(&fx.member, r2, "b", 120);
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let live: Vec<_> = page
        .entities
        .iter()
        .filter(|e| {
            e.entity_type == "record"
                && (e.client_uuid == r1.to_string() || e.client_uuid == r2.to_string())
                && e.deleted_at.is_none()
        })
        .collect();
    assert_eq!(live.len(), 2);
}

#[test]
fn author_declare_creates_source_relation_without_tombstone() {
    let fx = Fx::new();
    let display = Uuid::new_v4();
    let source = Uuid::new_v4();
    let v_display = fx.commit_record(&fx.owner, display, "keep", 100);
    let v_source = fx.commit_record(&fx.member, source, "dup", 120);

    let receipt = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: "mut-declare-1".to_owned(),
                record_client_uuid: source.to_string(),
                equivalent_to_client_uuid: display.to_string(),
                expected_record_version: v_source.clone(),
                expected_other_version: v_display.clone(),
            },
            1_700_000_100,
        )
        .unwrap();
    assert_eq!(receipt.status, "accepted");
    assert_eq!(
        receipt.display_client_uuid.as_deref(),
        Some(display.to_string().as_str())
    );
    assert_eq!(
        receipt.source_client_uuids.as_deref(),
        Some([source.to_string()].as_slice())
    );
    assert_eq!(receipt.media_retained, Some(true));

    // Both records still live.
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    for uuid in [display, source] {
        let ent = page
            .entities
            .iter()
            .find(|e| e.client_uuid == uuid.to_string())
            .unwrap();
        assert!(ent.deleted_at.is_none());
        assert!(ent.source_relation_summary.is_some());
    }
    let source_ent = page
        .entities
        .iter()
        .find(|e| e.client_uuid == source.to_string())
        .unwrap();
    assert_eq!(
        source_ent
            .source_relation_summary
            .as_ref()
            .map(|s| s.role.as_str()),
        Some("source")
    );
}

#[test]
fn source_relation_write_reemits_unchanged_stable_roots_after_the_prior_cursor() {
    let fx = Fx::new();
    let display = Uuid::new_v4();
    let source = Uuid::new_v4();
    let display_version = fx.commit_record(&fx.owner, display, "keep", 100);
    let source_version = fx.commit_record(&fx.member, source, "source", 120);
    let cursor_before_relation = fx.store.pull(&fx.family_id, 0).unwrap().cursor;

    let receipt = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: "mut-reemit-after-cursor".to_owned(),
                record_client_uuid: source.to_string(),
                equivalent_to_client_uuid: display.to_string(),
                expected_record_version: source_version.clone(),
                expected_other_version: display_version.clone(),
            },
            1_700_000_100,
        )
        .unwrap();
    assert_eq!(receipt.status, "accepted");

    let page = fx
        .store
        .pull(&fx.family_id, cursor_before_relation)
        .unwrap();
    assert_eq!(page.entities.len(), 2);
    for (record_id, version_id) in [(display, display_version), (source, source_version)] {
        let entity = page
            .entities
            .iter()
            .find(|entity| entity.client_uuid == record_id.to_string())
            .expect("relation member must be re-emitted");
        assert_eq!(entity.version_id.as_deref(), Some(version_id.as_str()));
        assert!(entity.source_relation_summary.is_some());
    }
    assert!(!page.has_more);
}

#[test]
fn author_cannot_declare_others_record() {
    let fx = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let va = fx.commit_record(&fx.owner, a, "a", 100);
    let vb = fx.commit_record(&fx.member, b, "b", 120);
    // Member tries to declare owner's record as their own.
    let err = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: "mut-forbid".to_owned(),
                record_client_uuid: a.to_string(),
                equivalent_to_client_uuid: b.to_string(),
                expected_record_version: va,
                expected_other_version: vb,
            },
            1_700_000_100,
        )
        .unwrap_err();
    assert!(matches!(err, StoreError::ForbiddenRecord));
}

#[test]
fn declare_cas_mismatch_when_version_drifts() {
    let fx = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let va = fx.commit_record(&fx.owner, a, "a", 100);
    let vb = fx.commit_record(&fx.member, b, "b", 120);
    let input = DeclareSourceRelationInput {
        mutation_id: "mut-cas".to_owned(),
        record_client_uuid: b.to_string(),
        equivalent_to_client_uuid: a.to_string(),
        expected_record_version: vb,
        expected_other_version: "stale-version".to_owned(),
    };
    let receipt = fx
        .store
        .declare_source_relation(&fx.member, input.clone(), 1_700_000_100)
        .unwrap();
    assert_eq!(receipt.status, "cas_mismatch");
    assert!(receipt.latest_versions.is_some());

    let edit = mut_unit(
        "record",
        a,
        Some(&va),
        record_root(fx.baby_id, "a-edited", 130, 30),
        false,
    );
    fx.store
        .causal_commit(&fx.owner, vec![edit], 1_700_000_110)
        .unwrap();
    let replay = fx
        .store
        .declare_source_relation(&fx.member, input.clone(), 1_700_000_120)
        .unwrap();
    assert_eq!(replay, receipt, "CAS receipt must survive state changes");

    let mut drifted = input;
    drifted.expected_other_version = "different-stale-version".to_owned();
    assert_eq!(
        fx.store
            .declare_source_relation(&fx.member, drifted, 1_700_000_130)
            .unwrap()
            .code
            .as_deref(),
        Some("content_drift")
    );
}

#[test]
fn owner_resolve_group_covers_full_set_and_rejects_partial_cas() {
    let fx = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let c = Uuid::new_v4();
    let va = fx.commit_record(&fx.owner, a, "a", 100);
    let vb = fx.commit_record(&fx.member, b, "b", 110);
    let vc = fx.commit_record(&fx.member, c, "c", 120);

    // Incomplete expected set → invalid request.
    let rejected = fx
        .store
        .resolve_source_relation_group(
            &fx.owner,
            ResolveSourceRelationGroupInput {
                mutation_id: "mut-partial".to_owned(),
                member_client_uuids: vec![a.to_string(), b.to_string(), c.to_string()],
                display_client_uuid: a.to_string(),
                expected_versions: BTreeMap::from([
                    (a.to_string(), va.clone()),
                    (b.to_string(), vb.clone()),
                    // missing c
                ]),
            },
            1_700_000_200,
        )
        .unwrap();
    assert_eq!(
        rejected.code.as_deref(),
        Some("incomplete_expected_versions")
    );

    let mut expected = BTreeMap::new();
    expected.insert(a.to_string(), va);
    expected.insert(b.to_string(), vb);
    expected.insert(c.to_string(), vc);
    let receipt = fx
        .store
        .resolve_source_relation_group(
            &fx.owner,
            ResolveSourceRelationGroupInput {
                mutation_id: "mut-owner-1".to_owned(),
                member_client_uuids: vec![c.to_string(), a.to_string(), b.to_string()],
                display_client_uuid: a.to_string(),
                expected_versions: expected,
            },
            1_700_000_201,
        )
        .unwrap();
    assert_eq!(receipt.status, "accepted");
    assert_eq!(
        receipt.display_client_uuid.as_deref(),
        Some(a.to_string().as_str())
    );
    let sources = receipt.source_client_uuids.unwrap();
    assert_eq!(sources.len(), 2);
    assert!(sources.contains(&b.to_string()));
    assert!(sources.contains(&c.to_string()));

    // All three still live.
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    for uuid in [a, b, c] {
        let ent = page
            .entities
            .iter()
            .find(|e| e.client_uuid == uuid.to_string())
            .unwrap();
        assert!(
            ent.deleted_at.is_none(),
            "source relation must not tombstone"
        );
        assert!(ent.source_relation_summary.is_some());
    }
}

#[test]
fn owner_resolve_cas_mismatch_when_new_source_arrives() {
    let fx = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let va = fx.commit_record(&fx.owner, a, "a", 100);
    let vb = fx.commit_record(&fx.member, b, "b", 120);
    // Edit b → new version.
    let edit = mut_unit(
        "record",
        b,
        Some(&vb),
        record_root(fx.baby_id, "b-edited", 130, 30),
        false,
    );
    let edited = fx
        .store
        .causal_commit(&fx.member, vec![edit], 1_700_000_050)
        .unwrap();
    assert_eq!(edited.results[0].status, "accepted");

    let mut expected = BTreeMap::new();
    expected.insert(a.to_string(), va);
    expected.insert(b.to_string(), vb); // stale
    let receipt = fx
        .store
        .resolve_source_relation_group(
            &fx.owner,
            ResolveSourceRelationGroupInput {
                mutation_id: "mut-stale".to_owned(),
                member_client_uuids: vec![a.to_string(), b.to_string()],
                display_client_uuid: a.to_string(),
                expected_versions: expected,
            },
            1_700_000_100,
        )
        .unwrap();
    assert_eq!(receipt.status, "cas_mismatch");
    assert!(receipt
        .latest_versions
        .as_ref()
        .unwrap()
        .contains_key(&b.to_string()));
}

#[test]
fn member_cannot_owner_resolve_group() {
    let fx = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let va = fx.commit_record(&fx.owner, a, "a", 100);
    let vb = fx.commit_record(&fx.member, b, "b", 120);
    let mut expected = BTreeMap::new();
    expected.insert(a.to_string(), va);
    expected.insert(b.to_string(), vb);
    let err = fx
        .store
        .resolve_source_relation_group(
            &fx.member,
            ResolveSourceRelationGroupInput {
                mutation_id: "mut-member".to_owned(),
                member_client_uuids: vec![a.to_string(), b.to_string()],
                display_client_uuid: a.to_string(),
                expected_versions: expected,
            },
            1_700_000_100,
        )
        .unwrap_err();
    assert!(matches!(err, StoreError::ForbiddenRecord));
}

#[test]
fn declare_idempotent_on_mutation_id() {
    let fx = Fx::new();
    let display = Uuid::new_v4();
    let source = Uuid::new_v4();
    let v_display = fx.commit_record(&fx.owner, display, "keep", 100);
    let v_source = fx.commit_record(&fx.member, source, "dup", 120);
    let input = DeclareSourceRelationInput {
        mutation_id: "mut-idem".to_owned(),
        record_client_uuid: source.to_string(),
        equivalent_to_client_uuid: display.to_string(),
        expected_record_version: v_source,
        expected_other_version: v_display,
    };
    let first = fx
        .store
        .declare_source_relation(&fx.member, input.clone(), 1_700_000_100)
        .unwrap();
    let second = fx
        .store
        .declare_source_relation(&fx.member, input, 1_700_000_101)
        .unwrap();
    assert_eq!(first.relation_id, second.relation_id);
    assert_eq!(first.status, "accepted");
    assert_eq!(second.status, "accepted");
}

#[test]
fn owner_resolve_rejects_unsupported_named_record_instead_of_skipping_it() {
    let fx = Fx::new();
    let supported = Uuid::new_v4();
    let unsupported = Uuid::new_v4();
    let supported_version = fx.commit_record(&fx.owner, supported, "supported", 100);
    let unsupported_version = fx.commit_record_at(
        &fx.member,
        unsupported,
        fx.baby_id,
        "walk",
        100,
        "unsupported",
    );

    let receipt = owner_resolve(
        &fx,
        "mut-unsupported",
        &[supported, unsupported],
        supported,
        &[supported_version, unsupported_version],
    );

    assert_eq!(receipt.status, "rejected");
    assert_eq!(receipt.code.as_deref(), Some("unsupported_record_type"));
}

#[test]
fn owner_resolve_rejects_disconnected_named_extra() {
    let fx = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let far = Uuid::new_v4();
    let va = fx.commit_record_at(&fx.owner, a, fx.baby_id, "formula", 0, "a");
    let vb = fx.commit_record_at(&fx.member, b, fx.baby_id, "formula", 30 * 60 * 1000, "b");
    let vfar = fx.commit_record_at(
        &fx.member,
        far,
        fx.baby_id,
        "formula",
        31 * 60 * 1000,
        "far",
    );

    let receipt = owner_resolve(&fx, "mut-disconnected", &[a, b, far], a, &[va, vb, vfar]);

    assert_eq!(receipt.status, "rejected");
    assert_eq!(receipt.code.as_deref(), Some("disconnected_group"));
}

#[test]
fn owner_resolve_rejects_duplicate_and_oversized_requests_with_stable_codes() {
    let fx = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let va = fx.commit_record(&fx.owner, a, "a", 100);
    let vb = fx.commit_record(&fx.member, b, "b", 100);

    let duplicate = fx
        .store
        .resolve_source_relation_group(
            &fx.owner,
            ResolveSourceRelationGroupInput {
                mutation_id: "mut-duplicate".to_owned(),
                member_client_uuids: vec![a.to_string(), b.to_string(), b.to_string()],
                display_client_uuid: a.to_string(),
                expected_versions: BTreeMap::from([(a.to_string(), va), (b.to_string(), vb)]),
            },
            1_700_000_200,
        )
        .unwrap();
    assert_eq!(duplicate.code.as_deref(), Some("duplicate_member"));

    let oversized = fx
        .store
        .resolve_source_relation_group(
            &fx.owner,
            ResolveSourceRelationGroupInput {
                mutation_id: "mut-oversized".to_owned(),
                member_client_uuids: (0..=64).map(|index| format!("record-{index}")).collect(),
                display_client_uuid: "record-0".to_owned(),
                expected_versions: (0..=64)
                    .map(|index| (format!("record-{index}"), format!("version-{index}")))
                    .collect(),
            },
            1_700_000_200,
        )
        .unwrap();
    assert_eq!(oversized.code.as_deref(), Some("too_many_members"));
}

#[test]
fn relation_mutation_replay_rejects_content_drift() {
    let fx = Fx::new();
    let display = Uuid::new_v4();
    let first_source = Uuid::new_v4();
    let second_source = Uuid::new_v4();
    let display_version = fx.commit_record(&fx.owner, display, "display", 100);
    let first_version = fx.commit_record(&fx.member, first_source, "first", 100);
    let second_version = fx.commit_record(&fx.member, second_source, "second", 100);
    let mutation_id = "mut-content-drift";
    let first = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: mutation_id.to_owned(),
                record_client_uuid: first_source.to_string(),
                equivalent_to_client_uuid: display.to_string(),
                expected_record_version: first_version,
                expected_other_version: display_version.clone(),
            },
            1_700_000_100,
        )
        .unwrap();
    assert_eq!(first.status, "accepted");

    let drift = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: mutation_id.to_owned(),
                record_client_uuid: second_source.to_string(),
                equivalent_to_client_uuid: display.to_string(),
                expected_record_version: second_version,
                expected_other_version: display_version,
            },
            1_700_000_101,
        )
        .unwrap();
    assert_eq!(drift.status, "rejected");
    assert_eq!(drift.code.as_deref(), Some("content_drift"));
}

#[test]
fn owner_resolve_atomically_replaces_related_half_edge_with_one_canonical_component() {
    let fx = Fx::new();
    let display = Uuid::new_v4();
    let first_source = Uuid::new_v4();
    let second_source = Uuid::new_v4();
    let display_version = fx.commit_record(&fx.owner, display, "display", 100);
    let first_version = fx.commit_record(&fx.member, first_source, "first", 100);
    let second_version = fx.commit_record(&fx.member, second_source, "second", 100);
    let original = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: "mut-half-edge".to_owned(),
                record_client_uuid: first_source.to_string(),
                equivalent_to_client_uuid: display.to_string(),
                expected_record_version: first_version.clone(),
                expected_other_version: display_version.clone(),
            },
            1_700_000_100,
        )
        .unwrap();
    let original_relation = original.relation_id.clone().unwrap();

    let resolved = owner_resolve(
        &fx,
        "mut-replace-half-edge",
        &[display, first_source, second_source],
        display,
        &[
            display_version.clone(),
            first_version.clone(),
            second_version,
        ],
    );
    let canonical_relation = resolved.relation_id.clone().unwrap();
    assert_ne!(canonical_relation, original_relation);

    let pull = fx.store.pull(&fx.family_id, 0).unwrap();
    for record_id in [display, first_source, second_source] {
        let summary = pull
            .entities
            .iter()
            .find(|entity| entity.client_uuid == record_id.to_string())
            .and_then(|entity| entity.source_relation_summary.as_ref())
            .expect("every canonical member has a summary");
        assert_eq!(summary.relation_id, canonical_relation);
        assert_eq!(summary.peer_ids.len(), 2);
    }
    let restarted = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    let restart_pull = restarted.pull(&fx.family_id, 0).unwrap();
    assert!([display, first_source, second_source]
        .iter()
        .all(|record_id| {
            restart_pull
                .entities
                .iter()
                .find(|entity| entity.client_uuid == record_id.to_string())
                .and_then(|entity| entity.source_relation_summary.as_ref())
                .is_some_and(|summary| summary.relation_id == canonical_relation)
        }));

    let replay = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: "mut-half-edge".to_owned(),
                record_client_uuid: first_source.to_string(),
                equivalent_to_client_uuid: display.to_string(),
                expected_record_version: first_version,
                expected_other_version: display_version,
            },
            1_700_000_300,
        )
        .unwrap();
    assert_eq!(replay.status, "accepted");
    assert_eq!(
        replay.relation_id.as_deref(),
        Some(original_relation.as_str())
    );
}

#[test]
fn owner_resolve_accepts_exact_thirty_minutes_and_rejects_same_author_or_wrong_baby() {
    let boundary = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let va = boundary.commit_record_at(&boundary.owner, a, boundary.baby_id, "formula", 0, "a");
    let vb = boundary.commit_record_at(
        &boundary.member,
        b,
        boundary.baby_id,
        "formula",
        30 * 60 * 1000,
        "b",
    );
    assert_eq!(
        owner_resolve(&boundary, "mut-boundary", &[a, b], a, &[va, vb]).status,
        "accepted"
    );

    let outside = Fx::new();
    let outside_display = Uuid::new_v4();
    let outside_source = Uuid::new_v4();
    let outside_display_version = outside.commit_record_at(
        &outside.owner,
        outside_display,
        outside.baby_id,
        "formula",
        0,
        "display",
    );
    let outside_source_version = outside.commit_record_at(
        &outside.member,
        outside_source,
        outside.baby_id,
        "formula",
        30 * 60 * 1000 + 1,
        "source",
    );
    let outside_receipt = outside
        .store
        .declare_source_relation(
            &outside.member,
            DeclareSourceRelationInput {
                mutation_id: "mut-outside-window".to_owned(),
                record_client_uuid: outside_source.to_string(),
                equivalent_to_client_uuid: outside_display.to_string(),
                expected_record_version: outside_source_version,
                expected_other_version: outside_display_version,
            },
            1_700_000_200,
        )
        .unwrap();
    assert_eq!(outside_receipt.code.as_deref(), Some("outside_time_window"));

    let same_author = Fx::new();
    let c = Uuid::new_v4();
    let d = Uuid::new_v4();
    let vc = same_author.commit_record(&same_author.owner, c, "c", 100);
    let vd = same_author.commit_record(&same_author.owner, d, "d", 100);
    assert_eq!(
        owner_resolve(&same_author, "mut-same-author", &[c, d], c, &[vc, vd])
            .code
            .as_deref(),
        Some("same_author_only")
    );

    let wrong_baby = Fx::new();
    let other_baby = Uuid::new_v4();
    let create = mut_unit("baby", other_baby, None, baby_root("另一个", 10), false);
    wrong_baby
        .store
        .causal_commit(&wrong_baby.owner, vec![create], 1_700_000_000)
        .unwrap();
    let e = Uuid::new_v4();
    let f = Uuid::new_v4();
    let ve = wrong_baby.commit_record(&wrong_baby.owner, e, "e", 100);
    let vf = wrong_baby.commit_record_at(&wrong_baby.member, f, other_baby, "formula", 100, "f");
    assert_eq!(
        owner_resolve(&wrong_baby, "mut-wrong-baby", &[e, f], e, &[ve, vf])
            .code
            .as_deref(),
        Some("wrong_baby_or_type")
    );
}

#[test]
fn owner_resolve_rejects_missing_legal_neighbor() {
    let incomplete = Fx::new();
    let a = Uuid::new_v4();
    let b = Uuid::new_v4();
    let missing = Uuid::new_v4();
    let va = incomplete.commit_record(&incomplete.owner, a, "a", 100);
    let vb = incomplete.commit_record(&incomplete.member, b, "b", 100);
    incomplete.commit_record(&incomplete.owner, missing, "missing", 100);
    assert_eq!(
        owner_resolve(&incomplete, "mut-incomplete", &[a, b], a, &[va, vb])
            .code
            .as_deref(),
        Some("incomplete_group")
    );
}

#[test]
fn owner_resolve_rejects_a_candidate_scan_past_the_bound() {
    let fx = Fx::new();
    let mut named = Vec::new();
    let mut versions = Vec::new();
    for index in 0..=super::super::source_relations::MAX_SOURCE_RELATION_CANDIDATES {
        let uuid = Uuid::new_v4();
        let principal = if index % 2 == 0 {
            &fx.owner
        } else {
            &fx.member
        };
        let version = fx.commit_record(principal, uuid, &format!("candidate-{index}"), 100);
        if index < 2 {
            named.push(uuid);
            versions.push(version);
        }
    }
    let connection = fx.store.connect().unwrap();
    let mut plan_statement = connection
        .prepare(
            "EXPLAIN QUERY PLAN
             SELECT eligibility.record_client_uuid, head.version_id
             FROM source_relation_record_eligibility eligibility
             JOIN entity_stable_heads head
               ON head.family_id = eligibility.family_id AND head.entity_type = 'record'
              AND head.client_uuid = eligibility.record_client_uuid
             WHERE eligibility.family_id = ?1
               AND eligibility.baby_client_uuid = ?2
               AND eligibility.record_type = 'formula'
               AND eligibility.record_timestamp BETWEEN 0 AND 200
             ORDER BY eligibility.record_timestamp,
                      eligibility.record_client_uuid COLLATE BINARY
             LIMIT 257",
        )
        .unwrap();
    let plan = plan_statement
        .query_map(
            rusqlite::params![fx.family_id, fx.baby_id.to_string()],
            |row| row.get::<_, String>(3),
        )
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    assert!(
        plan.iter()
            .any(|step| step.contains("source_relation_eligibility_window")),
        "candidate query must use bounded eligibility index: {plan:?}"
    );

    let receipt = owner_resolve(&fx, "mut-candidate-limit", &named, named[0], &versions);

    assert_eq!(receipt.status, "rejected");
    assert_eq!(receipt.code.as_deref(), Some("candidate_limit_exceeded"));
}

#[test]
fn owner_resolve_statement_budget_is_constant_at_the_member_limit() {
    fn measured(member_count: usize) -> usize {
        let fx = Fx::new();
        let mut members = Vec::new();
        let mut versions = Vec::new();
        for index in 0..member_count {
            let uuid = Uuid::new_v4();
            let principal = if index % 2 == 0 {
                &fx.owner
            } else {
                &fx.member
            };
            versions.push(fx.commit_record(principal, uuid, &format!("record-{index}"), 100));
            members.push(uuid);
        }
        begin_pull_statement_count(&fx.family_id);
        let receipt = owner_resolve(
            &fx,
            &format!("mut-query-budget-{member_count}"),
            &members,
            members[0],
            &versions,
        );
        let statements = finish_pull_statement_count(&fx.family_id);
        assert_eq!(receipt.status, "accepted");
        statements
    }

    assert_eq!(measured(2), measured(64));
}
