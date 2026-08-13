//! Family pull planning over complete, dependency-safe wire envelopes.
//!
//! [`PullPlanner`] owns candidate grouping, one batched causal/source projection,
//! and the final count/summary/serialized-byte decision. Sidecars are therefore
//! part of pagination rather than decoration after the cursor has advanced.

use std::collections::{BTreeMap, BTreeSet};

use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{Map, Value};

use crate::{PULL_PAGE_ENTITY_LIMIT, PULL_PAGE_TARGET_BYTES};

use super::{parse_payload, EntityKey, PullPage, PulledEntity, Store, StoreError};

#[derive(Debug)]
struct PullGroup {
    cursor_before: i64,
    base_rev: i64,
    entities: Vec<PulledEntity>,
}

#[derive(Default)]
struct PullDependencyIndex {
    log_media_by_parent: BTreeMap<EntityKey, Vec<String>>,
    completed_plans_by_record: BTreeMap<String, Vec<String>>,
    entities_by_key: BTreeMap<EntityKey, PulledEntity>,
}

impl PullDependencyIndex {
    fn load(
        connection: &Connection,
        family_id: &str,
        cursor: i64,
        current: i64,
    ) -> Result<Self, StoreError> {
        let mut statement = connection.prepare(
            "
            WITH base(entity_type, client_uuid, payload_json) AS (
                SELECT entity_type, client_uuid, payload_json
                FROM entities
                WHERE family_id = ?1 AND rev > ?2 AND rev <= ?3
                ORDER BY rev ASC, entity_type COLLATE BINARY, client_uuid COLLATE BINARY
                LIMIT ?4
            ),
            root_parents(entity_type, client_uuid) AS (
                SELECT entity_type, client_uuid FROM base
                WHERE entity_type IN ('record', 'care_plan')
                UNION
                SELECT 'record', json_extract(payload_json, '$.record_client_uuid')
                FROM base
                WHERE entity_type = 'media'
                  AND json_extract(payload_json, '$.record_client_uuid') IS NOT NULL
                UNION
                SELECT 'care_plan', json_extract(payload_json, '$.care_plan_client_uuid')
                FROM base
                WHERE entity_type = 'media'
                  AND json_extract(payload_json, '$.care_plan_client_uuid') IS NOT NULL
                UNION
                SELECT 'record', json_extract(payload_json, '$.record_client_uuid')
                FROM base WHERE entity_type = 'fulfillment_candidate'
                UNION
                SELECT 'care_plan', json_extract(payload_json, '$.care_plan_client_uuid')
                FROM base WHERE entity_type = 'fulfillment_candidate'
                UNION
                SELECT 'record', json_extract(payload_json, '$.fulfilled_record_client_uuid')
                FROM base
                WHERE entity_type = 'care_plan'
                  AND json_extract(payload_json, '$.fulfilled_record_client_uuid') IS NOT NULL
            ),
            completed_plans(record_client_uuid, plan_client_uuid) AS (
                SELECT parent.client_uuid, plan.client_uuid
                FROM root_parents parent
                JOIN entities plan
                  ON plan.family_id = ?1
                 AND plan.entity_type = 'care_plan'
                 AND plan.deleted_at IS NULL
                 AND json_extract(plan.payload_json, '$.status') = 'completed'
                 AND json_extract(plan.payload_json, '$.fulfilled_record_client_uuid') = parent.client_uuid
                WHERE parent.entity_type = 'record'
            ),
            all_parents(entity_type, client_uuid) AS (
                SELECT entity_type, client_uuid FROM root_parents
                UNION
                SELECT 'care_plan', plan_client_uuid FROM completed_plans
            ),
            dependencies(kind, parent_type, parent_id, child_id, child_rev) AS (
                SELECT 'log_media', parent.entity_type, parent.client_uuid,
                       media.client_uuid, media.rev
                FROM all_parents parent
                JOIN entities media
                  ON media.family_id = ?1
                 AND media.entity_type = 'media'
                 AND media.deleted_at IS NULL
                 AND json_extract(media.payload_json, '$.kind') = 'log'
                 AND ((parent.entity_type = 'record'
                       AND json_extract(media.payload_json, '$.record_client_uuid') = parent.client_uuid)
                      OR (parent.entity_type = 'care_plan'
                       AND json_extract(media.payload_json, '$.care_plan_client_uuid') = parent.client_uuid))
                UNION ALL
                SELECT 'completed_plan', 'record', completed.record_client_uuid,
                       completed.plan_client_uuid, plan.rev
                FROM completed_plans completed
                JOIN entities plan
                  ON plan.family_id = ?1
                 AND plan.entity_type = 'care_plan'
                 AND plan.client_uuid = completed.plan_client_uuid
            )
            SELECT kind, parent_type, parent_id, child_id
            FROM dependencies
            ORDER BY kind, parent_type COLLATE BINARY, parent_id COLLATE BINARY,
                     child_rev, child_id COLLATE BINARY
            ",
        )?;
        let mut rows = statement.query(params![
            family_id,
            cursor,
            current,
            PULL_PAGE_ENTITY_LIMIT as i64,
        ])?;
        let mut index = Self::default();
        while let Some(row) = rows.next()? {
            let kind = row.get::<_, String>(0)?;
            let parent_type = row.get::<_, String>(1)?;
            let parent_id = row.get::<_, String>(2)?;
            let child_id = row.get::<_, String>(3)?;
            match kind.as_str() {
                "log_media" => index
                    .log_media_by_parent
                    .entry((parent_type, parent_id))
                    .or_default()
                    .push(child_id),
                "completed_plan" => index
                    .completed_plans_by_record
                    .entry(parent_id)
                    .or_default()
                    .push(child_id),
                _ => return Err(StoreError::InvalidStoredPayload),
            }
        }
        let requested = index
            .log_media_by_parent
            .values()
            .flatten()
            .map(|client_uuid| serde_json::json!(["media", client_uuid]))
            .chain(
                index
                    .completed_plans_by_record
                    .values()
                    .flatten()
                    .map(|client_uuid| serde_json::json!(["care_plan", client_uuid])),
            )
            .collect::<Vec<_>>();
        let requested_json = serde_json::to_string(&requested)?;
        let mut entity_statement = connection.prepare(
            "
            WITH base(entity_type, client_uuid, payload_json) AS (
                SELECT entity_type, client_uuid, payload_json
                FROM entities
                WHERE family_id = ?1 AND rev > ?2 AND rev <= ?3
                ORDER BY rev ASC, entity_type COLLATE BINARY, client_uuid COLLATE BINARY
                LIMIT ?4
            ),
            dependency_keys(entity_type, client_uuid) AS (
                SELECT json_extract(value, '$[0]'), json_extract(value, '$[1]')
                FROM json_each(?5)
                UNION
                SELECT 'baby', json_extract(payload_json, '$.baby_client_uuid')
                FROM base
                WHERE entity_type IN ('record', 'care_plan')
                UNION
                SELECT 'custom_item', json_extract(payload_json, '$.custom_item_client_uuid')
                FROM base
                WHERE entity_type IN ('record', 'care_plan')
                  AND json_extract(payload_json, '$.custom_item_client_uuid') IS NOT NULL
                UNION
                SELECT 'baby', json_extract(payload_json, '$.baby_client_uuid')
                FROM base
                WHERE entity_type = 'media'
                  AND json_extract(payload_json, '$.baby_client_uuid') IS NOT NULL
                UNION
                SELECT 'record', json_extract(payload_json, '$.record_client_uuid')
                FROM base
                WHERE entity_type IN ('media', 'fulfillment_candidate')
                  AND json_extract(payload_json, '$.record_client_uuid') IS NOT NULL
                UNION
                SELECT 'care_plan', json_extract(payload_json, '$.care_plan_client_uuid')
                FROM base
                WHERE entity_type IN ('media', 'fulfillment_candidate')
                  AND json_extract(payload_json, '$.care_plan_client_uuid') IS NOT NULL
                UNION
                SELECT 'record', json_extract(payload_json, '$.fulfilled_record_client_uuid')
                FROM base
                WHERE entity_type = 'care_plan'
                  AND json_extract(payload_json, '$.fulfilled_record_client_uuid') IS NOT NULL
            )
            SELECT entity.entity_type, entity.client_uuid, entity.updated_at,
                   entity.deleted_at, entity.payload_json, entity.rev
            FROM dependency_keys key
            JOIN entities entity
              ON entity.family_id = ?1
             AND entity.entity_type = key.entity_type
             AND entity.client_uuid = key.client_uuid
            ORDER BY entity.rev, entity.entity_type COLLATE BINARY,
                     entity.client_uuid COLLATE BINARY
            ",
        )?;
        let mut entity_rows = entity_statement.query(params![
            family_id,
            cursor,
            current,
            PULL_PAGE_ENTITY_LIMIT as i64,
            requested_json,
        ])?;
        while let Some(row) = entity_rows.next()? {
            let entity = pulled_entity_from_row(row)?;
            index.entities_by_key.insert(
                (entity.entity_type.clone(), entity.client_uuid.clone()),
                entity,
            );
        }
        Ok(index)
    }
}

#[derive(Default)]
struct ProjectedSidecars {
    stable_version_id: Option<String>,
    conflict: Option<ProjectedConflict>,
    relation: Option<ProjectedRelation>,
}

struct ProjectedConflict {
    conflict_id: String,
    stable_version_id: String,
    branch_version_ids: Vec<String>,
}

struct ProjectedRelation {
    relation_id: String,
    role: String,
    peer_ids: Vec<String>,
}

type FinalEnvelopeSize<'a> =
    dyn Fn(usize, usize, i64, &Option<String>) -> Result<usize, StoreError> + 'a;

enum ProjectionRow {
    Stable {
        key: EntityKey,
        version_id: String,
    },
    Conflict {
        key: EntityKey,
        conflict_id: String,
        stable_version_id: String,
    },
    Branch {
        key: EntityKey,
        conflict_id: String,
        branch_version_id: String,
    },
    Relation {
        key: EntityKey,
        relation_id: String,
        role: String,
    },
    RelationPeer {
        key: EntityKey,
        relation_id: String,
        peer_id: String,
    },
}

impl ProjectionRow {
    fn from_sql(row: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        let kind = row.get::<_, String>(0)?;
        let key = (row.get::<_, String>(1)?, row.get::<_, String>(2)?);
        match kind.as_str() {
            "stable" => Ok(Self::Stable {
                key,
                version_id: row.get(3)?,
            }),
            "conflict" => Ok(Self::Conflict {
                key,
                stable_version_id: row.get(3)?,
                conflict_id: row.get(4)?,
            }),
            "branch" => Ok(Self::Branch {
                key,
                conflict_id: row.get(4)?,
                branch_version_id: row.get(5)?,
            }),
            "relation" => Ok(Self::Relation {
                key,
                relation_id: row.get(6)?,
                role: row.get(7)?,
            }),
            "relation_peer" => Ok(Self::RelationPeer {
                key,
                relation_id: row.get(6)?,
                peer_id: row.get(8)?,
            }),
            _ => Err(rusqlite::Error::InvalidQuery),
        }
    }
}

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
        source_relation_summary: None,
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
                    source_relation_summary: None,
                })
            },
        )
        .transpose()
}

struct PullGroupCollector<'a> {
    connection: &'a Connection,
    dependency_index: &'a PullDependencyIndex,
    family_id: &'a str,
    included_keys: &'a BTreeSet<EntityKey>,
    group_keys: &'a mut BTreeSet<EntityKey>,
    group: &'a mut Vec<PulledEntity>,
}

impl PullGroupCollector<'_> {
    fn collect(&mut self, cursor: i64, entity: PulledEntity) -> Result<(), StoreError> {
        let key = (entity.entity_type.clone(), entity.client_uuid.clone());
        if self.included_keys.contains(&key) || self.group_keys.contains(&key) {
            return Ok(());
        }
        // Mark before traversing dependencies: record/care_plan now include live
        // log media, while each log media includes its parent. Early marking makes
        // that bidirectional graph finite without changing dependency-first order.
        self.group_keys.insert(key);
        if entity.deleted_at.is_none() {
            match entity.entity_type.as_str() {
                "record" => {
                    self.append_dependency(
                        cursor,
                        "baby",
                        required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    )?;
                    if let Some(custom_item_id) = entity
                        .payload
                        .get("custom_item_client_uuid")
                        .and_then(Value::as_str)
                    {
                        self.append_dependency(cursor, "custom_item", custom_item_id)?;
                    }
                    self.append_log_media(cursor, "record", &entity.client_uuid)?;
                    self.append_completed_plans(&entity.client_uuid)?;
                }
                "care_plan" => {
                    self.append_dependency(
                        cursor,
                        "baby",
                        required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    )?;
                    if let Some(custom_item_id) = entity
                        .payload
                        .get("custom_item_client_uuid")
                        .and_then(Value::as_str)
                    {
                        self.append_dependency(cursor, "custom_item", custom_item_id)?;
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
                            self.append_dependency(cursor, "record", record_id)?;
                        }
                    }
                    self.append_log_media(cursor, "care_plan", &entity.client_uuid)?;
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
                    self.append_dependency(
                        dependency_cursor,
                        "care_plan",
                        required_payload_reference(&entity.payload, "care_plan_client_uuid")?,
                    )?;
                    self.append_dependency(
                        dependency_cursor,
                        "record",
                        required_payload_reference(&entity.payload, "record_client_uuid")?,
                    )?;
                }
                "media" => match entity.payload.get("kind").and_then(Value::as_str) {
                    Some("avatar") => self.append_dependency(
                        cursor,
                        "baby",
                        required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    )?,
                    Some("log") => {
                        if let Some(care_plan_id) = entity
                            .payload
                            .get("care_plan_client_uuid")
                            .and_then(Value::as_str)
                        {
                            self.append_dependency(cursor, "care_plan", care_plan_id)?;
                        } else {
                            self.append_dependency(
                                cursor,
                                "record",
                                required_payload_reference(&entity.payload, "record_client_uuid")?,
                            )?;
                        }
                    }
                    _ => return Err(StoreError::InvalidStoredPayload),
                },
                _ => {}
            }
        }
        self.group.push(entity);
        Ok(())
    }

    fn append_completed_plans(&mut self, record_client_uuid: &str) -> Result<(), StoreError> {
        let plan_ids = self
            .dependency_index
            .completed_plans_by_record
            .get(record_client_uuid)
            .cloned()
            .unwrap_or_default();
        for plan_id in plan_ids {
            self.append_dependency(-1, "care_plan", &plan_id)?;
        }
        Ok(())
    }

    fn append_log_media(
        &mut self,
        cursor: i64,
        parent_type: &str,
        parent_client_uuid: &str,
    ) -> Result<(), StoreError> {
        if !matches!(parent_type, "record" | "care_plan") {
            return Err(StoreError::InvalidStoredPayload);
        }
        let media_ids = self
            .dependency_index
            .log_media_by_parent
            .get(&(parent_type.to_owned(), parent_client_uuid.to_owned()))
            .cloned()
            .unwrap_or_default();
        for media_id in media_ids {
            self.append_dependency(cursor, "media", &media_id)?;
        }
        Ok(())
    }

    fn append_dependency(
        &mut self,
        cursor: i64,
        entity_type: &str,
        client_uuid: &str,
    ) -> Result<(), StoreError> {
        let key = (entity_type.to_owned(), client_uuid.to_owned());
        if self.included_keys.contains(&key) || self.group_keys.contains(&key) {
            return Ok(());
        }
        let dependency = if let Some(cached) = self.dependency_index.entities_by_key.get(&key) {
            Some(cached.clone())
        } else {
            load_pulled_entity(self.connection, self.family_id, entity_type, client_uuid)?
        }
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
        self.collect(cursor, dependency)
    }
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

fn load_projected_sidecars(
    connection: &Connection,
    family_id: &str,
    groups: &[PullGroup],
) -> Result<BTreeMap<EntityKey, ProjectedSidecars>, StoreError> {
    let keys = groups
        .iter()
        .flat_map(|group| &group.entities)
        .map(|entity| {
            serde_json::json!({
                "entity_type": entity.entity_type,
                "client_uuid": entity.client_uuid,
            })
        })
        .collect::<Vec<_>>();
    if keys.is_empty() {
        return Ok(BTreeMap::new());
    }
    let keys_json = serde_json::to_string(&keys)?;
    let mut statement = connection.prepare(
        "
        WITH page_keys(entity_type, client_uuid) AS (
            SELECT json_extract(value, '$.entity_type'),
                   json_extract(value, '$.client_uuid')
            FROM json_each(?2)
        ),
        open_conflicts AS (
            SELECT c.family_id, c.conflict_id, c.entity_type, c.client_uuid,
                   c.stable_version_id
            FROM conflicts c
            JOIN page_keys k
              ON k.entity_type = c.entity_type AND k.client_uuid = c.client_uuid
            WHERE c.family_id = ?1 AND c.status = 'open'
              AND NOT EXISTS (
                  SELECT 1 FROM conflicts earlier
                  WHERE earlier.family_id = c.family_id
                    AND earlier.entity_type = c.entity_type
                    AND earlier.client_uuid = c.client_uuid
                    AND earlier.status = 'open'
                    AND (earlier.created_at < c.created_at OR (
                        earlier.created_at = c.created_at
                        AND earlier.conflict_id < c.conflict_id COLLATE BINARY
                    ))
              )
        ),
        projection(
            kind, entity_type, client_uuid, stable_version_id, conflict_id,
            branch_version_id, relation_id, relation_role, peer_id
        ) AS (
            SELECT 'stable', k.entity_type, k.client_uuid,
                   h.version_id, NULL, NULL, NULL, NULL, NULL
            FROM page_keys k
            JOIN entity_stable_heads h
              ON h.family_id = ?1
             AND h.entity_type = k.entity_type
             AND h.client_uuid = k.client_uuid
            UNION ALL
            SELECT 'conflict', c.entity_type, c.client_uuid,
                   c.stable_version_id, c.conflict_id, NULL, NULL, NULL, NULL
            FROM open_conflicts c
            UNION ALL
            SELECT 'branch', c.entity_type, c.client_uuid,
                   NULL, c.conflict_id, b.branch_version_id, NULL, NULL, NULL
            FROM open_conflicts c
            JOIN conflict_branches b
              ON b.family_id = c.family_id AND b.conflict_id = c.conflict_id
            UNION ALL
            SELECT 'relation', 'record', member.record_client_uuid,
                   NULL, NULL, NULL, member.relation_id, member.role, NULL
            FROM source_relation_members member
            JOIN page_keys k
              ON k.entity_type = 'record'
             AND k.client_uuid = member.record_client_uuid
            WHERE member.family_id = ?1
            UNION ALL
            SELECT 'relation_peer', 'record', member.record_client_uuid,
                   NULL, NULL, NULL, member.relation_id, NULL,
                   peer.record_client_uuid
            FROM source_relation_members member
            JOIN page_keys k
              ON k.entity_type = 'record'
             AND k.client_uuid = member.record_client_uuid
            JOIN source_relation_members peer
              ON peer.family_id = member.family_id
             AND peer.relation_id = member.relation_id
             AND peer.record_client_uuid != member.record_client_uuid
            WHERE member.family_id = ?1
        )
        SELECT kind, entity_type, client_uuid, stable_version_id, conflict_id,
               branch_version_id, relation_id, relation_role, peer_id
        FROM projection
        ORDER BY entity_type COLLATE BINARY, client_uuid COLLATE BINARY,
                 CASE kind
                     WHEN 'stable' THEN 0
                     WHEN 'conflict' THEN 1
                     WHEN 'branch' THEN 2
                     WHEN 'relation' THEN 3
                     WHEN 'relation_peer' THEN 4
                 END,
                 conflict_id COLLATE BINARY, branch_version_id COLLATE BINARY,
                 relation_id COLLATE BINARY, peer_id COLLATE BINARY
        ",
    )?;
    let mut rows = statement.query(params![family_id, keys_json])?;
    let mut projected = BTreeMap::<EntityKey, ProjectedSidecars>::new();
    while let Some(row) = rows.next()? {
        match ProjectionRow::from_sql(row)? {
            ProjectionRow::Stable { key, version_id } => {
                projected.entry(key).or_default().stable_version_id = Some(version_id);
            }
            ProjectionRow::Conflict {
                key,
                conflict_id,
                stable_version_id,
            } => {
                let sidecars = projected.entry(key).or_default();
                sidecars.conflict = Some(ProjectedConflict {
                    conflict_id,
                    stable_version_id,
                    branch_version_ids: Vec::new(),
                });
            }
            ProjectionRow::Branch {
                key,
                conflict_id,
                branch_version_id,
            } => {
                let sidecars = projected.entry(key).or_default();
                let conflict = sidecars
                    .conflict
                    .as_mut()
                    .filter(|conflict| conflict.conflict_id == conflict_id)
                    .ok_or(StoreError::InvalidStoredPayload)?;
                conflict.branch_version_ids.push(branch_version_id);
            }
            ProjectionRow::Relation {
                key,
                relation_id,
                role,
            } => {
                let sidecars = projected.entry(key).or_default();
                if sidecars.relation.is_none() {
                    sidecars.relation = Some(ProjectedRelation {
                        relation_id,
                        role,
                        peer_ids: Vec::new(),
                    });
                }
            }
            ProjectionRow::RelationPeer {
                key,
                relation_id,
                peer_id,
            } => {
                let sidecars = projected.entry(key).or_default();
                if let Some(relation) = sidecars
                    .relation
                    .as_mut()
                    .filter(|relation| relation.relation_id == relation_id)
                {
                    relation.peer_ids.push(peer_id);
                }
            }
        }
    }
    Ok(projected)
}

fn attach_projected_sidecars(
    groups: &mut [PullGroup],
    projected: &BTreeMap<EntityKey, ProjectedSidecars>,
) {
    for entity in groups.iter_mut().flat_map(|group| &mut group.entities) {
        let Some(sidecars) =
            projected.get(&(entity.entity_type.clone(), entity.client_uuid.clone()))
        else {
            continue;
        };
        entity.version_id.clone_from(&sidecars.stable_version_id);
        entity.conflict_summary =
            sidecars
                .conflict
                .as_ref()
                .map(|conflict| super::ConflictSummary {
                    conflict_id: conflict.conflict_id.clone(),
                    entity_type: entity.entity_type.clone(),
                    client_uuid: entity.client_uuid.clone(),
                    stable_version_id: conflict.stable_version_id.clone(),
                    branch_version_ids: conflict.branch_version_ids.clone(),
                });
        entity.source_relation_summary =
            sidecars
                .relation
                .as_ref()
                .map(|relation| super::SourceRelationSummary {
                    relation_id: relation.relation_id.clone(),
                    role: relation.role.clone(),
                    peer_ids: relation.peer_ids.clone(),
                });
    }
}

struct PullPlanner<'a> {
    connection: &'a Connection,
    family_id: &'a str,
    cursor: i64,
    current: i64,
    family_name: Option<String>,
    final_envelope_size: &'a FinalEnvelopeSize<'a>,
}

impl PullPlanner<'_> {
    fn plan(self) -> Result<PullPage, StoreError> {
        let dependency_index =
            PullDependencyIndex::load(self.connection, self.family_id, self.cursor, self.current)?;
        let mut groups = Vec::<PullGroup>::new();
        let mut included_keys = BTreeSet::new();
        let mut candidate_entity_bytes = 0usize;
        let mut candidate_entity_count = 0usize;
        let mut scanned_cursor = self.cursor;
        let mut candidate_scan_has_more = false;
        {
            let mut statement = self.connection.prepare(
                "
                SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                FROM entities
                WHERE family_id = ?1 AND rev > ?2 AND rev <= ?3
                ORDER BY rev ASC, entity_type COLLATE BINARY, client_uuid COLLATE BINARY
                ",
            )?;
            let mut rows = statement.query(params![self.family_id, self.cursor, self.current])?;
            while let Some(row) = rows.next()? {
                let entity = pulled_entity_from_row(row)?;
                let base_rev = entity.rev;
                if is_deferred_fulfillment(self.connection, self.family_id, &entity)? {
                    scanned_cursor = base_rev;
                    continue;
                }
                let mut entities = Vec::new();
                let mut group_keys = BTreeSet::new();
                PullGroupCollector {
                    connection: self.connection,
                    dependency_index: &dependency_index,
                    family_id: self.family_id,
                    included_keys: &included_keys,
                    group_keys: &mut group_keys,
                    group: &mut entities,
                }
                .collect(self.cursor, entity)?;
                let group_bytes = entities.iter().try_fold(0usize, |total, entity| {
                    Ok::<_, StoreError>(total.saturating_add(serde_json::to_vec(entity)?.len()))
                })?;
                let next_candidate_count = candidate_entity_count.saturating_add(entities.len());
                let would_exceed_count = next_candidate_count > PULL_PAGE_ENTITY_LIMIT;
                let would_exceed_bytes = candidate_entity_bytes
                    .saturating_add(group_bytes)
                    .saturating_add(next_candidate_count.saturating_sub(1))
                    > PULL_PAGE_TARGET_BYTES;
                if would_exceed_count || would_exceed_bytes {
                    if groups.is_empty() && scanned_cursor == self.cursor {
                        return Err(StoreError::PullEntityTooLarge);
                    }
                    candidate_scan_has_more = true;
                    break;
                }
                candidate_entity_bytes = candidate_entity_bytes.saturating_add(group_bytes);
                candidate_entity_count = next_candidate_count;
                included_keys.extend(group_keys);
                groups.push(PullGroup {
                    cursor_before: scanned_cursor,
                    base_rev,
                    entities,
                });
                scanned_cursor = base_rev;
                if candidate_entity_count >= PULL_PAGE_ENTITY_LIMIT
                    || candidate_entity_bytes >= PULL_PAGE_TARGET_BYTES
                {
                    candidate_scan_has_more = base_rev < self.current;
                    break;
                }
            }
        }

        let projected = load_projected_sidecars(self.connection, self.family_id, &groups)?;
        attach_projected_sidecars(&mut groups, &projected);

        if (self.final_envelope_size)(0, 0, self.current, &self.family_name)?
            > PULL_PAGE_TARGET_BYTES
        {
            return Err(StoreError::PullEntityTooLarge);
        }

        use super::causal::MAX_CONFLICT_SUMMARIES_PER_PAGE;
        let mut entities = Vec::new();
        let mut serialized_entity_bytes = 0usize;
        let mut summary_count = 0usize;
        let mut page_cursor = if groups.is_empty() {
            scanned_cursor
        } else {
            self.cursor
        };
        let mut has_more = candidate_scan_has_more;
        for group in groups {
            let group_summary_count = group
                .entities
                .iter()
                .filter(|entity| entity.conflict_summary.is_some())
                .count();
            let group_entity_bytes = group.entities.iter().try_fold(0usize, |total, entity| {
                Ok::<_, StoreError>(total.saturating_add(serde_json::to_vec(entity)?.len()))
            })?;
            let next_entity_count = entities.len().saturating_add(group.entities.len());
            let next_entity_bytes = serialized_entity_bytes.saturating_add(group_entity_bytes);
            let exceeds_summary =
                summary_count.saturating_add(group_summary_count) > MAX_CONFLICT_SUMMARIES_PER_PAGE;
            let exceeds_count = next_entity_count > PULL_PAGE_ENTITY_LIMIT;
            let exceeds_bytes = (self.final_envelope_size)(
                next_entity_bytes,
                next_entity_count,
                self.current,
                &self.family_name,
            )? > PULL_PAGE_TARGET_BYTES;
            if exceeds_summary || exceeds_count || exceeds_bytes {
                if entities.is_empty() && group.cursor_before == self.cursor {
                    return Err(StoreError::PullEntityTooLarge);
                }
                page_cursor = group.cursor_before;
                has_more = true;
                break;
            }
            summary_count = summary_count.saturating_add(group_summary_count);
            serialized_entity_bytes = next_entity_bytes;
            page_cursor = group.base_rev;
            entities.extend(group.entities);
        }
        if !has_more {
            // Once the captured snapshot is exhausted it is safe to advance
            // across revisions whose superseded rows no longer exist.
            page_cursor = self.current;
        }
        Ok(PullPage {
            entities,
            cursor: page_cursor,
            has_more,
            family_name: self.family_name,
        })
    }
}

impl Store {
    pub(crate) fn pull_with_final_envelope_size(
        &self,
        family_id: &str,
        cursor: i64,
        final_envelope_size: impl Fn(usize, usize, i64, &Option<String>) -> Result<usize, StoreError>,
    ) -> Result<PullPage, StoreError> {
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
        PullPlanner {
            connection: &connection,
            family_id,
            cursor,
            current,
            family_name,
            final_envelope_size: &final_envelope_size,
        }
        .plan()
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
