import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineMilkStateTests: XCTestCase {
    private let kinds: [OfflineRecordKind] = [.formula, .pumpedFeed, .pumpExpress]
    func testDefaultsRecentPrecedenceAndStepAreSeparateFromFactChanges() {
        let fallback = OfflineMilkDraft()
        XCTAssertEqual(fallback.input(kind: .formula).amount, "120")
        XCTAssertEqual(fallback.input(kind: .pumpedFeed).amount, "120")
        XCTAssertEqual(fallback.input(kind: .pumpExpress).amount, "60")
        let recent: [RecordType: [Int]] = [.formula: [0, 130, 130, 140, 150, 160], .pumpExpress: [70]]
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900,
                                   kind: .formula, milkRecent: recent, milkStep: 123)
        XCTAssertEqual(state.milkStep, 5)
        XCTAssertEqual(state.draft.milk.recent[.formula], [130, 140, 150])
        XCTAssertEqual(state.draft.milk.input(kind: .formula).amount, "130")
        XCTAssertEqual(state.draft.milk.input(kind: .pumpExpress).amount, "70")
        state.setMilkStep(10)
        XCTAssertEqual(state.milkStep, 10)
        XCTAssertFalse(state.isDirty)
        XCTAssertEqual(state.draft.milk.quickAmounts(kind: .formula, step: 10), [130, 140, 150, 120])
    }
    func testStrictRawIntegerInputsPreservedAndCannotConfirm() {
        for kind in kinds {
            for text in ["", "0", "1000", "-1", "+12", "12.5", "12 ml", " 12", "00001", "NaN", "\u{1d7d9}"] {
                var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: kind)
                let milkKind = kind.milkKind!
                var milk = state.draft.milk; milk.set(.init(amount: text), kind: milkKind)
                state.edit(milk: milk)
                XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "milk.amount", text)
                XCTAssertEqual(state.draft.milk.input(kind: milkKind).amount, text)
                XCTAssertNil(milk.payload(kind: milkKind))
                guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail(text) }
                XCTAssertFalse(state.draft.payload.validationErrors().isEmpty)
            }
        }
        XCTAssertEqual(OfflineMilkDraft.integer("１２"), 12)
        XCTAssertEqual(OfflineMilkDraft.integer("٠١٢"), 12)
    }
    func testAmountBoundariesAndFormulaOptionalNullVersusZeroRoundTrip() throws {
        for kind in kinds {
            let milkKind = kind.milkKind!
            for amount in [1, 999] {
                var milk = OfflineMilkDraft(); milk.set(.init(amount: String(amount)), kind: milkKind)
                let payload = try XCTUnwrap(milk.payload(kind: milkKind))
                let encoded = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
                XCTAssertEqual(RecordPayloadCodec.decode(type: kind.recordType, payloadJSON: encoded, schemaVersion: 2).payload, payload)
                if kind != .formula { XCTAssertFalse(encoded.contains("prepared_ml")); XCTAssertFalse(encoded.contains("duration_min")) }
            }
        }
        var milk = OfflineMilkDraft(); var input = OfflineMilkInput(amount: "150")
        input.prepared = " \u{1c}"; input.duration = "\t"
        milk.set(input, kind: .formula)
        XCTAssertEqual(milk.payload(kind: .formula), .milk(kind: .formula, amountMl: 150))
        input.prepared = "0"; input.duration = "0"; milk.set(input, kind: .formula)
        XCTAssertEqual(milk.payload(kind: .formula), .milk(kind: .formula, amountMl: 150, preparedMl: 0, durationMinutes: 0))
        input.prepared = "999"; input.duration = "1440"; milk.set(input, kind: .formula)
        let payload = try XCTUnwrap(milk.payload(kind: .formula))
        let encoded = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
        XCTAssertEqual(RecordPayloadCodec.decode(type: .formula, payloadJSON: encoded, schemaVersion: 2).payload, payload)
        for (prepared, duration, field) in [("1000", "1", "milk.prepared"), ("1", "1441", "milk.duration"), (" 1", "", "milk.prepared"), ("", "-1", "milk.duration"), ("\u{85}", "", "milk.prepared")] {
            input.prepared = prepared; input.duration = duration; milk.set(input, kind: .formula)
            XCTAssertEqual(milk.issue(kind: .formula)?.fieldID, field)
            XCTAssertNil(milk.payload(kind: .formula))
        }
    }
    func testTypeSwitchDoesNotLeakOrDiscardFormulaFieldsAndStructuralErrorsFailClosed() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: .formula)
        var milk = state.draft.milk; var input = OfflineMilkInput(amount: "150")
        input.prepared = "180"; input.duration = "10"; milk.set(input, kind: .formula); state.edit(milk: milk)
        state.edit(kind: .pumpedFeed)
        XCTAssertEqual(state.draft.payload, .milk(kind: .pumpedFeed, amountMl: 120))
        state.edit(kind: .pumpExpress)
        XCTAssertEqual(state.draft.payload, .milk(kind: .pumpExpress, amountMl: 60))
        state.edit(kind: .formula)
        XCTAssertEqual(state.draft.milk.input(kind: .formula), input)
        milk.set(input, kind: .pumpedFeed)
        XCTAssertNil(milk.payload(kind: .pumpedFeed)) // Reject impossible fields, never silently discard them.
        XCTAssertEqual(milk.issue(kind: .pumpedFeed)?.fieldID, "milk.amount")
        XCTAssertEqual(state.dismissDecision, .confirmDiscard)
        state.edit(timestampMillis: 1001)
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "time")
    }
    func testQuickAmountsAreClampedDistinctAndInvalidInputHasNoInventedNeighbors() {
        var milk = OfflineMilkDraft(recent: [.formula: [999, 10]])
        milk.set(.init(amount: "999"), kind: .formula)
        XCTAssertEqual(milk.quickAmounts(kind: .formula, step: 15), [999, 10, 984])
        milk.set(.init(amount: "1"), kind: .formula)
        XCTAssertEqual(milk.quickAmounts(kind: .formula, step: 5), [999, 10, 1, 6, 11])
        milk.set(.init(amount: "invalid"), kind: .formula)
        XCTAssertEqual(milk.quickAmounts(kind: .formula, step: 5), [999, 10])
    }
    func testInvalidStructureUnknownDocumentsAndIntervalsStayReadOnly() throws {
        for kind in kinds {
            let milkKind = kind.milkKind!
            let payload = RecordPayload.milk(kind: milkKind, amountMl: 155)
            let valid = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
            for (json, schema, end) in [("{}", 99, nil as Int64?), (valid, 2, 950),
                                        ("{\"amount_ml\":0}", 2, nil)] {
                let document = RecordPayloadCodec.decode(type: kind.recordType, payloadJSON: json, schemaVersion: schema)
                let row = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: kind.rawValue,
                                        timestampMillis: 900, endTimestampMillis: end, note: nil,
                                        payloadJSON: json, payloadSchemaVersion: schema, payloadDocument: document,
                                        updatedAt: 1000, syncDirty: true, deletedAt: nil)
                if end != nil { XCTAssertFalse(row.isOpaque); XCTAssertEqual(document.payload, payload) }
                XCTAssertFalse(OfflinePeeState.canEdit(row))
                XCTAssertNil(OfflinePeeState(record: row, babyName: "测试"))
            }
        }
        for kind in [MilkKind.pumpedFeed, .pumpExpress] {
            let impossible = RecordPayload.milk(kind: kind, amountMl: 155, preparedMl: 180, durationMinutes: 10)
            XCTAssertFalse(impossible.validationErrors().isEmpty)
            XCTAssertThrowsError(try RecordPayloadCodec.encode(RecordPayloadDocument(payload: impossible)))
        }
    }
    @MainActor
    func testThreeKindsRealStoreReopenEditAndDeleteKeepOriginalBabyAndRevision() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("milk-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2026-01-02")
        let context = try await store.selectBaby(a.clientUUID)
        for kind in kinds {
            var state = OfflinePeeState(babyClientUUID: a.clientUUID, babyName: a.nickname, timestampMillis: 900, kind: kind)
            guard case let .confirmed(create) = state.confirm(nowMillis: 1000) else { return XCTFail() }
            let row = try await store.confirmRecord(create.draft, context: context)
            let reopened = try OfflineStore(path: path, clock: { 1000 })
            _ = try await reopened.selectBaby(b.clientUUID)
            let read = try await reopened.recordDetails(row.clientUUID)
            var edit = try XCTUnwrap(OfflinePeeState(record: XCTUnwrap(read), babyName: a.nickname))
            edit.edit(kind: .pee); XCTAssertEqual(edit.draft.kind, kind)
            var milk = edit.draft.milk; var input = OfflineMilkInput(amount: "155")
            if kind == .formula { input.prepared = "180"; input.duration = "10" }
            milk.set(input, kind: kind.milkKind!); edit.edit(milk: milk)
            guard case let .confirmed(change) = edit.confirm(nowMillis: 1000) else { return XCTFail() }
            XCTAssertTrue(edit.beginPersistence(change)); XCTAssertFalse(edit.beginPersistence(change))
            let saved = try await reopened.confirmRecord(change.draft, context: context, expectedUpdatedAt: edit.expectedUpdatedAt)
            XCTAssertEqual(saved.payloadDocument?.payload, milk.payload(kind: kind.milkKind!))
            XCTAssertEqual(saved.babyClientUUID, a.clientUUID)
            let recent = try await reopened.recentMilkAmounts(baby: a.clientUUID)
            XCTAssertEqual(recent[kind.recordType], [155])
            let deleted = try await reopened.deleteRecord(row.clientUUID, context: context, expectedUpdatedAt: saved.updatedAt)
            XCTAssertTrue(deleted)
        }
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineMilkModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testThreeKindsModelCreateEditDeleteAndRecentSnapshotRemainsWithOriginalBaby() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2026-01-02")
        _ = try await store.selectBaby(a.clientUUID)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        for kind in [OfflineRecordKind.formula, .pumpedFeed, .pumpExpress] {
            model.select(a.clientUUID); try await settle { !model.busy }
            model.openEditor(milkStep: 10); model.editor?.edit(kind: kind)
            var milk = try XCTUnwrap(model.editor?.draft.milk)
            milk.set(.init(amount: "155"), kind: kind.milkKind!); model.editor?.edit(milk: milk)
            guard case let .confirmed(create)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
            model.persist(create); model.persist(create); try await settle { model.editor == nil && !model.busy }
            model.openEditor(milkStep: 15); model.editor?.edit(kind: kind)
            XCTAssertEqual(model.editor?.draft.milk.input(kind: kind.milkKind!).amount, "155")
            XCTAssertEqual(model.editor?.milkStep, 15)
            model.select(b.clientUUID); try await settle { !model.busy }
            XCTAssertEqual(model.editor?.draft.babyClientUUID, a.clientUUID)
            XCTAssertEqual(model.editor?.draft.milk.recent[kind.recordType], [155])
            model.closeEditor(); model.discardChanges()
            model.select(a.clientUUID); try await settle { !model.busy }
            let row = try XCTUnwrap(model.records.first(where: { $0.clientUUID == create.draft.clientUUID }))
            model.openEditor(record: row)
            milk.set(.init(amount: "165"), kind: kind.milkKind!); model.editor?.edit(milk: milk)
            guard case let .confirmed(edit)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
            model.persist(edit); try await settle { model.editor == nil && !model.busy }
            let saved = try XCTUnwrap(model.records.first(where: { $0.clientUUID == row.clientUUID }))
            XCTAssertEqual(saved.payloadDocument?.payload, edit.draft.payload)
            model.openEditor(record: saved); model.requestDelete(); model.confirmDelete()
            try await settle { model.editor == nil && !model.busy }
        }
    }
    func testSnapshotReadFailurePreservesVisibleRecordsCapturedRecentAndDraft() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("milk-read-failure-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: path, clock: { now })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let record = try await store.confirmRecord(.init(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.formula)!, timestampMillis: now - 1000,
            payload: .milk(kind: .formula, amountMl: 147)), context: context)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        model.openEditor(); model.editor?.edit(note: "保留草稿", kind: .formula)
        let draft = model.editor?.draft
        // Deliberate test-only corruption makes the real snapshot read fail. No fake loader.
        let db = try SQLiteConnection(path: path)
        try db.execute("ALTER TABLE records RENAME TO unavailable_records")
        model.refresh(); try await settle { !model.busy }
        XCTAssertEqual(model.editor?.draft, draft)
        XCTAssertEqual(model.records.map(\.clientUUID), [record.clientUUID])
        XCTAssertEqual(model.editor?.draft.milk.recent[.formula], [147])
        XCTAssertEqual(model.editor?.draft.milk.input(kind: .formula).amount, "147")
        XCTAssertEqual(model.message, "本机操作失败，请重试。未完成的记录草稿已保留。")
    }
    func testInvalidAmountAndRollbackRetainExactTextWithoutWrite() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        model.openEditor(); model.editor?.edit(kind: .formula)
        var milk = try XCTUnwrap(model.editor?.draft.milk); milk.set(.init(amount: "12 ml"), kind: .formula)
        model.editor?.edit(milk: milk)
        guard case .explanation? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        XCTAssertEqual(model.editor?.draft.milk.input(kind: .formula).amount, "12 ml")
        milk.set(.init(amount: "155"), kind: .formula); model.editor?.edit(milk: milk)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen); try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.milk, milk)
        model.closeEditor(); model.cancelDiscard(); XCTAssertEqual(model.editor?.draft.milk, milk)
        model.closeEditor(); model.discardChanges()
        let page = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(page.records.isEmpty)
    }
}
#endif
