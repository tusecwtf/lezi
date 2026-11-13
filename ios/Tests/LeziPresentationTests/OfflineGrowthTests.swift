import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineGrowthStateTests: XCTestCase {
    private let kinds: [OfflineRecordKind] = [.height, .weight, .head, .chest, .footSize]
    private func growth(_ input: String) -> OfflineGrowthDraft { var value = OfflineGrowthDraft(); value.input = input; return value }
    func testEmptyInvalidAndInputLimitsNeverBecomeDefaultMeasurements() throws {
        for kind in kinds {
            let measurement = try XCTUnwrap(kind.measurementKind)
            for input in ["", ".", "0", "-1", "1,2", "12 kg", "NaN", "inf", "1e1", "1.2.3", "１２", " 12", "12345678"] {
                var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: kind)
                state.edit(growth: growth(input))
                XCTAssertNotNil(state.featureIssue(nowMillis: 1000), input)
                XCTAssertNil(state.draft.growth.payload(kind: measurement))
                XCTAssertEqual(state.draft.growth.input, input)
                guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail(input) }
                XCTAssertEqual(state.session.phase, .editing)
                XCTAssertFalse(state.draft.payload.validationErrors().isEmpty)
                XCTAssertThrowsError(try RecordPayloadCodec.encode(RecordPayloadDocument(payload: state.draft.payload)))
            }
        }
    }
    func testExactRangesUnitsAndWeightHalfGramRounding() throws {
        for kind in kinds {
            let measurement = try XCTUnwrap(kind.measurementKind)
            let limit = kind == .weight ? "100" : "250"
            XCTAssertNil(growth(limit).issue(kind: measurement))
            XCTAssertNotNil(growth(kind == .weight ? "100.001" : "250.001").issue(kind: measurement))
            XCTAssertEqual(growth("12.345").payload(kind: measurement),
                           .measurement(kind: measurement, value: kind == .weight ? 12345 : 12.345,
                                        unit: kind == .weight ? "g" : "cm"))
        }
        XCTAssertNil(growth("0.0004").payload(kind: .weight))
        XCTAssertEqual(growth("0.0005").payload(kind: .weight), .measurement(kind: .weight, value: 1, unit: "g"))
        XCTAssertEqual(growth("3.4565").payload(kind: .weight), .measurement(kind: .weight, value: 3457, unit: "g"))
        XCTAssertEqual(growth("0.00001").payload(kind: .height), .measurement(kind: .height, value: 0.00001, unit: "cm"))
        XCTAssertNil(growth("0.000001").payload(kind: .height)) // Eight characters, no truncation.
    }
    func testKindSwitchPreservesRawValueAndFutureDirtyGates() {
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: .weight)
        state.edit(growth: growth("12.345"))
        state.edit(kind: .diary, body: "正文")
        state.edit(kind: .height)
        XCTAssertEqual(state.draft.growth.input, "12.345")
        XCTAssertEqual(state.draft.payload, .measurement(kind: .height, value: 12.345, unit: "cm"))
        XCTAssertEqual(state.dismissDecision, .confirmDiscard)
        state.edit(timestampMillis: 1001)
        XCTAssertEqual(state.featureIssue(nowMillis: 1000)?.fieldID, "time")
    }
    func testLegacyUnchangedValuesKeepPrecisionUnitAndLongInput() throws {
        let originals: [RecordPayload] = [
            .measurement(kind: .weight, value: 12345, unit: "g"),
            .measurement(kind: .weight, value: 12345.6789, unit: "g"),
            .measurement(kind: .weight, value: 12.3456789, unit: "kg"),
            .measurement(kind: .height, value: 87.654321098765, unit: "cm"),
            .measurement(kind: .head, value: 0.0000001, unit: "cm")]
        for original in originals {
            guard case let .measurement(kind, _, _) = original else { return XCTFail() }
            var loaded = OfflineGrowthDraft(payload: original)
            XCTAssertNil(loaded.issue(kind: kind))
            XCTAssertEqual(loaded.payload(kind: kind), original)
            if loaded.input.count > 7 {
                loaded.input += "0"
                XCTAssertNotNil(loaded.issue(kind: kind))
                XCTAssertNil(loaded.payload(kind: kind))
            }
        }
        XCTAssertEqual(OfflineGrowthDraft(payload: originals[0]).input, "12.345")
        var changed = OfflineGrowthDraft(payload: originals[2]); changed.input = "12.678"
        XCTAssertEqual(changed.payload(kind: .weight), .measurement(kind: .weight, value: 12678, unit: "g"))
    }
    func testUnknownUnitsAndValidIntervalsRemainReadOnly() throws {
        for kind in kinds {
            let measurement = try XCTUnwrap(kind.measurementKind)
            for (unit, end) in [("unknown", nil as Int64?), (kind == .weight ? "g" : "cm", 950)] {
                let payload = RecordPayload.measurement(kind: measurement, value: 12, unit: unit)
                let document = try RecordPayloadDocument(payload: payload)
                let row = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: kind.rawValue,
                                        timestampMillis: 900, endTimestampMillis: end, note: nil,
                                        payloadJSON: try RecordPayloadCodec.encode(document), payloadSchemaVersion: 2,
                                        payloadDocument: document, updatedAt: 1000, syncDirty: true, deletedAt: nil)
                XCTAssertFalse(row.isOpaque)
                XCTAssertFalse(OfflinePeeState.canEdit(row))
                XCTAssertNil(OfflinePeeState(record: row, babyName: "测试"))
            }
        }
    }
    @MainActor
    func testEveryGrowthKindPersistsReopensEditsAndSoftDeletesWithoutPrecisionLoss() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("growth-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("records.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let other = try await store.createBaby(nickname: "另一测试", birthday: "2026-01-02")
        for kind in kinds {
            let measurement = try XCTUnwrap(kind.measurementKind)
            let payload = RecordPayload.measurement(kind: measurement, value: kind == .weight ? 12345 : 87.654321098765, unit: kind == .weight ? "g" : "cm")
            let input = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
                                               item: .builtIn(kind.recordType)!, timestampMillis: 900, payload: payload)
            let row = try await store.confirmRecord(input, context: context)
            let reopened = try OfflineStore(path: path, clock: { 1000 })
            let reread = try await reopened.recordDetails(row.clientUUID)
            var edit = try XCTUnwrap(OfflinePeeState(record: XCTUnwrap(reread), babyName: baby.nickname))
            _ = try await reopened.selectBaby(other.clientUUID)
            edit.edit(kind: .pee); XCTAssertEqual(edit.draft.kind, kind)
            edit.edit(note: "仅改备注")
            XCTAssertEqual(edit.draft.payload, payload)
            guard case let .confirmed(frozen) = edit.confirm(nowMillis: 1000) else { return XCTFail() }
            XCTAssertTrue(edit.beginPersistence(frozen)); XCTAssertFalse(edit.beginPersistence(frozen))
            let saved = try await reopened.confirmRecord(frozen.draft, context: context, expectedUpdatedAt: edit.expectedUpdatedAt)
            XCTAssertEqual(saved.payloadDocument?.payload, payload)
            var changed = try XCTUnwrap(OfflinePeeState(record: saved, babyName: baby.nickname))
            changed.edit(growth: growth("12.678"))
            guard case let .confirmed(update) = changed.confirm(nowMillis: 1000) else { return XCTFail() }
            let updated = try await reopened.confirmRecord(update.draft, context: context, expectedUpdatedAt: changed.expectedUpdatedAt)
            XCTAssertEqual(updated.payloadDocument?.payload, .measurement(kind: measurement, value: kind == .weight ? 12678 : 12.678, unit: kind == .weight ? "g" : "cm"))
            XCTAssertEqual(updated.babyClientUUID, baby.clientUUID)
            let deleted = try await reopened.deleteRecord(row.clientUUID, context: context, expectedUpdatedAt: updated.updatedAt)
            XCTAssertTrue(deleted)
            let after = try OfflineStore(path: path)
            let tombstone = try await after.recordDetails(row.clientUUID)
            XCTAssertNotNil(tombstone?.deletedAt)
        }
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineGrowthModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testAllFiveKindsRealOwnerCreateReopenEditAndSoftDelete() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("growth-model-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("records.sqlite").path
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: path, clock: { now })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store)
        model.refresh(); try await settle { !model.busy }
        for kind in [OfflineRecordKind.height, .weight, .head, .chest, .footSize] {
            var growth = OfflineGrowthDraft(); growth.input = "12.345"
            model.openEditor(); model.editor?.edit(kind: kind, growth: growth)
            guard case let .confirmed(create)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
            model.persist(create); model.persist(create)
            try await settle { model.editor == nil && !model.busy }
            let reopened = try OfflineStore(path: path, clock: { now })
            let restarted = OfflinePeeModel(store: reopened)
            restarted.refresh(); try await settle { !restarted.busy }
            let row = try XCTUnwrap(restarted.records.first(where: { $0.clientUUID == create.draft.clientUUID }))
            restarted.openEditor(record: row)
            XCTAssertEqual(restarted.editor?.draft.growth.input, "12.345")
            growth.input = "12.678"; restarted.editor?.edit(growth: growth)
            guard case let .confirmed(edit)? = restarted.editor?.confirm(nowMillis: now) else { return XCTFail() }
            restarted.persist(edit); try await settle { restarted.editor == nil && !restarted.busy }
            let saved = try XCTUnwrap(restarted.records.first(where: { $0.clientUUID == row.clientUUID }))
            XCTAssertEqual(saved.payloadDocument?.payload, edit.draft.payload)
            restarted.openEditor(record: saved); restarted.requestDelete(); restarted.confirmDelete()
            try await settle { restarted.editor == nil && !restarted.busy }
            let finalStore = try OfflineStore(path: path)
            let deleted = try await finalStore.recordDetails(row.clientUUID)
            XCTAssertNotNil(deleted?.deletedAt)
        }
    }
    func testInvalidInputDoesNotWriteAndRollbackRetainsRawDraft() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let baby = try await store.createBaby(nickname: "测试", birthday: "2026-01-01")
        _ = try await store.selectBaby(baby.clientUUID)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        var growth = OfflineGrowthDraft(); growth.input = "0.0004"
        model.openEditor(); model.editor?.edit(kind: .weight, growth: growth)
        guard case .explanation? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        let empty = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(empty.records.isEmpty)
        growth.input = "12.345"; model.editor?.edit(growth: growth)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.growth.input, "12.345")
        model.closeEditor(); model.cancelDiscard()
        XCTAssertEqual(model.editor?.draft.growth.input, "12.345")
        model.closeEditor(); model.discardChanges()
        let after = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(after.records.isEmpty)
    }
}
#endif
