//! Causal commit / conflict detail / resolution (wire §5–§9).
//!
//! Public Store façade methods are the only observed seam. No neighbor
//! adjudication runs on this path.

#![allow(clippy::too_many_arguments)]

use std::collections::{BTreeMap, BTreeSet};

use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use serde::Serialize;
use serde_json::{Map, Value};
use uuid::Uuid;

use crate::model::{validate_causal_root, validate_causal_root_shape, Entity};

use super::bundles::validate_canonical_package_ingress;
use super::causal_admission::admit_new_branch;
use super::causal_media_staging::{
    claim_manifest, verify_consumed_manifest_bytes, CausalMediaReceiptClaimError,
};
use super::causal_merge::{mutation_content_hash, three_way_merge, CausalMediaItem, MergeDecision};
use super::conflict_snapshots::{
    authorize_snapshot_resolution, build_conflict_snapshot, open_conflict_detail_page,
    ConflictDetailPageRequest, ConflictResolutionRejection, ConflictSnapshotBinding,
};
use super::{migration_content_hash, CausalCommitSaturation, Principal, Store, StoreError};

/// Wire §7: at most 32 conflict_summary entries per ordinary pull page.
pub(crate) const MAX_CONFLICT_SUMMARIES_PER_PAGE: usize = 32;
const MAX_RESOLUTION_CHOICES: usize = 64;
const RESOLUTION_TOKEN_LENGTH: usize = 43;
const MAX_RESOLUTION_PATH_BYTES: usize = 1_024;

/// Closed set of versioned entity types for the causal path.
const CAUSAL_ENTITY_TYPES: &[&str] = &[
    "baby",
    "record",
    "care_plan",
    "custom_item",
    "wake_observation",
];

pub const MAX_CAUSAL_UNITS: usize = 64;
pub(super) const VERSION_PROVENANCE_PRINCIPAL: &str = "__version_provenance_v2__";

#[derive(Debug, Clone)]
pub struct CausalMutation {
    pub mutation_id: String,
    pub base_version: Option<String>,
    pub entity_type: String,
    pub client_uuid: String,
    pub root: Map<String, Value>,
    pub media: Vec<CausalMediaItem>,
    pub deleted: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct CausalUnitResult {
    pub status: String,
    pub mutation_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stable_version_id: Option<String>,
    #[serde(default)]
    pub stable_root: Map<String, Value>,
    #[serde(default)]
    pub stable_media: Vec<CausalMediaItem>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stable_deleted_at: Option<i64>,
    pub request_hash: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub replay: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub branch_version_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conflict_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conflicting_paths: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct CausalCommitResult {
    pub results: Vec<CausalUnitResult>,
}

pub(crate) struct DurableCausalCommit {
    family_id: String,
    result: CausalCommitResult,
    promotion_manifests: Vec<Vec<CausalMediaItem>>,
}

impl DurableCausalCommit {
    pub(crate) fn requires_media_promotion(&self) -> bool {
        self.promotion_manifests
            .iter()
            .any(|manifest| !manifest.is_empty())
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictSummary {
    pub conflict_id: String,
    pub entity_type: String,
    pub client_uuid: String,
    pub stable_version_id: String,
    pub branch_version_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, serde::Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ConflictResolutionChoice {
    pub path: String,
    pub choice_id: String,
}

#[derive(Debug, Clone)]
pub struct ResolveConflictInput {
    pub snapshot_token: String,
    pub resolution_mutation_id: String,
    pub choices: Vec<ConflictResolutionChoice>,
}

impl ResolveConflictInput {
    fn validation_code(&self) -> Option<&'static str> {
        let is_framed_token = |value: &str| {
            value.len() == RESOLUTION_TOKEN_LENGTH
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
        };
        let canonical_mutation_id = Uuid::parse_str(&self.resolution_mutation_id)
            .is_ok_and(|id| id.to_string() == self.resolution_mutation_id);
        let choices_are_sorted = self
            .choices
            .windows(2)
            .all(|pair| pair[0].path <= pair[1].path);
        if !is_framed_token(&self.snapshot_token)
            || !canonical_mutation_id
            || self.choices.len() > MAX_RESOLUTION_CHOICES
            || !choices_are_sorted
            || self.choices.iter().any(|choice| {
                choice.path.is_empty()
                    || choice.path.len() > MAX_RESOLUTION_PATH_BYTES
                    || !choice.path.starts_with('/')
                    || !is_framed_token(&choice.choice_id)
            })
        {
            Some("non_canonical_value")
        } else {
            None
        }
    }
}

#[derive(Debug, Clone, Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct ResolveConflictError {
    pub code: String,
    pub retryable: bool,
}

#[derive(Debug, Clone, Serialize, serde::Deserialize, PartialEq)]
pub struct ResolveConflictResult {
    pub status: String,
    pub resolution_mutation_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stable_version_id: Option<String>,
    #[serde(default, skip_serializing_if = "Map::is_empty")]
    pub stable_root: Map<String, Value>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub stable_media: Vec<CausalMediaItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub replay: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ResolveConflictError>,
}

#[derive(Debug, Clone, Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct StoredResolutionReceipt {
    request_hash: String,
    result: ResolveConflictResult,
}

// Schema 12 is the immutable 0.3.13 upgrade source. Until H28 creates schema
// 13, `conflict_choices_json` stores exactly this target receipt and the two
// `expected_*` columns store the receipt-authoritative stable/branch audit set.
// No client-supplied value/root/media is persisted or read on this path.

fn resolution_request_hash(input: &ResolveConflictInput) -> Result<String, StoreError> {
    // validation_code enforces the wire's canonical path order before hashing.
    let choices_json = serde_json::to_string(&input.choices)?;
    Ok(migration_content_hash(&[
        "choice-only-resolution-v2",
        &input.snapshot_token,
        &input.resolution_mutation_id,
        &choices_json,
    ]))
}

fn rejected_resolution(input: &ResolveConflictInput, code: &str) -> ResolveConflictResult {
    ResolveConflictResult {
        status: "rejected".to_owned(),
        resolution_mutation_id: input.resolution_mutation_id.clone(),
        stable_version_id: None,
        stable_root: Map::new(),
        stable_media: vec![],
        replay: None,
        error: Some(ResolveConflictError {
            code: code.to_owned(),
            retryable: false,
        }),
    }
}

#[derive(Debug, Clone)]
pub(super) struct StableSnapshot {
    pub(super) version_id: String,
    pub(super) root: Map<String, Value>,
    pub(super) media: Vec<CausalMediaItem>,
    pub(super) deleted_at: Option<i64>,
    pub(super) updated_at: i64,
    pub(super) mutation_id: Option<String>,
    pub(super) parents: BTreeSet<String>,
    pub(super) actor_id: Option<String>,
    pub(super) device_id: Option<String>,
    pub(super) received_at: Option<i64>,
}

pub(super) struct ConflictHeads {
    pub(super) conflict_id: String,
    pub(super) entity_type: String,
    pub(super) client_uuid: String,
    pub(super) stable_version_id: String,
    pub(super) kind: String,
    pub(super) branch_version_ids: Vec<String>,
    pub(super) versions: BTreeMap<String, StableSnapshot>,
}

impl ConflictHeads {
    pub(super) fn version(&self, version_id: &str) -> &StableSnapshot {
        self.versions
            .get(version_id)
            .expect("validated conflict version")
    }

    pub(super) fn stable(&self) -> &StableSnapshot {
        self.version(&self.stable_version_id)
    }

    pub(super) fn direct_base(&self, version_id: &str) -> &StableSnapshot {
        let parent = self
            .version(version_id)
            .parents
            .iter()
            .next()
            .expect("validated direct base");
        self.version(parent)
    }
}

fn is_causal_type(entity_type: &str) -> bool {
    CAUSAL_ENTITY_TYPES.contains(&entity_type)
}

fn sort_media(media: &mut [CausalMediaItem]) {
    media.sort_by(|a, b| a.media_uuid.cmp(&b.media_uuid));
}

fn media_sorted(mut media: Vec<CausalMediaItem>) -> Vec<CausalMediaItem> {
    sort_media(&mut media);
    media
}

fn parse_media_json(raw: &str) -> Result<Vec<CausalMediaItem>, StoreError> {
    serde_json::from_str::<Vec<String>>(raw)?
        .into_iter()
        .map(|raw| serde_json::from_str::<Value>(&raw))
        .map(|value| CausalMediaItem::from_value(&value?).ok_or(StoreError::InvalidStoredPayload))
        .collect()
}

fn rejected(
    mutation_id: &str,
    request_hash: &str,
    code: &str,
    reason: &str,
    stable: Option<&StableSnapshot>,
) -> CausalUnitResult {
    CausalUnitResult {
        status: "rejected".to_owned(),
        mutation_id: mutation_id.to_owned(),
        stable_version_id: stable.map(|s| s.version_id.clone()),
        stable_root: stable.map(|s| s.root.clone()).unwrap_or_default(),
        stable_media: stable
            .map(|s| s.media.clone())
            .unwrap_or_default()
            .into_iter()
            .collect::<Vec<_>>(),
        stable_deleted_at: stable.and_then(|s| s.deleted_at),
        request_hash: request_hash.to_owned(),
        replay: false,
        branch_version_id: None,
        conflict_id: None,
        code: Some(code.to_owned()),
        conflicting_paths: None,
        reason: Some(reason.to_owned()),
    }
}

fn load_stable(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<Option<StableSnapshot>, StoreError> {
    let head: Option<String> = tx
        .query_row(
            "SELECT version_id FROM entity_stable_heads
             WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3",
            params![family_id, entity_type, client_uuid],
            |row| row.get(0),
        )
        .optional()?;
    let Some(version_id) = head else {
        // Fallback: entity projection without version head (pre-causal LWW row).
        // Causal path treats absence of head as no stable version.
        return Ok(None);
    };
    load_version(tx, family_id, &version_id)
}

fn load_version(
    tx: &Transaction<'_>,
    family_id: &str,
    version_id: &str,
) -> Result<Option<StableSnapshot>, StoreError> {
    Ok(load_validated_version(tx, family_id, version_id)?.map(|(_, _, snapshot)| snapshot))
}

pub(super) fn load_validated_version(
    tx: &Transaction<'_>,
    family_id: &str,
    version_id: &str,
) -> Result<Option<(String, String, StableSnapshot)>, StoreError> {
    let mut statement = tx.prepare(
        "SELECT v.version_id, v.entity_type, v.client_uuid, v.payload_json,
                    v.content_hash, v.updated_at, v.deleted_at, v.mutation_id, v.origin,
                (SELECT json_group_array(parent_version_id) FROM (
                    SELECT parent_version_id FROM entity_version_parents
                     WHERE family_id = v.family_id AND version_id = v.version_id
                     ORDER BY parent_version_id COLLATE BINARY)),
                (SELECT json_group_array(media_payload_json) FROM (
                    SELECT media_payload_json FROM entity_version_media
                     WHERE family_id = v.family_id AND version_id = v.version_id
                     ORDER BY media_uuid COLLATE BINARY)),
                (SELECT json_group_array(media_uuid) FROM (
                    SELECT media_uuid FROM entity_version_media
                     WHERE family_id = v.family_id AND version_id = v.version_id
                     ORDER BY media_uuid COLLATE BINARY)),
                (SELECT COUNT(*) FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id),
                (SELECT json_extract(mr.receipt_json, '$.actor_id')
                   FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id),
                (SELECT json_extract(mr.receipt_json, '$.device_id')
                   FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id),
                (SELECT json_extract(mr.receipt_json, '$.received_at')
                   FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id)
         FROM entity_versions v WHERE v.family_id = ?1 AND v.version_id = ?2",
    )?;
    let mut rows = statement.query(params![family_id, version_id, VERSION_PROVENANCE_PRINCIPAL])?;
    let Some(row) = rows.next()? else {
        return Ok(None);
    };
    let version = validated_snapshot_from_row(row, None, None)?;
    if rows.next()?.is_some() {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(Some(version))
}

fn validated_snapshot_from_row(
    row: &rusqlite::Row<'_>,
    expected_entity_type: Option<&str>,
    expected_client_uuid: Option<&str>,
) -> Result<(String, String, StableSnapshot), StoreError> {
    let version_id = row.get::<_, String>(0)?;
    let entity_type = row.get::<_, String>(1)?;
    let client_uuid = row.get::<_, String>(2)?;
    if expected_entity_type.is_some_and(|expected| expected != entity_type)
        || expected_client_uuid.is_some_and(|expected| expected != client_uuid)
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    let payload_json = row.get::<_, String>(3)?;
    let mut root = serde_json::from_str::<Map<String, Value>>(&payload_json)?;
    let content_hash = row.get::<_, String>(4)?;
    let updated_at = row.get::<_, i64>(5)?;
    // Schema-12 offline-migrate copies kept updated_at on the version column only.
    // Causal load/commit require it inside the root; inject without rewriting storage.
    if !root.contains_key("updated_at") {
        root.insert("updated_at".to_owned(), Value::Number(updated_at.into()));
    }
    let deleted_at = row.get::<_, Option<i64>>(6)?;
    let mutation_id = row.get::<_, Option<String>>(7)?;
    let origin = row.get::<_, String>(8)?;
    let parents = serde_json::from_str::<BTreeSet<String>>(&row.get::<_, String>(9)?)?;
    let media_json = row.get::<_, String>(10)?;
    let media_payloads = serde_json::from_str::<Vec<String>>(&media_json)?;
    let media_ids = serde_json::from_str::<Vec<String>>(&row.get::<_, String>(11)?)?;
    let provenance_count = row.get::<_, i64>(12)?;
    let media = parse_media_json(&media_json)?;
    let snapshot = StableSnapshot {
        version_id: version_id.clone(),
        root,
        media,
        updated_at,
        deleted_at,
        mutation_id,
        parents,
        actor_id: row.get(13)?,
        device_id: row.get(14)?,
        received_at: row.get(15)?,
    };
    let media_cap = match entity_type.as_str() {
        "baby" => 1,
        "custom_item" => 0,
        _ => 3,
    };
    let legacy_hash = || {
        let updated = updated_at.to_string();
        let deleted = deleted_at
            .map(|value| value.to_string())
            .unwrap_or_default();
        let mut parts = vec![updated.as_str(), deleted.as_str(), payload_json.as_str()];
        for (id, payload) in media_ids.iter().zip(&media_payloads) {
            parts.extend([id.as_str(), payload.as_str()]);
        }
        migration_content_hash(&parts)
    };
    if media_ids.len() != media_payloads.len()
        || !validate_causal_root(&entity_type, &client_uuid, &snapshot.root)
            .is_ok_and(|canonical| canonical == snapshot.root)
        || snapshot.media.len() > media_cap
        || snapshot
            .media
            .iter()
            .any(|item| item.validate_for_entity(&entity_type).is_err())
        || provenance_count > 1
        || if origin == "migration_base" {
            snapshot.mutation_id.is_some() || content_hash != legacy_hash()
        } else {
            snapshot.mutation_id.is_none()
                || content_hash
                    != root_content_hash(
                        &snapshot.root,
                        &snapshot.media,
                        snapshot.deleted_at.is_some(),
                    )
        }
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok((entity_type, client_uuid, snapshot))
}

/// Load the complete bounded conflict graph in two statements. Both detail and
/// resolution consume this projection so neither can regress to per-head I/O.
fn load_conflict_heads(
    tx: &Transaction<'_>,
    family_id: &str,
    conflict_id: &str,
) -> Result<ConflictHeads, StoreError> {
    let metadata = tx
        .query_row(
            "SELECT c.entity_type, c.client_uuid, c.stable_version_id,
                c.kind, c.status, (SELECT json_group_array(branch_version_id) FROM (
                    SELECT branch_version_id FROM conflict_branches
                     WHERE family_id = c.family_id AND conflict_id = c.conflict_id
                     ORDER BY branch_version_id COLLATE BINARY LIMIT 65))
         FROM conflicts c WHERE c.family_id = ?1 AND c.conflict_id = ?2",
            params![family_id, conflict_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, String>(4)?,
                    row.get::<_, String>(5)?,
                ))
            },
        )
        .optional()?;
    let Some((entity_type, client_uuid, stable_version_id, kind, status, branches)) = metadata
    else {
        return Err(StoreError::ConflictNotFound);
    };
    if status != "open" {
        return Err(StoreError::ConflictNotFound);
    }
    let branch_version_ids = serde_json::from_str::<Vec<String>>(&branches)?;
    if branch_version_ids.len() > 64
        || (kind == "concurrent" && branch_version_ids.is_empty())
        || (kind == "tombstone_restore" && !branch_version_ids.is_empty())
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    let mut versions = BTreeMap::<String, StableSnapshot>::new();
    let mut statement = tx.prepare(
        "WITH heads(version_id) AS (
             SELECT stable_version_id FROM conflicts
              WHERE family_id = ?1 AND conflict_id = ?2
             UNION
             SELECT branch_version_id FROM conflict_branches
              WHERE family_id = ?1 AND conflict_id = ?2
         ), wanted(version_id) AS (
             SELECT version_id FROM heads
             UNION
             SELECT p.parent_version_id FROM entity_version_parents p
              JOIN heads h ON h.version_id = p.version_id
              WHERE p.family_id = ?1
         )
         SELECT v.version_id, v.entity_type, v.client_uuid, v.payload_json,
                v.content_hash, v.updated_at, v.deleted_at, v.mutation_id, v.origin,
                (SELECT json_group_array(parent_version_id) FROM (
                    SELECT parent_version_id FROM entity_version_parents
                     WHERE family_id = v.family_id AND version_id = v.version_id
                     ORDER BY parent_version_id COLLATE BINARY)),
                (SELECT json_group_array(media_payload_json) FROM (
                    SELECT media_payload_json FROM entity_version_media
                     WHERE family_id = v.family_id AND version_id = v.version_id
                     ORDER BY media_uuid COLLATE BINARY)),
                (SELECT json_group_array(media_uuid) FROM (
                    SELECT media_uuid FROM entity_version_media
                     WHERE family_id = v.family_id AND version_id = v.version_id
                     ORDER BY media_uuid COLLATE BINARY)),
                (SELECT COUNT(*) FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id),
                (SELECT json_extract(mr.receipt_json, '$.actor_id')
                   FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id),
                (SELECT json_extract(mr.receipt_json, '$.device_id')
                   FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id),
                (SELECT json_extract(mr.receipt_json, '$.received_at')
                   FROM mutation_receipts mr
                  WHERE mr.family_id = v.family_id
                    AND mr.membership_id = ?3
                    AND mr.entity_type = v.entity_type
                    AND mr.client_uuid = v.client_uuid
                    AND mr.mutation_id = v.version_id)
         FROM wanted w
         JOIN entity_versions v ON v.family_id = ?1 AND v.version_id = w.version_id
         ORDER BY v.version_id COLLATE BINARY",
    )?;
    let mut rows = statement.query(params![
        family_id,
        conflict_id,
        VERSION_PROVENANCE_PRINCIPAL
    ])?;
    while let Some(row) = rows.next()? {
        let (_, _, snapshot) =
            validated_snapshot_from_row(row, Some(&entity_type), Some(&client_uuid))?;
        let version_id = snapshot.version_id.clone();
        versions.insert(version_id, snapshot);
    }

    if branch_version_ids
        .iter()
        .chain(std::iter::once(&stable_version_id))
        .any(|head| !versions.contains_key(head))
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    let has_direct_base = |id: &str| {
        versions.get(id).is_some_and(|version| {
            version.parents.len() == 1 && versions.contains_key(version.parents.first().unwrap())
        })
    };
    if branch_version_ids.iter().any(|id| !has_direct_base(id)) {
        return Err(StoreError::InvalidStoredPayload);
    }
    if kind == "tombstone_restore"
        && direct_restore_base_policy(
            tx,
            family_id,
            &entity_type,
            &client_uuid,
            &stable_version_id,
        )? != DirectRestoreBasePolicy::Restorable
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(ConflictHeads {
        conflict_id: conflict_id.to_owned(),
        entity_type,
        client_uuid,
        stable_version_id,
        kind,
        branch_version_ids,
        versions,
    })
}

#[derive(Clone, Copy)]
enum TombstoneRestorePreflight {
    NotApplicable,
    Forbidden,
    Authorized(Option<&'static str>),
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum DirectRestoreBasePolicy {
    Restorable,
    Missing,
    Incomplete,
}

/// Classifies the one direct base named by a stable tombstone. This is the
/// single policy used when minting a handle and when detail/resolve consume it.
/// It deliberately loads only the tombstone and its direct parent.
fn direct_restore_base_policy(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    stable_version_id: &str,
) -> Result<DirectRestoreBasePolicy, StoreError> {
    let stable = match load_validated_version(tx, family_id, stable_version_id) {
        Ok(Some((stored_type, stored_uuid, stable)))
            if stored_type == entity_type
                && stored_uuid == client_uuid
                && stable.deleted_at.is_some() =>
        {
            stable
        }
        Ok(_) | Err(StoreError::InvalidStoredPayload) => {
            return Ok(DirectRestoreBasePolicy::Incomplete);
        }
        Err(error) => return Err(error),
    };
    if stable.parents.is_empty() {
        return Ok(DirectRestoreBasePolicy::Missing);
    }
    if stable.parents.len() != 1 {
        return Ok(DirectRestoreBasePolicy::Incomplete);
    }
    let base_version_id = stable.parents.first().expect("one parent checked");
    match load_validated_version(tx, family_id, base_version_id) {
        Ok(Some((stored_type, stored_uuid, base)))
            if stored_type == entity_type
                && stored_uuid == client_uuid
                && base.deleted_at.is_none() =>
        {
            Ok(DirectRestoreBasePolicy::Restorable)
        }
        Ok(None) => Ok(DirectRestoreBasePolicy::Missing),
        Ok(Some(_)) | Err(StoreError::InvalidStoredPayload) => {
            Ok(DirectRestoreBasePolicy::Incomplete)
        }
        Err(error) => Err(error),
    }
}

/// Authorizes before classifying the direct-base proof, so a damaged restore
/// handle cannot become an ACL oracle. The bounded loader remains authoritative
/// for canonical root/media and content hashes; no ancestor fallback exists.
fn preflight_tombstone_restore(
    tx: &Transaction<'_>,
    principal: &Principal,
    conflict_id: &str,
) -> Result<TombstoneRestorePreflight, StoreError> {
    let conflict = tx
        .query_row(
            "SELECT kind, entity_type, client_uuid, stable_version_id
               FROM conflicts
              WHERE family_id = ?1 AND conflict_id = ?2 AND status = 'open'",
            params![principal.family_id, conflict_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                ))
            },
        )
        .optional()?;
    let Some((kind, entity_type, client_uuid, stable_version_id)) = conflict else {
        return Ok(TombstoneRestorePreflight::NotApplicable);
    };
    if kind != "tombstone_restore" {
        return Ok(TombstoneRestorePreflight::NotApplicable);
    }
    let stable = tx
        .query_row(
            "SELECT entity_type, client_uuid, deleted_at
               FROM entity_versions
              WHERE family_id = ?1 AND version_id = ?2",
            params![principal.family_id, stable_version_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Option<i64>>(2)?,
                ))
            },
        )
        .optional()?;
    let stable_identity_is_complete =
        stable.is_some_and(|(stored_type, stored_uuid, deleted_at)| {
            stored_type == entity_type && stored_uuid == client_uuid && deleted_at.is_some()
        });
    if principal.role != "owner" {
        let authorized = stable_identity_is_complete
            && load_validated_version(tx, &principal.family_id, &stable_version_id)
                .ok()
                .flatten()
                .is_some_and(|(stored_type, stored_uuid, snapshot)| {
                    stored_type == entity_type
                        && stored_uuid == client_uuid
                        && authorize_resolve(principal, &entity_type, &snapshot.root).is_ok()
                });
        if !authorized {
            return Ok(TombstoneRestorePreflight::Forbidden);
        }
    }
    if !stable_identity_is_complete {
        return Ok(TombstoneRestorePreflight::Authorized(Some(
            "incomplete_restore_base",
        )));
    }
    let base_error = match direct_restore_base_policy(
        tx,
        &principal.family_id,
        &entity_type,
        &client_uuid,
        &stable_version_id,
    )? {
        DirectRestoreBasePolicy::Restorable => None,
        DirectRestoreBasePolicy::Missing => Some("missing_restore_base"),
        DirectRestoreBasePolicy::Incomplete => Some("incomplete_restore_base"),
    };
    Ok(TombstoneRestorePreflight::Authorized(base_error))
}

pub(super) fn load_receipt(
    tx: &Transaction<'_>,
    family_id: &str,
    membership_id: &str,
    entity_type: &str,
    client_uuid: &str,
    mutation_id: &str,
) -> Result<Option<(String, String)>, StoreError> {
    tx.query_row(
        "SELECT content_hash, receipt_json FROM mutation_receipts
         WHERE family_id = ?1 AND membership_id = ?2
           AND entity_type = ?3 AND client_uuid = ?4 AND mutation_id = ?5",
        params![
            family_id,
            membership_id,
            entity_type,
            client_uuid,
            mutation_id
        ],
        |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
    )
    .optional()
    .map_err(StoreError::from)
}

pub(super) fn advance_rev(tx: &Transaction<'_>, family_id: &str) -> Result<i64, StoreError> {
    tx.execute(
        "UPDATE family_meta SET rev = rev + 1 WHERE family_id = ?1",
        params![family_id],
    )?;
    let rev: i64 = tx.query_row(
        "SELECT rev FROM family_meta WHERE family_id = ?1",
        params![family_id],
        |row| row.get(0),
    )?;
    Ok(rev)
}

fn upsert_entity_projection(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    updated_at: i64,
    deleted_at: Option<i64>,
    root: &Map<String, Value>,
    rev: i64,
) -> Result<(), StoreError> {
    // `updated_at` is part of the causal root/version hash but the ordinary
    // entity projection owns it as a column. Keeping a duplicate payload key
    // violates the shared canonical entity validator on restart.
    let mut payload = root.clone();
    payload.remove("updated_at");
    tx.execute(
        "INSERT INTO entities(
            family_id, entity_type, client_uuid, updated_at,
            deleted_at, payload_json, rev
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
        ON CONFLICT(family_id, entity_type, client_uuid) DO UPDATE SET
            updated_at = excluded.updated_at,
            deleted_at = excluded.deleted_at,
            payload_json = excluded.payload_json,
            rev = excluded.rev",
        params![
            family_id,
            entity_type,
            client_uuid,
            updated_at,
            deleted_at,
            serde_json::to_string(&payload)?,
            rev
        ],
    )?;
    super::source_relations::project_record_eligibility(
        tx,
        family_id,
        entity_type,
        client_uuid,
        deleted_at,
        root,
    )?;
    Ok(())
}

fn insert_version(
    tx: &Transaction<'_>,
    family_id: &str,
    version_id: &str,
    entity_type: &str,
    client_uuid: &str,
    updated_at: i64,
    deleted_at: Option<i64>,
    root: &Map<String, Value>,
    content_hash: &str,
    mutation_id: Option<&str>,
    origin: &str,
    created_at: i64,
    parents: &[String],
    media: &[CausalMediaItem],
) -> Result<(), StoreError> {
    tx.execute(
        "INSERT INTO entity_versions(
            family_id, version_id, entity_type, client_uuid,
            updated_at, deleted_at, payload_json, content_hash,
            mutation_id, origin, created_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
        params![
            family_id,
            version_id,
            entity_type,
            client_uuid,
            updated_at,
            deleted_at,
            serde_json::to_string(root)?,
            content_hash,
            mutation_id,
            origin,
            created_at
        ],
    )?;
    for parent in parents {
        tx.execute(
            "INSERT INTO entity_version_parents(family_id, version_id, parent_version_id)
             VALUES (?1, ?2, ?3)",
            params![family_id, version_id, parent],
        )?;
    }
    for item in media {
        let payload = item.to_value();
        let media_hash = mutation_content_hash(
            "media",
            &item.media_uuid,
            None,
            false,
            payload.as_object().unwrap_or(&Map::new()),
            &[],
        );
        tx.execute(
            "INSERT INTO entity_version_media(
                family_id, version_id, media_uuid, media_payload_json, content_hash
            ) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                family_id,
                version_id,
                item.media_uuid,
                serde_json::to_string(&payload)?,
                media_hash
            ],
        )?;
    }
    Ok(())
}

fn set_stable_head(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    version_id: &str,
) -> Result<(), StoreError> {
    tx.execute(
        "INSERT INTO entity_stable_heads(family_id, entity_type, client_uuid, version_id)
         VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(family_id, entity_type, client_uuid) DO UPDATE SET
            version_id = excluded.version_id",
        params![family_id, entity_type, client_uuid, version_id],
    )?;
    Ok(())
}

/// Project stable_media into pull-visible `entities` type=media rows (and
/// `media_publications`) so ordinary pull co-groups still discover attachments.
/// Tombstones live media for this root that left the stable set.
fn project_stable_media(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    media: &[CausalMediaItem],
    updated_at: i64,
    root_deleted: bool,
    tombstone_at: i64,
) -> Result<(), StoreError> {
    let prior = load_live_associated_media_uuids(tx, family_id, entity_type, client_uuid)?;
    let keep: BTreeSet<String> = if root_deleted {
        BTreeSet::new()
    } else {
        media.iter().map(|item| item.media_uuid.clone()).collect()
    };

    if !root_deleted {
        for item in media {
            let payload = media_entity_payload(entity_type, client_uuid, item)?;
            // Association is immutable once a live media row exists.
            if let Some(existing) = load_media_entity_row(tx, family_id, &item.media_uuid)? {
                if existing.kind.as_str() != expected_media_kind(entity_type, &item.role)? {
                    return Err(StoreError::ImmutableMediaAssociation);
                }
                if super::media_association_owner(&existing.payload)?
                    != Some((entity_type, client_uuid))
                {
                    return Err(StoreError::ImmutableMediaAssociation);
                }
            }
            let rev = advance_rev(tx, family_id)?;
            upsert_entity_projection(
                tx,
                family_id,
                "media",
                &item.media_uuid,
                updated_at,
                None,
                &payload,
                rev,
            )?;
        }
    }

    for media_uuid in prior {
        if keep.contains(&media_uuid) {
            continue;
        }
        let rev = advance_rev(tx, family_id)?;
        // Preserve last known payload; only stamp tombstone + rev for pull peers.
        tx.execute(
            "UPDATE entities
             SET deleted_at = ?1, rev = ?2
             WHERE family_id = ?3 AND entity_type = 'media' AND client_uuid = ?4
               AND deleted_at IS NULL",
            params![tombstone_at, rev, family_id, media_uuid],
        )?;
        tx.execute(
            "DELETE FROM media_publications
             WHERE family_id = ?1 AND media_uuid = ?2",
            params![family_id, media_uuid],
        )?;
    }
    Ok(())
}

fn expected_media_kind(entity_type: &str, role: &str) -> Result<&'static str, StoreError> {
    match (entity_type, role) {
        ("baby", "avatar") => Ok("avatar"),
        ("record", "log") => Ok("log"),
        // Care-plan log media uses role `plan` on the causal manifest, kind `log` on entities.
        ("care_plan", "plan") => Ok("log"),
        ("wake_observation", "wake") => Ok("wake"),
        _ => Err(StoreError::InvalidStoredPayload),
    }
}

fn media_entity_payload(
    entity_type: &str,
    client_uuid: &str,
    item: &CausalMediaItem,
) -> Result<Map<String, Value>, StoreError> {
    let kind = expected_media_kind(entity_type, &item.role)?;
    let mut payload = Map::new();
    payload.insert("kind".to_owned(), Value::String(kind.to_owned()));
    let (record, baby, care_plan) = match entity_type {
        "baby" => (None, Some(client_uuid), None),
        "record" => (Some(client_uuid), None, None),
        "care_plan" => (None, None, Some(client_uuid)),
        "wake_observation" => (Some(client_uuid), None, None),
        _ => return Err(StoreError::InvalidStoredPayload),
    };
    payload.insert(
        "record_client_uuid".to_owned(),
        record
            .map(|id| Value::String(id.to_owned()))
            .unwrap_or(Value::Null),
    );
    payload.insert(
        "baby_client_uuid".to_owned(),
        baby.map(|id| Value::String(id.to_owned()))
            .unwrap_or(Value::Null),
    );
    payload.insert(
        "care_plan_client_uuid".to_owned(),
        care_plan
            .map(|id| Value::String(id.to_owned()))
            .unwrap_or(Value::Null),
    );
    payload.insert("mime".to_owned(), Value::String(item.mime.clone()));
    payload.insert(
        "width".to_owned(),
        item.width
            .map(|w| Value::Number(w.into()))
            .unwrap_or(Value::Null),
    );
    payload.insert(
        "height".to_owned(),
        item.height
            .map(|h| Value::Number(h.into()))
            .unwrap_or(Value::Null),
    );
    payload.insert("byte_size".to_owned(), Value::Number(item.byte_size.into()));
    Ok(payload)
}

struct LiveMediaRow {
    kind: String,
    payload: Map<String, Value>,
}

fn load_media_entity_row(
    tx: &Transaction<'_>,
    family_id: &str,
    media_uuid: &str,
) -> Result<Option<LiveMediaRow>, StoreError> {
    let row: Option<(String, Option<i64>)> = tx
        .query_row(
            "SELECT payload_json, deleted_at FROM entities
             WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
            params![family_id, media_uuid],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    match row {
        None | Some((_, Some(_))) => Ok(None),
        Some((payload_json, None)) => {
            let payload: Map<String, Value> = serde_json::from_str(&payload_json)?;
            let kind = payload
                .get("kind")
                .and_then(Value::as_str)
                .ok_or(StoreError::InvalidStoredPayload)?
                .to_owned();
            Ok(Some(LiveMediaRow { kind, payload }))
        }
    }
}

fn load_live_associated_media_uuids(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<BTreeSet<String>, StoreError> {
    let (kind, field) = match entity_type {
        "baby" => ("avatar", "baby_client_uuid"),
        "record" => ("log", "record_client_uuid"),
        "care_plan" => ("log", "care_plan_client_uuid"),
        "wake_observation" => ("wake", "record_client_uuid"),
        // custom_item and unknown roots never own media associations.
        _ => return Ok(BTreeSet::new()),
    };
    let sql = format!(
        "SELECT client_uuid FROM entities
         WHERE family_id = ?1
           AND entity_type = 'media'
           AND deleted_at IS NULL
           AND json_extract(payload_json, '$.kind') = ?2
           AND json_extract(payload_json, '$.{field}') = ?3"
    );
    let mut statement = tx.prepare(&sql)?;
    let rows = statement
        .query_map(params![family_id, kind, client_uuid], |row| {
            row.get::<_, String>(0)
        })?
        .collect::<Result<BTreeSet<_>, _>>()?;
    Ok(rows)
}

fn save_receipt(
    tx: &Transaction<'_>,
    family_id: &str,
    principal: &Principal,
    entity_type: &str,
    client_uuid: &str,
    mutation_id: &str,
    content_hash: &str,
    status: &str,
    stable_version_id: Option<&str>,
    branch_version_id: Option<&str>,
    conflict_id: Option<&str>,
    receipt: &CausalUnitResult,
    created_at: i64,
) -> Result<(), StoreError> {
    tx.execute(
        "INSERT INTO mutation_receipts(
            family_id, membership_id, entity_type, client_uuid, mutation_id,
            content_hash, status, stable_version_id, branch_version_id,
            conflict_id, receipt_json, created_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)",
        params![
            family_id,
            principal.membership_id,
            entity_type,
            client_uuid,
            mutation_id,
            content_hash,
            status,
            stable_version_id,
            branch_version_id,
            conflict_id,
            serde_json::to_string(receipt)?,
            created_at
        ],
    )?;
    let version_id = branch_version_id.or(stable_version_id);
    if let Some(version_id) = version_id {
        save_version_provenance(
            tx,
            family_id,
            principal,
            entity_type,
            client_uuid,
            mutation_id,
            status,
            conflict_id,
            version_id,
            created_at,
        )?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn save_version_provenance(
    tx: &Transaction<'_>,
    family_id: &str,
    principal: &Principal,
    entity_type: &str,
    client_uuid: &str,
    mutation_id: &str,
    status: &str,
    conflict_id: Option<&str>,
    version_id: &str,
    created_at: i64,
) -> Result<(), StoreError> {
    let version_mutation: Option<String> = tx
        .query_row(
            "SELECT mutation_id FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![family_id, version_id],
            |row| row.get(0),
        )
        .optional()?;
    if version_mutation.as_deref() != Some(mutation_id) {
        return Ok(());
    }
    let provenance = serde_json::to_string(&serde_json::json!({
        "actor_id": principal.membership_id,
        "device_id": principal.device_id,
        "received_at": created_at,
    }))?;
    tx.execute(
        "INSERT OR IGNORE INTO mutation_receipts(
            family_id, membership_id, entity_type, client_uuid, mutation_id,
            content_hash, status, stable_version_id, branch_version_id,
            conflict_id, receipt_json, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?5, NULL, ?8, ?9, ?10)",
        params![
            family_id,
            VERSION_PROVENANCE_PRINCIPAL,
            entity_type,
            client_uuid,
            version_id,
            migration_content_hash(&["version-provenance-v2", version_id]),
            status,
            conflict_id,
            provenance,
            created_at,
        ],
    )?;
    let stored: String = tx.query_row(
        "SELECT receipt_json FROM mutation_receipts
         WHERE family_id = ?1 AND membership_id = ?2
           AND entity_type = ?3 AND client_uuid = ?4 AND mutation_id = ?5",
        params![
            family_id,
            VERSION_PROVENANCE_PRINCIPAL,
            entity_type,
            client_uuid,
            version_id,
        ],
        |row| row.get(0),
    )?;
    if stored != provenance {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(())
}

/// After a delete becomes stable: reuse open concurrent conflict if any (wire §8.2);
/// only mint pure tombstone_restore when the branch set is empty / no concurrent open.
fn conflict_id_for_stable_delete(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    stable_version_id: &str,
    now: i64,
) -> Result<Option<String>, StoreError> {
    let policy =
        direct_restore_base_policy(tx, family_id, entity_type, client_uuid, stable_version_id)?;
    let existing: Option<(String, bool)> = tx
        .query_row(
            "SELECT conflict_id, EXISTS(
                    SELECT 1 FROM conflict_branches b
                     WHERE b.family_id = conflicts.family_id
                       AND b.conflict_id = conflicts.conflict_id)
               FROM conflicts
             WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3
               AND status = 'open'
             ORDER BY created_at ASC LIMIT 1",
            params![family_id, entity_type, client_uuid],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, bool>(1)?)),
        )
        .optional()?;
    if let Some((id, has_branches)) = existing {
        if !has_branches && policy != DirectRestoreBasePolicy::Restorable {
            tx.execute(
                "UPDATE conflicts SET status = 'resolved', resolved_at = ?1,
                        stable_version_id = ?2
                 WHERE family_id = ?3 AND conflict_id = ?4 AND status = 'open'",
                params![now, stable_version_id, family_id, id],
            )?;
            return Ok(None);
        }
        // Keep a real concurrent handle; otherwise normalize the empty handle
        // to the one pure-restore kind before pinning the new tombstone.
        tx.execute(
            "UPDATE conflicts SET stable_version_id = ?1,
                    kind = CASE WHEN ?2 THEN kind ELSE 'tombstone_restore' END
             WHERE family_id = ?3 AND conflict_id = ?4",
            params![stable_version_id, has_branches, family_id, id],
        )?;
        return Ok(Some(id));
    }
    if policy != DirectRestoreBasePolicy::Restorable {
        return Ok(None);
    }
    open_or_get_tombstone_conflict(
        tx,
        family_id,
        entity_type,
        client_uuid,
        stable_version_id,
        now,
    )
}

fn open_or_get_tombstone_conflict(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    stable_version_id: &str,
    now: i64,
) -> Result<Option<String>, StoreError> {
    // Idempotent: same (type, uuid, stable tombstone version) → same open conflict.
    let existing: Option<String> = tx
        .query_row(
            "SELECT conflict_id FROM conflicts
             WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3
               AND stable_version_id = ?4 AND status = 'open'
               AND kind = 'tombstone_restore'
             ORDER BY created_at ASC LIMIT 1",
            params![family_id, entity_type, client_uuid, stable_version_id],
            |row| row.get(0),
        )
        .optional()?;
    if let Some(id) = existing {
        return Ok(Some(id));
    }
    // Prefer any open conflict on this root (should not happen if caller used
    // conflict_id_for_stable_delete first).
    if let Some(id) = load_open_conflict_id(tx, family_id, entity_type, client_uuid)? {
        tx.execute(
            "UPDATE conflicts SET stable_version_id = ?1
             WHERE family_id = ?2 AND conflict_id = ?3",
            params![stable_version_id, family_id, id],
        )?;
        return Ok(Some(id));
    }
    let conflict_id = Uuid::new_v4().to_string();
    tx.execute(
        "INSERT INTO conflicts(
            family_id, conflict_id, entity_type, client_uuid,
            base_version_id, stable_version_id, status, kind, created_at, resolved_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'open', 'tombstone_restore', ?7, NULL)",
        params![
            family_id,
            conflict_id,
            entity_type,
            client_uuid,
            Option::<String>::None,
            stable_version_id,
            now
        ],
    )?;
    Ok(Some(conflict_id))
}

/// Keep open conflict.stable_version_id aligned with entity_stable_heads (CAS safety).
fn sync_open_conflict_stable_head(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    stable_version_id: &str,
) -> Result<(), StoreError> {
    tx.execute(
        "UPDATE conflicts SET stable_version_id = ?1
         WHERE family_id = ?2 AND entity_type = ?3 AND client_uuid = ?4
           AND status = 'open'",
        params![stable_version_id, family_id, entity_type, client_uuid],
    )?;
    Ok(())
}

fn open_concurrent_conflict(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    base_version_id: Option<&str>,
    stable_version_id: &str,
    branch_version_id: &str,
    now: i64,
) -> Result<String, StoreError> {
    // Prefer attaching to an existing open concurrent conflict on this root.
    let existing: Option<(String, String)> = tx
        .query_row(
            "SELECT conflict_id, kind FROM conflicts
             WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3
               AND status = 'open'
             ORDER BY created_at ASC LIMIT 1",
            params![family_id, entity_type, client_uuid],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()?;
    let conflict_id = if let Some((id, kind)) = existing {
        // Upgrade tombstone_restore handle to concurrent when a live branch attaches.
        if kind == "tombstone_restore" {
            tx.execute(
                "UPDATE conflicts SET kind = 'concurrent', stable_version_id = ?1
                 WHERE family_id = ?2 AND conflict_id = ?3",
                params![stable_version_id, family_id, id],
            )?;
        } else {
            tx.execute(
                "UPDATE conflicts SET stable_version_id = ?1
                 WHERE family_id = ?2 AND conflict_id = ?3",
                params![stable_version_id, family_id, id],
            )?;
        }
        id
    } else {
        let id = Uuid::new_v4().to_string();
        tx.execute(
            "INSERT INTO conflicts(
                family_id, conflict_id, entity_type, client_uuid,
                base_version_id, stable_version_id, status, kind, created_at, resolved_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'open', 'concurrent', ?7, NULL)",
            params![
                family_id,
                id,
                entity_type,
                client_uuid,
                base_version_id,
                stable_version_id,
                now
            ],
        )?;
        id
    };
    tx.execute(
        "INSERT OR IGNORE INTO conflict_branches(family_id, conflict_id, branch_version_id)
         VALUES (?1, ?2, ?3)",
        params![family_id, conflict_id, branch_version_id],
    )?;
    Ok(conflict_id)
}

fn stamp_root(
    root: &mut Map<String, Value>,
    entity_type: &str,
    principal: &Principal,
    previous: Option<&Map<String, Value>>,
    now_ms: i64,
) {
    // Ignore client-forged stamps; re-stamp (wire §4.0 default).
    if entity_type == "wake_observation" {
        let observer = previous
            .and_then(|p| p.get("observer_membership_id"))
            .and_then(Value::as_str)
            .map(|s| s.to_owned())
            .unwrap_or_else(|| principal.membership_id.clone());
        root.insert("observer_membership_id".to_owned(), Value::String(observer));
    } else {
        let author = previous
            .and_then(|p| p.get("created_by_membership_id"))
            .cloned()
            .filter(|v| !v.is_null())
            .unwrap_or_else(|| Value::String(principal.membership_id.clone()));
        root.insert("created_by_membership_id".to_owned(), author);
    }
    if !root.contains_key("updated_at") || root.get("updated_at").and_then(Value::as_i64).is_none()
    {
        root.insert("updated_at".to_owned(), Value::Number(now_ms.into()));
    }
}

fn authorize_mutation(
    principal: &Principal,
    entity_type: &str,
    previous: Option<&Map<String, Value>>,
) -> Result<(), StoreError> {
    match entity_type {
        "baby" if principal.role != "owner" => Err(StoreError::ForbiddenBaby),
        "custom_item" => {
            if principal.role == "owner" {
                return Ok(());
            }
            let author = previous
                .and_then(|p| p.get("created_by_membership_id"))
                .and_then(Value::as_str);
            if previous.is_none() || author == Some(principal.membership_id.as_str()) {
                Ok(())
            } else {
                Err(StoreError::ForbiddenCustomItem)
            }
        }
        "care_plan" => {
            if principal.role == "owner" {
                return Ok(());
            }
            let author = previous
                .and_then(|p| p.get("created_by_membership_id"))
                .and_then(Value::as_str);
            if previous.is_none() || author == Some(principal.membership_id.as_str()) {
                Ok(())
            } else {
                Err(StoreError::ForbiddenCarePlan)
            }
        }
        "record" => {
            // Family members may create records; edits: author or owner.
            if previous.is_none() || principal.role == "owner" {
                return Ok(());
            }
            let author = previous
                .and_then(|p| p.get("created_by_membership_id"))
                .and_then(Value::as_str);
            if author == Some(principal.membership_id.as_str()) {
                Ok(())
            } else {
                // Non-author edits of open sleep wake path retired; still allow
                // note/media via ACL? Wire keeps existing ACL — non-author record
                // edit forbidden except historical wake. Causal path: author/owner.
                Err(StoreError::ForbiddenRecord)
            }
        }
        "wake_observation" => {
            if previous.is_none() {
                return Ok(());
            }
            if principal.role == "owner" {
                return Ok(());
            }
            let observer = previous
                .and_then(|p| p.get("observer_membership_id"))
                .and_then(Value::as_str);
            if observer == Some(principal.membership_id.as_str()) {
                Ok(())
            } else {
                Err(StoreError::ForbiddenRecord)
            }
        }
        _ => Ok(()),
    }
}

fn authorize_resolve(
    principal: &Principal,
    entity_type: &str,
    stable_root: &Map<String, Value>,
) -> Result<(), StoreError> {
    if principal.role == "owner" {
        return Ok(());
    }
    match entity_type {
        // Baby is an Owner-managed profile even when the present member was
        // its historical creator before an ownership takeover/downgrade.
        "baby" => Err(StoreError::ForbiddenBaby),
        "record" | "care_plan" | "custom_item" => {
            let author = stable_root
                .get("created_by_membership_id")
                .and_then(Value::as_str);
            if author == Some(principal.membership_id.as_str()) {
                Ok(())
            } else if entity_type == "record" {
                Err(StoreError::ForbiddenRecord)
            } else if entity_type == "care_plan" {
                Err(StoreError::ForbiddenCarePlan)
            } else {
                Err(StoreError::ForbiddenCustomItem)
            }
        }
        "wake_observation" => {
            let observer = stable_root
                .get("observer_membership_id")
                .and_then(Value::as_str);
            if observer == Some(principal.membership_id.as_str()) {
                Ok(())
            } else {
                Err(StoreError::ForbiddenRecord)
            }
        }
        _ => Err(StoreError::ForbiddenRecord),
    }
}

/// Wire §4.5 `invalid_wake_timestamp`: wake must not precede SleepStart.
/// Equality is legal (`wake_timestamp >= sleep.timestamp`).
fn validate_wake_against_sleep_start(
    tx: &Transaction<'_>,
    family_id: &str,
    mutation: &CausalMutation,
) -> Result<(), &'static str> {
    let sleep_uuid = mutation
        .root
        .get("sleep_record_client_uuid")
        .and_then(Value::as_str)
        .ok_or("missing_required_field")?;
    let wake_ts = mutation
        .root
        .get("wake_timestamp")
        .and_then(Value::as_i64)
        .ok_or("missing_required_field")?;
    let sleep = load_stable(tx, family_id, "record", sleep_uuid).map_err(|_| "internal")?;
    let Some(sleep) = sleep else {
        return Err("missing_sleep_reference");
    };
    if sleep.root.get("type").and_then(Value::as_str) != Some("sleep") {
        return Err("missing_sleep_reference");
    }
    let sleep_start = sleep
        .root
        .get("timestamp")
        .and_then(Value::as_i64)
        .ok_or("missing_sleep_reference")?;
    if wake_ts < sleep_start {
        return Err("invalid_wake_timestamp");
    }
    Ok(())
}

fn validate_mutation_shape(mutation: &CausalMutation) -> Result<(), &'static str> {
    if !is_causal_type(&mutation.entity_type) {
        return Err("unsupported_entity_type");
    }
    if Uuid::parse_str(&mutation.mutation_id).is_err() {
        return Err("invalid_mutation_id");
    }
    if Uuid::parse_str(&mutation.client_uuid).is_err() {
        return Err("invalid_client_uuid");
    }
    if mutation.root.contains_key("deleted") || mutation.root.contains_key("deleted_at") {
        return Err("unknown_field");
    }
    validate_causal_root_shape(&mutation.entity_type, &mutation.root)?;
    Ok(())
}

fn validate_mutation_content(
    mutation: &CausalMutation,
) -> Result<Map<String, Value>, &'static str> {
    let canonical_root =
        validate_causal_root(&mutation.entity_type, &mutation.client_uuid, &mutation.root)
            .map_err(|_| "invalid_entity_value")?;
    // Media order independence: duplicates forbidden; wire §4.6 constraints.
    let mut seen = BTreeSet::new();
    for item in &mutation.media {
        if !seen.insert(item.media_uuid.clone()) {
            return Err("duplicate_media_uuid");
        }
        item.validate_for_entity(&mutation.entity_type)?;
    }
    if mutation.entity_type == "custom_item" && !mutation.media.is_empty() {
        return Err("media_not_allowed");
    }
    // avatar_media_uuid ∈ media when present (wire media_referential_integrity).
    if mutation.entity_type == "baby" {
        if let Some(Value::String(avatar)) = mutation.root.get("avatar_media_uuid") {
            if !mutation.media.iter().any(|m| m.media_uuid == *avatar) {
                return Err("media_referential_integrity");
            }
        }
    }
    // Role caps: avatar 0–1, log/plan/wake 0–3.
    let cap = match mutation.entity_type.as_str() {
        "baby" => 1usize,
        "record" | "care_plan" | "wake_observation" => 3,
        _ => 0,
    };
    if mutation.media.len() > cap {
        return Err("media_limit_exceeded");
    }
    Ok(canonical_root)
}

/// H23 causal CarePlan publication waits for its fulfilled fact. Legacy bundle
/// ingress keeps its historical forward-reference product flow.
fn validate_care_plan_fulfilled_record_ready(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    root: &Map<String, Value>,
) -> Result<(), StoreError> {
    if entity_type != "care_plan" {
        return Ok(());
    }
    let Some(record_uuid) = root
        .get("fulfilled_record_client_uuid")
        .and_then(Value::as_str)
    else {
        return Ok(());
    };
    if load_stable(tx, family_id, "record", record_uuid)?.is_some() {
        Ok(())
    } else {
        Err(StoreError::UnresolvedReference(
            "care_plan fulfilled_record_client_uuid does not exist".to_owned(),
        ))
    }
}

/// Bind commit metadata to the durable preimage receipt before any version,
/// projection, branch, or terminal mutation receipt is written.
fn claim_media_receipts(
    tx: &Transaction<'_>,
    principal: &Principal,
    media: &[CausalMediaItem],
    now: i64,
) -> Result<Option<&'static str>, StoreError> {
    match claim_manifest(tx, principal, media, now) {
        Ok(()) => Ok(None),
        Err(CausalMediaReceiptClaimError::Rejected(code)) => Ok(Some(code)),
        Err(CausalMediaReceiptClaimError::Store(error)) => Err(error),
    }
}

fn root_content_hash(
    root: &Map<String, Value>,
    media: &[CausalMediaItem],
    deleted: bool,
) -> String {
    mutation_content_hash("_", "_", None, deleted, root, media)
}

fn canonical_package(
    mutation: &CausalMutation,
    root: &Map<String, Value>,
    media: &[CausalMediaItem],
    now: i64,
) -> Result<Vec<Entity>, StoreError> {
    let updated_at = root
        .get("updated_at")
        .and_then(Value::as_i64)
        .ok_or(StoreError::InvalidStoredPayload)?;
    let mut payload = root.clone();
    payload.remove("updated_at");
    let deleted_at = mutation.deleted.then_some(now.saturating_mul(1_000));
    let mut package = Vec::with_capacity(1 + media.len());
    package.push(Entity {
        entity_type: mutation.entity_type.clone(),
        client_uuid: mutation.client_uuid.clone(),
        updated_at,
        deleted_at,
        payload,
    });
    for item in media {
        package.push(Entity {
            entity_type: "media".to_owned(),
            client_uuid: item.media_uuid.clone(),
            updated_at,
            deleted_at,
            payload: media_entity_payload(&mutation.entity_type, &mutation.client_uuid, item)?,
        });
    }
    Ok(package)
}

fn bump_entity_rev_only(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<i64, StoreError> {
    // Branch-only write must advance pull rev so conflict_summary is discoverable.
    let rev = advance_rev(tx, family_id)?;
    tx.execute(
        "UPDATE entities SET rev = ?1
         WHERE family_id = ?2 AND entity_type = ?3 AND client_uuid = ?4",
        params![rev, family_id, entity_type, client_uuid],
    )?;
    Ok(rev)
}

struct EvalContext<'a> {
    tx: &'a Transaction<'a>,
    principal: &'a Principal,
    now: i64,
    dry_run: bool,
    max_open_branches_per_root: usize,
}

fn batch_is_exact_replay(
    transaction: &Transaction<'_>,
    principal: &Principal,
    units: &[CausalMutation],
) -> Result<bool, StoreError> {
    for mutation in units {
        let request_hash = mutation_content_hash(
            &mutation.entity_type,
            &mutation.client_uuid,
            mutation.base_version.as_deref(),
            mutation.deleted,
            &mutation.root,
            &mutation.media,
        );
        let Some((stored_hash, _)) = load_receipt(
            transaction,
            &principal.family_id,
            &principal.membership_id,
            &mutation.entity_type,
            &mutation.client_uuid,
            &mutation.mutation_id,
        )?
        else {
            return Ok(false);
        };
        if stored_hash != request_hash {
            return Ok(false);
        }
    }
    Ok(true)
}

fn evaluate_unit(
    ctx: &EvalContext<'_>,
    mutation: &CausalMutation,
) -> Result<CausalUnitResult, StoreError> {
    let request_hash = mutation_content_hash(
        &mutation.entity_type,
        &mutation.client_uuid,
        mutation.base_version.as_deref(),
        mutation.deleted,
        &mutation.root,
        &mutation.media,
    );

    if let Err(code) = validate_mutation_shape(mutation) {
        return Ok(rejected(
            &mutation.mutation_id,
            &request_hash,
            code,
            code,
            None,
        ));
    }

    // Receipt/content-drift precedes mutable external validation. A mutation
    // already accepted keeps replaying its durable receipt even if a referenced
    // root later changes; the same id with different canonical content still
    // fails closed before reference inspection.
    if let Some((stored_hash, receipt_json)) = load_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        &ctx.principal.membership_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        &mutation.mutation_id,
    )? {
        if stored_hash != request_hash {
            return Ok(rejected(
                &mutation.mutation_id,
                &request_hash,
                "content_drift",
                "mutation_id reused with different content",
                load_stable(
                    ctx.tx,
                    &ctx.principal.family_id,
                    &mutation.entity_type,
                    &mutation.client_uuid,
                )?
                .as_ref(),
            ));
        }
        let mut receipt: CausalUnitResult = serde_json::from_str(&receipt_json)?;
        // Receipts written before the contracted commit envelope did not
        // persist tombstone evidence. The referenced immutable version is the
        // authority for upgrading that replay without consulting mutable head.
        if receipt.stable_deleted_at.is_none() {
            if let Some(version_id) = receipt.stable_version_id.as_deref() {
                receipt.stable_deleted_at =
                    load_version(ctx.tx, &ctx.principal.family_id, version_id)?
                        .and_then(|version| version.deleted_at);
            }
        }
        if ctx.dry_run && matches!(receipt.status.as_str(), "accepted" | "merged" | "branched") {
            receipt.status = "confirmed".to_owned();
        }
        receipt.request_hash = request_hash;
        receipt.replay = true;
        return Ok(receipt);
    }

    let canonical_root = match validate_mutation_content(mutation) {
        Ok(root) => root,
        Err(code) => {
            return Ok(rejected(
                &mutation.mutation_id,
                &request_hash,
                code,
                code,
                None,
            ));
        }
    };
    // Wire §4.5: first-seen wake_timestamp must be >= target SleepStart timestamp.
    if mutation.entity_type == "wake_observation" {
        if let Err(code) =
            validate_wake_against_sleep_start(ctx.tx, &ctx.principal.family_id, mutation)
        {
            return Ok(rejected(
                &mutation.mutation_id,
                &request_hash,
                code,
                code,
                None,
            ));
        }
    }

    let stable = load_stable(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
    )?;

    if let Err(err) = authorize_mutation(
        ctx.principal,
        &mutation.entity_type,
        stable.as_ref().map(|s| &s.root),
    ) {
        let code = match err {
            StoreError::ForbiddenBaby => "forbidden_baby",
            StoreError::ForbiddenCustomItem => "forbidden_custom_item",
            StoreError::ForbiddenCarePlan => "forbidden_care_plan",
            StoreError::ForbiddenRecord => "forbidden_record",
            _ => "forbidden",
        };
        return Ok(rejected(
            &mutation.mutation_id,
            &request_hash,
            code,
            code,
            stable.as_ref(),
        ));
    }

    let mut incoming_root = canonical_root;
    stamp_root(
        &mut incoming_root,
        &mutation.entity_type,
        ctx.principal,
        stable.as_ref().map(|s| &s.root),
        ctx.now.saturating_mul(1_000),
    );
    let incoming_media = media_sorted(mutation.media.clone());
    let incoming_deleted = mutation.deleted;
    let package = canonical_package(mutation, &incoming_root, &incoming_media, ctx.now)?;
    if let Err(error) = validate_care_plan_fulfilled_record_ready(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &incoming_root,
    )
    .and_then(|()| validate_canonical_package_ingress(ctx.tx, ctx.principal, &package))
    {
        let code = match error {
            StoreError::Sqlite(_)
            | StoreError::Json(_)
            | StoreError::Io(_)
            | StoreError::InvalidStoredPayload => return Err(error),
            StoreError::UnresolvedReference(_) => "invalid_reference",
            StoreError::ImmutableMediaAssociation => "media_referential_integrity",
            StoreError::PullEntityTooLarge => "root_too_large",
            _ => "invalid_entity_value",
        };
        return Ok(rejected(
            &mutation.mutation_id,
            &request_hash,
            code,
            &error.to_string(),
            stable.as_ref(),
        ));
    }

    // First create: base_version must be null.
    if stable.is_none() {
        if mutation.base_version.is_some() {
            return Ok(rejected(
                &mutation.mutation_id,
                &request_hash,
                "unknown_base_version",
                "base_version set but root has no stable head",
                None,
            ));
        }
        if incoming_deleted {
            // Creating a pure tombstone is allowed as accepted empty tombstone.
        }
        if ctx.dry_run {
            return Ok(CausalUnitResult {
                status: "publish".to_owned(),
                mutation_id: mutation.mutation_id.clone(),
                stable_version_id: None,
                stable_root: Map::new(),
                stable_media: vec![],
                stable_deleted_at: None,
                request_hash,
                replay: false,
                branch_version_id: None,
                conflict_id: None,
                code: None,
                conflicting_paths: None,
                reason: Some("authoritative_absence".to_owned()),
            });
        }
        return commit_accepted_new(
            ctx,
            mutation,
            &incoming_root,
            &incoming_media,
            &request_hash,
        );
    }

    let stable = stable.expect("stable checked");
    let base_version = match mutation.base_version.as_deref() {
        Some(v) => v.to_owned(),
        None => {
            return Ok(rejected(
                &mutation.mutation_id,
                &request_hash,
                "base_version_required",
                "base_version required when stable head exists",
                Some(&stable),
            ));
        }
    };

    // Confirmed: expectation already matches stable (content-equal).
    let stable_deleted = stable.deleted_at.is_some();
    if base_version == stable.version_id
        && leaf_equal_ignoring_stamps(&stable.root, &incoming_root)
        && media_equal(&stable.media, &incoming_media)
        && stable_deleted == incoming_deleted
    {
        if !ctx.dry_run && !incoming_deleted {
            if let Some(code) =
                claim_media_receipts(ctx.tx, ctx.principal, &incoming_media, ctx.now)?
            {
                return Ok(rejected(
                    &mutation.mutation_id,
                    &request_hash,
                    code,
                    code,
                    Some(&stable),
                ));
            }
        }
        let conflict_id = if stable_deleted {
            conflict_id_for_stable_delete(
                ctx.tx,
                &ctx.principal.family_id,
                &mutation.entity_type,
                &mutation.client_uuid,
                &stable.version_id,
                ctx.now,
            )?
        } else {
            load_open_conflict_id(
                ctx.tx,
                &ctx.principal.family_id,
                &mutation.entity_type,
                &mutation.client_uuid,
            )?
        };
        return Ok(CausalUnitResult {
            status: if ctx.dry_run {
                "confirmed".to_owned()
            } else {
                "accepted".to_owned()
            },
            mutation_id: mutation.mutation_id.clone(),
            stable_version_id: Some(stable.version_id.clone()),
            stable_root: stable.root.clone(),
            stable_media: stable.media.clone(),
            stable_deleted_at: stable.deleted_at,
            request_hash,
            replay: false,
            branch_version_id: None,
            conflict_id,
            code: None,
            conflicting_paths: None,
            reason: Some("canonical_equivalent".to_owned()),
        });
    }

    // Stale live over tombstone (wire 例 F).
    if stable_deleted && !incoming_deleted {
        let concurrent = stable.parents.contains(&base_version);
        if !concurrent {
            return Ok(rejected(
                &mutation.mutation_id,
                &request_hash,
                "stale_live_over_tombstone",
                "live mutation cannot revive settled tombstone without concurrent proof",
                Some(&stable),
            ));
        }
        // concurrent live edit after delete → branch (wire 例 J)
        let paths = vec!["/_mutation.deleted".to_owned()];
        if ctx.dry_run {
            return Ok(CausalUnitResult {
                status: "conflict_preview".to_owned(),
                mutation_id: mutation.mutation_id.clone(),
                stable_version_id: Some(stable.version_id.clone()),
                stable_root: stable.root.clone(),
                stable_media: stable.media.clone(),
                stable_deleted_at: stable.deleted_at,
                request_hash,
                replay: false,
                branch_version_id: None,
                conflict_id: None,
                code: None,
                conflicting_paths: Some(paths),
                reason: Some("would_branch".to_owned()),
            });
        }
        return branch_unit(
            ctx,
            mutation,
            &stable,
            &incoming_root,
            &incoming_media,
            &request_hash,
            &base_version,
        );
    }

    // Base is current stable → fast path accept (single-sided change).
    if base_version == stable.version_id {
        if ctx.dry_run {
            return Ok(CausalUnitResult {
                status: "publish".to_owned(),
                mutation_id: mutation.mutation_id.clone(),
                stable_version_id: Some(stable.version_id.clone()),
                stable_root: stable.root.clone(),
                stable_media: stable.media.clone(),
                stable_deleted_at: stable.deleted_at,
                request_hash,
                replay: false,
                branch_version_id: None,
                conflict_id: None,
                code: None,
                conflicting_paths: None,
                reason: Some("current_base".to_owned()),
            });
        }
        return commit_accepted_update(
            ctx,
            mutation,
            &stable,
            &incoming_root,
            &incoming_media,
            &request_hash,
        );
    }

    // Base must exist.
    let base = match load_version(ctx.tx, &ctx.principal.family_id, &base_version)? {
        Some(v) => v,
        None => {
            return Ok(rejected(
                &mutation.mutation_id,
                &request_hash,
                "unknown_base_version",
                "base_version not found",
                Some(&stable),
            ));
        }
    };

    let base_deleted = base.deleted_at.is_some();
    let decision = three_way_merge(
        &base.root,
        &base.media,
        base_deleted,
        &stable.root,
        &stable.media,
        stable_deleted,
        &incoming_root,
        &incoming_media,
        incoming_deleted,
    );

    match decision {
        MergeDecision::Identical => Ok(CausalUnitResult {
            status: if ctx.dry_run {
                "confirmed".to_owned()
            } else {
                "accepted".to_owned()
            },
            mutation_id: mutation.mutation_id.clone(),
            stable_version_id: Some(stable.version_id.clone()),
            stable_root: stable.root.clone(),
            stable_media: stable.media.clone(),
            stable_deleted_at: stable.deleted_at,
            request_hash,
            replay: false,
            branch_version_id: None,
            conflict_id: load_open_conflict_id(
                ctx.tx,
                &ctx.principal.family_id,
                &mutation.entity_type,
                &mutation.client_uuid,
            )?,
            code: None,
            conflicting_paths: None,
            reason: Some("canonical_equivalent".to_owned()),
        }),
        MergeDecision::AutoMerge {
            merged_root,
            merged_media,
            merged_deleted,
            ..
        } => {
            if ctx.dry_run {
                return Ok(CausalUnitResult {
                    status: "publish".to_owned(),
                    mutation_id: mutation.mutation_id.clone(),
                    stable_version_id: Some(stable.version_id.clone()),
                    stable_root: stable.root.clone(),
                    stable_media: stable.media.clone(),
                    stable_deleted_at: stable.deleted_at,
                    request_hash,
                    replay: false,
                    branch_version_id: None,
                    conflict_id: None,
                    code: None,
                    conflicting_paths: None,
                    reason: Some("auto_merge".to_owned()),
                });
            }
            commit_merged(
                ctx,
                mutation,
                &stable,
                &base_version,
                merged_root,
                merged_media,
                merged_deleted,
                &request_hash,
            )
        }
        MergeDecision::Conflict {
            conflicting_paths,
            auto_merged: _,
        } => {
            if ctx.dry_run {
                return Ok(CausalUnitResult {
                    status: "conflict_preview".to_owned(),
                    mutation_id: mutation.mutation_id.clone(),
                    stable_version_id: Some(stable.version_id.clone()),
                    stable_root: stable.root.clone(),
                    stable_media: stable.media.clone(),
                    stable_deleted_at: stable.deleted_at,
                    request_hash,
                    replay: false,
                    branch_version_id: None,
                    conflict_id: None,
                    code: None,
                    conflicting_paths: Some(conflicting_paths),
                    reason: Some("would_branch".to_owned()),
                });
            }
            branch_unit(
                ctx,
                mutation,
                &stable,
                &incoming_root,
                &incoming_media,
                &request_hash,
                &base_version,
            )
        }
    }
}

fn leaf_equal_ignoring_stamps(a: &Map<String, Value>, b: &Map<String, Value>) -> bool {
    use super::causal_merge::leaf_paths;
    leaf_paths(a) == leaf_paths(b)
}

fn media_equal(a: &[CausalMediaItem], b: &[CausalMediaItem]) -> bool {
    let mut aa = a.to_vec();
    let mut bb = b.to_vec();
    sort_media(&mut aa);
    sort_media(&mut bb);
    aa == bb
}

fn load_open_conflict_id(
    tx: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<Option<String>, StoreError> {
    tx.query_row(
        "SELECT conflict_id FROM conflicts
         WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3
           AND status = 'open'
         ORDER BY created_at ASC LIMIT 1",
        params![family_id, entity_type, client_uuid],
        |row| row.get(0),
    )
    .optional()
    .map_err(StoreError::from)
}

fn commit_accepted_new(
    ctx: &EvalContext<'_>,
    mutation: &CausalMutation,
    root: &Map<String, Value>,
    media: &[CausalMediaItem],
    request_hash: &str,
) -> Result<CausalUnitResult, StoreError> {
    if !mutation.deleted {
        if let Some(code) = claim_media_receipts(ctx.tx, ctx.principal, media, ctx.now)? {
            return Ok(rejected(
                &mutation.mutation_id,
                request_hash,
                code,
                code,
                None,
            ));
        }
    }
    let version_id = Uuid::new_v4().to_string();
    let updated_at = root
        .get("updated_at")
        .and_then(Value::as_i64)
        .unwrap_or(ctx.now.saturating_mul(1_000));
    let deleted_at = if mutation.deleted {
        Some(ctx.now.saturating_mul(1_000))
    } else {
        None
    };
    let content_hash = root_content_hash(root, media, mutation.deleted);
    insert_version(
        ctx.tx,
        &ctx.principal.family_id,
        &version_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        updated_at,
        deleted_at,
        root,
        &content_hash,
        Some(&mutation.mutation_id),
        "accepted",
        ctx.now,
        &[],
        media,
    )?;
    let rev = advance_rev(ctx.tx, &ctx.principal.family_id)?;
    upsert_entity_projection(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        updated_at,
        deleted_at,
        root,
        rev,
    )?;
    project_stable_media(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        media,
        updated_at,
        mutation.deleted,
        deleted_at.unwrap_or_else(|| ctx.now.saturating_mul(1_000)),
    )?;
    set_stable_head(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        &version_id,
    )?;
    let conflict_id = if mutation.deleted {
        conflict_id_for_stable_delete(
            ctx.tx,
            &ctx.principal.family_id,
            &mutation.entity_type,
            &mutation.client_uuid,
            &version_id,
            ctx.now,
        )?
    } else {
        None
    };
    let result = CausalUnitResult {
        status: "accepted".to_owned(),
        mutation_id: mutation.mutation_id.clone(),
        stable_version_id: Some(version_id.clone()),
        stable_root: root.clone(),
        stable_media: media.to_vec(),
        stable_deleted_at: deleted_at,
        request_hash: request_hash.to_owned(),
        replay: false,
        branch_version_id: None,
        conflict_id: conflict_id.clone(),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        ctx.principal,
        &mutation.entity_type,
        &mutation.client_uuid,
        &mutation.mutation_id,
        request_hash,
        "accepted",
        Some(&version_id),
        None,
        conflict_id.as_deref(),
        &result,
        ctx.now,
    )?;
    Ok(result)
}

fn commit_accepted_update(
    ctx: &EvalContext<'_>,
    mutation: &CausalMutation,
    stable: &StableSnapshot,
    root: &Map<String, Value>,
    media: &[CausalMediaItem],
    request_hash: &str,
) -> Result<CausalUnitResult, StoreError> {
    if !mutation.deleted {
        if let Some(code) = claim_media_receipts(ctx.tx, ctx.principal, media, ctx.now)? {
            return Ok(rejected(
                &mutation.mutation_id,
                request_hash,
                code,
                code,
                Some(stable),
            ));
        }
    }
    let version_id = Uuid::new_v4().to_string();
    let updated_at = root
        .get("updated_at")
        .and_then(Value::as_i64)
        .unwrap_or(ctx.now.saturating_mul(1_000));
    // Wire §4.0: accepted uses max(mutation, previous).
    let updated_at = updated_at.max(stable.updated_at);
    let mut root = root.clone();
    root.insert("updated_at".to_owned(), Value::Number(updated_at.into()));
    let deleted_at = if mutation.deleted {
        Some(ctx.now.saturating_mul(1_000))
    } else {
        None
    };
    let content_hash = root_content_hash(&root, media, mutation.deleted);
    insert_version(
        ctx.tx,
        &ctx.principal.family_id,
        &version_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        updated_at,
        deleted_at,
        &root,
        &content_hash,
        Some(&mutation.mutation_id),
        "accepted",
        ctx.now,
        std::slice::from_ref(&stable.version_id),
        media,
    )?;
    let rev = advance_rev(ctx.tx, &ctx.principal.family_id)?;
    upsert_entity_projection(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        updated_at,
        deleted_at,
        &root,
        rev,
    )?;
    project_stable_media(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        media,
        updated_at,
        mutation.deleted,
        deleted_at.unwrap_or_else(|| ctx.now.saturating_mul(1_000)),
    )?;
    set_stable_head(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        &version_id,
    )?;
    sync_open_conflict_stable_head(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        &version_id,
    )?;
    let conflict_id = if mutation.deleted {
        conflict_id_for_stable_delete(
            ctx.tx,
            &ctx.principal.family_id,
            &mutation.entity_type,
            &mutation.client_uuid,
            &version_id,
            ctx.now,
        )?
    } else {
        load_open_conflict_id(
            ctx.tx,
            &ctx.principal.family_id,
            &mutation.entity_type,
            &mutation.client_uuid,
        )?
    };
    let result = CausalUnitResult {
        status: "accepted".to_owned(),
        mutation_id: mutation.mutation_id.clone(),
        stable_version_id: Some(version_id.clone()),
        stable_root: root.clone(),
        stable_media: media.to_vec(),
        stable_deleted_at: deleted_at,
        request_hash: request_hash.to_owned(),
        replay: false,
        branch_version_id: None,
        conflict_id: conflict_id.clone(),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        ctx.principal,
        &mutation.entity_type,
        &mutation.client_uuid,
        &mutation.mutation_id,
        request_hash,
        "accepted",
        Some(&version_id),
        None,
        conflict_id.as_deref(),
        &result,
        ctx.now,
    )?;
    Ok(result)
}

fn commit_merged(
    ctx: &EvalContext<'_>,
    mutation: &CausalMutation,
    stable: &StableSnapshot,
    base_version: &str,
    mut merged_root: Map<String, Value>,
    merged_media: Vec<CausalMediaItem>,
    merged_deleted: bool,
    request_hash: &str,
) -> Result<CausalUnitResult, StoreError> {
    if !merged_deleted {
        if let Some(code) = claim_media_receipts(ctx.tx, ctx.principal, &merged_media, ctx.now)? {
            return Ok(rejected(
                &mutation.mutation_id,
                request_hash,
                code,
                code,
                Some(stable),
            ));
        }
    }
    let version_id = Uuid::new_v4().to_string();
    let updated_at = merged_root
        .get("updated_at")
        .and_then(Value::as_i64)
        .unwrap_or(ctx.now.saturating_mul(1_000));
    let deleted_at = if merged_deleted {
        Some(ctx.now.saturating_mul(1_000))
    } else {
        None
    };
    // Preserve stamps from stable.
    if let Some(v) = stable.root.get("created_by_membership_id") {
        merged_root.insert("created_by_membership_id".to_owned(), v.clone());
    }
    if let Some(v) = stable.root.get("observer_membership_id") {
        merged_root.insert("observer_membership_id".to_owned(), v.clone());
    }
    let content_hash = root_content_hash(&merged_root, &merged_media, merged_deleted);
    let parents = vec![stable.version_id.clone(), base_version.to_owned()]
        .into_iter()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect::<Vec<_>>();
    // Also parent to incoming lineage: base is already included; stable is other side.
    insert_version(
        ctx.tx,
        &ctx.principal.family_id,
        &version_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        updated_at,
        deleted_at,
        &merged_root,
        &content_hash,
        Some(&mutation.mutation_id),
        "merged",
        ctx.now,
        &parents,
        &merged_media,
    )?;
    let rev = advance_rev(ctx.tx, &ctx.principal.family_id)?;
    upsert_entity_projection(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        updated_at,
        deleted_at,
        &merged_root,
        rev,
    )?;
    let projected_media = media_sorted(merged_media);
    project_stable_media(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        &projected_media,
        updated_at,
        merged_deleted,
        deleted_at.unwrap_or_else(|| ctx.now.saturating_mul(1_000)),
    )?;
    set_stable_head(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        &version_id,
    )?;
    sync_open_conflict_stable_head(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        &version_id,
    )?;
    let conflict_id = if merged_deleted {
        conflict_id_for_stable_delete(
            ctx.tx,
            &ctx.principal.family_id,
            &mutation.entity_type,
            &mutation.client_uuid,
            &version_id,
            ctx.now,
        )?
    } else {
        None
    };
    let result = CausalUnitResult {
        status: "merged".to_owned(),
        mutation_id: mutation.mutation_id.clone(),
        stable_version_id: Some(version_id.clone()),
        stable_root: merged_root,
        stable_media: projected_media,
        stable_deleted_at: deleted_at,
        request_hash: request_hash.to_owned(),
        replay: false,
        branch_version_id: None,
        conflict_id: conflict_id.clone(),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        ctx.principal,
        &mutation.entity_type,
        &mutation.client_uuid,
        &mutation.mutation_id,
        request_hash,
        "merged",
        Some(&version_id),
        None,
        conflict_id.as_deref(),
        &result,
        ctx.now,
    )?;
    Ok(result)
}

fn branch_unit(
    ctx: &EvalContext<'_>,
    mutation: &CausalMutation,
    stable: &StableSnapshot,
    incoming_root: &Map<String, Value>,
    incoming_media: &[CausalMediaItem],
    request_hash: &str,
    base_version: &str,
) -> Result<CausalUnitResult, StoreError> {
    admit_new_branch(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        ctx.max_open_branches_per_root,
    )?;
    if !mutation.deleted {
        if let Some(code) = claim_media_receipts(ctx.tx, ctx.principal, incoming_media, ctx.now)? {
            return Ok(rejected(
                &mutation.mutation_id,
                request_hash,
                code,
                code,
                Some(stable),
            ));
        }
    }
    let branch_version_id = Uuid::new_v4().to_string();
    let updated_at = incoming_root
        .get("updated_at")
        .and_then(Value::as_i64)
        .unwrap_or(ctx.now.saturating_mul(1_000));
    let deleted_at = if mutation.deleted {
        Some(ctx.now.saturating_mul(1_000))
    } else {
        None
    };
    let content_hash = root_content_hash(incoming_root, incoming_media, mutation.deleted);
    insert_version(
        ctx.tx,
        &ctx.principal.family_id,
        &branch_version_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        updated_at,
        deleted_at,
        incoming_root,
        &content_hash,
        Some(&mutation.mutation_id),
        "branched",
        ctx.now,
        &[base_version.to_owned()],
        incoming_media,
    )?;
    let conflict_id = open_concurrent_conflict(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        Some(base_version),
        &stable.version_id,
        &branch_version_id,
        ctx.now,
    )?;
    // Branch-only write still advances entity rev for pull discoverability.
    bump_entity_rev_only(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
    )?;
    let result = CausalUnitResult {
        status: "branched".to_owned(),
        mutation_id: mutation.mutation_id.clone(),
        stable_version_id: Some(stable.version_id.clone()),
        stable_root: stable.root.clone(),
        stable_media: stable.media.clone(),
        stable_deleted_at: stable.deleted_at,
        request_hash: request_hash.to_owned(),
        replay: false,
        branch_version_id: Some(branch_version_id.clone()),
        conflict_id: Some(conflict_id.clone()),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        ctx.principal,
        &mutation.entity_type,
        &mutation.client_uuid,
        &mutation.mutation_id,
        request_hash,
        "branched",
        Some(&stable.version_id),
        Some(&branch_version_id),
        Some(&conflict_id),
        &result,
        ctx.now,
    )?;
    Ok(result)
}

impl Store {
    /// Atomic causal commit (wire §6). Distinct roots remain distinct facts.
    pub(crate) fn causal_commit_durable(
        &self,
        principal: &Principal,
        units: Vec<CausalMutation>,
        now: i64,
    ) -> Result<DurableCausalCommit, StoreError> {
        if units.is_empty() {
            return Err(StoreError::InvalidCausalBatch);
        }
        let mut connection = self.connect()?;
        #[cfg(test)]
        super::conflict_snapshots::test_hook::arm_busy_handler(
            &connection,
            &principal.family_id,
            super::conflict_snapshots::test_hook::BusyOperation::Writer,
        )?;
        let tx_result = connection.transaction_with_behavior(TransactionBehavior::Immediate);
        #[cfg(test)]
        super::conflict_snapshots::test_hook::disarm_busy_handler(
            tx_result.as_ref().ok().map(|tx| &**tx),
        )?;
        let tx = tx_result?;
        let exact_replay = batch_is_exact_replay(&tx, principal, &units)?;
        if !exact_replay {
            let admission = self.causal_commit_limiter.check_and_record_in(
                &principal.membership_id,
                &principal.family_id,
                now,
            );
            match admission {
                Ok(()) => {}
                Err(crate::rate_limit::RateLimitRejection::Scoped) => {
                    return Err(StoreError::CausalCommitSaturated(
                        CausalCommitSaturation::Principal,
                    ));
                }
                Err(crate::rate_limit::RateLimitRejection::Group) => {
                    return Err(StoreError::CausalCommitSaturated(
                        CausalCommitSaturation::Family,
                    ));
                }
                Err(crate::rate_limit::RateLimitRejection::Unavailable) => {
                    return Err(StoreError::CausalAdmissionUnavailable);
                }
            }
        }
        if units.len() > MAX_CAUSAL_UNITS {
            return Err(StoreError::InvalidCausalBatch);
        }
        let ctx = EvalContext {
            tx: &tx,
            principal,
            now,
            dry_run: false,
            max_open_branches_per_root: self.max_open_causal_branches_per_root,
        };
        let mut results = Vec::with_capacity(units.len());
        let mut promotion_manifests = Vec::with_capacity(units.len());
        for unit in &units {
            let mut result = evaluate_unit(&ctx, unit)?;
            if result.status == "rejected" {
                let code = match result.code.as_deref() {
                    Some(
                        code @ ("unknown_field"
                        | "missing_field"
                        | "wrong_type"
                        | "non_canonical_value"
                        | "invalid_domain"
                        | "content_drift"
                        | "media_preimage_expired"
                        | "media_membership_mismatch"
                        | "media_sha256_mismatch"
                        | "media_byte_size_mismatch"
                        | "missing_media_bytes"
                        | "media_uuid_conflict"),
                    ) => code,
                    Some(code) if code.starts_with("forbidden") => "forbidden",
                    _ => "invalid_domain",
                };
                return Err(StoreError::CausalCommitRejected {
                    mutation_id: result.mutation_id,
                    code: code.to_owned(),
                });
            }
            // Persist no-op accepted receipt when identical current-base.
            if result.status == "accepted"
                && result.reason.as_deref() == Some("canonical_equivalent")
            {
                let request_hash = result.request_hash.clone();
                if load_receipt(
                    &tx,
                    &principal.family_id,
                    &principal.membership_id,
                    &unit.entity_type,
                    &unit.client_uuid,
                    &unit.mutation_id,
                )?
                .is_none()
                {
                    save_receipt(
                        &tx,
                        &principal.family_id,
                        principal,
                        &unit.entity_type,
                        &unit.client_uuid,
                        &unit.mutation_id,
                        &request_hash,
                        "accepted",
                        result.stable_version_id.as_deref(),
                        None,
                        result.conflict_id.as_deref(),
                        &result,
                        now,
                    )?;
                }
            }
            // Clear internal reason for successful wire statuses (optional).
            if matches!(result.status.as_str(), "accepted" | "merged" | "branched") {
                let version_id = result
                    .branch_version_id
                    .as_ref()
                    .or(result.stable_version_id.as_ref())
                    .ok_or(StoreError::InvalidStoredPayload)?;
                let (_, _, version) =
                    load_validated_version(&tx, &principal.family_id, version_id)?
                        .ok_or(StoreError::InvalidStoredPayload)?;
                promotion_manifests.push(version.media);
                result.reason = None;
            }
            results.push(result);
        }
        tx.commit()?;
        Ok(DurableCausalCommit {
            family_id: principal.family_id.clone(),
            result: CausalCommitResult { results },
            promotion_manifests,
        })
    }

    pub(crate) fn publish_causal_commit(
        &self,
        commit: DurableCausalCommit,
    ) -> Result<CausalCommitResult, StoreError> {
        self.publish_causal_commit_with_hook(commit, None)
    }

    pub(crate) fn publish_causal_commit_with_hook(
        &self,
        commit: DurableCausalCommit,
        blocking_hook: Option<&(dyn Fn(&'static str) + Send + Sync + 'static)>,
    ) -> Result<CausalCommitResult, StoreError> {
        for media in commit.promotion_manifests {
            if media.is_empty() {
                continue;
            }
            self.promote_consumed_causal_media_manifest_with_hook(
                &commit.family_id,
                &media,
                blocking_hook,
            )?;
        }
        Ok(commit.result)
    }

    /// Synchronous Store facade for callers that do not already hold the
    /// process family mutex. The HTTP adapter uses the two phases above so
    /// publication I/O runs after releasing that mutex.
    #[allow(dead_code)]
    pub fn causal_commit(
        &self,
        principal: &Principal,
        units: Vec<CausalMutation>,
        now: i64,
    ) -> Result<CausalCommitResult, StoreError> {
        let commit = self.causal_commit_durable(principal, units, now)?;
        self.publish_causal_commit(commit)
    }

    pub fn conflict_detail_page(
        &self,
        principal: &Principal,
        conflict_id: &str,
        request: ConflictDetailPageRequest,
        now: i64,
    ) -> Result<super::ConflictDetailPage, StoreError> {
        let mut connection = self.connect()?;
        #[cfg(test)]
        super::conflict_snapshots::test_hook::arm_busy_handler(
            &connection,
            &principal.family_id,
            super::conflict_snapshots::test_hook::BusyOperation::Snapshot,
        )?;
        let tx_result = connection.transaction_with_behavior(TransactionBehavior::Immediate);
        #[cfg(test)]
        super::conflict_snapshots::test_hook::disarm_busy_handler(
            tx_result.as_ref().ok().map(|tx| &**tx),
        )?;
        let tx = tx_result?;
        let projection = match load_conflict_heads(&tx, &principal.family_id, conflict_id) {
            Ok(projection) => projection,
            Err(StoreError::InvalidStoredPayload) => {
                match preflight_tombstone_restore(&tx, principal, conflict_id)? {
                    TombstoneRestorePreflight::Forbidden => {
                        return Err(StoreError::ConflictNotFound);
                    }
                    TombstoneRestorePreflight::Authorized(Some("missing_restore_base")) => {
                        return Err(StoreError::MissingRestoreBase);
                    }
                    TombstoneRestorePreflight::Authorized(Some("incomplete_restore_base")) => {
                        return Err(StoreError::IncompleteRestoreBase);
                    }
                    _ => return Err(StoreError::InvalidStoredPayload),
                }
            }
            Err(error) => return Err(error),
        };
        #[cfg(test)]
        super::conflict_snapshots::test_hook::projection_loaded(&principal.family_id);
        let material = build_conflict_snapshot(&projection)?;
        let binding = ConflictSnapshotBinding {
            family_id: &principal.family_id,
            conflict_id,
            kind: &projection.kind,
            entity_type: &projection.entity_type,
            client_uuid: &projection.client_uuid,
            stable_version_id: &projection.stable_version_id,
            branch_version_ids: &projection.branch_version_ids,
            receipt_key: &self.snapshot_receipt_key,
        };
        let page = open_conflict_detail_page(&tx, binding, material, request, now)?;
        tx.commit()?;
        Ok(page)
    }

    pub fn resolve_conflict(
        &self,
        principal: &Principal,
        conflict_id: &str,
        input: ResolveConflictInput,
        now: i64,
    ) -> Result<ResolveConflictResult, StoreError> {
        if let Some(code) = input.validation_code() {
            return Ok(rejected_resolution(&input, code));
        }
        let mut connection = self.connect()?;
        #[cfg(test)]
        super::conflict_snapshots::test_hook::arm_busy_handler(
            &connection,
            &principal.family_id,
            super::conflict_snapshots::test_hook::BusyOperation::Resolution,
        )?;
        let tx_result = connection.transaction_with_behavior(TransactionBehavior::Immediate);
        #[cfg(test)]
        super::conflict_snapshots::test_hook::disarm_busy_handler(
            tx_result.as_ref().ok().map(|tx| &**tx),
        )?;
        let tx = tx_result?;
        #[cfg(test)]
        super::conflict_snapshots::test_hook::resolution_entered(&principal.family_id);
        let request_hash = resolution_request_hash(&input)?;
        if let Some((
            resolver_membership_id,
            expected_stable_version_id,
            expected_branch_versions_json,
            receipt_json,
            resolved_version_id,
            conflict_entity_type,
            conflict_client_uuid,
            conflict_status,
            conflict_stable_version_id,
            conflict_kind,
            conflict_resolved_at,
        )) = tx
            .query_row(
                "SELECT resolver_membership_id, expected_stable_version_id,
                        expected_branch_versions_json, conflict_choices_json,
                        resolved_version_id, c.entity_type, c.client_uuid,
                        c.status, c.stable_version_id, c.kind, c.resolved_at
                   FROM conflict_resolutions r
                   JOIN conflicts c USING(family_id, conflict_id)
                  WHERE r.family_id = ?1 AND r.conflict_id = ?2
                    AND r.resolution_mutation_id = ?3",
                params![
                    principal.family_id,
                    conflict_id,
                    input.resolution_mutation_id,
                ],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, String>(5)?,
                        row.get::<_, String>(6)?,
                        row.get::<_, String>(7)?,
                        row.get::<_, String>(8)?,
                        row.get::<_, String>(9)?,
                        row.get::<_, Option<i64>>(10)?,
                    ))
                },
            )
            .optional()?
        {
            if resolver_membership_id != principal.membership_id {
                return Ok(rejected_resolution(&input, "forbidden"));
            }
            let receipt: StoredResolutionReceipt = serde_json::from_str(&receipt_json)?;
            let branch_versions: Vec<String> =
                serde_json::from_str(&expected_branch_versions_json)?;
            let branches_are_canonical = branch_versions.len() <= MAX_RESOLUTION_CHOICES
                && branch_versions.windows(2).all(|pair| pair[0] < pair[1])
                && serde_json::to_string(&branch_versions)? == expected_branch_versions_json;
            let (version_entity_type, version_client_uuid, stored_version) =
                load_validated_version(&tx, &principal.family_id, &resolved_version_id)?
                    .ok_or(StoreError::InvalidStoredPayload)?;
            let current_branches: Vec<String> = {
                let mut statement = tx.prepare(
                    "SELECT branch_version_id FROM conflict_branches
                     WHERE family_id = ?1 AND conflict_id = ?2
                     ORDER BY branch_version_id COLLATE BINARY",
                )?;
                let rows = statement
                    .query_map(params![principal.family_id, conflict_id], |row| row.get(0))?
                    .collect::<Result<_, _>>()?;
                rows
            };
            let retention_complete = conflict_resolved_at
                .map(|resolved_at| {
                    super::conflict_retention::terminal_metadata_was_compacted(
                        &tx,
                        super::conflict_retention::TerminalRetentionBinding {
                            family_id: &principal.family_id,
                            conflict_id,
                            kind: &conflict_kind,
                            entity_type: &conflict_entity_type,
                            client_uuid: &conflict_client_uuid,
                            expected_stable_version_id: &expected_stable_version_id,
                            expected_branch_versions_json: &expected_branch_versions_json,
                            resolved_version_id: &resolved_version_id,
                            resolved_at,
                        },
                    )
                })
                .transpose()?
                .unwrap_or(false);
            if !crate::constant_time_eq(receipt.request_hash.as_bytes(), request_hash.as_bytes()) {
                return Ok(rejected_resolution(&input, "content_drift"));
            }
            if receipt.result.resolution_mutation_id != input.resolution_mutation_id
                || receipt.result.status != "accepted"
                || receipt.result.replay != Some(false)
                || receipt.result.error.is_some()
                || receipt.result.stable_version_id.as_deref() != Some(&resolved_version_id)
                || receipt.result.stable_root != stored_version.root
                || receipt.result.stable_media != stored_version.media
                || stored_version.mutation_id.as_deref() != Some(&input.resolution_mutation_id)
                || stored_version.parents != BTreeSet::from([expected_stable_version_id])
                || version_entity_type != conflict_entity_type
                || version_client_uuid != conflict_client_uuid
                || conflict_status != "resolved"
                || conflict_stable_version_id != resolved_version_id
                || !branches_are_canonical
                || (current_branches != branch_versions
                    && !(retention_complete && current_branches.is_empty()))
                || receipt.request_hash.len() != 64
                || !receipt
                    .request_hash
                    .bytes()
                    .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
                || serde_json::to_string(&receipt)? != receipt_json
            {
                return Err(StoreError::InvalidStoredPayload);
            }
            let mut replay = receipt.result;
            replay.replay = Some(true);
            tx.commit()?;
            self.promote_consumed_causal_media_manifest(
                &principal.family_id,
                &replay.stable_media,
            )?;
            return Ok(replay);
        }

        let restore_preflight = preflight_tombstone_restore(&tx, principal, conflict_id)?;
        match restore_preflight {
            TombstoneRestorePreflight::Forbidden => {
                return Ok(rejected_resolution(&input, "forbidden"));
            }
            TombstoneRestorePreflight::Authorized(Some(code)) => {
                return Ok(rejected_resolution(&input, code));
            }
            _ => {}
        }
        let loading_tombstone_restore = matches!(
            restore_preflight,
            TombstoneRestorePreflight::Authorized(None)
        );
        let projection = match load_conflict_heads(&tx, &principal.family_id, conflict_id) {
            Ok(projection) => projection,
            Err(StoreError::ConflictNotFound) => {
                let known: bool = tx.query_row(
                    "SELECT EXISTS(SELECT 1 FROM conflicts
                     WHERE family_id = ?1 AND conflict_id = ?2)",
                    params![principal.family_id, conflict_id],
                    |row| row.get(0),
                )?;
                if known {
                    return Ok(rejected_resolution(&input, "snapshot_stale"));
                }
                return Err(StoreError::ConflictNotFound);
            }
            Err(StoreError::InvalidStoredPayload) if loading_tombstone_restore => {
                return Ok(rejected_resolution(&input, "incomplete_restore_base"));
            }
            Err(error) => return Err(error),
        };
        let entity_type = projection.entity_type.clone();
        let client_uuid = projection.client_uuid.clone();
        let stable_version_id = projection.stable_version_id.clone();
        let branch_ids = projection.branch_version_ids.clone();
        let stable = projection.stable();
        if !loading_tombstone_restore
            && authorize_resolve(principal, &entity_type, &stable.root).is_err()
        {
            return Ok(rejected_resolution(&input, "forbidden"));
        }

        let material = build_conflict_snapshot(&projection)?;
        let binding = ConflictSnapshotBinding {
            family_id: &principal.family_id,
            conflict_id,
            kind: &projection.kind,
            entity_type: &entity_type,
            client_uuid: &client_uuid,
            stable_version_id: &stable_version_id,
            branch_version_ids: &branch_ids,
            receipt_key: &self.snapshot_receipt_key,
        };
        let resolution = match authorize_snapshot_resolution(
            &tx,
            &binding,
            &material,
            &input.snapshot_token,
            &input.choices,
            now,
        ) {
            Ok(resolution) => resolution,
            Err(ConflictResolutionRejection::InvalidChoice) => {
                return Ok(rejected_resolution(&input, "invalid_choice"));
            }
            Err(ConflictResolutionRejection::DuplicateChoice) => {
                return Ok(rejected_resolution(&input, "duplicate_choice"));
            }
            Err(ConflictResolutionRejection::IncompleteChoices) => {
                return Ok(rejected_resolution(&input, "incomplete_choices"));
            }
            Err(ConflictResolutionRejection::Store(StoreError::InvalidSnapshotToken)) => {
                return Ok(rejected_resolution(&input, "invalid_snapshot_token"));
            }
            Err(ConflictResolutionRejection::Store(StoreError::SnapshotExpired)) => {
                return Ok(rejected_resolution(&input, "snapshot_expired"));
            }
            Err(ConflictResolutionRejection::Store(StoreError::SnapshotStale)) => {
                return Ok(rejected_resolution(&input, "snapshot_stale"));
            }
            Err(ConflictResolutionRejection::Store(error)) => return Err(error),
        };
        let mut resolved_root = resolution.root;
        if !resolution.restores_direct_base {
            stamp_root(
                &mut resolved_root,
                &entity_type,
                principal,
                Some(&stable.root),
                now.saturating_mul(1_000),
            );
        }
        let resolved_deleted = resolution.deleted;
        let resolved_media = if resolved_deleted {
            vec![]
        } else {
            media_sorted(resolution.media)
        };
        let candidate = CausalMutation {
            mutation_id: input.resolution_mutation_id.clone(),
            base_version: Some(stable_version_id.clone()),
            entity_type: entity_type.clone(),
            client_uuid: client_uuid.clone(),
            root: resolved_root.clone(),
            media: resolved_media.clone(),
            deleted: resolved_deleted,
        };
        if validate_mutation_shape(&candidate).is_err() {
            return Ok(rejected_resolution(&input, "invalid_domain"));
        }
        resolved_root = match validate_mutation_content(&candidate) {
            Ok(root) => root,
            Err(_) => return Ok(rejected_resolution(&input, "invalid_domain")),
        };
        if entity_type == "wake_observation"
            && validate_wake_against_sleep_start(&tx, &principal.family_id, &candidate).is_err()
        {
            return Ok(rejected_resolution(&input, "invalid_domain"));
        }
        let package = canonical_package(&candidate, &resolved_root, &resolved_media, now)?;
        if validate_canonical_package_ingress(&tx, principal, &package).is_err() {
            return Ok(rejected_resolution(&input, "invalid_domain"));
        }

        if !resolved_deleted {
            match verify_consumed_manifest_bytes(
                &tx,
                &self.database_path,
                &principal.family_id,
                &resolved_media,
            ) {
                Ok(()) => {}
                Err(CausalMediaReceiptClaimError::Rejected(_)) => {
                    let code = if resolution.restores_direct_base {
                        "missing_restore_media"
                    } else {
                        "invalid_domain"
                    };
                    return Ok(rejected_resolution(&input, code));
                }
                Err(CausalMediaReceiptClaimError::Store(error)) => return Err(error),
            }
        }

        let version_id = Uuid::new_v4().to_string();
        let updated_at = resolved_root
            .get("updated_at")
            .and_then(Value::as_i64)
            .unwrap_or(now.saturating_mul(1_000));
        let deleted_at = if resolved_deleted {
            Some(now.saturating_mul(1_000))
        } else {
            None
        };
        let content_hash = root_content_hash(&resolved_root, &resolved_media, resolved_deleted);
        // A resolution mutation has one direct causal base. The closed full
        // branch set is durably bound by its snapshot/request receipt, not
        // encoded as an ambiguous multi-parent `base_version`.
        let parents = vec![stable_version_id.clone()];
        insert_version(
            &tx,
            &principal.family_id,
            &version_id,
            &entity_type,
            &client_uuid,
            updated_at,
            deleted_at,
            &resolved_root,
            &content_hash,
            Some(&input.resolution_mutation_id),
            "resolved",
            now,
            &parents,
            &resolved_media,
        )?;
        save_version_provenance(
            &tx,
            &principal.family_id,
            principal,
            &entity_type,
            &client_uuid,
            &input.resolution_mutation_id,
            "accepted",
            Some(conflict_id),
            &version_id,
            now,
        )?;
        let rev = advance_rev(&tx, &principal.family_id)?;
        upsert_entity_projection(
            &tx,
            &principal.family_id,
            &entity_type,
            &client_uuid,
            updated_at,
            deleted_at,
            &resolved_root,
            rev,
        )?;
        project_stable_media(
            &tx,
            &principal.family_id,
            &entity_type,
            &client_uuid,
            &resolved_media,
            updated_at,
            resolved_deleted,
            deleted_at.unwrap_or_else(|| now.saturating_mul(1_000)),
        )?;
        set_stable_head(
            &tx,
            &principal.family_id,
            &entity_type,
            &client_uuid,
            &version_id,
        )?;
        let resolved = ResolveConflictResult {
            status: "accepted".to_owned(),
            resolution_mutation_id: input.resolution_mutation_id.clone(),
            stable_version_id: Some(version_id.clone()),
            stable_root: resolved_root.clone(),
            stable_media: resolved_media.clone(),
            replay: Some(false),
            error: None,
        };
        let stored_receipt = serde_json::to_string(&StoredResolutionReceipt {
            request_hash,
            result: resolved.clone(),
        })?;
        let updated = tx.execute(
            "UPDATE conflicts SET status = 'resolved', resolved_at = ?1, stable_version_id = ?2
             WHERE family_id = ?3 AND conflict_id = ?4 AND status = 'open'
               AND stable_version_id = ?5",
            params![
                now,
                version_id,
                principal.family_id,
                conflict_id,
                stable_version_id,
            ],
        )?;
        if updated != 1 {
            return Ok(rejected_resolution(&input, "cas_mismatch"));
        }
        let expected_branch_versions_json = serde_json::to_string(&branch_ids)?;
        tx.execute(
            "INSERT INTO conflict_resolutions(
                family_id, conflict_id, resolution_mutation_id, resolver_membership_id,
                expected_stable_version_id, expected_branch_versions_json,
                conflict_choices_json, resolved_version_id, created_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![
                principal.family_id,
                conflict_id,
                input.resolution_mutation_id,
                principal.membership_id,
                stable_version_id,
                &expected_branch_versions_json,
                stored_receipt,
                version_id,
                now
            ],
        )?;
        super::conflict_retention::stage_resolution_retention(
            &tx,
            super::conflict_retention::ResolutionRetentionBinding {
                family_id: &principal.family_id,
                conflict_id,
                kind: &projection.kind,
                entity_type: &entity_type,
                client_uuid: &client_uuid,
                expected_stable_version_id: &stable_version_id,
                expected_branch_versions_json: &expected_branch_versions_json,
                resolved_version_id: &version_id,
                resolved_at: now,
            },
        )?;
        if resolved_deleted {
            let _ = conflict_id_for_stable_delete(
                &tx,
                &principal.family_id,
                &entity_type,
                &client_uuid,
                &version_id,
                now,
            )?;
        }
        tx.commit()?;
        self.promote_consumed_causal_media_manifest(&principal.family_id, &resolved_media)?;
        Ok(resolved)
    }
}

// serde Deserialize for CausalUnitResult (receipt round-trip).
impl<'de> serde::Deserialize<'de> for CausalUnitResult {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        #[derive(serde::Deserialize)]
        struct Raw {
            status: String,
            mutation_id: String,
            stable_version_id: Option<String>,
            #[serde(default)]
            stable_root: Map<String, Value>,
            #[serde(default)]
            stable_media: Vec<CausalMediaItem>,
            #[serde(default)]
            stable_deleted_at: Option<i64>,
            request_hash: String,
            #[serde(default)]
            replay: bool,
            branch_version_id: Option<String>,
            conflict_id: Option<String>,
            code: Option<String>,
            conflicting_paths: Option<Vec<String>>,
            reason: Option<String>,
        }
        let raw = Raw::deserialize(deserializer)?;
        Ok(CausalUnitResult {
            status: raw.status,
            mutation_id: raw.mutation_id,
            stable_version_id: raw.stable_version_id,
            stable_root: raw.stable_root,
            stable_media: raw.stable_media,
            stable_deleted_at: raw.stable_deleted_at,
            request_hash: raw.request_hash,
            replay: raw.replay,
            branch_version_id: raw.branch_version_id,
            conflict_id: raw.conflict_id,
            code: raw.code,
            conflicting_paths: raw.conflicting_paths,
            reason: raw.reason,
        })
    }
}
