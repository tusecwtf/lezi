//! Family neighbor adjudication for whitelist care records (ADR-0018 / 0.3.10).
//!
//! Runs only inside the atomic commit transaction on shards touched by this
//! commit's whitelist record live mutations. Writes neighbor-loser tombstones in
//! the same transaction so the public authority graph never exposes dual live
//! neighbors after a successful commit.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};

use rusqlite::{params, Transaction};
use serde_json::{Map, Value};

use crate::model::Entity;

use super::bundles::ExistingEntity;
use super::{parse_payload, EntityKey, StoreError};

/// Inclusive 30-minute window on the event main timestamp (milliseconds or
/// any consistent integer unit used by the wire `timestamp` field).
pub(crate) const NEIGHBOR_WINDOW: i64 = 30 * 60 * 1000;

/// Exact wire type keys that participate in neighbor collapse.
const WHITELIST_TYPES: &[&str] = &[
    "nursing",
    "formula",
    "pumped_feed",
    "pump_express",
    "baby_food",
    "snack",
    "drink",
    "pee",
    "poop",
    "both_diaper",
    "temperature",
    "bath",
    "medicine",
];

pub(crate) fn is_neighbor_whitelist_type(record_type: &str) -> bool {
    WHITELIST_TYPES.contains(&record_type)
}

#[derive(Debug, Clone)]
struct LiveNeighborRow {
    client_uuid: String,
    updated_at: i64,
    payload: Map<String, Value>,
    baby_id: String,
    record_type: String,
    timestamp: i64,
    author_membership_id: String,
}

/// Shards `(baby, type)` whose live set or baby/type/timestamp/deleted state
/// changed via a whitelist record in this commit package.
pub(crate) fn touched_whitelist_shards(
    effective: &[Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> BTreeMap<(String, String), BTreeSet<i64>> {
    let mut shards: BTreeMap<(String, String), BTreeSet<i64>> = BTreeMap::new();
    for entity in effective.iter().filter(|e| e.entity_type == "record") {
        let Some(record_type) = entity.payload.get("type").and_then(Value::as_str) else {
            continue;
        };
        if !is_neighbor_whitelist_type(record_type) {
            continue;
        }
        let Some(baby_id) = entity
            .payload
            .get("baby_client_uuid")
            .and_then(Value::as_str)
        else {
            continue;
        };
        let Some(timestamp) = entity.payload.get("timestamp").and_then(Value::as_i64) else {
            continue;
        };
        let key = ("record".to_owned(), entity.client_uuid.clone());
        let previous = existing.get(&key);
        let live_mutation = match previous {
            None => entity.deleted_at.is_none(),
            Some(prev) => {
                let prev_type = prev.payload.get("type").and_then(Value::as_str);
                let prev_baby = prev.payload.get("baby_client_uuid").and_then(Value::as_str);
                let prev_ts = prev.payload.get("timestamp").and_then(Value::as_i64);
                let baby_type_ts_changed = prev_type != Some(record_type)
                    || prev_baby != Some(baby_id)
                    || prev_ts != Some(timestamp);
                let deleted_changed = prev.deleted_at.is_some() != entity.deleted_at.is_some();
                // Live create/update of identity fields, or any live↔tombstone flip.
                (entity.deleted_at.is_none()
                    && (baby_type_ts_changed || prev.updated_at != entity.updated_at))
                    || deleted_changed
                    || (entity.deleted_at.is_none() && baby_type_ts_changed)
            }
        };
        if !live_mutation {
            continue;
        }
        let shard = (baby_id.to_owned(), record_type.to_owned());
        let times = shards.entry(shard.clone()).or_default();
        times.insert(timestamp);
        if let Some(prev) = previous {
            if let Some(prev_ts) = prev.payload.get("timestamp").and_then(Value::as_i64) {
                times.insert(prev_ts);
            }
            // If baby/type moved, also touch the previous shard so leftover
            // neighbors re-evaluate without a full-family scan.
            if let (Some(prev_baby), Some(prev_type)) = (
                prev.payload.get("baby_client_uuid").and_then(Value::as_str),
                prev.payload.get("type").and_then(Value::as_str),
            ) {
                if is_neighbor_whitelist_type(prev_type)
                    && (prev_baby != baby_id || prev_type != record_type)
                {
                    let prev_time = prev
                        .payload
                        .get("timestamp")
                        .and_then(Value::as_i64)
                        .unwrap_or(timestamp);
                    shards
                        .entry((prev_baby.to_owned(), prev_type.to_owned()))
                        .or_default()
                        .insert(prev_time);
                }
            }
        }
    }
    shards
}

/// Adjudicate neighbor groups for touched shards; return loser root entities
/// ready to persist (soft-delete tombstones with raised `updated_at`).
pub(crate) fn adjudicate_neighbor_losers(
    transaction: &Transaction<'_>,
    family_id: &str,
    effective: &[Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
    now: i64,
) -> Result<Vec<Entity>, StoreError> {
    let shards = touched_whitelist_shards(effective, existing);
    if shards.is_empty() {
        return Ok(Vec::new());
    }

    let owner_membership_id = load_current_owner_membership_id(transaction, family_id)?;

    // Effective live winners from this commit override DB rows for the same uuid.
    let mut package_live: HashMap<String, LiveNeighborRow> = HashMap::new();
    let mut package_tombstoned: HashSet<String> = HashSet::new();
    for entity in effective.iter().filter(|e| e.entity_type == "record") {
        if entity.deleted_at.is_some() {
            package_tombstoned.insert(entity.client_uuid.clone());
            package_live.remove(&entity.client_uuid);
            continue;
        }
        if let Some(row) = live_row_from_entity(entity) {
            package_live.insert(entity.client_uuid.clone(), row);
        }
    }

    let mut losers: Vec<Entity> = Vec::new();
    let mut already_lost: HashSet<String> = HashSet::new();

    for ((baby_id, record_type), touch_times) in shards {
        if touch_times.is_empty() {
            continue;
        }
        let min_t = *touch_times.iter().min().unwrap();
        let max_t = *touch_times.iter().max().unwrap();
        let load_lo = min_t.saturating_sub(NEIGHBOR_WINDOW);
        let load_hi = max_t.saturating_add(NEIGHBOR_WINDOW);

        let mut candidates = load_live_whitelist_neighborhood(
            transaction,
            family_id,
            &baby_id,
            &record_type,
            load_lo,
            load_hi,
        )?;
        // Overlay package live rows for this shard.
        for (uuid, row) in &package_live {
            if row.baby_id == baby_id && row.record_type == record_type {
                candidates.insert(uuid.clone(), row.clone());
            }
        }
        // Drop package-tombstoned and empty authors.
        candidates.retain(|uuid, row| {
            !package_tombstoned.contains(uuid)
                && !row.author_membership_id.is_empty()
                && row.timestamp >= load_lo
                && row.timestamp <= load_hi
        });

        if candidates.len() < 2 {
            continue;
        }

        let components = connected_components(&candidates);
        for component in components {
            if component.len() < 2 {
                continue;
            }
            let winner_uuid =
                select_winner(&component, &candidates, owner_membership_id.as_deref());
            for uuid in component {
                if uuid == winner_uuid || already_lost.contains(&uuid) {
                    continue;
                }
                let Some(row) = candidates.get(&uuid) else {
                    continue;
                };
                let tombstone_updated_at = now.max(row.updated_at.saturating_add(1));
                losers.push(Entity {
                    entity_type: "record".to_owned(),
                    client_uuid: uuid.clone(),
                    updated_at: tombstone_updated_at,
                    deleted_at: Some(tombstone_updated_at),
                    payload: row.payload.clone(),
                });
                already_lost.insert(uuid);
            }
        }
    }

    losers.sort_by(|a, b| a.client_uuid.cmp(&b.client_uuid));
    Ok(losers)
}

fn load_current_owner_membership_id(
    transaction: &Transaction<'_>,
    family_id: &str,
) -> Result<Option<String>, StoreError> {
    let id = transaction
        .query_row(
            "
            SELECT membership_id
            FROM memberships
            WHERE family_id = ?1
              AND role = 'owner'
              AND left_at IS NULL
            LIMIT 1
            ",
            params![family_id],
            |row| row.get::<_, String>(0),
        )
        .optional_store()?;
    Ok(id)
}

trait OptionalStoreExt<T> {
    fn optional_store(self) -> Result<Option<T>, StoreError>;
}

impl<T> OptionalStoreExt<T> for Result<T, rusqlite::Error> {
    fn optional_store(self) -> Result<Option<T>, StoreError> {
        match self {
            Ok(value) => Ok(Some(value)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(error) => Err(StoreError::from(error)),
        }
    }
}

fn live_row_from_entity(entity: &Entity) -> Option<LiveNeighborRow> {
    if entity.deleted_at.is_some() {
        return None;
    }
    let record_type = entity
        .payload
        .get("type")
        .and_then(Value::as_str)?
        .to_owned();
    if !is_neighbor_whitelist_type(&record_type) {
        return None;
    }
    let baby_id = entity
        .payload
        .get("baby_client_uuid")
        .and_then(Value::as_str)?
        .to_owned();
    let timestamp = entity.payload.get("timestamp").and_then(Value::as_i64)?;
    let author = entity
        .payload
        .get("created_by_membership_id")
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .unwrap_or("")
        .to_owned();
    if author.is_empty() {
        return None;
    }
    Some(LiveNeighborRow {
        client_uuid: entity.client_uuid.clone(),
        updated_at: entity.updated_at,
        payload: entity.payload.clone(),
        baby_id,
        record_type,
        timestamp,
        author_membership_id: author,
    })
}

fn load_live_whitelist_neighborhood(
    transaction: &Transaction<'_>,
    family_id: &str,
    baby_id: &str,
    record_type: &str,
    load_lo: i64,
    load_hi: i64,
) -> Result<HashMap<String, LiveNeighborRow>, StoreError> {
    let mut statement = transaction.prepare(
        "
        SELECT client_uuid, updated_at, payload_json
        FROM entities
        WHERE family_id = ?1
          AND entity_type = 'record'
          AND deleted_at IS NULL
          AND json_extract(payload_json, '$.baby_client_uuid') = ?2
          AND json_extract(payload_json, '$.type') = ?3
          AND CAST(json_extract(payload_json, '$.timestamp') AS INTEGER) BETWEEN ?4 AND ?5
        ",
    )?;
    let rows = statement.query_map(
        params![family_id, baby_id, record_type, load_lo, load_hi],
        |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
            ))
        },
    )?;
    let mut out = HashMap::new();
    for row in rows {
        let (client_uuid, updated_at, payload_json) = row?;
        let payload = parse_payload(&payload_json)?;
        let entity = Entity {
            entity_type: "record".to_owned(),
            client_uuid: client_uuid.clone(),
            updated_at,
            deleted_at: None,
            payload,
        };
        if let Some(live) = live_row_from_entity(&entity) {
            out.insert(client_uuid, live);
        }
    }
    Ok(out)
}

fn connected_components(candidates: &HashMap<String, LiveNeighborRow>) -> Vec<Vec<String>> {
    let uuids: Vec<String> = candidates.keys().cloned().collect();
    let mut adj: HashMap<String, Vec<String>> = HashMap::new();
    for uuid in &uuids {
        adj.entry(uuid.clone()).or_default();
    }
    for i in 0..uuids.len() {
        for j in (i + 1)..uuids.len() {
            let a = &candidates[&uuids[i]];
            let b = &candidates[&uuids[j]];
            if a.author_membership_id == b.author_membership_id {
                continue;
            }
            let delta = (a.timestamp - b.timestamp).abs();
            if delta <= NEIGHBOR_WINDOW {
                adj.get_mut(&uuids[i]).unwrap().push(uuids[j].clone());
                adj.get_mut(&uuids[j]).unwrap().push(uuids[i].clone());
            }
        }
    }
    let mut seen = HashSet::new();
    let mut components = Vec::new();
    for start in &uuids {
        if !seen.insert(start.clone()) {
            continue;
        }
        let mut queue = VecDeque::new();
        let mut component = Vec::new();
        queue.push_back(start.clone());
        while let Some(node) = queue.pop_front() {
            component.push(node.clone());
            for next in adj.get(&node).into_iter().flatten() {
                if seen.insert(next.clone()) {
                    queue.push_back(next.clone());
                }
            }
        }
        components.push(component);
    }
    components
}

fn select_winner(
    component: &[String],
    candidates: &HashMap<String, LiveNeighborRow>,
    owner_membership_id: Option<&str>,
) -> String {
    let mut best: Option<&LiveNeighborRow> = None;
    for uuid in component {
        let row = &candidates[uuid];
        let replace = match best {
            None => true,
            Some(current) => preferred_over(row, current, owner_membership_id),
        };
        if replace {
            best = Some(row);
        }
    }
    best.expect("component non-empty").client_uuid.clone()
}

/// True when `candidate` should beat `incumbent` under Owner → earliest ts → uuid.
fn preferred_over(
    candidate: &LiveNeighborRow,
    incumbent: &LiveNeighborRow,
    owner_membership_id: Option<&str>,
) -> bool {
    let cand_owner =
        owner_membership_id.is_some_and(|owner| candidate.author_membership_id == owner);
    let inc_owner =
        owner_membership_id.is_some_and(|owner| incumbent.author_membership_id == owner);
    match (cand_owner, inc_owner) {
        (true, false) => true,
        (false, true) => false,
        _ => match candidate.timestamp.cmp(&incumbent.timestamp) {
            std::cmp::Ordering::Less => true,
            std::cmp::Ordering::Greater => false,
            std::cmp::Ordering::Equal => candidate.client_uuid < incumbent.client_uuid,
        },
    }
}

#[cfg(test)]
mod unit_tests {
    use super::*;

    #[test]
    fn whitelist_contains_bath_not_sleep() {
        assert!(is_neighbor_whitelist_type("bath"));
        assert!(is_neighbor_whitelist_type("nursing"));
        assert!(!is_neighbor_whitelist_type("sleep"));
        assert!(!is_neighbor_whitelist_type("custom"));
    }

    #[test]
    fn window_is_thirty_minutes_in_millis() {
        assert_eq!(NEIGHBOR_WINDOW, 1_800_000);
    }
}
