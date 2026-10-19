import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

private func peeRow(baby: UUID = UUID(), payloadJSON: String = "{\"pee_amount\":2}",
                    schema: Int = 2, end: Int64? = nil) -> OfflineRecord {
    .init(clientUUID: UUID(), babyClientUUID: baby, rawType: "pee", timestampMillis: 900,
          endTimestampMillis: end, note: "原备注", payloadJSON: payloadJSON,
          payloadSchemaVersion: schema,
          payloadDocument: RecordPayloadCodec.decode(type: .pee, payloadJSON: payloadJSON, schemaVersion: schema),
          updatedAt: 1000, syncDirty: true, deletedAt: nil)
}

final class OfflinePeeEditingStateTests: XCTestCase {
    func testEditCapturesOriginalIdentityRevisionAndCleanBaseline() throws {
        let row = peeRow()
        var state = try XCTUnwrap(OfflinePeeState(record: row, babyName: "甲"))
        XCTAssertEqual(state.expectedUpdatedAt, 1000)
        XCTAssertTrue(state.isEditing)
        XCTAssertEqual(state.dismissDecision, .close)
        state.edit(amount: 3, note: "已改")
        XCTAssertEqual(state.dismissDecision, .confirmDiscard)
        XCTAssertEqual(state.draft.clientUUID, row.clientUUID)
        XCTAssertEqual(state.draft.babyClientUUID, row.babyClientUUID)
        state.edit(amount: 2, note: "原备注")
        XCTAssertFalse(state.isDirty)
        XCTAssertEqual(state.dismissDecision, .close)
    }
    func testOpaqueUnsupportedIntervalAndInvalidPeeRemainReadOnly() {
        for row in [peeRow(schema: 99), peeRow(payloadJSON: "{\"pee_amount\":2,\"future\":true}"),
                    peeRow(payloadJSON: "{\"pee_amount\":9}"), peeRow(end: 950)] {
            XCTAssertFalse(OfflinePeeState.canEdit(row))
            XCTAssertNil(OfflinePeeState(record: row, babyName: "甲"))
        }
    }
    func testDeleteClaimBlocksSaveEditExitAndDuplicateTasks() throws {
        var state = try XCTUnwrap(OfflinePeeState(record: peeRow(), babyName: "甲"))
        state.edit(note: "未保存")
        let draft = state.draft
        let first = try XCTUnwrap(state.beginDeletion())
        XCTAssertNil(state.beginDeletion())
        XCTAssertEqual(state.confirm(nowMillis: 1000), .ignored)
        XCTAssertEqual(state.dismissDecision, .ignoreBusy)
        state.edit(note: "不可写")
        XCTAssertEqual(state.draft, draft)
        state.finishDeletion(attemptID: UUID())
        XCTAssertTrue(state.isBusy)
        state.finishDeletion(attemptID: first, failure: "失败")
        XCTAssertEqual(state.draft, draft)
        XCTAssertEqual(state.deletionFailure, "失败")
        let retry = try XCTUnwrap(state.beginDeletion())
        state.finishDeletion(attemptID: first)
        XCTAssertEqual(state.deletionAttemptID, retry)
        state.finishDeletion(attemptID: retry)
        XCTAssertEqual(state.session.phase, .invalidated)
    }
    func testSaveBlocksDeleteAndCreateCannotDelete() throws {
        var edit = try XCTUnwrap(OfflinePeeState(record: peeRow(), babyName: "甲"))
        guard case .confirmed = edit.confirm(nowMillis: 1000) else { return XCTFail() }
        XCTAssertNil(edit.beginDeletion())
        XCTAssertEqual(edit.dismissDecision, .ignoreBusy)
        var create = OfflinePeeState(babyClientUUID: UUID(), babyName: "甲", timestampMillis: 900)
        XCTAssertNil(create.beginDeletion())
        XCTAssertEqual(create.dismissDecision, .close)
        create.edit(note: "草稿")
        XCTAssertEqual(create.dismissDecision, .confirmDiscard)
    }
    @MainActor
    func testRealEditAndDeleteCaptureOriginalBabyAndRevision() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let a = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "乙", birthday: "2026-01-02")
        let context = try await store.selectBaby(a.clientUUID)
        let initial = OfflinePeeDraft(babyClientUUID: a.clientUUID, timestampMillis: 900)
        let row = try await store.confirmRecord(initial.confirmation, context: context)
        var edit = try XCTUnwrap(OfflinePeeState(record: row, babyName: a.nickname))
        _ = try await store.selectBaby(b.clientUUID)
        edit.edit(amount: 3, note: "新备注")
        guard case let .confirmed(frozen) = edit.confirm(nowMillis: 1000) else { return XCTFail() }
        let saved = try await store.confirmPee(frozen.draft, context: context, expectedUpdatedAt: edit.expectedUpdatedAt)
        XCTAssertEqual(saved.babyClientUUID, a.clientUUID)
        XCTAssertEqual(saved.amount, 3)
        do {
            _ = try await store.deleteRecord(saved.clientUUID, context: context, expectedUpdatedAt: row.updatedAt)
            XCTFail("Stale deletion must fail")
        } catch { XCTAssertEqual(error as? OfflineStoreError, .staleEdit) }
        _ = try await store.deleteRecord(saved.clientUUID, context: context, expectedUpdatedAt: saved.updatedAt)
        let tombstone = try await store.recordDetails(saved.clientUUID)
        XCTAssertNotNil(tombstone?.deletedAt)
        XCTAssertEqual(tombstone?.note, "新备注")
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflinePeeEditingModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while !condition(), clock.now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition(), "Expected model terminal state")
    }
    private func setup() async throws -> (OfflineStore, OfflinePeeModel, OfflineContext, OfflineRecord) {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let draft = OfflinePeeDraft(babyClientUUID: baby.clientUUID, timestampMillis: 900, note: "原备注")
        let row = try await store.confirmRecord(draft.confirmation, context: context)
        let model = OfflinePeeModel(store: store)
        model.refresh()
        try await settle { !model.busy }
        model.openEditor(record: row)
        return (store, model, context, row)
    }
    func testDirtyExitKeepDiscardAndDeleteCancellationWriteNothing() async throws {
        let (store, model, _, row) = try await setup()
        model.editor?.edit(note: "未保存")
        model.closeEditor()
        XCTAssertTrue(model.showingDiscardConfirmation)
        model.cancelDiscard()
        model.discardChanges() // Cancelled confirmation cannot later discard.
        XCTAssertEqual(model.editor?.draft.note, "未保存")
        model.requestDelete()
        model.cancelDelete()
        model.confirmDelete() // Cancelled confirmation cannot later delete.
        XCTAssertNotNil(model.editor)
        model.closeEditor()
        // SwiftUI clears isPresented before invoking the alert button action.
        model.showingDiscardConfirmation = false
        model.discardChanges()
        XCTAssertNil(model.editor)
        let after = try await store.recordDetails(row.clientUUID)
        XCTAssertEqual(after, row)
    }
    func testEditCommitsOriginalBabyAfterSwitchAndDuplicateCallback() async throws {
        let (store, model, _, row) = try await setup()
        let b = try await store.createBaby(nickname: "乙", birthday: "2026-01-02")
        model.select(b.clientUUID)
        try await settle { !model.busy }
        model.editor?.edit(amount: 3, note: "改后")
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: 1000) else { return XCTFail() }
        model.persist(frozen); model.persist(frozen)
        model.closeEditor(); model.requestDelete()
        XCTAssertNotNil(model.editor)
        XCTAssertFalse(model.showingDeleteConfirmation)
        try await settle { model.editor == nil && !model.busy }
        let after = try await store.recordDetails(row.clientUUID)
        XCTAssertEqual(after?.babyClientUUID, row.babyClientUUID)
        XCTAssertEqual(after?.updatedAt, row.updatedAt + 1)
        XCTAssertEqual(after?.note, "改后")
        XCTAssertEqual(after?.payloadDocument?.payload, .pee(amount: 3))
        XCTAssertTrue(model.records.isEmpty)
        XCTAssertEqual(model.selected?.babyClientUUID, b.clientUUID)
    }
    func testStaleEditAndDeletePreserveDraftAndNewerFact() async throws {
        let (store, model, context, row) = try await setup()
        let changed = OfflinePeeDraft(clientUUID: row.clientUUID, babyClientUUID: row.babyClientUUID,
                                      timestampMillis: 900, amount: 1, note: "其他修改")
        let newer = try await store.confirmRecord(changed.confirmation, context: context, expectedUpdatedAt: row.updatedAt)
        model.editor?.edit(note: "我的草稿")
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: 1000) else { return XCTFail() }
        model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertNotNil(model.editor?.session.saveFailure)
        XCTAssertEqual(model.editor?.draft.note, "我的草稿")
        model.requestDelete(); model.confirmDelete()
        try await settle { model.editor?.deletionFailure != nil }
        XCTAssertEqual(model.editor?.draft.note, "我的草稿")
        let after = try await store.recordDetails(row.clientUUID)
        XCTAssertEqual(after, newer)
    }
    func testDeleteFailureDuplicateCallbackThenSuccessfulRetry() async throws {
        let (store, model, _, row) = try await setup()
        model.editor?.edit(note: "保留草稿")
        await store.failNextConfirmation(at: .beforeCommit)
        model.requestDelete()
        model.showingDeleteConfirmation = false // Native alert lifecycle.
        model.confirmDelete(); model.confirmDelete()
        XCTAssertTrue(model.editor?.isBusy == true)
        model.closeEditor()
        XCTAssertFalse(model.showingDiscardConfirmation)
        try await settle { model.editor?.deletionFailure != nil }
        let afterFailure = try await store.recordDetails(row.clientUUID)
        XCTAssertEqual(afterFailure, row)
        XCTAssertEqual(model.editor?.draft.note, "保留草稿")
        model.requestDelete(); model.confirmDelete()
        try await settle { model.editor == nil && !model.busy }
        let deleted = try await store.recordDetails(row.clientUUID)
        XCTAssertNotNil(deleted?.deletedAt)
        XCTAssertEqual(deleted?.note, "原备注") // Unsaved edits do not leak into deletion.
        XCTAssertTrue(model.records.isEmpty)
        XCTAssertEqual(model.message, "已删除本机尿记录")
    }
    func testDiscardConfirmationCannotCloseReplacementSession() async throws {
        let (_, model, _, row) = try await setup()
        model.editor?.edit(note: "旧草稿")
        model.closeEditor()
        model.editor = OfflinePeeState(record: row, babyName: "甲")
        let replacementID = model.editor?.session.context.sessionID
        model.discardChanges()
        XCTAssertEqual(model.editor?.session.context.sessionID, replacementID)
        XCTAssertNotNil(model.editor)
    }
    func testOpaqueRowRefusesEditorWithoutChangingExistingFact() async throws {
        let (store, model, _, row) = try await setup()
        model.closeEditor()
        model.openEditor(record: peeRow(baby: row.babyClientUUID, schema: 99))
        XCTAssertNil(model.editor)
        XCTAssertNotNil(model.message)
        let after = try await store.recordDetails(row.clientUUID)
        XCTAssertEqual(after, row)
    }
}
#endif
