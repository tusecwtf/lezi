import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineSleepEditingStateTests: XCTestCase {
    private func context() -> OfflineContext { .init(generation: UUID(), selectionRevision: 1, babyClientUUID: UUID()) }
    func testBackfillRequiresExplicitValidEndAndShiftKeepsDuration() throws {
        var state = OfflineSleepState(context: context(), babyName: "测试", timestampMillis: 100, action: .completedCreate)
        XCTAssertNil(state.endTimestampMillis); XCTAssertEqual(state.issue(now: 1000), "请填写醒来时间")
        XCTAssertFalse(state.chooseEnd(100, now: 1000)); XCTAssertNil(state.endTimestampMillis)
        XCTAssertFalse(state.chooseEnd(1001, now: 1000)); XCTAssertNil(state.endTimestampMillis)
        XCTAssertTrue(state.chooseEnd(200, now: 1000))
        state.edit(timestamp: 300); XCTAssertEqual(state.endTimestampMillis, 400)
        state.edit(timestamp: 950); XCTAssertEqual(state.endTimestampMillis, 1050)
        XCTAssertEqual(state.issue(now: 1000), "不能选未来时刻"); XCTAssertNil(state.begin(now: 1000))
        state.edit(timestamp: 100)
        let (_, command) = try XCTUnwrap(state.begin(now: 1000))
        guard case let .completedCreate(draft) = command else { return XCTFail() }
        XCTAssertEqual(draft.endTimestampMillis, 200); XCTAssertEqual(draft.payload, .sleep())
    }
    private func fixture() async throws -> (OfflineStore, OfflineContext, UUID, OfflineSleep) {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let actor = try await store.identity().localActorUUID
        let sleep = try await store.recordCompletedSleep(.init(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.sleep)!, timestampMillis: 100, endTimestampMillis: 200,
            note: "原始备注", payload: .sleep(isNap: true, anomaly: true)), context: context)
        return (store, context, actor, sleep)
    }
    func testLoadedFlagsBothRevisionsAndIndependentWakeIdentityCaptured() async throws {
        let (_, context, actor, sleep) = try await fixture()
        let wake = try XCTUnwrap(sleep.observations.first)
        var state = try XCTUnwrap(OfflineSleepState(sleep: sleep, context: context, babyName: "测试", completed: true, localActorUUID: actor))
        XCTAssertFalse(state.isDirty); XCTAssertEqual(state.action,
            .editCompleted(revision: sleep.record.updatedAt, wakeID: wake.clientUUID, wakeRevision: wake.updatedAt))
        state.edit(note: "仅改睡眠备注")
        let (_, command) = try XCTUnwrap(state.begin(now: 1000))
        guard case let .editCompleted(draft, wakeID, revision, wakeRevision) = command else { return XCTFail() }
        XCTAssertEqual(draft.clientUUID, sleep.record.clientUUID); XCTAssertEqual(draft.payload, .sleep(isNap: true, anomaly: true))
        XCTAssertEqual(wakeID, wake.clientUUID); XCTAssertEqual(revision, sleep.record.updatedAt); XCTAssertEqual(wakeRevision, wake.updatedAt)
        XCTAssertNil(OfflineSleepState(sleep: sleep, context: context, babyName: "测试", completed: true, localActorUUID: UUID()))
    }
    func testEqualWakeNotConvertedToCompletedEditorAndLegacyEntirelyReadOnly() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let actor = try await store.identity().localActorUUID
        let id = UUID()
        _ = try await store.createSleepStart(.init(clientUUID: id, babyClientUUID: baby.clientUUID, item: .builtIn(.sleep)!, timestampMillis: 100, payload: .sleep()), context: context)
        _ = try await store.recordWake(.init(sleepClientUUID: id, babyClientUUID: baby.clientUUID, timestampMillis: 100), context: context)
        let loaded = try await store.sleepDetails(id)
        let sleep = try XCTUnwrap(loaded)
        XCTAssertNil(OfflineSleepState(sleep: sleep, context: context, babyName: "测试", completed: true, localActorUUID: actor))
        XCTAssertNotNil(OfflineSleepState(sleep: sleep, context: context, babyName: "测试", completed: false, localActorUUID: actor))
        let old = sleep.record
        let legacy = OfflineRecord(clientUUID: old.clientUUID, babyClientUUID: old.babyClientUUID, rawType: "sleep", timestampMillis: old.timestampMillis,
            endTimestampMillis: 200, note: old.note, payloadJSON: old.payloadJSON, payloadSchemaVersion: old.payloadSchemaVersion,
            payloadDocument: old.payloadDocument, updatedAt: old.updatedAt, syncDirty: old.syncDirty, deletedAt: nil)
        let legacySleep = OfflineSleep(record: legacy, interval: sleep.interval, observations: sleep.observations)
        for completed in [false, true] {
            XCTAssertNil(OfflineSleepState(sleep: legacySleep, context: context, babyName: "测试", completed: completed, localActorUUID: actor))
        }
    }
    func testDoubleCASRejectsEachStaleRootWithoutOverwriting() async throws {
        for changeWake in [false, true] {
            let (store, context, actor, sleep) = try await fixture()
            let wake = try XCTUnwrap(sleep.observations.first)
            var state = try XCTUnwrap(OfflineSleepState(sleep: sleep, context: context, babyName: "测试", completed: true, localActorUUID: actor))
            state.edit(note: "旧草稿")
            if changeWake {
                _ = try await store.updateWake(.init(clientUUID: wake.clientUUID, sleepClientUUID: sleep.record.clientUUID,
                    babyClientUUID: context.babyClientUUID, timestampMillis: 250, note: "新版观察"), context: context,
                    expectedUpdatedAt: wake.updatedAt, expectedSleepUpdatedAt: sleep.record.updatedAt)
            } else {
                _ = try await store.selectEffectiveWake(wake.clientUUID, sleepClientUUID: sleep.record.clientUUID,
                    context: context, expectedUpdatedAt: sleep.record.updatedAt)
            }
            let (attempt, command) = try XCTUnwrap(state.begin(now: 1000))
            guard case let .editCompleted(draft, wakeID, revision, wakeRevision) = command else { return XCTFail() }
            do {
                _ = try await store.editCompletedSleep(draft, wakeClientUUID: wakeID, context: context,
                    expectedUpdatedAt: revision, expectedWakeUpdatedAt: wakeRevision)
                XCTFail("stale root must fail")
            } catch { XCTAssertEqual(error as? OfflineStoreError, .staleEdit) }
            XCTAssertTrue(state.finish(attempt, error: "已变化")); XCTAssertTrue(state.isDirty); XCTAssertEqual(state.note, "旧草稿")
            let after = try await store.sleepDetails(sleep.record.clientUUID)
            XCTAssertEqual(after?.record.note, "原始备注")
            if changeWake { XCTAssertEqual(after?.observations.first?.note, "新版观察") }
        }
    }
    func testStartEditCannotReopenClosedProjectionAndDeletionIgnoresUnsavedFields() async throws {
        let (store, context, actor, sleep) = try await fixture()
        var state = try XCTUnwrap(OfflineSleepState(sleep: sleep, context: context, babyName: "测试", completed: false, localActorUUID: actor))
        state.edit(timestamp: 300, note: "不得写入")
        let (attempt, command) = try XCTUnwrap(state.begin(now: 1000))
        guard case let .editStart(draft, revision) = command else { return XCTFail() }
        do {
            _ = try await store.editSleepStart(draft, context: context, expectedUpdatedAt: revision)
            XCTFail()
        } catch { XCTAssertEqual(error as? OfflineStoreError, .sleepStateChanged) }
        state.finish(attempt, error: "原投影保留")
        let (_, deletion) = try XCTUnwrap(state.beginDeletion()); XCTAssertNil(state.beginDeletion())
        guard case let .delete(id, oldRevision) = deletion else { return XCTFail() }
        _ = try await store.deleteRecord(id, context: context, expectedUpdatedAt: oldRevision)
        let tombstone = try await store.recordDetails(id)
        XCTAssertEqual(tombstone?.note, "原始备注"); XCTAssertEqual(tombstone?.timestampMillis, 100); XCTAssertNotNil(tombstone?.deletedAt)
        let wake = try await store.wakeDetails(sleep.observations[0].clientUUID)
        XCTAssertNil(wake?.deletedAt); XCTAssertEqual(wake?.note, "原始备注")
    }
    func testFileBackfillEditAtomicRollbackOriginalBabyAndReopen() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("sleep-edit-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2026-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        var draft = OfflineSleepState(context: context, babyName: a.nickname, timestampMillis: 100, action: .completedCreate)
        draft.edit(end: 200, note: "两个root初始备注")
        let (_, create) = try XCTUnwrap(draft.begin(now: 1000))
        guard case let .completedCreate(fact) = create else { return XCTFail() }
        await store.failNextConfirmation(at: .afterWake)
        do { _ = try await store.recordCompletedSleep(fact, context: context); XCTFail() } catch {}
        let empty = try await store.sleepPage(baby: a.clientUUID); XCTAssertTrue(empty.page.records.isEmpty)
        let created = try await store.recordCompletedSleep(fact, context: context)
        let actor = try await store.identity().localActorUUID
        var edit = try XCTUnwrap(OfflineSleepState(sleep: created, context: context, babyName: a.nickname, completed: true, localActorUUID: actor))
        edit.edit(timestamp: 150, note: "仅睡眠新备注")
        _ = try await store.selectBaby(b.clientUUID)
        let (_, command) = try XCTUnwrap(edit.begin(now: 1000))
        guard case let .editCompleted(updated, wakeID, revision, wakeRevision) = command else { return XCTFail() }
        await store.failNextConfirmation(at: .afterWake)
        do {
            _ = try await store.editCompletedSleep(updated, wakeClientUUID: wakeID, context: context,
                expectedUpdatedAt: revision, expectedWakeUpdatedAt: wakeRevision); XCTFail()
        } catch {}
        let unchanged = try await store.sleepDetails(created.record.clientUUID)
        XCTAssertEqual(unchanged?.record.timestampMillis, 100); XCTAssertEqual(unchanged?.interval.endTimestamp, 200)
        _ = try await store.editCompletedSleep(updated, wakeClientUUID: wakeID, context: context,
            expectedUpdatedAt: revision, expectedWakeUpdatedAt: wakeRevision)
        let reopened = try OfflineStore(path: path)
        let result = try await reopened.sleepDetails(created.record.clientUUID)
        XCTAssertEqual(result?.record.babyClientUUID, a.clientUUID); XCTAssertEqual(result?.record.note, "仅睡眠新备注")
        XCTAssertEqual(result?.interval.endTimestamp, 250); XCTAssertEqual(result?.observations.first?.clientUUID, wakeID)
        XCTAssertEqual(result?.observations.first?.note, "两个root初始备注")
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineSleepEditingModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testRealBackfillEditDeleteOwnerAndDuplicateCallbacks() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2026-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        let actor = try await store.identity().localActorUUID
        let model = OfflineSleepModel(store: store)
        model.openCompleted(context: context, babyName: a.nickname, now: 100)
        XCTAssertFalse(model.chooseEnd(100, now: 1000)); XCTAssertNil(model.editor?.endTimestampMillis)
        XCTAssertTrue(model.chooseEnd(200, now: 1000)); model.edit(note: "初始备注")
        let id = try XCTUnwrap(model.editor?.clientUUID)
        await store.failNextConfirmation(at: .afterWake)
        model.confirm(now: 1000); model.confirm(now: 1000)
        try await settle { model.editor?.error != nil }
        XCTAssertEqual(model.editor?.clientUUID, id)
        model.confirm(now: 1000); model.confirm(now: 1000); try await settle { model.editor == nil }
        let loaded = try await store.sleepDetails(id); let sleep = try XCTUnwrap(loaded)
        model.openEdit(sleep: sleep, context: context, babyName: a.nickname, completed: true, localActorUUID: actor)
        model.edit(note: "仅睡眠新备注")
        _ = try await store.selectBaby(b.clientUUID)
        model.confirm(now: 1000); model.confirm(now: 1000); try await settle { model.editor == nil }
        let edited = try await store.sleepDetails(id); let updated = try XCTUnwrap(edited)
        XCTAssertEqual(updated.record.note, "仅睡眠新备注"); XCTAssertEqual(updated.observations.first?.note, "初始备注")
        model.openEdit(sleep: updated, context: context, babyName: a.nickname, completed: false, localActorUUID: actor)
        model.edit(note: "删除不写入")
        model.requestDelete(); model.cancelDelete(); XCTAssertNotNil(model.editor)
        await store.failNextConfirmation(at: .beforeCommit)
        model.requestDelete(); model.confirmDelete(); model.confirmDelete()
        try await settle { model.editor?.error != nil }; XCTAssertEqual(model.editor?.note, "删除不写入")
        model.requestDelete(); model.confirmDelete(); model.confirmDelete(); try await settle { model.editor == nil }
        let tombstone = try await store.recordDetails(id); XCTAssertNotNil(tombstone?.deletedAt)
        XCTAssertEqual(tombstone?.note, "仅睡眠新备注")
        let wake = try await store.wakeDetails(sleep.observations[0].clientUUID); XCTAssertNil(wake?.deletedAt)
    }
    func testStaleDualRevisionRetainsDraftAndDiscardDoesNotWrite() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let actor = try await store.identity().localActorUUID
        let sleep = try await store.recordCompletedSleep(.init(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.sleep)!, timestampMillis: 100, endTimestampMillis: 200, payload: .sleep(isNap: true, anomaly: true)), context: context)
        let model = OfflineSleepModel(store: store)
        model.openEdit(sleep: sleep, context: context, babyName: baby.nickname, completed: true, localActorUUID: actor)
        model.edit(note: "旧草稿")
        let wake = sleep.observations[0]
        _ = try await store.updateWake(.init(clientUUID: wake.clientUUID, sleepClientUUID: sleep.record.clientUUID,
            babyClientUUID: baby.clientUUID, timestampMillis: 250, note: "新观察"), context: context,
            expectedUpdatedAt: wake.updatedAt, expectedSleepUpdatedAt: sleep.record.updatedAt)
        model.confirm(now: 1000); try await settle { model.editor?.error != nil }
        XCTAssertEqual(model.editor?.note, "旧草稿"); XCTAssertNil(model.completion)
        model.close(); model.cancelDiscard(); XCTAssertNotNil(model.editor)
        model.close(); model.discard(); XCTAssertNil(model.editor)
        let result = try await store.sleepDetails(sleep.record.clientUUID)
        XCTAssertNil(result?.record.note); XCTAssertEqual(result?.observations.first?.note, "新观察")
        XCTAssertEqual(result?.record.payloadDocument?.payload, .sleep(isNap: true, anomaly: true))
    }
}
#endif
