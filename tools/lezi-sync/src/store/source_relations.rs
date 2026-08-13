//! Non-destructive source relations (wire §12 / ADR-0021).
//!
//! Author declare and Owner group resolve select a display version and retain
//! every other root/media as a source relation. Records stay live entities
//! (`deleted_at` is never set for provenance hiding).

use std::collections::{BTreeMap, BTreeSet};

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use super::{Principal, Store, StoreError};

pub(super) const MAX_SOURCE_RELATION_MEMBERS: usize = 64;
pub(super) const MAX_SOURCE_RELATION_CANDIDATES: usize = 256;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct DeclareSourceRelationInput {
    pub mutation_id: String,
    pub record_client_uuid: String,
    pub equivalent_to_client_uuid: String,
    pub expected_record_version: String,
    pub expected_other_version: String,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq, Eq)]
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

#[derive(Debug, Clone, Serialize)]
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

fn rejected(code: &'static str) -> SourceRelationReceipt {
    SourceRelationReceipt {
        status: "rejected".to_owned(),
        relation_id: None,
        display_client_uuid: None,
        source_client_uuids: None,
        media_retained: None,
        code: Some(code.to_owned()),
        latest_versions: None,
    }
}

fn request_fingerprint<T: Serialize>(kind: &str, input: &T) -> Result<String, StoreError> {
    Ok(format!("{kind}:{}", serde_json::to_string(input)?))
}

fn replay_mutation_receipt(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    mutation_id: &str,
    request_fingerprint: &str,
) -> Result<Option<SourceRelationReceipt>, StoreError> {
    let stored = tx
        .query_row(
            "SELECT request_fingerprint, receipt_json
             FROM source_relation_mutation_receipts
             WHERE family_id = ?1 AND mutation_id = ?2",
            params![family_id, mutation_id],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()?;
    let Some((stored_fingerprint, receipt_json)) = stored else {
        return Ok(None);
    };
    if stored_fingerprint != request_fingerprint {
        return Ok(Some(rejected("content_drift")));
    }
    Ok(Some(serde_json::from_str(&receipt_json)?))
}

fn persist_mutation_receipt(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    mutation_id: &str,
    request_kind: &str,
    request_fingerprint: &str,
    receipt: &SourceRelationReceipt,
    now: i64,
) -> Result<(), StoreError> {
    tx.execute(
        "INSERT INTO source_relation_mutation_receipts(
             family_id, mutation_id, request_kind, request_fingerprint,
             receipt_json, created_at
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            family_id,
            mutation_id,
            request_kind,
            request_fingerprint,
            serde_json::to_string(receipt)?,
            now,
        ],
    )?;
    Ok(())
}

fn commit_mutation_receipt(
    tx: rusqlite::Transaction<'_>,
    family_id: &str,
    mutation_id: &str,
    request_kind: &str,
    request_fingerprint: &str,
    receipt: SourceRelationReceipt,
    now: i64,
) -> Result<SourceRelationReceipt, StoreError> {
    persist_mutation_receipt(
        &tx,
        family_id,
        mutation_id,
        request_kind,
        request_fingerprint,
        &receipt,
        now,
    )?;
    tx.commit()?;
    Ok(receipt)
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
            return Ok(rejected("invalid_mutation_id"));
        }
        let fingerprint = request_fingerprint("author_declare", &input)?;
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        if let Some(receipt) =
            replay_mutation_receipt(&tx, &principal.family_id, &input.mutation_id, &fingerprint)?
        {
            return Ok(receipt);
        }
        if input.record_client_uuid == input.equivalent_to_client_uuid {
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "author_declare",
                &fingerprint,
                rejected("duplicate_member"),
                now,
            );
        }

        let named_ids = [
            input.record_client_uuid.clone(),
            input.equivalent_to_client_uuid.clone(),
        ];
        let named = match load_named_records(&tx, &principal.family_id, &named_ids)? {
            Ok(records) => records,
            Err(code) => {
                return commit_mutation_receipt(
                    tx,
                    &principal.family_id,
                    &input.mutation_id,
                    "author_declare",
                    &fingerprint,
                    rejected(code),
                    now,
                );
            }
        };
        if named[0].author_membership_id != principal.membership_id {
            return Err(StoreError::ForbiddenRecord);
        }
        if let Some(code) = validate_declared_pair(&named[0], &named[1]) {
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "author_declare",
                &fingerprint,
                rejected(code),
                now,
            );
        }
        if any_active_relation_member(&tx, &principal.family_id, &named_ids)? {
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "author_declare",
                &fingerprint,
                rejected("already_related"),
                now,
            );
        }

        let record_version = Some(named[0].stable_version_id.clone());
        let other_version = Some(named[1].stable_version_id.clone());
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
            let receipt = SourceRelationReceipt {
                status: "cas_mismatch".to_owned(),
                relation_id: None,
                display_client_uuid: None,
                source_client_uuids: None,
                media_retained: None,
                code: Some("cas_mismatch".to_owned()),
                latest_versions: Some(latest),
            };
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "author_declare",
                &fingerprint,
                receipt,
                now,
            );
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
        bump_relation_member_revisions(
            &tx,
            &principal.family_id,
            &[&input.record_client_uuid, &input.equivalent_to_client_uuid],
        )?;
        let receipt = SourceRelationReceipt {
            status: "accepted".to_owned(),
            relation_id: Some(relation_id),
            display_client_uuid: Some(input.equivalent_to_client_uuid),
            source_client_uuids: Some(vec![input.record_client_uuid]),
            media_retained: Some(true),
            code: None,
            latest_versions: None,
        };
        commit_mutation_receipt(
            tx,
            &principal.family_id,
            &input.mutation_id,
            "author_declare",
            &fingerprint,
            receipt,
            now,
        )
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
            return Ok(rejected("invalid_mutation_id"));
        }
        let mut members = input.member_client_uuids.clone();
        members.sort();
        let fingerprint = request_fingerprint(
            "owner_group_resolve",
            &ResolveSourceRelationGroupInput {
                mutation_id: input.mutation_id.clone(),
                member_client_uuids: members.clone(),
                display_client_uuid: input.display_client_uuid.clone(),
                expected_versions: input.expected_versions.clone(),
            },
        )?;

        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        if let Some(receipt) =
            replay_mutation_receipt(&tx, &principal.family_id, &input.mutation_id, &fingerprint)?
        {
            return Ok(receipt);
        }
        let static_rejection = if members.len() > MAX_SOURCE_RELATION_MEMBERS {
            Some("too_many_members")
        } else if members.windows(2).any(|pair| pair[0] == pair[1]) {
            Some("duplicate_member")
        } else if members.len() < 2 {
            Some("too_few_members")
        } else if !members.iter().any(|m| m == &input.display_client_uuid) {
            Some("display_not_member")
        } else {
            None
        };
        if let Some(code) = static_rejection {
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "owner_group_resolve",
                &fingerprint,
                rejected(code),
                now,
            );
        }
        // Complete expected version set (CAS): every member must be named.
        let expected_keys: BTreeSet<_> = input.expected_versions.keys().cloned().collect();
        let member_set: BTreeSet<_> = members.iter().cloned().collect();
        if expected_keys != member_set {
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "owner_group_resolve",
                &fingerprint,
                rejected("incomplete_expected_versions"),
                now,
            );
        }

        let named = match load_named_records(&tx, &principal.family_id, &members)? {
            Ok(records) => records,
            Err(code) => {
                return commit_mutation_receipt(
                    tx,
                    &principal.family_id,
                    &input.mutation_id,
                    "owner_group_resolve",
                    &fingerprint,
                    rejected(code),
                    now,
                );
            }
        };
        let mut latest = BTreeMap::new();
        let mut cas_ok = true;
        for record in &named {
            let uuid = &record.client_uuid;
            let version = &record.stable_version_id;
            latest.insert(uuid.clone(), version.clone());
            let expected = input.expected_versions.get(uuid).map(String::as_str);
            if Some(version.as_str()) != expected {
                cas_ok = false;
            }
        }
        if !cas_ok {
            let receipt = SourceRelationReceipt {
                status: "cas_mismatch".to_owned(),
                relation_id: None,
                display_client_uuid: None,
                source_client_uuids: None,
                media_retained: None,
                code: Some("cas_mismatch".to_owned()),
                latest_versions: Some(latest),
            };
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "owner_group_resolve",
                &fingerprint,
                receipt,
                now,
            );
        }

        if let Some(code) = validate_complete_group(&tx, &principal.family_id, &named, &member_set)?
        {
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "owner_group_resolve",
                &fingerprint,
                rejected(code),
                now,
            );
        }
        let replaced_relations = related_active_relations(&tx, &principal.family_id, &members)?;
        if replaced_relations
            .values()
            .flatten()
            .any(|record_uuid| !member_set.contains(record_uuid))
        {
            return commit_mutation_receipt(
                tx,
                &principal.family_id,
                &input.mutation_id,
                "owner_group_resolve",
                &fingerprint,
                rejected("incomplete_group"),
                now,
            );
        }
        for relation_id in replaced_relations.keys() {
            tx.execute(
                "DELETE FROM source_relation_members WHERE family_id = ?1 AND relation_id = ?2",
                params![principal.family_id, relation_id],
            )?;
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
        bump_relation_member_revisions(&tx, &principal.family_id, &member_refs)?;
        let receipt = SourceRelationReceipt {
            status: "accepted".to_owned(),
            relation_id: Some(relation_id),
            display_client_uuid: Some(input.display_client_uuid),
            source_client_uuids: Some(sources.iter().map(|s| (*s).to_owned()).collect()),
            media_retained: Some(true),
            code: None,
            latest_versions: None,
        };
        commit_mutation_receipt(
            tx,
            &principal.family_id,
            &input.mutation_id,
            "owner_group_resolve",
            &fingerprint,
            receipt,
            now,
        )
    }
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
    let sources_json = serde_json::to_string(sources)?;
    tx.execute(
        "WITH members(record_client_uuid, role) AS (
             SELECT ?3, 'display'
             UNION ALL
             SELECT value, 'source' FROM json_each(?4)
         )
         INSERT INTO source_relation_members(
             family_id, relation_id, record_client_uuid, role
         )
         SELECT ?1, ?2, record_client_uuid, role FROM members",
        params![family_id, relation_id, display, sources_json],
    )?;
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
    let member_count =
        i64::try_from(members.len()).map_err(|_| StoreError::InvalidStoredPayload)?;
    tx.execute(
        "UPDATE family_meta SET rev = rev + ?1 WHERE family_id = ?2",
        params![member_count, family_id],
    )?;
    let final_rev: i64 = tx.query_row(
        "SELECT rev FROM family_meta WHERE family_id = ?1",
        params![family_id],
        |row| row.get(0),
    )?;
    let first_rev = final_rev.saturating_sub(member_count).saturating_add(1);
    let members_json = serde_json::to_string(&members)?;
    let changed = tx.execute(
        "WITH requested AS (
             SELECT CAST(key AS INTEGER) AS ordinal, value AS client_uuid
             FROM json_each(?1)
         )
         UPDATE entities
         SET rev = ?2 + (
             SELECT ordinal FROM requested
             WHERE requested.client_uuid = entities.client_uuid
         )
         WHERE family_id = ?3 AND entity_type = 'record'
           AND client_uuid IN (SELECT client_uuid FROM requested)",
        params![members_json, first_rev, family_id],
    )?;
    if changed != members.len() {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(())
}

pub(super) fn project_record_eligibility(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    deleted_at: Option<i64>,
    root: &serde_json::Map<String, serde_json::Value>,
) -> Result<(), StoreError> {
    if entity_type != "record" {
        return Ok(());
    }
    if deleted_at.is_some() {
        tx.execute(
            "DELETE FROM source_relation_record_eligibility
             WHERE family_id = ?1 AND record_client_uuid = ?2",
            params![family_id, client_uuid],
        )?;
        return Ok(());
    }
    let baby_client_uuid = root
        .get("baby_client_uuid")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or(StoreError::InvalidStoredPayload)?;
    let record_type = root
        .get("type")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or(StoreError::InvalidStoredPayload)?;
    let timestamp = root
        .get("timestamp")
        .and_then(serde_json::Value::as_i64)
        .ok_or(StoreError::InvalidStoredPayload)?;
    let author_membership_id = root
        .get("created_by_membership_id")
        .and_then(serde_json::Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or(StoreError::InvalidStoredPayload)?;
    tx.execute(
        "INSERT INTO source_relation_record_eligibility(
             family_id, record_client_uuid, baby_client_uuid, record_type,
             record_timestamp, author_membership_id
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT(family_id, record_client_uuid) DO UPDATE SET
             baby_client_uuid = excluded.baby_client_uuid,
             record_type = excluded.record_type,
             record_timestamp = excluded.record_timestamp,
             author_membership_id = excluded.author_membership_id",
        params![
            family_id,
            client_uuid,
            baby_client_uuid,
            record_type,
            timestamp,
            author_membership_id,
        ],
    )?;
    Ok(())
}

pub(crate) fn rebuild_record_eligibility(
    connection: &rusqlite::Connection,
) -> rusqlite::Result<()> {
    connection.execute("DELETE FROM source_relation_record_eligibility", [])?;
    connection.execute(
        "INSERT INTO source_relation_record_eligibility(
             family_id, record_client_uuid, baby_client_uuid, record_type,
             record_timestamp, author_membership_id
         )
         SELECT family_id, client_uuid,
                json_extract(payload_json, '$.baby_client_uuid'),
                json_extract(payload_json, '$.type'),
                CAST(json_extract(payload_json, '$.timestamp') AS INTEGER),
                json_extract(payload_json, '$.created_by_membership_id')
         FROM entities
         WHERE entity_type = 'record' AND deleted_at IS NULL
           AND json_type(payload_json, '$.baby_client_uuid') = 'text'
           AND json_extract(payload_json, '$.baby_client_uuid') != ''
           AND json_type(payload_json, '$.type') = 'text'
           AND json_extract(payload_json, '$.type') != ''
           AND json_type(payload_json, '$.timestamp') = 'integer'
           AND json_type(payload_json, '$.created_by_membership_id') = 'text'
           AND json_extract(payload_json, '$.created_by_membership_id') != ''",
        [],
    )?;
    Ok(())
}

#[derive(Clone)]
struct CanonicalRecord {
    client_uuid: String,
    baby_client_uuid: String,
    record_type: String,
    timestamp: i64,
    author_membership_id: String,
    stable_version_id: String,
}

fn load_named_records(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    members: &[String],
) -> Result<Result<Vec<CanonicalRecord>, &'static str>, StoreError> {
    let members_json = serde_json::to_string(members)?;
    let mut statement = tx.prepare(
        "WITH requested AS (
             SELECT CAST(key AS INTEGER) AS ordinal, value AS client_uuid
             FROM json_each(?2)
         )
         SELECT requested.client_uuid, eligibility.baby_client_uuid,
                eligibility.record_type, eligibility.record_timestamp,
                eligibility.author_membership_id, head.version_id
         FROM requested
         LEFT JOIN source_relation_record_eligibility eligibility
           ON eligibility.family_id = ?1
          AND eligibility.record_client_uuid = requested.client_uuid
         LEFT JOIN entity_stable_heads head
           ON head.family_id = ?1 AND head.entity_type = 'record'
          AND head.client_uuid = requested.client_uuid
         ORDER BY requested.ordinal",
    )?;
    let rows = statement.query_map(params![family_id, members_json], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, Option<String>>(1)?,
            row.get::<_, Option<String>>(2)?,
            row.get::<_, Option<i64>>(3)?,
            row.get::<_, Option<String>>(4)?,
            row.get::<_, Option<String>>(5)?,
        ))
    })?;
    let mut records = Vec::with_capacity(members.len());
    for row in rows {
        let (
            client_uuid,
            baby_client_uuid,
            record_type,
            timestamp,
            author_membership_id,
            stable_version_id,
        ) = row?;
        let (
            Some(baby_client_uuid),
            Some(record_type),
            Some(timestamp),
            Some(author_membership_id),
            Some(stable_version_id),
        ) = (
            baby_client_uuid,
            record_type,
            timestamp,
            author_membership_id,
            stable_version_id,
        )
        else {
            return Ok(Err("not_live_record"));
        };
        records.push(CanonicalRecord {
            client_uuid,
            baby_client_uuid,
            record_type,
            timestamp,
            author_membership_id,
            stable_version_id,
        });
    }
    Ok(Ok(records))
}

fn validate_declared_pair(
    record: &CanonicalRecord,
    other: &CanonicalRecord,
) -> Option<&'static str> {
    use crate::store::suspected_duplicates::{
        is_suspected_duplicate_type, SUSPECTED_DUPLICATE_WINDOW,
    };
    if !is_suspected_duplicate_type(&record.record_type)
        || !is_suspected_duplicate_type(&other.record_type)
    {
        return Some("unsupported_record_type");
    }
    if record.baby_client_uuid != other.baby_client_uuid || record.record_type != other.record_type
    {
        return Some("wrong_baby_or_type");
    }
    if record.author_membership_id == other.author_membership_id {
        return Some("same_author_only");
    }
    if record.timestamp.abs_diff(other.timestamp) > SUSPECTED_DUPLICATE_WINDOW as u64 {
        return Some("outside_time_window");
    }
    None
}

fn any_active_relation_member(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    members: &[String],
) -> Result<bool, StoreError> {
    let members_json = serde_json::to_string(members)?;
    let exists: i64 = tx.query_row(
        "SELECT EXISTS(
             SELECT 1 FROM source_relation_members member
             JOIN json_each(?2) requested ON requested.value = member.record_client_uuid
             WHERE member.family_id = ?1
         )",
        params![family_id, members_json],
        |row| row.get(0),
    )?;
    Ok(exists != 0)
}

fn related_active_relations(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    members: &[String],
) -> Result<BTreeMap<String, Vec<String>>, StoreError> {
    let members_json = serde_json::to_string(members)?;
    let mut statement = tx.prepare(
        "WITH related(relation_id) AS (
             SELECT DISTINCT member.relation_id
             FROM source_relation_members member
             JOIN json_each(?2) requested ON requested.value = member.record_client_uuid
             WHERE member.family_id = ?1
         )
         SELECT member.relation_id, member.record_client_uuid
         FROM source_relation_members member
         JOIN related ON related.relation_id = member.relation_id
         WHERE member.family_id = ?1
         ORDER BY member.relation_id COLLATE BINARY,
                  member.record_client_uuid COLLATE BINARY",
    )?;
    let rows = statement.query_map(params![family_id, members_json], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
    })?;
    let mut relations = BTreeMap::<String, Vec<String>>::new();
    for row in rows {
        let (relation_id, record_uuid) = row?;
        relations.entry(relation_id).or_default().push(record_uuid);
    }
    Ok(relations)
}

fn validate_complete_group(
    tx: &rusqlite::Transaction<'_>,
    family_id: &str,
    named: &[CanonicalRecord],
    member_set: &BTreeSet<String>,
) -> Result<Option<&'static str>, StoreError> {
    use crate::store::suspected_duplicates::{
        is_suspected_duplicate_type, SUSPECTED_DUPLICATE_WINDOW,
    };
    let first = &named[0];
    if named
        .iter()
        .any(|record| !is_suspected_duplicate_type(&record.record_type))
    {
        return Ok(Some("unsupported_record_type"));
    }
    if named.iter().any(|record| {
        record.baby_client_uuid != first.baby_client_uuid || record.record_type != first.record_type
    }) {
        return Ok(Some("wrong_baby_or_type"));
    }
    if named
        .iter()
        .map(|record| &record.author_membership_id)
        .collect::<BTreeSet<_>>()
        .len()
        < 2
    {
        return Ok(Some("same_author_only"));
    }

    let minimum = named.iter().map(|record| record.timestamp).min().unwrap();
    let maximum = named.iter().map(|record| record.timestamp).max().unwrap();
    let mut statement = tx.prepare(
        "SELECT eligibility.record_client_uuid, eligibility.baby_client_uuid,
                eligibility.record_type, eligibility.record_timestamp,
                eligibility.author_membership_id, head.version_id
         FROM source_relation_record_eligibility eligibility
         JOIN entity_stable_heads head
           ON head.family_id = eligibility.family_id AND head.entity_type = 'record'
          AND head.client_uuid = eligibility.record_client_uuid
         WHERE eligibility.family_id = ?1
           AND eligibility.baby_client_uuid = ?2
           AND eligibility.record_type = ?3
           AND eligibility.record_timestamp BETWEEN ?4 AND ?5
         ORDER BY eligibility.record_timestamp,
                  eligibility.record_client_uuid COLLATE BINARY
         LIMIT ?6",
    )?;
    let rows = statement.query_map(
        params![
            family_id,
            first.baby_client_uuid,
            first.record_type,
            minimum.saturating_sub(SUSPECTED_DUPLICATE_WINDOW),
            maximum.saturating_add(SUSPECTED_DUPLICATE_WINDOW),
            i64::try_from(MAX_SOURCE_RELATION_CANDIDATES + 1)
                .map_err(|_| StoreError::InvalidStoredPayload)?,
        ],
        |row| {
            Ok(CanonicalRecord {
                client_uuid: row.get(0)?,
                baby_client_uuid: row.get(1)?,
                record_type: row.get(2)?,
                timestamp: row.get(3)?,
                author_membership_id: row.get(4)?,
                stable_version_id: row.get(5)?,
            })
        },
    )?;
    let mut candidates = Vec::new();
    for row in rows {
        candidates.push(row?);
    }
    if candidates.len() > MAX_SOURCE_RELATION_CANDIDATES {
        return Ok(Some("candidate_limit_exceeded"));
    }

    let mut parents = (0..candidates.len()).collect::<Vec<_>>();
    for current in 0..candidates.len() {
        for prior in (0..current).rev() {
            if candidates[current]
                .timestamp
                .saturating_sub(candidates[prior].timestamp)
                > SUSPECTED_DUPLICATE_WINDOW
            {
                break;
            }
            if candidates[current].author_membership_id != candidates[prior].author_membership_id {
                union(&mut parents, current, prior);
            }
        }
    }
    let candidate_indices = candidates
        .iter()
        .enumerate()
        .map(|(index, record)| (record.client_uuid.as_str(), index))
        .collect::<BTreeMap<_, _>>();
    let Some(&start_index) = candidate_indices.get(first.client_uuid.as_str()) else {
        return Err(StoreError::InvalidStoredPayload);
    };
    let component_root = find(&mut parents, start_index);
    if named.iter().any(|record| {
        candidate_indices
            .get(record.client_uuid.as_str())
            .is_none_or(|index| find(&mut parents, *index) != component_root)
    }) {
        return Ok(Some("disconnected_group"));
    }
    if candidates.iter().enumerate().any(|(index, record)| {
        find(&mut parents, index) == component_root && !member_set.contains(&record.client_uuid)
    }) {
        return Ok(Some("incomplete_group"));
    }
    Ok(None)
}

fn find(parents: &mut [usize], index: usize) -> usize {
    if parents[index] != index {
        parents[index] = find(parents, parents[index]);
    }
    parents[index]
}

fn union(parents: &mut [usize], left: usize, right: usize) {
    let left_root = find(parents, left);
    let right_root = find(parents, right);
    if left_root != right_root {
        parents[right_root] = left_root;
    }
}
