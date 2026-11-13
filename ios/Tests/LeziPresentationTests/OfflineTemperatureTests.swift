import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineTemperatureStateTests: XCTestCase {
    func testDefaultRangesInvalidRawTextAndNoUnitFallback() throws {
        XCTAssertEqual(OfflineTemperatureDraft().payload, .temperature(celsius: 36.5))
        for raw in ["", ".", "0", "33.999", "43.001", "-38", "38 ℃", "38,1", "NaN", "inf", "3.8e1", "３８", " 38", "38.12345", "3..8"] {
            var draft = OfflineTemperatureDraft(); draft.input = raw
            XCTAssertNotNil(draft.issue, raw); XCTAssertNil(draft.payload, raw)
            draft.selectUnit(.fahrenheit)
            XCTAssertEqual(draft.input, raw); XCTAssertEqual(draft.unit, .celsius)
            var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, kind: .temperature)
            state.edit(temperature: draft)
            guard case .explanation = state.confirm(nowMillis: 1000) else { return XCTFail(raw) }
            XCTAssertThrowsError(try RecordPayloadCodec.encode(RecordPayloadDocument(payload: state.draft.payload)))
        }
        for raw in ["34", "43", "38.1234"] {
            var draft = OfflineTemperatureDraft(); draft.input = raw
            XCTAssertNil(draft.issue)
        }
    }
    func testFahrenheitInputAndUnitOnlyChangesKeepExactCanonical() throws {
        var draft = OfflineTemperatureDraft(); draft.selectUnit(.fahrenheit); draft.input = "100.4"
        let canonical = try XCTUnwrap(draft.celsius)
        XCTAssertEqual(canonical, 38, accuracy: 0.000000001)
        for _ in 0..<20 { draft.selectUnit(.celsius); draft.selectUnit(.fahrenheit) }
        XCTAssertEqual(draft.celsius, canonical)
        XCTAssertEqual(draft.payload, .temperature(celsius: canonical))
        for value in [34.0, 43.0, 38.1234567890123] {
            var legacy = OfflineTemperatureDraft(payload: .temperature(celsius: value))
            XCTAssertEqual(legacy.payload, .temperature(celsius: value))
            legacy.selectUnit(.fahrenheit); legacy.selectUnit(.celsius)
            XCTAssertEqual(legacy.payload, .temperature(celsius: value))
            legacy.input += "0"
            XCTAssertNotNil(legacy.issue) // Changed legacy long input has no exception.
        }
    }
    func testCapturedBirthdayRecordDateAndSettingDriveAdvice() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let recordDate: Int64 = 1777507200000 // 2026-04-30, exactly three months after Jan 31.
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "原宝宝", timestampMillis: recordDate,
                                    kind: .temperature, babyBirthday: "2026-01-31")
        var draft = OfflineTemperatureDraft(); draft.input = "38"; state.edit(temperature: draft)
        func shown(_ enabled: Bool) -> Bool {
            TemperatureRules.shouldShowInfantFeverAdvice(birthday: state.babyBirthday,
                recordTimestampMillis: state.draft.timestampMillis, celsius: state.draft.temperature.celsius!, enabled: enabled, zone: zone)
        }
        XCTAssertFalse(shown(true))
        state.edit(timestampMillis: recordDate - 86_400_000)
        XCTAssertTrue(shown(true)); XCTAssertFalse(shown(false))
        XCTAssertEqual(state.babyBirthday, "2026-01-31")
        state.edit(kind: .diary, body: "正文"); state.edit(kind: .temperature)
        XCTAssertEqual(state.draft.temperature.input, "38")
        XCTAssertEqual(state.dismissDecision, .confirmDiscard)
        XCTAssertEqual(state.featureIssue(nowMillis: recordDate - 86_400_001)?.fieldID, "time")
    }
    func testOpaqueAndValidIntervalStayReadOnly() throws {
        let payload = RecordPayload.temperature(celsius: 38)
        let json = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
        for (schema, end) in [(99, nil as Int64?), (2, 950)] {
            let document = RecordPayloadCodec.decode(type: .temperature, payloadJSON: json, schemaVersion: schema)
            let row = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: "temperature", timestampMillis: 900,
                                    endTimestampMillis: end, note: nil, payloadJSON: json, payloadSchemaVersion: schema,
                                    payloadDocument: document, updatedAt: 1000, syncDirty: true, deletedAt: nil)
            if end != nil { XCTAssertFalse(row.isOpaque); XCTAssertEqual(document.payload, payload) }
            XCTAssertFalse(OfflinePeeState.canEdit(row))
        }
    }
    @MainActor
    func testRealStoreReopenExactNoteEditThenValueEditAndSoftDelete() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("temperature-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-31")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        let original = RecordPayload.temperature(celsius: 38.1234567890123)
        let row = try await store.confirmRecord(.init(clientUUID: UUID(), babyClientUUID: a.clientUUID,
            item: .builtIn(.temperature)!, timestampMillis: 900, payload: original), context: context)
        let restarted = try OfflineStore(path: path, clock: { 1000 })
        let reread = try await restarted.recordDetails(row.clientUUID)
        var state = try XCTUnwrap(OfflinePeeState(record: XCTUnwrap(reread), babyName: a.nickname, babyBirthday: a.birthday))
        _ = try await restarted.selectBaby(b.clientUUID)
        var value = state.draft.temperature; value.selectUnit(.fahrenheit)
        state.edit(kind: .pee); XCTAssertEqual(state.draft.kind, .temperature)
        state.edit(note: "仅改备注", temperature: value)
        XCTAssertEqual(state.draft.payload, original); XCTAssertEqual(state.babyBirthday, a.birthday)
        guard case let .confirmed(edit) = state.confirm(nowMillis: 1000) else { return XCTFail() }
        XCTAssertTrue(state.beginPersistence(edit)); XCTAssertFalse(state.beginPersistence(edit))
        let saved = try await restarted.confirmRecord(edit.draft, context: context, expectedUpdatedAt: state.expectedUpdatedAt)
        XCTAssertEqual(saved.payloadDocument?.payload, original)
        var changed = try XCTUnwrap(OfflinePeeState(record: saved, babyName: a.nickname))
        value = OfflineTemperatureDraft(); value.input = "37.5"; changed.edit(temperature: value)
        guard case let .confirmed(update) = changed.confirm(nowMillis: 1000) else { return XCTFail() }
        let updated = try await restarted.confirmRecord(update.draft, context: context, expectedUpdatedAt: changed.expectedUpdatedAt)
        XCTAssertEqual(updated.payloadDocument?.payload, .temperature(celsius: 37.5))
        let deleted = try await restarted.deleteRecord(row.clientUUID, context: context, expectedUpdatedAt: updated.updatedAt)
        XCTAssertTrue(deleted)
        let finalStore = try OfflineStore(path: path)
        let tombstone = try await finalStore.recordDetails(row.clientUUID)
        XCTAssertNotNil(tombstone?.deletedAt)
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineTemperatureModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testRealOwnerCapturesBirthdayAcrossSelectionReopensEditsAndDeletes() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("temperature-model-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: path, clock: { now })
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-31")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        _ = try await store.selectBaby(a.clientUUID)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        var value = OfflineTemperatureDraft(); value.selectUnit(.fahrenheit); value.input = "100.4"
        model.openEditor(); model.editor?.edit(kind: .temperature, temperature: value)
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
        XCTAssertEqual(restarted.editor?.draft.temperature.unit, .celsius) // No persisted unit preference.
        restarted.editor?.edit(kind: .pee); XCTAssertEqual(restarted.editor?.draft.kind, .temperature)
        value = OfflineTemperatureDraft(); value.input = "37.5"; restarted.editor?.edit(temperature: value)
        guard case let .confirmed(edit)? = restarted.editor?.confirm(nowMillis: now) else { return XCTFail() }
        restarted.persist(edit); try await settle { restarted.editor == nil && !restarted.busy }
        let updated = try XCTUnwrap(restarted.records.first)
        XCTAssertEqual(updated.clientUUID, row.clientUUID)
        XCTAssertEqual(updated.payloadDocument?.payload, .temperature(celsius: 37.5))
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
        model.openEditor(); var value = OfflineTemperatureDraft(); value.input = "0"
        model.editor?.edit(kind: .temperature, temperature: value)
        guard case .explanation? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        let empty = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(empty.records.isEmpty)
        value.input = "38"; model.editor?.edit(temperature: value)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.temperature.input, "38")
        XCTAssertEqual(model.editor?.babyBirthday, baby.birthday)
        model.closeEditor(); model.cancelDiscard(); XCTAssertNotNil(model.editor)
        model.closeEditor(); model.discardChanges(); XCTAssertNil(model.editor)
        let final = try await store.recordPage(baby: baby.clientUUID); XCTAssertTrue(final.records.isEmpty)
    }
}
#endif
