import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

final class OfflineSleepTests: XCTestCase, @unchecked Sendable {
    private func path() -> String { FileManager.default.temporaryDirectory.appendingPathComponent("sleep-\(UUID()).sqlite").path }
    private func setup(_ file: String) async throws -> (OfflineStore, OfflineContext) {
        let store = try OfflineStore(path: file, clock: { 1_000 })
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        return (store, try await store.selectBaby(baby.clientUUID))
    }
    private func draft(_ c: OfflineContext, id: UUID = UUID(), start: Int64 = 100, end: Int64? = nil) -> RecordConfirmationDraft {
        .init(clientUUID: id, babyClientUUID: c.babyClientUUID, item: .builtIn(.sleep)!, timestampMillis: start, endTimestampMillis: end, note: "睡眠", payload: .sleep())
    }
    private func wake(_ c: OfflineContext, sleep: UUID, id: UUID = UUID(), time: Int64 = 200) -> OfflineWakeDraft {
        .init(clientUUID: id, sleepClientUUID: sleep, babyClientUUID: c.babyClientUUID, timestampMillis: time)
    }
    func testStartWakeRestartReplayAndIndependentRoots() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        let d = draft(c)
        let started = try await store.createSleepStart(d, context: c)
        XCTAssertTrue(started.interval.isOpen); XCTAssertNil(started.record.endTimestampMillis)
        let w = wake(c, sleep: d.clientUUID)
        let observed = try await store.recordWake(w, context: c)
        XCTAssertTrue(observed.syncDirty)
        let reopened = try OfflineStore(path: file, clock: { 500 })
        let result = try await reopened.sleepDetails(d.clientUUID)
        XCTAssertEqual(result?.interval.endTimestamp, 200); XCTAssertEqual(result?.interval.endSource, .provisional)
        XCTAssertEqual(result?.record, started.record)
        let replay = try await reopened.recordWake(wake(c, sleep: d.clientUUID, id: w.clientUUID, time: 300), context: c)
        XCTAssertEqual(replay, observed)
        let again = try await reopened.createSleepStart(d, context: c)
        XCTAssertEqual(again, result)
        let next = try await reopened.createSleepStart(draft(c, start: 300), context: c)
        let page = try await reopened.sleepPage(baby: c.babyClientUUID)
        XCTAssertEqual(page.sleeps.count, 2); XCTAssertEqual(page.latestOpenSleepClientUUID, next.record.clientUUID)
    }
    func testCompletedTwoFactsDeterministicIdentityAndSoftDeletePreservesWake() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        let d = draft(c, id: UUID(uuidString: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")!, end: 200)
        let saved = try await store.recordCompletedSleep(d, context: c)
        XCTAssertNil(saved.record.endTimestampMillis); XCTAssertTrue(saved.record.syncDirty)
        XCTAssertEqual(saved.observations.first?.clientUUID.uuidString.lowercased(), "6c1755b9-c1ca-5ba4-99a5-53b16d1a7c55")
        let replay = try await store.recordCompletedSleep(d, context: c); XCTAssertEqual(replay, saved)
        _ = try await store.deleteRecord(d.clientUUID, context: c)
        let kept = try await store.wakeDetails(saved.observations[0].clientUUID)
        XCTAssertEqual(kept, saved.observations[0])
        let page = try await store.sleepPage(baby: c.babyClientUUID); XCTAssertTrue(page.sleeps.isEmpty)
        do { _ = try await store.recordCompletedSleep(d, context: c); XCTFail() } catch { XCTAssertEqual(error as? OfflineStoreError, .deletedRecord) }
    }
    func testAtomicFailuresIncludingRealCommitFailureCanRetrySameIdentities() async throws {
        for point in [OfflineStore.FailurePoint.afterSleep, .afterWake, .beforeCommit] {
            let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
            let (store, c) = try await setup(file); let d = draft(c, end: 200)
            await store.failNextConfirmation(at: point)
            do { _ = try await store.recordCompletedSleep(d, context: c); XCTFail() } catch { XCTAssertEqual(error as? OfflineStoreError, .injectedFailure) }
            let db = try SQLiteConnection(path: file)
            XCTAssertEqual(try db.query("SELECT count(*) FROM records")[0][0], "0")
            XCTAssertEqual(try db.query("SELECT count(*) FROM wake_observations")[0][0], "0")
            _ = try await store.recordCompletedSleep(d, context: c)
        }
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file); let d = draft(c, end: 200)
        let db = try SQLiteConnection(path: file)
        try db.execute("CREATE TABLE commit_parent(id INTEGER PRIMARY KEY)")
        try db.execute("CREATE TABLE commit_guard(id INTEGER REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try db.execute("CREATE TRIGGER fail_sleep_commit AFTER INSERT ON wake_observations BEGIN INSERT INTO commit_guard VALUES (1); END")
        do { _ = try await store.recordCompletedSleep(d, context: c); XCTFail("COMMIT must fail") } catch { XCTAssertNotNil(error as? OfflineStoreError) }
        XCTAssertEqual(try db.query("SELECT count(*) FROM records")[0][0], "0")
        XCTAssertEqual(try db.query("SELECT count(*) FROM wake_observations")[0][0], "0")
        try db.execute("DROP TRIGGER fail_sleep_commit")
        _ = try await store.recordCompletedSleep(d, context: c)
    }
    func testOriginalBabyAndCrossTargetReplayAndTimeValidation() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file); let d = draft(c)
        let other = try await store.createBaby(nickname: "二宝", birthday: "2026-01-01")
        let b = try await store.selectBaby(other.clientUUID)
        _ = try await store.createSleepStart(d, context: c)
        do { _ = try await store.createSleepStart(d, context: b); XCTFail() } catch { XCTAssertEqual(error as? OfflineStoreError, .staleContext) }
        for time: Int64 in [99, 1001] {
            do { _ = try await store.recordWake(wake(c, sleep: d.clientUUID, time: time), context: c); XCTFail() } catch { XCTAssertEqual(error as? OfflineStoreError, .invalidDraft) }
        }
        let first = try await store.recordWake(wake(c, sleep: d.clientUUID, time: 100), context: c)
        let second = try await store.createSleepStart(draft(c, start: 300), context: c)
        do { _ = try await store.recordWake(wake(c, sleep: second.record.clientUUID, id: first.clientUUID, time: 400), context: c); XCTFail() } catch { XCTAssertEqual(error as? OfflineStoreError, .identityConflict) }
        do { _ = try await store.createSleepStart(draft(c, start: 400), context: c); XCTFail() } catch { XCTAssertEqual(error as? OfflineStoreError, .sleepStateChanged) }
        do { _ = try await store.recordCompletedSleep(draft(b, end: 100), context: b); XCTFail() } catch { XCTAssertEqual(error as? OfflineStoreError, .invalidDraft) }
    }
    func testProjectedClosedEditCASAndEffectiveWithdrawal() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file); let d = draft(c, end: 200)
        let saved = try await store.recordCompletedSleep(d, context: c)
        let w = saved.observations[0]
        do { _ = try await store.editSleepStart(draft(c, id: d.clientUUID, start: 201), context: c, expectedUpdatedAt: saved.record.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .sleepStateChanged) }
        let unchanged = try await store.sleepDetails(d.clientUUID); XCTAssertEqual(unchanged, saved)
        let later = try await store.recordWake(wake(c, sleep: d.clientUUID, time: 250), context: c)
        let selected = try await store.selectEffectiveWake(later.clientUUID, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: saved.record.updatedAt)
        XCTAssertEqual(selected.interval.endSource, .effective); XCTAssertEqual(selected.interval.endTimestamp, 250)
        XCTAssertEqual(selected.record.updatedAt, 1001)
        let withdrawn = try await store.withdrawWake(later.clientUUID, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: later.updatedAt)
        XCTAssertTrue(withdrawn.withdrawn); XCTAssertNil(withdrawn.deletedAt)
        let fallback = try await store.sleepDetails(d.clientUUID)
        XCTAssertNil(fallback?.record.effectiveWakeObservationClientUUID)
        XCTAssertEqual(fallback?.interval.endTimestamp, 200); XCTAssertEqual(fallback?.record.updatedAt, 1002)
        _ = try await store.withdrawWake(w.clientUUID, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: w.updatedAt)
        let opened = try await store.sleepDetails(d.clientUUID); XCTAssertEqual(opened?.interval.isOpen, true)
        let restored = try await store.updateWake(wake(c, sleep: d.clientUUID, id: later.clientUUID, time: 300), context: c, expectedUpdatedAt: withdrawn.updatedAt)
        XCTAssertFalse(restored.withdrawn); XCTAssertEqual(restored.updatedAt, 1002)
        do { _ = try await store.updateWake(wake(c, sleep: d.clientUUID, id: later.clientUUID), context: c, expectedUpdatedAt: withdrawn.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .staleEdit) }
    }
    func testCompletedEditAndWithdrawalCommitFailurePreserveBothRoots() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file); let d = draft(c, end: 200)
        _ = try await store.recordCompletedSleep(d, context: c)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE wake_observations SET note='独立观察备注'")
        let savedValue = try await store.sleepDetails(d.clientUUID)
        let saved = try XCTUnwrap(savedValue); let w = saved.observations[0]
        try db.execute("CREATE TABLE commit_parent(id INTEGER PRIMARY KEY)")
        try db.execute("CREATE TABLE commit_guard(id INTEGER REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try db.execute("CREATE TRIGGER fail_wake_update AFTER UPDATE ON wake_observations BEGIN INSERT INTO commit_guard VALUES (1); END")
        do { _ = try await store.editCompletedSleep(draft(c, id: d.clientUUID, start: 110, end: 250), wakeClientUUID: w.clientUUID, context: c, expectedUpdatedAt: saved.record.updatedAt, expectedWakeUpdatedAt: w.updatedAt); XCTFail() }
        catch { XCTAssertNotNil(error as? OfflineStoreError) }
        let unchanged = try await store.sleepDetails(d.clientUUID); XCTAssertEqual(unchanged, saved)
        try db.execute("DROP TRIGGER fail_wake_update")
        let edited = try await store.editCompletedSleep(draft(c, id: d.clientUUID, start: 110, end: 250), wakeClientUUID: w.clientUUID, context: c, expectedUpdatedAt: saved.record.updatedAt, expectedWakeUpdatedAt: w.updatedAt)
        XCTAssertEqual(edited.record.updatedAt, 1001); XCTAssertEqual(edited.observations[0].updatedAt, 1001)
        XCTAssertEqual(edited.observations[0].note, "独立观察备注")
        XCTAssertEqual(edited.record.note, "睡眠")
        XCTAssertEqual(edited.interval.startTimestamp, 110); XCTAssertEqual(edited.interval.endTimestamp, 250)
        let selected = try await store.selectEffectiveWake(w.clientUUID, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: edited.record.updatedAt)
        try db.execute("CREATE TRIGGER fail_wake_update AFTER UPDATE ON wake_observations BEGIN INSERT INTO commit_guard VALUES (1); END")
        do { _ = try await store.withdrawWake(w.clientUUID, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: 1001); XCTFail() }
        catch { XCTAssertNotNil(error as? OfflineStoreError) }
        let preserved = try await store.sleepDetails(d.clientUUID); XCTAssertEqual(preserved, selected)
        try db.execute("DROP TRIGGER fail_wake_update")
        _ = try await store.withdrawWake(w.clientUUID, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: 1001)
        let reopened = try OfflineStore(path: file)
        let final = try await reopened.sleepDetails(d.clientUUID)
        XCTAssertEqual(final?.interval.isOpen, true); XCTAssertNil(final?.record.effectiveWakeObservationClientUUID)
    }
    func testWakeOwnershipOverflowAndIndexes() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file); let d = draft(c, end: 200)
        let saved = try await store.recordCompletedSleep(d, context: c); let w = saved.observations[0]
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE wake_observations SET local_observer_uuid=?", [.text(UUID().uuidString)])
        do { _ = try await store.withdrawWake(w.clientUUID, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: w.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .wakePermission) }
        let identity = try await store.identity()
        try db.execute("UPDATE wake_observations SET local_observer_uuid=?,updated_at=?", [.text(identity.localActorUUID.uuidString), .int(Int64.max)])
        do { _ = try await store.editCompletedSleep(draft(c, id: d.clientUUID, end: 250), wakeClientUUID: w.clientUUID, context: c, expectedUpdatedAt: saved.record.updatedAt, expectedWakeUpdatedAt: Int64.max); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .revisionOverflow) }
        let unchanged = try await store.recordDetails(d.clientUUID); XCTAssertEqual(unchanged, saved.record)
        let plan = try db.query("EXPLAIN QUERY PLAN " + OfflineStore.latestOpenSleepSQL, [.text(c.babyClientUUID.uuidString)]).flatMap { $0.compactMap { $0 } }.joined(separator: " ")
        XCTAssertTrue(plan.contains("record_type_timeline")); XCTAssertTrue(plan.contains("wake_by_sleep")); XCTAssertFalse(plan.contains("TEMP B-TREE"))
    }

    func testBatchProjectionMissingEffectiveLegacyAndOverlappingOpenRoots() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file)
        let first = try await store.recordCompletedSleep(draft(c, end: 200), context: c)
        let second = try await store.recordCompletedSleep(draft(c, start: 300, end: 400), context: c)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE records SET effective_wake_observation_client_uuid=?", [.text(UUID().uuidString)])
        var page = try await store.sleepPage(baby: c.babyClientUUID)
        XCTAssertEqual(page.sleeps[first.record.clientUUID]?.interval.endSource, .provisional)
        try db.execute("UPDATE wake_observations SET withdrawn=1")
        page = try await store.sleepPage(baby: c.babyClientUUID)
        XCTAssertEqual(page.latestOpenSleepClientUUID, second.record.clientUUID)
        XCTAssertEqual(page.sleeps[first.record.clientUUID]?.interval.isOverlapPending, true)
        XCTAssertEqual(page.sleeps[second.record.clientUUID]?.interval.isWakeShortcutTarget, true)
        try db.execute("UPDATE records SET end_timestamp=250 WHERE uuid=?", [.text(first.record.clientUUID.uuidString)])
        page = try await store.sleepPage(baby: c.babyClientUUID)
        XCTAssertEqual(page.sleeps[first.record.clientUUID]?.interval.endSource, .legacyEnd)
        XCTAssertEqual(page.sleeps[first.record.clientUUID]?.interval.endTimestamp, 250)
        do { _ = try await store.editSleepStart(draft(c, id: first.record.clientUUID), context: c, expectedUpdatedAt: first.record.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .unsupportedSleepHistory) }
    }

    func testEqualTimeWakeOrderingSelectionBindingAndCleanWakeGate() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, c) = try await setup(file); let d = draft(c)
        let root = try await store.createSleepStart(d, context: c)
        let low = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
        let high = UUID(uuidString: "00000000-0000-4000-8000-000000000002")!
        _ = try await store.recordWake(wake(c, sleep: d.clientUUID, id: high), context: c)
        let lower = try await store.recordWake(wake(c, sleep: d.clientUUID, id: low), context: c)
        let result = try await store.sleepDetails(d.clientUUID)
        XCTAssertEqual(result?.interval.endObservationClientUUID, low.uuidString)
        let selected = try await store.selectEffectiveWake(high, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: root.record.updatedAt)
        XCTAssertEqual(selected.interval.endObservationClientUUID, high.uuidString)
        let cleared = try await store.selectEffectiveWake(nil, sleepClientUUID: d.clientUUID, context: c, expectedUpdatedAt: selected.record.updatedAt)
        XCTAssertEqual(cleared.interval.endObservationClientUUID, low.uuidString)
        let other = try await store.createSleepStart(draft(c, start: 300), context: c)
        do { _ = try await store.selectEffectiveWake(low, sleepClientUUID: other.record.clientUUID, context: c, expectedUpdatedAt: other.record.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .identityConflict) }
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE wake_observations SET sync_dirty=0 WHERE uuid=?", [.text(low.uuidString)])
        do { _ = try await store.updateWake(wake(c, sleep: d.clientUUID, id: low), context: c, expectedUpdatedAt: lower.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .wakePermission) }
    }

}
