//! Family pull planning over complete, dependency-safe wire envelopes.
//!
//! [`PullPlanner`] owns candidate grouping, one batched causal/source projection,
//! and the final count/summary/serialized-byte decision. Sidecars are therefore
//! part of pagination rather than decoration after the cursor has advanced.

use std::collections::{BTreeMap, BTreeSet, HashMap};

use rusqlite::types::Value as SqlValue;
use rusqlite::{params, params_from_iter, Connection, OptionalExtension};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

use crate::{PULL_PAGE_ENTITY_LIMIT, PULL_PAGE_TARGET_BYTES};

use super::live_census_cache;
use super::LIVE_CENSUS_ENTITY_TYPES;
use super::{
    parse_payload, EntityKey, LiveCensus, LiveCensusEntry, PullPage, PulledEntity, Store,
    StoreError,
};

#[derive(Debug)]
struct PullGroup {
    cursor_before: i64,
    base_rev: i64,
    entities: Vec<PulledEntity>,
    /// Exact serialized bytes per entity, parallel to `entities`. Budget math
    /// reads their lengths; the handler splices the bytes into the response so
    /// the page is serialized once.
    wire: Vec<Vec<u8>>,
}

#[derive(Default)]
struct PullDependencyIndex {
    log_media_by_parent: BTreeMap<EntityKey, Vec<String>>,
    completed_plans_by_record: BTreeMap<String, Vec<String>>,
    entities_by_key: BTreeMap<EntityKey, PulledEntity>,
}

fn base_window_needs_dependency_index(
    connection: &Connection,
    family_id: &str,
    cursor: i64,
    current: i64,
) -> Result<bool, StoreError> {
    // fulfillment_candidate is included: those pages co-group parent records,
    // and the parents' log media and completed plans come from this index.
    let mut statement = connection.prepare(
        "
        SELECT 1
        FROM (
            SELECT entity_type
            FROM entities
            WHERE family_id = ?1 AND rev > ?2 AND rev <= ?3
            ORDER BY rev ASC, entity_type COLLATE BINARY, client_uuid COLLATE BINARY
            LIMIT ?4
        )
        WHERE entity_type IN (
            'record', 'care_plan', 'wake_observation', 'media', 'fulfillment_candidate'
        )
        LIMIT 1
        ",
    )?;
    Ok(statement
        .query_row(
            params![family_id, cursor, current, PULL_PAGE_ENTITY_LIMIT as i64],
            |_| Ok(()),
        )
        .optional()?
        .is_some())
}

impl PullDependencyIndex {
    fn load(
        connection: &Connection,
        family_id: &str,
        cursor: i64,
        current: i64,
    ) -> Result<Self, StoreError> {
        if !base_window_needs_dependency_index(connection, family_id, cursor, current)? {
            return Ok(Self::default());
        }
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
                WHERE entity_type IN ('record', 'care_plan', 'wake_observation')
                UNION
                SELECT 'record', json_extract(payload_json, '$.record_client_uuid')
                FROM base
                WHERE entity_type = 'media'
                  AND json_extract(payload_json, '$.kind') = 'log'
                  AND json_extract(payload_json, '$.record_client_uuid') IS NOT NULL
                UNION
                SELECT 'wake_observation', json_extract(payload_json, '$.record_client_uuid')
                FROM base
                WHERE entity_type = 'media'
                  AND json_extract(payload_json, '$.kind') = 'wake'
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
                SELECT 'record', json_extract(payload_json, '$.sleep_record_client_uuid')
                FROM base
                WHERE entity_type = 'wake_observation'
                  AND json_extract(payload_json, '$.sleep_record_client_uuid') IS NOT NULL
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
            scoped_media(client_uuid, rev, kind, record_client_uuid, care_plan_client_uuid) AS (
                SELECT client_uuid, rev,
                       json_extract(payload_json, '$.kind'),
                       json_extract(payload_json, '$.record_client_uuid'),
                       json_extract(payload_json, '$.care_plan_client_uuid')
                FROM (
                    SELECT client_uuid, rev, payload_json
                    FROM entities
                    WHERE family_id = ?1
                      AND entity_type = 'media'
                      AND deleted_at IS NULL
                )
            ),
            dependencies(kind, parent_type, parent_id, child_id, child_rev) AS (
                SELECT 'log_media', parent.entity_type, parent.client_uuid,
                       media.client_uuid, media.rev
                FROM all_parents parent
                JOIN scoped_media media
                  ON media.kind = 'log'
                 AND parent.entity_type = 'record'
                 AND media.record_client_uuid = parent.client_uuid
                UNION ALL
                SELECT 'log_media', parent.entity_type, parent.client_uuid,
                       media.client_uuid, media.rev
                FROM all_parents parent
                JOIN scoped_media media
                  ON media.kind = 'log'
                 AND parent.entity_type = 'care_plan'
                 AND media.care_plan_client_uuid = parent.client_uuid
                UNION ALL
                SELECT 'wake_media', parent.entity_type, parent.client_uuid,
                       media.client_uuid, media.rev
                FROM all_parents parent
                JOIN scoped_media media
                  ON media.kind = 'wake'
                 AND parent.entity_type = 'wake_observation'
                 AND media.record_client_uuid = parent.client_uuid
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
                "log_media" | "wake_media" => index
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
        // Keep candidate keys outside the entity lookup loop: an unconstrained
        // join order can rescan the key-generating coroutine for every family row.
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
                WHERE entity_type = 'fulfillment_candidate'
                  AND json_extract(payload_json, '$.record_client_uuid') IS NOT NULL
                UNION
                SELECT 'record', json_extract(payload_json, '$.sleep_record_client_uuid')
                FROM base
                WHERE entity_type = 'wake_observation'
                  AND json_extract(payload_json, '$.sleep_record_client_uuid') IS NOT NULL
                UNION
                SELECT 'record', json_extract(payload_json, '$.record_client_uuid')
                FROM base
                WHERE entity_type = 'media'
                  AND json_extract(payload_json, '$.kind') = 'log'
                  AND json_extract(payload_json, '$.record_client_uuid') IS NOT NULL
                UNION
                SELECT 'wake_observation', json_extract(payload_json, '$.record_client_uuid')
                FROM base
                WHERE entity_type = 'media'
                  AND json_extract(payload_json, '$.kind') = 'wake'
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
            CROSS JOIN entities entity
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
    mutation_id: Option<String>,
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
        mutation_id: Option<String>,
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
                mutation_id: row.get(9)?,
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

fn project_pulled_payload(
    entity_type: &str,
    mut payload: Map<String, Value>,
) -> Map<String, Value> {
    if entity_type == "care_plan" {
        crate::model::project_care_plan_source_key(&mut payload);
    }
    payload
}

fn pulled_entity_from_row(row: &rusqlite::Row<'_>) -> Result<PulledEntity, StoreError> {
    let entity_type = row.get::<_, String>(0)?;
    let payload = project_pulled_payload(&entity_type, parse_payload(&row.get::<_, String>(4)?)?);
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
        media_identity: None,
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
                let payload = project_pulled_payload(&entity_type, parse_payload(&payload_raw)?);
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
                    media_identity: None,
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
                "wake_observation" => {
                    // Type completeness: Wake names its SleepStart. Not an interval pair.
                    self.append_dependency(
                        -1,
                        "record",
                        required_payload_reference(&entity.payload, "sleep_record_client_uuid")?,
                    )?;
                    self.append_log_media(cursor, "wake_observation", &entity.client_uuid)?;
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
                    Some("wake") => self.append_dependency(
                        cursor,
                        "wake_observation",
                        required_payload_reference(&entity.payload, "record_client_uuid")?,
                    )?,
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
        if !matches!(parent_type, "record" | "care_plan" | "wake_observation") {
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
    deferred_plan_cache: &mut HashMap<String, bool>,
) -> Result<bool, StoreError> {
    match entity.entity_type.as_str() {
        "care_plan" => {
            let plan_id = entity.client_uuid.as_str();
            if let Some(cached) = deferred_plan_cache.get(plan_id) {
                return Ok(*cached);
            }
            let value = is_deferred_care_plan(connection, family_id, entity)?;
            deferred_plan_cache.insert(plan_id.to_string(), value);
            Ok(value)
        }
        "media" => {
            let Some(plan_id) = entity
                .payload
                .get("care_plan_client_uuid")
                .and_then(Value::as_str)
            else {
                return Ok(false);
            };
            // The whole pull runs behind the family write-lock, so repeated
            // probes of the same plan within one planning pass are stable;
            // cache the verdict (including the missing-plan case) to avoid
            // 1-2 queries per scanned media row.
            if let Some(cached) = deferred_plan_cache.get(plan_id) {
                return Ok(*cached);
            }
            let value = match load_pulled_entity(connection, family_id, "care_plan", plan_id)? {
                Some(plan) => is_deferred_care_plan(connection, family_id, &plan)?,
                None => false,
            };
            deferred_plan_cache.insert(plan_id.to_string(), value);
            Ok(value)
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
              AND EXISTS (
                  SELECT 1 FROM conflict_branches b
                   WHERE b.family_id = c.family_id AND b.conflict_id = c.conflict_id
              )
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
            branch_version_id, relation_id, relation_role, peer_id, mutation_id
        ) AS (
            SELECT 'stable', k.entity_type, k.client_uuid,
                   h.version_id, NULL, NULL, NULL, NULL, NULL, NULL
            FROM page_keys k
            CROSS JOIN entity_stable_heads h
              ON h.family_id = ?1
             AND h.entity_type = k.entity_type
             AND h.client_uuid = k.client_uuid
            UNION ALL
            SELECT 'conflict', c.entity_type, c.client_uuid,
                   c.stable_version_id, c.conflict_id, NULL, NULL, NULL, NULL, NULL
            FROM open_conflicts c
            UNION ALL
            SELECT 'branch', c.entity_type, c.client_uuid,
                   NULL, c.conflict_id, b.branch_version_id, NULL, NULL, NULL, NULL
            FROM open_conflicts c
            JOIN conflict_branches b
              ON b.family_id = c.family_id AND b.conflict_id = c.conflict_id
            UNION ALL
            SELECT 'relation', 'record', member.record_client_uuid,
                   NULL, NULL, NULL, member.relation_id, member.role, NULL,
                   rel.mutation_id
            FROM source_relation_members member
            JOIN source_relations rel
              ON rel.family_id = member.family_id
             AND rel.relation_id = member.relation_id
            JOIN page_keys k
              ON k.entity_type = 'record'
             AND k.client_uuid = member.record_client_uuid
            WHERE member.family_id = ?1
            UNION ALL
            SELECT 'relation_peer', 'record', member.record_client_uuid,
                   NULL, NULL, NULL, member.relation_id, NULL,
                   peer.record_client_uuid, NULL
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
               branch_version_id, relation_id, relation_role, peer_id, mutation_id
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
                mutation_id,
            } => {
                let sidecars = projected.entry(key).or_default();
                if sidecars.relation.is_none() {
                    sidecars.relation = Some(ProjectedRelation {
                        relation_id,
                        role,
                        peer_ids: Vec::new(),
                        mutation_id,
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
        entity.conflict_summary = sidecars.conflict.as_ref().and_then(|conflict| {
            if conflict.branch_version_ids.is_empty() {
                return None;
            }
            Some(super::ConflictSummary {
                conflict_id: conflict.conflict_id.clone(),
                entity_type: entity.entity_type.clone(),
                client_uuid: entity.client_uuid.clone(),
                stable_version_id: conflict.stable_version_id.clone(),
                branch_version_ids: conflict.branch_version_ids.clone(),
            })
        });
        entity.source_relation_summary =
            sidecars
                .relation
                .as_ref()
                .map(|relation| super::SourceRelationSummary {
                    relation_id: relation.relation_id.clone(),
                    role: relation.role.clone(),
                    peer_ids: relation.peer_ids.clone(),
                    auto_aligned: relation
                        .mutation_id
                        .as_deref()
                        .is_some_and(|id| {
                            id.starts_with(
                                super::suspected_duplicates::AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX,
                            )
                        })
                        .then_some(true),
                });
    }
}

/// Pure-read export of the full-family live set at the current head (wire
/// §1.4): per-type live (`deleted_at IS NULL`) `client_uuid` count and digest.
/// The census never writes entity rows and is independent of the page subset,
/// so every page under the same head carries the same census.
pub(in crate::store) fn compute_live_census(
    connection: &Connection,
    family_id: &str,
) -> Result<LiveCensus, StoreError> {
    let mut statement = connection.prepare(
        "
        SELECT entity_type, client_uuid
        FROM entities
        WHERE family_id = ?1 AND deleted_at IS NULL
        ORDER BY entity_type COLLATE BINARY, client_uuid COLLATE BINARY
        ",
    )?;
    let mut live_keys = BTreeMap::<String, Vec<String>>::new();
    let rows = statement.query_map(params![family_id], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
    })?;
    for row in rows {
        let (entity_type, client_uuid) = row?;
        live_keys.entry(entity_type).or_default().push(client_uuid);
    }
    let mut entries = BTreeMap::<&'static str, LiveCensusEntry>::new();
    for &entity_type in LIVE_CENSUS_ENTITY_TYPES {
        let keys = live_keys.remove(entity_type).unwrap_or_default();
        entries.insert(
            entity_type,
            LiveCensusEntry {
                count: keys.len() as u64,
                key_digest: live_key_digest(&keys),
                keys: None,
            },
        );
    }
    Ok(LiveCensus { entries })
}

/// Lowercase hex SHA-256 over the UTF-8 bytes of the live keys sorted
/// lexicographically and joined by `\n` (no trailing newline). The empty set
/// digests the empty byte string (`e3b0c442…b855`).
fn live_key_digest(keys: &[String]) -> String {
    hex::encode(Sha256::digest(keys.join("\n").as_bytes()))
}

fn pulled_entity_wire(entity: &PulledEntity) -> Result<Vec<u8>, StoreError> {
    Ok(serde_json::to_vec(entity)?)
}

fn refresh_sidecar_wire_bytes(groups: &mut [PullGroup]) -> Result<(), StoreError> {
    for group in groups {
        for (entity, bytes) in group.entities.iter().zip(group.wire.iter_mut()) {
            if entity.version_id.is_some()
                || entity.conflict_summary.is_some()
                || entity.source_relation_summary.is_some()
            {
                *bytes = pulled_entity_wire(entity)?;
            }
        }
    }
    Ok(())
}

struct PullPlanner<'a> {
    database_path: &'a std::path::Path,
    include_media_identity: bool,
    connection: &'a Connection,
    family_id: &'a str,
    cursor: i64,
    current: i64,
    family_name: Option<String>,
    live_census: Option<LiveCensus>,
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
        let mut deferred_plan_cache = HashMap::new();
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
                if is_deferred_fulfillment(
                    self.connection,
                    self.family_id,
                    &entity,
                    &mut deferred_plan_cache,
                )? {
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
                if self.include_media_identity {
                    for entity in &mut entities {
                        attach_media_identity(
                            self.connection,
                            self.database_path,
                            self.family_id,
                            entity,
                        )?;
                    }
                }
                let wire = entities
                    .iter()
                    .map(pulled_entity_wire)
                    .collect::<Result<Vec<_>, _>>()?;
                let group_bytes = wire
                    .iter()
                    .map(Vec::len)
                    .fold(0usize, |total, len| total.saturating_add(len));
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
                    wire,
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
        refresh_sidecar_wire_bytes(&mut groups)?;

        if (self.final_envelope_size)(0, 0, self.current, &self.family_name)?
            > PULL_PAGE_TARGET_BYTES
        {
            return Err(StoreError::PullEntityTooLarge);
        }

        use super::causal::MAX_CONFLICT_SUMMARIES_PER_PAGE;
        let mut entities = Vec::new();
        let mut entity_wire: Vec<Vec<u8>> = Vec::new();
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
            let group_entity_bytes = group
                .wire
                .iter()
                .map(Vec::len)
                .fold(0usize, |total, len| total.saturating_add(len));
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
            entity_wire.extend(group.wire);
        }
        if !has_more {
            // Once the captured snapshot is exhausted it is safe to advance
            // across revisions whose superseded rows no longer exist.
            page_cursor = self.current;
        }
        Ok(PullPage {
            entities,
            entity_wire,
            cursor: page_cursor,
            has_more,
            family_name: self.family_name,
            live_census: self.live_census,
        })
    }
}

impl Store {
    #[cfg(test)]
    pub(crate) fn pull_with_final_envelope_size(
        &self,
        family_id: &str,
        cursor: i64,
        include_live_census: bool,
        live_key_types: &BTreeSet<&str>,
        final_envelope_size: impl Fn(usize, usize, i64, &Option<String>) -> Result<usize, StoreError>,
    ) -> Result<PullPage, StoreError> {
        let connection = self.connect()?;
        self.pull_with_final_envelope_size_on(
            &connection,
            family_id,
            cursor,
            include_live_census,
            live_key_types,
            final_envelope_size,
        )
    }

    #[cfg(test)]
    pub(crate) fn pull_with_final_envelope_size_on(
        &self,
        connection: &Connection,
        family_id: &str,
        cursor: i64,
        include_live_census: bool,
        live_key_types: &BTreeSet<&str>,
        final_envelope_size: impl Fn(usize, usize, i64, &Option<String>) -> Result<usize, StoreError>,
    ) -> Result<PullPage, StoreError> {
        self.pull_with_media_identity_on(
            connection,
            family_id,
            cursor,
            include_live_census,
            live_key_types,
            false,
            final_envelope_size,
        )
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn pull_with_media_identity_on(
        &self,
        connection: &Connection,
        family_id: &str,
        cursor: i64,
        include_live_census: bool,
        live_key_types: &BTreeSet<&str>,
        include_media_identity: bool,
        final_envelope_size: impl Fn(usize, usize, i64, &Option<String>) -> Result<usize, StoreError>,
    ) -> Result<PullPage, StoreError> {
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
        // The census is a pure read taken at the same head as the page; it is
        // the full-family live set, not the page subset. Same-head pages hit
        // the per-(family, head) cache; see `live_census_cache`.
        let mut live_census = if include_live_census {
            Some(self.live_census_at_head(connection, family_id, current)?)
        } else {
            None
        };
        if let Some(census) = live_census.as_mut() {
            if !live_key_types.is_empty() {
                *census = self.attach_live_keys_to_census_on(
                    connection,
                    family_id,
                    census.clone(),
                    live_key_types,
                )?;
            }
        }
        // Keys ride on every keyed page. Reserve their excess over the
        // placeholder so a byte-full entity page cannot grow past the
        // decoded pull budget when the arrays are attached.
        let key_overhead = live_census
            .as_ref()
            .map(LiveCensus::budget_overhead_against_placeholder)
            .transpose()?
            .unwrap_or(0);
        let budgeted_envelope =
            |serialized_entity_bytes, entity_count, head: i64, name: &Option<String>| {
                Ok(
                    final_envelope_size(serialized_entity_bytes, entity_count, head, name)?
                        .saturating_add(key_overhead),
                )
            };
        if cursor == current {
            return Ok(PullPage {
                entities: Vec::new(),
                entity_wire: Vec::new(),
                cursor: current,
                has_more: false,
                family_name,
                live_census,
            });
        }
        PullPlanner {
            database_path: &self.database_path,
            include_media_identity,
            connection,
            family_id,
            cursor,
            current,
            family_name,
            live_census,
            final_envelope_size: &budgeted_envelope,
        }
        .plan()
    }

    #[cfg(test)]
    pub(crate) fn attach_live_keys_to_census(
        &self,
        family_id: &str,
        census: LiveCensus,
        key_types: &BTreeSet<&str>,
    ) -> Result<LiveCensus, StoreError> {
        let connection = self.connect()?;
        self.attach_live_keys_to_census_on(&connection, family_id, census, key_types)
    }

    pub(crate) fn attach_live_keys_to_census_on(
        &self,
        connection: &Connection,
        family_id: &str,
        mut census: LiveCensus,
        key_types: &BTreeSet<&str>,
    ) -> Result<LiveCensus, StoreError> {
        if key_types.is_empty() {
            return Ok(census);
        }
        let selected = LIVE_CENSUS_ENTITY_TYPES
            .iter()
            .copied()
            .filter(|entity_type| key_types.contains(entity_type))
            .collect::<Vec<_>>();
        if selected.is_empty() {
            return Ok(census);
        }
        let placeholders = std::iter::repeat_n("?", selected.len())
            .collect::<Vec<_>>()
            .join(", ");
        let sql = format!(
            "
            SELECT entity_type, client_uuid
            FROM entities
            WHERE family_id = ?1
              AND deleted_at IS NULL
              AND entity_type IN ({placeholders})
            ORDER BY entity_type COLLATE BINARY, client_uuid COLLATE BINARY
            "
        );
        let mut statement = connection.prepare(&sql)?;
        let mut parameters = Vec::with_capacity(selected.len() + 1);
        parameters.push(SqlValue::Text(family_id.to_owned()));
        parameters.extend(
            selected
                .iter()
                .map(|entity_type| SqlValue::Text((*entity_type).to_owned())),
        );
        let mut live_keys = BTreeMap::<String, Vec<String>>::new();
        let rows = statement.query_map(params_from_iter(parameters), |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        for row in rows {
            let (entity_type, client_uuid) = row?;
            if key_types.contains(entity_type.as_str()) {
                live_keys.entry(entity_type).or_default().push(client_uuid);
            }
        }
        let mut attached = BTreeMap::<&'static str, Vec<String>>::new();
        for &entity_type in LIVE_CENSUS_ENTITY_TYPES {
            if key_types.contains(entity_type) {
                attached.insert(
                    entity_type,
                    live_keys.remove(entity_type).unwrap_or_default(),
                );
            }
        }
        census.attach_keys(attached);
        Ok(census)
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

    /// Serves the live census at `head_rev` from the per-(family, head) cache
    /// when a live entry matches, else rebuilds exactly once and installs it.
    /// Read-only stores never install (they also never write revs).
    pub(in crate::store) fn live_census_at_head(
        &self,
        connection: &Connection,
        family_id: &str,
        head_rev: i64,
    ) -> Result<LiveCensus, StoreError> {
        let token = match self.live_census_cache.lookup(family_id, head_rev) {
            live_census_cache::CensusLookup::Hit(census) => return Ok(census),
            live_census_cache::CensusLookup::Miss(token) => token,
        };
        let census = compute_live_census(connection, family_id)?;
        if !self.read_only {
            self.live_census_cache
                .install(family_id, token, head_rev, census.clone());
        }
        Ok(census)
    }
}

pub(super) fn attach_media_identity(
    connection: &Connection,
    database_path: &std::path::Path,
    family_id: &str,
    entity: &mut PulledEntity,
) -> Result<(), StoreError> {
    if entity.entity_type != "media" || entity.deleted_at.is_some() {
        return Ok(());
    }
    let (owner_type, owner_id) =
        super::media_association_owner(&entity.payload)?.ok_or(StoreError::InvalidStoredPayload)?;
    let evidence: Option<(String,String)> = connection.query_row(
        "SELECT v.origin,m.media_payload_json FROM entity_stable_heads h JOIN entity_versions v ON v.family_id=h.family_id AND v.version_id=h.version_id AND v.entity_type=h.entity_type AND v.client_uuid=h.client_uuid JOIN entity_version_media m ON m.family_id=h.family_id AND m.version_id=h.version_id WHERE h.family_id=?1 AND h.entity_type=?2 AND h.client_uuid=?3 AND m.media_uuid=?4",
        params![family_id,owner_type,owner_id,entity.client_uuid], |row| Ok((row.get(0)?,row.get(1)?))).optional()?;
    let (origin, raw) = evidence.ok_or(StoreError::InvalidStoredPayload)?;
    let value: Value = serde_json::from_str(&raw)?;
    let role = match owner_type {
        "baby" => "avatar",
        "care_plan" => "plan",
        "wake_observation" => "wake",
        "record" => "log",
        _ => return Err(StoreError::InvalidStoredPayload),
    };
    let size = entity
        .payload
        .get("byte_size")
        .and_then(Value::as_i64)
        .filter(|size| *size > 0)
        .ok_or(StoreError::InvalidStoredPayload)?;
    let hash = if let Some(item) = super::CausalMediaItem::from_value(&value) {
        item.validate_for_entity(owner_type)
            .map_err(|_| StoreError::InvalidStoredPayload)?;
        if item.media_uuid != entity.client_uuid
            || item.role != role
            || item.byte_size != size
            || entity.payload.get("mime").and_then(Value::as_str) != Some(item.mime.as_str())
            || entity.payload.get("width").and_then(Value::as_i64) != item.width
            || entity.payload.get("height").and_then(Value::as_i64) != item.height
        {
            return Err(StoreError::InvalidStoredPayload);
        }
        item.sha256
    } else if origin == "migration_base" && value.as_object() == Some(&entity.payload) {
        super::causal_media_staging::published_file_sha256(
            &super::causal_media_staging::published_path(
                database_path,
                family_id,
                &entity.client_uuid,
            ),
            size as u64,
        )?
        .ok_or(StoreError::InvalidStoredPayload)?
    } else {
        return Err(StoreError::InvalidStoredPayload);
    };
    entity.media_identity = Some(
        serde_json::json!({"media_uuid":entity.client_uuid,"role":role,"sha256":hash,"byte_size":size}),
    );
    Ok(())
}

#[cfg(test)]
#[path = "tests/pull_dependency_tests.rs"]
mod dependency_tests;
