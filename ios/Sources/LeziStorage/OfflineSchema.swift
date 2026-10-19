import Foundation

/// Native SQLite versions only. These numbers are unrelated to payload schema 2,
/// Android Room versions, and the family server's schema/wire versions.
enum OfflineSchema {
    static let version = 2
    static let applicationID = "1279613513"
    enum MigrationFailurePoint { case afterCopy, beforeCommit }

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
                if failMigrationAt == .beforeCommit { throw OfflineStoreError.injectedFailure }
            case "2":
                guard application == applicationID else { throw OfflineStoreError.incompatibleDatabase }
            default:
                throw OfflineStoreError.incompatibleDatabase
            }
        }
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
