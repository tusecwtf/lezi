//! Membership authorship redaction for hard-delete and offline migrate.

use rusqlite::{params, Transaction};
use serde_json::{Map, Value};

use super::super::{confirmed_at_millis, parse_payload, StoreError};

/// Payload JSON keys that attribute a care fact to a membership.
///
/// Cleared on hard-delete and offline-migrate when the membership is removed
/// (current contract: null author/submitter, not rewrite to another member).
/// Future authorship fields must be added here so hard-delete and migrate stay aligned.
pub(crate) const MEMBERSHIP_AUTHORSHIP_PAYLOAD_KEYS: &[&str] =
    &["created_by_membership_id", "submitter_membership_id"];

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
