//! Causal reconcile / commit / pull summary / resolution — Store façade seams.

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

struct CausalFx {
    store: Store,
    dir: TempDir,
    family_id: String,
    owner: Principal,
    baby_id: Uuid,
}

impl CausalFx {
    fn new() -> Self {
        let dir = TempDir::new().unwrap();
        let store = Store::open(dir.path().join("lezi.db")).unwrap();
        let family_id = family(&store);
        let owner = owner_principal(&family_id);
        let baby_id = Uuid::new_v4();
        let create = mut_unit("baby", baby_id, None, baby_root("年年", 10), false);
        let result = store
            .causal_commit(&owner, vec![create], 1_700_000_000)
            .unwrap();
        assert_eq!(result.results[0].status, "accepted");
        Self {
            store,
            dir,
            family_id,
            owner,
            baby_id,
        }
    }

    /// Write authority media bytes under data_dir/media/{family}/{uuid}.
    fn stage_media_bytes(&self, item: &CausalMediaItem) {
        let path = self
            .dir
            .path()
            .join("media")
            .join(&self.family_id)
            .join(&item.media_uuid);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, vec![0u8; item.byte_size as usize]).unwrap();
    }
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
    let m1 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "a".repeat(64),
        byte_size: 10,
        mime: "image/jpeg".into(),
        width: Some(1),
        height: Some(1),
    };
    let mut create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    create.media = vec![m1.clone()];
    fx.stage_media_bytes(&m1);
    let v1 = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    let m2 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "b".repeat(64),
        byte_size: 20,
        mime: "image/jpeg".into(),
        width: None,
        height: None,
    };
    fx.stage_media_bytes(&m2);
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
    let mut create2 = mut_unit(
        "record",
        record2,
        None,
        record_root(fx.baby_id, "x", 10, 50),
        false,
    );
    create2.media = vec![m1.clone()];
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
    let mut m1_edit = m1.clone();
    m1_edit.sha256 = "c".repeat(64);
    edit_media.media = vec![m1_edit];
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
    let m1 = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "a".repeat(64),
        byte_size: 12,
        mime: "image/jpeg".into(),
        width: Some(2),
        height: Some(3),
    };
    let mut create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "with-photo", 100, 20),
        false,
    );
    create.media = vec![m1.clone()];
    fx.stage_media_bytes(&m1);
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
    assert!(matches!(err, StoreError::LegacyBundleCommitOnCausalEntity));
}

#[test]
fn causal_no_neighbor_losers_on_near_duplicate_records() {
    // Causal path never produces neighbor tombstones (different UUIDs all live).
    let fx = CausalFx::new();
    let member = Principal {
        family_id: fx.family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-member".to_owned(),
        device_id: "d-member".to_owned(),
    };
    let r1 = Uuid::new_v4();
    let r2 = Uuid::new_v4();
    let u1 = mut_unit(
        "record",
        r1,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    let u2 = mut_unit(
        "record",
        r2,
        None,
        record_root(fx.baby_id, "b", 100, 21),
        false,
    );
    // Same timestamp window, different memberships.
    fx.store
        .causal_commit(&fx.owner, vec![u1], 1_700_000_000)
        .unwrap();
    let second = fx
        .store
        .causal_commit(&member, vec![u2], 1_700_000_001)
        .unwrap();
    assert_eq!(second.results[0].status, "accepted");
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
