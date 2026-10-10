//! Read-only, same-snapshot source-group closure (wire §12.4). Static mutation
//! receipts and the ordinary pull cursor deliberately do not participate.
use std::collections::{BTreeMap, BTreeSet};

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use serde_json::{json, Value};
use uuid::Uuid;

use super::{Store, StoreError};

#[derive(Debug)]
pub(crate) enum CurrentSourceRelationsError {
    Store(StoreError),
    InvalidProjection,
    LimitExceeded,
}
impl From<StoreError> for CurrentSourceRelationsError {
    fn from(error: StoreError) -> Self {
        Self::Store(error)
    }
}
impl From<rusqlite::Error> for CurrentSourceRelationsError {
    fn from(error: rusqlite::Error) -> Self {
        Self::Store(error.into())
    }
}
fn canonical_uuid(value: &str) -> bool {
    Uuid::parse_str(value).is_ok_and(|uuid| uuid.hyphenated().to_string() == value)
}

impl Store {
    pub(crate) fn current_source_relations(
        &self,
        family_id: &str,
        generation: &str,
        requested: &BTreeSet<String>,
    ) -> Result<Value, CurrentSourceRelationsError> {
        use CurrentSourceRelationsError::{InvalidProjection, LimitExceeded};
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Deferred)?;
        let head_rev: i64 = tx
            .query_row(
                "SELECT rev FROM family_meta WHERE family_id = ?1",
                [family_id],
                |row| row.get(0),
            )
            .optional()?
            .ok_or(InvalidProjection)?;
        if head_rev < 0 {
            return Err(InvalidProjection);
        }
        let mut relation_ids = BTreeSet::new();
        let mut record_relations = BTreeMap::<String, Option<String>>::new();
        for uuid in requested {
            let relation_id: Option<String> = tx.query_row(
                "SELECT relation_id FROM source_relation_members WHERE family_id = ?1 AND record_client_uuid = ?2",
                params![family_id, uuid], |row| row.get(0),
            ).optional()?;
            if let Some(relation_id) = &relation_id {
                relation_ids.insert(relation_id.clone());
            }
            record_relations.insert(uuid.clone(), relation_id);
        }
        if relation_ids.len() > 64 {
            return Err(LimitExceeded);
        }
        let mut source_relations = Vec::new();
        for relation_id in relation_ids {
            if !canonical_uuid(&relation_id) {
                return Err(InvalidProjection);
            }
            let header: Option<(String, i64, String)> = tx.query_row(
                "SELECT display_client_uuid, media_retained, mutation_id FROM source_relations WHERE family_id = ?1 AND relation_id = ?2",
                params![family_id, relation_id], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            ).optional()?;
            let Some((display, media_retained, mutation_id)) = header else {
                return Err(InvalidProjection);
            };
            if !canonical_uuid(&display) || media_retained != 1 {
                return Err(InvalidProjection);
            }
            let members = {
                let mut statement = tx.prepare("SELECT record_client_uuid, role FROM source_relation_members WHERE family_id = ?1 AND relation_id = ?2 ORDER BY record_client_uuid LIMIT 65")?;
                let rows = statement.query_map(params![family_id, relation_id], |row| {
                    Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
                })?;
                rows.collect::<Result<Vec<_>, _>>()?
            };
            if members.len() > 64 {
                return Err(LimitExceeded);
            }
            if members.len() < 2 {
                return Err(InvalidProjection);
            }
            let mut display_count = 0;
            let mut sources = Vec::new();
            for (uuid, role) in members {
                if !canonical_uuid(&uuid) {
                    return Err(InvalidProjection);
                }
                match role.as_str() {
                    "display" if uuid == display => display_count += 1,
                    "source" if uuid != display => sources.push(uuid.clone()),
                    _ => return Err(InvalidProjection),
                }
                if let Some(Some(previous)) =
                    record_relations.insert(uuid, Some(relation_id.clone()))
                {
                    if previous != relation_id {
                        return Err(InvalidProjection);
                    }
                }
            }
            if display_count != 1 {
                return Err(InvalidProjection);
            }
            source_relations.push(json!({
                "relation_id": relation_id, "display_client_uuid": display,
                "source_client_uuids": sources, "media_retained": true,
                "auto_aligned": mutation_id.starts_with("auto-near-neighbor:"),
            }));
        }
        if record_relations.len() > 4096 {
            return Err(LimitExceeded);
        }
        let mut records = Vec::with_capacity(record_relations.len());
        for (uuid, relation_id) in record_relations {
            let deleted_at: Option<Option<i64>> = tx.query_row(
                "SELECT deleted_at FROM entities WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2",
                params![family_id, uuid], |row| row.get(0),
            ).optional()?;
            let record_state = match deleted_at {
                Some(None) => "live",
                Some(Some(_)) => "deleted",
                None if relation_id.is_some() => return Err(InvalidProjection),
                None => "missing",
            };
            records.push(json!({"record_client_uuid": uuid, "record_state": record_state, "relation_id": relation_id}));
        }
        let response = json!({
            "protocol_version": 1, "family_id": family_id, "generation": generation,
            "head_rev": head_rev, "requested_record_client_uuids": requested,
            "records": records, "source_relations": source_relations,
        });
        if serde_json::to_vec(&response)
            .map_err(|_| InvalidProjection)?
            .len()
            > 2 * 1024 * 1024
        {
            return Err(LimitExceeded);
        }
        tx.commit()?;
        Ok(response)
    }
}
