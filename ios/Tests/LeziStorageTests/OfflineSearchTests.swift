import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

private extension OfflineStore {
    // End observation deterministically, allowing absence-of-event assertions
    // without timeouts, polling, or relying on task scheduling.
    func finishSearchTestStreams() {
        for continuation in searchInvalidationContinuations.values { continuation.finish() }
        searchInvalidationContinuations.removeAll()
    }
}

final class OfflineSearchTests: XCTestCase, @unchecked Sendable {
    private func path() -> String {
        FileManager.default.temporaryDirectory.appendingPathComponent("search-\(UUID()).sqlite").path
    }
    private func setup(_ file: String) async throws -> (OfflineStore, OfflineContext) {
        let store = try OfflineStore(path: file, clock: { 10_000_000 })
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        return (store, try await store.selectBaby(baby.clientUUID))
    }
    private func draft(_ context: OfflineContext, id: UUID = UUID(), time: Int64 = 100,
                       note: String? = "needle") -> RecordConfirmationDraft {
        .init(clientUUID: id, babyClientUUID: context.babyClientUUID, item: .builtIn(.pee)!,
            timestampMillis: time, note: note, payload: .pee(amount: 2))
    }
    private func sleepDraft(_ context: OfflineContext, id: UUID = UUID(), start: Int64 = 100,
                            end: Int64? = nil) -> RecordConfirmationDraft {
        .init(clientUUID: id, babyClientUUID: context.babyClientUUID, item: .builtIn(.sleep)!,
            timestampMillis: start, endTimestampMillis: end, note: "needle", payload: .sleep())
    }
    private func expect(_ error: OfflineStoreError, _ action: () async throws -> Void,
                        file: StaticString = #filePath, line: UInt = #line) async {
        do { try await action(); XCTFail("Expected \(error)", file: file, line: line) }
        catch let actual { XCTAssertEqual(actual as? OfflineStoreError, error, file: file, line: line) }
    }
    private func expectEvent(_ store: OfflineStore, emitted: Bool = true,
                             file: StaticString = #filePath, line: UInt = #line,
                             _ action: () async throws -> Void) async throws {
        let stream = await store.searchInvalidations()
        try await action()
        await store.finishSearchTestStreams()
        var iterator = stream.makeAsyncIterator()
        let event: Void? = await iterator.next()
        XCTAssertEqual(event != nil, emitted, file: file, line: line)
        let end: Void? = await iterator.next()
        XCTAssertNil(end, file: file, line: line)
    }

    func testCompleteHistoryBeyondHundredAndEmptyChunksKeepStableTieContinuation() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let ids = (1...235).map { UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", $0))! }
        for (index, id) in ids.enumerated() {
            _ = try await store.confirmRecord(draft(context, id: id, note: index < 5 ? "needle" : "other"), context: context)
        }
        let revision = try await store.currentSearchRevision()
        let first = try await store.searchPage(query: "needle", revision: revision)
        XCTAssertTrue(first.hits.isEmpty); XCTAssertNotNil(first.nextCursor)
        let second = try await store.searchPage(query: "needle", revision: revision, before: first.nextCursor)
        XCTAssertTrue(second.hits.isEmpty); XCTAssertNotNil(second.nextCursor)
        let third = try await store.searchPage(query: "needle", revision: revision, before: second.nextCursor)
        XCTAssertEqual(third.hits.map(\.record.clientUUID), Array(ids.prefix(5).reversed()))
        XCTAssertNil(third.nextCursor); XCTAssertEqual(third.revision, revision)
        var cursor: OfflineSearchCursor?
        var all = [UUID]()
        repeat {
            let page = try await store.searchPage(query: "尿", revision: revision, before: cursor, limit: 17)
            all += page.hits.map(\.record.clientUUID)
            cursor = page.nextCursor
        } while cursor != nil
        XCTAssertEqual(all, Array(ids.reversed()))
        XCTAssertEqual(Set(all).count, 235)
    }

    func testCursorBindsQueryRevisionBabyAndSelectionEpoch() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        for _ in 0..<3 { _ = try await store.confirmRecord(draft(context), context: context) }
        let revision = try await store.currentSearchRevision()
        let first = try await store.searchPage(query: "needle", revision: revision, limit: 1)
        let cursor = try XCTUnwrap(first.nextCursor)
        await expect(.staleContext) { _ = try await store.searchPage(query: "different", revision: revision, before: cursor) }
        let literal = try await store.searchPage(query: "é", revision: revision, limit: 1)
        XCTAssertNotNil(literal.nextCursor)
        await expect(.staleContext) {
            _ = try await store.searchPage(query: "e\u{301}", revision: revision, before: literal.nextCursor)
        }
        for limit in [0, 201, Int.max] {
            await expect(.invalidPageLimit) { _ = try await store.searchPage(query: "needle", revision: revision, limit: limit) }
        }
        _ = try await store.confirmRecord(draft(context), context: context)
        let changed = try await store.currentSearchRevision()
        XCTAssertEqual(changed.context, revision.context)
        XCTAssertNotEqual(changed.changeRevision, revision.changeRevision)
        await expect(.staleContext) { _ = try await store.searchPage(query: "needle", revision: revision, before: cursor) }
        await expect(.staleContext) { _ = try await store.searchPage(query: "needle", revision: changed, before: cursor) }
        let baby = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let other = try await store.currentSearchRevision()
        await expect(.staleContext) { _ = try await store.searchPage(query: "needle", revision: other, before: cursor) }
        _ = try await store.selectBaby(context.babyClientUUID)
        let reselected = try await store.currentSearchRevision()
        XCTAssertNotEqual(reselected.context.selectionRevision, revision.context.selectionRevision)
        await expect(.staleContext) { _ = try await store.searchPage(query: "needle", revision: revision) }
    }

    func testReopenRequiresFreshScopeAndContentAndNeverReturnsAnotherBaby() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let saved = try await store.confirmRecord(draft(context), context: context)
        let revision = try await store.currentSearchRevision()
        let hit = try await store.searchHit(recordID: saved.clientUUID, revision: revision)
        XCTAssertEqual(hit?.record, saved)
        let forged = OfflineSearchRevision(context: revision.context, localActorUUID: revision.localActorUUID,
            changeRevision: revision.changeRevision)
        await expect(.staleContext) { _ = try await store.searchHit(recordID: saved.clientUUID, revision: forged) }
        let reopened = try OfflineStore(path: file, clock: { 10_000_000 })
        await expect(.staleContext) { _ = try await reopened.searchHit(recordID: saved.clientUUID, revision: revision) }
        let ownRevision = try await reopened.currentSearchRevision()
        let ownHit = try await reopened.searchHit(recordID: saved.clientUUID, revision: ownRevision)
        XCTAssertEqual(ownHit?.record, saved)
        _ = try await reopened.confirmRecord(draft(context, id: saved.clientUUID, note: "changed"),
            context: context, expectedUpdatedAt: saved.updatedAt)
        await expect(.staleContext) { _ = try await store.searchHit(recordID: saved.clientUUID, revision: revision) }
        let updated = try await store.currentSearchRevision()
        XCTAssertEqual(updated.context, revision.context)
        XCTAssertNotEqual(updated.changeRevision, revision.changeRevision)
        let baby = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let otherRevision = try await store.currentSearchRevision()
        let absent = try await store.searchHit(recordID: saved.clientUUID, revision: otherRevision)
        XCTAssertNil(absent)
        await expect(.staleContext) { _ = try await store.searchHit(recordID: saved.clientUUID, revision: updated) }
    }

    func testExternalConnectionChangesInvalidatePagesWithoutInventedPersistentRevision() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        for _ in 0..<3 { _ = try await store.confirmRecord(draft(context), context: context) }
        let revision = try await store.currentSearchRevision()
        let page = try await store.searchPage(query: "needle", revision: revision, limit: 1)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE records SET note='external'") // Even without an updated_at change.
        await expect(.staleContext) {
            _ = try await store.searchPage(query: "needle", revision: revision, before: page.nextCursor)
        }
        let fresh = try await store.currentSearchRevision()
        let found = try await store.searchPage(query: "external", revision: fresh)
        XCTAssertEqual(found.hits.count, 3)
        try db.execute("UPDATE local_identity SET generation=?", [.text(UUID().uuidString)])
        await expect(.staleContext) { _ = try await store.searchPage(query: "external", revision: fresh) }
    }

    func testBlockedMissingSelectionAndDeletedBabyFailClosed() async throws {
        let empty = try OfflineStore(path: ":memory:")
        await expect(.staleContext) { _ = try await empty.currentSearchRevision() }
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let revision = try await store.currentSearchRevision()
        try await expectEvent(store) { try await store.blockOfflineWrites() }
        await expect(.offlineOnly) { _ = try await store.currentSearchRevision() }
        await expect(.offlineOnly) { _ = try await store.searchPage(query: "needle", revision: revision) }
        await expect(.offlineOnly) { _ = try await store.searchHit(recordID: UUID(), revision: revision) }
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE local_identity SET mode='offline'")
        try db.execute("UPDATE babies SET deleted_at=1 WHERE uuid=?", [.text(context.babyClientUUID.uuidString)])
        await expect(.missingBaby) { _ = try await store.currentSearchRevision() }
    }

    func testOpaqueVisibleNotesNotJSONAndReadsPreservePersistedBytes() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let known = try await store.confirmRecord(draft(context, note: "visible note"), context: context)
        let db = try SQLiteConnection(path: file)
        let opaqueID = UUID(), unknownID = UUID()
        for (id, type) in [(opaqueID, "pee"), (unknownID, "future-record")] {
            try db.execute("""
                INSERT INTO records (uuid,baby_uuid,type,timestamp,note,payload_json,payload_schema_version,updated_at,sync_dirty)
                VALUES (?,?,?,200,'visible note','{"secret_key":"raw-secret-needle"}',99,1000,0)
                """, [.text(id.uuidString), .text(context.babyClientUUID.uuidString), .text(type)])
        }
        let before = try Data(contentsOf: URL(fileURLWithPath: file))
        let revision = try await store.currentSearchRevision()
        let hidden = try await store.searchPage(query: "raw-secret-needle", revision: revision)
        XCTAssertTrue(hidden.hits.isEmpty)
        let key = try await store.searchPage(query: "secret_key", revision: revision)
        XCTAssertTrue(key.hits.isEmpty)
        let page = try await store.searchPage(query: "visible note", revision: revision)
        XCTAssertEqual(Set(page.hits.map(\.record.clientUUID)), [known.clientUUID, opaqueID, unknownID])
        XCTAssertEqual(page.hits.filter(\.record.isOpaque).count, 2)
        for hit in page.hits {
            let reopened = try await store.searchHit(recordID: hit.record.clientUUID, revision: revision)
            XCTAssertEqual(reopened, hit)
        }
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: file)), before)
    }

    func testRollbackCommitFailureReplayAndReadsDoNotEmitOrAdvanceRevision() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let saved = try await store.confirmRecord(draft(context), context: context)
        let before = try await store.currentSearchRevision()
        try await expectEvent(store, emitted: false) {
            _ = try await store.confirmRecord(draft(context, id: saved.clientUUID, note: "replay"), context: context)
            _ = try await store.searchPage(query: "needle", revision: before)
            _ = try await store.searchHit(recordID: saved.clientUUID, revision: before)
        }
        for point in [OfflineStore.FailurePoint.afterFact, .beforeCommit] {
            try await expectEvent(store, emitted: false) {
                await store.failNextConfirmation(at: point)
                await self.expect(.injectedFailure) { _ = try await store.confirmRecord(self.draft(context), context: context) }
            }
            let after = try await store.currentSearchRevision()
            XCTAssertEqual(after, before)
        }
        let db = try SQLiteConnection(path: file)
        try db.execute("CREATE TABLE commit_parent(id INTEGER PRIMARY KEY)")
        try db.execute("CREATE TABLE commit_guard(id INTEGER REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try db.execute("CREATE TRIGGER fail_search_commit AFTER INSERT ON records BEGIN INSERT INTO commit_guard VALUES(1); END")
        let commitRevision = try await store.currentSearchRevision()
        let failed = draft(context)
        try await expectEvent(store, emitted: false) {
            do { _ = try await store.confirmRecord(failed, context: context); XCTFail("Expected COMMIT failure") }
            catch let error as OfflineStoreError {
                guard case .sqlite = error else { return XCTFail("Unexpected \(error)") }
            }
        }
        let after = try await store.currentSearchRevision()
        XCTAssertEqual(after, commitRevision)
        XCTAssertEqual(try db.query("SELECT count(*) FROM records WHERE uuid=?", [.text(failed.clientUUID.uuidString)])[0][0], "0")
        XCTAssertEqual(try db.query("SELECT count(*) FROM commit_guard")[0][0], "0")
        try db.execute("DROP TRIGGER fail_search_commit")
        try await expectEvent(store) {
            _ = try await store.confirmRecord(failed, context: context)
            // A second connection can read the committed fact when success returns.
            XCTAssertEqual(try db.query("SELECT count(*) FROM records WHERE uuid=?", [.text(failed.clientUUID.uuidString)])[0][0], "1")
        }
    }

    func testRecordSelectionPreferenceAndCustomMutationsInvalidateAfterCommit() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        var saved: OfflineRecord?
        try await expectEvent(store) { saved = try await store.confirmRecord(draft(context), context: context) }
        let original = try XCTUnwrap(saved)
        try await expectEvent(store) {
            _ = try await store.confirmRecord(draft(context, id: original.clientUUID, note: "edited"),
                context: context, expectedUpdatedAt: original.updatedAt)
        }
        try await expectEvent(store) { _ = try await store.deleteRecord(original.clientUUID, context: context) }
        try await expectEvent(store, emitted: false) { _ = try await store.deleteRecord(original.clientUUID, context: context) }
        try await expectEvent(store) { _ = try await store.selectBaby(context.babyClientUUID) }
        try await expectEvent(store) { try await store.setPreferences(baby: context.babyClientUUID, themeColor: 1, sortOrder: 1) }
        var definition: OfflineCustomDefinition?
        try await expectEvent(store) {
            definition = try await store.createCustomDefinition(name: "旧标题", iconSlot: 0, generation: context.generation)
        }
        let originalDefinition = try XCTUnwrap(definition)
        try await expectEvent(store) {
            try await store.reorderCustomDefinitions([originalDefinition.clientUUID], generation: context.generation)
        }
        let custom = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: context.babyClientUUID,
            item: .parseCatalogKey("custom:\(originalDefinition.clientUUID.uuidString.lowercased())")!,
            timestampMillis: 100, note: "needle", payload: .custom(titleSnapshot: "旧标题", iconSlot: 0))
        var customRecord: OfflineRecord?
        try await expectEvent(store) { customRecord = try await store.confirmCustomRecord(custom, context: context) }
        try await expectEvent(store) {
            _ = try await store.confirmCustomRecord(custom, context: context,
                expectedUpdatedAt: try XCTUnwrap(customRecord).updatedAt)
        }
        try await expectEvent(store) {
            definition = try await store.updateCustomDefinition(originalDefinition.clientUUID, name: "新标题", iconSlot: 1,
                generation: context.generation, expectedUpdatedAt: originalDefinition.updatedAt)
        }
        let revision = try await store.currentSearchRevision()
        let oldTitle = try await store.searchPage(query: "旧标题", revision: revision)
        let newTitle = try await store.searchPage(query: "新标题", revision: revision)
        XCTAssertEqual(oldTitle.hits.count, 1); XCTAssertTrue(newTitle.hits.isEmpty)
        try await expectEvent(store) {
            _ = try await store.deleteCustomDefinition(originalDefinition.clientUUID, generation: context.generation,
                expectedUpdatedAt: try XCTUnwrap(definition).updatedAt)
        }
        let afterDelete = try await store.currentSearchRevision()
        let history = try await store.searchPage(query: "旧标题", revision: afterDelete)
        XCTAssertEqual(history.hits.count, 1)
    }

    func testSleepStartCompletedAndEditWritesEmitButAtomicFailureDoesNot() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        var started: OfflineSleep?
        try await expectEvent(store) {
            started = try await store.createSleepStart(sleepDraft(context), context: context)
        }
        let first = try XCTUnwrap(started)
        try await expectEvent(store) {
            _ = try await store.editSleepStart(sleepDraft(context, id: first.record.clientUUID, start: 110),
                context: context, expectedUpdatedAt: first.record.updatedAt)
        }
        try await expectEvent(store) { _ = try await store.deleteRecord(first.record.clientUUID, context: context) }
        var closed: OfflineSleep?
        let completed = sleepDraft(context, start: 300, end: 500)
        for point in [OfflineStore.FailurePoint.afterSleep, .afterWake, .beforeCommit] {
            let revision = try await store.currentSearchRevision()
            try await expectEvent(store, emitted: false) {
                await store.failNextConfirmation(at: point)
                await self.expect(.injectedFailure) { _ = try await store.recordCompletedSleep(completed, context: context) }
            }
            let unchanged = try await store.currentSearchRevision()
            XCTAssertEqual(unchanged, revision)
        }
        try await expectEvent(store) { closed = try await store.recordCompletedSleep(completed, context: context) }
        let saved = try XCTUnwrap(closed)
        let wake = try XCTUnwrap(saved.observations.first)
        try await expectEvent(store) {
            _ = try await store.editCompletedSleep(sleepDraft(context, id: saved.record.clientUUID, start: 310, end: 600),
                wakeClientUUID: wake.clientUUID, context: context,
                expectedUpdatedAt: saved.record.updatedAt, expectedWakeUpdatedAt: wake.updatedAt)
        }
        try await expectEvent(store) {
            _ = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        }
    }

    func testSleepProjectionChangesInvalidateWithoutChangingRootAndUseProjectedEnd() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let sleep = try await store.createSleepStart(sleepDraft(context), context: context)
        let initial = try await store.currentSearchRevision()
        let open = try await store.searchPage(query: "needle", revision: initial)
        XCTAssertEqual(open.hits.first?.sleep?.interval.isOpen, true)
        var observed: OfflineWakeObservation?
        let wake = OfflineWakeDraft(sleepClientUUID: sleep.record.clientUUID, babyClientUUID: context.babyClientUUID,
            timestampMillis: 3_600_100)
        try await expectEvent(store) { observed = try await store.recordWake(wake, context: context) }
        let observation = try XCTUnwrap(observed)
        await expect(.staleContext) { _ = try await store.searchHit(recordID: sleep.record.clientUUID, revision: initial) }
        let revision = try await store.currentSearchRevision()
        let closed = try await store.searchPage(query: "needle", revision: revision)
        let hit = try XCTUnwrap(closed.hits.first)
        XCTAssertEqual(hit.record, sleep.record)
        XCTAssertNil(hit.record.endTimestampMillis)
        XCTAssertEqual(hit.sleep?.interval.endTimestamp, 3_600_100)
        XCTAssertEqual(hit.content, RecordSearchContent(rawType: "sleep", payload: .sleep(), timestampMillis: 100,
            endTimestampMillis: 3_600_100, note: "needle"))
        let byDuration = try await store.searchPage(query: hit.content.summary, revision: revision)
        XCTAssertEqual(byDuration.hits.map(\.record.clientUUID), [sleep.record.clientUUID])
        try await expectEvent(store) {
            _ = try await store.updateWake(.init(clientUUID: observation.clientUUID, sleepClientUUID: sleep.record.clientUUID,
                babyClientUUID: context.babyClientUUID, timestampMillis: 7_200_100), context: context,
                expectedUpdatedAt: observation.updatedAt, expectedSleepUpdatedAt: sleep.record.updatedAt)
        }
        let updatedValue = try await store.wakeDetails(observation.clientUUID)
        let updated = try XCTUnwrap(updatedValue)
        var selected: OfflineSleep?
        try await expectEvent(store) {
            selected = try await store.selectEffectiveWake(updated.clientUUID, sleepClientUUID: sleep.record.clientUUID,
                context: context, expectedUpdatedAt: sleep.record.updatedAt)
        }
        let selectedSleep = try XCTUnwrap(selected)
        try await expectEvent(store) {
            _ = try await store.withdrawWake(updated.clientUUID, sleepClientUUID: sleep.record.clientUUID,
                context: context, expectedUpdatedAt: updated.updatedAt, expectedSleepUpdatedAt: selectedSleep.record.updatedAt)
        }
        let latest = try await store.currentSearchRevision()
        let reopened = try await store.searchHit(recordID: sleep.record.clientUUID, revision: latest)
        XCTAssertEqual(reopened?.sleep?.interval.isOpen, true)
    }

    func testSleepChunkIncludesLatestOpenPeerOutsidePageAndFailedWakeStaysSilent() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let first = try await store.recordCompletedSleep(sleepDraft(context, start: 100, end: 200), context: context)
        let second = try await store.createSleepStart(sleepDraft(context, start: 300), context: context)
        let wake = try XCTUnwrap(first.observations.first)
        _ = try await store.withdrawWake(wake.clientUUID, sleepClientUUID: first.record.clientUUID, context: context,
            expectedUpdatedAt: wake.updatedAt, expectedSleepUpdatedAt: first.record.updatedAt)
        let revision = try await store.currentSearchRevision()
        let one = try await store.searchPage(query: "needle", revision: revision, limit: 1)
        let two = try await store.searchPage(query: "needle", revision: revision, before: one.nextCursor, limit: 1)
        XCTAssertEqual(one.hits.first?.record.clientUUID, second.record.clientUUID)
        XCTAssertEqual(one.hits.first?.sleep?.interval.isWakeShortcutTarget, true)
        XCTAssertEqual(two.hits.first?.record.clientUUID, first.record.clientUUID)
        XCTAssertEqual(two.hits.first?.sleep?.interval.isOverlapPending, true)
        for point in [OfflineStore.FailurePoint.afterWake, .beforeCommit] {
            try await expectEvent(store, emitted: false) {
                await store.failNextConfirmation(at: point)
                await self.expect(.injectedFailure) {
                    _ = try await store.recordWake(.init(sleepClientUUID: second.record.clientUUID,
                        babyClientUUID: context.babyClientUUID, timestampMillis: 500), context: context)
                }
            }
            let after = try await store.currentSearchRevision()
            XCTAssertEqual(after, revision)
        }
    }

    func testOrdinarySearchChunkPreservesSeparateTimelineGlobalSleepTargetContract() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, context) = try await setup(file)
        let sleep = try await store.createSleepStart(sleepDraft(context), context: context)
        let ordinary = try await store.confirmRecord(draft(context, time: 300), context: context)
        let timeline = try await store.sleepPage(baby: context.babyClientUUID, limit: 1)
        XCTAssertTrue(timeline.sleeps.isEmpty)
        XCTAssertEqual(timeline.latestOpenSleepClientUUID, sleep.record.clientUUID)
        let revision = try await store.currentSearchRevision()
        let first = try await store.searchPage(query: "needle", revision: revision, limit: 1)
        XCTAssertEqual(first.hits.first?.record.clientUUID, ordinary.clientUUID)
        XCTAssertNil(first.hits.first?.sleep)
        let second = try await store.searchPage(query: "needle", revision: revision, before: first.nextCursor, limit: 1)
        XCTAssertEqual(second.hits.first?.record.clientUUID, sleep.record.clientUUID)
        XCTAssertEqual(second.hits.first?.sleep?.interval.isWakeShortcutTarget, true)
    }
}
