import Foundation
import XCTest
@testable import LeziStorage

final class OfflineBabyProfileMigrationTests: XCTestCase, @unchecked Sendable {
    private func path() -> String {
        FileManager.default.temporaryDirectory.appendingPathComponent("lezi-v4-profile-\(UUID()).sqlite").path
    }

    // Frozen native v4, deliberately not created by the migration under test.
    private func seedV4(_ db: SQLiteConnection) throws -> UUID {
        try db.execute("CREATE TABLE local_identity (id INTEGER PRIMARY KEY CHECK(id=1),actor_uuid TEXT NOT NULL,generation TEXT NOT NULL,mode TEXT NOT NULL CHECK(mode IN('offline','blocked')),selection_revision INTEGER NOT NULL,selected_baby TEXT)")
        try db.execute("CREATE TABLE babies (uuid TEXT PRIMARY KEY,nickname TEXT NOT NULL,birthday TEXT NOT NULL,updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER)")
        try db.execute("CREATE TABLE baby_preferences (baby_uuid TEXT PRIMARY KEY REFERENCES babies(uuid),theme_color INTEGER NOT NULL,sort_order INTEGER NOT NULL)")
        try db.execute("CREATE TABLE custom_definitions (uuid TEXT PRIMARY KEY,name TEXT NOT NULL,icon_slot INTEGER NOT NULL CHECK(icon_slot BETWEEN 0 AND 7),updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER)")
        try db.execute("CREATE UNIQUE INDEX custom_live_name ON custom_definitions(name COLLATE BINARY) WHERE deleted_at IS NULL")
        try db.execute("CREATE TABLE custom_preferences (definition_uuid TEXT PRIMARY KEY REFERENCES custom_definitions(uuid),sort_order INTEGER NOT NULL,creation_ordinal INTEGER NOT NULL UNIQUE)")
        try db.execute("CREATE TABLE records (uuid TEXT PRIMARY KEY,baby_uuid TEXT NOT NULL REFERENCES babies(uuid),type TEXT NOT NULL,timestamp INTEGER NOT NULL,end_timestamp INTEGER,note TEXT,payload_json TEXT NOT NULL,payload_schema_version INTEGER NOT NULL,updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER,effective_wake_observation_client_uuid TEXT,custom_definition_uuid TEXT REFERENCES custom_definitions(uuid))")
        try db.execute("CREATE INDEX record_timeline ON records(baby_uuid,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        try db.execute("CREATE INDEX record_type_timeline ON records(baby_uuid,type,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
        try db.execute("CREATE TABLE wake_observations (uuid TEXT PRIMARY KEY,sleep_record_client_uuid TEXT NOT NULL REFERENCES records(uuid),wake_timestamp INTEGER NOT NULL,note TEXT,withdrawn INTEGER NOT NULL CHECK(withdrawn IN(0,1)),local_observer_uuid TEXT NOT NULL,updated_at INTEGER NOT NULL,sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN(0,1)),deleted_at INTEGER)")
        try db.execute("CREATE INDEX wake_by_sleep ON wake_observations(sleep_record_client_uuid,wake_timestamp,uuid)")
        let baby = UUID(), deletedBaby = UUID(), actor = UUID()
        let custom = UUID(), sleep = UUID(), wake = UUID()
        try db.execute("INSERT INTO local_identity VALUES(1,?,?,'blocked',27,?)",
            [.text(actor.uuidString), .text(UUID().uuidString), .text(baby.uuidString)])
        try db.execute("INSERT INTO babies VALUES(?,?,'2026-01-01',9000,0,NULL)",
            [.text(baby.uuidString), .text("年\0年🌱")])
        try db.execute("INSERT INTO babies VALUES(?,'已删除','2024-02-29',123,1,124)",
            [.text(deletedBaby.uuidString)])
        try db.execute("INSERT INTO baby_preferences VALUES(?,4279383126,-3)", [.text(baby.uuidString)])
        try db.execute("INSERT INTO baby_preferences VALUES(?,4278221742,8)", [.text(deletedBaby.uuidString)])
        try db.execute("INSERT INTO custom_definitions VALUES(?,?,7,456,0,457)", [.text(custom.uuidString), .text("项目\0🌱")])
        try db.execute("INSERT INTO custom_preferences VALUES(?,-2,17)", [.text(custom.uuidString)])
        try db.execute("INSERT INTO records VALUES(?,?,'custom',15,NULL,?,?,99,458,1,459,NULL,?)",
            [.text(UUID().uuidString), .text(baby.uuidString), .text("记\0录"), .text(" { opaque custom \0 } "), .text(custom.uuidString)])
        try db.execute("INSERT INTO records VALUES(?,?,'future',20,21,?,?,99,34,0,NULL,NULL,NULL)",
            [.text(UUID().uuidString), .text(deletedBaby.uuidString), .text("未来🌱"), .text(" { opaque future \0 } ")])
        try db.execute("INSERT INTO records VALUES(?,?,'sleep',30,NULL,?,'{\"is_nap\":false,\"anomaly_flag\":false}',2,60,1,NULL,?,NULL)",
            [.text(sleep.uuidString), .text(baby.uuidString), .text("睡\0眠"), .text(wake.uuidString)])
        try db.execute("INSERT INTO wake_observations VALUES(?,?,50,?,1,?,60,0,61)",
            [.text(wake.uuidString), .text(sleep.uuidString), .text("醒\0来"), .text(actor.uuidString)])
        try db.execute("PRAGMA application_id=1279613513")
        try db.execute("PRAGMA user_version=4")
        return baby
    }

    func testFrozenV4AddsOnlyNullProfileColumnsAndPreservesAllOtherRowsAcrossReopen() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let db = try SQLiteConnection(path: file)
        let id = try seedV4(db)
        let tables = ["local_identity", "baby_preferences", "records", "wake_observations", "custom_definitions", "custom_preferences"]
        let originals = try tables.map { try db.query("SELECT * FROM \($0) ORDER BY 1") }
        let babies = try db.query("SELECT * FROM babies ORDER BY uuid")
        let store = try OfflineStore(path: file)
        XCTAssertEqual(OfflineStorageScope.schemaVersion, 5)
        XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "5")
        XCTAssertEqual(try db.query("PRAGMA application_id")[0][0], "1279613513")
        XCTAssertEqual(try db.query("SELECT uuid,nickname,birthday,updated_at,sync_dirty,deleted_at FROM babies ORDER BY uuid"), babies)
        XCTAssertEqual(try db.query("SELECT sex,birth_weight_grams FROM babies ORDER BY uuid"), [[nil, nil], [nil, nil]])
        for (table, before) in zip(tables, originals) {
            XCTAssertEqual(try db.query("SELECT * FROM \(table) ORDER BY 1"), before, table)
        }
        XCTAssertTrue(try db.query("PRAGMA foreign_key_check").isEmpty)
        let profile = try await store.babyProfile(id)
        XCTAssertEqual(profile?.nickname, "年\0年🌱")
        XCTAssertEqual(profile?.updatedAt, 9_000); XCTAssertEqual(profile?.syncDirty, false)
        XCTAssertEqual(profile?.sortOrder, -3); XCTAssertEqual(profile?.themeColor, 4_279_383_126)
        XCTAssertNil(profile?.sex); XCTAssertNil(profile?.birthWeightGrams)
        for _ in 0..<2 {
            let reopened = try OfflineStore(path: file)
            let recovered = try await reopened.babyProfile(id); XCTAssertEqual(recovered, profile)
        }
        do {
            _ = try await store.createBaby(nickname: "不应创建", birthday: "2026-01-01")
            XCTFail("Migration must not un-block the store")
        } catch {
            XCTAssertEqual(error as? OfflineStoreError, .offlineOnly)
        }
    }

    func testV4DDLAndVersionFullyRollbackAtEveryProfileMigrationFailurePointThenRetry() throws {
        for point in [OfflineSchema.MigrationFailurePoint.afterBabyProfileSchema, .beforeCommit] {
            let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
            let db = try SQLiteConnection(path: file); _ = try seedV4(db)
            let schema = try db.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY name")
            let tables = ["local_identity", "babies", "baby_preferences", "records", "wake_observations", "custom_definitions", "custom_preferences"]
            let originals = try tables.map { try db.query("SELECT * FROM \($0) ORDER BY 1") }
            XCTAssertThrowsError(try OfflineSchema.prepare(db, failMigrationAt: point)) {
                XCTAssertEqual($0 as? OfflineStoreError, .injectedFailure)
            }
            XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "4")
            XCTAssertEqual(try db.query("PRAGMA table_info(babies)").count, 6)
            XCTAssertEqual(try db.query("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY name"), schema)
            for (table, before) in zip(tables, originals) {
                XCTAssertEqual(try db.query("SELECT * FROM \(table) ORDER BY 1"), before, table)
            }
            try OfflineSchema.prepare(db)
            XCTAssertEqual(try db.query("PRAGMA user_version")[0][0], "5")
            XCTAssertEqual(try db.query("PRAGMA table_info(babies)").count, 8)
        }
    }
}
