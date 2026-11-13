import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

final class OfflineRecordTests: XCTestCase, @unchecked Sendable {
    private func path() -> String { FileManager.default.temporaryDirectory.appendingPathComponent("lezi-records-\(UUID()).sqlite").path }
    private func setup(_ file: String) async throws -> (OfflineStore, OfflineContext) {
        let store = try OfflineStore(path: file, clock: { 1_000 })
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        return (store, try await store.selectBaby(baby.clientUUID))
    }
    private var payloads: [RecordPayload] {
        [.nursing(leftMinutes: 5, rightMinutes: 10, order: "LR"),
         .milk(kind: .formula, amountMl: 120, preparedMl: 150, durationMinutes: 10),
         .milk(kind: .pumpedFeed, amountMl: 120), .milk(kind: .pumpExpress, amountMl: 150),
         .pee(), .stool(), .bothDiaper(), .temperature(celsius: 36.8), .diary(body: "日记 🌱"), .bath, .walk,
         .symptom(kind: .cough), .symptom(kind: .rash), .symptom(kind: .vomit), .symptom(kind: .injury),
         .medicine(name: "药", dose: "一份"), .hospital(reason: "复诊", advice: "记录"),
         .measurement(kind: .height, value: 60, unit: "cm"), .measurement(kind: .weight, value: 6500, unit: "g"),
         .food(kind: .babyFood, content: "粥", amount: "半碗"), .food(kind: .snack, content: "水果", amount: "2片"),
         .food(kind: .drink, content: "水", amount: "100ml"),
         .measurement(kind: .head, value: 35, unit: "cm"), .measurement(kind: .chest, value: 36, unit: "cm"),
         .measurement(kind: .footSize, value: 10, unit: "cm"), .vaccine(name: "乙肝", batch: "批次")]
    }
    private func draft(_ context: OfflineContext, payload: RecordPayload, id: UUID = UUID(), time: Int64 = 100, end: Int64? = nil, note: String? = nil, photos: [UUID] = []) -> RecordConfirmationDraft {
        .init(clientUUID: id, babyClientUUID: context.babyClientUUID, item: .builtIn(payload.type) ?? .parseCatalogKey("custom:1")!, timestampMillis: time, endTimestampMillis: end, note: note, payload: payload, photoClientUUIDs: photos)
    }
    private func expect(_ error: OfflineStoreError, _ action: () async throws -> Void) async {
        do { try await action(); XCTFail("Expected \(error)") }
        catch let actual { XCTAssertEqual(actual as? OfflineStoreError, error) }
    }
    func testAll26OrdinaryTypesCreateRestartEditDeleteAndReplay() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        XCTAssertEqual(Set(payloads.map(\.type)), Set(OfflineStorageScope.supportedRecordTypes))
        XCTAssertEqual(payloads.count, 26)
        var saved = [OfflineRecord]()
        for payload in payloads {
            let value = draft(context, payload: payload, note: "原备注\0尾")
            let created = try await store.confirmRecord(value, context: context)
            XCTAssertEqual(created.payloadDocument?.payload, payload)
            XCTAssertEqual(created.payloadSchemaVersion, 2); XCTAssertTrue(created.syncDirty)
            let replay = try await store.confirmRecord(draft(context, payload: payload, id: value.clientUUID, note: "不能覆盖"), context: context)
            XCTAssertEqual(replay, created)
            saved.append(created)
        }
        let reopened = try OfflineStore(path: file, clock: { 500 })
        for record in saved {
            let read = try await reopened.recordDetails(record.clientUUID)
            XCTAssertEqual(read, record)
            let value = draft(context, payload: try XCTUnwrap(record.payloadDocument?.payload), id: record.clientUUID, note: "修改备注")
            let edited = try await reopened.confirmRecord(value, context: context, expectedUpdatedAt: record.updatedAt)
            XCTAssertEqual(edited.clientUUID, record.clientUUID); XCTAssertEqual(edited.babyClientUUID, record.babyClientUUID)
            XCTAssertEqual(edited.updatedAt, 1_001); XCTAssertEqual(edited.note, "修改备注")
            await expect(.staleEdit) { _ = try await reopened.confirmRecord(value, context: context, expectedUpdatedAt: record.updatedAt) }
            let deleted = try await reopened.deleteRecord(record.clientUUID, context: context, expectedUpdatedAt: edited.updatedAt)
            XCTAssertTrue(deleted)
            let again = try await reopened.deleteRecord(record.clientUUID, context: context, expectedUpdatedAt: edited.updatedAt)
            XCTAssertFalse(again)
            let tombstoneValue = try await reopened.recordDetails(record.clientUUID)
            let tombstone = try XCTUnwrap(tombstoneValue)
            XCTAssertEqual(tombstone.deletedAt, 1_002); XCTAssertEqual(tombstone.updatedAt, 1_002)
            XCTAssertEqual(tombstone.payloadJSON, edited.payloadJSON); XCTAssertTrue(tombstone.syncDirty)
            await expect(.deletedRecord) { _ = try await reopened.confirmRecord(value, context: context) }
        }
        let empty = try await reopened.recordPage(baby: context.babyClientUUID)
        XCTAssertTrue(empty.records.isEmpty)
    }
    func testPayloadFieldEditsPersistAcrossRestart() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let changes: [(RecordPayload, RecordPayload)] = [
            (.milk(kind: .formula, amountMl: 120), .milk(kind: .formula, amountMl: 140, preparedMl: 150, durationMinutes: 12)),
            (.temperature(celsius: 36.8), .temperature(celsius: 37.2)),
            (.measurement(kind: .weight, value: 6500, unit: "g"), .measurement(kind: .weight, value: 6700, unit: "g")),
            (.diary(body: "原文"), .diary(body: "新正文🌱"))]
        var edited = [OfflineRecord]()
        for (old, new) in changes {
            let saved = try await store.confirmRecord(draft(context, payload: old), context: context)
            let result = try await store.confirmRecord(draft(context, payload: new, id: saved.clientUUID), context: context, expectedUpdatedAt: saved.updatedAt)
            XCTAssertEqual(result.payloadDocument?.payload, new)
            edited.append(result)
        }
        let reopened = try OfflineStore(path: file)
        for expected in edited {
            let actual = try await reopened.recordDetails(expected.clientUUID)
            XCTAssertEqual(actual, expected)
        }
    }
    func testWriterRejectsFutureDomainMediaSleepCustomButAllowsOrdinaryInterval() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        for payload in payloads {
            await expect(.invalidDraft) { _ = try await store.confirmRecord(draft(context, payload: payload, time: 1_001), context: context) }
        }
        let invalid: [RecordPayload] = [.pee(amount: 0), .stool(amount: 5), .bothDiaper(peeAmount: 0),
            .milk(kind: .formula, amountMl: 0), .milk(kind: .pumpedFeed, amountMl: 10, preparedMl: 10),
            .nursing(leftMinutes: 0, rightMinutes: 0), .temperature(celsius: .nan), .diary(),
            .symptom(kind: .cough, severity: 4), .medicine(), .hospital(), .food(kind: .snack), .vaccine(),
            .measurement(kind: .height, value: 0, unit: "cm"), .sleep(), .custom(titleSnapshot: "项目")]
        for payload in invalid {
            await expect(.invalidDraft) { _ = try await store.confirmRecord(draft(context, payload: payload), context: context) }
        }
        await expect(.invalidDraft) { _ = try await store.confirmRecord(draft(context, payload: .bath, photos: [UUID()]), context: context) }
        await expect(.invalidDraft) { _ = try await store.confirmRecord(draft(context, payload: .bath, end: 100), context: context) }
        let interval = try await store.confirmRecord(draft(context, payload: .bath, end: 200), context: context)
        XCTAssertEqual(interval.endTimestampMillis, 200)
        let page = try await store.recordPage(baby: context.babyClientUUID)
        XCTAssertEqual(page.records.count, 1)
    }
    func testGenericIdentityAndOriginalBabyAndPeeAdapterUseOneWriter() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, contextA) = try await setup(file)
        let pending = draft(contextA, payload: .medicine(name: "药"))
        let babyB = try await store.createBaby(nickname: "二宝", birthday: "2026-01-01")
        let contextB = try await store.selectBaby(babyB.clientUUID)
        let saved = try await store.confirmRecord(pending, context: contextA)
        XCTAssertEqual(saved.babyClientUUID, contextA.babyClientUUID)
        await expect(.staleContext) { _ = try await store.confirmRecord(pending, context: contextB) }
        await expect(.identityConflict) { _ = try await store.confirmRecord(draft(contextB, payload: .medicine(name: "药"), id: saved.clientUUID), context: contextB) }
        await expect(.identityConflict) { _ = try await store.confirmPee(draft(contextA, payload: .pee(), id: saved.clientUUID), context: contextA) }
        await expect(.identityConflict) { _ = try await store.confirmRecord(draft(contextA, payload: .bath, id: saved.clientUUID), context: contextA, expectedUpdatedAt: saved.updatedAt) }
        await expect(.identityConflict) { _ = try await store.deleteRecord(saved.clientUUID, context: contextB) }
        let peeDraft = draft(contextA, payload: .pee())
        async let generic = store.confirmRecord(peeDraft, context: contextA)
        async let legacy = store.confirmPee(peeDraft, context: contextA)
        let pair = try await (generic, legacy)
        XCTAssertEqual(pair.0.clientUUID, pair.1.clientUUID)
        XCTAssertEqual(pair.0.updatedAt, pair.1.updatedAt)
        let db = try SQLiteConnection(path: file)
        XCTAssertEqual(try db.query("SELECT count(*) FROM records WHERE uuid=?", [.text(peeDraft.clientUUID.uuidString)])[0][0], "1")
    }
    func testOpaqueFutureSchemaAndUnknownTypeReadAndDeleteWithoutDataLoss() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let db = try SQLiteConnection(path: file)
        let inputs: [(String, Int, String)] = [
            ("pee", 99, " \n{\"pee_amount\":2}\t"), ("pee", 2, "{\"future\":true}"),
            ("formula", 2, "{broken\0text"), ("future_record", 7, " [1,2,3] ")]
        for (type, schema, json) in inputs {
            let id = UUID()
            try db.execute("INSERT INTO records (uuid,baby_uuid,type,timestamp,end_timestamp,note,payload_json,payload_schema_version,updated_at,sync_dirty,deleted_at) VALUES (?,?,?,100,NULL,'备注',?,?,1000,0,NULL)", [.text(id.uuidString), .text(context.babyClientUUID.uuidString), .text(type), .text(json), .int(Int64(schema))])
            let readValue = try await store.recordDetails(id)
            let read = try XCTUnwrap(readValue)
            XCTAssertTrue(read.isOpaque); XCTAssertEqual(read.rawType, type); XCTAssertEqual(read.payloadJSON, json)
            XCTAssertEqual(read.payloadSchemaVersion, schema)
            if type == "pee" {
                await expect(.opaqueRecord) { _ = try await store.confirmRecord(draft(context, payload: .pee(), id: id), context: context, expectedUpdatedAt: 1_000) }
                await expect(.opaqueRecord) { _ = try await store.record(id) }
            }
        }
        let page = try await store.recordPage(baby: context.babyClientUUID)
        XCTAssertEqual(page.records.count, 4); XCTAssertTrue(page.records.allSatisfy(\.isOpaque))
        try db.execute("UPDATE babies SET deleted_at=10") // Deletion need not reopen an editor or parse a payload.
        for record in page.records {
            let deleted = try await store.deleteRecord(record.clientUUID, context: context)
            XCTAssertTrue(deleted)
            let tombstoneValue = try await store.recordDetails(record.clientUUID)
            let tombstone = try XCTUnwrap(tombstoneValue)
            XCTAssertEqual(tombstone.payloadJSON, record.payloadJSON)
            XCTAssertEqual(tombstone.payloadSchemaVersion, record.payloadSchemaVersion)
            XCTAssertEqual(tombstone.rawType, record.rawType); XCTAssertEqual(tombstone.deletedAt, 1_001)
            XCTAssertTrue(tombstone.syncDirty)
        }
    }
    func testGenericCreateEditAndDeleteRollbackPreserveFullRow() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let value = draft(context, payload: .diary(body: "正文"))
        for point in [OfflineStore.FailurePoint.afterFact, .beforeCommit] {
            await store.failNextConfirmation(at: point)
            await expect(.injectedFailure) { _ = try await store.confirmRecord(value, context: context) }
            let absent = try await store.recordDetails(value.clientUUID); XCTAssertNil(absent)
        }
        let saved = try await store.confirmRecord(value, context: context)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE records SET sync_dirty=0 WHERE uuid=?", [.text(saved.clientUUID.uuidString)])
        let before = try db.query("SELECT * FROM records")
        for point in [OfflineStore.FailurePoint.afterFact, .beforeCommit] {
            await store.failNextConfirmation(at: point)
            await expect(.injectedFailure) { _ = try await store.confirmRecord(draft(context, payload: .diary(body: "修改"), id: saved.clientUUID), context: context, expectedUpdatedAt: saved.updatedAt) }
            XCTAssertEqual(try db.query("SELECT * FROM records"), before)
            await store.failNextConfirmation(at: point)
            await expect(.injectedFailure) { _ = try await store.deleteRecord(saved.clientUUID, context: context) }
            XCTAssertEqual(try db.query("SELECT * FROM records"), before)
        }
        await expect(.staleEdit) { _ = try await store.deleteRecord(saved.clientUUID, context: context, expectedUpdatedAt: 0) }
        try await store.blockOfflineWrites()
        await expect(.offlineOnly) { _ = try await store.deleteRecord(saved.clientUUID, context: context) }
        await expect(.offlineOnly) { _ = try await store.confirmRecord(value, context: context) }
    }
    func testDeleteCommitFailureCannotReportSuccessAndRetryKeepsPayload() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let saved = try await store.confirmRecord(draft(context, payload: .vaccine(name: "乙肝")), context: context)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE records SET sync_dirty=0")
        let before = try db.query("SELECT * FROM records")
        try db.execute("CREATE TABLE commit_parent (id INTEGER PRIMARY KEY)")
        try db.execute("CREATE TABLE commit_guard (id INTEGER REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try db.execute("CREATE TRIGGER fail_delete_commit AFTER UPDATE ON records BEGIN INSERT INTO commit_guard VALUES (1); END")
        do { _ = try await store.deleteRecord(saved.clientUUID, context: context); XCTFail("COMMIT must fail") }
        catch let error as OfflineStoreError {
            guard case .sqlite = error else { return XCTFail("Unexpected \(error)") }
        }
        XCTAssertEqual(try db.query("SELECT * FROM records"), before)
        XCTAssertEqual(try db.query("SELECT count(*) FROM commit_guard")[0][0], "0")
        try db.execute("DROP TRIGGER fail_delete_commit")
        let result = try await store.deleteRecord(saved.clientUUID, context: context)
        XCTAssertTrue(result)
        let tombstone = try await store.recordDetails(saved.clientUUID)
        XCTAssertEqual(tombstone?.payloadJSON, saved.payloadJSON)
        let missing = try await store.deleteRecord(UUID(), context: context)
        XCTAssertFalse(missing)
    }
    func testGenericAndPeePagingFilterCursorsAreNotInterchangeable() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        var ids = [UUID]()
        for i in 1...7 {
            let id = UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", i))!
            _ = try await store.confirmRecord(draft(context, payload: i % 2 == 0 ? .pee() : .bath, id: id), context: context)
            ids.append(id)
        }
        let first = try await store.recordPage(baby: context.babyClientUUID, limit: 2)
        let pee = try await store.records(baby: context.babyClientUUID, limit: 1)
        await expect(.staleContext) { _ = try await store.recordPage(baby: context.babyClientUUID, before: pee.nextCursor) }
        await expect(.staleContext) { _ = try await store.records(baby: context.babyClientUUID, before: first.nextCursor) }
        var collected = first.records
        var cursor = first.nextCursor
        while let next = cursor {
            let page = try await store.recordPage(baby: context.babyClientUUID, before: next, limit: 2)
            collected += page.records; cursor = page.nextCursor
        }
        XCTAssertEqual(collected.map(\.clientUUID), Array(ids.reversed()))
        XCTAssertEqual(Set(collected.map(\.clientUUID)).count, 7)
        let db = try SQLiteConnection(path: file)
        for hasCursor in [false, true] {
            var bindings: [SQLiteConnection.Value] = [.text(context.babyClientUUID.uuidString)]
            if hasCursor { bindings += [.int(100), .text(UUID().uuidString)] }
            bindings.append(.int(101))
            let plan = try db.query("EXPLAIN QUERY PLAN " + OfflineStore.timelinePageSQL(hasCursor: hasCursor, peeOnly: false), bindings)
                .compactMap { $0.last ?? nil }.joined(separator: "\n").uppercased()
            XCTAssertTrue(plan.contains("SEARCH RECORDS USING INDEX RECORD_TIMELINE"), plan)
            XCTAssertFalse(plan.contains("TEMP B-TREE"), plan)
            if hasCursor { XCTAssertTrue(plan.contains("(TIMESTAMP,UUID)<(?,?)"), plan) }
        }
    }
}
