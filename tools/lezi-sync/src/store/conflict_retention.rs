//! Bounded conflict metadata retention.
//!
//! Resolved conflicts retain one compact terminal receipt, resolver audit,
//! stable result, and version provenance forever. This module is the single
//! owner that, after a grace period, removes only snapshot choice material and
//! closed branch metadata proven unreachable. Media bytes, staging state, and
//! publications are deliberately outside this module (hardening H19/H24).

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};

use super::causal::load_validated_version;
use super::conflict_snapshots::{
    validate_snapshot_receipts_for_retention, ConflictSnapshotBinding,
};
use super::{migration_content_hash, Store, StoreError};

const RETENTION_PRINCIPAL: &str = "__conflict_retention_v2__";
const SNAPSHOT_PRINCIPAL: &str = "__conflict_snapshot_v2__";
const MARKER_CONTRACT: &str = "conflict-retention-v2";
const RESOLVED_METADATA_GRACE_SECONDS: i64 = 24 * 60 * 60;
const RESOLUTION_GC_BATCH: usize = 8;
const RETENTION_PENDING_PREFIX: &str = "retention-pending:";
const RETENTION_COMPLETE_PREFIX: &str = "retention-complete:";
const RETENTION_CANDIDATES_SQL: &str =
    "SELECT c.family_id, c.conflict_id, c.kind, c.entity_type, c.client_uuid,
            r.expected_stable_version_id, r.expected_branch_versions_json,
            r.resolved_version_id, c.resolved_at,
            (SELECT json_group_array(branch_version_id) FROM (
                SELECT branch_version_id FROM conflict_branches b
                 WHERE b.family_id = c.family_id AND b.conflict_id = c.conflict_id
                 ORDER BY branch_version_id COLLATE BINARY LIMIT 65)),
            marker.content_hash, marker.receipt_json
       FROM mutation_receipts marker INDEXED BY mutation_receipts_lookup
       JOIN conflicts c
         ON c.family_id = marker.family_id AND c.conflict_id = marker.conflict_id
       JOIN conflict_resolutions r
         ON r.family_id = c.family_id AND r.conflict_id = c.conflict_id
      WHERE marker.family_id = ?1 AND marker.membership_id = ?2
        AND marker.mutation_id >= ?3 AND marker.mutation_id < ?4
        AND json_extract(marker.receipt_json, '$.phase') = 'pending'
        AND c.status = 'resolved'
      ORDER BY marker.mutation_id
      LIMIT ?5";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
enum RetentionPhase {
    Pending,
    Complete,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct RetentionMarker {
    contract: String,
    conflict_id: String,
    expected_stable_version_id: String,
    expected_branch_versions_hash: String,
    resolved_version_id: String,
    eligible_at: i64,
    phase: RetentionPhase,
    completed_at: Option<i64>,
}

#[derive(Debug)]
struct RetentionCandidate {
    family_id: String,
    conflict_id: String,
    kind: String,
    entity_type: String,
    client_uuid: String,
    expected_stable_version_id: String,
    expected_branch_versions_json: String,
    resolved_version_id: String,
    resolved_at: i64,
    current_branch_versions_json: String,
    marker_hash: Option<String>,
    marker_json: Option<String>,
}

impl RetentionCandidate {
    fn expected_branches(&self) -> Result<Vec<String>, StoreError> {
        canonical_branch_ids(&self.expected_branch_versions_json)
    }

    fn current_branches(&self) -> Result<Vec<String>, StoreError> {
        canonical_branch_ids(&self.current_branch_versions_json)
    }

    fn pending_marker(&self) -> Result<RetentionMarker, StoreError> {
        Ok(RetentionMarker {
            contract: MARKER_CONTRACT.to_owned(),
            conflict_id: self.conflict_id.clone(),
            expected_stable_version_id: self.expected_stable_version_id.clone(),
            expected_branch_versions_hash: branch_set_hash(&self.expected_branch_versions_json),
            resolved_version_id: self.resolved_version_id.clone(),
            eligible_at: self
                .resolved_at
                .saturating_add(RESOLVED_METADATA_GRACE_SECONDS),
            phase: RetentionPhase::Pending,
            completed_at: None,
        })
    }

    fn stored_marker(&self) -> Result<Option<RetentionMarker>, StoreError> {
        match (&self.marker_hash, &self.marker_json) {
            (None, None) => Ok(None),
            (Some(stored_hash), Some(json)) => {
                let marker: RetentionMarker = serde_json::from_str(json)?;
                if marker != self.pending_marker()? && marker.phase != RetentionPhase::Complete {
                    return Err(StoreError::InvalidStoredPayload);
                }
                validate_marker(self, &marker, stored_hash, json)?;
                Ok(Some(marker))
            }
            _ => Err(StoreError::InvalidStoredPayload),
        }
    }
}

fn canonical_branch_ids(json: &str) -> Result<Vec<String>, StoreError> {
    let values: Vec<String> = serde_json::from_str(json)?;
    if values.len() > 64
        || values.windows(2).any(|pair| pair[0] >= pair[1])
        || serde_json::to_string(&values)? != json
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(values)
}

fn branch_set_hash(branches_json: &str) -> String {
    migration_content_hash(&[MARKER_CONTRACT, "branches", branches_json])
}

fn marker_hash(marker_json: &str) -> String {
    migration_content_hash(&[MARKER_CONTRACT, "marker", marker_json])
}

fn sortable_time(value: i64) -> String {
    format!("{:016x}", (value as u64) ^ (1_u64 << 63))
}

fn retention_key(eligible_at: i64, conflict_id: &str) -> String {
    format!(
        "{RETENTION_PENDING_PREFIX}{}:{conflict_id}",
        sortable_time(eligible_at)
    )
}

fn retention_upper_bound(now: i64) -> String {
    format!("{RETENTION_PENDING_PREFIX}{};", sortable_time(now))
}

fn complete_retention_key(conflict_id: &str) -> String {
    format!("{RETENTION_COMPLETE_PREFIX}{conflict_id}")
}

fn validate_marker(
    candidate: &RetentionCandidate,
    marker: &RetentionMarker,
    stored_hash: &str,
    stored_json: &str,
) -> Result<(), StoreError> {
    if marker.contract != MARKER_CONTRACT
        || marker.conflict_id != candidate.conflict_id
        || marker.expected_stable_version_id != candidate.expected_stable_version_id
        || marker.expected_branch_versions_hash
            != branch_set_hash(&candidate.expected_branch_versions_json)
        || marker.resolved_version_id != candidate.resolved_version_id
        || marker.eligible_at
            != candidate
                .resolved_at
                .saturating_add(RESOLVED_METADATA_GRACE_SECONDS)
        || (marker.phase == RetentionPhase::Pending && marker.completed_at.is_some())
        || (marker.phase == RetentionPhase::Complete && marker.completed_at.is_none())
        || marker
            .completed_at
            .is_some_and(|completed_at| completed_at < marker.eligible_at)
        || serde_json::to_string(marker)? != stored_json
        || marker_hash(stored_json) != stored_hash
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(())
}

fn retention_candidates(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    now: i64,
    batch_limit: usize,
) -> Result<Vec<RetentionCandidate>, StoreError> {
    let mut statement = tx.prepare(RETENTION_CANDIDATES_SQL)?;
    let rows = statement
        .query_map(
            params![
                family_id,
                RETENTION_PRINCIPAL,
                RETENTION_PENDING_PREFIX,
                retention_upper_bound(now),
                (batch_limit + 1) as i64,
            ],
            |row| {
                Ok(RetentionCandidate {
                    family_id: row.get(0)?,
                    conflict_id: row.get(1)?,
                    kind: row.get(2)?,
                    entity_type: row.get(3)?,
                    client_uuid: row.get(4)?,
                    expected_stable_version_id: row.get(5)?,
                    expected_branch_versions_json: row.get(6)?,
                    resolved_version_id: row.get(7)?,
                    resolved_at: row.get(8)?,
                    current_branch_versions_json: row.get(9)?,
                    marker_hash: row.get(10)?,
                    marker_json: row.get(11)?,
                })
            },
        )?
        .collect::<Result<Vec<_>, _>>()?;
    if rows.len() > batch_limit {
        Ok(rows.into_iter().take(batch_limit).collect())
    } else {
        Ok(rows)
    }
}

#[cfg(test)]
pub(in crate::store) fn candidate_query_plan(
    store: &Store,
    family_id: &str,
    now: i64,
) -> Result<Vec<String>, StoreError> {
    let connection = store.connect()?;
    let mut statement =
        connection.prepare(&format!("EXPLAIN QUERY PLAN {RETENTION_CANDIDATES_SQL}"))?;
    let rows = statement
        .query_map(
            params![
                family_id,
                RETENTION_PRINCIPAL,
                RETENTION_PENDING_PREFIX,
                retention_upper_bound(now),
                (RESOLUTION_GC_BATCH + 1) as i64,
            ],
            |row| row.get::<_, String>(3),
        )?
        .collect::<Result<Vec<_>, _>>()
        .map_err(StoreError::from)?;
    Ok(rows)
}

fn insert_pending_marker(
    tx: &rusqlite::Transaction<'_>,
    candidate: &RetentionCandidate,
    marker: &RetentionMarker,
) -> Result<(), StoreError> {
    let json = serde_json::to_string(marker)?;
    tx.execute(
        "INSERT INTO mutation_receipts(
            family_id, membership_id, entity_type, client_uuid, mutation_id,
            content_hash, status, stable_version_id, branch_version_id,
            conflict_id, receipt_json, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'accepted', ?7, NULL, ?8, ?9, ?10)",
        params![
            candidate.family_id,
            RETENTION_PRINCIPAL,
            candidate.entity_type,
            candidate.client_uuid,
            retention_key(marker.eligible_at, &candidate.conflict_id),
            marker_hash(&json),
            candidate.resolved_version_id,
            candidate.conflict_id,
            json,
            candidate.resolved_at,
        ],
    )?;
    Ok(())
}

fn branch_is_unreachable(
    tx: &rusqlite::Transaction<'_>,
    candidate: &RetentionCandidate,
    branch_id: &str,
) -> Result<bool, StoreError> {
    tx.query_row(
        "SELECT NOT EXISTS(
                SELECT 1 FROM entity_stable_heads
                 WHERE family_id = ?1 AND version_id = ?2
            ) AND NOT EXISTS(
                SELECT 1 FROM conflicts
                 WHERE family_id = ?1
                   AND (stable_version_id = ?2 OR base_version_id = ?2)
            ) AND NOT EXISTS(
                SELECT 1 FROM conflict_branches
                 WHERE family_id = ?1 AND branch_version_id = ?2
                   AND conflict_id != ?3
            ) AND NOT EXISTS(
                SELECT 1 FROM entity_version_parents
                 WHERE family_id = ?1 AND parent_version_id = ?2
            ) AND NOT EXISTS(
                SELECT 1 FROM conflict_resolutions
                 WHERE family_id = ?1 AND resolved_version_id = ?2
            )",
        params![candidate.family_id, branch_id, candidate.conflict_id],
        |row| row.get(0),
    )
    .map_err(StoreError::from)
}

fn compact_candidate(
    tx: &rusqlite::Transaction<'_>,
    database_path: &std::path::Path,
    candidate: &RetentionCandidate,
    snapshot_receipt_key: &[u8],
    now: i64,
) -> Result<(), StoreError> {
    let marker = candidate
        .stored_marker()?
        .ok_or(StoreError::InvalidStoredPayload)?;
    if marker.phase != RetentionPhase::Pending || now < marker.eligible_at {
        return Err(StoreError::InvalidStoredPayload);
    }
    let expected = candidate.expected_branches()?;
    let current = candidate.current_branches()?;
    if current != expected {
        return Err(StoreError::InvalidStoredPayload);
    }
    for branch_id in &current {
        let Some((entity_type, client_uuid, _)) =
            load_validated_version(tx, database_path, &candidate.family_id, branch_id)?
        else {
            return Err(StoreError::InvalidStoredPayload);
        };
        if entity_type != candidate.entity_type
            || client_uuid != candidate.client_uuid
            || !branch_is_unreachable(tx, candidate, branch_id)?
        {
            return Err(StoreError::InvalidStoredPayload);
        }
    }

    let snapshot_receipt: Option<(String, String)> = tx
        .query_row(
            "SELECT stable_version_id, receipt_json FROM mutation_receipts
              WHERE family_id = ?1 AND membership_id = ?2
                AND entity_type = ?3 AND client_uuid = ?4
                AND mutation_id = ?5 AND conflict_id = ?6",
            params![
                candidate.family_id,
                SNAPSHOT_PRINCIPAL,
                candidate.entity_type,
                candidate.client_uuid,
                format!("snapshot-receipts:{}", candidate.conflict_id),
                candidate.conflict_id,
            ],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let Some((snapshot_stable_version, snapshot_json)) = snapshot_receipt else {
        return Err(StoreError::InvalidStoredPayload);
    };
    if snapshot_stable_version != candidate.expected_stable_version_id {
        return Err(StoreError::InvalidStoredPayload);
    }
    let expected_fingerprint = ConflictSnapshotBinding {
        family_id: &candidate.family_id,
        conflict_id: &candidate.conflict_id,
        kind: &candidate.kind,
        entity_type: &candidate.entity_type,
        client_uuid: &candidate.client_uuid,
        stable_version_id: &candidate.expected_stable_version_id,
        branch_version_ids: &expected,
        receipt_key: snapshot_receipt_key,
    }
    .fingerprint();
    validate_snapshot_receipts_for_retention(
        &snapshot_json,
        snapshot_receipt_key,
        &expected_fingerprint,
    )?;

    tx.execute(
        "DELETE FROM conflict_branches WHERE family_id = ?1 AND conflict_id = ?2",
        params![candidate.family_id, candidate.conflict_id],
    )?;
    for branch_id in &current {
        let deleted = tx.execute(
            "DELETE FROM entity_versions WHERE family_id = ?1 AND version_id = ?2",
            params![candidate.family_id, branch_id],
        )?;
        if deleted != 1 {
            return Err(StoreError::InvalidStoredPayload);
        }
    }
    tx.execute(
        "DELETE FROM mutation_receipts
          WHERE family_id = ?1 AND membership_id = ?2
            AND entity_type = ?3 AND client_uuid = ?4
            AND mutation_id = ?5 AND conflict_id = ?6",
        params![
            candidate.family_id,
            SNAPSHOT_PRINCIPAL,
            candidate.entity_type,
            candidate.client_uuid,
            format!("snapshot-receipts:{}", candidate.conflict_id),
            candidate.conflict_id,
        ],
    )?;

    let complete = RetentionMarker {
        phase: RetentionPhase::Complete,
        completed_at: Some(now),
        ..marker
    };
    let json = serde_json::to_string(&complete)?;
    let updated = tx.execute(
        "UPDATE mutation_receipts
            SET mutation_id = ?1, content_hash = ?2, receipt_json = ?3
          WHERE family_id = ?4 AND membership_id = ?5
            AND entity_type = ?6 AND client_uuid = ?7 AND mutation_id = ?8",
        params![
            complete_retention_key(&candidate.conflict_id),
            marker_hash(&json),
            json,
            candidate.family_id,
            RETENTION_PRINCIPAL,
            candidate.entity_type,
            candidate.client_uuid,
            retention_key(complete.eligible_at, &candidate.conflict_id),
        ],
    )?;
    if updated != 1 {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(())
}

impl Store {
    /// Compact one bounded batch. SQLite's immediate transactions make the
    /// mark and sweep phases restart-safe; filesystem/media state is untouched.
    pub fn gc_conflict_metadata(&self, now: i64) -> Result<usize, StoreError> {
        let connection = self.connect()?;
        let family_ids = {
            let mut statement = connection.prepare("SELECT id FROM families ORDER BY id")?;
            let rows = statement
                .query_map([], |row| row.get::<_, String>(0))?
                .collect::<Result<Vec<_>, _>>()?;
            rows
        };
        drop(connection);
        let mut compacted = 0;
        for family_id in family_ids {
            let remaining = RESOLUTION_GC_BATCH.saturating_sub(compacted);
            if remaining == 0 {
                break;
            }
            compacted += self.gc_conflict_metadata_for_family_batch(&family_id, now, remaining)?;
        }
        Ok(compacted)
    }

    pub(crate) fn gc_conflict_metadata_for_family(
        &self,
        family_id: &str,
        now: i64,
    ) -> Result<usize, StoreError> {
        self.gc_conflict_metadata_for_family_batch(family_id, now, RESOLUTION_GC_BATCH)
    }

    fn gc_conflict_metadata_for_family_batch(
        &self,
        family_id: &str,
        now: i64,
        batch_limit: usize,
    ) -> Result<usize, StoreError> {
        #[cfg(test)]
        if test_hook::take_fail_after_mark() {
            return Err(StoreError::InvalidStoredPayload);
        }

        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let candidates = retention_candidates(&tx, family_id, now, batch_limit)?;
        let mut compacted = 0;
        for candidate in &candidates {
            let Some(marker) = candidate.stored_marker()? else {
                return Err(StoreError::InvalidStoredPayload);
            };
            if marker.phase == RetentionPhase::Complete {
                continue;
            }
            compact_candidate(
                &tx,
                &self.database_path,
                candidate,
                &self.snapshot_receipt_key,
                now,
            )?;
            compacted += 1;
        }
        tx.commit()?;
        Ok(compacted)
    }
}

pub(super) struct ResolutionRetentionBinding<'a> {
    pub family_id: &'a str,
    pub conflict_id: &'a str,
    pub kind: &'a str,
    pub entity_type: &'a str,
    pub client_uuid: &'a str,
    pub expected_stable_version_id: &'a str,
    pub expected_branch_versions_json: &'a str,
    pub resolved_version_id: &'a str,
    pub resolved_at: i64,
}

pub(super) fn stage_resolution_retention(
    tx: &rusqlite::Transaction<'_>,
    binding: ResolutionRetentionBinding<'_>,
) -> Result<(), StoreError> {
    let candidate = RetentionCandidate {
        family_id: binding.family_id.to_owned(),
        conflict_id: binding.conflict_id.to_owned(),
        kind: binding.kind.to_owned(),
        entity_type: binding.entity_type.to_owned(),
        client_uuid: binding.client_uuid.to_owned(),
        expected_stable_version_id: binding.expected_stable_version_id.to_owned(),
        expected_branch_versions_json: binding.expected_branch_versions_json.to_owned(),
        resolved_version_id: binding.resolved_version_id.to_owned(),
        resolved_at: binding.resolved_at,
        current_branch_versions_json: binding.expected_branch_versions_json.to_owned(),
        marker_hash: None,
        marker_json: None,
    };
    let marker = candidate.pending_marker()?;
    insert_pending_marker(tx, &candidate, &marker)
}

pub(super) struct TerminalRetentionBinding<'a> {
    pub family_id: &'a str,
    pub conflict_id: &'a str,
    pub kind: &'a str,
    pub entity_type: &'a str,
    pub client_uuid: &'a str,
    pub expected_stable_version_id: &'a str,
    pub expected_branch_versions_json: &'a str,
    pub resolved_version_id: &'a str,
    pub resolved_at: i64,
}

pub(super) fn terminal_metadata_was_compacted(
    tx: &rusqlite::Transaction<'_>,
    binding: TerminalRetentionBinding<'_>,
) -> Result<bool, StoreError> {
    let marker = tx
        .query_row(
            "SELECT content_hash, receipt_json FROM mutation_receipts
              WHERE family_id = ?1 AND membership_id = ?2
                AND entity_type = ?3 AND client_uuid = ?4
                AND mutation_id = ?5",
            params![
                binding.family_id,
                RETENTION_PRINCIPAL,
                binding.entity_type,
                binding.client_uuid,
                complete_retention_key(binding.conflict_id),
            ],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()?;
    let Some((stored_hash, stored_json)) = marker else {
        return Ok(false);
    };
    let candidate = RetentionCandidate {
        family_id: binding.family_id.to_owned(),
        conflict_id: binding.conflict_id.to_owned(),
        kind: binding.kind.to_owned(),
        entity_type: binding.entity_type.to_owned(),
        client_uuid: binding.client_uuid.to_owned(),
        expected_stable_version_id: binding.expected_stable_version_id.to_owned(),
        expected_branch_versions_json: binding.expected_branch_versions_json.to_owned(),
        resolved_version_id: binding.resolved_version_id.to_owned(),
        resolved_at: binding.resolved_at,
        current_branch_versions_json: "[]".to_owned(),
        marker_hash: Some(stored_hash),
        marker_json: Some(stored_json),
    };
    Ok(candidate
        .stored_marker()?
        .is_some_and(|marker| marker.phase == RetentionPhase::Complete))
}

#[cfg(test)]
pub(super) mod test_hook {
    use std::sync::atomic::{AtomicBool, Ordering};

    static FAIL_AFTER_MARK: AtomicBool = AtomicBool::new(false);

    pub(crate) fn fail_after_mark_once() {
        FAIL_AFTER_MARK.store(true, Ordering::SeqCst);
    }

    pub(super) fn take_fail_after_mark() -> bool {
        FAIL_AFTER_MARK.swap(false, Ordering::SeqCst)
    }
}
