//! Shared fixtures for store unit tests.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::sync::{Mutex, OnceLock};

use super::super::*;
use crate::model::Entity;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use tempfile::{NamedTempFile, TempDir};
use uuid::Uuid;

fn statement_counts() -> &'static Mutex<BTreeMap<String, usize>> {
    static COUNTS: OnceLock<Mutex<BTreeMap<String, usize>>> = OnceLock::new();
    COUNTS.get_or_init(|| Mutex::new(BTreeMap::new()))
}

fn statement_traces() -> &'static Mutex<BTreeMap<String, Vec<String>>> {
    static TRACES: OnceLock<Mutex<BTreeMap<String, Vec<String>>>> = OnceLock::new();
    TRACES.get_or_init(|| Mutex::new(BTreeMap::new()))
}

pub(in crate::store) fn trace_counted_pull_statement(sql: &str) {
    {
        let mut counts = statement_counts().lock().unwrap();
        for (family_id, count) in counts.iter_mut() {
            if sql.contains(family_id.as_str()) {
                *count = count.saturating_add(1);
            }
        }
    }
    {
        let mut traces = statement_traces().lock().unwrap();
        for (family_id, statements) in traces.iter_mut() {
            if sql.contains(family_id.as_str()) {
                statements.push(sql.to_owned());
            }
        }
    }
}

pub(super) fn begin_statement_count(family_id: &str) {
    statement_counts()
        .lock()
        .unwrap()
        .insert(family_id.to_owned(), 0);
}

pub(super) fn finish_statement_count(family_id: &str) -> usize {
    statement_counts()
        .lock()
        .unwrap()
        .remove(family_id)
        .expect("pull statement counter was started")
}

pub(super) fn begin_statement_trace(family_id: &str) {
    begin_statement_count(family_id);
    statement_traces()
        .lock()
        .unwrap()
        .insert(family_id.to_owned(), Vec::new());
}

pub(super) fn finish_statement_trace(family_id: &str) -> (usize, Vec<String>) {
    let statements = statement_traces()
        .lock()
        .unwrap()
        .remove(family_id)
        .expect("pull statement trace was started");
    let count = statement_counts()
        .lock()
        .unwrap()
        .remove(family_id)
        .expect("pull statement counter was started");
    (count, statements)
}

pub(super) trait TestPull {
    fn pull(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError>;
    fn pull_with_census(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError>;
}

impl TestPull for Store {
    fn pull(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError> {
        self.pull_with_final_envelope_size(
            family_id,
            cursor,
            false,
            &BTreeSet::new(),
            |serialized_entity_bytes, entity_count, _, _| {
                Ok(serialized_entity_bytes.saturating_add(entity_count.saturating_sub(1)))
            },
        )
    }

    fn pull_with_census(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError> {
        self.pull_with_final_envelope_size(
            family_id,
            cursor,
            true,
            &BTreeSet::new(),
            |serialized_entity_bytes, entity_count, _, _| {
                Ok(serialized_entity_bytes.saturating_add(entity_count.saturating_sub(1)))
            },
        )
    }
}

pub(super) fn entity(
    entity_type: &str,
    client_uuid: Uuid,
    updated_at: i64,
    payload: Value,
) -> Entity {
    Entity {
        entity_type: entity_type.to_owned(),
        client_uuid: client_uuid.to_string(),
        updated_at,
        deleted_at: None,
        payload: payload.as_object().unwrap().clone(),
    }
}
pub(super) fn test_owners() -> &'static Mutex<BTreeMap<String, Principal>> {
    static OWNERS: OnceLock<Mutex<BTreeMap<String, Principal>>> = OnceLock::new();
    OWNERS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
pub(super) fn family(store: &Store) -> String {
    let created = store
        .create_family(
            CreateFamilyInput {
                now: 1,
                create_request_id: "bounded-push-request-id-0000000001",
                display_name: "妈妈",
                display_name_key: "妈妈",
                family_name: "家庭",
                device_name: "owner",
                owner_root_fingerprint: None,
            },
            |_, _, _| ("owner-access".to_owned(), "owner-refresh".to_owned()),
        )
        .unwrap();
    let principal = Principal {
        family_id: created.family_id.clone(),
        role: "owner".to_owned(),
        membership_id: created.membership_id,
        device_id: created.device_id,
    };
    test_owners()
        .lock()
        .unwrap()
        .insert(created.family_id.clone(), principal);
    created.family_id
}
pub(super) fn owner_principal(family_id: &str) -> Principal {
    test_owners()
        .lock()
        .unwrap()
        .get(family_id)
        .cloned()
        .unwrap_or_else(|| Principal {
            family_id: family_id.to_owned(),
            role: "owner".to_owned(),
            membership_id: "m-owner".to_owned(),
            device_id: "d-owner".to_owned(),
        })
}

pub(super) fn register_test_principal(store: &Store, principal: &Principal) {
    let connection = store.connect().unwrap();
    connection.execute("INSERT OR IGNORE INTO memberships(membership_id,family_id,role,display_name,display_name_key) VALUES (?1,?2,?3,?1,?1)",rusqlite::params![principal.membership_id,principal.family_id,principal.role]).unwrap();
    connection.execute("INSERT OR IGNORE INTO devices(device_id,membership_id,device_name,device_name_key,status,created_at,last_used_at) VALUES (?1,?2,?1,?1,'active',1,1)",rusqlite::params![principal.device_id,principal.membership_id]).unwrap();
}
pub(super) fn publish_bundle(
    store: &Store,
    principal: &Principal,
    root: Entity,
    media: Vec<Entity>,
    max_updated_at: i64,
) -> Result<BundleCommitResult, StoreError> {
    let bundle_id = Uuid::new_v4().to_string();
    assert!(
        media.is_empty(),
        "current fulfillment bundles are media-free"
    );
    let mut media_ready = BTreeMap::new();
    store.stage_bundle(principal, &bundle_id, root, media, 1_700_000_000)?;
    media_ready.extend(
        store
            .deferred_fulfillment_media_integrity_for_bundle(&principal.family_id, &bundle_id)?
            .into_keys()
            .map(|media_id| (media_id, true)),
    );
    store
        .commit_bundle(
            principal,
            &bundle_id,
            &media_ready,
            max_updated_at,
            1_700_000_000,
        )
        .map(|(result, _)| result)
}
pub(super) trait TestCausalMediaStage {
    fn stage_test_preimage(
        &self,
        principal: &Principal,
        media_uuid: &str,
        bytes: &[u8],
        expected_sha256: &str,
        now: i64,
        limits: CausalMediaStagingLimits,
    ) -> Result<CausalMediaStageStatus, StoreError>;
}

impl TestCausalMediaStage for Store {
    fn stage_test_preimage(
        &self,
        principal: &Principal,
        media_uuid: &str,
        bytes: &[u8],
        expected_sha256: &str,
        now: i64,
        limits: CausalMediaStagingLimits,
    ) -> Result<CausalMediaStageStatus, StoreError> {
        let incoming = NamedTempFile::new().unwrap();
        fs::write(incoming.path(), bytes).unwrap();
        let verified = VerifiedCausalMediaPreimage::verify(
            incoming.path().to_owned(),
            expected_sha256,
            limits.max_file_bytes,
        )?;
        self.stage_verified_causal_media_preimage(principal, media_uuid, &verified, now, limits)
    }
}

pub(super) fn stage_log_media(
    store: &Store,
    principal: &Principal,
    media_uuid: Uuid,
    bytes: &[u8],
) -> CausalMediaItem {
    stage_role_media(store, principal, media_uuid, bytes, "log")
}

pub(super) fn stage_wake_media(
    store: &Store,
    principal: &Principal,
    media_uuid: Uuid,
    bytes: &[u8],
) -> CausalMediaItem {
    stage_role_media(store, principal, media_uuid, bytes, "wake")
}

fn stage_role_media(
    store: &Store,
    principal: &Principal,
    media_uuid: Uuid,
    bytes: &[u8],
    role: &str,
) -> CausalMediaItem {
    let sha256 = hex::encode(Sha256::digest(bytes));
    store
        .stage_test_preimage(
            principal,
            &media_uuid.to_string(),
            bytes,
            &sha256,
            1_700_000_000,
            DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
        )
        .unwrap();
    CausalMediaItem {
        media_uuid: media_uuid.to_string(),
        role: role.to_owned(),
        sha256,
        byte_size: i64::try_from(bytes.len()).expect("test media fits i64"),
        mime: "image/jpeg".to_owned(),
        width: Some(4),
        height: Some(4),
    }
}

pub(super) fn publish_root(
    store: &Store,
    principal: &Principal,
    root: Entity,
    max_updated_at: i64,
) -> Result<BundleCommitResult, StoreError> {
    publish_root_with_media(store, principal, root, vec![], max_updated_at)
}

pub(super) fn publish_root_with_media(
    store: &Store,
    principal: &Principal,
    root: Entity,
    media: Vec<CausalMediaItem>,
    max_updated_at: i64,
) -> Result<BundleCommitResult, StoreError> {
    if root.entity_type == "fulfillment_candidate" {
        assert!(
            media.is_empty(),
            "fulfillment candidates do not carry causal media"
        );
        return publish_bundle(store, principal, root, vec![], max_updated_at);
    }
    let mut causal_root = root.payload.clone();
    causal_root.insert("updated_at".to_owned(), json!(root.updated_at));
    let result = store.causal_commit(
        principal,
        vec![CausalMutation {
            mutation_id: Uuid::new_v4().to_string(),
            base_version: None,
            entity_type: root.entity_type,
            client_uuid: root.client_uuid,
            root: causal_root,
            media,
            deleted: root.deleted_at.is_some(),
        }],
        1_700_000_000,
    )?;
    let applied = usize::from(
        result
            .results
            .first()
            .is_some_and(|unit| unit.status == "accepted" || unit.status == "merged"),
    );
    Ok(BundleCommitResult {
        bundle_id: "causal-test-setup".to_owned(),
        status: "committed".to_owned(),
        applied,
        cursor: store.pull(&principal.family_id, 0)?.cursor,
        record_authors: vec![],
    })
}

pub(super) struct FulfillmentCandidateFixture {
    pub(super) store: Store,
    pub(super) _directory: TempDir,
    pub(super) family_id: String,
    pub(super) owner: Principal,
    pub(super) member: Principal,
    pub(super) peer: Principal,
    pub(super) baby_id: Uuid,
    pub(super) plan_id: Uuid,
    pub(super) other_plan_id: Uuid,
    pub(super) record_id: Uuid,
    pub(super) other_record_id: Uuid,
    pub(super) candidate_id: Uuid,
    pub(super) actual_timestamp: i64,
}

impl FulfillmentCandidateFixture {
    pub(super) fn seed() -> Self {
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
        let peer = Principal {
            family_id: family_id.clone(),
            role: "member".to_owned(),
            membership_id: "m-peer".to_owned(),
            device_id: "d-peer".to_owned(),
        };
        register_test_principal(&store, &member);
        register_test_principal(&store, &peer);
        let baby_id = Uuid::new_v4();
        let plan_id = Uuid::new_v4();
        let other_plan_id = Uuid::new_v4();
        let record_id = Uuid::new_v4();
        let other_record_id = Uuid::new_v4();
        let candidate_id = Uuid::new_v4();
        let actual_timestamp = 1_700_000_000_100i64;
        let baby_payload = json!({
            "nickname":"年年","sex":"female","birthday":"2025-01-02",
            "avatar_media_uuid":null,"birth_weight_grams":3200
        });
        let plan_payload = |plan_note: &str| {
            json!({
                "baby_client_uuid":baby_id,"type":"bath",
                "custom_item_client_uuid":null,
                "scheduled_at":1_700_000_000_000i64,
                "scheduled_zone_id":"Asia/Shanghai",
                "status":"pending","payload_json":{},"schema_version":2,
                "note":plan_note,"created_by_membership_id":"m-owner",
                "fulfilled_record_client_uuid":null,"fulfilled_at":null,
                "source_record_client_uuid":null
            })
        };
        let record_payload = || {
            json!({
                "baby_client_uuid":baby_id,"type":"bath",
                "custom_item_client_uuid":null,"timestamp":actual_timestamp,
                "end_timestamp":null,"note":null,"payload_json":{},"schema_version":2
            })
        };

        publish_root(&store, &owner, entity("baby", baby_id, 1, baby_payload), 10).unwrap();
        publish_root(
            &store,
            &owner,
            entity("care_plan", plan_id, 1, plan_payload("primary")),
            10,
        )
        .unwrap();
        publish_root(
            &store,
            &owner,
            entity("care_plan", other_plan_id, 1, plan_payload("other")),
            10,
        )
        .unwrap();
        publish_root(
            &store,
            &member,
            entity("record", record_id, 2, record_payload()),
            10,
        )
        .unwrap();
        publish_root(
            &store,
            &member,
            entity("record", other_record_id, 2, record_payload()),
            10,
        )
        .unwrap();

        Self {
            store,
            _directory: directory,
            family_id,
            owner,
            member,
            peer,
            baby_id,
            plan_id,
            other_plan_id,
            record_id,
            other_record_id,
            candidate_id,
            actual_timestamp,
        }
    }

    pub(super) fn candidate_payload(&self, plan: Uuid, record: Uuid, actual: Option<i64>) -> Value {
        json!({
            "care_plan_client_uuid": plan,
            "record_client_uuid": record,
            "actual_timestamp": actual,
            "submitter_membership_id": "forged",
            "submitter_role": "owner",
            "confirmed_at": 1,
        })
    }

    pub(super) fn exact_candidate_payload(&self) -> Value {
        self.candidate_payload(self.plan_id, self.record_id, Some(self.actual_timestamp))
    }

    pub(super) fn publish_first_accept(&self) -> (i64, i64, Value) {
        assert_eq!(
            publish_root(
                &self.store,
                &self.member,
                entity(
                    "fulfillment_candidate",
                    self.candidate_id,
                    3,
                    self.exact_candidate_payload(),
                ),
                10,
            )
            .unwrap()
            .applied,
            1
        );
        let after_first = self.store.pull(&self.family_id, 0).unwrap();
        let frozen = after_first
            .entities
            .iter()
            .find(|entity| {
                entity.entity_type == "fulfillment_candidate"
                    && entity.client_uuid == self.candidate_id.to_string()
            })
            .expect("candidate published");
        (
            after_first.cursor,
            frozen.rev,
            frozen.payload["confirmed_at"].clone(),
        )
    }

    pub(super) fn pull_candidate(&self) -> PulledEntity {
        self.store
            .pull(&self.family_id, 0)
            .unwrap()
            .entities
            .into_iter()
            .find(|entity| entity.client_uuid == self.candidate_id.to_string())
            .expect("candidate present")
    }
}

// Counts SQLite VM instructions across every connection opened by a measured
// Store operation on this test thread. No timing claims use the probe: the
// per-instruction callback intentionally adds measurement overhead.
thread_local! {
    static SQL_WORK_PROBE: std::cell::Cell<Option<u64>> = const { std::cell::Cell::new(None) };
}

pub(in crate::store) fn install_sql_work_probe(connection: &rusqlite::Connection) {
    if SQL_WORK_PROBE.with(|probe| probe.get().is_some()) {
        connection.progress_handler(
            1,
            Some(|| {
                SQL_WORK_PROBE.with(|probe| {
                    if let Some(count) = probe.get() {
                        probe.set(Some(count + 1));
                    }
                });
                false
            }),
        );
    }
}

pub(super) fn with_sql_work_probe<T>(work: impl FnOnce() -> T) -> (T, u64) {
    struct Reset;
    impl Drop for Reset {
        fn drop(&mut self) {
            SQL_WORK_PROBE.with(|probe| probe.set(None));
        }
    }
    SQL_WORK_PROBE.with(|probe| {
        assert!(probe.get().is_none(), "nested SQLite work probe");
        probe.set(Some(0));
    });
    let reset = Reset;
    let value = work();
    let count = SQL_WORK_PROBE.with(|probe| probe.get().unwrap());
    drop(reset);
    (value, count)
}
