import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

final class OfflineStoreTests: XCTestCase, @unchecked Sendable {
    private func path() -> String { FileManager.default.temporaryDirectory.appendingPathComponent("lezi-\(UUID()).sqlite").path }
    private func draft(_ baby: UUID, id: UUID = UUID(), amount: Int = 2, time: Int64 = 100, note: String? = nil, photos: [UUID] = []) -> RecordConfirmationDraft {
        .init(clientUUID: id, babyClientUUID: baby, item: .builtIn(.pee)!, timestampMillis: time, note: note, payload: .pee(amount: amount), photoClientUUIDs: photos)
    }
    private func setup(_ path: String, now: Int64 = 1_000) async throws -> (OfflineStore, OfflineContext) {
        let store = try OfflineStore(path: path, clock: { now })
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        return (store, try await store.selectBaby(baby.clientUUID))
    }
    private func expect(_ expected: OfflineStoreError, _ action: () async throws -> Void) async {
        do { try await action(); XCTFail("Expected \(expected)") }
        catch { XCTAssertEqual(error as? OfflineStoreError, expected) }
    }
    func testFileBackedRestartIdentityFactsAndPreferences() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let identity = try await store.identity()
        let draft = draft(context.babyClientUUID, note: "尿尿 🌱\0尾")
        let saved = try await store.confirmPee(draft, context: context)
        try await store.setPreferences(baby: context.babyClientUUID, themeColor: 4278221742, sortOrder: 4)
        let reopened = try OfflineStore(path: file)
        let recoveredIdentity = try await reopened.identity()
        let recovered = try await reopened.record(draft.clientUUID)
        let babies = try await reopened.babies()
        let recoveredContext = try await reopened.context()
        XCTAssertEqual(identity, recoveredIdentity); XCTAssertEqual(saved, recovered)
        XCTAssertTrue(saved.syncDirty); XCTAssertEqual(saved.note, "尿尿 🌱\0尾")
        XCTAssertEqual(babies.first?.sortOrder, 4); XCTAssertEqual(context, recoveredContext)
    }
    func testDoubleConfirmAndCreateReplayNeverOverwrite() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let first = draft(context.babyClientUUID)
        async let a = store.confirmPee(first, context: context)
        let secondConnection = try OfflineStore(path: file, clock: { 1_000 })
        async let b = secondConnection.confirmPee(first, context: context)
        let results = try await (a, b)
        XCTAssertEqual(results.0, results.1)
        let replay = try await store.confirmPee(draft(context.babyClientUUID, id: first.clientUUID, amount: 3), context: context)
        XCTAssertEqual(replay, results.0)
        let connection = try SQLiteConnection(path: file)
        XCTAssertEqual(try connection.query("SELECT count(*) FROM pee_records")[0][0], "1")
    }
    func testBothRollbackSeamsLeaveNoFactOrDirtyMarker() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        for point in [OfflineStore.FailurePoint.afterFact, .beforeCommit] {
            let value = draft(context.babyClientUUID)
            await store.failNextConfirmation(at: point)
            await expect(.injectedFailure) { _ = try await store.confirmPee(value, context: context) }
            let reopened = try OfflineStore(path: file)
            let absent = try await reopened.record(value.clientUUID)
            XCTAssertNil(absent)
            let saved = try await store.confirmPee(value, context: context)
            XCTAssertTrue(saved.syncDirty)
        }
    }
    func testEditRollbackAndClockRollbackPreserveMonotonicRevision() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let first = try await store.confirmPee(draft(context.babyClientUUID), context: context)
        let backwards = try OfflineStore(path: file, clock: { 500 })
        let edit = draft(context.babyClientUUID, id: first.clientUUID, amount: 1)
        await backwards.failNextConfirmation(at: .afterFact)
        await expect(.injectedFailure) { _ = try await backwards.confirmPee(edit, context: context, expectedUpdatedAt: first.updatedAt) }
        let unchanged = try await store.record(first.clientUUID); XCTAssertEqual(unchanged, first)
        let second = try await backwards.confirmPee(edit, context: context, expectedUpdatedAt: first.updatedAt)
        XCTAssertEqual(second.updatedAt, 1_001); XCTAssertEqual(second.amount, 1)
        await expect(.staleEdit) { _ = try await store.confirmPee(edit, context: context, expectedUpdatedAt: first.updatedAt) }
    }
    func testReselectionPreservesOriginalContextButForeignStoreFailsClosed() async throws {
        let file = path(), otherFile = path()
        defer { try? FileManager.default.removeItem(atPath: file); try? FileManager.default.removeItem(atPath: otherFile) }
        let (store, context) = try await setup(file)
        _ = try await store.selectBaby(context.babyClientUUID)
        let savedAfterReselect = try await store.confirmPee(draft(context.babyClientUUID), context: context)
        XCTAssertEqual(savedAfterReselect.babyClientUUID, context.babyClientUUID)
        let other = try OfflineStore(path: otherFile, clock: { 1_000 })
        _ = try await other.createBaby(clientUUID: context.babyClientUUID, nickname: "另一个", birthday: "2026-01-01")
        _ = try await other.selectBaby(context.babyClientUUID)
        await expect(.staleContext) { _ = try await other.confirmPee(draft(context.babyClientUUID), context: context) }
    }
    func testBabySwitchKeepsOriginalDraftBabyAcrossRestart() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, contextA) = try await setup(file)
        let pending = draft(contextA.babyClientUUID)
        let babyB = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        let contextB = try await store.selectBaby(babyB.clientUUID)
        await expect(.staleContext) { _ = try await store.confirmPee(pending, context: contextB) }
        let absent = try await store.record(pending.clientUUID); XCTAssertNil(absent)
        let reopened = try OfflineStore(path: file, clock: { 1_000 })
        let saved = try await reopened.confirmPee(pending, context: contextA)
        XCTAssertEqual(saved.babyClientUUID, contextA.babyClientUUID)
        let selected = try await reopened.context()
        XCTAssertEqual(selected.babyClientUUID, babyB.clientUUID)
        let anotherDraft = draft(contextA.babyClientUUID)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE local_identity SET generation=?", [.text(UUID().uuidString)])
        await expect(.staleContext) { _ = try await reopened.confirmPee(anotherDraft, context: contextA) }
    }
    func testNicknameNormalizationScalarLimitAndLiveUniqueness() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let store = try OfflineStore(path: file)
        let fallback = try await store.createBaby(nickname: "\u{001c} \u{00a0}\n", birthday: "2026-01-01")
        XCTAssertEqual(fallback.nickname, "年年")
        await expect(.duplicateNickname) { _ = try await store.createBaby(nickname: " 年年 ", birthday: "2026-01-01") }
        let twenty = String(repeating: "🌱", count: 20)
        let baby = try await store.createBaby(nickname: " " + twenty + "\u{001f}", birthday: "2026-01-01")
        XCTAssertEqual(baby.nickname, twenty)
        await expect(.invalidDraft) { _ = try await store.createBaby(nickname: twenty + "🌱", birthday: "2026-01-01") }
        // A combining sequence counts scalars, not Swift grapheme clusters.
        await expect(.invalidDraft) { _ = try await store.createBaby(nickname: String(repeating: "e\u{0301}", count: 11), birthday: "2026-01-01") }
        let nel = try await store.createBaby(nickname: "\u{0085}", birthday: "2026-01-01")
        XCTAssertEqual(nel.nickname, "\u{0085}")
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE babies SET deleted_at=1 WHERE uuid=?", [.text(fallback.clientUUID.uuidString)])
        let replacement = try await store.createBaby(nickname: "", birthday: "2026-01-01")
        XCTAssertEqual(replacement.nickname, "年年")
    }
    func testBlockedMembershipBoundaryPersistsAndRejectsAllWrites() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        try await store.blockOfflineWrites()
        let reopened = try OfflineStore(path: file, clock: { 1_000 })
        await expect(.offlineOnly) { _ = try await reopened.confirmPee(draft(context.babyClientUUID), context: context) }
        await expect(.offlineOnly) { _ = try await reopened.createBaby(nickname: "宝宝", birthday: "2026-01-01") }
        await expect(.offlineOnly) { try await reopened.setPreferences(baby: context.babyClientUUID, themeColor: 4278221742, sortOrder: 1) }
        await expect(.offlineOnly) { _ = try await reopened.selectBaby(context.babyClientUUID) }
    }
    func testValidationAndTombstonesNeverRevive() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        for invalid in [draft(context.babyClientUUID, amount: 4), draft(context.babyClientUUID, time: 1_001), draft(context.babyClientUUID, photos: [UUID()]), RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: context.babyClientUUID, item: .builtIn(.bath)!, timestampMillis: 100, payload: .bath), RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: context.babyClientUUID, item: .builtIn(.pee)!, timestampMillis: 100, endTimestampMillis: 200, payload: .pee())] {
            await expect(.invalidDraft) { _ = try await store.confirmPee(invalid, context: context) }
        }
        let value = draft(context.babyClientUUID)
        let saved = try await store.confirmPee(value, context: context)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE pee_records SET deleted_at=1000 WHERE uuid=?", [.text(value.clientUUID.uuidString)])
        await expect(.deletedRecord) { _ = try await store.confirmPee(value, context: context) }
        await expect(.deletedRecord) { _ = try await store.confirmPee(value, context: context, expectedUpdatedAt: saved.updatedAt) }
        try db.execute("UPDATE babies SET deleted_at=1000")
        await expect(.missingBaby) { _ = try await store.confirmPee(draft(context.babyClientUUID), context: context) }
    }
    func testEditCannotMoveBabyAndOverflowFails() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let saved = try await store.confirmPee(draft(context.babyClientUUID), context: context)
        let other = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        let switched = try await store.selectBaby(other.clientUUID)
        await expect(.identityConflict) { _ = try await store.confirmPee(draft(other.clientUUID, id: saved.clientUUID), context: switched, expectedUpdatedAt: saved.updatedAt) }
        let current = try await store.selectBaby(context.babyClientUUID)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE pee_records SET updated_at=?", [.int(Int64.max)])
        await expect(.revisionOverflow) { _ = try await store.confirmPee(draft(context.babyClientUUID, id: saved.clientUUID), context: current, expectedUpdatedAt: Int64.max) }
    }
    func testDraftCreationAndLocalPreferencesDoNotWriteCareFacts() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let value = draft(context.babyClientUUID)
        let absent = try await store.record(value.clientUUID)
        XCTAssertNil(absent)
        let db = try SQLiteConnection(path: file)
        let before = try db.query("SELECT updated_at,sync_dirty FROM babies")
        try await store.setPreferences(baby: context.babyClientUUID, themeColor: 0xFF123456, sortOrder: 3)
        XCTAssertEqual(try db.query("SELECT updated_at,sync_dirty FROM babies"), before)
        XCTAssertEqual(try db.query("SELECT count(*) FROM pee_records")[0][0], "0")
    }
    func testRealSQLiteCommitFailureDoesNotReturnSuccessOrKeepFact() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let connection = try SQLiteConnection(path: file)
        // A deferred FK fails at COMMIT, after every fact/dirty SQL statement.
        try connection.execute("CREATE TABLE commit_parent (id INTEGER PRIMARY KEY)")
        try connection.execute("CREATE TABLE commit_guard (id INTEGER REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try connection.execute("CREATE TRIGGER fail_commit AFTER INSERT ON pee_records BEGIN INSERT INTO commit_guard VALUES (1); END")
        let value = draft(context.babyClientUUID)
        do {
            _ = try await store.confirmPee(value, context: context)
            XCTFail("COMMIT failure must never report success")
        } catch let error as OfflineStoreError {
            guard case .sqlite = error else { return XCTFail("Unexpected error: \(error)") }
        }
        let absent = try await store.record(value.clientUUID)
        XCTAssertNil(absent)
        XCTAssertEqual(try connection.query("SELECT count(*) FROM commit_guard")[0][0], "0")
        try connection.execute("DROP TRIGGER fail_commit")
        let saved = try await store.confirmPee(value, context: context)
        XCTAssertTrue(saved.syncDirty)
    }
    func testBoundedPagesStableTieOrderingAndHiddenTombstones() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let ids = (1...7).map { UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", $0))! }
        for (index, id) in ids.enumerated() {
            _ = try await store.confirmPee(draft(context.babyClientUUID, id: id, time: index < 5 ? 100 : 200), context: context)
        }
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE pee_records SET deleted_at=1 WHERE uuid=?", [.text(ids[3].uuidString)])
        let anotherBaby = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        let otherContext = try await store.selectBaby(anotherBaby.clientUUID)
        _ = try await store.confirmPee(draft(anotherBaby.clientUUID), context: otherContext)
        let first = try await store.records(baby: context.babyClientUUID, limit: 2)
        let second = try await store.records(baby: context.babyClientUUID, before: first.nextCursor, limit: 2)
        let third = try await store.records(baby: context.babyClientUUID, before: second.nextCursor, limit: 2)
        XCTAssertNotNil(first.nextCursor); XCTAssertNotNil(second.nextCursor); XCTAssertNil(third.nextCursor)
        let actual = (first.records + second.records + third.records).map(\.clientUUID)
        XCTAssertEqual(actual, [ids[6], ids[5], ids[4], ids[2], ids[1], ids[0]])
        XCTAssertEqual(Set(actual).count, 6)
        XCTAssertTrue((first.records + second.records + third.records).allSatisfy { $0.deletedAt == nil && $0.babyClientUUID == context.babyClientUUID })
        let emptyBaby = try await store.createBaby(nickname: "三宝", birthday: "2026-03-01")
        let empty = try await store.records(baby: emptyBaby.clientUUID)
        XCTAssertTrue(empty.records.isEmpty); XCTAssertNil(empty.nextCursor)
    }
    func testPageCursorRejectsWrongBabyGenerationStoreAndInvalidLimits() async throws {
        let file = path(), otherFile = path()
        defer { try? FileManager.default.removeItem(atPath: file); try? FileManager.default.removeItem(atPath: otherFile) }
        let (store, context) = try await setup(file)
        for _ in 0..<2 { _ = try await store.confirmPee(draft(context.babyClientUUID), context: context) }
        let first = try await store.records(baby: context.babyClientUUID, limit: 1)
        let cursor = try XCTUnwrap(first.nextCursor)
        let babyB = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        await expect(.staleContext) { _ = try await store.records(baby: babyB.clientUUID, before: cursor) }
        let other = try OfflineStore(path: otherFile)
        _ = try await other.createBaby(clientUUID: context.babyClientUUID, nickname: "宝宝", birthday: "2026-01-01")
        await expect(.staleContext) { _ = try await other.records(baby: context.babyClientUUID, before: cursor) }
        for limit in [0, -1, 201, Int.max] {
            await expect(.invalidPageLimit) { _ = try await store.records(baby: context.babyClientUUID, limit: limit) }
        }
        let maximum = try await store.records(baby: context.babyClientUUID, limit: 200)
        XCTAssertEqual(maximum.records.count, 2)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE local_identity SET generation=?", [.text(UUID().uuidString)])
        await expect(.staleContext) { _ = try await store.records(baby: context.babyClientUUID, before: cursor) }
    }
    func testTimelinePagesSeekPartialIndexWithoutTemporarySort() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (_, context) = try await setup(file)
        let db = try SQLiteConnection(path: file)
        for hasCursor in [false, true] {
            var bindings: [SQLiteConnection.Value] = [.text(context.babyClientUUID.uuidString)]
            if hasCursor { bindings += [.int(100), .text(UUID().uuidString)] }
            bindings.append(.int(101))
            let plan = try db.query("EXPLAIN QUERY PLAN " + OfflineStore.timelinePageSQL(hasCursor: hasCursor), bindings)
                .compactMap { $0.last ?? nil }.joined(separator: "\n").uppercased()
            XCTAssertTrue(plan.contains("SEARCH PEE_RECORDS USING INDEX PEE_TIMELINE"), plan)
            XCTAssertFalse(plan.contains("TEMP B-TREE"), plan)
            if hasCursor { XCTAssertTrue(plan.contains("(TIMESTAMP,UUID)<(?,?)"), plan) }
        }
    }
    func testUnknownAndForeignDatabaseRejectedWithoutMigration() throws {
        for sql in ["PRAGMA user_version=29", "CREATE TABLE unrelated (id INTEGER)"] {
            let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
            let db = try SQLiteConnection(path: file); try db.execute(sql)
            XCTAssertThrowsError(try OfflineStore(path: file)) { XCTAssertEqual($0 as? OfflineStoreError, .incompatibleDatabase) }
        }
    }
}
