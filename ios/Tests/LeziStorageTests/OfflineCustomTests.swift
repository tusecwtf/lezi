import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

final class OfflineCustomTests: XCTestCase, @unchecked Sendable {
    private func path() -> String { FileManager.default.temporaryDirectory.appendingPathComponent("custom-\(UUID()).sqlite").path }
    private func setup(_ file: String) async throws -> (OfflineStore, OfflineContext) {
        let store = try OfflineStore(path: file, clock: { 1000 })
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        return (store, try await store.selectBaby(baby.clientUUID))
    }
    private func draft(_ c: OfflineContext, _ definition: UUID, id: UUID = UUID(), title: String = "原始标题") -> RecordConfirmationDraft {
        .init(clientUUID: id, babyClientUUID: c.babyClientUUID,
              item: .parseCatalogKey("custom:\(definition.uuidString.lowercased())")!, timestampMillis: 100,
              payload: .custom(titleSnapshot: title, detail: "内容", iconSlot: 2))
    }
    func testDefinitionRenameDeletePreservesHistoryAndEditableSnapshotAndBinding() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        let def = try await store.createCustomDefinition(name: " 原始标题 ", iconSlot: 2, generation: c.generation)
        let d = draft(c, def.clientUUID)
        let record = try await store.confirmCustomRecord(d, context: c)
        XCTAssertEqual(record.customDefinitionUUID, def.clientUUID)
        XCTAssertNil(record.payloadDocument?.localCustomItemID)
        XCTAssertFalse(record.payloadJSON.contains("custom_item_id"))
        let renamed = try await store.updateCustomDefinition(def.clientUUID, name: "新名字", iconSlot: 5, generation: c.generation, expectedUpdatedAt: def.updatedAt)
        XCTAssertEqual(renamed.updatedAt, 1001)
        let old = try await store.recordDetails(record.clientUUID); XCTAssertEqual(old, record)
        _ = try await store.deleteCustomDefinition(def.clientUUID, generation: c.generation, expectedUpdatedAt: renamed.updatedAt)
        let replay = try await store.confirmCustomRecord(d, context: c); XCTAssertEqual(replay, record)
        let otherBaby = try await store.createBaby(nickname: "二宝", birthday: "2026-01-01")
        _ = try await store.selectBaby(otherBaby.clientUUID)
        let reopened = try OfflineStore(path: file, clock: { 500 })
        let edited = try await reopened.confirmCustomRecord(draft(c, def.clientUUID, id: record.clientUUID, title: "历史编辑"), context: c, expectedUpdatedAt: record.updatedAt)
        XCTAssertEqual(edited.updatedAt, 1001); XCTAssertEqual(edited.babyClientUUID, c.babyClientUUID)
        XCTAssertEqual(edited.payloadDocument?.payload, .custom(titleSnapshot: "历史编辑", detail: "内容", iconSlot: 2))
        do { _ = try await reopened.confirmCustomRecord(draft(c, def.clientUUID), context: c); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .deletedRecord) }
        let newDef = try await reopened.createCustomDefinition(name: "新名字", iconSlot: 0, generation: c.generation)
        do { _ = try await reopened.confirmCustomRecord(draft(c, newDef.clientUUID, id: record.clientUUID), context: c); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .identityConflict) }
        do { _ = try await reopened.confirmCustomRecord(d, context: c, expectedUpdatedAt: record.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .staleEdit) }
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE records SET payload_schema_version=99 WHERE uuid=?", [.text(record.clientUUID.uuidString)])
        do { _ = try await reopened.confirmCustomRecord(d, context: c, expectedUpdatedAt: edited.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .opaqueRecord) }
        _ = try await reopened.deleteRecord(record.clientUUID, context: c)
        let tomb = try await reopened.recordDetails(record.clientUUID)
        XCTAssertEqual(tomb?.payloadJSON, edited.payloadJSON); XCTAssertEqual(tomb?.payloadSchemaVersion, 99)
    }
    func testExactNamesLimitReplayAndLocalOrderNeverDirtyFacts() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        let names = [" A ", "a", "é", "e\u{301}", "\u{85}", String(repeating: "🌱", count: 40), "7", "8", "9", "10"]
        var definitions = [OfflineCustomDefinition]()
        for name in names { definitions.append(try await store.createCustomDefinition(name: name, iconSlot: 0, generation: c.generation)) }
        XCTAssertEqual(definitions.first?.name, "A")
        let replay = try await store.createCustomDefinition(clientUUID: definitions[0].clientUUID, name: "changed", iconSlot: 7, generation: c.generation)
        XCTAssertEqual(replay, definitions[0])
        do { _ = try await store.createCustomDefinition(name: "11", iconSlot: 0, generation: c.generation); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .customDefinitionLimit) }
        do { _ = try await store.createCustomDefinition(name: "\u{1c}A\u{a0}", iconSlot: 0, generation: c.generation); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .duplicateCustomName) }
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE custom_definitions SET sync_dirty=0")
        let before = try db.query("SELECT * FROM custom_definitions ORDER BY uuid")
        try await store.reorderCustomDefinitions(definitions.reversed().map(\.clientUUID), generation: c.generation)
        XCTAssertEqual(try db.query("SELECT * FROM custom_definitions ORDER BY uuid"), before)
        let reopened = try OfflineStore(path: file)
        let ordered = try await reopened.customDefinitions(); XCTAssertEqual(ordered.map(\.clientUUID), definitions.reversed().map(\.clientUUID))
        let unchanged = try await store.updateCustomDefinition(definitions[0].clientUUID, name: "A", iconSlot: 0, generation: c.generation, expectedUpdatedAt: 1000)
        XCTAssertFalse(unchanged.syncDirty); XCTAssertEqual(unchanged.updatedAt, 1000)
    }
    func testConcurrentDuplicateAndActualCommitFailuresRollbackDefinitionAndRecord() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        let second = try OfflineStore(path: file, clock: { 1000 })
        let successes = await withTaskGroup(of: Bool.self) { group in
            for target in [store, second] {
                group.addTask { do { _ = try await target.createCustomDefinition(name: "same", iconSlot: 0, generation: c.generation); return true } catch { return false } }
            }
            var count = 0; for await success in group { if success { count += 1 } }; return count
        }
        XCTAssertEqual(successes, 1)
        let db = try SQLiteConnection(path: file)
        try db.execute("CREATE TABLE commit_parent(id INTEGER PRIMARY KEY)")
        try db.execute("CREATE TABLE commit_guard(id INTEGER REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try db.execute("CREATE TRIGGER fail_custom AFTER INSERT ON custom_preferences BEGIN INSERT INTO commit_guard VALUES (1); END")
        let id = UUID()
        do { _ = try await store.createCustomDefinition(clientUUID: id, name: "next", iconSlot: 0, generation: c.generation); XCTFail() }
        catch { XCTAssertNotNil(error as? OfflineStoreError) }
        let absent = try await store.customDefinition(id); XCTAssertNil(absent)
        XCTAssertTrue(try db.query("SELECT * FROM custom_definitions WHERE uuid=?", [.text(id.uuidString)]).isEmpty)
        try db.execute("DROP TRIGGER fail_custom")
        let def = try await store.createCustomDefinition(clientUUID: id, name: "next", iconSlot: 0, generation: c.generation)
        let d = draft(c, def.clientUUID)
        try db.execute("CREATE TRIGGER fail_record AFTER INSERT ON records BEGIN INSERT INTO commit_guard VALUES (1); END")
        do { _ = try await store.confirmCustomRecord(d, context: c); XCTFail() }
        catch { XCTAssertNotNil(error as? OfflineStoreError) }
        let missing = try await store.recordDetails(d.clientUUID); XCTAssertNil(missing)
        try db.execute("DROP TRIGGER fail_record")
        _ = try await store.confirmCustomRecord(d, context: c)
        let before = try db.query("SELECT * FROM custom_definitions ORDER BY uuid")
        try db.execute("CREATE TRIGGER fail_delete AFTER UPDATE ON custom_definitions BEGIN INSERT INTO commit_guard VALUES (1); END")
        do { _ = try await store.deleteCustomDefinition(id, generation: c.generation, expectedUpdatedAt: def.updatedAt); XCTFail() }
        catch { XCTAssertNotNil(error as? OfflineStoreError) }
        XCTAssertEqual(try db.query("SELECT * FROM custom_definitions ORDER BY uuid"), before)
    }
    func testAdmissionBareIdentityDeletedReplayAndConcurrentCapacity() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        for index in 0..<9 { _ = try await store.createCustomDefinition(name: "item\(index)", iconSlot: index % 8, generation: c.generation) }
        let second = try OfflineStore(path: file)
        let successes = await withTaskGroup(of: Bool.self) { group in
            for (index, target) in [store, second].enumerated() {
                group.addTask { do { _ = try await target.createCustomDefinition(name: "extra\(index)", iconSlot: 0, generation: c.generation); return true } catch { return false } }
            }
            var count = 0; for await success in group { if success { count += 1 } }; return count
        }
        XCTAssertEqual(successes, 1)
        let defs = try await store.customDefinitions(); XCTAssertEqual(defs.count, 10)
        let def = defs[0]
        for (name, icon) in [("\u{1c}\u{a0}", 0), ("valid", -1), ("valid", 8)] {
            do { _ = try await store.createCustomDefinition(name: name, iconSlot: icon, generation: c.generation); XCTFail() }
            catch { XCTAssertEqual(error as? OfflineStoreError, .invalidDraft) }
        }
        let localOnly = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: c.babyClientUUID, item: .parseCatalogKey("custom:1")!, timestampMillis: 100, payload: .custom(titleSnapshot: "unbound"))
        do { _ = try await store.confirmCustomRecord(localOnly, context: c); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .invalidDraft) }
        do { _ = try await store.confirmRecord(draft(c, def.clientUUID), context: c); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .invalidDraft) }
        do { _ = try await store.updateCustomDefinition(def.clientUUID, name: "changed", iconSlot: 0, generation: UUID(), expectedUpdatedAt: def.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .staleContext) }
        _ = try await store.deleteCustomDefinition(def.clientUUID, generation: c.generation, expectedUpdatedAt: def.updatedAt)
        do { _ = try await store.createCustomDefinition(clientUUID: def.clientUUID, name: "revive", iconSlot: 0, generation: c.generation); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .deletedRecord) }
        try await store.blockOfflineWrites()
        do { _ = try await store.createCustomDefinition(name: "offline bypass", iconSlot: 0, generation: c.generation); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .offlineOnly) }
    }

    func testCreationOrderBreaksTiesAcrossGapsReorderTombstonesAndRestart() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        var defs = [OfflineCustomDefinition]()
        // New UUIDs deliberately sort before every existing UUID.
        for index in (5...9).reversed() {
            let id = UUID(uuidString: String(format: "00000000-0000-4000-8000-%012d", index))!
            defs.append(try await store.createCustomDefinition(clientUUID: id, name: "item\(index)", iconSlot: 0, generation: c.generation))
        }
        // Positions 1 and 2 become gaps. live count 3 puts the new item at 3,
        // after old position 3, but before position 4 (not max(sort)+1).
        for index in [1, 2] {
            _ = try await store.deleteCustomDefinition(defs[index].clientUUID, generation: c.generation, expectedUpdatedAt: defs[index].updatedAt)
        }
        let newestID = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
        let newest = try await store.createCustomDefinition(clientUUID: newestID, name: "new", iconSlot: 0, generation: c.generation)
        var ordered = try await store.customDefinitions()
        XCTAssertEqual(ordered.map(\.clientUUID), [defs[0].clientUUID, defs[3].clientUUID, newestID, defs[4].clientUUID])
        XCTAssertEqual(newest.sortOrder, 3)
        let db = try SQLiteConnection(path: file)
        let ordinalRows = try db.query("SELECT definition_uuid,creation_ordinal FROM custom_preferences ORDER BY creation_ordinal")
        let facts = try db.query("SELECT * FROM custom_definitions ORDER BY uuid")
        try await store.reorderCustomDefinitions(ordered.reversed().map(\.clientUUID), generation: c.generation)
        XCTAssertEqual(try db.query("SELECT definition_uuid,creation_ordinal FROM custom_preferences ORDER BY creation_ordinal"), ordinalRows)
        XCTAssertEqual(try db.query("SELECT * FROM custom_definitions ORDER BY uuid"), facts)
        // Tombstone the largest ordinal; a later create must use 7, not reuse 6.
        _ = try await store.deleteCustomDefinition(newestID, generation: c.generation, expectedUpdatedAt: newest.updatedAt)
        let reopened = try OfflineStore(path: file, clock: { 500 })
        let lastID = UUID(uuidString: "00000000-0000-4000-8000-000000000000")!
        _ = try await reopened.createCustomDefinition(clientUUID: lastID, name: "last", iconSlot: 0, generation: c.generation)
        ordered = try await reopened.customDefinitions()
        XCTAssertEqual(ordered.map(\.clientUUID), [defs[4].clientUUID, defs[3].clientUUID, defs[0].clientUUID, lastID])
        XCTAssertEqual(try db.query("SELECT creation_ordinal FROM custom_preferences WHERE definition_uuid=?", [.text(lastID.uuidString)])[0][0], "7")
        let finalStore = try OfflineStore(path: file)
        let final = try await finalStore.customDefinitions()
        XCTAssertEqual(final, ordered)
    }

}
