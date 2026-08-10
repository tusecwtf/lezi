//! Fresh-current SQLite schema contract and store open/initialize.
//!
//! Offline `offline_migrate/` stays outside this package; schema shape and
//! `user_version` are the only coupling surface (`CURRENT_SCHEMA_SQL`,
//! `DATABASE_SCHEMA_VERSION`).

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use rusqlite::{Connection, TransactionBehavior};

use super::{CausalAdmissionConfig, Store, StoreError};
use crate::rate_limit::{RateLimitConfig, RateLimiter};

/// Current SQLite `PRAGMA user_version` / schema contract version.
/// Offline migration inventory couples to this constant (must not drift).
///
/// v12 adds immutable causal versions, mutation receipts, conflicts/branches,
/// resolutions, and source-relation storage. Runtime still opens only exact
/// current shape (no in-place upgrade). v11→v12 is offline copy-out only.
pub(crate) const DATABASE_SCHEMA_VERSION: i64 = 12;

/// Mutable atomic roots that participate in the causal version graph.
/// Single source of truth for versioned types; must match CHECK fragments in
/// [`CURRENT_SCHEMA_SQL`] (`VERSIONED_ENTITY_TYPES_SQL`).
pub(crate) const VERSIONED_ENTITY_TYPES: &[&str] = &[
    "baby",
    "record",
    "care_plan",
    "custom_item",
    "wake_observation",
];

/// SQL `IN (...)` list body for versioned entity types (kept next to the const).
/// Referenced by schema shape tests so CHECK fragments cannot drift silently.
#[cfg_attr(not(test), allow(dead_code))]
pub(crate) const VERSIONED_ENTITY_TYPES_SQL: &str =
    "'baby', 'record', 'care_plan', 'custom_item', 'wake_observation'";

#[cfg(test)]
mod versioned_types_alignment {
    use super::{CURRENT_SCHEMA_SQL, VERSIONED_ENTITY_TYPES, VERSIONED_ENTITY_TYPES_SQL};

    #[test]
    fn versioned_types_sql_matches_const_and_schema_checks() {
        let joined = VERSIONED_ENTITY_TYPES
            .iter()
            .map(|t| format!("'{t}'"))
            .collect::<Vec<_>>()
            .join(", ");
        assert_eq!(joined, VERSIONED_ENTITY_TYPES_SQL);
        // entity_versions / stable_heads / receipts / conflicts all share the fragment.
        assert!(CURRENT_SCHEMA_SQL.contains(VERSIONED_ENTITY_TYPES_SQL));
        assert_eq!(
            CURRENT_SCHEMA_SQL
                .matches(VERSIONED_ENTITY_TYPES_SQL)
                .count(),
            4,
            "expected CHECK fragment on versions, heads, receipts, conflicts"
        );
    }
}

pub(crate) const CURRENT_SCHEMA_SQL: &str = "
    CREATE TABLE families (
        id TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        create_request_hash TEXT,
        name TEXT,
        owner_root_fingerprint TEXT
    );
    CREATE UNIQUE INDEX families_create_request
        ON families(create_request_hash);

    CREATE TABLE memberships (
        membership_id TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
        display_name TEXT NOT NULL,
        display_name_key TEXT NOT NULL,
        left_at INTEGER
    );
    CREATE INDEX memberships_family ON memberships(family_id);
    CREATE UNIQUE INDEX memberships_one_owner
        ON memberships(family_id) WHERE role = 'owner' AND left_at IS NULL;
    CREATE UNIQUE INDEX memberships_active_display_name
        ON memberships(family_id, display_name_key) WHERE left_at IS NULL;

    CREATE TABLE devices (
        device_id TEXT PRIMARY KEY,
        membership_id TEXT NOT NULL
            REFERENCES memberships(membership_id) ON DELETE CASCADE,
        device_name TEXT NOT NULL,
        device_name_key TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('active', 'revoked')),
        created_at INTEGER NOT NULL,
        last_used_at INTEGER NOT NULL
    );
    CREATE INDEX devices_membership ON devices(membership_id);
    CREATE UNIQUE INDEX devices_active_name
        ON devices(membership_id, device_name_key) WHERE status = 'active';

    CREATE TABLE device_sessions (
        session_id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
        access_token_hash TEXT NOT NULL UNIQUE,
        access_expires_at INTEGER NOT NULL,
        refresh_token_hash TEXT NOT NULL UNIQUE,
        refresh_generation INTEGER NOT NULL DEFAULT 0,
        revoked_at INTEGER,
        revoked_reason TEXT
    );
    CREATE INDEX device_sessions_device ON device_sessions(device_id);

    CREATE TABLE refresh_token_history (
        token_hash TEXT PRIMARY KEY,
        session_id TEXT NOT NULL
            REFERENCES device_sessions(session_id) ON DELETE CASCADE,
        used_at INTEGER NOT NULL
    );
    CREATE INDEX refresh_token_history_session
        ON refresh_token_history(session_id);

    CREATE TABLE terminal_credential_denials (
        token_hash TEXT NOT NULL,
        token_kind TEXT NOT NULL CHECK(token_kind IN ('access', 'refresh')),
        reason TEXT NOT NULL CHECK(reason IN ('membership_deleted', 'family_deleted')),
        PRIMARY KEY (token_hash, token_kind)
    );

    CREATE TABLE owner_login_requests (
        request_hash TEXT PRIMARY KEY,
        device_id TEXT NOT NULL UNIQUE REFERENCES devices(device_id) ON DELETE CASCADE,
        device_name TEXT NOT NULL,
        takeover INTEGER NOT NULL CHECK(takeover IN (0, 1))
    );

    CREATE TABLE member_login_requests (
        request_id TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        pending_secret_hash TEXT NOT NULL UNIQUE,
        display_name TEXT NOT NULL,
        display_name_key TEXT NOT NULL,
        device_name TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN (
            'pending', 'approved', 'rejected', 'cancelled', 'expired', 'claimed'
        )),
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        decided_at INTEGER,
        claimed_at INTEGER,
        approval_kind TEXT CHECK(approval_kind IN ('new', 'existing')),
        membership_id TEXT REFERENCES memberships(membership_id) ON DELETE SET NULL,
        device_id TEXT REFERENCES devices(device_id) ON DELETE SET NULL
    );
    CREATE INDEX member_login_requests_family_status
        ON member_login_requests(family_id, status, expires_at);

    CREATE TABLE member_login_grants (
        grant_hash TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        membership_id TEXT NOT NULL REFERENCES memberships(membership_id) ON DELETE CASCADE,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used_at INTEGER,
        claimed_device_id TEXT REFERENCES devices(device_id) ON DELETE SET NULL
    );
    CREATE INDEX member_login_grants_family_expiry
        ON member_login_grants(family_id, expires_at);

    CREATE TABLE member_rename_requests (
        request_id TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        membership_id TEXT NOT NULL
            REFERENCES memberships(membership_id) ON DELETE CASCADE,
        requested_display_name TEXT NOT NULL,
        requested_display_name_key TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN (
            'pending', 'approved', 'rejected', 'cancelled', 'expired'
        )),
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        decided_at INTEGER
    );
    CREATE INDEX member_rename_requests_family_status
        ON member_rename_requests(family_id, status, expires_at);
    CREATE UNIQUE INDEX member_rename_requests_one_pending
        ON member_rename_requests(membership_id) WHERE status = 'pending';

    CREATE TABLE family_meta (
        family_id TEXT PRIMARY KEY REFERENCES families(id) ON DELETE CASCADE,
        rev INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE entities (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        entity_type TEXT NOT NULL CHECK(entity_type IN (
            'baby', 'record', 'media', 'care_plan', 'custom_item',
            'fulfillment_candidate', 'wake_observation'
        )),
        client_uuid TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL,
        rev INTEGER NOT NULL,
        PRIMARY KEY (family_id, entity_type, client_uuid)
    );
    CREATE INDEX entities_family_rev ON entities(family_id, rev);

    CREATE TABLE sync_bundles (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        bundle_id TEXT NOT NULL,
        staged_membership_id TEXT NOT NULL,
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
    CREATE INDEX sync_bundles_family_status
        ON sync_bundles(family_id, status);

    CREATE TABLE sync_bundle_media (
        family_id TEXT NOT NULL,
        bundle_id TEXT NOT NULL,
        media_uuid TEXT NOT NULL,
        declared_byte_size INTEGER,
        staged_byte_size INTEGER,
        staged_sha256 TEXT,
        staged_at INTEGER,
        PRIMARY KEY (family_id, bundle_id, media_uuid),
        FOREIGN KEY (family_id, bundle_id)
            REFERENCES sync_bundles(family_id, bundle_id) ON DELETE CASCADE
    );

    CREATE TABLE media_publications (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        media_uuid TEXT NOT NULL,
        source TEXT NOT NULL
            CHECK(source IN ('ordinary', 'bundle_pending', 'bundle')),
        bundle_id TEXT,
        PRIMARY KEY (family_id, media_uuid)
    );

    -- Causal media bytes are durable preimages until a successful causal
    -- transaction consumes their exact manifest. They never use the published
    -- media path while status is writing/staged/gc_pending.
    CREATE TABLE causal_media_staging (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        membership_id TEXT NOT NULL,
        media_uuid TEXT NOT NULL,
        sha256 TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK(byte_size > 0),
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        status TEXT NOT NULL CHECK(status IN (
            'writing', 'staged', 'consumed', 'gc_pending'
        )),
        consumed_at INTEGER,
        PRIMARY KEY (family_id, media_uuid)
    );
    CREATE INDEX causal_media_staging_quota
        ON causal_media_staging(family_id, membership_id, status, expires_at);

    -- Causal: immutable root versions (stable projection remains `entities`).
    CREATE TABLE entity_versions (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        version_id TEXT NOT NULL,
        entity_type TEXT NOT NULL CHECK(entity_type IN (
            'baby', 'record', 'care_plan', 'custom_item', 'wake_observation'
        )),
        client_uuid TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL,
        content_hash TEXT NOT NULL,
        mutation_id TEXT,
        origin TEXT NOT NULL CHECK(origin IN (
            'migration_base', 'accepted', 'merged', 'branched', 'resolved'
        )),
        created_at INTEGER NOT NULL,
        PRIMARY KEY (family_id, version_id)
    );
    CREATE INDEX entity_versions_root
        ON entity_versions(family_id, entity_type, client_uuid);

    CREATE TABLE entity_version_parents (
        family_id TEXT NOT NULL,
        version_id TEXT NOT NULL,
        parent_version_id TEXT NOT NULL,
        PRIMARY KEY (family_id, version_id, parent_version_id),
        FOREIGN KEY (family_id, version_id)
            REFERENCES entity_versions(family_id, version_id) ON DELETE CASCADE,
        FOREIGN KEY (family_id, parent_version_id)
            REFERENCES entity_versions(family_id, version_id) ON DELETE CASCADE
    );

    CREATE TABLE entity_version_media (
        family_id TEXT NOT NULL,
        version_id TEXT NOT NULL,
        media_uuid TEXT NOT NULL,
        media_payload_json TEXT NOT NULL,
        content_hash TEXT NOT NULL,
        PRIMARY KEY (family_id, version_id, media_uuid),
        FOREIGN KEY (family_id, version_id)
            REFERENCES entity_versions(family_id, version_id) ON DELETE CASCADE
    );

    -- O(1) stable head; ordinary pull does not join full version history.
    CREATE TABLE entity_stable_heads (
        family_id TEXT NOT NULL,
        entity_type TEXT NOT NULL CHECK(entity_type IN (
            'baby', 'record', 'care_plan', 'custom_item', 'wake_observation'
        )),
        client_uuid TEXT NOT NULL,
        version_id TEXT NOT NULL,
        PRIMARY KEY (family_id, entity_type, client_uuid),
        FOREIGN KEY (family_id, entity_type, client_uuid)
            REFERENCES entities(family_id, entity_type, client_uuid) ON DELETE CASCADE,
        FOREIGN KEY (family_id, version_id)
            REFERENCES entity_versions(family_id, version_id)
    );

    -- mutation_id unique at family / principal / atomic-root boundary.
    CREATE TABLE mutation_receipts (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        membership_id TEXT NOT NULL,
        entity_type TEXT NOT NULL CHECK(entity_type IN (
            'baby', 'record', 'care_plan', 'custom_item', 'wake_observation'
        )),
        client_uuid TEXT NOT NULL,
        mutation_id TEXT NOT NULL,
        content_hash TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('accepted', 'merged', 'branched')),
        stable_version_id TEXT,
        branch_version_id TEXT,
        conflict_id TEXT,
        receipt_json TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (family_id, membership_id, entity_type, client_uuid, mutation_id)
    );
    CREATE INDEX mutation_receipts_lookup
        ON mutation_receipts(family_id, membership_id, mutation_id);

    CREATE TABLE conflicts (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        conflict_id TEXT NOT NULL,
        entity_type TEXT NOT NULL CHECK(entity_type IN (
            'baby', 'record', 'care_plan', 'custom_item', 'wake_observation'
        )),
        client_uuid TEXT NOT NULL,
        base_version_id TEXT,
        stable_version_id TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('open', 'resolved')),
        kind TEXT NOT NULL CHECK(kind IN ('concurrent', 'tombstone_restore')),
        created_at INTEGER NOT NULL,
        resolved_at INTEGER,
        PRIMARY KEY (family_id, conflict_id),
        FOREIGN KEY (family_id, stable_version_id)
            REFERENCES entity_versions(family_id, version_id)
    );
    CREATE INDEX conflicts_root
        ON conflicts(family_id, entity_type, client_uuid, status);

    CREATE TABLE conflict_branches (
        family_id TEXT NOT NULL,
        conflict_id TEXT NOT NULL,
        branch_version_id TEXT NOT NULL,
        PRIMARY KEY (family_id, conflict_id, branch_version_id),
        FOREIGN KEY (family_id, conflict_id)
            REFERENCES conflicts(family_id, conflict_id) ON DELETE CASCADE,
        FOREIGN KEY (family_id, branch_version_id)
            REFERENCES entity_versions(family_id, version_id)
    );

    CREATE TABLE conflict_resolutions (
        family_id TEXT NOT NULL,
        conflict_id TEXT NOT NULL,
        resolution_mutation_id TEXT NOT NULL,
        resolver_membership_id TEXT NOT NULL,
        expected_stable_version_id TEXT NOT NULL,
        expected_branch_versions_json TEXT NOT NULL,
        conflict_choices_json TEXT NOT NULL,
        resolved_version_id TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (family_id, conflict_id, resolution_mutation_id),
        FOREIGN KEY (family_id, conflict_id)
            REFERENCES conflicts(family_id, conflict_id) ON DELETE CASCADE,
        FOREIGN KEY (family_id, resolved_version_id)
            REFERENCES entity_versions(family_id, version_id)
    );

    -- Source relations are not ordinary record tombstones (independent reason).
    CREATE TABLE source_relations (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        relation_id TEXT NOT NULL,
        display_client_uuid TEXT NOT NULL,
        media_retained INTEGER NOT NULL CHECK(media_retained = 1),
        reason TEXT NOT NULL CHECK(reason IN (
            'author_declare', 'owner_group_resolve'
        )),
        mutation_id TEXT NOT NULL,
        created_by_membership_id TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (family_id, relation_id)
    );

    CREATE TABLE source_relation_mutation_receipts (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        mutation_id TEXT NOT NULL,
        request_kind TEXT NOT NULL CHECK(request_kind IN (
            'author_declare', 'owner_group_resolve'
        )),
        request_fingerprint TEXT NOT NULL,
        receipt_json TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (family_id, mutation_id)
    );

    CREATE TABLE source_relation_record_eligibility (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        record_client_uuid TEXT NOT NULL,
        baby_client_uuid TEXT NOT NULL,
        record_type TEXT NOT NULL,
        record_timestamp INTEGER NOT NULL,
        author_membership_id TEXT NOT NULL,
        PRIMARY KEY (family_id, record_client_uuid)
    );
    CREATE INDEX source_relation_eligibility_window
        ON source_relation_record_eligibility(
            family_id, baby_client_uuid, record_type,
            record_timestamp, record_client_uuid
        );

    CREATE TABLE source_relation_members (
        family_id TEXT NOT NULL,
        relation_id TEXT NOT NULL,
        record_client_uuid TEXT NOT NULL,
        role TEXT NOT NULL CHECK(role IN ('display', 'source')),
        PRIMARY KEY (family_id, relation_id, record_client_uuid),
        UNIQUE (family_id, record_client_uuid),
        FOREIGN KEY (family_id, relation_id)
            REFERENCES source_relations(family_id, relation_id) ON DELETE CASCADE
    );

    CREATE TABLE source_relation_declarations (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        mutation_id TEXT NOT NULL,
        record_client_uuid TEXT NOT NULL,
        equivalent_to_client_uuid TEXT NOT NULL,
        expected_record_version TEXT NOT NULL,
        expected_other_version TEXT NOT NULL,
        author_membership_id TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('pending', 'consumed', 'superseded')),
        created_at INTEGER NOT NULL,
        PRIMARY KEY (family_id, mutation_id)
    );
";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SchemaState {
    Empty,
    Current,
}

fn normalized_schema_objects(
    connection: &Connection,
) -> Result<BTreeMap<(String, String), String>, StoreError> {
    let mut statement = connection.prepare(
        "
        SELECT type, name, sql
        FROM sqlite_schema
        WHERE type IN ('table', 'index', 'view', 'trigger')
          AND name NOT LIKE 'sqlite_%'
          AND sql IS NOT NULL
        ORDER BY type COLLATE BINARY, name COLLATE BINARY
        ",
    )?;
    let rows = statement.query_map([], |row| {
        let sql: String = row.get(2)?;
        Ok(((row.get(0)?, row.get(1)?), normalize_schema_sql(&sql)))
    })?;
    rows.collect::<Result<BTreeMap<_, _>, _>>()
        .map_err(StoreError::from)
}

fn normalize_schema_sql(sql: &str) -> String {
    sql.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn expected_schema_objects() -> Result<BTreeMap<(String, String), String>, StoreError> {
    let connection = Connection::open_in_memory()?;
    connection.execute_batch(CURRENT_SCHEMA_SQL)?;
    normalized_schema_objects(&connection)
}

fn inspect_schema(connection: &Connection) -> Result<SchemaState, StoreError> {
    let schema_version =
        connection.query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))?;
    let objects = normalized_schema_objects(connection)?;
    if schema_version == 0 && objects.is_empty() {
        return Ok(SchemaState::Empty);
    }
    if schema_version != DATABASE_SCHEMA_VERSION {
        return Err(StoreError::UnsupportedSchemaVersion {
            found: schema_version,
            supported: DATABASE_SCHEMA_VERSION,
        });
    }
    if objects != expected_schema_objects()? {
        return Err(StoreError::IncompatibleSchema {
            supported: DATABASE_SCHEMA_VERSION,
        });
    }
    Ok(SchemaState::Current)
}

impl Store {
    pub fn preflight_existing_schema(database_path: &Path) -> Result<(), StoreError> {
        if !database_path.try_exists()? {
            return Ok(());
        }
        let connection = Connection::open_with_flags(
            database_path,
            rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )?;
        inspect_schema(&connection).map(|_| ())
    }

    #[cfg(test)]
    pub fn open(database_path: impl Into<PathBuf>) -> Result<Self, StoreError> {
        Self::open_configured(
            database_path,
            CausalAdmissionConfig::default(),
            Arc::from(b"lezi-sync-test-snapshot-key".as_slice()),
        )
    }

    pub(crate) fn open_with_snapshot_key(
        database_path: impl Into<PathBuf>,
        snapshot_receipt_key: &[u8],
    ) -> Result<Self, StoreError> {
        Self::open_configured(
            database_path,
            CausalAdmissionConfig::default(),
            Arc::from(snapshot_receipt_key),
        )
    }

    #[cfg(test)]
    pub(crate) fn open_with_causal_admission(
        database_path: impl Into<PathBuf>,
        admission: CausalAdmissionConfig,
    ) -> Result<Self, StoreError> {
        Self::open_configured(
            database_path,
            admission,
            Arc::from(b"lezi-sync-test-snapshot-key".as_slice()),
        )
    }

    fn open_configured(
        database_path: impl Into<PathBuf>,
        admission: CausalAdmissionConfig,
        snapshot_receipt_key: Arc<[u8]>,
    ) -> Result<Self, StoreError> {
        let admission = admission.validate()?;
        let store = Self {
            database_path: database_path.into(),
            snapshot_receipt_key,
            causal_commit_limiter: Arc::new(RateLimiter::new_with_group_limit(
                RateLimitConfig {
                    max_attempts: admission.principal_commit_limit,
                    window_seconds: admission.window_seconds,
                },
                admission.family_commit_limit,
            )),
            max_open_causal_branches_per_root: admission.max_open_branches_per_root,
        };
        Self::preflight_existing_schema(&store.database_path)?;
        if let Some(parent) = store.database_path.parent() {
            fs::create_dir_all(parent)?;
            crate::secure_directory(parent)?;
        }
        store.initialize()?;
        store.secure_database_files()?;
        Ok(store)
    }

    fn initialize(&self) -> Result<(), StoreError> {
        let mut connection = Connection::open(&self.database_path)?;
        connection.busy_timeout(Duration::from_secs(10))?;
        let schema_state = inspect_schema(&connection)?;
        crate::secure_file(&self.database_path)?;
        connection.execute_batch(
            "
        PRAGMA foreign_keys = ON;
        PRAGMA journal_mode = WAL;
        ",
        )?;
        if schema_state == SchemaState::Empty {
            let transaction =
                connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
            transaction.execute_batch(CURRENT_SCHEMA_SQL)?;
            transaction.pragma_update(None, "user_version", DATABASE_SCHEMA_VERSION)?;
            transaction.commit()?;
        }
        self.secure_database_files()?;
        Ok(())
    }
}
