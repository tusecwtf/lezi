//! Authenticated authoritative head reconciliation.

use std::collections::{BTreeMap, BTreeSet};

use crate::model::{validate_bundle_media_for_root, Entity, MAX_BUNDLE_MEDIA_ENTITIES};
use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};

use super::bundles::{bundle_content_hash, validate_reconcile_package};
use super::{
    parse_payload, Principal, ReconcileBatch, ReconcileDisposition, ReconcileResult, ReconcileUnit,
    Store, StoreError,
};

pub const MAX_RECONCILE_UNITS: usize = 64;

fn load_entity(
    transaction: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
) -> Result<Option<Entity>, StoreError> {
    transaction
        .query_row(
            "SELECT updated_at, deleted_at, payload_json FROM entities
             WHERE family_id = ?1 AND entity_type = ?2 AND client_uuid = ?3",
            params![family_id, entity_type, client_uuid],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, Option<i64>>(1)?,
                    row.get::<_, String>(2)?,
                ))
            },
        )
        .optional()?
        .map(|(updated_at, deleted_at, payload_json)| {
            Ok(Entity {
                entity_type: entity_type.to_owned(),
                client_uuid: client_uuid.to_owned(),
                updated_at,
                deleted_at,
                payload: parse_payload(&payload_json)?,
            })
        })
        .transpose()
}

fn load_live_media_by_root(
    transaction: &Transaction<'_>,
    family_id: &str,
) -> Result<BTreeMap<(String, String), Vec<Entity>>, StoreError> {
    let mut statement = transaction.prepare(
        "SELECT client_uuid, updated_at, payload_json FROM entities
         WHERE family_id = ?1 AND entity_type = 'media' AND deleted_at IS NULL",
    )?;
    let rows = statement.query_map(params![family_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    let mut by_root = BTreeMap::<(String, String), Vec<Entity>>::new();
    for row in rows {
        let (client_uuid, updated_at, payload_json) = row?;
        let entity = Entity {
            entity_type: "media".to_owned(),
            client_uuid,
            updated_at,
            deleted_at: None,
            payload: parse_payload(&payload_json)?,
        };
        let kind = entity
            .payload
            .get("kind")
            .and_then(serde_json::Value::as_str);
        let key = match kind {
            Some("avatar") => entity
                .payload
                .get("baby_client_uuid")
                .and_then(serde_json::Value::as_str)
                .map(|uuid| ("baby".to_owned(), uuid.to_owned())),
            Some("log") => entity
                .payload
                .get("record_client_uuid")
                .and_then(serde_json::Value::as_str)
                .map(|uuid| ("record".to_owned(), uuid.to_owned()))
                .or_else(|| {
                    entity
                        .payload
                        .get("care_plan_client_uuid")
                        .and_then(serde_json::Value::as_str)
                        .map(|uuid| ("care_plan".to_owned(), uuid.to_owned()))
                }),
            _ => None,
        }
        .ok_or(StoreError::InvalidStoredPayload)?;
        by_root.entry(key).or_default().push(entity);
    }
    for media in by_root.values_mut() {
        media.sort_by(|left, right| left.client_uuid.cmp(&right.client_uuid));
    }
    Ok(by_root)
}

fn load_remote_media(
    transaction: &Transaction<'_>,
    family_id: &str,
    root: &Entity,
    live_media_by_root: &BTreeMap<(String, String), Vec<Entity>>,
) -> Result<Vec<Entity>, StoreError> {
    if matches!(
        root.entity_type.as_str(),
        "custom_item" | "fulfillment_candidate"
    ) {
        return Ok(Vec::new());
    }

    // Bundles are deltas, so start from the one request-scoped live-media scan.
    // This is also the canonical fallback for disaster-restored entity heads.
    let key = (root.entity_type.clone(), root.client_uuid.clone());
    let mut media_by_uuid = BTreeMap::new();
    if root.deleted_at.is_none() {
        for entity in live_media_by_root.get(&key).into_iter().flatten() {
            let points_at_current_avatar = root.entity_type != "baby"
                || root
                    .payload
                    .get("avatar_media_uuid")
                    .and_then(serde_json::Value::as_str)
                    == Some(entity.client_uuid.as_str());
            if points_at_current_avatar {
                media_by_uuid.insert(entity.client_uuid.clone(), entity.clone());
            }
        }
    }

    // Retain only the latest matching bundle's deletion proofs. Historical
    // tombstones are not part of the current head, while the latest proof lets
    // an interrupted delete be recognized as the exact committed package.
    let stored = transaction
        .query_row(
            "SELECT root_updated_at, root_deleted_at, root_payload_json, media_entities_json
             FROM sync_bundles
             WHERE family_id = ?1 AND status = 'committed'
               AND root_type = ?2 AND root_client_uuid = ?3
             ORDER BY committed_cursor DESC, rowid DESC
             LIMIT 1",
            params![family_id, root.entity_type, root.client_uuid],
            |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, Option<i64>>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, String>(3)?,
                ))
            },
        )
        .optional()?;
    if let Some((updated_at, deleted_at, payload_json, media_json)) = stored {
        if updated_at == root.updated_at
            && deleted_at == root.deleted_at
            && parse_payload(&payload_json)? == root.payload
        {
            let latest_delta: Vec<Entity> = serde_json::from_str(&media_json)?;
            for entity in latest_delta
                .into_iter()
                .filter(|entity| entity.deleted_at.is_some())
            {
                if validate_bundle_media_for_root(root, std::slice::from_ref(&entity)).is_ok() {
                    media_by_uuid.insert(entity.client_uuid.clone(), entity);
                }
            }
        }
    }

    let media = media_by_uuid.into_values().collect::<Vec<_>>();
    if media.len() > MAX_BUNDLE_MEDIA_ENTITIES
        || validate_bundle_media_for_root(root, &media).is_err()
    {
        return Err(StoreError::InvalidStoredPayload);
    }
    Ok(media)
}

fn permanent_rejection(error: &StoreError) -> Option<&'static str> {
    match error {
        StoreError::ForbiddenBaby => Some("forbidden_baby"),
        StoreError::ForbiddenAvatar => Some("forbidden_avatar"),
        StoreError::ForbiddenCustomItem => Some("forbidden_custom_item"),
        StoreError::ForbiddenCarePlan => Some("forbidden_care_plan"),
        StoreError::ForbiddenRecord => Some("forbidden_record"),
        StoreError::ForbiddenAnonymousFact => Some("forbidden_anonymous_fact"),
        StoreError::CustomItemTombstoneResurrection
        | StoreError::CarePlanTombstoneResurrection
        | StoreError::FulfillmentCandidateTombstoneResurrection => {
            Some("tombstone_resurrection_forbidden")
        }
        StoreError::ImmutableCarePlanFulfillmentBinding
        | StoreError::ImmutableFulfillmentCandidateEvidence
        | StoreError::ImmutableMediaAssociation => Some("immutable_evidence_conflict"),
        _ => None,
    }
}

impl Store {
    pub fn reconcile_units(
        &self,
        principal: &Principal,
        units: Vec<ReconcileUnit>,
        max_updated_at: i64,
        now: i64,
    ) -> Result<ReconcileBatch, StoreError> {
        if units.is_empty() || units.len() > MAX_RECONCILE_UNITS {
            return Err(StoreError::InvalidReconcileBatch);
        }
        let mut keys = BTreeSet::new();
        for unit in &units {
            if unit.content_hash.is_empty()
                || !keys.insert((unit.root.entity_type.clone(), unit.root.client_uuid.clone()))
                || !matches!(
                    unit.root.entity_type.as_str(),
                    "baby" | "record" | "care_plan" | "custom_item" | "fulfillment_candidate"
                )
                || validate_bundle_media_for_root(&unit.root, &unit.media).is_err()
            {
                return Err(StoreError::InvalidReconcileBatch);
            }
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let cursor = transaction.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![principal.family_id],
            |row| row.get(0),
        )?;
        let live_media_by_root = load_live_media_by_root(&transaction, &principal.family_id)?;
        let mut results = Vec::with_capacity(units.len());
        for unit in units {
            let remote_root = load_entity(
                &transaction,
                &principal.family_id,
                &unit.root.entity_type,
                &unit.root.client_uuid,
            )?;
            let remote_media = match &remote_root {
                Some(root) => load_remote_media(
                    &transaction,
                    &principal.family_id,
                    root,
                    &live_media_by_root,
                )?,
                None => Vec::new(),
            };
            let remote_content_hash = remote_root
                .as_ref()
                .map(|root| bundle_content_hash(root, &remote_media))
                .transpose()?;
            let (disposition, reason) = if let Some(remote) = remote_root.as_ref() {
                let exact = remote == &unit.root && remote_media == unit.media;
                if exact {
                    (ReconcileDisposition::Confirmed, "canonical_equivalent")
                } else if remote.updated_at >= unit.root.updated_at {
                    (ReconcileDisposition::AdoptRemote, "server_lww_winner")
                } else {
                    let mut package = vec![unit.root.clone()];
                    package.extend(unit.media.clone());
                    match validate_reconcile_package(
                        &transaction,
                        principal,
                        package,
                        max_updated_at,
                        now,
                    ) {
                        Ok(()) => (ReconcileDisposition::Publish, "local_lww_winner"),
                        Err(error) if permanent_rejection(&error).is_some() => {
                            (ReconcileDisposition::AdoptRemote, "server_acl_winner")
                        }
                        Err(StoreError::UnresolvedReference(_)) => (
                            ReconcileDisposition::RetryAuthority,
                            "dependency_unresolved",
                        ),
                        Err(error) => return Err(error),
                    }
                }
            } else {
                let mut package = vec![unit.root.clone()];
                package.extend(unit.media.clone());
                match validate_reconcile_package(
                    &transaction,
                    principal,
                    package,
                    max_updated_at,
                    now,
                ) {
                    Ok(()) => (
                        ReconcileDisposition::Publish,
                        if unit.root.deleted_at.is_some() {
                            "authoritative_tombstone"
                        } else {
                            "authoritative_absence"
                        },
                    ),
                    Err(error) if permanent_rejection(&error).is_some() => (
                        ReconcileDisposition::RemoteAbsentRejected,
                        permanent_rejection(&error).unwrap(),
                    ),
                    Err(StoreError::UnresolvedReference(_)) => (
                        ReconcileDisposition::RetryAuthority,
                        "dependency_unresolved",
                    ),
                    Err(error) => return Err(error),
                }
            };
            results.push(ReconcileResult {
                entity_type: unit.root.entity_type.clone(),
                client_uuid: unit.root.client_uuid.clone(),
                request_content_hash: unit.content_hash,
                disposition,
                reason: reason.to_owned(),
                remote_content_hash,
                remote_root,
                remote_media,
            });
        }
        transaction.commit()?;
        Ok(ReconcileBatch { cursor, results })
    }
}
