//! Deterministic accepted baselines and bounded source-coverage proof for a new restore.
use std::collections::{BTreeMap, BTreeSet};

use rusqlite::{params, Transaction};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::{causal, CausalMediaItem, DisasterRestoreIdentityInput, Principal, StoreError};
use crate::model::Entity;

const BASELINE_NAMESPACE: Uuid = Uuid::from_u128(0x300c6a8b5aab51b6bef053a13885188b);
const OPERATION_NAMESPACE: Uuid = Uuid::from_u128(0xb823b4c77ccf5dedb7f7d190cbb05d69);
pub(super) const COVERAGE_PRINCIPAL: &str = "__restore_source_coverage_v1__";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct RestoreSourceRelation {
    pub relation_id: String,
    pub display_client_uuid: String,
    pub source_client_uuids: Vec<String>,
    pub auto_aligned: bool,
}

pub struct RestoreAuthorityInput<'a> {
    pub batch_id: &'a str,
    pub manifest_request_id: &'a str,
    pub manifest_hash: &'a str,
    pub source_relations: &'a [RestoreSourceRelation],
    pub media_sha256: &'a BTreeMap<String, String>,
}

pub fn restore_baseline_version_id(batch_id: &str, entity_type: &str, client_uuid: &str) -> String {
    deterministic_id(BASELINE_NAMESPACE, batch_id, entity_type, client_uuid)
}

fn deterministic_id(
    namespace: Uuid,
    batch_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> String {
    let name = [batch_id, entity_type, client_uuid]
        .map(|v| format!("{}:{v}", v.len()))
        .join("");
    Uuid::new_v5(&namespace, name.as_bytes()).to_string()
}

fn canonical_uuid(value: &str) -> bool {
    Uuid::parse_str(value).is_ok_and(|uuid| uuid.to_string() == value)
}

pub(crate) fn validate_restore_relations(
    relations: &mut [RestoreSourceRelation],
    entities: &[Entity],
) -> Result<(), StoreError> {
    let records = entities
        .iter()
        .filter(|e| e.entity_type == "record")
        .map(|e| e.client_uuid.as_str())
        .collect::<BTreeSet<_>>();
    let mut ids = BTreeSet::new();
    let mut members = BTreeSet::new();
    for relation in relations.iter_mut() {
        if !canonical_uuid(&relation.relation_id)
            || !ids.insert(relation.relation_id.clone())
            || relation.source_client_uuids.is_empty()
            || relation.source_client_uuids.len() > 63
        {
            return Err(StoreError::InvalidStoredPayload);
        }
        relation.source_client_uuids.sort();
        for member in
            std::iter::once(&relation.display_client_uuid).chain(&relation.source_client_uuids)
        {
            if !canonical_uuid(member)
                || !records.contains(member.as_str())
                || !members.insert(member.clone())
            {
                return Err(StoreError::InvalidStoredPayload);
            }
        }
    }
    relations.sort_by(|a, b| a.relation_id.cmp(&b.relation_id));
    Ok(())
}

pub(super) fn establish(
    tx: &Transaction<'_>,
    identity: &DisasterRestoreIdentityInput<'_>,
    authority: &RestoreAuthorityInput<'_>,
    entities: &[Entity],
) -> Result<(), StoreError> {
    if !canonical_uuid(authority.batch_id)
        || authority.manifest_hash.len() != 64
        || !authority
            .manifest_hash
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    let mut relations = authority.source_relations.to_vec();
    validate_restore_relations(&mut relations, entities)?;
    let principal = Principal {
        family_id: identity.family_id.to_owned(),
        role: "owner".to_owned(),
        membership_id: identity.owner_membership_id.to_owned(),
        device_id: identity.device_id.to_owned(),
    };
    let mut media_by_owner: BTreeMap<(String, String), Vec<&Entity>> = BTreeMap::new();
    for item in entities
        .iter()
        .filter(|e| e.entity_type == "media" && e.deleted_at.is_none())
    {
        let (owner, id) = super::media_association_owner(&item.payload)?
            .ok_or(StoreError::InvalidStoredPayload)?;
        media_by_owner
            .entry((owner.to_owned(), id.to_owned()))
            .or_default()
            .push(item);
    }
    for entity in entities.iter().filter(|e| {
        matches!(
            e.entity_type.as_str(),
            "baby" | "record" | "care_plan" | "custom_item" | "wake_observation"
        )
    }) {
        let mut root = entity.payload.clone();
        root.insert("updated_at".to_owned(), json!(entity.updated_at));
        let mut media = Vec::new();
        let role = match entity.entity_type.as_str() {
            "baby" => "avatar",
            "record" => "log",
            "care_plan" => "plan",
            "wake_observation" => "wake",
            _ => "",
        };
        for item in media_by_owner
            .get(&(entity.entity_type.clone(), entity.client_uuid.clone()))
            .into_iter()
            .flatten()
        {
            let canonical = CausalMediaItem {
                media_uuid: item.client_uuid.clone(),
                role: role.to_owned(),
                sha256: authority
                    .media_sha256
                    .get(&item.client_uuid)
                    .ok_or(StoreError::InvalidStoredPayload)?
                    .clone(),
                byte_size: item
                    .payload
                    .get("byte_size")
                    .and_then(Value::as_i64)
                    .ok_or(StoreError::InvalidStoredPayload)?,
                mime: item
                    .payload
                    .get("mime")
                    .and_then(Value::as_str)
                    .ok_or(StoreError::InvalidStoredPayload)?
                    .to_owned(),
                width: item.payload.get("width").and_then(Value::as_i64),
                height: item.payload.get("height").and_then(Value::as_i64),
            };
            canonical
                .validate_for_entity(&entity.entity_type)
                .map_err(|_| StoreError::InvalidStoredPayload)?;
            media.push(canonical);
        }
        media.sort_by(|a, b| a.media_uuid.cmp(&b.media_uuid));
        if media.len()
            > match entity.entity_type.as_str() {
                "baby" => 1,
                "custom_item" => 0,
                _ => 3,
            }
        {
            return Err(StoreError::InvalidStoredPayload);
        }
        let canonical =
            crate::model::validate_causal_root(&entity.entity_type, &entity.client_uuid, &root)
                .map_err(|_| StoreError::InvalidStoredPayload)?;
        if canonical != root {
            return Err(StoreError::InvalidStoredPayload);
        }
        let version = restore_baseline_version_id(
            authority.batch_id,
            &entity.entity_type,
            &entity.client_uuid,
        );
        let operation = deterministic_id(
            OPERATION_NAMESPACE,
            authority.batch_id,
            &entity.entity_type,
            &entity.client_uuid,
        );
        let hash = causal::root_content_hash(&root, &media, entity.deleted_at.is_some());
        causal::insert_version(
            tx,
            identity.family_id,
            &version,
            &entity.entity_type,
            &entity.client_uuid,
            entity.updated_at,
            entity.deleted_at,
            &root,
            &hash,
            Some(&operation),
            "accepted",
            identity.now,
            &[],
            &media,
        )?;
        causal::set_stable_head(
            tx,
            identity.family_id,
            &entity.entity_type,
            &entity.client_uuid,
            &version,
        )?;
        causal::save_version_provenance(
            tx,
            identity.family_id,
            &principal,
            &entity.entity_type,
            &entity.client_uuid,
            &operation,
            "accepted",
            None,
            &version,
            identity.now,
        )?;
        super::source_relations::project_record_eligibility(
            tx,
            identity.family_id,
            &entity.entity_type,
            &entity.client_uuid,
            entity.deleted_at,
            &root,
        )?;
    }
    for relation in &relations {
        let mutation = if relation.auto_aligned {
            format!(
                "auto-near-neighbor:restore:{}:{}",
                authority.batch_id, relation.relation_id
            )
        } else {
            format!("restore:{}:{}", authority.batch_id, relation.relation_id)
        };
        tx.execute("INSERT INTO source_relations(family_id,relation_id,display_client_uuid,media_retained,reason,mutation_id,created_by_membership_id,created_at) VALUES (?1,?2,?3,1,'owner_group_resolve',?4,?5,?6)",
            params![identity.family_id, relation.relation_id, relation.display_client_uuid, mutation, identity.owner_membership_id, identity.now])?;
        for (id, role) in std::iter::once((&relation.display_client_uuid, "display"))
            .chain(relation.source_client_uuids.iter().map(|id| (id, "source")))
        {
            tx.execute("INSERT INTO source_relation_members(family_id,relation_id,record_client_uuid,role) VALUES (?1,?2,?3,?4)",
                params![identity.family_id, relation.relation_id, id, role])?;
        }
    }
    let proof = json!({"protocol_version":1,"batch_id":authority.batch_id,
        "manifest_request_id":authority.manifest_request_id,"manifest_sha256":authority.manifest_hash,
        "source_relations_sha256":hex::encode(Sha256::digest(serde_json::to_vec(&relations)?)),
        "relation_count":relations.len(), "member_count":relations.iter().map(|r|r.source_client_uuids.len()+1).sum::<usize>()});
    tx.execute("INSERT INTO mutation_receipts(family_id,membership_id,entity_type,client_uuid,mutation_id,content_hash,status,stable_version_id,branch_version_id,conflict_id,receipt_json,created_at) VALUES (?1,?2,'baby',?3,?3,?4,'accepted',NULL,NULL,NULL,?5,?6)",
        params![identity.family_id, COVERAGE_PRINCIPAL, authority.batch_id, authority.manifest_hash, proof.to_string(), identity.now])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn deterministic_ids_match_android_contract() {
        let batch = "11111111-1111-4111-8111-111111111111";
        let entity = "22222222-2222-4222-8222-222222222222";
        assert_eq!(
            super::restore_baseline_version_id(batch, "record", entity),
            "e0bec011-0509-5e15-98e1-0f7e7a49ee87"
        );
        assert_eq!(
            super::deterministic_id(super::OPERATION_NAMESPACE, batch, "record", entity),
            "048708c5-d2e5-5e67-bab6-de063addc130"
        );
    }
}
