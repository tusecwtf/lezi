//! Root-scoped media reads using the existing causal-head and manifest indexes.
//!
//! A valid headed root's live projection is exactly its stable manifest. Startup
//! authority validation checks that invariant in both directions; ordinary causal
//! writes preserve it in the same transaction. Incoming UUIDs are still looked up
//! independently, including tombstones, so cross-root reuse cannot hide here.
//!
//! Existing headless legacy roots retain the old association query. This explicit
//! compatibility fallback is linear in family media, and is not a bounded-work
//! claim for legacy graphs. No schema object or cache is introduced.

use std::collections::BTreeSet;

use rusqlite::{params, OptionalExtension, Transaction};

use super::{media_association_owner, parse_payload, StoreError};

pub(in crate::store) fn live_ids_for_root(
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
        _ => return Ok(BTreeSet::new()),
    };
    let head: Option<String> = tx
        .query_row(
            "SELECT version_id FROM entity_stable_heads
             WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3",
            params![family_id, entity_type, client_uuid],
            |row| row.get(0),
        )
        .optional()?;
    let Some(version_id) = head else {
        let exists = tx
            .query_row(
                "SELECT 1 FROM entities
                 WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3",
                params![family_id, entity_type, client_uuid],
                |_| Ok(()),
            )
            .optional()?
            .is_some();
        if !exists {
            // A valid graph cannot contain live media for an absent root.
            return Ok(BTreeSet::new());
        }
        return legacy_live_ids_for_root(tx, family_id, kind, field, client_uuid);
    };
    let version: Option<(String, String, Option<i64>)> = tx
        .query_row(
            "SELECT entity_type, client_uuid, deleted_at FROM entity_versions
             WHERE family_id = ?1 AND version_id = ?2",
            params![family_id, version_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let Some((version_type, version_root, deleted_at)) = version else {
        return Err(StoreError::InvalidStoredPayload);
    };
    if version_type != entity_type || version_root != client_uuid {
        return Err(StoreError::InvalidStoredPayload);
    }
    // Separate indexed queries avoid a planner choosing the family-media range
    // as the outer side of a join. Work is proportional to this manifest only.
    let mut manifest = tx.prepare(
        "SELECT media_uuid FROM entity_version_media
         WHERE family_id = ?1 AND version_id = ?2 ORDER BY media_uuid",
    )?;
    let ids = manifest
        .query_map(params![family_id, version_id], |row| {
            row.get::<_, String>(0)
        })?
        .collect::<Result<BTreeSet<_>, _>>()?;
    let mut projection = tx.prepare(
        "SELECT deleted_at, payload_json FROM entities
         WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
    )?;
    let mut live = BTreeSet::new();
    for id in ids {
        let row: Option<(Option<i64>, String)> = projection
            .query_row(params![family_id, &id], |row| {
                Ok((row.get(0)?, row.get(1)?))
            })
            .optional()?;
        let raw = match row {
            Some((None, raw)) => raw,
            // Tombstone manifests can retain historical preimages; offline
            // migration can also retain live media for a deleted legacy owner.
            None | Some((Some(_), _)) if deleted_at.is_some() => continue,
            _ => return Err(StoreError::InvalidStoredPayload),
        };
        let payload = parse_payload(&raw)?;
        if media_association_owner(&payload)? != Some((entity_type, client_uuid)) {
            return Err(StoreError::ImmutableMediaAssociation);
        }
        live.insert(id);
    }
    Ok(live)
}

fn legacy_live_ids_for_root(
    tx: &Transaction<'_>,
    family_id: &str,
    kind: &str,
    field: &str,
    client_uuid: &str,
) -> Result<BTreeSet<String>, StoreError> {
    let sql = format!(
        "SELECT client_uuid FROM entities
         WHERE family_id = ?1 AND entity_type = 'media' AND deleted_at IS NULL
           AND json_extract(payload_json, '$.kind') = ?2
           AND json_extract(payload_json, '$.{field}') = ?3"
    );
    let mut statement = tx.prepare(&sql)?;
    let rows = statement
        .query_map(params![family_id, kind, client_uuid], |row| row.get(0))?
        .collect::<Result<BTreeSet<_>, _>>()?;
    Ok(rows)
}
