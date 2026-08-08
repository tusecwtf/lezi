//! Family entity pull pagination and dependency co-grouping.

use std::collections::BTreeSet;

use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{Map, Value};

use crate::{PULL_PAGE_ENTITY_LIMIT, PULL_PAGE_TARGET_BYTES};

use super::{parse_payload, EntityKey, PullPage, PulledEntity, Store, StoreError};

fn pulled_entity_from_row(row: &rusqlite::Row<'_>) -> Result<PulledEntity, StoreError> {
    let entity_type = row.get::<_, String>(0)?;
    let payload = parse_payload(&row.get::<_, String>(4)?)?;
    Ok(PulledEntity {
        entity_type,
        client_uuid: row.get(1)?,
        updated_at: row.get(2)?,
        deleted_at: row.get(3)?,
        payload,
        rev: row.get(5)?,
        version_id: None,
        conflict_summary: None,
    })
}

fn load_pulled_entity(
    connection: &Connection,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<Option<PulledEntity>, StoreError> {
    connection
        .query_row(
            "
            SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            FROM entities
            WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3
            ",
            params![family_id, entity_type, client_uuid],
            |row| {
                let entity_type = row.get::<_, String>(0)?;
                let payload_raw = row.get::<_, String>(4)?;
                Ok((
                    entity_type,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, Option<i64>>(3)?,
                    payload_raw,
                    row.get::<_, i64>(5)?,
                ))
            },
        )
        .optional()?
        .map(
            |(entity_type, client_uuid, updated_at, deleted_at, payload_raw, rev)| {
                let payload = parse_payload(&payload_raw)?;
                Ok(PulledEntity {
                    entity_type,
                    client_uuid,
                    updated_at,
                    deleted_at,
                    payload,
                    rev,
                    version_id: None,
                    conflict_summary: None,
                })
            },
        )
        .transpose()
}

fn collect_pull_entity_with_dependencies(
    connection: &Connection,
    family_id: &str,
    cursor: i64,
    entity: PulledEntity,
    included_keys: &BTreeSet<EntityKey>,
    group_keys: &mut BTreeSet<EntityKey>,
    group: &mut Vec<PulledEntity>,
) -> Result<(), StoreError> {
    let key = (entity.entity_type.clone(), entity.client_uuid.clone());
    if included_keys.contains(&key) || group_keys.contains(&key) {
        return Ok(());
    }
    // Mark before traversing dependencies: record/care_plan now include live
    // log media, while each log media includes its parent. Early marking makes
    // that bidirectional graph finite without changing dependency-first order.
    group_keys.insert(key);
    if entity.deleted_at.is_none() {
        match entity.entity_type.as_str() {
            "record" => {
                append_pull_dependency(
                    connection,
                    family_id,
                    cursor,
                    "baby",
                    required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?;
                if let Some(custom_item_id) = entity
                    .payload
                    .get("custom_item_client_uuid")
                    .and_then(Value::as_str)
                {
                    append_pull_dependency(
                        connection,
                        family_id,
                        cursor,
                        "custom_item",
                        custom_item_id,
                        included_keys,
                        group_keys,
                        group,
                    )?;
                }
                append_log_media_for_parent(
                    connection,
                    family_id,
                    cursor,
                    "record",
                    &entity.client_uuid,
                    included_keys,
                    group_keys,
                    group,
                )?;
                append_completed_plans_for_record(
                    connection,
                    family_id,
                    &entity.client_uuid,
                    included_keys,
                    group_keys,
                    group,
                )?;
            }
            "care_plan" => {
                append_pull_dependency(
                    connection,
                    family_id,
                    cursor,
                    "baby",
                    required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?;
                if let Some(custom_item_id) = entity
                    .payload
                    .get("custom_item_client_uuid")
                    .and_then(Value::as_str)
                {
                    append_pull_dependency(
                        connection,
                        family_id,
                        cursor,
                        "custom_item",
                        custom_item_id,
                        included_keys,
                        group_keys,
                        group,
                    )?;
                }
                // Completed plans co-gate on the fulfill record at the client.
                // Pull the record (and its deps/media recursively) in the same
                // group so pages do not stall with unresolved completed plans.
                if let Some(record_id) = entity
                    .payload
                    .get("fulfilled_record_client_uuid")
                    .and_then(Value::as_str)
                {
                    if !record_id.is_empty() {
                        append_pull_dependency(
                            connection,
                            family_id,
                            cursor,
                            "record",
                            record_id,
                            included_keys,
                            group_keys,
                            group,
                        )?;
                    }
                }
                append_log_media_for_parent(
                    connection,
                    family_id,
                    cursor,
                    "care_plan",
                    &entity.client_uuid,
                    included_keys,
                    group_keys,
                    group,
                )?;
            }
            "fulfillment_candidate" => {
                // A client cannot apply a candidate until both frozen business
                // parents exist locally. Re-emit the complete live parent
                // closure even when those rows predate this pull cursor; the
                // candidate may be the only newly revised entity on the page.
                // Passing a sentinel cursor keeps the existing dependency-first
                // traversal (including each parent's log media) while bypassing
                // the ordinary `rev <= cursor` elision for this co-group only.
                let dependency_cursor = -1;
                append_pull_dependency(
                    connection,
                    family_id,
                    dependency_cursor,
                    "care_plan",
                    required_payload_reference(&entity.payload, "care_plan_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?;
                append_pull_dependency(
                    connection,
                    family_id,
                    dependency_cursor,
                    "record",
                    required_payload_reference(&entity.payload, "record_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?;
            }
            "media" => match entity.payload.get("kind").and_then(Value::as_str) {
                Some("avatar") => append_pull_dependency(
                    connection,
                    family_id,
                    cursor,
                    "baby",
                    required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?,
                Some("log") => {
                    if let Some(care_plan_id) = entity
                        .payload
                        .get("care_plan_client_uuid")
                        .and_then(Value::as_str)
                    {
                        append_pull_dependency(
                            connection,
                            family_id,
                            cursor,
                            "care_plan",
                            care_plan_id,
                            included_keys,
                            group_keys,
                            group,
                        )?;
                    } else {
                        append_pull_dependency(
                            connection,
                            family_id,
                            cursor,
                            "record",
                            required_payload_reference(&entity.payload, "record_client_uuid")?,
                            included_keys,
                            group_keys,
                            group,
                        )?;
                    }
                }
                _ => return Err(StoreError::InvalidStoredPayload),
            },
            _ => {}
        }
    }
    group.push(entity);
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn append_completed_plans_for_record(
    connection: &Connection,
    family_id: &str,
    record_client_uuid: &str,
    included_keys: &BTreeSet<EntityKey>,
    group_keys: &mut BTreeSet<EntityKey>,
    group: &mut Vec<PulledEntity>,
) -> Result<(), StoreError> {
    let plan_ids = {
        let mut statement = connection.prepare(
            "
            SELECT client_uuid
            FROM entities
            WHERE family_id = ?1
              AND entity_type = 'care_plan'
              AND deleted_at IS NULL
              AND json_extract(payload_json, '$.status') = 'completed'
              AND json_extract(payload_json, '$.fulfilled_record_client_uuid') = ?2
            ORDER BY rev ASC, client_uuid ASC
            ",
        )?;
        let ids = statement
            .query_map(params![family_id, record_client_uuid], |row| {
                row.get::<_, String>(0)
            })?
            .collect::<Result<Vec<_>, _>>()?;
        ids
    };
    for plan_id in plan_ids {
        append_pull_dependency(
            connection,
            family_id,
            -1,
            "care_plan",
            &plan_id,
            included_keys,
            group_keys,
            group,
        )?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn append_log_media_for_parent(
    connection: &Connection,
    family_id: &str,
    cursor: i64,
    parent_type: &str,
    parent_client_uuid: &str,
    included_keys: &BTreeSet<EntityKey>,
    group_keys: &mut BTreeSet<EntityKey>,
    group: &mut Vec<PulledEntity>,
) -> Result<(), StoreError> {
    let reference_field = match parent_type {
        "record" => "record_client_uuid",
        "care_plan" => "care_plan_client_uuid",
        _ => return Err(StoreError::InvalidStoredPayload),
    };
    let sql = format!(
        "
        SELECT client_uuid
        FROM entities
        WHERE family_id = ?1
          AND entity_type = 'media'
          AND deleted_at IS NULL
          AND json_extract(payload_json, '$.kind') = 'log'
          AND json_extract(payload_json, '$.{reference_field}') = ?2
        ORDER BY rev ASC, client_uuid ASC
        "
    );
    let media_ids = {
        let mut statement = connection.prepare(&sql)?;
        let ids = statement
            .query_map(params![family_id, parent_client_uuid], |row| {
                row.get::<_, String>(0)
            })?
            .collect::<Result<Vec<_>, _>>()?;
        ids
    };
    for media_id in media_ids {
        append_pull_dependency(
            connection,
            family_id,
            cursor,
            "media",
            &media_id,
            included_keys,
            group_keys,
            group,
        )?;
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn append_pull_dependency(
    connection: &Connection,
    family_id: &str,
    cursor: i64,
    entity_type: &str,
    client_uuid: &str,
    included_keys: &BTreeSet<EntityKey>,
    group_keys: &mut BTreeSet<EntityKey>,
    group: &mut Vec<PulledEntity>,
) -> Result<(), StoreError> {
    let key = (entity_type.to_owned(), client_uuid.to_owned());
    if included_keys.contains(&key) || group_keys.contains(&key) {
        return Ok(());
    }
    let dependency = load_pulled_entity(connection, family_id, entity_type, client_uuid)?
        .ok_or_else(|| {
            StoreError::UnresolvedReference(format!(
                "{entity_type} {client_uuid} referenced by stored entity does not exist"
            ))
        })?;
    // A tombstone is still a valid dependency. In particular, deleting a baby
    // intentionally retains its care records, so a fresh client needs the baby
    // tombstone before those records to preserve the relationship while keeping
    // the profile hidden. Push validation likewise treats retained tombstones as
    // existing reference targets.
    if dependency.rev <= cursor {
        return Ok(());
    }
    collect_pull_entity_with_dependencies(
        connection,
        family_id,
        cursor,
        dependency,
        included_keys,
        group_keys,
        group,
    )
}

fn required_payload_reference<'a>(
    payload: &'a Map<String, Value>,
    field: &str,
) -> Result<&'a str, StoreError> {
    payload
        .get(field)
        .and_then(Value::as_str)
        .ok_or(StoreError::InvalidStoredPayload)
}

fn is_deferred_fulfillment(
    connection: &Connection,
    family_id: &str,
    entity: &PulledEntity,
) -> Result<bool, StoreError> {
    match entity.entity_type.as_str() {
        "care_plan" => is_deferred_care_plan(connection, family_id, entity),
        "media" => {
            let Some(plan_id) = entity
                .payload
                .get("care_plan_client_uuid")
                .and_then(Value::as_str)
            else {
                return Ok(false);
            };
            let Some(plan) = load_pulled_entity(connection, family_id, "care_plan", plan_id)?
            else {
                return Ok(false);
            };
            is_deferred_care_plan(connection, family_id, &plan)
        }
        _ => Ok(false),
    }
}

fn is_deferred_care_plan(
    connection: &Connection,
    family_id: &str,
    plan: &PulledEntity,
) -> Result<bool, StoreError> {
    if plan.deleted_at.is_some()
        || plan.payload.get("status").and_then(Value::as_str) != Some("completed")
    {
        return Ok(false);
    }
    let record_id = required_payload_reference(&plan.payload, "fulfilled_record_client_uuid")?;
    let record_exists = connection.query_row(
        "
        SELECT EXISTS(
            SELECT 1
            FROM entities
            WHERE family_id = ?1
              AND entity_type = 'record'
              AND client_uuid = ?2
              AND deleted_at IS NULL
        )
        ",
        params![family_id, record_id],
        |row| row.get::<_, bool>(0),
    )?;
    Ok(!record_exists)
}

impl Store {
    pub fn pull(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError> {
        let connection = self.connect()?;
        let (current, family_name): (i64, Option<String>) = connection.query_row(
            "
        SELECT family_meta.rev, families.name
        FROM family_meta
        JOIN families ON families.id = family_meta.family_id
        WHERE family_meta.family_id = ?1
        ",
            params![family_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        if cursor > current {
            return Err(StoreError::CursorAhead(current));
        }
        let mut statement = connection.prepare(
            "
        SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
        FROM entities
        WHERE family_id = ?1 AND rev > ?2 AND rev <= ?3
        ORDER BY rev ASC
        ",
        )?;
        let mut rows = statement.query(params![family_id, cursor, current])?;
        let mut entities = Vec::new();
        let mut included_keys = BTreeSet::new();
        let mut serialized_bytes = 0usize;
        let mut page_cursor = cursor;
        let mut has_more = false;
        while let Some(row) = rows.next()? {
            let entity = pulled_entity_from_row(row)?;
            let base_rev = entity.rev;
            if is_deferred_fulfillment(&connection, family_id, &entity)? {
                page_cursor = base_rev;
                continue;
            }
            let mut group = Vec::new();
            let mut group_keys = BTreeSet::new();
            collect_pull_entity_with_dependencies(
                &connection,
                family_id,
                cursor,
                entity,
                &included_keys,
                &mut group_keys,
                &mut group,
            )?;
            let group_bytes = group.iter().try_fold(0usize, |total, entity| {
                Ok::<_, StoreError>(
                    total
                        .saturating_add(serde_json::to_vec(entity)?.len())
                        .saturating_add(1),
                )
            })?;
            let would_exceed_count =
                entities.len().saturating_add(group.len()) > PULL_PAGE_ENTITY_LIMIT;
            let would_exceed_bytes =
                serialized_bytes.saturating_add(group_bytes) > PULL_PAGE_TARGET_BYTES;
            if would_exceed_count || would_exceed_bytes {
                if page_cursor == cursor {
                    return Err(StoreError::PullEntityTooLarge);
                }
                has_more = true;
                break;
            }
            serialized_bytes = serialized_bytes.saturating_add(group_bytes);
            included_keys.extend(group_keys);
            entities.extend(group);
            page_cursor = base_rev;
            if entities.len() >= PULL_PAGE_ENTITY_LIMIT
                || serialized_bytes >= PULL_PAGE_TARGET_BYTES
            {
                has_more = page_cursor < current;
                break;
            }
        }
        if !has_more {
            // Revisions can contain gaps after a later update replaces an
            // entity's older row. Once the snapshot is exhausted it is safe to
            // advance across those gaps to the captured server revision.
            page_cursor = current;
        }
        // Attach causal version_id + conflict_summary for versioned roots.
        // Wire §7: at most MAX_CONFLICT_SUMMARIES_PER_PAGE summaries per page.
        use super::causal::MAX_CONFLICT_SUMMARIES_PER_PAGE;
        use super::schema::VERSIONED_ENTITY_TYPES;
        let mut summary_count = 0usize;
        for entity in &mut entities {
            if !VERSIONED_ENTITY_TYPES.contains(&entity.entity_type.as_str()) {
                continue;
            }
            entity.version_id = connection
                .query_row(
                    "SELECT version_id FROM entity_stable_heads
                     WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3",
                    params![family_id, entity.entity_type, entity.client_uuid],
                    |row| row.get(0),
                )
                .optional()?;
            if summary_count >= MAX_CONFLICT_SUMMARIES_PER_PAGE {
                continue;
            }
            let open = connection
                .query_row(
                    "SELECT conflict_id, stable_version_id FROM conflicts
                     WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3
                       AND status = 'open'
                     ORDER BY created_at ASC LIMIT 1",
                    params![family_id, entity.entity_type, entity.client_uuid],
                    |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
                )
                .optional()?;
            if let Some((conflict_id, stable_version_id)) = open {
                let mut branch_ids = Vec::new();
                {
                    let mut stmt = connection.prepare(
                        "SELECT branch_version_id FROM conflict_branches
                         WHERE family_id = ?1 AND conflict_id = ?2
                         ORDER BY branch_version_id COLLATE BINARY",
                    )?;
                    let rows = stmt.query_map(params![family_id, conflict_id], |row| {
                        row.get::<_, String>(0)
                    })?;
                    for r in rows {
                        branch_ids.push(r?);
                    }
                }
                entity.conflict_summary = Some(super::ConflictSummary {
                    conflict_id,
                    entity_type: entity.entity_type.clone(),
                    client_uuid: entity.client_uuid.clone(),
                    stable_version_id,
                    branch_version_ids: branch_ids,
                });
                summary_count += 1;
            }
        }
        Ok(PullPage {
            entities,
            cursor: page_cursor,
            has_more,
            family_name,
        })
    }

    pub fn current_revision(&self, family_id: &str) -> Result<i64, StoreError> {
        let connection = self.connect()?;
        Ok(connection
            .query_row(
                "SELECT rev FROM family_meta WHERE family_id = ?1",
                params![family_id],
                |row| row.get(0),
            )
            .optional()?
            .unwrap_or(0))
    }
}
