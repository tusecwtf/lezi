import Foundation

/// Native SQLite versions only. These numbers are unrelated to payload schema 2,
/// Android Room versions, and the family server's schema/wire versions.
enum OfflineSchema {
    static let version = 5
    static let applicationID = "1279613513"
    enum MigrationFailurePoint { case afterCopy, beforeCommit, afterSleepSchema, afterCustomSchema, afterBabyProfileSchema }

    static func prepare(_ db: SQLiteConnection, failMigrationAt: MigrationFailurePoint? = nil) throws {
        try db.transaction {
            let version = try db.query("PRAGMA user_version").first?.first ?? nil
            let application = try db.query("PRAGMA application_id").first?.first ?? nil
            switch version {
            case "0":
                guard application == "0", try db.query("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").isEmpty else {
                    throw OfflineStoreError.incompatibleDatabase
                }
                try db.execute("CREATE TABLE local_identity (id INTEGER PRIMARY KEY CHECK(id=1), actor_uuid TEXT NOT NULL, generation TEXT NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('offline','blocked')), selection_revision INTEGER NOT NULL, selected_baby TEXT)")
                try db.execute("CREATE TABLE babies (uuid TEXT PRIMARY KEY, nickname TEXT NOT NULL, birthday TEXT NOT NULL, updated_at INTEGER NOT NULL, sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN (0,1)), deleted_at INTEGER)")
                try db.execute("CREATE TABLE baby_preferences (baby_uuid TEXT PRIMARY KEY REFERENCES babies(uuid), theme_color INTEGER NOT NULL, sort_order INTEGER NOT NULL)")
                try createRecords(db)
                try db.execute("INSERT INTO local_identity VALUES (1, ?, ?, 'offline', 0, NULL)", [.text(UUID().uuidString), .text(UUID().uuidString)])
                try db.execute("PRAGMA application_id=1279613513")
                try db.execute("PRAGMA user_version=2")
            case "1":
                guard application == applicationID else { throw OfflineStoreError.incompatibleDatabase }
                try createRecords(db)
                // The old amount column is a constrained integer. Copy text and all
                // identities/revisions verbatim; no re-confirmation or dirty reset.
                try db.execute("""
                    INSERT INTO records (uuid,baby_uuid,type,timestamp,end_timestamp,note,payload_json,payload_schema_version,updated_at,sync_dirty,deleted_at)
                    SELECT uuid,baby_uuid,'pee',timestamp,NULL,note,'{"pee_amount":' || amount || '}',2,updated_at,sync_dirty,deleted_at FROM pee_records
                    """)
                if failMigrationAt == .afterCopy { throw OfflineStoreError.injectedFailure }
                guard try db.query("SELECT count(*) FROM records") == db.query("SELECT count(*) FROM pee_records"),
                      try db.query("PRAGMA foreign_key_check").isEmpty else { throw OfflineStoreError.incompatibleDatabase }
                try db.execute("DROP TABLE pee_records")
                try db.execute("PRAGMA user_version=2")
            case "2", "3", "4", "5":
                guard application == applicationID else { throw OfflineStoreError.incompatibleDatabase }
            default:
                throw OfflineStoreError.incompatibleDatabase
            }
            if version != "3" && version != "4" && version != "5" {
                try addSleepSchema(db)
                if failMigrationAt == .afterSleepSchema { throw OfflineStoreError.injectedFailure }
                try db.execute("PRAGMA user_version=3")
            }
            if version != "4" && version != "5" {
                guard try db.query("SELECT uuid FROM records WHERE type='custom' LIMIT 1").isEmpty else {
                    throw OfflineStoreError.unsupportedCustomHistory
                }
                try db.execute("CREATE TABLE custom_definitions (uuid TEXT PRIMARY KEY,name TEXT NOT NULL,icon_slot INTEGER NOT NULL CHECK(icon_slot BETWEEN 0 AND 7),updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER)")
                // BINARY equality matches Kotlin String equality, including case and
                // canonically-equivalent but differently encoded Unicode names.
                try db.execute("CREATE UNIQUE INDEX custom_live_name ON custom_definitions(name COLLATE BINARY) WHERE deleted_at IS NULL")
                try db.execute("CREATE TABLE custom_preferences (definition_uuid TEXT PRIMARY KEY REFERENCES custom_definitions(uuid),sort_order INTEGER NOT NULL,creation_ordinal INTEGER NOT NULL UNIQUE)")
                try db.execute("ALTER TABLE records ADD COLUMN custom_definition_uuid TEXT REFERENCES custom_definitions(uuid)")
                if failMigrationAt == .afterCustomSchema { throw OfflineStoreError.injectedFailure }
                try db.execute("PRAGMA user_version=4")
            }
            if version != "5" {
                // Additive native-only fields. Old roots keep their exact IDs,
                // revisions, dirty state and tombstones; no records are rewritten.
                try db.execute("ALTER TABLE babies ADD COLUMN sex TEXT")
                try db.execute("ALTER TABLE babies ADD COLUMN birth_weight_grams INTEGER")
                if failMigrationAt == .afterBabyProfileSchema { throw OfflineStoreError.injectedFailure }
                try db.execute("PRAGMA user_version=5")
            }
            if failMigrationAt == .beforeCommit { throw OfflineStoreError.injectedFailure }
        }
    }

    private static func addSleepSchema(_ db: SQLiteConnection) throws {
        // Published native v2 never admitted sleep writes. Do not invent history
        // or apply the different wire legacy-migration UUID rule to unknown rows.
        guard try db.query("SELECT uuid FROM records WHERE type='sleep' LIMIT 1").isEmpty else {
            throw OfflineStoreError.unsupportedSleepHistory
        }
        try db.execute("ALTER TABLE records ADD COLUMN effective_wake_observation_client_uuid TEXT")
        try db.execute("""
            CREATE TABLE wake_observations (
            uuid TEXT PRIMARY KEY, sleep_record_client_uuid TEXT NOT NULL REFERENCES records(uuid),
            wake_timestamp INTEGER NOT NULL, note TEXT, withdrawn INTEGER NOT NULL CHECK(withdrawn IN (0,1)),
            local_observer_uuid TEXT NOT NULL, updated_at INTEGER NOT NULL,
            sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN (0,1)), deleted_at INTEGER)
            """)
        try db.execute("CREATE INDEX wake_by_sleep ON wake_observations(sleep_record_client_uuid,wake_timestamp,uuid)")
    }

    private static func createRecords(_ db: SQLiteConnection) throws {
        try db.execute("""
            CREATE TABLE records (uuid TEXT PRIMARY KEY, baby_uuid TEXT NOT NULL REFERENCES babies(uuid),
            type TEXT NOT NULL, timestamp INTEGER NOT NULL, end_timestamp INTEGER, note TEXT,
            payload_json TEXT NOT NULL, payload_schema_version INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN (0,1)), deleted_at INTEGER)
            """)
        try db.execute("CREATE INDEX record_timeline ON records(baby_uuid,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        try db.execute("CREATE INDEX record_type_timeline ON records(baby_uuid,type,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
    }
}
