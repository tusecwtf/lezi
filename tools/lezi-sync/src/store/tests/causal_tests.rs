//! Causal reconcile / commit / pull summary / resolution — Store façade seams.

use super::super::causal::MAX_CAUSAL_UNITS;
use super::super::causal_admission::MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT;
use super::super::*;
use super::test_support::*;
use rusqlite::params;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::sync::{Arc, Barrier};
use std::thread;
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

fn sleep_root(baby: Uuid, timestamp: i64, updated_at: i64) -> Map<String, Value> {
    map(json!({
        "baby_client_uuid": baby,
        "type": "sleep",
        "custom_item_client_uuid": null,
        "timestamp": timestamp,
        "note": null,
        "payload_json": {"anomaly_flag": false, "is_nap": false},
        "schema_version": 2,
        "updated_at": updated_at,
        "effective_wake_observation_client_uuid": null,
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

struct CausalFx {
    store: Store,
    _dir: TempDir,
    family_id: String,
    owner: Principal,
    member: Principal,
    baby_id: Uuid,
}

impl CausalFx {
    fn new() -> Self {
        Self::with_admission(admission(
            u32::MAX,
            u32::MAX,
            MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT,
        ))
    }

    fn with_admission(admission: CausalAdmissionConfig) -> Self {
        let dir = TempDir::new().unwrap();
        let store =
            Store::open_with_causal_admission(dir.path().join("lezi.db"), admission).unwrap();
        let family_id = family(&store);
        let owner = owner_principal(&family_id);
        let baby_id = Uuid::new_v4();
        let create = mut_unit("baby", baby_id, None, baby_root("年年", 10), false);
        let result = store
            .causal_commit(&owner, vec![create], 1_700_000_000)
            .unwrap();
        assert_eq!(result.results[0].status, "accepted");
        let member = Principal {
            family_id: family_id.clone(),
            role: "member".to_owned(),
            membership_id: "m-causal-member".to_owned(),
            device_id: "d-causal-member".to_owned(),
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

    fn stage_media_bytes(&self, item: &mut CausalMediaItem) {
        let bytes = vec![0u8; item.byte_size as usize];
        item.sha256 = hex::encode(Sha256::digest(&bytes));
        self.store
            .stage_causal_media_preimage(
                &self.owner,
                &item.media_uuid,
                &bytes,
                &item.sha256,
                1_700_000_000,
                DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
            )
            .unwrap();
    }

    fn record_mutation(&self, record_id: Uuid, base: Option<&str>, note: &str) -> CausalMutation {
        mut_unit(
            "record",
            record_id,
            base,
            record_root(self.baby_id, note, 100, 40),
            false,
        )
    }

    fn commit(
        &self,
        principal: &Principal,
        mutation: CausalMutation,
        now: i64,
    ) -> Result<CausalBatchResult, StoreError> {
        self.store.causal_commit(principal, vec![mutation], now)
    }

    fn seed_concurrent_record(&self) -> (Uuid, String) {
        let record_id = Uuid::new_v4();
        let created = self
            .commit(
                &self.owner,
                self.record_mutation(record_id, None, "base"),
                1_700_000_000,
            )
            .unwrap();
        let base = created.results[0]
            .stable_version_id
            .clone()
            .expect("create has stable version");
        let accepted = self
            .commit(
                &self.owner,
                self.record_mutation(record_id, Some(&base), "stable"),
                1_700_000_001,
            )
            .unwrap();
        assert_eq!(accepted.results[0].status, "accepted");
        (record_id, base)
    }
}

fn admission(principal: u32, family: u32, branches: usize) -> CausalAdmissionConfig {
    CausalAdmissionConfig {
        principal_commit_limit: principal,
        family_commit_limit: family,
        window_seconds: 60,
        max_open_branches_per_root: branches,
    }
}

fn assert_principal_saturated(fx: &CausalFx, now: i64) {
    let error = fx
        .commit(
            &fx.owner,
            fx.record_mutation(Uuid::new_v4(), None, "over-budget"),
            now,
        )
        .expect_err("the preceding admission attempt must exhaust the principal budget");
    assert!(matches!(
        error,
        StoreError::CausalCommitSaturated(CausalCommitSaturation::Principal)
    ));
}

#[test]
fn causal_commit_principal_budget_exempts_exact_replay_and_resets_at_boundary() {
    let fx = CausalFx::with_admission(admission(2, 10, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    let record_id = Uuid::new_v4();
    let mutation = fx.record_mutation(record_id, None, "within-budget");
    let first = fx
        .commit(&fx.owner, mutation.clone(), 1_700_000_000)
        .unwrap();
    assert_eq!(first.results[0].status, "accepted");

    let rotated_device = Principal {
        device_id: "another-owner-device".to_owned(),
        ..fx.owner.clone()
    };
    let saturated = fx
        .commit(
            &rotated_device,
            fx.record_mutation(Uuid::new_v4(), None, "over-budget"),
            1_700_000_000,
        )
        .expect_err("device rotation must not bypass the principal budget");
    assert!(matches!(
        saturated,
        StoreError::CausalCommitSaturated(CausalCommitSaturation::Principal)
    ));

    let replay = fx.commit(&fx.owner, mutation, 1_700_000_000).unwrap();
    assert_eq!(replay.results, first.results);

    let restarted = Store::open_with_causal_admission(
        fx._dir.path().join("lezi.db"),
        admission(2, 10, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT),
    )
    .unwrap();
    restarted
        .causal_commit(
            &fx.owner,
            vec![fx.record_mutation(Uuid::new_v4(), None, "after-restart")],
            1_700_000_000,
        )
        .expect("the process-local budget resets on restart");

    let after_window = fx
        .commit(
            &fx.owner,
            fx.record_mutation(Uuid::new_v4(), None, "next-window"),
            1_700_000_060,
        )
        .unwrap();
    assert_eq!(after_window.results[0].status, "accepted");
}

#[test]
fn causal_commit_family_budget_is_shared_by_principals_and_root_types() {
    let fx = CausalFx::with_admission(admission(10, 3, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    fx.commit(
        &fx.owner,
        fx.record_mutation(Uuid::new_v4(), None, "owner"),
        1_700_000_000,
    )
    .unwrap();
    let member_mutation = fx.record_mutation(Uuid::new_v4(), None, "member");
    let member_result = fx
        .commit(&fx.member, member_mutation.clone(), 1_700_000_000)
        .unwrap();

    let saturated = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "custom_item",
                Uuid::new_v4(),
                None,
                map(json!({"name":"家庭事件", "icon_slot":1, "updated_at":20})),
                false,
            )],
            1_700_000_000,
        )
        .expect_err("all principals and root types share the family budget");
    assert!(matches!(
        saturated,
        StoreError::CausalCommitSaturated(CausalCommitSaturation::Family)
    ));

    let replay = fx
        .commit(&fx.member, member_mutation, 1_700_000_000)
        .unwrap();
    assert_eq!(replay.results, member_result.results);

    let after_window = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "custom_item",
                Uuid::new_v4(),
                None,
                map(json!({"name":"下一窗口", "icon_slot":1, "updated_at":20})),
                false,
            )],
            1_700_000_060,
        )
        .unwrap();
    assert_eq!(after_window.results[0].status, "accepted");
}

#[test]
fn causal_commit_clock_rollback_discards_every_future_sample() {
    let fx = CausalFx::with_admission(admission(2, 10, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    fx.commit(
        &fx.owner,
        fx.record_mutation(Uuid::new_v4(), None, "future"),
        1_700_000_010,
    )
    .unwrap();
    fx.commit(
        &fx.owner,
        fx.record_mutation(Uuid::new_v4(), None, "after-rollback"),
        1_700_000_005,
    )
    .expect("the t+10 sample must not consume budget after rollback to t+5");
    assert_principal_saturated(&fx, 1_700_000_005);
}

#[test]
fn causal_commit_charges_oversize_and_content_rejections_and_mixed_batches() {
    let oversize = CausalFx::with_admission(admission(2, 100, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    let units = (0..=MAX_CAUSAL_UNITS)
        .map(|index| oversize.record_mutation(Uuid::new_v4(), None, &format!("oversize-{index}")))
        .collect();
    assert!(matches!(
        oversize
            .store
            .causal_commit(&oversize.owner, units, 1_700_000_001),
        Err(StoreError::InvalidReconcileBatch)
    ));
    assert_principal_saturated(&oversize, 1_700_000_001);

    let invalid = CausalFx::with_admission(admission(2, 100, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    let rejected = invalid
        .store
        .causal_commit(
            &invalid.owner,
            vec![mut_unit(
                "unknown",
                Uuid::new_v4(),
                None,
                map(json!({"updated_at": 20})),
                false,
            )],
            1_700_000_001,
        )
        .unwrap();
    assert_eq!(rejected.results[0].status, "rejected");
    assert_principal_saturated(&invalid, 1_700_000_001);

    let drift = CausalFx::with_admission(admission(3, 100, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    let original = drift.record_mutation(Uuid::new_v4(), None, "original");
    drift
        .commit(&drift.owner, original.clone(), 1_700_000_001)
        .unwrap();
    let mut changed = original;
    changed.root.insert("note".to_owned(), json!("changed"));
    let rejected = drift.commit(&drift.owner, changed, 1_700_000_001).unwrap();
    assert_eq!(rejected.results[0].code.as_deref(), Some("content_drift"));
    assert_principal_saturated(&drift, 1_700_000_001);

    let mixed = CausalFx::with_admission(admission(3, 100, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    let replay = mixed.record_mutation(Uuid::new_v4(), None, "replay");
    mixed
        .commit(&mixed.owner, replay.clone(), 1_700_000_001)
        .unwrap();
    let result = mixed
        .store
        .causal_commit(
            &mixed.owner,
            vec![
                replay,
                mixed.record_mutation(Uuid::new_v4(), None, "new-in-mixed"),
            ],
            1_700_000_001,
        )
        .unwrap();
    assert_eq!(result.results.len(), 2);
    assert_principal_saturated(&mixed, 1_700_000_001);
}

#[test]
fn causal_commit_create_and_idempotent_replay() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut unit = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let first = fx
        .store
        .causal_commit(&fx.owner, vec![unit.clone()], 1_700_000_000)
        .unwrap();
    assert_eq!(first.results[0].status, "accepted");
    let v1 = first.results[0].stable_version_id.clone().unwrap();
    assert!(first.results[0].conflict_id.is_none());

    // Exact replay → original receipt.
    let replay = fx
        .store
        .causal_commit(&fx.owner, vec![unit.clone()], 1_700_000_001)
        .unwrap();
    assert_eq!(replay.results[0].status, "accepted");
    assert_eq!(
        replay.results[0].stable_version_id.as_deref(),
        Some(v1.as_str())
    );

    // Content drift under same mutation_id → rejected.
    unit.root.insert("note".to_owned(), json!("drift"));
    let drift = fx
        .store
        .causal_commit(&fx.owner, vec![unit], 1_700_000_002)
        .unwrap();
    assert_eq!(drift.results[0].status, "rejected");
    assert_eq!(drift.results[0].code.as_deref(), Some("content_drift"));
}

#[test]
fn causal_reconcile_publish_then_commit_accepted() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let unit = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "n", 80, 15),
        false,
    );
    let dry = fx
        .store
        .causal_reconcile(&fx.owner, vec![unit.clone()], 1_700_000_000)
        .unwrap();
    assert_eq!(dry.results[0].status, "publish");

    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![unit.clone()], 1_700_000_000)
        .unwrap();
    assert_eq!(committed.results[0].status, "accepted");
    let v = committed.results[0].stable_version_id.clone().unwrap();

    // Confirmed when expectation matches stable.
    let mut same = unit;
    same.mutation_id = Uuid::new_v4().to_string();
    same.base_version = Some(v.clone());
    let conf = fx
        .store
        .causal_reconcile(&fx.owner, vec![same], 1_700_000_001)
        .unwrap();
    assert_eq!(conf.results[0].status, "confirmed");
}

#[test]
fn causal_current_base_delete_accepted_with_tombstone_conflict_handle() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "live", 100, 20),
        false,
    );
    let created = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap();
    let v1 = created.results[0].stable_version_id.clone().unwrap();

    let del = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "live", 100, 21),
        true,
    );
    let deleted = fx
        .store
        .causal_commit(&fx.owner, vec![del.clone()], 1_700_000_001)
        .unwrap();
    assert_eq!(deleted.results[0].status, "accepted");
    let v2 = deleted.results[0].stable_version_id.clone().unwrap();
    assert_ne!(v1, v2);
    let conflict_id = deleted.results[0].conflict_id.clone().unwrap();

    // Projection is tombstone.
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let row = page
        .entities
        .iter()
        .find(|e| e.client_uuid == record_id.to_string())
        .unwrap();
    assert!(row.deleted_at.is_some());

    // Idempotent delete replay.
    let replay = fx
        .store
        .causal_commit(&fx.owner, vec![del], 1_700_000_002)
        .unwrap();
    assert_eq!(replay.results[0].status, "accepted");
    assert_eq!(
        replay.results[0].stable_version_id.as_deref(),
        Some(v2.as_str())
    );

    // Stale live over tombstone rejected (例 F).
    let stale = mut_unit(
        "record",
        record_id,
        Some(&v1), // not a parent of concurrent proof path if parent wrong
        record_root(fx.baby_id, "zombie", 100, 22),
        false,
    );
    // v1 IS parent of v2, so concurrent edit would branch. Use unknown base.
    let mut stale = stale;
    stale.base_version = Some(Uuid::new_v4().to_string());
    let rejected = fx
        .store
        .causal_commit(&fx.owner, vec![stale], 1_700_000_003)
        .unwrap();
    assert_eq!(rejected.results[0].status, "rejected");
    assert_eq!(
        rejected.results[0].code.as_deref(),
        Some("stale_live_over_tombstone")
    );

    // Detail exposes tombstone restore handle.
    let detail = fx.store.conflict_detail(&fx.owner, &conflict_id).unwrap();
    assert!(detail
        .conflicting_paths
        .iter()
        .any(|p| p == "/_mutation.deleted"));
    assert!(detail.branches.is_empty());
}

#[test]
fn causal_concurrent_edit_after_delete_branches() {
    // wire 例 J: delete first, then same-base live edit → branched
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    let del = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "a", 100, 21),
        true,
    );
    let deleted = fx
        .store
        .causal_commit(&fx.owner, vec![del], 1_700_000_001)
        .unwrap();
    assert_eq!(deleted.results[0].status, "accepted");
    let v2 = deleted.results[0].stable_version_id.clone().unwrap();

    let edit = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "b", 100, 22),
        false,
    );
    let branched = fx
        .store
        .causal_commit(&fx.owner, vec![edit], 1_700_000_002)
        .unwrap();
    assert_eq!(branched.results[0].status, "branched");
    assert_eq!(
        branched.results[0].stable_version_id.as_deref(),
        Some(v2.as_str())
    );
    assert!(branched.results[0].branch_version_id.is_some());
    assert!(branched.results[0].conflict_id.is_some());

    // Stable projection remains tombstone.
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let row = page
        .entities
        .iter()
        .find(|e| e.client_uuid == record_id.to_string())
        .unwrap();
    assert!(row.deleted_at.is_some());
    // Branch-only write advanced rev so peer can discover conflict.
    assert!(row.rev > 0);
}

#[test]
fn causal_disjoint_fields_merge() {
    // Two devices: base V1, A changes note, B changes amount → merged
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    let left = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "b", 100, 30),
        false,
    );
    let left_result = fx
        .store
        .causal_commit(&fx.owner, vec![left], 1_700_000_001)
        .unwrap();
    assert_eq!(left_result.results[0].status, "accepted");
    let v2 = left_result.results[0].stable_version_id.clone().unwrap();

    let right = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "a", 120, 40),
        false,
    );
    let merged = fx
        .store
        .causal_commit(&fx.owner, vec![right], 1_700_000_002)
        .unwrap();
    assert_eq!(merged.results[0].status, "merged");
    let root = &merged.results[0].stable_root;
    assert_eq!(root.get("note").and_then(Value::as_str), Some("b"));
    assert_eq!(
        root.get("payload_json")
            .and_then(|p| p.get("amount_ml"))
            .and_then(Value::as_i64),
        Some(120)
    );
    assert_eq!(root.get("updated_at").and_then(Value::as_i64), Some(40));
    assert_ne!(
        merged.results[0].stable_version_id.as_deref(),
        Some(v2.as_str())
    );
}

#[test]
fn causal_same_field_conflict_branches_and_resolve_cas() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    let left = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "b", 100, 30),
        false,
    );
    let left_result = fx
        .store
        .causal_commit(&fx.owner, vec![left], 1_700_000_001)
        .unwrap();
    assert_eq!(left_result.results[0].status, "accepted");
    let v2 = left_result.results[0].stable_version_id.clone().unwrap();

    // Reconcile preview before commit.
    let right = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "c", 100, 40),
        false,
    );
    let preview = fx
        .store
        .causal_reconcile(&fx.owner, vec![right.clone()], 1_700_000_002)
        .unwrap();
    assert_eq!(preview.results[0].status, "conflict_preview");

    let branched = fx
        .store
        .causal_commit(&fx.owner, vec![right], 1_700_000_002)
        .unwrap();
    assert_eq!(branched.results[0].status, "branched");
    assert_eq!(
        branched.results[0].stable_version_id.as_deref(),
        Some(v2.as_str())
    );
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let branch_id = branched.results[0].branch_version_id.clone().unwrap();

    let detail = fx.store.conflict_detail(&fx.owner, &conflict_id).unwrap();
    assert!(detail.conflicting_paths.iter().any(|p| p == "/note"));
    assert_eq!(detail.branches.len(), 1);

    // CAS mismatch returns latest summary without write.
    let bad = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                expected_stable_version: "wrong".into(),
                expected_branch_versions: vec![branch_id.clone()],
                resolved_root: record_root(fx.baby_id, "c", 100, 50),
                resolved_media: vec![],
                resolution_mutation_id: Uuid::new_v4().to_string(),
                conflict_choices: map(json!({"/note": "c"})),
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(bad.status, "cas_mismatch");
    assert!(bad.conflict_summary.is_some());

    // Successful resolve.
    let mut choices = Map::new();
    choices.insert("/note".to_owned(), json!("c"));
    let ok = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                expected_stable_version: v2.clone(),
                expected_branch_versions: vec![branch_id],
                resolved_root: record_root(fx.baby_id, "c", 100, 50),
                resolved_media: vec![],
                resolution_mutation_id: Uuid::new_v4().to_string(),
                conflict_choices: choices,
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(ok.status, "resolved");
    assert_eq!(
        ok.stable_root.get("note").and_then(Value::as_str),
        Some("c")
    );

    // Conflict closed.
    assert!(fx.store.conflict_detail(&fx.owner, &conflict_id).is_err());
}

#[test]
fn causal_explicit_tombstone_restore_via_resolution() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let del = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "a", 100, 21),
        true,
    );
    let deleted = fx
        .store
        .causal_commit(&fx.owner, vec![del], 1_700_000_001)
        .unwrap();
    let v2 = deleted.results[0].stable_version_id.clone().unwrap();
    let conflict_id = deleted.results[0].conflict_id.clone().unwrap();

    let mut choices = Map::new();
    choices.insert("/_mutation.deleted".to_owned(), json!(false));
    let restored = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                expected_stable_version: v2,
                expected_branch_versions: vec![],
                resolved_root: record_root(fx.baby_id, "a", 100, 30),
                resolved_media: vec![],
                resolution_mutation_id: Uuid::new_v4().to_string(),
                conflict_choices: choices,
            },
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(restored.status, "resolved");
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let row = page
        .entities
        .iter()
        .find(|e| e.client_uuid == record_id.to_string())
        .unwrap();
    assert!(row.deleted_at.is_none());
}

#[test]
fn causal_independent_media_merge_and_delete_edit_branch() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut m1 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "a".repeat(64),
        byte_size: 10,
        mime: "image/jpeg".into(),
        width: Some(1),
        height: Some(1),
    };
    fx.stage_media_bytes(&mut m1);
    let mut create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    create.media = vec![m1.clone()];
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    let mut m2 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "b".repeat(64),
        byte_size: 20,
        mime: "image/jpeg".into(),
        width: None,
        height: None,
    };
    fx.stage_media_bytes(&mut m2);
    // Side A: remove m1
    let mut left = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "a", 100, 30),
        false,
    );
    left.media = vec![];
    let left_r = fx
        .store
        .causal_commit(&fx.owner, vec![left], 1_700_000_001)
        .unwrap();
    assert_eq!(left_r.results[0].status, "accepted");

    // Side B from v1: add m2 (independent) — should merge with empty+m2
    let mut right = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "a", 100, 40),
        false,
    );
    right.media = vec![m1.clone(), m2.clone()];
    // Wait: base has m1, stable has [], incoming has m1+m2.
    // stable changed: -m1; incoming: +m2 (m1 same as base). Merge → [m2].
    let merged = fx
        .store
        .causal_commit(&fx.owner, vec![right], 1_700_000_002)
        .unwrap();
    assert_eq!(merged.results[0].status, "merged");
    let ids: Vec<_> = merged.results[0]
        .stable_media
        .iter()
        .map(|m| m.media_uuid.clone())
        .collect();
    assert_eq!(ids, vec![m2.media_uuid.clone()]);

    // Fresh root for delete/edit same media conflict.
    let record2 = Uuid::new_v4();
    let mut m3 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "d".repeat(64),
        byte_size: 10,
        mime: "image/jpeg".into(),
        width: None,
        height: None,
    };
    fx.stage_media_bytes(&mut m3);
    let mut create2 = mut_unit(
        "record",
        record2,
        None,
        record_root(fx.baby_id, "x", 10, 50),
        false,
    );
    create2.media = vec![m3.clone()];
    let base = fx
        .store
        .causal_commit(&fx.owner, vec![create2], 1_700_000_003)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let mut del_media = mut_unit(
        "record",
        record2,
        Some(&base),
        record_root(fx.baby_id, "x", 10, 51),
        false,
    );
    del_media.media = vec![];
    fx.store
        .causal_commit(&fx.owner, vec![del_media], 1_700_000_004)
        .unwrap();
    let mut edit_media = mut_unit(
        "record",
        record2,
        Some(&base),
        record_root(fx.baby_id, "x", 10, 52),
        false,
    );
    let mut m3_edit = m3;
    // Same UUID may edit descriptive manifest fields, but never claim different
    // bytes: the durable preimage contract makes UUID+SHA immutable.
    m3_edit.width = Some(2);
    edit_media.media = vec![m3_edit];
    let branch = fx
        .store
        .causal_commit(&fx.owner, vec![edit_media], 1_700_000_005)
        .unwrap();
    assert_eq!(branch.results[0].status, "branched");
}

#[test]
fn causal_member_acl_rejects_baby_and_foreign_record_edit() {
    let fx = CausalFx::new();
    let member = Principal {
        family_id: fx.family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let baby_version = fx
        .store
        .pull(&fx.family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .find(|e| e.client_uuid == fx.baby_id.to_string())
        .and_then(|e| e.version_id);
    let baby_edit = mut_unit(
        "baby",
        fx.baby_id,
        baby_version.as_deref(),
        baby_root("hack", 99),
        false,
    );
    let denied = fx
        .store
        .causal_commit(&member, vec![baby_edit], 1_700_000_000)
        .unwrap();
    assert_eq!(denied.results[0].status, "rejected");
    assert_eq!(denied.results[0].code.as_deref(), Some("forbidden_baby"));
}

#[test]
fn causal_pull_exposes_version_id_and_branch_conflict_summary() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let left = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "b", 100, 30),
        false,
    );
    let v2 = fx
        .store
        .causal_commit(&fx.owner, vec![left], 1_700_000_001)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let right = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "c", 100, 40),
        false,
    );
    let branched = fx
        .store
        .causal_commit(&fx.owner, vec![right], 1_700_000_002)
        .unwrap();
    assert_eq!(branched.results[0].status, "branched");
    let cursor_before = branched.cursor;

    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let row = page
        .entities
        .iter()
        .find(|e| e.client_uuid == record_id.to_string())
        .unwrap();
    assert_eq!(row.version_id.as_deref(), Some(v2.as_str()));
    let summary = row.conflict_summary.as_ref().expect("conflict_summary");
    assert!(!summary.branch_version_ids.is_empty());
    assert_eq!(summary.stable_version_id, v2);
    // Branch-only write advanced cursor past the accepted edit.
    assert!(cursor_before >= page.entities.iter().map(|e| e.rev).max().unwrap_or(0));
}

fn create_open_record_conflict(fx: &CausalFx, creator: &Principal, index: usize) -> (Uuid, String) {
    let record_id = Uuid::new_v4();
    let created = fx
        .store
        .causal_commit(
            creator,
            vec![mut_unit(
                "record",
                record_id,
                None,
                record_root(fx.baby_id, &format!("base-{index}"), 100, 20),
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.as_deref().unwrap();
    let accepted = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                record_id,
                Some(base),
                record_root(fx.baby_id, &format!("stable-{index}"), 100, 30),
                false,
            )],
            1_700_000_001,
        )
        .unwrap();
    assert_eq!(accepted.results[0].status, "accepted");
    let branched = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                record_id,
                Some(base),
                record_root(fx.baby_id, &format!("branch-{index}"), 100, 40),
                false,
            )],
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(branched.results[0].status, "branched");
    let stable_version = accepted.results[0]
        .stable_version_id
        .clone()
        .expect("accepted edit has stable version");
    (record_id, stable_version)
}

#[test]
fn causal_commit_caps_open_branches_without_hiding_durable_branches() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let first_branch = fx.record_mutation(record_id, Some(&base), "branch-0");
    let first = fx
        .commit(&fx.owner, first_branch.clone(), 1_700_000_002)
        .unwrap();
    assert_eq!(first.results[0].status, "branched");
    let conflict_id = first.results[0]
        .conflict_id
        .as_deref()
        .expect("first branch has conflict id")
        .to_owned();

    for index in 1..MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT {
        let result = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, Some(&base), &format!("branch-{index}")),
                1_700_000_002,
            )
            .unwrap();
        assert_eq!(result.results[0].status, "branched", "branch {index}");
        if index + 1 == MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT - 1 {
            assert_eq!(
                fx.store
                    .conflict_detail(&fx.owner, &conflict_id)
                    .unwrap()
                    .branches
                    .len(),
                MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT - 1,
            );
        }
    }

    let replay = fx.commit(&fx.owner, first_branch, 1_700_000_002).unwrap();
    assert_eq!(replay.results, first.results, "exact replay must be stable");

    let overflow = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-overflow"),
            1_700_000_002,
        )
        .expect_err("the first branch above the cap must be rejected");
    assert!(matches!(
        overflow,
        StoreError::CausalCommitSaturated(CausalCommitSaturation::OpenBranch)
    ));

    let detail = fx.store.conflict_detail(&fx.owner, &conflict_id).unwrap();
    assert_eq!(detail.branches.len(), MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT);

    let restarted = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    assert_eq!(
        restarted
            .conflict_detail(&fx.owner, &conflict_id)
            .unwrap()
            .branches
            .len(),
        MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT,
    );
    let restart_overflow = restarted
        .causal_commit(
            &fx.owner,
            vec![fx.record_mutation(record_id, Some(&base), "restart-overflow")],
            1_700_000_003,
        )
        .expect_err("durable branch capacity must survive restart");
    assert!(matches!(
        restart_overflow,
        StoreError::CausalCommitSaturated(CausalCommitSaturation::OpenBranch)
    ));

    let detail = restarted.conflict_detail(&fx.owner, &conflict_id).unwrap();
    let branch_ids = detail
        .branches
        .iter()
        .map(|branch| branch.branch_version_id.clone())
        .collect::<Vec<_>>();
    let resolved = restarted
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                expected_stable_version: detail.stable_version_id,
                expected_branch_versions: branch_ids,
                resolved_root: record_root(fx.baby_id, "branch-0", 100, 50),
                resolved_media: vec![],
                resolution_mutation_id: Uuid::new_v4().to_string(),
                conflict_choices: map(json!({"/note": "branch-0"})),
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(resolved.status, "resolved");
    let durable_count: usize = restarted
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM conflict_branches
             WHERE family_id = ?1 AND conflict_id = ?2",
            params![fx.family_id, conflict_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(durable_count, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT);
    let after_resolution = restarted
        .causal_commit(
            &fx.owner,
            vec![fx.record_mutation(record_id, Some(&base), "after-resolution")],
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(after_resolution.results[0].status, "branched");
}

#[test]
fn causal_branch_cap_spans_open_conflicts_and_ignores_empty_or_resolved_handles() {
    let fx = CausalFx::with_admission(admission(20, 20, 4));
    let (record_id, base) = fx.seed_concurrent_record();
    let mut first_conflict = String::new();
    for index in 0..3 {
        let result = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, Some(&base), &format!("branch-{index}")),
                1_700_000_002,
            )
            .unwrap();
        first_conflict = result.results[0].conflict_id.clone().unwrap();
    }
    let first_detail = fx
        .store
        .conflict_detail(&fx.owner, &first_conflict)
        .unwrap();
    let moved_branch = first_detail.branches[0].branch_version_id.clone();
    let second_conflict = Uuid::new_v4().to_string();
    let empty_conflict = Uuid::new_v4().to_string();
    let connection = fx.store.connect().unwrap();
    for (conflict_id, kind, created_at) in [
        (&second_conflict, "concurrent", 1_700_000_003),
        (&empty_conflict, "tombstone_restore", 1_700_000_004),
    ] {
        connection
            .execute(
                "INSERT INTO conflicts(
                    family_id, conflict_id, entity_type, client_uuid, base_version_id,
                    stable_version_id, status, kind, created_at, resolved_at
                 ) VALUES (?1, ?2, 'record', ?3, NULL, ?4, 'open', ?5, ?6, NULL)",
                params![
                    fx.family_id,
                    conflict_id,
                    record_id.to_string(),
                    first_detail.stable_version_id,
                    kind,
                    created_at,
                ],
            )
            .unwrap();
    }
    connection
        .execute(
            "UPDATE conflict_branches SET conflict_id = ?1
             WHERE family_id = ?2 AND conflict_id = ?3 AND branch_version_id = ?4",
            params![second_conflict, fx.family_id, first_conflict, moved_branch,],
        )
        .unwrap();
    drop(connection);

    assert_eq!(
        fx.store
            .conflict_detail(&fx.owner, &first_conflict)
            .unwrap()
            .branches
            .len(),
        2,
    );
    assert_eq!(
        fx.store
            .conflict_detail(&fx.owner, &second_conflict)
            .unwrap()
            .branches
            .len(),
        1,
    );
    assert!(fx
        .store
        .conflict_detail(&fx.owner, &empty_conflict)
        .unwrap()
        .branches
        .is_empty());

    fx.commit(
        &fx.owner,
        fx.record_mutation(record_id, Some(&base), "branch-at-cap"),
        1_700_000_005,
    )
    .unwrap();
    assert!(matches!(
        fx.commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-over-cap"),
            1_700_000_005,
        ),
        Err(StoreError::CausalCommitSaturated(
            CausalCommitSaturation::OpenBranch
        ))
    ));

    fx.store
        .connect()
        .unwrap()
        .execute(
            "UPDATE conflicts SET status = 'resolved', resolved_at = ?1
             WHERE family_id = ?2 AND conflict_id = ?3",
            params![1_700_000_006, fx.family_id, second_conflict],
        )
        .unwrap();
    let admitted = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "after-resolved"),
            1_700_000_006,
        )
        .unwrap();
    assert_eq!(admitted.results[0].status, "branched");
    assert_eq!(
        fx.store
            .conflict_detail(&fx.owner, &first_conflict)
            .unwrap()
            .branches
            .len(),
        4,
    );
}

#[test]
fn concurrent_causal_commits_cannot_overallocate_branch_capacity() {
    let fx = CausalFx::with_admission(admission(10, 10, 2));
    let (record_id, base) = fx.seed_concurrent_record();
    let first = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-0"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = first.results[0].conflict_id.clone().unwrap();

    let barrier = Arc::new(Barrier::new(3));
    let handles = ["branch-a", "branch-b"].map(|note| {
        let store = fx.store.clone();
        let principal = fx.owner.clone();
        let barrier = barrier.clone();
        let base = base.clone();
        let mutation = fx.record_mutation(record_id, Some(&base), note);
        thread::spawn(move || {
            barrier.wait();
            store.causal_commit(&principal, vec![mutation], 1_700_000_002)
        })
    });
    barrier.wait();
    let outcomes = handles.map(|handle| handle.join().unwrap());
    assert_eq!(
        outcomes
            .iter()
            .filter(|result| result
                .as_ref()
                .is_ok_and(|batch| { batch.results[0].status == "branched" }))
            .count(),
        1,
    );
    assert_eq!(
        outcomes
            .iter()
            .filter(|result| matches!(
                result,
                Err(StoreError::CausalCommitSaturated(
                    CausalCommitSaturation::OpenBranch
                ))
            ))
            .count(),
        1,
    );
    assert_eq!(
        fx.store
            .conflict_detail(&fx.owner, &conflict_id)
            .unwrap()
            .branches
            .len(),
        2,
    );
}

#[test]
fn pull_sql_statement_count_is_constant_for_conflicted_record_pages() {
    fn measured_statement_count(conflict_count: usize) -> usize {
        let fx = CausalFx::new();
        let conflicts = (0..conflict_count)
            .map(|index| {
                let creator = if index + 1 == conflict_count {
                    &fx.member
                } else {
                    &fx.owner
                };
                create_open_record_conflict(&fx, creator, index)
            })
            .collect::<Vec<_>>();
        if conflict_count > 1 {
            let (display_id, display_version) = &conflicts[0];
            let (source_id, source_version) = conflicts.last().unwrap();
            fx.store
                .declare_source_relation(
                    &fx.member,
                    DeclareSourceRelationInput {
                        mutation_id: format!("mut-query-count-{conflict_count}"),
                        record_client_uuid: source_id.to_string(),
                        equivalent_to_client_uuid: display_id.to_string(),
                        expected_record_version: source_version.clone(),
                        expected_other_version: display_version.clone(),
                    },
                    1_700_000_100,
                )
                .unwrap();
        }

        begin_pull_statement_count(&fx.family_id);
        let mut page = fx.store.pull(&fx.family_id, 0).unwrap();
        let statement_count = finish_pull_statement_count(&fx.family_id);
        assert_eq!(
            page.entities
                .iter()
                .filter(|entity| entity.conflict_summary.is_some())
                .count(),
            conflict_count.min(32),
        );
        if conflict_count > 1 {
            let mut relation_discovered = page
                .entities
                .iter()
                .any(|entity| entity.source_relation_summary.is_some());
            while page.has_more {
                let prior_cursor = page.cursor;
                page = fx.store.pull(&fx.family_id, prior_cursor).unwrap();
                assert!(page.cursor > prior_cursor);
                relation_discovered |= page
                    .entities
                    .iter()
                    .any(|entity| entity.source_relation_summary.is_some());
            }
            assert!(relation_discovered);
        }
        statement_count
    }

    let one_record = measured_statement_count(1);
    let one_hundred_records = measured_statement_count(100);
    assert_eq!(one_hundred_records, one_record);
}

#[test]
fn pull_paginates_every_mandatory_conflict_summary_without_cursor_loss() {
    for conflict_count in [31, 32, 33] {
        let fx = CausalFx::new();
        let conflicts = (0..conflict_count)
            .map(|index| {
                let creator = if index + 1 == conflict_count {
                    &fx.member
                } else {
                    &fx.owner
                };
                create_open_record_conflict(&fx, creator, index)
            })
            .collect::<Vec<_>>();
        if conflict_count == 33 {
            let (display_id, display_version) = &conflicts[0];
            let (source_id, source_version) = &conflicts[32];
            let relation = fx
                .store
                .declare_source_relation(
                    &fx.member,
                    DeclareSourceRelationInput {
                        mutation_id: "mut-conflict-cap-relation".to_owned(),
                        record_client_uuid: source_id.to_string(),
                        equivalent_to_client_uuid: display_id.to_string(),
                        expected_record_version: source_version.clone(),
                        expected_other_version: display_version.clone(),
                    },
                    1_700_000_100,
                )
                .unwrap();
            assert_eq!(relation.status, "accepted");
        }

        let first = fx.store.pull(&fx.family_id, 0).unwrap();
        let first_summaries = first
            .entities
            .iter()
            .filter(|entity| entity.conflict_summary.is_some())
            .map(|entity| entity.client_uuid.clone())
            .collect::<std::collections::BTreeSet<_>>();

        if conflict_count <= 32 {
            assert_eq!(first_summaries.len(), conflict_count);
            assert!(!first.has_more);
            continue;
        }

        assert_eq!(first_summaries.len(), 32);
        assert!(first.has_more);
        let second = fx.store.pull(&fx.family_id, first.cursor).unwrap();
        let discovered = first
            .entities
            .iter()
            .chain(second.entities.iter())
            .filter_map(|entity| {
                entity
                    .conflict_summary
                    .as_ref()
                    .map(|_| entity.client_uuid.clone())
            })
            .collect::<std::collections::BTreeSet<_>>();
        assert_eq!(discovered.len(), conflicts.len());
        assert!(conflicts
            .iter()
            .all(|(id, _)| discovered.contains(id.to_string().as_str())));
        for (relation_id, _) in [&conflicts[0], &conflicts[32]] {
            let relation_entity = first
                .entities
                .iter()
                .chain(second.entities.iter())
                .find(|entity| entity.client_uuid == relation_id.to_string())
                .expect("relation member remains pull-visible across conflict cap");
            assert!(relation_entity.source_relation_summary.is_some());
        }
        assert!(!second.has_more);
    }
}

#[test]
fn causal_resolve_rejects_rewrote_auto_merged_path() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let left = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "b", 100, 30),
        false,
    );
    let v2 = fx
        .store
        .causal_commit(&fx.owner, vec![left], 1_700_000_001)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let right = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "c", 100, 40),
        false,
    );
    let branched = fx
        .store
        .causal_commit(&fx.owner, vec![right], 1_700_000_002)
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let branch_id = branched.results[0].branch_version_id.clone().unwrap();
    // Client rewrites amount_ml which is not a conflict path (still 100 both sides).
    let bad_root = record_root(fx.baby_id, "c", 999, 50);
    let mut choices = Map::new();
    choices.insert("/note".to_owned(), json!("c"));
    let rejected = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                expected_stable_version: v2,
                expected_branch_versions: vec![branch_id],
                resolved_root: bad_root,
                resolved_media: vec![],
                resolution_mutation_id: Uuid::new_v4().to_string(),
                conflict_choices: choices,
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(rejected.status, "rejected");
    assert_eq!(rejected.code.as_deref(), Some("rewrote_auto_merged_path"));
}

#[test]
fn causal_reconcile_live_over_tombstone_is_conflict_preview_not_branched() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let del = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "a", 100, 21),
        true,
    );
    fx.store
        .causal_commit(&fx.owner, vec![del], 1_700_000_001)
        .unwrap();
    let edit = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "b", 100, 22),
        false,
    );
    let dry = fx
        .store
        .causal_reconcile(&fx.owner, vec![edit], 1_700_000_002)
        .unwrap();
    assert_eq!(dry.results[0].status, "conflict_preview");
    assert!(dry.results[0].branch_version_id.is_none());
    assert!(dry.results[0].conflict_id.is_none());
    assert!(dry.results[0]
        .conflicting_paths
        .as_ref()
        .unwrap()
        .iter()
        .any(|p| p == "/_mutation.deleted"));
}

#[test]
fn causal_unknown_root_field_rejected() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut unit = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    unit.root.insert("not_a_wire_field".to_owned(), json!("x"));
    let result = fx
        .store
        .causal_commit(&fx.owner, vec![unit], 1_700_000_000)
        .unwrap();
    assert_eq!(result.results[0].status, "rejected");
    assert_eq!(result.results[0].code.as_deref(), Some("unknown_field"));
}

#[test]
fn causal_ingress_accepts_the_canonical_diary_payload() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut root = record_root(fx.baby_id, "日记", 100, 20);
    root.insert("type".to_owned(), json!("diary"));
    root.insert("payload_json".to_owned(), json!({"body": "今天第一次翻身"}));

    let result = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit("record", record_id, None, root, false)],
            1_700_000_000,
        )
        .unwrap();

    assert_eq!(result.results[0].status, "accepted", "{result:?}");
}

#[test]
fn causal_ingress_matches_current_android_typed_payloads() {
    let fx = CausalFx::new();
    let custom_item_id = Uuid::new_v4();
    let custom = mut_unit(
        "custom_item",
        custom_item_id,
        None,
        map(json!({"name": "维生素D", "icon_slot": 2, "updated_at": 15})),
        false,
    );
    assert_eq!(
        fx.store
            .causal_commit(&fx.owner, vec![custom], 1_700_000_000)
            .unwrap()
            .results[0]
            .status,
        "accepted"
    );

    let fixtures = [
        ("diary", json!({"body": "今天第一次翻身"}), None),
        ("cough", json!({"severity": 2, "description": "偶尔"}), None),
        ("height", json!({"value": 52.5, "unit": "cm"}), None),
        (
            "baby_food",
            json!({"content": "南瓜泥", "amount": "两勺"}),
            None,
        ),
        (
            "custom",
            json!({"title": "维生素D", "detail": "1滴", "icon_slot": 2}),
            Some(custom_item_id),
        ),
    ];
    for (record_type, payload, custom_item) in fixtures {
        let mut root = record_root(fx.baby_id, record_type, 100, 20);
        root.insert("type".to_owned(), json!(record_type));
        root.insert("payload_json".to_owned(), payload);
        root.insert(
            "custom_item_client_uuid".to_owned(),
            custom_item.map_or(Value::Null, |id| json!(id)),
        );
        let result = fx
            .store
            .causal_commit(
                &fx.owner,
                vec![mut_unit("record", Uuid::new_v4(), None, root, false)],
                1_700_000_001,
            )
            .unwrap();
        assert_eq!(
            result.results[0].status, "accepted",
            "{record_type}: {result:?}"
        );
    }
}

#[test]
fn causal_ingress_rejects_invalid_canonical_values_with_stable_codes() {
    let fx = CausalFx::new();
    let negative_amount = record_root(fx.baby_id, "negative", -1, 20);
    let mut missing_typed_required = record_root(fx.baby_id, "missing", 1, 20);
    missing_typed_required.insert("type".to_owned(), json!("diary"));
    missing_typed_required.insert("payload_json".to_owned(), json!({}));
    let mut schema_drift = record_root(fx.baby_id, "schema", 1, 20);
    schema_drift.insert("schema_version".to_owned(), json!(3));
    let mut unknown_type = record_root(fx.baby_id, "type", 1, 20);
    unknown_type.insert("type".to_owned(), json!("memo"));
    let mut overlong_note = record_root(fx.baby_id, "note", 1, 20);
    overlong_note.insert("note".to_owned(), json!("字".repeat(20_001)));
    let invalid_zone = map(json!({
        "baby_client_uuid": fx.baby_id,
        "type": "formula",
        "custom_item_client_uuid": null,
        "scheduled_at": 100,
        "scheduled_zone_id": "Mars/Olympus",
        "note": null,
        "payload_json": {"amount_ml": 100},
        "schema_version": 2,
        "status": "pending",
        "fulfilled_record_client_uuid": null,
        "fulfilled_at": null,
        "updated_at": 20,
    }));
    let units = [
        ("record", negative_amount),
        ("record", missing_typed_required),
        ("record", schema_drift),
        ("record", unknown_type),
        ("record", overlong_note),
        ("care_plan", invalid_zone),
    ]
    .into_iter()
    .map(|(entity_type, root)| mut_unit(entity_type, Uuid::new_v4(), None, root, false))
    .collect();

    let before = fx.store.pull(&fx.family_id, 0).unwrap().cursor;
    let result = fx
        .store
        .causal_commit(&fx.owner, units, 1_700_000_000)
        .unwrap();

    assert!(
        result.results.iter().all(|unit| {
            unit.status == "rejected" && unit.code.as_deref() == Some("invalid_entity_value")
        }),
        "{result:?}"
    );
    assert_eq!(result.cursor, before);
}

#[test]
fn causal_ingress_rejects_a_dangling_baby_reference_without_publication() {
    let fx = CausalFx::new();
    let before = fx.store.pull(&fx.family_id, 0).unwrap();
    let record_id = Uuid::new_v4();
    let unit = mut_unit(
        "record",
        record_id,
        None,
        record_root(Uuid::new_v4(), "dangling", 100, 20),
        false,
    );

    let result = fx
        .store
        .causal_commit(&fx.owner, vec![unit], 1_700_000_000)
        .unwrap();

    assert_eq!(result.results[0].status, "rejected", "{result:?}");
    assert_eq!(result.results[0].code.as_deref(), Some("invalid_reference"));
    let after = fx.store.pull(&fx.family_id, 0).unwrap();
    assert_eq!(after.cursor, before.cursor);
    assert!(after
        .entities
        .iter()
        .all(|row| row.client_uuid != record_id.to_string()));
}

#[test]
fn causal_ingress_closes_custom_wake_and_fulfillment_references() {
    let fx = CausalFx::new();

    let mut dangling_custom = record_root(fx.baby_id, "custom", 1, 20);
    dangling_custom.insert("type".to_owned(), json!("custom"));
    dangling_custom.insert("custom_item_client_uuid".to_owned(), json!(Uuid::new_v4()));
    dangling_custom.insert("payload_json".to_owned(), json!({"title": "不存在"}));
    let custom_rejected = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                Uuid::new_v4(),
                None,
                dangling_custom,
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(
        custom_rejected.results[0].code.as_deref(),
        Some("invalid_reference")
    );

    let sleep_id = Uuid::new_v4();
    let sleep_created = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                sleep_id,
                None,
                sleep_root(fx.baby_id, 100, 30),
                false,
            )],
            1_700_000_001,
        )
        .unwrap();
    let sleep_version = sleep_created.results[0].stable_version_id.clone().unwrap();
    let mut invalid_effective = sleep_root(fx.baby_id, 100, 31);
    invalid_effective.insert(
        "effective_wake_observation_client_uuid".to_owned(),
        json!(Uuid::new_v4()),
    );
    let effective_rejected = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                sleep_id,
                Some(&sleep_version),
                invalid_effective,
                false,
            )],
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(
        effective_rejected.results[0].code.as_deref(),
        Some("invalid_reference")
    );

    let deleted_sleep = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                sleep_id,
                Some(&sleep_version),
                sleep_root(fx.baby_id, 100, 32),
                true,
            )],
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(deleted_sleep.results[0].status, "accepted");
    let wake_rejected = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "wake_observation",
                Uuid::new_v4(),
                None,
                map(json!({
                    "sleep_record_client_uuid": sleep_id,
                    "wake_timestamp": 120,
                    "note": null,
                    "withdrawn": false,
                    "updated_at": 40,
                })),
                false,
            )],
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(
        wake_rejected.results[0].code.as_deref(),
        Some("invalid_reference")
    );

    let other_baby_id = Uuid::new_v4();
    assert_eq!(
        fx.store
            .causal_commit(
                &fx.owner,
                vec![mut_unit(
                    "baby",
                    other_baby_id,
                    None,
                    baby_root("二宝", 50),
                    false,
                )],
                1_700_000_005,
            )
            .unwrap()
            .results[0]
            .status,
        "accepted"
    );
    let other_record_id = Uuid::new_v4();
    assert_eq!(
        fx.store
            .causal_commit(
                &fx.owner,
                vec![mut_unit(
                    "record",
                    other_record_id,
                    None,
                    record_root(other_baby_id, "other", 100, 51),
                    false,
                )],
                1_700_000_006,
            )
            .unwrap()
            .results[0]
            .status,
        "accepted"
    );
    let mismatched_plan = map(json!({
        "baby_client_uuid": fx.baby_id,
        "type": "formula",
        "custom_item_client_uuid": null,
        "scheduled_at": 100,
        "scheduled_zone_id": "Asia/Shanghai",
        "note": null,
        "payload_json": {"amount_ml": 100},
        "schema_version": 2,
        "status": "completed",
        "fulfilled_record_client_uuid": other_record_id,
        "fulfilled_at": 100,
        "updated_at": 52,
    }));
    let fulfillment_rejected = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "care_plan",
                Uuid::new_v4(),
                None,
                mismatched_plan,
                false,
            )],
            1_700_000_007,
        )
        .unwrap();
    assert_eq!(
        fulfillment_rejected.results[0].code.as_deref(),
        Some("invalid_reference")
    );
}

#[test]
fn causal_wake_receipt_replays_before_changed_sleep_validation() {
    let fx = CausalFx::new();
    let sleep_id = Uuid::new_v4();
    let created_sleep = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                sleep_id,
                None,
                sleep_root(fx.baby_id, 100, 20),
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    let sleep_v1 = created_sleep.results[0]
        .stable_version_id
        .clone()
        .expect("sleep version");

    let wake_id = Uuid::new_v4();
    let wake = mut_unit(
        "wake_observation",
        wake_id,
        None,
        map(json!({
            "sleep_record_client_uuid": sleep_id,
            "wake_timestamp": 120,
            "note": "醒了",
            "withdrawn": false,
            "updated_at": 30,
        })),
        false,
    );
    let accepted = fx
        .store
        .causal_commit(&fx.owner, vec![wake.clone()], 1_700_000_001)
        .unwrap();
    assert_eq!(accepted.results[0].status, "accepted", "{accepted:?}");
    let wake_version = accepted.results[0].stable_version_id.clone();

    let moved_sleep = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                sleep_id,
                Some(&sleep_v1),
                sleep_root(fx.baby_id, 130, 40),
                false,
            )],
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(moved_sleep.results[0].status, "accepted");
    let cursor_before_replay = moved_sleep.cursor;

    let replay = fx
        .store
        .causal_commit(&fx.owner, vec![wake.clone()], 1_700_000_003)
        .unwrap();
    assert_eq!(replay.results[0].status, "accepted", "{replay:?}");
    assert_eq!(replay.results[0].stable_version_id, wake_version);
    assert_eq!(replay.cursor, cursor_before_replay);

    let mut drift = wake;
    drift.root.insert("note".to_owned(), json!("不同内容"));
    let rejected = fx
        .store
        .causal_commit(&fx.owner, vec![drift], 1_700_000_004)
        .unwrap();
    assert_eq!(rejected.results[0].status, "rejected");
    assert_eq!(rejected.results[0].code.as_deref(), Some("content_drift"));
}

#[test]
fn causal_ingress_rejects_a_root_that_cannot_fit_the_pull_budget() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut root = record_root(fx.baby_id, "oversized", 100, 20);
    root.insert("type".to_owned(), json!("diary"));
    root.insert(
        "payload_json".to_owned(),
        json!({"body": "x".repeat(crate::PULL_ENTITY_TARGET_BYTES + 1_024)}),
    );

    let result = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit("record", record_id, None, root, false)],
            1_700_000_000,
        )
        .unwrap();

    assert_eq!(result.results[0].status, "rejected", "{result:?}");
    assert_eq!(result.results[0].code.as_deref(), Some("root_too_large"));
    assert!(fx
        .store
        .pull(&fx.family_id, 0)
        .unwrap()
        .entities
        .iter()
        .all(|row| row.client_uuid != record_id.to_string()));
}

#[test]
fn causal_missing_media_bytes_rejected() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let m1 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "a".repeat(64),
        byte_size: 10,
        mime: "image/jpeg".into(),
        width: None,
        height: None,
    };
    let mut unit = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    unit.media = vec![m1];
    // No stage_media_bytes — fail closed.
    let result = fx
        .store
        .causal_commit(&fx.owner, vec![unit], 1_700_000_000)
        .unwrap();
    assert_eq!(result.results[0].status, "rejected");
    assert_eq!(
        result.results[0].code.as_deref(),
        Some("missing_media_bytes")
    );
}

#[test]
fn causal_commit_projects_stable_media_into_pull_entities() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut m1 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "a".repeat(64),
        byte_size: 12,
        mime: "image/jpeg".into(),
        width: Some(2),
        height: Some(3),
    };
    fx.stage_media_bytes(&mut m1);
    let mut create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "with-photo", 100, 20),
        false,
    );
    create.media = vec![m1.clone()];
    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap();
    assert_eq!(committed.results[0].status, "accepted");
    let v1 = committed.results[0]
        .stable_version_id
        .clone()
        .expect("stable version");

    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let media_row = page
        .entities
        .iter()
        .find(|e| e.entity_type == "media" && e.client_uuid == m1.media_uuid)
        .expect("pull must include projected media entity");
    assert!(media_row.deleted_at.is_none());
    assert_eq!(
        media_row.payload.get("kind").and_then(Value::as_str),
        Some("log")
    );
    assert_eq!(
        media_row
            .payload
            .get("record_client_uuid")
            .and_then(Value::as_str),
        Some(record_id.to_string().as_str())
    );
    assert_eq!(
        media_row.payload.get("byte_size").and_then(Value::as_i64),
        Some(m1.byte_size)
    );
    assert!(fx
        .store
        .is_media_published(&fx.family_id, &m1.media_uuid)
        .unwrap());

    // Remove media on next accepted commit — peers must observe the tombstone.
    let mut remove = mut_unit(
        "record",
        record_id,
        Some(&v1),
        record_root(fx.baby_id, "with-photo", 100, 30),
        false,
    );
    remove.media = vec![];
    let removed = fx
        .store
        .causal_commit(&fx.owner, vec![remove], 1_700_000_001)
        .unwrap();
    assert_eq!(removed.results[0].status, "accepted");
    let page2 = fx.store.pull(&fx.family_id, 0).unwrap();
    let media_row2 = page2
        .entities
        .iter()
        .find(|e| e.entity_type == "media" && e.client_uuid == m1.media_uuid)
        .expect("tombstoned media remains pull-visible");
    assert!(media_row2.deleted_at.is_some());
    assert!(!fx
        .store
        .is_media_published(&fx.family_id, &m1.media_uuid)
        .unwrap());
}

#[test]
fn causal_stable_head_blocks_legacy_bundle_commit() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap();
    assert_eq!(committed.results[0].status, "accepted");

    let err = publish_root(
        &fx.store,
        &fx.owner,
        entity(
            "record",
            record_id,
            40,
            json!({
                "baby_client_uuid": fx.baby_id,
                "type": "formula",
                "custom_item_client_uuid": null,
                "timestamp": 100,
                "end_timestamp": null,
                "note": "lww-poison",
                "payload_json": {"amount_ml": 200},
                "schema_version": 2,
            }),
        ),
        100,
    )
    .unwrap_err();
    assert!(matches!(
        err,
        StoreError::LegacyBundleCausalRootUnsupported(ref entity_type)
            if entity_type == "record"
    ));
}
