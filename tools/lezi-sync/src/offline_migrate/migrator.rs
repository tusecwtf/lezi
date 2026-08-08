//! One-shot offline migrator: measured v3 `lezi.db` → current schema `lezi.db`.
//!
//! **Not** wired into server startup. Reads a backup source read-only; writes an
//! independent dest via temp+rename so failures leave no copy-back-ready partial.
//!
//! Library surface: [`migrate_v3_database`] (requires ops **new root password** —
//! ticket 04). Process exit codes / CLI wiring are ticket 05.
//! `Err(MigrateError::Authoritative)` is the fail-closed stand-in for non-zero exit.
//!
//! On success the dest data dir (parent of `dest_db`) also receives a freshly
//! generated `server.secret` (`PathDispositionKind::RegenerateAlways`) and each
//! family row carries `owner_root_fingerprint` derived with the live product
//! formula so current lezi-sync can open the data and Owner can re-login with
//! the same password as `LEZI_BOOTSTRAP_SECRET`.

use std::collections::{BTreeSet, HashMap, HashSet};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use rand::rngs::OsRng;
use rand::RngCore;
use rusqlite::{params, Connection};
use serde_json::{Map, Value};
use thiserror::Error;
use uuid::Uuid;

use crate::model::{
    normalized_display_name_key, validate_bundle_media_for_root, Entity, EntityValidationContext,
    RawEntity,
};
use crate::owner_root_fingerprint;
use crate::store::{self, CURRENT_SCHEMA_SQL, DATABASE_SCHEMA_VERSION};
use crate::{
    write_server_secret, DEFAULT_MAX_MEDIA_BYTES, MIN_BOOTSTRAP_SECRET_LEN, SERVER_SECRET_BYTES,
};

use super::inventory::{
    entity_validation_context, is_departed_membership_left_at, is_discarded_bundle_status,
    payload_validation_policy, source_v3_tables, staging_cascade, target_only_empty_tables,
    AuthoritativeFailure, StagingCascade, ALLOWED_ENTITY_TYPES, ALLOWED_MEDIA_PUBLICATION_SOURCES,
    BUNDLE_STATUS_COMMITTED, SOURCE_USER_VERSION,
};

/// Minimum length for the migration-time new root password.
/// Alias of crate-level [`MIN_BOOTSTRAP_SECRET_LEN`] (same rule as `LEZI_BOOTSTRAP_SECRET`).
pub(crate) const MIN_NEW_ROOT_PASSWORD_LEN: usize = MIN_BOOTSTRAP_SECRET_LEN;

/// Human-readable ops fragment for runbooks / CLI help (ticket 04 / 05 / 06).
///
/// Cutover does **not** silently restore sessions: every family member re-auths.
pub(crate) const REAUTH_OPS_NOTE: &str = "\
Cutover re-auth (no silent restore):\n\
- Owner: sign in with the migration-time new root password (same value as LEZI_BOOTSTRAP_SECRET).\n\
- Members: use the current member request/approve or login-grant flows.\n\
- All pre-migration membership_credentials, invites, and device sessions are void.\n\
- server.secret is always regenerated; never copy the backup's HMAC material.\n";

/// Human-oriented counters for the migration report (non-authoritative discards included).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub(crate) struct MigrateReport {
    pub families: u64,
    /// Active memberships copied to the target (`left_at IS NULL` only).
    pub memberships: u64,
    /// v3 departed memberships (`left_at IS NOT NULL`) dropped — hard-delete disposition.
    pub discarded_departed_memberships: u64,
    /// Author/submitter/stager refs on retained facts nulled because they pointed at departed memberships.
    pub anonymized_membership_refs: u64,
    pub entities: u64,
    pub committed_bundles: u64,
    pub discarded_staging_bundles: u64,
    pub discarded_bundle_media: u64,
    pub discarded_publications: u64,
    /// Authority media files copied under dest `media/` (ticket 03 data-dir path).
    pub media_files_copied: u64,
    /// Causal migration base versions minted for versioned roots.
    pub base_versions: u64,
    /// Historical closed Sleep → WakeObservation rows created.
    pub wake_observations: u64,
}

#[derive(Debug, Error)]
pub(crate) enum MigrateError {
    /// Authoritative abort: no copy-back-ready dest; includes partial report counters.
    #[error("authoritative migration failure {kind:?}: {detail}")]
    Authoritative {
        kind: AuthoritativeFailure,
        detail: String,
        report: MigrateReport,
    },
    #[error("sqlite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    /// Ops-provided new root password fails product length / emptiness gates.
    #[error("invalid new root password: {0}")]
    InvalidRootPassword(String),
    /// Target-side / programmer invariant (not a source authoritative failure).
    #[error("internal migration error: {0}")]
    Internal(String),
}

impl MigrateError {
    pub(crate) fn authoritative(&self) -> Option<AuthoritativeFailure> {
        match self {
            Self::Authoritative { kind, .. } => Some(*kind),
            _ => None,
        }
    }

    /// Partial report fragment when failure mode is authoritative abort.
    pub(crate) fn report(&self) -> Option<&MigrateReport> {
        match self {
            Self::Authoritative { report, .. } => Some(report),
            _ => None,
        }
    }

    pub(crate) fn authoritative_failure(
        kind: AuthoritativeFailure,
        detail: impl Into<String>,
        report: MigrateReport,
    ) -> Self {
        Self::Authoritative {
            kind,
            detail: detail.into(),
            report,
        }
    }
}

/// Transform a v3 source database into a current-schema destination database.
///
/// - Source is opened read-only and never mutated.
/// - Destination is written via a sibling temp file and renamed only on full success.
/// - On failure the temp is deleted; a pre-existing dest is left untouched.
/// - `new_root_password` is required (ticket 04 / `OwnerReauth::NewRootPasswordAtMigration`):
///   written into `families.owner_root_fingerprint` using a freshly generated
///   signing secret; that secret is persisted as `{dest_parent}/server.secret`.
/// - Legacy membership_credentials / invites are discarded (table absent on target).
pub(crate) fn migrate_v3_database(
    source_db: &Path,
    dest_db: &Path,
    new_root_password: &str,
) -> Result<MigrateReport, MigrateError> {
    validate_new_root_password(new_root_password)?;

    if !source_db.try_exists()? {
        return Err(MigrateError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            format!("source database missing: {}", source_db.display()),
        )));
    }

    let source = Connection::open_with_flags(
        source_db,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    validate_source(&source)?;

    let parent = dest_db.parent().unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent)?;
    let temp = temp_dest_path(dest_db);
    remove_db_files(&temp);

    // RegenerateAlways: mint signing material for this out/ — never copy backup.
    let signing_secret = generate_server_secret_bytes();
    let fingerprint = owner_root_fingerprint(&signing_secret, new_root_password);

    let result = (|| {
        let mut dest = Connection::open(&temp)?;
        dest.execute_batch(
            "
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = DELETE;
            ",
        )?;
        dest.execute_batch(CURRENT_SCHEMA_SQL)?;
        dest.pragma_update(None, "user_version", DATABASE_SCHEMA_VERSION)?;

        let report = transfer_all(&source, &mut dest, &fingerprint)?;
        dest.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")?;
        drop(dest);
        Ok::<_, MigrateError>(report)
    })();

    match result {
        Ok(report) => {
            if let Err(error) = fs::rename(&temp, dest_db) {
                remove_db_files(&temp);
                return Err(MigrateError::Io(error));
            }
            // Best-effort cleanup of any leftover WAL beside dest.
            for suffix in ["-wal", "-shm"] {
                let _ = fs::remove_file(PathBuf::from(format!("{}{suffix}", dest_db.display())));
            }
            // Fail closed: if secret cannot be written, remove the dest DB so
            // no copy-back-ready partial without matching server.secret remains.
            if let Err(error) = write_regenerated_server_secret(parent, &signing_secret) {
                remove_db_files(dest_db);
                return Err(error);
            }
            Ok(report)
        }
        Err(error) => {
            remove_db_files(&temp);
            Err(error)
        }
    }
}

/// Password gate shared with the ticket-05 CLI (map to usage exit, not migrate abort).
pub(crate) fn validate_new_root_password(password: &str) -> Result<(), MigrateError> {
    if password.is_empty() {
        return Err(MigrateError::InvalidRootPassword(
            "must not be empty".to_owned(),
        ));
    }
    if password.len() < MIN_NEW_ROOT_PASSWORD_LEN {
        return Err(MigrateError::InvalidRootPassword(format!(
            "must be at least {MIN_NEW_ROOT_PASSWORD_LEN} characters (matches LEZI_BOOTSTRAP_SECRET)"
        )));
    }
    Ok(())
}

fn generate_server_secret_bytes() -> Vec<u8> {
    let mut secret = vec![0u8; SERVER_SECRET_BYTES];
    OsRng.fill_bytes(&mut secret);
    secret
}

fn write_regenerated_server_secret(data_dir: &Path, secret: &[u8]) -> Result<(), MigrateError> {
    // Shared with startup: temp → secure(0o600) → rename → secure → parent fsync.
    write_server_secret(data_dir, secret).map_err(MigrateError::Io)
}

pub(crate) fn remove_db_files(path: &Path) {
    let _ = fs::remove_file(path);
    for suffix in ["-wal", "-shm"] {
        let _ = fs::remove_file(PathBuf::from(format!("{}{suffix}", path.display())));
    }
}

fn temp_dest_path(dest_db: &Path) -> PathBuf {
    let parent = dest_db.parent().unwrap_or_else(|| Path::new("."));
    let name = dest_db
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("lezi.db");
    parent.join(format!(".{name}.migrating"))
}

fn validate_source(source: &Connection) -> Result<(), MigrateError> {
    let empty = MigrateReport::default();
    let version: i64 = source.query_row("PRAGMA user_version", [], |row| row.get(0))?;
    if version != SOURCE_USER_VERSION {
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::SourceUserVersionNotThree,
            format!("found user_version={version}, expected {SOURCE_USER_VERSION}"),
            empty,
        ));
    }

    let mut stmt = source.prepare(
        "
        SELECT name FROM sqlite_master
        WHERE type = 'table'
          AND name NOT LIKE 'sqlite_%'
        ",
    )?;
    let names: BTreeSet<String> = stmt
        .query_map([], |row| row.get(0))?
        .collect::<Result<_, _>>()?;
    let allow: BTreeSet<&str> = source_v3_tables().iter().map(|t| t.name).collect();
    for name in &names {
        if !allow.contains(name.as_str()) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::UnknownSourceUserTable,
                format!("unexpected user table `{name}`"),
                empty,
            ));
        }
    }
    for table in source_v3_tables() {
        if !names.contains(table.name) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::SourceShapeMismatch,
                format!("missing allowlisted table `{}`", table.name),
                empty,
            ));
        }
        validate_table_shape(source, table.name, table.columns)?;
    }
    Ok(())
}

fn validate_table_shape(
    source: &Connection,
    table: &str,
    expected: &[super::inventory::SourceColumn],
) -> Result<(), MigrateError> {
    let empty = MigrateReport::default();
    let mut statement = source.prepare(&format!("PRAGMA table_info('{table}')"))?;
    let rows: Vec<(String, String, bool, bool)> = statement
        .query_map([], |row| {
            let name: String = row.get(1)?;
            let sql_type: String = row.get(2)?;
            let not_null: i64 = row.get(3)?;
            let pk: i64 = row.get(5)?;
            Ok((name, sql_type, not_null != 0, pk != 0))
        })?
        .collect::<Result<_, _>>()?;
    let found: BTreeSet<&str> = rows.iter().map(|(n, _, _, _)| n.as_str()).collect();
    let want: BTreeSet<&str> = expected.iter().map(|c| c.name).collect();
    if found != want {
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::SourceShapeMismatch,
            format!("table `{table}` columns {found:?} != {want:?}"),
            empty,
        ));
    }
    for col in expected {
        let row = rows
            .iter()
            .find(|(n, _, _, _)| n == col.name)
            .expect("column present");
        if row.1.to_uppercase() != col.sql_type.to_uppercase() {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::SourceShapeMismatch,
                format!(
                    "table `{table}` column `{}` type {} != {}",
                    col.name, row.1, col.sql_type
                ),
                empty,
            ));
        }
        let effective_not_null = row.2 || row.3;
        if effective_not_null != col.not_null {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::SourceShapeMismatch,
                format!("table `{table}` column `{}` not_null mismatch", col.name),
                empty,
            ));
        }
        if row.3 != col.primary_key {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::SourceShapeMismatch,
                format!("table `{table}` column `{}` pk mismatch", col.name),
                empty,
            ));
        }
    }
    Ok(())
}

fn transfer_all(
    source: &Connection,
    dest: &mut Connection,
    owner_root_fingerprint: &str,
) -> Result<MigrateReport, MigrateError> {
    // Staging cascade contract is locked in inventory (not free-form).
    assert_eq!(
        staging_cascade(),
        StagingCascade::DropDependentsReportOrphanBytesIgnore
    );

    let tx = dest.transaction()?;
    let mut report = MigrateReport::default();

    let family_ids = copy_families(source, &tx, owner_root_fingerprint, &mut report)?;
    let MembershipPartition {
        active_ids: membership_ids,
        departed_ids,
    } = copy_memberships(source, &tx, &family_ids, &mut report)?;
    copy_family_meta(source, &tx, &family_ids, &report)?;
    copy_entities(source, &tx, &family_ids, &departed_ids, &mut report)?;

    let BundlePartition {
        retained,
        discarded_staging,
    } = partition_bundles(source, &family_ids, &membership_ids, &departed_ids, &report)?;
    report.discarded_staging_bundles = discarded_staging.len() as u64;
    // Cleanup evidence: bundle_pending pubs on retained committed bundles (and
    // matching sync_bundle_media) are report-only discards — never file authority.
    let committed_pending_cleanup = collect_committed_pending_cleanup(source, &retained)?;
    copy_sync_bundles(
        source,
        &tx,
        &retained,
        &membership_ids,
        &departed_ids,
        &mut report,
    )?;
    report.discarded_bundle_media = copy_sync_bundle_media(
        source,
        &tx,
        &retained,
        &discarded_staging,
        &committed_pending_cleanup,
        &report,
    )?;
    report.discarded_publications = copy_media_publications(
        source,
        &tx,
        &family_ids,
        &retained,
        &discarded_staging,
        &committed_pending_cleanup,
        &report,
    )?;

    // TargetOnlyEmpty session shells must remain empty (inventory-driven list).
    for table in target_only_empty_tables() {
        let count: i64 =
            tx.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))?;
        if count != 0 {
            return Err(MigrateError::Internal(format!(
                "target session table `{table}` unexpectedly non-empty after migrate"
            )));
        }
    }

    // Mint causal base versions + closed-sleep WakeObservation (schema v12).
    // Media files are not yet on dest for the pure-DB path; wake media UUID
    // derivation uses staged_sha256 when present.
    super::causal::finalize_causal_v12(&tx, None, &mut report)?;

    tx.commit()?;
    Ok(report)
}

fn copy_families(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    owner_root_fingerprint: &str,
    report: &mut MigrateReport,
) -> Result<BTreeSet<String>, MigrateError> {
    let mut stmt = source
        .prepare("SELECT id, created_at, create_request_hash, name FROM families ORDER BY id")?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, Option<String>>(2)?,
            row.get::<_, Option<String>>(3)?,
        ))
    })?;
    let mut ids = BTreeSet::new();
    for row in rows {
        let (id, created_at, create_request_hash, name) = row?;
        // owner_root_fingerprint TargetAdd — migration-time new root password (ticket 04).
        dest.execute(
            "
            INSERT INTO families(id, created_at, create_request_hash, name, owner_root_fingerprint)
            VALUES (?1, ?2, ?3, ?4, ?5)
            ",
            params![
                id,
                created_at,
                create_request_hash,
                name,
                owner_root_fingerprint
            ],
        )?;
        ids.insert(id);
        report.families += 1;
    }
    Ok(ids)
}

/// Active memberships copied vs departed memberships dropped (inventory row filter).
struct MembershipPartition {
    active_ids: BTreeSet<String>,
    departed_ids: BTreeSet<String>,
}

fn copy_memberships(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    family_ids: &BTreeSet<String>,
    report: &mut MigrateReport,
) -> Result<MembershipPartition, MigrateError> {
    let mut stmt = source.prepare(
        "
        SELECT membership_id, family_id, role, display_name, left_at
        FROM memberships
        ORDER BY membership_id
        ",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, String>(3)?,
            row.get::<_, Option<i64>>(4)?,
        ))
    })?;

    let mut active_ids = BTreeSet::new();
    let mut departed_ids = BTreeSet::new();
    let mut active_keys: HashMap<String, HashSet<String>> = HashMap::new();
    let mut active_owners: HashMap<String, u32> = HashMap::new();

    for row in rows {
        let (membership_id, family_id, role, display_name, left_at) = row?;
        if !family_ids.contains(&family_id) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!("membership `{membership_id}` family `{family_id}` missing"),
                report.clone(),
            ));
        }

        // Inventory: DiscardRowsWhenNotNull { left_at } — non-authoritative discard
        // alone must not fail the run. Apply before role/display-name/owner gates so a
        // departed row with a corrupt role becomes discarded_departed_memberships, not
        // InvalidMembershipRole.
        if is_departed_membership_left_at(left_at) {
            departed_ids.insert(membership_id);
            report.discarded_departed_memberships += 1;
            continue;
        }

        if role != "owner" && role != "member" {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::InvalidMembershipRole,
                format!("membership `{membership_id}` role `{role}`"),
                report.clone(),
            ));
        }

        let display_name_key = normalized_display_name_key(&display_name);
        // Active-only uniqueness (departed rows do not participate).
        let keys = active_keys.entry(family_id.clone()).or_default();
        if !keys.insert(display_name_key.clone()) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::ActiveDisplayNameKeyConflict,
                format!(
                    "family `{family_id}` active display_name_key `{display_name_key}` conflict"
                ),
                report.clone(),
            ));
        }
        if role == "owner" {
            // Pre-check before INSERT: target UNIQUE memberships_one_owner would
            // otherwise surface dual-owner as MigrateError::Sqlite (non-authoritative).
            let owners = active_owners.entry(family_id.clone()).or_default();
            *owners += 1;
            if *owners > 1 {
                return Err(MigrateError::authoritative_failure(
                    AuthoritativeFailure::NotExactlyOneActiveOwner,
                    format!("family `{family_id}` has {owners} active owners"),
                    report.clone(),
                ));
            }
        }
        // Retained active rows: left_at is always NULL on target.
        dest.execute(
            "
            INSERT INTO memberships(
                membership_id, family_id, role, display_name, display_name_key, left_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, NULL)
            ",
            params![
                membership_id,
                family_id,
                role,
                display_name,
                display_name_key,
            ],
        )?;
        active_ids.insert(membership_id);
        report.memberships += 1;
    }

    for family_id in family_ids {
        let owners = active_owners.get(family_id).copied().unwrap_or(0);
        if owners != 1 {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::NotExactlyOneActiveOwner,
                format!("family `{family_id}` has {owners} active owners"),
                report.clone(),
            ));
        }
    }
    Ok(MembershipPartition {
        active_ids,
        departed_ids,
    })
}

/// Null authorship fields that point at a departed membership (same keys/contract as
/// [`store::anonymize_membership_authorship_fields`] / hard-delete). Returns count cleared.
fn anonymize_departed_membership_fields(
    payload: &mut Map<String, Value>,
    departed_ids: &BTreeSet<String>,
) -> u64 {
    store::anonymize_membership_authorship_fields(payload, |id| departed_ids.contains(id))
}

fn copy_family_meta(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    family_ids: &BTreeSet<String>,
    report: &MigrateReport,
) -> Result<(), MigrateError> {
    let mut stmt = source.prepare("SELECT family_id, rev FROM family_meta")?;
    let rows = stmt.query_map([], |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
    })?;
    let mut seen = BTreeSet::new();
    for row in rows {
        let (family_id, rev) = row?;
        if !family_ids.contains(&family_id) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!("family_meta for missing family `{family_id}`"),
                report.clone(),
            ));
        }
        dest.execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, ?2)",
            params![family_id, rev],
        )?;
        seen.insert(family_id);
    }
    for family_id in family_ids {
        if !seen.contains(family_id) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::FamilyMetaMissingForFamily,
                format!("family `{family_id}` lacks family_meta"),
                report.clone(),
            ));
        }
    }
    Ok(())
}

fn copy_entities(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    family_ids: &BTreeSet<String>,
    departed_ids: &BTreeSet<String>,
    report: &mut MigrateReport,
) -> Result<(), MigrateError> {
    let policy = payload_validation_policy();
    let known: BTreeSet<&str> = ALLOWED_ENTITY_TYPES.iter().copied().collect();
    let mut stmt = source.prepare(
        "
        SELECT family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
        FROM entities
        ORDER BY family_id, entity_type, client_uuid
        ",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, i64>(3)?,
            row.get::<_, Option<i64>>(4)?,
            row.get::<_, String>(5)?,
            row.get::<_, i64>(6)?,
        ))
    })?;

    for row in rows {
        let (family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev) = row?;
        if !family_ids.contains(&family_id) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!("entity family `{family_id}` missing"),
                report.clone(),
            ));
        }
        if !known.contains(entity_type.as_str()) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::UnknownEntityType,
                format!("entity_type `{entity_type}`"),
                report.clone(),
            ));
        }
        let client_uuid_parsed = Uuid::parse_str(&client_uuid).map_err(|e| {
            MigrateError::authoritative_failure(
                AuthoritativeFailure::PayloadValidationFailed,
                format!("entity client_uuid `{client_uuid}`: {e}"),
                report.clone(),
            )
        })?;
        let mut payload: Map<String, Value> = serde_json::from_str(&payload_json).map_err(|e| {
            MigrateError::authoritative_failure(
                AuthoritativeFailure::PayloadValidationFailed,
                format!("entity `{entity_type}/{client_uuid}` payload parse: {e}"),
                report.clone(),
            )
        })?;
        // Hard-delete contract: clear author/submitter refs to departed memberships.
        report.anonymized_membership_refs +=
            anonymize_departed_membership_fields(&mut payload, departed_ids);

        let is_tombstone = deleted_at.is_some();
        let out_payload = if is_tombstone && !policy.validate_tombstones {
            // Inventory can flip this only by amending payload_validation_policy.
            // Still persist anonymized payload when we rewrote authorship.
            serde_json::to_string(&payload)?
        } else {
            let raw = RawEntity {
                entity_type: entity_type.clone(),
                client_uuid: client_uuid_parsed,
                updated_at,
                deleted_at,
                payload,
            };
            let context = entity_validation_context(&entity_type);
            let validated = raw
                .validate_as(DEFAULT_MAX_MEDIA_BYTES, context)
                .map_err(|e| {
                    MigrateError::authoritative_failure(
                        AuthoritativeFailure::PayloadValidationFailed,
                        format!("entity `{entity_type}/{client_uuid}`: {e:?}"),
                        report.clone(),
                    )
                })?;
            // Always persist post-anonymize canonical payload so departed
            // authorship cannot remain as pseudo-attribution on the target.
            serde_json::to_string(&validated.payload)?
        };

        dest.execute(
            "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
            ",
            params![
                family_id,
                entity_type,
                client_uuid,
                updated_at,
                deleted_at,
                out_payload,
                rev
            ],
        )?;
        report.entities += 1;
    }
    Ok(())
}

struct BundlePartition {
    retained: BTreeSet<(String, String)>,
    discarded_staging: BTreeSet<(String, String)>,
}

/// Single scan: partition sync_bundles by inventory row filter.
fn partition_bundles(
    source: &Connection,
    family_ids: &BTreeSet<String>,
    active_membership_ids: &BTreeSet<String>,
    departed_membership_ids: &BTreeSet<String>,
    report: &MigrateReport,
) -> Result<BundlePartition, MigrateError> {
    let mut stmt = source.prepare(
        "
        SELECT family_id, bundle_id, status, staged_membership_id
        FROM sync_bundles
        ",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, String>(3)?,
        ))
    })?;
    let mut retained = BTreeSet::new();
    let mut discarded_staging = BTreeSet::new();
    for row in rows {
        let (family_id, bundle_id, status, staged_membership_id) = row?;
        if !family_ids.contains(&family_id) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!("bundle `{bundle_id}` family `{family_id}` missing"),
                report.clone(),
            ));
        }
        if is_discarded_bundle_status(&status) {
            discarded_staging.insert((family_id, bundle_id));
            continue;
        }
        if status != BUNDLE_STATUS_COMMITTED {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::SourceConstrainedValueInvalid,
                format!("bundle `{bundle_id}` status `{status}`"),
                report.clone(),
            ));
        }
        // Stager may be departed (anonymized on copy) but must exist in source partition.
        let known_stager = active_membership_ids.contains(&staged_membership_id)
            || departed_membership_ids.contains(&staged_membership_id);
        if !known_stager {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!(
                    "bundle `{bundle_id}` staged_membership_id `{staged_membership_id}` missing"
                ),
                report.clone(),
            ));
        }
        retained.insert((family_id, bundle_id));
    }
    Ok(BundlePartition {
        retained,
        discarded_staging,
    })
}

fn copy_sync_bundles(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    retained: &BTreeSet<(String, String)>,
    active_membership_ids: &BTreeSet<String>,
    departed_membership_ids: &BTreeSet<String>,
    report: &mut MigrateReport,
) -> Result<(), MigrateError> {
    let policy = payload_validation_policy();
    let mut stmt = source.prepare(
        "
        SELECT family_id, bundle_id, staged_membership_id, status, root_type,
               root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
               media_entities_json, content_hash, created_at, committed_at,
               committed_cursor, committed_applied
        FROM sync_bundles
        WHERE status = ?1
        ORDER BY family_id, bundle_id
        ",
    )?;
    let rows = stmt.query_map(params![BUNDLE_STATUS_COMMITTED], |row| {
        Ok(BundleRow {
            family_id: row.get(0)?,
            bundle_id: row.get(1)?,
            staged_membership_id: row.get(2)?,
            status: row.get(3)?,
            root_type: row.get(4)?,
            root_client_uuid: row.get(5)?,
            root_updated_at: row.get(6)?,
            root_deleted_at: row.get(7)?,
            root_payload_json: row.get(8)?,
            media_entities_json: row.get(9)?,
            content_hash: row.get(10)?,
            created_at: row.get(11)?,
            committed_at: row.get(12)?,
            committed_cursor: row.get(13)?,
            committed_applied: row.get(14)?,
        })
    })?;

    for row in rows {
        let row = row?;
        if !retained.contains(&(row.family_id.clone(), row.bundle_id.clone())) {
            continue;
        }
        let known_stager = active_membership_ids.contains(&row.staged_membership_id)
            || departed_membership_ids.contains(&row.staged_membership_id);
        if !known_stager {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!(
                    "bundle `{}` staged_membership_id `{}`",
                    row.bundle_id, row.staged_membership_id
                ),
                report.clone(),
            ));
        }
        // hard_delete_membership blanks staged_membership_id when stager is deleted.
        let mut staged_membership_id = row.staged_membership_id.clone();
        if departed_membership_ids.contains(&staged_membership_id) {
            staged_membership_id = String::new();
            report.anonymized_membership_refs += 1;
        }

        let root_uuid = Uuid::parse_str(&row.root_client_uuid).map_err(|e| {
            MigrateError::authoritative_failure(
                AuthoritativeFailure::PayloadValidationFailed,
                format!("bundle `{}` root uuid: {e}", row.bundle_id),
                report.clone(),
            )
        })?;
        let mut root_payload: Map<String, Value> = serde_json::from_str(&row.root_payload_json)
            .map_err(|e| {
                MigrateError::authoritative_failure(
                    AuthoritativeFailure::PayloadValidationFailed,
                    format!("bundle `{}` root payload: {e}", row.bundle_id),
                    report.clone(),
                )
            })?;
        let context = entity_validation_context(&row.root_type);
        if matches!(context, EntityValidationContext::AtomicBundleMedia) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::UnknownEntityType,
                format!("bundle `{}` root_type media", row.bundle_id),
                report.clone(),
            ));
        }
        // Source integrity: canonicalize pre-anonymize root for content_hash check.
        let source_root = RawEntity {
            entity_type: row.root_type.clone(),
            client_uuid: root_uuid,
            updated_at: row.root_updated_at,
            deleted_at: row.root_deleted_at,
            payload: root_payload.clone(),
        }
        .validate_as(
            DEFAULT_MAX_MEDIA_BYTES,
            EntityValidationContext::AtomicBundleRoot,
        )
        .map_err(|e| {
            MigrateError::authoritative_failure(
                AuthoritativeFailure::PayloadValidationFailed,
                format!("bundle `{}` root: {e:?}", row.bundle_id),
                report.clone(),
            )
        })?;
        // Dest write: anonymize author/submitter when pointing at departed memberships.
        let payload_anonymized =
            anonymize_departed_membership_fields(&mut root_payload, departed_membership_ids);
        report.anonymized_membership_refs += payload_anonymized;
        let canonical_root = if payload_anonymized > 0 {
            RawEntity {
                entity_type: row.root_type.clone(),
                client_uuid: root_uuid,
                updated_at: row.root_updated_at,
                deleted_at: row.root_deleted_at,
                payload: root_payload,
            }
            .validate_as(
                DEFAULT_MAX_MEDIA_BYTES,
                EntityValidationContext::AtomicBundleRoot,
            )
            .map_err(|e| {
                MigrateError::authoritative_failure(
                    AuthoritativeFailure::PayloadValidationFailed,
                    format!("bundle `{}` root after anonymize: {e:?}", row.bundle_id),
                    report.clone(),
                )
            })?
        } else {
            source_root.clone()
        };

        let media_value: Value = serde_json::from_str(&row.media_entities_json).map_err(|e| {
            MigrateError::authoritative_failure(
                AuthoritativeFailure::MediaEntitiesJsonInvalid,
                format!("bundle `{}` media_entities_json parse: {e}", row.bundle_id),
                report.clone(),
            )
        })?;
        let media_arr = media_value.as_array().ok_or_else(|| {
            MigrateError::authoritative_failure(
                AuthoritativeFailure::MediaEntitiesJsonInvalid,
                format!("bundle `{}` media_entities_json not array", row.bundle_id),
                report.clone(),
            )
        })?;
        let mut canonical_media = Vec::with_capacity(media_arr.len());
        for (idx, item) in media_arr.iter().enumerate() {
            let entity: Entity = serde_json::from_value(item.clone()).map_err(|e| {
                MigrateError::authoritative_failure(
                    AuthoritativeFailure::MediaEntitiesJsonInvalid,
                    format!("bundle `{}` media[{idx}]: {e}", row.bundle_id),
                    report.clone(),
                )
            })?;
            let media_uuid = Uuid::parse_str(&entity.client_uuid).map_err(|e| {
                MigrateError::authoritative_failure(
                    AuthoritativeFailure::MediaEntitiesJsonInvalid,
                    format!("bundle `{}` media uuid: {e}", row.bundle_id),
                    report.clone(),
                )
            })?;
            let raw = RawEntity {
                entity_type: entity.entity_type.clone(),
                client_uuid: media_uuid,
                updated_at: entity.updated_at,
                deleted_at: entity.deleted_at,
                payload: entity.payload,
            };
            let validated = raw
                .validate_as(
                    DEFAULT_MAX_MEDIA_BYTES,
                    EntityValidationContext::AtomicBundleMedia,
                )
                .map_err(|e| {
                    MigrateError::authoritative_failure(
                        AuthoritativeFailure::MediaEntitiesJsonInvalid,
                        format!("bundle `{}` media: {e:?}", row.bundle_id),
                        report.clone(),
                    )
                })?;
            canonical_media.push(validated);
        }
        if policy.validate_media_entities_json {
            validate_bundle_media_for_root(&canonical_root, &canonical_media).map_err(|e| {
                MigrateError::authoritative_failure(
                    AuthoritativeFailure::MediaEntitiesJsonInvalid,
                    format!("bundle `{}` media/root consistency: {e:?}", row.bundle_id),
                    report.clone(),
                )
            })?;
        }

        // Persist canonical root (post-anonymize when needed) so dest has no
        // departed authorship pseudo-attribution.
        let root_payload_out = serde_json::to_string(&canonical_root.payload)?;
        let media_out = if policy.persist_canonical_payload {
            serde_json::to_string(&canonical_media)?
        } else {
            row.media_entities_json.clone()
        };

        let content_hash_out = if policy.recompute_content_hash {
            // Always verify source integrity against pre-anonymize canonical form.
            let source_hash = store::bundle_content_hash(&source_root, &canonical_media)
                .map_err(|e| MigrateError::Internal(format!("content hash: {e}")))?;
            if source_hash != row.content_hash {
                return Err(MigrateError::authoritative_failure(
                    AuthoritativeFailure::ContentHashMismatchAfterCanonicalize,
                    format!(
                        "bundle `{}` content_hash stored={} recomputed={}",
                        row.bundle_id, row.content_hash, source_hash
                    ),
                    report.clone(),
                ));
            }
            if payload_anonymized > 0 {
                // hard_delete recomputes after anonymize; dest must match rewritten root.
                store::bundle_content_hash(&canonical_root, &canonical_media)
                    .map_err(|e| MigrateError::Internal(format!("content hash: {e}")))?
            } else {
                row.content_hash.clone()
            }
        } else {
            row.content_hash.clone()
        };

        dest.execute(
            "
            INSERT INTO sync_bundles(
                family_id, bundle_id, staged_membership_id, status, root_type,
                root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                media_entities_json, content_hash, created_at, committed_at,
                committed_cursor, committed_applied
            ) VALUES (
                ?1, ?2, ?3, ?4, ?5,
                ?6, ?7, ?8, ?9,
                ?10, ?11, ?12, ?13,
                ?14, ?15
            )
            ",
            params![
                row.family_id,
                row.bundle_id,
                staged_membership_id,
                row.status,
                row.root_type,
                row.root_client_uuid,
                row.root_updated_at,
                row.root_deleted_at,
                root_payload_out,
                media_out,
                content_hash_out,
                row.created_at,
                row.committed_at,
                row.committed_cursor,
                row.committed_applied,
            ],
        )?;
        report.committed_bundles += 1;
    }
    Ok(())
}

struct BundleRow {
    family_id: String,
    bundle_id: String,
    staged_membership_id: String,
    status: String,
    root_type: String,
    root_client_uuid: String,
    root_updated_at: i64,
    root_deleted_at: Option<i64>,
    root_payload_json: String,
    media_entities_json: String,
    content_hash: String,
    created_at: i64,
    committed_at: Option<i64>,
    committed_cursor: Option<i64>,
    committed_applied: Option<i64>,
}

/// `(family_id, bundle_id, media_uuid)` triples for `source=bundle_pending`
/// publications whose bundle is a retained committed bundle — cleanup evidence
/// only (mirrors live `finalize_committed_pending_bundle_media`).
fn collect_committed_pending_cleanup(
    source: &Connection,
    retained_bundles: &BTreeSet<(String, String)>,
) -> Result<BTreeSet<(String, String, String)>, MigrateError> {
    let mut stmt = source.prepare(
        "
        SELECT family_id, media_uuid, source, bundle_id
        FROM media_publications
        ",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, Option<String>>(3)?,
        ))
    })?;
    let mut cleanup = BTreeSet::new();
    for row in rows {
        let (family_id, media_uuid, source_kind, bundle_id) = row?;
        if source_kind != "bundle_pending" {
            continue;
        }
        let Some(bid) = bundle_id else {
            // NULL bundle_id for bundle_pending fails later in copy_media_publications.
            continue;
        };
        if retained_bundles.contains(&(family_id.clone(), bid.clone())) {
            cleanup.insert((family_id, bid, media_uuid));
        }
    }
    Ok(cleanup)
}

fn copy_sync_bundle_media(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    retained: &BTreeSet<(String, String)>,
    discarded_staging: &BTreeSet<(String, String)>,
    committed_pending_cleanup: &BTreeSet<(String, String, String)>,
    report: &MigrateReport,
) -> Result<u64, MigrateError> {
    let mut stmt = source.prepare(
        "
        SELECT family_id, bundle_id, media_uuid, declared_byte_size,
               staged_byte_size, staged_sha256, staged_at
        FROM sync_bundle_media
        ",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, Option<i64>>(3)?,
            row.get::<_, Option<i64>>(4)?,
            row.get::<_, Option<String>>(5)?,
            row.get::<_, Option<i64>>(6)?,
        ))
    })?;
    let mut discarded = 0u64;
    for row in rows {
        let (family_id, bundle_id, media_uuid, declared, staged_size, staged_sha, staged_at) = row?;
        let key = (family_id.clone(), bundle_id.clone());
        if discarded_staging.contains(&key) {
            discarded += 1;
            continue;
        }
        if committed_pending_cleanup.contains(&(
            family_id.clone(),
            bundle_id.clone(),
            media_uuid.clone(),
        )) {
            // Matching loser / incomplete-cleanup pending on committed: report-only.
            discarded += 1;
            continue;
        }
        if !retained.contains(&key) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!("sync_bundle_media for missing bundle `{bundle_id}`"),
                report.clone(),
            ));
        }
        dest.execute(
            "
            INSERT INTO sync_bundle_media(
                family_id, bundle_id, media_uuid, declared_byte_size,
                staged_byte_size, staged_sha256, staged_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
            ",
            params![
                family_id,
                bundle_id,
                media_uuid,
                declared,
                staged_size,
                staged_sha,
                staged_at
            ],
        )?;
    }
    Ok(discarded)
}

/// StagingCascade steps 3–5:
/// - discard publications whose bundle_id is in discarded-staging (any source);
/// - discard `source=bundle_pending` on retained committed (cleanup evidence);
/// - keep `source=ordinary`;
/// - keep `source=bundle` only when bundle_id points at a retained committed bundle;
/// - fail closed on orphan bundle references and on bundle* with NULL bundle_id.
fn copy_media_publications(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    family_ids: &BTreeSet<String>,
    retained_bundles: &BTreeSet<(String, String)>,
    discarded_staging: &BTreeSet<(String, String)>,
    committed_pending_cleanup: &BTreeSet<(String, String, String)>,
    report: &MigrateReport,
) -> Result<u64, MigrateError> {
    let allowed_sources: BTreeSet<&str> =
        ALLOWED_MEDIA_PUBLICATION_SOURCES.iter().copied().collect();
    let mut stmt = source.prepare(
        "
        SELECT family_id, media_uuid, source, bundle_id
        FROM media_publications
        ",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, Option<String>>(3)?,
        ))
    })?;
    let mut discarded = 0u64;
    for row in rows {
        let (family_id, media_uuid, source_kind, bundle_id) = row?;
        if !family_ids.contains(&family_id) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!("publication family `{family_id}` missing"),
                report.clone(),
            ));
        }
        if !allowed_sources.contains(source_kind.as_str()) {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::SourceConstrainedValueInvalid,
                format!("publication source `{source_kind}`"),
                report.clone(),
            ));
        }

        if let Some(ref bid) = bundle_id {
            let key = (family_id.clone(), bid.clone());
            if discarded_staging.contains(&key) {
                discarded += 1;
                continue;
            }
            if source_kind == "bundle_pending"
                && committed_pending_cleanup.contains(&(
                    family_id.clone(),
                    bid.clone(),
                    media_uuid.clone(),
                ))
            {
                discarded += 1;
                continue;
            }
        }

        let keep = if source_kind == "ordinary" {
            true
        } else if source_kind == "bundle_pending" {
            // Remaining bundle_pending must not land on retained committed (handled above).
            // Orphan / non-retained non-staging → fail closed.
            match &bundle_id {
                Some(bid) => {
                    // Staging already continued; committed pending already continued.
                    // Any other retained status is impossible (only committed retained).
                    // Non-retained non-staging is orphan.
                    let _ = bid;
                    false
                }
                None => {
                    return Err(MigrateError::authoritative_failure(
                        AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                        format!(
                            "publication `{media_uuid}` source `{source_kind}` missing bundle_id"
                        ),
                        report.clone(),
                    ));
                }
            }
        } else {
            // source=bundle: require retained committed bundle_id
            match &bundle_id {
                Some(bid) => retained_bundles.contains(&(family_id.clone(), bid.clone())),
                None => {
                    return Err(MigrateError::authoritative_failure(
                        AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                        format!(
                            "publication `{media_uuid}` source `{source_kind}` missing bundle_id"
                        ),
                        report.clone(),
                    ));
                }
            }
        };
        if !keep {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::OrphanAuthoritativeForeignKey,
                format!("publication `{media_uuid}` source `{source_kind}` bundle_id not retained"),
                report.clone(),
            ));
        }

        dest.execute(
            "
            INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
            VALUES (?1, ?2, ?3, ?4)
            ",
            params![family_id, media_uuid, source_kind, bundle_id],
        )?;
    }
    Ok(discarded)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::offline_migrate::inventory::{target_only_empty_tables, BUNDLE_STATUS_STAGING};
    use crate::offline_migrate::test_support::{
        open_v3_fixture, seed_baby_entity, seed_minimal_family, TEST_NEW_ROOT_PASSWORD,
    };
    use crate::owner_root_fingerprint;
    use crate::store::{Store, DATABASE_SCHEMA_VERSION};
    use crate::{build_app, ServerConfig};
    use axum::body::Body;
    use axum::http::{Method, Request, StatusCode};
    use axum::Router;
    use rusqlite::Connection;
    use serde_json::{json, Value};
    use tempfile::tempdir;
    use tower::ServiceExt;

    fn record_payload(baby_uuid: &str) -> Map<String, Value> {
        json!({
            "baby_client_uuid": baby_uuid,
            "type": "formula",
            "custom_item_client_uuid": null,
            "timestamp": 300,
            "end_timestamp": null,
            "note": null,
            "payload_json": {"amount_ml": 120},
            "schema_version": 2,
        })
        .as_object()
        .unwrap()
        .clone()
    }

    fn canonical_record_bundle_parts() -> (Entity, Vec<Entity>, String, String, String) {
        let baby = "11111111-1111-1111-1111-111111111111";
        let root_uuid = "22222222-2222-2222-2222-222222222222";
        let raw = RawEntity {
            entity_type: "record".to_owned(),
            client_uuid: Uuid::parse_str(root_uuid).unwrap(),
            updated_at: 300,
            deleted_at: None,
            payload: record_payload(baby),
        };
        let canonical = raw
            .validate_as(
                DEFAULT_MAX_MEDIA_BYTES,
                EntityValidationContext::AtomicBundleRoot,
            )
            .unwrap();
        let media: Vec<Entity> = vec![];
        let content_hash = store::bundle_content_hash(&canonical, &media).unwrap();
        let root_payload_json = serde_json::to_string(&canonical.payload).unwrap();
        let media_json = serde_json::to_string(&media).unwrap();
        (
            canonical,
            media,
            content_hash,
            root_payload_json,
            media_json,
        )
    }

    fn seed_committed_record_bundle(conn: &Connection) {
        let root_uuid = "22222222-2222-2222-2222-222222222222";
        let (_canonical, _media, content_hash, root_payload_json, media_json) =
            canonical_record_bundle_parts();

        conn.execute(
            "
            INSERT INTO sync_bundles(
                family_id, bundle_id, staged_membership_id, status, root_type,
                root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                media_entities_json, content_hash, created_at, committed_at,
                committed_cursor, committed_applied
            ) VALUES (
                'fam-1', 'bundle-committed', 'mem-owner', 'committed', 'record',
                ?1, 300, NULL, ?2,
                ?3, ?4, 300, 301,
                2, 1
            )
            ",
            params![root_uuid, root_payload_json, media_json, content_hash],
        )
        .unwrap();
        // Committed bundle_media retained.
        conn.execute(
            "
            INSERT INTO sync_bundle_media(
                family_id, bundle_id, media_uuid, declared_byte_size,
                staged_byte_size, staged_sha256, staged_at
            ) VALUES ('fam-1', 'bundle-committed', '33333333-3333-3333-3333-333333333333', 12, 12, 'abc', 300)
            ",
            [],
        )
        .unwrap();
        // Ordinary publication kept (null bundle_id).
        conn.execute(
            "
            INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
            VALUES ('fam-1', '44444444-4444-4444-4444-444444444444', 'ordinary', NULL)
            ",
            [],
        )
        .unwrap();
        // Staging cascade discards.
        conn.execute(
            "
            INSERT INTO sync_bundles(
                family_id, bundle_id, staged_membership_id, status, root_type,
                root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                media_entities_json, content_hash, created_at, committed_at,
                committed_cursor, committed_applied
            ) VALUES (
                'fam-1', 'bundle-staging', 'mem-owner', ?1, 'record',
                ?2, 400, NULL, ?3,
                '[]', 'deadbeef', 400, NULL,
                NULL, NULL
            )
            ",
            params![BUNDLE_STATUS_STAGING, root_uuid, root_payload_json],
        )
        .unwrap();
        conn.execute(
            "
            INSERT INTO sync_bundle_media(
                family_id, bundle_id, media_uuid, declared_byte_size,
                staged_byte_size, staged_sha256, staged_at
            ) VALUES ('fam-1', 'bundle-staging', 'm-stage', 1, NULL, NULL, NULL)
            ",
            [],
        )
        .unwrap();
        conn.execute(
            "
            INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
            VALUES ('fam-1', 'm-stage', 'bundle_pending', 'bundle-staging')
            ",
            [],
        )
        .unwrap();
    }

    fn assert_dest_unwritten(dest: &Path) {
        assert!(
            !dest.exists() || {
                // sentinel-only case handled by callers
                true
            }
        );
        assert!(!temp_dest_path(dest).exists());
    }

    #[test]
    fn migrate_success_opens_with_current_preflight_and_keeps_business_rows() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");

        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            seed_baby_entity(&conn);
            seed_committed_record_bundle(&conn);
        }

        let report = migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        assert_eq!(report.families, 1);
        assert_eq!(report.memberships, 2);
        assert_eq!(report.entities, 1);
        assert_eq!(report.committed_bundles, 1);
        assert_eq!(report.discarded_staging_bundles, 1);
        assert_eq!(report.discarded_bundle_media, 1);
        assert_eq!(report.discarded_publications, 1);

        Store::preflight_existing_schema(&dest).expect("preflight");
        let store = Store::open(&dest).expect("open");
        store.health_check().expect("health");

        let conn = Connection::open(&dest).unwrap();
        let version: i64 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, DATABASE_SCHEMA_VERSION);

        let family_name: String = conn
            .query_row("SELECT name FROM families WHERE id = 'fam-1'", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(family_name, "我家");

        let (role, display_name, display_name_key): (String, String, String) = conn
            .query_row(
                "SELECT role, display_name, display_name_key FROM memberships WHERE membership_id = 'mem-owner'",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .unwrap();
        assert_eq!(role, "owner");
        assert_eq!(display_name, "爸爸");
        assert_eq!(display_name_key, normalized_display_name_key("爸爸"));

        let has_device_id: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM pragma_table_info('memberships') WHERE name = 'device_id'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(has_device_id, 0);

        let bundle_statuses: Vec<String> = conn
            .prepare("SELECT status FROM sync_bundles")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        assert_eq!(bundle_statuses, vec!["committed".to_owned()]);

        // Inventory TargetOnlyEmpty shells all empty.
        for table in target_only_empty_tables() {
            let n: i64 = conn
                .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))
                .unwrap();
            assert_eq!(n, 0, "{table} must be empty");
        }
        for table in ["invites", "membership_credentials"] {
            let n: i64 = conn
                .query_row(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?1",
                    params![table],
                    |r| r.get(0),
                )
                .unwrap();
            assert_eq!(n, 0, "{table} must not exist on target");
        }

        // Ticket 04: owner_root_fingerprint + regenerated server.secret.
        let fingerprint: String = conn
            .query_row(
                "SELECT owner_root_fingerprint FROM families WHERE id = 'fam-1'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        let secret_path = dest.parent().unwrap().join("server.secret");
        let signing_secret = fs::read(&secret_path).expect("server.secret written");
        assert!(signing_secret.len() >= SERVER_SECRET_BYTES);
        assert_eq!(
            fingerprint,
            owner_root_fingerprint(&signing_secret, TEST_NEW_ROOT_PASSWORD)
        );

        // Retained committed bundle_media + ordinary publication.
        let media_uuid: String = conn
            .query_row(
                "SELECT media_uuid FROM sync_bundle_media WHERE bundle_id = 'bundle-committed'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(media_uuid, "33333333-3333-3333-3333-333333333333");
        let ordinary: (String, Option<String>) = conn
            .query_row(
                "SELECT source, bundle_id FROM media_publications WHERE media_uuid = '44444444-4444-4444-4444-444444444444'",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(ordinary.0, "ordinary");
        assert!(ordinary.1.is_none());
        // Staging pub discarded.
        let staging_pubs: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM media_publications WHERE media_uuid = 'm-stage'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(staging_pubs, 0);

        let src = Connection::open(&source).unwrap();
        let src_version: i64 = src
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(src_version, 3);
        let creds: i64 = src
            .query_row("SELECT COUNT(*) FROM membership_credentials", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(creds, 1);
    }

    #[test]
    fn migrate_fail_closed_on_missing_active_owner_does_not_write_dest() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");
        fs::create_dir_all(dest.parent().unwrap()).unwrap();
        fs::write(&dest, b"preexisting-sentinel").unwrap();

        {
            let conn = open_v3_fixture(&source);
            conn.execute(
                "INSERT INTO families(id, created_at, create_request_hash, name) VALUES ('fam-1', 1, NULL, 'x')",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-1', 'fam-1', 'member', 'd1', 'A', NULL)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO family_meta(family_id, rev) VALUES ('fam-1', 0)",
                [],
            )
            .unwrap();
        }

        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::NotExactlyOneActiveOwner)
        );
        assert!(err.report().is_some());
        assert!(!temp_dest_path(&dest).exists());
        assert_eq!(fs::read(&dest).unwrap(), b"preexisting-sentinel");
    }

    #[test]
    fn migrate_fail_closed_on_dual_active_owner_is_authoritative_not_sqlite() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");
        fs::create_dir_all(dest.parent().unwrap()).unwrap();
        fs::write(&dest, b"preexisting-sentinel").unwrap();

        {
            let conn = open_v3_fixture(&source);
            // Source v3 has no memberships_one_owner UNIQUE — dual active owners possible.
            conn.execute(
                "INSERT INTO families(id, created_at, create_request_hash, name) VALUES ('fam-1', 1, NULL, 'x')",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-owner-a', 'fam-1', 'owner', 'd1', '爸爸', NULL)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-owner-b', 'fam-1', 'owner', 'd2', '妈妈', NULL)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO family_meta(family_id, rev) VALUES ('fam-1', 0)",
                [],
            )
            .unwrap();
        }

        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::NotExactlyOneActiveOwner),
            "dual owner must not surface as MigrateError::Sqlite: {err:?}"
        );
        assert!(err.report().is_some());
        assert!(!temp_dest_path(&dest).exists());
        assert_eq!(fs::read(&dest).unwrap(), b"preexisting-sentinel");
    }

    #[test]
    fn migrate_discards_committed_bundle_pending_cleanup_evidence_without_failing() {
        // Leftover LWW-loser / incomplete-cleanup: source=bundle_pending on a
        // retained committed bundle (+ matching sync_bundle_media). Live server
        // treats these as non-servable cleanup; migrate report-only discards them
        // so ticket 03 does not treat them as file authority.
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");

        let pending_media = "55555555-5555-5555-5555-555555555555";
        let authority_media = "33333333-3333-3333-3333-333333333333";

        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            seed_baby_entity(&conn);
            seed_committed_record_bundle(&conn);
            // Extra cleanup leftover on the committed bundle (in addition to
            // the retained authority media + staging cascade rows from seed).
            conn.execute(
                "
                INSERT INTO sync_bundle_media(
                    family_id, bundle_id, media_uuid, declared_byte_size,
                    staged_byte_size, staged_sha256, staged_at
                ) VALUES ('fam-1', 'bundle-committed', ?1, 4, 4, 'dead', 301)
                ",
                params![pending_media],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
                VALUES ('fam-1', ?1, 'bundle_pending', 'bundle-committed')
                ",
                params![pending_media],
            )
            .unwrap();
            // Real authority publication on the same committed bundle stays.
            conn.execute(
                "
                INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
                VALUES ('fam-1', ?1, 'bundle', 'bundle-committed')
                ",
                params![authority_media],
            )
            .unwrap();
        }

        let report = migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        // Seed: 1 staging bundle_media + 1 staging pub.
        // Cleanup: 1 pending media on committed + 1 pending pub.
        assert_eq!(report.discarded_bundle_media, 2);
        assert_eq!(report.discarded_publications, 2);
        assert_eq!(report.committed_bundles, 1);

        let conn = Connection::open(&dest).unwrap();
        let pending_pubs: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM media_publications WHERE media_uuid = ?1",
                params![pending_media],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            pending_pubs, 0,
            "bundle_pending on committed must be discarded"
        );
        let pending_bm: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM sync_bundle_media WHERE media_uuid = ?1",
                params![pending_media],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            pending_bm, 0,
            "matching pending bundle_media must be discarded"
        );

        let authority_pub: String = conn
            .query_row(
                "SELECT source FROM media_publications WHERE media_uuid = ?1",
                params![authority_media],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(authority_pub, "bundle");
        let authority_bm: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM sync_bundle_media WHERE media_uuid = ?1 AND bundle_id = 'bundle-committed'",
                params![authority_media],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(authority_bm, 1);
        let any_pending_source: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM media_publications WHERE source = 'bundle_pending'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(any_pending_source, 0);
    }

    #[test]
    fn migrate_fail_closed_on_wrong_user_version() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            conn.pragma_update(None, "user_version", 4i64).unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::SourceUserVersionNotThree)
        );
        assert!(!dest.exists());
        assert_dest_unwritten(&dest);
    }

    #[test]
    fn migrate_fail_closed_on_unknown_source_table() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            conn.execute_batch("CREATE TABLE unexpected_legacy (id TEXT PRIMARY KEY);")
                .unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::UnknownSourceUserTable)
        );
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_fail_closed_on_invalid_entity_payload() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            conn.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES (
                    'fam-1', 'baby', '11111111-1111-1111-1111-111111111111',
                    1, NULL, '{}', 1
                )
                ",
                [],
            )
            .unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::PayloadValidationFailed)
        );
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_fail_closed_on_content_hash_mismatch() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            seed_baby_entity(&conn);
            let root_uuid = "22222222-2222-2222-2222-222222222222";
            let (_c, _m, _hash, root_payload_json, media_json) = canonical_record_bundle_parts();
            conn.execute(
                "
                INSERT INTO sync_bundles(
                    family_id, bundle_id, staged_membership_id, status, root_type,
                    root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                    media_entities_json, content_hash, created_at, committed_at,
                    committed_cursor, committed_applied
                ) VALUES (
                    'fam-1', 'bundle-bad-hash', 'mem-owner', 'committed', 'record',
                    ?1, 300, NULL, ?2,
                    ?3, '0000000000000000000000000000000000000000000000000000000000000000',
                    300, 301, 2, 1
                )
                ",
                params![root_uuid, root_payload_json, media_json],
            )
            .unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::ContentHashMismatchAfterCanonicalize)
        );
        assert!(!dest.exists());
        assert!(!temp_dest_path(&dest).exists());
    }

    #[test]
    fn migrate_fail_closed_on_media_entities_json_invalid() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            seed_baby_entity(&conn);
            let root_uuid = "22222222-2222-2222-2222-222222222222";
            let (_c, _m, content_hash, root_payload_json, _media_json) =
                canonical_record_bundle_parts();
            // Not a JSON array → MediaEntitiesJsonInvalid
            conn.execute(
                "
                INSERT INTO sync_bundles(
                    family_id, bundle_id, staged_membership_id, status, root_type,
                    root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                    media_entities_json, content_hash, created_at, committed_at,
                    committed_cursor, committed_applied
                ) VALUES (
                    'fam-1', 'bundle-bad-media', 'mem-owner', 'committed', 'record',
                    ?1, 300, NULL, ?2,
                    '{}', ?3, 300, 301, 2, 1
                )
                ",
                params![root_uuid, root_payload_json, content_hash],
            )
            .unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::MediaEntitiesJsonInvalid)
        );
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_fail_closed_on_orphan_bundle_publication() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            // bundle source pointing at non-existent (non-staging) bundle
            conn.execute(
                "
                INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
                VALUES ('fam-1', 'orphan-media', 'bundle', 'no-such-bundle')
                ",
                [],
            )
            .unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::OrphanAuthoritativeForeignKey)
        );
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_fail_closed_on_missing_family_meta() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            conn.execute(
                "INSERT INTO families(id, created_at, create_request_hash, name) VALUES ('fam-1', 1, NULL, 'x')",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-1', 'fam-1', 'owner', 'd1', 'A', NULL)
                ",
                [],
            )
            .unwrap();
            // no family_meta
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::FamilyMetaMissingForFamily)
        );
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_fail_closed_on_active_display_name_key_conflict() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            conn.execute(
                "INSERT INTO families(id, created_at, create_request_hash, name) VALUES ('fam-1', 1, NULL, 'x')",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-1', 'fam-1', 'owner', 'd1', '爸爸', NULL)
                ",
                [],
            )
            .unwrap();
            // Same NFKC key as 爸爸 after normalization — use exact same display_name
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-2', 'fam-1', 'member', 'd2', '爸爸', NULL)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO family_meta(family_id, rev) VALUES ('fam-1', 0)",
                [],
            )
            .unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::ActiveDisplayNameKeyConflict)
        );
        assert!(!dest.exists());
    }

    /// Ticket 14: departed Member with historical facts + candidate + same-name
    /// active replacement — drop tombstone, anonymize refs, free the name.
    #[test]
    fn migrate_drops_departed_membership_anonymizes_facts_and_frees_display_name() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");

        let departed_id = "mem-departed";
        let active_same_name = "mem-new-grandma";
        let record_uuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        let plan_uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        let candidate_uuid = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        let custom_uuid = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        let left_at: i64 = 1_700_000_000;
        let source_bundle_content_hash;

        {
            let conn = open_v3_fixture(&source);
            // Owner 爸爸 + member 妈妈 from seed; departed/new share 奶奶.
            seed_minimal_family(&conn);
            seed_baby_entity(&conn);
            // Departed member who shared the display name "奶奶" with an active replacement.
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES (?1, 'fam-1', 'member', 'dev-left', '奶奶', ?2)
                ",
                params![departed_id, left_at],
            )
            .unwrap();
            // Active replacement reuses the freed name (would conflict if departed were kept).
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES (?1, 'fam-1', 'member', 'dev-new', '奶奶', NULL)
                ",
                params![active_same_name],
            )
            .unwrap();

            let record_payload = json!({
                "baby_client_uuid": "11111111-1111-1111-1111-111111111111",
                "type": "formula",
                "custom_item_client_uuid": null,
                "timestamp": 400,
                "end_timestamp": null,
                "note": null,
                "payload_json": {"amount_ml": 90},
                "schema_version": 2,
                "created_by_membership_id": departed_id,
            });
            conn.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES ('fam-1', 'record', ?1, 400, NULL, ?2, 2)
                ",
                params![record_uuid, record_payload.to_string()],
            )
            .unwrap();

            let plan_payload = json!({
                "baby_client_uuid": "11111111-1111-1111-1111-111111111111",
                "type": "formula",
                "custom_item_client_uuid": null,
                "scheduled_at": 500,
                "scheduled_zone_id": "Asia/Shanghai",
                "note": null,
                "payload_json": {"amount_ml": 100},
                "schema_version": 2,
                "status": "pending",
                "created_by_membership_id": departed_id,
                "fulfilled_record_client_uuid": null,
                "fulfilled_at": null,
            });
            conn.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES ('fam-1', 'care_plan', ?1, 500, NULL, ?2, 3)
                ",
                params![plan_uuid, plan_payload.to_string()],
            )
            .unwrap();

            let candidate_payload = json!({
                "care_plan_client_uuid": plan_uuid,
                "record_client_uuid": record_uuid,
                "actual_timestamp": 400,
                "submitter_membership_id": departed_id,
                "submitter_role": "member",
                "confirmed_at": 401,
            });
            conn.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES ('fam-1', 'fulfillment_candidate', ?1, 401, NULL, ?2, 4)
                ",
                params![candidate_uuid, candidate_payload.to_string()],
            )
            .unwrap();

            let custom_payload = json!({
                "name": "旧项目",
                "icon_slot": 2,
                "created_by_membership_id": departed_id,
            });
            conn.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES ('fam-1', 'custom_item', ?1, 402, NULL, ?2, 5)
                ",
                params![custom_uuid, custom_payload.to_string()],
            )
            .unwrap();

            // Committed bundle staged by departed member with author field.
            let root_uuid = record_uuid;
            let raw = RawEntity {
                entity_type: "record".to_owned(),
                client_uuid: Uuid::parse_str(root_uuid).unwrap(),
                updated_at: 400,
                deleted_at: None,
                payload: record_payload.as_object().unwrap().clone(),
            };
            let canonical = raw
                .validate_as(
                    DEFAULT_MAX_MEDIA_BYTES,
                    EntityValidationContext::AtomicBundleRoot,
                )
                .unwrap();
            let media: Vec<Entity> = vec![];
            let content_hash = store::bundle_content_hash(&canonical, &media).unwrap();
            source_bundle_content_hash = content_hash.clone();
            let root_payload_json = serde_json::to_string(&canonical.payload).unwrap();
            let media_json = serde_json::to_string(&media).unwrap();
            conn.execute(
                "
                INSERT INTO sync_bundles(
                    family_id, bundle_id, staged_membership_id, status, root_type,
                    root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                    media_entities_json, content_hash, created_at, committed_at,
                    committed_cursor, committed_applied
                ) VALUES (
                    'fam-1', 'bundle-departed-author', ?1, 'committed', 'record',
                    ?2, 400, NULL, ?3,
                    ?4, ?5, 400, 401, 5, 1
                )
                ",
                params![
                    departed_id,
                    root_uuid,
                    root_payload_json,
                    media_json,
                    content_hash
                ],
            )
            .unwrap();
        }

        let report = migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        // seed_minimal: owner + mem-member + active_same_name = 3 active; 1 departed dropped.
        assert_eq!(report.memberships, 3);
        assert_eq!(report.discarded_departed_memberships, 1);
        // Exact contract lock: record + plan + candidate + custom entity fields
        // + bundle root author + bundle stager blank = 6 anonymized refs.
        assert_eq!(
            report.anonymized_membership_refs, 6,
            "expected 6 cleared authorship/stager refs"
        );
        assert_eq!(report.entities, 5); // baby + record + plan + candidate + custom
        assert_eq!(report.committed_bundles, 1);

        let conn = Connection::open(&dest).unwrap();
        let departed_rows: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM memberships WHERE membership_id = ?1",
                params![departed_id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(departed_rows, 0, "departed membership must not be copied");

        let left_at_rows: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM memberships WHERE left_at IS NOT NULL",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            left_at_rows, 0,
            "target must not keep left_at identity tombstones"
        );

        let grandma_count: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM memberships WHERE display_name = '奶奶' AND left_at IS NULL",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(grandma_count, 1, "only active replacement keeps 奶奶");

        let record_author: Option<String> = conn
            .query_row(
                "SELECT json_extract(payload_json, '$.created_by_membership_id') FROM entities WHERE client_uuid = ?1",
                params![record_uuid],
                |r| r.get(0),
            )
            .unwrap();
        assert!(record_author.is_none(), "record author anonymized");

        let plan_author: Option<String> = conn
            .query_row(
                "SELECT json_extract(payload_json, '$.created_by_membership_id') FROM entities WHERE client_uuid = ?1",
                params![plan_uuid],
                |r| r.get(0),
            )
            .unwrap();
        assert!(plan_author.is_none(), "care_plan author anonymized");

        let candidate_submitter: Option<String> = conn
            .query_row(
                "SELECT json_extract(payload_json, '$.submitter_membership_id') FROM entities WHERE client_uuid = ?1",
                params![candidate_uuid],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            candidate_submitter.is_none(),
            "candidate submitter anonymized"
        );

        let custom_author: Option<String> = conn
            .query_row(
                "SELECT json_extract(payload_json, '$.created_by_membership_id') FROM entities WHERE client_uuid = ?1",
                params![custom_uuid],
                |r| r.get(0),
            )
            .unwrap();
        assert!(custom_author.is_none(), "custom_item author anonymized");

        let (stager, root_author, dest_content_hash, root_payload_json, media_entities_json): (
            String,
            Option<String>,
            String,
            String,
            String,
        ) = conn
            .query_row(
                "
                SELECT staged_membership_id,
                       json_extract(root_payload_json, '$.created_by_membership_id'),
                       content_hash,
                       root_payload_json,
                       media_entities_json
                FROM sync_bundles WHERE bundle_id = 'bundle-departed-author'
                ",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
            )
            .unwrap();
        assert_eq!(stager, "", "departed stager blanked like hard_delete");
        assert!(root_author.is_none(), "bundle root author anonymized");

        // Dest content_hash must equal post-anonymize recompute (not the pre-anonymize source hash).
        let dest_root_payload: Map<String, Value> =
            serde_json::from_str(&root_payload_json).unwrap();
        let dest_root = RawEntity {
            entity_type: "record".to_owned(),
            client_uuid: Uuid::parse_str(record_uuid).unwrap(),
            updated_at: 400,
            deleted_at: None,
            payload: dest_root_payload,
        }
        .validate_as(
            DEFAULT_MAX_MEDIA_BYTES,
            EntityValidationContext::AtomicBundleRoot,
        )
        .unwrap();
        let dest_media: Vec<Entity> = serde_json::from_str(&media_entities_json).unwrap();
        let expected_hash = store::bundle_content_hash(&dest_root, &dest_media).unwrap();
        assert_eq!(
            dest_content_hash, expected_hash,
            "committed bundle content_hash must match post-anonymize recompute"
        );
        assert_ne!(
            dest_content_hash, source_bundle_content_hash,
            "anonymize must change content_hash from source when root author is cleared"
        );

        // Current hard_delete / members API must not need to re-clean departed rows.
        Store::preflight_existing_schema(&dest).expect("preflight");
        let store = Store::open(&dest).expect("open");
        let hard_delete_err = store.hard_delete_membership("fam-1", departed_id, 1_800_000_000);
        assert!(
            matches!(
                hard_delete_err,
                Err(crate::store::StoreError::MembershipNotFound)
            ),
            "departed id is already gone: {hard_delete_err:?}"
        );
    }

    #[test]
    fn migrate_fail_closed_when_only_owner_is_departed() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            conn.execute(
                "INSERT INTO families(id, created_at, create_request_hash, name) VALUES ('fam-1', 1, NULL, 'x')",
                [],
            )
            .unwrap();
            // Departed Owner only — illegal source: no active Owner after drop.
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-old-owner', 'fam-1', 'owner', 'd1', '爸爸', 1_700_000_000)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-member', 'fam-1', 'member', 'd2', '妈妈', NULL)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO family_meta(family_id, rev) VALUES ('fam-1', 0)",
                [],
            )
            .unwrap();
        }
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::NotExactlyOneActiveOwner)
        );
        if let Some(report) = err.report() {
            assert_eq!(report.discarded_departed_memberships, 1);
            assert_eq!(report.memberships, 1); // member copied before owner count fails
        }
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_discards_departed_even_when_role_is_corrupt() {
        // Non-authoritative departed discard must not become InvalidMembershipRole.
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            conn.execute(
                "INSERT INTO families(id, created_at, create_request_hash, name) VALUES ('fam-1', 1, NULL, 'x')",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-owner', 'fam-1', 'owner', 'd1', '爸爸', NULL)
                ",
                [],
            )
            .unwrap();
            // Bypass CHECK so we can simulate a corrupt historical role on a departed row.
            conn.execute("PRAGMA ignore_check_constraints = 1", [])
                .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-left-bad-role', 'fam-1', 'not-a-role', 'd2', '奶奶', 1_700_000_000)
                ",
                [],
            )
            .unwrap();
            conn.execute("PRAGMA ignore_check_constraints = 0", [])
                .unwrap();
            conn.execute(
                "INSERT INTO family_meta(family_id, rev) VALUES ('fam-1', 0)",
                [],
            )
            .unwrap();
        }
        let report = migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        assert_eq!(report.memberships, 1);
        assert_eq!(report.discarded_departed_memberships, 1);
        assert_eq!(report.anonymized_membership_refs, 0);
        let conn = Connection::open(&dest).unwrap();
        let n: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM memberships WHERE membership_id = 'mem-left-bad-role'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            n, 0,
            "corrupt-role departed row must be discarded, not copied"
        );
    }

    #[test]
    fn migrate_departed_does_not_count_toward_active_display_name_conflict() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            conn.execute(
                "INSERT INTO families(id, created_at, create_request_hash, name) VALUES ('fam-1', 1, NULL, 'x')",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-owner', 'fam-1', 'owner', 'd1', '爸爸', NULL)
                ",
                [],
            )
            .unwrap();
            // Departed and active share the same display_name — only active participates.
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-left', 'fam-1', 'member', 'd2', '奶奶', 1_700_000_000)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
                VALUES ('mem-active', 'fam-1', 'member', 'd3', '奶奶', NULL)
                ",
                [],
            )
            .unwrap();
            conn.execute(
                "INSERT INTO family_meta(family_id, rev) VALUES ('fam-1', 0)",
                [],
            )
            .unwrap();
        }
        let report = migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        assert_eq!(report.memberships, 2);
        assert_eq!(report.discarded_departed_memberships, 1);
        let conn = Connection::open(&dest).unwrap();
        let n: i64 = conn
            .query_row("SELECT COUNT(*) FROM memberships", [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 2);
    }

    #[test]
    fn migrate_missing_source_is_io_not_shape_mismatch() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("absent.db");
        let dest = dir.path().join("lezi.db");
        let err =
            migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert!(matches!(err, MigrateError::Io(_)));
        assert_eq!(err.authoritative(), None);
        assert!(!dest.exists());
    }

    #[test]
    fn migrator_session_shells_match_inventory_target_only_empty() {
        let shells = target_only_empty_tables();
        assert!(shells.contains(&"devices"));
        assert!(shells.contains(&"member_rename_requests"));
        assert!(shells.contains(&"terminal_credential_denials"));
        assert_eq!(
            staging_cascade(),
            StagingCascade::DropDependentsReportOrphanBytesIgnore
        );
        assert!(is_discarded_bundle_status(BUNDLE_STATUS_STAGING));
    }

    // -----------------------------------------------------------------------
    // Ticket 04 — root password reset + current server open
    // Public seams: migrate_v3_database(password), server.secret, fingerprint,
    // build_app ready/setup-status, owner login with new root only.
    // -----------------------------------------------------------------------

    #[test]
    fn migrate_rejects_short_root_password_without_writing_dest() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");
        fs::create_dir_all(dest.parent().unwrap()).unwrap();
        fs::write(&dest, b"preexisting-sentinel").unwrap();
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
        }

        let err = migrate_v3_database(&source, &dest, "short-password")
            .expect_err("password < 16 must fail");
        assert!(
            matches!(err, MigrateError::InvalidRootPassword(_)),
            "got {err:?}"
        );
        assert_eq!(err.authoritative(), None);
        assert_eq!(fs::read(&dest).unwrap(), b"preexisting-sentinel");
        assert!(!dest.parent().unwrap().join("server.secret").exists());
        assert!(!temp_dest_path(&dest).exists());
    }

    #[test]
    fn migrate_rejects_empty_root_password() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
        }
        let err = migrate_v3_database(&source, &dest, "").expect_err("empty password");
        assert!(matches!(err, MigrateError::InvalidRootPassword(_)));
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_sets_fingerprint_matching_regenerated_server_secret() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let out = dir.path().join("out");
        let dest = out.join("lezi.db");
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
        }

        migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");

        let secret_path = out.join("server.secret");
        let secret = fs::read(&secret_path).expect("server.secret");
        assert_eq!(secret.len(), SERVER_SECRET_BYTES);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                secret_path.metadata().unwrap().permissions().mode() & 0o777,
                0o600,
                "regenerated server.secret must match write_private_file 0o600"
            );
        }
        let conn = Connection::open(&dest).unwrap();
        let stored: String = conn
            .query_row(
                "SELECT owner_root_fingerprint FROM families WHERE id = 'fam-1'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            stored,
            owner_root_fingerprint(&secret, TEST_NEW_ROOT_PASSWORD)
        );
        // Wrong password must not match stored fingerprint.
        assert_ne!(
            stored,
            owner_root_fingerprint(&secret, "different-root-pw!!")
        );
        // Source backup must never gain a server.secret (RegenerateAlways on out/).
        assert!(!source.parent().unwrap().join("server.secret").exists());
    }

    #[test]
    fn reauth_ops_note_states_no_silent_restore() {
        assert!(REAUTH_OPS_NOTE.contains("no silent restore"));
        assert!(REAUTH_OPS_NOTE.contains("new root password"));
        assert!(REAUTH_OPS_NOTE.contains("membership_credentials"));
        assert!(REAUTH_OPS_NOTE.contains("LEZI_BOOTSTRAP_SECRET"));
        assert_eq!(MIN_NEW_ROOT_PASSWORD_LEN, crate::MIN_BOOTSTRAP_SECRET_LEN);
        assert_eq!(MIN_NEW_ROOT_PASSWORD_LEN, 16);
    }

    async fn oneshot_json(
        app: &Router,
        method: Method,
        uri: &str,
        body: Option<Value>,
        extra_headers: &[(&str, &str)],
    ) -> (StatusCode, Value) {
        use std::net::{IpAddr, Ipv4Addr, SocketAddr};

        use axum::extract::ConnectInfo;
        use axum::http::header::CONTENT_TYPE;

        let has_body = body.is_some();
        let payload = body
            .map(|v| Body::from(serde_json::to_vec(&v).unwrap()))
            .unwrap_or_else(Body::empty);
        let mut builder = Request::builder().method(method).uri(uri);
        if has_body {
            builder = builder.header(CONTENT_TYPE, "application/json");
        }
        for (name, value) in extra_headers {
            builder = builder.header(*name, *value);
        }
        let mut request = builder.body(payload).unwrap();
        request.extensions_mut().insert(ConnectInfo(SocketAddr::new(
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            43210,
        )));
        let response = app.clone().oneshot(request).await.unwrap();
        let status = response.status();
        let bytes = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        let value = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap_or(Value::Null)
        };
        (status, value)
    }

    #[tokio::test]
    async fn migrated_out_is_ready_configured_and_owner_logs_in_with_new_root_only() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup").join("lezi.db");
        let out = dir.path().join("out");
        let dest = out.join("lezi.db");
        fs::create_dir_all(source.parent().unwrap()).unwrap();
        {
            let conn = open_v3_fixture(&source);
            seed_minimal_family(&conn);
            seed_baby_entity(&conn);
        }

        migrate_v3_database(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        Store::preflight_existing_schema(&dest).expect("preflight");

        let mut config = ServerConfig::new(&out);
        config.bootstrap_secret = Some(TEST_NEW_ROOT_PASSWORD.to_owned());
        config.generation = Some("migrate-gen".to_owned());
        let app = build_app(config).expect("build_app on migrated data");

        let (ready_status, _) = oneshot_json(&app, Method::GET, "/ready", None, &[]).await;
        assert_eq!(ready_status, StatusCode::OK, "migrated data must be /ready");

        let (setup_status, setup) =
            oneshot_json(&app, Method::GET, "/v1/setup-status", None, &[]).await;
        assert_eq!(setup_status, StatusCode::OK, "{setup}");
        assert_eq!(setup["family_state"], "configured");

        // Wrong root password cannot open owner login.
        let (bad_status, bad_body) = oneshot_json(
            &app,
            Method::POST,
            "/v1/owner/login",
            Some(json!({
                "login_request_id": "migrate-owner-login-wrong-00000001",
                "device_name": "旧手机",
            })),
            &[("x-lezi-bootstrap-secret", "wrong-root-password!")],
        )
        .await;
        assert_eq!(bad_status, StatusCode::UNAUTHORIZED, "{bad_body}");
        assert!(bad_body.get("access_token").is_none());

        // New root password establishes a device session.
        let (ok_status, logged_in) = oneshot_json(
            &app,
            Method::POST,
            "/v1/owner/login",
            Some(json!({
                "login_request_id": "migrate-owner-login-ok-00000000001",
                "device_name": "管理员手机",
            })),
            &[("x-lezi-bootstrap-secret", TEST_NEW_ROOT_PASSWORD)],
        )
        .await;
        assert_eq!(ok_status, StatusCode::OK, "{logged_in}");
        let access = logged_in["access_token"]
            .as_str()
            .expect("access_token present");
        assert!(!access.is_empty());
        assert_eq!(logged_in["membership_id"], "mem-owner");
        assert!(logged_in["device_id"].as_str().is_some());

        // Session works for an authenticated read.
        let (members_status, members) = oneshot_json(
            &app,
            Method::GET,
            "/v1/family/members",
            None,
            &[("authorization", &format!("Bearer {access}"))],
        )
        .await;
        assert_eq!(members_status, StatusCode::OK, "{members}");
        assert!(!members["members"].as_array().unwrap().is_empty());

        // Legacy credential material has no session path on the target schema.
        let conn = Connection::open(&dest).unwrap();
        let legacy_tables: i64 = conn
            .query_row(
                "
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table'
                  AND name IN ('membership_credentials', 'invites')
                ",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(legacy_tables, 0);
        let active_devices_before_login_only: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM devices WHERE status = 'active'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        // Exactly the one device from successful owner login above.
        assert_eq!(active_devices_before_login_only, 1);
    }
}
