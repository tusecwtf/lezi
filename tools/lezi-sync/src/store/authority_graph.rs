//! Read-only semantic preflight for the current family authority graph.

use std::collections::HashMap;

use rusqlite::{params, OptionalExtension};
use serde_json::{Map, Value};
use uuid::Uuid;

use crate::model::{EntityValidationContext, RawEntity};

use super::{parse_payload, AuthorityGraphValidationSummary, Store, StoreError};

#[derive(Clone)]
struct AuthorityEntity {
    family_id: String,
    entity_type: String,
    client_uuid: String,
    deleted_at: Option<i64>,
    payload: Map<String, Value>,
}

impl AuthorityEntity {
    fn key(&self) -> (String, String, String) {
        (
            self.family_id.clone(),
            self.entity_type.clone(),
            self.client_uuid.clone(),
        )
    }

    fn invalid(&self, reason_code: &'static str) -> StoreError {
        StoreError::AuthorityGraphInvalid {
            reason_code,
            entity_type: self.entity_type.clone(),
            client_uuid: self.client_uuid.clone(),
        }
    }
}

fn same_family_key(
    entity: &AuthorityEntity,
    entity_type: &str,
    id: &str,
) -> (String, String, String) {
    (
        entity.family_id.clone(),
        entity_type.to_owned(),
        id.to_owned(),
    )
}

fn required_string<'a>(entity: &'a AuthorityEntity, field: &str) -> Result<&'a str, StoreError> {
    entity
        .payload
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| entity.invalid("invalid_payload"))
}

fn optional_string<'a>(entity: &'a AuthorityEntity, field: &str) -> Option<&'a str> {
    entity
        .payload
        .get(field)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
}

fn live_entity<'a>(
    entities: &'a HashMap<(String, String, String), AuthorityEntity>,
    key: &(String, String, String),
) -> Option<&'a AuthorityEntity> {
    entities
        .get(key)
        .filter(|entity| entity.deleted_at.is_none())
}

fn committed_deferred_evidence_exists(
    connection: &rusqlite::Connection,
    plan: &AuthorityEntity,
    record_id: &str,
) -> Result<bool, StoreError> {
    let rows = {
        let mut statement = connection.prepare(
            "
            SELECT root_payload_json, staged_membership_id
            FROM sync_bundles
            WHERE family_id = ?1
              AND status = 'committed'
              AND root_type = 'care_plan'
              AND root_client_uuid = ?2
            ORDER BY committed_at DESC, bundle_id DESC
            ",
        )?;
        let rows = statement
            .query_map(params![plan.family_id, plan.client_uuid], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        rows
    };
    for (raw, staged_membership_id) in rows {
        if staged_membership_id.trim().is_empty() {
            continue;
        }
        let evidence = parse_payload(&raw)?;
        let same_binding = evidence
            .get("fulfilled_record_client_uuid")
            .and_then(Value::as_str)
            == Some(record_id)
            && evidence.get("fulfilled_at").and_then(Value::as_i64)
                == plan.payload.get("fulfilled_at").and_then(Value::as_i64);
        let same_root = evidence.get("baby_client_uuid") == plan.payload.get("baby_client_uuid")
            && evidence.get("type") == plan.payload.get("type")
            && evidence.get("scheduled_at") == plan.payload.get("scheduled_at");
        if same_binding && same_root {
            return Ok(true);
        }
    }
    Ok(false)
}

impl Store {
    pub fn validate_authority_graph<F>(
        &self,
        max_media_bytes: usize,
        mut media_ready: F,
    ) -> Result<AuthorityGraphValidationSummary, StoreError>
    where
        F: FnMut(&str, &str, usize) -> Result<bool, StoreError>,
    {
        let connection = self.connect()?;
        let family_count = connection.query_row("SELECT COUNT(*) FROM families", [], |row| {
            row.get::<_, usize>(0)
        })?;
        let family_ids = self.family_ids()?;
        if let Some(invalid_family_id) = family_ids.iter().find(|family_id| {
            family_id.is_empty()
                || family_id.len() > 128
                || !family_id.chars().all(|character| {
                    character.is_ascii_alphanumeric() || matches!(character, '-' | '_')
                })
        }) {
            return Err(StoreError::AuthorityGraphInvalid {
                reason_code: "invalid_family_id",
                entity_type: "family".to_owned(),
                client_uuid: invalid_family_id.clone(),
            });
        }
        let rows = {
            let mut statement = connection.prepare(
                "
                SELECT family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json
                FROM entities
                ORDER BY family_id, rev, entity_type, client_uuid
                ",
            )?;
            let rows = statement
                .query_map([], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, Option<i64>>(4)?,
                        row.get::<_, String>(5)?,
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            rows
        };
        let entity_count = rows.len();
        let mut entities = HashMap::with_capacity(entity_count);
        for (family_id, entity_type, client_uuid, updated_at, deleted_at, raw_payload) in rows {
            let payload = parse_payload(&raw_payload)?;
            let context = if entity_type == "media" {
                EntityValidationContext::AtomicBundleMedia
            } else {
                EntityValidationContext::AtomicBundleRoot
            };
            let parsed_uuid =
                Uuid::parse_str(&client_uuid).map_err(|_| StoreError::AuthorityGraphInvalid {
                    reason_code: "invalid_entity_uuid",
                    entity_type: entity_type.clone(),
                    client_uuid: client_uuid.clone(),
                })?;
            RawEntity {
                entity_type: entity_type.clone(),
                client_uuid: parsed_uuid,
                updated_at,
                deleted_at,
                payload: payload.clone(),
            }
            .validate_as(max_media_bytes, context)
            .map_err(|_| StoreError::AuthorityGraphInvalid {
                reason_code: "invalid_payload",
                entity_type: entity_type.clone(),
                client_uuid: client_uuid.clone(),
            })?;
            let entity = AuthorityEntity {
                family_id,
                entity_type,
                client_uuid,
                deleted_at,
                payload,
            };
            entities.insert(entity.key(), entity);
        }

        let mut deferred_fulfillment_count = 0usize;
        for entity in entities.values() {
            match entity.entity_type.as_str() {
                "baby" if entity.deleted_at.is_none() => {
                    if let Some(avatar_id) = optional_string(entity, "avatar_media_uuid") {
                        let media =
                            live_entity(&entities, &same_family_key(entity, "media", avatar_id))
                                .ok_or_else(|| entity.invalid("missing_avatar_media"))?;
                        if media.payload.get("kind").and_then(Value::as_str) != Some("avatar")
                            || optional_string(media, "baby_client_uuid")
                                != Some(entity.client_uuid.as_str())
                        {
                            return Err(entity.invalid("invalid_avatar_ownership"));
                        }
                    }
                }
                "record" | "care_plan" => {
                    let baby_id = required_string(entity, "baby_client_uuid")?;
                    if !entities.contains_key(&same_family_key(entity, "baby", baby_id)) {
                        return Err(entity.invalid("missing_baby_reference"));
                    }
                    if let Some(custom_item_id) = optional_string(entity, "custom_item_client_uuid")
                    {
                        if !entities.contains_key(&same_family_key(
                            entity,
                            "custom_item",
                            custom_item_id,
                        )) {
                            return Err(entity.invalid("missing_custom_item_reference"));
                        }
                    }
                    if entity.entity_type == "care_plan"
                        && entity.deleted_at.is_none()
                        && entity.payload.get("status").and_then(Value::as_str) == Some("completed")
                    {
                        let record_id = required_string(entity, "fulfilled_record_client_uuid")?;
                        let record_key = same_family_key(entity, "record", record_id);
                        match entities.get(&record_key) {
                            Some(record) if record.deleted_at.is_none() => {
                                if required_string(record, "baby_client_uuid")? != baby_id {
                                    return Err(entity.invalid("fulfillment_baby_mismatch"));
                                }
                            }
                            Some(_) => {
                                return Err(entity.invalid("fulfilled_record_deleted"));
                            }
                            None => {
                                if !committed_deferred_evidence_exists(
                                    &connection,
                                    entity,
                                    record_id,
                                )? {
                                    return Err(entity.invalid("missing_deferred_evidence"));
                                }
                                if deferred_fulfillment_count < 32 {
                                    tracing::warn!(
                                        family_id = %entity.family_id,
                                        entity_type = "care_plan",
                                        client_uuid = %entity.client_uuid,
                                        record_client_uuid = %record_id,
                                        reason_code = "legacy_missing_fulfilled_record",
                                        "deferred legacy fulfillment retained outside the public graph"
                                    );
                                }
                                deferred_fulfillment_count += 1;
                            }
                        }
                    }
                }
                "media" if entity.deleted_at.is_none() => {
                    let kind = required_string(entity, "kind")?;
                    let (parent_type, parent_field) = match kind {
                        "avatar" => ("baby", "baby_client_uuid"),
                        "log" if optional_string(entity, "care_plan_client_uuid").is_some() => {
                            ("care_plan", "care_plan_client_uuid")
                        }
                        "log" => ("record", "record_client_uuid"),
                        _ => return Err(entity.invalid("invalid_media_owner")),
                    };
                    let parent_id = required_string(entity, parent_field)?;
                    if !entities.contains_key(&same_family_key(entity, parent_type, parent_id)) {
                        return Err(entity.invalid("missing_media_owner"));
                    }
                    let publication = connection
                        .query_row(
                            "SELECT 1 FROM media_publications WHERE family_id = ?1 AND media_uuid = ?2",
                            params![entity.family_id, entity.client_uuid],
                            |_| Ok(()),
                        )
                        .optional()?;
                    if publication.is_none() {
                        return Err(entity.invalid("missing_media_publication"));
                    }
                    let byte_size = entity
                        .payload
                        .get("byte_size")
                        .and_then(Value::as_u64)
                        .and_then(|value| usize::try_from(value).ok())
                        .ok_or_else(|| entity.invalid("invalid_media_size"))?;
                    if !media_ready(&entity.family_id, &entity.client_uuid, byte_size)? {
                        return Err(entity.invalid("media_bytes_not_ready"));
                    }
                }
                "fulfillment_candidate" => {
                    let submitter = entity
                        .payload
                        .get("submitter_membership_id")
                        .ok_or_else(|| entity.invalid("missing_candidate_server_evidence"))?;
                    let submitter_role = required_string(entity, "submitter_role")?;
                    if !matches!(submitter_role, "owner" | "member")
                        || entity
                            .payload
                            .get("confirmed_at")
                            .and_then(Value::as_i64)
                            .filter(|value| *value > 0)
                            .is_none()
                    {
                        return Err(entity.invalid("invalid_candidate_server_evidence"));
                    }
                    if let Some(membership_id) = submitter.as_str() {
                        let membership_exists = connection.query_row(
                            "SELECT EXISTS(SELECT 1 FROM memberships WHERE family_id = ?1 AND id = ?2)",
                            params![entity.family_id, membership_id],
                            |row| row.get::<_, bool>(0),
                        )?;
                        if !membership_exists {
                            return Err(entity.invalid("unknown_candidate_submitter"));
                        }
                    } else if !submitter.is_null() {
                        return Err(entity.invalid("invalid_candidate_server_evidence"));
                    }
                    if entity.deleted_at.is_some() {
                        continue;
                    }
                    let plan_id = required_string(entity, "care_plan_client_uuid")?;
                    let record_id = required_string(entity, "record_client_uuid")?;
                    let plan =
                        live_entity(&entities, &same_family_key(entity, "care_plan", plan_id))
                            .ok_or_else(|| entity.invalid("missing_candidate_plan"))?;
                    let record =
                        live_entity(&entities, &same_family_key(entity, "record", record_id))
                            .ok_or_else(|| entity.invalid("missing_candidate_record"))?;
                    if required_string(plan, "baby_client_uuid")?
                        != required_string(record, "baby_client_uuid")?
                    {
                        return Err(entity.invalid("candidate_baby_mismatch"));
                    }
                }
                _ => {}
            }
        }
        Ok(AuthorityGraphValidationSummary {
            family_count,
            entity_count,
            deferred_fulfillment_count,
        })
    }
}
