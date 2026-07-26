use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::fs;
use std::path::PathBuf;
use std::time::Duration;

use rusqlite::types::Value as SqlValue;
use rusqlite::{
    params, params_from_iter, Connection, OptionalExtension, Transaction, TransactionBehavior,
};
use serde::Serialize;
use serde_json::{Map, Value};
use thiserror::Error;
use uuid::Uuid;

use crate::model::Entity;
use crate::{PULL_ENTITY_TARGET_BYTES, PULL_PAGE_ENTITY_LIMIT, PULL_PAGE_TARGET_BYTES};

const ENTITY_QUERY_CHUNK_SIZE: usize = 400;

#[derive(Debug, Clone)]
pub struct Principal {
    pub family_id: String,
    pub role: String,
    pub token_hash: String,
    pub device_id: String,
}

#[derive(Debug, Clone)]
pub struct ActiveMembership {
    pub token_hash: String,
    pub role: String,
    pub device_id: String,
    pub display_name: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct PulledEntity {
    #[serde(rename = "type")]
    pub entity_type: String,
    pub client_uuid: String,
    pub updated_at: i64,
    pub deleted_at: Option<i64>,
    pub payload: Map<String, Value>,
    pub rev: i64,
}

#[derive(Debug)]
pub struct PullPage {
    pub entities: Vec<PulledEntity>,
    pub cursor: i64,
    pub has_more: bool,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct PushResult {
    pub applied: usize,
    pub skipped: usize,
    pub cursor: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MediaMetadata {
    pub kind: String,
    pub byte_size: Option<usize>,
}

#[derive(Debug, Error)]
pub enum StoreError {
    #[error("sqlite error: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("family already exists")]
    FamilyAlreadyExists,
    #[error("invitation not found")]
    InviteNotFound,
    #[error("invitation expired")]
    InviteExpired,
    #[error("invitation already used by another device")]
    InviteAlreadyUsed,
    #[error("cursor is ahead of server cursor {0}")]
    CursorAhead(i64),
    #[error("only owner may change avatar")]
    ForbiddenAvatar,
    #[error("media kind and association are immutable")]
    ImmutableMediaAssociation,
    #[error("entity updated_at is outside the accepted time range")]
    TimestampOutOfRange,
    #[error("entity is too large for a bounded pull page")]
    PullEntityTooLarge,
    #[error("{0}")]
    UnresolvedReference(String),
    #[error("stored entity payload is invalid")]
    InvalidStoredPayload,
}

#[derive(Clone)]
pub struct Store {
    database_path: PathBuf,
}

#[derive(Debug, Clone)]
struct ExistingEntity {
    updated_at: i64,
    deleted_at: Option<i64>,
    payload: Map<String, Value>,
}

type EntityKey = (String, String);
type MediaAssociation = (String, Option<String>, Option<String>);

impl Store {
    pub fn open(database_path: impl Into<PathBuf>) -> Result<Self, StoreError> {
        let store = Self {
            database_path: database_path.into(),
        };
        if let Some(parent) = store.database_path.parent() {
            fs::create_dir_all(parent)?;
            crate::secure_directory(parent)?;
        }
        store.initialize()?;
        store.secure_database_files()?;
        Ok(store)
    }

    fn connect(&self) -> Result<Connection, StoreError> {
        let connection = Connection::open(&self.database_path)?;
        crate::secure_file(&self.database_path)?;
        connection.busy_timeout(Duration::from_secs(10))?;
        connection.execute_batch(
            "
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = WAL;
            ",
        )?;
        self.secure_database_files()?;
        Ok(connection)
    }

    fn secure_database_files(&self) -> Result<(), StoreError> {
        for suffix in ["", "-wal", "-shm"] {
            let path = PathBuf::from(format!("{}{}", self.database_path.display(), suffix));
            if path.exists() {
                crate::secure_file(&path)?;
            }
        }
        Ok(())
    }

    pub fn health_check(&self) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.query_row("SELECT COUNT(*) FROM family_meta", [], |row| {
            row.get::<_, i64>(0)
        })?;
        Ok(())
    }

    fn initialize(&self) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS families (
                id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                create_request_hash TEXT,
                name TEXT
            );

            CREATE TABLE IF NOT EXISTS memberships (
                token_hash TEXT PRIMARY KEY,
                family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
                device_id TEXT NOT NULL,
                display_name TEXT,
                revoked_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS memberships_family
                ON memberships(family_id);

            CREATE TABLE IF NOT EXISTS invites (
                code_hash TEXT PRIMARY KEY,
                family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                expires_at INTEGER NOT NULL,
                used_at INTEGER,
                joined_device_id TEXT
            );

            CREATE TABLE IF NOT EXISTS family_meta (
                family_id TEXT PRIMARY KEY REFERENCES families(id) ON DELETE CASCADE,
                rev INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS entities (
                family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                entity_type TEXT NOT NULL CHECK(entity_type IN ('baby', 'record', 'media')),
                client_uuid TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER,
                payload_json TEXT NOT NULL,
                rev INTEGER NOT NULL,
                PRIMARY KEY (family_id, entity_type, client_uuid)
            );
            CREATE INDEX IF NOT EXISTS entities_family_rev
                ON entities(family_id, rev);
            ",
        )?;

        let invite_columns = table_columns(&connection, "invites")?;
        if !invite_columns.contains("joined_device_id") {
            connection.execute("ALTER TABLE invites ADD COLUMN joined_device_id TEXT", [])?;
        }
        let family_columns = table_columns(&connection, "families")?;
        if !family_columns.contains("create_request_hash") {
            connection.execute(
                "ALTER TABLE families ADD COLUMN create_request_hash TEXT",
                [],
            )?;
        }
        if !family_columns.contains("name") {
            connection.execute("ALTER TABLE families ADD COLUMN name TEXT", [])?;
        }
        connection.execute(
            "
            CREATE UNIQUE INDEX IF NOT EXISTS families_create_request
            ON families(create_request_hash)
            ",
            [],
        )?;
        self.secure_database_files()?;
        Ok(())
    }

    /// Creates a family (or returns the same credentials on matching idempotent retry).
    ///
    /// Returns `(family_id, token, family_name)`.
    pub fn create_family<F>(
        &self,
        now: i64,
        create_request_id: &str,
        device_id: &str,
        display_name: &str,
        family_name: Option<&str>,
        derive_token: F,
    ) -> Result<(String, String, Option<String>), StoreError>
    where
        F: Fn(&str, &str) -> String,
    {
        let create_request_hash = crate::hash_secret(create_request_id);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let retry = transaction
            .query_row(
                "
                SELECT families.id, families.name, memberships.device_id,
                       memberships.display_name, memberships.revoked_at
                FROM families
                JOIN memberships
                  ON memberships.family_id = families.id
                 AND memberships.role = 'owner'
                WHERE families.create_request_hash = ?1
                ",
                params![create_request_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, Option<String>>(3)?,
                        row.get::<_, Option<i64>>(4)?,
                    ))
                },
            )
            .optional()?;
        if let Some((family_id, stored_family_name, stored_device, stored_name, revoked_at)) = retry {
            if stored_device != device_id
                || stored_name.as_deref() != Some(display_name)
                || stored_family_name.as_deref() != family_name
                || revoked_at.is_some()
            {
                return Err(StoreError::FamilyAlreadyExists);
            }
            return Ok((
                family_id.clone(),
                derive_token(&create_request_hash, &family_id),
                stored_family_name,
            ));
        }
        if transaction
            .query_row("SELECT 1 FROM families LIMIT 1", [], |_| Ok(()))
            .optional()?
            .is_some()
        {
            return Err(StoreError::FamilyAlreadyExists);
        }

        let family_id = Uuid::new_v4().to_string();
        let token = derive_token(&create_request_hash, &family_id);
        transaction.execute(
            "
            INSERT INTO families(id, created_at, create_request_hash, name)
            VALUES (?1, ?2, ?3, ?4)
            ",
            params![family_id, now, create_request_hash, family_name],
        )?;
        transaction.execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, 0)",
            params![family_id],
        )?;
        transaction.execute(
            "
            INSERT INTO memberships(
                token_hash, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, 'owner', ?3, ?4)
            ",
            params![
                crate::hash_secret(&token),
                family_id,
                device_id,
                display_name
            ],
        )?;
        transaction.commit()?;
        Ok((
            family_id,
            token,
            family_name.map(str::to_owned),
        ))
    }

    /// Sets the shared family name (`None` clears). Caller enforces owner role.
    pub fn rename_family(
        &self,
        family_id: &str,
        family_name: Option<&str>,
    ) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute(
            "UPDATE families SET name = ?1 WHERE id = ?2",
            params![family_name, family_id],
        )?;
        Ok(())
    }

    pub fn authenticate(&self, token: &str) -> Result<Option<Principal>, StoreError> {
        let connection = self.connect()?;
        let principal = connection
            .query_row(
                "
                SELECT token_hash, family_id, role, device_id
                FROM memberships
                WHERE token_hash = ?1 AND revoked_at IS NULL
                ",
                params![crate::hash_secret(token)],
                |row| {
                    Ok(Principal {
                        token_hash: row.get(0)?,
                        family_id: row.get(1)?,
                        role: row.get(2)?,
                        device_id: row.get(3)?,
                    })
                },
            )
            .optional()?;
        Ok(principal)
    }

    pub fn active_memberships(&self, family_id: &str) -> Result<Vec<ActiveMembership>, StoreError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "
            SELECT token_hash, role, device_id, display_name
            FROM memberships
            WHERE family_id = ?1 AND revoked_at IS NULL
            ORDER BY
                CASE role WHEN 'owner' THEN 0 ELSE 1 END,
                device_id COLLATE BINARY,
                token_hash COLLATE BINARY
            ",
        )?;
        let rows = statement.query_map(params![family_id], |row| {
            Ok(ActiveMembership {
                token_hash: row.get(0)?,
                role: row.get(1)?,
                device_id: row.get(2)?,
                display_name: row.get(3)?,
            })
        })?;
        rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
    }

    pub fn create_invite(
        &self,
        family_id: &str,
        now: i64,
        ttl_seconds: i64,
        code: &str,
    ) -> Result<i64, StoreError> {
        let expires_at = now + ttl_seconds;
        let connection = self.connect()?;
        connection.execute(
            "
            INSERT INTO invites(code_hash, family_id, expires_at)
            VALUES (?1, ?2, ?3)
            ",
            params![crate::hash_secret(code), family_id, expires_at],
        )?;
        Ok(expires_at)
    }

    /// Updates the active membership row for `token_hash` only (self-rename).
    pub fn update_membership_display_name(
        &self,
        token_hash: &str,
        display_name: &str,
    ) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute(
            "
            UPDATE memberships
            SET display_name = ?1
            WHERE token_hash = ?2 AND revoked_at IS NULL
            ",
            params![display_name, token_hash],
        )?;
        Ok(())
    }

    /// Returns `(family_id, token, family_name)`.
    pub fn join_family<F>(
        &self,
        code: &str,
        device_id: &str,
        display_name: &str,
        now: i64,
        derive_token: F,
    ) -> Result<(String, String, Option<String>), StoreError>
    where
        F: Fn(&str, &str) -> String,
    {
        let code_hash = crate::hash_secret(code);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let invite = transaction
            .query_row(
                "
                SELECT invites.family_id, invites.expires_at, invites.used_at,
                       invites.joined_device_id, families.name
                FROM invites
                JOIN families ON families.id = invites.family_id
                WHERE invites.code_hash = ?1
                ",
                params![code_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, Option<i64>>(2)?,
                        row.get::<_, Option<String>>(3)?,
                        row.get::<_, Option<String>>(4)?,
                    ))
                },
            )
            .optional()?
            .ok_or(StoreError::InviteNotFound)?;
        let (family_id, expires_at, used_at, joined_device_id, family_name) = invite;
        let token = derive_token(&code_hash, device_id);
        let token_hash = crate::hash_secret(&token);
        if used_at.is_some() {
            if joined_device_id.as_deref() != Some(device_id) {
                return Err(StoreError::InviteAlreadyUsed);
            }
            let active = transaction
                .query_row(
                    "
                    SELECT 1 FROM memberships
                    WHERE token_hash = ?1 AND family_id = ?2 AND revoked_at IS NULL
                    ",
                    params![token_hash, family_id],
                    |_| Ok(()),
                )
                .optional()?;
            if active.is_none() {
                return Err(StoreError::InviteNotFound);
            }
            return Ok((family_id, token, family_name));
        }
        if expires_at <= now {
            return Err(StoreError::InviteExpired);
        }
        transaction.execute(
            "
            INSERT INTO memberships(
                token_hash, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, 'member', ?3, ?4)
            ",
            params![token_hash, family_id, device_id, display_name],
        )?;
        transaction.execute(
            "
            UPDATE invites
            SET used_at = ?1, joined_device_id = ?2
            WHERE code_hash = ?3
            ",
            params![now, device_id, code_hash],
        )?;
        transaction.commit()?;
        Ok((family_id, token, family_name))
    }

    pub fn revoke(&self, token_hash: &str, now: i64) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute(
            "UPDATE memberships SET revoked_at = ?1 WHERE token_hash = ?2",
            params![now, token_hash],
        )?;
        Ok(())
    }

    pub fn delete_family(&self, family_id: &str) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute("DELETE FROM families WHERE id = ?1", params![family_id])?;
        Ok(())
    }

    pub fn push(
        &self,
        family_id: &str,
        role: &str,
        entities: Vec<Entity>,
        max_updated_at: i64,
    ) -> Result<PushResult, StoreError> {
        if entities
            .iter()
            .any(|entity| entity.updated_at > max_updated_at)
        {
            return Err(StoreError::TimestampOutOfRange);
        }
        let original_count = entities.len();
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let incoming_keys = entities.iter().map(entity_key).collect::<BTreeSet<_>>();
        let mut existing = load_existing_entities(&transaction, family_id, &incoming_keys)?;
        let mut effective = effective_lww_winners(entities, &existing);
        for entity in &effective {
            let payload_bytes = serde_json::to_vec(&entity.payload)?.len();
            // Leave room for the entity envelope and the page response fields.
            // A media page can need media -> record -> baby. Keeping each
            // entity below one third of the page target guarantees that the
            // complete dependency group can be emitted without stalling.
            if payload_bytes.saturating_add(512) > PULL_ENTITY_TARGET_BYTES {
                return Err(StoreError::PullEntityTooLarge);
            }
        }
        let reference_keys = validation_reference_keys(&effective);
        let missing_references = reference_keys
            .difference(&incoming_keys)
            .cloned()
            .collect::<BTreeSet<_>>();
        existing.extend(load_existing_entities(
            &transaction,
            family_id,
            &missing_references,
        )?);
        validate_push(role, &effective, &existing)?;

        let entity_count = effective.len();
        let mut cursor: i64 = transaction.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![family_id],
            |row| row.get(0),
        )?;
        effective.sort_by_key(|entity| match entity.entity_type.as_str() {
            "baby" => 0,
            "record" => 1,
            _ => 2,
        });
        for entity in &effective {
            cursor += 1;
            transaction.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at,
                    deleted_at, payload_json, rev
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                ON CONFLICT(family_id, entity_type, client_uuid) DO UPDATE SET
                    updated_at = excluded.updated_at,
                    deleted_at = excluded.deleted_at,
                    payload_json = excluded.payload_json,
                    rev = excluded.rev
                ",
                params![
                    family_id,
                    entity.entity_type,
                    entity.client_uuid,
                    entity.updated_at,
                    entity.deleted_at,
                    serde_json::to_string(&entity.payload)?,
                    cursor
                ],
            )?;
        }
        transaction.execute(
            "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
            params![cursor, family_id],
        )?;
        transaction.commit()?;
        self.secure_database_files()?;
        Ok(PushResult {
            applied: entity_count,
            skipped: original_count.saturating_sub(entity_count),
            cursor,
        })
    }

    pub fn pull(&self, family_id: &str, cursor: i64) -> Result<PullPage, StoreError> {
        let connection = self.connect()?;
        let current: i64 = connection.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![family_id],
            |row| row.get(0),
        )?;
        if cursor > current {
            return Err(StoreError::CursorAhead(current));
        }
        let mut statement = connection.prepare(
            "
            SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            FROM entities
            WHERE family_id = ?1 AND rev > ?2 AND rev <= ?3
            ORDER BY rev ASC
            ",
        )?;
        let mut rows = statement.query(params![family_id, cursor, current])?;
        let mut entities = Vec::new();
        let mut included_keys = BTreeSet::new();
        let mut serialized_bytes = 0usize;
        let mut page_cursor = cursor;
        let mut has_more = false;
        while let Some(row) = rows.next()? {
            let entity = pulled_entity_from_row(row)?;
            let base_rev = entity.rev;
            let mut group = Vec::new();
            let mut group_keys = BTreeSet::new();
            collect_pull_entity_with_dependencies(
                &connection,
                family_id,
                cursor,
                entity,
                &included_keys,
                &mut group_keys,
                &mut group,
            )?;
            let group_bytes = group.iter().try_fold(0usize, |total, entity| {
                Ok::<_, StoreError>(
                    total
                        .saturating_add(serde_json::to_vec(entity)?.len())
                        .saturating_add(1),
                )
            })?;
            let would_exceed_count =
                entities.len().saturating_add(group.len()) > PULL_PAGE_ENTITY_LIMIT;
            let would_exceed_bytes =
                serialized_bytes.saturating_add(group_bytes) > PULL_PAGE_TARGET_BYTES;
            if would_exceed_count || would_exceed_bytes {
                if page_cursor == cursor {
                    return Err(StoreError::PullEntityTooLarge);
                }
                has_more = true;
                break;
            }
            serialized_bytes = serialized_bytes.saturating_add(group_bytes);
            included_keys.extend(group_keys);
            entities.extend(group);
            page_cursor = base_rev;
            if entities.len() >= PULL_PAGE_ENTITY_LIMIT
                || serialized_bytes >= PULL_PAGE_TARGET_BYTES
            {
                has_more = page_cursor < current;
                break;
            }
        }
        if !has_more {
            // Revisions can contain gaps after a later update replaces an
            // entity's older row. Once the snapshot is exhausted it is safe to
            // advance across those gaps to the captured server revision.
            page_cursor = current;
        }
        Ok(PullPage {
            entities,
            cursor: page_cursor,
            has_more,
        })
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

    pub fn media_metadata(
        &self,
        family_id: &str,
        client_uuid: &str,
    ) -> Result<Option<MediaMetadata>, StoreError> {
        let connection = self.connect()?;
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

    /// Bump a live media entity's revision after its bytes become available so
    /// pull clients that advanced past the incomplete metadata rev see it again.
    pub fn republish_media(&self, family_id: &str, client_uuid: &str) -> Result<bool, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let live = transaction
            .query_row(
                "
                SELECT 1 FROM entities
                WHERE family_id = ?1
                  AND entity_type = 'media'
                  AND client_uuid = ?2
                  AND deleted_at IS NULL
                ",
                params![family_id, client_uuid],
                |_| Ok(()),
            )
            .optional()?
            .is_some();
        if !live {
            return Ok(false);
        }
        let mut cursor: i64 = transaction.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![family_id],
            |row| row.get(0),
        )?;
        cursor += 1;
        transaction.execute(
            "
            UPDATE entities
            SET rev = ?1
            WHERE family_id = ?2
              AND entity_type = 'media'
              AND client_uuid = ?3
              AND deleted_at IS NULL
            ",
            params![cursor, family_id, client_uuid],
        )?;
        transaction.execute(
            "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
            params![cursor, family_id],
        )?;
        transaction.commit()?;
        self.secure_database_files()?;
        Ok(true)
    }
}

fn pulled_entity_from_row(row: &rusqlite::Row<'_>) -> Result<PulledEntity, StoreError> {
    let entity_type = row.get::<_, String>(0)?;
    let mut payload = parse_payload(&row.get::<_, String>(4)?)?;
    if entity_type == "baby" {
        payload.remove("sort_order");
    }
    Ok(PulledEntity {
        entity_type,
        client_uuid: row.get(1)?,
        updated_at: row.get(2)?,
        deleted_at: row.get(3)?,
        payload,
        rev: row.get(5)?,
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
                let mut payload = parse_payload(&payload_raw)?;
                if entity_type == "baby" {
                    payload.remove("sort_order");
                }
                Ok(PulledEntity {
                    entity_type,
                    client_uuid,
                    updated_at,
                    deleted_at,
                    payload,
                    rev,
                })
            },
        )
        .transpose()
}

fn collect_pull_entity_with_dependencies(
    connection: &Connection,
    family_id: &str,
    cursor: i64,
    entity: PulledEntity,
    included_keys: &BTreeSet<EntityKey>,
    group_keys: &mut BTreeSet<EntityKey>,
    group: &mut Vec<PulledEntity>,
) -> Result<(), StoreError> {
    let key = (entity.entity_type.clone(), entity.client_uuid.clone());
    if included_keys.contains(&key) || group_keys.contains(&key) {
        return Ok(());
    }
    if entity.deleted_at.is_none() {
        match entity.entity_type.as_str() {
            "record" => append_pull_dependency(
                connection,
                family_id,
                cursor,
                "baby",
                required_payload_reference(&entity.payload, "baby_client_uuid")?,
                included_keys,
                group_keys,
                group,
            )?,
            "media" => match entity.payload.get("kind").and_then(Value::as_str) {
                Some("avatar") => append_pull_dependency(
                    connection,
                    family_id,
                    cursor,
                    "baby",
                    required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?,
                Some("log") => append_pull_dependency(
                    connection,
                    family_id,
                    cursor,
                    "record",
                    required_payload_reference(&entity.payload, "record_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?,
                _ => return Err(StoreError::InvalidStoredPayload),
            },
            _ => {}
        }
    }
    group_keys.insert(key);
    group.push(entity);
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn append_pull_dependency(
    connection: &Connection,
    family_id: &str,
    cursor: i64,
    entity_type: &str,
    client_uuid: &str,
    included_keys: &BTreeSet<EntityKey>,
    group_keys: &mut BTreeSet<EntityKey>,
    group: &mut Vec<PulledEntity>,
) -> Result<(), StoreError> {
    let key = (entity_type.to_owned(), client_uuid.to_owned());
    if included_keys.contains(&key) || group_keys.contains(&key) {
        return Ok(());
    }
    let dependency = load_pulled_entity(connection, family_id, entity_type, client_uuid)?
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
    collect_pull_entity_with_dependencies(
        connection,
        family_id,
        cursor,
        dependency,
        included_keys,
        group_keys,
        group,
    )
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

fn table_columns(connection: &Connection, table: &str) -> Result<BTreeSet<String>, StoreError> {
    let mut statement = connection.prepare(&format!("PRAGMA table_info({table})"))?;
    let rows = statement.query_map([], |row| row.get::<_, String>(1))?;
    rows.collect::<Result<BTreeSet<_>, _>>()
        .map_err(StoreError::from)
}

fn entity_key(entity: &Entity) -> EntityKey {
    (entity.entity_type.clone(), entity.client_uuid.clone())
}

fn parse_payload(raw: &str) -> Result<Map<String, Value>, StoreError> {
    serde_json::from_str::<Value>(raw)?
        .as_object()
        .cloned()
        .ok_or(StoreError::InvalidStoredPayload)
}

fn load_existing_entities(
    transaction: &Transaction<'_>,
    family_id: &str,
    keys: &BTreeSet<EntityKey>,
) -> Result<HashMap<EntityKey, ExistingEntity>, StoreError> {
    let mut by_type: BTreeMap<&str, Vec<&str>> = BTreeMap::new();
    for (entity_type, client_uuid) in keys {
        by_type
            .entry(entity_type.as_str())
            .or_default()
            .push(client_uuid.as_str());
    }
    let mut existing = HashMap::new();
    for (entity_type, client_uuids) in by_type {
        for chunk in client_uuids.chunks(ENTITY_QUERY_CHUNK_SIZE) {
            let placeholders = std::iter::repeat_n("?", chunk.len())
                .collect::<Vec<_>>()
                .join(", ");
            let sql = format!(
                "
                SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json
                FROM entities
                WHERE family_id = ? AND entity_type = ?
                  AND client_uuid IN ({placeholders})
                "
            );
            let mut parameters = Vec::with_capacity(chunk.len() + 2);
            parameters.push(SqlValue::Text(family_id.to_owned()));
            parameters.push(SqlValue::Text(entity_type.to_owned()));
            parameters.extend(
                chunk
                    .iter()
                    .map(|client_uuid| SqlValue::Text((*client_uuid).to_owned())),
            );
            let mut statement = transaction.prepare(&sql)?;
            let rows = statement.query_map(params_from_iter(parameters), |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, Option<i64>>(3)?,
                    row.get::<_, String>(4)?,
                ))
            })?;
            for row in rows {
                let (stored_type, client_uuid, updated_at, deleted_at, payload) = row?;
                existing.insert(
                    (stored_type, client_uuid),
                    ExistingEntity {
                        updated_at,
                        deleted_at,
                        payload: parse_payload(&payload)?,
                    },
                );
            }
        }
    }
    Ok(existing)
}

fn effective_lww_winners(
    entities: Vec<Entity>,
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Vec<Entity> {
    let mut winners: HashMap<EntityKey, (usize, Entity)> = HashMap::new();
    for (index, entity) in entities.into_iter().enumerate() {
        let key = entity_key(&entity);
        if existing
            .get(&key)
            .is_some_and(|current| current.updated_at >= entity.updated_at)
        {
            continue;
        }
        let replace = winners
            .get(&key)
            .is_none_or(|(_, previous)| entity.updated_at > previous.updated_at);
        if replace {
            winners.insert(key, (index, entity));
        }
    }
    let mut winners = winners.into_values().collect::<Vec<_>>();
    winners.sort_by_key(|(index, _)| *index);
    winners.into_iter().map(|(_, entity)| entity).collect()
}

fn validation_reference_keys(entities: &[Entity]) -> BTreeSet<EntityKey> {
    let mut references = BTreeSet::new();
    for entity in entities {
        match entity.entity_type.as_str() {
            "record" => {
                if let Some(id) = entity.payload["baby_client_uuid"].as_str() {
                    references.insert(("baby".to_owned(), id.to_owned()));
                }
            }
            "media" => {
                if entity.payload["kind"] == "avatar" {
                    if let Some(id) = entity.payload["baby_client_uuid"].as_str() {
                        references.insert(("baby".to_owned(), id.to_owned()));
                    }
                } else if let Some(id) = entity.payload["record_client_uuid"].as_str() {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
            }
            "baby" => {
                if let Some(id) = entity.payload["avatar_media_uuid"].as_str() {
                    references.insert(("media".to_owned(), id.to_owned()));
                }
            }
            _ => {}
        }
    }
    references
}

fn validate_push(
    role: &str,
    entities: &[Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<(), StoreError> {
    let mut baby_ids = existing
        .keys()
        .filter(|(entity_type, _)| entity_type == "baby")
        .map(|(_, id)| id.clone())
        .collect::<BTreeSet<_>>();
    baby_ids.extend(
        entities
            .iter()
            .filter(|entity| entity.entity_type == "baby")
            .map(|entity| entity.client_uuid.clone()),
    );

    let mut effective_records = existing
        .iter()
        .filter(|((entity_type, _), _)| entity_type == "record")
        .map(|((_, id), entity)| (id.clone(), entity.payload.clone()))
        .collect::<HashMap<_, _>>();
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "record")
    {
        let baby_id = entity.payload["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !baby_ids.contains(baby_id) {
            return Err(StoreError::UnresolvedReference(
                "record baby_client_uuid does not exist".to_owned(),
            ));
        }
        effective_records.insert(entity.client_uuid.clone(), entity.payload.clone());
    }
    let record_ids = effective_records.keys().cloned().collect::<BTreeSet<_>>();

    let mut effective_media = existing
        .iter()
        .filter(|((entity_type, _), _)| entity_type == "media")
        .map(|((_, id), entity)| {
            Ok((
                id.clone(),
                (
                    media_association(&entity.payload)?,
                    entity.deleted_at,
                    entity.updated_at,
                ),
            ))
        })
        .collect::<Result<HashMap<_, _>, StoreError>>()?;
    let mut seen_media: HashMap<String, MediaAssociation> = HashMap::new();
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "media")
    {
        let incoming = media_association(&entity.payload)?;
        if seen_media
            .insert(entity.client_uuid.clone(), incoming.clone())
            .is_some_and(|previous| previous != incoming)
        {
            return Err(StoreError::ImmutableMediaAssociation);
        }
        let existing_association = existing
            .get(&("media".to_owned(), entity.client_uuid.clone()))
            .map(|current| media_association(&current.payload))
            .transpose()?;
        if role != "owner"
            && (incoming.0 == "avatar"
                || existing_association
                    .as_ref()
                    .is_some_and(|association| association.0 == "avatar"))
        {
            return Err(StoreError::ForbiddenAvatar);
        }
        if existing_association
            .as_ref()
            .is_some_and(|association| association != &incoming)
        {
            return Err(StoreError::ImmutableMediaAssociation);
        }
        effective_media.insert(
            entity.client_uuid.clone(),
            (incoming.clone(), entity.deleted_at, entity.updated_at),
        );

        if incoming.0 == "avatar" {
            if incoming
                .2
                .as_ref()
                .is_none_or(|baby_id| !baby_ids.contains(baby_id))
            {
                return Err(StoreError::UnresolvedReference(
                    "avatar baby_client_uuid does not exist".to_owned(),
                ));
            }
        } else {
            let record_id = incoming
                .1
                .as_ref()
                .ok_or(StoreError::InvalidStoredPayload)?;
            if !record_ids.contains(record_id) {
                return Err(StoreError::UnresolvedReference(
                    "log media record_client_uuid does not exist".to_owned(),
                ));
            }
            let record_baby_id = effective_records[record_id]["baby_client_uuid"]
                .as_str()
                .ok_or(StoreError::InvalidStoredPayload)?;
            if incoming
                .2
                .as_ref()
                .is_some_and(|baby_id| baby_id != record_baby_id)
            {
                return Err(StoreError::UnresolvedReference(
                    "log media baby does not match record baby".to_owned(),
                ));
            }
        }
    }

    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "baby")
    {
        let avatar_id = entity.payload["avatar_media_uuid"]
            .as_str()
            .map(str::to_owned);
        let current = existing.get(&("baby".to_owned(), entity.client_uuid.clone()));
        let current_avatar = current
            .and_then(|value| value.payload["avatar_media_uuid"].as_str())
            .map(str::to_owned);
        if role != "owner"
            && ((current.is_none() && avatar_id.is_some())
                || (current.is_some() && avatar_id != current_avatar))
        {
            return Err(StoreError::ForbiddenAvatar);
        }
        let Some(avatar_id) = avatar_id else {
            continue;
        };
        let valid = effective_media
            .get(&avatar_id)
            .is_some_and(|(association, deleted_at, _)| {
                deleted_at.is_none()
                    && association.0 == "avatar"
                    && association.2.as_deref() == Some(entity.client_uuid.as_str())
            });
        if !valid {
            return Err(StoreError::UnresolvedReference(
                "baby avatar_media_uuid must reference avatar media for the same baby".to_owned(),
            ));
        }
    }
    Ok(())
}

fn media_association(payload: &Map<String, Value>) -> Result<MediaAssociation, StoreError> {
    let kind = payload["kind"]
        .as_str()
        .ok_or(StoreError::InvalidStoredPayload)?
        .to_owned();
    let record = payload
        .get("record_client_uuid")
        .and_then(Value::as_str)
        .map(str::to_owned);
    let baby = payload
        .get("baby_client_uuid")
        .and_then(Value::as_str)
        .map(str::to_owned);
    Ok((kind, record, baby))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tempfile::TempDir;

    fn entity(entity_type: &str, client_uuid: Uuid, updated_at: i64, payload: Value) -> Entity {
        Entity {
            entity_type: entity_type.to_owned(),
            client_uuid: client_uuid.to_string(),
            updated_at,
            deleted_at: None,
            payload: payload.as_object().unwrap().clone(),
        }
    }

    fn family(store: &Store) -> String {
        store
            .create_family(
                1,
                "bounded-push-request-id-0000000001",
                "owner",
                "妈妈",
                None,
                |_, _| "owner-token".to_owned(),
            )
            .unwrap()
            .0
    }

    #[test]
    fn lww_and_reference_validation_share_one_transaction() {
        let directory = TempDir::new().unwrap();
        let store = Store::open(directory.path().join("lezi.db")).unwrap();
        let family_id = family(&store);
        let baby_id = Uuid::new_v4();
        let record_id = Uuid::new_v4();
        let baby = json!({
            "nickname":"年年","sex":"female","birthday":"2025-01-02",
            "due_date":null,"avatar_media_uuid":null,"birth_weight_grams":3200
        });
        let record = json!({
            "baby_client_uuid":baby_id,"type":"formula","timestamp":100,
            "end_timestamp":null,"note":null,"payload_json":{"amount_ml":120},
            "schema_version":1
        });
        assert_eq!(
            store
                .push(
                    &family_id,
                    "owner",
                    vec![
                        entity("record", record_id, 1, record),
                        entity("baby", baby_id, 1, baby.clone()),
                    ],
                    10,
                )
                .unwrap()
                .applied,
            2
        );
        assert_eq!(
            store
                .push(
                    &family_id,
                    "owner",
                    vec![entity("baby", baby_id, 1, baby)],
                    10,
                )
                .unwrap()
                .skipped,
            1
        );
        assert_eq!(store.pull(&family_id, 0).unwrap().entities.len(), 2);
    }

    #[test]
    fn push_rejects_entities_past_the_supplied_timestamp_limit_without_writing() {
        let directory = TempDir::new().unwrap();
        let store = Store::open(directory.path().join("lezi.db")).unwrap();
        let family_id = family(&store);
        let baby = json!({
            "nickname":"年年","sex":"female","birthday":"2025-01-02",
            "due_date":null,"avatar_media_uuid":null,"birth_weight_grams":3200
        });

        let result = store.push(
            &family_id,
            "owner",
            vec![entity("baby", Uuid::new_v4(), 11, baby)],
            10,
        );

        assert!(matches!(result, Err(StoreError::TimestampOutOfRange)));
        assert!(store.pull(&family_id, 0).unwrap().entities.is_empty());
    }

    #[test]
    fn pull_page_is_bounded_by_serialized_bytes_as_well_as_entity_count() {
        let directory = TempDir::new().unwrap();
        let store = Store::open(directory.path().join("lezi.db")).unwrap();
        let family_id = family(&store);
        let baby_id = Uuid::new_v4();
        let baby = json!({
            "nickname":"年年","sex":"female","birthday":"2025-01-02",
            "due_date":null,"avatar_media_uuid":null,"birth_weight_grams":3200
        });
        let mut entities = vec![entity("baby", baby_id, 1, baby)];
        entities.extend((0..10).map(|index| {
            entity(
                "record",
                Uuid::new_v4(),
                index + 2,
                json!({
                    "baby_client_uuid":baby_id,
                    "type":"custom",
                    "timestamp":100,
                    "payload_json":{"blob":"x".repeat(1024 * 1024)}
                }),
            )
        }));
        store.push(&family_id, "owner", entities, 100).unwrap();

        let first = store.pull(&family_id, 0).unwrap();
        let serialized_bytes = first
            .entities
            .iter()
            .map(|entity| serde_json::to_vec(entity).unwrap().len() + 1)
            .sum::<usize>();

        assert!(first.has_more);
        assert!(first.cursor < 11);
        assert!(serialized_bytes <= PULL_PAGE_TARGET_BYTES);
    }

    #[test]
    fn push_rejects_an_entity_that_cannot_fit_on_a_bounded_pull_page() {
        let directory = TempDir::new().unwrap();
        let store = Store::open(directory.path().join("lezi.db")).unwrap();
        let family_id = family(&store);
        let baby_id = Uuid::new_v4();
        store
            .push(
                &family_id,
                "owner",
                vec![entity(
                    "baby",
                    baby_id,
                    1,
                    json!({
                        "nickname":"年年","sex":"female","birthday":"2025-01-02",
                        "due_date":null,"avatar_media_uuid":null
                    }),
                )],
                100,
            )
            .unwrap();
        let record_id = Uuid::new_v4();
        let result = store.push(
            &family_id,
            "owner",
            vec![entity(
                "record",
                record_id,
                2,
                json!({
                    "baby_client_uuid":baby_id,
                    "type":"custom",
                    "timestamp":100,
                    "payload_json":{"blob":"x".repeat(PULL_PAGE_TARGET_BYTES)}
                }),
            )],
            100,
        );

        assert!(matches!(result, Err(StoreError::PullEntityTooLarge)));
        assert!(store
            .pull(&family_id, 0)
            .unwrap()
            .entities
            .iter()
            .all(|entity| entity.client_uuid != record_id.to_string()));
    }
}
