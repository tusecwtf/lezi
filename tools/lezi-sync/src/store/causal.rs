//! Causal reconcile / commit / conflict detail / resolution (wire §5–§8).
//!
//! Public Store façade methods are the only observed seam. No neighbor
//! adjudication runs on this path.

#![allow(clippy::too_many_arguments)]

use std::collections::{BTreeMap, BTreeSet};

use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use serde::Serialize;
use serde_json::{Map, Value};
use uuid::Uuid;

use std::path::Path;

use crate::model::{validate_causal_root, validate_causal_root_shape, Entity};

use super::bundles::validate_canonical_package_ingress;
use super::causal_admission::admit_new_branch;
use super::causal_media_staging::{consume_manifest, verify_manifest};
use super::causal_merge::{
    leaf_paths, mutation_content_hash, set_path, three_way_merge, CausalMediaItem, MergeDecision,
};
use super::{CausalCommitSaturation, Principal, Store, StoreError};

/// Wire §7: at most 32 conflict_summary entries per ordinary pull page.
pub(crate) const MAX_CONFLICT_SUMMARIES_PER_PAGE: usize = 32;

/// Closed set of versioned entity types for the causal path.
const CAUSAL_ENTITY_TYPES: &[&str] = &[
    "baby",
    "record",
    "care_plan",
    "custom_item",
    "wake_observation",
];

pub const MAX_CAUSAL_UNITS: usize = 64;

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
    pub request_hash: String,
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
pub struct CausalBatchResult {
    pub cursor: i64,
    pub results: Vec<CausalUnitResult>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictSummary {
    pub conflict_id: String,
    pub entity_type: String,
    pub client_uuid: String,
    pub stable_version_id: String,
    pub branch_version_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictBranchDetail {
    pub branch_version_id: String,
    pub root: Map<String, Value>,
    pub media: Vec<CausalMediaItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ConflictDetail {
    pub conflict_id: String,
    pub stable_version_id: String,
    pub stable_root: Map<String, Value>,
    pub stable_media: Vec<CausalMediaItem>,
    pub branches: Vec<ConflictBranchDetail>,
    pub conflicting_paths: Vec<String>,
    pub auto_merged: Map<String, Value>,
}

#[derive(Debug, Clone)]
pub struct ResolveConflictInput {
    pub expected_stable_version: String,
    pub expected_branch_versions: Vec<String>,
    pub resolved_root: Map<String, Value>,
    pub resolved_media: Vec<CausalMediaItem>,
    pub resolution_mutation_id: String,
    pub conflict_choices: Map<String, Value>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ResolveConflictResult {
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stable_version_id: Option<String>,
    #[serde(default)]
    pub stable_root: Map<String, Value>,
    #[serde(default)]
    pub stable_media: Vec<CausalMediaItem>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conflict_summary: Option<ConflictSummary>,
}

#[derive(Debug, Clone)]
struct StableSnapshot {
    version_id: String,
    root: Map<String, Value>,
    media: Vec<CausalMediaItem>,
    deleted_at: Option<i64>,
    updated_at: i64,
    #[allow(dead_code)]
    content_hash: String,
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
        request_hash: request_hash.to_owned(),
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
    let row = tx
        .query_row(
            "SELECT payload_json, content_hash, updated_at, deleted_at
             FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![family_id, version_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, Option<i64>>(3)?,
                ))
            },
        )
        .optional()?;
    let Some((payload_json, content_hash, updated_at, deleted_at)) = row else {
        return Ok(None);
    };
    let root: Map<String, Value> = serde_json::from_str(&payload_json)?;
    let mut media = load_version_media(tx, family_id, version_id)?;
    sort_media(&mut media);
    Ok(Some(StableSnapshot {
        version_id: version_id.to_owned(),
        root,
        media,
        deleted_at,
        updated_at,
        content_hash,
    }))
}

fn load_version_media(
    tx: &Transaction<'_>,
    family_id: &str,
    version_id: &str,
) -> Result<Vec<CausalMediaItem>, StoreError> {
    let mut stmt = tx.prepare(
        "SELECT media_payload_json FROM entity_version_media
         WHERE family_id = ?1 AND version_id = ?2
         ORDER BY media_uuid COLLATE BINARY",
    )?;
    let rows = stmt.query_map(params![family_id, version_id], |row| {
        row.get::<_, String>(0)
    })?;
    let mut media = Vec::new();
    for row in rows {
        let raw = row?;
        let value: Value = serde_json::from_str(&raw)?;
        if let Some(item) = CausalMediaItem::from_value(&value) {
            media.push(item);
        } else {
            return Err(StoreError::InvalidStoredPayload);
        }
    }
    Ok(media)
}

fn load_parents(
    tx: &Transaction<'_>,
    family_id: &str,
    version_id: &str,
) -> Result<BTreeSet<String>, StoreError> {
    let mut stmt = tx.prepare(
        "SELECT parent_version_id FROM entity_version_parents
         WHERE family_id = ?1 AND version_id = ?2",
    )?;
    let rows = stmt.query_map(params![family_id, version_id], |row| {
        row.get::<_, String>(0)
    })?;
    rows.collect::<Result<BTreeSet<_>, _>>()
        .map_err(StoreError::from)
}

fn load_receipt(
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
    membership_id: &str,
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
            membership_id,
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
) -> Result<String, StoreError> {
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
    if let Some((id, kind)) = existing {
        // Keep concurrent handle; pin stable to new tombstone head.
        tx.execute(
            "UPDATE conflicts SET stable_version_id = ?1
             WHERE family_id = ?2 AND conflict_id = ?3",
            params![stable_version_id, family_id, id],
        )?;
        let _ = kind;
        return Ok(id);
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
) -> Result<String, StoreError> {
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
        return Ok(id);
    }
    // Prefer any open conflict on this root (should not happen if caller used
    // conflict_id_for_stable_delete first).
    if let Some(id) = load_open_conflict_id(tx, family_id, entity_type, client_uuid)? {
        tx.execute(
            "UPDATE conflicts SET stable_version_id = ?1
             WHERE family_id = ?2 AND conflict_id = ?3",
            params![stable_version_id, family_id, id],
        )?;
        return Ok(id);
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
    Ok(conflict_id)
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
        "record" | "care_plan" | "custom_item" | "baby" => {
            let author = stable_root
                .get("created_by_membership_id")
                .and_then(Value::as_str);
            if author == Some(principal.membership_id.as_str()) {
                Ok(())
            } else if entity_type == "baby" {
                Err(StoreError::ForbiddenBaby)
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

/// Wire §9.3: referenced media bytes must exist before branch/accept ack.
fn require_media_bytes_present(
    tx: &Transaction<'_>,
    database_path: &Path,
    principal: &Principal,
    media: &[CausalMediaItem],
    now: i64,
) -> Result<(), &'static str> {
    verify_manifest(tx, database_path, principal, media, now)
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
    database_path: &'a Path,
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
        if ctx.dry_run && matches!(receipt.status.as_str(), "accepted" | "merged" | "branched") {
            receipt.status = "confirmed".to_owned();
        }
        receipt.request_hash = request_hash;
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
    if let Err(error) = validate_canonical_package_ingress(ctx.tx, ctx.principal, &package) {
        let code = match error {
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
                request_hash,
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
        let conflict_id = if stable_deleted {
            Some(open_or_get_tombstone_conflict(
                ctx.tx,
                &ctx.principal.family_id,
                &mutation.entity_type,
                &mutation.client_uuid,
                &stable.version_id,
                ctx.now,
            )?)
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
            request_hash,
            branch_version_id: None,
            conflict_id,
            code: None,
            conflicting_paths: None,
            reason: Some("canonical_equivalent".to_owned()),
        });
    }

    // Stale live over tombstone (wire 例 F).
    if stable_deleted && !incoming_deleted {
        let parents = load_parents(ctx.tx, &ctx.principal.family_id, &stable.version_id)?;
        let concurrent = parents.contains(&base_version);
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
                request_hash,
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
            paths,
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
                request_hash,
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
            request_hash,
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
                    request_hash,
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
                    request_hash,
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
                conflicting_paths,
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
        if let Err(code) =
            require_media_bytes_present(ctx.tx, ctx.database_path, ctx.principal, media, ctx.now)
        {
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
        Some(conflict_id_for_stable_delete(
            ctx.tx,
            &ctx.principal.family_id,
            &mutation.entity_type,
            &mutation.client_uuid,
            &version_id,
            ctx.now,
        )?)
    } else {
        None
    };
    let result = CausalUnitResult {
        status: "accepted".to_owned(),
        mutation_id: mutation.mutation_id.clone(),
        stable_version_id: Some(version_id.clone()),
        stable_root: root.clone(),
        stable_media: media.to_vec(),
        request_hash: request_hash.to_owned(),
        branch_version_id: None,
        conflict_id: conflict_id.clone(),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        &ctx.principal.membership_id,
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
        if let Err(code) =
            require_media_bytes_present(ctx.tx, ctx.database_path, ctx.principal, media, ctx.now)
        {
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
        Some(conflict_id_for_stable_delete(
            ctx.tx,
            &ctx.principal.family_id,
            &mutation.entity_type,
            &mutation.client_uuid,
            &version_id,
            ctx.now,
        )?)
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
        request_hash: request_hash.to_owned(),
        branch_version_id: None,
        conflict_id: conflict_id.clone(),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        &ctx.principal.membership_id,
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
        if let Err(code) = require_media_bytes_present(
            ctx.tx,
            ctx.database_path,
            ctx.principal,
            &merged_media,
            ctx.now,
        ) {
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
        Some(conflict_id_for_stable_delete(
            ctx.tx,
            &ctx.principal.family_id,
            &mutation.entity_type,
            &mutation.client_uuid,
            &version_id,
            ctx.now,
        )?)
    } else {
        None
    };
    let result = CausalUnitResult {
        status: "merged".to_owned(),
        mutation_id: mutation.mutation_id.clone(),
        stable_version_id: Some(version_id.clone()),
        stable_root: merged_root,
        stable_media: projected_media,
        request_hash: request_hash.to_owned(),
        branch_version_id: None,
        conflict_id: conflict_id.clone(),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        &ctx.principal.membership_id,
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
    conflicting_paths: Vec<String>,
) -> Result<CausalUnitResult, StoreError> {
    admit_new_branch(
        ctx.tx,
        &ctx.principal.family_id,
        &mutation.entity_type,
        &mutation.client_uuid,
        ctx.max_open_branches_per_root,
    )?;
    if !mutation.deleted {
        if let Err(code) = require_media_bytes_present(
            ctx.tx,
            ctx.database_path,
            ctx.principal,
            incoming_media,
            ctx.now,
        ) {
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
    let _ = conflicting_paths;
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
    // Persist auto_merged / conflicting_paths? Store in receipt_json only;
    // detail recomputes from versions.
    let _ = conflicting_paths;
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
        request_hash: request_hash.to_owned(),
        branch_version_id: Some(branch_version_id.clone()),
        conflict_id: Some(conflict_id.clone()),
        code: None,
        conflicting_paths: None,
        reason: None,
    };
    save_receipt(
        ctx.tx,
        &ctx.principal.family_id,
        &ctx.principal.membership_id,
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
    /// Dry-run causal reconcile (wire §5). Never writes stable versions/branches.
    pub fn causal_reconcile(
        &self,
        principal: &Principal,
        units: Vec<CausalMutation>,
        now: i64,
    ) -> Result<CausalBatchResult, StoreError> {
        if units.is_empty() || units.len() > MAX_CAUSAL_UNITS {
            return Err(StoreError::InvalidReconcileBatch);
        }
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let cursor: i64 = tx.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![principal.family_id],
            |row| row.get(0),
        )?;
        let ctx = EvalContext {
            tx: &tx,
            principal,
            now,
            dry_run: true,
            database_path: &self.database_path,
            max_open_branches_per_root: self.max_open_causal_branches_per_root,
        };
        let mut results = Vec::with_capacity(units.len());
        for unit in &units {
            // Dry-run must not persist open conflicts from tombstone handle minting.
            // Use savepoint so side effects roll back.
            tx.execute("SAVEPOINT causal_reconcile_unit", [])?;
            let result = evaluate_unit(&ctx, unit)?;
            tx.execute("ROLLBACK TO causal_reconcile_unit", [])?;
            tx.execute("RELEASE causal_reconcile_unit", [])?;
            results.push(result);
        }
        // Entire reconcile is read-only: roll back any accidental writes.
        tx.rollback()?;
        Ok(CausalBatchResult { cursor, results })
    }

    /// Atomic causal commit (wire §6). Distinct roots remain distinct facts.
    pub fn causal_commit(
        &self,
        principal: &Principal,
        units: Vec<CausalMutation>,
        now: i64,
    ) -> Result<CausalBatchResult, StoreError> {
        if units.is_empty() {
            return Err(StoreError::InvalidReconcileBatch);
        }
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
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
            return Err(StoreError::InvalidReconcileBatch);
        }
        let ctx = EvalContext {
            tx: &tx,
            principal,
            now,
            dry_run: false,
            database_path: &self.database_path,
            max_open_branches_per_root: self.max_open_causal_branches_per_root,
        };
        let mut results = Vec::with_capacity(units.len());
        for unit in &units {
            let mut result = evaluate_unit(&ctx, unit)?;
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
                        &principal.membership_id,
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
                if !unit.deleted {
                    consume_manifest(&tx, principal, &unit.media, now)?;
                }
                result.reason = None;
            }
            results.push(result);
        }
        let cursor: i64 = tx.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![principal.family_id],
            |row| row.get(0),
        )?;
        tx.commit()?;
        self.promote_consumed_causal_media_for_family(&principal.family_id)?;
        let cursor = self.current_revision(&principal.family_id)?.max(cursor);
        Ok(CausalBatchResult { cursor, results })
    }

    pub fn conflict_detail(
        &self,
        principal: &Principal,
        conflict_id: &str,
    ) -> Result<ConflictDetail, StoreError> {
        let mut connection = self.connect()?;
        let tx = connection.transaction().map_err(StoreError::from)?;
        let row = tx
            .query_row(
                "SELECT entity_type, client_uuid, stable_version_id, kind, status
                 FROM conflicts
                 WHERE family_id = ?1 AND conflict_id = ?2",
                params![principal.family_id, conflict_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                    ))
                },
            )
            .optional()?;
        let Some((_entity_type, _client_uuid, stable_version_id, kind, status)) = row else {
            return Err(StoreError::ConflictNotFound);
        };
        if status != "open" {
            return Err(StoreError::ConflictNotFound);
        }
        let stable = load_version(&tx, &principal.family_id, &stable_version_id)?
            .ok_or(StoreError::InvalidStoredPayload)?;

        let mut branch_ids = Vec::new();
        {
            let mut stmt = tx.prepare(
                "SELECT branch_version_id FROM conflict_branches
                 WHERE family_id = ?1 AND conflict_id = ?2
                 ORDER BY branch_version_id COLLATE BINARY",
            )?;
            let rows = stmt.query_map(params![principal.family_id, conflict_id], |row| {
                row.get::<_, String>(0)
            })?;
            for row in rows {
                branch_ids.push(row?);
            }
        }
        let mut branches = Vec::new();
        let mut branch_deleted_flags = Vec::new();
        for branch_id in &branch_ids {
            let snap = load_version(&tx, &principal.family_id, branch_id)?
                .ok_or(StoreError::InvalidStoredPayload)?;
            branch_deleted_flags.push(snap.deleted_at.is_some());
            let mutation_id: Option<String> = tx
                .query_row(
                    "SELECT mutation_id FROM entity_versions
                     WHERE family_id = ?1 AND version_id = ?2",
                    params![principal.family_id, branch_id],
                    |row| row.get(0),
                )
                .optional()?
                .flatten();
            branches.push(ConflictBranchDetail {
                branch_version_id: branch_id.clone(),
                root: snap.root,
                media: snap.media,
                mutation_id,
            });
        }

        let (conflicting_paths, auto_merged) = compute_conflict_paths(
            &tx,
            &principal.family_id,
            kind.as_str(),
            &stable,
            &branches,
            &branch_deleted_flags,
        )?;

        Ok(ConflictDetail {
            conflict_id: conflict_id.to_owned(),
            stable_version_id,
            stable_root: stable.root,
            stable_media: stable.media,
            branches,
            conflicting_paths,
            auto_merged,
        })
    }

    pub fn resolve_conflict(
        &self,
        principal: &Principal,
        conflict_id: &str,
        input: ResolveConflictInput,
        now: i64,
    ) -> Result<ResolveConflictResult, StoreError> {
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let row = tx
            .query_row(
                "SELECT entity_type, client_uuid, stable_version_id, kind, status
                 FROM conflicts
                 WHERE family_id = ?1 AND conflict_id = ?2",
                params![principal.family_id, conflict_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                    ))
                },
            )
            .optional()?;
        let Some((entity_type, client_uuid, stable_version_id, kind, status)) = row else {
            return Err(StoreError::ConflictNotFound);
        };
        if status != "open" {
            return Err(StoreError::ConflictNotFound);
        }

        // CAS: expected stable + complete branch set. Also pin to live head so a
        // newer accepted projection cannot be overwritten by an old screen.
        let live_head: Option<String> = tx
            .query_row(
                "SELECT version_id FROM entity_stable_heads
                 WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3",
                params![principal.family_id, entity_type, client_uuid],
                |row| row.get(0),
            )
            .optional()?;
        let mut branch_ids: Vec<String> = {
            let mut stmt = tx.prepare(
                "SELECT branch_version_id FROM conflict_branches
                 WHERE family_id = ?1 AND conflict_id = ?2
                 ORDER BY branch_version_id COLLATE BINARY",
            )?;
            let rows = stmt.query_map(params![principal.family_id, conflict_id], |row| {
                row.get::<_, String>(0)
            })?;
            rows.collect::<Result<Vec<_>, _>>()?
        };
        branch_ids.sort();
        let mut expected_branches = input.expected_branch_versions.clone();
        expected_branches.sort();
        let cas_stable = live_head
            .as_ref()
            .filter(|h| *h == &stable_version_id)
            .cloned()
            .unwrap_or(stable_version_id.clone());
        if input.expected_stable_version != cas_stable
            || input.expected_stable_version != stable_version_id
            || branch_ids != expected_branches
        {
            let summary = ConflictSummary {
                conflict_id: conflict_id.to_owned(),
                entity_type: entity_type.clone(),
                client_uuid: client_uuid.clone(),
                stable_version_id: live_head.unwrap_or(stable_version_id.clone()),
                branch_version_ids: branch_ids,
            };
            return Ok(ResolveConflictResult {
                status: "cas_mismatch".to_owned(),
                stable_version_id: None,
                stable_root: Map::new(),
                stable_media: vec![],
                code: Some("cas_mismatch".to_owned()),
                conflict_summary: Some(summary),
            });
        }

        let stable = load_version(&tx, &principal.family_id, &stable_version_id)?
            .ok_or(StoreError::InvalidStoredPayload)?;
        authorize_resolve(principal, &entity_type, &stable.root)?;

        // Idempotent resolution mutation.
        if let Ok(Some(existing_version)) = tx
            .query_row(
                "SELECT resolved_version_id FROM conflict_resolutions
                 WHERE family_id = ?1 AND conflict_id = ?2 AND resolution_mutation_id = ?3",
                params![
                    principal.family_id,
                    conflict_id,
                    input.resolution_mutation_id
                ],
                |row| row.get::<_, String>(0),
            )
            .optional()
        {
            let snap = load_version(&tx, &principal.family_id, &existing_version)?
                .ok_or(StoreError::InvalidStoredPayload)?;
            return Ok(ResolveConflictResult {
                status: "resolved".to_owned(),
                stable_version_id: Some(existing_version),
                stable_root: snap.root,
                stable_media: snap.media,
                code: None,
                conflict_summary: None,
            });
        }

        // Load branches for path recompute.
        let mut branches = Vec::new();
        let mut branch_deleted_flags = Vec::new();
        for branch_id in &branch_ids {
            let snap = load_version(&tx, &principal.family_id, branch_id)?
                .ok_or(StoreError::InvalidStoredPayload)?;
            branch_deleted_flags.push(snap.deleted_at.is_some());
            branches.push(ConflictBranchDetail {
                branch_version_id: branch_id.clone(),
                root: snap.root,
                media: snap.media,
                mutation_id: None,
            });
        }
        let (conflicting_paths, auto_merged) = compute_conflict_paths(
            &tx,
            &principal.family_id,
            kind.as_str(),
            &stable,
            &branches,
            &branch_deleted_flags,
        )?;

        // Choices must be subset of conflicting paths.
        for key in input.conflict_choices.keys() {
            if !conflicting_paths.iter().any(|p| p == key) {
                return Ok(ResolveConflictResult {
                    status: "rejected".to_owned(),
                    stable_version_id: Some(stable_version_id.clone()),
                    stable_root: stable.root.clone(),
                    stable_media: stable.media.clone(),
                    code: Some("invalid_conflict_choices".to_owned()),
                    conflict_summary: None,
                });
            }
        }
        for path in &conflicting_paths {
            if !input.conflict_choices.contains_key(path) {
                return Ok(ResolveConflictResult {
                    status: "rejected".to_owned(),
                    stable_version_id: Some(stable_version_id.clone()),
                    stable_root: stable.root.clone(),
                    stable_media: stable.media.clone(),
                    code: Some("incomplete_conflict_choices".to_owned()),
                    conflict_summary: None,
                });
            }
        }

        // Server rebuild: auto_merged ⊕ conflict_choices (wire §8.2).
        let (mut rebuilt_root, rebuilt_media, rebuilt_deleted) = rebuild_from_auto_merged(
            &stable,
            &auto_merged,
            &input.conflict_choices,
            &conflicting_paths,
        );
        stamp_root(
            &mut rebuilt_root,
            &entity_type,
            principal,
            Some(&stable.root),
            now.saturating_mul(1_000),
        );

        // Normalize client resolved_* and deep-equal.
        let mut client_root = input.resolved_root.clone();
        stamp_root(
            &mut client_root,
            &entity_type,
            principal,
            Some(&stable.root),
            now.saturating_mul(1_000),
        );
        // updated_at is non-conflict; align for equality.
        if let Some(v) = rebuilt_root.get("updated_at").cloned() {
            client_root.insert("updated_at".to_owned(), v);
        }
        let client_media = media_sorted(input.resolved_media.clone());
        let client_deleted = input
            .conflict_choices
            .get("/_mutation.deleted")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        // For concurrent without delete choice, deleted follows rebuild.
        let client_deleted = if conflicting_paths.iter().any(|p| p == "/_mutation.deleted") {
            client_deleted
        } else {
            rebuilt_deleted
        };

        if leaf_paths(&client_root) != leaf_paths(&rebuilt_root)
            || !media_equal(&client_media, &rebuilt_media)
            || client_deleted != rebuilt_deleted
        {
            return Ok(ResolveConflictResult {
                status: "rejected".to_owned(),
                stable_version_id: Some(stable_version_id),
                stable_root: stable.root,
                stable_media: stable.media,
                code: Some("rewrote_auto_merged_path".to_owned()),
                conflict_summary: None,
            });
        }

        let resolved_root = rebuilt_root;
        let resolved_media = rebuilt_media;
        let resolved_deleted = rebuilt_deleted;

        if !resolved_deleted {
            if let Err(code) = require_media_bytes_present(
                &tx,
                &self.database_path,
                principal,
                &resolved_media,
                now,
            ) {
                return Ok(ResolveConflictResult {
                    status: "rejected".to_owned(),
                    stable_version_id: Some(stable_version_id),
                    stable_root: stable.root,
                    stable_media: stable.media,
                    code: Some(code.to_owned()),
                    conflict_summary: None,
                });
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
        let mut parents = vec![stable_version_id.clone()];
        parents.extend(branch_ids.iter().cloned());
        parents.sort();
        parents.dedup();
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
        tx.execute(
            "UPDATE conflicts SET status = 'resolved', resolved_at = ?1, stable_version_id = ?2
             WHERE family_id = ?3 AND conflict_id = ?4",
            params![now, version_id, principal.family_id, conflict_id],
        )?;
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
                input.expected_stable_version,
                serde_json::to_string(&input.expected_branch_versions)?,
                serde_json::to_string(&input.conflict_choices)?,
                version_id,
                now
            ],
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
        } else {
            consume_manifest(&tx, principal, &resolved_media, now)?;
        }
        tx.commit()?;
        self.promote_consumed_causal_media_for_family(&principal.family_id)?;
        Ok(ResolveConflictResult {
            status: "resolved".to_owned(),
            stable_version_id: Some(version_id),
            stable_root: resolved_root,
            stable_media: resolved_media,
            code: None,
            conflict_summary: None,
        })
    }
}

/// Union conflicting paths across all branches (wire §8.1 multi-device).
fn compute_conflict_paths(
    tx: &Transaction<'_>,
    family_id: &str,
    kind: &str,
    stable: &StableSnapshot,
    branches: &[ConflictBranchDetail],
    branch_deleted_flags: &[bool],
) -> Result<(Vec<String>, Map<String, Value>), StoreError> {
    if kind == "tombstone_restore" {
        let mut auto = Map::new();
        for (k, v) in &stable.root {
            auto.insert(format!("/{k}"), v.clone());
        }
        auto.insert("/_mutation.deleted".to_owned(), Value::Bool(true));
        return Ok((vec!["/_mutation.deleted".to_owned()], auto));
    }
    let mut all_paths: BTreeSet<String> = BTreeSet::new();
    let mut auto_merged: Map<String, Value> = Map::new();
    for (idx, branch) in branches.iter().enumerate() {
        let parent: Option<String> = tx
            .query_row(
                "SELECT parent_version_id FROM entity_version_parents
                 WHERE family_id = ?1 AND version_id = ?2
                 LIMIT 1",
                params![family_id, branch.branch_version_id],
                |row| row.get(0),
            )
            .optional()?;
        let Some(base_id) = parent else {
            all_paths.insert("/_mutation.deleted".to_owned());
            continue;
        };
        let Some(base) = load_version(tx, family_id, &base_id)? else {
            all_paths.insert("/_mutation.deleted".to_owned());
            continue;
        };
        let branch_deleted = branch_deleted_flags.get(idx).copied().unwrap_or(false);
        let decision = three_way_merge(
            &base.root,
            &base.media,
            base.deleted_at.is_some(),
            &stable.root,
            &stable.media,
            stable.deleted_at.is_some(),
            &branch.root,
            &branch.media,
            branch_deleted,
        );
        match decision {
            MergeDecision::Conflict {
                conflicting_paths,
                auto_merged: am,
            } => {
                all_paths.extend(conflicting_paths);
                for (k, v) in am {
                    auto_merged.entry(k).or_insert(v);
                }
            }
            MergeDecision::AutoMerge {
                auto_merged: am, ..
            } => {
                for (k, v) in am {
                    auto_merged.entry(k).or_insert(v);
                }
            }
            MergeDecision::Identical => {}
        }
    }
    if all_paths.is_empty() && !branches.is_empty() {
        // Distinct branches on same field values still need a path if roots differ
        // only by stamps — treat as note conflict fallback for resolution UI.
        all_paths.insert("/note".to_owned());
    }
    Ok((all_paths.into_iter().collect(), auto_merged))
}

/// Rebuild authoritative root/media/deleted from auto_merged ⊕ choices.
fn rebuild_from_auto_merged(
    stable: &StableSnapshot,
    auto_merged: &Map<String, Value>,
    choices: &Map<String, Value>,
    conflicting_paths: &[String],
) -> (Map<String, Value>, Vec<CausalMediaItem>, bool) {
    let mut root = stable.root.clone();
    // Apply auto_merged non-media paths first.
    for (path, value) in auto_merged {
        if path == "/_mutation.deleted" || path.starts_with("/media/") {
            continue;
        }
        set_path(&mut root, path, value.clone());
    }
    // Apply conflict choices (authoritative for conflicting paths).
    for path in conflicting_paths {
        if path == "/_mutation.deleted" || path.starts_with("/media/") {
            continue;
        }
        if let Some(v) = choices.get(path) {
            set_path(&mut root, path, v.clone());
        }
    }
    let mut deleted = stable.deleted_at.is_some();
    if let Some(Value::Bool(d)) = choices.get("/_mutation.deleted") {
        deleted = *d;
    } else if let Some(Value::Bool(d)) = auto_merged.get("/_mutation.deleted") {
        deleted = *d;
    }

    // Media: start from stable, apply auto_merged media paths, then choices.
    let mut media_map: BTreeMap<String, CausalMediaItem> = stable
        .media
        .iter()
        .map(|m| (m.media_uuid.clone(), m.clone()))
        .collect();
    for (path, value) in auto_merged {
        if let Some(uuid) = path.strip_prefix("/media/") {
            if value.is_null() {
                media_map.remove(uuid);
            } else if let Some(item) = CausalMediaItem::from_value(value) {
                media_map.insert(uuid.to_owned(), item);
            }
        }
    }
    for path in conflicting_paths {
        if let Some(uuid) = path.strip_prefix("/media/") {
            if let Some(value) = choices.get(path) {
                if value.is_null() {
                    media_map.remove(uuid);
                } else if let Some(item) = CausalMediaItem::from_value(value) {
                    media_map.insert(uuid.to_owned(), item);
                }
            }
        }
    }
    let media: Vec<CausalMediaItem> = media_map.into_values().collect();
    (root, media_sorted(media), deleted)
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
            request_hash: String,
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
            request_hash: raw.request_hash,
            branch_version_id: raw.branch_version_id,
            conflict_id: raw.conflict_id,
            code: raw.code,
            conflicting_paths: raw.conflicting_paths,
            reason: raw.reason,
        })
    }
}
