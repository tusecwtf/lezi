//! Membership authorship redaction for hard-delete and offline migrate.

use std::collections::BTreeSet;
use std::path::Path;

use rusqlite::{params, Transaction};
use serde_json::{Map, Value};

use super::super::causal_merge::{mutation_content_hash, CausalMediaItem};
use super::super::{confirmed_at_millis, migration_content_hash, parse_payload, StoreError};

/// Payload JSON keys that attribute a care fact to a membership.
///
/// Cleared on hard-delete and offline-migrate when the membership is removed
/// (current contract: null author/submitter, not rewrite to another member).
/// Future authorship fields must be added here so hard-delete and migrate stay aligned.
pub(crate) const MEMBERSHIP_AUTHORSHIP_PAYLOAD_KEYS: &[&str] = &[
    "created_by_membership_id",
    "submitter_membership_id",
    "observer_membership_id",
];

/// Null authorship fields whose string value is accepted by `should_clear`.
/// Returns how many fields were cleared.
pub(crate) fn anonymize_membership_authorship_fields(
    payload: &mut Map<String, Value>,
    should_clear: impl Fn(&str) -> bool,
) -> u64 {
    let mut cleared = 0u64;
    for key in MEMBERSHIP_AUTHORSHIP_PAYLOAD_KEYS {
        let points = payload
            .get(*key)
            .and_then(Value::as_str)
            .is_some_and(&should_clear);
        if points {
            payload.insert((*key).to_owned(), Value::Null);
            cleared += 1;
        }
    }
    cleared
}

/// Null authorship fields that point at a single membership (hard-delete path).
pub(super) fn anonymize_membership_fields(
    payload: &mut Map<String, Value>,
    membership_id: &str,
) -> bool {
    anonymize_membership_authorship_fields(payload, |id| id == membership_id) > 0
}

pub(super) fn anonymize_membership_entity_references(
    transaction: &Transaction<'_>,
    family_id: &str,
    membership_id: &str,
    now: i64,
) -> Result<(), StoreError> {
    let rows = {
        let mut statement = transaction.prepare(
            "
            SELECT entity_type, client_uuid, updated_at, payload_json
            FROM entities
            WHERE family_id = ?1
            ORDER BY rev ASC
            ",
        )?;
        let rows = statement
            .query_map(params![family_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, String>(3)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        rows
    };
    let mut next_rev: i64 = transaction.query_row(
        "SELECT rev FROM family_meta WHERE family_id = ?1",
        params![family_id],
        |row| row.get(0),
    )?;
    let server_updated_at = confirmed_at_millis(now);
    for (entity_type, client_uuid, updated_at, payload_json) in rows {
        let mut payload = parse_payload(&payload_json)?;
        if !anonymize_membership_fields(&mut payload, membership_id) {
            continue;
        }
        next_rev = next_rev.saturating_add(1);
        let anonymous_updated_at = server_updated_at.max(updated_at.saturating_add(1));
        transaction.execute(
            "
            UPDATE entities
            SET payload_json = ?1, updated_at = ?2, rev = ?3
            WHERE family_id = ?4 AND entity_type = ?5 AND client_uuid = ?6
            ",
            params![
                serde_json::to_string(&payload)?,
                anonymous_updated_at,
                next_rev,
                family_id,
                entity_type,
                client_uuid,
            ],
        )?;
    }
    transaction.execute(
        "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
        params![next_rev, family_id],
    )?;
    Ok(())
}

/// ADR-0025's narrowly scoped exception to immutable history. Version IDs,
/// nursing values, parent edges, media and original request hashes stay intact.
/// Only identity metadata is rewritten, with each storage integrity hash rebuilt.
pub(super) fn anonymize_membership_history(
    transaction: &Transaction<'_>,
    database_path: &Path,
    snapshot_receipt_key: &[u8],
    family_id: &str,
    membership_id: &str,
) -> Result<(), StoreError> {
    let device_ids = {
        let mut statement =
            transaction.prepare("SELECT device_id FROM devices WHERE membership_id = ?1")?;
        let rows = statement
            .query_map(params![membership_id], |row| row.get::<_, String>(0))?
            .collect::<Result<BTreeSet<_>, _>>()?;
        rows
    };
    let versions = {
        let mut statement = transaction.prepare(
            "SELECT version_id, entity_type, client_uuid, payload_json, updated_at, deleted_at, origin, content_hash, mutation_id
             FROM entity_versions WHERE family_id = ?1",
        )?;
        let rows = statement
            .query_map(params![family_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, i64>(4)?,
                    row.get::<_, Option<i64>>(5)?,
                    row.get::<_, String>(6)?,
                    row.get::<_, String>(7)?,
                    row.get::<_, Option<String>>(8)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        rows
    };
    let mut affected_roots = BTreeSet::new();
    for (
        version_id,
        entity_type,
        client_uuid,
        payload_json,
        updated_at,
        deleted_at,
        origin,
        original_hash,
        mutation_id,
    ) in versions
    {
        let mut root = parse_payload(&payload_json)?;
        if !anonymize_membership_fields(&mut root, membership_id) {
            continue;
        }
        let redacted_payload_json = serde_json::to_string(&root)?;
        let media = {
            let mut statement = transaction.prepare(
                "SELECT media_uuid, media_payload_json FROM entity_version_media
                 WHERE family_id = ?1 AND version_id = ?2 ORDER BY media_uuid",
            )?;
            let rows = statement
                .query_map(params![family_id, version_id], |row| {
                    Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            rows
        };
        let content_hash = if origin == "migration_base" {
            // Legacy media deliberately retain nullable/long raw MIME values.
            // Privacy redaction must validate the original stored evidence,
            // not coerce it through the narrower canonical media wire codec.
            let updated = updated_at.to_string();
            let deleted = deleted_at
                .map(|value| value.to_string())
                .unwrap_or_default();
            let hash = |payload: &str| {
                let mut parts = vec![updated.as_str(), deleted.as_str(), payload];
                for (id, media_payload) in &media {
                    parts.extend([id.as_str(), media_payload.as_str()]);
                }
                migration_content_hash(&parts)
            };
            if mutation_id.is_some() || hash(&payload_json) != original_hash {
                return Err(StoreError::InvalidStoredPayload);
            }
            for (media_id, raw) in &media {
                let payload: Value = serde_json::from_str(raw)?;
                if let Some(item) = CausalMediaItem::from_value(&payload) {
                    if item.media_uuid != *media_id
                        || item.validate_for_entity(&entity_type).is_err()
                    {
                        return Err(StoreError::InvalidStoredPayload);
                    }
                } else {
                    let payload = payload
                        .as_object()
                        .ok_or(StoreError::InvalidStoredPayload)?;
                    if super::super::media_association_owner(payload)?
                        != Some((entity_type.as_str(), client_uuid.as_str()))
                    {
                        return Err(StoreError::InvalidStoredPayload);
                    }
                }
            }
            hash(&redacted_payload_json)
        } else {
            // Never bless already-corrupted canonical care content by
            // replacing its old integrity hash with a newly computed value.
            super::super::causal::load_validated_version(
                transaction,
                database_path,
                family_id,
                &version_id,
            )?
            .ok_or(StoreError::InvalidStoredPayload)?;
            root.entry("updated_at".to_owned())
                .or_insert_with(|| Value::Number(updated_at.into()));
            let manifest = media
                .iter()
                .map(|(_, payload)| serde_json::from_str::<CausalMediaItem>(payload))
                .collect::<Result<Vec<_>, _>>()?;
            mutation_content_hash("_", "_", None, deleted_at.is_some(), &root, &manifest)
        };
        transaction.execute(
            "UPDATE entity_versions SET payload_json = ?1, content_hash = ?2
             WHERE family_id = ?3 AND version_id = ?4",
            params![redacted_payload_json, content_hash, family_id, version_id],
        )?;
        affected_roots.insert((entity_type, client_uuid));
    }

    let receipts = {
        let mut statement = transaction.prepare(
            "SELECT rowid, membership_id, entity_type, client_uuid, receipt_json
             FROM mutation_receipts WHERE family_id = ?1",
        )?;
        let rows = statement
            .query_map(params![family_id], |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, String>(4)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        rows
    };
    for (rowid, principal_id, entity_type, client_uuid, json) in receipts {
        let mut receipt: Value = serde_json::from_str(&json)?;
        let changed = anonymize_cached_identity(&mut receipt, membership_id, &device_ids);
        if changed {
            transaction.execute(
                "UPDATE mutation_receipts SET receipt_json = ?1 WHERE rowid = ?2",
                params![serde_json::to_string(&receipt)?, rowid],
            )?;
            affected_roots.insert((entity_type, client_uuid));
        }
        if principal_id == membership_id {
            // The departed principal can no longer authenticate. Preserve the
            // opaque receipt without its identity-bearing lookup key. One
            // uncorrelated key per row avoids collisions across deleted members
            // whose legal mutation IDs happened to match. No mapping survives.
            transaction.execute(
                "UPDATE mutation_receipts SET membership_id = ?1 WHERE rowid = ?2",
                params![
                    format!("__anonymous_receipt__:{}", uuid::Uuid::new_v4()),
                    rowid
                ],
            )?;
        }
    }
    for (entity_type, client_uuid) in affected_roots {
        super::super::conflict_snapshots::invalidate_identity_snapshot_receipts(
            transaction,
            snapshot_receipt_key,
            family_id,
            &entity_type,
            &client_uuid,
        )?;
    }

    let resolutions = {
        let mut statement = transaction.prepare(
            "SELECT rowid, conflict_choices_json FROM conflict_resolutions WHERE family_id = ?1",
        )?;
        let rows = statement
            .query_map(params![family_id], |row| {
                Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        rows
    };
    for (rowid, json) in resolutions {
        // This receipt has a typed canonical-byte invariant on replay. Keep
        // its serializer ordering rather than round-tripping through Value.
        let mut receipt: super::super::causal::StoredResolutionReceipt =
            serde_json::from_str(&json)?;
        if anonymize_membership_fields(&mut receipt.result.stable_root, membership_id) {
            transaction.execute(
                "UPDATE conflict_resolutions SET conflict_choices_json = ?1 WHERE rowid = ?2",
                params![serde_json::to_string(&receipt)?, rowid],
            )?;
        }
    }
    // These NOT NULL columns have always represented attribution, not a
    // foreign-key ownership grant. Empty means anonymous and preserves facts.
    for (table, column) in [
        ("conflict_resolutions", "resolver_membership_id"),
        ("source_relations", "created_by_membership_id"),
        ("source_relation_declarations", "author_membership_id"),
        ("source_relation_record_eligibility", "author_membership_id"),
        ("causal_media_staging", "membership_id"),
        ("causal_media_uploads", "membership_id"),
    ] {
        transaction.execute(
            &format!("UPDATE {table} SET {column} = '' WHERE family_id = ?1 AND {column} = ?2"),
            params![family_id, membership_id],
        )?;
    }
    Ok(())
}

// The closed wire keeps actor/device IDs as nonempty strings. A single shared
// sentinel is compatible with old readers and does not link anonymous actors.
const ANONYMOUS_PROVENANCE: &str = "__anonymous__";

/// Closed identity field names only: user-authored nursing strings, IDs of
/// facts and mutations, and opaque request hashes are never string-replaced.
fn anonymize_cached_identity(
    value: &mut Value,
    membership_id: &str,
    device_ids: &BTreeSet<String>,
) -> bool {
    let mut changed = false;
    match value {
        Value::Object(object) => {
            changed |= anonymize_membership_fields(object, membership_id);
            if object.get("actor_id").and_then(Value::as_str) == Some(membership_id) {
                object.insert(
                    "actor_id".to_owned(),
                    Value::String(ANONYMOUS_PROVENANCE.to_owned()),
                );
                if object.contains_key("device_id") {
                    object.insert(
                        "device_id".to_owned(),
                        Value::String(ANONYMOUS_PROVENANCE.to_owned()),
                    );
                }
                changed = true;
            }
            if object
                .get("device_id")
                .and_then(Value::as_str)
                .is_some_and(|id| device_ids.contains(id))
            {
                object.insert(
                    "device_id".to_owned(),
                    Value::String(ANONYMOUS_PROVENANCE.to_owned()),
                );
                changed = true;
            }
            for child in object.values_mut() {
                changed |= anonymize_cached_identity(child, membership_id, device_ids);
            }
        }
        Value::Array(values) => {
            for child in values {
                changed |= anonymize_cached_identity(child, membership_id, device_ids);
            }
        }
        _ => {}
    }
    changed
}
