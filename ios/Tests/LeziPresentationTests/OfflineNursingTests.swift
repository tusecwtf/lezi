import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineNursingStateTests: XCTestCase {
    private func draft(_ left: String = "5", _ right: String = "0", _ order: String = "LR", _ amount: String = "") -> OfflineNursingDraft {
        var value = OfflineNursingDraft(); value.leftMinutes = left; value.rightMinutes = right
        value.order = order; value.amountMl = amount; return value
    }
    func testAllOrdersSingleAndDoubleSidedFactsWithoutInventedMatchingRules() {
        XCTAssertEqual(OfflineNursingDraft.orders, ["L", "LR", "RL", "R"])
        XCTAssertEqual(OfflineNursingDraft.orders.map(OfflineNursingDraft.orderLabel), ["仅左", "先左后右", "先右后左", "仅右"])
        XCTAssertEqual(OfflineNursingDraft().issue?.message, "请填写左侧或右侧喂养时长")
        for order in OfflineNursingDraft.orders {
            for (left, right) in [(5, 0), (0, 8), (5, 8), (1440, 1440)] {
                XCTAssertEqual(draft(String(left), String(right), order).payload,
                    .nursing(leftMinutes: left, rightMinutes: right, order: order, amountMl: nil, recordMode: "end"))
            }
        }
        XCTAssertEqual(draft("５", "٠", "R", "８０").payload,
            .nursing(leftMinutes: 5, rightMinutes: 0, order: "R", amountMl: 80, recordMode: "end"))
    }
    func testStrictRawTextAndOptionalAmountNeverFallbackToSuccessfulPayload() {
        for raw in ["", "-1", "+5", " 5", "5.0", "5分", "10000", "00005", "1441", "𝟓"] {
            let value = draft(raw)
            XCTAssertNil(value.payload, raw); XCTAssertEqual(value.leftMinutes, raw)
            XCTAssertEqual(value.issue?.fieldID, "nursing.duration")
            var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: .nursing)
            state.edit(nursing: value)
            guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail(raw) }
            XCTAssertFalse(state.draft.payload.validationErrors().isEmpty)
        }
        for raw in ["0", "1000", "0080", "+80", "80 ml", "80.0", "-1", "\u{0085}"] {
            XCTAssertNil(draft("5", "0", "LR", raw).payload, raw)
            XCTAssertEqual(draft("5", "0", "LR", raw).issue?.fieldID, "nursing.amount")
        }
        for raw in ["", " \u{001C}"] { XCTAssertEqual(draft("5", "0", "LR", raw).payload, draft().payload) }
        for raw in ["1", "999"] { XCTAssertNil(draft("5", "0", "LR", raw).issue) }
        XCTAssertEqual(draft("5", "0", "unknown").issue?.fieldID, "nursing.order")
    }
    private func row(_ payload: RecordPayload, end: Int64? = nil, schema: Int = 2) throws -> OfflineRecord {
        let json = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
        return OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: "nursing", timestampMillis: 900,
            endTimestampMillis: end, note: nil, payloadJSON: json, payloadSchemaVersion: schema,
            payloadDocument: RecordPayloadCodec.decode(type: .nursing, payloadJSON: json, schemaVersion: schema),
            updatedAt: 1000, syncDirty: true, deletedAt: nil)
    }
    func testLegacyStartAndEndPreservedAndBadNumbersRequireExplicitCorrection() throws {
        for mode in ["start", "end"] {
            let original = RecordPayload.nursing(leftMinutes: 5, rightMinutes: 3, order: "RL", amountMl: 80, recordMode: mode)
            var state = try XCTUnwrap(OfflinePeeState(record: row(original), babyName: "测试"))
            state.edit(note: "仅改备注", kind: .pee); XCTAssertEqual(state.draft.kind, .nursing)
            state.edit(note: "仅改备注"); XCTAssertEqual(state.draft.payload, original)
            XCTAssertEqual(state.draft.timestampMillis, 900)
            XCTAssertNil(state.draft.confirmation.endTimestampMillis)
        }
        for (left, right, amount) in [(2000, 0, 80), (-1, 5, 0), (0, 0, 1000)] {
            let original = RecordPayload.nursing(leftMinutes: left, rightMinutes: right, order: "L", amountMl: amount, recordMode: "start")
            var state = try XCTUnwrap(OfflinePeeState(record: row(original), babyName: "测试"))
            XCTAssertEqual(state.draft.nursing.leftMinutes, String(left)); XCTAssertEqual(state.draft.nursing.amountMl, String(amount))
            state.edit(note: "不能绕过")
            guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
            var corrected = state.draft.nursing; corrected.leftMinutes = "5"; corrected.rightMinutes = "0"; corrected.amountMl = "80"
            state.edit(nursing: corrected)
            XCTAssertEqual(state.draft.payload, .nursing(leftMinutes: 5, rightMinutes: 0, order: "L", amountMl: 80, recordMode: "start"))
        }
    }
    func testOpaqueUnknownStructureAndTimerIntervalsRemainReadOnly() throws {
        let payload = draft().payload!
        XCTAssertFalse(OfflinePeeState.canEdit(try row(payload, end: 950)))
        XCTAssertFalse(OfflinePeeState.canEdit(try row(payload, schema: 99)))
        for json in ["{\"left_min\":5,\"right_min\":0,\"order\":\"X\",\"record_mode\":\"end\"}",
                     "{\"left_min\":5,\"right_min\":0,\"order\":\"L\",\"record_mode\":\"X\"}"] {
            let record = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: "nursing", timestampMillis: 900,
                endTimestampMillis: nil, note: nil, payloadJSON: json, payloadSchemaVersion: 2,
                payloadDocument: RecordPayloadCodec.decode(type: .nursing, payloadJSON: json, schemaVersion: 2),
                updatedAt: 1000, syncDirty: true, deletedAt: nil)
            XCTAssertFalse(OfflinePeeState.canEdit(record))
        }
    }
    func testTypeSwitchRetainsDraftAndFutureFactsCannotConfirm() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: .nursing)
        let value = draft("5", "3", "RL", "80")
        state.edit(nursing: value); state.edit(kind: .diary, body: "正文"); state.edit(kind: .nursing)
        XCTAssertEqual(state.draft.nursing, value); XCTAssertEqual(state.dismissDecision, .confirmDiscard)
        state.edit(timestampMillis: 1001)
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "time")
        guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
    }
    @MainActor
    func testLegacyOutOfConfirmationRangeRequiresRepairBeforeRealCommit() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let original = RecordPayload.nursing(leftMinutes: 2000, rightMinutes: 0, order: "L", amountMl: 0, recordMode: "start")
        let row = try await store.confirmRecord(.init(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.nursing)!, timestampMillis: 900, payload: original), context: context)
        var state = try XCTUnwrap(OfflinePeeState(record: row, babyName: baby.nickname))
        state.edit(note: "只改备注")
        guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
        let unchanged = try await store.recordDetails(row.clientUUID)
        XCTAssertEqual(unchanged?.payloadDocument?.payload, original)
        var fixed = state.draft.nursing; fixed.leftMinutes = "5"; fixed.amountMl = ""
        state.edit(nursing: fixed)
        guard case let .confirmed(frozen) = state.confirm(nowMillis: 1000) else { return XCTFail() }
        let updated = try await store.confirmRecord(frozen.draft, context: context, expectedUpdatedAt: state.expectedUpdatedAt)
        XCTAssertEqual(updated.payloadDocument?.payload, .nursing(leftMinutes: 5, rightMinutes: 0, order: "L", amountMl: nil, recordMode: "start"))
    }
    @MainActor
    func testFileStoreReopenModePreservingEditAndSoftDelete() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("nursing-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-31")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        for mode in ["start", "end"] {
            let original = RecordPayload.nursing(leftMinutes: 5, rightMinutes: 0, order: "LR", amountMl: nil, recordMode: mode)
            let saved = try await store.confirmRecord(.init(clientUUID: UUID(), babyClientUUID: a.clientUUID,
                item: .builtIn(.nursing)!, timestampMillis: 900, payload: original), context: context)
            let reopened = try OfflineStore(path: path, clock: { 1000 })
            let reread = try await reopened.recordDetails(saved.clientUUID)
            var state = try XCTUnwrap(OfflinePeeState(record: XCTUnwrap(reread), babyName: a.nickname))
            _ = try await reopened.selectBaby(b.clientUUID)
            var edit = state.draft.nursing; edit.rightMinutes = "3"; edit.order = "RL"; edit.amountMl = "80"
            state.edit(nursing: edit)
            guard case let .confirmed(frozen) = state.confirm(nowMillis: 1000) else { return XCTFail() }
            XCTAssertTrue(state.beginPersistence(frozen)); XCTAssertFalse(state.beginPersistence(frozen))
            let updated = try await reopened.confirmRecord(frozen.draft, context: context, expectedUpdatedAt: state.expectedUpdatedAt)
            XCTAssertEqual(updated.babyClientUUID, a.clientUUID)
            XCTAssertEqual(updated.payloadDocument?.payload, .nursing(leftMinutes: 5, rightMinutes: 3, order: "RL", amountMl: 80, recordMode: mode))
            XCTAssertEqual(updated.timestampMillis, 900); XCTAssertNil(updated.endTimestampMillis)
            let deleted = try await reopened.deleteRecord(updated.clientUUID, context: context, expectedUpdatedAt: updated.updatedAt)
            XCTAssertTrue(deleted)
            let finalStore = try OfflineStore(path: path)
            let tombstone = try await finalStore.recordDetails(updated.clientUUID); XCTAssertNotNil(tombstone?.deletedAt)
        }
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineNursingModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testRealOwnerCapturesBirthdayAcrossSelectionReopensEditsAndDeletes() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("nursing-model-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: path, clock: { now })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-31")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        _ = try await store.selectBaby(a.clientUUID)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        var value = OfflineNursingDraft(); value.leftMinutes = "5"
        model.openEditor(); model.editor?.edit(kind: .nursing, nursing: value)
        model.select(b.clientUUID); try await settle { !model.busy }
        XCTAssertEqual(model.editor?.babyBirthday, a.birthday)
        XCTAssertEqual(model.editor?.draft.babyClientUUID, a.clientUUID)
        guard case let .confirmed(create)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(create); model.persist(create); try await settle { model.editor == nil && !model.busy }
        let reopened = try OfflineStore(path: path, clock: { now })
        _ = try await reopened.selectBaby(a.clientUUID)
        let restarted = OfflinePeeModel(store: reopened); restarted.refresh(); try await settle { !restarted.busy }
        let row = try XCTUnwrap(restarted.records.first)
        XCTAssertEqual(row.payloadDocument?.payload, create.draft.payload)
        restarted.openEditor(record: row)
        XCTAssertEqual(restarted.editor?.babyBirthday, a.birthday)
        XCTAssertEqual(restarted.editor?.draft.nursing.recordMode, "end")
        restarted.editor?.edit(kind: .pee); XCTAssertEqual(restarted.editor?.draft.kind, .nursing)
        value = OfflineNursingDraft(); value.leftMinutes = "5"; value.rightMinutes = "3"; value.order = "RL"; value.amountMl = "80"; restarted.editor?.edit(nursing: value)
        guard case let .confirmed(edit)? = restarted.editor?.confirm(nowMillis: now) else { return XCTFail() }
        restarted.persist(edit); try await settle { restarted.editor == nil && !restarted.busy }
        let updated = try XCTUnwrap(restarted.records.first)
        XCTAssertEqual(updated.clientUUID, row.clientUUID)
        XCTAssertEqual(updated.payloadDocument?.payload, .nursing(leftMinutes: 5, rightMinutes: 3, order: "RL", amountMl: 80, recordMode: "end"))
        restarted.openEditor(record: updated); restarted.requestDelete(); restarted.confirmDelete()
        try await settle { restarted.editor == nil && !restarted.busy }
        let finalStore = try OfflineStore(path: path)
        let deleted = try await finalStore.recordDetails(row.clientUUID)
        XCTAssertNotNil(deleted?.deletedAt)
    }
    func testInvalidNoWriteAndCommitFailureRetainsDraftWithDiscardGate() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-31")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        model.openEditor(); var value = OfflineNursingDraft(); value.leftMinutes = "bad"
        model.editor?.edit(kind: .nursing, nursing: value)
        guard case .explanation? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        let empty = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(empty.records.isEmpty)
        value.leftMinutes = "5"; model.editor?.edit(nursing: value)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.nursing.leftMinutes, "5")
        XCTAssertEqual(model.editor?.babyBirthday, baby.birthday)
        model.closeEditor(); model.cancelDiscard(); XCTAssertNotNil(model.editor)
        model.closeEditor(); model.discardChanges(); XCTAssertNil(model.editor)
        let final = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(final.records.isEmpty)
    }
}
#endif
