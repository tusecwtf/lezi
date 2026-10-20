import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineWakeObservationStateTests: XCTestCase {
    private let day: Int64 = 1_767_225_600_000 // 2026-01-01 UTC
    private let actor = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
    private let sleepID = UUID(uuidString: "00000000-0000-4000-8000-000000000010")!
    private let babyID = UUID(uuidString: "00000000-0000-4000-8000-000000000020")!
    private var zone: TimeZone { TimeZone(secondsFromGMT: 0)! }
    private func wake(_ suffix: Int = 1, at: Int64? = nil, owner: UUID? = nil, dirty: Bool = true,
                      withdrawn: Bool = false, deleted: Bool = false, sleep: UUID? = nil) -> OfflineWakeObservation {
        .init(clientUUID: UUID(uuidString: String(format: "00000000-0000-4000-9000-%012d", suffix))!,
              sleepClientUUID: sleep ?? sleepID, timestampMillis: at ?? (day + 8 * 3_600_000 + 15_123),
              note: "观察原备注", withdrawn: withdrawn, localObserverUUID: owner ?? actor,
              updatedAt: 51, syncDirty: dirty, deletedAt: deleted ? 100 : nil)
    }
    private func list(_ wakes: [OfflineWakeObservation], legacyEnd: Int64? = nil,
                      schema: Int = 2, start: Int64? = nil) throws -> OfflineWakeObservationListState? {
        let json = try RecordPayloadCodec.encode(.init(payload: .sleep(isNap: true, anomaly: true)))
        let record = OfflineRecord(clientUUID: sleepID, babyClientUUID: babyID, rawType: "sleep",
            timestampMillis: start ?? (day + 7 * 3_600_000), endTimestampMillis: legacyEnd, note: "睡下原备注",
            payloadJSON: json, payloadSchemaVersion: schema,
            payloadDocument: RecordPayloadCodec.decode(type: .sleep, payloadJSON: json, schemaVersion: schema),
            updatedAt: 41, syncDirty: true, deletedAt: nil)
        let projection = SleepProjection.project(sleepClientUUID: sleepID.uuidString, startTimestamp: record.timestampMillis,
            observations: wakes.map { .init(clientUUID: $0.clientUUID.uuidString, wakeTimestamp: $0.timestampMillis,
                withdrawn: $0.withdrawn, deleted: $0.deletedAt != nil) }, legacyEndTimestamp: legacyEnd)
        return .init(sleep: .init(record: record, interval: projection, observations: wakes),
            context: .init(generation: UUID(), selectionRevision: 1, babyClientUUID: babyID),
            babyName: "原宝宝", localActorUUID: actor, zone: zone)
    }
    func testLegalListFiltersEvidenceAndStableOrderWithoutRestoreEntry() throws {
        let sameTime = day + 8 * 3_600_000
        let high = wake(2, at: sameTime), low = wake(1, at: sameTime)
        let withdrawn = wake(3, withdrawn: true), deleted = wake(4, deleted: true)
        let beforeStart = wake(5, at: day)
        let list = try XCTUnwrap(list([high, withdrawn, deleted, beforeStart, low]))
        XCTAssertEqual(list.observations.map(\.clientUUID), [low.clientUUID, high.clientUUID])
        XCTAssertEqual(list.sleep.observations.count, 5)
        XCTAssertEqual(list.status(low), "暂定"); XCTAssertEqual(list.status(high), "保留的观察")
        XCTAssertNil(OfflineWakeObservationState(wake: withdrawn, list: list))
        XCTAssertNil(OfflineWakeObservationState(wake: deleted, list: list))
        XCTAssertNil(OfflineWakeObservationState(wake: beforeStart, list: list))
    }
    func testOnlyOriginalLocalDirtyWakeCanEditAndUnsupportedParentsStayReadOnly() throws {
        let own = wake(), peer = wake(2, owner: UUID()), clean = wake(3, dirty: false)
        let foreign = wake(4, sleep: UUID())
        let list = try XCTUnwrap(list([own, peer, clean, foreign]))
        XCTAssertTrue(list.canEdit(own)); XCTAssertFalse(list.canEdit(peer))
        XCTAssertFalse(list.canEdit(clean)); XCTAssertFalse(list.canEdit(foreign))
        XCTAssertNil(OfflineWakeObservationState(wake: peer, list: list))
        XCTAssertNil(OfflineWakeObservationState(wake: clean, list: list))
        XCTAssertNil(try self.list([own], legacyEnd: day + 9 * 3_600_000))
        XCTAssertNil(try self.list([own], schema: 99))
        let other = OfflineContext(generation: list.context.generation, selectionRevision: 2, babyClientUUID: UUID())
        XCTAssertNil(OfflineWakeObservationListState(sleep: list.sleep, context: other,
            babyName: "其他宝宝", localActorUUID: actor, zone: zone))
    }
    func testCapturedIdentitiesDualRevisionsLongNoteAndDuplicateAttemptGate() throws {
        let wake = wake(), list = try XCTUnwrap(list([wake]))
        var state = try XCTUnwrap(OfflineWakeObservationState(wake: wake, list: list))
        XCTAssertEqual(state.timeText, "08:00"); XCTAssertFalse(state.isDirty)
        let longNote = String(repeating: "😀独立", count: 101)
        state.edit(timeText: "09:30", note: " \t\(longNote)\n")
        let (attempt, command) = try XCTUnwrap(state.begin(now: day + 86_400_000))
        XCTAssertEqual(command.sleepRevision, 41); XCTAssertEqual(command.wakeRevision, 51)
        XCTAssertEqual(command.context, list.context); XCTAssertEqual(command.draft.clientUUID, wake.clientUUID)
        XCTAssertEqual(command.draft.sleepClientUUID, sleepID); XCTAssertEqual(command.draft.babyClientUUID, babyID)
        XCTAssertEqual(command.draft.timestampMillis, day + 9 * 3_600_000 + 30 * 60_000)
        XCTAssertEqual(command.draft.note, longNote)
        XCTAssertNil(state.begin(now: day + 86_400_000))
        state.edit(timeText: "10:00", note: "不应替换"); XCTAssertEqual(state.timeText, "09:30")
        XCTAssertFalse(state.finish(UUID())); XCTAssertTrue(state.finish(attempt, error: "保存失败"))
        XCTAssertTrue(state.isDirty); XCTAssertEqual(state.error, "保存失败")
        let (_, retry) = try XCTUnwrap(state.begin(now: day + 86_400_000)); XCTAssertEqual(retry, command)
    }
    func testInvalidFutureEarlierAndEqualWakeWithMinuteNormalization() throws {
        let wake = wake(), list = try XCTUnwrap(list([wake]))
        var state = try XCTUnwrap(OfflineWakeObservationState(wake: wake, list: list))
        state.edit(timeText: "8:00"); XCTAssertEqual(state.issue(now: .max), "时间请填写为 HH:mm")
        XCTAssertNil(state.begin(now: .max))
        state.edit(timeText: "06:59"); XCTAssertEqual(state.issue(now: .max), "醒来时间不能早于入睡时间")
        state.edit(timeText: "09:01"); XCTAssertEqual(state.issue(now: day + 9 * 3_600_000), "不能选未来时刻")
        state.edit(timeText: "07:00", note: " \t\n")
        let (_, command) = try XCTUnwrap(state.begin(now: day + 9 * 3_600_000))
        XCTAssertEqual(command.draft.timestampMillis, list.sleep.record.timestampMillis)
        XCTAssertNil(command.draft.note)
    }
    func testNoteOnlySaveUsesOriginalDateAndZerosSecondsNotToday() throws {
        let wake = wake(), list = try XCTUnwrap(list([wake]))
        var state = try XCTUnwrap(OfflineWakeObservationState(wake: wake, list: list))
        state.edit(note: "只改观察备注")
        let (_, command) = try XCTUnwrap(state.begin(now: day + 31 * 86_400_000))
        XCTAssertEqual(command.draft.timestampMillis, day + 8 * 3_600_000)
        XCTAssertEqual(list.sleep.record.note, "睡下原备注")
        XCTAssertEqual(list.sleep.record.payloadDocument?.payload, .sleep(isNap: true, anomaly: true))
    }
    func testUnrepresentableOriginalDateNeverOpensACorrectionAroundClampedValues() throws {
        let wake = wake(at: Int64.min + 3_600_000)
        let list = try XCTUnwrap(list([wake], start: .min))
        XCTAssertEqual(list.observations, [wake])
        XCTAssertTrue(list.canEdit(wake)) // Provenance still permits an explicit withdrawal.
        XCTAssertNil(OfflineWakeObservationState(wake: wake, list: list))
        XCTAssertEqual(list.sleep.observations[0].timestampMillis, Int64.min + 3_600_000)
    }
    func testRealFileCommandRetryOriginalBabyAndWithdrawnEvidenceReopen() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("wake-state-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let now = day + 86_400_000
        let store = try OfflineStore(path: path, clock: { now })
        let a = try await store.createBaby(nickname: "原宝宝", birthday: "2025-01-01")
        let b = try await store.createBaby(nickname: "其他宝宝", birthday: "2025-01-01")
        let context = try await store.selectBaby(a.clientUUID), actor = try await store.identity().localActorUUID
        let start = day + 7 * 3_600_000, end = day + 8 * 3_600_000
        let draft = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: a.clientUUID,
            item: .builtIn(.sleep)!, timestampMillis: start, endTimestampMillis: end,
            note: "睡眠备注", payload: .sleep(isNap: true))
        let sleep = try await store.recordCompletedSleep(draft, context: context)
        let list = try XCTUnwrap(OfflineWakeObservationListState(sleep: sleep, context: context,
            babyName: a.nickname, localActorUUID: actor, zone: zone))
        var state = try XCTUnwrap(OfflineWakeObservationState(wake: sleep.observations[0], list: list))
        state.edit(timeText: "09:30", note: "独立醒来备注")
        let (_, command) = try XCTUnwrap(state.begin(now: now))
        _ = try await store.selectBaby(b.clientUUID)
        await store.failNextConfirmation(at: .beforeCommit)
        do {
            _ = try await store.updateWake(command.draft, context: command.context,
                expectedUpdatedAt: command.wakeRevision, expectedSleepUpdatedAt: command.sleepRevision)
            XCTFail("rollback required")
        } catch { XCTAssertEqual(error as? OfflineStoreError, .injectedFailure) }
        let unchanged = try await store.sleepDetails(sleep.record.clientUUID); XCTAssertEqual(unchanged, sleep)
        let updated = try await store.updateWake(command.draft, context: command.context,
            expectedUpdatedAt: command.wakeRevision, expectedSleepUpdatedAt: command.sleepRevision)
        let reopened = try OfflineStore(path: path)
        let result = try await reopened.sleepDetails(sleep.record.clientUUID)
        XCTAssertEqual(result?.record, sleep.record)
        XCTAssertEqual(result?.observations.first?.clientUUID, state.clientUUID)
        XCTAssertEqual(result?.observations.first?.note, "独立醒来备注")
        _ = try await reopened.withdrawWake(updated.clientUUID, sleepClientUUID: sleep.record.clientUUID,
            context: context, expectedUpdatedAt: updated.updatedAt, expectedSleepUpdatedAt: sleep.record.updatedAt)
        let after = try await reopened.sleepDetails(sleep.record.clientUUID)
        let afterSleep = try XCTUnwrap(after)
        let afterList = try XCTUnwrap(list.refreshed(afterSleep))
        XCTAssertTrue(afterList.observations.isEmpty); XCTAssertTrue(afterList.sleep.interval.isOpen)
        XCTAssertTrue(afterList.sleep.observations[0].withdrawn); XCTAssertNil(afterList.sleep.observations[0].deletedAt)
        XCTAssertNil(OfflineWakeObservationState(wake: afterList.sleep.observations[0], list: afterList))
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineWakeObservationModelTests: XCTestCase {
    private let day: Int64 = 1_767_225_600_000
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    private func fixture(path: String = ":memory:") async throws -> (OfflineStore, OfflineContext, UUID, OfflineSleep, OfflineWakeObservationModel) {
        let now = day + 86_400_000
        let store = try OfflineStore(path: path, clock: { now })
        let baby = try await store.createBaby(nickname: "原宝宝", birthday: "2025-01-01")
        let context = try await store.selectBaby(baby.clientUUID), actor = try await store.identity().localActorUUID
        let start = day + 7 * 3_600_000, end = day + 8 * 3_600_000
        let draft = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.sleep)!, timestampMillis: start, endTimestampMillis: end,
            note: "睡眠备注", payload: .sleep(isNap: true))
        let sleep = try await store.recordCompletedSleep(draft, context: context)
        let model = OfflineWakeObservationModel(store: store)
        model.open(sleep: sleep, context: context, babyName: baby.nickname, localActorUUID: actor, zone: TimeZone(secondsFromGMT: 0)!)
        return (store, context, actor, sleep, model)
    }
    func testFailureRetryDoubleCallbacksOriginalBabyAndIndependentNote() async throws {
        let (store, _, _, sleep, model) = try await fixture()
        let wake = sleep.observations[0]
        model.openEditor(wake); model.edit(timeText: "09:30", note: String(repeating: "观察", count: 150))
        let b = try await store.createBaby(nickname: "其他宝宝", birthday: "2025-01-01")
        _ = try await store.selectBaby(b.clientUUID)
        await store.failNextConfirmation(at: .beforeCommit)
        model.confirm(now: day + 86_400_000); model.confirm(now: day + 86_400_000)
        try await settle { !model.busy }; XCTAssertNotNil(model.editor?.error); XCTAssertNil(model.completion)
        XCTAssertEqual(model.editor?.clientUUID, wake.clientUUID)
        model.confirm(now: day + 86_400_000); model.confirm(now: day + 86_400_000)
        try await settle { !model.busy }
        XCTAssertNil(model.editor); XCTAssertEqual(model.message, "醒来观察已修正")
        XCTAssertEqual(model.details?.sleep.record, sleep.record)
        XCTAssertEqual(model.details?.observations[0].note, String(repeating: "观察", count: 150))
        XCTAssertEqual(model.details?.observations[0].clientUUID, wake.clientUUID)
        XCTAssertEqual(model.details?.observations[0].timestampMillis, day + 9 * 3_600_000 + 30 * 60_000)
    }
    func testDirtyCancelDiscardAndOldCallbacksDoNotWriteOrDismissNewEditor() async throws {
        let (store, _, _, sleep, model) = try await fixture()
        model.openEditor(sleep.observations[0]); model.edit(note: "放弃")
        model.cancelEdit(); XCTAssertTrue(model.showingDiscard)
        model.cancelDiscard(); XCTAssertEqual(model.editor?.note, "放弃")
        model.cancelEdit(); model.discard(); XCTAssertNil(model.editor); XCTAssertNotNil(model.details)
        model.openEditor(sleep.observations[0]); let newID = model.editor?.sessionID
        model.discard(); XCTAssertEqual(model.editor?.sessionID, newID)
        model.cancelEdit(); model.close(); XCTAssertNil(model.details)
        let result = try await store.sleepDetails(sleep.record.clientUUID); XCTAssertEqual(result, sleep)
    }
    func testStaleSleepAndWakeCommandsKeepDraftAndWithdrawalRefreshesWithoutRestore() async throws {
        for changeSleep in [false, true] {
            let (store, context, _, sleep, model) = try await fixture()
            let wake = sleep.observations[0]
            model.openEditor(wake); model.edit(note: "旧草稿")
            if changeSleep {
                _ = try await store.selectEffectiveWake(wake.clientUUID, sleepClientUUID: sleep.record.clientUUID,
                    context: context, expectedUpdatedAt: sleep.record.updatedAt)
            } else {
                _ = try await store.withdrawWake(wake.clientUUID, sleepClientUUID: sleep.record.clientUUID,
                    context: context, expectedUpdatedAt: wake.updatedAt, expectedSleepUpdatedAt: sleep.record.updatedAt)
            }
            model.confirm(now: day + 86_400_000); try await settle { !model.busy }
            XCTAssertEqual(model.editor?.note, "旧草稿"); XCTAssertNotNil(model.editor?.error); XCTAssertNil(model.completion)
            model.cancelEdit(); model.discard(); model.refresh(); try await settle { !model.busy }
            if changeSleep {
                let current = try XCTUnwrap(model.details?.observations.first)
                model.withdraw(current); model.withdraw(current); try await settle { !model.busy }
                XCTAssertEqual(model.message, "醒来观察已撤回"); XCTAssertNotNil(model.completion)
            }
            XCTAssertTrue(model.details?.observations.isEmpty == true)
            XCTAssertTrue(model.details?.sleep.interval.isOpen == true)
            XCTAssertNil(model.details?.sleep.record.effectiveWakeObservationClientUUID)
            let retained = try XCTUnwrap(model.details?.sleep.observations.first)
            model.openEditor(retained); XCTAssertNil(model.editor)
            model.withdraw(retained); XCTAssertFalse(model.busy)
        }
    }
    func testCommitSuccessRemainsDistinctFromSubsequentReadFailure() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("wake-read-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let (store, _, _, sleep, model) = try await fixture(path: path)
        let wake = sleep.observations[0]
        let db = try SQLiteConnection(path: path)
        // The write validates the parent before mutating its independent wake.
        // This synthetic trigger breaks only the subsequent parent read, not
        // the committed wake fact or the transaction's wake return value.
        try db.execute("""
            CREATE TRIGGER break_parent_read AFTER UPDATE ON wake_observations
            BEGIN UPDATE records SET updated_at='unreadable' WHERE uuid=NEW.sleep_record_client_uuid; END
            """)
        model.openEditor(wake); model.edit(timeText: "09:30", note: "已提交观察")
        model.confirm(now: day + 86_400_000); try await settle { !model.busy }
        XCTAssertNil(model.editor); XCTAssertNotNil(model.completion)
        XCTAssertEqual(model.message, "醒来观察已修正。重新读取失败，请重新读取本机观察。")
        let saved = try await store.wakeDetails(wake.clientUUID)
        XCTAssertEqual(saved?.note, "已提交观察")
        XCTAssertEqual(saved?.timestampMillis, day + 9 * 3_600_000 + 30 * 60_000)
        XCTAssertEqual(model.details?.sleep, sleep) // Last good display remains, never a success rollback.
    }
}
#endif
