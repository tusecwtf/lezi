import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineSleepStateTests: XCTestCase {
    private func context() -> OfflineContext { .init(generation: UUID(), selectionRevision: 1, babyClientUUID: UUID()) }
    func testSeparateRootsStableIdentityAndDuplicateGate() throws {
        let context = context()
        var state = OfflineSleepState(context: context, babyName: "测试", timestampMillis: 900)
        let id = state.clientUUID
        state.edit(note: "  睡下  ")
        let (attempt, command) = try XCTUnwrap(state.begin(now: 1000))
        guard case let .start(draft) = command else { return XCTFail() }
        XCTAssertEqual(draft.clientUUID, id); XCTAssertEqual(draft.babyClientUUID, context.babyClientUUID)
        XCTAssertNil(draft.endTimestampMillis); XCTAssertEqual(draft.payload, .sleep()); XCTAssertEqual(draft.note, "睡下")
        XCTAssertNil(state.begin(now: 1000)); state.edit(note: "禁止")
        XCTAssertEqual(state.note, "  睡下  "); XCTAssertFalse(state.finish(UUID()))
        XCTAssertTrue(state.finish(attempt, error: "rollback")); XCTAssertTrue(state.isDirty)
        let (_, retry) = try XCTUnwrap(state.begin(now: 1000)); XCTAssertEqual(retry, command)
    }
    func testEqualWakeAllowedEarlierAndFutureRejectedWithoutDefault() throws {
        let sleepID = UUID()
        var state = OfflineSleepState(context: context(), babyName: "测试", timestampMillis: 900,
            action: .wake(sleepID: sleepID, start: 900))
        state.edit(timestamp: 899); XCTAssertNotNil(state.issue(now: 1000)); XCTAssertNil(state.begin(now: 1000))
        state.edit(timestamp: 1001); XCTAssertEqual(state.issue(now: 1000), "不能选未来时刻")
        state.edit(timestamp: 900, note: "醒来")
        let (_, command) = try XCTUnwrap(state.begin(now: 1000))
        guard case let .wake(wake) = command else { return XCTFail() }
        XCTAssertEqual(wake.sleepClientUUID, sleepID); XCTAssertEqual(wake.timestampMillis, 900)
        XCTAssertEqual(wake.note, "醒来")
    }
    func testNoteUTF16DirtyAndWhitespaceRules() {
        var state = OfflineSleepState(context: context(), babyName: "测试", timestampMillis: 900)
        XCTAssertFalse(state.isDirty)
        state.edit(note: String(repeating: "😀", count: 100)); XCTAssertNil(state.issue(now: 1000))
        state.edit(note: String(repeating: "😀", count: 101)); XCTAssertNotNil(state.issue(now: 1000))
        state.edit(timestamp: 901, note: ""); XCTAssertTrue(state.isDirty)
        state.edit(timestamp: 900); XCTAssertFalse(state.isDirty)
    }
    func testDurationOverflowDoesNotTrapOrRewriteFacts() {
        XCTAssertEqual(OfflineSleepState.durationLabel(start: .min, end: 0), "时长超出可显示范围")
        XCTAssertEqual(OfflineSleepState.durationLabel(start: 900, end: 900), "时长 0 分钟")
        XCTAssertEqual(OfflineSleepState.durationLabel(start: 0, end: 120_000), "时长 2 分钟")
    }
    func testLegacyAndOpaqueAreReadOnly() throws {
        for (end, schema) in [(Optional<Int64>(950), 2), (nil, 99), (nil, 2)] {
            let json = try RecordPayloadCodec.encode(.init(payload: .sleep()))
            let record = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: "sleep", timestampMillis: 900,
                endTimestampMillis: end, note: nil, payloadJSON: json, payloadSchemaVersion: schema,
                payloadDocument: RecordPayloadCodec.decode(type: .sleep, payloadJSON: json, schemaVersion: schema),
                updatedAt: 1000, syncDirty: true, deletedAt: nil)
            let value = OfflineSleep(record: record, interval: SleepProjection.project(sleepClientUUID: record.clientUUID.uuidString,
                startTimestamp: 900, legacyEndTimestamp: end), observations: [])
            XCTAssertEqual(OfflineSleepState.supports(value), end == nil && schema == 2)
        }
    }
    func testRealFileStartWakeEqualAndReopenOriginalBabyIndependentNotes() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("sleep-ui-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2026-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        var start = OfflineSleepState(context: context, babyName: a.nickname, timestampMillis: 900)
        start.edit(note: "睡下备注")
        let (_, startCommand) = try XCTUnwrap(start.begin(now: 1000))
        guard case let .start(draft) = startCommand else { return XCTFail() }
        _ = try await store.selectBaby(b.clientUUID)
        _ = try await store.createSleepStart(draft, context: context)
        var wake = OfflineSleepState(context: context, babyName: a.nickname, timestampMillis: 900,
            action: .wake(sleepID: start.clientUUID, start: 900))
        wake.edit(note: "醒来备注")
        let (_, wakeCommand) = try XCTUnwrap(wake.begin(now: 1000))
        guard case let .wake(wakeDraft) = wakeCommand else { return XCTFail() }
        _ = try await store.recordWake(wakeDraft, context: context)
        let reopened = try OfflineStore(path: path)
        let page = try await reopened.sleepPage(baby: a.clientUUID)
        let result = try XCTUnwrap(page.sleeps[start.clientUUID])
        XCTAssertEqual(result.interval.endTimestamp, 900); XCTAssertTrue(result.interval.isProvisional)
        XCTAssertEqual(result.record.note, "睡下备注"); XCTAssertEqual(result.observations.first?.note, "醒来备注")
        XCTAssertEqual(result.observations.first?.clientUUID, wake.clientUUID)
        XCTAssertNil(page.latestOpenSleepClientUUID); XCTAssertNil(result.record.endTimestampMillis)
        let other = try await reopened.sleepPage(baby: b.clientUUID); XCTAssertTrue(other.page.records.isEmpty)
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineSleepModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testModelStartWakeDuplicateFailureRetryAndOriginalBaby() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2026-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        let model = OfflineSleepModel(store: store)
        model.openStart(context: context, babyName: a.nickname, now: 900)
        model.edit(note: "睡下备注")
        let id = try XCTUnwrap(model.editor?.clientUUID)
        await store.failNextConfirmation(at: .beforeCommit)
        model.confirm(now: 1000); model.confirm(now: 1000)
        try await settle { model.editor?.error != nil }
        XCTAssertNil(model.completion); XCTAssertEqual(model.editor?.clientUUID, id)
        let empty = try await store.sleepPage(baby: a.clientUUID); XCTAssertTrue(empty.page.records.isEmpty)
        model.close(); model.cancelDiscard(); XCTAssertNotNil(model.editor)
        _ = try await store.selectBaby(b.clientUUID)
        model.confirm(now: 1000); model.confirm(now: 1000)
        try await settle { model.editor == nil }; XCTAssertEqual(model.message, "已保存本机睡下记录")
        model.openWake(context: context, babyName: a.nickname, now: 900)
        try await settle { !model.loading }
        XCTAssertEqual(model.editor?.action, .wake(sleepID: id, start: 900))
        model.edit(note: "醒来备注")
        let wakeID = model.editor?.clientUUID
        await store.failNextConfirmation(at: .beforeCommit)
        model.confirm(now: 1000); model.confirm(now: 1000)
        try await settle { model.editor?.error != nil }
        XCTAssertEqual(model.editor?.clientUUID, wakeID)
        let afterFailure = try await store.sleepPage(baby: a.clientUUID)
        XCTAssertTrue(afterFailure.sleeps[id]?.interval.isOpen == true)
        XCTAssertTrue(afterFailure.sleeps[id]?.observations.isEmpty == true)
        model.confirm(now: 1000); model.confirm(now: 1000)
        try await settle { model.editor == nil }
        let page = try await store.sleepPage(baby: a.clientUUID)
        XCTAssertEqual(page.sleeps[id]?.observations.count, 1)
        XCTAssertEqual(page.sleeps[id]?.record.note, "睡下备注")
        XCTAssertEqual(page.sleeps[id]?.observations.first?.note, "醒来备注")
    }
    func testDiscardAndMissingOpenDoNotWrite() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let model = OfflineSleepModel(store: store)
        model.openStart(context: context, babyName: baby.nickname, now: 900)
        model.edit(note: "放弃"); model.close(); XCTAssertTrue(model.showingDiscard)
        model.discard(); XCTAssertNil(model.editor)
        model.openWake(context: context, babyName: baby.nickname, now: 900)
        try await settle { !model.loading }; XCTAssertNil(model.editor); XCTAssertNotNil(model.message)
        let page = try await store.sleepPage(baby: baby.clientUUID); XCTAssertTrue(page.page.records.isEmpty)
    }
    func testBatchRefreshFailureKeepsSnapshotAndCommitResultSeparate() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("sleep-read-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let sleep = OfflineSleepModel(store: store)
        sleep.openStart(context: context, babyName: baby.nickname, now: 900); sleep.confirm(now: 1000)
        try await settle { sleep.editor == nil }
        let owner = OfflinePeeModel(store: store); owner.refresh(); try await settle { !owner.busy }
        let old = owner.records; XCTAssertEqual(owner.sleeps.count, 1)
        let db = try SQLiteConnection(path: path)
        try db.execute("ALTER TABLE wake_observations RENAME TO unavailable_wakes")
        owner.refresh(); try await settle { !owner.busy }
        XCTAssertEqual(owner.records, old); XCTAssertEqual(owner.sleeps.count, 1)
        XCTAssertNotNil(owner.message); XCTAssertEqual(sleep.message, "已保存本机睡下记录")
    }
}
#endif
