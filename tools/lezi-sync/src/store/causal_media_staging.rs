//! Durable, manifest-bound lifecycle for causal media preimages.
//!
//! Bytes remain under `.causal-stage` until the causal SQLite transaction has
//! consumed their exact UUID/SHA/size manifest. `consumed` is the crash journal:
//! startup can finish publication before serving requests, while expired open
//! preimages use `gc_pending` until both bytes and metadata are durably removed.

use std::collections::BTreeSet;
use std::fs::{self, OpenOptions};
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};

use rusqlite::{params, OptionalExtension, Transaction, TransactionBehavior};
use serde::Serialize;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::{CausalMediaItem, Principal, Store, StoreError};

pub(crate) const CAUSAL_MEDIA_STAGING_TTL_SECONDS: i64 = 24 * 60 * 60;
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

fn published_path(database_path: &Path, family_id: &str, media_uuid: &str) -> PathBuf {
    media_root(database_path).join(family_id).join(media_uuid)
}

fn checked_digest(path: &Path, byte_size: usize, sha256: &str) -> Result<bool, StoreError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(error.into()),
    };
    if !metadata.file_type().is_file() || metadata.len() != byte_size as u64 {
        return Ok(false);
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
    Ok(hex::encode(digest.finalize()) == sha256)
}

fn sync_parent(path: &Path) -> Result<(), StoreError> {
    if let Some(parent) = path.parent().filter(|parent| parent.exists()) {
        crate::sync_directory(parent)?;
    }
    Ok(())
}

fn remove_untracked_staging_files(
    database_path: &Path,
    tracked: &BTreeSet<(String, String)>,
    family_scope: Option<&str>,
) -> Result<(), StoreError> {
    let root = staging_root(database_path);
    if !root.try_exists()? {
        return Ok(());
    }
    let mut root_changed = false;
    for family_entry in fs::read_dir(&root)? {
        let family_entry = family_entry?;
        let family_name = family_entry.file_name().to_string_lossy().into_owned();
        if family_scope.is_some_and(|scope| scope != family_name) {
            continue;
        }
        if !family_entry.file_type()?.is_dir() {
            fs::remove_file(family_entry.path())?;
            root_changed = true;
            continue;
        }
        let mut family_changed = false;
        for entry in fs::read_dir(family_entry.path())? {
            let entry = entry?;
            let media_name = entry.file_name().to_string_lossy().into_owned();
            if tracked.contains(&(family_name.clone(), media_name)) {
                continue;
            }
            if entry.file_type()?.is_dir() {
                fs::remove_dir_all(entry.path())?;
            } else {
                fs::remove_file(entry.path())?;
            }
            family_changed = true;
        }
        if family_changed {
            crate::sync_directory(&family_entry.path())?;
            root_changed = true;
        }
    }
    if root_changed {
        crate::sync_directory(&root)?;
    }
    Ok(())
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

fn advance_family_rev(tx: &Transaction<'_>, family_id: &str) -> Result<i64, StoreError> {
    tx.execute(
        "UPDATE family_meta SET rev = rev + 1 WHERE family_id = ?1",
        params![family_id],
    )?;
    tx.query_row(
        "SELECT rev FROM family_meta WHERE family_id = ?1",
        params![family_id],
        |row| row.get(0),
    )
    .map_err(StoreError::from)
}

fn finalize_consumed_publication(
    store: &Store,
    family_id: &str,
    media_uuid: &str,
) -> Result<(), StoreError> {
    let mut connection = store.connect()?;
    let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let row = tx
        .query_row(
            "SELECT deleted_at, payload_json FROM entities
             WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
            params![family_id, media_uuid],
            |row| Ok((row.get::<_, Option<i64>>(0)?, row.get::<_, String>(1)?)),
        )
        .optional()?;
    let Some((deleted_at, payload_json)) = row else {
        tx.commit()?;
        return Ok(()); // Branch-only preimage: retained but not publicly addressable.
    };
    if deleted_at.is_some() {
        tx.commit()?;
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
        tx.commit()?;
        return Ok(());
    }

    let payload = super::parse_payload(&payload_json)?;
    let (owner_type, owner_uuid) =
        super::media_association_owner(&payload)?.ok_or(StoreError::InvalidStoredPayload)?;
    let media_rev = advance_family_rev(&tx, family_id)?;
    tx.execute(
        "UPDATE entities SET rev = ?1
         WHERE family_id = ?2 AND entity_type = 'media' AND client_uuid = ?3",
        params![media_rev, family_id, media_uuid],
    )?;
    let owner_rev = advance_family_rev(&tx, family_id)?;
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
    tx.commit()?;
    Ok(())
}

fn promote_consumed_row(
    store: &Store,
    row: (String, String, String, i64),
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

pub(in crate::store) fn verify_manifest(
    tx: &Transaction<'_>,
    database_path: &Path,
    principal: &Principal,
    media: &[CausalMediaItem],
    now: i64,
) -> Result<(), &'static str> {
    for item in media {
        let existing_payload = tx
            .query_row(
                "SELECT payload_json FROM entities
                 WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2",
                params![principal.family_id, item.media_uuid],
                |row| row.get::<_, String>(0),
            )
            .optional()
            .map_err(|_| "media_staging_invalid")?;
        if let Some(payload_json) = existing_payload {
            let payload =
                super::parse_payload(&payload_json).map_err(|_| "media_staging_invalid")?;
            let existing_sha = payload.get("sha256").and_then(serde_json::Value::as_str);
            let existing_size = payload
                .get("byte_size")
                .and_then(serde_json::Value::as_i64)
                .ok_or("media_staging_invalid")?;
            if existing_sha.is_some_and(|sha| sha != item.sha256) || existing_size != item.byte_size
            {
                return Err("media_uuid_conflict");
            }
        }
        let row = load_row(tx, &principal.family_id, &item.media_uuid)
            .map_err(|_| "media_staging_invalid")?
            .ok_or("missing_media_bytes")?;
        if row.sha256 != item.sha256 {
            return Err("media_sha256_mismatch");
        }
        if row.byte_size as i64 != item.byte_size {
            return Err("media_byte_size_mismatch");
        }
        let published = published_path(database_path, &principal.family_id, &item.media_uuid);
        match fs::symlink_metadata(&published) {
            Ok(_) => {
                if !checked_digest(&published, row.byte_size, &row.sha256)
                    .map_err(|_| "media_staging_invalid")?
                {
                    return Err("media_uuid_conflict");
                }
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err("media_staging_invalid"),
        }
        let path = if row.status == StagingStatus::Consumed {
            published
        } else {
            if row.membership_id != principal.membership_id {
                return Err("media_membership_mismatch");
            }
            if row.expires_at <= now || row.status != StagingStatus::Staged {
                return Err("media_preimage_expired");
            }
            staging_path(database_path, &principal.family_id, &item.media_uuid)
        };
        if !checked_digest(&path, row.byte_size, &row.sha256)
            .map_err(|_| "media_staging_invalid")?
        {
            return Err("media_bytes_integrity_mismatch");
        }
    }
    Ok(())
}

pub(in crate::store) fn consume_manifest(
    tx: &Transaction<'_>,
    principal: &Principal,
    media: &[CausalMediaItem],
    now: i64,
) -> Result<(), StoreError> {
    for item in media {
        tx.execute(
            "UPDATE causal_media_staging
             SET status = 'consumed', consumed_at = COALESCE(consumed_at, ?1)
             WHERE family_id = ?2 AND media_uuid = ?3
               AND sha256 = ?4 AND byte_size = ?5
               AND (status = 'consumed' OR (status = 'staged' AND membership_id = ?6))",
            params![
                now,
                principal.family_id,
                item.media_uuid,
                item.sha256,
                item.byte_size,
                principal.membership_id,
            ],
        )?;
    }
    Ok(())
}

impl Store {
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
        if let Some(row) = load_row(&tx, &principal.family_id, media_uuid)? {
            if row.status == StagingStatus::GcPending
                || (row.expires_at <= now && row.status != StagingStatus::Consumed)
            {
                return Err(StoreError::InvalidCausalMediaStaging);
            }
            if row.sha256 != incoming.sha256 || row.byte_size != incoming.byte_size {
                return Err(StoreError::CausalMediaPreimageConflict);
            }
            if row.membership_id != principal.membership_id {
                return Err(StoreError::CausalMediaMembershipMismatch);
            }
            tx.commit()?;
            let path = if row.status == StagingStatus::Consumed {
                published_path(&self.database_path, &principal.family_id, media_uuid)
            } else {
                staging_path(&self.database_path, &principal.family_id, media_uuid)
            };
            install_staged_file(&path, incoming)?;
            if row.status == StagingStatus::Writing {
                let connection = self.connect()?;
                let updated = connection.execute(
                    "UPDATE causal_media_staging SET status = 'staged'
                     WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'writing'
                       AND membership_id = ?3 AND sha256 = ?4 AND byte_size = ?5",
                    params![
                        principal.family_id,
                        media_uuid,
                        principal.membership_id,
                        incoming.sha256,
                        incoming.byte_size,
                    ],
                )?;
                if updated != 1 {
                    return Err(StoreError::InvalidCausalMediaStaging);
                }
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
        if family_bytes.saturating_add(incoming.byte_size as i64) > limits.max_family_bytes as i64 {
            return Err(StoreError::CausalMediaStagingQuota("family_bytes"));
        }
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
                incoming.sha256,
                incoming.byte_size,
                now,
                expires_at,
            ],
        )?;
        tx.commit()?;

        let path = staging_path(&self.database_path, &principal.family_id, media_uuid);
        install_staged_file(&path, incoming)?;
        let connection = self.connect()?;
        let updated = connection.execute(
            "UPDATE causal_media_staging SET status = 'staged'
             WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'writing'
               AND membership_id = ?3 AND sha256 = ?4 AND byte_size = ?5",
            params![
                principal.family_id,
                media_uuid,
                principal.membership_id,
                incoming.sha256,
                incoming.byte_size,
            ],
        )?;
        if updated != 1 {
            return Err(StoreError::InvalidCausalMediaStaging);
        }
        Ok(CausalMediaStageStatus {
            media_uuid: media_uuid.to_owned(),
            status: "staged".to_owned(),
            byte_size: incoming.byte_size,
            sha256: incoming.sha256.clone(),
            expires_at,
        })
    }

    pub fn promote_consumed_causal_media(&self) -> Result<(), StoreError> {
        self.promote_consumed_causal_media_scoped(None)
    }

    pub fn promote_consumed_causal_media_for_family(
        &self,
        family_id: &str,
    ) -> Result<(), StoreError> {
        self.promote_consumed_causal_media_scoped(Some(family_id))
    }

    /// Repairs only the receipt-authorized root manifest. Resolution replay is
    /// admission-free, so it must never turn into a scan of retained family
    /// media. Current causal root schemas cap this manifest at three items.
    pub(in crate::store) fn promote_consumed_causal_media_manifest(
        &self,
        family_id: &str,
        media: &[CausalMediaItem],
    ) -> Result<(), StoreError> {
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
        for row in rows {
            promote_consumed_row(self, row)?;
        }
        Ok(())
    }

    fn promote_consumed_causal_media_scoped(
        &self,
        family_scope: Option<&str>,
    ) -> Result<(), StoreError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "SELECT family_id, media_uuid, sha256, byte_size
             FROM causal_media_staging
             WHERE status = 'consumed' AND (?1 IS NULL OR family_id = ?1)
             ORDER BY family_id, media_uuid",
        )?;
        let rows = statement
            .query_map(params![family_scope], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        drop(statement);
        drop(connection);
        for row in rows {
            promote_consumed_row(self, row)?;
        }
        Ok(())
    }

    pub fn gc_expired_causal_media_preimages(&self, now: i64) -> Result<usize, StoreError> {
        self.gc_expired_causal_media_preimages_scoped(None, now)
    }

    #[cfg(test)]
    pub(super) fn gc_expired_causal_media_preimages_for_family(
        &self,
        family_id: &str,
        now: i64,
    ) -> Result<usize, StoreError> {
        self.gc_expired_causal_media_preimages_scoped(Some(family_id), now)
    }

    fn gc_expired_causal_media_preimages_scoped(
        &self,
        family_scope: Option<&str>,
        now: i64,
    ) -> Result<usize, StoreError> {
        let mut connection = self.connect()?;
        let tx = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        tx.execute(
            "UPDATE causal_media_staging SET status = 'gc_pending'
             WHERE status IN ('writing', 'staged') AND expires_at <= ?1
               AND (?2 IS NULL OR family_id = ?2)",
            params![now, family_scope],
        )?;
        let mut statement = tx.prepare(
            "SELECT family_id, media_uuid FROM causal_media_staging
             WHERE status = 'gc_pending' AND (?1 IS NULL OR family_id = ?1)
             ORDER BY family_id, media_uuid",
        )?;
        let pending = statement
            .query_map(params![family_scope], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        drop(statement);
        tx.commit()?;

        for (family_id, media_uuid) in &pending {
            let path = staging_path(&self.database_path, family_id, media_uuid);
            match fs::remove_file(&path) {
                Ok(()) => sync_parent(&path)?,
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => sync_parent(&path)?,
                Err(error) => return Err(error.into()),
            }
            let connection = self.connect()?;
            connection.execute(
                "DELETE FROM causal_media_staging
                 WHERE family_id = ?1 AND media_uuid = ?2 AND status = 'gc_pending'",
                params![family_id, media_uuid],
            )?;
        }
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "SELECT family_id, media_uuid FROM causal_media_staging
             WHERE (?1 IS NULL OR family_id = ?1)
             ORDER BY family_id, media_uuid",
        )?;
        let tracked = statement
            .query_map(params![family_scope], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<Result<BTreeSet<_>, _>>()?;
        drop(statement);
        drop(connection);
        remove_untracked_staging_files(&self.database_path, &tracked, family_scope)?;
        Ok(pending.len())
    }
}
