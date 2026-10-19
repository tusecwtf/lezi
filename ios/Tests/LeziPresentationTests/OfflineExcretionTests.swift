import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineExcretionStateTests: XCTestCase {
    func testAndroidDefaultsAndExactChoiceLabels() {
        let baby = UUID()
        let expected: [RecordPayload] = [.pee(amount: 2), .stool(amount: 3, consistency: 3, color: 0),
                                        .bothDiaper(peeAmount: 2, stoolAmount: 3, stoolConsistency: 3, stoolColor: 0)]
        for (kind, payload) in zip(OfflineExcretionKind.excretionCases, expected) {
            let draft = OfflinePeeDraft(babyClientUUID: baby, timestampMillis: 900, kind: kind)
            XCTAssertEqual(draft.payload, payload)
            XCTAssertEqual(draft.confirmation.item, .builtIn(kind.recordType)!)
            XCTAssertTrue(draft.confirmation.photoClientUUIDs.isEmpty)
            XCTAssertNil(draft.confirmation.endTimestampMillis)
        }
        XCTAssertEqual((1...3).map(OfflineExcretionLabels.pee), ["小", "中", "大"])
        XCTAssertEqual((1...4).map(OfflineExcretionLabels.amount), ["一点", "偏少", "正常", "偏多"])
        XCTAssertEqual((1...4).map(OfflineExcretionLabels.consistency), ["稀", "偏软", "正常", "偏硬"])
        XCTAssertEqual((0...7).map(OfflineExcretionLabels.color), ["未选", "白", "黄", "橙", "褐", "绿", "红", "黑"])
    }
    func testNewTypeSwitchKeepsIdentityAndEnteredValuesWithoutWriting() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "甲", timestampMillis: 900)
        let id = state.draft.clientUUID
        state.edit(amount: 3, note: "草稿", kind: .bothDiaper, stoolAmount: 1, stoolConsistency: 2, stoolColor: 7)
        state.edit(kind: .pee)
        XCTAssertEqual(state.draft.payload, .pee(amount: 3))
        state.edit(kind: .bothDiaper)
        XCTAssertEqual(state.draft.payload, .bothDiaper(peeAmount: 3, stoolAmount: 1, stoolConsistency: 2, stoolColor: 7))
        XCTAssertEqual(state.draft.clientUUID, id)
        XCTAssertEqual(state.draft.note, "草稿")
        XCTAssertEqual(state.dismissDecision, .confirmDiscard)
        XCTAssertEqual(state.session.phase, .editing)
    }
    func testInvalidStoolChoicesNeverConfirm() {
        for change in [(0, 3, 0), (5, 3, 0), (3, 0, 0), (3, 5, 0), (3, 3, -1), (3, 3, 8)] {
            for kind in [OfflineExcretionKind.poop, .bothDiaper] {
                var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "甲", timestampMillis: 900, kind: kind)
                state.edit(stoolAmount: change.0, stoolConsistency: change.1, stoolColor: change.2)
                guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
                XCTAssertEqual(state.session.phase, .editing)
            }
        }
    }
    func testStoolAndBothOpaqueInvalidAndIntervalRowsStayReadOnly() {
        for type in [RecordType.poop, .bothDiaper] {
            for (json, schema, end) in [("{}", 99, nil as Int64?),
                                         ("{\"future\":true}", 2, nil),
                                         ("{\"stool_color\":8}", 2, nil),
                                         ("{}", 2, 950)] {
                let row = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: type.rawValue,
                                        timestampMillis: 900, endTimestampMillis: end, note: nil,
                                        payloadJSON: json, payloadSchemaVersion: schema,
                                        payloadDocument: RecordPayloadCodec.decode(type: type, payloadJSON: json, schemaVersion: schema),
                                        updatedAt: 1000, syncDirty: true, deletedAt: nil)
                XCTAssertFalse(OfflinePeeState.canEdit(row))
                XCTAssertNil(OfflinePeeState(record: row, babyName: "甲"))
            }
        }
    }
    @MainActor
    func testThreeKindsRoundTripEditTypeLockOriginalBabyAndSoftDelete() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let a = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "乙", birthday: "2026-01-02")
        let context = try await store.selectBaby(a.clientUUID)
        for kind in OfflineExcretionKind.excretionCases {
            var create = OfflinePeeState(babyClientUUID: a.clientUUID, babyName: a.nickname, timestampMillis: 900, kind: kind)
            create.edit(amount: 3, note: "原值", stoolAmount: 1, stoolConsistency: 2, stoolColor: 7)
            guard case let .confirmed(frozen) = create.confirm(nowMillis: 1000) else { return XCTFail() }
            let row = try await store.confirmRecord(frozen.draft, context: context)
            _ = try await store.selectBaby(b.clientUUID)
            var edit = try XCTUnwrap(OfflinePeeState(record: row, babyName: a.nickname))
            XCTAssertEqual(edit.draft.payload, frozen.draft.payload)
            XCTAssertEqual(edit.expectedUpdatedAt, row.updatedAt)
            let original = edit.draft
            edit.edit(kind: kind == .pee ? .poop : .pee)
            XCTAssertEqual(edit.draft, original)
            edit.edit(amount: 1, note: "修改值", stoolAmount: 4, stoolConsistency: 4, stoolColor: 6)
            guard case let .confirmed(update) = edit.confirm(nowMillis: 1000) else { return XCTFail() }
            XCTAssertTrue(edit.beginPersistence(update))
            XCTAssertFalse(edit.beginPersistence(update))
            let saved = try await store.confirmRecord(update.draft, context: context, expectedUpdatedAt: edit.expectedUpdatedAt)
            XCTAssertEqual(saved.babyClientUUID, a.clientUUID)
            XCTAssertEqual(saved.type, kind.recordType)
            XCTAssertEqual(saved.payloadDocument?.payload, edit.draft.payload)
            _ = try await store.deleteRecord(saved.clientUUID, context: context, expectedUpdatedAt: saved.updatedAt)
            let deleted = try await store.recordDetails(saved.clientUUID)
            XCTAssertNotNil(deleted?.deletedAt)
            XCTAssertEqual(deleted?.payloadJSON, saved.payloadJSON)
        }
    }
    @MainActor
    func testStoolRollbackPreservesFrozenDraftAndCannotDispatchTwice() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        for kind in [OfflineExcretionKind.poop, .bothDiaper] {
            var state = OfflinePeeState(babyClientUUID: baby.clientUUID, babyName: baby.nickname, timestampMillis: 900, kind: kind)
            state.edit(note: "保留", stoolColor: 5)
            guard case let .confirmed(frozen) = state.confirm(nowMillis: 1000) else { return XCTFail() }
            XCTAssertTrue(state.beginPersistence(frozen))
            XCTAssertFalse(state.beginPersistence(frozen))
            state.edit(kind: .pee)
            XCTAssertEqual(state.draft.kind, kind)
            await store.failNextConfirmation(at: .beforeCommit)
            do { _ = try await store.confirmRecord(frozen.draft, context: context); XCTFail() }
            catch { state.finish(attemptID: frozen.attemptID, failure: "失败") }
            let row = try await store.recordDetails(frozen.draft.clientUUID)
            XCTAssertNil(row)
            XCTAssertEqual(state.draft.confirmation, frozen.draft)
            XCTAssertEqual(state.session.phase, .editing)
        }
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineExcretionModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let clock = ContinuousClock(), limit: Duration = .seconds(5)
        let deadline = clock.now.advanced(by: limit)
        while !condition(), clock.now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testPoopAndBothUseRealSharedCreateEditDeleteTasks() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh(); try await settle { !model.busy }
        for kind in [OfflineExcretionKind.poop, .bothDiaper] {
            model.openEditor()
            model.editor?.edit(amount: 3, note: "新建", kind: kind, stoolAmount: 1, stoolConsistency: 2, stoolColor: 3)
            guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
            model.persist(frozen); model.persist(frozen)
            try await settle { model.editor == nil && !model.busy }
            let row = try XCTUnwrap(model.records.first(where: { $0.clientUUID == frozen.draft.clientUUID }))
            XCTAssertEqual(row.payloadDocument?.payload, frozen.draft.payload)
            model.openEditor(record: row)
            model.editor?.edit(kind: .pee)
            XCTAssertEqual(model.editor?.draft.kind, kind)
            model.editor?.edit(note: "修改", stoolAmount: 4, stoolConsistency: 4, stoolColor: 6)
            guard case let .confirmed(edit)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
            model.persist(edit)
            try await settle { model.editor == nil && !model.busy }
            let updated = try XCTUnwrap(model.records.first(where: { $0.clientUUID == row.clientUUID }))
            XCTAssertEqual(updated.payloadDocument?.payload, edit.draft.payload)
            XCTAssertEqual(updated.updatedAt, row.updatedAt + 1)
            model.openEditor(record: updated)
            model.requestDelete(); model.confirmDelete(); model.confirmDelete()
            try await settle { model.editor == nil && !model.busy }
            XCTAssertEqual(model.message, "已删除本机\(kind.label)")
            let deleted = try await store.recordDetails(row.clientUUID)
            XCTAssertNotNil(deleted?.deletedAt)
        }
    }
    func testBothRollbackAndDirtyCancelDoNotWrite() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh(); try await settle { !model.busy }
        model.openEditor()
        model.editor?.edit(kind: .bothDiaper, stoolColor: 6)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.confirmation, frozen.draft)
        model.closeEditor()
        XCTAssertTrue(model.showingDiscardConfirmation)
        model.discardChanges()
        XCTAssertNil(model.editor)
        let page = try await store.recordPage(baby: baby.clientUUID)
        XCTAssertTrue(page.records.isEmpty)
    }
}
#endif
