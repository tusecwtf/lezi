import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

final class OfflineRecentMilkTests: XCTestCase, @unchecked Sendable {
    func testCrossPageDistinctValuesSkipOpaqueInvalidDeletedAndOtherBabyOrType() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("milk-\(UUID()).sqlite").path
        defer { try? FileManager.default.removeItem(atPath: file) }
        let store = try OfflineStore(path: file, clock: { 10_000 })
        let baby = try await store.createBaby(nickname: "一宝", birthday: "2026-01-01")
        let other = try await store.createBaby(nickname: "二宝", birthday: "2026-01-01")
        _ = try await store.selectBaby(other.clientUUID)
        let db = try SQLiteConnection(path: file)
        func insert(_ time: Int64, _ json: String, schema: Int64 = 2, type: String = "formula", babyID: UUID? = nil, deleted: Bool = false, id: UUID = UUID()) throws {
            try db.execute("INSERT INTO records (uuid,baby_uuid,type,timestamp,payload_json,payload_schema_version,updated_at,sync_dirty,deleted_at) VALUES (?,?,?,?,?,?,1,1,?)", [.text(id.uuidString), .text((babyID ?? baby.clientUUID).uuidString), .text(type), .int(time), .text(json), .int(schema), deleted ? .int(2) : .null])
        }
        try db.transaction {
            // Entire skipped page, then >100 repeated valid values, then older choices.
            for time in 300...370 { try insert(Int64(time), "{\"amount_ml\":999}", schema: 99) }
            for time in 100...230 { try insert(Int64(time), "{\"amount_ml\":120}") }
            try insert(99, "{\"amount_ml\":0}")
            try insert(98, "{\"amount_ml\":1000}")
            try insert(97, "not JSON")
            try insert(96, "{\"amount_ml\":150}")
            try insert(95, "{\"amount_ml\":120}")
            try insert(94, "{\"amount_ml\":180}")
            try insert(999, "{\"amount_ml\":888}", deleted: true)
            try insert(999, "{\"amount_ml\":777}", babyID: other.clientUUID)
            try insert(999, "{\"amount_ml\":160}", type: "pumped_feed")
            try insert(999, "{\"amount_ml\":190}", type: "pump_express")
        }
        let amounts = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .formula)
        XCTAssertEqual(amounts, [120, 150, 180])
        let pumped = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .pumpedFeed)
        XCTAssertEqual(pumped, [160])
        let expressed = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .pumpExpress)
        XCTAssertEqual(expressed, [190])
        let snapshot = try await store.recentMilkAmounts(baby: baby.clientUUID)
        XCTAssertEqual(snapshot, [.formula: [120, 150, 180], .pumpedFeed: [160], .pumpExpress: [190]])
        let otherSnapshot = try await store.recentMilkAmounts(baby: other.clientUUID)
        XCTAssertEqual(otherSnapshot, [.formula: [777], .pumpedFeed: [], .pumpExpress: []])
        let zero = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .formula, limit: 0)
        XCTAssertTrue(zero.isEmpty)
        let two = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .formula, limit: 2)
        XCTAssertEqual(two, [120, 150])
        for cursor in [false, true] {
            var values: [SQLiteConnection.Value] = [.text(baby.clientUUID.uuidString), .text("formula")]
            if cursor { values += [.int(200), .text(UUID().uuidString)] }
            values.append(.int(64))
            let plan = try db.query("EXPLAIN QUERY PLAN " + OfflineStore.recentMilkPageSQL(hasCursor: cursor), values).flatMap { $0.compactMap { $0 } }.joined(separator: " ")
            XCTAssertTrue(plan.contains("record_type_timeline")); XCTAssertFalse(plan.contains("TEMP B-TREE"))
        }
        let reopened = try OfflineStore(path: file)
        let restarted = try await reopened.recentMilkAmounts(baby: baby.clientUUID, type: .formula)
        XCTAssertEqual(restarted, amounts)
    }
    func testSameTimestampKeysetAndInvalidRequests() async throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("milk-\(UUID()).sqlite").path
        defer { try? FileManager.default.removeItem(atPath: file) }
        let store = try OfflineStore(path: file)
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        let db = try SQLiteConnection(path: file)
        try db.transaction {
            for index in 1...130 {
                let id = String(format: "00000000-0000-4000-8000-%012d", index)
                let amount = index == 1 ? 180 : index == 2 ? 150 : 120
                try db.execute("INSERT INTO records (uuid,baby_uuid,type,timestamp,payload_json,payload_schema_version,updated_at,sync_dirty) VALUES (?,?,'formula',100,?,2,1,1)", [.text(id), .text(baby.clientUUID.uuidString), .text("{\"amount_ml\":\(amount)}")])
            }
        }
        let values = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .formula)
        XCTAssertEqual(values, [120, 150, 180])
        for limit in [-1, 4] {
            do { _ = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .formula, limit: limit); XCTFail() }
            catch { XCTAssertEqual(error as? OfflineStoreError, .invalidPageLimit) }
        }
        do { _ = try await store.recentMilkAmounts(baby: baby.clientUUID, type: .nursing); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .invalidDraft) }
        do { _ = try await store.recentMilkAmounts(baby: UUID(), type: .formula); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .missingBaby) }
    }
}
