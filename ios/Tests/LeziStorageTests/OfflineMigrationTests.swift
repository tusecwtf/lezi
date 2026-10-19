import Foundation
import XCTest
@testable import LeziStorage

final class OfflineMigrationTests: XCTestCase, @unchecked Sendable {
    private func path() -> String { FileManager.default.temporaryDirectory.appendingPathComponent("lezi-migrate-\(UUID()).sqlite").path }
    // Frozen schema from published native v1, not built by the current schema code.
    private func seedV1(_ db: SQLiteConnection, blocked: Bool = false) throws -> UUID {
        try db.execute("CREATE TABLE local_identity (id INTEGER PRIMARY KEY CHECK(id=1), actor_uuid TEXT NOT NULL, generation TEXT NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('offline','blocked')), selection_revision INTEGER NOT NULL, selected_baby TEXT)")
        try db.execute("CREATE TABLE babies (uuid TEXT PRIMARY KEY, nickname TEXT NOT NULL, birthday TEXT NOT NULL, updated_at INTEGER NOT NULL, sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN (0,1)), deleted_at INTEGER)")
        try db.execute("CREATE TABLE baby_preferences (baby_uuid TEXT PRIMARY KEY REFERENCES babies(uuid), theme_color INTEGER NOT NULL, sort_order INTEGER NOT NULL)")
        try db.execute("CREATE TABLE pee_records (uuid TEXT PRIMARY KEY, baby_uuid TEXT NOT NULL REFERENCES babies(uuid), timestamp INTEGER NOT NULL, note TEXT, amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 3), updated_at INTEGER NOT NULL, sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN (0,1)), deleted_at INTEGER)")
        try db.execute("CREATE INDEX pee_timeline ON pee_records(baby_uuid,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        let baby = UUID()
        try db.execute("INSERT INTO local_identity VALUES (1,?,?,?,7,?)", [.text(UUID().uuidString), .text(UUID().uuidString), .text(blocked ? "blocked" : "offline"), .text(baby.uuidString)])
        try db.execute("INSERT INTO babies VALUES (?,'宝宝','2026-01-01',70,0,NULL)", [.text(baby.uuidString)])
        try db.execute("INSERT INTO baby_preferences VALUES (?,4278221742,9)", [.text(baby.uuidString)])
        for index in 1...3 {
            try db.execute("INSERT INTO pee_records VALUES (?,?,?,?,?,?,?,?)", [.text(UUID().uuidString), .text(baby.uuidString), .int(Int64(index)), index == 1 ? .null : .text("备注\0尾🌱"), .int(Int64(index)), .int(Int64(100 + index)), .int(Int64(index % 2)), index == 3 ? .int(103) : .null])
        }
        try db.execute("PRAGMA application_id=1279613513")
        try db.execute("PRAGMA user_version=1")
        return baby
    }
    func testV1MigrationPreservesEveryFactAndIdentityAndReopensIdempotently() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let db = try SQLiteConnection(path: file)
        let baby = try seedV1(db)
        let identity = try db.query("SELECT * FROM local_identity")
        let babies = try db.query("SELECT * FROM babies")
        let preferences = try db.query("SELECT * FROM baby_preferences")
        let old = try db.query("SELECT uuid,baby_uuid,timestamp,note,amount,updated_at,sync_dirty,deleted_at FROM pee_records ORDER BY uuid")
        let store = try OfflineStore(path: file)
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "4")
        XCTAssertEqual(try db.query("SELECT * FROM local_identity"), identity)
        XCTAssertEqual(try db.query("SELECT * FROM babies"), babies)
        XCTAssertEqual(try db.query("SELECT * FROM baby_preferences"), preferences)
        let rows = try db.query("SELECT uuid,baby_uuid,timestamp,note,payload_json,updated_at,sync_dirty,deleted_at,type,payload_schema_version,end_timestamp FROM records ORDER BY uuid")
        for (before, after) in zip(old, rows) {
            XCTAssertEqual(Array(after[0...3]), Array(before[0...3]))
            XCTAssertEqual(after[4], "{\"pee_amount\":\(before[4]!)}")
            XCTAssertEqual(Array(after[5...7]), Array(before[5...7]))
            XCTAssertEqual(after[8], "pee"); XCTAssertEqual(after[9], "2"); XCTAssertNil(after[10])
        }
        XCTAssertEqual(rows.count, 3)
        XCTAssertTrue(try db.query("SELECT name FROM sqlite_master WHERE name='pee_records'").isEmpty)
        let page = try await store.records(baby: baby); XCTAssertEqual(page.records.count, 2)
        let reopened = try OfflineStore(path: file)
        let recovered = try await reopened.records(baby: baby); XCTAssertEqual(page, recovered)
        XCTAssertEqual(try db.query("SELECT count(*) FROM records")[0][0], "3")
    }
    func testMigrationFailureRestoresV1TablesRowsAndVersionThenRetries() throws {
        for point in [OfflineSchema.MigrationFailurePoint.afterCopy, .beforeCommit, .afterSleepSchema, .afterCustomSchema] {
            let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
            let db = try SQLiteConnection(path: file)
            _ = try seedV1(db)
            let original = try db.query("SELECT * FROM pee_records ORDER BY uuid")
            let identity = try db.query("SELECT * FROM local_identity")
            XCTAssertThrowsError(try OfflineSchema.prepare(db, failMigrationAt: point)) { XCTAssertEqual($0 as? OfflineStoreError, .injectedFailure) }
            XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "1")
            XCTAssertEqual(try db.query("SELECT * FROM pee_records ORDER BY uuid"), original)
            XCTAssertEqual(try db.query("SELECT * FROM local_identity"), identity)
            XCTAssertTrue(try db.query("SELECT name FROM sqlite_master WHERE name='records'").isEmpty)
            try OfflineSchema.prepare(db)
            XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "4")
        }
    }
    func testBlockedIdentityRemainsBlockedAfterMigration() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let db = try SQLiteConnection(path: file); _ = try seedV1(db, blocked: true)
        let store = try OfflineStore(path: file)
        do { _ = try await store.createBaby(nickname: "二宝", birthday: "2026-01-01"); XCTFail("Must stay blocked") }
        catch { XCTAssertEqual(error as? OfflineStoreError, .offlineOnly) }
    }
    func testV2IncrementPreservesOpaqueTombstonesAndRollsBackDDL() throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let db = try SQLiteConnection(path: file); let baby = try seedV1(db)
        // Frozen published v2 table shape, independent of the schema under test.
        try db.execute("CREATE TABLE records (uuid TEXT PRIMARY KEY,baby_uuid TEXT NOT NULL REFERENCES babies(uuid),type TEXT NOT NULL,timestamp INTEGER NOT NULL,end_timestamp INTEGER,note TEXT,payload_json TEXT NOT NULL,payload_schema_version INTEGER NOT NULL,updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER)")
        try db.execute("CREATE INDEX record_timeline ON records(baby_uuid,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        try db.execute("CREATE INDEX record_type_timeline ON records(baby_uuid,type,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        try db.execute("INSERT INTO records VALUES (?,?,'future',7,9,'opaque',' {future raw} ',99,42,0,42)", [.text(UUID().uuidString), .text(baby.uuidString)])
        try db.execute("DROP TABLE pee_records"); try db.execute("PRAGMA user_version=2")
        let old = try db.query("SELECT * FROM records")
        let identity = try db.query("SELECT * FROM local_identity")
        XCTAssertThrowsError(try OfflineSchema.prepare(db, failMigrationAt: .afterSleepSchema))
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "2")
        XCTAssertEqual(try db.query("SELECT * FROM records"), old)
        XCTAssertTrue(try db.query("SELECT name FROM sqlite_master WHERE name='wake_observations'").isEmpty)
        try db.execute("UPDATE records SET type='sleep'")
        XCTAssertThrowsError(try OfflineSchema.prepare(db)) { XCTAssertEqual($0 as? OfflineStoreError, .unsupportedSleepHistory) }
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "2")
        try db.execute("UPDATE records SET type='future'")
        try OfflineSchema.prepare(db); try OfflineSchema.prepare(db)
        XCTAssertEqual(try db.query("SELECT * FROM local_identity"), identity)
        let migrated = try db.query("SELECT * FROM records")
        XCTAssertEqual(Array(migrated[0].dropLast(2)), old[0]); XCTAssertNil(migrated[0].last!)
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "4")
    }
    func testFrozenV3CustomMigrationPreservesSleepWakeOpaqueAndRollsBack() throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let db = try SQLiteConnection(path: file); let baby = try seedV1(db)
        try db.execute("CREATE TABLE records (uuid TEXT PRIMARY KEY,baby_uuid TEXT NOT NULL REFERENCES babies(uuid),type TEXT NOT NULL,timestamp INTEGER NOT NULL,end_timestamp INTEGER,note TEXT,payload_json TEXT NOT NULL,payload_schema_version INTEGER NOT NULL,updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER,effective_wake_observation_client_uuid TEXT)")
        try db.execute("CREATE INDEX record_timeline ON records(baby_uuid,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        try db.execute("CREATE INDEX record_type_timeline ON records(baby_uuid,type,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        try db.execute("CREATE TABLE wake_observations (uuid TEXT PRIMARY KEY,sleep_record_client_uuid TEXT NOT NULL REFERENCES records(uuid),wake_timestamp INTEGER NOT NULL,note TEXT,withdrawn INTEGER NOT NULL CHECK(withdrawn IN(0,1)),local_observer_uuid TEXT NOT NULL,updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER)")
        try db.execute("CREATE INDEX wake_by_sleep ON wake_observations(sleep_record_client_uuid,wake_timestamp,uuid)")
        let sleep = UUID(); let wake = UUID()
        try db.execute("INSERT INTO records VALUES (?,?,'sleep',10,NULL,?, '{\"is_nap\":false,\"anomaly_flag\":false}',2,42,0,43,?)", [.text(sleep.uuidString), .text(baby.uuidString), .text("睡\0眠"), .text(wake.uuidString)])
        try db.execute("INSERT INTO records VALUES (?,?,'future',20,NULL,NULL,'opaque bytes',99,44,1,NULL,NULL)", [.text(UUID().uuidString), .text(baby.uuidString)])
        try db.execute("INSERT INTO wake_observations VALUES (?,?,30,?,1,?,45,1,46)", [.text(wake.uuidString), .text(sleep.uuidString), .text("醒\0来"), .text(UUID().uuidString)])
        try db.execute("DROP TABLE pee_records"); try db.execute("PRAGMA user_version=3")
        let records = try db.query("SELECT * FROM records ORDER BY uuid")
        let wakes = try db.query("SELECT * FROM wake_observations")
        let identity = try db.query("SELECT * FROM local_identity")
        let preferences = try db.query("SELECT * FROM baby_preferences")
        XCTAssertThrowsError(try OfflineSchema.prepare(db, failMigrationAt: .afterCustomSchema))
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "3")
        XCTAssertEqual(try db.query("SELECT * FROM records ORDER BY uuid"), records)
        XCTAssertEqual(try db.query("SELECT * FROM wake_observations"), wakes)
        XCTAssertTrue(try db.query("SELECT name FROM sqlite_master WHERE name='custom_definitions'").isEmpty)
        try db.execute("UPDATE records SET type='custom' WHERE type='future'")
        XCTAssertThrowsError(try OfflineSchema.prepare(db)) { XCTAssertEqual($0 as? OfflineStoreError, .unsupportedCustomHistory) }
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "3")
        try db.execute("UPDATE records SET type='future' WHERE type='custom'")
        try OfflineSchema.prepare(db); try OfflineSchema.prepare(db)
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "4")
        XCTAssertEqual(try db.query("SELECT * FROM records ORDER BY uuid").map { Array($0.dropLast()) }, records)
        XCTAssertEqual(try db.query("SELECT * FROM wake_observations"), wakes)
        XCTAssertEqual(try db.query("SELECT * FROM local_identity"), identity)
        XCTAssertEqual(try db.query("SELECT * FROM baby_preferences"), preferences)
    }
    func testUnknownNativeVersionsAndForeignV1AreRejectedWithoutReset() throws {
        for version in [-1, 5, 29] {
            let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
            let db = try SQLiteConnection(path: file); _ = try seedV1(db)
            try db.execute("PRAGMA user_version=\(version)")
            XCTAssertThrowsError(try OfflineStore(path: file)) { XCTAssertEqual($0 as? OfflineStoreError, .incompatibleDatabase) }
            XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], String(version))
            XCTAssertEqual(try db.query("SELECT count(*) FROM pee_records")[0][0], "3")
        }
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let db = try SQLiteConnection(path: file); _ = try seedV1(db)
        try db.execute("PRAGMA application_id=1")
        XCTAssertThrowsError(try OfflineStore(path: file)) { XCTAssertEqual($0 as? OfflineStoreError, .incompatibleDatabase) }
    }
}
