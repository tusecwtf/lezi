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
    map(json!({
        "baby_client_uuid": baby,
        "type": "formula",
        "custom_item_client_uuid": null,
        "timestamp": 100,
        "end_timestamp": null,
        "note": note,
        "payload_json": {"amount_ml": amount},
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
        let store = Store::open(dir.path().join("lezi.db")).unwrap();
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

    fn commit_record(&self, principal: &Principal, uuid: Uuid, note: &str, amount: i64) -> String {
        let unit = mut_unit(
            "record",
            uuid,
            None,
            record_root(self.baby_id, note, amount, 20),
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
    let _va = fx.commit_record(&fx.owner, a, "a", 100);
    let vb = fx.commit_record(&fx.member, b, "b", 120);
    let receipt = fx
        .store
        .declare_source_relation(
            &fx.member,
            DeclareSourceRelationInput {
                mutation_id: "mut-cas".to_owned(),
                record_client_uuid: b.to_string(),
                equivalent_to_client_uuid: a.to_string(),
                expected_record_version: vb,
                expected_other_version: "stale-version".to_owned(),
            },
            1_700_000_100,
        )
        .unwrap();
    assert_eq!(receipt.status, "cas_mismatch");
    assert!(receipt.latest_versions.is_some());
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
    let err = fx
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
        .unwrap_err();
    assert!(matches!(err, StoreError::InvalidSourceRelationRequest(_)));

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
