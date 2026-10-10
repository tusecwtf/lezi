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
//! - [`causal_media_staging`] — bounded preimages, manifest consume, promotion, and GC
//! - [`bundles`] — atomic bundle stage/commit, LWW loaders, and bundle-row helpers
//! - package root — façade types/errors, `Store::{connect,secure_*,health_check,family_ids}`,
//!   shared `parse_payload` / `EntityKey`, and crate re-exports
//!
//! `offline_migrate/` is **not** part of this package.

mod authority_graph;
pub(crate) use authority_graph::validate_authority_graph_on;
mod bundles;
mod causal;
mod causal_admission;
mod causal_media_staging;
mod causal_merge;
mod conflict_retention;
mod conflict_snapshots;
pub(crate) mod current_source_relations;
mod identity;
mod live_census_cache;
mod media;
mod media_associations;
mod pull;
mod restore;
mod restore_authority;
pub(crate) use restore_authority::validate_restore_relations;
pub use restore_authority::{RestoreAuthorityInput, RestoreSourceRelation};
mod schema;
mod source_relations;
mod suspected_duplicates;

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::PathBuf;
#[cfg(test)]
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use std::time::Duration;

use rusqlite::Connection;
use serde::Serialize;
use serde_json::{Map, Value};
use thiserror::Error;

#[allow(unused_imports)]
use self::SourceRelationReceipt as _;
pub(crate) use bundles::{bundle_content_hash, migration_content_hash};
pub use causal::{
    CausalCommitResult, CausalMutation, CausalUnitResult, ConflictResolutionChoice,
    ConflictSummary, ResolveConflictInput, ResolveConflictResult, WithdrawConflictInput,
    WithdrawConflictResult,
};
pub(crate) use causal::{DurableCausalCommit, MAX_CAUSAL_UNITS};
pub(crate) use causal_admission::CausalAdmissionConfig;
pub use causal_admission::CausalCommitSaturation;
pub use causal_media_staging::{
    CausalMediaStageStatus, CausalMediaStagingLimits, VerifiedCausalMediaPreimage,
    DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
};
#[cfg(test)]
pub(crate) use causal_merge::mutation_content_hash as test_mutation_content_hash;
pub use causal_merge::CausalMediaItem;
pub use conflict_snapshots::{ConflictDetailPage, ConflictDetailPageRequest};
pub(crate) use media::media_association_owner;
pub(crate) use media::MediaMetadataIfPublished;
pub(crate) use source_relations::rebuild_record_eligibility;
pub use source_relations::{
    DeclareSourceRelationInput, ResolveSourceRelationGroupInput, SourceRelationReceipt,
    SourceRelationSummary,
};

// Re-exported types are the public Store/HTTP causal seams (ticket 03).
#[allow(unused_imports)]
use self::{
    CausalCommitResult as _, ConflictDetailPage as _, ResolveConflictResult as _,
    WithdrawConflictResult as _,
};
pub(crate) use identity::anonymize_membership_authorship_fields;
pub(crate) use schema::VERSIONED_ENTITY_TYPES;
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
    pub role: String,
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

#[derive(Debug, Clone)]
pub struct FamilyDirectorySnapshot {
    pub generation: String,
    pub memberships: Vec<ActiveMembership>,
    pub visible_devices: Vec<ActiveDevice>,
    pub last_sync_by_membership: HashMap<String, i64>,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub media_identity: Option<Value>,
    #[serde(rename = "type")]
    pub entity_type: String,
    pub client_uuid: String,
    pub updated_at: i64,
    pub deleted_at: Option<i64>,
    pub payload: Map<String, Value>,
    pub rev: i64,
    /// Opaque stable version for causal roots (wire §7). Omitted for media /
    /// fulfillment_candidate and for pre-causal projections without a head.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version_id: Option<String>,
    /// Bounded open-conflict summary; discoverable even when stable version_id
    /// is unchanged after a branch-only write.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub conflict_summary: Option<ConflictSummary>,
    /// Optional source-relation summary (wire §12.3); omitted when no relation.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_relation_summary: Option<SourceRelationSummary>,
}

/// Closed live-census key set; a census always reports all seven types.
pub(crate) const LIVE_CENSUS_ENTITY_TYPES: &[&str] = &[
    "baby",
    "record",
    "media",
    "care_plan",
    "custom_item",
    "fulfillment_candidate",
    "wake_observation",
];

/// Per-type live-set census (wire §1.4 additive field): live row count and
/// digest of the sorted live `client_uuid` keys.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct LiveCensusEntry {
    pub count: u64,
    pub key_digest: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub keys: Option<Vec<String>>,
}

/// Full-family live-set census at the current head, tombstones excluded.
/// Serializes as a flat map keyed by entity type and is attached to a pull
/// page only when the pull explicitly asked for it.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct LiveCensus {
    #[serde(flatten)]
    entries: BTreeMap<&'static str, LiveCensusEntry>,
}

impl LiveCensus {
    /// Fully populated placeholder — widest possible counts and 64-hex digests —
    /// used only by the pull budget formula so the additive census bytes can
    /// never push a planned page over budget. Never serialized to clients.
    pub(crate) fn budget_placeholder() -> Self {
        Self {
            entries: LIVE_CENSUS_ENTITY_TYPES
                .iter()
                .map(|&entity_type| {
                    (
                        entity_type,
                        LiveCensusEntry {
                            count: u64::MAX,
                            key_digest: "0".repeat(64),
                            keys: None,
                        },
                    )
                })
                .collect(),
        }
    }

    pub(crate) fn attach_keys(&mut self, keys_by_type: BTreeMap<&'static str, Vec<String>>) {
        for (entity_type, keys) in keys_by_type {
            if let Some(entry) = self.entries.get_mut(entity_type) {
                entry.keys = Some(keys);
            }
        }
    }

    /// Extra decoded bytes beyond [`Self::budget_placeholder`].
    ///
    /// The placeholder already reserves the widest counts and digests. Live
    /// `keys` arrays are attached after that estimate, so only their excess
    /// has to shrink the entity page. A census without keys is smaller than
    /// the placeholder and contributes zero.
    pub(crate) fn budget_overhead_against_placeholder(&self) -> Result<usize, serde_json::Error> {
        let placeholder = serde_json::to_vec(&Self::budget_placeholder())?;
        let actual = serde_json::to_vec(self)?;
        Ok(actual.len().saturating_sub(placeholder.len()))
    }
}

#[derive(Debug)]
pub struct PullPage {
    pub entities: Vec<PulledEntity>,
    /// Exact serde bytes of each entity, parallel to [Self::entities]. The
    /// handler splices them into the response frame so a page (up to
    /// PULL_PAGE_TARGET_BYTES decoded) is serialized once, not twice.
    pub entity_wire: Vec<Vec<u8>>,
    pub cursor: i64,
    pub has_more: bool,
    pub family_name: Option<String>,
    /// Present only when the pull requested `include_live_census=true`.
    pub live_census: Option<LiveCensus>,
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
    #[error("identity administration is forbidden for this actor")]
    ForbiddenIdentityAdministration,
    #[error("deleted custom item cannot be resurrected")]
    CustomItemTombstoneResurrection,
    #[error("deleted care plan cannot be resurrected")]
    CarePlanTombstoneResurrection,
    #[error("deleted care record cannot be resurrected")]
    RecordTombstoneResurrection,
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
    #[error("tombstone restore base is missing")]
    MissingRestoreBase,
    #[error("tombstone restore base is incomplete")]
    IncompleteRestoreBase,
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
    #[error("causal media preimage conflicts with durable bytes")]
    CausalMediaPreimageConflict,
    #[error("causal media preimage belongs to a different membership")]
    CausalMediaMembershipMismatch,
    #[error("causal media staging quota exceeded: {0}")]
    CausalMediaStagingQuota(&'static str),
    #[error("causal media staging metadata is invalid")]
    InvalidCausalMediaStaging,
    /// Atomic bundles are retained only for immutable fulfillment facts.
    #[error("atomic bundle does not accept mutable root type {0}")]
    LegacyBundleCausalRootUnsupported(String),
    #[error("causal commit batch is invalid")]
    InvalidCausalBatch,
    #[error("causal commit rejected for mutation {mutation_id}: {code}")]
    CausalCommitRejected { mutation_id: String, code: String },
    #[error("causal commit admission saturated: {0:?}")]
    CausalCommitSaturated(CausalCommitSaturation),
    #[error("causal commit admission is unavailable")]
    CausalAdmissionUnavailable,
    #[error("causal commit admission config is invalid")]
    InvalidCausalAdmissionConfig,
    #[error("conflict not found")]
    ConflictNotFound,
    #[error("invalid snapshot token")]
    InvalidSnapshotToken,
    #[error("snapshot expired")]
    SnapshotExpired,
    #[error("snapshot stale")]
    SnapshotStale,
    #[error("conflict snapshot page exceeds the encoded response budget")]
    ConflictSnapshotPageTooLarge,
    #[error("source relation request invalid: {0}")]
    InvalidSourceRelationRequest(&'static str),
    #[error("authority graph validation failed ({reason_code}) for {entity_type} {client_uuid}")]
    AuthorityGraphInvalid {
        reason_code: &'static str,
        entity_type: String,
        client_uuid: String,
        rev: Option<i64>,
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
pub(in crate::store) struct CachedSession {
    pub principal: Principal,
    pub access_expires_at: i64,
    pub last_used_synced_at: i64,
}

#[cfg(test)]
type AuthCacheReadHook = Arc<dyn Fn() + Send + Sync>;

#[derive(Clone)]
pub struct Store {
    database_path: PathBuf,
    read_only: bool,
    snapshot_receipt_key: Arc<[u8]>,
    causal_commit_limiter: Arc<crate::rate_limit::RateLimiter>,
    max_open_causal_branches_per_root: usize,
    causal_media_publication_locks:
        Arc<std::sync::Mutex<HashMap<String, Arc<std::sync::Mutex<()>>>>>,
    causal_media_gc_in_flight: Arc<std::sync::Mutex<BTreeSet<String>>>,
    /// Per-(family, head) memo for the wire §1.4 live census. Shared by all
    /// clones of this `Store` so invalidation inside the rev-write funnel
    /// reaches every handle; see `live_census_cache` for the contract.
    live_census_cache: Arc<live_census_cache::LiveCensusCache>,
    pub(in crate::store) auth_cache: Arc<std::sync::RwLock<HashMap<String, CachedSession>>>,
    auth_cache_generation: Arc<std::sync::atomic::AtomicU64>,
    #[cfg(test)]
    pub(in crate::store) auth_cache_read_hook: Arc<std::sync::Mutex<Option<AuthCacheReadHook>>>,
    /// Tests opt in; production always auto-aligns after a successful record commit.
    #[cfg(test)]
    pub(crate) auto_align_enabled: Arc<AtomicBool>,
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
    serde_json::from_str::<Map<String, Value>>(raw).map_err(|_| StoreError::InvalidStoredPayload)
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
        if self.read_only {
            let connection = Connection::open_with_flags(
                &self.database_path,
                rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY
                    | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
            )?;
            connection.busy_timeout(Duration::from_secs(10))?;
            connection.execute_batch("PRAGMA foreign_keys = ON;")?;
            return Ok(connection);
        }
        let connection = Connection::open(&self.database_path)?;
        #[cfg(test)]
        let mut connection = connection;
        #[cfg(test)]
        connection.trace(Some(tests::test_support::trace_counted_pull_statement));
        #[cfg(test)]
        tests::test_support::install_sql_work_probe(&connection);
        crate::secure_file(&self.database_path)?;
        connection.busy_timeout(Duration::from_secs(10))?;
        // synchronous=FULL is explicit. NORMAL is a durability decision (power
        // loss can drop the tail transaction) and is not taken.
        connection.execute_batch(
            "
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = FULL;
            ",
        )?;
        self.secure_database_files()?;
        Ok(connection)
    }

    /// One connection for sequential reads. Callers that need a transaction
    /// still open their own; this does not change lock mode.
    pub(crate) fn with_connection<T, E>(
        &self,
        body: impl FnOnce(&Connection) -> Result<T, E>,
    ) -> Result<T, E>
    where
        E: From<StoreError>,
    {
        let connection = self.connect()?;
        body(&connection)
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
    pub fn invalidate_auth_cache(&self) {
        let mut cache = self
            .auth_cache
            .write()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        self.auth_cache_generation
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        cache.clear();
    }

    /// Test probe: the head rev a live cache entry claims for this family, or
    /// `None` when the next census pull would rebuild.
    #[cfg(test)]
    pub(in crate::store) fn test_cached_census_head_rev(&self, family_id: &str) -> Option<i64> {
        self.live_census_cache.test_cached_head_rev(family_id)
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
