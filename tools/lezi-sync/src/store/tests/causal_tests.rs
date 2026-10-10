//! Causal commit / pull summary / resolution — Store façade seams.

mod identity_redaction_tests;
mod media_metadata_tests;

use super::super::causal::{MAX_CAUSAL_UNITS, VERSION_PROVENANCE_PRINCIPAL};
use super::super::causal_admission::MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT;
use super::super::causal_media_staging::{claim_manifest, CausalMediaReceiptClaimError};
use super::super::causal_merge::mutation_content_hash;
use super::super::conflict_snapshots::ConflictOutcome;
use super::super::*;
use super::test_support::*;
use rusqlite::params;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::fs;
use std::sync::{mpsc, Arc, Barrier};
use std::thread;
use tempfile::{NamedTempFile, TempDir};
use uuid::Uuid;

fn map(v: Value) -> Map<String, Value> {
    v.as_object().unwrap().clone()
}

fn assert_commit_rejected(result: Result<CausalCommitResult, StoreError>, expected_code: &str) {
    match result {
        Err(StoreError::CausalCommitRejected { code, .. }) => assert_eq!(code, expected_code),
        other => panic!("expected terminal causal commit rejection, got {other:?}"),
    }
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

#[test]
fn new_sleep_mutation_rejects_end_timestamp() {
    let fx = CausalFx::new();
    let sleep_id = Uuid::new_v4();
    let mut root = sleep_root(fx.baby_id, 100, 20);
    root.insert("end_timestamp".to_owned(), json!(200));
    assert_commit_rejected(
        fx.commit(
            &fx.owner,
            mut_unit("record", sleep_id, None, root, false),
            1_700_000_000,
        ),
        "invalid_domain",
    );
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
        let connection = store.connect().unwrap();
        connection.execute("INSERT INTO memberships(membership_id,family_id,role,display_name,display_name_key) VALUES (?1,?2,'member','合成成员','合成成员')",params![member.membership_id,family_id]).unwrap();
        connection.execute("INSERT INTO devices(device_id,membership_id,device_name,device_name_key,status,created_at,last_used_at) VALUES (?1,?2,'synthetic-member','synthetic-member','active',1,1)",params![member.device_id,member.membership_id]).unwrap();
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
        let incoming = NamedTempFile::new().unwrap();
        fs::write(incoming.path(), &bytes).unwrap();
        let verified = VerifiedCausalMediaPreimage::verify(
            incoming.path().to_owned(),
            &item.sha256,
            DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS.max_file_bytes,
        )
        .unwrap();
        self.store
            .stage_verified_causal_media_preimage(
                &self.owner,
                &item.media_uuid,
                &verified,
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
    ) -> Result<CausalCommitResult, StoreError> {
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
    let initial = serde_json::to_value(page).unwrap();
    let owner = initial["stable"]["actor_id"].as_str().unwrap();
    let device = initial["stable"]["device_id"].as_str().unwrap();
    let raw = serde_json::to_string(&initial)
        .unwrap()
        .replace(owner, "synthetic-owner")
        .replace(device, "synthetic-owner-device");
    let mut value: Value = serde_json::from_str(&raw).unwrap();
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
        register_test_principal(&fx.store, &principal);
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
    register_test_principal(&fx.store, &rotated_device);
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
    assert_eq!(replay.results[0].status, first.results[0].status);
    assert_eq!(
        replay.results[0].stable_version_id,
        first.results[0].stable_version_id
    );
    assert!(replay.results[0].replay);

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
    assert_eq!(replay.results[0].status, member_result.results[0].status);
    assert_eq!(
        replay.results[0].stable_version_id,
        member_result.results[0].stable_version_id,
    );
    assert!(replay.results[0].replay);

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
        Err(StoreError::InvalidCausalBatch)
    ));
    assert_principal_saturated(&oversize, 1_700_000_001);

    let invalid = CausalFx::with_admission(admission(2, 100, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    assert_commit_rejected(
        invalid.store.causal_commit(
            &invalid.owner,
            vec![mut_unit(
                "unknown",
                Uuid::new_v4(),
                None,
                map(json!({"updated_at": 20})),
                false,
            )],
            1_700_000_001,
        ),
        "invalid_domain",
    );
    assert_principal_saturated(&invalid, 1_700_000_001);

    let drift = CausalFx::with_admission(admission(3, 100, MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT));
    let original = drift.record_mutation(Uuid::new_v4(), None, "original");
    drift
        .commit(&drift.owner, original.clone(), 1_700_000_001)
        .unwrap();
    let mut changed = original;
    changed.root.insert("note".to_owned(), json!("changed"));
    assert_commit_rejected(
        drift.commit(&drift.owner, changed, 1_700_000_001),
        "content_drift",
    );
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
    assert!(replay.results[0].replay);
    assert_eq!(
        replay.results[0].stable_version_id.as_deref(),
        Some(v1.as_str())
    );

    // Content drift under same mutation_id → rejected.
    unit.root.insert("note".to_owned(), json!("drift"));
    let drift = fx
        .store
        .causal_commit(&fx.owner, vec![unit], 1_700_000_002)
        .unwrap_err();
    assert!(matches!(
        drift,
        StoreError::CausalCommitRejected { ref code, .. } if code == "content_drift"
    ));
}

#[test]
fn causal_commit_mixed_live_delete_and_media_units_share_one_terminal_batch() {
    let fx = CausalFx::new();
    let mut media = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".to_owned(),
        sha256: String::new(),
        byte_size: 7,
        mime: "image/jpeg".to_owned(),
        width: None,
        height: None,
    };
    fx.stage_media_bytes(&mut media);
    let live = fx.record_mutation(Uuid::new_v4(), None, "live");
    let tombstone = mut_unit(
        "record",
        Uuid::new_v4(),
        None,
        record_root(fx.baby_id, "delete", 100, 41),
        true,
    );
    let mut with_media = fx.record_mutation(Uuid::new_v4(), None, "media");
    with_media.media = vec![media.clone()];

    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![live, tombstone, with_media], 1_700_000_000)
        .unwrap();

    assert_eq!(committed.results.len(), 3);
    assert!(committed
        .results
        .iter()
        .all(|result| result.status == "accepted"));
    assert!(committed.results.iter().all(|result| !result.replay));
    assert!(committed.results[0].stable_deleted_at.is_none());
    assert!(committed.results[1].stable_deleted_at.is_some());
    assert_eq!(committed.results[2].stable_media, vec![media]);
}

#[test]
fn causal_current_base_delete_is_not_a_sync_conflict() {
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
    assert!(
        deleted.results[0].conflict_id.is_none(),
        "stable delete without branches is not a sync conflict"
    );
    let deleted_at = deleted.results[0].stable_deleted_at;
    assert!(deleted_at.is_some());

    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let row = page
        .entities
        .iter()
        .find(|e| e.client_uuid == record_id.to_string())
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

    // Simulate a receipt written before H25 persisted tombstone evidence. The
    // exact immutable version, not the mutable head, repairs the replay.
    let deletion_mutation_id = del.mutation_id.clone();
    let connection = fx.store.connect().unwrap();
    let receipt_json: String = connection
        .query_row(
            "SELECT receipt_json FROM mutation_receipts
             WHERE family_id = ?1 AND membership_id = ?2 AND mutation_id = ?3",
            params![fx.family_id, fx.owner.membership_id, deletion_mutation_id],
            |row| row.get(0),
        )
        .unwrap();
    let mut legacy_receipt: Value = serde_json::from_str(&receipt_json).unwrap();
    legacy_receipt
        .as_object_mut()
        .unwrap()
        .remove("stable_deleted_at");
    connection
        .execute(
            "UPDATE mutation_receipts SET receipt_json = ?1
             WHERE family_id = ?2 AND membership_id = ?3 AND mutation_id = ?4",
            params![
                serde_json::to_string(&legacy_receipt).unwrap(),
                fx.family_id,
                fx.owner.membership_id,
                deletion_mutation_id,
            ],
        )
        .unwrap();

    // Idempotent delete replay preserves the original tombstone projection.
    let replay = fx
        .store
        .causal_commit(&fx.owner, vec![del], 1_700_000_002)
        .unwrap();
    assert_eq!(replay.results[0].status, "accepted");
    assert!(replay.results[0].replay);
    assert_eq!(replay.results[0].stable_deleted_at, deleted_at);
    assert_eq!(
        replay.results[0].stable_version_id.as_deref(),
        Some(v2.as_str())
    );
    assert!(replay.results[0].conflict_id.is_none());

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
    assert_commit_rejected(
        fx.store
            .causal_commit(&fx.owner, vec![stale], 1_700_000_003),
        "invalid_domain",
    );
}

#[test]
fn leftover_empty_open_conflict_closes_via_maintenance_and_delivers_rev_bump() {
    // 0.5 contract (design §1.1 / B6 / C4): the empty-open closure no longer
    // runs on the pull read path. A residual row keeps its conflict_summary
    // visible until a writing transaction or the maintenance sweep closes it,
    // and the closure keeps its delivery semantics: head + entity rev advance
    // so an already-caught-up pull-only device receives the summary's
    // disappearance. The tombstone bytes themselves never change.
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let created = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                record_id,
                None,
                record_root(fx.baby_id, "live", 100, 20),
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    let v1 = created.results[0].stable_version_id.clone().unwrap();
    let deleted = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                record_id,
                Some(&v1),
                record_root(fx.baby_id, "live", 100, 21),
                true,
            )],
            1_700_000_001,
        )
        .unwrap();
    let tombstone = deleted.results[0].stable_version_id.clone().unwrap();
    let deleted_at = deleted.results[0].stable_deleted_at;
    let leftover = insert_open_empty_conflict(
        &fx,
        "record",
        &record_id.to_string(),
        &tombstone,
        "tombstone_restore",
        1_700_000_002,
    );
    let synced_cursor = fx.store.current_revision(&fx.family_id).unwrap();

    // Read path no longer closes: pull responses never surface empty-open
    // rows (the sidecar query filters them), but the leftover stays 'open'
    // in the database until a write transaction or the maintenance sweep
    // resolves it.
    let stale_head = fx.store.current_revision(&fx.family_id).unwrap();
    fx.store.pull(&fx.family_id, 0).unwrap();
    let still_open: String = fx
        .store
        .connect()
        .unwrap()
        .query_row(
            "SELECT status FROM conflicts WHERE family_id = ?1 AND conflict_id = ?2",
            params![fx.family_id, leftover],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(still_open, "open");
    assert_eq!(
        fx.store.current_revision(&fx.family_id).unwrap(),
        stale_head
    );

    // Maintenance sweep closes the leftover (startup/periodic fallback).
    fx.store
        .close_leftover_empty_open_conflicts(1_700_000_003)
        .unwrap();
    let closure_head = fx.store.current_revision(&fx.family_id).unwrap();
    assert!(
        closure_head > synced_cursor,
        "closure must advance the head to deliver the summary's disappearance"
    );

    // The caught-up pull-only device now receives the row again (rev bump)
    // without the conflict_summary; the tombstone bytes are unchanged.
    let page = fx.store.pull(&fx.family_id, synced_cursor).unwrap();
    let row = page
        .entities
        .iter()
        .find(|entity| entity.client_uuid == record_id.to_string())
        .unwrap();
    assert!(row.conflict_summary.is_none());
    assert_eq!(row.deleted_at, deleted_at);
    assert_eq!(row.version_id.as_deref(), Some(tombstone.as_str()));

    let connection = fx.store.connect().unwrap();
    let (status, resolved_stable): (String, String) = connection
        .query_row(
            "SELECT status, stable_version_id FROM conflicts
              WHERE family_id = ?1 AND conflict_id = ?2",
            params![fx.family_id, leftover],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(status, "resolved");
    assert_eq!(resolved_stable, tombstone);
    assert!(matches!(
        first_conflict_detail(&fx.store, &fx.owner, &leftover),
        Err(StoreError::ConflictNotFound)
    ));

    // The sweep is idempotent: a second pass finds nothing and the head is
    // unchanged.
    fx.store
        .close_leftover_empty_open_conflicts(1_700_000_004)
        .unwrap();
    assert_eq!(
        fx.store.current_revision(&fx.family_id).unwrap(),
        closure_head
    );
}

fn insert_open_empty_conflict(
    fx: &CausalFx,
    entity_type: &str,
    client_uuid: &str,
    stable_version_id: &str,
    kind: &str,
    created_at: i64,
) -> String {
    let conflict_id = Uuid::new_v4().to_string();
    fx.store
        .connect()
        .unwrap()
        .execute(
            "INSERT INTO conflicts(
                family_id, conflict_id, entity_type, client_uuid, base_version_id,
                stable_version_id, status, kind, created_at, resolved_at
             ) VALUES (?1, ?2, ?3, ?4, NULL, ?5, 'open', ?6, ?7, NULL)",
            params![
                fx.family_id,
                conflict_id,
                entity_type,
                client_uuid,
                stable_version_id,
                kind,
                created_at,
            ],
        )
        .unwrap();
    conflict_id
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
    assert_commit_rejected(
        fx.commit(&fx.owner, invalid_commit, 1_700_000_004),
        "invalid_domain",
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
fn h39_media_branch_matrix_preserves_exact_bytes_for_record_baby_and_care_plan() {
    for (entity_type, role) in [("record", "log"), ("baby", "avatar"), ("care_plan", "plan")] {
        let fx = CausalFx::new();
        let root_id = Uuid::new_v4();

        // Independent add/add: Record and CarePlan merge disjoint media UUIDs;
        // Baby's one-avatar cap deliberately remains a choice-only branch.
        let base_root = h39_root(entity_type, fx.baby_id, "base", 100, None);
        let base = fx
            .commit(
                &fx.owner,
                mut_unit(entity_type, root_id, None, base_root, false),
                1_700_000_000,
            )
            .unwrap()
            .results[0]
            .stable_version_id
            .clone()
            .unwrap();
        let mut added_a = h39_media(role, 11);
        let mut added_b = h39_media(role, 12);
        fx.stage_media_bytes(&mut added_a);
        fx.stage_media_bytes(&mut added_b);
        let stable_add = h39_root(entity_type, fx.baby_id, "same", 101, Some(&added_a));
        let mut stable = mut_unit(entity_type, root_id, Some(&base), stable_add, false);
        stable.media = vec![added_a.clone()];
        assert_eq!(
            fx.commit(&fx.owner, stable, 1_700_000_001).unwrap().results[0].status,
            "accepted"
        );
        let mut incoming = mut_unit(
            entity_type,
            root_id,
            Some(&base),
            h39_root(entity_type, fx.baby_id, "same", 102, Some(&added_b)),
            false,
        );
        incoming.media = vec![added_b.clone()];
        let add_result = fx.commit(&fx.owner, incoming, 1_700_000_002).unwrap();
        if entity_type == "baby" {
            assert_eq!(add_result.results[0].status, "branched");
            let conflict_id = add_result.results[0].conflict_id.clone().unwrap();
            let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
            let branch_version = add_result.results[0].branch_version_id.clone().unwrap();
            let choices = detail
                .conflicting
                .iter()
                .map(|path| {
                    let candidate = path
                        .candidates
                        .iter()
                        .find(|candidate| {
                            candidate
                                .sources
                                .iter()
                                .any(|source| source.version_id == branch_version)
                        })
                        .unwrap();
                    ConflictResolutionChoice {
                        path: path.path.clone(),
                        choice_id: candidate.choice_id.clone(),
                    }
                })
                .collect::<Vec<_>>();
            let resolved = fx
                .store
                .resolve_conflict(
                    &fx.owner,
                    &conflict_id,
                    ResolveConflictInput {
                        snapshot_token: detail.snapshot_token,
                        resolution_mutation_id: Uuid::new_v4().to_string(),
                        choices,
                    },
                    1_700_000_003,
                )
                .unwrap();
            assert_eq!(resolved.status, "accepted", "resolution={resolved:?}");
            assert_eq!(resolved.stable_media, vec![added_b.clone()]);
            assert_eq!(
                fs::read(
                    fx._dir
                        .path()
                        .join("media")
                        .join(&fx.family_id)
                        .join(&added_b.media_uuid)
                )
                .unwrap(),
                vec![0; added_b.byte_size as usize]
            );
        } else {
            assert_eq!(add_result.results[0].status, "merged");
            let mut expected_media = vec![added_a.clone(), added_b.clone()];
            expected_media.sort_by(|left, right| left.media_uuid.cmp(&right.media_uuid));
            assert_eq!(add_result.results[0].stable_media, expected_media);
            for item in [&added_a, &added_b] {
                assert_eq!(
                    fs::read(
                        fx._dir
                            .path()
                            .join("media")
                            .join(&fx.family_id)
                            .join(&item.media_uuid)
                    )
                    .unwrap(),
                    vec![0; item.byte_size as usize]
                );
            }
        }

        // Same-media delete/edit: both sides changed the shared media path in
        // different ways. Choice-only resolution must be able to retain the
        // exact base bytes instead of silently dropping the attachment.
        let mut same_media = h39_media(role, 13);
        fx.stage_media_bytes(&mut same_media);
        let mut edited_media = same_media.clone();
        edited_media.width = Some(8);
        let same_root_id = Uuid::new_v4();
        let mut create = mut_unit(
            entity_type,
            same_root_id,
            None,
            h39_root(entity_type, fx.baby_id, "same-base", 110, Some(&same_media)),
            false,
        );
        create.media = vec![same_media.clone()];
        let same_base = fx.commit(&fx.owner, create, 1_700_000_010).unwrap().results[0]
            .stable_version_id
            .clone()
            .unwrap();
        let delete = mut_unit(
            entity_type,
            same_root_id,
            Some(&same_base),
            h39_root(entity_type, fx.baby_id, "stable-edit", 111, None),
            false,
        );
        let _stable_delete = fx.commit(&fx.owner, delete, 1_700_000_011).unwrap();
        let mut edit = mut_unit(
            entity_type,
            same_root_id,
            Some(&same_base),
            h39_root(
                entity_type,
                fx.baby_id,
                "incoming-edit",
                112,
                Some(&edited_media),
            ),
            false,
        );
        edit.media = vec![edited_media.clone()];
        let branch = fx.commit(&fx.owner, edit, 1_700_000_012).unwrap();
        assert_eq!(branch.results[0].status, "branched");
        let conflict_id = branch.results[0].conflict_id.clone().unwrap();
        let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
        let branch_version = branch.results[0].branch_version_id.clone().unwrap();
        let choices = detail
            .conflicting
            .iter()
            .map(|path| {
                let candidate = path
                    .candidates
                    .iter()
                    .find(|candidate| {
                        candidate
                            .sources
                            .iter()
                            .any(|source| source.version_id == branch_version)
                    })
                    .unwrap();
                ConflictResolutionChoice {
                    path: path.path.clone(),
                    choice_id: candidate.choice_id.clone(),
                }
            })
            .collect::<Vec<_>>();
        let resolved = fx
            .store
            .resolve_conflict(
                &fx.owner,
                &conflict_id,
                ResolveConflictInput {
                    snapshot_token: detail.snapshot_token,
                    resolution_mutation_id: Uuid::new_v4().to_string(),
                    choices,
                },
                1_700_000_013,
            )
            .unwrap();
        assert_eq!(resolved.status, "accepted");
        assert_eq!(resolved.stable_media, vec![edited_media.clone()]);
        if entity_type != "baby" {
            assert_eq!(resolved.stable_root["note"], json!("incoming-edit"));
        }
        assert_eq!(
            fs::read(
                fx._dir
                    .path()
                    .join("media")
                    .join(&fx.family_id)
                    .join(&same_media.media_uuid)
            )
            .unwrap(),
            vec![0; same_media.byte_size as usize]
        );
    }
}

fn h39_media(role: &str, fill: u8) -> CausalMediaItem {
    CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: role.to_owned(),
        sha256: String::new(),
        byte_size: i64::from(fill) + 8,
        mime: "image/jpeg".to_owned(),
        width: Some(4),
        height: Some(4),
    }
}

fn h39_root(
    entity_type: &str,
    baby_id: Uuid,
    note: &str,
    updated_at: i64,
    media: Option<&CausalMediaItem>,
) -> Map<String, Value> {
    match entity_type {
        "record" => record_root(baby_id, note, 100, updated_at),
        "baby" => map(json!({
            "nickname": "H39宝宝",
            "sex": "female",
            "birthday": "2025-01-02",
            "avatar_media_uuid": media.map(|item| item.media_uuid.clone()),
            "updated_at": updated_at,
        })),
        "care_plan" => map(json!({
            "baby_client_uuid": baby_id,
            "type": "bath",
            "custom_item_client_uuid": null,
            "scheduled_at": 1_700_000_000_000i64,
            "scheduled_zone_id": "Asia/Shanghai",
            "note": note,
            "status": "pending",
            "payload_json": {},
            "schema_version": 2,
            "created_by_membership_id": "m-owner",
            "fulfilled_record_client_uuid": null,
            "fulfilled_at": null,
            "source_record_client_uuid": null,
            "updated_at": updated_at,
        })),
        _ => panic!("unsupported H39 root: {entity_type}"),
    }
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
    assert!(deleted.results[0].conflict_id.is_none());
    let tombstone_version = deleted.results[0].stable_version_id.clone().unwrap();
    let conflict_id = fx
        .store
        .open_tombstone_restore_handle(
            &fx.family_id,
            "record",
            &record_id.to_string(),
            &tombstone_version,
            1_700_000_001,
        )
        .unwrap()
        .expect("authorized restore CAS still has a lazy handle");
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
    register_test_principal(&fx.store, &member);
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
    assert_commit_rejected(
        fx.store
            .causal_commit(&member, vec![baby_edit], 1_700_000_000),
        "forbidden",
    );
}

#[test]
fn choice_only_resolution_keeps_baby_owner_only_after_author_downgrade() {
    let fx = CausalFx::new();
    let baby_version = fx
        .store
        .pull(&fx.family_id, 0)
        .unwrap()
        .entities
        .into_iter()
        .find(|entity| entity.client_uuid == fx.baby_id.to_string())
        .and_then(|entity| entity.version_id)
        .unwrap();
    let stable = fx
        .commit(
            &fx.owner,
            mut_unit(
                "baby",
                fx.baby_id,
                Some(&baby_version),
                baby_root("stable-baby", 20),
                false,
            ),
            1_700_000_001,
        )
        .unwrap();
    assert_eq!(stable.results[0].status, "accepted");
    let branched = fx
        .commit(
            &fx.owner,
            mut_unit(
                "baby",
                fx.baby_id,
                Some(&baby_version),
                baby_root("branch-baby", 30),
                false,
            ),
            1_700_000_002,
        )
        .unwrap();
    let conflict_id = branched.results[0].conflict_id.clone().unwrap();
    let detail = first_conflict_detail(&fx.store, &fx.owner, &conflict_id).unwrap();
    let downgraded_author = Principal {
        role: "member".to_owned(),
        ..fx.owner.clone()
    };
    fx.store
        .connect()
        .unwrap()
        .execute(
            "UPDATE memberships SET role='member' WHERE membership_id=?1",
            params![fx.owner.membership_id],
        )
        .unwrap();
    let result = fx
        .store
        .resolve_conflict(
            &downgraded_author,
            &conflict_id,
            ResolveConflictInput {
                snapshot_token: detail.snapshot_token.clone(),
                resolution_mutation_id: Uuid::new_v4().to_string(),
                choices: vec![resolution_choice(
                    &detail,
                    "/nickname",
                    ConflictOutcome::Set {
                        value: json!("branch-baby"),
                    },
                )],
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(result.status, "rejected");
    assert_eq!(result.error.unwrap().code, "forbidden");
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
    let cursor_before = fx.store.pull(&fx.family_id, 0).unwrap().cursor;

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
    assert_eq!(replay.results[0].status, first.results[0].status);
    assert_eq!(
        replay.results[0].stable_version_id,
        first.results[0].stable_version_id
    );
    assert_eq!(
        replay.results[0].branch_version_id,
        first.results[0].branch_version_id
    );
    assert!(replay.results[0].replay, "exact replay must be marked");

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
    assert_eq!(one_branch_statements, 6);
    assert_eq!(full_branch_statements, 5);

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

/// Schema-12 → 13 offline migrate copied LWW payload_json without embedding
/// `updated_at` (that stays the version column). Family NAS production rows
/// look like this; load_stable must still accept them.
fn strip_root_updated_at_and_rehash_migration_base(fx: &CausalFx, version_id: &str) {
    let connection = fx.store.connect().unwrap();
    let payload: String = connection
        .query_row(
            "SELECT payload_json FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, version_id],
            |row| row.get(0),
        )
        .unwrap();
    let mut root: Map<String, Value> = serde_json::from_str(&payload).unwrap();
    assert!(
        root.remove("updated_at").is_some(),
        "fixture root must start with updated_at so the strip is meaningful"
    );
    connection
        .execute(
            "UPDATE entity_versions SET payload_json = ?1
             WHERE family_id = ?2 AND version_id = ?3",
            params![
                serde_json::to_string(&root).unwrap(),
                fx.family_id,
                version_id
            ],
        )
        .unwrap();
    drop(connection);
    mark_migration_base(fx, version_id);
}

#[test]
fn causal_commit_edits_migration_base_that_omits_updated_at_in_payload() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let created = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                record_id,
                None,
                record_root(fx.baby_id, "base", 100, 20),
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(created.results[0].status, "accepted");
    let base = created.results[0].stable_version_id.clone().unwrap();
    strip_root_updated_at_and_rehash_migration_base(&fx, &base);

    let edit = mut_unit(
        "record",
        record_id,
        Some(&base),
        record_root(fx.baby_id, "adapted", 100, 30),
        false,
    );
    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![edit], 1_700_000_001)
        .expect("migration_base without root updated_at must still load for commit");
    assert_eq!(committed.results[0].status, "accepted");
    assert!(!committed.results[0].replay);

    let (_, _, snapshot) = fx
        .store
        .load_validated_version_for_test(&fx.family_id, &base)
        .expect("无媒体 migration_base 必须继续 因果 reload")
        .expect("migration_base version exists");
    assert!(snapshot.media.is_empty());
}

/// Household `entity_version_media.media_payload_json` for `origin=migration_base`
/// is the pre-causal LWW media entity — `kind` plus association UUIDs, no
/// `media_uuid` / `role` / `sha256` inside the JSON.
fn household_lww_media_payload(
    entity_type: &str,
    client_uuid: &str,
    item: &CausalMediaItem,
) -> String {
    let kind = match (entity_type, item.role.as_str()) {
        ("baby", "avatar") => "avatar",
        ("record", "log") => "log",
        other => panic!("fixture covers 宝宝头像 and 护理记录 log only: {other:?}"),
    };
    let (record, baby, care_plan) = match entity_type {
        "baby" => (Value::Null, json!(client_uuid), Value::Null),
        "record" => (json!(client_uuid), Value::Null, Value::Null),
        other => panic!("unsupported LWW media root {other}"),
    };
    let payload = json!({
        "baby_client_uuid": baby,
        "byte_size": item.byte_size,
        "care_plan_client_uuid": care_plan,
        "height": item.height,
        "kind": kind,
        "mime": item.mime,
        "record_client_uuid": record,
        "width": item.width,
    });
    let object = payload.as_object().unwrap();
    assert!(!object.contains_key("media_uuid"));
    assert!(!object.contains_key("role"));
    assert!(!object.contains_key("sha256"));
    serde_json::to_string(&payload).unwrap()
}

fn rewrite_version_media_to_household_lww(fx: &CausalFx, version_id: &str) {
    let connection = fx.store.connect().unwrap();
    let (entity_type, client_uuid): (String, String) = connection
        .query_row(
            "SELECT entity_type, client_uuid FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, version_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    let mut statement = connection
        .prepare(
            "SELECT media_uuid, media_payload_json FROM entity_version_media
             WHERE family_id = ?1 AND version_id = ?2 ORDER BY media_uuid COLLATE BINARY",
        )
        .unwrap();
    let rows = statement
        .query_map(params![fx.family_id, version_id], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    drop(statement);
    for (media_uuid, payload) in rows {
        let value: Value = serde_json::from_str(&payload).unwrap();
        let item = CausalMediaItem::from_value(&value)
            .expect("today's causal path stores closed media JSON");
        let lww = household_lww_media_payload(&entity_type, &client_uuid, &item);
        connection
            .execute(
                "UPDATE entity_version_media SET media_payload_json = ?1
                 WHERE family_id = ?2 AND version_id = ?3 AND media_uuid = ?4",
                params![lww, fx.family_id, version_id, media_uuid],
            )
            .unwrap();
    }
}

fn stored_version_media_payloads(fx: &CausalFx, version_id: &str) -> Vec<(String, String)> {
    let connection = fx.store.connect().unwrap();
    let mut statement = connection
        .prepare(
            "SELECT media_uuid, media_payload_json FROM entity_version_media
             WHERE family_id = ?1 AND version_id = ?2 ORDER BY media_uuid COLLATE BINARY",
        )
        .unwrap();
    statement
        .query_map(params![fx.family_id, version_id], |row| {
            Ok((row.get(0)?, row.get(1)?))
        })
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap()
}

fn published_media_path(fx: &CausalFx, media_uuid: &str) -> std::path::PathBuf {
    fx._dir
        .path()
        .join("media")
        .join(&fx.family_id)
        .join(media_uuid)
}

struct MigrationBaseMediaHead {
    client_uuid: Uuid,
    version_id: String,
    media: CausalMediaItem,
    lww_payload: String,
    published_bytes: Vec<u8>,
}

fn commit_migration_base_lww_media_head(
    fx: &CausalFx,
    entity_type: &str,
    role: &str,
    now: i64,
) -> MigrationBaseMediaHead {
    let client_uuid = Uuid::new_v4();
    let mut media = h39_media(role, 16);
    fx.stage_media_bytes(&mut media);
    let mut create = mut_unit(
        entity_type,
        client_uuid,
        None,
        h39_root(entity_type, fx.baby_id, "household-lww", 20, Some(&media)),
        false,
    );
    create.media = vec![media.clone()];
    let created = fx
        .store
        .causal_commit(&fx.owner, vec![create], now)
        .unwrap();
    assert_eq!(created.results[0].status, "accepted");
    let version_id = created.results[0].stable_version_id.clone().unwrap();
    let published_bytes = fs::read(published_media_path(fx, &media.media_uuid)).unwrap();
    rewrite_version_media_to_household_lww(fx, &version_id);
    mark_migration_base(fx, &version_id);
    let stored = stored_version_media_payloads(fx, &version_id);
    assert_eq!(stored.len(), 1);
    assert_eq!(stored[0].0, media.media_uuid);
    let lww_payload = stored[0].1.clone();
    let lww: Map<String, Value> = serde_json::from_str(&lww_payload).unwrap();
    assert!(!lww.contains_key("media_uuid"));
    assert!(!lww.contains_key("role"));
    assert!(!lww.contains_key("sha256"));
    MigrationBaseMediaHead {
        client_uuid,
        version_id,
        media,
        lww_payload,
        published_bytes,
    }
}

fn assert_migration_base_lww_media_reload(
    fx: &CausalFx,
    entity_type: &str,
    head: &MigrationBaseMediaHead,
) {
    let (loaded_type, loaded_uuid, snapshot) = fx
        .store
        .load_validated_version_for_test(&fx.family_id, &head.version_id)
        .expect("带媒体的 migration_base 必须通过 因果 reload")
        .expect("migration_base version exists");
    assert_eq!(loaded_type, entity_type);
    assert_eq!(loaded_uuid, head.client_uuid.to_string());
    assert_eq!(snapshot.media.len(), 1);
    let item = &snapshot.media[0];
    assert_eq!(item.media_uuid, head.media.media_uuid);
    assert_eq!(item.role, head.media.role);
    assert_eq!(item.sha256, head.media.sha256);
    assert_eq!(item.byte_size, head.media.byte_size);
    assert_eq!(item.mime, head.media.mime);
    item.validate_for_entity(entity_type)
        .expect("projected 媒体清单项 must be valid for the entity");
    assert_eq!(
        stored_version_media_payloads(fx, &head.version_id),
        vec![(head.media.media_uuid.clone(), head.lww_payload.clone())],
        "因果 reload must not rewrite stored LWW media_payload_json"
    );
    assert_eq!(
        fs::read(published_media_path(fx, &head.media.media_uuid)).unwrap(),
        head.published_bytes,
        "因果 reload must not rewrite published media bytes"
    );
}

#[test]
fn causal_reload_migration_base_baby_with_avatar() {
    let fx = CausalFx::new();
    let head = commit_migration_base_lww_media_head(&fx, "baby", "avatar", 1_700_000_010);
    assert_migration_base_lww_media_reload(&fx, "baby", &head);
}

#[test]
fn causal_reload_migration_base_record_with_log_image() {
    let fx = CausalFx::new();
    let head = commit_migration_base_lww_media_head(&fx, "record", "log", 1_700_000_011);
    assert_migration_base_lww_media_reload(&fx, "record", &head);
}

#[test]
fn causal_commit_first_migration_base_baby_with_avatar() {
    let fx = CausalFx::new();
    let head = commit_migration_base_lww_media_head(&fx, "baby", "avatar", 1_700_000_010);
    let mut edit_root = h39_root("baby", fx.baby_id, "adapted", 30, Some(&head.media));
    edit_root.insert("nickname".to_owned(), json!("改名宝宝"));
    let mut edit = mut_unit(
        "baby",
        head.client_uuid,
        Some(&head.version_id),
        edit_root,
        false,
    );
    edit.media = vec![head.media.clone()];
    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![edit], 1_700_000_012)
        .expect("commit-first on 宝宝头像 migration_base must not be InvalidStoredPayload");
    assert_eq!(committed.results[0].status, "accepted");
    assert!(!committed.results[0].replay);
    assert_eq!(
        stored_version_media_payloads(&fx, &head.version_id)[0].1,
        head.lww_payload,
        "commit-first must not rewrite the migration_base LWW media JSON"
    );
}

#[test]
fn causal_commit_first_migration_base_record_with_log_image() {
    let fx = CausalFx::new();
    let head = commit_migration_base_lww_media_head(&fx, "record", "log", 1_700_000_011);
    let mut edit = mut_unit(
        "record",
        head.client_uuid,
        Some(&head.version_id),
        h39_root("record", fx.baby_id, "adapted", 30, Some(&head.media)),
        false,
    );
    edit.media = vec![head.media.clone()];
    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![edit], 1_700_000_013)
        .expect("commit-first on 护理记录 migration_base must not be InvalidStoredPayload");
    assert_eq!(committed.results[0].status, "accepted");
    assert!(!committed.results[0].replay);
    assert_eq!(
        stored_version_media_payloads(&fx, &head.version_id)[0].1,
        head.lww_payload,
        "commit-first must not rewrite the migration_base LWW media JSON"
    );
}

#[test]
fn causal_reload_migration_base_lww_media_fails_closed_without_published_bytes() {
    for damage in ["missing", "wrong_size"] {
        let fx = CausalFx::new();
        let head = commit_migration_base_lww_media_head(&fx, "record", "log", 1_700_000_011);
        let path = published_media_path(&fx, &head.media.media_uuid);
        match damage {
            "missing" => fs::remove_file(&path).unwrap(),
            "wrong_size" => {
                fs::write(&path, vec![0u8; (head.media.byte_size as usize) - 1]).unwrap()
            }
            other => panic!("unknown damage {other}"),
        }
        assert!(
            matches!(
                fx.store
                    .load_validated_version_for_test(&fx.family_id, &head.version_id),
                Err(StoreError::InvalidStoredPayload)
            ),
            "{damage} published media must fail closed as InvalidStoredPayload"
        );
        assert_eq!(
            stored_version_media_payloads(&fx, &head.version_id)[0].1,
            head.lww_payload,
            "fail-closed reload must not rewrite stored LWW media JSON"
        );
    }
}

/// Household-equivalent accepted 护理计划: source key was never stored, and
/// `content_hash` is `root_content_hash` of that pre-projection root.
/// Fixture-only surgery — production never rewrites `entity_versions`.
fn strip_care_plan_source_key_and_rehash_stored_root(fx: &CausalFx, version_id: &str) {
    let connection = fx.store.connect().unwrap();
    connection
        .execute(
            "UPDATE entity_versions
             SET payload_json = json_remove(payload_json, '$.source_record_client_uuid')
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, version_id],
        )
        .unwrap();
    connection
        .execute(
            "UPDATE entities
             SET payload_json = json_remove(payload_json, '$.source_record_client_uuid')
             WHERE family_id = ?1 AND entity_type = 'care_plan' AND client_uuid = (
                 SELECT client_uuid FROM entity_versions
                  WHERE family_id = ?1 AND version_id = ?2
             )",
            params![fx.family_id, version_id],
        )
        .unwrap();
    let (payload_json, deleted_at): (String, Option<i64>) = connection
        .query_row(
            "SELECT payload_json, deleted_at FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, version_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    let stripped_root: Map<String, Value> = serde_json::from_str(&payload_json).unwrap();
    assert!(
        !stripped_root.contains_key("source_record_client_uuid"),
        "fixture must match household rows that never stored the source key"
    );
    let content_hash =
        mutation_content_hash("_", "_", None, deleted_at.is_some(), &stripped_root, &[]);
    connection
        .execute(
            "UPDATE entity_versions SET content_hash = ?1
             WHERE family_id = ?2 AND version_id = ?3",
            params![content_hash, fx.family_id, version_id],
        )
        .unwrap();
}

fn stored_care_plan_payload(fx: &CausalFx, version_id: &str) -> Map<String, Value> {
    let connection = fx.store.connect().unwrap();
    let payload: String = connection
        .query_row(
            "SELECT payload_json FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, version_id],
            |row| row.get(0),
        )
        .unwrap();
    serde_json::from_str(&payload).unwrap()
}

#[test]
fn causal_reload_accepted_care_plan_missing_source_key_hashes_stored_root() {
    let fx = CausalFx::new();
    let plan_id = Uuid::new_v4();
    let created = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "care_plan",
                plan_id,
                None,
                h39_root("care_plan", fx.baby_id, "household-equivalent", 20, None),
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(created.results[0].status, "accepted");
    let version = created.results[0].stable_version_id.clone().unwrap();
    strip_care_plan_source_key_and_rehash_stored_root(&fx, &version);

    let (entity_type, client_uuid, snapshot) = fx
        .store
        .load_validated_version_for_test(&fx.family_id, &version)
        .expect("因果 reload must accept household-equivalent 护理计划")
        .expect("accepted 护理计划 version exists");
    assert_eq!(entity_type, "care_plan");
    assert_eq!(client_uuid, plan_id.to_string());
    assert_eq!(
        snapshot.root.get("source_record_client_uuid"),
        Some(&Value::Null),
        "stable snapshot must still carry the projected source key"
    );
    assert!(
        !stored_care_plan_payload(&fx, &version).contains_key("source_record_client_uuid"),
        "因果 reload must not rewrite stored 护理计划 payload"
    );
}

#[test]
fn causal_commit_first_accepted_care_plan_missing_source_key() {
    let fx = CausalFx::new();
    let plan_id = Uuid::new_v4();
    let created = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "care_plan",
                plan_id,
                None,
                h39_root("care_plan", fx.baby_id, "household-equivalent", 20, None),
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(created.results[0].status, "accepted");
    let version = created.results[0].stable_version_id.clone().unwrap();
    strip_care_plan_source_key_and_rehash_stored_root(&fx, &version);

    let edit = mut_unit(
        "care_plan",
        plan_id,
        Some(&version),
        h39_root("care_plan", fx.baby_id, "adapted", 30, None),
        false,
    );
    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![edit], 1_700_000_001)
        .expect("commit-first must not fail as InvalidStoredPayload");
    assert_eq!(committed.results[0].status, "accepted");
    assert!(!committed.results[0].replay);
}

#[test]
fn causal_care_plan_client_brought_creator_stamp_is_ignored_and_re_stamped() {
    // Emit-set contract: new clients send plan mutations without the creator
    // stamp; 0.4.3 senders still send one. Both legs must be accepted, and the
    // persisted stable root must carry the authenticated principal's stamp.
    let fx = CausalFx::new();

    let mut stamped = h39_root("care_plan", fx.baby_id, "legacy stamped sender", 20, None);
    stamped.insert(
        "created_by_membership_id".to_owned(),
        Value::String("forged-creator".to_owned()),
    );
    let legacy = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit("care_plan", Uuid::new_v4(), None, stamped, false)],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(legacy.results[0].status, "accepted");
    let legacy_version = legacy.results[0].stable_version_id.clone().unwrap();
    assert_eq!(
        stored_care_plan_payload(&fx, &legacy_version).get("created_by_membership_id"),
        Some(&Value::String(fx.owner.membership_id.clone())),
        "client-forged creator stamp must be re-stamped to the principal"
    );

    let mut stamp_less = h39_root("care_plan", fx.baby_id, "current emit-set sender", 20, None);
    stamp_less.remove("created_by_membership_id");
    let current = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "care_plan",
                Uuid::new_v4(),
                None,
                stamp_less,
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(current.results[0].status, "accepted");
    let current_version = current.results[0].stable_version_id.clone().unwrap();
    assert_eq!(
        stored_care_plan_payload(&fx, &current_version).get("created_by_membership_id"),
        Some(&Value::String(fx.owner.membership_id.clone())),
        "stamp-less mutation must still land a server-stamped stable root"
    );
}

#[test]
fn causal_reload_published_care_plan_hashes_complete_source_key_root() {
    let fx = CausalFx::new();
    let plan_id = Uuid::new_v4();
    let created = fx
        .store
        .causal_commit(
            &fx.owner,
            vec![mut_unit(
                "care_plan",
                plan_id,
                None,
                h39_root("care_plan", fx.baby_id, "published", 20, None),
                false,
            )],
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(created.results[0].status, "accepted");
    let version = created.results[0].stable_version_id.clone().unwrap();
    assert_eq!(
        stored_care_plan_payload(&fx, &version).get("source_record_client_uuid"),
        Some(&Value::Null),
        "today's publish path stores the source key"
    );

    let (_, _, snapshot) = fx
        .store
        .load_validated_version_for_test(&fx.family_id, &version)
        .expect("published 护理计划 must still 因果 reload")
        .expect("accepted 护理计划 version exists");
    assert_eq!(
        snapshot.root.get("source_record_client_uuid"),
        Some(&Value::Null)
    );

    let edit = mut_unit(
        "care_plan",
        plan_id,
        Some(&version),
        h39_root("care_plan", fx.baby_id, "follow-up", 30, None),
        false,
    );
    let committed = fx
        .store
        .causal_commit(&fx.owner, vec![edit], 1_700_000_001)
        .expect("follow-up commit-first on a complete-root 护理计划");
    assert_eq!(committed.results[0].status, "accepted");

    let connection = fx.store.connect().unwrap();
    connection
        .execute(
            "UPDATE entity_versions
             SET payload_json = json_remove(payload_json, '$.source_record_client_uuid')
             WHERE family_id = ?1 AND version_id = ?2",
            params![fx.family_id, version],
        )
        .unwrap();
    drop(connection);
    assert!(
        matches!(
            fx.store
                .load_validated_version_for_test(&fx.family_id, &version),
            Err(StoreError::InvalidStoredPayload)
        ),
        "stripping the source key without rehash must fail closed"
    );
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
    assert_commit_rejected(
        fx.store.causal_commit(&fx.owner, vec![unit], 1_700_000_000),
        "unknown_field",
    );
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
        "source_record_client_uuid": null,
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
    assert_commit_rejected(
        fx.store.causal_commit(&fx.owner, units, 1_700_000_000),
        "invalid_domain",
    );
    assert_eq!(fx.store.pull(&fx.family_id, 0).unwrap().cursor, before);
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

    assert_commit_rejected(
        fx.store.causal_commit(&fx.owner, vec![unit], 1_700_000_000),
        "invalid_domain",
    );
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
    assert_commit_rejected(
        fx.store.causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                Uuid::new_v4(),
                None,
                dangling_custom,
                false,
            )],
            1_700_000_000,
        ),
        "invalid_domain",
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
    assert_commit_rejected(
        fx.store.causal_commit(
            &fx.owner,
            vec![mut_unit(
                "record",
                sleep_id,
                Some(&sleep_version),
                invalid_effective,
                false,
            )],
            1_700_000_002,
        ),
        "invalid_domain",
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
    assert_commit_rejected(
        fx.store.causal_commit(
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
        ),
        "invalid_domain",
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
        "source_record_client_uuid": null,
        "updated_at": 52,
    }));
    assert_commit_rejected(
        fx.store.causal_commit(
            &fx.owner,
            vec![mut_unit(
                "care_plan",
                Uuid::new_v4(),
                None,
                mismatched_plan,
                false,
            )],
            1_700_000_007,
        ),
        "invalid_domain",
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
    let cursor_before_replay = fx.store.pull(&fx.family_id, 0).unwrap().cursor;

    let replay = fx
        .store
        .causal_commit(&fx.owner, vec![wake.clone()], 1_700_000_003)
        .unwrap();
    assert_eq!(replay.results[0].status, "accepted", "{replay:?}");
    assert_eq!(replay.results[0].stable_version_id, wake_version);
    assert_eq!(
        fx.store.pull(&fx.family_id, 0).unwrap().cursor,
        cursor_before_replay,
    );

    let mut drift = wake;
    drift.root.insert("note".to_owned(), json!("不同内容"));
    assert_commit_rejected(
        fx.store
            .causal_commit(&fx.owner, vec![drift], 1_700_000_004),
        "content_drift",
    );
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

    assert_commit_rejected(
        fx.store.causal_commit(
            &fx.owner,
            vec![mut_unit("record", record_id, None, root, false)],
            1_700_000_000,
        ),
        "invalid_domain",
    );
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
    assert_commit_rejected(
        fx.store.causal_commit(&fx.owner, vec![unit], 1_700_000_000),
        "missing_media_bytes",
    );
}

#[test]
fn causal_media_manifest_claim_is_all_or_none_on_a_wrong_receipt() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut first = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: String::new(),
        byte_size: 10,
        mime: "image/jpeg".into(),
        width: None,
        height: None,
    };
    let mut second = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        byte_size: 11,
        ..first.clone()
    };
    fx.stage_media_bytes(&mut first);
    fx.stage_media_bytes(&mut second);

    let mut wrong_second = second.clone();
    wrong_second.sha256 = "f".repeat(64);
    let mut unit = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "two-receipts", 100, 20),
        false,
    );
    unit.media = vec![first.clone(), wrong_second];
    assert_commit_rejected(
        fx.store.causal_commit(&fx.owner, vec![unit], 1_700_000_000),
        "media_sha256_mismatch",
    );

    let bytes = vec![0_u8; first.byte_size as usize];
    let incoming = NamedTempFile::new().unwrap();
    fs::write(incoming.path(), &bytes).unwrap();
    let verified = VerifiedCausalMediaPreimage::verify(
        incoming.path().to_owned(),
        &first.sha256,
        DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS.max_file_bytes,
    )
    .unwrap();
    let replayed_prepare = fx
        .store
        .stage_verified_causal_media_preimage(
            &fx.owner,
            &first.media_uuid,
            &verified,
            1_700_000_001,
            DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
        )
        .unwrap();
    assert_eq!(replayed_prepare.status, "staged");
}

#[test]
fn causal_media_receipt_length_and_expiry_equality_reject_before_any_store_write() {
    for case in ["wrong_byte_size", "expiry_equality"] {
        let fx = CausalFx::new();
        let record_id = Uuid::new_v4();
        let mut media = CausalMediaItem {
            media_uuid: Uuid::new_v4().to_string(),
            role: "log".into(),
            sha256: String::new(),
            byte_size: 10,
            mime: "image/jpeg".into(),
            width: None,
            height: None,
        };
        fx.stage_media_bytes(&mut media);
        let connection = fx.store.connect().unwrap();
        let expires_at: i64 = connection
            .query_row(
                "SELECT expires_at FROM causal_media_staging
                 WHERE family_id = ?1 AND media_uuid = ?2",
                params![fx.family_id, media.media_uuid],
                |row| row.get(0),
            )
            .unwrap();
        let mutation_id = Uuid::new_v4().to_string();
        let before: (i64, i64, i64, i64, i64, String) = connection
            .query_row(
                "SELECT
                    (SELECT rev FROM family_meta WHERE family_id = ?1),
                    (SELECT COUNT(*) FROM entity_versions
                      WHERE family_id = ?1 AND mutation_id = ?2),
                    (SELECT COUNT(*) FROM mutation_receipts
                      WHERE family_id = ?1 AND mutation_id = ?2),
                    (SELECT COUNT(*) FROM entities
                      WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?3),
                    (SELECT COUNT(*) FROM media_publications
                      WHERE family_id = ?1 AND media_uuid = ?4),
                    (SELECT status FROM causal_media_staging
                      WHERE family_id = ?1 AND media_uuid = ?4)",
                params![
                    fx.family_id,
                    mutation_id,
                    record_id.to_string(),
                    media.media_uuid,
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
        drop(connection);

        let mut declared = media.clone();
        let now = match case {
            "wrong_byte_size" => {
                declared.byte_size += 1;
                1_700_000_001
            }
            "expiry_equality" => expires_at,
            _ => unreachable!(),
        };
        let mut mutation = fx.record_mutation(record_id, None, case);
        mutation.mutation_id = mutation_id.clone();
        mutation.media = vec![declared];
        let expected_code = match case {
            "wrong_byte_size" => "media_byte_size_mismatch",
            "expiry_equality" => "media_preimage_expired",
            _ => unreachable!(),
        };
        assert_commit_rejected(
            fx.store
                .causal_commit(&fx.owner, vec![mutation.clone()], now),
            expected_code,
        );

        let connection = fx.store.connect().unwrap();
        let after: (i64, i64, i64, i64, i64, String) = connection
            .query_row(
                "SELECT
                    (SELECT rev FROM family_meta WHERE family_id = ?1),
                    (SELECT COUNT(*) FROM entity_versions
                      WHERE family_id = ?1 AND mutation_id = ?2),
                    (SELECT COUNT(*) FROM mutation_receipts
                      WHERE family_id = ?1 AND mutation_id = ?2),
                    (SELECT COUNT(*) FROM entities
                      WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?3),
                    (SELECT COUNT(*) FROM media_publications
                      WHERE family_id = ?1 AND media_uuid = ?4),
                    (SELECT status FROM causal_media_staging
                      WHERE family_id = ?1 AND media_uuid = ?4)",
                params![
                    fx.family_id,
                    mutation_id,
                    record_id.to_string(),
                    media.media_uuid,
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
        assert_eq!(after, before, "{case}");

        if case == "wrong_byte_size" {
            mutation.media = vec![media];
            let accepted = fx
                .store
                .causal_commit(&fx.owner, vec![mutation], now + 1)
                .unwrap();
            assert_eq!(accepted.results[0].status, "accepted");
        }
    }
}

#[test]
fn causal_media_corrupt_stored_receipt_is_a_retryable_store_failure_with_zero_writes() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut media = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: String::new(),
        byte_size: 10,
        mime: "image/jpeg".into(),
        width: None,
        height: None,
    };
    fx.stage_media_bytes(&mut media);
    let connection = fx.store.connect().unwrap();
    connection
        .execute_batch("PRAGMA ignore_check_constraints = ON")
        .unwrap();
    connection
        .execute(
            "UPDATE causal_media_staging SET status = 'corrupt'
              WHERE family_id = ?1 AND media_uuid = ?2",
            params![fx.family_id, media.media_uuid],
        )
        .unwrap();
    let before = connection
        .query_row(
            "SELECT
                (SELECT rev FROM family_meta WHERE family_id = ?1),
                (SELECT COUNT(*) FROM entity_versions WHERE family_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts WHERE family_id = ?1)",
            params![fx.family_id],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                ))
            },
        )
        .unwrap();
    drop(connection);

    let mut unit = mut_unit(
        "record",
        record_id,
        None,
        record_root(fx.baby_id, "corrupt-receipt", 100, 20),
        false,
    );
    unit.media = vec![media];
    let error = fx
        .store
        .causal_commit(&fx.owner, vec![unit], 1_700_000_001)
        .unwrap_err();
    assert!(matches!(error, StoreError::InvalidCausalMediaStaging));

    let connection = fx.store.connect().unwrap();
    let after = connection
        .query_row(
            "SELECT
                (SELECT rev FROM family_meta WHERE family_id = ?1),
                (SELECT COUNT(*) FROM entity_versions WHERE family_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts WHERE family_id = ?1)",
            params![fx.family_id],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                ))
            },
        )
        .unwrap();
    assert_eq!(after, before);
}

#[test]
fn causal_media_corrupt_stored_entity_is_a_store_failure_with_zero_terminal_writes() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let mut media = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: String::new(),
        byte_size: 10,
        mime: "image/jpeg".into(),
        width: None,
        height: None,
    };
    fx.stage_media_bytes(&mut media);
    let mut created = fx.record_mutation(record_id, None, "media-base");
    created.media = vec![media.clone()];
    let stable_version = fx
        .commit(&fx.owner, created, 1_700_000_001)
        .unwrap()
        .results[0]
        .stable_version_id
        .clone()
        .unwrap();
    let connection = fx.store.connect().unwrap();
    connection
        .execute(
            "UPDATE entities SET payload_json = '{'
              WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
            params![fx.family_id, media.media_uuid],
        )
        .unwrap();
    let before = connection
        .query_row(
            "SELECT
                (SELECT rev FROM family_meta WHERE family_id = ?1),
                (SELECT COUNT(*) FROM entity_versions WHERE family_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts WHERE family_id = ?1)",
            params![fx.family_id],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                ))
            },
        )
        .unwrap();
    drop(connection);

    let mut connection = fx.store.connect().unwrap();
    let transaction = connection.transaction().unwrap();
    let claim_error = claim_manifest(
        &transaction,
        &fx.owner,
        std::slice::from_ref(&media),
        1_700_000_002,
    )
    .unwrap_err();
    assert!(matches!(
        claim_error,
        CausalMediaReceiptClaimError::Store(StoreError::InvalidStoredPayload)
    ));
    transaction.rollback().unwrap();

    let mut update = fx.record_mutation(record_id, Some(&stable_version), "media-update");
    update.media = vec![media];
    let error = fx.commit(&fx.owner, update, 1_700_000_002).unwrap_err();
    assert!(matches!(error, StoreError::InvalidStoredPayload));
    let connection = fx.store.connect().unwrap();
    let after = connection
        .query_row(
            "SELECT
                (SELECT rev FROM family_meta WHERE family_id = ?1),
                (SELECT COUNT(*) FROM entity_versions WHERE family_id = ?1),
                (SELECT COUNT(*) FROM mutation_receipts WHERE family_id = ?1)",
            params![fx.family_id],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, i64>(2)?,
                ))
            },
        )
        .unwrap();
    assert_eq!(after, before);
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
fn pull_groups_wake_media_with_wake_root_and_advances_cursor() {
    let fx = CausalFx::new();
    let sleep_id = Uuid::new_v4();
    let accepted_sleep = fx
        .commit(
            &fx.owner,
            mut_unit(
                "record",
                sleep_id,
                None,
                sleep_root(fx.baby_id, 100, 20),
                false,
            ),
            1_700_000_000,
        )
        .unwrap();
    assert_eq!(accepted_sleep.results[0].status, "accepted");

    let wake_id = Uuid::new_v4();
    let mut wake_photo = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "wake".into(),
        sha256: "a".repeat(64),
        byte_size: 12,
        mime: "image/jpeg".into(),
        width: Some(2),
        height: Some(3),
    };
    fx.stage_media_bytes(&mut wake_photo);
    let mut create = mut_unit(
        "wake_observation",
        wake_id,
        None,
        wake_observation_root(sleep_id, 200, 30),
        false,
    );
    create.media = vec![wake_photo.clone()];
    let committed = fx.commit(&fx.owner, create, 1_700_000_001).unwrap();
    assert_eq!(committed.results[0].status, "accepted");

    let tip = fx.store.current_revision(&fx.family_id).unwrap();
    let page = fx.store.pull(&fx.family_id, 0).unwrap();
    let keys = page
        .entities
        .iter()
        .map(|entity| (entity.entity_type.clone(), entity.client_uuid.clone()))
        .collect::<BTreeSet<_>>();
    let wake_media = page
        .entities
        .iter()
        .find(|entity| entity.entity_type == "media" && entity.client_uuid == wake_photo.media_uuid)
        .expect("wake media must travel with the wake root");

    assert!(keys.contains(&("baby".to_owned(), fx.baby_id.to_string())));
    assert!(keys.contains(&("record".to_owned(), sleep_id.to_string())));
    assert!(keys.contains(&("wake_observation".to_owned(), wake_id.to_string())));
    assert!(keys.contains(&("media".to_owned(), wake_photo.media_uuid.clone())));
    assert_eq!(
        wake_media.payload.get("kind").and_then(Value::as_str),
        Some("wake")
    );
    assert_eq!(
        wake_media
            .payload
            .get("record_client_uuid")
            .and_then(Value::as_str),
        Some(wake_id.to_string().as_str())
    );
    assert!(!page.has_more);
    assert_eq!(page.cursor, tip);
}

#[test]
fn legacy_bundle_rejects_mutable_root_without_or_with_causal_head() {
    let fx = CausalFx::new();
    let record_id = Uuid::new_v4();
    let legacy_record = || {
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
        )
    };
    let fresh_err = fx
        .store
        .stage_bundle(
            &fx.owner,
            &Uuid::new_v4().to_string(),
            legacy_record(),
            vec![],
            100,
        )
        .unwrap_err();
    assert!(matches!(
        fresh_err,
        StoreError::LegacyBundleCausalRootUnsupported(ref entity_type)
            if entity_type == "record"
    ));

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

    let err = fx
        .store
        .stage_bundle(
            &fx.owner,
            &Uuid::new_v4().to_string(),
            legacy_record(),
            vec![],
            100,
        )
        .unwrap_err();
    assert!(matches!(
        err,
        StoreError::LegacyBundleCausalRootUnsupported(ref entity_type)
            if entity_type == "record"
    ));
}

/// Historical rows are inserted directly so fixture construction is excluded
/// from measured commits. Each media item has a distinct owner/head; tombstones
/// are excluded from that owner's current manifest. No family/NAS data is used.
fn seed_us038_media_history(fx: &CausalFx, count: usize) {
    let mut connection = fx.store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    let mut rev: i64 = tx
        .query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![fx.family_id],
            |r| r.get(0),
        )
        .unwrap();
    {
        let mut entity = tx
            .prepare("INSERT INTO entities VALUES (?1,?2,?3,40,?4,?5,?6)")
            .unwrap();
        let mut version = tx.prepare(
            "INSERT INTO entity_versions VALUES (?1,?2,'record',?3,40,NULL,?4,?5,?6,'accepted',1700000000)",
        ).unwrap();
        let mut head = tx
            .prepare("INSERT INTO entity_stable_heads VALUES (?1,'record',?2,?3)")
            .unwrap();
        let mut media = tx
            .prepare("INSERT INTO entity_version_media VALUES (?1,?2,?3,?4,?5)")
            .unwrap();
        let mut publication = tx
            .prepare("INSERT INTO media_publications VALUES (?1,?2,'ordinary',NULL)")
            .unwrap();
        for i in 0..count {
            let root_id =
                Uuid::from_u128(0x10000000000040008000000000000000 + i as u128).to_string();
            let media_id =
                Uuid::from_u128(0x20000000000040008000000000000000 + i as u128).to_string();
            let version_id =
                Uuid::from_u128(0x30000000000040008000000000000000 + i as u128).to_string();
            let mutation_id =
                Uuid::from_u128(0x40000000000040008000000000000000 + i as u128).to_string();
            let deleted = (i % 3 == 0).then_some(41_i64);
            let item = CausalMediaItem {
                media_uuid: media_id.clone(),
                role: "log".into(),
                sha256: hex::encode(Sha256::digest([0_u8])),
                byte_size: 1,
                mime: "image/jpeg".into(),
                width: Some(1),
                height: Some(1),
            };
            let mut root = record_root(fx.baby_id, "history", 10, 40);
            root.insert(
                "created_by_membership_id".into(),
                json!(fx.owner.membership_id),
            );
            let manifest = if deleted.is_some() {
                vec![]
            } else {
                vec![item.clone()]
            };
            let hash = mutation_content_hash("_", "_", None, false, &root, &manifest);
            let root_json = serde_json::to_string(&root).unwrap();
            root.remove("updated_at");
            rev += 1;
            entity
                .execute(params![
                    fx.family_id,
                    "record",
                    root_id,
                    None::<i64>,
                    serde_json::to_string(&root).unwrap(),
                    rev
                ])
                .unwrap();
            version
                .execute(params![
                    fx.family_id,
                    version_id,
                    root_id,
                    root_json,
                    hash,
                    mutation_id
                ])
                .unwrap();
            head.execute(params![fx.family_id, root_id, version_id])
                .unwrap();
            let payload = json!({"kind":"log", "record_client_uuid":root_id,
                "baby_client_uuid":null,"care_plan_client_uuid":null,"mime":"image/jpeg",
                "width":1,"height":1,"byte_size":1});
            rev += 1;
            entity
                .execute(params![
                    fx.family_id,
                    "media",
                    media_id,
                    deleted,
                    payload.to_string(),
                    rev
                ])
                .unwrap();
            if deleted.is_none() {
                let media_payload = item.to_value();
                let media_hash = mutation_content_hash(
                    "media",
                    &media_id,
                    None,
                    false,
                    media_payload.as_object().unwrap(),
                    &[],
                );
                media
                    .execute(params![
                        fx.family_id,
                        version_id,
                        media_id,
                        media_payload.to_string(),
                        media_hash
                    ])
                    .unwrap();
                publication
                    .execute(params![fx.family_id, media_id])
                    .unwrap();
            }
        }
    }
    tx.execute(
        "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
        params![rev, fx.family_id],
    )
    .unwrap();
    tx.commit().unwrap();
}

fn us038_small_edit_work(history: usize) -> u64 {
    let fx = CausalFx::new();
    let root = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.owner,
            fx.record_mutation(root, None, "before"),
            1_700_000_000,
        )
        .unwrap();
    let base = created.results[0].stable_version_id.as_deref().unwrap();
    seed_us038_media_history(&fx, history);
    let (result, steps) = with_sql_work_probe(|| {
        fx.commit(
            &fx.owner,
            fx.record_mutation(root, Some(base), "after"),
            1_700_000_001,
        )
    });
    assert_eq!(result.unwrap().results[0].status, "accepted");
    steps
}

#[test]
fn causal_small_edit_sql_work_does_not_scan_unrelated_media() {
    let small = us038_small_edit_work(100);
    let large = us038_small_edit_work(10_000);
    eprintln!("US038 full commit SQLite VM instructions: 100={small}, 10000={large}");
    assert!(
        large <= small + 100,
        "unrelated media history grew SQL work: {small} -> {large}"
    );
}

#[test]
#[ignore = "100k synthetic history timing/work evidence; run explicitly"]
fn causal_us038_hundred_thousand_media_work_evidence() {
    let small = us038_small_edit_work(100);
    let large = us038_small_edit_work(100_000);
    eprintln!("US038 full commit SQLite VM instructions: 100={small}, 100000={large}");
    assert!(
        large <= small + 100,
        "unrelated media history grew SQL work: {small} -> {large}"
    );
}

#[test]
fn causal_media_scope_rejects_cross_root_reuse_even_after_tombstone() {
    let fx = CausalFx::new();
    let first = Uuid::new_v4();
    let second = Uuid::new_v4();
    let mut item = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "a".repeat(64),
        byte_size: 12,
        mime: "image/jpeg".into(),
        width: Some(2),
        height: Some(3),
    };
    fx.stage_media_bytes(&mut item);
    let mut create = fx.record_mutation(first, None, "first");
    create.media = vec![item.clone()];
    let created = fx.commit(&fx.owner, create, 1_700_000_000).unwrap();
    let base = created.results[0].stable_version_id.as_deref().unwrap();
    let mut reuse = fx.record_mutation(second, None, "second");
    reuse.media = vec![item];
    assert_commit_rejected(
        fx.commit(&fx.owner, reuse.clone(), 1_700_000_001),
        "invalid_domain",
    );
    fx.commit(
        &fx.owner,
        fx.record_mutation(first, Some(base), "removed"),
        1_700_000_002,
    )
    .unwrap();
    reuse.mutation_id = Uuid::new_v4().to_string();
    assert_commit_rejected(fx.commit(&fx.owner, reuse, 1_700_000_003), "invalid_domain");
}

#[test]
fn causal_scoped_media_reads_preserve_legacy_headless_fallback() {
    let fx = CausalFx::new();
    let root = Uuid::new_v4();
    let mut item = CausalMediaItem {
        media_uuid: Uuid::new_v4().to_string(),
        role: "log".into(),
        sha256: "a".repeat(64),
        byte_size: 12,
        mime: "image/jpeg".into(),
        width: Some(2),
        height: Some(3),
    };
    fx.stage_media_bytes(&mut item);
    let mut create = fx.record_mutation(root, None, "legacy");
    create.media = vec![item.clone()];
    fx.commit(&fx.owner, create, 1_700_000_000).unwrap();
    let mut connection = fx.store.connect().unwrap();
    let tx = connection.transaction().unwrap();
    tx.execute("DELETE FROM entity_stable_heads WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2", params![fx.family_id, root.to_string()]).unwrap();
    tx.execute(
        "DELETE FROM mutation_receipts WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2",
        params![fx.family_id, root.to_string()],
    ).unwrap();
    tx.execute(
        "DELETE FROM entity_versions WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2",
        params![fx.family_id, root.to_string()],
    ).unwrap();
    let ids = super::super::media_associations::live_ids_for_root(
        &tx,
        &fx.family_id,
        "record",
        &root.to_string(),
    )
    .unwrap();
    assert_eq!(ids, BTreeSet::from([item.media_uuid]));
    tx.commit().unwrap();
    fx.store
        .validate_authority_graph(1024, |family, media, size| {
            Ok(fs::read(fx._dir.path().join("media").join(family).join(media))?.len() == size)
        })
        .unwrap();
    let before = resolution_durable_state(&fx.store, &fx.family_id, "", root);
    // Compatibility reads keep this legitimate historical graph. A current
    // causal edit cannot fabricate its missing base or enter the linear helper.
    assert!(matches!(
        fx.commit(
            &fx.owner,
            fx.record_mutation(root, None, "must stay historical"),
            1_700_000_001
        ),
        Err(StoreError::InvalidStoredPayload)
    ));
    assert_eq!(
        resolution_durable_state(&fx.store, &fx.family_id, "", root),
        before
    );
}

/// Release-only local timing evidence. Uses the production heartbeat Store core
/// behind the same per-family mutual-exclusion discipline, not HTTP/TLS, a NAS,
/// a physical Android device, or a measurement of cold startup validation.
#[test]
#[ignore = "release timing evidence; LEZI_US038_HISTORY selects isolated size"]
fn causal_us038_small_edit_timing_evidence() {
    use std::time::Instant;
    fn rss_kib() -> u64 {
        fs::read_to_string("/proc/self/status")
            .unwrap()
            .lines()
            .find_map(|line| line.strip_prefix("VmRSS:"))
            .unwrap()
            .split_whitespace()
            .next()
            .unwrap()
            .parse()
            .unwrap()
    }
    fn percentiles(mut values: Vec<f64>) -> (f64, f64) {
        values.sort_by(f64::total_cmp);
        (
            values[values.len() / 2],
            values[(values.len() * 95 / 100).min(values.len() - 1)],
        )
    }
    let history: usize = std::env::var("LEZI_US038_HISTORY")
        .unwrap_or_else(|_| "100".into())
        .parse()
        .unwrap();
    assert!([100, 10_000, 100_000].contains(&history));
    let fx = CausalFx::new();
    let root = Uuid::new_v4();
    let created = fx
        .commit(
            &fx.owner,
            fx.record_mutation(root, None, "before"),
            1_700_000_000,
        )
        .unwrap();
    let mut base = created.results[0].stable_version_id.clone().unwrap();
    seed_us038_media_history(&fx, history);
    let family_lock = Arc::new(std::sync::Mutex::new(()));
    let mut edits = Vec::new();
    let mut heartbeats = Vec::new();
    let initial_rss = rss_kib();
    let mut observed_rss = initial_rss;
    for i in 0..35 {
        let guard = family_lock.lock().unwrap();
        let lock = family_lock.clone();
        let store = fx.store.clone();
        let family_id = fx.family_id.clone();
        let barrier = Arc::new(Barrier::new(2));
        let probe_barrier = barrier.clone();
        let heartbeat = thread::spawn(move || {
            probe_barrier.wait();
            let start = Instant::now();
            let _guard = lock.lock().unwrap();
            store.head_and_directory_generation(&family_id).unwrap();
            start.elapsed().as_secs_f64() * 1000.0
        });
        barrier.wait();
        let start = Instant::now();
        let result = fx
            .commit(
                &fx.owner,
                fx.record_mutation(root, Some(&base), &format!("edit-{i}")),
                1_700_000_001 + i,
            )
            .unwrap();
        let elapsed = start.elapsed().as_secs_f64() * 1000.0;
        base = result.results[0].stable_version_id.clone().unwrap();
        drop(guard);
        let heartbeat_ms = heartbeat.join().unwrap();
        observed_rss = observed_rss.max(rss_kib());
        if i >= 5 {
            edits.push(elapsed);
            heartbeats.push(heartbeat_ms);
        }
    }
    let (edit_p50, edit_p95) = percentiles(edits);
    let (heartbeat_p50, heartbeat_p95) = percentiles(heartbeats);
    eprintln!(
        "US038_TIMING {}",
        json!({
            "history_media":history,"samples":30,"edit_p50_ms":edit_p50,"edit_p95_ms":edit_p95,
            "heartbeat_core_p50_ms":heartbeat_p50,"heartbeat_core_p95_ms":heartbeat_p95,
            "process_rss_before_kib":initial_rss,"process_rss_post_operation_max_kib":observed_rss,
            "debug_assertions":cfg!(debug_assertions),
            "scope":"Store commit plus production heartbeat core and family mutex; metadata-only unrelated history; cold startup and HTTP excluded"
        })
    );
}

#[test]
fn causal_scoped_media_validates_every_manifest_projection() {
    for corrupt_owner in [false, true] {
        let fx = CausalFx::new();
        let root = Uuid::new_v4();
        let mut item = CausalMediaItem {
            media_uuid: Uuid::new_v4().to_string(),
            role: "log".into(),
            sha256: "a".repeat(64),
            byte_size: 12,
            mime: "image/jpeg".into(),
            width: Some(2),
            height: Some(3),
        };
        fx.stage_media_bytes(&mut item);
        let mut create = fx.record_mutation(root, None, "photo");
        create.media = vec![item.clone()];
        let created = fx.commit(&fx.owner, create, 1_700_000_000).unwrap();
        let base = created.results[0].stable_version_id.as_deref().unwrap();
        let connection = fx.store.connect().unwrap();
        if corrupt_owner {
            connection.execute(
                "UPDATE entities SET payload_json = json_set(payload_json, '$.record_client_uuid', ?1)
                 WHERE family_id = ?2 AND entity_type = 'media' AND client_uuid = ?3",
                params![Uuid::new_v4().to_string(), fx.family_id, item.media_uuid],
            ).unwrap();
        } else {
            connection.execute(
                "UPDATE entities SET deleted_at = 1 WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
                params![fx.family_id, item.media_uuid],
            ).unwrap();
        }
        let before = resolution_durable_state(&fx.store, &fx.family_id, "", root);
        let result = fx.commit(
            &fx.owner,
            fx.record_mutation(root, Some(base), "attempt"),
            1_700_000_001,
        );
        assert!(result.is_err());
        assert_eq!(
            resolution_durable_state(&fx.store, &fx.family_id, "", root),
            before
        );
    }
}

#[test]
fn causal_photo_edit_sql_work_does_not_scan_unrelated_media() {
    let measure = |history| {
        let fx = CausalFx::new();
        let root = Uuid::new_v4();
        let mut item = CausalMediaItem {
            media_uuid: Uuid::new_v4().to_string(),
            role: "log".into(),
            sha256: "a".repeat(64),
            byte_size: 12,
            mime: "image/jpeg".into(),
            width: Some(2),
            height: Some(3),
        };
        fx.stage_media_bytes(&mut item);
        let mut create = fx.record_mutation(root, None, "before");
        create.media = vec![item.clone()];
        let created = fx.commit(&fx.owner, create, 1_700_000_000).unwrap();
        let base = created.results[0].stable_version_id.as_deref().unwrap();
        seed_us038_media_history(&fx, history);
        let mut edit = fx.record_mutation(root, Some(base), "after");
        edit.media = vec![item];
        let (result, steps) = with_sql_work_probe(|| fx.commit(&fx.owner, edit, 1_700_000_001));
        assert_eq!(result.unwrap().results[0].status, "accepted");
        steps
    };
    let small = measure(100);
    let large = measure(10_000);
    eprintln!("US038 photo commit SQLite VM instructions: 100={small}, 10000={large}");
    assert!(
        large <= small + 100,
        "unrelated media history grew SQL work: {small} -> {large}"
    );
}
