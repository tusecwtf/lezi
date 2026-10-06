//! Published media metadata and committed-pending bundle media cleanup.

use std::collections::BTreeSet;

use rusqlite::types::Value as SqlValue;
use rusqlite::{params, params_from_iter, Connection, OptionalExtension, TransactionBehavior};
use serde_json::{Map, Value};

use super::bundles::load_open_staging_bundle_for_membership;
use super::{
    parse_payload, CommittedPendingBundleMedia, MediaMetadata, Principal, Store, StoreError,
    ENTITY_QUERY_CHUNK_SIZE,
};

pub(crate) fn media_association_owner(
    payload: &Map<String, Value>,
) -> Result<Option<(&'static str, &str)>, StoreError> {
    let kind = payload
        .get("kind")
        .and_then(Value::as_str)
        .ok_or(StoreError::InvalidStoredPayload)?;
    Ok(match kind {
        "avatar" => payload
            .get("baby_client_uuid")
            .and_then(Value::as_str)
            .map(|id| ("baby", id)),
        "log" => payload
            .get("record_client_uuid")
            .and_then(Value::as_str)
            .map(|id| ("record", id))
            .or_else(|| {
                payload
                    .get("care_plan_client_uuid")
                    .and_then(Value::as_str)
                    .map(|id| ("care_plan", id))
            }),
        "wake" => payload
            .get("record_client_uuid")
            .and_then(Value::as_str)
            .map(|id| ("wake_observation", id)),
        _ => None,
    })
}

fn committed_pending_bundle_media_query(
    connection: &Connection,
    bundle: Option<(&str, &str)>,
) -> Result<Vec<CommittedPendingBundleMedia>, StoreError> {
    let (sql, parameters): (&str, Vec<SqlValue>) = match bundle {
        Some((family_id, bundle_id)) => (
            "
            SELECT publication.family_id, publication.bundle_id, publication.media_uuid
            FROM media_publications AS publication
            JOIN sync_bundles AS bundle
              ON bundle.family_id = publication.family_id
             AND bundle.bundle_id = publication.bundle_id
            WHERE publication.source = 'bundle_pending'
              AND bundle.status = 'committed'
              AND publication.family_id = ?1
              AND publication.bundle_id = ?2
            ORDER BY publication.media_uuid
            ",
            vec![
                SqlValue::Text(family_id.to_owned()),
                SqlValue::Text(bundle_id.to_owned()),
            ],
        ),
        None => (
            "
            SELECT publication.family_id, publication.bundle_id, publication.media_uuid
            FROM media_publications AS publication
            JOIN sync_bundles AS bundle
              ON bundle.family_id = publication.family_id
             AND bundle.bundle_id = publication.bundle_id
            WHERE publication.source = 'bundle_pending'
              AND bundle.status = 'committed'
            ORDER BY publication.family_id, publication.bundle_id, publication.media_uuid
            ",
            Vec::new(),
        ),
    };
    let mut statement = connection.prepare(sql)?;
    let rows = statement
        .query_map(params_from_iter(parameters), |row| {
            Ok(CommittedPendingBundleMedia {
                family_id: row.get(0)?,
                bundle_id: row.get(1)?,
                media_uuid: row.get(2)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()
        .map_err(StoreError::from)?;
    Ok(rows)
}

impl Store {
    /// get_media reads metadata and publication back-to-back under one held
    /// family lock; one connection halves the per-request setup cost (open,
    /// chmod, pragma batch) without changing the observed statements.
    pub fn media_metadata_if_published(
        &self,
        family_id: &str,
        client_uuid: &str,
    ) -> Result<MediaMetadataIfPublished, StoreError> {
        self.with_connection(|connection| {
            let Some(metadata) = media_metadata_on(connection, family_id, client_uuid)? else {
                return Ok(MediaMetadataIfPublished::Missing);
            };
            if !is_media_published_on(connection, family_id, client_uuid)? {
                return Ok(MediaMetadataIfPublished::Unpublished);
            }
            Ok(MediaMetadataIfPublished::Metadata(metadata))
        })
    }

    /// Test-only probe; production reads ride [Self::media_metadata_if_published].
    #[cfg(test)]
    pub fn is_media_published(
        &self,
        family_id: &str,
        client_uuid: &str,
    ) -> Result<bool, StoreError> {
        self.with_connection(|connection| is_media_published_on(connection, family_id, client_uuid))
    }

    pub(crate) fn published_media_on(
        &self,
        connection: &Connection,
        family_id: &str,
        client_uuids: &BTreeSet<String>,
    ) -> Result<BTreeSet<String>, StoreError> {
        if client_uuids.is_empty() {
            return Ok(BTreeSet::new());
        }
        let ids = client_uuids.iter().map(String::as_str).collect::<Vec<_>>();
        let mut published = BTreeSet::new();
        for chunk in ids.chunks(ENTITY_QUERY_CHUNK_SIZE) {
            let placeholders = std::iter::repeat_n("?", chunk.len())
                .collect::<Vec<_>>()
                .join(", ");
            let sql = format!(
                "
            SELECT media_uuid FROM media_publications
            WHERE family_id = ?
              AND source != 'bundle_pending'
              AND media_uuid IN ({placeholders})
            "
            );
            let mut parameters = Vec::with_capacity(chunk.len() + 1);
            parameters.push(SqlValue::Text(family_id.to_owned()));
            parameters.extend(
                chunk
                    .iter()
                    .map(|client_uuid| SqlValue::Text((*client_uuid).to_owned())),
            );
            let mut statement = connection.prepare(&sql)?;
            for media_uuid in
                statement.query_map(params_from_iter(parameters), |row| row.get::<_, String>(0))?
            {
                published.insert(media_uuid?);
            }
        }
        Ok(published)
    }

    /// Persist quarantine ownership after the final-path files have been
    /// fsynced, but before bundle validation and publication enter SQLite. A
    /// failed commit therefore leaves durable bytes explicitly unservable
    /// across restart; a successful commit promotes these rows in its publish
    /// transaction. One connection serves the whole manifest while each entry
    /// keeps its own IMMEDIATE transaction (identical per-item crash windows
    /// to marking them one by one); callers hold the family lock, so per-item
    /// outcomes are deterministic across the batch.
    pub fn mark_bundle_media_prepared(
        &self,
        principal: &Principal,
        bundle_id: &str,
        media_uuids: &[String],
    ) -> Result<(), StoreError> {
        let family_id = &principal.family_id;
        let mut connection = self.connect()?;
        for media_uuid in media_uuids {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            let Some(_row) = load_open_staging_bundle_for_membership(
                &transaction,
                family_id,
                bundle_id,
                &principal.membership_id,
            )?
            else {
                return Ok(());
            };
            let declared = transaction
                .query_row(
                    "
            SELECT 1 FROM sync_bundle_media
            WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
            ",
                    params![family_id, bundle_id, media_uuid],
                    |_| Ok(()),
                )
                .optional()?
                .is_some();
            if !declared {
                return Err(StoreError::BundleMediaNotInManifest);
            }
            // Preserve an already committed ordinary/bundle owner. Reusing identical
            // bytes in a rejected bundle must not hide a previously valid upload.
            transaction.execute(
                "
        INSERT OR IGNORE INTO media_publications(
            family_id, media_uuid, source, bundle_id
        ) VALUES (?1, ?2, 'bundle_pending', ?3)
        ",
                params![family_id, media_uuid, bundle_id],
            )?;
            transaction.commit()?;
        }
        self.secure_database_files()?;
        Ok(())
    }

    pub fn committed_pending_bundle_media_for_bundle(
        &self,
        family_id: &str,
        bundle_id: &str,
    ) -> Result<Vec<CommittedPendingBundleMedia>, StoreError> {
        let connection = self.connect()?;
        committed_pending_bundle_media_query(&connection, Some((family_id, bundle_id)))
    }

    pub fn committed_pending_bundle_media(
        &self,
    ) -> Result<Vec<CommittedPendingBundleMedia>, StoreError> {
        let connection = self.connect()?;
        committed_pending_bundle_media_query(&connection, None)
    }

    /// Forget cleanup evidence only after the caller has removed and fsynced
    /// the exact final-path file. Repeating this operation is harmless.
    pub fn finalize_committed_pending_bundle_media(
        &self,
        pending: &CommittedPendingBundleMedia,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let removed = transaction.execute(
            "
        DELETE FROM media_publications
        WHERE family_id = ?1
          AND media_uuid = ?2
          AND source = 'bundle_pending'
          AND bundle_id = ?3
          AND EXISTS (
              SELECT 1 FROM sync_bundles
              WHERE family_id = ?1
                AND bundle_id = ?3
                AND status = 'committed'
          )
        ",
            params![pending.family_id, pending.media_uuid, pending.bundle_id],
        )?;
        if removed > 0 {
            transaction.execute(
                "
            DELETE FROM sync_bundle_media
            WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
            ",
                params![pending.family_id, pending.bundle_id, pending.media_uuid],
            )?;
        }
        transaction.commit()?;
        self.secure_database_files()?;
        Ok(())
    }
}

pub(crate) enum MediaMetadataIfPublished {
    Metadata(MediaMetadata),
    /// Live metadata row exists but its bytes are not published.
    Unpublished,
    /// No live metadata row.
    Missing,
}

fn media_metadata_on(
    connection: &Connection,
    family_id: &str,
    client_uuid: &str,
) -> Result<Option<MediaMetadata>, StoreError> {
    let row = connection
        .query_row(
            "
        SELECT payload_json, deleted_at FROM entities
        WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2
        ",
            params![family_id, client_uuid],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, Option<i64>>(1)?)),
        )
        .optional()?;
    match row {
        None | Some((_, Some(_))) => Ok(None),
        Some((payload, None)) => {
            let payload = parse_payload(&payload)?;
            let kind = payload
                .get("kind")
                .and_then(Value::as_str)
                .ok_or(StoreError::InvalidStoredPayload)?
                .to_owned();
            let byte_size = payload
                .get("byte_size")
                .map(|value| {
                    value
                        .as_u64()
                        .and_then(|size| usize::try_from(size).ok())
                        .ok_or(StoreError::InvalidStoredPayload)
                })
                .transpose()?;
            Ok(Some(MediaMetadata { kind, byte_size }))
        }
    }
}

fn is_media_published_on(
    connection: &Connection,
    family_id: &str,
    client_uuid: &str,
) -> Result<bool, StoreError> {
    Ok(connection
        .query_row(
            "
        SELECT 1 FROM media_publications
        WHERE family_id = ?1 AND media_uuid = ?2
          AND source != 'bundle_pending'
        ",
            params![family_id, client_uuid],
            |_| Ok(()),
        )
        .optional()?
        .is_some())
}
