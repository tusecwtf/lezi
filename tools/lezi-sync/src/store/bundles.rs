//! Atomic sync bundles (stage/commit) and LWW push validation.

use std::collections::{BTreeMap, BTreeSet, HashMap};

use rusqlite::types::Value as SqlValue;
use rusqlite::{
    params, params_from_iter, Connection, OptionalExtension, Transaction, TransactionBehavior,
};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

use crate::model::{
    care_plan_fulfillment_pair_issue, is_next_feed_plan_note, Entity, MAX_BUNDLE_MEDIA_ENTITIES,
    MAX_OPEN_STAGING_BUNDLES_PER_FAMILY,
};
use crate::PULL_ENTITY_TARGET_BYTES;

use super::{
    anonymize_membership_authorship_fields, confirmed_at_millis, parse_payload, BundleCommitResult,
    BundleMediaIntegrity, BundleStageStatus, EntityKey, Principal, RecordAuthor, Store, StoreError,
    StoredBundle, ENTITY_QUERY_CHUNK_SIZE,
};

#[derive(Debug, Clone)]
pub(in crate::store) struct ExistingEntity {
    pub(in crate::store) updated_at: i64,
    pub(in crate::store) deleted_at: Option<i64>,
    pub(in crate::store) payload: Map<String, Value>,
}

/// (kind, record_client_uuid, baby_client_uuid, care_plan_client_uuid)
type MediaAssociation = (String, Option<String>, Option<String>, Option<String>);

#[derive(Debug, Clone)]
pub(in crate::store) struct BundleRow {
    pub(in crate::store) bundle_id: String,
    pub(in crate::store) staged_membership_id: String,
    pub(in crate::store) status: String,
    pub(in crate::store) root_type: String,
    pub(in crate::store) root_client_uuid: String,
    pub(in crate::store) root_updated_at: i64,
    pub(in crate::store) root_deleted_at: Option<i64>,
    pub(in crate::store) root_payload_json: String,
    pub(in crate::store) media_entities_json: String,
    pub(in crate::store) content_hash: String,
    pub(in crate::store) committed_cursor: Option<i64>,
    pub(in crate::store) committed_applied: Option<i64>,
}

pub(in crate::store) fn load_bundle_row(
    connection: &Connection,
    family_id: &str,
    bundle_id: &str,
) -> Result<Option<BundleRow>, StoreError> {
    connection
        .query_row(
            "
            SELECT bundle_id, staged_membership_id, status,
                   root_type, root_client_uuid,
                   root_updated_at, root_deleted_at, root_payload_json,
                   media_entities_json, content_hash,
                   committed_cursor, committed_applied
            FROM sync_bundles
            WHERE family_id = ?1 AND bundle_id = ?2
            ",
            params![family_id, bundle_id],
            |row| {
                Ok(BundleRow {
                    bundle_id: row.get(0)?,
                    staged_membership_id: row.get(1)?,
                    status: row.get(2)?,
                    root_type: row.get(3)?,
                    root_client_uuid: row.get(4)?,
                    root_updated_at: row.get(5)?,
                    root_deleted_at: row.get(6)?,
                    root_payload_json: row.get(7)?,
                    media_entities_json: row.get(8)?,
                    content_hash: row.get(9)?,
                    committed_cursor: row.get(10)?,
                    committed_applied: row.get(11)?,
                })
            },
        )
        .optional()
        .map_err(StoreError::from)
}

/// Load a staging bundle owned by `membership_id`.
///
/// Returns `Ok(None)` when the bundle is already committed (callers choose
/// success-noop vs `BundleMediaUploadClosed`). Membership mismatch and missing
/// rows fail closed.
pub(in crate::store) fn load_open_staging_bundle_for_membership(
    connection: &Connection,
    family_id: &str,
    bundle_id: &str,
    membership_id: &str,
) -> Result<Option<BundleRow>, StoreError> {
    let row =
        load_bundle_row(connection, family_id, bundle_id)?.ok_or(StoreError::BundleNotFound)?;
    if row.status == "committed" {
        return Ok(None);
    }
    if row.staged_membership_id != membership_id {
        return Err(StoreError::BundleMembershipMismatch);
    }
    Ok(Some(row))
}

fn bundle_stage_status_from_row(
    connection: &Connection,
    family_id: &str,
    row: &BundleRow,
) -> Result<BundleStageStatus, StoreError> {
    let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
    let required = required_live_media_uuids(&media);
    let staged = staged_media_uuids(connection, family_id, &row.bundle_id)?;
    let staged_set = staged.iter().cloned().collect::<BTreeSet<_>>();
    let missing = required
        .into_iter()
        .filter(|id| !staged_set.contains(id))
        .collect::<Vec<_>>();
    Ok(BundleStageStatus {
        bundle_id: row.bundle_id.clone(),
        status: row.status.clone(),
        missing_media: missing,
        staged_media: staged,
    })
}

fn load_bundle_media_integrity(
    connection: &Connection,
    family_id: &str,
    bundle_id: &str,
) -> Result<BTreeMap<String, BundleMediaIntegrity>, StoreError> {
    let mut statement = connection.prepare(
        "
        SELECT media_uuid, declared_byte_size, staged_sha256
        FROM sync_bundle_media
        WHERE family_id = ?1 AND bundle_id = ?2
        ",
    )?;
    let rows = statement
        .query_map(params![family_id, bundle_id], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, Option<i64>>(1)?,
                row.get::<_, Option<String>>(2)?,
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    rows.into_iter()
        .map(|(media_uuid, declared_byte_size, staged_sha256)| {
            let declared_byte_size = declared_byte_size
                .map(usize::try_from)
                .transpose()
                .map_err(|_| StoreError::InvalidStoredPayload)?;
            Ok((
                media_uuid,
                BundleMediaIntegrity {
                    declared_byte_size,
                    staged_sha256,
                },
            ))
        })
        .collect()
}

fn staged_media_uuids(
    connection: &Connection,
    family_id: &str,
    bundle_id: &str,
) -> Result<Vec<String>, StoreError> {
    let mut statement = connection.prepare(
        "
        SELECT media_uuid FROM sync_bundle_media
        WHERE family_id = ?1 AND bundle_id = ?2
          AND staged_byte_size IS NOT NULL
          AND (declared_byte_size IS NULL OR staged_byte_size = declared_byte_size)
        ORDER BY media_uuid
        ",
    )?;
    let rows = statement.query_map(params![family_id, bundle_id], |row| row.get(0))?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(StoreError::from)
}

fn required_live_media_uuids(media: &[Entity]) -> Vec<String> {
    media
        .iter()
        .filter(|entity| entity.deleted_at.is_none())
        .map(|entity| entity.client_uuid.clone())
        .collect()
}

fn entity_key(entity: &Entity) -> EntityKey {
    (entity.entity_type.clone(), entity.client_uuid.clone())
}

fn load_existing_entities(
    transaction: &Transaction<'_>,
    family_id: &str,
    keys: &BTreeSet<EntityKey>,
) -> Result<HashMap<EntityKey, ExistingEntity>, StoreError> {
    let mut by_type: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for (entity_type, client_uuid) in keys {
        by_type
            .entry(entity_type.as_str())
            .or_default()
            .push(client_uuid.as_str());
    }
    let mut existing = HashMap::new();
    for (entity_type, client_uuids) in by_type {
        for chunk in client_uuids.chunks(ENTITY_QUERY_CHUNK_SIZE) {
            let placeholders = std::iter::repeat_n("?", chunk.len())
                .collect::<Vec<_>>()
                .join(", ");
            let sql = format!(
                "
                SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json
                FROM entities
                WHERE family_id = ? AND entity_type = ?
                  AND client_uuid IN ({placeholders})
                "
            );
            let mut parameters = Vec::with_capacity(chunk.len() + 2);
            parameters.push(SqlValue::Text(family_id.to_owned()));
            parameters.push(SqlValue::Text(entity_type.to_owned()));
            parameters.extend(
                chunk
                    .iter()
                    .map(|client_uuid| SqlValue::Text((*client_uuid).to_owned())),
            );
            let mut statement = transaction.prepare(&sql)?;
            let rows = statement.query_map(params_from_iter(parameters), |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, Option<i64>>(3)?,
                    row.get::<_, String>(4)?,
                ))
            })?;
            for row in rows {
                let (stored_type, client_uuid, updated_at, deleted_at, payload) = row?;
                existing.insert(
                    (stored_type, client_uuid),
                    ExistingEntity {
                        updated_at,
                        deleted_at,
                        payload: parse_payload(&payload)?,
                    },
                );
            }
        }
    }
    Ok(existing)
}

fn load_family_custom_items(
    transaction: &Transaction<'_>,
    family_id: &str,
) -> Result<HashMap<EntityKey, ExistingEntity>, StoreError> {
    let mut statement = transaction.prepare(
        "
        SELECT client_uuid, updated_at, deleted_at, payload_json
        FROM entities
        WHERE family_id = ?1 AND entity_type = 'custom_item'
        ",
    )?;
    let rows = statement.query_map(params![family_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, Option<i64>>(2)?,
            row.get::<_, String>(3)?,
        ))
    })?;
    let mut existing = HashMap::new();
    for row in rows {
        let (client_uuid, updated_at, deleted_at, payload_json) = row?;
        existing.insert(
            ("custom_item".to_owned(), client_uuid),
            ExistingEntity {
                updated_at,
                deleted_at,
                payload: parse_payload(&payload_json)?,
            },
        );
    }
    Ok(existing)
}

fn load_family_media(
    transaction: &Transaction<'_>,
    family_id: &str,
) -> Result<HashMap<EntityKey, ExistingEntity>, StoreError> {
    let mut statement = transaction.prepare(
        "
        SELECT client_uuid, updated_at, deleted_at, payload_json
        FROM entities
        WHERE family_id = ?1 AND entity_type = 'media'
        ",
    )?;
    let rows = statement.query_map(params![family_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, Option<i64>>(2)?,
            row.get::<_, String>(3)?,
        ))
    })?;
    let mut existing = HashMap::new();
    for row in rows {
        let (client_uuid, updated_at, deleted_at, payload_json) = row?;
        existing.insert(
            ("media".to_owned(), client_uuid),
            ExistingEntity {
                updated_at,
                deleted_at,
                payload: parse_payload(&payload_json)?,
            },
        );
    }
    Ok(existing)
}

/// Rewrite or drop `sync_bundles` rows that still reference a departed membership.
///
/// Called from membership hard-delete inside the same SQLite transaction. Staging
/// bundles owned by the membership are deleted; other rows clear authorship fields
/// and recompute content hash.
pub(in crate::store) fn anonymize_membership_bundle_references(
    transaction: &Transaction<'_>,
    family_id: &str,
    membership_id: &str,
) -> Result<(), StoreError> {
    let bundle_ids = {
        let mut statement = transaction.prepare(
            "SELECT bundle_id FROM sync_bundles WHERE family_id = ?1 ORDER BY bundle_id",
        )?;
        let ids = statement
            .query_map(params![family_id], |row| row.get::<_, String>(0))?
            .collect::<Result<Vec<_>, _>>()?;
        ids
    };
    for bundle_id in bundle_ids {
        let Some(row) = load_bundle_row(transaction, family_id, &bundle_id)? else {
            continue;
        };
        if row.status == "staging" && row.staged_membership_id == membership_id {
            transaction.execute(
                "DELETE FROM sync_bundles WHERE family_id = ?1 AND bundle_id = ?2",
                params![family_id, bundle_id],
            )?;
            continue;
        }
        let mut root_payload = parse_payload(&row.root_payload_json)?;
        let payload_changed =
            anonymize_membership_authorship_fields(&mut root_payload, |id| id == membership_id) > 0;
        let stager_changed = row.staged_membership_id == membership_id;
        if !payload_changed && !stager_changed {
            continue;
        }
        let root = Entity {
            entity_type: row.root_type,
            client_uuid: row.root_client_uuid,
            updated_at: row.root_updated_at,
            deleted_at: row.root_deleted_at,
            payload: root_payload.clone(),
        };
        let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
        let content_hash = bundle_content_hash(&root, &media)?;
        let staged_membership_id = if stager_changed {
            ""
        } else {
            row.staged_membership_id.as_str()
        };
        transaction.execute(
            "
            UPDATE sync_bundles
            SET staged_membership_id = ?1, root_payload_json = ?2, content_hash = ?3
            WHERE family_id = ?4 AND bundle_id = ?5
            ",
            params![
                staged_membership_id,
                serde_json::to_string(&root_payload)?,
                content_hash,
                family_id,
                bundle_id,
            ],
        )?;
    }
    Ok(())
}

/// Content hash for an atomic bundle (root + media entities).
/// Shared by live commit paths and the offline v3→current migrator so integrity
/// gates cannot drift.
pub(crate) fn bundle_content_hash(root: &Entity, media: &[Entity]) -> Result<String, StoreError> {
    let payload = serde_json::json!({
        "root": root,
        "media": media,
    });
    let bytes = serde_json::to_vec(&payload)?;
    Ok(hex::encode(Sha256::digest(bytes)))
}

/// Stamp creator membership on first insert; freeze creator; enforce member-own /
/// owner-all ACL; refuse clearing deleted_at on tombstones.
fn stamp_and_authorize_custom_items(
    role: &str,
    membership_id: &str,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<(), StoreError> {
    for entity in entities.iter_mut() {
        if entity.entity_type != "custom_item" {
            continue;
        }
        let key = ("custom_item".to_owned(), entity.client_uuid.clone());
        let current = existing.get(&key);
        if let Some(current) = current {
            if current.deleted_at.is_some() && entity.deleted_at.is_none() {
                return Err(StoreError::CustomItemTombstoneResurrection);
            }
            let creator = current
                .payload
                .get("created_by_membership_id")
                .and_then(Value::as_str)
                .filter(|creator| !creator.is_empty())
                .map(str::to_owned);
            let Some(creator) = creator else {
                entity
                    .payload
                    .insert("created_by_membership_id".to_owned(), Value::Null);
                if role != "owner" {
                    return Err(StoreError::ForbiddenAnonymousFact);
                }
                continue;
            };
            // Creator is immutable after first write.
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(creator.clone()),
            );
            if role != "owner" && creator != membership_id {
                return Err(StoreError::ForbiddenCustomItem);
            }
        } else {
            // First insert: server stamps authenticated membership (ignore client guess).
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
        }
    }
    Ok(())
}

/// Record authorship belongs to the authenticated principal, never to client claims.
/// Once published, only that membership (across all of its devices) or the
/// family owner may edit or tombstone the record. Soft-deleted records cannot
/// be resurrected (record tombstone wins — ADR-0018 / 0.3.10).
fn canonicalize_record_authors(
    role: &str,
    membership_id: &str,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<(), StoreError> {
    for entity in entities.iter_mut() {
        if entity.entity_type != "record" {
            continue;
        }
        let key = ("record".to_owned(), entity.client_uuid.clone());
        if let Some(current) = existing.get(&key) {
            if current.deleted_at.is_some() && entity.deleted_at.is_none() {
                return Err(StoreError::RecordTombstoneResurrection);
            }
            let value = current
                .payload
                .get("created_by_membership_id")
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty());
            if let Some(value) = value {
                if role != "owner" && value != membership_id {
                    return Err(StoreError::ForbiddenRecord);
                }
                entity.payload.insert(
                    "created_by_membership_id".to_owned(),
                    Value::String(value.to_owned()),
                );
            } else {
                entity
                    .payload
                    .insert("created_by_membership_id".to_owned(), Value::Null);
                if role != "owner" {
                    return Err(StoreError::ForbiddenAnonymousFact);
                }
            }
        } else {
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
        }
    }
    Ok(())
}

fn record_author_acknowledgement(
    client_uuid: &str,
    payload: &Map<String, Value>,
) -> Option<RecordAuthor> {
    let created_by_membership_id = payload
        .get("created_by_membership_id")
        .and_then(Value::as_str)
        .filter(|membership_id| !membership_id.is_empty())?
        .to_owned();
    Some(RecordAuthor {
        client_uuid: client_uuid.to_owned(),
        created_by_membership_id,
    })
}

fn load_persisted_record_author(
    connection: &Connection,
    family_id: &str,
    client_uuid: &str,
) -> Result<Option<RecordAuthor>, StoreError> {
    let payload_json = connection
        .query_row(
            "
            SELECT payload_json
            FROM entities
            WHERE family_id = ?1
              AND entity_type = 'record'
              AND client_uuid = ?2
            ",
            params![family_id, client_uuid],
            |row| row.get::<_, String>(0),
        )
        .optional()?;
    payload_json
        .map(|payload_json| {
            let payload = parse_payload(&payload_json)?;
            Ok(record_author_acknowledgement(client_uuid, &payload))
        })
        .transpose()
        .map(Option::flatten)
}

/// Equal `updated_at` keeps the already-published LWW winner. Atomic staging and
/// commit must hash and persist that exact root rather than a losing same-version
/// payload, otherwise committed bundle retries diverge from pull.
fn canonicalize_equal_lww_bundle_root(
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) {
    for entity in entities.iter_mut() {
        if entity.entity_type == "media" {
            continue;
        }
        let key = entity_key(entity);
        let Some(current) = existing.get(&key) else {
            continue;
        };
        if current.updated_at == entity.updated_at {
            entity.deleted_at = current.deleted_at;
            entity.payload = current.payload.clone();
        }
    }
}

/// CarePlan ACL mirrors custom items: any member may create; only creator or
/// owner may edit/skip/delete. Creator membership is server-stamped and frozen.
/// Left authors keep their membership_id; ordinary members do not gain rights.
fn stamp_and_authorize_care_plans(
    role: &str,
    membership_id: &str,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<BTreeSet<String>, StoreError> {
    let mut noop_care_plan_ids = BTreeSet::new();
    for entity in entities.iter_mut() {
        if entity.entity_type != "care_plan" {
            continue;
        }
        let key = ("care_plan".to_owned(), entity.client_uuid.clone());
        let current = existing.get(&key);
        if let Some(current) = current {
            if current.deleted_at.is_some() && entity.deleted_at.is_none() {
                return Err(StoreError::CarePlanTombstoneResurrection);
            }
            let creator = current
                .payload
                .get("created_by_membership_id")
                .and_then(Value::as_str)
                .filter(|creator| !creator.is_empty())
                .map(str::to_owned);
            let Some(creator) = creator else {
                entity
                    .payload
                    .insert("created_by_membership_id".to_owned(), Value::Null);
                if role != "owner" {
                    return Err(StoreError::ForbiddenAnonymousFact);
                }
                freeze_care_plan_fulfillment_binding(current, &entity.payload)?;
                continue;
            };
            let incoming_creator = entity
                .payload
                .get("created_by_membership_id")
                .and_then(Value::as_str)
                .map(str::to_owned);
            let incoming_is_exact_current = entity.updated_at == current.updated_at
                && entity.deleted_at == current.deleted_at
                && entity.payload == current.payload;
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(creator.clone()),
            );
            // Manage ACL (edit/skip/delete/status): creator or owner only.
            // Fulfillment is not a care_plan rewrite path here (separate candidate).
            if role != "owner" && creator != membership_id {
                let current_is_open_next_feed =
                    current.deleted_at.is_none() && is_open_next_feed_payload(&current.payload);
                let incoming_is_open_next_feed =
                    entity.deleted_at.is_none() && is_open_next_feed_payload(&entity.payload);
                if (incoming_creator.as_deref() == Some(membership_id) || incoming_is_exact_current)
                    && current_is_open_next_feed
                    && incoming_is_open_next_feed
                {
                    // Offline members can independently create the same
                    // deterministic next-feed UUID. An exact foreign replay is
                    // also the durable form of a package already canonicalized
                    // as no-op during stage. Keep the published NAS row as the
                    // exact winner without granting edit rights to it.
                    entity.updated_at = current.updated_at;
                    entity.deleted_at = current.deleted_at;
                    entity.payload = current.payload.clone();
                    noop_care_plan_ids.insert(entity.client_uuid.clone());
                    continue;
                }
                return Err(StoreError::ForbiddenCarePlan);
            }
            freeze_care_plan_fulfillment_binding(current, &entity.payload)?;
        } else {
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
        }
    }
    Ok(noop_care_plan_ids)
}

fn freeze_care_plan_fulfillment_binding(
    current: &ExistingEntity,
    incoming: &Map<String, Value>,
) -> Result<(), StoreError> {
    let current_record = current
        .payload
        .get("fulfilled_record_client_uuid")
        .and_then(Value::as_str);
    let current_fulfilled_at = current.payload.get("fulfilled_at").and_then(Value::as_i64);
    let (Some(current_record), Some(current_fulfilled_at)) = (current_record, current_fulfilled_at)
    else {
        return Ok(());
    };
    let incoming_record = incoming
        .get("fulfilled_record_client_uuid")
        .and_then(Value::as_str);
    let incoming_fulfilled_at = incoming.get("fulfilled_at").and_then(Value::as_i64);
    if incoming_record != Some(current_record)
        || incoming_fulfilled_at != Some(current_fulfilled_at)
    {
        return Err(StoreError::ImmutableCarePlanFulfillmentBinding);
    }
    Ok(())
}

/// Push-path CarePlan fulfillment pair invariant.
/// Shape rules use the shared model predicate → [`StoreError::InvalidCarePlanFulfillmentPair`]
/// (HTTP 422). Same-baby on an already-present record stays
/// [`StoreError::UnresolvedReference`] (HTTP 409). Forward refs remain allowed.
fn validate_care_plan_fulfillment_pair_on_push(
    entity: &Entity,
    effective_records: &HashMap<String, Map<String, Value>>,
) -> Result<(), StoreError> {
    let status = entity
        .payload
        .get("status")
        .and_then(Value::as_str)
        .ok_or(StoreError::InvalidStoredPayload)?;
    // Empty string treated as absent (model rejects empty UUID before this seam).
    let fulfilled_record = entity
        .payload
        .get("fulfilled_record_client_uuid")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty());
    let has_record = fulfilled_record.is_some();
    let has_at = entity
        .payload
        .get("fulfilled_at")
        .and_then(Value::as_i64)
        .is_some();
    if let Some(issue) = care_plan_fulfillment_pair_issue(status, has_record, has_at) {
        return Err(StoreError::InvalidCarePlanFulfillmentPair(issue.detail()));
    }
    if let Some(record_id) = fulfilled_record {
        if let Some(record_payload) = effective_records.get(record_id) {
            let plan_baby = entity
                .payload
                .get("baby_client_uuid")
                .and_then(Value::as_str)
                .ok_or(StoreError::InvalidStoredPayload)?;
            let record_baby = record_payload
                .get("baby_client_uuid")
                .and_then(Value::as_str)
                .ok_or(StoreError::InvalidStoredPayload)?;
            if plan_baby != record_baby {
                return Err(StoreError::UnresolvedReference(
                    "care_plan fulfilled record baby does not match care_plan baby".to_owned(),
                ));
            }
        }
    }
    Ok(())
}

fn discard_media_for_noop_care_plans(
    entities: &mut Vec<Entity>,
    noop_care_plan_ids: &BTreeSet<String>,
) {
    if noop_care_plan_ids.is_empty() {
        return;
    }
    entities.retain(|entity| {
        entity.entity_type != "media"
            || entity
                .payload
                .get("care_plan_client_uuid")
                .and_then(Value::as_str)
                .is_none_or(|plan_uuid| !noop_care_plan_ids.contains(plan_uuid))
    });
}

fn is_open_next_feed_payload(payload: &Map<String, Value>) -> bool {
    is_next_feed_plan_note(payload.get("note").and_then(Value::as_str))
        && payload
            .get("status")
            .and_then(Value::as_str)
            .is_some_and(|status| status == "pending" || status == "missed")
}

/// Any active member may submit a fulfillment candidate for any plan. Server
/// freezes the full evidence tuple after first accept: plan/record/actual time
/// plus submitter membership, role, and confirmed_at. Clients cannot forge or
/// splice-rewrite evidence. Exact replay is a no-op (no revision advance).
fn stamp_and_authorize_fulfillment_candidates(
    role: &str,
    membership_id: &str,
    confirmed_at: i64,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<(), StoreError> {
    // Any active principal may submit; role is frozen only as conflict evidence.
    for entity in entities.iter_mut() {
        if entity.entity_type != "fulfillment_candidate" {
            continue;
        }
        let key = (
            "fulfillment_candidate".to_owned(),
            entity.client_uuid.clone(),
        );
        if let Some(current) = existing.get(&key) {
            if current.deleted_at.is_some() && entity.deleted_at.is_none() {
                return Err(StoreError::FulfillmentCandidateTombstoneResurrection);
            }
            let submitter = current
                .payload
                .get("submitter_membership_id")
                .and_then(Value::as_str);
            if submitter.is_none() && role != "owner" {
                return Err(StoreError::ForbiddenAnonymousFact);
            }
            // Validate-only freeze; single payload clone below is the canonizer.
            freeze_fulfillment_candidate_evidence(current, &entity.payload)?;
            // Exact evidence + same tombstone state → force LWW no-op so
            // exact replay does not advance revision or rewrite content.
            if entity.deleted_at == current.deleted_at {
                entity.updated_at = current.updated_at;
                entity.payload = current.payload.clone();
            } else {
                // Live → tombstone is allowed only with frozen evidence payload.
                entity.payload = current.payload.clone();
            }
        } else {
            entity.payload.insert(
                "submitter_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
            entity
                .payload
                .insert("submitter_role".to_owned(), Value::String(role.to_owned()));
            entity.payload.insert(
                "confirmed_at".to_owned(),
                Value::Number(confirmed_at.into()),
            );
        }
    }
    Ok(())
}

/// Business pair + actual time are immutable after first accept (validate-only).
/// Submitter stamps and the full payload are restored by
/// [`stamp_and_authorize_fulfillment_candidates`] via a single published-row
/// clone, matching [`freeze_care_plan_fulfillment_binding`].
fn freeze_fulfillment_candidate_evidence(
    current: &ExistingEntity,
    incoming: &Map<String, Value>,
) -> Result<(), StoreError> {
    for field in [
        "care_plan_client_uuid",
        "record_client_uuid",
        "actual_timestamp",
    ] {
        let current_value = current.payload.get(field).unwrap_or(&Value::Null);
        let incoming_value = incoming.get(field).unwrap_or(&Value::Null);
        if current_value != incoming_value {
            return Err(StoreError::ImmutableFulfillmentCandidateEvidence);
        }
    }
    Ok(())
}

fn effective_lww_winners(
    entities: Vec<Entity>,
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Vec<Entity> {
    let mut winners: HashMap<EntityKey, (usize, Entity)> = HashMap::new();
    for (index, entity) in entities.into_iter().enumerate() {
        let key = entity_key(&entity);
        if existing
            .get(&key)
            .is_some_and(|current| current.updated_at >= entity.updated_at)
        {
            continue;
        }
        let replace = winners
            .get(&key)
            .is_none_or(|(_, previous)| entity.updated_at > previous.updated_at);
        if replace {
            winners.insert(key, (index, entity));
        }
    }
    let mut winners = winners.into_values().collect::<Vec<_>>();
    winners.sort_by_key(|(index, _)| *index);
    winners.into_iter().map(|(_, entity)| entity).collect()
}

fn validation_reference_keys(entities: &[Entity]) -> BTreeSet<EntityKey> {
    let mut references = BTreeSet::new();
    for entity in entities {
        match entity.entity_type.as_str() {
            "record" | "care_plan" => {
                if let Some(id) = entity.payload["baby_client_uuid"].as_str() {
                    references.insert(("baby".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity
                    .payload
                    .get("custom_item_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("custom_item".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity
                    .payload
                    .get("fulfilled_record_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity
                    .payload
                    .get("source_record_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity
                    .payload
                    .get("effective_wake_observation_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("wake_observation".to_owned(), id.to_owned()));
                }
            }
            "wake_observation" => {
                if let Some(id) = entity
                    .payload
                    .get("sleep_record_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
            }
            "fulfillment_candidate" => {
                if let Some(id) = entity.payload["care_plan_client_uuid"].as_str() {
                    references.insert(("care_plan".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity.payload["record_client_uuid"].as_str() {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
            }
            "media" => {
                if entity.payload["kind"] == "avatar" {
                    if let Some(id) = entity.payload["baby_client_uuid"].as_str() {
                        references.insert(("baby".to_owned(), id.to_owned()));
                    }
                } else if let Some(id) = entity
                    .payload
                    .get("care_plan_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("care_plan".to_owned(), id.to_owned()));
                } else if let Some(id) = entity.payload["record_client_uuid"].as_str() {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
            }
            "baby" => {
                if let Some(id) = entity.payload["avatar_media_uuid"].as_str() {
                    references.insert(("media".to_owned(), id.to_owned()));
                }
            }
            _ => {}
        }
    }
    references
}

/// A completed, persisted CarePlan is the server-side proof that a subsequently
/// published Record belongs to an explicit fulfillment. This reverse lookup is
/// intentionally narrow: it does not make a deleted catalog item selectable for
/// unrelated new facts.
fn load_fulfillment_custom_references(
    connection: &Connection,
    family_id: &str,
    entities: &[Entity],
) -> Result<BTreeSet<(String, String)>, StoreError> {
    let record_ids = entities
        .iter()
        .filter(|entity| entity.entity_type == "record")
        .map(|entity| entity.client_uuid.as_str())
        .collect::<BTreeSet<_>>();
    if record_ids.is_empty() {
        return Ok(BTreeSet::new());
    }
    let mut statement = connection.prepare(
        "
        SELECT json_extract(payload_json, '$.custom_item_client_uuid')
        FROM entities
        WHERE family_id = ?1
          AND entity_type = 'care_plan'
          AND deleted_at IS NULL
          AND json_extract(payload_json, '$.status') = 'completed'
          AND json_extract(payload_json, '$.fulfilled_record_client_uuid') = ?2
          AND json_extract(payload_json, '$.fulfilled_at') IS NOT NULL
        ",
    )?;
    let mut references = BTreeSet::new();
    for record_id in record_ids {
        let rows = statement.query_map(params![family_id, record_id], |row| {
            row.get::<_, Option<String>>(0)
        })?;
        for custom_item_id in rows {
            if let Some(custom_item_id) = custom_item_id? {
                references.insert((record_id.to_owned(), custom_item_id));
            }
        }
    }
    Ok(references)
}

fn validate_deferred_fulfillment_resolutions(
    connection: &Connection,
    family_id: &str,
    entities: &[Entity],
    media_ready: Option<&BTreeMap<String, bool>>,
) -> Result<Vec<(String, String)>, StoreError> {
    let mut resolutions = Vec::new();
    for record in entities
        .iter()
        .filter(|entity| entity.entity_type == "record" && entity.deleted_at.is_none())
    {
        let record_baby = record
            .payload
            .get("baby_client_uuid")
            .and_then(Value::as_str)
            .ok_or(StoreError::InvalidStoredPayload)?;
        for (plan_id, raw) in
            load_deferred_plan_rows_for_record(connection, family_id, &record.client_uuid)?
        {
            let plan = parse_payload(&raw)?;
            let plan_baby = plan
                .get("baby_client_uuid")
                .and_then(Value::as_str)
                .ok_or(StoreError::InvalidStoredPayload)?;
            if plan.get("fulfilled_at").and_then(Value::as_i64).is_none() {
                return Err(StoreError::InvalidStoredPayload);
            }
            if plan_baby != record_baby {
                return Err(StoreError::UnresolvedReference(
                    "fulfilled record baby does not match care_plan baby".to_owned(),
                ));
            }
            if let Some(media_ready) = media_ready {
                for (media_id, _) in
                    load_deferred_plan_media_integrity(connection, family_id, &plan_id)?
                {
                    if !media_ready.get(&media_id).copied().unwrap_or(false) {
                        return Err(StoreError::BundleMediaIncomplete);
                    }
                }
            }
            resolutions.push((plan_id, record.client_uuid.clone()));
        }
    }
    Ok(resolutions)
}

fn load_deferred_plan_rows_for_record(
    connection: &Connection,
    family_id: &str,
    record_client_uuid: &str,
) -> Result<Vec<(String, String)>, StoreError> {
    let mut statement = connection.prepare(
        "
        SELECT plan.client_uuid, plan.payload_json
        FROM entities AS plan
        WHERE plan.family_id = ?1
          AND plan.entity_type = 'care_plan'
          AND plan.deleted_at IS NULL
          AND json_extract(plan.payload_json, '$.status') = 'completed'
          AND json_extract(plan.payload_json, '$.fulfilled_record_client_uuid') = ?2
          AND NOT EXISTS (
              SELECT 1
              FROM entities AS record
              WHERE record.family_id = ?1
                AND record.entity_type = 'record'
                AND record.client_uuid = ?2
                AND record.deleted_at IS NULL
          )
        ",
    )?;
    let rows = statement
        .query_map(params![family_id, record_client_uuid], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?
        .collect::<Result<Vec<_>, _>>()
        .map_err(StoreError::from)?;
    Ok(rows)
}

fn load_deferred_plan_media_integrity(
    connection: &Connection,
    family_id: &str,
    plan_client_uuid: &str,
) -> Result<BTreeMap<String, BundleMediaIntegrity>, StoreError> {
    let mut statement = connection.prepare(
        "
        SELECT media.client_uuid,
               json_extract(media.payload_json, '$.byte_size'),
               publication.source,
               bundle.status,
               bundle_media.declared_byte_size,
               bundle_media.staged_sha256
        FROM entities AS media
        LEFT JOIN media_publications AS publication
          ON publication.family_id = media.family_id
         AND publication.media_uuid = media.client_uuid
        LEFT JOIN sync_bundles AS bundle
          ON bundle.family_id = publication.family_id
         AND bundle.bundle_id = publication.bundle_id
        LEFT JOIN sync_bundle_media AS bundle_media
          ON bundle_media.family_id = publication.family_id
         AND bundle_media.bundle_id = publication.bundle_id
         AND bundle_media.media_uuid = media.client_uuid
        WHERE media.family_id = ?1
          AND media.entity_type = 'media'
          AND media.deleted_at IS NULL
          AND json_extract(media.payload_json, '$.kind') = 'log'
          AND json_extract(media.payload_json, '$.care_plan_client_uuid') = ?2
        ORDER BY media.client_uuid
        ",
    )?;
    let rows = statement
        .query_map(params![family_id, plan_client_uuid], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, Option<i64>>(1)?,
                row.get::<_, Option<String>>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, Option<i64>>(4)?,
                row.get::<_, Option<String>>(5)?,
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    let mut integrity = BTreeMap::new();
    for (media_id, payload_size, source, bundle_status, declared_size, digest) in rows {
        let valid_digest = digest.as_deref().is_some_and(|value| {
            value.len() == 64
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        });
        if payload_size.is_none()
            || payload_size != declared_size
            || payload_size.is_some_and(|value| value <= 0)
            || source.as_deref() != Some("bundle")
            || bundle_status.as_deref() != Some("committed")
            || !valid_digest
        {
            return Err(StoreError::BundleMediaIncomplete);
        }
        integrity.insert(
            media_id,
            BundleMediaIntegrity {
                declared_byte_size: payload_size.and_then(|value| usize::try_from(value).ok()),
                staged_sha256: digest,
            },
        );
    }
    Ok(integrity)
}

fn validate_push(
    role: &str,
    membership_id: &str,
    entities: &[Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
    persisted: &HashMap<EntityKey, ExistingEntity>,
    fulfillment_custom_references: &BTreeSet<(String, String)>,
) -> Result<(), StoreError> {
    for entity in entities {
        match entity.entity_type.as_str() {
            "wake_observation" if entity.deleted_at.is_none() => {
                let sleep_id = entity
                    .payload
                    .get("sleep_record_client_uuid")
                    .and_then(Value::as_str)
                    .ok_or(StoreError::InvalidStoredPayload)?;
                let sleep =
                    effective_entity(entities, existing, "record", sleep_id).ok_or_else(|| {
                        StoreError::UnresolvedReference(
                            "wake_observation sleep_record_client_uuid does not exist".to_owned(),
                        )
                    })?;
                if sleep.deleted_at.is_some()
                    || sleep.payload.get("type").and_then(Value::as_str) != Some("sleep")
                {
                    return Err(StoreError::UnresolvedReference(
                        "wake_observation must reference a live sleep record".to_owned(),
                    ));
                }
                let sleep_start = sleep
                    .payload
                    .get("timestamp")
                    .and_then(Value::as_i64)
                    .ok_or(StoreError::InvalidStoredPayload)?;
                let wake_timestamp = entity
                    .payload
                    .get("wake_timestamp")
                    .and_then(Value::as_i64)
                    .ok_or(StoreError::InvalidStoredPayload)?;
                if wake_timestamp < sleep_start {
                    return Err(StoreError::UnresolvedReference(
                        "wake_observation precedes its sleep record".to_owned(),
                    ));
                }
            }
            "record" if entity.deleted_at.is_none() => {
                let Some(wake_id) = entity
                    .payload
                    .get("effective_wake_observation_client_uuid")
                    .and_then(Value::as_str)
                else {
                    continue;
                };
                let wake = effective_entity(entities, existing, "wake_observation", wake_id)
                    .ok_or_else(|| {
                        StoreError::UnresolvedReference(
                            "effective WakeObservation does not exist".to_owned(),
                        )
                    })?;
                let valid = wake.deleted_at.is_none()
                    && wake
                        .payload
                        .get("sleep_record_client_uuid")
                        .and_then(Value::as_str)
                        == Some(entity.client_uuid.as_str())
                    && wake.payload.get("withdrawn").and_then(Value::as_bool) == Some(false);
                if !valid {
                    return Err(StoreError::UnresolvedReference(
                        "effective WakeObservation is not valid for this sleep".to_owned(),
                    ));
                }
            }
            _ => {}
        }
    }
    let mut baby_ids = existing
        .keys()
        .filter(|(entity_type, _)| entity_type == "baby")
        .map(|(_, id)| id.clone())
        .collect::<BTreeSet<_>>();
    baby_ids.extend(
        entities
            .iter()
            .filter(|entity| entity.entity_type == "baby")
            .map(|entity| entity.client_uuid.clone()),
    );
    let mut live_custom_item_ids = existing
        .iter()
        .filter(|((entity_type, _), entity)| {
            entity_type == "custom_item" && entity.deleted_at.is_none()
        })
        .map(|((_, id), _)| id.clone())
        .collect::<BTreeSet<_>>();
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "custom_item")
    {
        if entity.deleted_at.is_none() {
            live_custom_item_ids.insert(entity.client_uuid.clone());
        } else {
            live_custom_item_ids.remove(&entity.client_uuid);
        }
    }
    if live_custom_item_ids.len() > 10 {
        return Err(StoreError::UnresolvedReference(
            "family supports at most 10 live custom items".to_owned(),
        ));
    }
    let referential_custom_item_ids = existing
        .keys()
        .filter(|(entity_type, _)| entity_type == "custom_item")
        .map(|(_, id)| id.clone())
        .collect::<BTreeSet<_>>();

    let mut effective_records = existing
        .iter()
        .filter(|((entity_type, _), _)| entity_type == "record")
        .map(|((_, id), entity)| (id.clone(), entity.payload.clone()))
        .collect::<HashMap<_, _>>();
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "record")
    {
        let baby_id = entity.payload["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !baby_ids.contains(baby_id) {
            return Err(StoreError::UnresolvedReference(
                "record baby_client_uuid does not exist".to_owned(),
            ));
        }
        validate_custom_item_reference(
            entity,
            &live_custom_item_ids,
            &referential_custom_item_ids,
            persisted,
            fulfillment_custom_references,
        )?;
        effective_records.insert(entity.client_uuid.clone(), entity.payload.clone());
    }
    let record_ids = effective_records.keys().cloned().collect::<BTreeSet<_>>();

    let mut effective_care_plans = existing
        .iter()
        .filter(|((entity_type, _), _)| entity_type == "care_plan")
        .map(|((_, id), entity)| (id.clone(), entity.payload.clone()))
        .collect::<HashMap<_, _>>();
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "care_plan")
    {
        let baby_id = entity.payload["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !baby_ids.contains(baby_id) {
            return Err(StoreError::UnresolvedReference(
                "care_plan baby_client_uuid does not exist".to_owned(),
            ));
        }
        // Defense in depth: same status↔pair invariant as model wire validation.
        // Product publish order is completed CarePlan → Record, so the bound
        // record may be a forward reference; when it already exists, same-baby.
        validate_care_plan_fulfillment_pair_on_push(entity, &effective_records)?;
        validate_custom_item_reference(
            entity,
            &live_custom_item_ids,
            &referential_custom_item_ids,
            persisted,
            fulfillment_custom_references,
        )?;
        effective_care_plans.insert(entity.client_uuid.clone(), entity.payload.clone());
    }
    let care_plan_ids = effective_care_plans
        .keys()
        .cloned()
        .collect::<BTreeSet<_>>();

    // Fulfillment candidates must reference live care_plan + record in-family.
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "fulfillment_candidate")
    {
        let plan_id = entity.payload["care_plan_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !care_plan_ids.contains(plan_id) {
            return Err(StoreError::UnresolvedReference(
                "fulfillment_candidate care_plan_client_uuid does not exist".to_owned(),
            ));
        }
        let record_id = entity.payload["record_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !record_ids.contains(record_id) {
            return Err(StoreError::UnresolvedReference(
                "fulfillment_candidate record_client_uuid does not exist".to_owned(),
            ));
        }
        let plan_baby = effective_care_plans[plan_id]["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        let record_baby = effective_records[record_id]["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if plan_baby != record_baby {
            return Err(StoreError::UnresolvedReference(
                "fulfillment_candidate record baby does not match care_plan baby".to_owned(),
            ));
        }
    }

    let mut effective_media = existing
        .iter()
        .filter(|((entity_type, _), _)| entity_type == "media")
        .map(|((_, id), entity)| {
            Ok((
                id.clone(),
                (
                    media_association(&entity.payload)?,
                    entity.deleted_at,
                    entity.updated_at,
                ),
            ))
        })
        .collect::<Result<HashMap<_, _>, StoreError>>()?;
    let mut seen_media: HashMap<String, MediaAssociation> = HashMap::new();
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "media")
    {
        let incoming = media_association(&entity.payload)?;
        if seen_media
            .insert(entity.client_uuid.clone(), incoming.clone())
            .is_some_and(|previous| previous != incoming)
        {
            return Err(StoreError::ImmutableMediaAssociation);
        }
        let existing_association = existing
            .get(&("media".to_owned(), entity.client_uuid.clone()))
            .map(|current| media_association(&current.payload))
            .transpose()?;
        if role != "owner"
            && (incoming.0 == "avatar"
                || existing_association
                    .as_ref()
                    .is_some_and(|association| association.0 == "avatar"))
        {
            return Err(StoreError::ForbiddenAvatar);
        }
        if existing_association
            .as_ref()
            .is_some_and(|association| association != &incoming)
        {
            return Err(StoreError::ImmutableMediaAssociation);
        }
        effective_media.insert(
            entity.client_uuid.clone(),
            (incoming.clone(), entity.deleted_at, entity.updated_at),
        );

        if incoming.0 == "avatar" {
            if incoming
                .2
                .as_ref()
                .is_none_or(|baby_id| !baby_ids.contains(baby_id))
            {
                return Err(StoreError::UnresolvedReference(
                    "avatar baby_client_uuid does not exist".to_owned(),
                ));
            }
        } else if let Some(care_plan_id) = incoming.3.as_ref() {
            if !care_plan_ids.contains(care_plan_id) {
                return Err(StoreError::UnresolvedReference(
                    "log media care_plan_client_uuid does not exist".to_owned(),
                ));
            }
            if role != "owner" {
                let creator = effective_care_plans[care_plan_id]
                    .get("created_by_membership_id")
                    .and_then(Value::as_str)
                    .filter(|creator| !creator.is_empty())
                    .ok_or(StoreError::InvalidStoredPayload)?;
                if creator != membership_id {
                    return Err(StoreError::ForbiddenCarePlan);
                }
            }
            let plan_baby_id = effective_care_plans[care_plan_id]["baby_client_uuid"]
                .as_str()
                .ok_or(StoreError::InvalidStoredPayload)?;
            if incoming
                .2
                .as_ref()
                .is_some_and(|baby_id| baby_id != plan_baby_id)
            {
                return Err(StoreError::UnresolvedReference(
                    "log media baby does not match care_plan baby".to_owned(),
                ));
            }
        } else {
            let record_id = incoming
                .1
                .as_ref()
                .ok_or(StoreError::InvalidStoredPayload)?;
            if !record_ids.contains(record_id) {
                return Err(StoreError::UnresolvedReference(
                    "log media record_client_uuid does not exist".to_owned(),
                ));
            }
            if role != "owner" {
                let creator = effective_records[record_id]
                    .get("created_by_membership_id")
                    .and_then(Value::as_str)
                    .filter(|creator| !creator.is_empty())
                    .ok_or(StoreError::InvalidStoredPayload)?;
                if creator != membership_id {
                    return Err(StoreError::ForbiddenRecord);
                }
            }
            let record_baby_id = effective_records[record_id]["baby_client_uuid"]
                .as_str()
                .ok_or(StoreError::InvalidStoredPayload)?;
            if incoming
                .2
                .as_ref()
                .is_some_and(|baby_id| baby_id != record_baby_id)
            {
                return Err(StoreError::UnresolvedReference(
                    "log media baby does not match record baby".to_owned(),
                ));
            }
        }
    }

    let bounded_roots = entities
        .iter()
        .filter(|entity| matches!(entity.entity_type.as_str(), "baby" | "record" | "care_plan"))
        .map(|entity| (entity.entity_type.clone(), entity.client_uuid.clone()))
        .chain(
            entities
                .iter()
                .filter(|entity| entity.entity_type == "media")
                .map(|entity| media_association(&entity.payload))
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .filter_map(|association| media_root_key(&association)),
        )
        .collect::<BTreeSet<_>>();
    let mut live_media_counts = HashMap::<EntityKey, usize>::new();
    for (association, deleted_at, _) in effective_media.values() {
        if deleted_at.is_none() {
            if let Some(root_key) = media_root_key(association) {
                if bounded_roots.contains(&root_key) {
                    *live_media_counts.entry(root_key).or_default() += 1;
                }
            }
        }
    }
    if live_media_counts
        .values()
        .any(|count| *count > MAX_BUNDLE_MEDIA_ENTITIES)
    {
        return Err(StoreError::UnresolvedReference(format!(
            "atomic root supports at most {MAX_BUNDLE_MEDIA_ENTITIES} live media items"
        )));
    }

    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "baby")
    {
        let avatar_id = entity.payload["avatar_media_uuid"]
            .as_str()
            .map(str::to_owned);
        let current = existing.get(&("baby".to_owned(), entity.client_uuid.clone()));
        let current_avatar = current
            .and_then(|value| value.payload["avatar_media_uuid"].as_str())
            .map(str::to_owned);
        if role != "owner"
            && ((current.is_none() && avatar_id.is_some())
                || (current.is_some() && avatar_id != current_avatar))
        {
            return Err(StoreError::ForbiddenAvatar);
        }
        let Some(avatar_id) = avatar_id else {
            continue;
        };
        let valid = effective_media
            .get(&avatar_id)
            .is_some_and(|(association, deleted_at, _)| {
                deleted_at.is_none()
                    && association.0 == "avatar"
                    && association.2.as_deref() == Some(entity.client_uuid.as_str())
            });
        if !valid {
            return Err(StoreError::UnresolvedReference(
                "baby avatar_media_uuid must reference avatar media for the same baby".to_owned(),
            ));
        }
    }
    Ok(())
}

fn effective_entity(
    entities: &[Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
    entity_type: &str,
    client_uuid: &str,
) -> Option<ExistingEntity> {
    entities
        .iter()
        .rev()
        .find(|entity| entity.entity_type == entity_type && entity.client_uuid == client_uuid)
        .map(|entity| ExistingEntity {
            updated_at: entity.updated_at,
            deleted_at: entity.deleted_at,
            payload: entity.payload.clone(),
        })
        .or_else(|| {
            existing
                .get(&(entity_type.to_owned(), client_uuid.to_owned()))
                .cloned()
        })
}

/// Dry-run the same canonicalization, ACL, immutable-evidence, reference, and
/// atomic-package rules that commit applies. Reconciliation calls this while
/// holding its own Store transaction; no staging or entity row is written.
pub(in crate::store) fn validate_reconcile_package(
    transaction: &Transaction<'_>,
    principal: &Principal,
    mut package: Vec<Entity>,
    max_updated_at: i64,
    now: i64,
) -> Result<(), StoreError> {
    let Principal {
        family_id,
        role,
        membership_id,
        ..
    } = principal;
    let root = package
        .iter()
        .find(|entity| entity.entity_type != "media")
        .ok_or(StoreError::InvalidStoredPayload)?;
    if role != "owner" && root.entity_type == "baby" {
        return Err(StoreError::ForbiddenBaby);
    }
    if package
        .iter()
        .any(|entity| entity.updated_at > max_updated_at)
    {
        return Err(StoreError::TimestampOutOfRange);
    }
    let incoming_keys = package.iter().map(entity_key).collect::<BTreeSet<_>>();
    let mut existing = load_existing_entities(transaction, family_id, &incoming_keys)?;
    existing.extend(load_family_custom_items(transaction, family_id)?);
    existing.extend(load_family_media(transaction, family_id)?);
    stamp_and_authorize_custom_items(role, membership_id, &mut package, &existing)?;
    let noop_care_plan_ids =
        stamp_and_authorize_care_plans(role, membership_id, &mut package, &existing)?;
    discard_media_for_noop_care_plans(&mut package, &noop_care_plan_ids);
    canonicalize_equal_lww_bundle_root(&mut package, &existing);
    canonicalize_record_authors(role, membership_id, &mut package, &existing)?;
    let confirmed_at = package
        .iter()
        .find(|entity| entity.entity_type == "fulfillment_candidate")
        .and_then(|entity| entity.payload.get("confirmed_at"))
        .and_then(Value::as_i64)
        .unwrap_or_else(|| confirmed_at_millis(now));
    stamp_and_authorize_fulfillment_candidates(
        role,
        membership_id,
        confirmed_at,
        &mut package,
        &existing,
    )?;
    let mut effective = effective_lww_winners(package.clone(), &existing);
    stamp_and_authorize_custom_items(role, membership_id, &mut effective, &existing)?;
    let noop_care_plan_ids =
        stamp_and_authorize_care_plans(role, membership_id, &mut effective, &existing)?;
    discard_media_for_noop_care_plans(&mut effective, &noop_care_plan_ids);
    stamp_and_authorize_fulfillment_candidates(
        role,
        membership_id,
        confirmed_at,
        &mut effective,
        &existing,
    )?;
    let reference_keys = validation_reference_keys(&effective);
    let missing_references = reference_keys
        .difference(&incoming_keys)
        .cloned()
        .collect::<BTreeSet<_>>();
    existing.extend(load_existing_entities(
        transaction,
        family_id,
        &missing_references,
    )?);
    let persisted = existing.clone();
    let fulfillment_custom_references =
        load_fulfillment_custom_references(transaction, family_id, &effective)?;
    for entity in &package {
        existing
            .entry(entity_key(entity))
            .or_insert_with(|| ExistingEntity {
                updated_at: entity.updated_at,
                deleted_at: entity.deleted_at,
                payload: entity.payload.clone(),
            });
    }
    validate_push(
        role,
        membership_id,
        &effective,
        &existing,
        &persisted,
        &fulfillment_custom_references,
    )
}

/// Validate one already shape-adapted canonical atomic package against the
/// current family graph. Both legacy reconciliation and causal ingress route
/// reference/association invariants through `validate_push`; wire adapters do
/// not reimplement the graph rules.
pub(in crate::store) fn validate_canonical_package_ingress(
    transaction: &Transaction<'_>,
    principal: &Principal,
    package: &[Entity],
) -> Result<(), StoreError> {
    validate_pull_entity_sizes(package)?;
    let incoming_keys = package.iter().map(entity_key).collect::<BTreeSet<_>>();
    let mut existing = load_existing_entities(transaction, &principal.family_id, &incoming_keys)?;
    existing.extend(load_family_custom_items(transaction, &principal.family_id)?);
    existing.extend(load_family_media(transaction, &principal.family_id)?);
    let reference_keys = validation_reference_keys(package);
    let missing_references = reference_keys
        .difference(&incoming_keys)
        .cloned()
        .collect::<BTreeSet<_>>();
    existing.extend(load_existing_entities(
        transaction,
        &principal.family_id,
        &missing_references,
    )?);
    let persisted = existing.clone();
    let fulfillment_custom_references =
        load_fulfillment_custom_references(transaction, &principal.family_id, package)?;
    validate_push(
        &principal.role,
        &principal.membership_id,
        package,
        &existing,
        &persisted,
        &fulfillment_custom_references,
    )
}

fn validate_pull_entity_sizes(entities: &[Entity]) -> Result<(), StoreError> {
    for entity in entities {
        let payload_bytes = serde_json::to_vec(&entity.payload)?.len();
        if payload_bytes.saturating_add(512) > PULL_ENTITY_TARGET_BYTES {
            return Err(StoreError::PullEntityTooLarge);
        }
    }
    Ok(())
}

fn validate_custom_item_reference(
    entity: &Entity,
    live_custom_item_ids: &BTreeSet<String>,
    referential_custom_item_ids: &BTreeSet<String>,
    persisted: &HashMap<EntityKey, ExistingEntity>,
    fulfillment_custom_references: &BTreeSet<(String, String)>,
) -> Result<(), StoreError> {
    let item_type = entity
        .payload
        .get("type")
        .and_then(Value::as_str)
        .ok_or(StoreError::InvalidStoredPayload)?;
    let custom_item_id = entity
        .payload
        .get("custom_item_client_uuid")
        .and_then(Value::as_str);
    match (item_type == "custom", custom_item_id) {
        (true, None) => {
            return Err(StoreError::UnresolvedReference(format!(
                "{} type custom requires custom_item_client_uuid",
                entity.entity_type
            )));
        }
        (false, Some(_)) => {
            return Err(StoreError::UnresolvedReference(format!(
                "{} custom_item_client_uuid is only valid for type custom",
                entity.entity_type
            )));
        }
        _ => {}
    }
    let Some(custom_item_id) = custom_item_id else {
        return Ok(());
    };
    if !referential_custom_item_ids.contains(custom_item_id) {
        return Err(StoreError::UnresolvedReference(format!(
            "{} custom_item_client_uuid does not exist",
            entity.entity_type
        )));
    }
    if live_custom_item_ids.contains(custom_item_id) {
        return Ok(());
    }
    let historical_entity = persisted
        .get(&(entity.entity_type.clone(), entity.client_uuid.clone()))
        .and_then(|current| current.payload.get("custom_item_client_uuid"))
        .and_then(Value::as_str)
        == Some(custom_item_id);
    let explicit_fulfillment = entity.entity_type == "record"
        && fulfillment_custom_references
            .contains(&(entity.client_uuid.clone(), custom_item_id.to_owned()));
    if !historical_entity && !explicit_fulfillment {
        return Err(StoreError::UnresolvedReference(format!(
            "{} custom_item_client_uuid is deleted and cannot be selected for a new item",
            entity.entity_type
        )));
    }
    Ok(())
}

fn media_association(payload: &Map<String, Value>) -> Result<MediaAssociation, StoreError> {
    let kind = payload["kind"]
        .as_str()
        .ok_or(StoreError::InvalidStoredPayload)?
        .to_owned();
    let record = payload
        .get("record_client_uuid")
        .and_then(Value::as_str)
        .map(str::to_owned);
    let baby = payload
        .get("baby_client_uuid")
        .and_then(Value::as_str)
        .map(str::to_owned);
    let care_plan = payload
        .get("care_plan_client_uuid")
        .and_then(Value::as_str)
        .map(str::to_owned);
    Ok((kind, record, baby, care_plan))
}

fn media_root_key(association: &MediaAssociation) -> Option<EntityKey> {
    match association.0.as_str() {
        "avatar" => association
            .2
            .as_ref()
            .map(|uuid| ("baby".to_owned(), uuid.clone())),
        "log" => association
            .1
            .as_ref()
            .map(|uuid| ("record".to_owned(), uuid.clone()))
            .or_else(|| {
                association
                    .3
                    .as_ref()
                    .map(|uuid| ("care_plan".to_owned(), uuid.clone()))
            }),
        _ => None,
    }
}

impl Store {
    /// Remove abandoned, unpublished bundle manifests before applying the
    /// per-family open-staging cap. Committed bundles are durable history and
    /// are never selected by this lifecycle.
    pub fn expire_open_staging_bundles(
        &self,
        family_id: &str,
        created_at_or_before: i64,
    ) -> Result<Vec<String>, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let bundle_ids = {
            let mut statement = transaction.prepare(
                "
                SELECT bundle_id
                FROM sync_bundles
                WHERE family_id = ?1
                  AND status = 'staging'
                  AND created_at <= ?2
                ORDER BY bundle_id COLLATE BINARY
                ",
            )?;
            let rows = statement
                .query_map(params![family_id, created_at_or_before], |row| {
                    row.get::<_, String>(0)
                })?
                .collect::<Result<Vec<_>, _>>()?;
            rows
        };
        if !bundle_ids.is_empty() {
            transaction.execute(
                "
                DELETE FROM sync_bundles
                WHERE family_id = ?1
                  AND status = 'staging'
                  AND created_at <= ?2
                ",
                params![family_id, created_at_or_before],
            )?;
        }
        transaction.commit()?;
        self.secure_database_files()?;
        Ok(bundle_ids)
    }

    /// Stage (or refresh) an atomic bundle: root + media metadata only.
    /// Nothing is visible to pull until [Self::commit_bundle].
    ///
    /// [Principal] supplies server-authenticated identity for canonical authors and
    /// CarePlan creator ACL on the publish path.
    pub fn stage_bundle(
        &self,
        principal: &Principal,
        bundle_id: &str,
        root: Entity,
        media: Vec<Entity>,
        now: i64,
    ) -> Result<BundleStageStatus, StoreError> {
        let Principal {
            family_id,
            role,
            membership_id,
            ..
        } = principal;
        if media.len() > MAX_BUNDLE_MEDIA_ENTITIES {
            return Err(StoreError::UnresolvedReference(format!(
                "bundle media must contain at most {MAX_BUNDLE_MEDIA_ENTITIES} items"
            )));
        }
        if role != "owner" && root.entity_type == "baby" {
            return Err(StoreError::ForbiddenBaby);
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;

        // Canonicalize every server-owned field before hashing so forged client
        // claims never become part of the durable package identity.
        let mut package = Vec::with_capacity(1 + media.len());
        package.push(root);
        package.extend(media);
        let incoming_keys = package.iter().map(entity_key).collect::<BTreeSet<_>>();
        let mut existing = load_existing_entities(&transaction, family_id, &incoming_keys)?;
        existing.extend(load_family_custom_items(&transaction, family_id)?);
        existing.extend(load_family_media(&transaction, family_id)?);
        stamp_and_authorize_custom_items(role, membership_id, &mut package, &existing)?;
        // CarePlan collision authorization must inspect the caller's creator
        // claim before equal-LWW canonicalization replaces it with the published
        // payload; two offline creates can share the same millisecond revision.
        let noop_care_plan_ids =
            stamp_and_authorize_care_plans(role, membership_id, &mut package, &existing)?;
        discard_media_for_noop_care_plans(&mut package, &noop_care_plan_ids);
        canonicalize_equal_lww_bundle_root(&mut package, &existing);
        canonicalize_record_authors(role, membership_id, &mut package, &existing)?;
        stamp_and_authorize_fulfillment_candidates(
            role,
            membership_id,
            confirmed_at_millis(now),
            &mut package,
            &existing,
        )?;
        let reference_keys = validation_reference_keys(&package);
        let missing_references = reference_keys
            .difference(&incoming_keys)
            .cloned()
            .collect::<BTreeSet<_>>();
        existing.extend(load_existing_entities(
            &transaction,
            family_id,
            &missing_references,
        )?);
        let persisted = existing.clone();
        let fulfillment_custom_references =
            load_fulfillment_custom_references(&transaction, family_id, &package)?;
        let _ = validate_deferred_fulfillment_resolutions(&transaction, family_id, &package, None)?;
        for entity in &package {
            existing.insert(
                entity_key(entity),
                ExistingEntity {
                    updated_at: entity.updated_at,
                    deleted_at: entity.deleted_at,
                    payload: entity.payload.clone(),
                },
            );
        }
        validate_push(
            role,
            membership_id,
            &package,
            &existing,
            &persisted,
            &fulfillment_custom_references,
        )?;
        let root = package
            .iter()
            .find(|entity| entity.entity_type != "media")
            .cloned()
            .ok_or(StoreError::InvalidStoredPayload)?;
        let media: Vec<Entity> = package
            .into_iter()
            .filter(|entity| entity.entity_type == "media")
            .collect();
        let content_hash = bundle_content_hash(&root, &media)?;

        if let Some(existing_bundle) = load_bundle_row(&transaction, family_id, bundle_id)? {
            if existing_bundle.status == "committed" {
                if existing_bundle.content_hash != content_hash {
                    return Err(StoreError::BundleContentConflict);
                }
                return bundle_stage_status_from_row(&transaction, family_id, &existing_bundle);
            }
            if existing_bundle.staged_membership_id != *membership_id {
                return Err(StoreError::BundleMembershipMismatch);
            }
            // Replace open staging with the new package (same bundle_id retry/refine).
            transaction.execute(
                "DELETE FROM sync_bundle_media WHERE family_id = ?1 AND bundle_id = ?2",
                params![family_id, bundle_id],
            )?;
            transaction.execute(
                "DELETE FROM sync_bundles WHERE family_id = ?1 AND bundle_id = ?2",
                params![family_id, bundle_id],
            )?;
        } else {
            let open_count: i64 = transaction.query_row(
                "
            SELECT COUNT(*) FROM sync_bundles
            WHERE family_id = ?1 AND status = 'staging'
            ",
                params![family_id],
                |row| row.get(0),
            )?;
            if open_count >= MAX_OPEN_STAGING_BUNDLES_PER_FAMILY as i64 {
                return Err(StoreError::BundleStagingLimit);
            }
        }

        let media_entities_json = serde_json::to_string(&media)?;
        transaction.execute(
            "
        INSERT INTO sync_bundles(
            family_id, bundle_id, staged_membership_id, status,
            root_type, root_client_uuid, root_updated_at, root_deleted_at,
            root_payload_json, media_entities_json, content_hash, created_at
        ) VALUES (?1, ?2, ?3, 'staging', ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
        ",
            params![
                family_id,
                bundle_id,
                membership_id,
                root.entity_type,
                root.client_uuid,
                root.updated_at,
                root.deleted_at,
                serde_json::to_string(&root.payload)?,
                media_entities_json,
                content_hash,
                now,
            ],
        )?;
        for entity in &media {
            let declared = entity
                .payload
                .get("byte_size")
                .and_then(Value::as_i64)
                .filter(|_| entity.deleted_at.is_none());
            transaction.execute(
                "
            INSERT INTO sync_bundle_media(
                family_id, bundle_id, media_uuid, declared_byte_size
            ) VALUES (?1, ?2, ?3, ?4)
            ",
                params![family_id, bundle_id, entity.client_uuid, declared],
            )?;
        }
        transaction.commit()?;
        self.secure_database_files()?;
        self.bundle_status(family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)
    }

    pub fn bundle_status(
        &self,
        family_id: &str,
        bundle_id: &str,
    ) -> Result<Option<BundleStageStatus>, StoreError> {
        let connection = self.connect()?;
        let Some(row) = load_bundle_row(&connection, family_id, bundle_id)? else {
            return Ok(None);
        };
        Ok(Some(bundle_stage_status_from_row(
            &connection,
            family_id,
            &row,
        )?))
    }

    pub fn load_bundle(
        &self,
        family_id: &str,
        bundle_id: &str,
    ) -> Result<Option<StoredBundle>, StoreError> {
        let connection = self.connect()?;
        let Some(row) = load_bundle_row(&connection, family_id, bundle_id)? else {
            return Ok(None);
        };
        let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
        let required = required_live_media_uuids(&media);
        let media_integrity = load_bundle_media_integrity(&connection, family_id, bundle_id)?;
        Ok(Some(StoredBundle {
            media,
            required_media: required,
            media_integrity,
            status: row.status,
            staged_membership_id: row.staged_membership_id,
        }))
    }

    pub fn deferred_fulfillment_media_integrity_for_bundle(
        &self,
        family_id: &str,
        bundle_id: &str,
    ) -> Result<BTreeMap<String, BundleMediaIntegrity>, StoreError> {
        let connection = self.connect()?;
        let row = load_bundle_row(&connection, family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)?;
        if row.root_type != "record" || row.root_deleted_at.is_some() {
            return Ok(BTreeMap::new());
        }
        let mut integrity = BTreeMap::new();
        for (plan_id, _) in
            load_deferred_plan_rows_for_record(&connection, family_id, &row.root_client_uuid)?
        {
            integrity.extend(load_deferred_plan_media_integrity(
                &connection,
                family_id,
                &plan_id,
            )?);
        }
        Ok(integrity)
    }

    /// Record that staged bytes for a manifest media UUID are durable.
    pub fn mark_bundle_media_staged(
        &self,
        principal: &Principal,
        bundle_id: &str,
        media_uuid: &str,
        staged_byte_size: usize,
        staged_sha256: &str,
        now: i64,
    ) -> Result<BundleStageStatus, StoreError> {
        let family_id = &principal.family_id;
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let _row = load_open_staging_bundle_for_membership(
            &transaction,
            family_id,
            bundle_id,
            &principal.membership_id,
        )?
        .ok_or(StoreError::BundleMediaUploadClosed)?;
        let updated = transaction.execute(
            "
        UPDATE sync_bundle_media
        SET staged_byte_size = ?1, staged_sha256 = ?2, staged_at = ?3
        WHERE family_id = ?4 AND bundle_id = ?5 AND media_uuid = ?6
        ",
            params![
                staged_byte_size as i64,
                staged_sha256,
                now,
                family_id,
                bundle_id,
                media_uuid
            ],
        )?;
        if updated == 0 {
            return Err(StoreError::BundleMediaNotInManifest);
        }
        // Size must match declared byte_size for live media.
        let declared: Option<i64> = transaction.query_row(
            "
        SELECT declared_byte_size FROM sync_bundle_media
        WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
        ",
            params![family_id, bundle_id, media_uuid],
            |r| r.get(0),
        )?;
        if let Some(declared) = declared {
            if declared != staged_byte_size as i64 {
                // Clear the bad staging mark so commit still sees it missing.
                transaction.execute(
                    "
                UPDATE sync_bundle_media
                SET staged_byte_size = NULL, staged_sha256 = NULL, staged_at = NULL
                WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
                ",
                    params![family_id, bundle_id, media_uuid],
                )?;
                transaction.commit()?;
                return Err(StoreError::BundleMediaIncomplete);
            }
        }
        transaction.commit()?;
        self.secure_database_files()?;
        self.bundle_status(family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)
    }

    /// Publish a complete package in one transaction. Idempotent after success.
    ///
    /// `media_ready` maps each live media UUID to an exact, durable filesystem check.
    /// The caller publishes and fsyncs media before entering this transaction, and
    /// revalidates already-committed retries against the persisted upload digest.
    pub fn commit_bundle(
        &self,
        principal: &Principal,
        bundle_id: &str,
        media_ready: &BTreeMap<String, bool>,
        max_updated_at: i64,
        now: i64,
    ) -> Result<(BundleCommitResult, Vec<Entity>), StoreError> {
        let Principal {
            family_id,
            role,
            membership_id,
            ..
        } = principal;
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let row = load_bundle_row(&transaction, family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)?;

        if row.staged_membership_id != *membership_id {
            return Err(StoreError::BundleMembershipMismatch);
        }

        let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
        for entity in &media {
            if entity.deleted_at.is_some() {
                continue;
            }
            if !media_ready
                .get(&entity.client_uuid)
                .copied()
                .unwrap_or(false)
            {
                return Err(StoreError::BundleMediaIncomplete);
            }
        }

        if row.status == "committed" {
            let cursor = row.committed_cursor.unwrap_or(0);
            let applied = row.committed_applied.unwrap_or(0) as usize;
            let root = Entity {
                entity_type: row.root_type,
                client_uuid: row.root_client_uuid,
                updated_at: row.root_updated_at,
                deleted_at: row.root_deleted_at,
                payload: parse_payload(&row.root_payload_json)?,
            };
            let record_authors = if root.entity_type == "record" {
                load_persisted_record_author(&transaction, family_id, root.client_uuid.as_str())?
                    .or_else(|| record_author_acknowledgement(&root.client_uuid, &root.payload))
                    .into_iter()
                    .collect()
            } else {
                Vec::new()
            };
            let mut package = vec![root];
            package.extend(media);
            return Ok((
                BundleCommitResult {
                    bundle_id: bundle_id.to_owned(),
                    status: "committed".to_owned(),
                    applied,
                    cursor,
                    record_authors,
                },
                package,
            ));
        }

        let root = Entity {
            entity_type: row.root_type.clone(),
            client_uuid: row.root_client_uuid.clone(),
            updated_at: row.root_updated_at,
            deleted_at: row.root_deleted_at,
            payload: parse_payload(&row.root_payload_json)?,
        };

        if role != "owner" && root.entity_type == "baby" {
            return Err(StoreError::ForbiddenBaby);
        }

        // Historical pre-causal Store/API fixtures still exercise the old atomic
        // bundle contract. Once a stable head exists, however, timestamp LWW can
        // never advance that root; current Android clients use causal commit for
        // every mutable root and reserve this path for FulfillmentCandidate.
        let causal_head_exists = transaction
            .query_row(
                "SELECT 1 FROM entity_stable_heads
                 WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3",
                params![family_id, root.entity_type, root.client_uuid],
                |_| Ok(()),
            )
            .optional()?
            .is_some();
        if causal_head_exists {
            return Err(StoreError::LegacyBundleCausalRootUnsupported(
                root.entity_type.clone(),
            ));
        }

        if root.updated_at > max_updated_at
            || media
                .iter()
                .any(|entity| entity.updated_at > max_updated_at)
        {
            return Err(StoreError::TimestampOutOfRange);
        }

        // Record ordinary stale-root conflicts now, but defer rejection until
        // after inspecting the full package. A cross-creator next-feed create
        // intentionally becomes a successful whole-package no-op even when the
        // NAS winner has a newer timestamp.
        let root_key = entity_key(&root);
        let existing_root =
            load_existing_entities(&transaction, family_id, &BTreeSet::from([root_key.clone()]))?;
        let stale_published_root = existing_root
            .get(&root_key)
            .is_some_and(|published| published.updated_at > root.updated_at);

        let mut package = Vec::with_capacity(1 + media.len());
        package.push(root);
        package.extend(media.iter().cloned());

        let original_count = package.len();
        let incoming_keys = package.iter().map(entity_key).collect::<BTreeSet<_>>();
        let mut existing = load_existing_entities(&transaction, family_id, &incoming_keys)?;
        existing.extend(load_family_custom_items(&transaction, family_id)?);
        existing.extend(load_family_media(&transaction, family_id)?);
        stamp_and_authorize_custom_items(role, membership_id, &mut package, &existing)?;
        // A competing CarePlan can publish after this bundle was staged. Inspect
        // the complete staged package before LWW removes an equal/stale root so a
        // cross-creator next-feed no-op also discards every attached media change.
        let noop_care_plan_ids =
            stamp_and_authorize_care_plans(role, membership_id, &mut package, &existing)?;
        discard_media_for_noop_care_plans(&mut package, &noop_care_plan_ids);
        if stale_published_root && noop_care_plan_ids.is_empty() {
            return Err(StoreError::BundleRootNotNewer);
        }
        canonicalize_equal_lww_bundle_root(&mut package, &existing);
        canonicalize_record_authors(role, membership_id, &mut package, &existing)?;
        let confirmed_at = package
            .iter()
            .find(|entity| entity.entity_type == "fulfillment_candidate")
            .and_then(|entity| entity.payload.get("confirmed_at"))
            .and_then(Value::as_i64)
            .unwrap_or_else(|| confirmed_at_millis(now));
        stamp_and_authorize_fulfillment_candidates(
            role,
            membership_id,
            confirmed_at,
            &mut package,
            &existing,
        )?;
        let canonical_root = package
            .iter()
            .find(|entity| entity.entity_type != "media")
            .ok_or(StoreError::InvalidStoredPayload)?;
        let record_authors = if canonical_root.entity_type == "record" {
            record_author_acknowledgement(&canonical_root.client_uuid, &canonical_root.payload)
                .into_iter()
                .collect()
        } else {
            Vec::new()
        };
        let canonical_media = package
            .iter()
            .filter(|entity| entity.entity_type == "media")
            .cloned()
            .collect::<Vec<_>>();
        let canonical_media_ids = canonical_media
            .iter()
            .map(|entity| entity.client_uuid.as_str())
            .collect::<BTreeSet<_>>();
        let discarded_media_ids = media
            .iter()
            .filter(|entity| !canonical_media_ids.contains(entity.client_uuid.as_str()))
            .map(|entity| entity.client_uuid.clone())
            .collect::<Vec<_>>();
        let canonical_root_payload_json = serde_json::to_string(&canonical_root.payload)?;
        let canonical_media_entities_json = serde_json::to_string(&canonical_media)?;
        let canonical_content_hash = bundle_content_hash(canonical_root, &canonical_media)?;
        let mut effective = effective_lww_winners(package.clone(), &existing);
        // Re-stamp/authorize winners on commit so a staged package cannot bypass
        // ACL after membership role changes or concurrent publication.
        stamp_and_authorize_custom_items(role, membership_id, &mut effective, &existing)?;
        let noop_care_plan_ids =
            stamp_and_authorize_care_plans(role, membership_id, &mut effective, &existing)?;
        discard_media_for_noop_care_plans(&mut effective, &noop_care_plan_ids);
        stamp_and_authorize_fulfillment_candidates(
            role,
            membership_id,
            confirmed_at,
            &mut effective,
            &existing,
        )?;
        validate_pull_entity_sizes(&effective)?;
        let reference_keys = validation_reference_keys(&effective);
        let missing_references = reference_keys
            .difference(&incoming_keys)
            .cloned()
            .collect::<BTreeSet<_>>();
        existing.extend(load_existing_entities(
            &transaction,
            family_id,
            &missing_references,
        )?);
        let persisted = existing.clone();
        let fulfillment_custom_references =
            load_fulfillment_custom_references(&transaction, family_id, &effective)?;
        let deferred_fulfillment_resolutions = validate_deferred_fulfillment_resolutions(
            &transaction,
            family_id,
            &effective,
            Some(media_ready),
        )?;
        // Intra-package references: treat full package (not only LWW winners) as present.
        for entity in &package {
            existing
                .entry(entity_key(entity))
                .or_insert_with(|| ExistingEntity {
                    updated_at: entity.updated_at,
                    deleted_at: entity.deleted_at,
                    payload: entity.payload.clone(),
                });
        }
        validate_push(
            role,
            membership_id,
            &effective,
            &existing,
            &persisted,
            &fulfillment_custom_references,
        )?;

        let mut cursor: i64 = transaction.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![family_id],
            |row| row.get(0),
        )?;
        effective.sort_by_key(|entity| match entity.entity_type.as_str() {
            "baby" => 0,
            "record" | "care_plan" => 1,
            "fulfillment_candidate" => 2,
            _ => 3,
        });
        let entity_count = effective.len();
        for entity in &effective {
            cursor += 1;
            transaction.execute(
                "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at,
                deleted_at, payload_json, rev
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
            ON CONFLICT(family_id, entity_type, client_uuid) DO UPDATE SET
                updated_at = excluded.updated_at,
                deleted_at = excluded.deleted_at,
                payload_json = excluded.payload_json,
                rev = excluded.rev
            ",
                params![
                    family_id,
                    entity.entity_type,
                    entity.client_uuid,
                    entity.updated_at,
                    entity.deleted_at,
                    serde_json::to_string(&entity.payload)?,
                    cursor
                ],
            )?;
        }
        transaction.execute(
            "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
            params![cursor, family_id],
        )?;
        // Ownership follows only media that actually won this transaction's
        // LWW decision. A losing manifest entry must not publish quarantined
        // bytes merely because an unrelated live metadata row already exists.
        for entity in effective
            .iter()
            .filter(|entity| entity.entity_type == "media")
        {
            if entity.deleted_at.is_some() {
                transaction.execute(
                    "
                DELETE FROM media_publications
                WHERE family_id = ?1 AND media_uuid = ?2
                ",
                    params![family_id, entity.client_uuid],
                )?;
                continue;
            }
            if !media_ready
                .get(&entity.client_uuid)
                .copied()
                .unwrap_or(false)
            {
                return Err(StoreError::BundleMediaIncomplete);
            }
            transaction.execute(
                "
            INSERT INTO media_publications(
                family_id, media_uuid, source, bundle_id
            ) VALUES (?1, ?2, 'bundle', ?3)
            ON CONFLICT(family_id, media_uuid) DO UPDATE SET
                source = 'bundle',
                bundle_id = excluded.bundle_id
            ",
                params![family_id, entity.client_uuid, bundle_id],
            )?;
        }
        transaction.execute(
            "
        UPDATE sync_bundles
        SET status = 'committed',
            committed_at = ?1,
            committed_cursor = ?2,
            committed_applied = ?3,
            root_deleted_at = ?4,
            root_payload_json = ?5,
            media_entities_json = ?6,
            content_hash = ?7
        WHERE family_id = ?8 AND bundle_id = ?9
        ",
            params![
                now,
                cursor,
                entity_count as i64,
                canonical_root.deleted_at,
                canonical_root_payload_json,
                canonical_media_entities_json,
                canonical_content_hash,
                family_id,
                bundle_id
            ],
        )?;
        // Dropped manifest rows with no bundle-pending ownership are already
        // safe to forget (for example, a UUID owned by a legitimate prior
        // publication). Pending rows stay as durable cleanup evidence until the
        // handler deletes the exact final-path file and acknowledges cleanup.
        for media_uuid in discarded_media_ids {
            transaction.execute(
                "
            DELETE FROM sync_bundle_media
            WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
              AND NOT EXISTS (
                  SELECT 1 FROM media_publications
                  WHERE family_id = ?1
                    AND media_uuid = ?3
                    AND source = 'bundle_pending'
                    AND bundle_id = ?2
              )
            ",
                params![family_id, bundle_id, media_uuid],
            )?;
        }
        transaction.commit()?;
        self.secure_database_files()?;
        for (care_plan_client_uuid, record_client_uuid) in
            deferred_fulfillment_resolutions.into_iter().take(32)
        {
            tracing::info!(
                family_id,
                entity_type = "care_plan",
                client_uuid = %care_plan_client_uuid,
                record_client_uuid = %record_client_uuid,
                reason_code = "deferred_fulfillment_resolved",
                "deferred legacy fulfillment joined the public authority graph"
            );
        }
        let _ = original_count;
        Ok((
            BundleCommitResult {
                bundle_id: bundle_id.to_owned(),
                status: "committed".to_owned(),
                applied: entity_count,
                cursor,
                record_authors,
            },
            package,
        ))
    }
}
