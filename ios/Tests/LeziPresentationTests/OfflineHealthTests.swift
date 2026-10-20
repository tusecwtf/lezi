import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineHealthStateTests: XCTestCase {
    private let kinds: [OfflineRecordKind] = [.cough, .rash, .vomit, .injury, .medicine, .hospital, .vaccine]
    private func validHealth() -> OfflineHealthDraft {
        var value = OfflineHealthDraft()
        value.medicineName = " 测试药名 "; value.medicineDose = " 测试剂量 "
        value.hospitalReason = " 测试原因 "; value.hospitalAdvice = " 测试医嘱 "
        value.vaccineName = " 测试疫苗 "; value.vaccineBatch = " 测试批次 "
        value.description = " 测试描述 "
        return value
    }
    func testDefaultsRequiredFieldsAndDistinctTypedPayloads() {
        for kind in kinds {
            var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: kind)
            XCTAssertEqual(state.draft.health.severity, 2)
            XCTAssertEqual(state.draft.health.description, "")
            if kind.symptomKind == nil {
                state.edit(note: "备注不能替代必填")
                XCTAssertNotNil(state.featureIssue(nowMillis: 1000))
                guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
            } else { XCTAssertNil(state.featureIssue(nowMillis: 1000)) }
            state.edit(health: validHealth())
            XCTAssertEqual(state.draft.payload.type, kind.recordType)
            XCTAssertEqual(state.draft.payload.validationErrors(), [])
            XCTAssertNil(state.featureIssue(nowMillis: 1000))
            XCTAssertNil(state.draft.confirmation.endTimestampMillis)
        }
        XCTAssertEqual((1...3).map(OfflineHealthDraft.severityLabel), ["轻微", "一般", "明显"])
        XCTAssertEqual(OfflineHealthDraft.severityLabel(0), "未知")
    }
    func testAllTextLimitsCountUTF16PreserveInputAndKotlinBlankSemantics() {
        for kind in kinds {
            for field in kind.healthFields {
                var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: kind)
                var health = validHealth()
                health[keyPath: field.keyPath] = String(repeating: "😀", count: field.limit / 2)
                state.edit(health: health)
                XCTAssertNil(state.featureIssue(nowMillis: 1000))
                health[keyPath: field.keyPath] += "e\u{301}"
                state.edit(health: health)
                XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, field.id)
                XCTAssertEqual(state.draft.health, health)
                if field.required {
                    health[keyPath: field.keyPath] = "\u{1c} \u{a0}"
                    state.edit(health: health)
                    XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, field.id)
                    health[keyPath: field.keyPath] = "\u{85}"
                    state.edit(health: health)
                    XCTAssertNil(state.featureIssue(nowMillis: 1000))
                }
            }
        }
    }
    func testInactiveFieldsPreservedAndInvalidSeverityNeverDefaults() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: .cough)
        var health = validHealth(); health.severity = 0
        state.edit(health: health)
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "severity")
        guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail() }
        state.edit(kind: .medicine)
        XCTAssertNil(state.featureIssue(nowMillis: 1000))
        state.edit(kind: .cough)
        XCTAssertEqual(state.draft.health, health)
        XCTAssertEqual(state.draft.payload, .symptom(kind: .cough, severity: 0, description: "测试描述"))
        health.severity = 3; health.description = "\u{1c} "
        state.edit(health: health)
        XCTAssertEqual(state.draft.payload, .symptom(kind: .cough, severity: 3, description: nil))
        state.edit(timestampMillis: 1001)
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "time")
    }
    func testOptionalWhitespaceBecomesNilWithoutMixingTypedFields() {
        var health = validHealth()
        health.description = "\u{1c} "; health.medicineDose = "\u{1c} "
        health.hospitalAdvice = "\u{1c} "; health.vaccineBatch = "\u{1c} "
        for kind in kinds {
            let draft = OfflinePeeDraft(babyClientUUID: UUID(), timestampMillis: 900, kind: kind, health: health)
            switch draft.payload {
            case let .symptom(_, _, value): XCTAssertNil(value)
            case let .medicine(name, value): XCTAssertEqual(name, "测试药名"); XCTAssertNil(value)
            case let .hospital(reason, value): XCTAssertEqual(reason, "测试原因"); XCTAssertNil(value)
            case let .vaccine(name, value): XCTAssertEqual(name, "测试疫苗"); XCTAssertNil(value)
            default: XCTFail()
            }
        }
    }
    func testUnknownAndIntervalHealthRowsStayReadOnly() throws {
        for kind in kinds {
            let payload = OfflinePeeDraft(babyClientUUID: UUID(), timestampMillis: 900,
                                         kind: kind, health: validHealth()).payload
            let validJSON = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
            for (json, schema, end) in [("{}", 99, nil as Int64?), ("{\"future\":true}", 2, nil), (validJSON, 2, 950)] {
                let row = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: kind.rawValue,
                                        timestampMillis: 900, endTimestampMillis: end, note: nil,
                                        payloadJSON: json, payloadSchemaVersion: schema,
                                        payloadDocument: RecordPayloadCodec.decode(type: kind.recordType, payloadJSON: json, schemaVersion: schema),
                                        updatedAt: 1000, syncDirty: true, deletedAt: nil)
                if end != nil {
                    XCTAssertFalse(row.isOpaque)
                    XCTAssertEqual(row.payloadDocument?.payload, payload)
                    XCTAssertEqual(payload.validationErrors(), [])
                }
                XCTAssertFalse(OfflinePeeState.canEdit(row))
                XCTAssertNil(OfflinePeeState(record: row, babyName: "测试"))
            }
        }
    }
    @MainActor
    func testSevenTypesRealCreateEditDeleteWithOriginalBabyRevisionAndLegacyText() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let other = try await store.createBaby(nickname: "另一测试", birthday: "2026-01-02")
        for kind in kinds {
            var state = OfflinePeeState(babyClientUUID: baby.clientUUID, babyName: baby.nickname, timestampMillis: 900, kind: kind)
            var health = validHealth()
            // Android loads every legacy text field verbatim, without onChange truncation.
            for field in kind.healthFields { health[keyPath: field.keyPath] = String(repeating: "长", count: field.limit + 1) }
            state.edit(health: health)
            let row = try await store.confirmRecord(state.draft.confirmation, context: context)
            _ = try await store.selectBaby(other.clientUUID)
            var edit = try XCTUnwrap(OfflinePeeState(record: row, babyName: baby.nickname))
            XCTAssertEqual(edit.draft.payload, row.payloadDocument?.payload)
            XCTAssertEqual(edit.expectedUpdatedAt, row.updatedAt)
            edit.edit(note: "新备注", kind: .pee)
            XCTAssertEqual(edit.draft.kind, kind)
            edit.edit(note: "新备注")
            XCTAssertNil(edit.featureIssue(nowMillis: 1000))
            guard case let .confirmed(frozen) = edit.confirm(nowMillis: 1000) else { return XCTFail() }
            XCTAssertTrue(edit.beginPersistence(frozen)); XCTAssertFalse(edit.beginPersistence(frozen))
            let updated = try await store.confirmRecord(frozen.draft, context: context, expectedUpdatedAt: edit.expectedUpdatedAt)
            XCTAssertEqual(updated.payloadDocument?.payload, row.payloadDocument?.payload)
            XCTAssertEqual(updated.babyClientUUID, baby.clientUUID)
            var changed = try XCTUnwrap(OfflinePeeState(record: updated, babyName: baby.nickname))
            let field = try XCTUnwrap(kind.healthFields.first)
            var next = changed.draft.health; next[keyPath: field.keyPath] += "改"
            changed.edit(health: next)
            XCTAssertEqual(changed.featureIssue(nowMillis: 1000)?.fieldID, field.id)
            let removed = try await store.deleteRecord(updated.clientUUID, context: context, expectedUpdatedAt: updated.updatedAt)
            XCTAssertTrue(removed)
        }
        let page = try await store.recordPage(baby: baby.clientUUID)
        XCTAssertTrue(page.records.isEmpty)
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineHealthModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testRealHealthOwnerRollbackRetryEditAndDelete() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh(); try await settle { !model.busy }
        model.openEditor(); var health = OfflineHealthDraft(); health.medicineName = "测试药名"; health.medicineDose = "测试剂量"
        model.editor?.edit(kind: .medicine, health: health)
        guard case let .confirmed(first)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(first); model.persist(first)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.health, health)
        let empty = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(empty.records.isEmpty)
        guard case let .confirmed(retry)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(retry); try await settle { model.editor == nil && !model.busy }
        let row = try XCTUnwrap(model.records.first)
        model.openEditor(record: row); health.medicineDose = "修改测试剂量"; model.editor?.edit(health: health)
        model.closeEditor(); model.cancelDiscard(); XCTAssertEqual(model.editor?.draft.health, health)
        guard case let .confirmed(edit)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(edit); try await settle { model.editor == nil && !model.busy }
        let saved = try XCTUnwrap(model.records.first)
        XCTAssertEqual(saved.clientUUID, row.clientUUID)
        XCTAssertEqual(saved.payloadDocument?.payload, .medicine(name: "测试药名", dose: "修改测试剂量"))
        model.openEditor(record: saved); model.requestDelete(); model.confirmDelete(); model.confirmDelete()
        try await settle { model.editor == nil && !model.busy }
        let page = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(page.records.isEmpty)
    }
}
#endif
