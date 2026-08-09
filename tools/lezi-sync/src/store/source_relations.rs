//! Non-destructive source relations (wire §12 / ADR-0021).
//!
//! Author declare and Owner group resolve select a display version and retain
//! every other root/media as a source relation. Records stay live entities
//! (`deleted_at` is never set for provenance hiding).

use std::collections::{BTreeMap, BTreeSet};

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use serde::Serialize;
use uuid::Uuid;

use super::{Principal, Store, StoreError};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeclareSourceRelationInput {
    pub mutation_id: String,
    pub record_client_uuid: String,
    pub equivalent_to_client_uuid: String,
    pub expected_record_version: String,
    pub expected_other_version: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct SourceRelationReceipt {
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub relation_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub display_client_uuid: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_client_uuids: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media_retained: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    /// On CAS mismatch: latest known versions for the named records.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub latest_versions: Option<BTreeMap<String, String>>,
}

#[derive(Debug, Clone)]
pub struct ResolveSourceRelationGroupInput {
    pub mutation_id: String,
    pub member_client_uuids: Vec<String>,
    pub display_client_uuid: String,
    pub expected_versions: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct SourceRelationSummary {
    pub relation_id: String,
    pub role: String,
    pub peer_ids: Vec<String>,
}

impl Store {
    /// Author declares their record equivalent to another (wire §12.1).
    /// Author's record becomes `source`; the other becomes `display`.
    pub fn declare_source_relation(
        &self,
        principal: &Principal,
        input: DeclareSourceRelationInput,
        now: i64,
    ) -> Result<SourceRelationReceipt, StoreError> {
        if input.mutation_id.trim().is_empty() || input.mutation_id.len() > 128 {
            return Err(StoreError::InvalidSourceRelationRequest(
                "mutation_id is invalid",
            ));
        }
        if input.record_client_uuid == input.equivalent_to_client_uuid {
            return Err(StoreError::InvalidSourceRelationRequest(
                "record_client_uuid must differ from equivalent_to_client_uuid",
            ));
        }
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;

        // Idempotent mutation replay.
        if let Some(existing) =
            load_relation_by_mutation(&tx, &principal.family_id, &input.mutation_id)?
        {
            return Ok(relation_receipt(&existing));
        }

        let record_author =
            live_record_author(&tx, &principal.family_id, &input.record_client_uuid)?;
        let Some(author) = record_author else {
            return Err(StoreError::InvalidSourceRelationRequest(
                "record_client_uuid is not a live record",
            ));
        };
        if author != principal.membership_id {
            return Err(StoreError::ForbiddenRecord);
        }
        let other_live =
            live_record_author(&tx, &principal.family_id, &input.equivalent_to_client_uuid)?;
        if other_live.is_none() {
            return Err(StoreError::InvalidSourceRelationRequest(
                "equivalent_to_client_uuid is not a live record",
            ));
        }

        let record_version = stable_version(
            &tx,
            &principal.family_id,
            "record",
            &input.record_client_uuid,
        )?;
        let other_version = stable_version(
            &tx,
            &principal.family_id,
            "record",
            &input.equivalent_to_client_uuid,
        )?;
        let mut latest = BTreeMap::new();
        if let Some(v) = &record_version {
            latest.insert(input.record_client_uuid.clone(), v.clone());
        }
        if let Some(v) = &other_version {
            latest.insert(input.equivalent_to_client_uuid.clone(), v.clone());
        }
        if record_version.as_deref() != Some(input.expected_record_version.as_str())
            || other_version.as_deref() != Some(input.expected_other_version.as_str())
        {
            // Persist pending declaration for audit; return CAS without writing relation.
            tx.execute(
                "
                INSERT INTO source_relation_declarations(
                    family_id, mutation_id, record_client_uuid, equivalent_to_client_uuid,
                    expected_record_version, expected_other_version, author_membership_id,
                    status, created_at
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'superseded', ?8)
                ON CONFLICT(family_id, mutation_id) DO NOTHING
                ",
                params![
                    principal.family_id,
                    input.mutation_id,
                    input.record_client_uuid,
                    input.equivalent_to_client_uuid,
                    input.expected_record_version,
                    input.expected_other_version,
                    principal.membership_id,
                    now,
                ],
            )?;
            tx.commit()?;
            return Ok(SourceRelationReceipt {
                status: "cas_mismatch".to_owned(),
                relation_id: None,
                display_client_uuid: None,
                source_client_uuids: None,
                media_retained: None,
                code: Some("cas_mismatch".to_owned()),
                latest_versions: Some(latest),
            });
        }

        let relation_id = Uuid::new_v4().to_string();
        insert_relation(
            &tx,
            InsertRelationArgs {
                family_id: &principal.family_id,
                relation_id: &relation_id,
                display: &input.equivalent_to_client_uuid,
                sources: &[&input.record_client_uuid],
                reason: "author_declare",
                mutation_id: &input.mutation_id,
                created_by: &principal.membership_id,
                now,
            },
        )?;
        tx.execute(
            "
            INSERT INTO source_relation_declarations(
                family_id, mutation_id, record_client_uuid, equivalent_to_client_uuid,
                expected_record_version, expected_other_version, author_membership_id,
                status, created_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'consumed', ?8)
            ",
            params![
                principal.family_id,
                input.mutation_id,
                input.record_client_uuid,
                input.equivalent_to_client_uuid,
                input.expected_record_version,
                input.expected_other_version,
                principal.membership_id,
                now,
            ],
        )?;
        // Provenance: entities stay live — no deleted_at writes.
        assert_records_still_live(
            &tx,
            &principal.family_id,
            &[&input.record_client_uuid, &input.equivalent_to_client_uuid],
        )?;
        bump_relation_member_revisions(
            &tx,
            &principal.family_id,
            &[&input.record_client_uuid, &input.equivalent_to_client_uuid],
        )?;
        tx.commit()?;
        Ok(SourceRelationReceipt {
            status: "accepted".to_owned(),
            relation_id: Some(relation_id),
            display_client_uuid: Some(input.equivalent_to_client_uuid),
            source_client_uuids: Some(vec![input.record_client_uuid]),
            media_retained: Some(true),
            code: None,
            latest_versions: None,
        })
    }

    /// Owner resolves a complete suspected-duplicate group (wire §12.2).
    pub fn resolve_source_relation_group(
        &self,
        principal: &Principal,
        input: ResolveSourceRelationGroupInput,
        now: i64,
    ) -> Result<SourceRelationReceipt, StoreError> {
        if principal.role != "owner" {
            return Err(StoreError::ForbiddenRecord);
        }
        if input.mutation_id.trim().is_empty() || input.mutation_id.len() > 128 {
            return Err(StoreError::InvalidSourceRelationRequest(
                "mutation_id is invalid",
            ));
        }
        let mut members: Vec<String> = input.member_client_uuids.clone();
        members.sort();
        members.dedup();
        if members.len() < 2 {
            return Err(StoreError::InvalidSourceRelationRequest(
                "member_client_uuids must contain at least two records",
            ));
        }
        if !members.iter().any(|m| m == &input.display_client_uuid) {
            return Err(StoreError::InvalidSourceRelationRequest(
                "display_client_uuid must be in member_client_uuids",
            ));
        }
        // Complete expected version set (CAS): every member must be named.
        let expected_keys: BTreeSet<_> = input.expected_versions.keys().cloned().collect();
        let member_set: BTreeSet<_> = members.iter().cloned().collect();
        if expected_keys != member_set {
            return Err(StoreError::InvalidSourceRelationRequest(
                "expected_versions must cover the complete member set",
            ));
        }

        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;

        if let Some(existing) =
            load_relation_by_mutation(&tx, &principal.family_id, &input.mutation_id)?
        {
            return Ok(relation_receipt(&existing));
        }

        let mut latest = BTreeMap::new();
        let mut cas_ok = true;
        for uuid in &members {
            let live = live_record_author(&tx, &principal.family_id, uuid)?;
            if live.is_none() {
                return Err(StoreError::InvalidSourceRelationRequest(
                    "member is not a live record",
                ));
            }
            let version = stable_version(&tx, &principal.family_id, "record", uuid)?;
            if let Some(v) = &version {
                latest.insert(uuid.clone(), v.clone());
            }
            let expected = input.expected_versions.get(uuid).map(String::as_str);
            if version.as_deref() != expected {
                cas_ok = false;
            }
        }
        if !cas_ok {
            tx.commit()?;
            return Ok(SourceRelationReceipt {
                status: "cas_mismatch".to_owned(),
                relation_id: None,
                display_client_uuid: None,
                source_client_uuids: None,
                media_retained: None,
                code: Some("cas_mismatch".to_owned()),
                latest_versions: Some(latest),
            });
        }

        // Closed-group check: named members must equal the full soft-group
        // connected component so a concurrent third neighbor cannot be 漏收.
        if let Some(missing) =
            incomplete_component_member(&tx, &principal.family_id, &members, &member_set)?
        {
            latest.insert(missing.clone(), String::new());
            tx.commit()?;
            return Ok(SourceRelationReceipt {
                status: "cas_mismatch".to_owned(),
                relation_id: None,
                display_client_uuid: None,
                source_client_uuids: None,
                media_retained: None,
                code: Some("incomplete_group".to_owned()),
                latest_versions: Some(latest),
            });
        }

        let sources: Vec<&str> = members
            .iter()
            .filter(|u| *u != &input.display_client_uuid)
            .map(String::as_str)
            .collect();
        let relation_id = Uuid::new_v4().to_string();
        insert_relation(
            &tx,
            InsertRelationArgs {
                family_id: &principal.family_id,
                relation_id: &relation_id,
                display: &input.display_client_uuid,
                sources: &sources,
                reason: "owner_group_resolve",
                mutation_id: &input.mutation_id,
                created_by: &principal.membership_id,
                now,
            },
        )?;
        let member_refs: Vec<&str> = members.iter().map(String::as_str).collect();
        assert_records_still_live(&tx, &principal.family_id, &member_refs)?;
        bump_relation_member_revisions(&tx, &principal.family_id, &member_refs)?;
        tx.commit()?;
        Ok(SourceRelationReceipt {
            status: "accepted".to_owned(),
            relation_id: Some(relation_id),
            display_client_uuid: Some(input.display_client_uuid),
            source_client_uuids: Some(sources.iter().map(|s| (*s).to_owned()).collect()),
            media_retained: Some(true),
            code: None,
            latest_versions: None,
        })
    }
}

struct StoredRelation {
    relation_id: String,
    display_client_uuid: String,
    source_client_uuids: Vec<String>,
}

fn relation_receipt(rel: &StoredRelation) -> SourceRelationReceipt {
    SourceRelationReceipt {
        status: "accepted".to_owned(),
        relation_id: Some(rel.relation_id.clone()),
        display_client_uuid: Some(rel.display_client_uuid.clone()),
        source_client_uuids: Some(rel.source_client_uuids.clone()),
        media_retained: Some(true),
        code: None,
        latest_versions: None,
    }
}

fn load_relation_by_mutation(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    mutation_id: &str,
) -> Result<Option<StoredRelation>, StoreError> {
    let row = tx
        .query_row(
            "
            SELECT relation_id, display_client_uuid
            FROM source_relations
            WHERE family_id = ?1 AND mutation_id = ?2
            LIMIT 1
            ",
            params![family_id, mutation_id],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()?;
    let Some((relation_id, display)) = row else {
        return Ok(None);
    };
    let mut sources = Vec::new();
    {
        let mut stmt = tx.prepare(
            "
            SELECT record_client_uuid FROM source_relation_members
            WHERE family_id = ?1 AND relation_id = ?2 AND role = 'source'
            ORDER BY record_client_uuid COLLATE BINARY
            ",
        )?;
        let rows = stmt.query_map(params![family_id, relation_id], |row| {
            row.get::<_, String>(0)
        })?;
        for r in rows {
            sources.push(r?);
        }
    }
    Ok(Some(StoredRelation {
        relation_id,
        display_client_uuid: display,
        source_client_uuids: sources,
    }))
}

fn live_record_author(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    client_uuid: &str,
) -> Result<Option<String>, StoreError> {
    let payload_json: Option<String> = tx
        .query_row(
            "
            SELECT payload_json FROM entities
            WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
              AND deleted_at IS NULL
            ",
            params![family_id, client_uuid],
            |row| row.get(0),
        )
        .optional()?;
    let Some(payload_json) = payload_json else {
        return Ok(None);
    };
    let payload: serde_json::Map<String, serde_json::Value> = serde_json::from_str(&payload_json)?;
    let author = payload
        .get("created_by_membership_id")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .map(str::to_owned);
    Ok(author)
}

fn stable_version(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<Option<String>, StoreError> {
    Ok(tx
        .query_row(
            "
            SELECT version_id FROM entity_stable_heads
            WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3
            ",
            params![family_id, entity_type, client_uuid],
            |row| row.get(0),
        )
        .optional()?)
}

struct InsertRelationArgs<'a> {
    family_id: &'a str,
    relation_id: &'a str,
    display: &'a str,
    sources: &'a [&'a str],
    reason: &'a str,
    mutation_id: &'a str,
    created_by: &'a str,
    now: i64,
}

fn insert_relation(
    tx: &rusqlite::Transaction<'_>,
    args: InsertRelationArgs<'_>,
) -> Result<(), StoreError> {
    let InsertRelationArgs {
        family_id,
        relation_id,
        display,
        sources,
        reason,
        mutation_id,
        created_by,
        now,
    } = args;
    tx.execute(
        "
        INSERT INTO source_relations(
            family_id, relation_id, display_client_uuid, media_retained,
            reason, mutation_id, created_by_membership_id, created_at
        ) VALUES (?1, ?2, ?3, 1, ?4, ?5, ?6, ?7)
        ",
        params![
            family_id,
            relation_id,
            display,
            reason,
            mutation_id,
            created_by,
            now
        ],
    )?;
    tx.execute(
        "
        INSERT INTO source_relation_members(family_id, relation_id, record_client_uuid, role)
        VALUES (?1, ?2, ?3, 'display')
        ",
        params![family_id, relation_id, display],
    )?;
    for source in sources {
        tx.execute(
            "
            INSERT INTO source_relation_members(family_id, relation_id, record_client_uuid, role)
            VALUES (?1, ?2, ?3, 'source')
            ",
            params![family_id, relation_id, source],
        )?;
    }
    Ok(())
}

fn assert_records_still_live(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    uuids: &[&str],
) -> Result<(), StoreError> {
    for uuid in uuids {
        let deleted: Option<i64> = tx
            .query_row(
                "
                SELECT deleted_at FROM entities
                WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
                ",
                params![family_id, uuid],
                |row| row.get(0),
            )
            .optional()?
            .flatten();
        if deleted.is_some() {
            return Err(StoreError::InvalidStoredPayload);
        }
    }
    Ok(())
}

fn bump_relation_member_revisions(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    member_client_uuids: &[&str],
) -> Result<(), StoreError> {
    let mut members = member_client_uuids.to_vec();
    members.sort_unstable();
    members.dedup();
    for client_uuid in members {
        let rev = super::causal::advance_rev(tx, family_id)?;
        let changed = tx.execute(
            "UPDATE entities SET rev = ?1
             WHERE family_id = ?2 AND entity_type = 'record' AND client_uuid = ?3",
            params![rev, family_id, client_uuid],
        )?;
        if changed != 1 {
            return Err(StoreError::InvalidStoredPayload);
        }
    }
    Ok(())
}

/// If any live whitelist neighbor within 30min of the named set is missing from
/// the named set, return that uuid (first missing, sorted). Same baby+type only.
fn incomplete_component_member(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    members: &[String],
    member_set: &BTreeSet<String>,
) -> Result<Option<String>, StoreError> {
    use crate::store::suspected_duplicates::{
        is_suspected_duplicate_type, SUSPECTED_DUPLICATE_WINDOW,
    };

    #[derive(Clone)]
    struct Row {
        uuid: String,
        baby: String,
        record_type: String,
        ts: i64,
        author: String,
    }
    let mut named: Vec<Row> = Vec::new();
    for uuid in members {
        let payload_json: String = tx.query_row(
            "
            SELECT payload_json FROM entities
            WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
              AND deleted_at IS NULL
            ",
            params![family_id, uuid],
            |row| row.get(0),
        )?;
        let payload: serde_json::Map<String, serde_json::Value> =
            serde_json::from_str(&payload_json)?;
        let record_type = payload
            .get("type")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_owned();
        if !is_suspected_duplicate_type(&record_type) {
            continue;
        }
        let baby = payload
            .get("baby_client_uuid")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_owned();
        let ts = payload
            .get("timestamp")
            .and_then(|v| v.as_i64())
            .unwrap_or(0);
        let author = payload
            .get("created_by_membership_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_owned();
        named.push(Row {
            uuid: uuid.clone(),
            baby,
            record_type,
            ts,
            author,
        });
    }
    if named.is_empty() {
        return Ok(None);
    }
    // Load neighborhood for each named row's baby+type around its timestamp.
    let mut candidates: BTreeMap<String, Row> = BTreeMap::new();
    for row in &named {
        candidates.insert(row.uuid.clone(), row.clone());
        let lo = row.ts.saturating_sub(SUSPECTED_DUPLICATE_WINDOW);
        let hi = row.ts.saturating_add(SUSPECTED_DUPLICATE_WINDOW);
        let mut stmt = tx.prepare(
            "
            SELECT client_uuid, payload_json FROM entities
            WHERE family_id = ?1
              AND entity_type = 'record'
              AND deleted_at IS NULL
              AND json_extract(payload_json, '$.baby_client_uuid') = ?2
              AND json_extract(payload_json, '$.type') = ?3
              AND CAST(json_extract(payload_json, '$.timestamp') AS INTEGER) BETWEEN ?4 AND ?5
            ",
        )?;
        let rows = stmt.query_map(params![family_id, row.baby, row.record_type, lo, hi], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })?;
        for item in rows {
            let (uuid, payload_json) = item?;
            if candidates.contains_key(&uuid) {
                continue;
            }
            let payload: serde_json::Map<String, serde_json::Value> =
                serde_json::from_str(&payload_json)?;
            let ts = payload
                .get("timestamp")
                .and_then(|v| v.as_i64())
                .unwrap_or(0);
            let author = payload
                .get("created_by_membership_id")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_owned();
            if author.is_empty() {
                continue;
            }
            candidates.insert(
                uuid.clone(),
                Row {
                    uuid,
                    baby: row.baby.clone(),
                    record_type: row.record_type.clone(),
                    ts,
                    author,
                },
            );
        }
    }
    // Build adjacency for cross-membership within window, same baby+type.
    let uuids: Vec<String> = candidates.keys().cloned().collect();
    let mut adj: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for u in &uuids {
        adj.entry(u.clone()).or_default();
    }
    for i in 0..uuids.len() {
        for j in (i + 1)..uuids.len() {
            let a = &candidates[&uuids[i]];
            let b = &candidates[&uuids[j]];
            if a.baby != b.baby || a.record_type != b.record_type {
                continue;
            }
            if a.author == b.author {
                continue;
            }
            if (a.ts - b.ts).abs() <= SUSPECTED_DUPLICATE_WINDOW {
                adj.get_mut(&uuids[i]).unwrap().push(uuids[j].clone());
                adj.get_mut(&uuids[j]).unwrap().push(uuids[i].clone());
            }
        }
    }
    // BFS component from first named member.
    let start = members[0].clone();
    let mut seen = BTreeSet::new();
    let mut queue = std::collections::VecDeque::new();
    queue.push_back(start.clone());
    seen.insert(start);
    while let Some(node) = queue.pop_front() {
        for next in adj.get(&node).into_iter().flatten() {
            if seen.insert(next.clone()) {
                queue.push_back(next.clone());
            }
        }
    }
    for uuid in &seen {
        if !member_set.contains(uuid) {
            return Ok(Some(uuid.clone()));
        }
    }
    for uuid in member_set {
        if !seen.contains(uuid) {
            // Named set includes something outside the component — still incomplete
            // relative to soft-group semantics; prefer reporting first extra as missing.
            continue;
        }
    }
    Ok(None)
}
