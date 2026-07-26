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
use sha2::{Digest, Sha256};
use thiserror::Error;
use uuid::Uuid;

use crate::model::{Entity, MAX_BUNDLE_MEDIA_ENTITIES, MAX_OPEN_STAGING_BUNDLES_PER_FAMILY};
use crate::{PULL_ENTITY_TARGET_BYTES, PULL_PAGE_ENTITY_LIMIT, PULL_PAGE_TARGET_BYTES};

const ENTITY_QUERY_CHUNK_SIZE: usize = 400;

#[derive(Debug, Clone)]
pub struct Principal {
    pub family_id: String,
    pub role: String,
    pub device_id: String,
    /// Server-minted immutable membership identity (UUID). Safe to expose to clients.
    pub membership_id: String,
}

#[derive(Debug, Clone)]
pub struct ActiveMembership {
    pub role: String,
    pub device_id: String,
    pub display_name: Option<String>,
    /// Server-minted immutable membership identity (UUID).
    pub membership_id: String,
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
    pub record_authors: Vec<RecordAuthor>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct RecordAuthor {
    pub client_uuid: String,
    pub created_by_membership_id: String,
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
    #[error("custom item change forbidden for this membership")]
    ForbiddenCustomItem,
    #[error("care plan change forbidden for this membership")]
    ForbiddenCarePlan,
    #[error("deleted custom item cannot be resurrected")]
    CustomItemTombstoneResurrection,
    #[error("deleted care plan cannot be resurrected")]
    CarePlanTombstoneResurrection,
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
    #[error("atomic bundle not found")]
    BundleNotFound,
    #[error("atomic bundle already committed with different content")]
    BundleContentConflict,
    #[error("atomic bundle belongs to a different staging membership")]
    BundleMembershipMismatch,
    #[error("legacy staging bundle has no verifiable membership")]
    LegacyBundleMembershipUnknown,
    #[error("too many open staging bundles for this family")]
    BundleStagingLimit,
    #[error("media is not listed in the bundle manifest")]
    BundleMediaNotInManifest,
    #[error("committed bundle does not accept staged media")]
    BundleMediaUploadClosed,
    #[error("bundle media bytes are incomplete")]
    BundleMediaIncomplete,
    #[error("bundle root is not newer than the published version")]
    BundleRootNotNewer,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct BundleStageStatus {
    pub bundle_id: String,
    pub status: String,
    pub missing_media: Vec<String>,
    pub staged_media: Vec<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct BundleCommitResult {
    pub bundle_id: String,
    pub status: String,
    pub applied: usize,
    pub cursor: i64,
    pub record_authors: Vec<RecordAuthor>,
}

#[derive(Debug, Clone)]
pub struct StoredBundle {
    pub media: Vec<Entity>,
    pub required_media: Vec<String>,
    pub status: String,
    pub staged_membership_id: Option<String>,
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
/// (kind, record_client_uuid, baby_client_uuid, care_plan_client_uuid)
type MediaAssociation = (String, Option<String>, Option<String>, Option<String>);

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
        let mut connection = self.connect()?;
        connection.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS families (
                id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                create_request_hash TEXT,
                name TEXT
            );

            CREATE TABLE IF NOT EXISTS memberships (
                membership_id TEXT PRIMARY KEY,
                family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
                device_id TEXT NOT NULL,
                display_name TEXT,
                left_at INTEGER
            );

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
                entity_type TEXT NOT NULL CHECK(entity_type IN ('baby', 'record', 'media', 'care_plan', 'custom_item', 'fulfillment_candidate')),
                client_uuid TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                deleted_at INTEGER,
                payload_json TEXT NOT NULL,
                rev INTEGER NOT NULL,
                PRIMARY KEY (family_id, entity_type, client_uuid)
            );
            CREATE INDEX IF NOT EXISTS entities_family_rev
                ON entities(family_id, rev);

            CREATE TABLE IF NOT EXISTS sync_bundles (
                family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                bundle_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                staged_membership_id TEXT,
                status TEXT NOT NULL CHECK(status IN ('staging', 'committed')),
                root_type TEXT NOT NULL,
                root_client_uuid TEXT NOT NULL,
                root_updated_at INTEGER NOT NULL,
                root_deleted_at INTEGER,
                root_payload_json TEXT NOT NULL,
                media_entities_json TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                committed_at INTEGER,
                committed_cursor INTEGER,
                committed_applied INTEGER,
                PRIMARY KEY (family_id, bundle_id)
            );
            CREATE INDEX IF NOT EXISTS sync_bundles_family_status
                ON sync_bundles(family_id, status);

            CREATE TABLE IF NOT EXISTS sync_bundle_media (
                family_id TEXT NOT NULL,
                bundle_id TEXT NOT NULL,
                media_uuid TEXT NOT NULL,
                declared_byte_size INTEGER,
                staged_byte_size INTEGER,
                staged_at INTEGER,
                PRIMARY KEY (family_id, bundle_id, media_uuid),
                FOREIGN KEY (family_id, bundle_id)
                    REFERENCES sync_bundles(family_id, bundle_id) ON DELETE CASCADE
            );
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
        let membership_columns = table_columns(&connection, "memberships")?;
        if membership_columns.contains("token_hash") {
            migrate_legacy_memberships(&mut connection)?;
        }
        let bundle_columns = table_columns(&connection, "sync_bundles")?;
        if !bundle_columns.contains("staged_membership_id") {
            connection.execute(
                "ALTER TABLE sync_bundles ADD COLUMN staged_membership_id TEXT",
                [],
            )?;
        }
        // Legacy bundles only persisted the authenticated device. Recover the
        // stager membership when that family/device pair is unambiguous across
        // all historical memberships after normalization. Counting left rows
        // prevents a same-device rejoin from taking over an older staged bundle;
        // ambiguous rows remain null and fail closed at stage/commit.
        connection.execute(
            "
            UPDATE sync_bundles
            SET staged_membership_id = (
                SELECT memberships.membership_id
                FROM memberships
                WHERE memberships.family_id = sync_bundles.family_id
                  AND memberships.device_id = sync_bundles.device_id
                ORDER BY memberships.membership_id COLLATE BINARY
                LIMIT 1
            )
            WHERE sync_bundles.status = 'staging'
              AND sync_bundles.staged_membership_id IS NULL
              AND 1 = (
                  SELECT COUNT(*)
                  FROM memberships
                  WHERE memberships.family_id = sync_bundles.family_id
                    AND memberships.device_id = sync_bundles.device_id
              )
            ",
            [],
        )?;
        connection.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS membership_credentials (
                token_hash TEXT PRIMARY KEY,
                membership_id TEXT NOT NULL
                    REFERENCES memberships(membership_id) ON DELETE CASCADE,
                revoked_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS membership_credentials_membership
                ON membership_credentials(membership_id);

            CREATE TABLE IF NOT EXISTS membership_aliases (
                alias_membership_id TEXT PRIMARY KEY,
                canonical_membership_id TEXT NOT NULL
                    REFERENCES memberships(membership_id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS membership_aliases_canonical
                ON membership_aliases(canonical_membership_id);

            CREATE INDEX IF NOT EXISTS memberships_family
                ON memberships(family_id);
            ",
        )?;
        // Expand entities CHECK for care_plan, custom_item, fulfillment_candidate.
        ensure_entities_allow_care_plan_and_custom_item(&connection)?;
        hydrate_legacy_record_authors(&mut connection)?;
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
    /// Returns `(family_id, token, membership_id, family_name)`.
    pub fn create_family<F>(
        &self,
        now: i64,
        create_request_id: &str,
        device_id: &str,
        display_name: &str,
        family_name: Option<&str>,
        derive_token: F,
    ) -> Result<(String, String, String, Option<String>), StoreError>
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
                       memberships.display_name, memberships.membership_id
                FROM families
                JOIN memberships
                  ON memberships.family_id = families.id
                 AND memberships.role = 'owner'
                 AND memberships.left_at IS NULL
                JOIN membership_credentials
                  ON membership_credentials.membership_id = memberships.membership_id
                 AND membership_credentials.revoked_at IS NULL
                WHERE families.create_request_hash = ?1
                LIMIT 1
                ",
                params![create_request_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, Option<String>>(3)?,
                        row.get::<_, String>(4)?,
                    ))
                },
            )
            .optional()?;
        if let Some((family_id, stored_family_name, stored_device, stored_name, membership_id)) =
            retry
        {
            if stored_device != device_id
                || stored_name.as_deref() != Some(display_name)
                || stored_family_name.as_deref() != family_name
            {
                return Err(StoreError::FamilyAlreadyExists);
            }
            return Ok((
                family_id.clone(),
                derive_token(&create_request_hash, &family_id),
                membership_id,
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
        let membership_id = Uuid::new_v4().to_string();
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
                membership_id, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, 'owner', ?3, ?4)
            ",
            params![membership_id, family_id, device_id, display_name],
        )?;
        transaction.execute(
            "
            INSERT INTO membership_credentials(token_hash, membership_id)
            VALUES (?1, ?2)
            ",
            params![crate::hash_secret(&token), membership_id],
        )?;
        transaction.commit()?;
        Ok((
            family_id,
            token,
            membership_id,
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
        let token_hash = crate::hash_secret(token);
        let row = connection
            .query_row(
                "
                SELECT memberships.family_id, memberships.role,
                       memberships.device_id, memberships.membership_id
                FROM membership_credentials AS credentials
                JOIN memberships
                  ON memberships.membership_id = credentials.membership_id
                WHERE credentials.token_hash = ?1
                  AND credentials.revoked_at IS NULL
                  AND memberships.left_at IS NULL
                ",
                params![token_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                    ))
                },
            )
            .optional()?;
        let Some((family_id, role, device_id, membership_id)) = row else {
            return Ok(None);
        };
        Ok(Some(Principal {
            family_id,
            role,
            device_id,
            membership_id,
        }))
    }

    pub fn active_memberships(&self, family_id: &str) -> Result<Vec<ActiveMembership>, StoreError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "
            SELECT role, device_id, display_name, membership_id
            FROM memberships
            WHERE family_id = ?1 AND left_at IS NULL
            ORDER BY
                CASE role WHEN 'owner' THEN 0 ELSE 1 END,
                device_id COLLATE BINARY,
                membership_id COLLATE BINARY
            ",
        )?;
        let rows = statement
            .query_map(params![family_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Option<String>>(2)?,
                    row.get::<_, String>(3)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows
            .into_iter()
            .map(
                |(role, device_id, display_name, membership_id)| ActiveMembership {
                    role,
                    device_id,
                    display_name,
                    membership_id,
                },
            )
            .collect())
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

    /// Updates the canonical active membership, regardless of which credential authenticated.
    pub fn update_membership_display_name(
        &self,
        membership_id: &str,
        display_name: &str,
    ) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute(
            "
            UPDATE memberships
            SET display_name = ?1
            WHERE membership_id = ?2 AND left_at IS NULL
            ",
            params![display_name, membership_id],
        )?;
        Ok(())
    }

    /// Returns `(family_id, token, membership_id, family_name)`.
    pub fn join_family<F>(
        &self,
        code: &str,
        device_id: &str,
        display_name: &str,
        now: i64,
        derive_token: F,
    ) -> Result<(String, String, String, Option<String>), StoreError>
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
            let stored_membership_id = transaction
                .query_row(
                    "
                    SELECT memberships.membership_id
                    FROM membership_credentials AS credentials
                    JOIN memberships
                      ON memberships.membership_id = credentials.membership_id
                    WHERE credentials.token_hash = ?1
                      AND memberships.family_id = ?2
                      AND credentials.revoked_at IS NULL
                      AND memberships.left_at IS NULL
                    ",
                    params![token_hash, family_id],
                    |row| row.get::<_, String>(0),
                )
                .optional()?;
            let Some(stored_membership_id) = stored_membership_id else {
                return Err(StoreError::InviteNotFound);
            };
            return Ok((family_id, token, stored_membership_id, family_name));
        }
        if expires_at <= now {
            return Err(StoreError::InviteExpired);
        }
        let membership_id = Uuid::new_v4().to_string();
        transaction.execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, 'member', ?3, ?4)
            ",
            params![membership_id, family_id, device_id, display_name],
        )?;
        transaction.execute(
            "
            INSERT INTO membership_credentials(token_hash, membership_id)
            VALUES (?1, ?2)
            ",
            params![token_hash, membership_id],
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
        Ok((family_id, token, membership_id, family_name))
    }

    pub fn leave_membership(&self, membership_id: &str, now: i64) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
            "
            UPDATE memberships
            SET left_at = ?1
            WHERE membership_id = ?2 AND left_at IS NULL
            ",
            params![now, membership_id],
        )?;
        transaction.execute(
            "
            UPDATE membership_credentials
            SET revoked_at = ?1
            WHERE membership_id = ?2 AND revoked_at IS NULL
            ",
            params![now, membership_id],
        )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn delete_family(&self, family_id: &str) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute("DELETE FROM families WHERE id = ?1", params![family_id])?;
        Ok(())
    }

    pub fn push(
        &self,
        principal: &Principal,
        entities: Vec<Entity>,
        max_updated_at: i64,
        // Server wall clock (seconds or millis — stored as-is for confirmed_at).
        now: i64,
    ) -> Result<PushResult, StoreError> {
        let Principal {
            family_id,
            role,
            device_id,
            membership_id,
        } = principal;
        if entities
            .iter()
            .any(|entity| entity.updated_at > max_updated_at)
        {
            return Err(StoreError::TimestampOutOfRange);
        }
        let original_count = entities.len();
        let incoming_record_ids = entities
            .iter()
            .filter(|entity| entity.entity_type == "record")
            .map(|entity| entity.client_uuid.clone())
            .collect::<BTreeSet<_>>();
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let incoming_keys = entities.iter().map(entity_key).collect::<BTreeSet<_>>();
        let mut existing = load_existing_entities(&transaction, family_id, &incoming_keys)?;
        let mut effective = effective_lww_winners(entities, &existing);
        canonicalize_record_authors(device_id, membership_id, &mut effective, &existing);
        let equivalent_memberships = equivalent_membership_ids(&transaction, membership_id)?;
        stamp_and_authorize_custom_items(
            role,
            membership_id,
            &equivalent_memberships,
            &mut effective,
            &existing,
        )?;
        stamp_and_authorize_care_plans(
            role,
            membership_id,
            &equivalent_memberships,
            &mut effective,
            &existing,
        )?;
        // confirmed_at is millis-like; prefer entity.updated_at when already ms-scale.
        let confirmed_at = if now > 1_000_000_000_000 {
            now
        } else {
            now.saturating_mul(1_000)
        };
        stamp_fulfillment_candidates(role, membership_id, confirmed_at, &mut effective, &existing)?;
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
            "custom_item" => 1,
            "record" | "care_plan" => 2,
            "fulfillment_candidate" => 3,
            _ => 4,
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
        let record_authors =
            record_author_acknowledgements(&incoming_record_ids, &effective, &existing);
        transaction.commit()?;
        self.secure_database_files()?;
        Ok(PushResult {
            applied: entity_count,
            skipped: original_count.saturating_sub(entity_count),
            cursor,
            record_authors,
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

    /// Stage (or refresh) an atomic bundle: root + media metadata only.
    /// Nothing is visible to ordinary pull until [Self::commit_bundle].
    ///
    /// [Principal] supplies server-authenticated identity for canonical authors and
    /// CarePlan creator ACL on the real publish path (not only legacy push).
    pub fn stage_bundle(
        &self,
        principal: &Principal,
        bundle_id: &str,
        root: Entity,
        media: Vec<Entity>,
        now: i64,
    ) -> Result<BundleStageStatus, StoreError> {
        let Principal {
            family_id,
            role,
            device_id,
            membership_id,
        } = principal;
        if media.len() > MAX_BUNDLE_MEDIA_ENTITIES {
            return Err(StoreError::UnresolvedReference(format!(
                "bundle media must contain at most {MAX_BUNDLE_MEDIA_ENTITIES} items"
            )));
        }
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;

        // Canonicalize server-owned Record/CarePlan authors before hashing so forged
        // membership claims never become part of the durable package identity.
        let mut package = Vec::with_capacity(1 + media.len());
        package.push(root);
        package.extend(media);
        let incoming_keys = package.iter().map(entity_key).collect::<BTreeSet<_>>();
        let mut existing = load_existing_entities(&transaction, family_id, &incoming_keys)?;
        canonicalize_equal_lww_bundle_root(&mut package, &existing);
        canonicalize_record_authors(device_id, membership_id, &mut package, &existing);
        let equivalent_memberships = equivalent_membership_ids(&transaction, membership_id)?;
        stamp_and_authorize_care_plans(
            role,
            membership_id,
            &equivalent_memberships,
            &mut package,
            &existing,
        )?;
        let reference_keys = validation_reference_keys(&package);
        let missing_references = reference_keys
            .difference(&incoming_keys)
            .cloned()
            .collect::<BTreeSet<_>>();
        existing.extend(load_existing_entities(
            &transaction,
            family_id,
            &missing_references,
        )?);
        for entity in &package {
            existing.insert(
                entity_key(entity),
                ExistingEntity {
                    updated_at: entity.updated_at,
                    deleted_at: entity.deleted_at,
                    payload: entity.payload.clone(),
                },
            );
        }
        validate_push(role, &package, &existing)?;
        let root = package
            .iter()
            .find(|entity| entity.entity_type != "media")
            .cloned()
            .ok_or(StoreError::InvalidStoredPayload)?;
        let media: Vec<Entity> = package
            .into_iter()
            .filter(|entity| entity.entity_type == "media")
            .collect();
        let content_hash = bundle_content_hash(&root, &media)?;

        if let Some(existing_bundle) = load_bundle_row(&transaction, family_id, bundle_id)? {
            if existing_bundle.status == "committed" {
                if existing_bundle.content_hash != content_hash {
                    return Err(StoreError::BundleContentConflict);
                }
                return bundle_stage_status_from_row(&transaction, family_id, &existing_bundle);
            }
            match existing_bundle.staged_membership_id.as_deref() {
                Some(staged_membership_id) if staged_membership_id == membership_id => {}
                Some(_) => return Err(StoreError::BundleMembershipMismatch),
                None => return Err(StoreError::LegacyBundleMembershipUnknown),
            }
            // Replace open staging with the new package (same bundle_id retry/refine).
            transaction.execute(
                "DELETE FROM sync_bundle_media WHERE family_id = ?1 AND bundle_id = ?2",
                params![family_id, bundle_id],
            )?;
            transaction.execute(
                "DELETE FROM sync_bundles WHERE family_id = ?1 AND bundle_id = ?2",
                params![family_id, bundle_id],
            )?;
        } else {
            let open_count: i64 = transaction.query_row(
                "
                SELECT COUNT(*) FROM sync_bundles
                WHERE family_id = ?1 AND status = 'staging'
                ",
                params![family_id],
                |row| row.get(0),
            )?;
            if open_count >= MAX_OPEN_STAGING_BUNDLES_PER_FAMILY as i64 {
                return Err(StoreError::BundleStagingLimit);
            }
        }

        let media_entities_json = serde_json::to_string(&media)?;
        transaction.execute(
            "
            INSERT INTO sync_bundles(
                family_id, bundle_id, device_id, staged_membership_id, status,
                root_type, root_client_uuid, root_updated_at, root_deleted_at,
                root_payload_json, media_entities_json, content_hash, created_at
            ) VALUES (?1, ?2, ?3, ?4, 'staging', ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)
            ",
            params![
                family_id,
                bundle_id,
                device_id,
                membership_id,
                root.entity_type,
                root.client_uuid,
                root.updated_at,
                root.deleted_at,
                serde_json::to_string(&root.payload)?,
                media_entities_json,
                content_hash,
                now,
            ],
        )?;
        for entity in &media {
            let declared = entity
                .payload
                .get("byte_size")
                .and_then(Value::as_i64)
                .filter(|_| entity.deleted_at.is_none());
            transaction.execute(
                "
                INSERT INTO sync_bundle_media(
                    family_id, bundle_id, media_uuid, declared_byte_size
                ) VALUES (?1, ?2, ?3, ?4)
                ",
                params![family_id, bundle_id, entity.client_uuid, declared],
            )?;
        }
        transaction.commit()?;
        self.secure_database_files()?;
        self.bundle_status(family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)
    }

    pub fn bundle_status(
        &self,
        family_id: &str,
        bundle_id: &str,
    ) -> Result<Option<BundleStageStatus>, StoreError> {
        let connection = self.connect()?;
        let Some(row) = load_bundle_row(&connection, family_id, bundle_id)? else {
            return Ok(None);
        };
        Ok(Some(bundle_stage_status_from_row(
            &connection,
            family_id,
            &row,
        )?))
    }

    pub fn load_bundle(
        &self,
        family_id: &str,
        bundle_id: &str,
    ) -> Result<Option<StoredBundle>, StoreError> {
        let connection = self.connect()?;
        let Some(row) = load_bundle_row(&connection, family_id, bundle_id)? else {
            return Ok(None);
        };
        let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
        let required = required_live_media_uuids(&media);
        Ok(Some(StoredBundle {
            media,
            required_media: required,
            status: row.status,
            staged_membership_id: row.staged_membership_id,
        }))
    }

    /// Record that staged bytes for a manifest media UUID are durable.
    pub fn mark_bundle_media_staged(
        &self,
        principal: &Principal,
        bundle_id: &str,
        media_uuid: &str,
        staged_byte_size: usize,
        now: i64,
    ) -> Result<BundleStageStatus, StoreError> {
        let family_id = &principal.family_id;
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let row = load_bundle_row(&transaction, family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)?;
        if row.status == "committed" {
            return Err(StoreError::BundleMediaUploadClosed);
        }
        match row.staged_membership_id.as_deref() {
            Some(staged_membership_id)
                if staged_membership_id == principal.membership_id.as_str() => {}
            Some(_) => return Err(StoreError::BundleMembershipMismatch),
            None => return Err(StoreError::LegacyBundleMembershipUnknown),
        }
        let updated = transaction.execute(
            "
            UPDATE sync_bundle_media
            SET staged_byte_size = ?1, staged_at = ?2
            WHERE family_id = ?3 AND bundle_id = ?4 AND media_uuid = ?5
            ",
            params![
                staged_byte_size as i64,
                now,
                family_id,
                bundle_id,
                media_uuid
            ],
        )?;
        if updated == 0 {
            return Err(StoreError::BundleMediaNotInManifest);
        }
        // Size must match declared byte_size for live media.
        let declared: Option<i64> = transaction.query_row(
            "
            SELECT declared_byte_size FROM sync_bundle_media
            WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
            ",
            params![family_id, bundle_id, media_uuid],
            |r| r.get(0),
        )?;
        if let Some(declared) = declared {
            if declared != staged_byte_size as i64 {
                // Clear the bad staging mark so commit still sees it missing.
                transaction.execute(
                    "
                    UPDATE sync_bundle_media
                    SET staged_byte_size = NULL, staged_at = NULL
                    WHERE family_id = ?1 AND bundle_id = ?2 AND media_uuid = ?3
                    ",
                    params![family_id, bundle_id, media_uuid],
                )?;
                transaction.commit()?;
                return Err(StoreError::BundleMediaIncomplete);
            }
        }
        transaction.commit()?;
        self.secure_database_files()?;
        self.bundle_status(family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)
    }

    /// Publish a complete package in one transaction. Idempotent after success.
    ///
    /// `media_ready` maps media_uuid → whether durable staged bytes match declared size.
    /// Caller installs final media files after a successful first commit (or re-installs
    /// on idempotent retry).
    pub fn commit_bundle(
        &self,
        principal: &Principal,
        bundle_id: &str,
        media_ready: &BTreeMap<String, bool>,
        max_updated_at: i64,
        now: i64,
    ) -> Result<(BundleCommitResult, Vec<Entity>), StoreError> {
        let Principal {
            family_id,
            role,
            device_id,
            membership_id,
        } = principal;
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let row = load_bundle_row(&transaction, family_id, bundle_id)?
            .ok_or(StoreError::BundleNotFound)?;

        match row.staged_membership_id.as_deref() {
            Some(staged_membership_id) if staged_membership_id == membership_id => {}
            Some(_) => return Err(StoreError::BundleMembershipMismatch),
            None if row.status == "staging" => {
                return Err(StoreError::LegacyBundleMembershipUnknown)
            }
            // A legacy committed row has already published immutable content. Keep
            // lost-response retries compatible because no author can change now.
            None => {}
        }

        if row.status == "committed" {
            let cursor = row.committed_cursor.unwrap_or(0);
            let applied = row.committed_applied.unwrap_or(0) as usize;
            let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
            let root = Entity {
                entity_type: row.root_type,
                client_uuid: row.root_client_uuid,
                updated_at: row.root_updated_at,
                deleted_at: row.root_deleted_at,
                payload: parse_payload(&row.root_payload_json)?,
            };
            let record_authors = if root.entity_type == "record" {
                load_persisted_record_author(&transaction, family_id, root.client_uuid.as_str())?
                    .or_else(|| record_author_acknowledgement(&root.client_uuid, &root.payload))
                    .into_iter()
                    .collect()
            } else {
                Vec::new()
            };
            let mut package = vec![root];
            package.extend(media);
            return Ok((
                BundleCommitResult {
                    bundle_id: bundle_id.to_owned(),
                    status: "committed".to_owned(),
                    applied,
                    cursor,
                    record_authors,
                },
                package,
            ));
        }

        let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
        let root = Entity {
            entity_type: row.root_type.clone(),
            client_uuid: row.root_client_uuid.clone(),
            updated_at: row.root_updated_at,
            deleted_at: row.root_deleted_at,
            payload: parse_payload(&row.root_payload_json)?,
        };

        // All live media must have ready bytes before any publish.
        for entity in &media {
            if entity.deleted_at.is_some() {
                continue;
            }
            if !media_ready
                .get(&entity.client_uuid)
                .copied()
                .unwrap_or(false)
            {
                return Err(StoreError::BundleMediaIncomplete);
            }
        }

        if root.updated_at > max_updated_at
            || media
                .iter()
                .any(|entity| entity.updated_at > max_updated_at)
        {
            return Err(StoreError::TimestampOutOfRange);
        }

        // Reject stale packages that would not update the published root (except
        // identical re-publish of a never-committed package that lost the race
        // to an equal/newer legacy write of the same root).
        let root_key = entity_key(&root);
        let existing_root =
            load_existing_entities(&transaction, family_id, &BTreeSet::from([root_key.clone()]))?;
        if let Some(published) = existing_root.get(&root_key) {
            if published.updated_at > root.updated_at {
                return Err(StoreError::BundleRootNotNewer);
            }
            // Equal updated_at: allow commit so lost-response retries and
            // same-version media repair can finish; LWW will skip the root row.
        }

        let mut package = Vec::with_capacity(1 + media.len());
        package.push(root);
        package.extend(media.iter().cloned());

        let original_count = package.len();
        let incoming_keys = package.iter().map(entity_key).collect::<BTreeSet<_>>();
        let mut existing = load_existing_entities(&transaction, family_id, &incoming_keys)?;
        canonicalize_equal_lww_bundle_root(&mut package, &existing);
        canonicalize_record_authors(device_id, membership_id, &mut package, &existing);
        let canonical_root = package
            .iter()
            .find(|entity| entity.entity_type != "media")
            .ok_or(StoreError::InvalidStoredPayload)?;
        let record_authors = if canonical_root.entity_type == "record" {
            record_author_acknowledgement(&canonical_root.client_uuid, &canonical_root.payload)
                .into_iter()
                .collect()
        } else {
            Vec::new()
        };
        let canonical_media = package
            .iter()
            .filter(|entity| entity.entity_type == "media")
            .cloned()
            .collect::<Vec<_>>();
        let canonical_root_payload_json = serde_json::to_string(&canonical_root.payload)?;
        let canonical_media_entities_json = serde_json::to_string(&canonical_media)?;
        let canonical_content_hash = bundle_content_hash(canonical_root, &canonical_media)?;
        let mut effective = effective_lww_winners(package.clone(), &existing);
        // Re-stamp/authorize CarePlan winners on commit so a staged package cannot
        // bypass ACL after membership role changes.
        let equivalent_memberships = equivalent_membership_ids(&transaction, membership_id)?;
        stamp_and_authorize_care_plans(
            role,
            membership_id,
            &equivalent_memberships,
            &mut effective,
            &existing,
        )?;
        for entity in &effective {
            let payload_bytes = serde_json::to_vec(&entity.payload)?.len();
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
        // Intra-package references: treat full package (not only LWW winners) as present.
        for entity in &package {
            existing
                .entry(entity_key(entity))
                .or_insert_with(|| ExistingEntity {
                    updated_at: entity.updated_at,
                    deleted_at: entity.deleted_at,
                    payload: entity.payload.clone(),
                });
        }
        validate_push(role, &effective, &existing)?;

        let mut cursor: i64 = transaction.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![family_id],
            |row| row.get(0),
        )?;
        effective.sort_by_key(|entity| match entity.entity_type.as_str() {
            "baby" => 0,
            "record" | "care_plan" => 1,
            "fulfillment_candidate" => 2,
            _ => 3,
        });
        let entity_count = effective.len();
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
        transaction.execute(
            "
            UPDATE sync_bundles
            SET status = 'committed',
                committed_at = ?1,
                committed_cursor = ?2,
                committed_applied = ?3,
                root_deleted_at = ?4,
                root_payload_json = ?5,
                media_entities_json = ?6,
                content_hash = ?7
            WHERE family_id = ?8 AND bundle_id = ?9
            ",
            params![
                now,
                cursor,
                entity_count as i64,
                canonical_root.deleted_at,
                canonical_root_payload_json,
                canonical_media_entities_json,
                canonical_content_hash,
                family_id,
                bundle_id
            ],
        )?;
        transaction.commit()?;
        self.secure_database_files()?;
        let _ = original_count;
        Ok((
            BundleCommitResult {
                bundle_id: bundle_id.to_owned(),
                status: "committed".to_owned(),
                applied: entity_count,
                cursor,
                record_authors,
            },
            package,
        ))
    }
}

#[derive(Debug, Clone)]
struct BundleRow {
    bundle_id: String,
    staged_membership_id: Option<String>,
    status: String,
    root_type: String,
    root_client_uuid: String,
    root_updated_at: i64,
    root_deleted_at: Option<i64>,
    root_payload_json: String,
    media_entities_json: String,
    content_hash: String,
    committed_cursor: Option<i64>,
    committed_applied: Option<i64>,
}

fn load_bundle_row(
    connection: &Connection,
    family_id: &str,
    bundle_id: &str,
) -> Result<Option<BundleRow>, StoreError> {
    connection
        .query_row(
            "
            SELECT bundle_id, staged_membership_id, status,
                   root_type, root_client_uuid,
                   root_updated_at, root_deleted_at, root_payload_json,
                   media_entities_json, content_hash,
                   committed_cursor, committed_applied
            FROM sync_bundles
            WHERE family_id = ?1 AND bundle_id = ?2
            ",
            params![family_id, bundle_id],
            |row| {
                Ok(BundleRow {
                    bundle_id: row.get(0)?,
                    staged_membership_id: row.get(1)?,
                    status: row.get(2)?,
                    root_type: row.get(3)?,
                    root_client_uuid: row.get(4)?,
                    root_updated_at: row.get(5)?,
                    root_deleted_at: row.get(6)?,
                    root_payload_json: row.get(7)?,
                    media_entities_json: row.get(8)?,
                    content_hash: row.get(9)?,
                    committed_cursor: row.get(10)?,
                    committed_applied: row.get(11)?,
                })
            },
        )
        .optional()
        .map_err(StoreError::from)
}

fn bundle_stage_status_from_row(
    connection: &Connection,
    family_id: &str,
    row: &BundleRow,
) -> Result<BundleStageStatus, StoreError> {
    let media: Vec<Entity> = serde_json::from_str(&row.media_entities_json)?;
    let required = required_live_media_uuids(&media);
    let staged = staged_media_uuids(connection, family_id, &row.bundle_id)?;
    let staged_set = staged.iter().cloned().collect::<BTreeSet<_>>();
    let missing = required
        .into_iter()
        .filter(|id| !staged_set.contains(id))
        .collect::<Vec<_>>();
    Ok(BundleStageStatus {
        bundle_id: row.bundle_id.clone(),
        status: row.status.clone(),
        missing_media: missing,
        staged_media: staged,
    })
}

fn staged_media_uuids(
    connection: &Connection,
    family_id: &str,
    bundle_id: &str,
) -> Result<Vec<String>, StoreError> {
    let mut statement = connection.prepare(
        "
        SELECT media_uuid FROM sync_bundle_media
        WHERE family_id = ?1 AND bundle_id = ?2
          AND staged_byte_size IS NOT NULL
          AND (declared_byte_size IS NULL OR staged_byte_size = declared_byte_size)
        ORDER BY media_uuid
        ",
    )?;
    let rows = statement.query_map(params![family_id, bundle_id], |row| row.get(0))?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(StoreError::from)
}

fn required_live_media_uuids(media: &[Entity]) -> Vec<String> {
    media
        .iter()
        .filter(|entity| entity.deleted_at.is_none())
        .map(|entity| entity.client_uuid.clone())
        .collect()
}

fn bundle_content_hash(root: &Entity, media: &[Entity]) -> Result<String, StoreError> {
    let payload = serde_json::json!({
        "root": root,
        "media": media,
    });
    let bytes = serde_json::to_vec(&payload)?;
    Ok(hex::encode(Sha256::digest(bytes)))
}

/// Rebuild entities table when an older schema lacked care_plan / custom_item /
/// fulfillment_candidate types. Additive CHECK expansion only.
fn ensure_entities_allow_care_plan_and_custom_item(
    connection: &Connection,
) -> Result<(), StoreError> {
    let sql: Option<String> = connection
        .query_row(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'entities'",
            [],
            |row| row.get(0),
        )
        .optional()?;
    let Some(sql) = sql else {
        return Ok(());
    };
    if sql.contains("care_plan")
        && sql.contains("custom_item")
        && sql.contains("fulfillment_candidate")
    {
        return Ok(());
    }
    connection.execute_batch(
        "
        CREATE TABLE entities_new (
            family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
            entity_type TEXT NOT NULL CHECK(entity_type IN ('baby', 'record', 'media', 'care_plan', 'custom_item', 'fulfillment_candidate')),
            client_uuid TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted_at INTEGER,
            payload_json TEXT NOT NULL,
            rev INTEGER NOT NULL,
            PRIMARY KEY (family_id, entity_type, client_uuid)
        );
        INSERT INTO entities_new(
            family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
        )
        SELECT family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
        FROM entities;
        DROP TABLE entities;
        ALTER TABLE entities_new RENAME TO entities;
        CREATE INDEX IF NOT EXISTS entities_family_rev ON entities(family_id, rev);
        ",
    )?;
    Ok(())
}

/// Stamp creator membership on first insert; freeze creator; enforce member-own /
/// owner-all ACL; refuse clearing deleted_at on tombstones.
fn stamp_and_authorize_custom_items(
    role: &str,
    membership_id: &str,
    equivalent_membership_ids: &BTreeSet<String>,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<(), StoreError> {
    for entity in entities.iter_mut() {
        if entity.entity_type != "custom_item" {
            continue;
        }
        let key = ("custom_item".to_owned(), entity.client_uuid.clone());
        let current = existing.get(&key);
        if let Some(current) = current {
            if current.deleted_at.is_some() && entity.deleted_at.is_none() {
                return Err(StoreError::CustomItemTombstoneResurrection);
            }
            let creator = current
                .payload
                .get("created_by_membership_id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            // Creator is immutable after first write.
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(creator.clone()),
            );
            if role != "owner"
                && (creator.is_empty() || !equivalent_membership_ids.contains(&creator))
            {
                return Err(StoreError::ForbiddenCustomItem);
            }
        } else {
            // First insert: server stamps authenticated membership (ignore client guess).
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
        }
    }
    Ok(())
}

/// Record authorship belongs to the authenticated principal, never to client claims.
fn canonicalize_record_authors(
    device_id: &str,
    membership_id: &str,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) {
    for entity in entities.iter_mut() {
        if entity.entity_type != "record" {
            continue;
        }
        let key = ("record".to_owned(), entity.client_uuid.clone());
        if let Some(current) = existing.get(&key) {
            for field in ["created_by_device_id", "created_by_membership_id"] {
                if let Some(value) = current.payload.get(field).cloned() {
                    entity.payload.insert(field.to_owned(), value);
                } else {
                    entity.payload.remove(field);
                }
            }
        } else {
            entity.payload.insert(
                "created_by_device_id".to_owned(),
                Value::String(device_id.to_owned()),
            );
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
        }
    }
}

fn record_author_acknowledgements(
    requested_record_ids: &BTreeSet<String>,
    effective: &[Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Vec<RecordAuthor> {
    requested_record_ids
        .iter()
        .filter_map(|client_uuid| {
            let payload = effective
                .iter()
                .find(|entity| entity.entity_type == "record" && entity.client_uuid == *client_uuid)
                .map(|entity| &entity.payload)
                .or_else(|| {
                    existing
                        .get(&("record".to_owned(), client_uuid.clone()))
                        .map(|entity| &entity.payload)
                })?;
            record_author_acknowledgement(client_uuid, payload)
        })
        .collect()
}

fn record_author_acknowledgement(
    client_uuid: &str,
    payload: &Map<String, Value>,
) -> Option<RecordAuthor> {
    let created_by_membership_id = payload
        .get("created_by_membership_id")
        .and_then(Value::as_str)
        .filter(|membership_id| !membership_id.is_empty())?
        .to_owned();
    Some(RecordAuthor {
        client_uuid: client_uuid.to_owned(),
        created_by_membership_id,
    })
}

fn load_persisted_record_author(
    connection: &Connection,
    family_id: &str,
    client_uuid: &str,
) -> Result<Option<RecordAuthor>, StoreError> {
    let payload_json = connection
        .query_row(
            "
            SELECT payload_json
            FROM entities
            WHERE family_id = ?1
              AND entity_type = 'record'
              AND client_uuid = ?2
            ",
            params![family_id, client_uuid],
            |row| row.get::<_, String>(0),
        )
        .optional()?;
    payload_json
        .map(|payload_json| {
            let payload = parse_payload(&payload_json)?;
            Ok(record_author_acknowledgement(client_uuid, &payload))
        })
        .transpose()
        .map(Option::flatten)
}

/// Equal `updated_at` keeps the already-published LWW winner. Atomic staging and
/// commit must hash and persist that exact root rather than a losing same-version
/// payload, otherwise committed bundle retries diverge from ordinary pull.
fn canonicalize_equal_lww_bundle_root(
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) {
    for entity in entities.iter_mut() {
        if entity.entity_type != "record" && entity.entity_type != "care_plan" {
            continue;
        }
        let key = entity_key(entity);
        let Some(current) = existing.get(&key) else {
            continue;
        };
        if current.updated_at == entity.updated_at {
            entity.deleted_at = current.deleted_at;
            entity.payload = current.payload.clone();
        }
    }
}

/// CarePlan ACL mirrors custom items: any member may create; only creator or
/// owner may edit/skip/delete. Creator membership is server-stamped and frozen.
/// Left authors keep their membership_id; ordinary members do not gain rights.
fn stamp_and_authorize_care_plans(
    role: &str,
    membership_id: &str,
    equivalent_membership_ids: &BTreeSet<String>,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<(), StoreError> {
    for entity in entities.iter_mut() {
        if entity.entity_type != "care_plan" {
            continue;
        }
        let key = ("care_plan".to_owned(), entity.client_uuid.clone());
        let current = existing.get(&key);
        if let Some(current) = current {
            if current.deleted_at.is_some() && entity.deleted_at.is_none() {
                return Err(StoreError::CarePlanTombstoneResurrection);
            }
            let creator = current
                .payload
                .get("created_by_membership_id")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_owned();
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(creator.clone()),
            );
            // Manage ACL (edit/skip/delete/status): creator or owner only.
            // Fulfillment is not a care_plan rewrite path here (separate candidate).
            if role != "owner"
                && (creator.is_empty() || !equivalent_membership_ids.contains(&creator))
            {
                return Err(StoreError::ForbiddenCarePlan);
            }
        } else {
            entity.payload.insert(
                "created_by_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
        }
    }
    Ok(())
}

/// Any active member may submit a fulfillment candidate for any plan. Server
/// freezes submitter membership, role, and confirmed_at; clients cannot forge.
fn stamp_fulfillment_candidates(
    role: &str,
    membership_id: &str,
    confirmed_at: i64,
    entities: &mut [Entity],
    existing: &HashMap<EntityKey, ExistingEntity>,
) -> Result<(), StoreError> {
    // Any active principal may submit; role is frozen only as conflict evidence.
    for entity in entities.iter_mut() {
        if entity.entity_type != "fulfillment_candidate" {
            continue;
        }
        let key = (
            "fulfillment_candidate".to_owned(),
            entity.client_uuid.clone(),
        );
        if let Some(current) = existing.get(&key) {
            // Freeze submitter evidence after first write.
            let submitter = current
                .payload
                .get("submitter_membership_id")
                .and_then(Value::as_str)
                .unwrap_or(membership_id)
                .to_owned();
            let submitter_role = current
                .payload
                .get("submitter_role")
                .and_then(Value::as_str)
                .unwrap_or(role)
                .to_owned();
            let frozen_at = current
                .payload
                .get("confirmed_at")
                .and_then(Value::as_i64)
                .unwrap_or(confirmed_at);
            entity.payload.insert(
                "submitter_membership_id".to_owned(),
                Value::String(submitter),
            );
            entity
                .payload
                .insert("submitter_role".to_owned(), Value::String(submitter_role));
            entity
                .payload
                .insert("confirmed_at".to_owned(), Value::Number(frozen_at.into()));
        } else {
            entity.payload.insert(
                "submitter_membership_id".to_owned(),
                Value::String(membership_id.to_owned()),
            );
            entity
                .payload
                .insert("submitter_role".to_owned(), Value::String(role.to_owned()));
            entity.payload.insert(
                "confirmed_at".to_owned(),
                Value::Number(confirmed_at.into()),
            );
        }
    }
    Ok(())
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
            "care_plan" => {
                append_pull_dependency(
                    connection,
                    family_id,
                    cursor,
                    "baby",
                    required_payload_reference(&entity.payload, "baby_client_uuid")?,
                    included_keys,
                    group_keys,
                    group,
                )?;
                // Completed plans co-gate on the fulfill record at the client.
                // Pull the record (and its deps/media recursively) in the same
                // group so pages do not stall with unresolved completed plans.
                if let Some(record_id) = entity
                    .payload
                    .get("fulfilled_record_client_uuid")
                    .and_then(Value::as_str)
                {
                    if !record_id.is_empty() {
                        append_pull_dependency(
                            connection,
                            family_id,
                            cursor,
                            "record",
                            record_id,
                            included_keys,
                            group_keys,
                            group,
                        )?;
                    }
                }
            }
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
                Some("log") => {
                    if let Some(care_plan_id) = entity
                        .payload
                        .get("care_plan_client_uuid")
                        .and_then(Value::as_str)
                    {
                        append_pull_dependency(
                            connection,
                            family_id,
                            cursor,
                            "care_plan",
                            care_plan_id,
                            included_keys,
                            group_keys,
                            group,
                        )?;
                    } else {
                        append_pull_dependency(
                            connection,
                            family_id,
                            cursor,
                            "record",
                            required_payload_reference(&entity.payload, "record_client_uuid")?,
                            included_keys,
                            group_keys,
                            group,
                        )?;
                    }
                }
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

fn equivalent_membership_ids(
    transaction: &Transaction<'_>,
    canonical_membership_id: &str,
) -> Result<BTreeSet<String>, StoreError> {
    let mut membership_ids = BTreeSet::from([canonical_membership_id.to_owned()]);
    let mut statement = transaction.prepare(
        "
        SELECT alias_membership_id
        FROM membership_aliases
        WHERE canonical_membership_id = ?1
        ",
    )?;
    for alias in statement.query_map(params![canonical_membership_id], |row| {
        row.get::<_, String>(0)
    })? {
        membership_ids.insert(alias?);
    }
    Ok(membership_ids)
}

#[derive(Debug)]
struct LegacyMembership {
    token_hash: String,
    family_id: String,
    role: String,
    device_id: String,
    display_name: Option<String>,
    revoked_at: Option<i64>,
    membership_id: Option<String>,
}

/// Transactionally separates persistent memberships from bearer credentials.
///
/// Historical active rows are grouped once by `(family_id, role, device_id)`.
/// The row with the lexicographically smallest token hash supplies the first
/// existing membership id; if it has none, the first later existing id wins,
/// otherwise a UUID is minted. Display name uses the first render-safe,
/// non-placeholder value in that same stable order, falling back to the first
/// raw non-null value when every historical name is unsafe. Other historical
/// ids are retained as aliases.
fn migrate_legacy_memberships(connection: &mut Connection) -> Result<(), StoreError> {
    let legacy_columns = table_columns(connection, "memberships")?;
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    if !legacy_columns.contains("membership_id") {
        transaction.execute("ALTER TABLE memberships ADD COLUMN membership_id TEXT", [])?;
    }
    transaction.execute_batch(
        "
        DROP INDEX IF EXISTS memberships_family;
        DROP INDEX IF EXISTS memberships_membership_id;
        ALTER TABLE memberships RENAME TO memberships_legacy;

        CREATE TABLE memberships (
            membership_id TEXT PRIMARY KEY,
            family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
            role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
            device_id TEXT NOT NULL,
            display_name TEXT,
            left_at INTEGER
        );
        CREATE TABLE membership_credentials (
            token_hash TEXT PRIMARY KEY,
            membership_id TEXT NOT NULL
                REFERENCES memberships(membership_id) ON DELETE CASCADE,
            revoked_at INTEGER
        );
        CREATE TABLE membership_aliases (
            alias_membership_id TEXT PRIMARY KEY,
            canonical_membership_id TEXT NOT NULL
                REFERENCES memberships(membership_id) ON DELETE CASCADE
        );
        ",
    )?;

    let rows = {
        let mut statement = transaction.prepare(
            "
            SELECT token_hash, family_id, role, device_id, display_name,
                   revoked_at, membership_id
            FROM memberships_legacy
            ORDER BY family_id COLLATE BINARY, role COLLATE BINARY,
                     device_id COLLATE BINARY, token_hash COLLATE BINARY
            ",
        )?;
        let rows = statement
            .query_map([], |row| {
                Ok(LegacyMembership {
                    token_hash: row.get(0)?,
                    family_id: row.get(1)?,
                    role: row.get(2)?,
                    device_id: row.get(3)?,
                    display_name: row.get(4)?,
                    revoked_at: row.get(5)?,
                    membership_id: row.get(6)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;
        rows
    };

    let mut active_groups = BTreeMap::<(String, String, String), Vec<LegacyMembership>>::new();
    let mut revoked_rows = Vec::new();
    for row in rows {
        if row.revoked_at.is_some() {
            revoked_rows.push(row);
        } else {
            active_groups
                .entry((
                    row.family_id.clone(),
                    row.role.clone(),
                    row.device_id.clone(),
                ))
                .or_default()
                .push(row);
        }
    }

    for ((family_id, role, device_id), rows) in active_groups {
        let canonical_membership_id = rows
            .iter()
            .find_map(|row| {
                row.membership_id
                    .as_deref()
                    .filter(|value| !value.is_empty())
            })
            .map(str::to_owned)
            .unwrap_or_else(|| Uuid::new_v4().to_string());
        let display_name = rows
            .iter()
            .filter_map(|row| {
                crate::model::normalize_display_name(row.display_name.as_deref())
                    .ok()
                    .flatten()
            })
            .find(|name| name != crate::model::LOCAL_DEVICE_DISPLAY_NAME)
            .or_else(|| rows.iter().find_map(|row| row.display_name.clone()));
        transaction.execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, device_id, display_name
            ) VALUES (?1, ?2, ?3, ?4, ?5)
            ",
            params![
                canonical_membership_id,
                family_id,
                role,
                device_id,
                display_name
            ],
        )?;
        for row in rows {
            transaction.execute(
                "
                INSERT INTO membership_credentials(token_hash, membership_id)
                VALUES (?1, ?2)
                ",
                params![row.token_hash, canonical_membership_id],
            )?;
            if let Some(alias) = row
                .membership_id
                .filter(|alias| !alias.is_empty() && alias != &canonical_membership_id)
            {
                transaction.execute(
                    "
                    INSERT INTO membership_aliases(
                        alias_membership_id, canonical_membership_id
                    ) VALUES (?1, ?2)
                    ",
                    params![alias, canonical_membership_id],
                )?;
            }
        }
    }

    // Revoked legacy rows were not active duplicates and therefore remain
    // separate historical memberships with their original credential state.
    for row in revoked_rows {
        let membership_id = row
            .membership_id
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| Uuid::new_v4().to_string());
        transaction.execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, device_id, display_name, left_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
            ",
            params![
                membership_id,
                row.family_id,
                row.role,
                row.device_id,
                row.display_name,
                row.revoked_at
            ],
        )?;
        transaction.execute(
            "
            INSERT INTO membership_credentials(token_hash, membership_id, revoked_at)
            VALUES (?1, ?2, ?3)
            ",
            params![row.token_hash, membership_id, row.revoked_at],
        )?;
    }

    transaction.execute_batch(
        "
        DROP TABLE memberships_legacy;
        CREATE INDEX memberships_family ON memberships(family_id);
        CREATE INDEX membership_credentials_membership
            ON membership_credentials(membership_id);
        CREATE INDEX membership_aliases_canonical
            ON membership_aliases(canonical_membership_id);
        ",
    )?;
    transaction.commit()?;
    Ok(())
}

/// Hydrates pre-membership Record authors only when the legacy family/device
/// link resolves to exactly one canonical membership.
///
/// Every changed row receives a fresh family revision so clients that already
/// consumed the legacy version pull the canonical metadata once. Rows with an
/// empty/unknown device or multiple historical memberships remain unknown. The
/// persisted membership field is the idempotency marker for later restarts.
fn hydrate_legacy_record_authors(connection: &mut Connection) -> Result<(), StoreError> {
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let memberships_by_device = {
        let mut statement = transaction.prepare(
            "
            SELECT family_id, device_id, membership_id
            FROM memberships
            ORDER BY family_id COLLATE BINARY,
                     device_id COLLATE BINARY,
                     membership_id COLLATE BINARY
            ",
        )?;
        let rows = statement.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
            ))
        })?;
        let mut grouped = BTreeMap::<(String, String), BTreeSet<String>>::new();
        for row in rows {
            let (family_id, device_id, membership_id) = row?;
            if device_id.is_empty() || membership_id.is_empty() {
                continue;
            }
            grouped
                .entry((family_id, device_id))
                .or_default()
                .insert(membership_id);
        }
        grouped
            .into_iter()
            .filter_map(|(key, memberships)| {
                (memberships.len() == 1)
                    .then(|| (key, memberships.into_iter().next().expect("one membership")))
            })
            .collect::<BTreeMap<_, _>>()
    };

    let legacy_records = {
        let mut statement = transaction.prepare(
            "
            SELECT family_id, client_uuid, payload_json
            FROM entities
            WHERE entity_type = 'record'
            ORDER BY family_id COLLATE BINARY, rev, client_uuid COLLATE BINARY
            ",
        )?;
        let records = statement
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        records
    };

    for (family_id, client_uuid, payload_json) in legacy_records {
        let mut payload = parse_payload(&payload_json)?;
        let has_membership_author = payload
            .get("created_by_membership_id")
            .and_then(Value::as_str)
            .is_some_and(|membership_id| !membership_id.is_empty());
        if has_membership_author {
            continue;
        }
        let Some(device_id) = payload
            .get("created_by_device_id")
            .and_then(Value::as_str)
            .filter(|device_id| !device_id.is_empty())
        else {
            continue;
        };
        let Some(membership_id) =
            memberships_by_device.get(&(family_id.clone(), device_id.to_owned()))
        else {
            continue;
        };
        payload.insert(
            "created_by_membership_id".to_owned(),
            Value::String(membership_id.clone()),
        );
        transaction.execute(
            "UPDATE family_meta SET rev = rev + 1 WHERE family_id = ?1",
            params![family_id],
        )?;
        let rev: i64 = transaction.query_row(
            "SELECT rev FROM family_meta WHERE family_id = ?1",
            params![family_id],
            |row| row.get(0),
        )?;
        transaction.execute(
            "
            UPDATE entities
            SET payload_json = ?1, rev = ?2
            WHERE family_id = ?3
              AND entity_type = 'record'
              AND client_uuid = ?4
            ",
            params![
                serde_json::to_string(&payload)?,
                rev,
                family_id,
                client_uuid
            ],
        )?;
    }
    transaction.commit()?;
    Ok(())
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
            "record" | "care_plan" => {
                if let Some(id) = entity.payload["baby_client_uuid"].as_str() {
                    references.insert(("baby".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity
                    .payload
                    .get("custom_item_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("custom_item".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity
                    .payload
                    .get("fulfilled_record_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
            }
            "fulfillment_candidate" => {
                if let Some(id) = entity.payload["care_plan_client_uuid"].as_str() {
                    references.insert(("care_plan".to_owned(), id.to_owned()));
                }
                if let Some(id) = entity.payload["record_client_uuid"].as_str() {
                    references.insert(("record".to_owned(), id.to_owned()));
                }
            }
            "media" => {
                if entity.payload["kind"] == "avatar" {
                    if let Some(id) = entity.payload["baby_client_uuid"].as_str() {
                        references.insert(("baby".to_owned(), id.to_owned()));
                    }
                } else if let Some(id) = entity
                    .payload
                    .get("care_plan_client_uuid")
                    .and_then(Value::as_str)
                {
                    references.insert(("care_plan".to_owned(), id.to_owned()));
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

    let mut effective_care_plans = existing
        .iter()
        .filter(|((entity_type, _), _)| entity_type == "care_plan")
        .map(|((_, id), entity)| (id.clone(), entity.payload.clone()))
        .collect::<HashMap<_, _>>();
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "care_plan")
    {
        let baby_id = entity.payload["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !baby_ids.contains(baby_id) {
            return Err(StoreError::UnresolvedReference(
                "care_plan baby_client_uuid does not exist".to_owned(),
            ));
        }
        effective_care_plans.insert(entity.client_uuid.clone(), entity.payload.clone());
    }
    let care_plan_ids = effective_care_plans
        .keys()
        .cloned()
        .collect::<BTreeSet<_>>();

    // Fulfillment candidates must reference live care_plan + record in-family.
    for entity in entities
        .iter()
        .filter(|entity| entity.entity_type == "fulfillment_candidate")
    {
        let plan_id = entity.payload["care_plan_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !care_plan_ids.contains(plan_id) {
            return Err(StoreError::UnresolvedReference(
                "fulfillment_candidate care_plan_client_uuid does not exist".to_owned(),
            ));
        }
        let record_id = entity.payload["record_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if !record_ids.contains(record_id) {
            return Err(StoreError::UnresolvedReference(
                "fulfillment_candidate record_client_uuid does not exist".to_owned(),
            ));
        }
        let plan_baby = effective_care_plans[plan_id]["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        let record_baby = effective_records[record_id]["baby_client_uuid"]
            .as_str()
            .ok_or(StoreError::InvalidStoredPayload)?;
        if plan_baby != record_baby {
            return Err(StoreError::UnresolvedReference(
                "fulfillment_candidate record baby does not match care_plan baby".to_owned(),
            ));
        }
    }

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
        } else if let Some(care_plan_id) = incoming.3.as_ref() {
            if !care_plan_ids.contains(care_plan_id) {
                return Err(StoreError::UnresolvedReference(
                    "log media care_plan_client_uuid does not exist".to_owned(),
                ));
            }
            let plan_baby_id = effective_care_plans[care_plan_id]["baby_client_uuid"]
                .as_str()
                .ok_or(StoreError::InvalidStoredPayload)?;
            if incoming
                .2
                .as_ref()
                .is_some_and(|baby_id| baby_id != plan_baby_id)
            {
                return Err(StoreError::UnresolvedReference(
                    "log media baby does not match care_plan baby".to_owned(),
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
    let care_plan = payload
        .get("care_plan_client_uuid")
        .and_then(Value::as_str)
        .map(str::to_owned);
    Ok((kind, record, baby, care_plan))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tempfile::TempDir;

    #[test]
    fn membership_normalization_supports_schema_without_membership_id() {
        let directory = TempDir::new().unwrap();
        let database_path = directory.path().join("lezi.db");
        let family_id = Uuid::new_v4().to_string();
        let owner_token = "pre-membership-id-owner-token";
        let member_tokens = [
            "pre-membership-id-member-token-a",
            "pre-membership-id-member-token-b",
        ];
        let connection = Connection::open(&database_path).unwrap();
        connection
            .execute_batch(
                "
                PRAGMA foreign_keys = ON;
                CREATE TABLE families (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    create_request_hash TEXT,
                    name TEXT
                );
                CREATE TABLE memberships (
                    token_hash TEXT PRIMARY KEY,
                    family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
                    device_id TEXT NOT NULL,
                    display_name TEXT,
                    revoked_at INTEGER
                );
                ",
            )
            .unwrap();
        connection
            .execute(
                "INSERT INTO families(id, created_at, name) VALUES (?1, 1, '旧家庭')",
                params![family_id],
            )
            .unwrap();
        connection
            .execute(
                "
                INSERT INTO memberships(
                    token_hash, family_id, role, device_id, display_name
                ) VALUES (?1, ?2, 'owner', 'legacy-owner-device', '妈妈')
                ",
                params![crate::hash_secret(owner_token), family_id],
            )
            .unwrap();
        for (index, token) in member_tokens.iter().enumerate() {
            connection
                .execute(
                    "
                    INSERT INTO memberships(
                        token_hash, family_id, role, device_id, display_name
                    ) VALUES (?1, ?2, 'member', 'legacy-member-device', ?3)
                    ",
                    params![
                        crate::hash_secret(token),
                        family_id,
                        if index == 0 { "" } else { "家人" }
                    ],
                )
                .unwrap();
        }
        drop(connection);

        let first = Store::open(&database_path).unwrap();
        let owner = first.authenticate(owner_token).unwrap().unwrap();
        let member_a = first.authenticate(member_tokens[0]).unwrap().unwrap();
        let member_b = first.authenticate(member_tokens[1]).unwrap().unwrap();
        assert_eq!(owner.family_id, family_id);
        assert_eq!(owner.role, "owner");
        assert_eq!(member_a.role, "member");
        assert_eq!(member_a.membership_id, member_b.membership_id);
        assert_ne!(owner.membership_id, member_a.membership_id);
        let original_owner_id = owner.membership_id;
        let original_member_id = member_a.membership_id;
        let active = first.active_memberships(&family_id).unwrap();
        assert_eq!(active.len(), 2);
        assert_eq!(active[0].display_name.as_deref(), Some("妈妈"));
        assert_eq!(active[1].display_name.as_deref(), Some("家人"));
        drop(first);

        let restarted = Store::open(&database_path).unwrap();
        assert_eq!(
            restarted
                .authenticate(owner_token)
                .unwrap()
                .unwrap()
                .membership_id,
            original_owner_id
        );
        for token in member_tokens {
            assert_eq!(
                restarted
                    .authenticate(token)
                    .unwrap()
                    .unwrap()
                    .membership_id,
                original_member_id
            );
        }
        drop(restarted);

        let connection = Connection::open(database_path).unwrap();
        assert_eq!(
            connection
                .query_row(
                    "SELECT name FROM families WHERE id = ?1",
                    params![family_id],
                    |row| row.get::<_, String>(0),
                )
                .unwrap(),
            "旧家庭"
        );
        assert!(!table_columns(&connection, "memberships")
            .unwrap()
            .contains("token_hash"));
        assert_eq!(
            connection
                .query_row("SELECT COUNT(*) FROM memberships", [], |row| {
                    row.get::<_, i64>(0)
                })
                .unwrap(),
            2
        );
        assert_eq!(
            connection
                .query_row("SELECT COUNT(*) FROM membership_credentials", [], |row| {
                    row.get::<_, i64>(0)
                })
                .unwrap(),
            3
        );
        assert_eq!(
            connection
                .query_row("SELECT COUNT(*) FROM pragma_foreign_key_check", [], |row| {
                    row.get::<_, i64>(0)
                })
                .unwrap(),
            0
        );
    }

    #[test]
    fn membership_normalization_failure_rolls_back_the_legacy_schema() {
        let directory = TempDir::new().unwrap();
        let database_path = directory.path().join("lezi.db");
        let family_id = Uuid::new_v4().to_string();
        let duplicate_membership_id = Uuid::new_v4().to_string();
        let connection = Connection::open(&database_path).unwrap();
        connection
            .execute_batch(
                "
                PRAGMA foreign_keys = ON;
                CREATE TABLE families (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL
                );
                CREATE TABLE memberships (
                    token_hash TEXT PRIMARY KEY,
                    family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
                    device_id TEXT NOT NULL,
                    display_name TEXT,
                    revoked_at INTEGER,
                    membership_id TEXT
                );
                ",
            )
            .unwrap();
        connection
            .execute(
                "INSERT INTO families(id, created_at) VALUES (?1, 1)",
                params![family_id],
            )
            .unwrap();
        for (token_hash, device_id) in [
            ("rollback-token-a", "rollback-device-a"),
            ("rollback-token-b", "rollback-device-b"),
        ] {
            connection
                .execute(
                    "
                    INSERT INTO memberships(
                        token_hash, family_id, role, device_id, display_name, membership_id
                    ) VALUES (?1, ?2, 'member', ?3, '成员', ?4)
                    ",
                    params![token_hash, family_id, device_id, duplicate_membership_id],
                )
                .unwrap();
        }
        drop(connection);

        assert!(Store::open(&database_path).is_err());

        let connection = Connection::open(database_path).unwrap();
        let tables = connection
            .prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
            .unwrap()
            .query_map([], |row| row.get::<_, String>(0))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert!(tables.iter().any(|table| table == "memberships"));
        assert!(!tables.iter().any(|table| table == "memberships_legacy"));
        assert!(!tables.iter().any(|table| table == "membership_credentials"));
        assert!(table_columns(&connection, "memberships")
            .unwrap()
            .contains("token_hash"));
        assert_eq!(
            connection
                .query_row("SELECT COUNT(*) FROM memberships", [], |row| {
                    row.get::<_, i64>(0)
                })
                .unwrap(),
            2
        );
    }

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

    fn owner_principal(family_id: &str) -> Principal {
        Principal {
            family_id: family_id.to_owned(),
            role: "owner".to_owned(),
            device_id: "owner-device".to_owned(),
            membership_id: "m-owner".to_owned(),
        }
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
                    &owner_principal(&family_id),
                    vec![
                        entity("record", record_id, 1, record),
                        entity("baby", baby_id, 1, baby.clone()),
                    ],
                    10,
                    1_700_000_000_000,
                )
                .unwrap()
                .applied,
            2
        );
        assert_eq!(
            store
                .push(
                    &owner_principal(&family_id),
                    vec![entity("baby", baby_id, 1, baby)],
                    10,
                    1_700_000_000_000,
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
            &owner_principal(&family_id),
            vec![entity("baby", Uuid::new_v4(), 11, baby)],
            10,
            1_700_000_000_000,
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
        store
            .push(
                &owner_principal(&family_id),
                entities,
                100,
                1_700_000_000_000,
            )
            .unwrap();

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
                &owner_principal(&family_id),
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
                1_700_000_000_000,
            )
            .unwrap();
        let record_id = Uuid::new_v4();
        let result = store.push(
            &owner_principal(&family_id),
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
            1_700_000_000_000,
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
