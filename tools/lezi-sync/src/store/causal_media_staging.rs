//! Durable, manifest-bound lifecycle for causal media preimages.
//!
//! Bytes remain under `.causal-stage` until the causal SQLite transaction has
//! consumed their exact UUID/SHA/size manifest. `consumed` is the crash journal:
//! startup can finish publication before serving requests, while expired open
//! preimages use `gc_pending` until both bytes and metadata are durably removed.
//! Consumed bytes retain the same receipt row while any live projection or
//! immutable version still references them; every upload temporary has a
//! durable sequence marker and the same TTL, so GC never guesses `read_dir`
//! order or collects an in-flight prepare.

use std::fs::{self, OpenOptions};
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use serde::Serialize;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::{CausalMediaItem, Principal, Store, StoreError};

pub(crate) const CAUSAL_MEDIA_STAGING_TTL_SECONDS: i64 = 24 * 60 * 60;
pub(crate) const CAUSAL_MEDIA_GC_BATCH: usize = 8;
pub(crate) const CAUSAL_MEDIA_GC_SCAN_LIMIT: usize = 512;
pub(crate) const MAX_CAUSAL_MEDIA_STAGED_PER_MEMBERSHIP: usize = 64;
pub(crate) const MAX_CAUSAL_MEDIA_STAGED_PER_FAMILY: usize = 256;
pub(crate) const MAX_CAUSAL_MEDIA_STAGED_BYTES_PER_FAMILY: usize = 512 * 1024 * 1024;
const MAX_CAUSAL_MEDIA_PER_ROOT: usize = 3;

#[derive(Debug, Clone, Copy)]
pub struct CausalMediaStagingLimits {
    pub max_file_bytes: usize,
    pub max_membership_count: usize,
    pub max_family_count: usize,
    pub max_family_bytes: usize,
    pub ttl_seconds: i64,
}

pub const DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS: CausalMediaStagingLimits =
    CausalMediaStagingLimits {
        max_file_bytes: crate::DEFAULT_MAX_MEDIA_BYTES,
        max_membership_count: MAX_CAUSAL_MEDIA_STAGED_PER_MEMBERSHIP,
        max_family_count: MAX_CAUSAL_MEDIA_STAGED_PER_FAMILY,
        max_family_bytes: MAX_CAUSAL_MEDIA_STAGED_BYTES_PER_FAMILY,
        ttl_seconds: CAUSAL_MEDIA_STAGING_TTL_SECONDS,
    };

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct CausalMediaStageStatus {
    pub media_uuid: String,
    pub status: String,
    pub byte_size: usize,
    pub sha256: String,
    pub expires_at: i64,
}

/// Durable identity for one streamed request body. Its sequence makes orphan
/// discovery keyset-addressable across process restart.
pub(crate) struct CausalMediaUploadReservation {
    path: PathBuf,
    sequence: i64,
}

pub(crate) struct CausalMediaGcLease {
    family_id: String,
    in_flight: Arc<std::sync::Mutex<std::collections::BTreeSet<String>>>,
}

impl Drop for CausalMediaGcLease {
    fn drop(&mut self) {
        self.in_flight
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .remove(&self.family_id);
    }
}

impl CausalMediaUploadReservation {
    pub(crate) fn path(&self) -> &Path {
        &self.path
    }

    pub(crate) fn sequence(&self) -> i64 {
        self.sequence
    }
}

/// File-backed preimage whose exact length and digest were verified before the
/// caller enters a family lock or SQLite write transaction.
pub struct VerifiedCausalMediaPreimage {
    path: PathBuf,
    byte_size: usize,
    sha256: String,
}

impl VerifiedCausalMediaPreimage {
    pub(crate) fn verify(
        path: PathBuf,
        expected_sha256: &str,
        max_file_bytes: usize,
    ) -> Result<Self, StoreError> {
        let metadata = fs::symlink_metadata(&path)?;
        let byte_size =
            usize::try_from(metadata.len()).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
        if !metadata.file_type().is_file()
            || byte_size == 0
            || byte_size > max_file_bytes
            || expected_sha256.len() != 64
            || !expected_sha256
                .bytes()
                .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
        {
            return Err(StoreError::InvalidCausalMediaStaging);
        }
        if !checked_digest(&path, byte_size, expected_sha256)? {
            return Err(StoreError::CausalMediaPreimageConflict);
        }
        Ok(Self {
            path,
            byte_size,
            sha256: expected_sha256.to_owned(),
        })
    }
}

#[derive(Debug)]
struct StagingRow {
    membership_id: String,
    sha256: String,
    byte_size: usize,
    expires_at: i64,
    status: StagingStatus,
}

#[derive(Debug)]
struct GcScanRow {
    family_id: String,
    media_uuid: String,
    consumed_at: Option<i64>,
    expires_at: i64,
    status: StagingStatus,
    live: bool,
    published: bool,
    version_referenced: bool,
    upload_active: bool,
}

#[derive(Debug)]
struct GcUploadRow {
    family_id: String,
    sequence: i64,
    expires_at: i64,
}

impl GcScanRow {
    fn eligible(&self, now: i64) -> bool {
        match self.status {
            StagingStatus::GcPending => true,
            StagingStatus::Writing | StagingStatus::Staged => {
                self.expires_at <= now && !self.upload_active
            }
            StagingStatus::Consumed => {
                self.consumed_at.is_some_and(|consumed_at| {
                    consumed_at <= now.saturating_sub(CAUSAL_MEDIA_STAGING_TTL_SECONDS)
                }) && !self.live
                    && !self.published
                    && !self.version_referenced
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum StagingStatus {
    Writing,
    Staged,
    Consumed,
    GcPending,
}

impl StagingStatus {
    fn parse(raw: &str) -> Result<Self, StoreError> {
        match raw {
            "writing" => Ok(Self::Writing),
            "staged" => Ok(Self::Staged),
            "consumed" => Ok(Self::Consumed),
            "gc_pending" => Ok(Self::GcPending),
            _ => Err(StoreError::InvalidCausalMediaStaging),
        }
    }

    fn as_str(self) -> &'static str {
        match self {
            Self::Writing => "writing",
            Self::Staged => "staged",
            Self::Consumed => "consumed",
            Self::GcPending => "gc_pending",
        }
    }
}

fn data_root(database_path: &Path) -> &Path {
    database_path.parent().unwrap_or_else(|| Path::new("."))
}

fn media_root(database_path: &Path) -> PathBuf {
    data_root(database_path).join("media")
}

fn staging_root(database_path: &Path) -> PathBuf {
    media_root(database_path).join(".causal-stage")
}

fn staging_path(database_path: &Path, family_id: &str, media_uuid: &str) -> PathBuf {
    staging_root(database_path).join(family_id).join(media_uuid)
}

pub(super) fn published_path(database_path: &Path, family_id: &str, media_uuid: &str) -> PathBuf {
    media_root(database_path).join(family_id).join(media_uuid)
}

/// Read-only SHA-256 of a published media file. `None` if the path is missing
/// or not a regular file of `byte_size`. Unexpected I/O is a store error.
pub(super) fn published_file_sha256(
    path: &Path,
    byte_size: u64,
) -> Result<Option<String>, StoreError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    if !metadata.file_type().is_file() || metadata.len() != byte_size {
        return Ok(None);
    }
    let mut reader = BufReader::new(fs::File::open(path)?);
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = reader.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(Some(hex::encode(digest.finalize())))
}

fn checked_digest(path: &Path, byte_size: usize, sha256: &str) -> Result<bool, StoreError> {
    Ok(published_file_sha256(path, byte_size as u64)?.as_deref() == Some(sha256))
}

fn sync_parent(path: &Path) -> Result<(), StoreError> {
    if let Some(parent) = path.parent().filter(|parent| parent.exists()) {
        crate::sync_directory(parent)?;
    }
    Ok(())
}

fn incoming_upload_path(database_path: &Path, family_id: &str, sequence: i64) -> PathBuf {
    staging_root(database_path)
        .join(family_id)
        .join(format!(".upload-{sequence}.tmp"))
}

fn remove_file_if_present(path: &Path, _family_id: &str) -> Result<(), StoreError> {
    match fs::remove_file(path) {
        Ok(()) => {
            #[cfg(test)]
            if test_hook::take_fail_before_parent_sync(_family_id) {
                return Err(StoreError::InvalidCausalMediaStaging);
            }
            sync_parent(path)
        }
        // A previous unlink may have reached the filesystem while its parent
        // fsync failed. Re-sync before terminal receipt deletion on retry.
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => sync_parent(path),
        Err(error) => Err(error.into()),
    }
}

fn install_staged_file(
    path: &Path,
    incoming: &VerifiedCausalMediaPreimage,
) -> Result<(), StoreError> {
    let parent = path.parent().ok_or(StoreError::InvalidCausalMediaStaging)?;
    fs::create_dir_all(parent)?;
    crate::secure_directory(parent)?;
    if path.try_exists()? {
        let metadata = fs::symlink_metadata(path)?;
        if !metadata.file_type().is_file() {
            return Err(StoreError::CausalMediaPreimageConflict);
        }
    }
    let incoming_metadata = fs::symlink_metadata(&incoming.path)?;
    if !incoming_metadata.file_type().is_file()
        || incoming_metadata.len() != incoming.byte_size as u64
    {
        return Err(StoreError::InvalidCausalMediaStaging);
    }
    // The incoming file has already been fully verified outside the family
    // lock. Replacing an exact receipt path repairs same-size corruption with
    // one metadata operation; no large read/hash occurs in the critical path.
    fs::rename(&incoming.path, path)?;
    crate::secure_file(path)?;
    crate::sync_directory(parent)?;
    if let Some(root) = parent.parent() {
        crate::sync_directory(root)?;
    }
    Ok(())
}

fn is_lowercase_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}

fn readable_receipt_blob(
    database_path: &Path,
    family_id: &str,
    media_uuid: &str,
    byte_size: usize,
    sha256: &str,
) -> Result<PathBuf, StoreError> {
    let published = published_path(database_path, family_id, media_uuid);
    if checked_digest(&published, byte_size, sha256)? {
        return Ok(published);
    }
    let staged = staging_path(database_path, family_id, media_uuid);
    if checked_digest(&staged, byte_size, sha256)? {
        return Ok(staged);
    }
    Err(StoreError::InvalidCausalMediaStaging)
}

fn hard_link_without_copy(source: &Path, destination: &Path) -> Result<(), StoreError> {
    let parent = destination
        .parent()
        .ok_or(StoreError::InvalidCausalMediaStaging)?;
    fs::create_dir_all(parent)?;
    crate::secure_directory(parent)?;
    if destination.try_exists()? {
        return Err(StoreError::InvalidCausalMediaStaging);
    }
    fs::hard_link(source, destination).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
    crate::secure_file(destination)?;
    fs::File::open(destination)?.sync_all()?;
    crate::sync_directory(parent)?;
    if let Some(root) = parent.parent() {
        crate::sync_directory(root)?;
    }
    Ok(())
}

fn require_staging_capacity(
    tx: &Transaction<'_>,
    principal: &Principal,
    byte_size: usize,
    now: i64,
    limits: CausalMediaStagingLimits,
) -> Result<(), StoreError> {
    let (membership_count, family_count, family_bytes): (i64, i64, i64) = tx.query_row(
        "SELECT
            COALESCE(SUM(CASE WHEN membership_id = ?2 THEN 1 ELSE 0 END), 0),
            COUNT(*), COALESCE(SUM(byte_size), 0)
         FROM causal_media_staging
         WHERE family_id = ?1 AND status IN ('writing', 'staged') AND expires_at > ?3",
        params![principal.family_id, principal.membership_id, now],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    )?;
    if membership_count >= limits.max_membership_count as i64 {
        return Err(StoreError::CausalMediaStagingQuota("membership_count"));
    }
    if family_count >= limits.max_family_count as i64 {
        return Err(StoreError::CausalMediaStagingQuota("family_count"));
    }
    if family_bytes.saturating_add(byte_size as i64) > limits.max_family_bytes as i64 {
        return Err(StoreError::CausalMediaStagingQuota("family_bytes"));
    }
    Ok(())
}

fn reserve_writing_staging_row(
    tx: &Transaction<'_>,
    principal: &Principal,
    media_uuid: &str,
    sha256: &str,
    byte_size: usize,
    now: i64,
    limits: CausalMediaStagingLimits,
) -> Result<i64, StoreError> {
    require_staging_capacity(tx, principal, byte_size, now, limits)?;
    let expires_at = now.saturating_add(limits.ttl_seconds);
    tx.execute(
        "INSERT INTO causal_media_staging(
            family_id, membership_id, media_uuid, sha256, byte_size,
            created_at, expires_at, status
         ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, 'writing')",
        params![
            principal.family_id,
            principal.membership_id,
            media_uuid,
            sha256,
            byte_size,
            now,
            expires_at,
        ],
    )?;
    Ok(expires_at)
}

fn promote_writing_row_to_staged(
    connection: &mut rusqlite::Connection,
    principal: &Principal,
    media_uuid: &str,
    sha256: &str,
    byte_size: usize,
    expires_at: i64,
) -> Result<(), StoreError> {
    let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    super::causal::require_current_principal(&tx, principal)?;
    let updated = tx.execute(
        "UPDATE causal_media_staging SET status = 'staged'
         WHERE family_id = ?1 AND media_uuid = ?2 AND status IN ('writing', 'staged')
           AND membership_id = ?3 AND sha256 = ?4 AND byte_size = ?5 AND expires_at = ?6",
        params![
            principal.family_id,
            media_uuid,
            principal.membership_id,
            sha256,
            byte_size,
            expires_at,
        ],
    )?;
    if updated != 1 {
        return Err(StoreError::InvalidCausalMediaStaging);
    }
    tx.commit()?;
    Ok(())
}

fn replay_empty_existing_row(
    database_path: &Path,
    principal: &Principal,
    media_uuid: &str,
    expected_sha256: &str,
    now: i64,
    row: StagingRow,
) -> Result<CausalMediaStageStatus, StoreError> {
    if row.status == StagingStatus::GcPending
        || row.status == StagingStatus::Writing
        || (row.expires_at <= now && row.status != StagingStatus::Consumed)
    {
        return Err(StoreError::InvalidCausalMediaStaging);
    }
    if row.sha256 != expected_sha256 || row.byte_size == 0 {
        return Err(StoreError::CausalMediaPreimageConflict);
    }
    // Consumed media is already family authority (the same rule as claim_manifest).
    // Replaying its immutable receipt does not transfer ownership or accept bytes.
    // Unconsumed staging remains private to the original preparing membership.
    if row.status != StagingStatus::Consumed && row.membership_id != principal.membership_id {
        return Err(StoreError::CausalMediaMembershipMismatch);
    }
    readable_receipt_blob(
        database_path,
        &principal.family_id,
        media_uuid,
        row.byte_size,
        &row.sha256,
    )?;
    Ok(CausalMediaStageStatus {
        media_uuid: media_uuid.to_owned(),
        status: if row.status == StagingStatus::Consumed {
            StagingStatus::Consumed
        } else {
            StagingStatus::Staged
        }
        .as_str()
        .to_owned(),
        byte_size: row.byte_size,
        sha256: row.sha256,
        expires_at: row.expires_at,
    })
}

fn hard_link_fallback_allowed(error: &std::io::Error) -> bool {
    if error.kind() == std::io::ErrorKind::Unsupported {
        return true;
    }
    #[cfg(unix)]
    {
        matches!(
            error.raw_os_error(),
            Some(code)
                if code == libc::EXDEV
                    || code == libc::EPERM
                    || code == libc::EACCES
                    || code == libc::EOPNOTSUPP
        )
    }
    #[cfg(not(unix))]
    false
}

fn atomic_copy_noreplace(staged: &Path, published: &Path) -> Result<(), StoreError> {
    let parent = published
        .parent()
        .ok_or(StoreError::InvalidCausalMediaStaging)?;
    let temporary = parent.join(format!(
        ".{}.{}.publish.tmp",
        published.file_name().unwrap().to_string_lossy(),
        Uuid::new_v4(),
    ));
    let result = (|| -> Result<(), StoreError> {
        let mut source = fs::File::open(staged)?;
        let mut destination = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        std::io::copy(&mut source, &mut destination)?;
        destination.sync_all()?;
        crate::secure_file(&temporary)?;
        if published.try_exists()? {
            return Err(StoreError::CausalMediaPreimageConflict);
        }
        // Production serializes a family in one process. Rename is atomic; the
        // pre-check gives the same no-replace property inside that boundary.
        fs::rename(&temporary, published)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn load_row(
    connection: &rusqlite::Connection,
    family_id: &str,
    media_uuid: &str,
) -> Result<Option<StagingRow>, StoreError> {
    connection
        .query_row(
            "SELECT membership_id, sha256, byte_size, expires_at, status
             FROM causal_media_staging
             WHERE family_id = ?1 AND media_uuid = ?2",
            params![family_id, media_uuid],
            |row| {
                let size = row.get::<_, i64>(2)?;
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    size,
                    row.get::<_, i64>(3)?,
                    row.get::<_, String>(4)?,
                ))
            },
        )
        .optional()?
        .map(|(membership_id, sha256, byte_size, expires_at, status)| {
            Ok(StagingRow {
                membership_id,
                sha256,
                byte_size: usize::try_from(byte_size)
                    .map_err(|_| StoreError::InvalidCausalMediaStaging)?,
                expires_at,
                status: StagingStatus::parse(&status)?,
            })
        })
        .transpose()
}

fn load_gc_row(
    connection: &rusqlite::Connection,
    family_id: &str,
    media_uuid: &str,
) -> Result<Option<GcScanRow>, StoreError> {
    connection
        .query_row(
            "SELECT s.consumed_at, s.expires_at, s.status,
                    EXISTS(
                        SELECT 1 FROM entities e
                         WHERE e.family_id = s.family_id
                           AND e.entity_type = 'media'
                           AND e.client_uuid = s.media_uuid
                           AND e.deleted_at IS NULL
                    ),
                    EXISTS(
                        SELECT 1 FROM media_publications p
                         WHERE p.family_id = s.family_id
                           AND p.media_uuid = s.media_uuid
                    ),
                    EXISTS(
                        SELECT 1 FROM entity_version_media vm
                         WHERE vm.family_id = s.family_id
                           AND vm.media_uuid = s.media_uuid
                    ),
                    EXISTS(
                        SELECT 1 FROM causal_media_uploads u
                         WHERE u.family_id = s.family_id
                           AND u.media_uuid = s.media_uuid
                    )
               FROM causal_media_staging s
              WHERE s.family_id = ?1 AND s.media_uuid = ?2",
            params![family_id, media_uuid],
            |row| {
                Ok((
                    row.get::<_, Option<i64>>(0)?,
                    row.get::<_, i64>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, bool>(3)?,
                    row.get::<_, bool>(4)?,
                    row.get::<_, bool>(5)?,
                    row.get::<_, bool>(6)?,
                ))
            },
        )
        .optional()?
        .map(
            |(
                consumed_at,
                expires_at,
                status,
                live,
                published,
                version_referenced,
                upload_active,
            )| {
                Ok(GcScanRow {
                    family_id: family_id.to_owned(),
                    media_uuid: media_uuid.to_owned(),
                    consumed_at,
                    expires_at,
                    status: StagingStatus::parse(&status)?,
                    live,
                    published,
                    version_referenced,
                    upload_active,
                })
            },
        )
        .transpose()
}

fn confirm_consumed_publication(
    tx: &Transaction<'_>,
    family_id: &str,
    media_uuid: &str,
) -> Result<(), StoreError> {
    let updated = tx.execute(
        "UPDATE causal_media_staging SET publication_confirmed = 1
          WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'consumed'",
        params![family_id, media_uuid],
    )?;
    if updated != 1 {
        return Err(StoreError::InvalidCausalMediaStaging);
    }
    Ok(())
}

fn finalize_consumed_publication(
    store: &Store,
    family_id: &str,
    media_uuid: &str,
) -> Result<(), StoreError> {
    let mut connection = store.connect()?;
    let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    finalize_consumed_publication_in_tx(store, &tx, family_id, media_uuid)?;
    tx.commit()?;
    Ok(())
}

fn finalize_consumed_publication_in_tx(
    store: &Store,
    tx: &Transaction<'_>,
    family_id: &str,
    media_uuid: &str,
) -> Result<(), StoreError> {
    let row = tx
        .query_row(
            "SELECT deleted_at, payload_json FROM entities
             WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
            params![family_id, media_uuid],
            |row| Ok((row.get::<_, Option<i64>>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()?;
    let Some((deleted_at, payload_json)) = row else {
        confirm_consumed_publication(tx, family_id, media_uuid)?;
        return Ok(()); // Branch-only preimage: retained but not publicly addressable.
    };
    if deleted_at.is_some() {
        confirm_consumed_publication(tx, family_id, media_uuid)?;
        return Ok(());
    }
    let already_published = tx
        .query_row(
            "SELECT 1 FROM media_publications
             WHERE family_id = ?1 AND media_uuid = ?2 AND source != 'bundle_pending'",
            params![family_id, media_uuid],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
    if already_published {
        confirm_consumed_publication(tx, family_id, media_uuid)?;
        return Ok(());
    }

    let payload = super::parse_payload(&payload_json)?;
    let (owner_type, owner_uuid) =
        super::media_association_owner(&payload)?.ok_or(StoreError::InvalidStoredPayload)?;
    let media_rev = super::causal::advance_rev(&store.live_census_cache, tx, family_id)?;
    tx.execute(
        "UPDATE entities SET rev = ?1
         WHERE family_id = ?2 AND entity_type = 'media' AND client_uuid = ?3",
        params![media_rev, family_id, media_uuid],
    )?;
    let owner_rev = super::causal::advance_rev(&store.live_census_cache, tx, family_id)?;
    let owner_updated = tx.execute(
        "UPDATE entities SET rev = ?1
         WHERE family_id = ?2 AND entity_type = ?3 AND client_uuid = ?4",
        params![owner_rev, family_id, owner_type, owner_uuid],
    )?;
    if owner_updated != 1 {
        return Err(StoreError::InvalidStoredPayload);
    }
    tx.execute(
        "INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
         VALUES (?1, ?2, 'ordinary', NULL)",
        params![family_id, media_uuid],
    )?;
    confirm_consumed_publication(tx, family_id, media_uuid)?;
    Ok(())
}

fn stage_consumed_bytes_for_manifest(
    store: &Store,
    row: &(String, String, String, i64),
    blocking_hook: Option<&(dyn Fn(&'static str) + Send + Sync + 'static)>,
) -> Result<(), StoreError> {
    let (family_id, media_uuid, sha256, byte_size) = row.clone();
    let byte_size =
        usize::try_from(byte_size).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
    let staged = staging_path(&store.database_path, &family_id, &media_uuid);
    let published = published_path(&store.database_path, &family_id, &media_uuid);
    if checked_digest(&published, byte_size, &sha256)? {
        if staged.try_exists()? {
            fs::remove_file(&staged)?;
            sync_parent(&staged)?;
        }
        return Ok(());
    }
    if published.try_exists()? || !checked_digest(&staged, byte_size, &sha256)? {
        return Err(StoreError::CausalMediaPreimageConflict);
    }
    if let Some(hook) = blocking_hook {
        hook("before_promotion");
    }
    let parent = published
        .parent()
        .ok_or(StoreError::InvalidCausalMediaStaging)?;
    fs::create_dir_all(parent)?;
    crate::secure_directory(parent)?;
    match fs::hard_link(&staged, &published) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            if !checked_digest(&published, byte_size, &sha256)? {
                return Err(StoreError::CausalMediaPreimageConflict);
            }
        }
        Err(error) if hard_link_fallback_allowed(&error) => {
            atomic_copy_noreplace(&staged, &published)?;
        }
        Err(error) => return Err(error.into()),
    }
    crate::secure_file(&published)?;
    fs::File::open(&published)?.sync_all()?;
    crate::sync_directory(parent)?;
    crate::sync_directory(&media_root(&store.database_path))?;
    fs::remove_file(&staged)?;
    sync_parent(&staged)?;
    Ok(())
}

fn promote_consumed_row(
    store: &Store,
    row: (String, String, String, i64),
    blocking_hook: Option<&(dyn Fn(&'static str) + Send + Sync + 'static)>,
) -> Result<(), StoreError> {
    let (family_id, media_uuid, sha256, byte_size) = row;
    let byte_size =
        usize::try_from(byte_size).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
    let staged = staging_path(&store.database_path, &family_id, &media_uuid);
    let published = published_path(&store.database_path, &family_id, &media_uuid);
    if checked_digest(&published, byte_size, &sha256)? {
        if staged.try_exists()? {
            fs::remove_file(&staged)?;
            sync_parent(&staged)?;
        }
        finalize_consumed_publication(store, &family_id, &media_uuid)?;
        return Ok(());
    }
    if published.try_exists()? || !checked_digest(&staged, byte_size, &sha256)? {
        return Err(StoreError::CausalMediaPreimageConflict);
    }
    if let Some(hook) = blocking_hook {
        hook("before_promotion");
    }
    let parent = published
        .parent()
        .ok_or(StoreError::InvalidCausalMediaStaging)?;
    fs::create_dir_all(parent)?;
    crate::secure_directory(parent)?;
    match fs::hard_link(&staged, &published) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            if !checked_digest(&published, byte_size, &sha256)? {
                return Err(StoreError::CausalMediaPreimageConflict);
            }
        }
        Err(error) if hard_link_fallback_allowed(&error) => {
            atomic_copy_noreplace(&staged, &published)?;
        }
        Err(error) => return Err(error.into()),
    }
    crate::secure_file(&published)?;
    fs::File::open(&published)?.sync_all()?;
    crate::sync_directory(parent)?;
    crate::sync_directory(&media_root(&store.database_path))?;
    fs::remove_file(&staged)?;
    sync_parent(&staged)?;
    finalize_consumed_publication(store, &family_id, &media_uuid)
}

fn validate_existing_media_identity(
    tx: &Transaction<'_>,
    family_id: &str,
    item: &CausalMediaItem,
) -> Result<(), CausalMediaReceiptClaimError> {
    let existing_payload = tx
        .query_row(
            "SELECT payload_json FROM entities
             WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
            params![family_id, item.media_uuid],
            |row| row.get::<_, String>(0),
        )
        .optional()
        .map_err(StoreError::from)
        .map_err(CausalMediaReceiptClaimError::Store)?;
    if let Some(payload_json) = existing_payload {
        let payload =
            super::parse_payload(&payload_json).map_err(CausalMediaReceiptClaimError::Store)?;
        let existing_sha = payload.get("sha256").and_then(serde_json::Value::as_str);
        let existing_size = payload
            .get("byte_size")
            .and_then(serde_json::Value::as_i64)
            .ok_or(StoreError::InvalidStoredPayload)
            .map_err(CausalMediaReceiptClaimError::Store)?;
        if existing_sha.is_some_and(|sha| sha != item.sha256) || existing_size != item.byte_size {
            return Err(CausalMediaReceiptClaimError::Rejected(
                "media_uuid_conflict",
            ));
        }
    }
    Ok(())
}

fn validate_receipt_metadata(row: &StagingRow, item: &CausalMediaItem) -> Result<(), &'static str> {
    if row.sha256 != item.sha256 {
        return Err("media_sha256_mismatch");
    }
    if row.byte_size as i64 != item.byte_size {
        return Err("media_byte_size_mismatch");
    }
    Ok(())
}

#[derive(Debug)]
pub(in crate::store) enum CausalMediaReceiptClaimError {
    Rejected(&'static str),
    Store(StoreError),
}

/// Atomically bind the complete canonical manifest to its durable preimage
/// receipts. The receipt row is the authority for bytes already verified by
/// prepare; commit never re-opens or hashes a large object while holding the
/// family lock / SQLite write transaction.
pub(in crate::store) fn claim_manifest(
    tx: &Transaction<'_>,
    principal: &Principal,
    media: &[CausalMediaItem],
    now: i64,
) -> Result<(), CausalMediaReceiptClaimError> {
    let mut staged = Vec::new();
    for item in media {
        validate_existing_media_identity(tx, &principal.family_id, item)?;
        let row = load_row(tx, &principal.family_id, &item.media_uuid)
            .map_err(CausalMediaReceiptClaimError::Store)?
            .ok_or(CausalMediaReceiptClaimError::Rejected(
                "missing_media_bytes",
            ))?;
        validate_receipt_metadata(&row, item).map_err(CausalMediaReceiptClaimError::Rejected)?;
        match row.status {
            StagingStatus::Consumed => {}
            StagingStatus::Staged => {
                if row.membership_id != principal.membership_id {
                    return Err(CausalMediaReceiptClaimError::Rejected(
                        "media_membership_mismatch",
                    ));
                }
                if row.expires_at <= now {
                    return Err(CausalMediaReceiptClaimError::Rejected(
                        "media_preimage_expired",
                    ));
                }
                staged.push(item);
            }
            StagingStatus::Writing | StagingStatus::GcPending => {
                if row.membership_id != principal.membership_id {
                    return Err(CausalMediaReceiptClaimError::Rejected(
                        "media_membership_mismatch",
                    ));
                }
                return Err(CausalMediaReceiptClaimError::Rejected(
                    "media_preimage_expired",
                ));
            }
        }
    }
    // Validation is deliberately complete before the first state transition:
    // one wrong/expired/foreign receipt leaves every peer receipt staged.
    for item in staged {
        let updated = tx
            .execute(
                "UPDATE causal_media_staging
                 SET status = 'consumed', consumed_at = COALESCE(consumed_at, ?1)
                 WHERE family_id = ?2 AND media_uuid = ?3
                   AND membership_id = ?4 AND sha256 = ?5 AND byte_size = ?6
                   AND status = 'staged' AND expires_at > ?1",
                params![
                    now,
                    principal.family_id,
                    item.media_uuid,
                    principal.membership_id,
                    item.sha256,
                    item.byte_size,
                ],
            )
            .map_err(StoreError::from)
            .map_err(CausalMediaReceiptClaimError::Store)?;
        if updated != 1 {
            return Err(CausalMediaReceiptClaimError::Store(
                StoreError::InvalidCausalMediaStaging,
            ));
        }
    }
    Ok(())
}

/// Resolution only reuses media already attached by an accepted/merged/branched
/// commit. It must not claim another principal's open staged receipt.
pub(in crate::store) fn require_consumed_manifest(
    tx: &Transaction<'_>,
    family_id: &str,
    media: &[CausalMediaItem],
) -> Result<(), CausalMediaReceiptClaimError> {
    for item in media {
        validate_existing_media_identity(tx, family_id, item)?;
        let row = load_row(tx, family_id, &item.media_uuid)
            .map_err(CausalMediaReceiptClaimError::Store)?
            .ok_or(CausalMediaReceiptClaimError::Rejected(
                "missing_media_bytes",
            ))?;
        validate_receipt_metadata(&row, item).map_err(CausalMediaReceiptClaimError::Rejected)?;
        if row.status != StagingStatus::Consumed {
            return Err(CausalMediaReceiptClaimError::Rejected(
                "missing_media_bytes",
            ));
        }
    }
    Ok(())
}

/// Choice-only resolution has no client receipt to repair. It must prove that
/// every already-consumed preimage is still readable before publishing a new
/// stable version. Ordinary commit deliberately does not call this function.
pub(in crate::store) fn verify_consumed_manifest_bytes(
    tx: &Transaction<'_>,
    database_path: &Path,
    family_id: &str,
    media: &[CausalMediaItem],
) -> Result<(), CausalMediaReceiptClaimError> {
    require_consumed_manifest(tx, family_id, media)?;
    for item in media {
        let byte_size = usize::try_from(item.byte_size)
            .map_err(|_| StoreError::InvalidStoredPayload)
            .map_err(CausalMediaReceiptClaimError::Store)?;
        let published = published_path(database_path, family_id, &item.media_uuid);
        let staged = staging_path(database_path, family_id, &item.media_uuid);
        let published_matches = checked_digest(&published, byte_size, &item.sha256)
            .map_err(CausalMediaReceiptClaimError::Store)?;
        let staged_matches = checked_digest(&staged, byte_size, &item.sha256)
            .map_err(CausalMediaReceiptClaimError::Store)?;
        if !published_matches && !staged_matches {
            return Err(CausalMediaReceiptClaimError::Rejected(
                "missing_media_bytes",
            ));
        }
    }
    Ok(())
}

impl Store {
    fn causal_media_publication_lock(&self, family_id: &str) -> Arc<std::sync::Mutex<()>> {
        let mut locks = self
            .causal_media_publication_locks
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        locks
            .entry(family_id.to_owned())
            .or_insert_with(|| Arc::new(std::sync::Mutex::new(())))
            .clone()
    }

    pub(crate) fn try_begin_causal_media_gc(&self, family_id: &str) -> Option<CausalMediaGcLease> {
        let mut in_flight = self
            .causal_media_gc_in_flight
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let global_in_flight = in_flight.contains("");
        let conflicts = if family_id.is_empty() {
            !in_flight.is_empty()
        } else {
            global_in_flight || in_flight.contains(family_id)
        };
        if conflicts {
            return None;
        }
        in_flight.insert(family_id.to_owned());
        Some(CausalMediaGcLease {
            family_id: family_id.to_owned(),
            in_flight: self.causal_media_gc_in_flight.clone(),
        })
    }

    pub(crate) fn reserve_causal_media_upload(
        &self,
        principal: &Principal,
        media_uuid: &str,
        now: i64,
    ) -> Result<CausalMediaUploadReservation, StoreError> {
        Uuid::parse_str(media_uuid).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        super::causal::require_current_principal(&tx, principal)?;
        tx.execute(
            "INSERT OR IGNORE INTO causal_media_gc_state(family_id) VALUES (?1)",
            params![principal.family_id],
        )?;
        let (total, active): (i64, i64) = tx.query_row(
            "SELECT
                (SELECT COUNT(*) FROM causal_media_uploads WHERE family_id = ?1),
                (SELECT COUNT(*) FROM causal_media_uploads
                  WHERE family_id = ?1 AND expires_at > ?2)",
            params![principal.family_id, now],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        if total >= CAUSAL_MEDIA_GC_SCAN_LIMIT as i64 {
            tx.execute(
                "UPDATE causal_media_gc_state
                    SET orphan_after_family_id = '', orphan_after_sequence = 0
                  WHERE family_id = ?1",
                params![principal.family_id],
            )?;
            tx.commit()?;
            drop(connection);
            let removed = self.gc_expired_causal_media_uploads_scoped(
                Some(&principal.family_id),
                now,
                CAUSAL_MEDIA_GC_BATCH,
            )?;
            if removed == 0 {
                return Err(StoreError::CausalMediaStagingQuota("family_upload_history"));
            }
            return self.reserve_causal_media_upload(principal, media_uuid, now);
        }
        if active >= MAX_CAUSAL_MEDIA_STAGED_PER_FAMILY as i64 {
            return Err(StoreError::CausalMediaStagingQuota("family_upload_count"));
        }
        let sequence: i64 = tx.query_row(
            "UPDATE causal_media_gc_state
                SET next_upload_sequence = next_upload_sequence + 1
              WHERE family_id = ?1
              RETURNING next_upload_sequence - 1",
            params![principal.family_id],
            |row| row.get(0),
        )?;
        tx.execute(
            "INSERT INTO causal_media_uploads(
                family_id, sequence, membership_id, media_uuid, created_at, expires_at
             ) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                principal.family_id,
                sequence,
                principal.membership_id,
                media_uuid,
                now,
                now.saturating_add(CAUSAL_MEDIA_STAGING_TTL_SECONDS),
            ],
        )?;
        tx.commit()?;
        Ok(CausalMediaUploadReservation {
            path: incoming_upload_path(&self.database_path, &principal.family_id, sequence),
            sequence,
        })
    }

    pub(crate) fn complete_causal_media_upload(
        &self,
        family_id: &str,
        sequence: i64,
    ) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute(
            "DELETE FROM causal_media_uploads
              WHERE family_id = ?1 AND sequence = ?2",
            params![family_id, sequence],
        )?;
        Ok(())
    }

    pub(crate) fn abort_causal_media_upload(
        &self,
        family_id: &str,
        sequence: i64,
    ) -> Result<(), StoreError> {
        let path = incoming_upload_path(&self.database_path, family_id, sequence);
        remove_file_if_present(&path, family_id)?;
        self.complete_causal_media_upload(family_id, sequence)
    }

    pub fn stage_verified_causal_media_preimage(
        &self,
        principal: &Principal,
        media_uuid: &str,
        incoming: &VerifiedCausalMediaPreimage,
        now: i64,
        limits: CausalMediaStagingLimits,
    ) -> Result<CausalMediaStageStatus, StoreError> {
        Uuid::parse_str(media_uuid).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
        if incoming.byte_size > limits.max_file_bytes || limits.ttl_seconds <= 0 {
            return Err(StoreError::InvalidCausalMediaStaging);
        }

        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        super::causal::require_current_principal(&tx, principal)?;
        if let Some(mut row) = load_row(&tx, &principal.family_id, media_uuid)? {
            if row.status == StagingStatus::GcPending {
                return Err(StoreError::InvalidCausalMediaStaging);
            }
            if row.sha256 != incoming.sha256 || row.byte_size != incoming.byte_size {
                return Err(StoreError::CausalMediaPreimageConflict);
            }
            if row.membership_id != principal.membership_id {
                return Err(StoreError::CausalMediaMembershipMismatch);
            }
            if row.expires_at <= now && row.status != StagingStatus::Consumed {
                let references = load_gc_row(&tx, &principal.family_id, media_uuid)?
                    .ok_or(StoreError::InvalidCausalMediaStaging)?;
                if references.consumed_at.is_some()
                    || references.live
                    || references.published
                    || references.version_referenced
                {
                    return Err(StoreError::InvalidCausalMediaStaging);
                }
                // Expiry released this slot from quota, so renewal must acquire
                // it again under the same transaction as the GC/state check.
                // Keep UUID ownership and creation evidence; only verified full
                // bytes can renew. A fresh writing journal prevents commit from
                // consuming a missing/corrupt old file before install + fsync.
                require_staging_capacity(&tx, principal, row.byte_size, now, limits)?;
                row.expires_at = now.saturating_add(limits.ttl_seconds);
                tx.execute(
                    "UPDATE causal_media_staging SET status = 'writing', expires_at = ?3
                     WHERE family_id = ?1 AND media_uuid = ?2",
                    params![principal.family_id, media_uuid, row.expires_at],
                )?;
                row.status = StagingStatus::Writing;
            }
            tx.commit()?;
            let current = load_row(&self.connect()?, &principal.family_id, media_uuid)?
                .ok_or(StoreError::InvalidCausalMediaStaging)?;
            // A concurrent exact PUT may already have completed this writing
            // journal. Its staged completion is idempotent, never a new slot.
            if (current.status != row.status
                && !(row.status == StagingStatus::Writing
                    && current.status == StagingStatus::Staged))
                || current.membership_id != row.membership_id
                || current.sha256 != row.sha256
                || current.byte_size != row.byte_size
                || current.expires_at != row.expires_at
            {
                return Err(StoreError::InvalidCausalMediaStaging);
            }
            let path = if row.status == StagingStatus::Consumed {
                published_path(&self.database_path, &principal.family_id, media_uuid)
            } else {
                staging_path(&self.database_path, &principal.family_id, media_uuid)
            };
            install_staged_file(&path, incoming)?;
            #[cfg(test)]
            test_hook::after_stage_file(&principal.family_id);
            if row.status == StagingStatus::Writing {
                promote_writing_row_to_staged(
                    &mut self.connect()?,
                    principal,
                    media_uuid,
                    &incoming.sha256,
                    incoming.byte_size,
                    row.expires_at,
                )?;
            } else {
                // File installation happens outside the admission transaction.
                // Recheck the identity at completion too; keep verified bytes
                // when removal won the race, rather than deleting a valid copy.
                let mut connection = self.connect()?;
                let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
                super::causal::require_current_principal(&tx, principal)?;
                tx.commit()?;
            }
            return Ok(CausalMediaStageStatus {
                media_uuid: media_uuid.to_owned(),
                status: if row.status == StagingStatus::Consumed {
                    StagingStatus::Consumed
                } else {
                    StagingStatus::Staged
                }
                .as_str()
                .to_owned(),
                byte_size: row.byte_size,
                sha256: row.sha256,
                expires_at: row.expires_at,
            });
        }

        let expires_at = reserve_writing_staging_row(
            &tx,
            principal,
            media_uuid,
            &incoming.sha256,
            incoming.byte_size,
            now,
            limits,
        )?;
        tx.commit()?;

        let current = load_row(&self.connect()?, &principal.family_id, media_uuid)?
            .ok_or(StoreError::InvalidCausalMediaStaging)?;
        if current.status != StagingStatus::Writing
            || current.membership_id != principal.membership_id
            || current.sha256 != incoming.sha256
            || current.byte_size != incoming.byte_size
        {
            return Err(StoreError::InvalidCausalMediaStaging);
        }

        let path = staging_path(&self.database_path, &principal.family_id, media_uuid);
        install_staged_file(&path, incoming)?;
        #[cfg(test)]
        test_hook::after_stage_file(&principal.family_id);
        promote_writing_row_to_staged(
            &mut self.connect()?,
            principal,
            media_uuid,
            &incoming.sha256,
            incoming.byte_size,
            expires_at,
        )?;
        Ok(CausalMediaStageStatus {
            media_uuid: media_uuid.to_owned(),
            status: "staged".to_owned(),
            byte_size: incoming.byte_size,
            sha256: incoming.sha256.clone(),
            expires_at,
        })
    }

    /// Bind a new media UUID to a same-family consumed SHA, or replay the same
    /// UUID+SHA receipt, without accepting upload bytes. Miss, cross-family,
    /// unreadable blob, or hard-link failure is fail-closed — never a 200.
    pub fn bind_empty_causal_media_preimage(
        &self,
        principal: &Principal,
        media_uuid: &str,
        expected_sha256: &str,
        now: i64,
        limits: CausalMediaStagingLimits,
    ) -> Result<CausalMediaStageStatus, StoreError> {
        Uuid::parse_str(media_uuid).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
        if !is_lowercase_sha256(expected_sha256) || limits.ttl_seconds <= 0 {
            return Err(StoreError::InvalidCausalMediaStaging);
        }

        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        super::causal::require_current_principal(&tx, principal)?;
        if let Some(row) = load_row(&tx, &principal.family_id, media_uuid)? {
            let receipt = replay_empty_existing_row(
                &self.database_path,
                principal,
                media_uuid,
                expected_sha256,
                now,
                row,
            )?;
            tx.commit()?;
            return Ok(receipt);
        }

        let donor = tx
            .query_row(
                "SELECT media_uuid, sha256, byte_size
                   FROM causal_media_staging
                  WHERE family_id = ?1 AND status = 'consumed' AND sha256 = ?2
                  ORDER BY media_uuid
                  LIMIT 1",
                params![principal.family_id, expected_sha256],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?,
                    ))
                },
            )
            .optional()?;
        let Some((donor_uuid, donor_sha, donor_size)) = donor else {
            return Err(StoreError::InvalidCausalMediaStaging);
        };
        let donor_size =
            usize::try_from(donor_size).map_err(|_| StoreError::InvalidCausalMediaStaging)?;
        if donor_size == 0 || donor_size > limits.max_file_bytes || donor_sha != expected_sha256 {
            return Err(StoreError::InvalidCausalMediaStaging);
        }
        let source = readable_receipt_blob(
            &self.database_path,
            &principal.family_id,
            &donor_uuid,
            donor_size,
            &donor_sha,
        )?;
        let expires_at = reserve_writing_staging_row(
            &tx,
            principal,
            media_uuid,
            expected_sha256,
            donor_size,
            now,
            limits,
        )?;
        tx.commit()?;

        let destination = staging_path(&self.database_path, &principal.family_id, media_uuid);
        if let Err(error) = hard_link_without_copy(&source, &destination) {
            let connection = self.connect()?;
            let _ = connection.execute(
                "DELETE FROM causal_media_staging
                  WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'writing'
                    AND membership_id = ?3 AND sha256 = ?4 AND byte_size = ?5",
                params![
                    principal.family_id,
                    media_uuid,
                    principal.membership_id,
                    expected_sha256,
                    donor_size
                ],
            );
            return Err(error);
        }
        promote_writing_row_to_staged(
            &mut self.connect()?,
            principal,
            media_uuid,
            expected_sha256,
            donor_size,
            expires_at,
        )?;
        Ok(CausalMediaStageStatus {
            media_uuid: media_uuid.to_owned(),
            status: "staged".to_owned(),
            byte_size: donor_size,
            sha256: expected_sha256.to_owned(),
            expires_at,
        })
    }

    pub fn promote_consumed_causal_media(&self) -> Result<(), StoreError> {
        self.promote_consumed_causal_media_scoped()
    }

    /// Repairs only the receipt-authorized root manifest. Resolution replay is
    /// admission-free, so it must never turn into a scan of retained family
    /// media. Current causal root schemas cap this manifest at three items.
    pub(in crate::store) fn promote_consumed_causal_media_manifest(
        &self,
        family_id: &str,
        media: &[CausalMediaItem],
    ) -> Result<(), StoreError> {
        self.promote_consumed_causal_media_manifest_with_hook(family_id, media, None)
    }

    pub(in crate::store) fn promote_consumed_causal_media_manifest_with_hook(
        &self,
        family_id: &str,
        media: &[CausalMediaItem],
        blocking_hook: Option<&(dyn Fn(&'static str) + Send + Sync + 'static)>,
    ) -> Result<(), StoreError> {
        let publication_lock = self.causal_media_publication_lock(family_id);
        let _publication_guard = publication_lock
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if media.len() > MAX_CAUSAL_MEDIA_PER_ROOT
            || media
                .windows(2)
                .any(|pair| pair[0].media_uuid >= pair[1].media_uuid)
        {
            return Err(StoreError::InvalidCausalMediaStaging);
        }
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "SELECT family_id, media_uuid, sha256, byte_size
               FROM causal_media_staging
              WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'consumed'",
        )?;
        let mut rows = Vec::with_capacity(media.len());
        for item in media {
            let row = statement
                .query_row(params![family_id, item.media_uuid], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, i64>(3)?,
                    ))
                })
                .optional()?
                .ok_or(StoreError::InvalidCausalMediaStaging)?;
            if row.2 != item.sha256 || row.3 != item.byte_size {
                return Err(StoreError::InvalidCausalMediaStaging);
            }
            rows.push(row);
        }
        drop(statement);
        drop(connection);
        if rows.is_empty() {
            return Ok(());
        }
        for row in &rows {
            stage_consumed_bytes_for_manifest(self, row, blocking_hook)?;
        }
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        for row in &rows {
            finalize_consumed_publication_in_tx(self, &tx, &row.0, &row.1)?;
        }
        tx.commit()?;
        Ok(())
    }

    fn promote_consumed_causal_media_scoped(&self) -> Result<(), StoreError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "SELECT family_id, media_uuid, sha256, byte_size
             FROM causal_media_staging
             WHERE status = 'consumed' AND publication_confirmed = 0
             ORDER BY family_id, media_uuid
             LIMIT ?1",
        )?;
        let rows = statement
            .query_map(params![CAUSAL_MEDIA_GC_BATCH as i64], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        let full_batch = rows.len() == CAUSAL_MEDIA_GC_BATCH;
        drop(statement);
        drop(connection);
        for row in rows {
            let publication_lock = self.causal_media_publication_lock(&row.0);
            let _publication_guard = publication_lock
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            promote_consumed_row(self, row, None)?;
        }
        if full_batch {
            // Routes remain closed until every crash-incomplete publication is
            // terminal, but each restart performs one durable hard-bounded
            // batch. Confirmed rows leave the recovery index, so the next
            // restart advances without a separate mutable cursor.
            return Err(StoreError::InvalidCausalMediaStaging);
        }
        Ok(())
    }

    /// Collect at most one hard-bounded batch across every family.
    ///
    /// Eligibility and `gc_pending` are committed before filesystem work;
    /// retrying after any deletion/confirmation failure is idempotent. Receipt,
    /// version, and mutation audit rows are never compacted by this owner.
    pub fn gc_causal_media(&self, now: i64) -> Result<usize, StoreError> {
        self.gc_causal_media_scoped(None, now)
    }

    /// Run the same bounded policy after releasing one family's commit mutex.
    pub(crate) fn gc_causal_media_for_family(
        &self,
        family_id: &str,
        now: i64,
    ) -> Result<usize, StoreError> {
        self.gc_causal_media_scoped(Some(family_id), now)
    }

    fn scan_causal_media_gc_window(
        &self,
        family_scope: Option<&str>,
    ) -> Result<Vec<GcScanRow>, StoreError> {
        let cursor_key = family_scope.unwrap_or("").to_owned();
        let connection = self.connect()?;
        connection.execute(
            "INSERT OR IGNORE INTO causal_media_gc_state(family_id) VALUES (?1)",
            params![cursor_key],
        )?;
        let cursor = connection.query_row(
            "SELECT staging_after_family_id, staging_after_media_uuid
               FROM causal_media_gc_state WHERE family_id = ?1",
            params![cursor_key],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)),
        )?;
        let (scan_key, scan_after, predicate) = match family_scope {
            Some(family_id) => (
                family_id.to_owned(),
                cursor.1,
                "family_id = ?1 AND (?2 = '' OR media_uuid > ?2)",
            ),
            None => (cursor.0, cursor.1, "(family_id, media_uuid) > (?1, ?2)"),
        };
        let sql = format!(
            "WITH scan AS MATERIALIZED (
                SELECT family_id, media_uuid, consumed_at, expires_at, status
                  FROM causal_media_staging
                 WHERE {predicate}
                 ORDER BY family_id, media_uuid
                 LIMIT ?3
             )
             SELECT scan.family_id, scan.media_uuid, scan.consumed_at,
                    scan.expires_at, scan.status,
                    EXISTS(
                        SELECT 1 FROM entities e
                         WHERE e.family_id = scan.family_id
                           AND e.entity_type = 'media'
                           AND e.client_uuid = scan.media_uuid
                           AND e.deleted_at IS NULL
                    ),
                    EXISTS(
                        SELECT 1 FROM media_publications p
                         WHERE p.family_id = scan.family_id
                           AND p.media_uuid = scan.media_uuid
                    ),
                    EXISTS(
                        SELECT 1 FROM entity_version_media vm
                         WHERE vm.family_id = scan.family_id
                           AND vm.media_uuid = scan.media_uuid
                    ),
                    EXISTS(
                        SELECT 1 FROM causal_media_uploads u
                         WHERE u.family_id = scan.family_id
                           AND u.media_uuid = scan.media_uuid
                    )
               FROM scan
              ORDER BY scan.family_id, scan.media_uuid"
        );
        let mut statement = connection.prepare(&sql)?;
        let raw = statement
            .query_map(
                params![scan_key, scan_after, CAUSAL_MEDIA_GC_SCAN_LIMIT as i64,],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, Option<i64>>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, bool>(5)?,
                        row.get::<_, bool>(6)?,
                        row.get::<_, bool>(7)?,
                        row.get::<_, bool>(8)?,
                    ))
                },
            )?
            .collect::<Result<Vec<_>, _>>()?;
        let mut scanned = Vec::with_capacity(raw.len());
        for (
            family_id,
            media_uuid,
            consumed_at,
            expires_at,
            status,
            live,
            published,
            version_referenced,
            upload_active,
        ) in raw
        {
            scanned.push(GcScanRow {
                family_id,
                media_uuid,
                consumed_at,
                expires_at,
                status: StagingStatus::parse(&status)?,
                live,
                published,
                version_referenced,
                upload_active,
            });
        }
        #[cfg(test)]
        test_hook::record_candidate_inspections(&cursor_key, scanned.len());
        let next = if scanned.len() == CAUSAL_MEDIA_GC_SCAN_LIMIT {
            let last = scanned.last().expect("non-empty full GC scan window");
            (last.family_id.as_str(), last.media_uuid.as_str())
        } else {
            ("", "")
        };
        connection.execute(
            "UPDATE causal_media_gc_state
                SET staging_after_family_id = ?1, staging_after_media_uuid = ?2
              WHERE family_id = ?3",
            params![next.0, next.1, cursor_key],
        )?;
        Ok(scanned)
    }

    fn scan_causal_media_upload_window(
        &self,
        family_scope: Option<&str>,
    ) -> Result<Vec<GcUploadRow>, StoreError> {
        let cursor_key = family_scope.unwrap_or("").to_owned();
        let connection = self.connect()?;
        connection.execute(
            "INSERT OR IGNORE INTO causal_media_gc_state(family_id) VALUES (?1)",
            params![cursor_key],
        )?;
        let cursor = connection.query_row(
            "SELECT orphan_after_family_id, orphan_after_sequence
               FROM causal_media_gc_state WHERE family_id = ?1",
            params![cursor_key],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )?;
        let (scan_family, scan_sequence, predicate) = match family_scope {
            Some(family_id) => (
                family_id.to_owned(),
                cursor.1,
                "family_id = ?1 AND sequence > ?2",
            ),
            None => (cursor.0, cursor.1, "(family_id, sequence) > (?1, ?2)"),
        };
        let sql = format!(
            "SELECT family_id, sequence, expires_at
               FROM causal_media_uploads
              WHERE {predicate}
              ORDER BY family_id, sequence
              LIMIT ?3"
        );
        let mut statement = connection.prepare(&sql)?;
        let scanned = statement
            .query_map(
                params![
                    scan_family,
                    scan_sequence,
                    CAUSAL_MEDIA_GC_SCAN_LIMIT as i64,
                ],
                |row| {
                    Ok(GcUploadRow {
                        family_id: row.get(0)?,
                        sequence: row.get(1)?,
                        expires_at: row.get(2)?,
                    })
                },
            )?
            .collect::<Result<Vec<_>, _>>()?;
        drop(statement);
        #[cfg(test)]
        test_hook::record_orphan_inspections(&cursor_key, scanned.len());
        let next = if scanned.len() == CAUSAL_MEDIA_GC_SCAN_LIMIT {
            let last = scanned.last().expect("non-empty full upload GC window");
            (last.family_id.as_str(), last.sequence)
        } else {
            ("", 0)
        };
        connection.execute(
            "UPDATE causal_media_gc_state
                SET orphan_after_family_id = ?1, orphan_after_sequence = ?2
              WHERE family_id = ?3",
            params![next.0, next.1, cursor_key],
        )?;
        Ok(scanned)
    }

    fn gc_expired_causal_media_uploads_scoped(
        &self,
        family_scope: Option<&str>,
        now: i64,
        limit: usize,
    ) -> Result<usize, StoreError> {
        let candidates = self
            .scan_causal_media_upload_window(family_scope)?
            .into_iter()
            .filter(|row| row.expires_at <= now)
            .take(limit)
            .collect::<Vec<_>>();
        for row in &candidates {
            let path = incoming_upload_path(&self.database_path, &row.family_id, row.sequence);
            remove_file_if_present(&path, &row.family_id)?;
            let connection = self.connect()?;
            connection.execute(
                "DELETE FROM causal_media_uploads
                  WHERE family_id = ?1 AND sequence = ?2 AND expires_at <= ?3",
                params![row.family_id, row.sequence, now],
            )?;
        }
        Ok(candidates.len())
    }

    fn gc_causal_media_scoped(
        &self,
        family_scope: Option<&str>,
        now: i64,
    ) -> Result<usize, StoreError> {
        let candidates = self
            .scan_causal_media_gc_window(family_scope)?
            .into_iter()
            .filter(|row| row.eligible(now))
            .take(CAUSAL_MEDIA_GC_BATCH)
            .collect::<Vec<_>>();
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let mut pending = Vec::with_capacity(candidates.len());
        for candidate in candidates {
            let Some(current) = load_gc_row(&tx, &candidate.family_id, &candidate.media_uuid)?
            else {
                continue;
            };
            if !current.eligible(now) {
                continue;
            }
            if current.status == StagingStatus::GcPending {
                pending.push(current);
                continue;
            }
            let updated = tx.execute(
                "UPDATE causal_media_staging SET status = 'gc_pending'
                 WHERE family_id = ?1 AND media_uuid = ?2 AND status = ?3",
                params![
                    current.family_id,
                    current.media_uuid,
                    current.status.as_str(),
                ],
            )?;
            if updated != 1 {
                return Err(StoreError::InvalidCausalMediaStaging);
            }
            pending.push(current);
        }
        tx.commit()?;

        #[cfg(test)]
        if !pending.is_empty() && test_hook::take_fail_after_mark(&pending[0].family_id) {
            return Err(StoreError::InvalidCausalMediaStaging);
        }

        for row in &pending {
            let staged = staging_path(&self.database_path, &row.family_id, &row.media_uuid);
            remove_file_if_present(&staged, &row.family_id)?;
            if row.consumed_at.is_some() {
                let published =
                    published_path(&self.database_path, &row.family_id, &row.media_uuid);
                remove_file_if_present(&published, &row.family_id)?;
            }
            let connection = self.connect()?;
            connection.execute(
                "DELETE FROM causal_media_staging
                 WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'gc_pending'",
                params![row.family_id, row.media_uuid],
            )?;
        }
        let remaining = CAUSAL_MEDIA_GC_BATCH.saturating_sub(pending.len());
        let orphaned = if remaining == 0 {
            0
        } else {
            self.gc_expired_causal_media_uploads_scoped(family_scope, now, remaining)?
        };
        Ok(pending.len() + orphaned)
    }
}

#[cfg(test)]
pub(in crate::store) mod test_hook {
    use std::collections::HashMap;
    use std::sync::{Mutex, OnceLock};

    type StageCallback = Box<dyn FnOnce() + Send>;

    fn stage_callbacks() -> &'static Mutex<HashMap<String, StageCallback>> {
        static CALLBACKS: OnceLock<Mutex<HashMap<String, StageCallback>>> = OnceLock::new();
        CALLBACKS.get_or_init(|| Mutex::new(HashMap::new()))
    }

    pub fn after_stage_file_once(family_id: &str, callback: impl FnOnce() + Send + 'static) {
        stage_callbacks()
            .lock()
            .unwrap()
            .insert(family_id.to_owned(), Box::new(callback));
    }

    pub(super) fn after_stage_file(family_id: &str) {
        let callback = stage_callbacks().lock().unwrap().remove(family_id);
        if let Some(callback) = callback {
            callback();
        }
    }

    fn fail_family() -> &'static Mutex<Option<String>> {
        static FAIL_FAMILY: OnceLock<Mutex<Option<String>>> = OnceLock::new();
        FAIL_FAMILY.get_or_init(|| Mutex::new(None))
    }

    pub fn fail_after_mark_once(family_id: &str) {
        *fail_family().lock().unwrap() = Some(family_id.to_owned());
    }

    pub(super) fn take_fail_after_mark(family_id: &str) -> bool {
        let mut armed = fail_family().lock().unwrap();
        if armed.as_deref() == Some(family_id) {
            armed.take();
            true
        } else {
            false
        }
    }

    fn fail_parent_sync_family() -> &'static Mutex<Option<String>> {
        static FAIL_FAMILY: OnceLock<Mutex<Option<String>>> = OnceLock::new();
        FAIL_FAMILY.get_or_init(|| Mutex::new(None))
    }

    pub fn fail_before_parent_sync_once(family_id: &str) {
        *fail_parent_sync_family().lock().unwrap() = Some(family_id.to_owned());
    }

    pub(super) fn take_fail_before_parent_sync(family_id: &str) -> bool {
        let mut armed = fail_parent_sync_family().lock().unwrap();
        if armed.as_deref() == Some(family_id) {
            armed.take();
            true
        } else {
            false
        }
    }

    fn orphan_inspection_counts() -> &'static Mutex<HashMap<String, usize>> {
        static COUNTS: OnceLock<Mutex<HashMap<String, usize>>> = OnceLock::new();
        COUNTS.get_or_init(|| Mutex::new(HashMap::new()))
    }

    pub(super) fn record_orphan_inspections(scope: &str, inspected: usize) {
        orphan_inspection_counts()
            .lock()
            .unwrap()
            .insert(scope.to_owned(), inspected);
    }

    pub fn orphan_inspections(scope: &str) -> usize {
        orphan_inspection_counts()
            .lock()
            .unwrap()
            .get(scope)
            .copied()
            .unwrap_or(0)
    }

    fn candidate_inspection_counts() -> &'static Mutex<HashMap<String, usize>> {
        static COUNTS: OnceLock<Mutex<HashMap<String, usize>>> = OnceLock::new();
        COUNTS.get_or_init(|| Mutex::new(HashMap::new()))
    }

    pub(super) fn record_candidate_inspections(scope: &str, inspected: usize) {
        candidate_inspection_counts()
            .lock()
            .unwrap()
            .insert(scope.to_owned(), inspected);
    }

    pub fn candidate_inspections(scope: &str) -> usize {
        candidate_inspection_counts()
            .lock()
            .unwrap()
            .get(scope)
            .copied()
            .unwrap_or(0)
    }
}
