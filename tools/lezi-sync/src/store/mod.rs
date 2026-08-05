//! Crate-private SQLite persistence façade.
//!
//! [`Store`] remains the single transaction owner. Implementation is partitioned
//! into cohesive private modules (`schema`, `identity`, `pull`, `media`,
//! `bundles`) — not a thin-delegation web. Callers keep `crate::store::…` paths.
//!
//! ## Module ownership
//!
//! - [`schema`] — fresh-current schema SQL, `user_version`, open/initialize/preflight
//! - [`identity`] — family/membership/device/session (submodules) and anonymization
//! - [`pull`] — revision pull pages and dependency co-grouping
//! - [`media`] — published media metadata and committed-pending cleanup
//! - [`bundles`] — atomic bundle stage/commit, LWW loaders, and bundle-row helpers
//! - package root — façade types/errors, `Store::{connect,secure_*,health_check,family_ids}`,
//!   shared `parse_payload` / `EntityKey`, and crate re-exports
//!
//! `offline_migrate/` is **not** part of this package.

mod authority_graph;
mod bundles;
mod identity;
mod media;
mod pull;
mod reconciliation;
mod restore;
mod schema;

use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;
use std::time::Duration;

use rusqlite::Connection;
use serde::Serialize;
use serde_json::{Map, Value};
use thiserror::Error;

use crate::model::Entity;

pub(crate) use bundles::bundle_content_hash;
pub(crate) use identity::anonymize_membership_authorship_fields;
pub(crate) use schema::{CURRENT_SCHEMA_SQL, DATABASE_SCHEMA_VERSION};

/// Shared chunk size for IN-list entity/media queries (media + bundle LWW loaders).
pub(in crate::store) const ENTITY_QUERY_CHUNK_SIZE: usize = 400;

#[derive(Debug, Clone)]
pub struct Principal {
    pub family_id: String,
    pub role: String,
    /// Server-minted immutable membership identity (UUID). Safe to expose to clients.
    pub membership_id: String,
    /// Server-minted device identity resolved only from the access credential.
    pub device_id: String,
}

#[derive(Clone, PartialEq, Eq)]
pub struct CreatedDeviceSession {
    pub family_id: String,
    pub membership_id: String,
    pub device_id: String,
    pub session_id: String,
    pub access_token: String,
    pub access_expires_at: i64,
    pub refresh_token: String,
    pub family_name: Option<String>,
}

pub struct CreateFamilyInput<'a> {
    pub now: i64,
    pub create_request_id: &'a str,
    pub display_name: &'a str,
    pub display_name_key: &'a str,
    pub family_name: &'a str,
    pub device_name: &'a str,
    pub owner_root_fingerprint: Option<&'a str>,
}

pub struct CreateMemberLoginRequestInput<'a> {
    pub now: i64,
    pub ttl_seconds: i64,
    pub max_pending: usize,
    pub display_name: &'a str,
    pub display_name_key: &'a str,
    pub device_name: &'a str,
    pub pending_secret: &'a str,
}

pub struct DisasterRestoreIdentityInput<'a> {
    pub now: i64,
    pub family_id: &'a str,
    pub family_name: &'a str,
    pub owner_membership_id: &'a str,
    pub owner_display_name: &'a str,
    pub owner_display_name_key: &'a str,
    pub device_id: &'a str,
    pub device_name: &'a str,
    pub session_id: &'a str,
    pub access_token: &'a str,
    pub access_expires_at: i64,
    pub refresh_token: &'a str,
    pub owner_root_fingerprint: Option<&'a str>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
/// Legacy compatibility name for an Owner-visible open request DTO.
/// Rows are limited to `pending` and `approved`-but-unclaimed; inspect `status`.
pub struct PendingMemberLoginRequest {
    pub request_id: String,
    pub display_name: String,
    pub device_name: String,
    pub status: String,
    pub created_at: i64,
    pub expires_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CreatedMemberLoginGrant {
    pub family_name: Option<String>,
    pub member_display_name: String,
    pub expires_at: i64,
}

#[derive(Debug, Clone)]
pub struct ActiveMembership {
    pub role: String,
    pub display_name: String,
    /// Server-minted immutable membership identity (UUID).
    pub membership_id: String,
}

#[derive(Debug, Clone)]
pub struct ActiveDevice {
    pub device_id: String,
    pub membership_id: String,
    pub device_name: String,
    pub last_used_at: i64,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct PendingMemberRenameRequest {
    pub request_id: String,
    pub membership_id: String,
    pub current_display_name: String,
    pub requested_display_name: String,
    pub created_at: i64,
    pub expires_at: i64,
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
    pub family_name: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ReconcileUnit {
    pub root: Entity,
    pub media: Vec<Entity>,
    /// Opaque client-computed identity for the frozen local package.
    pub content_hash: String,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ReconcileDisposition {
    Confirmed,
    Publish,
    AdoptRemote,
    RemoteAbsentRejected,
    RetryAuthority,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ReconcileResult {
    pub entity_type: String,
    pub client_uuid: String,
    pub request_content_hash: String,
    pub disposition: ReconcileDisposition,
    pub reason: String,
    pub remote_content_hash: Option<String>,
    pub remote_root: Option<Entity>,
    pub remote_media: Vec<Entity>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct ReconcileBatch {
    pub cursor: i64,
    pub results: Vec<ReconcileResult>,
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
    #[error("database schema version {found} is unsupported; expected version {supported}")]
    UnsupportedSchemaVersion { found: i64, supported: i64 },
    #[error("database schema does not match current version {supported}")]
    IncompatibleSchema { supported: i64 },
    #[error("family already exists")]
    FamilyAlreadyExists,
    #[error("refresh token is invalid or revoked")]
    InvalidRefreshToken,
    #[error("refresh token replay detected")]
    RefreshTokenReplay,
    #[error("family is not configured")]
    FamilyNotConfigured,
    #[error("family name confirmation does not match")]
    FamilyNameMismatch,
    #[error("owner login request conflicts with stored state")]
    OwnerLoginRequestConflict,
    #[error("pending member request not found")]
    MemberRequestNotFound,
    #[error("pending member request expired")]
    MemberRequestExpired,
    #[error("pending member request is in a terminal state")]
    MemberRequestStateConflict,
    #[error("too many pending member requests")]
    MemberRequestLimit,
    #[error("family display name already exists")]
    DisplayNameConflict,
    #[error("device display name already exists for this member")]
    DeviceNameConflict,
    #[error("member rename request not found")]
    RenameRequestNotFound,
    #[error("member rename request expired")]
    RenameRequestExpired,
    #[error("member rename request is in a terminal state")]
    RenameRequestStateConflict,
    #[error("active membership not found")]
    MembershipNotFound,
    #[error("active device not found")]
    DeviceNotFound,
    #[error("device was removed")]
    DeviceRemoved,
    #[error("family membership was deleted")]
    MembershipDeleted,
    #[error("family was deleted")]
    FamilyDeleted,
    #[error("member login grant not found")]
    MemberLoginGrantNotFound,
    #[error("member login grant expired")]
    MemberLoginGrantExpired,
    #[error("member login grant was already used")]
    MemberLoginGrantAlreadyUsed,
    #[error("cursor is ahead of server cursor {0}")]
    CursorAhead(i64),
    #[error("only owner may change avatar")]
    ForbiddenAvatar,
    #[error("only owner may manage baby profiles")]
    ForbiddenBaby,
    #[error("custom item change forbidden for this membership")]
    ForbiddenCustomItem,
    #[error("care plan change forbidden for this membership")]
    ForbiddenCarePlan,
    #[error("record change forbidden for this membership")]
    ForbiddenRecord,
    #[error("only the family owner may manage an anonymous shared fact")]
    ForbiddenAnonymousFact,
    #[error("deleted custom item cannot be resurrected")]
    CustomItemTombstoneResurrection,
    #[error("deleted care plan cannot be resurrected")]
    CarePlanTombstoneResurrection,
    #[error("completed care plan fulfillment binding is immutable")]
    ImmutableCarePlanFulfillmentBinding,
    #[error("fulfillment candidate evidence is immutable")]
    ImmutableFulfillmentCandidateEvidence,
    #[error("deleted fulfillment candidate cannot be resurrected")]
    FulfillmentCandidateTombstoneResurrection,
    #[error("media kind and association are immutable")]
    ImmutableMediaAssociation,
    #[error("entity updated_at is outside the accepted time range")]
    TimestampOutOfRange,
    #[error("entity is too large for a bounded pull page")]
    PullEntityTooLarge,
    /// Closed CarePlan status↔fulfillment-pair schema invariant (not a missing ref).
    /// Maps to HTTP 422 so defense-in-depth matches the model wire boundary.
    #[error("{0}")]
    InvalidCarePlanFulfillmentPair(&'static str),
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
    #[error("authoritative reconcile batch is invalid")]
    InvalidReconcileBatch,
    #[error("authority graph validation failed ({reason_code}) for {entity_type} {client_uuid}")]
    AuthorityGraphInvalid {
        reason_code: &'static str,
        entity_type: String,
        client_uuid: String,
    },
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
    pub media_integrity: BTreeMap<String, BundleMediaIntegrity>,
    pub status: String,
    pub staged_membership_id: String,
}

#[derive(Debug, Clone)]
pub struct BundleMediaIntegrity {
    pub declared_byte_size: Option<usize>,
    pub staged_sha256: Option<String>,
}

/// Final-path media installed for a bundle that later committed without that
/// media. The row remains durable until the filesystem entry is removed and
/// the cleanup is acknowledged in SQLite.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommittedPendingBundleMedia {
    pub family_id: String,
    pub bundle_id: String,
    pub media_uuid: String,
}

#[derive(Clone)]
pub struct Store {
    database_path: PathBuf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AuthorityGraphValidationSummary {
    pub family_count: usize,
    pub entity_count: usize,
    pub deferred_fulfillment_count: usize,
}

/// Entity primary key: `(entity_type, client_uuid)`.
pub(in crate::store) type EntityKey = (String, String);

fn confirmed_at_millis(now: i64) -> i64 {
    if now > 1_000_000_000_000 {
        now
    } else {
        now.saturating_mul(1_000)
    }
}

fn parse_payload(raw: &str) -> Result<Map<String, Value>, StoreError> {
    serde_json::from_str::<Value>(raw)?
        .as_object()
        .cloned()
        .ok_or(StoreError::InvalidStoredPayload)
}

#[cfg(test)]
fn table_columns(connection: &Connection, table: &str) -> Result<BTreeSet<String>, StoreError> {
    let mut statement = connection.prepare(&format!("PRAGMA table_info({table})"))?;
    let rows = statement.query_map([], |row| row.get::<_, String>(1))?;
    rows.collect::<Result<BTreeSet<_>, _>>()
        .map_err(StoreError::from)
}

impl Store {
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

    pub fn family_ids(&self) -> Result<BTreeSet<String>, StoreError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare("SELECT id FROM families")?;
        let family_ids = statement
            .query_map([], |row| row.get::<_, String>(0))?
            .collect::<Result<BTreeSet<_>, _>>()?;
        Ok(family_ids)
    }
}

#[cfg(test)]
mod tests;
