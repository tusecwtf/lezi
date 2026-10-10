//! Empty-server disaster restore activation.
//!
//! Files are validated and installed before this façade call. This module owns the one SQLite
//! transaction that makes identity and the complete entity set visible together. It deliberately
//! adds no tables and therefore keeps the current schema/user_version unchanged.

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use serde_json::Value;

use crate::model::Entity;

use super::{CreatedDeviceSession, DisasterRestoreIdentityInput, Store, StoreError};

impl Store {
    pub fn activate_disaster_restore(
        &self,
        identity: DisasterRestoreIdentityInput<'_>,
        mut entities: Vec<Entity>,
        authority: super::RestoreAuthorityInput<'_>,
    ) -> Result<CreatedDeviceSession, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        if transaction
            .query_row("SELECT 1 FROM families LIMIT 1", [], |_| Ok(()))
            .optional()?
            .is_some()
        {
            return Err(StoreError::FamilyAlreadyExists);
        }

        transaction.execute(
            "INSERT INTO families(id, created_at, create_request_hash, name, owner_root_fingerprint)
             VALUES (?1, ?2, NULL, ?3, ?4)",
            params![
                identity.family_id,
                identity.now,
                identity.family_name,
                identity.owner_root_fingerprint,
            ],
        )?;
        transaction.execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, 0)",
            params![identity.family_id],
        )?;
        transaction.execute(
            "INSERT INTO memberships(
                 membership_id, family_id, role, display_name, display_name_key
             ) VALUES (?1, ?2, 'owner', ?3, ?4)",
            params![
                identity.owner_membership_id,
                identity.family_id,
                identity.owner_display_name,
                identity.owner_display_name_key,
            ],
        )?;
        transaction.execute(
            "INSERT INTO devices(
                 device_id, membership_id, device_name, device_name_key,
                 status, created_at, last_used_at
             ) VALUES (?1, ?2, ?3, ?4, 'active', ?5, ?5)",
            params![
                identity.device_id,
                identity.owner_membership_id,
                identity.device_name,
                crate::model::normalized_device_name_key(identity.device_name),
                identity.now,
            ],
        )?;
        transaction.execute(
            "INSERT INTO device_sessions(
                 session_id, device_id, access_token_hash, access_expires_at, refresh_token_hash
             ) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                identity.session_id,
                identity.device_id,
                crate::hash_secret(identity.access_token),
                identity.access_expires_at,
                crate::hash_secret(identity.refresh_token),
            ],
        )?;

        for entity in &mut entities {
            reauthor_history(entity, identity.owner_membership_id);
        }
        entities.sort_by_key(|entity| match entity.entity_type.as_str() {
            "baby" | "custom_item" => 0,
            "record" | "care_plan" => 1,
            // Wake names a sleep record and owns kind=wake media.
            "wake_observation" => 2,
            "fulfillment_candidate" => 3,
            "media" => 4,
            _ => 5,
        });
        let mut cursor = 0_i64;
        for entity in &entities {
            cursor += 1;
            transaction.execute(
                "INSERT INTO entities(
                     family_id, entity_type, client_uuid, updated_at,
                     deleted_at, payload_json, rev
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![
                    identity.family_id,
                    entity.entity_type,
                    entity.client_uuid,
                    entity.updated_at,
                    entity.deleted_at,
                    serde_json::to_string(&entity.payload)?,
                    cursor,
                ],
            )?;
            if entity.entity_type == "media" {
                transaction.execute(
                    "INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
                     VALUES (?1, ?2, 'ordinary', NULL)",
                    params![identity.family_id, entity.client_uuid],
                )?;
            }
        }
        super::restore_authority::establish(&transaction, &identity, &authority, &entities)?;
        transaction.execute(
            "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
            params![cursor, identity.family_id],
        )?;
        // Absolute (non-increment) rev write: invalidate the cached census
        // unconditionally so a watermark that reuses a previous rev value can
        // never be served from a stale entry (0.5 design §1.1, review A4/B7).
        self.live_census_cache.invalidate(identity.family_id);
        super::authority_graph::validate_authority_graph_on(
            &transaction,
            &self.database_path,
            usize::MAX,
            |family, uuid, size| {
                let path =
                    super::causal_media_staging::published_path(&self.database_path, family, uuid);
                let expected = authority
                    .media_sha256
                    .get(uuid)
                    .ok_or(StoreError::InvalidStoredPayload)?;
                Ok(
                    super::causal_media_staging::published_file_sha256(&path, size as u64)?
                        .as_ref()
                        == Some(expected),
                )
            },
        )?;
        transaction.commit()?;
        self.secure_database_files()?;
        Ok(CreatedDeviceSession {
            family_id: identity.family_id.to_owned(),
            membership_id: identity.owner_membership_id.to_owned(),
            device_id: identity.device_id.to_owned(),
            session_id: identity.session_id.to_owned(),
            access_token: identity.access_token.to_owned(),
            access_expires_at: identity.access_expires_at,
            refresh_token: identity.refresh_token.to_owned(),
            family_name: Some(identity.family_name.to_owned()),
            role: "owner".to_owned(),
        })
    }
}

fn reauthor_history(entity: &mut Entity, owner_membership_id: &str) {
    if matches!(
        entity.entity_type.as_str(),
        "baby" | "record" | "care_plan" | "custom_item"
    ) {
        entity.payload.insert(
            "created_by_membership_id".to_owned(),
            Value::String(owner_membership_id.to_owned()),
        );
    }
    // Same server stamp as causal commit: the restoring owner is the observer.
    if entity.entity_type == "wake_observation" {
        entity.payload.insert(
            "observer_membership_id".to_owned(),
            Value::String(owner_membership_id.to_owned()),
        );
    }
    if entity.entity_type == "fulfillment_candidate" {
        entity.payload.insert(
            "submitter_membership_id".to_owned(),
            Value::String(owner_membership_id.to_owned()),
        );
        entity.payload.insert(
            "submitter_role".to_owned(),
            Value::String("owner".to_owned()),
        );
    }
}

impl Store {
    /// Identity-chain equality proves activation without reviving expired/revoked credentials.
    pub fn has_restore_identity(
        &self,
        family: &str,
        membership: &str,
        device: &str,
        session: &str,
    ) -> Result<bool, StoreError> {
        Ok(self.connect()?.query_row(
            "SELECT EXISTS(SELECT 1 FROM families f JOIN memberships m ON m.family_id=f.id JOIN devices d ON d.membership_id=m.membership_id JOIN device_sessions s ON s.device_id=d.device_id WHERE f.id=?1 AND m.membership_id=?2 AND d.device_id=?3 AND s.session_id=?4)",
            params![family,membership,device,session], |row| row.get(0))?)
    }

    pub fn has_restore_authority_coverage(
        &self,
        family: &str,
        batch: &str,
        request: Option<&str>,
        hash: Option<&str>,
    ) -> Result<bool, StoreError> {
        let receipt: Option<(String,String)> = self.connect()?.query_row(
            "SELECT content_hash,receipt_json FROM mutation_receipts WHERE family_id=?1 AND membership_id=?2 AND entity_type='baby' AND client_uuid=?3 AND mutation_id=?3",
            params![family,super::restore_authority::COVERAGE_PRINCIPAL,batch], |row| Ok((row.get(0)?,row.get(1)?))).optional()?;
        let Some((stored_hash, json)) = receipt else {
            return Ok(false);
        };
        let value: Value = serde_json::from_str(&json)?;
        let keys = [
            "protocol_version",
            "batch_id",
            "manifest_request_id",
            "manifest_sha256",
            "source_relations_sha256",
            "relation_count",
            "member_count",
        ];
        if value.as_object().is_none_or(|object| {
            object.len() != keys.len() || keys.iter().any(|key| !object.contains_key(*key))
        }) {
            return Ok(false);
        }
        let Some(relations) = value.get("relation_count").and_then(Value::as_u64) else {
            return Ok(false);
        };
        let Some(members) = value.get("member_count").and_then(Value::as_u64) else {
            return Ok(false);
        };
        if members < relations.saturating_mul(2)
            || members > relations.saturating_mul(64)
            || value
                .get("source_relations_sha256")
                .and_then(Value::as_str)
                .is_none_or(|hash| {
                    hash.len() != 64
                        || !hash
                            .bytes()
                            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
                })
        {
            return Ok(false);
        }
        Ok(
            value.get("protocol_version").and_then(Value::as_u64) == Some(1)
                && value.get("batch_id").and_then(Value::as_str) == Some(batch)
                && value.get("manifest_sha256").and_then(Value::as_str)
                    == Some(stored_hash.as_str())
                && request.is_none_or(|request| {
                    value.get("manifest_request_id").and_then(Value::as_str) == Some(request)
                })
                && hash.is_none_or(|hash| stored_hash == hash),
        )
    }
}

impl Store {
    pub fn has_complete_restore_baselines(&self, family: &str) -> Result<bool, StoreError> {
        Ok(!self.connect()?.query_row("SELECT EXISTS(SELECT 1 FROM entities e LEFT JOIN entity_stable_heads h ON h.family_id=e.family_id AND h.entity_type=e.entity_type AND h.client_uuid=e.client_uuid WHERE e.family_id=?1 AND e.entity_type IN ('baby','record','care_plan','custom_item','wake_observation') AND h.version_id IS NULL)",params![family],|row|row.get::<_,bool>(0))?)
    }
}
