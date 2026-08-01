//! Domain store tests.

use super::super::*;
use rusqlite::Connection;
use std::fs;
use tempfile::TempDir;

#[test]
fn empty_database_initializes_current_schema_and_restarts_with_persistence() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    fs::File::create(&database_path).unwrap();

    let first = Store::open(&database_path).unwrap();
    let issued = first
        .create_family(
            CreateFamilyInput {
                now: 1,
                create_request_id: "fresh-schema-request-000000000001",
                display_name: "妈妈",
                display_name_key: "妈妈",
                family_name: "新家庭",
                device_name: "fresh-device",
                owner_root_fingerprint: None,
            },
            |_, _, _| ("fresh-access".to_owned(), "fresh-refresh".to_owned()),
        )
        .unwrap();
    drop(first);

    let restarted = Store::open(&database_path).unwrap();
    let principal = restarted
        .authenticate(&issued.access_token, 2)
        .unwrap()
        .unwrap();
    assert_eq!(principal.family_id, issued.family_id);
    assert_eq!(principal.membership_id, issued.membership_id);
    assert_eq!(principal.device_id, issued.device_id);
    drop(restarted);

    let connection = Connection::open(database_path).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        DATABASE_SCHEMA_VERSION
    );
    let bundle_columns = table_columns(&connection, "sync_bundles").unwrap();
    assert!(bundle_columns.contains("staged_membership_id"));
    assert!(!bundle_columns.contains("device_id"));
    assert_eq!(
    connection
        .query_row(
            "SELECT \"notnull\" FROM pragma_table_info('sync_bundles') WHERE name = 'staged_membership_id'",
            [],
            |row| row.get::<_, i64>(0),
        )
        .unwrap(),
    1
);
    let membership_columns = table_columns(&connection, "memberships").unwrap();
    assert!(membership_columns.contains("display_name_key"));
    let member_request_columns = table_columns(&connection, "member_login_requests").unwrap();
    assert!(member_request_columns.contains("pending_secret_hash"));
    assert!(member_request_columns.contains("expires_at"));
    assert!(member_request_columns.contains("approval_kind"));
    let member_grant_columns = table_columns(&connection, "member_login_grants").unwrap();
    assert!(member_grant_columns.contains("grant_hash"));
    assert!(member_grant_columns.contains("membership_id"));
    assert!(member_grant_columns.contains("expires_at"));
    assert!(member_grant_columns.contains("used_at"));
    let device_columns = table_columns(&connection, "devices").unwrap();
    assert!(device_columns.contains("device_name_key"));
    let rename_request_columns = table_columns(&connection, "member_rename_requests").unwrap();
    assert!(rename_request_columns.contains("requested_display_name_key"));
    assert!(rename_request_columns.contains("expires_at"));
    assert_eq!(
    connection
        .query_row(
            "SELECT \"notnull\" FROM pragma_table_info('memberships') WHERE name = 'display_name'",
            [],
            |row| row.get::<_, i64>(0),
        )
        .unwrap(),
    1
);
    assert!(connection
        .prepare("SELECT 1 FROM membership_aliases")
        .is_err());
}
#[test]
fn nonempty_unsupported_schema_versions_fail_without_mutation() {
    for version in [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12] {
        let directory = TempDir::new().unwrap();
        let database_path = directory.path().join("lezi.db");
        let connection = Connection::open(&database_path).unwrap();
        connection
            .execute_batch(&format!(
                "
            PRAGMA user_version = {version};
            CREATE TABLE sentinel(value TEXT NOT NULL);
            INSERT INTO sentinel(value) VALUES ('preserve-me');
            "
            ))
            .unwrap();
        drop(connection);

        let before = fs::read(&database_path).unwrap();
        let before_entries = fs::read_dir(directory.path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect::<BTreeSet<_>>();
        let error = Store::open(&database_path)
            .err()
            .expect("unsupported schema version was accepted");
        assert!(
            matches!(
                &error,
                StoreError::UnsupportedSchemaVersion { found, supported }
                    if *found == version && *supported == DATABASE_SCHEMA_VERSION
            ),
            "unexpected rejection for schema v{version}: {error}"
        );
        assert_eq!(
            fs::read(&database_path).unwrap(),
            before,
            "mutated schema v{version}"
        );
        assert_eq!(
            fs::read_dir(directory.path())
                .unwrap()
                .map(|entry| entry.unwrap().file_name())
                .collect::<BTreeSet<_>>(),
            before_entries,
            "created sidecars for schema v{version}"
        );
    }
}
#[test]
fn current_version_with_wrong_shape_fails_without_mutation() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    let connection = Connection::open(&database_path).unwrap();
    connection
        .execute_batch(
            "
        PRAGMA user_version = 11;
        CREATE TABLE families(id TEXT PRIMARY KEY);
        INSERT INTO families(id) VALUES ('preserve-me');
        ",
        )
        .unwrap();
    drop(connection);

    let before = fs::read(&database_path).unwrap();
    let before_entries = fs::read_dir(directory.path())
        .unwrap()
        .map(|entry| entry.unwrap().file_name())
        .collect::<BTreeSet<_>>();
    assert!(matches!(
        Store::open(&database_path).err(),
        Some(StoreError::IncompatibleSchema { supported })
            if supported == DATABASE_SCHEMA_VERSION
    ));
    assert_eq!(fs::read(&database_path).unwrap(), before);
    assert_eq!(
        fs::read_dir(directory.path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect::<BTreeSet<_>>(),
        before_entries
    );
}
#[test]
fn current_version_with_nullable_membership_name_fails_without_mutation() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    let connection = Connection::open(&database_path).unwrap();
    let nullable_membership_schema =
        CURRENT_SCHEMA_SQL.replace("display_name TEXT NOT NULL", "display_name TEXT");
    connection
        .execute_batch(&nullable_membership_schema)
        .unwrap();
    connection
        .pragma_update(None, "user_version", DATABASE_SCHEMA_VERSION)
        .unwrap();
    drop(connection);

    let before = fs::read(&database_path).unwrap();
    let before_entries = fs::read_dir(directory.path())
        .unwrap()
        .map(|entry| entry.unwrap().file_name())
        .collect::<BTreeSet<_>>();
    assert!(matches!(
        Store::open(&database_path).err(),
        Some(StoreError::IncompatibleSchema { supported })
            if supported == DATABASE_SCHEMA_VERSION
    ));
    assert_eq!(fs::read(&database_path).unwrap(), before);
    assert_eq!(
        fs::read_dir(directory.path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect::<BTreeSet<_>>(),
        before_entries
    );
}
