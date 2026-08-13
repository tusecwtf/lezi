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
            "fulfillment_candidate" => 2,
            "media" => 3,
            _ => 4,
        });
        let mut cursor = 0_i64;
        for entity in &entities {
            cursor += 1;
            transaction.execute(
                "INSERT INTO entities(
                     family_id, entity_type, client_uuid, updated_at,
                     deleted_at, payload_json, rev
                 ) VALUES (?1, ?2, ?3, ?4, NULL, ?5, ?6)",
                params![
                    identity.family_id,
                    entity.entity_type,
                    entity.client_uuid,
                    entity.updated_at,
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
        transaction.execute(
            "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
            params![cursor, identity.family_id],
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
        })
    }
}

fn reauthor_history(entity: &mut Entity, owner_membership_id: &str) {
    if matches!(
        entity.entity_type.as_str(),
        "record" | "care_plan" | "custom_item"
    ) {
        entity.payload.insert(
            "created_by_membership_id".to_owned(),
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
