//! Offline v11 → frozen legacy schema-12 copy-out migrator.
//!
//! Preserves identity shells (devices/sessions/credentials material in DB),
//! family facts, and server.secret. Mints causal base versions and converts
//! historical closed Sleep into WakeObservation. Never mutates the source.

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use rusqlite::Connection;

use super::causal::{finalize_causal_v12, validate_causal_integrity_with_media};
use super::inventory::AuthoritativeFailure;
use super::migrator::{remove_db_files, MigrateError, MigrateReport};
use super::schema_contract::LEGACY_SCHEMA_V12;

/// Source schema version for the causal cutover path.
pub(crate) const SOURCE_V11_USER_VERSION: i64 = 11;

/// Frozen v11 schema (pre-causal) — exact shape of DATABASE_SCHEMA_VERSION=11.
pub(crate) const SOURCE_V11_SCHEMA_SQL: &str = include_str!("source_v11_schema.sql");

/// Copy-out migrate a v11 `lezi.db` into a frozen legacy schema-12 dest DB.
///
/// - Source opened read-only; never mutated.
/// - Dest must not already exist (fail closed non-empty target).
/// - Dest written via temp+rename; failures leave pre-existing dest untouched.
/// - Does **not** regenerate `server.secret` (caller copies data-dir secret).
/// - `media_root`: optional data-dir root with `media/{family}/{uuid}` for sha256
///   and wake media file copies during finalize.
pub(crate) fn migrate_v11_database(
    source_db: &Path,
    dest_db: &Path,
    media_root: Option<&Path>,
) -> Result<MigrateReport, MigrateError> {
    if !source_db.try_exists()? {
        return Err(MigrateError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            format!("source database missing: {}", source_db.display()),
        )));
    }

    // Validate source first so wrong user_version is reported accurately even
    // when dest already exists (ops diagnostics / fail-closed preserve dest).
    let source = Connection::open_with_flags(
        source_db,
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    validate_source_v11(&source)?;

    if dest_db.try_exists()? {
        return Err(MigrateError::Internal(format!(
            "dest lezi.db already exists (non-empty target refused): {}",
            dest_db.display()
        )));
    }

    let parent = dest_db.parent().unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(parent)?;
    let temp = temp_dest_path(dest_db);
    remove_db_files(&temp);

    let result = (|| {
        let mut dest = Connection::open(&temp)?;
        dest.execute_batch(
            "
            PRAGMA foreign_keys = ON;
            PRAGMA journal_mode = DELETE;
            ",
        )?;
        LEGACY_SCHEMA_V12
            .initialize(&dest)
            .map_err(|error| MigrateError::Internal(error.to_string()))?;

        let mut report = MigrateReport::default();
        {
            let tx = dest.transaction()?;
            copy_v11_all(&source, &tx, &mut report)?;
            finalize_causal_v12(&tx, media_root, &mut report)?;
            tx.commit()?;
        }
        dest.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")?;
        validate_causal_integrity_with_media(&dest, media_root).map_err(|detail| {
            MigrateError::authoritative_failure(
                AuthoritativeFailure::CausalIntegrityFailed,
                format!("causal integrity after migrate: {detail}"),
                report.clone(),
            )
        })?;
        drop(dest);
        Ok::<_, MigrateError>(report)
    })();

    match result {
        Ok(report) => {
            if let Err(error) = fs::rename(&temp, dest_db) {
                remove_db_files(&temp);
                return Err(MigrateError::Io(error));
            }
            for suffix in ["-wal", "-shm"] {
                let _ = fs::remove_file(PathBuf::from(format!("{}{suffix}", dest_db.display())));
            }
            Ok(report)
        }
        Err(error) => {
            remove_db_files(&temp);
            Err(error)
        }
    }
}

/// Full data-dir migrate: v11 `lezi.db` + media + server.secret → frozen schema-12 out/.
///
/// Copies authority media bytes **before** causal finalize so wake media UUIDs
/// derive from on-disk sha256 and legacy→wake hard copies succeed.
pub(crate) fn migrate_v11_data_dir(
    source_data_dir: &Path,
    dest_data_dir: &Path,
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

    if dest_db.try_exists()? {
        return Err(MigrateError::Internal(format!(
            "dest lezi.db already exists: {}",
            dest_db.display()
        )));
    }

    // Stage legacy media under dest so finalize can hash and mint wake copies.
    let mut report = MigrateReport::default();
    let src_media = source_data_dir.join("media");
    if src_media.is_dir() {
        if let Err(error) =
            copy_dir_recursive(&src_media, &dest_data_dir.join("media"), &mut report)
        {
            super::media::cleanup_migrator_data_dir_outputs(dest_data_dir);
            return Err(error);
        }
    }

    let mut migrate_report = match migrate_v11_database(&source_db, &dest_db, Some(dest_data_dir)) {
        Ok(r) => r,
        Err(error) => {
            super::media::cleanup_migrator_data_dir_outputs(dest_data_dir);
            return Err(error);
        }
    };
    migrate_report.media_files_copied += report.media_files_copied;

    // Copy server.secret byte-for-byte (identity continuity for v11→v12).
    let src_secret = source_data_dir.join("server.secret");
    if src_secret.is_file() {
        let dest_secret = dest_data_dir.join("server.secret");
        if let Err(error) = fs::copy(&src_secret, &dest_secret) {
            super::media::cleanup_migrator_data_dir_outputs(dest_data_dir);
            return Err(MigrateError::Io(error));
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mut perms = fs::metadata(&dest_secret)?.permissions();
            perms.set_mode(0o600);
            fs::set_permissions(&dest_secret, perms)?;
        }
    }

    let dest = Connection::open(&dest_db)?;
    if let Err(detail) = validate_causal_integrity_with_media(&dest, Some(dest_data_dir)) {
        super::media::cleanup_migrator_data_dir_outputs(dest_data_dir);
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::CausalIntegrityFailed,
            format!("causal integrity after media copy: {detail}"),
            migrate_report,
        ));
    }
    Ok(migrate_report)
}

fn copy_dir_recursive(
    from: &Path,
    to: &Path,
    report: &mut MigrateReport,
) -> Result<(), MigrateError> {
    fs::create_dir_all(to)?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        let ft = entry.file_type()?;
        let dest_path = to.join(entry.file_name());
        if ft.is_dir() {
            copy_dir_recursive(&entry.path(), &dest_path, report)?;
        } else if ft.is_file() && !dest_path.exists() {
            fs::copy(entry.path(), &dest_path)?;
            report.media_files_copied += 1;
        }
    }
    Ok(())
}

fn temp_dest_path(dest_db: &Path) -> PathBuf {
    let parent = dest_db.parent().unwrap_or_else(|| Path::new("."));
    let name = dest_db
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("lezi.db");
    parent.join(format!(".{name}.v11-migrating"))
}

fn validate_source_v11(source: &Connection) -> Result<(), MigrateError> {
    let empty = MigrateReport::default();
    let version: i64 = source.query_row("PRAGMA user_version", [], |row| row.get(0))?;
    if version != SOURCE_V11_USER_VERSION {
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::SourceUserVersionNotEleven,
            format!("found user_version={version}, expected {SOURCE_V11_USER_VERSION} for v11→v12"),
            empty,
        ));
    }

    // Full normalized object equality (tables + indexes), same style as Store preflight.
    let expected = expected_v11_schema_objects()?;
    let found = normalized_schema_objects(source)?;
    if found != expected {
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::SourceShapeMismatch,
            format!(
                "v11 schema object mismatch (tables/indexes/sql): found {} objects, expected {}",
                found.len(),
                expected.len()
            ),
            empty,
        ));
    }
    Ok(())
}

fn normalize_schema_sql(sql: &str) -> String {
    sql.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn normalized_schema_objects(
    connection: &Connection,
) -> Result<BTreeMap<(String, String), String>, MigrateError> {
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
        .map_err(MigrateError::from)
}

fn expected_v11_schema_objects() -> Result<BTreeMap<(String, String), String>, MigrateError> {
    let conn = Connection::open_in_memory()?;
    conn.execute_batch(SOURCE_V11_SCHEMA_SQL)?;
    normalized_schema_objects(&conn)
}

fn copy_v11_all(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    report: &mut MigrateReport,
) -> Result<(), MigrateError> {
    // Table order respects FKs.
    copy_table(
        source,
        dest,
        "families",
        &[
            "id",
            "created_at",
            "create_request_hash",
            "name",
            "owner_root_fingerprint",
        ],
        &mut report.families,
    )?;
    copy_table(
        source,
        dest,
        "memberships",
        &[
            "membership_id",
            "family_id",
            "role",
            "display_name",
            "display_name_key",
            "left_at",
        ],
        &mut report.memberships,
    )?;
    copy_table(
        source,
        dest,
        "devices",
        &[
            "device_id",
            "membership_id",
            "device_name",
            "device_name_key",
            "status",
            "created_at",
            "last_used_at",
        ],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "device_sessions",
        &[
            "session_id",
            "device_id",
            "access_token_hash",
            "access_expires_at",
            "refresh_token_hash",
            "refresh_generation",
            "revoked_at",
            "revoked_reason",
        ],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "refresh_token_history",
        &["token_hash", "session_id", "used_at"],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "terminal_credential_denials",
        &["token_hash", "token_kind", "reason"],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "owner_login_requests",
        &["request_hash", "device_id", "device_name", "takeover"],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "member_login_requests",
        &[
            "request_id",
            "family_id",
            "pending_secret_hash",
            "display_name",
            "display_name_key",
            "device_name",
            "status",
            "created_at",
            "expires_at",
            "decided_at",
            "claimed_at",
            "approval_kind",
            "membership_id",
            "device_id",
        ],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "member_login_grants",
        &[
            "grant_hash",
            "family_id",
            "membership_id",
            "created_at",
            "expires_at",
            "used_at",
            "claimed_device_id",
        ],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "member_rename_requests",
        &[
            "request_id",
            "family_id",
            "membership_id",
            "requested_display_name",
            "requested_display_name_key",
            "status",
            "created_at",
            "expires_at",
            "decided_at",
        ],
        &mut 0,
    )?;
    copy_table(source, dest, "family_meta", &["family_id", "rev"], &mut 0)?;
    copy_table(
        source,
        dest,
        "entities",
        &[
            "family_id",
            "entity_type",
            "client_uuid",
            "updated_at",
            "deleted_at",
            "payload_json",
            "rev",
        ],
        &mut report.entities,
    )?;
    {
        let mut total = 0u64;
        copy_table(
            source,
            dest,
            "sync_bundles",
            &[
                "family_id",
                "bundle_id",
                "staged_membership_id",
                "status",
                "root_type",
                "root_client_uuid",
                "root_updated_at",
                "root_deleted_at",
                "root_payload_json",
                "media_entities_json",
                "content_hash",
                "created_at",
                "committed_at",
                "committed_cursor",
                "committed_applied",
            ],
            &mut total,
        )?;
        // Count committed only (v3 report semantics); staging still copied as-is.
        let committed: i64 = dest.query_row(
            "SELECT COUNT(*) FROM sync_bundles WHERE status = 'committed'",
            [],
            |r| r.get(0),
        )?;
        let staging: i64 = dest.query_row(
            "SELECT COUNT(*) FROM sync_bundles WHERE status = 'staging'",
            [],
            |r| r.get(0),
        )?;
        report.committed_bundles = committed as u64;
        report.discarded_staging_bundles = 0; // v11 preserves staging rows; not discarded
        let _ = (total, staging);
    }
    copy_table(
        source,
        dest,
        "sync_bundle_media",
        &[
            "family_id",
            "bundle_id",
            "media_uuid",
            "declared_byte_size",
            "staged_byte_size",
            "staged_sha256",
            "staged_at",
        ],
        &mut 0,
    )?;
    copy_table(
        source,
        dest,
        "media_publications",
        &["family_id", "media_uuid", "source", "bundle_id"],
        &mut 0,
    )?;
    Ok(())
}

fn copy_table(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    table: &str,
    columns: &[&str],
    counter: &mut u64,
) -> Result<(), MigrateError> {
    let col_list = columns.join(", ");
    let placeholders = (1..=columns.len())
        .map(|i| format!("?{i}"))
        .collect::<Vec<_>>()
        .join(", ");
    let select_sql = format!("SELECT {col_list} FROM {table}");
    let insert_sql = format!("INSERT INTO {table} ({col_list}) VALUES ({placeholders})");

    let mut select = source.prepare(&select_sql)?;
    let col_count = columns.len();
    let mut rows = select.query([])?;
    while let Some(row) = rows.next()? {
        let mut values: Vec<rusqlite::types::Value> = Vec::with_capacity(col_count);
        for i in 0..col_count {
            values.push(row.get(i)?);
        }
        dest.execute(&insert_sql, rusqlite::params_from_iter(values.iter()))?;
        *counter += 1;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::offline_migrate::causal::{
        migration_base_version_id, validate_causal_integrity, validate_causal_integrity_with_media,
        wake_observation_client_uuid,
    };
    use crate::offline_migrate::schema_contract::LEGACY_SCHEMA_V12;
    use rusqlite::{params, Connection};
    use serde_json::json;
    use std::fs;
    use tempfile::tempdir;

    fn seed_v11_db(path: &Path) {
        let conn = Connection::open(path).unwrap();
        conn.execute_batch(SOURCE_V11_SCHEMA_SQL).unwrap();
        conn.pragma_update(None, "user_version", SOURCE_V11_USER_VERSION)
            .unwrap();
        conn.execute_batch(
            "
            INSERT INTO families(id, created_at, name, owner_root_fingerprint)
            VALUES ('11111111-1111-1111-1111-111111111111', 1, '家', 'fp');
            INSERT INTO memberships(
                membership_id, family_id, role, display_name, display_name_key, left_at
            ) VALUES (
                '22222222-2222-2222-2222-222222222222',
                '11111111-1111-1111-1111-111111111111',
                'owner', '妈妈', '妈妈', NULL
            );
            INSERT INTO family_meta(family_id, rev) VALUES ('11111111-1111-1111-1111-111111111111', 10);
            ",
        )
        .unwrap();
    }

    fn insert_entity(
        conn: &Connection,
        entity_type: &str,
        uuid: &str,
        updated_at: i64,
        deleted_at: Option<i64>,
        payload: serde_json::Value,
        rev: i64,
    ) {
        conn.execute(
            "
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
            ",
            params![
                "11111111-1111-1111-1111-111111111111",
                entity_type,
                uuid,
                updated_at,
                deleted_at,
                payload.to_string(),
                rev
            ],
        )
        .unwrap();
    }

    #[test]
    fn migrate_v11_mints_base_versions_into_frozen_schema12() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("out").join("lezi.db");
        fs::create_dir_all(dest.parent().unwrap()).unwrap();
        seed_v11_db(&source);
        let conn = Connection::open(&source).unwrap();
        insert_entity(
            &conn,
            "baby",
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            100,
            None,
            json!({
                "name": "宝",
                "sex": "unknown",
                "birth_date": "2024-01-01",
                "birth_weight_grams": null,
                "avatar_media_uuid": null
            }),
            1,
        );
        drop(conn);
        let source_bytes = fs::read(&source).unwrap();

        let report = migrate_v11_database(&source, &dest, None).expect("migrate");
        assert_eq!(report.families, 1);
        assert_eq!(report.base_versions, 1);
        assert_eq!(
            Connection::open(&dest)
                .unwrap()
                .query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            LEGACY_SCHEMA_V12.user_version()
        );
        // Source untouched.
        assert_eq!(
            Connection::open(&source)
                .unwrap()
                .query_row("PRAGMA user_version", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            SOURCE_V11_USER_VERSION
        );
        assert_eq!(source_bytes, fs::read(&source).unwrap());
        LEGACY_SCHEMA_V12.validate_path(&dest).unwrap();
        let dest_conn = Connection::open(&dest).unwrap();
        validate_causal_integrity(&dest_conn).unwrap();
        let heads: i64 = dest_conn
            .query_row("SELECT COUNT(*) FROM entity_stable_heads", [], |r| r.get(0))
            .unwrap();
        assert_eq!(heads, 1);
    }

    #[test]
    fn migrate_v11_closed_sleep_creates_deterministic_wake_observation() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        seed_v11_db(&source);
        let sleep_uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        let updated_at = 1_700_000_000_000i64;
        let conn = Connection::open(&source).unwrap();
        insert_entity(
            &conn,
            "record",
            sleep_uuid,
            updated_at,
            None,
            json!({
                "baby_client_uuid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "type": "sleep",
                "custom_item_client_uuid": null,
                "timestamp": 1_700_000_000_000i64 - 3_600_000,
                "end_timestamp": 1_700_000_000_000i64,
                "note": "醒了",
                "payload_json": {"is_nap": false, "anomaly_flag": false},
                "schema_version": 2,
                "created_by_membership_id": "22222222-2222-2222-2222-222222222222"
            }),
            5,
        );
        // Also a live baby so references are plausible (not FK-enforced).
        insert_entity(
            &conn,
            "baby",
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            50,
            None,
            json!({
                "name": "宝",
                "sex": "unknown",
                "birth_date": "2024-01-01",
                "birth_weight_grams": null,
                "avatar_media_uuid": null
            }),
            1,
        );
        drop(conn);

        let report = migrate_v11_database(&source, &dest, None).expect("migrate");
        assert_eq!(report.wake_observations, 1);
        let expected_wake = wake_observation_client_uuid(sleep_uuid, updated_at).to_string();
        let dest_conn = Connection::open(&dest).unwrap();
        let wake_payload: String = dest_conn
            .query_row(
                "
                SELECT payload_json FROM entities
                WHERE entity_type = 'wake_observation' AND client_uuid = ?1
                ",
                params![expected_wake],
                |r| r.get(0),
            )
            .unwrap();
        let wake: serde_json::Value = serde_json::from_str(&wake_payload).unwrap();
        assert_eq!(wake["wake_timestamp"], 1_700_000_000_000i64);
        assert_eq!(wake["note"], "醒了");
        assert_eq!(
            wake["observer_membership_id"],
            "22222222-2222-2222-2222-222222222222"
        );
        assert_eq!(wake["withdrawn"], false);

        let sleep_payload: String = dest_conn
            .query_row(
                "
                SELECT payload_json FROM entities
                WHERE entity_type = 'record' AND client_uuid = ?1
                ",
                params![sleep_uuid],
                |r| r.get(0),
            )
            .unwrap();
        let sleep: serde_json::Value = serde_json::from_str(&sleep_payload).unwrap();
        assert!(sleep.get("end_timestamp").is_none());
        assert_eq!(
            sleep["effective_wake_observation_client_uuid"],
            expected_wake
        );
        validate_causal_integrity(&dest_conn).unwrap();
    }

    #[test]
    fn migrate_v11_open_sleep_has_no_wake_observation() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        seed_v11_db(&source);
        let conn = Connection::open(&source).unwrap();
        insert_entity(
            &conn,
            "record",
            "cccccccc-cccc-cccc-cccc-cccccccccccc",
            100,
            None,
            json!({
                "baby_client_uuid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "type": "sleep",
                "custom_item_client_uuid": null,
                "timestamp": 100,
                "end_timestamp": null,
                "note": null,
                "payload_json": {"is_nap": true, "anomaly_flag": false},
                "schema_version": 2,
                "created_by_membership_id": "22222222-2222-2222-2222-222222222222"
            }),
            2,
        );
        drop(conn);
        let report = migrate_v11_database(&source, &dest, None).unwrap();
        assert_eq!(report.wake_observations, 0);
        let dest_conn = Connection::open(&dest).unwrap();
        let n: i64 = dest_conn
            .query_row(
                "SELECT COUNT(*) FROM entities WHERE entity_type = 'wake_observation'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 0);
        let sleep_payload: String = dest_conn
            .query_row(
                "
                SELECT payload_json FROM entities
                WHERE client_uuid = 'cccccccc-cccc-cccc-cccc-cccccccccccc'
                ",
                [],
                |r| r.get(0),
            )
            .unwrap();
        let sleep: serde_json::Value = serde_json::from_str(&sleep_payload).unwrap();
        assert!(sleep.get("end_timestamp").is_none());
        assert!(sleep["effective_wake_observation_client_uuid"].is_null());
    }

    #[test]
    fn migrate_v11_preserves_record_tombstone_without_restore_branch() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        seed_v11_db(&source);
        let conn = Connection::open(&source).unwrap();
        insert_entity(
            &conn,
            "record",
            "dddddddd-dddd-dddd-dddd-dddddddddddd",
            200,
            Some(200),
            json!({
                "baby_client_uuid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "type": "formula",
                "custom_item_client_uuid": null,
                "timestamp": 150,
                "end_timestamp": null,
                "note": null,
                "payload_json": {"amount_ml": 30},
                "schema_version": 2,
                "created_by_membership_id": "22222222-2222-2222-2222-222222222222"
            }),
            3,
        );
        drop(conn);
        migrate_v11_database(&source, &dest, None).unwrap();
        let dest_conn = Connection::open(&dest).unwrap();
        let deleted: Option<i64> = dest_conn
            .query_row(
                "
                SELECT deleted_at FROM entities
                WHERE client_uuid = 'dddddddd-dddd-dddd-dddd-dddddddddddd'
                ",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(deleted, Some(200));
        let branches: i64 = dest_conn
            .query_row("SELECT COUNT(*) FROM conflict_branches", [], |r| r.get(0))
            .unwrap();
        assert_eq!(branches, 0);
        let conflicts: i64 = dest_conn
            .query_row("SELECT COUNT(*) FROM conflicts", [], |r| r.get(0))
            .unwrap();
        assert_eq!(conflicts, 0);
    }

    #[test]
    fn migrate_v11_wrong_user_version_leaves_dest_absent() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        let conn = Connection::open(&source).unwrap();
        conn.execute_batch(SOURCE_V11_SCHEMA_SQL).unwrap();
        conn.pragma_update(None, "user_version", 10i64).unwrap();
        drop(conn);
        let err = migrate_v11_database(&source, &dest, None).unwrap_err();
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::SourceUserVersionNotEleven)
        );
        assert!(!dest.exists());
    }

    #[test]
    fn migrate_v11_data_dir_closed_sleep_with_ordinary_photo() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        let out = dir.path().join("out");
        fs::create_dir_all(&backup).unwrap();
        let source = backup.join("lezi.db");
        seed_v11_db(&source);
        let family = "11111111-1111-1111-1111-111111111111";
        let sleep_uuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        let media_uuid = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        let updated_at = 1_700_000_000_000i64;
        let bytes = b"fake-jpeg-bytes-for-hash";
        let media_dir = backup.join("media").join(family);
        fs::create_dir_all(&media_dir).unwrap();
        fs::write(media_dir.join(media_uuid), bytes).unwrap();
        fs::write(backup.join("server.secret"), vec![7u8; 32]).unwrap();

        let conn = Connection::open(&source).unwrap();
        insert_entity(
            &conn,
            "baby",
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            50,
            None,
            json!({
                "name": "宝",
                "sex": "unknown",
                "birth_date": "2024-01-01",
                "birth_weight_grams": null,
                "avatar_media_uuid": null
            }),
            1,
        );
        insert_entity(
            &conn,
            "record",
            sleep_uuid,
            updated_at,
            None,
            json!({
                "baby_client_uuid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "type": "sleep",
                "custom_item_client_uuid": null,
                "timestamp": updated_at - 3_600_000,
                "end_timestamp": updated_at,
                "note": "photo wake",
                "payload_json": {"is_nap": false, "anomaly_flag": false},
                "schema_version": 2,
                "created_by_membership_id": "22222222-2222-2222-2222-222222222222"
            }),
            5,
        );
        insert_entity(
            &conn,
            "media",
            media_uuid,
            updated_at,
            None,
            json!({
                "kind": "log",
                "record_client_uuid": sleep_uuid,
                "baby_client_uuid": null,
                "care_plan_client_uuid": null,
                "mime": "image/jpeg",
                "width": 1,
                "height": 1,
                "byte_size": bytes.len()
            }),
            6,
        );
        conn.execute(
            "
            INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
            VALUES (?1, ?2, 'ordinary', NULL)
            ",
            params![family, media_uuid],
        )
        .unwrap();
        drop(conn);

        let report = migrate_v11_data_dir(&backup, &out).expect("data dir migrate");
        assert_eq!(report.wake_observations, 1);
        let dest = Connection::open(out.join("lezi.db")).unwrap();
        // Legacy log media is tombstoned (transfer, not dual live).
        let legacy_deleted: Option<i64> = dest
            .query_row(
                "SELECT deleted_at FROM entities WHERE client_uuid = ?1",
                params![media_uuid],
                |r| r.get(0),
            )
            .unwrap();
        assert!(legacy_deleted.is_some());
        // Wake media live with deterministic UUID.
        use sha2::{Digest, Sha256};
        let sha = hex::encode(Sha256::digest(bytes));
        let wake_media =
            crate::offline_migrate::causal::wake_media_uuid(sleep_uuid, media_uuid, &sha)
                .to_string();
        let wake_deleted: Option<i64> = dest
            .query_row(
                "SELECT deleted_at FROM entities WHERE client_uuid = ?1",
                params![wake_media],
                |r| r.get(0),
            )
            .unwrap();
        assert!(wake_deleted.is_none());
        assert!(out.join("media").join(family).join(&wake_media).is_file());
        validate_causal_integrity_with_media(&dest, Some(out.as_path())).unwrap();
    }

    #[test]
    fn migrate_v11_refuses_preexisting_dest_without_mutation() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("source.db");
        let dest = dir.path().join("lezi.db");
        seed_v11_db(&source);
        fs::write(&dest, b"keep-me").unwrap();
        let err = migrate_v11_database(&source, &dest, None).unwrap_err();
        assert!(matches!(err, MigrateError::Internal(_)), "{err:?}");
        assert_eq!(fs::read(&dest).unwrap(), b"keep-me");
    }

    #[test]
    fn migrate_v11_wrong_version_preserves_preexisting_dest_bytes() {
        let dir = tempdir().unwrap();
        let source = dir.path().join("bad.db");
        let dest = dir.path().join("lezi.db");
        let conn = Connection::open(&source).unwrap();
        conn.execute_batch(SOURCE_V11_SCHEMA_SQL).unwrap();
        conn.pragma_update(None, "user_version", 9i64).unwrap();
        drop(conn);
        fs::write(&dest, b"keep-me").unwrap();
        let err = migrate_v11_database(&source, &dest, None).unwrap_err();
        assert_eq!(
            err.authoritative(),
            Some(AuthoritativeFailure::SourceUserVersionNotEleven)
        );
        assert_eq!(fs::read(&dest).unwrap(), b"keep-me");
    }

    #[test]
    fn base_version_id_function_is_public_seam() {
        let id = migration_base_version_id("f", "baby", "u", 1, "h");
        assert_eq!(id.get_version(), Some(uuid::Version::Sha1));
    }
}
