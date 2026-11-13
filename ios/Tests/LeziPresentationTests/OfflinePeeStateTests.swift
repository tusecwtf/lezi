import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflinePeeStateTests: XCTestCase {
    @MainActor
    func testRealCommitKeepsOriginalBabyAfterSelectionChanges() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = try OfflineStore(path: folder.appendingPathComponent("offline.sqlite").path, clock: { 1000 })
        let babyA = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let babyB = try await store.createBaby(nickname: "乙", birthday: "2026-01-02")
        let captured = try await store.selectBaby(babyA.clientUUID)
        var state = OfflinePeeState(babyClientUUID: babyA.clientUUID, babyName: babyA.nickname, timestampMillis: 900)
        state.edit(note: "仍属于甲")
        _ = try await store.selectBaby(babyB.clientUUID)
        guard case let .confirmed(frozen) = state.confirm(nowMillis: 1000) else { return XCTFail() }
        XCTAssertTrue(state.session.isSaving)
        let saved = try await store.confirmPee(frozen.draft, context: captured)
        XCTAssertEqual(saved.babyClientUUID, babyA.clientUUID)
        XCTAssertEqual(saved.note, "仍属于甲")
        state.finish(attemptID: frozen.attemptID)
        XCTAssertEqual(state.session.phase, .completed)
        let pageA = try await store.records(baby: babyA.clientUUID)
        let pageB = try await store.records(baby: babyB.clientUUID)
        XCTAssertEqual(pageA.records.map(\.clientUUID), [state.draft.clientUUID])
        XCTAssertTrue(pageB.records.isEmpty)
    }

    @MainActor
    func testDuplicateDispatchCannotCommitBehindFailure() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        var state = OfflinePeeState(babyClientUUID: baby.clientUUID, babyName: baby.nickname, timestampMillis: 900)
        guard case let .confirmed(frozen) = state.confirm(nowMillis: 1000) else { return XCTFail() }
        XCTAssertTrue(state.beginPersistence(frozen))
        // A repeated delivery cannot launch a second task while the first is suspended.
        XCTAssertFalse(state.beginPersistence(frozen))
        await store.failNextConfirmation(at: .beforeCommit)
        do {
            _ = try await store.confirmPee(frozen.draft, context: context)
            XCTFail("Expected rollback")
        } catch {
            state.finish(attemptID: frozen.attemptID, failure: "保存失败")
        }
        let stored = try await store.record(frozen.draft.clientUUID)
        XCTAssertNil(stored)
        XCTAssertEqual(state.session.phase, .editing)
        XCTAssertEqual(state.session.saveFailure, "保存失败")
        XCTAssertFalse(state.beginPersistence(frozen))
        XCTAssertEqual(state.draft.confirmation, frozen.draft)
    }

    func testEditsKeepIdentityAndProduceOnlyPee() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "年年", timestampMillis: 1000)
        let original = state.draft
        state.edit(timestampMillis: 900, amount: 3, note: "观察")
        XCTAssertEqual(state.draft.clientUUID, original.clientUUID)
        XCTAssertEqual(state.draft.babyClientUUID, original.babyClientUUID)
        XCTAssertEqual(state.draft.confirmation.payload, .pee(amount: 3))
        XCTAssertEqual(state.draft.confirmation.photoClientUUIDs, [])
        XCTAssertNil(state.draft.confirmation.endTimestampMillis)
        XCTAssertEqual(state.session.phase, .editing)
    }
    func testConfirmationFreezesAndFailureRetainsDraftForRetry() throws {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "年年", timestampMillis: 1000)
        state.edit(note: "保留备注")
        let draft = state.draft
        guard case let .confirmed(first) = state.confirm(nowMillis: 1000) else { return XCTFail("Expected confirmation") }
        XCTAssertTrue(state.accepts(first))
        XCTAssertTrue(state.beginPersistence(first))
        XCTAssertFalse(state.beginPersistence(first))
        state.edit(amount: 3)
        XCTAssertEqual(state.draft, draft)
        XCTAssertEqual(state.confirm(nowMillis: 1000), .ignored)
        state.finish(attemptID: first.attemptID, failure: "写入失败")
        XCTAssertEqual(state.draft, draft)
        XCTAssertEqual(state.session.phase, .editing)
        XCTAssertEqual(state.session.saveFailure, "写入失败")
        guard case let .confirmed(retry) = state.confirm(nowMillis: 1000) else { return XCTFail("Expected retry") }
        XCTAssertNotEqual(first.attemptID, retry.attemptID)
        XCTAssertTrue(state.beginPersistence(retry))
        XCTAssertFalse(state.beginPersistence(first))
        state.finish(attemptID: first.attemptID)
        XCTAssertTrue(state.accepts(retry))
        state.finish(attemptID: retry.attemptID)
        XCTAssertEqual(state.session.phase, .completed)
    }
    func testReplacementAndInvalidationRejectOldResults() {
        let babyA = UUID(), babyB = UUID()
        var original = OfflinePeeState(babyClientUUID: babyA, babyName: "甲", timestampMillis: 1000)
        guard case let .confirmed(first) = original.confirm(nowMillis: 1000) else { return XCTFail() }
        var replacement = OfflinePeeState(babyClientUUID: babyB, babyName: "乙", timestampMillis: 1000)
        replacement.finish(attemptID: first.attemptID)
        XCTAssertFalse(replacement.accepts(first))
        XCTAssertEqual(replacement.session.phase, .editing)
        XCTAssertEqual(original.draft.babyClientUUID, babyA)
        original.invalidate()
        original.finish(attemptID: first.attemptID)
        XCTAssertEqual(original.session.phase, .invalidated)
    }
    func testInvalidAmountDoesNotSubmit() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "年年", timestampMillis: 1000)
        state.edit(amount: 0)
        guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
        XCTAssertEqual(state.session.phase, .editing)
    }
}

#if canImport(SwiftUI)
/// These exercise the real ObservableObject task orchestration on the Apple test gate.
/// They are intentionally absent from Linux's test count.
@MainActor
final class OfflinePeeModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while !condition(), clock.now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition(), "Model operation did not reach its expected terminal state")
    }

    func testDuplicateCallbackCannotSaveBehindRollbackFailure() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh()
        try await settle { !model.busy }
        model.openEditor()
        model.editor?.edit(note: "保留草稿")
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen)
        model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        // Queue a read behind the actor's queued save work: no second hidden COMMIT is permitted.
        let stored = try await store.record(frozen.draft.clientUUID)
        XCTAssertNil(stored)
        XCTAssertEqual(model.editor?.draft.note, "保留草稿")
        XCTAssertNotNil(model.editor?.session.saveFailure)
    }

    func testRealModelSaveKeepsCapturedBabyAndRefreshesSelectedList() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let babyA = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let babyB = try await store.createBaby(nickname: "乙", birthday: "2026-01-02")
        _ = try await store.selectBaby(babyA.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh()
        try await settle { !model.busy }
        model.openEditor()
        model.select(babyB.clientUUID)
        try await settle { !model.busy }
        XCTAssertEqual(model.selected?.babyClientUUID, babyB.clientUUID)
        XCTAssertEqual(model.editor?.draft.babyClientUUID, babyA.clientUUID)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(frozen)
        try await settle { model.editor == nil && !model.busy }
        let stored = try await store.record(frozen.draft.clientUUID)
        XCTAssertEqual(stored?.babyClientUUID, babyA.clientUUID)
        XCTAssertTrue(model.records.isEmpty)
        XCTAssertEqual(model.selected?.babyClientUUID, babyB.clientUUID)
        XCTAssertTrue(model.message?.contains("甲") == true)
    }
}
#endif
