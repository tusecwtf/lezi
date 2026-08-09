//! Shared fixtures for store unit tests.

use std::collections::BTreeMap;
use std::sync::{Condvar, Mutex, OnceLock};
use std::time::Duration;

use super::super::*;
use serde_json::{json, Value};
use tempfile::TempDir;
use uuid::Uuid;

fn statement_counts() -> &'static Mutex<BTreeMap<String, usize>> {
    static COUNTS: OnceLock<Mutex<BTreeMap<String, usize>>> = OnceLock::new();
    COUNTS.get_or_init(|| Mutex::new(BTreeMap::new()))
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
    let (lock, changed) = statement_pause();
    let mut pause = lock.lock().unwrap();
    if pause
        .as_ref()
        .is_some_and(|state| sql.contains(&state.family_id))
    {
        let state = pause.as_mut().unwrap();
        state.seen += 1;
        if state.seen != state.pause_at {
            return;
        }
        state.reached = true;
        changed.notify_all();
        while !pause.as_ref().unwrap().released {
            let waited = changed
                .wait_timeout(pause, Duration::from_secs(10))
                .unwrap();
            pause = waited.0;
            if waited.1.timed_out() {
                pause.as_mut().unwrap().released = true;
            }
        }
        pause.take();
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

struct StatementPause {
    family_id: String,
    pause_at: usize,
    seen: usize,
    reached: bool,
    released: bool,
}

fn statement_pause() -> &'static (Mutex<Option<StatementPause>>, Condvar) {
    static PAUSE: OnceLock<(Mutex<Option<StatementPause>>, Condvar)> = OnceLock::new();
    PAUSE.get_or_init(|| (Mutex::new(None), Condvar::new()))
}

pub(super) fn begin_statement_pause(family_id: &str, pause_at: usize) {
    *statement_pause().0.lock().unwrap() = Some(StatementPause {
        family_id: family_id.to_owned(),
        pause_at,
        seen: 0,
        reached: false,
        released: false,
    });
}

pub(super) fn wait_for_statement_pause() {
    let (lock, changed) = statement_pause();
    let mut pause = lock.lock().unwrap();
    while !pause.as_ref().is_some_and(|state| state.reached) {
        let waited = changed
            .wait_timeout(pause, Duration::from_secs(10))
            .unwrap();
        pause = waited.0;
        if waited.1.timed_out() {
            pause.take();
            panic!("timed out waiting for statement pause");
        }
    }
}

pub(super) fn release_statement_pause() {
    let (lock, changed) = statement_pause();
    let mut pause = lock.lock().unwrap();
    pause.as_mut().expect("statement pause started").released = true;
    changed.notify_all();
}

pub(super) trait TestPull {
    fn pull(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError>;
}

impl TestPull for Store {
    fn pull(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError> {
        self.pull_with_final_envelope_size(
            family_id,
            cursor,
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
pub(super) fn family(store: &Store) -> String {
    store
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
        .unwrap()
        .family_id
}
pub(super) fn owner_principal(family_id: &str) -> Principal {
    Principal {
        family_id: family_id.to_owned(),
        role: "owner".to_owned(),
        membership_id: "m-owner".to_owned(),
        device_id: "d-owner".to_owned(),
    }
}
pub(super) fn publish_bundle(
    store: &Store,
    principal: &Principal,
    root: Entity,
    media: Vec<Entity>,
    max_updated_at: i64,
) -> Result<BundleCommitResult, StoreError> {
    let bundle_id = Uuid::new_v4().to_string();
    let mut media_ready = media
        .iter()
        .filter(|entity| entity.deleted_at.is_none())
        .map(|entity| (entity.client_uuid.clone(), true))
        .collect::<BTreeMap<_, _>>();
    let staged_media = media
        .iter()
        .filter(|entity| entity.deleted_at.is_none())
        .map(|entity| {
            Ok::<_, StoreError>((
                entity.client_uuid.clone(),
                entity
                    .payload
                    .get("byte_size")
                    .and_then(Value::as_u64)
                    .and_then(|value| usize::try_from(value).ok())
                    .ok_or(StoreError::InvalidStoredPayload)?,
            ))
        })
        .collect::<Result<Vec<_>, _>>()?;
    store.stage_bundle(principal, &bundle_id, root, media, 1_700_000_000)?;
    for (media_id, byte_size) in staged_media {
        store.mark_bundle_media_staged(
            principal,
            &bundle_id,
            &media_id,
            byte_size,
            &"a".repeat(64),
            1_700_000_000,
        )?;
    }
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
pub(super) fn publish_root(
    store: &Store,
    principal: &Principal,
    root: Entity,
    max_updated_at: i64,
) -> Result<BundleCommitResult, StoreError> {
    publish_bundle(store, principal, root, vec![], max_updated_at)
}

pub(super) struct FulfillmentCandidateFixture {
    pub(super) store: Store,
    pub(super) _directory: TempDir,
    pub(super) family_id: String,
    pub(super) owner: Principal,
    pub(super) member: Principal,
    pub(super) peer: Principal,
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
                "fulfilled_record_client_uuid":null,"fulfilled_at":null
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
