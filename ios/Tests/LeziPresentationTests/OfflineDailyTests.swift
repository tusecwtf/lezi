import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineDailyStateTests: XCTestCase {
    func testDiaryBodyRequiredSeparatelyFromNoteAndNoFallback() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "甲", timestampMillis: 900, kind: .diary)
        state.edit(note: "不能替代正文", body: " \n\u{1c}\u{a0}")
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.message, "正文不能为空")
        guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
        XCTAssertEqual(state.draft.payload, .diary(body: ""))
        XCTAssertEqual(state.session.phase, .editing)
        state.edit(body: "  正文 \n")
        XCTAssertEqual(state.draft.payload, .diary(body: "正文"))
        XCTAssertEqual(state.draft.confirmation.note, "不能替代正文")
    }
    func testUTF16LimitsPreserveWholeUserInputIncludingCombiningCharacters() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "甲", timestampMillis: 900, kind: .diary)
        state.edit(note: String(repeating: "e\u{301}", count: 100), body: String(repeating: "😀", count: 400))
        XCTAssertNil(state.featureIssue(nowMillis: 1000))
        let overlong = state.draft.body + "e\u{301}"
        state.edit(body: overlong)
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "body")
        XCTAssertEqual(state.draft.body, overlong) // Never truncate into a surrogate or grapheme.
        state.edit(note: String(repeating: "😀", count: 101))
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "note")
        XCTAssertEqual(state.draft.note.utf16.count, 202)
    }
    func testKotlinTrimAndBlankNoteSemantics() {
        XCTAssertEqual(OfflineRecordText.trimmed("\u{1c}\u{a0}正文\u{2007}\n"), "正文")
        XCTAssertEqual(OfflineRecordText.trimmed("\u{85}"), "\u{85}") // NEL is not Kotlin whitespace.
        XCTAssertNil(OfflineRecordText.optional(" \u{1f}\t\u{a0}"))
        XCTAssertEqual(OfflineRecordText.optional(" 备注 \n"), "备注")
    }
    func testSimpleDefaultsAndFutureFactRefusal() {
        for kind in [OfflineRecordKind.bath, .walk] {
            var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "甲", timestampMillis: 900, kind: kind)
            XCTAssertEqual(state.draft.payload, kind == .bath ? .bath : .walk)
            XCTAssertEqual(state.draft.note, "")
            XCTAssertNil(state.draft.confirmation.endTimestampMillis)
            XCTAssertNil(state.draft.confirmation.note)
            state.edit(timestampMillis: 1001)
            XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "time")
            guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
            XCTAssertEqual(state.session.phase, .editing)
        }
    }
    func testDiarySwitchPreservesBodyAndNELIsNotBlank() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "甲", timestampMillis: 900, kind: .diary)
        state.edit(body: "\u{85}")
        XCTAssertNil(state.featureIssue(nowMillis: 1000))
        XCTAssertEqual(state.draft.payload.validationErrors(), [])
        state.edit(kind: .bath)
        XCTAssertEqual(state.draft.payload, .bath)
        state.edit(kind: .diary)
        XCTAssertEqual(state.draft.body, "\u{85}")
        state.edit(body: "\u{1c}")
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "body")
    }
    @MainActor
    func testLegacyLongBodyLoadsUntruncatedAndOnlyUnchangedBodyMayExceedInputLimit() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let legacy = String(repeating: "😀", count: 450)
        let input = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
                                             item: .builtIn(.diary)!, timestampMillis: 900, payload: .diary(body: legacy))
        let row = try await store.confirmRecord(input, context: context)
        var state = try XCTUnwrap(OfflinePeeState(record: row, babyName: baby.nickname))
        XCTAssertEqual(state.draft.body, legacy)
        state.edit(note: "  修改备注  ")
        XCTAssertNil(state.featureIssue(nowMillis: 1000))
        guard case let .confirmed(frozen) = state.confirm(nowMillis: 1000) else { return XCTFail() }
        let saved = try await store.confirmRecord(frozen.draft, context: context, expectedUpdatedAt: state.expectedUpdatedAt)
        XCTAssertEqual(saved.payloadDocument?.payload, .diary(body: legacy))
        XCTAssertEqual(saved.note, "修改备注")
        var changed = try XCTUnwrap(OfflinePeeState(record: saved, babyName: baby.nickname))
        changed.edit(body: legacy + "改")
        XCTAssertEqual(changed.featureIssue(nowMillis: 1000)?.fieldID, "body")
        XCTAssertEqual(changed.draft.body, legacy + "改")
    }
    @MainActor
    func testDailyKindsRoundTripAndEditTypeFrozenWithoutIntervals() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        for kind in [OfflineRecordKind.diary, .bath, .walk] {
            var state = OfflinePeeState(babyClientUUID: baby.clientUUID, babyName: baby.nickname, timestampMillis: 900, kind: kind)
            state.edit(note: " \n", body: "正文")
            guard case let .confirmed(frozen) = state.confirm(nowMillis: 1000) else { return XCTFail() }
            let row = try await store.confirmRecord(frozen.draft, context: context)
            var edit = try XCTUnwrap(OfflinePeeState(record: row, babyName: baby.nickname))
            edit.edit(kind: .pee)
            XCTAssertEqual(edit.draft.kind, kind)
            XCTAssertEqual(edit.draft.payload, row.payloadDocument?.payload)
            XCTAssertNil(row.note)
            XCTAssertNil(row.endTimestampMillis)
            _ = try await store.deleteRecord(row.clientUUID, context: context, expectedUpdatedAt: row.updatedAt)
        }
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineDailyModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let clock = ContinuousClock(), deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), clock.now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testDiaryValidationThenRealEditAndSimpleRecordDeletionRemainIsolated() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh(); try await settle { !model.busy }
        var ids: [UUID] = []
        for kind in [OfflineRecordKind.diary, .bath, .walk] {
            model.openEditor(); model.editor?.edit(note: "  备注  ", kind: kind)
            if kind == .diary {
                guard case .explanation? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
                let empty = try await store.recordPage(baby: baby.clientUUID)
                XCTAssertTrue(empty.records.isEmpty)
                model.editor?.edit(body: "  日记正文  ")
            }
            guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
            ids.append(frozen.draft.clientUUID)
            model.persist(frozen); model.persist(frozen)
            try await settle { model.editor == nil && !model.busy }
        }
        let diary = try XCTUnwrap(model.records.first(where: { $0.clientUUID == ids[0] }))
        model.openEditor(record: diary); model.editor?.edit(body: "修改正文")
        guard case let .confirmed(edit)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(edit); try await settle { model.editor == nil && !model.busy }
        let walk = try XCTUnwrap(model.records.first(where: { $0.clientUUID == ids[2] }))
        model.openEditor(record: walk); model.requestDelete(); model.confirmDelete()
        try await settle { model.editor == nil && !model.busy }
        XCTAssertEqual(Set(model.records.map(\.clientUUID)), Set(ids.prefix(2)))
        let after = try await store.recordDetails(ids[0])
        XCTAssertEqual(after?.payloadDocument?.payload, .diary(body: "修改正文"))
        XCTAssertEqual(after?.note, "备注")
    }
    func testDiaryFailureAndDirtyDiscardPreserveInputWithoutWriting() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "甲", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh(); try await settle { !model.busy }
        model.openEditor(); model.editor?.edit(kind: .diary, body: "保留正文")
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.body, "保留正文")
        model.closeEditor(); model.cancelDiscard()
        XCTAssertEqual(model.editor?.draft.body, "保留正文")
        model.closeEditor(); model.discardChanges()
        let page = try await store.recordPage(baby: baby.clientUUID)
        XCTAssertTrue(page.records.isEmpty)
    }
}
#endif
