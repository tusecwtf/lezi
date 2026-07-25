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

const ENTITY_QUERY_CHUNK_SIZE: usize = 400;

#[derive(Debug, Clone)]
pub struct Principal {
    pub family_id: String,
    pub role: String,
    pub token_hash: String,
    pub device_id: String,
}

#[derive(Debug, Serialize)]
pub struct PulledEntity {
    #[serde(rename = "type")]
    pub entity_type: String,
    pub client_uuid: String,
    pub updated_at: i64,
    pub deleted_at: Option<i64>,
    pub payload: Map<String, Value>,
    pub rev: i64,
}

#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct PushResult {
    pub applied: usize,
    pub skipped: usize,
    pub cursor: i64,
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

    fn initialize(&self) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS families (
                id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                create_request_hash TEXT
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

    pub fn create_family<F>(
        &self,
        now: i64,
        create_request_id: &str,
        device_id: &str,
        display_name: Option<&str>,
        derive_token: F,
    ) -> Result<(String, String), StoreError>
    where
        F: Fn(&str, &str) -> String,
    {
        let create_request_hash = crate::hash_secret(create_request_id);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let retry = transaction
            .query_row(
                "
                SELECT families.id, memberships.device_id,
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
                        row.get::<_, String>(1)?,
                        row.get::<_, Option<String>>(2)?,
                        row.get::<_, Option<i64>>(3)?,
                    ))
                },
            )
            .optional()?;
        if let Some((family_id, stored_device, stored_name, revoked_at)) = retry {
            if stored_device != device_id
                || stored_name.as_deref() != display_name
                || revoked_at.is_some()
            {
                return Err(StoreError::FamilyAlreadyExists);
            }
            return Ok((
                family_id.clone(),
                derive_token(&create_request_hash, &family_id),
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
            INSERT INTO families(id, created_at, create_request_hash)
            VALUES (?1, ?2, ?3)
            ",
            params![family_id, now, create_request_hash],
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
        Ok((family_id, token))
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

    pub fn join_family<F>(
        &self,
        code: &str,
        device_id: &str,
        display_name: Option<&str>,
        now: i64,
        derive_token: F,
    ) -> Result<(String, String), StoreError>
    where
        F: Fn(&str, &str) -> String,
    {
        let code_hash = crate::hash_secret(code);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let invite = transaction
            .query_row(
                "
                SELECT family_id, expires_at, used_at, joined_device_id
                FROM invites WHERE code_hash = ?1
                ",
                params![code_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, Option<i64>>(2)?,
                        row.get::<_, Option<String>>(3)?,
                    ))
                },
            )
            .optional()?
            .ok_or(StoreError::InviteNotFound)?;
        let (family_id, expires_at, used_at, joined_device_id) = invite;
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
            return Ok((family_id, token));
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
        Ok((family_id, token))
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
    ) -> Result<PushResult, StoreError> {
        let original_count = entities.len();
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let incoming_keys = entities.iter().map(entity_key).collect::<BTreeSet<_>>();
        let mut existing = load_existing_entities(&transaction, family_id, &incoming_keys)?;
        let mut effective = effective_lww_winners(entities, &existing);
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

    pub fn pull(
        &self,
        family_id: &str,
        cursor: i64,
    ) -> Result<(Vec<PulledEntity>, i64), StoreError> {
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
            WHERE family_id = ?1 AND rev > ?2
            ORDER BY rev ASC
            ",
        )?;
        let rows = statement.query_map(params![family_id, cursor], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, Option<i64>>(3)?,
                row.get::<_, String>(4)?,
                row.get::<_, i64>(5)?,
            ))
        })?;
        let mut entities = Vec::new();
        for row in rows {
            let (entity_type, client_uuid, updated_at, deleted_at, payload_json, rev) = row?;
            let mut payload = parse_payload(&payload_json)?;
            if entity_type == "baby" {
                payload.remove("sort_order");
            }
            entities.push(PulledEntity {
                entity_type,
                client_uuid,
                updated_at,
                deleted_at,
                payload,
                rev,
            });
        }
        Ok((entities, current))
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

    pub fn media_kind(
        &self,
        family_id: &str,
        client_uuid: &str,
    ) -> Result<Option<String>, StoreError> {
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
            Some((payload, None)) => Ok(parse_payload(&payload)?
                .get("kind")
                .and_then(Value::as_str)
                .map(str::to_owned)),
        }
    }
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
                )
                .unwrap()
                .applied,
            2
        );
        assert_eq!(
            store
                .push(&family_id, "owner", vec![entity("baby", baby_id, 1, baby)],)
                .unwrap()
                .skipped,
            1
        );
        assert_eq!(store.pull(&family_id, 0).unwrap().0.len(), 2);
    }
}
