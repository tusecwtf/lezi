//! Durable conflict-detail receipts and bounded page replay.
//!
//! This module owns receipt credentials, binding, page plans, and final-wire
//! byte accounting. [`Store`](super::Store) remains the public seam.

use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rand::rngs::OsRng;
use rand::RngCore;
use rusqlite::{params, Transaction};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

use super::causal::{ConflictHeads, ConflictResolutionChoice, StableSnapshot};
use super::causal_merge::set_path;
use super::{migration_content_hash, CausalMediaItem, StoreError};

const SYSTEM_RECEIPT_PRINCIPAL: &str = "__conflict_snapshot_v2__";
const SNAPSHOT_RECEIPT_TTL_SECONDS: i64 = 10 * 60;
const MAX_BRANCHES_PER_PAGE: usize = 16;
const MAX_ENCODED_PAGE_BYTES: usize = 128 * 1024;
const MAX_RECEIPTS_PER_CONFLICT: usize = 64;
const CREDENTIAL_BYTES: usize = 32;
const SERIALIZER_CONTRACT: &str = "conflict-detail-page-serde-v2";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConflictDetailPageRequest {
    First,
    SnapshotToken(String),
    Continuation {
        snapshot_token: String,
        continuation: String,
    },
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictDetailPage {
    pub contract: &'static str,
    pub conflict_id: String,
    pub entity_type: String,
    pub client_uuid: String,
    pub snapshot_token: String,
    pub expires_at: i64,
    pub stable: ConflictVersionView,
    pub branches: Vec<ConflictVersionView>,
    pub conflicting: Vec<ConflictingPath>,
    pub auto_merged: Vec<AutoMergedPath>,
    pub page_index: usize,
    pub continuation: Option<String>,
    pub complete: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictVersionView {
    pub version_id: String,
    pub base_version: Option<String>,
    pub root: Map<String, Value>,
    pub media: Vec<CausalMediaItem>,
    pub deleted: bool,
    pub mutation_id: String,
    pub actor_id: String,
    pub device_id: String,
    pub received_at: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum ConflictOutcome {
    Set { value: Value },
    Remove,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct ConflictSource {
    pub version_id: String,
    pub mutation_id: String,
    pub actor_id: String,
    pub device_id: String,
    pub received_at: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictCandidate {
    pub choice_id: String,
    pub outcome: ConflictOutcome,
    pub sources: Vec<ConflictSource>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictingPath {
    pub path: String,
    pub candidates: Vec<ConflictCandidate>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct AutoMergedPath {
    pub path: String,
    pub outcome: ConflictOutcome,
    pub sources: Vec<ConflictSource>,
}

pub(super) struct ConflictSnapshotMaterial {
    pub conflict_id: String,
    pub entity_type: String,
    pub client_uuid: String,
    pub stable: ConflictVersionView,
    pub branches: Vec<ConflictVersionView>,
    pub conflicting: Vec<ConflictingPath>,
    pub auto_merged: Vec<AutoMergedPath>,
    restore_base: Option<ConflictVersionView>,
}

pub(super) struct ConflictSnapshotBinding<'a> {
    pub family_id: &'a str,
    pub conflict_id: &'a str,
    pub kind: &'a str,
    pub entity_type: &'a str,
    pub client_uuid: &'a str,
    pub stable_version_id: &'a str,
    pub branch_version_ids: &'a [String],
    pub receipt_key: &'a [u8],
}

pub(super) struct AuthoritativeResolution {
    pub root: Map<String, Value>,
    pub media: Vec<CausalMediaItem>,
    pub deleted: bool,
    pub restores_direct_base: bool,
}

pub(super) enum ConflictResolutionRejection {
    InvalidChoice,
    DuplicateChoice,
    IncompleteChoices,
    Store(StoreError),
}

impl From<StoreError> for ConflictResolutionRejection {
    fn from(error: StoreError) -> Self {
        Self::Store(error)
    }
}

impl ConflictSnapshotBinding<'_> {
    pub(super) fn fingerprint(&self) -> String {
        let mut parts = vec![
            SERIALIZER_CONTRACT,
            self.family_id,
            self.conflict_id,
            self.kind,
            self.entity_type,
            self.client_uuid,
            self.stable_version_id,
        ];
        parts.extend(self.branch_version_ids.iter().map(String::as_str));
        migration_content_hash(&parts)
    }

    fn receipt_id(&self) -> String {
        format!("snapshot-receipts:{}", self.conflict_id)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredSnapshotReceipt {
    token_nonce: String,
    fingerprint: String,
    expires_at_seconds: i64,
    page_ends: Vec<usize>,
    continuation_nonces: Vec<String>,
    #[serde(default)]
    choice_ids: BTreeMap<String, String>,
    #[serde(default)]
    resolution_ready: bool,
    page_digests: Vec<String>,
    integrity_tag: String,
}

/// Revoke identity-bearing snapshot credentials without losing the signed
/// terminal evidence required by existing metadata retention. No old token or
/// choice is rebound to redacted nursing content.
pub(super) fn invalidate_identity_snapshot_receipts(
    tx: &Transaction<'_>,
    receipt_key: &[u8],
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<(), StoreError> {
    let rows = {
        let mut statement = tx.prepare(
            "SELECT rowid, receipt_json FROM mutation_receipts
             WHERE family_id = ?1 AND membership_id = ?2
               AND entity_type = ?3 AND client_uuid = ?4",
        )?;
        let rows = statement
            .query_map(
                params![
                    family_id,
                    SYSTEM_RECEIPT_PRINCIPAL,
                    entity_type,
                    client_uuid
                ],
                |row| Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?)),
            )?
            .collect::<Result<Vec<_>, _>>()?;
        rows
    };
    for (rowid, json) in rows {
        let mut receipts: Vec<StoredSnapshotReceipt> = serde_json::from_str(&json)?;
        if serde_json::to_string(&receipts)? != json
            || receipts.iter().any(|receipt| {
                !crate::constant_time_eq(
                    receipt.expected_integrity_tag(receipt_key).as_bytes(),
                    receipt.integrity_tag.as_bytes(),
                )
            })
        {
            return Err(StoreError::InvalidStoredPayload);
        }
        for receipt in &mut receipts {
            receipt.expires_at_seconds = 0;
            receipt.choice_ids.clear();
            receipt.integrity_tag = receipt.expected_integrity_tag(receipt_key);
        }
        tx.execute(
            "UPDATE mutation_receipts SET receipt_json = ?1 WHERE rowid = ?2",
            params![serde_json::to_string(&receipts)?, rowid],
        )?;
    }
    Ok(())
}

pub(super) fn validate_snapshot_receipts_for_retention(
    receipt_json: &str,
    receipt_key: &[u8],
    expected_fingerprint: &str,
) -> Result<(), StoreError> {
    let receipts: Vec<StoredSnapshotReceipt> = serde_json::from_str(receipt_json)?;
    let has_bound_resolution = receipts.iter().any(|receipt| {
        receipt.resolution_ready
            && crate::constant_time_eq(
                receipt.fingerprint.as_bytes(),
                expected_fingerprint.as_bytes(),
            )
    });
    if receipts.is_empty()
        || receipts.len() > MAX_RECEIPTS_PER_CONFLICT
        || serde_json::to_string(&receipts)? != receipt_json
        || !has_bound_resolution
        || receipts.iter().any(|receipt| {
            receipt.token_nonce.len() != RESOLUTION_CREDENTIAL_LENGTH
                || receipt.page_ends.is_empty()
                || receipt.page_ends.len() != receipt.page_digests.len()
                || receipt.continuation_nonces.len() != receipt.page_ends.len().saturating_sub(1)
                || receipt.page_ends.windows(2).any(|pair| pair[0] >= pair[1])
                || receipt
                    .page_digests
                    .iter()
                    .any(|digest| !is_lower_hex_sha256(digest))
                || !crate::constant_time_eq(
                    receipt.expected_integrity_tag(receipt_key).as_bytes(),
                    receipt.integrity_tag.as_bytes(),
                )
        })
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(())
}

#[cfg(test)]
pub(in crate::store) struct TestReceiptBinding<'a> {
    pub family_id: &'a str,
    pub conflict_id: &'a str,
    pub kind: &'a str,
    pub entity_type: &'a str,
    pub client_uuid: &'a str,
    pub stable_version_id: &'a str,
    pub branch_version_ids: &'a [String],
}

#[cfg(test)]
pub(in crate::store) fn test_resolution_ready_receipt_json(
    store: &super::Store,
    test_binding: TestReceiptBinding<'_>,
) -> String {
    let binding = ConflictSnapshotBinding {
        family_id: test_binding.family_id,
        conflict_id: test_binding.conflict_id,
        kind: test_binding.kind,
        entity_type: test_binding.entity_type,
        client_uuid: test_binding.client_uuid,
        stable_version_id: test_binding.stable_version_id,
        branch_version_ids: test_binding.branch_version_ids,
        receipt_key: &store.snapshot_receipt_key,
    };
    let mut receipt = StoredSnapshotReceipt {
        token_nonce: "A".repeat(RESOLUTION_CREDENTIAL_LENGTH),
        fingerprint: binding.fingerprint(),
        expires_at_seconds: i64::MAX,
        page_ends: vec![1],
        continuation_nonces: Vec::new(),
        choice_ids: BTreeMap::new(),
        resolution_ready: true,
        page_digests: vec!["0".repeat(64)],
        integrity_tag: String::new(),
    };
    receipt.integrity_tag = receipt.expected_integrity_tag(binding.receipt_key);
    serde_json::to_string(&[receipt]).expect("test receipt is canonical JSON")
}

const RESOLUTION_CREDENTIAL_LENGTH: usize = 43;

fn is_lower_hex_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn random_nonce() -> String {
    let mut bytes = [0_u8; CREDENTIAL_BYTES];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

impl StoredSnapshotReceipt {
    fn layout_binding(&self) -> String {
        serde_json::to_string(&(
            SERIALIZER_CONTRACT,
            &self.fingerprint,
            self.expires_at_seconds,
            &self.page_ends,
            &self.continuation_nonces,
            &self.choice_ids,
        ))
        .expect("receipt layout is serializable")
    }

    fn token(&self, key: &[u8]) -> String {
        crate::derive_framed_token(
            key,
            "conflict-snapshot-token",
            &[&self.token_nonce, &self.layout_binding()],
        )
    }

    fn continuation(&self, key: &[u8], page_index: usize) -> Option<String> {
        self.continuation_nonces.get(page_index).map(|nonce| {
            crate::derive_framed_token(
                key,
                "conflict-snapshot-continuation",
                &[
                    &self.token_nonce,
                    &self.layout_binding(),
                    &page_index.to_string(),
                    nonce,
                ],
            )
        })
    }

    fn expected_integrity_tag(&self, key: &[u8]) -> String {
        crate::derive_framed_token(
            key,
            "conflict-snapshot-receipt",
            &[
                &self.token_nonce,
                &self.layout_binding(),
                if self.resolution_ready {
                    "resolution-ready"
                } else {
                    "resolution-pending"
                },
                &serde_json::to_string(&self.page_digests).expect("page digests are serializable"),
            ],
        )
    }
}

fn version_view(version: &StableSnapshot) -> Result<ConflictVersionView, StoreError> {
    let base_version = match version.parents.len() {
        0 => None,
        1 => version.parents.iter().next().cloned(),
        _ => return Err(StoreError::InvalidStoredPayload),
    };
    Ok(ConflictVersionView {
        version_id: version.version_id.clone(),
        base_version,
        root: version.root.clone(),
        media: version.media.clone(),
        deleted: version.deleted_at.is_some(),
        mutation_id: version
            .mutation_id
            .clone()
            .ok_or(StoreError::InvalidStoredPayload)?,
        actor_id: version
            .actor_id
            .clone()
            .ok_or(StoreError::InvalidStoredPayload)?,
        device_id: version
            .device_id
            .clone()
            .ok_or(StoreError::InvalidStoredPayload)?,
        received_at: version
            .received_at
            .ok_or(StoreError::InvalidStoredPayload)?,
    })
}

fn source(version: &StableSnapshot) -> Result<ConflictSource, StoreError> {
    let view = version_view(version)?;
    Ok(ConflictSource {
        version_id: view.version_id,
        mutation_id: view.mutation_id,
        actor_id: view.actor_id,
        device_id: view.device_id,
        received_at: view.received_at,
    })
}

fn pointer_escape(segment: &str) -> String {
    segment.replace('~', "~0").replace('/', "~1")
}

fn root_differences(
    path: &str,
    base: Option<&Value>,
    head: Option<&Value>,
    output: &mut BTreeMap<String, ConflictOutcome>,
) -> Result<(), StoreError> {
    if base == head {
        return Ok(());
    }
    let Some(head) = head else {
        return Err(StoreError::InvalidStoredPayload);
    };
    match (base, head) {
        (Some(Value::Object(base)), Value::Object(head))
            if base.keys().any(|key| !head.contains_key(key)) =>
        {
            output.insert(
                path.to_owned(),
                ConflictOutcome::Set {
                    value: Value::Object(head.clone()),
                },
            );
        }
        (Some(Value::Object(base)), Value::Object(head)) => {
            let keys = base.keys().chain(head.keys()).collect::<BTreeSet<_>>();
            for key in keys {
                root_differences(
                    &format!("{path}/{}", pointer_escape(key)),
                    base.get(key),
                    head.get(key),
                    output,
                )?;
            }
        }
        (None, Value::Object(head)) if !head.is_empty() => {
            for (key, value) in head {
                root_differences(
                    &format!("{path}/{}", pointer_escape(key)),
                    None,
                    Some(value),
                    output,
                )?;
            }
        }
        _ => {
            output.insert(
                path.to_owned(),
                ConflictOutcome::Set {
                    value: head.clone(),
                },
            );
        }
    }
    Ok(())
}

fn version_differences(
    base: &StableSnapshot,
    head: &StableSnapshot,
) -> Result<BTreeMap<String, ConflictOutcome>, StoreError> {
    let mut differences = BTreeMap::new();
    let type_changed = base.root.get("type") != head.root.get("type");
    let root_keys = base
        .root
        .keys()
        .chain(head.root.keys())
        .filter(|key| {
            !matches!(
                key.as_str(),
                "updated_at" | "created_by_membership_id" | "observer_membership_id"
            )
        })
        .collect::<BTreeSet<_>>();
    for key in root_keys {
        if type_changed
            && matches!(
                key.as_str(),
                "end_timestamp" | "effective_wake_observation_client_uuid"
            )
            && head.root.get(key).is_none()
        {
            // These keys are conditionally forbidden/required by the selected
            // record type. A removed key travels with the complete /type source
            // view instead of being misreported as set(null)/remove. A key
            // introduced by the target type remains a typed outcome, so two
            // same-type heads can still disagree on its value.
            continue;
        }
        root_differences(
            &format!("/{}", pointer_escape(key)),
            base.root.get(key),
            head.root.get(key),
            &mut differences,
        )?;
    }

    let base_deleted = base.deleted_at.is_some();
    let head_deleted = head.deleted_at.is_some();
    if base_deleted != head_deleted {
        differences.insert(
            "/_mutation.deleted".to_owned(),
            if head_deleted {
                ConflictOutcome::Remove
            } else {
                ConflictOutcome::Set {
                    value: Value::Bool(false),
                }
            },
        );
    }

    let base_media = base
        .media
        .iter()
        .map(|item| (&item.media_uuid, item))
        .collect::<BTreeMap<_, _>>();
    let head_media = head
        .media
        .iter()
        .map(|item| (&item.media_uuid, item))
        .collect::<BTreeMap<_, _>>();
    for media_uuid in base_media
        .keys()
        .chain(head_media.keys())
        .collect::<BTreeSet<_>>()
    {
        if base_media.get(media_uuid) == head_media.get(media_uuid) {
            continue;
        }
        differences.insert(
            format!("/media/{media_uuid}"),
            match head_media.get(media_uuid) {
                Some(item) => ConflictOutcome::Set {
                    value: item.to_value(),
                },
                None => ConflictOutcome::Remove,
            },
        );
    }
    Ok(differences)
}

fn is_path_ancestor(ancestor: &str, path: &str) -> bool {
    path.strip_prefix(ancestor)
        .is_some_and(|suffix| suffix.starts_with('/'))
}

fn value_at_pointer(root: &Map<String, Value>, path: &str) -> Option<Value> {
    Value::Object(root.clone()).pointer(path).cloned()
}

fn conditional_record_shape(root: &Map<String, Value>, path: &str) -> Option<&'static str> {
    let record_type = root.get("type")?.as_str()?;
    match (path, record_type) {
        ("/effective_wake_observation_client_uuid", "sleep") => Some("sleep"),
        ("/end_timestamp", "sleep") => None,
        ("/end_timestamp", _) => Some("non_sleep"),
        _ => None,
    }
}

pub(super) fn build_conflict_snapshot(
    projection: &ConflictHeads,
) -> Result<ConflictSnapshotMaterial, StoreError> {
    let stable = projection.stable();
    let mut heads = vec![stable];
    heads.extend(
        projection
            .branch_version_ids
            .iter()
            .map(|version_id| projection.version(version_id)),
    );

    let mut changes = BTreeMap::<String, BTreeMap<String, ConflictOutcome>>::new();
    for head in &heads {
        let base_id = head
            .parents
            .iter()
            .next()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if head.parents.len() != 1 {
            return Err(StoreError::InvalidStoredPayload);
        }
        let base = projection.version(base_id);
        changes.insert(head.version_id.clone(), version_differences(base, head)?);
    }

    if projection.entity_type == "record" {
        let mut unpaired_shape_introductions = Vec::new();
        for head in &heads {
            let base = projection.direct_base(&head.version_id);
            if base.root.get("type") == head.root.get("type") {
                continue;
            }
            for (path, key) in [
                ("/end_timestamp", "end_timestamp"),
                (
                    "/effective_wake_observation_client_uuid",
                    "effective_wake_observation_client_uuid",
                ),
            ] {
                if base.root.contains_key(key) || !head.root.contains_key(key) {
                    continue;
                }
                let Some(shape) = conditional_record_shape(&head.root, path) else {
                    continue;
                };
                let peers = heads
                    .iter()
                    .filter(|candidate| {
                        conditional_record_shape(&candidate.root, path) == Some(shape)
                    })
                    .count();
                if peers < 2 {
                    unpaired_shape_introductions.push((head.version_id.clone(), path));
                }
            }
        }
        for (version_id, path) in unpaired_shape_introductions {
            changes
                .get_mut(&version_id)
                .ok_or(StoreError::InvalidStoredPayload)?
                .remove(path);
        }
    }

    let restore_base = if projection.kind == "tombstone_restore" {
        let base = projection.direct_base(&stable.version_id);
        changes.clear();
        changes.insert(
            stable.version_id.clone(),
            BTreeMap::from([("/_mutation.deleted".to_owned(), ConflictOutcome::Remove)]),
        );
        changes.insert(
            base.version_id.clone(),
            BTreeMap::from([(
                "/_mutation.deleted".to_owned(),
                ConflictOutcome::Set {
                    value: Value::Bool(false),
                },
            )]),
        );
        heads.push(base);
        Some(version_view(base)?)
    } else {
        let deleting = changes
            .values()
            .any(|paths| paths.get("/_mutation.deleted") == Some(&ConflictOutcome::Remove));
        if deleting {
            for head in &heads {
                let paths = changes
                    .get_mut(&head.version_id)
                    .ok_or(StoreError::InvalidStoredPayload)?;
                if head.deleted_at.is_none()
                    && paths.keys().any(|path| path != "/_mutation.deleted")
                {
                    paths.insert(
                        "/_mutation.deleted".to_owned(),
                        ConflictOutcome::Set {
                            value: Value::Bool(false),
                        },
                    );
                }
            }
        }
        None
    };

    // A Baby has a single avatar slot. Two different concurrent media adds
    // cannot be auto-merged as independent set members: retaining both media
    // manifests would violate the avatar cardinality even when the root path
    // choice selects only one UUID. Materialize the complete media union as
    // competing choices so the resolver can select one coherent branch.
    if projection.entity_type == "baby" {
        let media_uuids = heads
            .iter()
            .flat_map(|head| head.media.iter().map(|item| item.media_uuid.clone()))
            .collect::<BTreeSet<_>>();
        if media_uuids.len() > 1 {
            for media_uuid in media_uuids {
                let path = format!("/media/{media_uuid}");
                for head in &heads {
                    let outcome = head
                        .media
                        .iter()
                        .find(|item| item.media_uuid == media_uuid)
                        .map(|item| ConflictOutcome::Set {
                            value: item.to_value(),
                        })
                        .unwrap_or(ConflictOutcome::Remove);
                    changes
                        .get_mut(&head.version_id)
                        .ok_or(StoreError::InvalidStoredPayload)?
                        .insert(path.clone(), outcome);
                }
            }
        }
    }

    let all_paths = changes
        .values()
        .flat_map(BTreeMap::keys)
        .cloned()
        .collect::<BTreeSet<_>>();
    let normalized_paths = all_paths
        .iter()
        .filter(|path| {
            !all_paths
                .iter()
                .any(|candidate| candidate != *path && is_path_ancestor(candidate, path))
        })
        .cloned()
        .collect::<Vec<_>>();

    let mut conflicting = Vec::new();
    let mut auto_merged = Vec::new();
    for path in normalized_paths {
        let mut outcomes = BTreeMap::<String, (ConflictOutcome, Vec<ConflictSource>)>::new();
        for head in &heads {
            let Some(head_changes) = changes.get(&head.version_id) else {
                continue;
            };
            let relevant = head_changes
                .iter()
                .find(|(candidate, _)| {
                    candidate.as_str() == path || is_path_ancestor(&path, candidate)
                })
                .map(|(_, outcome)| outcome.clone());
            let Some(outcome) = relevant else {
                continue;
            };
            let outcome = if head_changes.contains_key(&path) {
                outcome
            } else {
                ConflictOutcome::Set {
                    value: value_at_pointer(&head.root, &path)
                        .ok_or(StoreError::InvalidStoredPayload)?,
                }
            };
            let key = serde_json::to_string(&outcome)?;
            outcomes
                .entry(key)
                .or_insert_with(|| (outcome, Vec::new()))
                .1
                .push(source(head)?);
        }
        for (_, sources) in outcomes.values_mut() {
            sources.sort_by(|left, right| {
                (
                    &left.mutation_id,
                    &left.actor_id,
                    &left.device_id,
                    left.received_at,
                    &left.version_id,
                )
                    .cmp(&(
                        &right.mutation_id,
                        &right.actor_id,
                        &right.device_id,
                        right.received_at,
                        &right.version_id,
                    ))
            });
        }
        if outcomes.len() == 1 {
            let (_, (outcome, sources)) = outcomes.pop_first().expect("one outcome");
            auto_merged.push(AutoMergedPath {
                path,
                outcome,
                sources,
            });
        } else if outcomes.len() > 1 {
            conflicting.push(ConflictingPath {
                path,
                candidates: outcomes
                    .into_values()
                    .map(|(outcome, sources)| ConflictCandidate {
                        choice_id: String::new(),
                        outcome,
                        sources,
                    })
                    .collect(),
            });
        }
    }

    let branches = projection
        .branch_version_ids
        .iter()
        .map(|version_id| version_view(projection.version(version_id)))
        .collect::<Result<Vec<_>, _>>()?;
    Ok(ConflictSnapshotMaterial {
        conflict_id: projection.conflict_id.clone(),
        entity_type: projection.entity_type.clone(),
        client_uuid: projection.client_uuid.clone(),
        stable: version_view(stable)?,
        branches,
        conflicting,
        auto_merged,
        restore_base,
    })
}

fn choice_binding(path: &str, candidate: &ConflictCandidate) -> Result<String, StoreError> {
    Ok(hex::encode(Sha256::digest(serde_json::to_vec(&(
        path,
        &candidate.outcome,
        &candidate.sources,
    ))?)))
}

fn apply_resolution_outcome(
    root: &mut Map<String, Value>,
    media: &mut BTreeMap<String, CausalMediaItem>,
    deleted: &mut bool,
    path: &str,
    outcome: &ConflictOutcome,
) -> Result<(), StoreError> {
    if path == "/_mutation.deleted" {
        *deleted = match outcome {
            ConflictOutcome::Set {
                value: Value::Bool(false),
            } => false,
            ConflictOutcome::Remove => true,
            _ => return Err(StoreError::InvalidStoredPayload),
        };
        return Ok(());
    }
    if let Some(media_uuid) = path.strip_prefix("/media/") {
        match outcome {
            ConflictOutcome::Set { value } => {
                let item = CausalMediaItem::from_value(value)
                    .filter(|item| item.media_uuid == media_uuid)
                    .ok_or(StoreError::InvalidStoredPayload)?;
                media.insert(media_uuid.to_owned(), item);
            }
            ConflictOutcome::Remove => {
                media.remove(media_uuid);
            }
        }
        return Ok(());
    }
    match outcome {
        ConflictOutcome::Set { value } => {
            set_path(root, path, value.clone());
            Ok(())
        }
        ConflictOutcome::Remove => Err(StoreError::InvalidStoredPayload),
    }
}

pub(super) fn authorize_snapshot_resolution(
    tx: &Transaction<'_>,
    binding: &ConflictSnapshotBinding<'_>,
    material: &ConflictSnapshotMaterial,
    snapshot_token: &str,
    choices: &[ConflictResolutionChoice],
    now: i64,
) -> Result<AuthoritativeResolution, ConflictResolutionRejection> {
    let fingerprint = binding.fingerprint();
    let receipt_id = binding.receipt_id();
    let receipts: Vec<StoredSnapshotReceipt> = super::causal::load_receipt(
        tx,
        binding.family_id,
        SYSTEM_RECEIPT_PRINCIPAL,
        binding.entity_type,
        binding.client_uuid,
        &receipt_id,
    )?
    .map(|(_, json)| serde_json::from_str(&json).map_err(StoreError::from))
    .transpose()?
    .unwrap_or_default();
    let receipt = receipts
        .iter()
        .find(|receipt| {
            crate::constant_time_eq(
                receipt.token(binding.receipt_key).as_bytes(),
                snapshot_token.as_bytes(),
            )
        })
        .ok_or(StoreError::InvalidSnapshotToken)?;
    if !crate::constant_time_eq(
        receipt
            .expected_integrity_tag(binding.receipt_key)
            .as_bytes(),
        receipt.integrity_tag.as_bytes(),
    ) {
        return Err(StoreError::InvalidStoredPayload.into());
    }
    if now >= receipt.expires_at_seconds {
        return Err(StoreError::SnapshotExpired.into());
    }
    if receipt.fingerprint != fingerprint {
        return Err(StoreError::SnapshotStale.into());
    }
    if !receipt.resolution_ready {
        return Err(ConflictResolutionRejection::IncompleteChoices);
    }

    let mut selected = BTreeMap::new();
    for choice in choices {
        if selected.insert(choice.path.as_str(), choice).is_some() {
            return Err(ConflictResolutionRejection::DuplicateChoice);
        }
    }
    let expected_paths = material
        .conflicting
        .iter()
        .map(|item| item.path.as_str())
        .collect::<BTreeSet<_>>();
    if selected.keys().any(|path| !expected_paths.contains(path)) {
        return Err(ConflictResolutionRejection::InvalidChoice);
    }
    if selected.len() != expected_paths.len() {
        return Err(ConflictResolutionRejection::IncompleteChoices);
    }

    let mut root = material.stable.root.clone();
    let mut media = material
        .stable
        .media
        .iter()
        .cloned()
        .map(|item| (item.media_uuid.clone(), item))
        .collect::<BTreeMap<_, _>>();
    let mut deleted = material.stable.deleted;
    for merged in &material.auto_merged {
        apply_resolution_outcome(
            &mut root,
            &mut media,
            &mut deleted,
            &merged.path,
            &merged.outcome,
        )?;
    }
    let mut selected_candidates = Vec::with_capacity(material.conflicting.len());
    for conflict in &material.conflicting {
        let choice = selected
            .get(conflict.path.as_str())
            .ok_or(ConflictResolutionRejection::IncompleteChoices)?;
        let candidate = conflict
            .candidates
            .iter()
            .find(|candidate| {
                choice_binding(&conflict.path, candidate)
                    .ok()
                    .and_then(|candidate_binding| receipt.choice_ids.get(&candidate_binding))
                    .is_some_and(|expected| {
                        crate::constant_time_eq(expected.as_bytes(), choice.choice_id.as_bytes())
                    })
            })
            .ok_or(ConflictResolutionRejection::InvalidChoice)?;
        apply_resolution_outcome(
            &mut root,
            &mut media,
            &mut deleted,
            &conflict.path,
            &candidate.outcome,
        )?;
        selected_candidates.push((&conflict.path, candidate));
    }
    if binding.kind == "tombstone_restore" {
        let base = material
            .restore_base
            .as_ref()
            .ok_or(StoreError::InvalidStoredPayload)?;
        let is_exact_restore_choice = material.branches.is_empty()
            && material.auto_merged.is_empty()
            && selected_candidates.len() == 1
            && selected_candidates[0].0.as_str() == "/_mutation.deleted"
            && selected_candidates[0].1.outcome
                == (ConflictOutcome::Set {
                    value: Value::Bool(false),
                })
            && selected_candidates[0].1.sources.len() == 1
            && selected_candidates[0].1.sources[0].version_id == base.version_id
            && material.stable.base_version.as_deref() == Some(base.version_id.as_str())
            && !base.deleted;
        if !is_exact_restore_choice {
            return Err(ConflictResolutionRejection::InvalidChoice);
        }
        return Ok(AuthoritativeResolution {
            root: base.root.clone(),
            media: base.media.clone(),
            deleted: false,
            restores_direct_base: true,
        });
    }
    Ok(AuthoritativeResolution {
        root,
        media: media.into_values().collect(),
        deleted,
        restores_direct_base: false,
    })
}

fn render_page(
    receipt: &StoredSnapshotReceipt,
    material: &ConflictSnapshotMaterial,
    receipt_key: &[u8],
    snapshot_token: &str,
    page_index: usize,
) -> Result<ConflictDetailPage, StoreError> {
    let end = *receipt
        .page_ends
        .get(page_index)
        .ok_or(StoreError::InvalidSnapshotToken)?;
    let start = page_index
        .checked_sub(1)
        .and_then(|previous| receipt.page_ends.get(previous).copied())
        .unwrap_or(0);
    if start > end || end > material.branches.len() {
        return Err(StoreError::InvalidStoredPayload);
    }
    let continuation = receipt.continuation(receipt_key, page_index);
    let mut conflicting = material.conflicting.clone();
    for item in &mut conflicting {
        for candidate in &mut item.candidates {
            candidate.choice_id = receipt
                .choice_ids
                .get(&choice_binding(&item.path, candidate)?)
                .cloned()
                .ok_or(StoreError::InvalidStoredPayload)?;
        }
    }
    Ok(ConflictDetailPage {
        contract: "conflict_snapshot_v2",
        conflict_id: material.conflict_id.clone(),
        entity_type: material.entity_type.clone(),
        client_uuid: material.client_uuid.clone(),
        snapshot_token: snapshot_token.to_owned(),
        expires_at: receipt.expires_at_seconds.saturating_mul(1_000),
        stable: material.stable.clone(),
        branches: material.branches[start..end].to_vec(),
        conflicting,
        auto_merged: material.auto_merged.clone(),
        page_index,
        complete: continuation.is_none(),
        continuation,
    })
}

fn checked_page(
    receipt: &StoredSnapshotReceipt,
    material: &ConflictSnapshotMaterial,
    receipt_key: &[u8],
    snapshot_token: &str,
    fingerprint: &str,
    now: i64,
    page_index: usize,
) -> Result<ConflictDetailPage, StoreError> {
    if !crate::constant_time_eq(
        receipt.expected_integrity_tag(receipt_key).as_bytes(),
        receipt.integrity_tag.as_bytes(),
    ) {
        return Err(StoreError::InvalidStoredPayload);
    }
    if !crate::constant_time_eq(
        receipt.token(receipt_key).as_bytes(),
        snapshot_token.as_bytes(),
    ) {
        return Err(StoreError::InvalidSnapshotToken);
    }
    if now >= receipt.expires_at_seconds {
        return Err(StoreError::SnapshotExpired);
    }
    if receipt.fingerprint != fingerprint {
        return Err(StoreError::SnapshotStale);
    }
    let result = render_page(receipt, material, receipt_key, snapshot_token, page_index)?;
    let encoded = serde_json::to_vec(&result)?;
    if encoded.len() > MAX_ENCODED_PAGE_BYTES {
        return Err(StoreError::ConflictSnapshotPageTooLarge);
    }
    if receipt.page_digests.get(page_index) != Some(&hex::encode(Sha256::digest(&encoded))) {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(result)
}

fn plan_receipt(
    material: &ConflictSnapshotMaterial,
    binding: &ConflictSnapshotBinding<'_>,
    now: i64,
) -> Result<StoredSnapshotReceipt, StoreError> {
    let mut receipt = StoredSnapshotReceipt {
        token_nonce: random_nonce(),
        fingerprint: binding.fingerprint(),
        expires_at_seconds: now.saturating_add(SNAPSHOT_RECEIPT_TTL_SECONDS),
        page_ends: vec![],
        continuation_nonces: vec![],
        choice_ids: material
            .conflicting
            .iter()
            .flat_map(|item| {
                item.candidates
                    .iter()
                    .map(|candidate| choice_binding(&item.path, candidate))
            })
            .collect::<Result<BTreeSet<_>, _>>()?
            .into_iter()
            .map(|binding| (binding, random_nonce()))
            .collect(),
        resolution_ready: false,
        page_digests: vec![],
        integrity_tag: String::new(),
    };
    if material.branches.is_empty() {
        receipt.page_ends.push(0);
    } else {
        let mut offset = 0;
        while offset < material.branches.len() {
            let page_index = receipt.page_ends.len();
            receipt.page_ends.push(offset);
            receipt.continuation_nonces.push(random_nonce());
            let mut end = offset;
            while end < material.branches.len() && end - offset < MAX_BRANCHES_PER_PAGE {
                receipt.page_ends[page_index] = end + 1;
                if end + 1 == material.branches.len() {
                    receipt.continuation_nonces.truncate(page_index);
                }
                let token = receipt.token(binding.receipt_key);
                let candidate =
                    render_page(&receipt, material, binding.receipt_key, &token, page_index)?;
                if serde_json::to_vec(&candidate)?.len() > MAX_ENCODED_PAGE_BYTES {
                    break;
                }
                end += 1;
            }
            if end == offset {
                return Err(StoreError::ConflictSnapshotPageTooLarge);
            }
            receipt.page_ends[page_index] = end;
            if end < material.branches.len() && receipt.continuation_nonces.len() == page_index {
                receipt.continuation_nonces.push(random_nonce());
            }
            offset = end;
        }
    }
    let token = receipt.token(binding.receipt_key);
    receipt.page_digests = (0..receipt.page_ends.len())
        .map(|page_index| {
            let page = render_page(&receipt, material, binding.receipt_key, &token, page_index)?;
            Ok(hex::encode(Sha256::digest(serde_json::to_vec(&page)?)))
        })
        .collect::<Result<_, StoreError>>()?;
    receipt.resolution_ready = receipt.page_ends.len() == 1;
    receipt.integrity_tag = receipt.expected_integrity_tag(binding.receipt_key);
    Ok(receipt)
}

fn save_receipts(
    tx: &Transaction<'_>,
    binding: &ConflictSnapshotBinding<'_>,
    receipt_id: &str,
    receipts: &[StoredSnapshotReceipt],
    now: i64,
) -> Result<(), StoreError> {
    let receipt_json = serde_json::to_string(receipts)?;
    let updated = tx.execute(
        "UPDATE mutation_receipts
         SET content_hash = ?1, stable_version_id = ?2, receipt_json = ?3, created_at = ?4
         WHERE family_id = ?5 AND membership_id = ?6 AND entity_type = ?7
           AND client_uuid = ?8 AND mutation_id = ?9",
        params![
            binding.fingerprint(),
            binding.stable_version_id,
            receipt_json,
            now,
            binding.family_id,
            SYSTEM_RECEIPT_PRINCIPAL,
            binding.entity_type,
            binding.client_uuid,
            receipt_id,
        ],
    )?;
    if updated == 0 {
        tx.execute(
            "INSERT INTO mutation_receipts(
                family_id, membership_id, entity_type, client_uuid, mutation_id,
                content_hash, status, stable_version_id, conflict_id, receipt_json, created_at
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'accepted', ?7, ?8, ?9, ?10)",
            params![
                binding.family_id,
                SYSTEM_RECEIPT_PRINCIPAL,
                binding.entity_type,
                binding.client_uuid,
                receipt_id,
                binding.fingerprint(),
                binding.stable_version_id,
                binding.conflict_id,
                receipt_json,
                now,
            ],
        )?;
    }
    Ok(())
}

pub(super) fn open_conflict_detail_page(
    tx: &Transaction<'_>,
    binding: ConflictSnapshotBinding<'_>,
    material: ConflictSnapshotMaterial,
    request: ConflictDetailPageRequest,
    now: i64,
) -> Result<ConflictDetailPage, StoreError> {
    let fingerprint = binding.fingerprint();
    let receipt_id = binding.receipt_id();
    let mut receipts: Vec<StoredSnapshotReceipt> = super::causal::load_receipt(
        tx,
        binding.family_id,
        SYSTEM_RECEIPT_PRINCIPAL,
        binding.entity_type,
        binding.client_uuid,
        &receipt_id,
    )?
    .map(|(_, json)| serde_json::from_str(&json))
    .transpose()?
    .unwrap_or_default();

    let first_request = matches!(&request, ConflictDetailPageRequest::First);
    let receipt_count_before_expiry = receipts.len();
    if first_request {
        receipts.retain(|receipt| now < receipt.expires_at_seconds);
    }
    let expired_receipts_pruned = receipts.len() != receipt_count_before_expiry;
    let requested = match request {
        ConflictDetailPageRequest::First => receipts
            .iter()
            .rposition(|receipt| {
                receipt.fingerprint == fingerprint && now < receipt.expires_at_seconds
            })
            .map(|index| (index, receipts[index].token(binding.receipt_key), 0)),
        ConflictDetailPageRequest::SnapshotToken(token) => receipts
            .iter()
            .position(|receipt| {
                crate::constant_time_eq(
                    receipt.token(binding.receipt_key).as_bytes(),
                    token.as_bytes(),
                )
            })
            .map(|index| (index, token, 0)),
        ConflictDetailPageRequest::Continuation {
            snapshot_token,
            continuation,
        } => receipts
            .iter()
            .position(|receipt| {
                crate::constant_time_eq(
                    receipt.token(binding.receipt_key).as_bytes(),
                    snapshot_token.as_bytes(),
                )
            })
            .and_then(|index| {
                let receipt = &receipts[index];
                (0..receipt.continuation_nonces.len())
                    .find(|page_index| {
                        receipt
                            .continuation(binding.receipt_key, *page_index)
                            .is_some_and(|expected| {
                                crate::constant_time_eq(
                                    expected.as_bytes(),
                                    continuation.as_bytes(),
                                )
                            })
                    })
                    .map(|page_index| (index, snapshot_token, page_index + 1))
            }),
    };
    if let Some((receipt_index, token, page_index)) = requested {
        let result = checked_page(
            &receipts[receipt_index],
            &material,
            binding.receipt_key,
            &token,
            &fingerprint,
            now,
            page_index,
        )?;
        let became_resolution_ready = result.complete && !receipts[receipt_index].resolution_ready;
        if became_resolution_ready {
            receipts[receipt_index].resolution_ready = true;
            receipts[receipt_index].integrity_tag =
                receipts[receipt_index].expected_integrity_tag(binding.receipt_key);
        }
        if became_resolution_ready || expired_receipts_pruned {
            save_receipts(tx, &binding, &receipt_id, &receipts, now)?;
        }
        return Ok(result);
    }
    if !first_request {
        return Err(StoreError::InvalidSnapshotToken);
    }

    let receipt = plan_receipt(&material, &binding, now)?;
    let token = receipt.token(binding.receipt_key);
    if receipts.len() == MAX_RECEIPTS_PER_CONFLICT {
        receipts.remove(0);
    }
    receipts.push(receipt);
    save_receipts(tx, &binding, &receipt_id, &receipts, now)?;
    checked_page(
        receipts.last().expect("new receipt was appended"),
        &material,
        binding.receipt_key,
        &token,
        &fingerprint,
        now,
        0,
    )
}

#[cfg(test)]
pub(super) mod test_hook {
    use std::cell::RefCell;
    use std::collections::BTreeMap;
    use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
    use std::time::Duration;

    use rusqlite::Connection;

    const WAIT_TIMEOUT: Duration = Duration::from_secs(5);

    #[derive(Clone, Copy)]
    pub(crate) enum BusyOperation {
        Snapshot,
        Writer,
        Resolution,
    }

    thread_local! {
        static BUSY_CONTEXT: RefCell<Option<(String, BusyOperation)>> = const { RefCell::new(None) };
    }

    #[derive(Default)]
    struct State {
        snapshot_busy: usize,
        writer_busy: usize,
        resolution_busy: usize,
        resolution_entries: usize,
        resolution_releases: usize,
        projection_loaded: bool,
        released: bool,
    }

    struct Hook {
        state: Mutex<State>,
        changed: Condvar,
        timeout: Duration,
    }

    impl Hook {
        fn new(timeout: Duration) -> Self {
            Self {
                state: Mutex::new(State::default()),
                changed: Condvar::new(),
                timeout,
            }
        }
    }

    fn lock_unpoisoned<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
        mutex
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn hooks() -> &'static Mutex<BTreeMap<String, Arc<Hook>>> {
        static HOOKS: OnceLock<Mutex<BTreeMap<String, Arc<Hook>>>> = OnceLock::new();
        HOOKS.get_or_init(Default::default)
    }

    pub(crate) struct Control {
        family_id: String,
        hook: Arc<Hook>,
    }

    pub(crate) fn install(family_id: &str) -> Control {
        install_with_timeout(family_id, WAIT_TIMEOUT)
    }

    fn install_with_timeout(family_id: &str, timeout: Duration) -> Control {
        let hook = Arc::new(Hook::new(timeout));
        let previous = lock_unpoisoned(hooks()).insert(family_id.to_owned(), hook.clone());
        assert!(previous.is_none());
        Control {
            family_id: family_id.to_owned(),
            hook,
        }
    }

    fn notify(family_id: &str, update: impl FnOnce(&mut State)) {
        let hook = lock_unpoisoned(hooks()).get(family_id).cloned();
        if let Some(hook) = hook {
            let mut state = lock_unpoisoned(&hook.state);
            update(&mut state);
            hook.changed.notify_all();
        }
    }

    fn busy_handler(attempt: i32) -> bool {
        if attempt == 0 {
            BUSY_CONTEXT.with(|context| {
                if let Some((family_id, operation)) = context.borrow().as_ref() {
                    notify(family_id, |state| match operation {
                        BusyOperation::Snapshot => state.snapshot_busy += 1,
                        BusyOperation::Writer => state.writer_busy += 1,
                        BusyOperation::Resolution => state.resolution_busy += 1,
                    });
                }
            });
        }
        std::thread::sleep(Duration::from_millis(1));
        true
    }

    pub(crate) fn arm_busy_handler(
        connection: &Connection,
        family_id: &str,
        operation: BusyOperation,
    ) -> rusqlite::Result<()> {
        BUSY_CONTEXT.with(|context| {
            assert!(context
                .replace(Some((family_id.to_owned(), operation)))
                .is_none());
        });
        connection.busy_handler(Some(busy_handler))
    }

    pub(crate) fn disarm_busy_handler(connection: Option<&Connection>) -> rusqlite::Result<()> {
        BUSY_CONTEXT.with(|context| {
            context.replace(None);
        });
        match connection {
            Some(connection) => connection.busy_timeout(Duration::from_secs(10)),
            None => Ok(()),
        }
    }

    pub(crate) fn projection_loaded(family_id: &str) {
        let hook = lock_unpoisoned(hooks()).get(family_id).cloned();
        if let Some(hook) = hook {
            let mut state = lock_unpoisoned(&hook.state);
            if !state.projection_loaded {
                state.projection_loaded = true;
                hook.changed.notify_all();
                while !state.released {
                    let (next, timeout) = hook
                        .changed
                        .wait_timeout(state, hook.timeout)
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    if timeout.timed_out() {
                        drop(next);
                        panic!("snapshot hook release timed out");
                    }
                    state = next;
                }
            }
        }
    }

    pub(crate) fn resolution_entered(family_id: &str) {
        let hook = lock_unpoisoned(hooks()).get(family_id).cloned();
        if let Some(hook) = hook {
            let mut state = lock_unpoisoned(&hook.state);
            state.resolution_entries += 1;
            let entry = state.resolution_entries;
            hook.changed.notify_all();
            while state.resolution_releases < entry {
                let (next, timeout) = hook
                    .changed
                    .wait_timeout(state, hook.timeout)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if timeout.timed_out() {
                    drop(next);
                    panic!("resolution hook release timed out");
                }
                state = next;
            }
        }
    }

    impl Control {
        pub(crate) fn wait_projection(&self) {
            self.wait(|state| state.projection_loaded);
        }

        pub(crate) fn wait_snapshot_busy(&self) {
            self.wait(|state| state.snapshot_busy >= 1);
        }

        pub(crate) fn wait_writer_busy(&self) {
            self.wait(|state| state.writer_busy >= 1);
        }

        pub(crate) fn wait_resolution_busy(&self) {
            self.wait(|state| state.resolution_busy >= 1);
        }

        pub(crate) fn wait_resolution_entry(&self, count: usize) {
            self.wait(|state| state.resolution_entries >= count);
        }

        pub(crate) fn release_one_resolution(&self) {
            let mut state = lock_unpoisoned(&self.hook.state);
            state.resolution_releases += 1;
            self.hook.changed.notify_all();
        }

        fn wait(&self, ready: impl Fn(&State) -> bool) {
            let mut state = lock_unpoisoned(&self.hook.state);
            while !ready(&state) {
                let (next, timeout) = self
                    .hook
                    .changed
                    .wait_timeout(state, self.hook.timeout)
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if timeout.timed_out() {
                    drop(next);
                    panic!("SQLite busy acknowledgement timed out");
                }
                state = next;
            }
        }

        pub(crate) fn release(&self) {
            let mut state = lock_unpoisoned(&self.hook.state);
            state.released = true;
            state.resolution_releases = usize::MAX;
            self.hook.changed.notify_all();
        }
    }

    impl Drop for Control {
        fn drop(&mut self) {
            self.release();
            lock_unpoisoned(hooks()).remove(&self.family_id);
        }
    }

    #[test]
    fn timeout_cleanup_allows_the_same_family_hook_to_be_reinstalled() {
        let family_id = "snapshot-hook-timeout-family";
        let control = install_with_timeout(family_id, Duration::from_millis(1));
        let timed_out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            control.wait_projection();
        }));
        assert!(timed_out.is_err());
        drop(control);

        let replacement = install_with_timeout(family_id, Duration::from_millis(1));
        replacement.release();
    }
}
