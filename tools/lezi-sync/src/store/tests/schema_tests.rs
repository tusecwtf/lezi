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
    for version in [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 13] {
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
        PRAGMA user_version = 12;
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
fn fresh_schema_v12_has_causal_tables_and_wake_observation_entity_type() {
    let directory = TempDir::new().unwrap();
    let database_path = directory.path().join("lezi.db");
    fs::File::create(&database_path).unwrap();
    let _store = Store::open(&database_path).unwrap();
    let connection = Connection::open(database_path).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        DATABASE_SCHEMA_VERSION
    );
    for table in [
        "entity_versions",
        "entity_version_parents",
        "entity_version_media",
        "entity_stable_heads",
        "mutation_receipts",
        "conflicts",
        "conflict_branches",
        "conflict_resolutions",
        "causal_media_staging",
        "source_relations",
        "source_relation_mutation_receipts",
        "source_relation_record_eligibility",
        "source_relation_members",
        "source_relation_declarations",
    ] {
        let n: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?1",
                rusqlite::params![table],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(n, 1, "missing table {table}");
    }
    // entities CHECK includes wake_observation (insert of allowed type must succeed shape-wise).
    connection
        .execute(
            "
            INSERT INTO families(id, created_at) VALUES ('fam', 1);
            INSERT INTO entities(
                family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
            ) VALUES (
                'fam', 'wake_observation', '11111111-1111-1111-1111-111111111111',
                1, NULL, '{}', 1
            );
            ",
            [],
        )
        .unwrap();
    // Mutation receipt PK enforces family/principal/atomic-root uniqueness.
    connection
        .execute(
            "
            INSERT INTO mutation_receipts(
                family_id, membership_id, entity_type, client_uuid, mutation_id,
                content_hash, status, receipt_json, created_at
            ) VALUES (
                'fam', 'm1', 'record', '11111111-1111-1111-1111-111111111111',
                '22222222-2222-2222-2222-222222222222', 'hash', 'accepted', '{}', 1
            );
            ",
            [],
        )
        .unwrap();
    let err = connection
        .execute(
            "
            INSERT INTO mutation_receipts(
                family_id, membership_id, entity_type, client_uuid, mutation_id,
                content_hash, status, receipt_json, created_at
            ) VALUES (
                'fam', 'm1', 'record', '11111111-1111-1111-1111-111111111111',
                '22222222-2222-2222-2222-222222222222', 'other-hash', 'merged', '{}', 2
            );
            ",
            [],
        )
        .unwrap_err();
    assert!(
        err.to_string().contains("UNIQUE") || err.to_string().contains("unique"),
        "expected unique violation, got {err}"
    );
    // source_relations forbid media_retained != 1
    let err = connection
        .execute(
            "
            INSERT INTO source_relations(
                family_id, relation_id, display_client_uuid, media_retained,
                reason, mutation_id, created_by_membership_id, created_at
            ) VALUES (
                'fam', 'rel', '11111111-1111-1111-1111-111111111111', 0,
                'author_declare', 'm', 'm1', 1
            );
            ",
            [],
        )
        .unwrap_err();
    assert!(
        err.to_string().to_lowercase().contains("check")
            || err.to_string().to_lowercase().contains("constraint"),
        "expected check failure for media_retained, got {err}"
    );

    connection
        .execute_batch(
            "INSERT INTO source_relations(
                 family_id, relation_id, display_client_uuid, media_retained,
                 reason, mutation_id, created_by_membership_id, created_at
             ) VALUES (
                 'fam', 'rel-active', 'record-display', 1,
                 'author_declare', 'mutation-active', 'm1', 1
             );
             INSERT INTO source_relation_members(
                 family_id, relation_id, record_client_uuid, role
             ) VALUES ('fam', 'rel-active', 'record-display', 'display');
             INSERT INTO source_relations(
                 family_id, relation_id, display_client_uuid, media_retained,
                 reason, mutation_id, created_by_membership_id, created_at
             ) VALUES (
                 'fam', 'rel-other', 'record-other', 1,
                 'owner_group_resolve', 'mutation-other', 'm1', 2
             );",
        )
        .unwrap();
    let duplicate_membership = connection
        .execute(
            "INSERT INTO source_relation_members(
                 family_id, relation_id, record_client_uuid, role
             ) VALUES ('fam', 'rel-other', 'record-display', 'source')",
            [],
        )
        .unwrap_err();
    assert!(
        duplicate_membership
            .to_string()
            .to_lowercase()
            .contains("unique"),
        "one live record must have at most one canonical relation role: {duplicate_membership}"
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
