import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineFoodStateTests: XCTestCase {
    private let kinds: [FoodKind] = [.babyFood, .snack, .drink]
    private func draft(_ content: String = "南瓜米糊", _ amount: String = "", kind: FoodKind = .babyFood) -> OfflineFoodDraft {
        var draft = OfflineFoodDraft(); draft.set(.init(content: content, amount: amount), kind: kind); return draft
    }
    func testRequiredFieldsFocusNormalizationAndNumericBoundaries() {
        for kind in kinds {
            XCTAssertEqual(draft("\u{001C} ", "半碗", kind: kind).issue(kind: kind)?.fieldID, "food.content")
            XCTAssertNil(draft("\u{0085}", "半碗", kind: kind).issue(kind: kind))
        }
        XCTAssertEqual(draft().issue(kind: .babyFood)?.message, "请填写量（如 50g）")
        for amount in ["几口", "十几口", "一两勺", "半2碗", "1,000g", "1.2.3碗", "-50g", "1000001g"] {
            XCTAssertEqual(draft("南瓜", amount).issue(kind: .babyFood)?.fieldID, "food.amount", amount)
            XCTAssertNil(draft("南瓜", amount).payload(kind: .babyFood))
        }
        for (raw, normalized) in [("半碗", "0.5碗"), ("３。５碗", "3.5碗"), ("０g", "0g"), (" ５０g ", "50g"), ("1000000g", "1000000g")] {
            let value = draft(" 南瓜 ", raw)
            XCTAssertEqual(value.payload(kind: .babyFood), .food(kind: .babyFood, content: "南瓜", amount: normalized))
            XCTAssertEqual(value.input(kind: .babyFood).amount, raw)
        }
        for kind in [FoodKind.snack, .drink] {
            for amount in ["", "少量", "小半碗", "-50g"] {
                XCTAssertEqual(draft("内容", amount, kind: kind).payload(kind: kind),
                               .food(kind: kind, content: "内容", amount: amount.isEmpty ? nil : amount))
            }
        }
    }
    func testUTF16LimitsRetainRawTextAndLegacyLongValues() {
        for kind in kinds {
            let content = String(repeating: "😀", count: 40)
            let amount = "1" + String(repeating: "😀", count: 14) + "g"
            XCTAssertNil(draft(content, amount, kind: kind).issue(kind: kind))
            XCTAssertEqual(draft(content + "a", amount, kind: kind).issue(kind: kind)?.fieldID, "food.content")
            XCTAssertEqual(draft(content, amount + "a", kind: kind).issue(kind: kind)?.fieldID, "food.amount")
            let legacyContent = content + "旧值"
            let legacyAmount = amount + "旧值"
            var legacy = OfflineFoodDraft(payload: .food(kind: kind, content: legacyContent, amount: legacyAmount))
            XCTAssertNil(legacy.issue(kind: kind))
            legacy.set(.init(content: legacyContent + "新", amount: legacyAmount), kind: kind)
            XCTAssertEqual(legacy.issue(kind: kind)?.fieldID, "food.content")
            legacy.set(.init(content: legacyContent, amount: legacyAmount + "新"), kind: kind)
            XCTAssertEqual(legacy.issue(kind: kind)?.fieldID, "food.amount")
        }
    }
    func testUntouchedOptionalAmountPreservesExactSourceAndChangedAmountValidates() {
        let amounts: [String?] = [nil, "", "半碗", " ５０g ", "\u{001C}  \u{001F}", "几口"]
        for kind in kinds {
            for amount in amounts {
                var value = OfflineFoodDraft(payload: .food(kind: kind, content: "旧内容", amount: amount))
                var input = value.input(kind: kind); input.content = "改内容"; value.set(input, kind: kind)
                XCTAssertEqual(value.payload(kind: kind), .food(kind: kind, content: "改内容", amount: amount))
                input.amount = "半碗"; value.set(input, kind: kind)
                XCTAssertEqual(value.payload(kind: kind), .food(kind: kind, content: "改内容", amount: amount == "半碗" ? "半碗" : "0.5碗"))
                input.amount = "几口新值"; value.set(input, kind: kind)
                XCTAssertEqual(value.issue(kind: kind) != nil, kind == .babyFood)
            }
        }
    }
    func testTypeIsolationSuggestionOnlyChangesContentAndFutureCannotConfirm() {
        var food = draft("原辅食", "半碗")
        food.set(.init(content: "点心", amount: "少量"), kind: .snack)
        food.set(.init(content: "水", amount: ""), kind: .drink)
        food.chooseSuggestion("高铁米粉")
        XCTAssertEqual(food.input(kind: .babyFood), .init(content: "高铁米粉", amount: "半碗"))
        XCTAssertEqual(food.input(kind: .snack), .init(content: "点心", amount: "少量"))
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: .babyFood, babyBirthday: "2026-01-01")
        state.edit(note: "原备注", food: food); state.edit(kind: .snack); state.edit(kind: .drink); state.edit(kind: .babyFood)
        XCTAssertEqual(state.draft.food, food); XCTAssertEqual(state.draft.note, "原备注")
        XCTAssertEqual(state.babyBirthday, "2026-01-01"); XCTAssertEqual(state.dismissDecision, .confirmDiscard)
        state.edit(timestampMillis: 1001)
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "time")
        guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
    }
    func testOpaqueAndValidIntervalStayReadOnly() throws {
        for kind in kinds {
            let payload = RecordPayload.food(kind: kind, content: "旧内容", amount: nil)
            let json = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
            for (schema, end) in [(99, nil as Int64?), (2, 950)] {
                let document = RecordPayloadCodec.decode(type: kind.recordType, payloadJSON: json, schemaVersion: schema)
                let row = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: kind.recordType.rawValue,
                    timestampMillis: 900, endTimestampMillis: end, note: nil, payloadJSON: json, payloadSchemaVersion: schema,
                    payloadDocument: document, updatedAt: 1000, syncDirty: true, deletedAt: nil)
                if end != nil { XCTAssertFalse(row.isOpaque); XCTAssertEqual(document.payload, payload) }
                XCTAssertFalse(OfflinePeeState.canEdit(row))
            }
        }
    }
    @MainActor
    func testRealStoreAllKindsLegacyOptionalReopenEditAndSoftDelete() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("food-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-31")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        for kind in kinds {
            for amount in [nil, "", "半碗", " ５０g ", "  "] as [String?] {
                let original = RecordPayload.food(kind: kind, content: "旧内容", amount: amount)
                let row = try await store.confirmRecord(.init(clientUUID: UUID(), babyClientUUID: a.clientUUID,
                    item: .builtIn(kind.recordType)!, timestampMillis: 900, payload: original), context: context)
                let reopened = try OfflineStore(path: path, clock: { 1000 })
                let reread = try await reopened.recordDetails(row.clientUUID)
                var state = try XCTUnwrap(OfflinePeeState(record: XCTUnwrap(reread), babyName: a.nickname, babyBirthday: a.birthday))
                _ = try await reopened.selectBaby(b.clientUUID)
                state.edit(kind: .pee); XCTAssertEqual(state.draft.kind.recordType, kind.recordType)
                state.edit(note: "仅改备注")
                XCTAssertEqual(state.draft.payload, original)
                guard case let .confirmed(edit) = state.confirm(nowMillis: 1000) else { return XCTFail() }
                XCTAssertTrue(state.beginPersistence(edit)); XCTAssertFalse(state.beginPersistence(edit))
                let saved = try await reopened.confirmRecord(edit.draft, context: context, expectedUpdatedAt: state.expectedUpdatedAt)
                XCTAssertEqual(saved.payloadDocument?.payload, original)
                XCTAssertEqual(saved.babyClientUUID, a.clientUUID)
                let deleted = try await reopened.deleteRecord(row.clientUUID, context: context, expectedUpdatedAt: saved.updatedAt)
                XCTAssertTrue(deleted)
                let finalStore = try OfflineStore(path: path)
                let tombstone = try await finalStore.recordDetails(row.clientUUID)
                XCTAssertNotNil(tombstone?.deletedAt)
            }
        }
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineFoodModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testAllThreeKindsActualOwnerCUDReopenAndOriginalBaby() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("food-model-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: path, clock: { now })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-31")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        for kind in [OfflineRecordKind.babyFood, .snack, .drink] {
            _ = try await store.selectBaby(a.clientUUID)
            let foodKind = try XCTUnwrap(kind.foodKind)
            let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
            var value = OfflineFoodDraft()
            value.set(.init(content: "原内容", amount: kind == .babyFood ? "半碗" : kind == .snack ? "少量" : ""), kind: foodKind)
            model.openEditor(); model.editor?.edit(kind: kind, food: value)
            model.select(b.clientUUID); try await settle { !model.busy }
            XCTAssertEqual(model.editor?.babyBirthday, a.birthday)
            XCTAssertEqual(model.editor?.draft.babyClientUUID, a.clientUUID)
            guard case let .confirmed(create)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
            model.persist(create); model.persist(create); try await settle { model.editor == nil && !model.busy }
            let reopened = try OfflineStore(path: path, clock: { now })
            _ = try await reopened.selectBaby(a.clientUUID)
            let restarted = OfflinePeeModel(store: reopened); restarted.refresh(); try await settle { !restarted.busy }
            let row = try XCTUnwrap(restarted.records.first(where: { $0.clientUUID == create.draft.clientUUID }))
            XCTAssertEqual(row.payloadDocument?.payload, create.draft.payload)
            restarted.openEditor(record: row)
            XCTAssertEqual(restarted.editor?.babyBirthday, a.birthday)
            restarted.editor?.edit(kind: .pee); XCTAssertEqual(restarted.editor?.draft.kind, kind)
            value = try XCTUnwrap(restarted.editor?.draft.food)
            var changed = value.input(kind: foodKind); changed.content = "修改内容"
            value.set(changed, kind: foodKind); restarted.editor?.edit(food: value)
            guard case let .confirmed(edit)? = restarted.editor?.confirm(nowMillis: now) else { return XCTFail() }
            restarted.persist(edit); try await settle { restarted.editor == nil && !restarted.busy }
            let updated = try XCTUnwrap(restarted.records.first(where: { $0.clientUUID == row.clientUUID }))
            XCTAssertEqual(updated.payloadDocument?.payload, .food(kind: foodKind, content: "修改内容", amount: kind == .babyFood ? "0.5碗" : kind == .snack ? "少量" : nil))
            restarted.openEditor(record: updated); restarted.requestDelete(); restarted.confirmDelete()
            try await settle { restarted.editor == nil && !restarted.busy }
            let finalStore = try OfflineStore(path: path)
            let deleted = try await finalStore.recordDetails(row.clientUUID)
            XCTAssertNotNil(deleted?.deletedAt)
        }
    }
    func testInvalidBabyFoodCannotWriteAndRollbackRetainsRawAmount() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-31")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        var food = OfflineFoodDraft(); food.set(.init(content: "南瓜", amount: "几口"), kind: .babyFood)
        model.openEditor(); model.editor?.edit(kind: .babyFood, food: food)
        guard case .explanation? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        let empty = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(empty.records.isEmpty)
        food.set(.init(content: "南瓜", amount: "半碗"), kind: .babyFood); model.editor?.edit(food: food)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.food.input(kind: .babyFood).amount, "半碗")
        model.closeEditor(); model.cancelDiscard(); XCTAssertNotNil(model.editor)
        model.closeEditor(); model.discardChanges(); XCTAssertNil(model.editor)
        let final = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(final.records.isEmpty)
    }
}
#endif
