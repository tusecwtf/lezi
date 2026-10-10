//! Explicit copy-out migration from frozen server schema 11/12 to schema 13.
//!
//! This module deliberately rebuilds a new database from the frozen source
//! contract. It never runs `ALTER` against the source and is not reachable from
//! [`crate::store::Store`] startup.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use rusqlite::{params_from_iter, types::Value, Connection};
use sha2::{Digest, Sha256};

use super::immutable::open_immutable;
use super::migrator::{MigrateError, MigrateReport};
use super::private_output::{
    copy_private, disjoint_paths, private_directories, private_file, publish_directory,
    OutputLease, PrivateDirectory,
};
use super::schema_contract::LEGACY_SCHEMA_V12;
use super::v11::{migrate_v11_data_dir, SOURCE_V11_USER_VERSION};
use crate::store::{Store, CURRENT_SCHEMA_SQL, DATABASE_SCHEMA_VERSION};
use crate::SERVER_SECRET_BYTES;

const SOURCE_V12_USER_VERSION: i64 = 12;
const DATA_ROOT_ENTRIES: &[&str] = &[
    "app-release.apk",
    "app-update.json",
    "lezi.db",
    "media",
    "server.secret",
    "tls",
];

/// Auto-detect a frozen v11/v12 source and rebuild it as schema 13.
pub(crate) fn migrate_v11_or_v12_data_dir_to_v13(
    source_data_dir: &Path,
    dest_data_dir: &Path,
) -> Result<MigrateReport, MigrateError> {
    let (source_path, dest_path) = disjoint_paths(source_data_dir, dest_data_dir)?;
    let source_data_dir = source_path.as_path();
    let dest_data_dir = dest_path.as_path();
    validate_data_root(source_data_dir)?;
    let source_digest = tree_digest(source_data_dir)?;
    let source_version = read_user_version(&source_data_dir.join("lezi.db"))?;
    let report = match source_version {
        SOURCE_V12_USER_VERSION => migrate_v12_data_dir_to_v13(source_data_dir, dest_data_dir),
        SOURCE_V11_USER_VERSION => {
            let owned_stage =
                PrivateDirectory::new(dest_data_dir.parent().unwrap(), "schema12-stage")?;
            let stage = owned_stage.path();
            migrate_v11_data_dir(source_data_dir, stage)?;
            copy_tree_exact(&source_data_dir.join("tls"), &stage.join("tls"))?;
            migrate_v12_data_dir_to_v13(stage, dest_data_dir)
        }
        other => Err(MigrateError::Internal(format!(
            "unsupported schema-13 source user_version={other}; expected 11 or 12"
        ))),
    }?;
    if source_digest != tree_digest(source_data_dir)? {
        return Err(MigrateError::Internal(
            "source data root changed while offline migration was running".to_owned(),
        ));
    }
    Ok(report)
}

fn read_user_version(database: &Path) -> Result<i64, MigrateError> {
    let connection = open_immutable(database)?;
    connection
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .map_err(MigrateError::from)
}

/// Rebuild a frozen schema-12 data root into a new schema-13 data root.
pub(crate) fn migrate_v12_data_dir_to_v13(
    source_data_dir: &Path,
    dest_data_dir: &Path,
) -> Result<MigrateReport, MigrateError> {
    let (source_path, dest_path) = disjoint_paths(source_data_dir, dest_data_dir)?;
    let source_data_dir = source_path.as_path();
    let dest_data_dir = dest_path.as_path();
    validate_source_root(source_data_dir)?;
    let _lease = OutputLease::acquire(dest_data_dir)?;
    if dest_data_dir.try_exists()? && fs::read_dir(dest_data_dir)?.next().is_some() {
        return Err(MigrateError::Internal(format!(
            "schema-13 destination must be absent or empty: {}",
            dest_data_dir.display()
        )));
    }

    let source_digest = tree_digest(source_data_dir)?;
    let owned_temp = PrivateDirectory::new(dest_data_dir.parent().unwrap(), "schema13-migrating")?;
    let temp = owned_temp.path();
    let result = (|| {
        rebuild_v12_database(&source_data_dir.join("lezi.db"), &temp.join("lezi.db"))?;
        copy_required_file(
            &source_data_dir.join("server.secret"),
            &temp.join("server.secret"),
        )?;
        copy_tree_exact(&source_data_dir.join("media"), &temp.join("media"))?;
        copy_tree_exact(&source_data_dir.join("tls"), &temp.join("tls"))?;
        validate_schema13_data_dir(temp)?;
        if source_digest != tree_digest(source_data_dir)? {
            return Err(MigrateError::Internal(
                "source data root changed while offline migration was running".to_owned(),
            ));
        }
        validate_copied_assets(source_data_dir, temp)?;
        migration_report(&temp.join("lezi.db"))
    })();

    let report = result?;
    publish_directory(temp, dest_data_dir)?;
    Ok(report)
}

fn validate_source_root(root: &Path) -> Result<(), MigrateError> {
    validate_data_root(root)?;
    let db = root.join("lezi.db");
    LEGACY_SCHEMA_V12
        .validate_path(&db)
        .map_err(|error| MigrateError::Internal(format!("schema-12 source: {error}")))?;
    Ok(())
}

fn validate_data_root(root: &Path) -> Result<(), MigrateError> {
    if !root.is_dir() {
        return Err(MigrateError::Io(io::Error::new(
            io::ErrorKind::NotFound,
            format!("data root missing: {}", root.display()),
        )));
    }
    let mut names = fs::read_dir(root)?
        .map(|entry| entry.map(|entry| entry.file_name().to_string_lossy().into_owned()))
        .collect::<Result<Vec<_>, _>>()?;
    names.sort();
    for name in &names {
        if !DATA_ROOT_ENTRIES.contains(&name.as_str()) {
            return Err(MigrateError::Internal(format!(
                "unsupported data-root entry `{name}`"
            )));
        }
    }
    for sidecar in ["lezi.db-wal", "lezi.db-shm", "lezi.db-journal"] {
        if root.join(sidecar).try_exists()? {
            return Err(MigrateError::Internal(format!(
                "source SQLite sidecar `{sidecar}` present; provide a complete checkpointed offline copy"
            )));
        }
    }
    validate_optional_app_update_pair(root)?;
    require_regular_file(&root.join("lezi.db"))?;
    let secret = root.join("server.secret");
    require_regular_file(&secret)?;
    if fs::metadata(&secret)?.len() < SERVER_SECRET_BYTES as u64 {
        return Err(MigrateError::Internal(format!(
            "server.secret is shorter than {SERVER_SECRET_BYTES} bytes"
        )));
    }
    let media = root.join("media");
    require_directory(&media)?;
    let tls = root.join("tls");
    require_directory(&tls)?;
    require_regular_nonempty_file(&tls.join("server.crt"))?;
    require_regular_nonempty_file(&tls.join("server.key"))?;
    reject_extra_tls_entries(&tls)?;
    validate_tls_identity(&tls)?;
    reject_symlinks(root)?;
    let connection = open_immutable(&root.join("lezi.db"))?;
    validate_database_integrity(&connection)?;
    Ok(())
}

fn validate_optional_app_update_pair(root: &Path) -> Result<(), MigrateError> {
    let apk = root.join("app-release.apk");
    let metadata = root.join("app-update.json");
    match (apk.try_exists()?, metadata.try_exists()?) {
        (false, false) => return Ok(()),
        (true, true) => {}
        _ => {
            return Err(MigrateError::Internal(
                "app-update APK and metadata must be present as an atomic pair".to_owned(),
            ));
        }
    }
    require_regular_nonempty_file(&apk)?;
    require_regular_nonempty_file(&metadata)?;
    let value: serde_json::Value = serde_json::from_slice(&fs::read(&metadata)?)
        .map_err(|error| MigrateError::Internal(format!("invalid app-update metadata: {error}")))?;
    let object = value.as_object().ok_or_else(|| {
        MigrateError::Internal("app-update metadata must be a JSON object".to_owned())
    })?;
    let expected = object
        .get("sha256")
        .and_then(|value| value.as_str())
        .ok_or_else(|| {
            MigrateError::Internal("app-update metadata is missing sha256".to_owned())
        })?;
    if expected.len() != 64
        || !expected
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        return Err(MigrateError::Internal(
            "app-update sha256 must be 64 lowercase hexadecimal characters".to_owned(),
        ));
    }
    let actual = hex::encode(Sha256::digest(fs::read(&apk)?));
    if actual != expected {
        return Err(MigrateError::Internal(
            "app-update APK sha256 does not match metadata".to_owned(),
        ));
    }
    let version = object.get("version_code").and_then(|value| value.as_u64());
    let floor = object
        .get("min_supported_version_code")
        .and_then(|value| value.as_u64());
    if version != Some(21) || floor != Some(21) {
        return Err(MigrateError::Internal(
            "schema-13 cutover app-update pair must enforce client version code 21".to_owned(),
        ));
    }
    Ok(())
}

fn validate_tls_identity(tls: &Path) -> Result<(), MigrateError> {
    let certificate = tls.join("server.crt");
    let private_key = tls.join("server.key");
    let certificate_ok = Command::new("openssl")
        .args(["x509", "-in"])
        .arg(&certificate)
        .args(["-noout", "-checkend", "0"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map_err(|error| MigrateError::Internal(format!("cannot execute openssl: {error}")))?
        .success();
    if !certificate_ok {
        return Err(MigrateError::Internal(
            "TLS certificate is invalid or expired".to_owned(),
        ));
    }
    let certificate_spki = openssl_public_key(&[
        "x509",
        "-in",
        certificate.to_str().ok_or_else(|| {
            MigrateError::Internal("TLS certificate path is not UTF-8".to_owned())
        })?,
        "-pubkey",
        "-noout",
    ])?;
    let private_spki = openssl_public_key(&[
        "pkey",
        "-in",
        private_key.to_str().ok_or_else(|| {
            MigrateError::Internal("TLS private-key path is not UTF-8".to_owned())
        })?,
        "-pubout",
    ])?;
    if certificate_spki != private_spki {
        return Err(MigrateError::Internal(
            "TLS certificate/private-key identity mismatch".to_owned(),
        ));
    }
    Ok(())
}

fn openssl_public_key(args: &[&str]) -> Result<Vec<u8>, MigrateError> {
    let output = Command::new("openssl")
        .args(args)
        .stderr(Stdio::null())
        .output()
        .map_err(|error| MigrateError::Internal(format!("cannot execute openssl: {error}")))?;
    if !output.status.success() || output.stdout.is_empty() {
        return Err(MigrateError::Internal(
            "TLS identity cannot be decoded".to_owned(),
        ));
    }
    Ok(output.stdout)
}

fn rebuild_v12_database(source_path: &Path, dest_path: &Path) -> Result<(), MigrateError> {
    let source = open_immutable(source_path)?;
    LEGACY_SCHEMA_V12
        .validate(&source)
        .map_err(|error| MigrateError::Internal(error.to_string()))?;
    private_file(dest_path)?;
    let mut dest = Connection::open(dest_path)?;
    dest.execute_batch("PRAGMA foreign_keys = OFF; PRAGMA journal_mode = DELETE;")?;
    dest.execute_batch(CURRENT_SCHEMA_SQL)?;
    dest.pragma_update(None, "user_version", DATABASE_SCHEMA_VERSION)?;

    let tables = source_tables(&source)?;
    let transaction = dest.transaction()?;
    for table in tables {
        copy_table_intersection(&source, &transaction, &table)?;
    }
    transaction.commit()?;
    validate_table_counts(&source, &dest)?;
    dest.execute_batch("PRAGMA foreign_keys = ON;")?;
    validate_database_integrity(&dest)?;
    drop(dest);
    for suffix in ["-wal", "-shm", "-journal"] {
        let _ = fs::remove_file(PathBuf::from(format!("{}{suffix}", dest_path.display())));
    }
    Ok(())
}

fn validate_table_counts(source: &Connection, dest: &Connection) -> Result<(), MigrateError> {
    for table in source_tables(source)? {
        let source_count: i64 = source.query_row(
            &format!("SELECT COUNT(*) FROM {}", quoted(&table)),
            [],
            |row| row.get(0),
        )?;
        let dest_count: i64 = dest.query_row(
            &format!("SELECT COUNT(*) FROM {}", quoted(&table)),
            [],
            |row| row.get(0),
        )?;
        if source_count != dest_count {
            return Err(MigrateError::Internal(format!(
                "row-count mismatch for `{table}`: source={source_count} target={dest_count}"
            )));
        }
    }
    Ok(())
}

fn source_tables(connection: &Connection) -> Result<Vec<String>, MigrateError> {
    let mut statement = connection.prepare(
        "SELECT name FROM sqlite_schema
          WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
          ORDER BY rowid",
    )?;
    let rows = statement
        .query_map([], |row| row.get(0))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(MigrateError::from)?;
    Ok(rows)
}

fn table_columns(connection: &Connection, table: &str) -> Result<Vec<String>, MigrateError> {
    let mut statement = connection.prepare(&format!("PRAGMA table_info({})", quoted(table)))?;
    let rows = statement
        .query_map([], |row| row.get(1))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(MigrateError::from)?;
    Ok(rows)
}

fn copy_table_intersection(
    source: &Connection,
    dest: &rusqlite::Transaction<'_>,
    table: &str,
) -> Result<(), MigrateError> {
    let source_columns = table_columns(source, table)?;
    let dest_columns = table_columns(dest, table)?;
    let columns = source_columns
        .into_iter()
        .filter(|column| dest_columns.contains(column))
        .collect::<Vec<_>>();
    if columns.is_empty() {
        return Err(MigrateError::Internal(format!(
            "schema-13 target has no compatible columns for source table `{table}`"
        )));
    }
    let names = columns.iter().map(|name| quoted(name)).collect::<Vec<_>>();
    let select_sql = format!("SELECT {} FROM {}", names.join(", "), quoted(table));
    let placeholders = (1..=columns.len())
        .map(|index| format!("?{index}"))
        .collect::<Vec<_>>()
        .join(", ");
    let insert_sql = format!(
        "INSERT INTO {} ({}) VALUES ({placeholders})",
        quoted(table),
        names.join(", ")
    );
    let mut select = source.prepare(&select_sql)?;
    let mut rows = select.query([])?;
    while let Some(row) = rows.next()? {
        let values = (0..columns.len())
            .map(|index| row.get::<_, Value>(index))
            .collect::<Result<Vec<_>, _>>()?;
        dest.execute(&insert_sql, params_from_iter(values))?;
    }
    Ok(())
}

fn quoted(identifier: &str) -> String {
    format!("\"{}\"", identifier.replace('"', "\"\""))
}

pub(crate) fn validate_schema13_data_dir(root: &Path) -> Result<(), MigrateError> {
    validate_data_root(root)?;
    Store::preflight_existing_schema(&root.join("lezi.db"))
        .map_err(|error| MigrateError::Internal(format!("schema-13 preflight: {error}")))?;
    let connection = open_immutable(&root.join("lezi.db"))?;
    validate_media_inventory(&connection, root)?;
    crate::store::validate_authority_graph_on(
        &connection,
        &root.join("lezi.db"),
        usize::MAX,
        |family, media, size| {
            let metadata = fs::symlink_metadata(root.join("media").join(family).join(media))?;
            Ok(metadata.file_type().is_file() && metadata.len() == size as u64)
        },
    )
    .map_err(|error| MigrateError::Internal(format!("schema-13 authority preflight: {error}")))?;
    Ok(())
}

fn validate_database_integrity(connection: &Connection) -> Result<(), MigrateError> {
    for pragma in ["quick_check", "integrity_check"] {
        let result: String =
            connection.query_row(&format!("PRAGMA {pragma}"), [], |row| row.get(0))?;
        if result != "ok" {
            return Err(MigrateError::Internal(format!(
                "SQLite {pragma} failed: {result}"
            )));
        }
    }
    let violations: i64 =
        connection.query_row("SELECT COUNT(*) FROM pragma_foreign_key_check", [], |row| {
            row.get(0)
        })?;
    if violations != 0 {
        return Err(MigrateError::Internal(format!(
            "SQLite foreign_key_check found {violations} violations"
        )));
    }
    Ok(())
}

fn validate_media_inventory(connection: &Connection, root: &Path) -> Result<(), MigrateError> {
    validate_published_media_references(connection, root)?;
    validate_bundle_media(connection, root)?;
    validate_causal_staged_media(connection, root)?;
    Ok(())
}

fn validate_published_media_references(
    connection: &Connection,
    root: &Path,
) -> Result<(), MigrateError> {
    for query in [
        "SELECT family_id, media_uuid FROM media_publications",
        "SELECT family_id, media_uuid FROM entity_version_media",
    ] {
        let mut statement = connection.prepare(query)?;
        let rows = statement.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        for row in rows {
            let (family, media) = row?;
            require_regular_file(&root.join("media").join(family).join(media))?;
        }
    }
    Ok(())
}

fn validate_bundle_media(connection: &Connection, root: &Path) -> Result<(), MigrateError> {
    let mut statement = connection.prepare(
        "SELECT media.family_id, media.bundle_id, media.media_uuid,
                media.declared_byte_size, media.staged_byte_size, media.staged_sha256,
                bundle.status, publication.source
           FROM sync_bundle_media AS media
           JOIN sync_bundles AS bundle
             ON bundle.family_id = media.family_id AND bundle.bundle_id = media.bundle_id
      LEFT JOIN media_publications AS publication
             ON publication.family_id = media.family_id
            AND publication.media_uuid = media.media_uuid
            AND publication.bundle_id = media.bundle_id
          WHERE media.staged_byte_size IS NOT NULL OR media.staged_sha256 IS NOT NULL",
    )?;
    let rows = statement.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, Option<i64>>(3)?,
            row.get::<_, Option<i64>>(4)?,
            row.get::<_, Option<String>>(5)?,
            row.get::<_, String>(6)?,
            row.get::<_, Option<String>>(7)?,
        ))
    })?;
    for row in rows {
        let (
            family,
            bundle,
            media,
            declared_size,
            staged_size,
            expected_hash,
            status,
            publication_source,
        ) = row?;
        let path = if status == "committed" || publication_source.is_some() {
            root.join("media").join(&family).join(&media)
        } else {
            root.join("media")
                .join(&family)
                .join(".stage")
                .join(&bundle)
                .join(&media)
        };
        let expected_size = staged_size.or(declared_size).ok_or_else(|| {
            MigrateError::Internal(format!(
                "bundle media integrity is incomplete for {family}/{bundle}/{media}"
            ))
        })?;
        let expected_hash = expected_hash.ok_or_else(|| {
            MigrateError::Internal(format!(
                "bundle media digest is missing for {family}/{bundle}/{media}"
            ))
        })?;
        validate_media_bytes(&path, expected_size, &expected_hash)?;
    }
    Ok(())
}

fn validate_causal_staged_media(connection: &Connection, root: &Path) -> Result<(), MigrateError> {
    let mut statement = connection.prepare(
        "SELECT family_id, media_uuid, sha256, byte_size, consumed_at
           FROM causal_media_staging",
    )?;
    let rows = statement.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
            row.get::<_, i64>(3)?,
            row.get::<_, Option<i64>>(4)?,
        ))
    })?;
    for row in rows {
        let (family, media, expected_hash, expected_size, consumed_at) = row?;
        let path = if consumed_at.is_some() {
            root.join("media").join(family).join(media)
        } else {
            root.join("media")
                .join(".causal-stage")
                .join(family)
                .join(media)
        };
        validate_media_bytes(&path, expected_size, &expected_hash)?;
    }
    Ok(())
}

fn validate_media_bytes(
    path: &Path,
    expected_size: i64,
    expected_hash: &str,
) -> Result<(), MigrateError> {
    require_regular_file(path)?;
    let bytes = fs::read(path)?;
    if bytes.len() as i64 != expected_size || hex::encode(Sha256::digest(&bytes)) != expected_hash {
        return Err(MigrateError::Internal(format!(
            "media bytes mismatch: {}",
            path.display()
        )));
    }
    Ok(())
}

fn copy_tree_exact(source: &Path, dest: &Path) -> Result<(), MigrateError> {
    require_directory(source)?;
    private_directories(dest)?;
    let mut entries = fs::read_dir(source)?.collect::<Result<Vec<_>, _>>()?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let source_path = entry.path();
        let dest_path = dest.join(entry.file_name());
        let file_type = entry.file_type()?;
        if file_type.is_dir() {
            copy_tree_exact(&source_path, &dest_path)?;
        } else if file_type.is_file() {
            copy_required_file(&source_path, &dest_path)?;
        } else {
            return Err(MigrateError::Internal(format!(
                "unsupported non-regular source path: {}",
                source_path.display()
            )));
        }
    }
    Ok(())
}

fn copy_required_file(source: &Path, dest: &Path) -> Result<(), MigrateError> {
    require_regular_file(source)?;
    if let Some(parent) = dest.parent() {
        private_directories(parent)?;
    }
    copy_private(source, dest)?;
    Ok(())
}

fn validate_copied_assets(source: &Path, dest: &Path) -> Result<(), MigrateError> {
    for entry in ["media", "server.secret", "tls"] {
        if tree_digest(&source.join(entry))? != tree_digest(&dest.join(entry))? {
            return Err(MigrateError::Internal(format!(
                "copied `{entry}` bytes do not match source"
            )));
        }
    }
    Ok(())
}

fn tree_digest(path: &Path) -> Result<Vec<u8>, MigrateError> {
    let metadata = fs::symlink_metadata(path)?;
    let mut digest = Sha256::new();
    if metadata.file_type().is_file() {
        digest.update(b"file\0");
        digest.update(fs::read(path)?);
    } else if metadata.file_type().is_dir() {
        digest.update(b"dir\0");
        let mut entries = fs::read_dir(path)?.collect::<Result<Vec<_>, _>>()?;
        entries.sort_by_key(|entry| entry.file_name());
        for entry in entries {
            digest.update(entry.file_name().to_string_lossy().as_bytes());
            digest.update([0]);
            digest.update(tree_digest(&entry.path())?);
        }
    } else {
        return Err(MigrateError::Internal(format!(
            "unsupported source path: {}",
            path.display()
        )));
    }
    Ok(digest.finalize().to_vec())
}

fn reject_symlinks(path: &Path) -> Result<(), MigrateError> {
    let metadata = fs::symlink_metadata(path)?;
    if metadata.file_type().is_symlink() {
        return Err(MigrateError::Internal(format!(
            "source symlink is not allowed: {}",
            path.display()
        )));
    }
    if metadata.is_dir() {
        for entry in fs::read_dir(path)? {
            reject_symlinks(&entry?.path())?;
        }
    }
    Ok(())
}

fn reject_extra_tls_entries(tls: &Path) -> Result<(), MigrateError> {
    for entry in fs::read_dir(tls)? {
        let name = entry?.file_name().to_string_lossy().into_owned();
        if name != "server.crt" && name != "server.key" {
            return Err(MigrateError::Internal(format!(
                "unexpected TLS identity entry `{name}`"
            )));
        }
    }
    Ok(())
}

fn require_directory(path: &Path) -> Result<(), MigrateError> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_dir() {
        return Err(MigrateError::Internal(format!(
            "required directory is missing or unsafe: {}",
            path.display()
        )));
    }
    Ok(())
}

fn require_regular_file(path: &Path) -> Result<(), MigrateError> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_file() {
        return Err(MigrateError::Internal(format!(
            "required regular file is missing or unsafe: {}",
            path.display()
        )));
    }
    Ok(())
}

fn require_regular_nonempty_file(path: &Path) -> Result<(), MigrateError> {
    require_regular_file(path)?;
    if fs::metadata(path)?.len() == 0 {
        return Err(MigrateError::Internal(format!(
            "required identity file is empty: {}",
            path.display()
        )));
    }
    Ok(())
}

fn migration_report(database: &Path) -> Result<MigrateReport, MigrateError> {
    let connection = open_immutable(database)?;
    let count = |table: &str| -> Result<u64, MigrateError> {
        Ok(connection.query_row(
            &format!("SELECT COUNT(*) FROM {}", quoted(table)),
            [],
            |row| row.get::<_, i64>(0),
        )? as u64)
    };
    Ok(MigrateReport {
        families: count("families")?,
        memberships: count("memberships")?,
        entities: count("entities")?,
        committed_bundles: connection.query_row(
            "SELECT COUNT(*) FROM sync_bundles WHERE status = 'committed'",
            [],
            |row| row.get::<_, i64>(0),
        )? as u64,
        ..MigrateReport::default()
    })
}

#[cfg(test)]
mod tests {
    use std::fs;

    use rusqlite::{params, Connection};
    use sha2::{Digest, Sha256};
    use tempfile::tempdir;

    use crate::offline_migrate::schema_contract::LEGACY_SCHEMA_V12;
    use crate::offline_migrate::test_support::generate_test_tls_identity;
    use crate::offline_migrate::v11::{SOURCE_V11_SCHEMA_SQL, SOURCE_V11_USER_VERSION};
    use crate::store::Store;

    use super::{migrate_v11_or_v12_data_dir_to_v13, migrate_v12_data_dir_to_v13};

    const FAMILY: &str = "11111111-1111-1111-1111-111111111111";
    const MEMBER: &str = "22222222-2222-2222-2222-222222222222";
    const ROOT: &str = "33333333-3333-3333-3333-333333333333";
    const STABLE: &str = "44444444-4444-4444-4444-444444444444";
    const BRANCH: &str = "55555555-5555-5555-5555-555555555555";
    const CONFLICT: &str = "66666666-6666-6666-6666-666666666666";
    const MEDIA: &str = "77777777-7777-7777-7777-777777777777";

    fn write_v12_fixture(root: &std::path::Path) {
        fs::create_dir_all(root.join("media").join(FAMILY)).unwrap();
        fs::create_dir_all(root.join("tls")).unwrap();
        fs::write(root.join("server.secret"), [9u8; 32]).unwrap();
        generate_test_tls_identity(&root.join("tls"));
        fs::write(root.join("media").join(FAMILY).join(MEDIA), b"media-bytes").unwrap();

        let connection = Connection::open(root.join("lezi.db")).unwrap();
        LEGACY_SCHEMA_V12.initialize(&connection).unwrap();
        connection
            .execute_batch(&format!(
                "
                PRAGMA foreign_keys = ON;
                INSERT INTO families(id, created_at, name, owner_root_fingerprint)
                VALUES ('{FAMILY}', 1, 'family', 'root-fingerprint');
                INSERT INTO memberships(
                    membership_id, family_id, role, display_name, display_name_key, left_at
                ) VALUES ('{MEMBER}', '{FAMILY}', 'owner', 'Owner', 'owner', NULL);
                INSERT INTO family_meta(family_id, rev) VALUES ('{FAMILY}', 2);
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES (
                    '{FAMILY}', 'record', '{ROOT}', 10, NULL,
                    '{{\"client_uuid\":\"{ROOT}\",\"type\":\"feeding\"}}', 1
                );
                INSERT INTO entity_versions(
                    family_id, version_id, entity_type, client_uuid, updated_at, deleted_at,
                    payload_json, content_hash, mutation_id, origin, created_at
                ) VALUES
                    ('{FAMILY}', '{STABLE}', 'record', '{ROOT}', 10, NULL,
                     '{{\"client_uuid\":\"{ROOT}\",\"type\":\"feeding\"}}', 'stable-hash',
                     'stable-mutation', 'accepted', 10),
                    ('{FAMILY}', '{BRANCH}', 'record', '{ROOT}', 11, NULL,
                     '{{\"client_uuid\":\"{ROOT}\",\"type\":\"feeding\"}}', 'branch-hash',
                     'branch-mutation', 'branched', 11);
                INSERT INTO entity_stable_heads(
                    family_id, entity_type, client_uuid, version_id
                ) VALUES ('{FAMILY}', 'record', '{ROOT}', '{STABLE}');
                INSERT INTO conflicts(
                    family_id, conflict_id, entity_type, client_uuid, base_version_id,
                    stable_version_id, status, kind, created_at, resolved_at
                ) VALUES (
                    '{FAMILY}', '{CONFLICT}', 'record', '{ROOT}', NULL,
                    '{STABLE}', 'open', 'concurrent', 12, NULL
                );
                INSERT INTO conflict_branches(family_id, conflict_id, branch_version_id)
                VALUES ('{FAMILY}', '{CONFLICT}', '{BRANCH}');
                INSERT INTO entity_version_media(
                    family_id, version_id, media_uuid, media_payload_json, content_hash
                ) VALUES ('{FAMILY}', '{BRANCH}', '{MEDIA}', '{{}}', 'media-hash');
                INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
                VALUES ('{FAMILY}', '{MEDIA}', 'ordinary', NULL);
                "
            ))
            .unwrap();
        connection
            .execute(
                "INSERT INTO causal_media_staging(
                    family_id, membership_id, media_uuid, sha256, byte_size,
                    created_at, expires_at, status, consumed_at
                 ) VALUES (?1, ?2, ?3, ?4, ?5, 1, 9999999999, 'consumed', 2)",
                params![
                    FAMILY,
                    MEMBER,
                    MEDIA,
                    "bd7aa67d0cee967e6fca8ef4917e3c70445a9cfe0f3d91ddd2eeff1bfe4b2069",
                    11i64,
                ],
            )
            .unwrap();
        // Use real current-domain roots and integrity hashes; the old fixture's
        // placeholder "feeding" payload never represented a startable authority graph.
        let baby = "88888888-8888-4888-8888-888888888888";
        let root = serde_json::json!({"baby_client_uuid":baby,"type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"stable","payload_json":{"amount_ml":100},"schema_version":2,"created_by_membership_id":MEMBER,"updated_at":10});
        let root = root.as_object().unwrap().clone();
        let mut projection = root.clone();
        projection.remove("updated_at");
        connection
            .execute(
                "UPDATE entities SET payload_json=?1 WHERE entity_type='record' AND client_uuid=?2",
                params![serde_json::to_string(&projection).unwrap(), ROOT],
            )
            .unwrap();
        connection.execute("INSERT INTO entities(family_id,entity_type,client_uuid,updated_at,deleted_at,payload_json,rev) VALUES (?1,'baby',?2,1,NULL,?3,2)",params![FAMILY,baby,serde_json::json!({"nickname":"synthetic","sex":"female","birthday":"2025-01-02","birth_weight_grams":null,"avatar_media_uuid":null}).to_string()]).unwrap();
        let hash = crate::store::test_mutation_content_hash("_", "_", None, false, &root, &[]);
        connection
            .execute(
                "UPDATE entity_versions SET payload_json=?1,content_hash=?2 WHERE version_id=?3",
                params![serde_json::to_string(&root).unwrap(), hash, STABLE],
            )
            .unwrap();
        let mut branch = root;
        branch.insert("updated_at".to_owned(), serde_json::json!(11));
        branch.insert("note".to_owned(), serde_json::json!("branch"));
        let item = crate::store::CausalMediaItem {
            media_uuid: MEDIA.to_owned(),
            role: "log".to_owned(),
            sha256: hex::encode(Sha256::digest(b"media-bytes")),
            byte_size: 11,
            mime: "image/jpeg".to_owned(),
            width: Some(1),
            height: Some(1),
        };
        let hash = crate::store::test_mutation_content_hash(
            "_",
            "_",
            None,
            false,
            &branch,
            std::slice::from_ref(&item),
        );
        connection
            .execute(
                "UPDATE entity_versions SET payload_json=?1,content_hash=?2 WHERE version_id=?3",
                params![serde_json::to_string(&branch).unwrap(), hash, BRANCH],
            )
            .unwrap();
        let media = item.to_value();
        let hash = crate::store::test_mutation_content_hash(
            "media",
            MEDIA,
            None,
            false,
            media.as_object().unwrap(),
            &[],
        );
        connection.execute("UPDATE entity_version_media SET media_payload_json=?1,content_hash=?2 WHERE version_id=?3",params![media.to_string(),hash,BRANCH]).unwrap();
    }

    fn write_v11_fixture(root: &std::path::Path) {
        fs::create_dir_all(root.join("media")).unwrap();
        fs::create_dir_all(root.join("tls")).unwrap();
        fs::write(root.join("server.secret"), [8u8; 32]).unwrap();
        generate_test_tls_identity(&root.join("tls"));
        let connection = Connection::open(root.join("lezi.db")).unwrap();
        connection.execute_batch(SOURCE_V11_SCHEMA_SQL).unwrap();
        connection
            .pragma_update(None, "user_version", SOURCE_V11_USER_VERSION)
            .unwrap();
        connection
            .execute_batch(&format!(
                "INSERT INTO families(id, created_at, name, owner_root_fingerprint)
                 VALUES ('{FAMILY}', 1, 'family', 'root-fingerprint');
                 INSERT INTO memberships(
                     membership_id, family_id, role, display_name, display_name_key, left_at
                 ) VALUES ('{MEMBER}', '{FAMILY}', 'owner', 'Owner', 'owner', NULL);
                 INSERT INTO devices(
                     device_id, membership_id, device_name, device_name_key,
                     status, created_at, last_used_at
                 ) VALUES ('device', '{MEMBER}', 'phone', 'phone', 'active', 1, 2);
                 INSERT INTO device_sessions(
                     session_id, device_id, access_token_hash, access_expires_at,
                     refresh_token_hash, refresh_generation, revoked_at, revoked_reason
                 ) VALUES ('session', 'device', 'access-hash', 10, 'refresh-hash', 3, NULL, NULL);
                 INSERT INTO family_meta(family_id, rev) VALUES ('{FAMILY}', 0);"
            ))
            .unwrap();
    }

    #[test]
    fn legacy_staging_names_never_delete_the_input_or_unowned_siblings() {
        for name in [".out.schema13-migrating", ".out.schema12-stage"] {
            let temp = tempdir().unwrap();
            let source = temp.path().join(name);
            let out = temp.path().join("out");
            if name.ends_with("schema12-stage") {
                write_v11_fixture(&source);
            } else {
                write_v12_fixture(&source);
            }
            let before = super::tree_digest(&source).unwrap();
            migrate_v11_or_v12_data_dir_to_v13(&source, &out).unwrap();
            assert_eq!(super::tree_digest(&source).unwrap(), before);
        }
    }

    #[cfg(unix)]
    #[test]
    fn copy_out_rejects_a_symlink_ancestor_into_the_source() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        write_v12_fixture(&source);
        let alias = temp.path().join("alias");
        std::os::unix::fs::symlink(&source, &alias).unwrap();
        let before = super::tree_digest(&source).unwrap();
        assert!(migrate_v11_or_v12_data_dir_to_v13(&source, &alias.join("out")).is_err());
        assert_eq!(super::tree_digest(&source).unwrap(), before);
    }

    #[cfg(unix)]
    #[test]
    fn copy_out_outputs_are_private_even_when_source_modes_are_public() {
        use std::os::unix::fs::PermissionsExt;
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v12_fixture(&source);
        fs::set_permissions(
            source.join("server.secret"),
            fs::Permissions::from_mode(0o666),
        )
        .unwrap();
        migrate_v11_or_v12_data_dir_to_v13(&source, &out).unwrap();
        fn assert_private(path: &std::path::Path) {
            let metadata = path.metadata().unwrap();
            assert_eq!(
                metadata.permissions().mode() & 0o077,
                0,
                "{}",
                path.display()
            );
            if metadata.is_dir() {
                for entry in fs::read_dir(path).unwrap() {
                    assert_private(&entry.unwrap().path());
                }
            }
        }
        assert_private(&out);
        assert_eq!(
            source
                .join("server.secret")
                .metadata()
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o666
        );
    }

    #[test]
    fn v12_copy_out_opens_as_schema13_and_preserves_authority() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v12_fixture(&source);
        let source_db = fs::read(source.join("lezi.db")).unwrap();

        migrate_v12_data_dir_to_v13(&source, &out).expect("v12 to v13");

        assert_eq!(fs::read(source.join("lezi.db")).unwrap(), source_db);
        Store::preflight_existing_schema(&out.join("lezi.db")).unwrap();
        let connection = Connection::open(out.join("lezi.db")).unwrap();
        assert_eq!(
            connection
                .query_row("SELECT COUNT(*) FROM conflict_branches", [], |row| row
                    .get::<_, i64>(0))
                .unwrap(),
            1,
        );
        assert_eq!(
            connection
                .query_row(
                    "SELECT publication_confirmed FROM causal_media_staging
                     WHERE family_id = ?1 AND media_uuid = ?2",
                    params![FAMILY, MEDIA],
                    |row| row.get::<_, i64>(0),
                )
                .unwrap(),
            0,
        );
        assert_eq!(
            fs::read(out.join("media").join(FAMILY).join(MEDIA)).unwrap(),
            b"media-bytes",
        );
        assert_eq!(
            fs::read(out.join("server.secret")).unwrap(),
            fs::read(source.join("server.secret")).unwrap(),
        );
        assert_eq!(
            fs::read(out.join("tls/server.crt")).unwrap(),
            fs::read(source.join("tls/server.crt")).unwrap(),
        );
        assert_eq!(
            fs::read(out.join("tls/server.key")).unwrap(),
            fs::read(source.join("tls/server.key")).unwrap(),
        );
    }

    #[test]
    fn checkpointed_wal_source_migrates_without_creating_sidecars() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v12_fixture(&source);
        let connection = Connection::open(source.join("lezi.db")).unwrap();
        assert_eq!(
            connection
                .query_row("PRAGMA journal_mode = WAL", [], |row| row
                    .get::<_, String>(0))
                .unwrap(),
            "wal",
        );
        connection
            .execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")
            .unwrap();
        drop(connection);
        for sidecar in ["lezi.db-wal", "lezi.db-shm"] {
            let path = source.join(sidecar);
            if path.exists() {
                fs::remove_file(path).unwrap();
            }
        }

        migrate_v12_data_dir_to_v13(&source, &out).expect("checkpointed WAL source");

        assert!(!source.join("lezi.db-wal").exists());
        assert!(!source.join("lezi.db-shm").exists());
        Store::preflight_existing_schema(&out.join("lezi.db")).unwrap();
    }

    #[test]
    fn checkpointed_v11_wal_source_migrates_without_creating_sidecars() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v11_fixture(&source);
        let connection = Connection::open(source.join("lezi.db")).unwrap();
        assert_eq!(
            connection
                .query_row("PRAGMA journal_mode = WAL", [], |row| row
                    .get::<_, String>(0))
                .unwrap(),
            "wal",
        );
        connection
            .execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")
            .unwrap();
        drop(connection);
        for sidecar in ["lezi.db-wal", "lezi.db-shm"] {
            let path = source.join(sidecar);
            if path.exists() {
                fs::remove_file(path).unwrap();
            }
        }

        migrate_v11_or_v12_data_dir_to_v13(&source, &out).expect("checkpointed v11 WAL");

        assert!(!source.join("lezi.db-wal").exists());
        assert!(!source.join("lezi.db-shm").exists());
        Store::preflight_existing_schema(&out.join("lezi.db")).unwrap();
    }

    #[test]
    fn integrity_checked_update_pair_may_quiesce_source_without_entering_migrated_root() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v12_fixture(&source);
        let apk = b"signed-release-apk";
        fs::write(source.join("app-release.apk"), apk).unwrap();
        fs::write(
            source.join("app-update.json"),
            format!(
                r#"{{"version_code":21,"min_supported_version_code":21,"sha256":"{}"}}"#,
                hex::encode(Sha256::digest(apk)),
            ),
        )
        .unwrap();
        let source_before = super::tree_digest(&source).unwrap();

        migrate_v12_data_dir_to_v13(&source, &out).expect("v12 update channel to v13");

        assert_eq!(super::tree_digest(&source).unwrap(), source_before);
        assert!(!out.join("app-release.apk").exists());
        assert!(!out.join("app-update.json").exists());
        Store::preflight_existing_schema(&out.join("lezi.db")).unwrap();
    }

    #[test]
    fn update_channel_requires_an_atomic_integrity_checked_pair() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v12_fixture(&source);
        fs::write(source.join("app-release.apk"), b"apk").unwrap();
        let error = migrate_v12_data_dir_to_v13(&source, &out).unwrap_err();
        assert!(error.to_string().contains("atomic pair"));

        fs::write(
            source.join("app-update.json"),
            r#"{"version_code":21,"min_supported_version_code":21,"sha256":"0000000000000000000000000000000000000000000000000000000000000000"}"#,
        )
        .unwrap();
        let error = migrate_v12_data_dir_to_v13(&source, &out).unwrap_err();
        assert!(error.to_string().contains("does not match metadata"));
    }

    #[test]
    fn v11_copy_out_opens_as_schema13_and_preserves_sessions() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v11_fixture(&source);
        let source_before = super::tree_digest(&source).unwrap();

        migrate_v11_or_v12_data_dir_to_v13(&source, &out).expect("v11 to v13");

        assert_eq!(super::tree_digest(&source).unwrap(), source_before);
        Store::preflight_existing_schema(&out.join("lezi.db")).unwrap();
        let connection = Connection::open(out.join("lezi.db")).unwrap();
        assert_eq!(
            connection
                .query_row(
                    "SELECT access_token_hash || ':' || refresh_token_hash
                     FROM device_sessions WHERE session_id = 'session'",
                    [],
                    |row| row.get::<_, String>(0),
                )
                .unwrap(),
            "access-hash:refresh-hash",
        );
        assert_eq!(
            fs::read(out.join("server.secret")).unwrap(),
            fs::read(source.join("server.secret")).unwrap(),
        );
    }

    #[test]
    fn invalid_source_states_leave_no_promotable_output() {
        for case in [
            "wrong-version",
            "wrong-shape",
            "wal",
            "short-secret",
            "partial-tls",
            "invalid-tls",
            "missing-media",
            "source-integrity",
        ] {
            let temp = tempdir().unwrap();
            let source = temp.path().join("source");
            let out = temp.path().join("out");
            write_v12_fixture(&source);
            match case {
                "wrong-version" => Connection::open(source.join("lezi.db"))
                    .unwrap()
                    .pragma_update(None, "user_version", 10)
                    .unwrap(),
                "wrong-shape" => {
                    Connection::open(source.join("lezi.db"))
                        .unwrap()
                        .execute("DROP INDEX entity_versions_root", [])
                        .unwrap();
                }
                "wal" => fs::write(source.join("lezi.db-wal"), b"incomplete").unwrap(),
                "short-secret" => fs::write(source.join("server.secret"), [1u8; 8]).unwrap(),
                "partial-tls" => fs::remove_file(source.join("tls/server.key")).unwrap(),
                "invalid-tls" => fs::write(source.join("tls/server.crt"), b"invalid").unwrap(),
                "missing-media" => {
                    fs::remove_file(source.join("media").join(FAMILY).join(MEDIA)).unwrap()
                }
                "source-integrity" => {
                    let connection = Connection::open(source.join("lezi.db")).unwrap();
                    connection
                        .execute_batch(&format!(
                            "PRAGMA foreign_keys = OFF;
                             INSERT INTO entity_version_media(
                                 family_id, version_id, media_uuid,
                                 media_payload_json, content_hash
                             ) VALUES ('{FAMILY}', 'missing-version',
                                 '88888888-8888-8888-8888-888888888888', '{{}}', 'orphan');"
                        ))
                        .unwrap();
                }
                _ => unreachable!(),
            }
            let source_before = super::tree_digest(&source).unwrap();
            let error = migrate_v11_or_v12_data_dir_to_v13(&source, &out)
                .expect_err("invalid source must fail");
            assert!(!out.exists(), "{case} left output after {error}");
            assert_eq!(
                super::tree_digest(&source).unwrap(),
                source_before,
                "{case} mutated source",
            );
        }
    }

    #[test]
    fn large_v12_fixture_preserves_every_row() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let out = temp.path().join("out");
        write_v12_fixture(&source);
        let mut connection = Connection::open(source.join("lezi.db")).unwrap();
        let transaction = connection.transaction().unwrap();
        for index in 0..2_000 {
            transaction
                .execute(
                    "INSERT INTO source_relation_record_eligibility(
                        family_id, record_client_uuid, baby_client_uuid,
                        record_type, record_timestamp, author_membership_id
                     ) VALUES (?1, ?2, 'baby', 'feeding', ?3, ?4)",
                    params![FAMILY, format!("record-{index:04}"), index, MEMBER],
                )
                .unwrap();
        }
        transaction.commit().unwrap();
        drop(connection);

        migrate_v12_data_dir_to_v13(&source, &out).expect("large migrate");

        let connection = Connection::open(out.join("lezi.db")).unwrap();
        assert_eq!(
            connection
                .query_row(
                    "SELECT COUNT(*) FROM source_relation_record_eligibility",
                    [],
                    |row| row.get::<_, i64>(0),
                )
                .unwrap(),
            2_000,
        );
    }
    #[test]
    fn copy_out_rejects_a_stray_live_media_projection_before_publication() {
        let temp = tempdir().unwrap();
        let source = temp.path().join("source");
        let output = temp.path().join("out");
        write_v12_fixture(&source);
        let connection = Connection::open(source.join("lezi.db")).unwrap();
        connection.execute("INSERT INTO entities(family_id,entity_type,client_uuid,updated_at,deleted_at,payload_json,rev) VALUES (?1,'media',?2,10,NULL,?3,3)",params![FAMILY,MEDIA,serde_json::json!({"kind":"log","record_client_uuid":ROOT,"baby_client_uuid":null,"care_plan_client_uuid":null,"byte_size":11,"mime":"image/jpeg","width":1,"height":1}).to_string()]).unwrap();
        drop(connection);
        let before = super::tree_digest(&source).unwrap();
        let error = migrate_v12_data_dir_to_v13(&source, &output).unwrap_err();
        assert!(
            error
                .to_string()
                .contains("stable_media_projection_mismatch"),
            "{error}"
        );
        assert!(!output.exists());
        assert_eq!(super::tree_digest(&source).unwrap(), before);
    }
}
