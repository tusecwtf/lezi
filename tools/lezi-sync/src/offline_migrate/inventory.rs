//! v3 ↔ current table/field/path inventory and migration policy.
//!
//! **Authority:** this module is the machine-readable contract for tickets 02+.
//! `.scratch/nas-v3-offline-migrate/spec.md` is the human narrative; it must not
//! contradict this inventory. New authoritative failures amend this file.

use crate::model::EntityValidationContext;
use crate::store::DATABASE_SCHEMA_VERSION;

/// Measured family-NAS source schema (`PRAGMA user_version`).
pub(crate) const SOURCE_USER_VERSION: i64 = 3;

/// Coupled to [`DATABASE_SCHEMA_VERSION`] — do not set a free-floating number.
pub(crate) const TARGET_USER_VERSION: i64 = DATABASE_SCHEMA_VERSION;

// ---------------------------------------------------------------------------
// Disposition algebra
// ---------------------------------------------------------------------------

/// Table-level treatment of a named SQLite table.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TableDispositionKind {
    /// Source rows may be kept after field mapping / row filter.
    KeepOrTransform,
    /// Entire source table is dropped; target has no corresponding legacy rows.
    Discard,
    /// Target-only table created empty by current schema init; no v3 source rows.
    TargetOnlyEmpty,
}

/// Row-level filter applied **before** field mapping on KeepOrTransform tables.
/// Field maps describe columns of **retained** rows only.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RowFilter {
    /// Every source row is a mapping candidate (subject to validation).
    All,
    /// Discard whole rows when `column` equals `value` (non-authoritative).
    /// Counts go to the human report; discard alone does **not** fail the run.
    DiscardRowsWhenEquals {
        column: &'static str,
        value: &'static str,
    },
    /// Discard whole rows when `column` IS NOT NULL (non-authoritative).
    /// Used for v3 departed memberships (`left_at`): treated as hard-delete, not
    /// identity tombstones. Counts go to the human report; discard alone does
    /// **not** fail the run.
    DiscardRowsWhenNotNull { column: &'static str },
    /// No row filter (Discard / TargetOnlyEmpty tables).
    NotApplicable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct TableDisposition {
    pub source_or_target_name: &'static str,
    pub kind: TableDispositionKind,
    pub row_filter: RowFilter,
    pub note: &'static str,
}

/// Field-level action on retained rows of a KeepOrTransform table.
///
/// Algebra:
/// - **Keep** — type-compatible copy; no value rewrite.
/// - **Validate** — Keep plus a CHECK/model gate; failure is unmappable (run aborts).
/// - **Derive** — pure function of source fields into a target column.
/// - **DropColumn** — source column is not written to the target row.
/// - **TargetAdd** — target-only column; value from note / later ticket, not v3.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FieldDisposition {
    Keep,
    Validate,
    Derive,
    DropColumn,
    TargetAdd,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct FieldMapping {
    pub table: &'static str,
    pub source_field: Option<&'static str>,
    pub target_field: Option<&'static str>,
    pub disposition: FieldDisposition,
    pub note: &'static str,
}

// ---------------------------------------------------------------------------
// Source schema contract (frozen v3 DDL)
// ---------------------------------------------------------------------------

/// Measured / historical v3 schema SQL (git `2c6bbf6` CURRENT_SCHEMA_SQL while
/// `DATABASE_SCHEMA_VERSION = 3`). Ticket 02 validates source DBs against this
/// shape (table set + columns); non-allowlisted user tables fail closed.
pub(crate) const SOURCE_V3_SCHEMA_SQL: &str = "
    CREATE TABLE families (
        id TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        create_request_hash TEXT,
        name TEXT
    );
    CREATE UNIQUE INDEX families_create_request
        ON families(create_request_hash);

    CREATE TABLE memberships (
        membership_id TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
        device_id TEXT NOT NULL,
        display_name TEXT NOT NULL,
        left_at INTEGER
    );
    CREATE INDEX memberships_family ON memberships(family_id);

    CREATE TABLE membership_credentials (
        token_hash TEXT PRIMARY KEY,
        membership_id TEXT NOT NULL
            REFERENCES memberships(membership_id) ON DELETE CASCADE,
        revoked_at INTEGER
    );
    CREATE INDEX membership_credentials_membership
        ON membership_credentials(membership_id);

    CREATE TABLE invites (
        code_hash TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL,
        used_at INTEGER,
        joined_device_id TEXT
    );

    CREATE TABLE family_meta (
        family_id TEXT PRIMARY KEY REFERENCES families(id) ON DELETE CASCADE,
        rev INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE entities (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        entity_type TEXT NOT NULL CHECK(entity_type IN ('baby', 'record', 'media', 'care_plan', 'custom_item', 'fulfillment_candidate')),
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
";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct SourceColumn {
    pub name: &'static str,
    pub sql_type: &'static str,
    pub not_null: bool,
    pub primary_key: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct SourceTable {
    pub name: &'static str,
    pub columns: &'static [SourceColumn],
}

/// Allowlisted v3 user tables and their columns (contract for shape checks).
pub(crate) fn source_v3_tables() -> &'static [SourceTable] {
    &[
        SourceTable {
            name: "families",
            columns: &[
                SourceColumn {
                    name: "id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "created_at",
                    sql_type: "INTEGER",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "create_request_hash",
                    sql_type: "TEXT",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "name",
                    sql_type: "TEXT",
                    not_null: false,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "memberships",
            columns: &[
                SourceColumn {
                    name: "membership_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "family_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "role",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "device_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "display_name",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "left_at",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "membership_credentials",
            columns: &[
                SourceColumn {
                    name: "token_hash",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "membership_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "revoked_at",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "invites",
            columns: &[
                SourceColumn {
                    name: "code_hash",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "family_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "expires_at",
                    sql_type: "INTEGER",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "used_at",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "joined_device_id",
                    sql_type: "TEXT",
                    not_null: false,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "family_meta",
            columns: &[
                SourceColumn {
                    name: "family_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "rev",
                    sql_type: "INTEGER",
                    not_null: true,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "entities",
            columns: &[
                SourceColumn {
                    name: "family_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "entity_type",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "client_uuid",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "updated_at",
                    sql_type: "INTEGER",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "deleted_at",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "payload_json",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "rev",
                    sql_type: "INTEGER",
                    not_null: true,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "sync_bundles",
            columns: &[
                SourceColumn {
                    name: "family_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "bundle_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "staged_membership_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "status",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "root_type",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "root_client_uuid",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "root_updated_at",
                    sql_type: "INTEGER",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "root_deleted_at",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "root_payload_json",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "media_entities_json",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "content_hash",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "created_at",
                    sql_type: "INTEGER",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "committed_at",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "committed_cursor",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "committed_applied",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "sync_bundle_media",
            columns: &[
                SourceColumn {
                    name: "family_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "bundle_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "media_uuid",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "declared_byte_size",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "staged_byte_size",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "staged_sha256",
                    sql_type: "TEXT",
                    not_null: false,
                    primary_key: false,
                },
                SourceColumn {
                    name: "staged_at",
                    sql_type: "INTEGER",
                    not_null: false,
                    primary_key: false,
                },
            ],
        },
        SourceTable {
            name: "media_publications",
            columns: &[
                SourceColumn {
                    name: "family_id",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "media_uuid",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: true,
                },
                SourceColumn {
                    name: "source",
                    sql_type: "TEXT",
                    not_null: true,
                    primary_key: false,
                },
                SourceColumn {
                    name: "bundle_id",
                    sql_type: "TEXT",
                    not_null: false,
                    primary_key: false,
                },
            ],
        },
    ]
}

// ---------------------------------------------------------------------------
// Policy markers (unrepresentable alternatives)
// ---------------------------------------------------------------------------

/// Locked failure mode for unmappable authoritative work.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FailureMode {
    /// Abort the whole run; leave no copy-back-ready out/; emit a human report.
    AbortNoCopyBackWithReport,
}

/// Closed set of authoritative failure predicates. New cases amend ticket 01 /
/// this inventory — ticket 02+ must not invent private meanings for existing variants.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum AuthoritativeFailure {
    SourceUserVersionNotThree,
    /// Allowlisted v3 table missing, wrong columns/types/nullability/pk, or shape mismatch.
    /// **Not** used for missing path, constrained value corruption, or target-side invariants.
    SourceShapeMismatch,
    /// Any user table not in the v3 allowlist.
    UnknownSourceUserTable,
    /// Constrained text outside the closed CHECK/domain set (e.g. bundle status, publication source).
    SourceConstrainedValueInvalid,
    InvalidMembershipRole,
    UnknownEntityType,
    PayloadValidationFailed,
    ActiveDisplayNameKeyConflict,
    /// Exactly one active (`left_at IS NULL`) owner per family is required.
    NotExactlyOneActiveOwner,
    /// FK points at a missing family/membership required for a kept row.
    OrphanAuthoritativeForeignKey,
    /// family_meta row missing for a kept family.
    FamilyMetaMissingForFamily,
    /// media_entities_json parse/validation or root↔media consistency failed.
    MediaEntitiesJsonInvalid,
    /// content_hash does not match canonical recompute after payload rewrite.
    ContentHashMismatchAfterCanonicalize,
    /// Media file missing / size / hash disagree with publications or bundle_media (ticket 03).
    MediaFileMissingOrMismatch,
}

/// Entity types allowed by v3/v11 CHECK contracts (single source for migrator + tests).
pub(crate) const ALLOWED_ENTITY_TYPES: &[&str] = &[
    "baby",
    "record",
    "media",
    "care_plan",
    "custom_item",
    "fulfillment_candidate",
];

/// `media_publications.source` closed set (CHECK).
pub(crate) const ALLOWED_MEDIA_PUBLICATION_SOURCES: &[&str] =
    &["ordinary", "bundle_pending", "bundle"];

/// Bundle status discarded by [`RowFilter`] on `sync_bundles` (non-authoritative).
pub(crate) const BUNDLE_STATUS_STAGING: &str = "staging";
/// Bundle status retained after row filter.
pub(crate) const BUNDLE_STATUS_COMMITTED: &str = "committed";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum LegacyCredentialDisposition {
    DiscardAll,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum OwnerReauth {
    NewRootPasswordAtMigration,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MemberReauth {
    CurrentRequestOrGrantFlows,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum DeviceSessionAfterMigrate {
    NoneUsable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum StartupUpgrade {
    Forbidden,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MigrationScope {
    PrivateOfflineOpsOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum UnknownSourceTablePolicy {
    FailClosed,
}

/// Cascade when staging bundles are discarded by [`RowFilter`], plus
/// committed-pending cleanup evidence (mirrors live server startup cleanup).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum StagingCascade {
    /// Drop `sync_bundle_media` for discarded `bundle_id`s (report counts).
    /// Drop `media_publications` whose `bundle_id` was discarded (any source), report.
    /// Keep `source=ordinary` and `source=bundle` pubs reachable from retained committed
    /// bundles. **Discard** `source=bundle_pending` pubs on retained committed bundles
    /// (and matching `sync_bundle_media`) as non-servable cleanup evidence — report-only;
    /// never treat them as media-file authority (ticket 03).
    /// Media bytes only referenced by discarded staging / pending cleanup: **ignore** + report.
    /// Cascade discards alone never fail the run.
    DropDependentsReportOrphanBytesIgnore,
}

/// Payload / bundle validation locked for ticket 02.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct PayloadValidationPolicy {
    /// Validate tombstones with the same context (deleted_at stays set).
    pub validate_tombstones: bool,
    /// Persist the post-validate (possibly default-filled) canonical payload.
    pub persist_canonical_payload: bool,
    /// Recompute/verify content_hash after root+media canonicalization for committed bundles.
    pub recompute_content_hash: bool,
    /// Parse media_entities_json, validate each as AtomicBundleMedia, check root consistency.
    pub validate_media_entities_json: bool,
}

pub(crate) fn failure_mode() -> FailureMode {
    FailureMode::AbortNoCopyBackWithReport
}

pub(crate) fn authoritative_failures() -> &'static [AuthoritativeFailure] {
    &[
        AuthoritativeFailure::SourceUserVersionNotThree,
        AuthoritativeFailure::SourceShapeMismatch,
        AuthoritativeFailure::UnknownSourceUserTable,
        AuthoritativeFailure::SourceConstrainedValueInvalid,
        AuthoritativeFailure::InvalidMembershipRole,
        AuthoritativeFailure::UnknownEntityType,
        AuthoritativeFailure::PayloadValidationFailed,
        AuthoritativeFailure::ActiveDisplayNameKeyConflict,
        AuthoritativeFailure::NotExactlyOneActiveOwner,
        AuthoritativeFailure::OrphanAuthoritativeForeignKey,
        AuthoritativeFailure::FamilyMetaMissingForFamily,
        AuthoritativeFailure::MediaEntitiesJsonInvalid,
        AuthoritativeFailure::ContentHashMismatchAfterCanonicalize,
        AuthoritativeFailure::MediaFileMissingOrMismatch,
    ]
}

/// Target-only empty tables (session shells) from [`table_dispositions`].
pub(crate) fn target_only_empty_tables() -> Vec<&'static str> {
    table_dispositions()
        .iter()
        .filter(|row| row.kind == TableDispositionKind::TargetOnlyEmpty)
        .map(|row| row.source_or_target_name)
        .collect()
}

/// Row filter for `sync_bundles` (must be staging discard for cascade contract).
pub(crate) fn sync_bundles_row_filter() -> RowFilter {
    table_dispositions()
        .iter()
        .find(|row| row.source_or_target_name == "sync_bundles")
        .map(|row| row.row_filter)
        .expect("sync_bundles disposition")
}

/// Row filter for `memberships` (must discard departed / `left_at IS NOT NULL`).
pub(crate) fn memberships_row_filter() -> RowFilter {
    table_dispositions()
        .iter()
        .find(|row| row.source_or_target_name == "memberships")
        .map(|row| row.row_filter)
        .expect("memberships disposition")
}

/// True when a bundle `status` is discarded by the inventory row filter (non-authoritative).
pub(crate) fn is_discarded_bundle_status(status: &str) -> bool {
    match sync_bundles_row_filter() {
        RowFilter::DiscardRowsWhenEquals { column, value } => column == "status" && status == value,
        _ => false,
    }
}

/// True when a membership row is departed under the inventory row filter
/// (`left_at IS NOT NULL` → hard-delete + anonymize, not copy).
pub(crate) fn is_departed_membership_left_at(left_at: Option<i64>) -> bool {
    match memberships_row_filter() {
        RowFilter::DiscardRowsWhenNotNull { column } => column == "left_at" && left_at.is_some(),
        _ => false,
    }
}

pub(crate) fn legacy_credential_disposition() -> LegacyCredentialDisposition {
    LegacyCredentialDisposition::DiscardAll
}

pub(crate) fn owner_reauth() -> OwnerReauth {
    OwnerReauth::NewRootPasswordAtMigration
}

pub(crate) fn member_reauth() -> MemberReauth {
    MemberReauth::CurrentRequestOrGrantFlows
}

pub(crate) fn device_session_after_migrate() -> DeviceSessionAfterMigrate {
    DeviceSessionAfterMigrate::NoneUsable
}

pub(crate) fn startup_upgrade() -> StartupUpgrade {
    StartupUpgrade::Forbidden
}

pub(crate) fn migration_scope() -> MigrationScope {
    MigrationScope::PrivateOfflineOpsOnly
}

pub(crate) fn unknown_source_table_policy() -> UnknownSourceTablePolicy {
    UnknownSourceTablePolicy::FailClosed
}

pub(crate) fn staging_cascade() -> StagingCascade {
    StagingCascade::DropDependentsReportOrphanBytesIgnore
}

pub(crate) fn payload_validation_policy() -> PayloadValidationPolicy {
    PayloadValidationPolicy {
        validate_tombstones: true,
        persist_canonical_payload: true,
        recompute_content_hash: true,
        validate_media_entities_json: true,
    }
}

/// entity_type → validation context for kept entity rows and committed roots.
pub(crate) fn entity_validation_context(entity_type: &str) -> EntityValidationContext {
    match entity_type {
        "media" => EntityValidationContext::AtomicBundleMedia,
        _ => EntityValidationContext::AtomicBundleRoot,
    }
}

// ---------------------------------------------------------------------------
// Flow
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum MigrationFlowStep {
    CopyOutNasBackup,
    LocalOneShotUpgrade,
    ValidateCurrentPreflight,
    CopyBackToNas,
    StartTlsAndAccept,
}

pub(crate) fn migration_flow_steps() -> &'static [MigrationFlowStep] {
    &[
        MigrationFlowStep::CopyOutNasBackup,
        MigrationFlowStep::LocalOneShotUpgrade,
        MigrationFlowStep::ValidateCurrentPreflight,
        MigrationFlowStep::CopyBackToNas,
        MigrationFlowStep::StartTlsAndAccept,
    ]
}

// ---------------------------------------------------------------------------
// Path dispositions (data-dir sidepaths)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PathDispositionKind {
    /// DB transform: read backup, write a new DB under independent out/; never mutate backup.
    TransformWriteOut,
    /// Keep bytes; missing/mismatch vs DB authority fails closed (ticket 03).
    KeepWithDbValidation,
    /// Always mint new material; never copy old token-signing / auth secrets.
    RegenerateAlways,
    /// May be absent on pre-TLS NAS; created at cutover / TLS init (ticket 06).
    AbsentOrCreateAtCutover,
    /// Ops packaging; not part of authoritative family data.
    OpsOptionalIgnore,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct PathDisposition {
    pub pattern: &'static str,
    pub kind: PathDispositionKind,
    pub note: &'static str,
}

pub(crate) fn path_dispositions() -> &'static [PathDisposition] {
    &[
        PathDisposition {
            pattern: "lezi.db",
            kind: PathDispositionKind::TransformWriteOut,
            note: "Read-only backup; transform writes a new lezi.db under independent out/",
        },
        PathDisposition {
            pattern: "media/{family_uuid}/{media_uuid}",
            kind: PathDispositionKind::KeepWithDbValidation,
            note: "Required when a kept publication (source ordinary|bundle only; bundle_pending is discarded cleanup evidence) or retained committed bundle_media references it",
        },
        PathDisposition {
            pattern: "server.secret",
            kind: PathDispositionKind::RegenerateAlways,
            note: "Never copy old HMAC material; migrator always regenerates",
        },
        PathDisposition {
            pattern: "tls/",
            kind: PathDispositionKind::AbsentOrCreateAtCutover,
            note: "Pre-TLS NAS may lack tls/; current image init creates identity",
        },
        PathDisposition {
            pattern: "app-update.json",
            kind: PathDispositionKind::OpsOptionalIgnore,
            note: "Not family authority",
        },
        PathDisposition {
            pattern: "app-release.apk",
            kind: PathDispositionKind::OpsOptionalIgnore,
            note: "Not family authority",
        },
    ]
}

// ---------------------------------------------------------------------------
// Table + field inventories
// ---------------------------------------------------------------------------

pub(crate) fn table_dispositions() -> &'static [TableDisposition] {
    &[
        TableDisposition {
            source_or_target_name: "families",
            kind: TableDispositionKind::KeepOrTransform,
            row_filter: RowFilter::All,
            note: "Keep family identity and name; owner_root_fingerprint TargetAdd (04)",
        },
        TableDisposition {
            source_or_target_name: "memberships",
            kind: TableDispositionKind::KeepOrTransform,
            row_filter: RowFilter::DiscardRowsWhenNotNull {
                column: "left_at",
            },
            note: "Active only (left_at IS NULL). Departed rows = hard-delete: drop membership, anonymize author/submitter refs on retained facts; DropColumn device_id; Derive display_name_key",
        },
        TableDisposition {
            source_or_target_name: "membership_credentials",
            kind: TableDispositionKind::Discard,
            row_filter: RowFilter::NotApplicable,
            note: "All legacy token hashes void",
        },
        TableDisposition {
            source_or_target_name: "invites",
            kind: TableDispositionKind::Discard,
            row_filter: RowFilter::NotApplicable,
            note: "Legacy invite codes void",
        },
        TableDisposition {
            source_or_target_name: "family_meta",
            kind: TableDispositionKind::KeepOrTransform,
            row_filter: RowFilter::All,
            note: "Keep family_id and rev",
        },
        TableDisposition {
            source_or_target_name: "entities",
            kind: TableDispositionKind::KeepOrTransform,
            row_filter: RowFilter::All,
            note: "Authoritative care data; payload Validate + canonicalize",
        },
        TableDisposition {
            source_or_target_name: "sync_bundles",
            kind: TableDispositionKind::KeepOrTransform,
            row_filter: RowFilter::DiscardRowsWhenEquals {
                column: "status",
                value: BUNDLE_STATUS_STAGING,
            },
            note: "Committed keep; staging discarded at row level (non-authoritative)",
        },
        TableDisposition {
            source_or_target_name: "sync_bundle_media",
            kind: TableDispositionKind::KeepOrTransform,
            row_filter: RowFilter::All,
            note: "Keep rows whose bundle_id survived staging cascade, except matching committed-pending cleanup media",
        },
        TableDisposition {
            source_or_target_name: "media_publications",
            kind: TableDispositionKind::KeepOrTransform,
            row_filter: RowFilter::All,
            note: "Keep ordinary + source=bundle for retained committed; cascade drops staging pubs and committed bundle_pending cleanup",
        },
        TableDisposition {
            source_or_target_name: "devices",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty until re-login",
        },
        TableDisposition {
            source_or_target_name: "device_sessions",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty",
        },
        TableDisposition {
            source_or_target_name: "refresh_token_history",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty",
        },
        TableDisposition {
            source_or_target_name: "terminal_credential_denials",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty",
        },
        TableDisposition {
            source_or_target_name: "owner_login_requests",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty",
        },
        TableDisposition {
            source_or_target_name: "member_login_requests",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty",
        },
        TableDisposition {
            source_or_target_name: "member_login_grants",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty",
        },
        TableDisposition {
            source_or_target_name: "member_rename_requests",
            kind: TableDispositionKind::TargetOnlyEmpty,
            row_filter: RowFilter::NotApplicable,
            note: "Empty",
        },
    ]
}

/// Field maps for retained rows only (after row filters / cascade).
pub(crate) fn field_mappings() -> &'static [FieldMapping] {
    &[
        // --- families ---
        FieldMapping {
            table: "families",
            source_field: Some("id"),
            target_field: Some("id"),
            disposition: FieldDisposition::Keep,
            note: "Family UUID primary key",
        },
        FieldMapping {
            table: "families",
            source_field: Some("created_at"),
            target_field: Some("created_at"),
            disposition: FieldDisposition::Keep,
            note: "Creation epoch seconds",
        },
        FieldMapping {
            table: "families",
            source_field: Some("create_request_hash"),
            target_field: Some("create_request_hash"),
            disposition: FieldDisposition::Keep,
            note: "Idempotency hash; may be null",
        },
        FieldMapping {
            table: "families",
            source_field: Some("name"),
            target_field: Some("name"),
            disposition: FieldDisposition::Keep,
            note: "Shared family display name",
        },
        FieldMapping {
            table: "families",
            source_field: None,
            target_field: Some("owner_root_fingerprint"),
            disposition: FieldDisposition::TargetAdd,
            note: "Set from migration-time new root password (ticket 04)",
        },
        // --- memberships ---
        FieldMapping {
            table: "memberships",
            source_field: Some("membership_id"),
            target_field: Some("membership_id"),
            disposition: FieldDisposition::Keep,
            note: "Immutable membership identity",
        },
        FieldMapping {
            table: "memberships",
            source_field: Some("family_id"),
            target_field: Some("family_id"),
            disposition: FieldDisposition::Keep,
            note: "FK to families",
        },
        FieldMapping {
            table: "memberships",
            source_field: Some("role"),
            target_field: Some("role"),
            disposition: FieldDisposition::Validate,
            note: "owner|member CHECK; invalid → InvalidMembershipRole",
        },
        FieldMapping {
            table: "memberships",
            source_field: Some("display_name"),
            target_field: Some("display_name"),
            disposition: FieldDisposition::Keep,
            note: "Family display name (称呼)",
        },
        FieldMapping {
            table: "memberships",
            source_field: Some("display_name"),
            target_field: Some("display_name_key"),
            disposition: FieldDisposition::Derive,
            note: "normalized_display_name_key(display_name)",
        },
        FieldMapping {
            table: "memberships",
            source_field: Some("left_at"),
            target_field: Some("left_at"),
            disposition: FieldDisposition::Keep,
            note: "Retained active rows only (row filter drops left_at IS NOT NULL); always NULL on target — never keep departed identity tombstones",
        },
        FieldMapping {
            table: "memberships",
            source_field: Some("device_id"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "v3 one-device binding is not a v11 device session",
        },
        // --- entities ---
        FieldMapping {
            table: "entities",
            source_field: Some("family_id"),
            target_field: Some("family_id"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "entities",
            source_field: Some("entity_type"),
            target_field: Some("entity_type"),
            disposition: FieldDisposition::Validate,
            note: "Closed type set; unknown → UnknownEntityType",
        },
        FieldMapping {
            table: "entities",
            source_field: Some("client_uuid"),
            target_field: Some("client_uuid"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "entities",
            source_field: Some("updated_at"),
            target_field: Some("updated_at"),
            disposition: FieldDisposition::Keep,
            note: "LWW clock",
        },
        FieldMapping {
            table: "entities",
            source_field: Some("deleted_at"),
            target_field: Some("deleted_at"),
            disposition: FieldDisposition::Keep,
            note: "Tombstone clock; still validate payload",
        },
        FieldMapping {
            table: "entities",
            source_field: Some("payload_json"),
            target_field: Some("payload_json"),
            disposition: FieldDisposition::Validate,
            note: "entity_validation_context(type); persist canonical post-validate JSON",
        },
        FieldMapping {
            table: "entities",
            source_field: Some("rev"),
            target_field: Some("rev"),
            disposition: FieldDisposition::Keep,
            note: "Entity revision",
        },
        // --- sync_bundles (retained = committed only) ---
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("family_id"),
            target_field: Some("family_id"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("bundle_id"),
            target_field: Some("bundle_id"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("staged_membership_id"),
            target_field: Some("staged_membership_id"),
            disposition: FieldDisposition::Validate,
            note: "Must reference a kept membership",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("status"),
            target_field: Some("status"),
            disposition: FieldDisposition::Keep,
            note: "Retained rows are committed only (staging filtered out by RowFilter)",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("root_type"),
            target_field: Some("root_type"),
            disposition: FieldDisposition::Validate,
            note: "Must be a valid AtomicBundleRoot type",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("root_client_uuid"),
            target_field: Some("root_client_uuid"),
            disposition: FieldDisposition::Keep,
            note: "Root uuid",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("root_updated_at"),
            target_field: Some("root_updated_at"),
            disposition: FieldDisposition::Keep,
            note: "Root clock",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("root_deleted_at"),
            target_field: Some("root_deleted_at"),
            disposition: FieldDisposition::Keep,
            note: "Root tombstone",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("root_payload_json"),
            target_field: Some("root_payload_json"),
            disposition: FieldDisposition::Validate,
            note: "AtomicBundleRoot for root_type; persist canonical payload",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("media_entities_json"),
            target_field: Some("media_entities_json"),
            disposition: FieldDisposition::Validate,
            note: "Parse + AtomicBundleMedia each + root consistency; persist canonical JSON",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("content_hash"),
            target_field: Some("content_hash"),
            disposition: FieldDisposition::Validate,
            note: "Must equal recompute after root+media canonicalize",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("created_at"),
            target_field: Some("created_at"),
            disposition: FieldDisposition::Keep,
            note: "Created clock",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("committed_at"),
            target_field: Some("committed_at"),
            disposition: FieldDisposition::Keep,
            note: "Commit clock",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("committed_cursor"),
            target_field: Some("committed_cursor"),
            disposition: FieldDisposition::Keep,
            note: "Commit cursor",
        },
        FieldMapping {
            table: "sync_bundles",
            source_field: Some("committed_applied"),
            target_field: Some("committed_applied"),
            disposition: FieldDisposition::Keep,
            note: "Applied flag",
        },
        // --- sync_bundle_media ---
        FieldMapping {
            table: "sync_bundle_media",
            source_field: Some("family_id"),
            target_field: Some("family_id"),
            disposition: FieldDisposition::Keep,
            note: "PK part; only retained bundles",
        },
        FieldMapping {
            table: "sync_bundle_media",
            source_field: Some("bundle_id"),
            target_field: Some("bundle_id"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "sync_bundle_media",
            source_field: Some("media_uuid"),
            target_field: Some("media_uuid"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "sync_bundle_media",
            source_field: Some("declared_byte_size"),
            target_field: Some("declared_byte_size"),
            disposition: FieldDisposition::Keep,
            note: "Declared size",
        },
        FieldMapping {
            table: "sync_bundle_media",
            source_field: Some("staged_byte_size"),
            target_field: Some("staged_byte_size"),
            disposition: FieldDisposition::Keep,
            note: "Staged size",
        },
        FieldMapping {
            table: "sync_bundle_media",
            source_field: Some("staged_sha256"),
            target_field: Some("staged_sha256"),
            disposition: FieldDisposition::Keep,
            note: "Staged hash",
        },
        FieldMapping {
            table: "sync_bundle_media",
            source_field: Some("staged_at"),
            target_field: Some("staged_at"),
            disposition: FieldDisposition::Keep,
            note: "Staged clock",
        },
        // --- media_publications ---
        FieldMapping {
            table: "media_publications",
            source_field: Some("family_id"),
            target_field: Some("family_id"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "media_publications",
            source_field: Some("media_uuid"),
            target_field: Some("media_uuid"),
            disposition: FieldDisposition::Keep,
            note: "PK part",
        },
        FieldMapping {
            table: "media_publications",
            source_field: Some("source"),
            target_field: Some("source"),
            disposition: FieldDisposition::Validate,
            note: "Must already be ordinary|bundle_pending|bundle CHECK; no value remap. bundle_pending on retained committed is cascade-discarded (cleanup evidence), not remapped",
        },
        FieldMapping {
            table: "media_publications",
            source_field: Some("bundle_id"),
            target_field: Some("bundle_id"),
            disposition: FieldDisposition::Keep,
            note: "Nullable; cascade drops pubs for discarded staging bundle_id and committed-pending cleanup rows",
        },
        // --- invites (discard table; DropColumn documents source-only columns) ---
        FieldMapping {
            table: "invites",
            source_field: Some("code_hash"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Legacy invite secret hash",
        },
        FieldMapping {
            table: "invites",
            source_field: Some("family_id"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Dropped with invite row",
        },
        FieldMapping {
            table: "invites",
            source_field: Some("expires_at"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Dropped",
        },
        FieldMapping {
            table: "invites",
            source_field: Some("used_at"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Dropped",
        },
        FieldMapping {
            table: "invites",
            source_field: Some("joined_device_id"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Dropped",
        },
        // --- membership_credentials ---
        FieldMapping {
            table: "membership_credentials",
            source_field: Some("token_hash"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Legacy long-lived token hash",
        },
        FieldMapping {
            table: "membership_credentials",
            source_field: Some("membership_id"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Dropped; membership row itself is kept",
        },
        FieldMapping {
            table: "membership_credentials",
            source_field: Some("revoked_at"),
            target_field: None,
            disposition: FieldDisposition::DropColumn,
            note: "Dropped",
        },
        // --- family_meta ---
        FieldMapping {
            table: "family_meta",
            source_field: Some("family_id"),
            target_field: Some("family_id"),
            disposition: FieldDisposition::Keep,
            note: "PK / FK",
        },
        FieldMapping {
            table: "family_meta",
            source_field: Some("rev"),
            target_field: Some("rev"),
            disposition: FieldDisposition::Keep,
            note: "Family pull cursor",
        },
    ]
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::CURRENT_SCHEMA_SQL;
    use rusqlite::Connection;
    use std::collections::{BTreeMap, BTreeSet};

    #[test]
    fn target_user_version_couples_to_store_schema_version() {
        assert_eq!(TARGET_USER_VERSION, DATABASE_SCHEMA_VERSION);
        assert_eq!(SOURCE_USER_VERSION, 3);
    }

    #[test]
    fn flow_is_copy_out_upgrade_validate_copy_back_tls() {
        assert_eq!(
            migration_flow_steps(),
            &[
                MigrationFlowStep::CopyOutNasBackup,
                MigrationFlowStep::LocalOneShotUpgrade,
                MigrationFlowStep::ValidateCurrentPreflight,
                MigrationFlowStep::CopyBackToNas,
                MigrationFlowStep::StartTlsAndAccept,
            ]
        );
    }

    #[test]
    fn product_boundary_and_policies_are_locked_enums() {
        assert_eq!(migration_scope(), MigrationScope::PrivateOfflineOpsOnly);
        assert_eq!(startup_upgrade(), StartupUpgrade::Forbidden);
        assert_eq!(failure_mode(), FailureMode::AbortNoCopyBackWithReport);
        assert_eq!(
            legacy_credential_disposition(),
            LegacyCredentialDisposition::DiscardAll
        );
        assert_eq!(owner_reauth(), OwnerReauth::NewRootPasswordAtMigration);
        assert_eq!(member_reauth(), MemberReauth::CurrentRequestOrGrantFlows);
        assert_eq!(
            device_session_after_migrate(),
            DeviceSessionAfterMigrate::NoneUsable
        );
        assert_eq!(
            unknown_source_table_policy(),
            UnknownSourceTablePolicy::FailClosed
        );
        assert_eq!(
            staging_cascade(),
            StagingCascade::DropDependentsReportOrphanBytesIgnore
        );
        let payload = payload_validation_policy();
        assert!(payload.validate_tombstones);
        assert!(payload.persist_canonical_payload);
        assert!(payload.recompute_content_hash);
        assert!(payload.validate_media_entities_json);
    }

    #[test]
    fn authoritative_failure_set_is_closed_and_non_empty() {
        let set = authoritative_failures();
        assert!(set.len() >= 10);
        assert!(set.contains(&AuthoritativeFailure::SourceShapeMismatch));
        assert!(set.contains(&AuthoritativeFailure::UnknownSourceUserTable));
        assert!(set.contains(&AuthoritativeFailure::SourceConstrainedValueInvalid));
        assert!(set.contains(&AuthoritativeFailure::NotExactlyOneActiveOwner));
        assert!(set.contains(&AuthoritativeFailure::ContentHashMismatchAfterCanonicalize));
        assert!(set.contains(&AuthoritativeFailure::MediaFileMissingOrMismatch));
    }

    #[test]
    fn allowed_entity_types_match_source_schema_check() {
        for ty in ALLOWED_ENTITY_TYPES {
            assert!(
                SOURCE_V3_SCHEMA_SQL.contains(&format!("'{ty}'")),
                "SOURCE_V3_SCHEMA_SQL missing entity type {ty}"
            );
            assert!(
                CURRENT_SCHEMA_SQL.contains(&format!("'{ty}'")),
                "CURRENT_SCHEMA_SQL missing entity type {ty}"
            );
        }
        assert!(is_discarded_bundle_status(BUNDLE_STATUS_STAGING));
        assert!(!is_discarded_bundle_status(BUNDLE_STATUS_COMMITTED));
        assert_eq!(
            sync_bundles_row_filter(),
            RowFilter::DiscardRowsWhenEquals {
                column: "status",
                value: BUNDLE_STATUS_STAGING,
            }
        );
        let shells = target_only_empty_tables();
        for required in [
            "devices",
            "device_sessions",
            "refresh_token_history",
            "terminal_credential_denials",
            "owner_login_requests",
            "member_login_requests",
            "member_login_grants",
            "member_rename_requests",
        ] {
            assert!(
                shells.contains(&required),
                "target_only_empty missing {required}"
            );
        }
    }

    #[test]
    fn staging_bundles_use_row_filter_not_field_transform() {
        let bundles = table_dispositions()
            .iter()
            .find(|row| row.source_or_target_name == "sync_bundles")
            .expect("sync_bundles");
        assert_eq!(
            bundles.row_filter,
            RowFilter::DiscardRowsWhenEquals {
                column: "status",
                value: "staging",
            }
        );
        assert!(field_mappings().iter().any(|row| {
            row.table == "sync_bundles"
                && row.source_field == Some("status")
                && row.disposition == FieldDisposition::Keep
        }));
        assert!(!field_mappings().iter().any(|row| {
            row.table == "sync_bundles" && row.disposition == FieldDisposition::Derive
        }));
    }

    #[test]
    fn departed_memberships_use_row_filter_hard_delete_not_left_at_keep() {
        assert_eq!(
            memberships_row_filter(),
            RowFilter::DiscardRowsWhenNotNull { column: "left_at" }
        );
        assert!(is_departed_membership_left_at(Some(1_700_000_000)));
        assert!(!is_departed_membership_left_at(None));
        let memberships = table_dispositions()
            .iter()
            .find(|row| row.source_or_target_name == "memberships")
            .expect("memberships");
        assert!(
            memberships
                .note
                .to_ascii_lowercase()
                .contains("hard-delete")
                || memberships.note.contains("anonymize"),
            "memberships disposition must document hard-delete + anonymize: {}",
            memberships.note
        );
        assert!(
            !memberships.note.contains("Keep role/display_name/left_at"),
            "must not claim left_at keep for departed identity: {}",
            memberships.note
        );
        let left_at = field_mappings()
            .iter()
            .find(|row| {
                row.table == "memberships"
                    && row.source_field == Some("left_at")
                    && row.target_field == Some("left_at")
            })
            .expect("left_at field map");
        assert!(
            left_at.note.contains("active") || left_at.note.contains("NULL"),
            "left_at map is for retained active rows only: {}",
            left_at.note
        );
        assert!(
            !left_at.note.to_ascii_lowercase().contains("soft-left"),
            "must not document soft-left keep: {}",
            left_at.note
        );
    }

    #[test]
    fn entity_validation_context_maps_media_and_roots() {
        assert_eq!(
            entity_validation_context("media"),
            EntityValidationContext::AtomicBundleMedia
        );
        for root in [
            "baby",
            "record",
            "care_plan",
            "custom_item",
            "fulfillment_candidate",
        ] {
            assert_eq!(
                entity_validation_context(root),
                EntityValidationContext::AtomicBundleRoot
            );
        }
    }

    #[test]
    fn table_inventory_covers_required_and_session_tables() {
        let by_name: BTreeSet<_> = table_dispositions()
            .iter()
            .map(|row| row.source_or_target_name)
            .collect();
        for required in [
            "families",
            "memberships",
            "entities",
            "sync_bundles",
            "sync_bundle_media",
            "media_publications",
            "invites",
            "membership_credentials",
            "family_meta",
            "devices",
            "device_sessions",
        ] {
            assert!(by_name.contains(required), "missing {required}");
        }
    }

    #[test]
    fn invites_and_credentials_are_discarded() {
        let kind = |name: &str| {
            table_dispositions()
                .iter()
                .find(|row| row.source_or_target_name == name)
                .map(|row| row.kind)
                .expect(name)
        };
        assert_eq!(kind("invites"), TableDispositionKind::Discard);
        assert_eq!(
            kind("membership_credentials"),
            TableDispositionKind::Discard
        );
    }

    #[test]
    fn field_disposition_samples_use_algebra_not_overloaded_transform() {
        assert!(field_mappings().iter().any(|row| {
            row.table == "memberships"
                && row.source_field == Some("device_id")
                && row.disposition == FieldDisposition::DropColumn
        }));
        assert!(field_mappings().iter().any(|row| {
            row.table == "memberships"
                && row.target_field == Some("display_name_key")
                && row.disposition == FieldDisposition::Derive
        }));
        assert!(field_mappings().iter().any(|row| {
            row.table == "memberships"
                && row.source_field == Some("role")
                && row.disposition == FieldDisposition::Validate
        }));
        assert!(field_mappings().iter().any(|row| {
            row.table == "entities"
                && row.source_field == Some("payload_json")
                && row.disposition == FieldDisposition::Validate
        }));
        assert!(field_mappings().iter().any(|row| {
            row.table == "media_publications"
                && row.source_field == Some("source")
                && row.disposition == FieldDisposition::Validate
        }));
        assert!(field_mappings().iter().any(|row| {
            row.table == "sync_bundles"
                && row.source_field == Some("media_entities_json")
                && row.disposition == FieldDisposition::Validate
        }));
        assert!(field_mappings().iter().any(|row| {
            row.table == "sync_bundles"
                && row.source_field == Some("content_hash")
                && row.disposition == FieldDisposition::Validate
        }));
    }

    #[test]
    fn path_dispositions_cover_media_secret_tls_and_regenerate_secret() {
        let by_pattern: BTreeMap<_, _> = path_dispositions()
            .iter()
            .map(|row| (row.pattern, row.kind))
            .collect();
        assert_eq!(
            by_pattern.get("lezi.db"),
            Some(&PathDispositionKind::TransformWriteOut)
        );
        assert_eq!(
            by_pattern.get("server.secret"),
            Some(&PathDispositionKind::RegenerateAlways)
        );
        assert_eq!(
            by_pattern.get("media/{family_uuid}/{media_uuid}"),
            Some(&PathDispositionKind::KeepWithDbValidation)
        );
        assert_eq!(
            by_pattern.get("tls/"),
            Some(&PathDispositionKind::AbsentOrCreateAtCutover)
        );
        // lezi.db is out-of-place transform, not byte-keep like media.
        assert_ne!(
            by_pattern.get("lezi.db"),
            by_pattern.get("media/{family_uuid}/{media_uuid}")
        );
    }

    #[test]
    fn source_v3_schema_sql_matches_column_inventory() {
        let connection = Connection::open_in_memory().unwrap();
        connection.execute_batch(SOURCE_V3_SCHEMA_SQL).unwrap();
        for table in source_v3_tables() {
            let mut statement = connection
                .prepare(&format!("PRAGMA table_info('{}')", table.name))
                .unwrap();
            let rows: Vec<(String, String, bool, bool)> = statement
                .query_map([], |row| {
                    let name: String = row.get(1)?;
                    let sql_type: String = row.get(2)?;
                    let not_null: i64 = row.get(3)?;
                    let pk: i64 = row.get(5)?;
                    Ok((name, sql_type, not_null != 0, pk != 0))
                })
                .unwrap()
                .collect::<Result<_, _>>()
                .unwrap();
            let expected: BTreeSet<_> = table.columns.iter().map(|c| c.name).collect();
            let found: BTreeSet<_> = rows.iter().map(|(n, _, _, _)| n.as_str()).collect();
            assert_eq!(found, expected, "columns for {}", table.name);
            for col in table.columns {
                let row = rows
                    .iter()
                    .find(|(n, _, _, _)| n == col.name)
                    .unwrap_or_else(|| panic!("{} missing {}", table.name, col.name));
                assert_eq!(
                    row.1.to_uppercase(),
                    col.sql_type.to_uppercase(),
                    "{} {}",
                    table.name,
                    col.name
                );
                // SQLite PRIMARY KEY columns are implicitly NOT NULL even if pragma notnull=0.
                let effective_not_null = row.2 || row.3;
                assert_eq!(
                    effective_not_null, col.not_null,
                    "not_null {} {}",
                    table.name, col.name
                );
                assert_eq!(row.3, col.primary_key, "pk {} {}", table.name, col.name);
            }
        }
    }

    #[test]
    fn source_allowlist_is_exactly_source_v3_tables() {
        let allow: BTreeSet<_> = source_v3_tables().iter().map(|t| t.name).collect();
        let disposed: BTreeSet<_> = table_dispositions()
            .iter()
            .filter(|row| {
                matches!(
                    row.kind,
                    TableDispositionKind::KeepOrTransform | TableDispositionKind::Discard
                )
            })
            .map(|row| row.source_or_target_name)
            .collect();
        assert_eq!(allow, disposed);
    }

    #[test]
    fn field_mappings_cover_every_target_column_for_keep_tables() {
        let connection = Connection::open_in_memory().unwrap();
        connection.execute_batch(CURRENT_SCHEMA_SQL).unwrap();

        let keep_tables: BTreeSet<_> = table_dispositions()
            .iter()
            .filter(|row| row.kind == TableDispositionKind::KeepOrTransform)
            .map(|row| row.source_or_target_name)
            .collect();

        for table in keep_tables {
            let mut statement = connection
                .prepare(&format!("PRAGMA table_info('{table}')"))
                .unwrap();
            let columns: BTreeSet<String> = statement
                .query_map([], |row| row.get::<_, String>(1))
                .unwrap()
                .collect::<Result<_, _>>()
                .unwrap();
            let mapped: BTreeSet<_> = field_mappings()
                .iter()
                .filter(|row| row.table == table)
                .filter_map(|row| row.target_field)
                .collect();
            for column in &columns {
                assert!(
                    mapped.contains(column.as_str()),
                    "keep table {table} missing field map target for column {column}"
                );
            }
            for target in mapped {
                assert!(
                    columns.contains(target),
                    "field map for {table}.{target} is not a current schema column"
                );
            }
        }
    }

    #[test]
    fn target_only_tables_have_no_keep_field_mappings() {
        for table in table_dispositions()
            .iter()
            .filter(|row| row.kind == TableDispositionKind::TargetOnlyEmpty)
        {
            let keepish: Vec<_> = field_mappings()
                .iter()
                .filter(|row| {
                    row.table == table.source_or_target_name
                        && matches!(
                            row.disposition,
                            FieldDisposition::Keep
                                | FieldDisposition::Validate
                                | FieldDisposition::Derive
                                | FieldDisposition::TargetAdd
                        )
                })
                .collect();
            assert!(
                keepish.is_empty(),
                "TargetOnlyEmpty {} must not have keep mappings",
                table.source_or_target_name
            );
        }
    }

    #[test]
    fn discard_tables_only_drop_columns() {
        for table in ["invites", "membership_credentials"] {
            assert!(field_mappings()
                .iter()
                .filter(|row| row.table == table)
                .all(|row| row.disposition == FieldDisposition::DropColumn));
        }
    }
}
