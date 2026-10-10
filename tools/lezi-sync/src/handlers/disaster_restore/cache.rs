//! Compact immutable upload index. Root payloads are retained only on disk, never in this cache.
//! Final activation always decodes and hashes the complete durable manifest afresh.
use super::{
    load_fresh_manifest, manifest_path, staged_media_path, verified_marker, ApiError,
    RestoreJournal, RestoreMediaSpec,
};
use std::os::unix::fs::MetadataExt;
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, OnceLock},
};
const MAX_ENTRIES: usize = 8;
const MAX_CHARGED_BYTES: usize = 32 * 1024 * 1024;
#[derive(Clone, PartialEq, Eq)]
struct Stamp {
    device: u64,
    inode: u64,
    size: u64,
    mtime: i64,
    mtime_nsec: i64,
    ctime: i64,
    ctime_nsec: i64,
}
fn stamp(path: &Path) -> Result<Stamp, ApiError> {
    let m = std::fs::symlink_metadata(path)?;
    if !m.file_type().is_file() {
        return Err(ApiError::conflict(
            "restore manifest must be a regular private file",
        ));
    }
    Ok(Stamp {
        device: m.dev(),
        inode: m.ino(),
        size: m.len(),
        mtime: m.mtime(),
        mtime_nsec: m.mtime_nsec(),
        ctime: m.ctime(),
        ctime_nsec: m.ctime_nsec(),
    })
}
struct Progress {
    verified: Vec<bool>,
    count: usize,
}
#[derive(Clone)]
struct Entry {
    media: Arc<Vec<RestoreMediaSpec>>,
    stamp: Stamp,
    hash: String,
    progress: Arc<Mutex<Progress>>,
    charge: usize,
    last_used: u64,
}
#[derive(Default)]
struct Cache {
    entries: BTreeMap<PathBuf, Entry>,
    clock: u64,
}
fn cache() -> &'static Mutex<Cache> {
    static CACHE: OnceLock<Mutex<Cache>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(Cache::default()))
}
fn lock() -> Result<std::sync::MutexGuard<'static, Cache>, ApiError> {
    cache()
        .lock()
        .map_err(|_| ApiError::internal("restore cache lock failed"))
}
fn charge(media: &[RestoreMediaSpec]) -> usize {
    media
        .iter()
        .map(|m| {
            std::mem::size_of::<RestoreMediaSpec>()
                + m.client_uuid.capacity()
                + m.sha256.capacity()
                + 1
        })
        .sum::<usize>()
}
pub(super) fn validate_capacity(media: &[RestoreMediaSpec]) -> Result<(), ApiError> {
    if charge(media) > MAX_CHARGED_BYTES {
        return Err(ApiError::payload_too_large(
            "restore upload index exceeds its bounded memory capacity",
        )
        .with_code("restore_capacity_exceeded"));
    }
    Ok(())
}
fn load(data_root: &Path, journal: &RestoreJournal) -> Result<Entry, ApiError> {
    let path = manifest_path(data_root, &journal.batch_id)?;
    let before = stamp(&path)?;
    let expected = journal
        .manifest_hash
        .as_deref()
        .ok_or_else(|| ApiError::conflict("restore manifest is missing"))?;
    {
        let mut cache = lock()?;
        cache.clock = cache.clock.wrapping_add(1);
        let clock = cache.clock;
        if let Some(entry) = cache.entries.get_mut(&path) {
            if entry.stamp == before && entry.hash == expected {
                entry.last_used = clock;
                return Ok(entry.clone());
            }
        }
        cache.entries.remove(&path);
    }
    let manifest = load_fresh_manifest(data_root, journal)?;
    validate_capacity(&manifest.media)?;
    if stamp(&path)? != before {
        return Err(ApiError::conflict(
            "restore manifest changed while being read",
        ));
    }
    let media = Arc::new(manifest.media);
    let mut verified = vec![false; media.len()];
    let mut count = 0;
    for (index, spec) in media.iter().enumerate() {
        let marker = std::fs::read(verified_marker(
            data_root,
            &journal.batch_id,
            &spec.client_uuid,
        )?);
        if !marker.is_ok_and(|bytes| bytes == spec.sha256.as_bytes()) {
            continue;
        }
        if metadata_ready(data_root, journal, spec)? {
            verified[index] = true;
            count += 1;
        }
    }
    let mut entry = Entry {
        charge: charge(&media),
        media,
        stamp: before,
        hash: expected.to_owned(),
        progress: Arc::new(Mutex::new(Progress { verified, count })),
        last_used: 0,
    };
    let mut cache = lock()?;
    while cache.entries.len() >= MAX_ENTRIES
        || cache
            .entries
            .values()
            .map(|e| e.charge)
            .sum::<usize>()
            .saturating_add(entry.charge)
            > MAX_CHARGED_BYTES
    {
        let oldest = cache
            .entries
            .iter()
            .min_by_key(|(_, e)| e.last_used)
            .map(|(p, _)| p.clone());
        if let Some(oldest) = oldest {
            cache.entries.remove(&oldest);
        } else {
            break;
        }
    }
    cache.clock = cache.clock.wrapping_add(1);
    entry.last_used = cache.clock;
    cache.entries.insert(path, entry.clone());
    Ok(entry)
}
fn metadata_ready(
    data_root: &Path,
    journal: &RestoreJournal,
    spec: &RestoreMediaSpec,
) -> Result<bool, ApiError> {
    let staged = staged_media_path(data_root, &journal.batch_id, &spec.client_uuid)?;
    let path = if staged.exists() {
        staged
    } else {
        data_root
            .join("media")
            .join(&journal.family_id)
            .join(&spec.client_uuid)
    };
    #[cfg(test)]
    record_metadata(data_root);
    match std::fs::symlink_metadata(path) {
        Ok(m) => Ok(m.file_type().is_file() && m.len() == spec.byte_size as u64),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(e.into()),
    }
}
pub(super) fn media(
    data_root: &Path,
    journal: &RestoreJournal,
) -> Result<Arc<Vec<RestoreMediaSpec>>, ApiError> {
    Ok(load(data_root, journal)?.media)
}
pub(super) fn ready(
    data_root: &Path,
    journal: &RestoreJournal,
    validate_files: bool,
) -> Result<bool, ApiError> {
    let entry = load(data_root, journal)?;
    let complete = entry
        .progress
        .lock()
        .map_err(|_| ApiError::internal("restore progress lock failed"))?
        .count
        == entry.media.len();
    if !complete || !validate_files {
        return Ok(complete);
    }
    let mut complete = true;
    for spec in entry.media.iter() {
        if !metadata_ready(data_root, journal, spec)? {
            invalidate_media(data_root, journal, &spec.client_uuid)?;
            complete = false;
        }
    }
    Ok(complete)
}
pub(super) fn verified(
    data_root: &Path,
    journal: &RestoreJournal,
    media: &str,
) -> Result<(), ApiError> {
    let entry = load(data_root, journal)?;
    let index = entry
        .media
        .binary_search_by(|m| m.client_uuid.as_str().cmp(media))
        .map_err(|_| ApiError::conflict("verified media is not in manifest"))?;
    let mut p = entry
        .progress
        .lock()
        .map_err(|_| ApiError::internal("restore progress lock failed"))?;
    if !p.verified[index] {
        p.verified[index] = true;
        p.count += 1;
    }
    Ok(())
}
pub(super) fn invalidate_media(
    data_root: &Path,
    journal: &RestoreJournal,
    media: &str,
) -> Result<(), ApiError> {
    if let Some(entry) = lock()?
        .entries
        .get(&manifest_path(data_root, &journal.batch_id)?)
    {
        if let Ok(index) = entry
            .media
            .binary_search_by(|m| m.client_uuid.as_str().cmp(media))
        {
            let mut p = entry
                .progress
                .lock()
                .map_err(|_| ApiError::internal("restore progress lock failed"))?;
            if p.verified[index] {
                p.verified[index] = false;
                p.count -= 1;
            }
        }
    }
    match std::fs::remove_file(verified_marker(data_root, &journal.batch_id, media)?) {
        Ok(()) => super::sync_directory(&super::batch_dir(data_root, &journal.batch_id)?)?,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
        Err(e) => return Err(e.into()),
    }
    Ok(())
}
pub(super) fn retire(data_root: &Path, journal: &RestoreJournal) -> Result<(), ApiError> {
    lock()?
        .entries
        .remove(&manifest_path(data_root, &journal.batch_id)?);
    Ok(())
}
#[cfg(test)]
pub(super) fn clear(data_root: &Path, journal: &RestoreJournal) {
    retire(data_root, journal).unwrap();
}

#[cfg(test)]
#[derive(Default, Clone, Debug)]
pub(super) struct Work {
    pub manifest_reads: usize,
    pub media_hashes: usize,
    pub media_bytes: usize,
    pub metadata_checks: usize,
}
#[cfg(test)]
fn work() -> &'static Mutex<BTreeMap<PathBuf, Work>> {
    static WORK: OnceLock<Mutex<BTreeMap<PathBuf, Work>>> = OnceLock::new();
    WORK.get_or_init(|| Mutex::new(BTreeMap::new()))
}
#[cfg(test)]
pub(super) fn record_manifest(root: &Path) {
    let mut w = work().lock().unwrap();
    if let Some(w) = w.get_mut(root) {
        w.manifest_reads += 1;
    }
}
#[cfg(test)]
pub(super) fn record_media(path: &Path, size: usize) {
    let mut w = work().lock().unwrap();
    for (root, w) in w.iter_mut() {
        if path.starts_with(root) {
            w.media_hashes += 1;
            w.media_bytes += size;
        }
    }
}
#[cfg(test)]
pub(super) fn begin_work(root: &Path) {
    work()
        .lock()
        .unwrap()
        .insert(root.to_owned(), Work::default());
}
#[cfg(test)]
pub(super) fn finish_work(root: &Path) -> Work {
    work().lock().unwrap().remove(root).unwrap()
}

#[cfg(test)]
pub(super) fn record_metadata(root: &Path) {
    let mut w = work().lock().unwrap();
    if let Some(w) = w.get_mut(root) {
        w.metadata_checks += 1;
    }
}
