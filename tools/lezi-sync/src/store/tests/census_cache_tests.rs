//! W1 golden tests for the per-(family, head) live-census cache (ticket 02).
//!
//! Contract under test (0.5 design §1.1, review A1/A4/B7):
//! - a cache hit is byte-identical to a full recompute at the same head;
//! - every `family_meta` rev increment invalidates the entry through the
//!   single `advance_rev` funnel (the staging publish and source-relation
//!   batch bumps delegate to it);
//! - every absolute (non-increment) rev write invalidates unconditionally;
//! - non-rev writes neither invalidate nor change the golden census;
//! - read-only stores never install; restarts rebuild lazily exactly once;
//!   families are isolated.
//!
//! Exhaustiveness evidence for the "single funnel" claim: a repo-wide grep
//! for `family_meta` increment writes finds `causal::advance_rev` (+1) and
//! `causal::advance_rev_by` (+N); source-relation member bumps and the
//! deleted staging `advance_family_rev` both delegate to that funnel — and
//! exactly four absolute-write sites (restore,
//! bundle import, membership anonymization, offline_migrate). Session/login/
//! membership-admin methods other than the ones exercised below write no
//! `family_meta` rows at all (grep-verified), so they cannot affect the
//! cache. `offline_migrate` is an offline-cutover tool operating on raw
//! connections with no live `Store` in the same process, so there is no
//! in-process cache entry to invalidate; the next `Store` that opens the
//! database starts with a fresh cache.

use std::collections::BTreeMap;

use rusqlite::Connection;
use serde_json::{json, Map, Value};
use tempfile::TempDir;
use uuid::Uuid;

use super::super::conflict_snapshots::ConflictOutcome;
use super::super::live_census_cache::{CensusLookup, LiveCensusCache};
use super::super::*;
use super::test_support::*;
use crate::model::Entity;
use std::sync::Arc;

fn map(value: Value) -> Map<String, Value> {
    value.as_object().unwrap().clone()
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

#[allow(clippy::too_many_arguments)]
fn commit_unit(
    store: &Store,
    principal: &Principal,
    entity_type: &str,
    client_uuid: Uuid,
    base: Option<&str>,
    root: Map<String, Value>,
    deleted: bool,
    now: i64,
) -> CausalUnitResult {
    let mut result = store
        .causal_commit(
            principal,
            vec![mut_unit(entity_type, client_uuid, base, root, deleted)],
            now,
        )
        .unwrap();
    assert_ne!(
        result.results[0].status, "rejected",
        "{:?}",
        result.results[0]
    );
    result.results.swap_remove(0)
}

/// The golden assertion: a served census — whether from a fresh install or a
/// cache hit — equals a full recompute taken directly from SQLite, and the
/// cache entry sits exactly at `family_meta.rev`.
fn assert_cache_matches_full_recompute(store: &Store, family_id: &str) {
    let head = store.current_revision(family_id).unwrap();
    let golden = {
        let connection = store.connect().unwrap();
        pull::compute_live_census(&connection, family_id).unwrap()
    };
    // First pull may rebuild and install; second must be a hit. Both carry
    // the golden census.
    let first = store.pull_with_census(family_id, head).unwrap();
    assert_eq!(first.live_census.as_ref().unwrap(), &golden);
    assert_eq!(
        store.test_cached_census_head_rev(family_id),
        Some(head),
        "live entry must be installed at family_meta.rev"
    );
    let second = store.pull_with_census(family_id, head).unwrap();
    assert_eq!(second.live_census.as_ref().unwrap(), &golden);
    // Byte-identical: hit vs rebuild vs full recompute (wire §1.4 invariant).
    let rebuild_bytes = serde_json::to_vec(first.live_census.as_ref().unwrap()).unwrap();
    let hit_bytes = serde_json::to_vec(second.live_census.as_ref().unwrap()).unwrap();
    let golden_bytes = serde_json::to_vec(&golden).unwrap();
    assert_eq!(rebuild_bytes, golden_bytes);
    assert_eq!(hit_bytes, golden_bytes);
}

/// Creates a distinct family — `test_support::family` replays on a fixed
/// create_request_id, so tests needing two families must use their own ids.
fn new_family(store: &Store, create_request_id: &str) -> String {
    store
        .create_family(
            CreateFamilyInput {
                now: 1,
                create_request_id,
                display_name: "妈妈",
                display_name_key: "妈妈",
                family_name: "测试家庭",
                device_name: "owner",
                owner_root_fingerprint: None,
            },
            |_, _, _| ("owner-access".to_owned(), "owner-refresh".to_owned()),
        )
        .unwrap()
        .family_id
}

/// Commits the family's live baby; record commits require it (invalid_domain
/// otherwise because their baby_client_uuid must reference a live baby).
fn seed_baby(store: &Store, principal: &Principal) -> Uuid {
    let baby_id = Uuid::new_v4();
    commit_unit(
        store,
        principal,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    baby_id
}

/// Installs a cache entry at the current head and returns the head.
fn install_entry_at_head(store: &Store, family_id: &str) -> i64 {
    let head = store.current_revision(family_id).unwrap();
    let page = store.pull_with_census(family_id, head).unwrap();
    assert!(page.live_census.is_some());
    assert_eq!(store.test_cached_census_head_rev(family_id), Some(head));
    head
}

#[test]
fn cache_hit_serves_bytes_identical_to_full_recompute() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    commit_unit(
        &store,
        &owner,
        "record",
        Uuid::new_v4(),
        None,
        record_root(baby_id, "first", 10, 2),
        false,
        1_700_000_001,
    );
    assert_cache_matches_full_recompute(&store, &family_id);
}

#[test]
fn causal_commit_accepted_invalidates_through_the_funnel() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = new_family(&store, "census-accept-family");
    let owner = owner_principal(&family_id);
    let baby_id = seed_baby(&store, &owner);
    install_entry_at_head(&store, &family_id);

    commit_unit(
        &store,
        &owner,
        "record",
        Uuid::new_v4(),
        None,
        record_root(baby_id, "first", 10, 2),
        false,
        1_700_000_001,
    );
    assert_eq!(store.test_cached_census_head_rev(&family_id), None);
    assert_cache_matches_full_recompute(&store, &family_id);
}

#[test]
fn causal_commit_with_media_invalidates_for_each_projected_media_rev() {
    // project_stable_media advances the family rev once per media row.
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    install_entry_at_head(&store, &family_id);

    let media_uuid = Uuid::new_v4();
    let item = stage_log_media(&store, &owner, media_uuid, b"media");
    let root = map(json!({
        "baby_client_uuid": baby_id,
        "type": "formula",
        "custom_item_client_uuid": null,
        "timestamp": 100,
        "end_timestamp": null,
        "note": "with media",
        "payload_json": {"amount_ml": 10},
        "schema_version": 2,
        "updated_at": 2,
    }));
    let result = store
        .causal_commit(
            &owner,
            vec![CausalMutation {
                mutation_id: Uuid::new_v4().to_string(),
                base_version: None,
                entity_type: "record".to_owned(),
                client_uuid: Uuid::new_v4().to_string(),
                root,
                media: vec![item],
                deleted: false,
            }],
            1_700_000_001,
        )
        .unwrap();
    assert_eq!(result.results[0].status, "accepted");
    assert_eq!(store.test_cached_census_head_rev(&family_id), None);
    assert_cache_matches_full_recompute(&store, &family_id);
}

/// Seeds the proven branched-conflict shape (base → stable edit → same-base
/// live edit) and returns the conflicting pieces.
struct BranchedConflict {
    store: Store,
    _directory: TempDir,
    family_id: String,
    owner: Principal,
    record_id: Uuid,
    stable_version_id: String,
    conflict_id: String,
    branch_version_id: String,
}

fn seed_branched_conflict() -> BranchedConflict {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let record_id = Uuid::new_v4();
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    let base_version_id = commit_unit(
        &store,
        &owner,
        "record",
        record_id,
        None,
        record_root(baby_id, "base", 100, 40),
        false,
        1_700_000_001,
    )
    .stable_version_id
    .unwrap();
    let stable_version_id = commit_unit(
        &store,
        &owner,
        "record",
        record_id,
        Some(&base_version_id),
        record_root(baby_id, "stable", 100, 41),
        false,
        1_700_000_002,
    )
    .stable_version_id
    .unwrap();
    let branched = commit_unit(
        &store,
        &owner,
        "record",
        record_id,
        Some(&base_version_id),
        record_root(baby_id, "branch-0", 100, 42),
        false,
        1_700_000_003,
    );
    assert_eq!(branched.status, "branched", "{branched:?}");
    BranchedConflict {
        store,
        _directory: directory,
        family_id,
        owner,
        record_id,
        stable_version_id,
        conflict_id: branched.conflict_id.unwrap(),
        branch_version_id: branched.branch_version_id.unwrap(),
    }
}

fn note_set_choice(detail: &ConflictDetailPage) -> ConflictResolutionChoice {
    let candidate = detail
        .conflicting
        .iter()
        .find(|item| item.path == "/note")
        .and_then(|item| {
            item.candidates.iter().find(|candidate| {
                candidate.outcome
                    == ConflictOutcome::Set {
                        value: Value::String("branch-0".to_owned()),
                    }
            })
        })
        .expect("missing /note set-to-branch-0 candidate");
    ConflictResolutionChoice {
        path: "/note".to_owned(),
        choice_id: candidate.choice_id.clone(),
    }
}

#[test]
fn resolve_conflict_invalidates() {
    let fx = seed_branched_conflict();
    install_entry_at_head(&fx.store, &fx.family_id);

    let detail = fx
        .store
        .conflict_detail_page(
            &fx.owner,
            &fx.conflict_id,
            ConflictDetailPageRequest::First,
            1_700_000_100,
        )
        .unwrap();
    let input = ResolveConflictInput {
        snapshot_token: detail.snapshot_token.clone(),
        resolution_mutation_id: Uuid::new_v4().to_string(),
        choices: vec![note_set_choice(&detail)],
    };
    let resolved = fx
        .store
        .resolve_conflict(&fx.owner, &fx.conflict_id, input, 1_700_000_101)
        .unwrap();
    assert_eq!(resolved.status, "accepted");
    assert_eq!(fx.store.test_cached_census_head_rev(&fx.family_id), None);
    assert_cache_matches_full_recompute(&fx.store, &fx.family_id);
}

#[test]
fn withdraw_conflict_branches_main_exit_invalidates_and_closes() {
    let fx = seed_branched_conflict();
    install_entry_at_head(&fx.store, &fx.family_id);

    let withdrawn = fx
        .store
        .withdraw_conflict_branches(
            &fx.owner,
            &fx.conflict_id,
            WithdrawConflictInput {
                withdrawal_mutation_id: Uuid::new_v4().to_string(),
                expected_stable_version_id: fx.stable_version_id.clone(),
                expected_branch_version_ids: vec![fx.branch_version_id.clone()],
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(withdrawn.status, "accepted", "{withdrawn:?}");
    assert_eq!(
        withdrawn.remaining_branch_version_ids,
        Some(Vec::<String>::new()),
        "{withdrawn:?}"
    );
    assert_eq!(fx.store.test_cached_census_head_rev(&fx.family_id), None);
    assert_cache_matches_full_recompute(&fx.store, &fx.family_id);
}

#[test]
fn withdraw_conflict_branches_no_target_exit_still_sweeps_leftovers() {
    let fx = seed_branched_conflict();
    // Withdraw both... the conflict still has one branch; withdraw it first
    // so the conflict itself is resolved, then re-entry has no targets.
    let withdrawn = fx
        .store
        .withdraw_conflict_branches(
            &fx.owner,
            &fx.conflict_id,
            WithdrawConflictInput {
                withdrawal_mutation_id: Uuid::new_v4().to_string(),
                expected_stable_version_id: fx.stable_version_id.clone(),
                expected_branch_version_ids: vec![fx.branch_version_id.clone()],
            },
            1_700_000_003,
        )
        .unwrap();
    assert_eq!(withdrawn.status, "accepted", "{withdrawn:?}");
    let head = install_entry_at_head(&fx.store, &fx.family_id);

    // A no-target withdrawal over the resolved conflict commits through the
    // early exit; the in-tx sweep runs (nothing left to close here).
    let again = fx
        .store
        .withdraw_conflict_branches(
            &fx.owner,
            &fx.conflict_id,
            WithdrawConflictInput {
                withdrawal_mutation_id: Uuid::new_v4().to_string(),
                expected_stable_version_id: fx.stable_version_id.clone(),
                expected_branch_version_ids: vec![],
            },
            1_700_000_004,
        )
        .unwrap();
    assert_eq!(again.status, "accepted", "{again:?}");
    assert_eq!(
        fx.store.test_cached_census_head_rev(&fx.family_id),
        Some(head),
        "a no-op sweep bumps nothing, so the entry legitimately survives"
    );
    assert_cache_matches_full_recompute(&fx.store, &fx.family_id);

    // When a leftover DOES exist, the same early exit closes it and the bump
    // invalidates the entry.
    let stable_version_id = fx
        .store
        .pull(&fx.family_id, 0)
        .unwrap()
        .entities
        .iter()
        .find(|entity| entity.client_uuid == fx.record_id.to_string())
        .unwrap()
        .version_id
        .clone()
        .unwrap();
    let leftover = Uuid::new_v4().to_string();
    fx.store
        .connect()
        .unwrap()
        .execute(
            "INSERT INTO conflicts(
                family_id, conflict_id, entity_type, client_uuid, base_version_id,
                stable_version_id, status, kind, created_at, resolved_at
             ) VALUES (?1, ?2, 'record', ?3, NULL, ?4, 'open', 'tombstone_restore', 1, NULL)",
            rusqlite::params![
                fx.family_id,
                leftover,
                fx.record_id.to_string(),
                stable_version_id
            ],
        )
        .unwrap();
    let synced_cursor = fx.store.current_revision(&fx.family_id).unwrap();
    install_entry_at_head(&fx.store, &fx.family_id);

    let third = fx
        .store
        .withdraw_conflict_branches(
            &fx.owner,
            &fx.conflict_id,
            WithdrawConflictInput {
                withdrawal_mutation_id: Uuid::new_v4().to_string(),
                expected_stable_version_id: fx.stable_version_id.clone(),
                expected_branch_version_ids: vec![],
            },
            1_700_000_005,
        )
        .unwrap();
    assert_eq!(third.status, "accepted", "{third:?}");
    let closure_head = fx.store.current_revision(&fx.family_id).unwrap();
    assert!(
        closure_head > synced_cursor,
        "early-exit sweep must close the leftover and advance the head"
    );
    assert_eq!(fx.store.test_cached_census_head_rev(&fx.family_id), None);
    assert_cache_matches_full_recompute(&fx.store, &fx.family_id);
}

#[test]
fn rename_family_invalidates_through_the_funnel() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    install_entry_at_head(&store, &family_id);

    store.rename_family(&family_id, "新名字").unwrap();
    assert_eq!(store.test_cached_census_head_rev(&family_id), None);
    assert_cache_matches_full_recompute(&store, &family_id);
}

#[test]
fn staging_publish_invalidates_via_advance_rev() {
    // The split publication API mirrors the HTTP flow: causal_commit_durable
    // claims the manifest (status 'consumed') without publishing, leaving the
    // promotion to publish_causal_commit / the startup sweep. Promotion of a
    // live media entity advances the family rev through the staging funnel.
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    install_entry_at_head(&store, &family_id);

    let media_uuid = Uuid::new_v4();
    let item = stage_log_media(&store, &owner, media_uuid, b"media");
    let root = map(json!({
        "baby_client_uuid": baby_id,
        "type": "formula",
        "custom_item_client_uuid": null,
        "timestamp": 100,
        "end_timestamp": null,
        "note": "with media",
        "payload_json": {"amount_ml": 10},
        "schema_version": 2,
        "updated_at": 2,
    }));
    let _durable = store
        .causal_commit_durable(
            &owner,
            vec![CausalMutation {
                mutation_id: Uuid::new_v4().to_string(),
                base_version: None,
                entity_type: "record".to_owned(),
                client_uuid: Uuid::new_v4().to_string(),
                root,
                media: vec![item],
                deleted: false,
            }],
            1_700_000_001,
        )
        .unwrap();
    // The commit bumped the head; rebuild the cache entry at the new head
    // while the media bytes are still unpromoted.
    install_entry_at_head(&store, &family_id);
    let head_after_commit = store.current_revision(&family_id).unwrap();

    store.promote_consumed_causal_media().unwrap();
    assert_eq!(
        store.test_cached_census_head_rev(&family_id),
        None,
        "staging publish must invalidate through the advance_rev funnel"
    );
    assert_cache_matches_full_recompute(&store, &family_id);
    assert!(
        store.current_revision(&family_id).unwrap() > head_after_commit,
        "promotion of a live media must have advanced the head"
    );
}

#[test]
fn declare_source_relation_invalidates_via_relation_member_bump() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-census-member".to_owned(),
        device_id: "d-census-member".to_owned(),
    };
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    let display = Uuid::new_v4();
    let source = Uuid::new_v4();
    let v_display = commit_unit(
        &store,
        &owner,
        "record",
        display,
        None,
        record_root(baby_id, "keep", 10, 2),
        false,
        1_700_000_001,
    )
    .stable_version_id
    .unwrap();
    let v_source = commit_unit(
        &store,
        &member,
        "record",
        source,
        None,
        record_root(baby_id, "dup", 10, 3),
        false,
        1_700_000_002,
    )
    .stable_version_id
    .unwrap();
    install_entry_at_head(&store, &family_id);

    let receipt = store
        .declare_source_relation(
            &member,
            DeclareSourceRelationInput {
                mutation_id: "mut-census-declare-1".to_owned(),
                record_client_uuid: source.to_string(),
                equivalent_to_client_uuid: display.to_string(),
                expected_record_version: v_source,
                expected_other_version: v_display,
            },
            1_700_000_100,
        )
        .unwrap();
    assert_eq!(receipt.status, "accepted");
    assert_eq!(
        store.test_cached_census_head_rev(&family_id),
        None,
        "relation member batch bump must invalidate"
    );
    assert_cache_matches_full_recompute(&store, &family_id);
}

#[test]
fn resolve_source_relation_group_invalidates_via_relation_member_bump() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let member = Principal {
        family_id: family_id.clone(),
        role: "member".to_owned(),
        membership_id: "m-census-member".to_owned(),
        device_id: "d-census-member".to_owned(),
    };
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    let first = Uuid::new_v4();
    let second = Uuid::new_v4();
    let v_first = commit_unit(
        &store,
        &owner,
        "record",
        first,
        None,
        record_root(baby_id, "a", 10, 2),
        false,
        1_700_000_001,
    )
    .stable_version_id
    .unwrap();
    let v_second = commit_unit(
        &store,
        &member,
        "record",
        second,
        None,
        record_root(baby_id, "b", 10, 3),
        false,
        1_700_000_002,
    )
    .stable_version_id
    .unwrap();
    install_entry_at_head(&store, &family_id);

    let receipt = store
        .resolve_source_relation_group(
            &owner,
            ResolveSourceRelationGroupInput {
                mutation_id: "mut-census-group-1".to_owned(),
                member_client_uuids: vec![first.to_string(), second.to_string()],
                display_client_uuid: first.to_string(),
                expected_versions: BTreeMap::from([
                    (first.to_string(), v_first),
                    (second.to_string(), v_second),
                ]),
            },
            1_700_000_200,
        )
        .unwrap();
    assert_eq!(receipt.status, "accepted", "{receipt:?}");
    assert_eq!(store.test_cached_census_head_rev(&family_id), None);
    assert_cache_matches_full_recompute(&store, &family_id);
}

#[test]
fn commit_bundle_absolute_rev_write_invalidates_unconditionally() {
    // Legacy bundles only accept fulfillment_candidate roots, so use the
    // shared fixture for a committable bundle.
    let fx = FulfillmentCandidateFixture::seed();
    install_entry_at_head(&fx.store, &fx.family_id);

    // stage_bundle writes no revs; commit_bundle assigns absolute revs.
    let bundle_id = Uuid::new_v4().to_string();
    let root = entity(
        "fulfillment_candidate",
        fx.candidate_id,
        3,
        fx.exact_candidate_payload(),
    );
    fx.store
        .stage_bundle(&fx.owner, &bundle_id, root, vec![], 1_700_000_000)
        .unwrap();
    assert_eq!(
        fx.store.test_cached_census_head_rev(&fx.family_id),
        Some(fx.store.current_revision(&fx.family_id).unwrap()),
        "staging must not invalidate (no rev write)"
    );

    let media_ready = BTreeMap::new();
    let (result, _) = fx
        .store
        .commit_bundle(&fx.owner, &bundle_id, &media_ready, 10, 1_700_000_000)
        .unwrap();
    assert_eq!(result.status, "committed");
    assert_eq!(fx.store.test_cached_census_head_rev(&fx.family_id), None);
    assert_cache_matches_full_recompute(&fx.store, &fx.family_id);
}

#[test]
fn hard_delete_membership_absolute_rev_write_invalidates_unconditionally() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    let membership_id = store
        .add_device_less_member(&family_id, "成员", "成员")
        .unwrap();
    install_entry_at_head(&store, &family_id);

    // Deleting the membership anonymizes surviving entity payloads with
    // absolute rev assignments.
    store
        .hard_delete_membership(&family_id, &membership_id, 1_700_000_100)
        .unwrap();
    assert_eq!(store.test_cached_census_head_rev(&family_id), None);
    assert_cache_matches_full_recompute(&store, &family_id);
}

#[test]
fn disaster_restore_leaves_a_correctly_populated_cache() {
    // activate_disaster_restore only runs on an empty database, so a stale
    // entry can never pre-exist in the same store; the unconditional
    // invalidation is defense-in-depth (A4) and the rev-reuse epoch guard is
    // unit-tested at the cache level below.
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    store
        .activate_disaster_restore(
            DisasterRestoreIdentityInput {
                now: 1_700_000_000,
                family_id: "restore-family",
                family_name: "恢复家庭",
                owner_membership_id: "m-restore",
                owner_display_name: "妈妈",
                owner_display_name_key: "妈妈",
                device_id: "d-restore",
                device_name: "restore",
                session_id: "s-restore",
                access_token: "access-restore",
                access_expires_at: 1_800_000_000,
                refresh_token: "refresh-restore",
                owner_root_fingerprint: None,
            },
            vec![Entity {
                entity_type: "baby".to_owned(),
                client_uuid: Uuid::new_v4().to_string(),
                updated_at: 1,
                deleted_at: None,
                payload: map(json!({
                    "nickname":"年年","sex":"female","birthday":"2025-01-02",
                    "avatar_media_uuid":null,"birth_weight_grams":3200
                })),
            }],
        )
        .unwrap();
    assert_cache_matches_full_recompute(&store, "restore-family");
}

#[test]
fn non_rev_writes_neither_invalidate_nor_change_the_census() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let owner = owner_principal(&family_id);
    let baby_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "baby",
        baby_id,
        None,
        baby_root("年年", 1),
        false,
        1_700_000_000,
    );
    let record_id = Uuid::new_v4();
    let v1 = commit_unit(
        &store,
        &owner,
        "record",
        record_id,
        None,
        record_root(baby_id, "a", 10, 2),
        false,
        1_700_000_001,
    )
    .stable_version_id
    .unwrap();
    let head = install_entry_at_head(&store, &family_id);

    // gc_causal_media and gc_conflict_metadata: no live-set or rev writes.
    store.gc_causal_media(1_700_000_200).unwrap();
    store.gc_conflict_metadata(1_700_000_200).unwrap();
    assert_eq!(store.test_cached_census_head_rev(&family_id), Some(head));

    // The entry still serves the golden census.
    assert_cache_matches_full_recompute(&store, &family_id);
    assert_eq!(store.current_revision(&family_id).unwrap(), head);

    // A funnel write then proves the entry tracks live changes.
    commit_unit(
        &store,
        &owner,
        "record",
        record_id,
        Some(&v1),
        record_root(baby_id, "a", 10, 3),
        true,
        1_700_000_201,
    );
    assert_eq!(store.test_cached_census_head_rev(&family_id), None);
    assert_cache_matches_full_recompute(&store, &family_id);
    let live_records: i64 = {
        let connection = store.connect().unwrap();
        connection
            .query_row(
                "SELECT COUNT(*) FROM entities
                  WHERE family_id = ?1 AND entity_type = 'record' AND deleted_at IS NULL",
                rusqlite::params![family_id],
                |row| row.get(0),
            )
            .unwrap()
    };
    assert_eq!(live_records, 0, "tombstoned record left the live set");
}

/// Seeds a second family row directly: production `create_family` enforces
/// one family per data root, so cross-family tests graft a sibling family
/// (same shape `activate_disaster_restore` or offline migration would yield).
fn seed_sibling_family(store: &Store, family_id: &str, baby_client_uuid: &str) {
    store
        .connect()
        .unwrap()
        .execute_batch(&format!(
            "INSERT INTO families(id, created_at, create_request_hash, name)
         VALUES ('{family_id}', 1, NULL, '邻家');
         INSERT INTO family_meta(family_id, rev) VALUES ('{family_id}', 0);
         INSERT INTO entities(
             family_id, entity_type, client_uuid, updated_at,
             deleted_at, payload_json, rev
         ) VALUES ('{family_id}', 'baby', '{baby_client_uuid}', 1, NULL, '{{}}', 1);"
        ))
        .unwrap();
}

#[test]
fn create_family_starts_with_no_cached_entry() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let first = new_family(&store, "census-family-first");
    install_entry_at_head(&store, &first);
    // A second family INSERTs a new family_meta row (rev 0), it does not
    // increment an existing one: no entry exists for it and the first family
    // stays cached.
    let second = "census-family-sibling";
    seed_sibling_family(&store, second, &Uuid::new_v4().to_string());
    assert_eq!(store.test_cached_census_head_rev(second), None);
    assert!(store.test_cached_census_head_rev(&first).is_some());
    assert_cache_matches_full_recompute(&store, second);
    assert_cache_matches_full_recompute(&store, &first);
}

#[test]
fn multi_family_entries_are_isolated() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_a = new_family(&store, "census-family-a");
    let owner_a = owner_principal(&family_a);
    let _baby_a = seed_baby(&store, &owner_a);
    let family_b = "census-family-b";
    seed_sibling_family(&store, family_b, &Uuid::new_v4().to_string());
    let _head_a = install_entry_at_head(&store, &family_a);
    let _head_b = install_entry_at_head(&store, family_b);

    // A rev-advancing write in family A must not drop family B's entry.
    commit_unit(
        &store,
        &owner_a,
        "record",
        Uuid::new_v4(),
        None,
        record_root(_baby_a, "a", 10, 2),
        false,
        1_700_000_001,
    );
    assert_eq!(store.test_cached_census_head_rev(family_b), Some(0));
    assert_eq!(store.test_cached_census_head_rev(&family_a), None);
    assert_cache_matches_full_recompute(&store, family_b);
    assert_cache_matches_full_recompute(&store, &family_a);
}

#[test]
fn restart_rebuilds_exactly_once() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let store = Store::open(&db_path).unwrap();
    let family_id = family(&store);
    install_entry_at_head(&store, &family_id);
    drop(store);

    // A fresh Store (process restart) starts with an empty cache: the first
    // census pull rebuilds and installs, later same-head pulls hit.
    let reopened = Store::open(&db_path).unwrap();
    assert_eq!(reopened.test_cached_census_head_rev(&family_id), None);
    assert_cache_matches_full_recompute(&reopened, &family_id);
}

#[test]
fn read_only_store_never_installs() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let writer = Store::open(&db_path).unwrap();
    let family_id = family(&writer);
    install_entry_at_head(&writer, &family_id);

    let reader =
        Store::open_read_only_with_snapshot_key(&db_path, b"lezi-sync-test-snapshot-key").unwrap();
    let head = reader.current_revision(&family_id).unwrap();
    let page = reader.pull_with_census(&family_id, head).unwrap();
    assert!(page.live_census.is_some());
    assert_eq!(
        reader.test_cached_census_head_rev(&family_id),
        None,
        "read-only stores must not write the cache"
    );
    // And repeated read-only pulls stay correct (always rebuilt).
    let again = reader.pull_with_census(&family_id, head).unwrap();
    assert_eq!(again.live_census, page.live_census);
}

#[test]
fn store_clones_share_one_cache() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = family(&store);
    let clone = store.clone();
    install_entry_at_head(&store, &family_id);
    assert_eq!(
        clone.test_cached_census_head_rev(&family_id),
        store.test_cached_census_head_rev(&family_id),
        "cloned Store handles share the family cache"
    );
    clone.live_census_cache.invalidate(&family_id);
    assert_eq!(store.test_cached_census_head_rev(&family_id), None);
}

#[test]
fn cache_epoch_guard_refuses_stale_rebuild_and_rev_reuse() {
    // Cache-level A4/B7: an entry at head 5 must never survive an
    // invalidation, even when the head later returns to exactly 5.
    let cache = LiveCensusCache::new();
    let census = LiveCensus::budget_placeholder;
    let CensusLookup::Miss(token) = cache.lookup("f", 5) else {
        panic!("empty cache must miss");
    };
    cache.install("f", token, 5, census());
    assert!(matches!(cache.lookup("f", 5), CensusLookup::Hit(_)));

    // Invalidation bumps the epoch; a rebuild captured before it is refused
    // at install time, so a rollback that reuses rev 5 cannot collide.
    cache.invalidate("f");
    let CensusLookup::Miss(fresh_token) = cache.lookup("f", 5) else {
        panic!("invalidated entry must miss");
    };
    cache.install("f", fresh_token, 5, census());
    assert!(matches!(cache.lookup("f", 5), CensusLookup::Hit(_)));

    // A token captured before a later invalidation is refused at install.
    cache.invalidate("f");
    cache.install("f", fresh_token, 5, census());
    assert!(matches!(cache.lookup("f", 5), CensusLookup::Miss(_)));

    // Other families have independent epochs.
    cache.invalidate("g");
    assert!(matches!(cache.lookup("f", 5), CensusLookup::Miss(_)));
}

#[test]
fn leftover_rows_are_closed_by_the_maintenance_sweep_and_invalidate() {
    let directory = TempDir::new().unwrap();
    let store = Store::open(directory.path().join("lezi.db")).unwrap();
    let family_id = new_family(&store, "census-leftover-family");
    let owner = owner_principal(&family_id);
    let baby_id = seed_baby(&store, &owner);
    let record_id = Uuid::new_v4();
    commit_unit(
        &store,
        &owner,
        "record",
        record_id,
        None,
        record_root(baby_id, "a", 10, 20),
        false,
        1_700_000_001,
    );
    // Seed a leftover empty-branch open conflict (what retention cleanup or a
    // pre-0.5 upgrade can leave behind).
    let conflict_id = Uuid::new_v4().to_string();
    let stable_version_id = store
        .pull(&family_id, 0)
        .unwrap()
        .entities
        .iter()
        .find(|entity| entity.client_uuid == record_id.to_string())
        .unwrap()
        .version_id
        .clone()
        .unwrap();
    store
        .connect()
        .unwrap()
        .execute(
            "INSERT INTO conflicts(
                family_id, conflict_id, entity_type, client_uuid, base_version_id,
                stable_version_id, status, kind, created_at, resolved_at
             ) VALUES (?1, ?2, 'record', ?3, NULL, ?4, 'open', 'tombstone_restore', 1, NULL)",
            rusqlite::params![
                family_id,
                conflict_id,
                record_id.to_string(),
                stable_version_id
            ],
        )
        .unwrap();
    let synced_cursor = store.current_revision(&family_id).unwrap();
    install_entry_at_head(&store, &family_id);

    store
        .close_leftover_empty_open_conflicts(1_700_000_300)
        .unwrap();
    let closure_head = store.current_revision(&family_id).unwrap();
    assert!(closure_head > synced_cursor, "closure advances the head");
    assert_eq!(
        store.test_cached_census_head_rev(&family_id),
        None,
        "closure bumps the head through the funnel and must invalidate"
    );
    assert_cache_matches_full_recompute(&store, &family_id);
    let status: String = store
        .connect()
        .unwrap()
        .query_row(
            "SELECT status FROM conflicts WHERE family_id = ?1 AND conflict_id = ?2",
            rusqlite::params![family_id, conflict_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(status, "resolved");
    // The pull-only device receives the closure via the rev bump.
    let page = store.pull(&family_id, synced_cursor).unwrap();
    assert!(
        page.entities
            .iter()
            .any(|entity| entity.client_uuid == record_id.to_string()),
        "closure rev bump must redeliver the entity to caught-up devices"
    );
}

#[test]
fn maintenance_sweep_is_a_no_op_for_read_only_and_empty_stores() {
    let directory = TempDir::new().unwrap();
    let db_path = directory.path().join("lezi.db");
    let writer = Store::open(&db_path).unwrap();
    let _family_id = family(&writer);
    drop(writer);
    let reader =
        Store::open_read_only_with_snapshot_key(&db_path, b"lezi-sync-test-snapshot-key").unwrap();
    reader
        .close_leftover_empty_open_conflicts(1_700_000_300)
        .unwrap();
    drop(reader);
    // An empty database sweep finds nothing to do.
    let fresh_dir = TempDir::new().unwrap();
    let fresh = Store::open(fresh_dir.path().join("lezi.db")).unwrap();
    fresh
        .close_leftover_empty_open_conflicts(1_700_000_300)
        .unwrap();
    let connection: Connection = fresh.connect().unwrap();
    let families: i64 = connection
        .query_row("SELECT COUNT(*) FROM families", [], |row| row.get(0))
        .unwrap();
    assert_eq!(families, 0);
}

#[test]
fn census_cache_is_shareable_across_threads() {
    // Shape check for the production sharing story: the cache must be usable
    // behind an Arc from multiple threads (Store clones, AppState).
    let cache = Arc::new(LiveCensusCache::new());
    let other = cache.clone();
    other.invalidate("f");
    let CensusLookup::Miss(token) = cache.lookup("f", 1) else {
        panic!("invalidated family must miss");
    };
    let spawned = std::thread::spawn(move || {
        other.install("f", token, 1, LiveCensus::budget_placeholder());
    });
    spawned.join().unwrap();
    assert!(matches!(cache.lookup("f", 1), CensusLookup::Hit(_)));
}
