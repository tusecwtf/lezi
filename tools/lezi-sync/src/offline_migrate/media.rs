//! Media path layout + file authority for offline v3→current data-dir migration.
//!
//! Ticket 03 public seams (crate-internal):
//! - [`media_file_relative_path`] — current server layout under a data dir
//! - [`migrate_v3_data_dir`] — DB migrate + copy/validate authority media bytes
//!
//! Authority media = retained `media_publications` with `source ∈ {ordinary,bundle}`
//! union retained `sync_bundle_media`. Missing / size / sha256 mismatch →
//! [`AuthoritativeFailure::MediaFileMissingOrMismatch`] and no copy-back-ready dest.

use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use rusqlite::Connection;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::inventory::AuthoritativeFailure;
use super::migrator::{migrate_v3_database, remove_db_files, MigrateError, MigrateReport};

/// Relative path under a data dir matching inventory `media/{family_uuid}/{media_uuid}`
/// and live server final media layout (`data/media/{family}/{uuid}`).
///
/// Both IDs are UUID-parsed and re-stringified (same as live `AppState::media_path`)
/// so path components cannot contain `..` or other non-UUID text.
pub(crate) fn media_file_relative_path(
    family_id: &str,
    media_uuid: &str,
) -> Result<PathBuf, MigrateError> {
    let (family_id, media_uuid) = canonical_media_path_ids(family_id, media_uuid)?;
    Ok(PathBuf::from("media").join(family_id).join(media_uuid))
}

fn canonical_media_path_ids(
    family_id: &str,
    media_uuid: &str,
) -> Result<(String, String), MigrateError> {
    let family_id = Uuid::parse_str(family_id)
        .map(|id| id.to_string())
        .map_err(|_| {
            MigrateError::Internal(format!(
                "invalid family_id for media path (expected UUID): `{family_id}`"
            ))
        })?;
    let media_uuid = Uuid::parse_str(media_uuid)
        .map(|id| id.to_string())
        .map_err(|_| {
            MigrateError::Internal(format!(
                "invalid media_uuid for media path (expected UUID): `{media_uuid}`"
            ))
        })?;
    Ok((family_id, media_uuid))
}

/// One-shot offline: v3 data dir (`lezi.db` + optional `media/`) → current data dir.
///
/// - Transforms `source/lezi.db` via [`migrate_v3_database`] (ops new root password).
/// - Wipes any pre-existing `dest/media`, then copies only authority media files
///   into `dest/media/{family}/{uuid}` so the media tree is exactly this run's set.
/// - Validates size (entity `byte_size` and/or bundle_media sizes) and `staged_sha256`
///   when present.
/// - On any media failure: removes dest `lezi.db` (+ sidecars), `server.secret`, and
///   `media/` written this run (`FailureMode::AbortNoCopyBackWithReport`).
pub(crate) fn migrate_v3_data_dir(
    source_data_dir: &Path,
    dest_data_dir: &Path,
    new_root_password: &str,
) -> Result<MigrateReport, MigrateError> {
    let source_db = source_data_dir.join("lezi.db");
    let dest_db = dest_data_dir.join("lezi.db");

    if !source_db.try_exists()? {
        return Err(MigrateError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            format!("source database missing: {}", source_db.display()),
        )));
    }

    fs::create_dir_all(dest_data_dir)?;
    let mut report = migrate_v3_database(&source_db, &dest_db, new_root_password)?;

    match transfer_authority_media(source_data_dir, dest_data_dir, &dest_db, &mut report) {
        Ok(()) => Ok(report),
        Err(error) => {
            cleanup_dest_data_dir_outputs(dest_data_dir, &dest_db);
            Err(error)
        }
    }
}

/// Remove migrator-owned outputs under dest so failures are AbortNoCopyBack.
///
/// Wipes `lezi.db` (+ sidecars), `server.secret` (+ secret temps), and `media/`.
/// Used on media abort and by the ticket-05 CLI when post-migrate validate fails.
pub(crate) fn cleanup_migrator_data_dir_outputs(dest_data_dir: &Path) {
    cleanup_dest_data_dir_outputs(dest_data_dir, &dest_data_dir.join("lezi.db"));
}

/// Remove all migrator-owned outputs under dest so a media failure is AbortNoCopyBack.
fn cleanup_dest_data_dir_outputs(dest_data_dir: &Path, dest_db: &Path) {
    remove_db_files(dest_db);
    let _ = fs::remove_dir_all(dest_data_dir.join("media"));
    let _ = fs::remove_file(dest_data_dir.join("server.secret"));
    if let Ok(entries) = fs::read_dir(dest_data_dir) {
        for entry in entries.flatten() {
            let name = entry.file_name();
            let Some(s) = name.to_str() else {
                continue;
            };
            if s.starts_with(".server.secret.") && s.ends_with(".tmp") {
                let _ = fs::remove_file(entry.path());
            }
            // Leftover atomic media temps under media/ are removed with remove_dir_all.
        }
    }
}

#[derive(Debug, Clone, Default)]
struct MediaAuthority {
    /// Expected file size when known (entity byte_size or bundle_media sizes).
    expected_size: Option<u64>,
    /// Hex sha256 from retained sync_bundle_media.staged_sha256 when present.
    expected_sha256: Option<String>,
    /// True when referenced by a kept publication or retained bundle_media row.
    required: bool,
}

fn transfer_authority_media(
    source_data_dir: &Path,
    dest_data_dir: &Path,
    dest_db: &Path,
    report: &mut MigrateReport,
) -> Result<(), MigrateError> {
    // out purity: dest/media must be exactly the authority set written this run.
    // Wipe any pre-existing tree (reuse of out/, leftover non-authority bytes).
    let dest_media_root = dest_data_dir.join("media");
    if dest_media_root.try_exists()? {
        fs::remove_dir_all(&dest_media_root)?;
    }

    let dest = Connection::open_with_flags(
        dest_db,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    let required = collect_required_media(&dest, report)?;
    if required.is_empty() {
        return Ok(());
    }

    for ((family_id, media_uuid), auth) in &required {
        // Single I/O path source: data_dir + media_file_relative_path (UUID-canonical).
        let rel = media_file_relative_path(family_id, media_uuid)?;
        let source_path = source_data_dir.join(&rel);
        let dest_path = dest_data_dir.join(&rel);

        validate_and_copy_media_file(
            &source_path,
            &dest_path,
            auth,
            family_id,
            media_uuid,
            &rel,
            report,
        )?;
        report.media_files_copied += 1;
    }
    Ok(())
}

fn collect_required_media(
    dest: &Connection,
    report: &MigrateReport,
) -> Result<BTreeMap<(String, String), MediaAuthority>, MigrateError> {
    let mut map: BTreeMap<(String, String), MediaAuthority> = BTreeMap::new();

    // 1) Kept publications: source ordinary|bundle only (bundle_pending discarded earlier).
    {
        let mut stmt = dest.prepare(
            "
            SELECT family_id, media_uuid, source
            FROM media_publications
            ",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
            ))
        })?;
        for row in rows {
            let (family_id, media_uuid, source) = row?;
            match source.as_str() {
                "ordinary" | "bundle" => {
                    let entry = map.entry((family_id, media_uuid)).or_default();
                    entry.required = true;
                }
                "bundle_pending" => {
                    // Dest must not retain these after migrate — post-migrate invariant.
                    return Err(MigrateError::Internal(format!(
                        "dest retains unmappable publication source `bundle_pending` for media `{media_uuid}`"
                    )));
                }
                other => {
                    // Illegal publication source domain value on dest.
                    return Err(MigrateError::authoritative_failure(
                        AuthoritativeFailure::SourceConstrainedValueInvalid,
                        format!(
                            "dest retains unmappable publication source `{other}` for media `{media_uuid}`"
                        ),
                        report.clone(),
                    ));
                }
            }
        }
    }

    // 2) Retained sync_bundle_media — file authority + size/hash when staged.
    {
        let mut stmt = dest.prepare(
            "
            SELECT family_id, media_uuid, declared_byte_size, staged_byte_size, staged_sha256
            FROM sync_bundle_media
            ",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, Option<i64>>(2)?,
                row.get::<_, Option<i64>>(3)?,
                row.get::<_, Option<String>>(4)?,
            ))
        })?;
        for row in rows {
            let (family_id, media_uuid, declared, staged_size, staged_sha) = row?;
            let entry = map.entry((family_id, media_uuid.clone())).or_default();
            entry.required = true;
            let size_hint =
                resolve_bundle_media_row_size(&media_uuid, declared, staged_size, report)?;
            if let Some(size) = size_hint {
                if let Some(existing) = entry.expected_size {
                    if existing != size {
                        return Err(MigrateError::authoritative_failure(
                            AuthoritativeFailure::MediaFileMissingOrMismatch,
                            format!(
                                "conflicting expected sizes for media `{media_uuid}`: {existing} vs {size}"
                            ),
                            report.clone(),
                        ));
                    }
                } else {
                    entry.expected_size = Some(size);
                }
            }
            if let Some(sha) = staged_sha {
                if let Some(existing) = entry.expected_sha256.as_ref() {
                    if existing != &sha {
                        return Err(MigrateError::authoritative_failure(
                            AuthoritativeFailure::MediaFileMissingOrMismatch,
                            format!("conflicting staged_sha256 for media `{media_uuid}`"),
                            report.clone(),
                        ));
                    }
                } else {
                    entry.expected_sha256 = Some(sha);
                }
            }
        }
    }

    // 3) Live media entity payloads supply byte_size when present.
    {
        let mut stmt = dest.prepare(
            "
            SELECT family_id, client_uuid, payload_json
            FROM entities
            WHERE entity_type = 'media' AND deleted_at IS NULL
            ",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
            ))
        })?;
        for row in rows {
            let (family_id, media_uuid, payload_json) = row?;
            let key = (family_id, media_uuid.clone());
            // Only constrain required media; orphan media entities without pub/bundle_media
            // are not file authority for ticket 03.
            let Some(entry) = map.get_mut(&key) else {
                continue;
            };
            let payload: serde_json::Value = serde_json::from_str(&payload_json)?;
            let Some(size) = payload.get("byte_size").and_then(|v| v.as_u64()) else {
                continue;
            };
            if let Some(existing) = entry.expected_size {
                if existing != size {
                    return Err(MigrateError::authoritative_failure(
                        AuthoritativeFailure::MediaFileMissingOrMismatch,
                        format!(
                            "entity byte_size {size} disagrees with expected {existing} for media `{media_uuid}`"
                        ),
                        report.clone(),
                    ));
                }
            } else {
                entry.expected_size = Some(size);
            }
        }
    }

    // Drop non-required (should be empty) and return only required keys.
    map.retain(|_, v| v.required);
    Ok(map)
}

/// Fail-closed size authority for one `sync_bundle_media` row.
///
/// - Negative `declared_byte_size` / `staged_byte_size` → mismatch (corrupt retained row).
/// - Both present and unequal → mismatch (no silent preference for staged).
/// - Otherwise the agreed non-negative size, or `None` when both columns are null.
fn resolve_bundle_media_row_size(
    media_uuid: &str,
    declared: Option<i64>,
    staged: Option<i64>,
    report: &MigrateReport,
) -> Result<Option<u64>, MigrateError> {
    let declared_u = match declared {
        Some(s) if s < 0 => {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::MediaFileMissingOrMismatch,
                format!(
                    "negative declared_byte_size ({s}) for media `{media_uuid}` in sync_bundle_media"
                ),
                report.clone(),
            ));
        }
        Some(s) => Some(s as u64),
        None => None,
    };
    let staged_u = match staged {
        Some(s) if s < 0 => {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::MediaFileMissingOrMismatch,
                format!(
                    "negative staged_byte_size ({s}) for media `{media_uuid}` in sync_bundle_media"
                ),
                report.clone(),
            ));
        }
        Some(s) => Some(s as u64),
        None => None,
    };
    match (declared_u, staged_u) {
        (Some(d), Some(s)) if d != s => Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::MediaFileMissingOrMismatch,
            format!(
                "declared_byte_size {d} disagrees with staged_byte_size {s} for media `{media_uuid}`"
            ),
            report.clone(),
        )),
        (Some(d), Some(_)) => Ok(Some(d)),
        (Some(d), None) => Ok(Some(d)),
        (None, Some(s)) => Ok(Some(s)),
        (None, None) => Ok(None),
    }
}

fn validate_and_copy_media_file(
    source_path: &Path,
    dest_path: &Path,
    auth: &MediaAuthority,
    family_id: &str,
    media_uuid: &str,
    rel: &Path,
    report: &MigrateReport,
) -> Result<(), MigrateError> {
    let meta = match fs::symlink_metadata(source_path) {
        Ok(m) => m,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::MediaFileMissingOrMismatch,
                format!(
                    "media file missing for family `{family_id}` media `{media_uuid}` at {}",
                    rel.display()
                ),
                report.clone(),
            ));
        }
        Err(error) => return Err(MigrateError::Io(error)),
    };

    if !meta.file_type().is_file() {
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::MediaFileMissingOrMismatch,
            format!(
                "media path is not a regular file for `{media_uuid}` at {}",
                rel.display()
            ),
            report.clone(),
        ));
    }

    let actual_size = meta.len();
    if actual_size == 0 {
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::MediaFileMissingOrMismatch,
            format!("media file empty for `{media_uuid}` at {}", rel.display()),
            report.clone(),
        ));
    }
    if let Some(expected) = auth.expected_size {
        if actual_size != expected {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::MediaFileMissingOrMismatch,
                format!(
                    "media size mismatch for `{media_uuid}`: actual {actual_size} expected {expected}"
                ),
                report.clone(),
            ));
        }
    }

    let bytes = fs::read(source_path)?;
    if let Some(expected_sha) = auth.expected_sha256.as_deref() {
        let actual_sha = hex::encode(Sha256::digest(&bytes));
        if actual_sha != expected_sha {
            return Err(MigrateError::authoritative_failure(
                AuthoritativeFailure::MediaFileMissingOrMismatch,
                format!(
                    "media sha256 mismatch for `{media_uuid}`: actual {actual_sha} expected {expected_sha}"
                ),
                report.clone(),
            ));
        }
    }

    atomic_write_media_file(dest_path, &bytes)?;
    Ok(())
}

/// Write media bytes via sibling temp under the dest family dir, sync, then rename.
/// On error leave no partial final name (match live publish / migrator temp+rename).
fn atomic_write_media_file(dest_path: &Path, bytes: &[u8]) -> Result<(), MigrateError> {
    let parent = dest_path.parent().ok_or_else(|| {
        MigrateError::Internal(format!(
            "media dest path has no parent: {}",
            dest_path.display()
        ))
    })?;
    fs::create_dir_all(parent)?;

    let file_name = dest_path
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("media");
    let temporary = parent.join(format!(".{file_name}.migrate.tmp"));
    let _ = fs::remove_file(&temporary);

    let write = (|| -> Result<(), MigrateError> {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::rename(&temporary, dest_path)?;
        // Durability for the final name + parent directory entry.
        if let Ok(final_file) = fs::File::open(dest_path) {
            let _ = final_file.sync_all();
        }
        Ok(())
    })();
    if write.is_err() {
        let _ = fs::remove_file(&temporary);
        // Never leave a partial final path from a failed mid-write rename race.
        let _ = fs::remove_file(dest_path);
    }
    write
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{normalized_display_name_key, Entity, EntityValidationContext, RawEntity};
    use crate::offline_migrate::test_support::{
        baby_payload_json, open_v3_fixture, TEST_NEW_ROOT_PASSWORD,
    };
    use crate::store::{self, Store, DATABASE_SCHEMA_VERSION};
    use crate::DEFAULT_MAX_MEDIA_BYTES;
    use rusqlite::{params, Connection};
    use serde_json::{json, Map, Value};
    use tempfile::tempdir;

    // Stable fixture IDs (UUID-shaped so path layout matches live server).
    const FAM: &str = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    const MEM_OWNER: &str = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    const BABY: &str = "11111111-1111-4111-8111-111111111111";
    const RECORD: &str = "22222222-2222-4222-8222-222222222222";
    const MEDIA: &str = "33333333-3333-4333-8333-333333333333";
    const BUNDLE: &str = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    fn seed_family(conn: &Connection) {
        conn.execute(
            "INSERT INTO families(id, created_at, create_request_hash, name) VALUES (?1, 100, NULL, '我家')",
            params![FAM],
        )
        .unwrap();
        conn.execute(
            "
            INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
            VALUES (?1, ?2, 'owner', 'dev-old', '爸爸', NULL)
            ",
            params![MEM_OWNER, FAM],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, 3)",
            params![FAM],
        )
        .unwrap();
    }

    fn seed_baby(conn: &Connection) {
        conn.execute(
            "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            ) VALUES (?1, 'baby', ?2, 200, NULL, ?3, 1)
            ",
            params![FAM, BABY, baby_payload_json()],
        )
        .unwrap();
    }

    fn media_bytes() -> &'static [u8] {
        b"fake-jpeg-media-bytes-for-fixture"
    }

    fn media_sha256() -> String {
        hex::encode(Sha256::digest(media_bytes()))
    }

    fn media_payload() -> Map<String, Value> {
        json!({
            "kind": "log",
            "record_client_uuid": RECORD,
            "baby_client_uuid": null,
            "care_plan_client_uuid": null,
            "mime": "image/jpeg",
            "width": 10,
            "height": 10,
            "byte_size": media_bytes().len() as i64,
        })
        .as_object()
        .unwrap()
        .clone()
    }

    fn record_payload() -> Map<String, Value> {
        json!({
            "baby_client_uuid": BABY,
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

    /// Canonical record + one media entity for a committed bundle (authoritative image).
    fn canonical_record_with_media() -> (Entity, Vec<Entity>, String, String, String) {
        let root_raw = RawEntity {
            entity_type: "record".to_owned(),
            client_uuid: Uuid::parse_str(RECORD).unwrap(),
            updated_at: 300,
            deleted_at: None,
            payload: record_payload(),
        };
        let root = root_raw
            .validate_as(
                DEFAULT_MAX_MEDIA_BYTES,
                EntityValidationContext::AtomicBundleRoot,
            )
            .unwrap();
        let media_raw = RawEntity {
            entity_type: "media".to_owned(),
            client_uuid: Uuid::parse_str(MEDIA).unwrap(),
            updated_at: 300,
            deleted_at: None,
            payload: media_payload(),
        };
        let media_entity = media_raw
            .validate_as(
                DEFAULT_MAX_MEDIA_BYTES,
                EntityValidationContext::AtomicBundleMedia,
            )
            .unwrap();
        let media = vec![media_entity];
        let content_hash = store::bundle_content_hash(&root, &media).unwrap();
        let root_payload_json = serde_json::to_string(&root.payload).unwrap();
        let media_json = serde_json::to_string(&media).unwrap();
        (root, media, content_hash, root_payload_json, media_json)
    }

    fn seed_committed_bundle_with_media(conn: &Connection) {
        let (root, media, content_hash, root_payload_json, media_json) =
            canonical_record_with_media();
        let size = media_bytes().len() as i64;
        let sha = media_sha256();
        let media_payload_json = serde_json::to_string(&media[0].payload).unwrap();
        let root_payload_for_entity = serde_json::to_string(&root.payload).unwrap();

        conn.execute(
            "
            INSERT INTO sync_bundles(
                family_id, bundle_id, staged_membership_id, status, root_type,
                root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                media_entities_json, content_hash, created_at, committed_at,
                committed_cursor, committed_applied
            ) VALUES (
                ?1, ?2, ?3, 'committed', 'record',
                ?4, 300, NULL, ?5,
                ?6, ?7, 300, 301,
                3, 1
            )
            ",
            params![
                FAM,
                BUNDLE,
                MEM_OWNER,
                RECORD,
                root_payload_json,
                media_json,
                content_hash
            ],
        )
        .unwrap();
        conn.execute(
            "
            INSERT INTO sync_bundle_media(
                family_id, bundle_id, media_uuid, declared_byte_size,
                staged_byte_size, staged_sha256, staged_at
            ) VALUES (?1, ?2, ?3, ?4, ?4, ?5, 300)
            ",
            params![FAM, BUNDLE, MEDIA, size, sha],
        )
        .unwrap();
        conn.execute(
            "
            INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
            VALUES (?1, ?2, 'bundle', ?3)
            ",
            params![FAM, MEDIA, BUNDLE],
        )
        .unwrap();
        // Applied media entity row (as after live commit).
        conn.execute(
            "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            ) VALUES (?1, 'media', ?2, 300, NULL, ?3, 2)
            ",
            params![FAM, MEDIA, media_payload_json],
        )
        .unwrap();
        // Applied record entity.
        conn.execute(
            "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            ) VALUES (?1, 'record', ?2, 300, NULL, ?3, 3)
            ",
            params![FAM, RECORD, root_payload_for_entity],
        )
        .unwrap();
    }

    fn write_source_media(source_dir: &Path, family: &str, media: &str, bytes: &[u8]) {
        let path =
            source_dir.join(media_file_relative_path(family, media).expect("fixture UUID path"));
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, bytes).unwrap();
    }

    /// Readiness contract aligned with live `media_file_is_ready` + `media_path`:
    /// UUID-canonical path under data/media, regular file, size > 0, matches declared.
    fn assert_media_ready_like_server(
        dest_data_dir: &Path,
        family_id: &str,
        media_uuid: &str,
        declared_size: Option<u64>,
    ) {
        let family = Uuid::parse_str(family_id).expect("family UUID").to_string();
        let media = Uuid::parse_str(media_uuid).expect("media UUID").to_string();
        // Live media_path: media_root.join(family).join(uuid) with media_root = data/media.
        let path = dest_data_dir.join("media").join(&family).join(&media);
        assert_eq!(
            path,
            dest_data_dir.join(media_file_relative_path(family_id, media_uuid).unwrap()),
            "layout must match UUID-canonical media_file_relative_path"
        );
        let meta = fs::symlink_metadata(&path).expect("media file metadata");
        assert!(meta.file_type().is_file(), "must be regular file");
        let actual = meta.len();
        assert!(actual > 0, "media_file_is_ready requires size > 0");
        if let Some(expected) = declared_size {
            assert_eq!(actual, expected, "media_file_is_ready declared size match");
        }
    }

    fn assert_no_copy_back_dest(dest: &Path) {
        assert!(
            !dest.join("lezi.db").exists(),
            "dest lezi.db must be removed"
        );
        assert!(!dest.join("media").exists(), "dest media/ must be removed");
        assert!(
            !dest.join("server.secret").exists(),
            "dest server.secret must be removed on media failure"
        );
    }

    #[test]
    fn media_file_relative_path_matches_inventory_layout() {
        let p = media_file_relative_path(FAM, MEDIA).unwrap();
        assert_eq!(p, PathBuf::from(format!("media/{FAM}/{MEDIA}")));
    }

    #[test]
    fn media_file_relative_path_rejects_path_escape_components() {
        let err = media_file_relative_path("..", MEDIA).expect_err("must reject");
        assert!(
            matches!(err, MigrateError::Internal(_)),
            "expected Internal, got {err}"
        );
        let err = media_file_relative_path(FAM, "../escape").expect_err("must reject");
        assert!(matches!(err, MigrateError::Internal(_)), "{err}");
    }

    #[test]
    fn migrate_data_dir_copies_authority_media_and_resolves_record_with_image() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            seed_committed_bundle_with_media(&conn);
        }
        write_source_media(&source, FAM, MEDIA, media_bytes());

        let report =
            migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate data dir");
        assert_eq!(report.media_files_copied, 1);
        assert_eq!(report.committed_bundles, 1);
        assert_eq!(report.families, 1);

        Store::preflight_existing_schema(&dest.join("lezi.db")).expect("preflight");
        let version: i64 = Connection::open(dest.join("lezi.db"))
            .unwrap()
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, DATABASE_SCHEMA_VERSION);

        // Live media_path + media_file_is_ready contract (UUID parse, size>0, declared match).
        assert_media_ready_like_server(&dest, FAM, MEDIA, Some(media_bytes().len() as u64));
        assert_eq!(
            fs::read(dest.join(media_file_relative_path(FAM, MEDIA).unwrap())).unwrap(),
            media_bytes()
        );

        // Publication + entity reachable for the record-with-image path.
        let conn = Connection::open(dest.join("lezi.db")).unwrap();
        let source: String = conn
            .query_row(
                "SELECT source FROM media_publications WHERE media_uuid = ?1",
                params![MEDIA],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(source, "bundle");
        let media_entity: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM entities WHERE entity_type = 'media' AND client_uuid = ?1",
                params![MEDIA],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(media_entity, 1);
        let record_entity: i64 = conn
            .query_row(
                "SELECT COUNT(*) FROM entities WHERE entity_type = 'record' AND client_uuid = ?1",
                params![RECORD],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(record_entity, 1);
    }

    #[test]
    fn migrate_data_dir_fails_when_authority_media_missing_and_cleans_dest() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();
        fs::create_dir_all(&dest).unwrap();
        fs::write(dest.join("lezi.db"), b"preexisting-sentinel").unwrap();

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            seed_committed_bundle_with_media(&conn);
        }
        // No media/ bytes under source.

        let err =
            migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::MediaFileMissingOrMismatch)
        );
        assert!(err.report().is_some());
        assert_no_copy_back_dest(&dest);
    }

    #[test]
    fn migrate_data_dir_fails_on_size_mismatch() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            seed_committed_bundle_with_media(&conn);
        }
        write_source_media(&source, FAM, MEDIA, b"wrong-len");

        let err =
            migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::MediaFileMissingOrMismatch)
        );
        assert!(
            err.to_string().contains("size mismatch")
                || err.to_string().contains("MediaFileMissingOrMismatch"),
            "{err}"
        );
        assert_no_copy_back_dest(&dest);
    }

    #[test]
    fn migrate_data_dir_fails_on_declared_vs_staged_size_disagreement() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            seed_committed_bundle_with_media(&conn);
            // Corrupt retained row: declared ≠ staged on the same media.
            conn.execute(
                "
                UPDATE sync_bundle_media
                SET declared_byte_size = 1, staged_byte_size = 2
                WHERE media_uuid = ?1
                ",
                params![MEDIA],
            )
            .unwrap();
        }
        write_source_media(&source, FAM, MEDIA, media_bytes());

        let err =
            migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::MediaFileMissingOrMismatch)
        );
        assert!(
            err.to_string().contains("disagrees") || err.to_string().contains("declared_byte_size"),
            "{err}"
        );
        assert_no_copy_back_dest(&dest);
    }

    #[test]
    fn migrate_data_dir_fails_on_negative_bundle_media_size() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            seed_committed_bundle_with_media(&conn);
            conn.execute(
                "
                UPDATE sync_bundle_media
                SET declared_byte_size = -1, staged_byte_size = NULL
                WHERE media_uuid = ?1
                ",
                params![MEDIA],
            )
            .unwrap();
        }
        write_source_media(&source, FAM, MEDIA, media_bytes());

        let err =
            migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::MediaFileMissingOrMismatch)
        );
        assert!(
            err.to_string().contains("negative declared_byte_size"),
            "{err}"
        );
        assert_no_copy_back_dest(&dest);
    }

    #[test]
    fn migrate_data_dir_fails_on_sha256_mismatch() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            seed_committed_bundle_with_media(&conn);
        }
        // Same length as fixture, different bytes → size ok, hash fails.
        let mut wrong = media_bytes().to_vec();
        wrong[0] ^= 0xff;
        assert_eq!(wrong.len(), media_bytes().len());
        write_source_media(&source, FAM, MEDIA, &wrong);

        let err =
            migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect_err("must fail");
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::MediaFileMissingOrMismatch)
        );
        assert!(err.to_string().contains("sha256"), "{err}");
        assert_no_copy_back_dest(&dest);
    }

    #[test]
    fn migrate_data_dir_ignores_staging_only_orphan_bytes() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();

        let staging_media = "44444444-4444-4444-8444-444444444444";
        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            seed_committed_bundle_with_media(&conn);
            // Staging bundle + media (discarded by cascade) — file present but not authority.
            let (_r, _m, _h, root_payload_json, _) = canonical_record_with_media();
            conn.execute(
                "
                INSERT INTO sync_bundles(
                    family_id, bundle_id, staged_membership_id, status, root_type,
                    root_client_uuid, root_updated_at, root_deleted_at, root_payload_json,
                    media_entities_json, content_hash, created_at, committed_at,
                    committed_cursor, committed_applied
                ) VALUES (
                    ?1, 'stage-b', ?2, 'staging', 'record',
                    ?3, 400, NULL, ?4,
                    '[]', 'deadbeef', 400, NULL, NULL, NULL
                )
                ",
                params![FAM, MEM_OWNER, RECORD, root_payload_json],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO sync_bundle_media(
                    family_id, bundle_id, media_uuid, declared_byte_size,
                    staged_byte_size, staged_sha256, staged_at
                ) VALUES (?1, 'stage-b', ?2, 4, NULL, NULL, NULL)
                ",
                params![FAM, staging_media],
            )
            .unwrap();
            conn.execute(
                "
                INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
                VALUES (?1, ?2, 'bundle_pending', 'stage-b')
                ",
                params![FAM, staging_media],
            )
            .unwrap();
        }
        write_source_media(&source, FAM, MEDIA, media_bytes());
        write_source_media(&source, FAM, staging_media, b"orph");

        let report = migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        assert_eq!(report.media_files_copied, 1);
        assert_eq!(report.discarded_staging_bundles, 1);
        assert!(dest
            .join(media_file_relative_path(FAM, MEDIA).unwrap())
            .is_file());
        assert!(
            !dest
                .join(media_file_relative_path(FAM, staging_media).unwrap())
                .exists(),
            "staging-only orphan must not be copied"
        );
    }

    #[test]
    fn migrate_data_dir_ordinary_publication_without_bundle_media() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();
        let ordinary = "55555555-5555-4555-8555-555555555555";
        let bytes = b"ordinary-avatar-bytes";

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
            conn.execute(
                "
                INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
                VALUES (?1, ?2, 'ordinary', NULL)
                ",
                params![FAM, ordinary],
            )
            .unwrap();
            // Media entity with matching size (no staged_sha256).
            let payload = json!({
                "kind": "avatar",
                "record_client_uuid": null,
                "baby_client_uuid": BABY,
                "care_plan_client_uuid": null,
                "mime": "image/png",
                "width": 1,
                "height": 1,
                "byte_size": bytes.len() as i64,
            });
            conn.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES (?1, 'media', ?2, 50, NULL, ?3, 1)
                ",
                params![FAM, ordinary, payload.to_string()],
            )
            .unwrap();
        }
        write_source_media(&source, FAM, ordinary, bytes);

        let report = migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        assert_eq!(report.media_files_copied, 1);
        assert_media_ready_like_server(&dest, FAM, ordinary, Some(bytes.len() as u64));
        assert_eq!(
            fs::read(dest.join(media_file_relative_path(FAM, ordinary).unwrap())).unwrap(),
            bytes
        );
    }

    #[test]
    fn migrate_data_dir_wipes_stale_dest_media_when_authority_empty() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();
        fs::create_dir_all(&dest).unwrap();
        // Pre-existing non-authority media under reused out/.
        let stale = dest
            .join("media")
            .join(FAM)
            .join("99999999-9999-4999-8999-999999999999");
        fs::create_dir_all(stale.parent().unwrap()).unwrap();
        fs::write(&stale, b"stale-leftover").unwrap();

        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
        }
        let report = migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        assert_eq!(report.media_files_copied, 0);
        assert!(dest.join("lezi.db").is_file());
        assert!(
            !dest.join("media").exists(),
            "empty authority must leave no dest/media (including wiped stale)"
        );
    }

    #[test]
    fn migrate_data_dir_without_media_requirement_skips_media_tree() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("backup");
        let dest = dir.path().join("out");
        fs::create_dir_all(&source).unwrap();
        {
            let db = source.join("lezi.db");
            let conn = open_v3_fixture(&db);
            seed_family(&conn);
            seed_baby(&conn);
        }
        let report = migrate_v3_data_dir(&source, &dest, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        assert_eq!(report.media_files_copied, 0);
        assert!(dest.join("lezi.db").is_file());
        assert!(!dest.join("media").exists());
        // display_name_key still derived for owner
        let key: String = Connection::open(dest.join("lezi.db"))
            .unwrap()
            .query_row(
                "SELECT display_name_key FROM memberships WHERE membership_id = ?1",
                params![MEM_OWNER],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(key, normalized_display_name_key("爸爸"));
    }
}
