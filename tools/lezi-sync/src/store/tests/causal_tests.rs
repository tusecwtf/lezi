//! Causal reconcile / commit / pull summary / resolution — Store façade seams.

use super::super::causal::{MAX_CAUSAL_UNITS, VERSION_PROVENANCE_PRINCIPAL};
use super::super::causal_admission::MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT;
use super::super::conflict_snapshots::ConflictOutcome;
use super::super::*;
use super::test_support::*;
use rusqlite::params;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::sync::{mpsc, Arc, Barrier};
use std::thread;
use tempfile::TempDir;
use uuid::Uuid;

fn map(v: Value) -> Map<String, Value> {
    v.as_object().unwrap().clone()
}

fn first_conflict_detail(
    store: &Store,
    principal: &Principal,
    conflict_id: &str,
) -> Result<ConflictDetailPage, StoreError> {
    store.conflict_detail_page(
        principal,
        conflict_id,
        ConflictDetailPageRequest::First,
        1_700_000_100,
    )
}

fn conflict_detail_pages(
    store: &Store,
    principal: &Principal,
    conflict_id: &str,
    now: i64,
) -> Result<Vec<ConflictDetailPage>, StoreError> {
    let mut pages = Vec::new();
    let mut request = ConflictDetailPageRequest::First;
    loop {
        let page = store.conflict_detail_page(principal, conflict_id, request, now)?;
        let continuation = page.continuation.clone();
        let snapshot_token = page.snapshot_token.clone();
        let complete = page.complete;
        pages.push(page);
        if complete {
            return Ok(pages);
        }
        request = ConflictDetailPageRequest::Continuation {
            snapshot_token,
            continuation: continuation.expect("an incomplete page has continuation"),
        };
    }
}

fn seed_resolved_conflict_with_branches(
    branch_count: usize,
) -> (
    CausalFx,
    Uuid,
    String,
    ResolveConflictInput,
    ResolveConflictResult,
) {
    let fx = CausalFx::new();
    let (record_id, conflict_id, input, resolved) = seed_resolved_conflict_on(&fx, branch_count);
    (fx, record_id, conflict_id, input, resolved)
}

fn seed_resolved_conflict_on(
    fx: &CausalFx,
    branch_count: usize,
) -> (Uuid, String, ResolveConflictInput, ResolveConflictResult) {
    assert!((1..=MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT).contains(&branch_count));
    let record_id = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, None, "base"),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    let stable = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "stable"),
            1_700_000_001,
        )
        .unwrap();
    assert_eq!(stable.results[0].status, "accepted");

    let mut conflict_id = None;
    for index in 0..branch_count {
        let branch = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, Some(&base), &format!("branch-{index}")),
                1_700_000_002,
            )
            .unwrap();
        assert_eq!(branch.results[0].status, "branched");
        let current = branch.results[0].conflict_id.clone().unwrap();
        assert!(conflict_id
            .as_ref()
            .is_none_or(|expected| expected == &current));
        conflict_id = Some(current);
    }
    let conflict_id = conflict_id.unwrap();
    let pages = conflict_detail_pages(&fx.store, &fx.owner, &conflict_id, 1_700_000_100).unwrap();
    let detail = pages.last().unwrap();
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![resolution_choice(
            detail,
            "/note",
            ConflictOutcome::Set {
                value: Value::String(format!("branch-{}", branch_count - 1)),
            },
        )],
    };
    let resolved = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_000_101)
        .unwrap();
    assert_eq!(resolved.status, "accepted");
    (record_id, conflict_id, input, resolved)
}

fn resolution_choice(
    detail: &ConflictDetailPage,
    path: &str,
    outcome: ConflictOutcome,
) -> ConflictResolutionChoice {
    let candidate = detail
        .conflicting
        .iter()
        .find(|item| item.path == path)
        .and_then(|item| {
            item.candidates
                .iter()
                .find(|candidate| candidate.outcome == outcome)
        })
        .unwrap_or_else(|| panic!("missing {path} resolution candidate"));
    ConflictResolutionChoice {
        path: path.to_owned(),
        choice_id: candidate.choice_id.clone(),
    }
}

#[derive(Debug, PartialEq, Eq)]
struct ResolutionDurableState {
    rev: i64,
    entities: String,
    versions: String,
    parents: String,
    version_media: String,
    stable_heads: String,
    receipts: String,
    conflicts: String,
    conflict_branches: String,
    resolutions: String,
    media_staging: String,
    media_publications: String,
}

fn resolution_durable_state(
    store: &Store,
    family_id: &str,
    _conflict_id: &str,
    _client_uuid: Uuid,
) -> ResolutionDurableState {
    let connection = store.connect().unwrap();
    let rows = |sql: &str| {
        connection
            .query_row(sql, params![family_id], |row| row.get(0))
            .unwrap()
    };
    ResolutionDurableState {
        rev: connection
            .query_row(
                "SELECT rev FROM family_meta WHERE family_id = ?1",
                params![family_id],
                |row| row.get(0),
            )
            .unwrap(),
        entities: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(entity_type, client_uuid, updated_at, deleted_at,
                    payload_json, rev) AS value FROM entities
                 WHERE family_id = ?1 ORDER BY entity_type, client_uuid)",
        ),
        versions: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(version_id, entity_type, client_uuid, updated_at, deleted_at,
                    payload_json, content_hash, mutation_id, origin, created_at) AS value
                  FROM entity_versions WHERE family_id = ?1 ORDER BY version_id)",
        ),
        parents: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(version_id, parent_version_id) AS value
                  FROM entity_version_parents WHERE family_id = ?1
                 ORDER BY version_id, parent_version_id)",
        ),
        version_media: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(version_id, media_uuid, media_payload_json, content_hash) AS value
                  FROM entity_version_media WHERE family_id = ?1 ORDER BY version_id, media_uuid)",
        ),
        stable_heads: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(entity_type, client_uuid, version_id) AS value
                  FROM entity_stable_heads WHERE family_id = ?1 ORDER BY entity_type, client_uuid)",
        ),
        receipts: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(membership_id, entity_type, client_uuid, mutation_id,
                    content_hash, status, stable_version_id, branch_version_id, conflict_id,
                    receipt_json, created_at) AS value
                  FROM mutation_receipts WHERE family_id = ?1
                 ORDER BY membership_id, entity_type, client_uuid, mutation_id)",
        ),
        conflicts: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(conflict_id, entity_type, client_uuid, base_version_id,
                    stable_version_id, status, kind, created_at, resolved_at) AS value
                  FROM conflicts WHERE family_id = ?1 ORDER BY conflict_id)",
        ),
        conflict_branches: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(conflict_id, branch_version_id) AS value
                  FROM conflict_branches WHERE family_id = ?1 ORDER BY conflict_id, branch_version_id)",
        ),
        resolutions: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(conflict_id, resolution_mutation_id, resolver_membership_id,
                    expected_stable_version_id, expected_branch_versions_json,
                    conflict_choices_json, resolved_version_id, created_at) AS value
                  FROM conflict_resolutions WHERE family_id = ?1
                 ORDER BY conflict_id, resolution_mutation_id)",
        ),
        media_staging: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(membership_id, media_uuid, sha256, byte_size, created_at,
                    expires_at, status, consumed_at) AS value
                  FROM causal_media_staging WHERE family_id = ?1 ORDER BY media_uuid)",
        ),
        media_publications: rows(
            "SELECT json_group_array(value) FROM (
                SELECT json_array(media_uuid, source, bundle_id) AS value
                  FROM media_publications WHERE family_id = ?1 ORDER BY media_uuid)",
        ),
    }
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

fn wake_observation_root(
    sleep_record_id: Uuid,
    wake_timestamp: i64,
    updated_at: i64,
) -> Map<String, Value> {
    map(json!({
        "sleep_record_client_uuid": sleep_record_id,
        "wake_timestamp": wake_timestamp,
        "note": null,
        "withdrawn": false,
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

fn custom_item_root(name: &str, icon_slot: i64, updated_at: i64) -> Map<String, Value> {
    map(json!({
        "name": name,
        "icon_slot": icon_slot,
        "updated_at": updated_at,
    }))
}

fn deterministic_snapshot_payload(page: &ConflictDetailPage) -> Value {
    let mut value = serde_json::to_value(page).unwrap();
    let root = value.as_object_mut().unwrap();
    for key in [
        "conflict_id",
        "snapshot_token",
        "expires_at",
        "page_index",
        "continuation",
        "complete",
    ] {
        root.remove(key);
    }
    for key in ["stable", "branches"] {
        let versions = if key == "stable" {
            vec![root.get_mut(key).unwrap()]
        } else {
            root.get_mut(key)
                .unwrap()
                .as_array_mut()
                .unwrap()
                .iter_mut()
                .collect()
        };
        for version in versions {
            let version = version.as_object_mut().unwrap();
            version.remove("version_id");
            version.remove("base_version");
        }
    }
    root.get_mut("branches")
        .unwrap()
        .as_array_mut()
        .unwrap()
        .sort_by_key(|version| version["mutation_id"].as_str().unwrap().to_owned());
    for item in root.get_mut("conflicting").unwrap().as_array_mut().unwrap() {
        for candidate in item["candidates"].as_array_mut().unwrap() {
            candidate.as_object_mut().unwrap().remove("choice_id");
            for source in candidate["sources"].as_array_mut().unwrap() {
                source.as_object_mut().unwrap().remove("version_id");
            }
        }
    }
    for item in root.get_mut("auto_merged").unwrap().as_array_mut().unwrap() {
        for source in item["sources"].as_array_mut().unwrap() {
            source.as_object_mut().unwrap().remove("version_id");
        }
    }
    value
}

fn three_branch_snapshot(order: [usize; 3]) -> ConflictDetailPage {
    let fx = CausalFx::new();
    let item_id = Uuid::parse_str("00000000-0000-0000-0000-000000000900").unwrap();
    let mut base = mut_unit(
        "custom_item",
        item_id,
        None,
        custom_item_root("base", 0, 10),
        false,
    );
    base.mutation_id = "00000000-0000-0000-0000-000000000901".to_owned();
    let base_version = fx.commit(&fx.owner, base, 1_700_000_000).unwrap().results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let mut stable = mut_unit(
        "custom_item",
        item_id,
        Some(&base_version),
        custom_item_root("stable", 0, 20),
        false,
    );
    stable.mutation_id = "00000000-0000-0000-0000-000000000902".to_owned();
    assert_eq!(
        fx.commit(&fx.owner, stable, 1_700_000_001).unwrap().results[0].status,
        "accepted",
    );

    let candidates = [
        (
            "alpha",
            "00000000-0000-0000-0000-000000000903",
            "device-alpha",
        ),
        (
            "beta",
            "00000000-0000-0000-0000-000000000904",
            "device-beta",
        ),
        (
            "alpha",
            "00000000-0000-0000-0000-000000000905",
            "device-alpha-2",
        ),
    ];
    let mut conflict_id = None;
    for index in order {
        let (name, mutation_id, device_id) = candidates[index];
        let mut branch = mut_unit(
            "custom_item",
            item_id,
            Some(&base_version),
            custom_item_root(name, 1, 30 + index as i64),
            false,
        );
        branch.mutation_id = mutation_id.to_owned();
        let principal = Principal {
            device_id: device_id.to_owned(),
            ..fx.owner.clone()
        };
        let result = fx
            .commit(&principal, branch, 1_700_000_010 + index as i64)
            .unwrap();
        assert_eq!(result.results[0].status, "branched");
        conflict_id = result.results[0].conflict_id.clone().or(conflict_id);
    }
    first_conflict_detail(&fx.store, &fx.owner, &conflict_id.unwrap()).unwrap()
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
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    assert!(detail
        .conflicting
        .iter()
        .any(|item| item.path == "/_mutation.deleted"));
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
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    assert!(detail.conflicting.iter().any(|item| item.path == "/note"));
    assert_eq!(detail.branches.len(), 1);

    // A token, not client-declared stable/branch values, owns full-set CAS.
    let mut tampered_token = detail.snapshot_token.clone();
    let replacement = if tampered_token.starts_with('A') {
        "B"
    } else {
        "A"
    };
    tampered_token.replace_range(..1, replacement);
    let bad = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: tampered_token,
                resolution_mutation_id: Uuid::new_v4().to_string(),
                choices: vec![resolution_choice(
                    &detail,
                    "/note",
                    ConflictOutcome::Set { value: json!("c") },
                )],
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(bad.status, "rejected");
    assert_eq!(bad.error.unwrap().code, "invalid_snapshot_token");

    let ok = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token.clone(),
                resolution_mutation_id: Uuid::new_v4().to_string(),
                choices: vec![resolution_choice(
                    &detail,
                    "/note",
                    ConflictOutcome::Set { value: json!("c") },
                )],
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(ok.status, "accepted");
    assert_eq!(
        ok.stable_root.get("note").and_then(Value::as_str),
        Some("c")
    );

    // Conflict closed.
    assert!(first_conflict_detail(&fx.store, &fx.owner, &conflict_id).is_err());
}

#[test]
fn choice_only_resolution_rebuilds_authoritative_result_and_replays_after_restart() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-choice"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let choice = resolution_choice(
        &detail,
        "/note",
        ConflictOutcome::Set {
            value: json!("branch-choice"),
        },
    );
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![choice],
    };

    let accepted = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_000_003)
        .unwrap();
    assert_eq!(accepted.status, "accepted");
    assert_eq!(accepted.replay, Some(false));
    assert_eq!(
        accepted.stable_root.get("note").and_then(Value::as_str),
        Some("branch-choice")
    );

    let restarted = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    let replay = restarted
        .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_000_004)
        .unwrap();
    assert_eq!(replay.status, "accepted");
    assert_eq!(replay.replay, Some(true));
    assert_eq!(replay.stable_version_id, accepted.stable_version_id);
    assert_eq!(replay.stable_root, accepted.stable_root);
    let stored: Value = restarted
        .connect()
        .unwrap()
        .query_row(
            "SELECT conflict_choices_json FROM conflict_resolutions
             WHERE family_id = ?1 AND conflict_id = ?2
               AND resolution_mutation_id = ?3",
            params![fx.family_id, conflict_id, input.resolution_mutation_id],
            |row| row.get::<_, String>(0),
        )
        .map(|raw| serde_json::from_str(&raw).unwrap())
        .unwrap();
    assert_eq!(stored["request_hash"].as_str().unwrap().len(), 64);
    assert_eq!(stored["result"]["status"], "accepted");
    assert_eq!(stored["result"]["stable_root"]["note"], "branch-choice");
    assert!(!stored.to_string().contains(&detail.snapshot_token));

    let later = restarted
        .causal_commit(
            &fx.owner,
            vec![fx.record_mutation(record_id, Some(&base), "later-branch")],
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(later.results[0].status, "branched");
    let later_detail = first_conflict_detail(
        &restarted,
        &fx.owner,
        later.results[0].conflict_id.as_deref().unwrap(),
    )
    .unwrap();
    assert_eq!(
        later_detail.stable.version_id,
        accepted.stable_version_id.unwrap()
    );
}

#[test]
fn choice_only_resolution_replay_rejects_corrupt_terminal_receipt_and_audit() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-choice"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![resolution_choice(
            &detail,
            "/note",
            ConflictOutcome::Set {
                value: json!("branch-choice"),
            },
        )],
    };
    fx.store
        .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_000_003)
        .unwrap();
    let connection = fx.store.connect().unwrap();
    let original: (String, String, String, String) = connection
        .query_row(
            "SELECT expected_stable_version_id, expected_branch_versions_json,
                    conflict_choices_json, resolved_version_id
             FROM conflict_resolutions
             WHERE family_id = ?1 AND conflict_id = ?2 AND resolution_mutation_id = ?3",
            params![fx.family_id, conflict_id, input.resolution_mutation_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )
        .unwrap();
    let mut corrupt_receipt: Value = serde_json::from_str(&original.2).unwrap();
    corrupt_receipt["result"]["stable_root"]["note"] = json!("stored-tamper");
    let cases = [
        (
            "conflict_choices_json",
            serde_json::to_string(&corrupt_receipt).unwrap(),
        ),
        ("resolved_version_id", original.0.clone()),
        ("expected_stable_version_id", original.3.clone()),
        ("expected_branch_versions_json", "[]".to_owned()),
    ];
    for (column, corrupted) in cases {
        connection
            .execute(
                &format!(
                    "UPDATE conflict_resolutions SET {column} = ?1
                     WHERE family_id = ?2 AND conflict_id = ?3 AND resolution_mutation_id = ?4"
                ),
                params![
                    corrupted,
                    fx.family_id,
                    conflict_id,
                    input.resolution_mutation_id
                ],
            )
            .unwrap();
        assert!(matches!(
            fx.store
                .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_000_004),
            Err(StoreError::InvalidStoredPayload)
        ));
        connection
            .execute(
                "UPDATE conflict_resolutions SET expected_stable_version_id = ?1,
                        expected_branch_versions_json = ?2, conflict_choices_json = ?3,
                        resolved_version_id = ?4
                 WHERE family_id = ?5 AND conflict_id = ?6 AND resolution_mutation_id = ?7",
                params![
                    original.0,
                    original.1,
                    original.2,
                    original.3,
                    fx.family_id,
                    conflict_id,
                    input.resolution_mutation_id
                ],
            )
            .unwrap();
    }

    let original_payload: String = connection
        .query_row(
            "SELECT payload_json FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, original.3],
            |row| row.get(0),
        )
        .unwrap();
    let mut coordinated_receipt: Value = serde_json::from_str(&original.2).unwrap();
    coordinated_receipt["result"]["stable_root"]["note"] = json!("coordinated-tamper");
    let mut coordinated_payload: Value = serde_json::from_str(&original_payload).unwrap();
    coordinated_payload["note"] = json!("coordinated-tamper");
    connection
        .execute(
            "UPDATE conflict_resolutions SET conflict_choices_json = ?1
             WHERE family_id = ?2 AND conflict_id = ?3 AND resolution_mutation_id = ?4",
            params![
                serde_json::to_string(&coordinated_receipt).unwrap(),
                fx.family_id,
                conflict_id,
                input.resolution_mutation_id
            ],
        )
        .unwrap();
    connection
        .execute(
            "UPDATE entity_versions SET payload_json = ?1
             WHERE family_id = ?2 AND version_id = ?3",
            params![
                serde_json::to_string(coordinated_payload.as_object().unwrap()).unwrap(),
                fx.family_id,
                original.3
            ],
        )
        .unwrap();
    let coordinated_result =
        fx.store
            .resolve_conflict(&fx.owner, &conflict_id, input, 1_700_000_004);
    assert!(
        matches!(coordinated_result, Err(StoreError::InvalidStoredPayload)),
        "coordinated receipt/version drift returned {coordinated_result:?}"
    );
    connection
        .execute(
            "UPDATE conflict_resolutions SET conflict_choices_json = ?1
             WHERE family_id = ?2 AND conflict_id = ?3",
            params![original.2, fx.family_id, conflict_id],
        )
        .unwrap();
    connection
        .execute(
            "UPDATE entity_versions SET payload_json = ?1
             WHERE family_id = ?2 AND version_id = ?3",
            params![original_payload, fx.family_id, original.3],
        )
        .unwrap();
}

#[test]
fn choice_only_resolution_rejects_tamper_acl_and_incomplete_sets_without_write() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-choice"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let valid_choice = resolution_choice(
        &detail,
        "/note",
        ConflictOutcome::Set {
            value: json!("branch-choice"),
        },
    );
    let request = |choices: Vec<ConflictResolutionChoice>| ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices,
    };

    let cases = [
        (request(vec![]), "incomplete_choices"),
        (
            request(vec![valid_choice.clone(), valid_choice.clone()]),
            "duplicate_choice",
        ),
        (
            request(vec![ConflictResolutionChoice {
                path: "/foreign".to_owned(),
                choice_id: valid_choice.choice_id.clone(),
            }]),
            "invalid_choice",
        ),
        (
            request(vec![ConflictResolutionChoice {
                path: valid_choice.path.clone(),
                choice_id: "A".repeat(43),
            }]),
            "invalid_choice",
        ),
        (
            ResolveConflictInput {
                snapshot_token: "x".repeat(44),
                ..request(vec![valid_choice.clone()])
            },
            "non_canonical_value",
        ),
        (
            request(vec![valid_choice.clone(); 65]),
            "non_canonical_value",
        ),
        (
            request(vec![ConflictResolutionChoice {
                path: format!("/{}", "x".repeat(1_024)),
                choice_id: valid_choice.choice_id.clone(),
            }]),
            "non_canonical_value",
        ),
    ];
    for (input, expected) in cases {
        let before = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
        let result = fx
            .store
            .resolve_conflict(&fx.owner, &conflict_id, input, 1_700_000_003)
            .unwrap();
        assert_eq!(result.status, "rejected");
        assert_eq!(result.error.unwrap().code, expected);
        assert_eq!(
            resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id),
            before,
        );
    }

    let before = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
    let forbidden = fx
        .store
        .resolve_conflict(
            &fx.member,
            &conflict_id,
            request(vec![valid_choice.clone()]),
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(forbidden.error.unwrap().code, "forbidden");
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id),
        before,
    );

    let before = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
    let expired = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            request(vec![valid_choice]),
            detail.expires_at / 1_000,
        )
        .unwrap();
    assert_eq!(expired.error.unwrap().code, "snapshot_expired");
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id),
        before,
    );

    let status: String = fx
        .store
        .connect()
        .unwrap()
        .query_row(
            "SELECT status FROM conflicts WHERE family_id = ?1 AND conflict_id = ?2",
            params![fx.family_id, conflict_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(status, "open");
}

#[test]
fn choice_only_resolution_requires_every_conflicting_path_exactly_once() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.owner,
            mut_unit(
                "record",
                record_id,
                None,
                record_root(fx.baby_id, "base", 100, 20),
                false,
            ),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    fx.commit(
        &fx.owner,
        mut_unit(
            "record",
            record_id,
            Some(&base),
            record_root(fx.baby_id, "stable", 110, 30),
            false,
        ),
        1_700_000_001,
    )
    .unwrap();
    let branched = fx
        .commit(
            &fx.owner,
            mut_unit(
                "record",
                record_id,
                Some(&base),
                record_root(fx.baby_id, "branch", 120, 40),
                false,
            ),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    assert_eq!(detail.conflicting.len(), 2);
    let note = resolution_choice(
        &detail,
        "/note",
        ConflictOutcome::Set {
            value: json!("branch"),
        },
    );
    let amount = resolution_choice(
        &detail,
        "/payload_json/amount_ml",
        ConflictOutcome::Set { value: json!(120) },
    );
    let mutation_id = Uuid::new_v4().to_string();
    let partial = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token.clone(),
                resolution_mutation_id: mutation_id.clone(),
                choices: vec![note.clone()],
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(partial.error.unwrap().code, "incomplete_choices");
    let unsorted = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token.clone(),
                resolution_mutation_id: mutation_id.clone(),
                choices: vec![amount.clone(), note.clone()],
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(unsorted.error.unwrap().code, "non_canonical_value");
    let accepted = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token.clone(),
                resolution_mutation_id: mutation_id.clone(),
                choices: vec![note.clone(), amount.clone()],
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(accepted.status, "accepted");
    let replay = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token,
                resolution_mutation_id: mutation_id,
                choices: vec![note, amount],
            },
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(replay.replay, Some(true));
}

#[test]
fn choice_only_resolution_request_drift_cannot_replace_the_original_terminal() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-choice"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let mutation_id = Uuid::new_v4().to_string();
    let accepted_input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: mutation_id.clone(),
        choices: vec![resolution_choice(
            &detail,
            "/note",
            ConflictOutcome::Set {
                value: json!("branch-choice"),
            },
        )],
    };
    let accepted = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, accepted_input, 1_700_000_003)
        .unwrap();
    let drifted = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token.clone(),
                resolution_mutation_id: mutation_id,
                choices: vec![resolution_choice(
                    &detail,
                    "/note",
                    ConflictOutcome::Set {
                        value: json!("stable"),
                    },
                )],
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(drifted.error.unwrap().code, "content_drift");
    let pull = fx.store.pull(&fx.family_id, 0).unwrap();
    let record = pull
        .entities
        .iter()
        .find(|entity| entity.client_uuid == record_id.to_string())
        .unwrap();
    assert_eq!(record.payload.get("note"), Some(&json!("branch-choice")));
    assert_eq!(
        accepted.stable_version_id.as_deref(),
        record.version_id.as_deref()
    );
}

#[test]
fn concurrent_choice_only_resolutions_publish_exactly_one_terminal() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-choice"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let choice = resolution_choice(
        &detail,
        "/note",
        ConflictOutcome::Set {
            value: json!("branch-choice"),
        },
    );
    let before = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
    let control = super::super::conflict_snapshots::test_hook::install(&fx.family_id);
    let (results_tx, results_rx) = mpsc::channel();
    let mut handles = Vec::new();
    for _ in 0..2 {
        let store = fx.store.clone();
        let owner = fx.owner.clone();
        let conflict_id = conflict_id.clone();
        let snapshot_token = detail.snapshot_token.clone();
        let choice = choice.clone();
        let results_tx = results_tx.clone();
        handles.push(thread::spawn(move || {
            let result = store
                .resolve_conflict(
                    &owner,
                    &conflict_id,
                    ResolveConflictInput {
                        snapshot_token,
                        resolution_mutation_id: Uuid::new_v4().to_string(),
                        choices: vec![choice],
                    },
                    1_700_000_003,
                )
                .unwrap();
            results_tx.send(result).unwrap();
        }));
    }
    drop(results_tx);
    control.wait_resolution_entry(1);
    control.wait_resolution_busy();
    control.release_one_resolution();
    control.wait_resolution_entry(2);
    let first = results_rx.recv().unwrap();
    let after_winner = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
    control.release_one_resolution();
    let second = results_rx.recv().unwrap();
    for handle in handles {
        handle.join().unwrap();
    }
    let after_loser = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
    assert_ne!(after_winner, before);
    assert_eq!(after_loser, after_winner);
    let results = [first, second];
    assert_eq!(
        results
            .iter()
            .filter(|result| result.status == "accepted")
            .count(),
        1
    );
    assert_eq!(
        results
            .iter()
            .filter(|result| {
                result
                    .error
                    .as_ref()
                    .is_some_and(|error| error.code == "snapshot_stale")
            })
            .count(),
        1
    );
}

#[test]
fn choice_only_resolution_statement_budget_is_independent_of_branch_count() {
    fn measured(branch_count: usize) -> usize {
        let fx = CausalFx::new();
        let record_id = Uuid::new_v4();
        let created = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, None, "base"),
                1_700_000_000,
            )
            .unwrap();
        let base = created.results[0].stable_version_id.clone().unwrap();
        fx.commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "stable"),
            1_700_000_001,
        )
        .unwrap();
        let mut conflict_id = None;
        for index in 0..branch_count {
            let branched = fx
                .commit(
                    &fx.owner,
                    fx.record_mutation(record_id, Some(&base), &format!("branch-{index}")),
                    1_700_000_002,
                )
                .unwrap();
            conflict_id = branched.results[0].conflict_id.clone();
        }
        let conflict_id = conflict_id.unwrap();
        let pages =
            conflict_detail_pages(&fx.store, &fx.owner, &conflict_id, 1_700_000_100).unwrap();
        let detail = pages.last().unwrap();
        let input = ResolveConflictInput {
            snapshot_token: detail.snapshot_token.clone(),
            resolution_mutation_id: Uuid::new_v4().to_string(),
            choices: vec![resolution_choice(
                detail,
                "/note",
                ConflictOutcome::Set {
                    value: Value::String("branch-0".to_owned()),
                },
            )],
        };
        begin_statement_count(&fx.family_id);
        let result = fx
            .store
            .resolve_conflict(&fx.owner, &conflict_id, input, 1_700_000_101)
            .unwrap();
        let statements = finish_statement_count(&fx.family_id);
        assert_eq!(result.status, "accepted");
        statements
    }

    let one = measured(1);
    let sixty_four = measured(MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT);
    assert_eq!(sixty_four, one);
    assert!(one <= 32, "resolution statement budget drifted to {one}");
}

#[test]
fn conflict_retention_gc_is_two_phase_and_replay_safe_after_restart() {
    let (fx, record_id, conflict_id, input, resolved) =
        seed_resolved_conflict_with_branches(MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT);
    let stable_version = resolved.stable_version_id.clone().unwrap();
    let before = fx.store.connect().unwrap();
    let branch_rows: i64 = before
        .query_row(
            "SELECT COUNT(*) FROM conflict_branches WHERE family_id = ?1 AND conflict_id = ?2",
            params![fx.family_id, conflict_id],
            |row| row.get(0),
        )
        .unwrap();
    let snapshot_rows: i64 = before
        .query_row(
            "SELECT COUNT(*) FROM mutation_receipts
              WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'
                AND conflict_id = ?2",
            params![fx.family_id, conflict_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(branch_rows, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT as i64);
    assert_eq!(snapshot_rows, 1);
    drop(before);

    assert_eq!(
        fx.store
            .gc_conflict_metadata(1_700_000_101 + 86_399)
            .unwrap(),
        0,
    );
    super::super::conflict_retention::test_hook::fail_after_mark_once();
    assert!(matches!(
        fx.store.gc_conflict_metadata(1_700_000_101 + 86_400),
        Err(StoreError::InvalidStoredPayload),
    ));

    let restarted = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    assert_eq!(
        restarted
            .gc_conflict_metadata(1_700_000_101 + 86_400)
            .unwrap(),
        1,
    );
    let replay = restarted
        .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_100_000)
        .unwrap();
    assert_eq!(replay.replay, Some(true));
    assert_eq!(
        replay.stable_version_id.as_deref(),
        Some(stable_version.as_str())
    );
    let mut drift = input;
    drift.choices[0].choice_id = "A".repeat(43);
    let drift = restarted
        .resolve_conflict(&fx.owner, &conflict_id, drift, 1_700_100_000)
        .unwrap();
    assert_eq!(drift.error.unwrap().code, "content_drift");

    let after = restarted.connect().unwrap();
    let compacted: (i64, i64, i64, i64, i64, i64) = after
        .query_row(
            "SELECT
                (SELECT COUNT(*) FROM conflict_branches
                  WHERE family_id = ?1 AND conflict_id = ?2),
                (SELECT COUNT(*) FROM mutation_receipts
                  WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'
                    AND conflict_id = ?2),
                (SELECT COUNT(*) FROM conflict_resolutions
                  WHERE family_id = ?1 AND conflict_id = ?2),
                (SELECT COUNT(*) FROM conflicts
                  WHERE family_id = ?1 AND conflict_id = ?2 AND status = 'resolved'),
                (SELECT COUNT(*) FROM entity_stable_heads
                  WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?3
                    AND version_id = ?4),
                (SELECT COUNT(*) FROM mutation_receipts
                  WHERE family_id = ?1 AND membership_id = ?5 AND mutation_id = ?4)",
            params![
                fx.family_id,
                conflict_id,
                record_id.to_string(),
                stable_version,
                VERSION_PROVENANCE_PRINCIPAL,
            ],
            |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                    row.get(5)?,
                ))
            },
        )
        .unwrap();
    assert_eq!(compacted, (0, 0, 1, 1, 1, 1));
}

#[test]
fn conflict_detail_compacts_expired_snapshot_choice_receipts() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    for now in [1_700_000_100, 1_700_000_700, 1_700_001_300] {
        fx.store
            .conflict_detail_page(
                &fx.owner,
                &conflict_id,
                ConflictDetailPageRequest::First,
                now,
            )
            .unwrap();
    }
    let receipt_json: String = fx
        .store
        .connect()
        .unwrap()
        .query_row(
            "SELECT receipt_json FROM mutation_receipts
              WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'
                AND conflict_id = ?2",
            params![fx.family_id, conflict_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(
        serde_json::from_str::<Vec<Value>>(&receipt_json)
            .unwrap()
            .len(),
        1,
    );
}

#[test]
fn conflict_retention_fails_closed_on_corrupt_choice_material() {
    let (fx, _, conflict_id, _, _) = seed_resolved_conflict_with_branches(1);
    let connection = fx.store.connect().unwrap();
    connection
        .execute(
            "UPDATE mutation_receipts SET receipt_json = '[]'
              WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'
                AND conflict_id = ?2",
            params![fx.family_id, conflict_id],
        )
        .unwrap();
    drop(connection);
    assert!(matches!(
        fx.store.gc_conflict_metadata(1_700_000_101 + 86_400),
        Err(StoreError::InvalidStoredPayload),
    ));
    let connection = fx.store.connect().unwrap();
    let retained: (i64, i64) = connection
        .query_row(
            "SELECT
                (SELECT COUNT(*) FROM conflict_branches
                  WHERE family_id = ?1 AND conflict_id = ?2),
                (SELECT COUNT(*) FROM entity_versions v
                  JOIN conflict_branches b
                    ON b.family_id = v.family_id AND b.branch_version_id = v.version_id
                 WHERE b.family_id = ?1 AND b.conflict_id = ?2)",
            params![fx.family_id, conflict_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(retained, (1, 1));
}

#[test]
fn conflict_retention_fails_closed_on_valid_receipt_bound_to_another_conflict() {
    let fx = CausalFx::new();
    let (_, target_conflict, _, _) = seed_resolved_conflict_on(&fx, 1);
    let (_, donor_conflict, _, _) = seed_resolved_conflict_on(&fx, 1);
    let connection = fx.store.connect().unwrap();
    let donor_receipt: String = connection
        .query_row(
            "SELECT receipt_json FROM mutation_receipts
              WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'
                AND conflict_id = ?2",
            params![fx.family_id, donor_conflict],
            |row| row.get(0),
        )
        .unwrap();
    connection
        .execute(
            "UPDATE mutation_receipts SET receipt_json = ?1
              WHERE family_id = ?2 AND membership_id = '__conflict_snapshot_v2__'
                AND conflict_id = ?3",
            params![donor_receipt, fx.family_id, target_conflict],
        )
        .unwrap();
    connection
        .execute(
            "DELETE FROM mutation_receipts
              WHERE family_id = ?1 AND membership_id = '__conflict_retention_v2__'
                AND conflict_id = ?2",
            params![fx.family_id, donor_conflict],
        )
        .unwrap();
    drop(connection);

    assert!(matches!(
        fx.store.gc_conflict_metadata(1_700_000_101 + 86_400),
        Err(StoreError::InvalidStoredPayload),
    ));
    let connection = fx.store.connect().unwrap();
    let retained: (i64, i64) = connection
        .query_row(
            "SELECT
                (SELECT COUNT(*) FROM conflict_branches
                  WHERE family_id = ?1 AND conflict_id = ?2),
                (SELECT COUNT(*) FROM entity_versions v
                  JOIN conflict_branches b
                    ON b.family_id = v.family_id AND b.branch_version_id = v.version_id
                 WHERE b.family_id = ?1 AND b.conflict_id = ?2)",
            params![fx.family_id, target_conflict],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(retained, (1, 1));
}

#[test]
fn concurrent_conflict_retention_sweeps_publish_one_idempotent_terminal() {
    let (fx, _, conflict_id, input, resolved) = seed_resolved_conflict_with_branches(1);
    let barrier = Arc::new(Barrier::new(3));
    let handles = (0..2)
        .map(|_| {
            let store = fx.store.clone();
            let barrier = barrier.clone();
            thread::spawn(move || {
                barrier.wait();
                store.gc_conflict_metadata(1_700_000_101 + 86_400)
            })
        })
        .collect::<Vec<_>>();
    barrier.wait();
    let results = handles
        .into_iter()
        .map(|handle| handle.join().unwrap().unwrap())
        .collect::<Vec<_>>();
    assert_eq!(results.iter().sum::<usize>(), 1);
    let replay = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, input, 1_700_100_000)
        .unwrap();
    assert_eq!(replay.replay, Some(true));
    assert_eq!(replay.stable_version_id, resolved.stable_version_id);
}

#[test]
fn conflict_retention_budget_is_bounded_with_one_hundred_thousand_eligible_receipts() {
    let fx = CausalFx::new();
    for _ in 0..8 {
        seed_resolved_conflict_on(&fx, 1);
    }
    let connection = fx.store.connect().unwrap();
    let stable_version: String = connection
        .query_row(
            "SELECT version_id FROM entity_stable_heads
              WHERE family_id = ?1 AND entity_type = 'baby' AND client_uuid = ?2",
            params![fx.family_id, fx.baby_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    let client_uuid = fx.baby_id.to_string();
    let tx = connection.unchecked_transaction().unwrap();
    {
        let mut insert_conflict = tx
            .prepare(
                "INSERT INTO conflicts(
                    family_id, conflict_id, entity_type, client_uuid, base_version_id,
                    stable_version_id, status, kind, created_at, resolved_at
                 ) VALUES (?1, ?2, 'baby', ?3, NULL, ?4, 'resolved',
                    'tombstone_restore', 1, ?5)",
            )
            .unwrap();
        let mut insert_resolution = tx
            .prepare(
                "INSERT INTO conflict_resolutions(
                    family_id, conflict_id, resolution_mutation_id, resolver_membership_id,
                    expected_stable_version_id, expected_branch_versions_json,
                    conflict_choices_json, resolved_version_id, created_at
                 ) VALUES (?1, ?2, ?3, ?4, ?5, '[]', '{}', ?5, ?6)",
            )
            .unwrap();
        let mut insert_snapshot_receipt = tx
            .prepare(
                "INSERT INTO mutation_receipts(
                    family_id, membership_id, entity_type, client_uuid, mutation_id,
                    content_hash, status, stable_version_id, branch_version_id,
                    conflict_id, receipt_json, created_at
                 ) VALUES (?1, '__conflict_snapshot_v2__', 'baby', ?2, ?3,
                    ?4, 'accepted', ?5, NULL, ?6, ?7, ?8)",
            )
            .unwrap();
        for index in 0..100_000 {
            let conflict_id = format!("historical-eligible-{index:06}");
            let resolution_mutation_id = format!("historical-resolution-{index:06}");
            let snapshot_receipt =
                super::super::conflict_snapshots::test_resolution_ready_receipt_json(
                    &fx.store,
                    super::super::conflict_snapshots::TestReceiptBinding {
                        family_id: &fx.family_id,
                        conflict_id: &conflict_id,
                        kind: "tombstone_restore",
                        entity_type: "baby",
                        client_uuid: &client_uuid,
                        stable_version_id: &stable_version,
                        branch_version_ids: &[],
                    },
                );
            insert_conflict
                .execute(params![
                    fx.family_id,
                    conflict_id,
                    client_uuid,
                    stable_version,
                    1_700_000_102_i64,
                ])
                .unwrap();
            insert_resolution
                .execute(params![
                    fx.family_id,
                    conflict_id,
                    resolution_mutation_id,
                    fx.owner.membership_id,
                    stable_version,
                    1_700_000_102_i64,
                ])
                .unwrap();
            insert_snapshot_receipt
                .execute(params![
                    fx.family_id,
                    client_uuid,
                    format!("snapshot-receipts:{conflict_id}"),
                    "0".repeat(64),
                    stable_version,
                    conflict_id,
                    snapshot_receipt,
                    1_700_000_102_i64,
                ])
                .unwrap();
            super::super::conflict_retention::stage_resolution_retention(
                &tx,
                super::super::conflict_retention::ResolutionRetentionBinding {
                    family_id: &fx.family_id,
                    conflict_id: &conflict_id,
                    kind: "tombstone_restore",
                    entity_type: "baby",
                    client_uuid: &client_uuid,
                    expected_stable_version_id: &stable_version,
                    expected_branch_versions_json: "[]",
                    resolved_version_id: &stable_version,
                    resolved_at: 1_700_000_102,
                },
            )
            .unwrap();
        }
    }
    tx.commit().unwrap();
    let now = 1_700_000_102 + 86_400;
    let plan =
        super::super::conflict_retention::candidate_query_plan(&fx.store, &fx.family_id, now)
            .unwrap();
    assert!(
        plan.iter()
            .any(|step| step.contains("mutation_receipts_lookup")),
        "retention candidates must use the due-marker range index: {plan:?}",
    );
    assert!(
        plan.iter().all(|step| !step.contains("USE TEMP B-TREE")),
        "retention candidates must not sort eligible history: {plan:?}",
    );
    begin_statement_count(&fx.family_id);
    assert_eq!(
        fx.store
            .gc_conflict_metadata_for_family(&fx.family_id, now)
            .unwrap(),
        8,
    );
    let statements = finish_statement_count(&fx.family_id);
    assert!(
        statements <= 128,
        "retention statement budget drifted to {statements}"
    );
}

#[test]
fn ordinary_commit_and_choice_resolution_share_media_domain_validation() {
    let fx = CausalFx::new();
    let media_item = || CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".to_owned(),
        sha256: "0".repeat(64),
        byte_size: 1,
        mime: "image/jpeg".to_owned(),
        width: Some(1),
        height: Some(1),
    };
    let mut media = (0..4).map(|_| media_item()).collect::<Vec<_>>();
    for item in &mut media {
        fx.stage_media_bytes(item);
    }

    let record_id = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.owner,
            mut_unit(
                "record",
                record_id,
                None,
                record_root(fx.baby_id, "base", 100, 20),
                false,
            ),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.clone().unwrap();
    let mut stable = mut_unit(
        "record",
        record_id,
        Some(&base),
        record_root(fx.baby_id, "stable", 100, 30),
        false,
    );
    stable.media = media[..2].to_vec();
    assert_eq!(
        fx.commit(&fx.owner, stable, 1_700_000_001).unwrap().results[0].status,
        "accepted"
    );
    let mut branch = mut_unit(
        "record",
        record_id,
        Some(&base),
        record_root(fx.baby_id, "branch", 100, 40),
        false,
    );
    branch.media = media[2..].to_vec();
    let branched = fx.commit(&fx.owner, branch, 1_700_000_002).unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    assert_eq!(
        detail
            .auto_merged
            .iter()
            .filter(|merged| merged.path.starts_with("/media/"))
            .count(),
        4
    );
    let resolution = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token.clone(),
                resolution_mutation_id: Uuid::new_v4().to_string(),
                choices: vec![resolution_choice(
                    &detail,
                    "/note",
                    ConflictOutcome::Set {
                        value: json!("branch"),
                    },
                )],
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(resolution.error.unwrap().code, "invalid_domain");

    let mut invalid_commit = mut_unit(
        "record",
        Uuid::new_v4(),
        None,
        record_root(fx.baby_id, "too-many-media", 100, 50),
        false,
    );
    invalid_commit.media = media;
    let commit_result = fx.commit(&fx.owner, invalid_commit, 1_700_000_004).unwrap();
    assert_eq!(
        commit_result.results[0].code.as_deref(),
        Some("media_limit_exceeded")
    );
}

#[test]
fn choice_only_resolution_rebuilds_media_and_concurrent_tombstone_authoritatively() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let media_item = |fill: u8| CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".to_owned(),
        sha256: String::new(),
        byte_size: usize::from(fill) as i64 + 8,
        mime: "image/jpeg".to_owned(),
        width: Some(1),
        height: Some(1),
    };
    let mut removed_media = media_item(1);
    let mut added_media = media_item(2);
    fx.stage_media_bytes(&mut removed_media);
    fx.stage_media_bytes(&mut added_media);
    let mut create = fx.record_mutation(record_id, None, "base");
    create.media = vec![removed_media.clone()];
    let base = fx.commit(&fx.owner, create, 1_700_000_000).unwrap().results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let mut live = fx.record_mutation(record_id, Some(&base), "stable-live");
    live.media = vec![added_media.clone()];
    let live_version = fx.commit(&fx.owner, live, 1_700_000_001).unwrap().results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let mut tombstone = fx.record_mutation(record_id, Some(&base), "offline-delete");
    tombstone.deleted = true;
    tombstone.media = vec![];
    let branched = fx.commit(&fx.owner, tombstone, 1_700_000_002).unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    assert!(detail.branches.iter().any(|branch| branch.deleted));
    assert!(detail.auto_merged.iter().any(|item| {
        item.path == format!("/media/{}", added_media.media_uuid)
            && item.outcome
                == ConflictOutcome::Set {
                    value: added_media.to_value(),
                }
    }));
    assert!(detail.auto_merged.iter().any(|item| {
        item.path == format!("/media/{}", removed_media.media_uuid)
            && item.outcome == ConflictOutcome::Remove
    }));
    let mut choices = detail
        .conflicting
        .iter()
        .map(|item| {
            let candidate = if item.path == "/_mutation.deleted" {
                item.candidates
                    .iter()
                    .find(|candidate| {
                        candidate.outcome
                            == ConflictOutcome::Set {
                                value: Value::Bool(false),
                            }
                    })
                    .unwrap()
            } else {
                item.candidates
                    .iter()
                    .find(|candidate| {
                        candidate
                            .sources
                            .iter()
                            .any(|source| source.version_id == detail.stable.version_id)
                    })
                    .unwrap()
            };
            ConflictResolutionChoice {
                path: item.path.clone(),
                choice_id: candidate.choice_id.clone(),
            }
        })
        .collect::<Vec<_>>();
    choices.sort_by(|left, right| left.path.cmp(&right.path));
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token,
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices,
    };
    let accepted = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_000_003)
        .unwrap();
    assert_eq!(accepted.status, "accepted");
    assert_eq!(
        accepted.stable_root.get("note"),
        Some(&json!("stable-live"))
    );
    assert_eq!(accepted.stable_media, vec![added_media.clone()]);
    let resolved_version = accepted.stable_version_id.clone().unwrap();
    let connection = fx.store.connect().unwrap();
    let direct_parents: Vec<String> = connection
        .prepare(
            "SELECT parent_version_id FROM entity_version_parents
             WHERE family_id = ?1 AND version_id = ?2 ORDER BY parent_version_id",
        )
        .unwrap()
        .query_map(params![fx.family_id, resolved_version], |row| row.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert_eq!(direct_parents, vec![live_version]);
    let provenance: Value = serde_json::from_str(
        &connection
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                 WHERE family_id = ?1 AND membership_id = ?2
                   AND entity_type = 'record' AND client_uuid = ?3 AND mutation_id = ?4",
                params![
                    fx.family_id,
                    VERSION_PROVENANCE_PRINCIPAL,
                    record_id.to_string(),
                    resolved_version
                ],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();
    assert_eq!(provenance["actor_id"], fx.owner.membership_id);
    assert_eq!(provenance["device_id"], fx.owner.device_id);
    assert_eq!(provenance["received_at"], 1_700_000_003);
    let pull = fx.store.pull(&fx.family_id, 0).unwrap();
    let record = pull
        .entities
        .iter()
        .find(|entity| {
            entity.entity_type == "record" && entity.client_uuid == record_id.to_string()
        })
        .unwrap();
    assert!(record.deleted_at.is_none());
    assert_eq!(
        record.version_id.as_deref(),
        Some(resolved_version.as_str())
    );
    let added_projection = pull
        .entities
        .iter()
        .find(|entity| {
            entity.entity_type == "media" && entity.client_uuid == added_media.media_uuid
        })
        .unwrap();
    assert!(added_projection.deleted_at.is_none());
    let removed_projection = pull
        .entities
        .iter()
        .find(|entity| {
            entity.entity_type == "media" && entity.client_uuid == removed_media.media_uuid
        })
        .unwrap();
    assert!(removed_projection.deleted_at.is_some());
    let before_replay = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
    let replay = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, input, 1_700_000_004)
        .unwrap();
    assert_eq!(replay.replay, Some(true));
    assert_eq!(replay.stable_root, accepted.stable_root);
    assert_eq!(replay.stable_media, accepted.stable_media);
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id),
        before_replay,
    );
}

#[test]
fn choice_only_resolution_can_authoritatively_select_concurrent_tombstone() {
    let (fx, conflict_id, _, branch_version) = seed_media_conflict(true);
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let record_id = Uuid::parse_str(&detail.client_uuid).unwrap();
    let stable_version = detail.stable.version_id.clone();
    let stable_media_id = detail.stable.media[0].media_uuid.clone();
    let mut choices = detail
        .conflicting
        .iter()
        .map(|item| {
            let candidate = if item.path == "/_mutation.deleted" {
                item.candidates
                    .iter()
                    .find(|candidate| candidate.outcome == ConflictOutcome::Remove)
                    .unwrap()
            } else {
                item.candidates
                    .iter()
                    .find(|candidate| {
                        candidate
                            .sources
                            .iter()
                            .any(|source| source.version_id == branch_version)
                    })
                    .unwrap()
            };
            ConflictResolutionChoice {
                path: item.path.clone(),
                choice_id: candidate.choice_id.clone(),
            }
        })
        .collect::<Vec<_>>();
    choices.sort_by(|left, right| left.path.cmp(&right.path));
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token,
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices,
    };
    let accepted = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, input.clone(), 1_700_000_003)
        .unwrap();
    assert_eq!(accepted.status, "accepted");
    assert_eq!(accepted.stable_root.get("note"), Some(&json!("branch")));
    assert!(accepted.stable_media.is_empty());
    let resolved_version = accepted.stable_version_id.clone().unwrap();
    let connection = fx.store.connect().unwrap();
    let (deleted_at, parent_count, parent): (Option<i64>, i64, String) = connection
        .query_row(
            "SELECT v.deleted_at, COUNT(p.parent_version_id), MIN(p.parent_version_id)
             FROM entity_versions v
             JOIN entity_version_parents p
               ON p.family_id = v.family_id AND p.version_id = v.version_id
             WHERE v.family_id = ?1 AND v.version_id = ?2
             GROUP BY v.family_id, v.version_id",
            params![fx.family_id, resolved_version],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    assert_eq!(deleted_at, Some(1_700_000_003_000));
    assert_eq!(parent_count, 1);
    assert_eq!(parent, stable_version);
    let provenance: Value = serde_json::from_str(
        &connection
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                 WHERE family_id = ?1 AND membership_id = ?2
                   AND entity_type = 'record' AND client_uuid = ?3 AND mutation_id = ?4",
                params![
                    fx.family_id,
                    VERSION_PROVENANCE_PRINCIPAL,
                    record_id.to_string(),
                    resolved_version
                ],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();
    assert_eq!(provenance["actor_id"], fx.owner.membership_id);
    assert_eq!(provenance["device_id"], fx.owner.device_id);
    assert_eq!(provenance["received_at"], 1_700_000_003);
    let pull = fx.store.pull(&fx.family_id, 0).unwrap();
    let record = pull
        .entities
        .iter()
        .find(|entity| {
            entity.entity_type == "record" && entity.client_uuid == record_id.to_string()
        })
        .unwrap();
    assert_eq!(record.deleted_at, Some(1_700_000_003_000));
    assert_eq!(
        record.version_id.as_deref(),
        Some(resolved_version.as_str())
    );
    let media = pull
        .entities
        .iter()
        .find(|entity| entity.entity_type == "media" && entity.client_uuid == stable_media_id)
        .unwrap();
    assert!(media.deleted_at.is_some());
    let before_replay = resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id);
    let replay = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, input, 1_700_000_004)
        .unwrap();
    assert_eq!(replay.replay, Some(true));
    assert_eq!(replay.stable_root, accepted.stable_root);
    assert!(replay.stable_media.is_empty());
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, &conflict_id, record_id),
        before_replay,
    );
}

struct TombstoneRestoreFixture {
    fx: CausalFx,
    record_id: Uuid,
    base_version: String,
    tombstone_version: String,
    conflict_id: String,
    detail: ConflictDetailPage,
    input: ResolveConflictInput,
    direct_base_root: Map<String, Value>,
    media: Vec<CausalMediaItem>,
}

fn seed_tombstone_restore(with_media: bool) -> TombstoneRestoreFixture {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let media = with_media
        .then(|| {
            let mut item = CausalMediaItem {
                media_uuid: Uuid::new_v4().to_string(),
                role: "log".to_owned(),
                sha256: String::new(),
                byte_size: 19,
                mime: "image/jpeg".to_owned(),
                width: Some(1),
                height: Some(1),
            };
            fx.stage_media_bytes(&mut item);
            item
        })
        .into_iter()
        .collect::<Vec<_>>();
    let mut create = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "a", 100, 20),
        false,
    );
    create.media = media.clone();
    let created = fx
        .store
        .causal_commit(&fx.owner, vec![create], 1_700_000_000)
        .unwrap();
    let base_version = created.results[0].stable_version_id.clone().unwrap();
    let direct_base_root = created.results[0].stable_root.clone();
    let del = mut_unit(
        "record",
        record_id,
        Some(&base_version),
        record_root(fx.baby_id, "a", 100, 21),
        true,
    );
    let deleted = fx
        .store
        .causal_commit(&fx.owner, vec![del], 1_700_000_001)
        .unwrap();
    let conflict_id = deleted.results[0].conflict_id.clone().unwrap();
    let tombstone_version = deleted.results[0].stable_version_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![resolution_choice(
            &detail,
            "/_mutation.deleted",
            ConflictOutcome::Set {
                value: Value::Bool(false),
            },
        )],
    };
    TombstoneRestoreFixture {
        fx,
        record_id,
        base_version,
        tombstone_version,
        conflict_id,
        detail,
        input,
        direct_base_root,
        media,
    }
}

#[test]
fn tombstone_without_one_direct_live_base_does_not_mint_a_restore_handle() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut first = fx.record_mutation(record_id, None, "never-live");
    first.deleted = true;
    let deleted = fx.commit(&fx.owner, first, 1_700_000_001).unwrap();
    assert_eq!(deleted.results[0].status, "accepted");
    assert!(deleted.results[0].conflict_id.is_none());
    let row = fx
        .store
        .pull(&fx.family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .find(|entity| entity.client_uuid == record_id.to_string())
        .unwrap();
    assert!(row.deleted_at.is_some());
    assert!(row.conflict_summary.is_none());
    let open_handles: i64 = fx
        .store
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM conflicts
              WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
                AND status = 'open'",
            params![fx.family_id, record_id.to_string()],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(open_handles, 0);
}

#[test]
fn choice_only_resolution_restores_the_complete_direct_live_base() {
    let seeded = seed_tombstone_restore(true);
    let restore_candidate = seeded
        .detail
        .conflicting
        .iter()
        .find(|item| item.path == "/_mutation.deleted")
        .unwrap()
        .candidates
        .iter()
        .find(|candidate| {
            candidate.outcome
                == ConflictOutcome::Set {
                    value: Value::Bool(false),
                }
        })
        .unwrap();
    assert_eq!(restore_candidate.sources.len(), 1);
    assert_eq!(restore_candidate.sources[0].version_id, seeded.base_version);
    let before_forbidden = resolution_durable_state(
        &seeded.fx.store,
        &seeded.fx.family_id,
        &seeded.conflict_id,
        seeded.record_id,
    );
    let forbidden = seeded
        .fx
        .store
        .resolve_conflict(
            &seeded.fx.member,
            &seeded.conflict_id,
            seeded.input.clone(),
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(forbidden.error.unwrap().code, "forbidden");
    assert_eq!(
        resolution_durable_state(
            &seeded.fx.store,
            &seeded.fx.family_id,
            &seeded.conflict_id,
            seeded.record_id,
        ),
        before_forbidden,
    );
    let restored = seeded
        .fx
        .store
        .resolve_conflict(
            &seeded.fx.owner,
            &seeded.conflict_id,
            seeded.input.clone(),
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(restored.status, "accepted");
    assert_eq!(restored.stable_root, seeded.direct_base_root);
    assert_eq!(restored.stable_media, seeded.media);
    assert_eq!(restored.replay, Some(false));
    let resolved_version = restored.stable_version_id.clone().unwrap();
    let connection = seeded.fx.store.connect().unwrap();
    let direct_parent: String = connection
        .query_row(
            "SELECT parent_version_id FROM entity_version_parents
              WHERE family_id = ?1 AND version_id = ?2",
            params![seeded.fx.family_id, resolved_version],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(direct_parent, seeded.tombstone_version);
    let provenance: Value = serde_json::from_str(
        &connection
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                  WHERE family_id = ?1 AND membership_id = ?2 AND mutation_id = ?3",
                params![
                    seeded.fx.family_id,
                    VERSION_PROVENANCE_PRINCIPAL,
                    resolved_version
                ],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();
    assert_eq!(provenance["actor_id"], seeded.fx.owner.membership_id);
    assert_eq!(provenance["device_id"], seeded.fx.owner.device_id);
    assert_eq!(provenance["received_at"], 1_700_000_002);
    drop(connection);
    let page = seeded.fx.store.pull(&seeded.fx.family_id, 0).unwrap();
    let row = page
        .entities
        .iter()
        .find(|e| e.client_uuid == seeded.record_id.to_string())
        .unwrap();
    assert!(row.deleted_at.is_none());
    assert!(row.conflict_summary.is_none());
    assert!(seeded
        .fx
        .store
        .conflict_detail_page(
            &seeded.fx.owner,
            &seeded.conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_003,
        )
        .is_err());
    let mut unrelated_staging = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".to_owned(),
        sha256: String::new(),
        byte_size: 7,
        mime: "image/jpeg".to_owned(),
        width: None,
        height: None,
    };
    seeded.fx.stage_media_bytes(&mut unrelated_staging);
    let unrelated_staging_path = seeded
        .fx
        ._dir
        .path()
        .join("media/.causal-stage")
        .join(&seeded.fx.family_id)
        .join(&unrelated_staging.media_uuid);
    let restarted = Store::open(seeded.fx._dir.path().join("lezi.db")).unwrap();
    let replay = restarted
        .resolve_conflict(
            &seeded.fx.owner,
            &seeded.conflict_id,
            seeded.input.clone(),
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(replay.replay, Some(true));
    assert_eq!(replay.stable_version_id, Some(resolved_version.clone()));
    assert_eq!(
        restarted
            .gc_conflict_metadata(1_700_000_002 + 86_400)
            .unwrap(),
        1,
    );
    let replay_after_retention = restarted
        .resolve_conflict(
            &seeded.fx.owner,
            &seeded.conflict_id,
            seeded.input,
            1_700_100_000,
        )
        .unwrap();
    assert_eq!(replay_after_retention.replay, Some(true));
    let retained_direct_chain: i64 = restarted
        .connect()
        .unwrap()
        .query_row(
            "SELECT COUNT(*) FROM entity_versions
              WHERE family_id = ?1 AND version_id IN (?2, ?3)",
            params![
                seeded.fx.family_id,
                seeded.base_version,
                seeded.tombstone_version,
            ],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(retained_direct_chain, 2);
    assert!(unrelated_staging_path.is_file());
    let retained_media_state: (i64, i64) = restarted
        .connect()
        .unwrap()
        .query_row(
            "SELECT
                (SELECT COUNT(*) FROM causal_media_staging
                  WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'staged'),
                (SELECT COUNT(*) FROM media_publications
                  WHERE family_id = ?1 AND media_uuid = ?3)",
            params![
                seeded.fx.family_id,
                unrelated_staging.media_uuid,
                seeded.media[0].media_uuid,
            ],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(retained_media_state, (1, 1));
}

#[test]
fn tombstone_restore_snapshot_fails_stale_after_a_new_live_branch() {
    let seeded = seed_tombstone_restore(false);
    let branch = seeded.fx.record_mutation(
        seeded.record_id,
        Some(&seeded.base_version),
        "late-live-branch",
    );
    let branched = seeded
        .fx
        .commit(&seeded.fx.owner, branch, 1_700_000_002)
        .unwrap();
    assert_eq!(branched.results[0].status, "branched");
    assert_eq!(
        branched.results[0].conflict_id.as_deref(),
        Some(seeded.conflict_id.as_str()),
    );
    let before = resolution_durable_state(
        &seeded.fx.store,
        &seeded.fx.family_id,
        &seeded.conflict_id,
        seeded.record_id,
    );
    let stale = seeded
        .fx
        .store
        .resolve_conflict(
            &seeded.fx.owner,
            &seeded.conflict_id,
            seeded.input,
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(stale.error.unwrap().code, "snapshot_stale");
    assert_eq!(
        resolution_durable_state(
            &seeded.fx.store,
            &seeded.fx.family_id,
            &seeded.conflict_id,
            seeded.record_id,
        ),
        before,
    );
    let row = seeded
        .fx
        .store
        .pull(&seeded.fx.family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .find(|entity| entity.client_uuid == seeded.record_id.to_string())
        .unwrap();
    assert!(row.deleted_at.is_some());
}

#[test]
fn concurrent_tombstone_restores_publish_exactly_one_live_terminal() {
    let seeded = seed_tombstone_restore(false);
    let before = resolution_durable_state(
        &seeded.fx.store,
        &seeded.fx.family_id,
        &seeded.conflict_id,
        seeded.record_id,
    );
    let control = super::super::conflict_snapshots::test_hook::install(&seeded.fx.family_id);
    let (results_tx, results_rx) = mpsc::channel();
    let mut handles = Vec::new();
    for _ in 0..2 {
        let store = seeded.fx.store.clone();
        let owner = seeded.fx.owner.clone();
        let conflict_id = seeded.conflict_id.clone();
        let snapshot_token = seeded.input.snapshot_token.clone();
        let choices = seeded.input.choices.clone();
        let results_tx = results_tx.clone();
        handles.push(thread::spawn(move || {
            let result = store
                .resolve_conflict(
                    &owner,
                    &conflict_id,
                    ResolveConflictInput {
                        snapshot_token,
                        resolution_mutation_id: Uuid::new_v4().to_string(),
                        choices,
                    },
                    1_700_000_002,
                )
                .unwrap();
            results_tx.send(result).unwrap();
        }));
    }
    drop(results_tx);
    control.wait_resolution_entry(1);
    control.wait_resolution_busy();
    control.release_one_resolution();
    control.wait_resolution_entry(2);
    let first = results_rx.recv().unwrap();
    let after_winner = resolution_durable_state(
        &seeded.fx.store,
        &seeded.fx.family_id,
        &seeded.conflict_id,
        seeded.record_id,
    );
    control.release_one_resolution();
    let second = results_rx.recv().unwrap();
    for handle in handles {
        handle.join().unwrap();
    }
    let after_loser = resolution_durable_state(
        &seeded.fx.store,
        &seeded.fx.family_id,
        &seeded.conflict_id,
        seeded.record_id,
    );
    assert_ne!(after_winner, before);
    assert_eq!(after_loser, after_winner);
    let results = [first, second];
    assert_eq!(
        results
            .iter()
            .filter(|result| result.status == "accepted")
            .count(),
        1,
    );
    assert_eq!(
        results
            .iter()
            .filter(|result| result
                .error
                .as_ref()
                .is_some_and(|error| error.code == "snapshot_stale"))
            .count(),
        1,
    );
}

#[test]
fn concurrent_delete_replay_does_not_revive_a_tombstone() {
    let seeded = seed_tombstone_restore(false);
    let mut second_delete = seeded.fx.record_mutation(
        seeded.record_id,
        Some(&seeded.base_version),
        "second-delete",
    );
    second_delete.deleted = true;
    let result = seeded
        .fx
        .commit(&seeded.fx.owner, second_delete, 1_700_000_002)
        .unwrap();
    assert!(matches!(
        result.results[0].status.as_str(),
        "accepted" | "merged"
    ));
    assert!(result.results[0].conflict_id.is_none());
    let row = seeded
        .fx
        .store
        .pull(&seeded.fx.family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .find(|entity| entity.client_uuid == seeded.record_id.to_string())
        .unwrap();
    assert!(row.deleted_at.is_some());
    assert!(row.conflict_summary.is_none());
    assert!(matches!(
        first_conflict_detail(&seeded.fx.store, &seeded.fx.owner, &seeded.conflict_id),
        Err(StoreError::ConflictNotFound)
    ));
}

#[test]
fn tombstone_restore_rejects_non_restore_choices_without_writes() {
    for add_edit_choice in [false, true] {
        let mut seeded = seed_tombstone_restore(false);
        if add_edit_choice {
            seeded.input.choices.push(ConflictResolutionChoice {
                path: "/note".to_owned(),
                choice_id: seeded.input.choices[0].choice_id.clone(),
            });
        } else {
            seeded.input.choices = vec![resolution_choice(
                &seeded.detail,
                "/_mutation.deleted",
                ConflictOutcome::Remove,
            )];
        }
        let before = resolution_durable_state(
            &seeded.fx.store,
            &seeded.fx.family_id,
            &seeded.conflict_id,
            seeded.record_id,
        );
        let rejected = seeded
            .fx
            .store
            .resolve_conflict(
                &seeded.fx.owner,
                &seeded.conflict_id,
                seeded.input,
                1_700_000_002,
            )
            .unwrap();
        assert_eq!(rejected.error.unwrap().code, "invalid_choice");
        assert_eq!(
            resolution_durable_state(
                &seeded.fx.store,
                &seeded.fx.family_id,
                &seeded.conflict_id,
                seeded.record_id,
            ),
            before,
        );
    }
}

#[test]
fn tombstone_restore_classifies_missing_and_incomplete_direct_bases_without_writes() {
    for corruption in [
        "missing_parent",
        "multiple_parents",
        "deleted_base",
        "content_drift",
    ] {
        let seeded = seed_tombstone_restore(false);
        let connection = seeded.fx.store.connect().unwrap();
        match corruption {
            "missing_parent" => {
                connection
                    .execute(
                        "DELETE FROM entity_version_parents
                          WHERE family_id = ?1 AND version_id = ?2",
                        params![seeded.fx.family_id, seeded.tombstone_version],
                    )
                    .unwrap();
            }
            "multiple_parents" => {
                let baby_version: String = connection
                    .query_row(
                        "SELECT version_id FROM entity_stable_heads
                          WHERE family_id = ?1 AND entity_type = 'baby'",
                        params![seeded.fx.family_id],
                        |row| row.get(0),
                    )
                    .unwrap();
                connection
                    .execute(
                        "INSERT INTO entity_version_parents(family_id, version_id, parent_version_id)
                         VALUES (?1, ?2, ?3)",
                        params![seeded.fx.family_id, seeded.tombstone_version, baby_version],
                    )
                    .unwrap();
            }
            "deleted_base" => {
                connection
                    .execute(
                        "UPDATE entity_versions SET deleted_at = 1
                          WHERE family_id = ?1 AND version_id = ?2",
                        params![seeded.fx.family_id, seeded.base_version],
                    )
                    .unwrap();
            }
            "content_drift" => {
                connection
                    .execute(
                        "UPDATE entity_versions SET content_hash = ?1
                          WHERE family_id = ?2 AND version_id = ?3",
                        params!["0".repeat(64), seeded.fx.family_id, seeded.base_version],
                    )
                    .unwrap();
            }
            _ => unreachable!(),
        }
        drop(connection);
        let detail_error =
            first_conflict_detail(&seeded.fx.store, &seeded.fx.owner, &seeded.conflict_id)
                .unwrap_err();
        if corruption == "missing_parent" {
            assert!(matches!(detail_error, StoreError::MissingRestoreBase));
        } else {
            assert!(matches!(detail_error, StoreError::IncompleteRestoreBase));
        }
        let before = resolution_durable_state(
            &seeded.fx.store,
            &seeded.fx.family_id,
            &seeded.conflict_id,
            seeded.record_id,
        );
        if corruption == "missing_parent" {
            let forbidden = seeded
                .fx
                .store
                .resolve_conflict(
                    &seeded.fx.member,
                    &seeded.conflict_id,
                    seeded.input.clone(),
                    1_700_000_002,
                )
                .unwrap();
            assert_eq!(forbidden.error.unwrap().code, "forbidden");
            assert_eq!(
                resolution_durable_state(
                    &seeded.fx.store,
                    &seeded.fx.family_id,
                    &seeded.conflict_id,
                    seeded.record_id,
                ),
                before,
            );
        }
        let rejected = seeded
            .fx
            .store
            .resolve_conflict(
                &seeded.fx.owner,
                &seeded.conflict_id,
                seeded.input,
                1_700_000_002,
            )
            .unwrap();
        let expected = if corruption == "missing_parent" {
            "missing_restore_base"
        } else {
            "incomplete_restore_base"
        };
        assert_eq!(rejected.error.unwrap().code, expected, "{corruption}");
        assert_eq!(
            resolution_durable_state(
                &seeded.fx.store,
                &seeded.fx.family_id,
                &seeded.conflict_id,
                seeded.record_id,
            ),
            before,
            "{corruption}",
        );
    }
}

#[test]
fn tombstone_restore_requires_exact_direct_base_media_bytes_without_writes() {
    for corruption in ["missing", "corrupt"] {
        let seeded = seed_tombstone_restore(true);
        let media_path = seeded
            .fx
            ._dir
            .path()
            .join("media")
            .join(&seeded.fx.family_id)
            .join(&seeded.media[0].media_uuid);
        if corruption == "missing" {
            std::fs::remove_file(&media_path).unwrap();
        } else {
            std::fs::write(&media_path, vec![1_u8; seeded.media[0].byte_size as usize]).unwrap();
        }
        let before = resolution_durable_state(
            &seeded.fx.store,
            &seeded.fx.family_id,
            &seeded.conflict_id,
            seeded.record_id,
        );
        let rejected = seeded
            .fx
            .store
            .resolve_conflict(
                &seeded.fx.owner,
                &seeded.conflict_id,
                seeded.input,
                1_700_000_002,
            )
            .unwrap();
        assert_eq!(rejected.error.unwrap().code, "missing_restore_media");
        assert_eq!(
            resolution_durable_state(
                &seeded.fx.store,
                &seeded.fx.family_id,
                &seeded.conflict_id,
                seeded.record_id,
            ),
            before,
            "{corruption}",
        );
    }
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
    begin_statement_count(&fx.family_id);
    assert_eq!(
        first_conflict_detail(&fx.store, &fx.owner, &conflict_id)
            .unwrap()
            .branches
            .len(),
        1,
    );
    let one_branch_statements = finish_statement_count(&fx.family_id);

    for index in 1..MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT {
        let result = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, Some(&base), &format!("branch-{index}")),
                1_700_000_002,
            )
            .unwrap();
        assert_eq!(result.results[0].status, "branched", "branch {index}");
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

    begin_statement_count(&fx.family_id);
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let full_branch_statements = finish_statement_count(&fx.family_id);
    assert_eq!(detail.branches.len(), 16);
    assert_eq!(one_branch_statements, 5);
    assert_eq!(full_branch_statements, 4);

    let restarted = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    assert_eq!(
        conflict_detail_pages(&restarted, &fx.owner, &conflict_id, 1_700_000_101)
            .unwrap()
            .iter()
            .map(|page| page.branches.len())
            .sum::<usize>(),
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

    let pages = conflict_detail_pages(&restarted, &fx.owner, &conflict_id, 1_700_000_102).unwrap();
    assert!(pages.windows(2).all(|pair| {
        pair[0].conflicting == pair[1].conflicting && pair[0].auto_merged == pair[1].auto_merged
    }));
    let branch_ids = pages
        .iter()
        .flat_map(|page| &page.branches)
        .map(|branch| branch.version_id.clone())
        .collect::<Vec<_>>();
    assert!(branch_ids.windows(2).all(|pair| pair[0] < pair[1]));
    let resolved = restarted
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: pages[0].snapshot_token.clone(),
                resolution_mutation_id: Uuid::new_v4().to_string(),
                choices: vec![resolution_choice(
                    &pages[0],
                    "/note",
                    ConflictOutcome::Set {
                        value: json!("branch-0"),
                    },
                )],
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(resolved.status, "accepted");
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
fn causal_branch_cap_spans_open_conflicts_and_rejects_incomplete_empty_handles() {
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
    let first_detail = first_conflict_detail(&fx.store, &fx.owner, &first_conflict).unwrap();
    let moved_branch = first_detail.branches[0].version_id.clone();
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
                    first_detail.stable.version_id,
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
        first_conflict_detail(&fx.store, &fx.owner, &first_conflict)
            .unwrap()
            .branches
            .len(),
        2,
    );
    assert_eq!(
        first_conflict_detail(&fx.store, &fx.owner, &second_conflict)
            .unwrap()
            .branches
            .len(),
        1,
    );
    let empty_error = first_conflict_detail(&fx.store, &fx.owner, &empty_conflict).unwrap_err();
    assert!(
        matches!(empty_error, StoreError::IncompleteRestoreBase),
        "unexpected empty handle result: {empty_error:?}",
    );

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
        first_conflict_detail(&fx.store, &fx.owner, &first_conflict)
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
        first_conflict_detail(&fx.store, &fx.owner, &conflict_id)
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

        begin_statement_count(&fx.family_id);
        let mut page = fx.store.pull(&fx.family_id, 0).unwrap();
        let statement_count = finish_statement_count(&fx.family_id);
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

fn seed_media_conflict(branch_deleted: bool) -> (CausalFx, String, String, String) {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut media = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".to_owned(),
        sha256: String::new(),
        byte_size: 16,
        mime: "image/jpeg".to_owned(),
        width: Some(4),
        height: Some(4),
    };
    fx.stage_media_bytes(&mut media);
    let mut create = fx.record_mutation(record_id, None, "base");
    create.media = vec![media.clone()];
    let base = fx.commit(&fx.owner, create, 1_700_000_000).unwrap().results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let mut stable = fx.record_mutation(record_id, Some(&base), "stable");
    stable.media = vec![media.clone()];
    fx.commit(&fx.owner, stable, 1_700_000_001).unwrap();
    let mut branch = fx.record_mutation(record_id, Some(&base), "branch");
    branch.deleted = branch_deleted;
    branch.media = if branch_deleted { vec![] } else { vec![media] };
    let result = fx.commit(&fx.owner, branch, 1_700_000_002).unwrap();
    assert_eq!(result.results[0].status, "branched");
    (
        fx,
        result.results[0].conflict_id.clone().unwrap(),
        base,
        result.results[0].branch_version_id.clone().unwrap(),
    )
}

fn mark_migration_base(fx: &CausalFx, version_id: &str) {
    let connection = fx.store.connect().unwrap();
    let (updated_at, deleted_at, payload): (i64, Option<i64>, String) = connection
        .query_row(
            "SELECT updated_at, deleted_at, payload_json FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, version_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    let mut statement = connection
        .prepare(
            "SELECT media_uuid, media_payload_json FROM entity_version_media
             WHERE family_id = ?1 AND version_id = ?2 ORDER BY media_uuid COLLATE BINARY",
        )
        .unwrap();
    let media = statement
        .query_map(params![fx.family_id, version_id], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    let mut parts = vec![
        updated_at.to_string(),
        deleted_at
            .map(|value| value.to_string())
            .unwrap_or_default(),
        payload,
    ];
    for (id, payload) in media {
        parts.extend([id, payload]);
    }
    let refs = parts.iter().map(String::as_str).collect::<Vec<_>>();
    connection
        .execute(
            "UPDATE entity_versions SET origin = 'migration_base', mutation_id = NULL,
                    content_hash = ?1 WHERE family_id = ?2 AND version_id = ?3",
            params![migration_content_hash(&refs), fx.family_id, version_id],
        )
        .unwrap();
}

#[test]
fn conflict_detail_exposes_typed_candidates_with_complete_provenance() {
    let (fx, conflict_id, _, branch_id) = seed_media_conflict(true);
    let connection = fx.store.connect().unwrap();
    let (client_uuid, mutation_id): (String, String) = connection
        .query_row(
            "SELECT client_uuid, mutation_id FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, branch_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO mutation_receipts(
               family_id, membership_id, entity_type, client_uuid, mutation_id,
               content_hash, status, stable_version_id, branch_version_id,
               conflict_id, receipt_json, created_at
             ) VALUES (?1, 'decoy-cross-membership', 'record', ?2, ?3,
                       'decoy', 'branched', NULL, NULL, ?4, '{}', 1700000002)",
            params![fx.family_id, client_uuid, mutation_id, conflict_id],
        )
        .unwrap();
    drop(connection);
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();

    assert_eq!(detail.contract, "conflict_snapshot_v2");
    assert_eq!(detail.entity_type, "record");
    assert!(!detail.stable.deleted);
    assert!(detail.stable.base_version.is_some());
    assert_eq!(detail.branches.len(), 1);
    let branch = &detail.branches[0];
    assert!(branch.media.is_empty());
    assert!(branch.deleted);
    assert!(!branch.mutation_id.is_empty());
    assert_eq!(branch.actor_id, fx.owner.membership_id);
    assert_eq!(branch.device_id, fx.owner.device_id);
    assert_eq!(branch.received_at, 1_700_000_002);

    let deleted = detail
        .conflicting
        .iter()
        .find(|item| item.path == "/_mutation.deleted")
        .expect("delete/edit is a typed conflict");
    assert_eq!(deleted.candidates.len(), 2);
    assert!(deleted
        .candidates
        .iter()
        .all(|candidate| !candidate.choice_id.is_empty() && !candidate.sources.is_empty()));
    assert!(detail
        .auto_merged
        .iter()
        .all(|item| item.path != "/_mutation.deleted"));
    let removed_media = detail
        .auto_merged
        .iter()
        .find(|item| item.path.starts_with("/media/"))
        .expect("media membership uses the same typed classifier");
    assert_eq!(removed_media.outcome, ConflictOutcome::Remove);
    assert_eq!(removed_media.sources.len(), 1);
}

#[test]
fn conflict_snapshot_keeps_explicit_null_as_a_typed_candidate() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let base = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, None, "base"),
            1_700_000_000,
        )
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    fx.commit(
        &fx.owner,
        fx.record_mutation(record_id, Some(&base), "stable"),
        1_700_000_001,
    )
    .unwrap();
    let mut null_branch = fx.record_mutation(record_id, Some(&base), "ignored");
    null_branch.root.insert("note".to_owned(), Value::Null);
    let branched = fx.commit(&fx.owner, null_branch, 1_700_000_002).unwrap();
    let detail = first_conflict_detail(
        &fx.store,
        &fx.owner,
        branched.results[0].conflict_id.as_deref().unwrap(),
    )
    .unwrap();
    let note = detail
        .conflicting
        .iter()
        .find(|item| item.path == "/note")
        .unwrap();
    assert!(note
        .candidates
        .iter()
        .any(|candidate| { candidate.outcome == ConflictOutcome::Set { value: Value::Null } }));
}

#[test]
fn conflict_snapshot_normalizes_type_dependent_subtree_replacement() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let base = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, None, "same"),
            1_700_000_000,
        )
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let root_for_type = |record_type: &str, updated_at: i64| {
        let mut root = record_root(fx.baby_id, "same", 100, updated_at);
        root.insert("type".to_owned(), Value::String(record_type.to_owned()));
        root.insert("payload_json".to_owned(), json!({}));
        root
    };
    fx.commit(
        &fx.owner,
        mut_unit(
            "record",
            record_id,
            Some(&base),
            root_for_type("walk", 30),
            false,
        ),
        1_700_000_001,
    )
    .unwrap();
    let branched = fx
        .commit(
            &fx.owner,
            mut_unit(
                "record",
                record_id,
                Some(&base),
                root_for_type("bath", 40),
                false,
            ),
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(branched.results[0].status, "branched");
    let detail = first_conflict_detail(
        &fx.store,
        &fx.owner,
        branched.results[0].conflict_id.as_deref().unwrap(),
    )
    .expect("a canonical dependent subtree replacement must be classifiable");
    assert!(detail.conflicting.iter().any(|item| item.path == "/type"));
    let payload = detail
        .auto_merged
        .iter()
        .find(|item| item.path == "/payload_json")
        .unwrap();
    assert_eq!(payload.outcome, ConflictOutcome::Set { value: json!({}) },);
    assert_eq!(payload.sources.len(), 2);
}

#[test]
fn conflict_snapshot_classifies_non_sleep_and_sleep_key_sets() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let base = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, None, "base"),
            1_700_000_000,
        )
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let mut walk = record_root(fx.baby_id, "walk", 100, 30);
    walk.insert("type".to_owned(), json!("walk"));
    walk.insert("payload_json".to_owned(), json!({}));
    fx.commit(
        &fx.owner,
        mut_unit("record", record_id, Some(&base), walk, false),
        1_700_000_001,
    )
    .unwrap();
    let sleep = sleep_root(fx.baby_id, 100, 40);
    let branched = fx
        .commit(
            &fx.owner,
            mut_unit("record", record_id, Some(&base), sleep, false),
            1_700_000_002,
        )
        .unwrap();
    assert_eq!(branched.results[0].status, "branched");
    let detail = first_conflict_detail(
        &fx.store,
        &fx.owner,
        branched.results[0].conflict_id.as_deref().unwrap(),
    )
    .expect("conditional record keys are represented by their typed source views");
    assert!(detail.conflicting.iter().any(|item| item.path == "/type"));
    let is_conditional_key =
        |path: &str| path == "/end_timestamp" || path == "/effective_wake_observation_client_uuid";
    assert!(detail
        .conflicting
        .iter()
        .all(|item| !is_conditional_key(&item.path)));
    assert!(detail
        .auto_merged
        .iter()
        .all(|item| !is_conditional_key(&item.path)));
}

#[test]
fn conflict_snapshot_preserves_same_sleep_type_conditional_choices() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let base = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, None, "base"),
            1_700_000_000,
        )
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    // Establish a valid sleep target before creating the observations. The
    // parent relation below then models two offline type transitions whose
    // direct causal base was the original non-sleep record.
    let sleep = fx
        .commit(
            &fx.owner,
            mut_unit(
                "record",
                record_id,
                Some(&base),
                sleep_root(fx.baby_id, 100, 30),
                false,
            ),
            1_700_000_001,
        )
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    let wake_a = Uuid::new_v4();
    let wake_b = Uuid::new_v4();
    for (wake_id, wake_timestamp, now) in
        [(wake_a, 120, 1_700_000_002), (wake_b, 130, 1_700_000_003)]
    {
        let accepted = fx
            .commit(
                &fx.owner,
                mut_unit(
                    "wake_observation",
                    wake_id,
                    None,
                    wake_observation_root(record_id, wake_timestamp, wake_timestamp),
                    false,
                ),
                now,
            )
            .unwrap();
        assert_eq!(accepted.results[0].status, "accepted", "{accepted:?}");
    }

    let mut head_a = sleep_root(fx.baby_id, 100, 40);
    head_a.insert(
        "effective_wake_observation_client_uuid".to_owned(),
        json!(wake_a),
    );
    let stable = fx
        .commit(
            &fx.owner,
            mut_unit("record", record_id, Some(&sleep), head_a, false),
            1_700_000_004,
        )
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();

    let connection = fx.store.connect().unwrap();
    connection
        .execute(
            "DELETE FROM entity_version_parents
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, stable],
        )
        .unwrap();
    connection
        .execute(
            "INSERT INTO entity_version_parents(
               family_id, version_id, parent_version_id
             ) VALUES (?1, ?2, ?3)",
            params![fx.family_id, stable, base],
        )
        .unwrap();
    drop(connection);

    let mut head_b = sleep_root(fx.baby_id, 100, 50);
    head_b.insert(
        "effective_wake_observation_client_uuid".to_owned(),
        json!(wake_b),
    );
    let branched = fx
        .commit(
            &fx.owner,
            mut_unit("record", record_id, Some(&base), head_b, false),
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(branched.results[0].status, "branched", "{branched:?}");

    let detail = first_conflict_detail(
        &fx.store,
        &fx.owner,
        branched.results[0].conflict_id.as_deref().unwrap(),
    )
    .expect("same-type conditional values remain server-authoritative choices");
    let effective = detail
        .conflicting
        .iter()
        .find(|item| item.path == "/effective_wake_observation_client_uuid")
        .expect("different effective wake observations conflict");
    assert_eq!(effective.candidates.len(), 2);
    assert!(effective
        .candidates
        .iter()
        .all(|candidate| candidate.sources.len() == 1));
    let mut choices = effective
        .candidates
        .iter()
        .map(|candidate| match &candidate.outcome {
            ConflictOutcome::Set { value } => value.as_str().unwrap().to_owned(),
            ConflictOutcome::Remove => panic!("effective wake choice cannot be a removal"),
        })
        .collect::<Vec<_>>();
    choices.sort();
    let mut expected = vec![wake_a.to_string(), wake_b.to_string()];
    expected.sort();
    assert_eq!(choices, expected);

    let record_type = detail
        .auto_merged
        .iter()
        .find(|item| item.path == "/type")
        .expect("the common sleep transition is auto-merged");
    assert_eq!(
        record_type.outcome,
        ConflictOutcome::Set {
            value: json!("sleep"),
        }
    );
    assert_eq!(record_type.sources.len(), 2);
    assert!(detail
        .conflicting
        .iter()
        .all(|item| item.path != "/end_timestamp"));
    assert!(detail
        .auto_merged
        .iter()
        .all(|item| item.path != "/end_timestamp"));
}

#[test]
fn conflict_snapshot_is_identical_for_every_three_branch_arrival_permutation() {
    let permutations = [
        [0, 1, 2],
        [0, 2, 1],
        [1, 0, 2],
        [1, 2, 0],
        [2, 0, 1],
        [2, 1, 0],
    ];
    let snapshots = permutations.map(three_branch_snapshot);
    let expected = deterministic_snapshot_payload(&snapshots[0]);
    for (index, snapshot) in snapshots.iter().enumerate().skip(1) {
        assert_eq!(
            deterministic_snapshot_payload(snapshot),
            expected,
            "arrival permutation {index} changed semantic snapshot bytes",
        );
    }

    let name = snapshots[0]
        .conflicting
        .iter()
        .find(|item| item.path == "/name")
        .unwrap();
    assert_eq!(name.candidates.len(), 3);
    let icon = snapshots[0]
        .auto_merged
        .iter()
        .find(|item| item.path == "/icon_slot")
        .unwrap();
    assert_eq!(icon.sources.len(), 3);
    assert!(snapshots[0].conflicting.iter().all(|conflict| snapshots[0]
        .auto_merged
        .iter()
        .all(|auto| auto.path != conflict.path)));
}

#[test]
fn conflict_detail_replays_one_persistent_snapshot_receipt_after_restart() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.as_deref().unwrap();

    let first = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(first.page_index, 0);
    assert!(first.complete);
    assert!(first.continuation.is_none());
    assert!(first.snapshot_token.len() >= 43);
    assert_eq!(first.expires_at, 1_700_000_603_000);
    let persisted: String = fx
        .store
        .connect()
        .unwrap()
        .query_row(
            "SELECT receipt_json FROM mutation_receipts
             WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'",
            params![fx.family_id],
            |row| row.get(0),
        )
        .unwrap();
    assert!(!persisted.contains(&first.snapshot_token));

    let replay = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::SnapshotToken(first.snapshot_token.clone()),
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(
        serde_json::to_vec(&replay).unwrap(),
        serde_json::to_vec(&first).unwrap()
    );

    let restarted = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    let after_restart = restarted
        .conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(
        serde_json::to_vec(&after_restart).unwrap(),
        serde_json::to_vec(&first).unwrap(),
    );
}

#[test]
fn concurrent_conflict_detail_first_reads_share_one_receipt() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let control = super::super::conflict_snapshots::test_hook::install(&fx.family_id);
    let start = |principal: Principal| {
        let store = fx.store.clone();
        let conflict_id = conflict_id.clone();
        thread::spawn(move || {
            store
                .conflict_detail_page(
                    &principal,
                    &conflict_id,
                    ConflictDetailPageRequest::First,
                    1_700_000_003,
                )
                .unwrap()
        })
    };
    let first = start(fx.owner.clone());
    control.wait_projection();
    let second = start(fx.member.clone());
    control.wait_snapshot_busy();
    control.release();
    let [first, second] = [first, second].map(|handle| handle.join().unwrap());
    assert_eq!(
        serde_json::to_vec(&first).unwrap(),
        serde_json::to_vec(&second).unwrap(),
    );
    let receipts: Value = serde_json::from_str(
        &fx.store
            .connect()
            .unwrap()
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                 WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'",
                params![fx.family_id],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();
    assert_eq!(receipts.as_array().unwrap().len(), 1);
}

#[test]
fn conflict_detail_first_and_branch_writer_are_linearizable() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-0"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let next_branch = fx.record_mutation(record_id, Some(&base), "branch-1");
    let control = super::super::conflict_snapshots::test_hook::install(&fx.family_id);
    let read = {
        let store = fx.store.clone();
        let owner = fx.owner.clone();
        let conflict_id = conflict_id.clone();
        thread::spawn(move || {
            store
                .conflict_detail_page(
                    &owner,
                    &conflict_id,
                    ConflictDetailPageRequest::First,
                    1_700_000_003,
                )
                .unwrap()
        })
    };
    control.wait_projection();
    let write = {
        let store = fx.store.clone();
        let owner = fx.owner.clone();
        thread::spawn(move || {
            store
                .causal_commit(&owner, vec![next_branch], 1_700_000_003)
                .unwrap()
        })
    };
    control.wait_writer_busy();
    control.release();
    let page = read.join().unwrap();
    assert_eq!(write.join().unwrap().results[0].status, "branched");
    let replay = fx.store.conflict_detail_page(
        &fx.owner,
        &conflict_id,
        ConflictDetailPageRequest::SnapshotToken(page.snapshot_token.clone()),
        1_700_000_004,
    );
    assert_eq!(page.branches.len(), 1);
    assert!(matches!(replay, Err(StoreError::SnapshotStale)));
}

#[test]
fn conflict_detail_pages_all_heads_with_count_and_encoded_byte_budgets() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let mut conflict_id = None;
    for index in 0..17 {
        let result = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, Some(&base), &format!("branch-{index:02}")),
                1_700_000_002,
            )
            .unwrap();
        conflict_id = result.results[0].conflict_id.clone().or(conflict_id);
    }
    let conflict_id = conflict_id.unwrap();

    let first = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            &conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(first.branches.len(), 16);
    assert!(!first.complete);
    assert!(serde_json::to_vec(&first).unwrap().len() <= 128 * 1024);
    let resolution_input = ResolveConflictInput {
        snapshot_token: first.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![resolution_choice(
            &first,
            "/note",
            ConflictOutcome::Set {
                value: json!("branch-00"),
            },
        )],
    };
    let partial_resolution = fx
        .store
        .resolve_conflict(
            &fx.owner,
            &conflict_id,
            resolution_input.clone(),
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(partial_resolution.status, "rejected");
    assert_eq!(partial_resolution.error.unwrap().code, "incomplete_choices");

    let continuation = first.continuation.clone().unwrap();
    let second = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            &conflict_id,
            ConflictDetailPageRequest::Continuation {
                snapshot_token: first.snapshot_token.clone(),
                continuation: continuation.clone(),
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(second.branches.len(), 1);
    assert_eq!(second.page_index, 1);
    assert!(second.complete);
    assert!(second.continuation.is_none());
    assert_eq!(second.snapshot_token, first.snapshot_token);
    assert!(serde_json::to_vec(&second).unwrap().len() <= 128 * 1024);

    let replay = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            &conflict_id,
            ConflictDetailPageRequest::Continuation {
                snapshot_token: first.snapshot_token.clone(),
                continuation,
            },
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(
        serde_json::to_vec(&replay).unwrap(),
        serde_json::to_vec(&second).unwrap()
    );

    let branch_ids = first
        .branches
        .iter()
        .chain(&second.branches)
        .map(|branch| branch.version_id.as_str())
        .collect::<Vec<_>>();
    assert_eq!(branch_ids.len(), 17);
    assert!(branch_ids.windows(2).all(|pair| pair[0] < pair[1]));
    let accepted = fx
        .store
        .resolve_conflict(&fx.owner, &conflict_id, resolution_input, 1_700_000_006)
        .unwrap();
    assert_eq!(accepted.status, "accepted");
}

#[test]
fn conflict_detail_rejects_one_semantic_dependency_group_over_the_byte_budget() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let mut conflict_id = None;
    for index in 0..10 {
        let note = format!("{index:02}-{}", "界".repeat(6_000));
        let result = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, Some(&base), &note),
                1_700_000_002,
            )
            .unwrap();
        conflict_id = result.results[0].conflict_id.clone().or(conflict_id);
    }

    let error = first_conflict_detail(&fx.store, &fx.owner, &conflict_id.unwrap())
        .expect_err("one indivisible path candidate set cannot be silently truncated");
    assert!(matches!(error, StoreError::ConflictSnapshotPageTooLarge));
}

#[test]
fn conflict_detail_receipt_authentication_rejects_plan_and_serializer_drift() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let mut conflict_id = None;
    for index in 0..17 {
        let result = fx
            .commit(
                &fx.owner,
                fx.record_mutation(record_id, Some(&base), &format!("branch-{index:02}")),
                1_700_000_002,
            )
            .unwrap();
        conflict_id = result.results[0].conflict_id.clone().or(conflict_id);
    }
    let conflict_id = conflict_id.unwrap();
    let first = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            &conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_003,
        )
        .unwrap();
    let connection = fx.store.connect().unwrap();
    let original: Value = serde_json::from_str(
        &connection
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                 WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'",
                params![fx.family_id],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();

    for damage in [
        "offset",
        "truncation",
        "continuation",
        "choice",
        "serializer",
    ] {
        let mut damaged = original.clone();
        let receipt = &mut damaged.as_array_mut().unwrap()[0];
        match damage {
            "offset" => receipt["page_ends"][0] = json!(15),
            "truncation" => {
                receipt["page_ends"].as_array_mut().unwrap().pop();
            }
            "continuation" => receipt["continuation_nonces"][0] = json!("tampered"),
            "choice" => {
                let choice_ids = receipt["choice_ids"].as_object_mut().unwrap();
                let first = choice_ids.values_mut().next().unwrap();
                *first = json!("tampered-choice");
            }
            "serializer" => receipt["page_digests"][0] = json!("00"),
            _ => unreachable!(),
        }
        connection
            .execute(
                "UPDATE mutation_receipts SET receipt_json = ?1
                 WHERE family_id = ?2 AND membership_id = '__conflict_snapshot_v2__'",
                params![serde_json::to_string(&damaged).unwrap(), fx.family_id],
            )
            .unwrap();
        assert!(
            matches!(
                fx.store.conflict_detail_page(
                    &fx.owner,
                    &conflict_id,
                    ConflictDetailPageRequest::SnapshotToken(first.snapshot_token.clone()),
                    1_700_000_004,
                ),
                Err(StoreError::InvalidStoredPayload | StoreError::InvalidSnapshotToken),
            ),
            "{damage}"
        );
    }
}

#[test]
fn conflict_detail_receipt_history_is_bounded_without_evicting_the_latest() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let mut tokens = Vec::new();
    let mut current_stable = String::new();
    let mut latest_page_bytes = Vec::new();
    for index in 0..65 {
        let page = fx
            .store
            .conflict_detail_page(
                &fx.owner,
                &conflict_id,
                ConflictDetailPageRequest::First,
                1_700_000_003 + index,
            )
            .unwrap();
        current_stable = page.stable.version_id.clone();
        tokens.push(page.snapshot_token.clone());
        if index == 64 {
            latest_page_bytes = serde_json::to_vec(&page).unwrap();
        }
        if index < 64 {
            let accepted = fx
                .commit(
                    &fx.owner,
                    fx.record_mutation(
                        record_id,
                        Some(&current_stable),
                        &format!("stable-{index}"),
                    ),
                    1_700_000_004 + index,
                )
                .unwrap();
            assert_eq!(accepted.results[0].status, "accepted");
        }
    }

    let persisted: Value = serde_json::from_str(
        &fx.store
            .connect()
            .unwrap()
            .query_row(
                "SELECT receipt_json FROM mutation_receipts
                 WHERE family_id = ?1 AND membership_id = '__conflict_snapshot_v2__'",
                params![fx.family_id],
                |row| row.get::<_, String>(0),
            )
            .unwrap(),
    )
    .unwrap();
    assert_eq!(persisted.as_array().unwrap().len(), 64);
    assert!(matches!(
        fx.store.conflict_detail_page(
            &fx.owner,
            &conflict_id,
            ConflictDetailPageRequest::SnapshotToken(tokens[0].clone()),
            1_700_000_100,
        ),
        Err(StoreError::InvalidSnapshotToken),
    ));
    let latest = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            &conflict_id,
            ConflictDetailPageRequest::SnapshotToken(tokens[64].clone()),
            1_700_000_100,
        )
        .unwrap();
    assert_eq!(latest.stable.version_id, current_stable);
    assert_eq!(serde_json::to_vec(&latest).unwrap(), latest_page_bytes);
}

#[test]
fn conflict_detail_tokens_fail_closed_for_tamper_expiry_and_branch_drift() {
    let fx = CausalFx::new();
    let (record_id, base) = fx.seed_concurrent_record();
    let branched = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&base), "branch-0"),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.as_deref().unwrap();
    let first = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_003,
        )
        .unwrap();

    let mut tampered = first.snapshot_token.clone();
    tampered.replace_range(0..1, if &tampered[0..1] == "a" { "b" } else { "a" });
    assert!(matches!(
        fx.store.conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::SnapshotToken(tampered),
            1_700_000_004,
        ),
        Err(StoreError::InvalidSnapshotToken),
    ));
    assert!(matches!(
        fx.store.conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::SnapshotToken(first.snapshot_token.clone()),
            1_700_000_603,
        ),
        Err(StoreError::SnapshotExpired),
    ));

    fx.commit(
        &fx.owner,
        fx.record_mutation(record_id, Some(&base), "branch-1"),
        1_700_000_004,
    )
    .unwrap();
    assert!(matches!(
        fx.store.conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::SnapshotToken(first.snapshot_token.clone()),
            1_700_000_005,
        ),
        Err(StoreError::SnapshotStale),
    ));

    let refreshed = fx
        .store
        .conflict_detail_page(
            &fx.member,
            conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_005,
        )
        .unwrap();
    assert_ne!(refreshed.snapshot_token, first.snapshot_token);
    assert_eq!(refreshed.branches.len(), 2);
    let owner_replay = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(
        serde_json::to_vec(&owner_replay).unwrap(),
        serde_json::to_vec(&refreshed).unwrap(),
        "one family snapshot receipt is shared by authenticated clients",
    );

    let stable_changed = fx
        .commit(
            &fx.owner,
            fx.record_mutation(record_id, Some(&refreshed.stable.version_id), "stable-v3"),
            1_700_000_006,
        )
        .unwrap();
    assert_eq!(stable_changed.results[0].status, "accepted");
    assert!(matches!(
        fx.store.conflict_detail_page(
            &fx.owner,
            conflict_id,
            ConflictDetailPageRequest::SnapshotToken(refreshed.snapshot_token),
            1_700_000_007,
        ),
        Err(StoreError::SnapshotStale),
    ));
}

#[test]
fn conflict_detail_fails_closed_for_incomplete_persisted_projection() {
    for damage in [
        "root",
        "parent",
        "base",
        "media",
        "provenance",
        "device_provenance",
        "multi_parent",
        "legacy_root",
        "legacy_media",
        "legacy_hash",
    ] {
        let (fx, conflict_id, base, branch) = seed_media_conflict(false);
        if damage.starts_with("legacy_") {
            mark_migration_base(&fx, &base);
            first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
        }
        let connection = fx.store.connect().unwrap();
        match damage {
            "root" => {
                connection
                    .execute(
                        "UPDATE entity_versions SET payload_json = '{}'
                         WHERE family_id = ?1 AND version_id = ?2",
                        params![fx.family_id, branch],
                    )
                    .unwrap();
            }
            "parent" => {
                connection
                    .execute(
                        "DELETE FROM entity_version_parents
                         WHERE family_id = ?1 AND version_id = ?2",
                        params![fx.family_id, branch],
                    )
                    .unwrap();
            }
            "base" => {
                connection
                    .execute_batch("PRAGMA foreign_keys = OFF")
                    .unwrap();
                connection
                    .execute(
                        "DELETE FROM entity_versions WHERE family_id = ?1 AND version_id = ?2",
                        params![fx.family_id, base],
                    )
                    .unwrap();
            }
            "media" => {
                connection
                    .execute(
                        "DELETE FROM entity_version_media
                         WHERE family_id = ?1 AND version_id = ?2",
                        params![fx.family_id, branch],
                    )
                    .unwrap();
            }
            "provenance" => {
                connection
                    .execute(
                        "UPDATE entity_versions SET mutation_id = NULL
                         WHERE family_id = ?1 AND version_id = ?2",
                        params![fx.family_id, branch],
                    )
                    .unwrap();
            }
            "device_provenance" => {
                connection
                    .execute(
                        "UPDATE mutation_receipts
                         SET receipt_json = json_remove(receipt_json, '$.device_id')
                         WHERE family_id = ?1 AND membership_id = ?2
                           AND mutation_id = ?3",
                        params![fx.family_id, VERSION_PROVENANCE_PRINCIPAL, branch],
                    )
                    .unwrap();
            }
            "multi_parent" => {
                let stable: String = connection
                    .query_row(
                        "SELECT stable_version_id FROM conflicts
                         WHERE family_id = ?1 AND conflict_id = ?2",
                        params![fx.family_id, conflict_id],
                        |row| row.get(0),
                    )
                    .unwrap();
                connection
                    .execute(
                        "INSERT INTO entity_version_parents(
                           family_id, version_id, parent_version_id
                         ) VALUES (?1, ?2, ?3)",
                        params![fx.family_id, branch, stable],
                    )
                    .unwrap();
            }
            "legacy_root" => {
                connection
                    .execute(
                        "UPDATE entity_versions SET payload_json = json_set(payload_json, '$.note', 'drift')
                         WHERE family_id = ?1 AND version_id = ?2",
                        params![fx.family_id, base],
                    )
                    .unwrap();
            }
            "legacy_media" => {
                connection
                    .execute(
                        "DELETE FROM entity_version_media WHERE family_id = ?1 AND version_id = ?2",
                        params![fx.family_id, base],
                    )
                    .unwrap();
            }
            "legacy_hash" => {
                connection
                    .execute(
                        "UPDATE entity_versions SET content_hash = ?1
                         WHERE family_id = ?2 AND version_id = ?3",
                        params!["0".repeat(64), fx.family_id, base],
                    )
                    .unwrap();
            }
            _ => unreachable!(),
        }
        drop(connection);
        let outcome = first_conflict_detail(&fx.store, &fx.owner, &conflict_id);
        assert!(
            matches!(&outcome, Err(StoreError::InvalidStoredPayload)),
            "damage={damage}, outcome={outcome:?}"
        );
    }
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
