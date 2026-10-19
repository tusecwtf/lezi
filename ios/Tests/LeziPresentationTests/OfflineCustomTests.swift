import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineCustomStateTests: XCTestCase {
    private func definition(_ name: String = "项目甲", icon: Int = 0) -> OfflineCustomDefinition {
        .init(clientUUID: UUID(), name: name, iconSlot: icon, updatedAt: 1000, syncDirty: true, deletedAt: nil, sortOrder: 0)
    }
    func testConcreteTargetNeverEntersBuiltInPickerAndCannotBeRetargeted() {
        XCTAssertNil(OfflineRecordKind(rawValue: "custom"))
        let item = definition()
        var state = OfflinePeeState(babyClientUUID: UUID(), babyName: "测试", timestampMillis: 900, definition: item)
        XCTAssertNil(state.draft.kind)
        XCTAssertEqual(state.draft.target, .custom(item.clientUUID))
        XCTAssertEqual(state.draft.confirmation.item, .customDefinition(item.clientUUID))
        XCTAssertNil(state.draft.confirmation.item.legacyLocalCustomID)
        state.edit(kind: .pee); XCTAssertEqual(state.draft.target, .custom(item.clientUUID))
        XCTAssertEqual(state.draft.payload, .custom(titleSnapshot: item.name, detail: nil, iconSlot: 0))
        var snapshot = state.draft.custom; snapshot.title = "记录名称"; snapshot.detail = " 详情 "
        state.edit(custom: snapshot)
        XCTAssertEqual(state.draft.payload, .custom(titleSnapshot: "记录名称", detail: "详情", iconSlot: 0))
        XCTAssertEqual(state.dismissDecision, .confirmDiscard)
    }
    func testDefinitionAndRecordUTF16LimitsRetainInvalidAndLegacyInputs() {
        var catalog = OfflineCustomDefinitionDraft()
        catalog.name = String(repeating: "😀", count: 10); XCTAssertNil(catalog.issue)
        catalog.name += "a"; XCTAssertEqual(catalog.issue, "名称最多 20 字")
        catalog.name = "\u{001C} "; XCTAssertEqual(catalog.issue, "请输入名称")
        catalog.name = "有效"; catalog.iconSlot = 8; XCTAssertNotNil(catalog.issue)
        var legacy = OfflineCustomDefinitionDraft(definition(String(repeating: "旧", count: 21)))
        legacy.iconSlot = 1; XCTAssertNil(legacy.issue)
        legacy.name += "新"; XCTAssertNotNil(legacy.issue)
        let original = OfflineCustomDraft(title: String(repeating: "旧", count: 31), detail: String(repeating: "详", count: 201), iconSlot: nil)
        XCTAssertNil(original.issue(original: original))
        var changed = original; changed.title += "新"; XCTAssertEqual(changed.issue(original: original)?.fieldID, "custom.title")
        changed = original; changed.detail += "新"; XCTAssertEqual(changed.issue(original: original)?.fieldID, "custom.detail")
        XCTAssertNil(OfflineCustomDraft(title: String(repeating: "😀", count: 15), detail: String(repeating: "😀", count: 100)).issue(original: nil))
        XCTAssertEqual(OfflineCustomDraft(title: "  ").issue(original: nil)?.fieldID, "custom.title")
    }
    func testMissingAssociationOpaqueAndIntervalStayReadOnly() throws {
        let payload = RecordPayload.custom(titleSnapshot: "历史", detail: nil, iconSlot: nil)
        let json = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
        for (id, schema, end) in [(nil as UUID?, 2, nil as Int64?), (UUID(), 99, nil), (UUID(), 2, 950)] {
            let row = OfflineRecord(clientUUID: UUID(), babyClientUUID: UUID(), rawType: "custom", timestampMillis: 900,
                endTimestampMillis: end, note: nil, payloadJSON: json, payloadSchemaVersion: schema,
                payloadDocument: RecordPayloadCodec.decode(type: .custom, payloadJSON: json, schemaVersion: schema),
                updatedAt: 1000, syncDirty: true, deletedAt: nil, customDefinitionUUID: id)
            XCTAssertFalse(OfflinePeeState.canEdit(row))
            XCTAssertNil(OfflinePeeState(record: row, babyName: "测试"))
        }
    }
    @MainActor
    func testRealStoreRenameDeleteRestartPreservesHistoryAndOriginalBaby() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("custom-ui-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let generation = try await store.identity().generation
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        let context = try await store.selectBaby(a.clientUUID)
        let first = try await store.createCustomDefinition(name: "项目甲", iconSlot: 0, generation: generation)
        let second = try await store.createCustomDefinition(name: "项目乙", iconSlot: 1, generation: generation)
        var state = OfflinePeeState(babyClientUUID: a.clientUUID, babyName: a.nickname, timestampMillis: 900, definition: first)
        let renamed = try await store.updateCustomDefinition(first.clientUUID, name: "改名甲", iconSlot: 2, generation: generation, expectedUpdatedAt: first.updatedAt)
        XCTAssertEqual(state.draft.custom.title, "项目甲"); XCTAssertEqual(state.draft.custom.iconSlot, 0)
        _ = try await store.selectBaby(b.clientUUID)
        guard case let .confirmed(create) = state.confirm(nowMillis: 1000) else { return XCTFail() }
        let row = try await store.confirmCustomRecord(create.draft, context: context)
        _ = try await store.deleteCustomDefinition(first.clientUUID, generation: generation, expectedUpdatedAt: renamed.updatedAt)
        let reopened = try OfflineStore(path: path, clock: { 1000 })
        let reread = try await reopened.recordDetails(row.clientUUID)
        var edit = try XCTUnwrap(OfflinePeeState(record: XCTUnwrap(reread), babyName: a.nickname))
        var custom = edit.draft.custom; custom.title = "历史改名"; edit.edit(custom: custom)
        XCTAssertEqual(edit.draft.target, .custom(first.clientUUID)); XCTAssertEqual(edit.draft.custom.iconSlot, 0)
        guard case let .confirmed(frozen) = edit.confirm(nowMillis: 1000) else { return XCTFail() }
        XCTAssertTrue(edit.beginPersistence(frozen)); XCTAssertFalse(edit.beginPersistence(frozen))
        let updated = try await reopened.confirmCustomRecord(frozen.draft, context: context, expectedUpdatedAt: edit.expectedUpdatedAt)
        XCTAssertEqual(updated.payloadDocument?.payload, .custom(titleSnapshot: "历史改名", detail: nil, iconSlot: 0))
        let cross = RecordConfirmationDraft(clientUUID: updated.clientUUID, babyClientUUID: a.clientUUID,
            item: .customDefinition(second.clientUUID), timestampMillis: 900, payload: try XCTUnwrap(updated.payloadDocument?.payload))
        do { _ = try await reopened.confirmCustomRecord(cross, context: context, expectedUpdatedAt: updated.updatedAt); XCTFail() }
        catch { XCTAssertEqual(error as? OfflineStoreError, .identityConflict) }
        let deleted = try await reopened.deleteRecord(updated.clientUUID, context: context, expectedUpdatedAt: updated.updatedAt)
        XCTAssertTrue(deleted)
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineCustomModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    func testCatalogActualCreateRenameSortDeleteAndRestart() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("catalog-model-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let model = OfflineCustomCatalogModel(store: store); model.open(); try await settle { !model.busy }
        model.create(); model.draft?.name = "项目甲"; model.save(); model.save()
        try await settle { !model.busy }; XCTAssertNil(model.draft); XCTAssertEqual(model.definitions.count, 1)
        let a = try XCTUnwrap(model.definitions.first)
        model.create(); model.draft?.name = "项目乙"; model.draft?.iconSlot = 1; model.save()
        try await settle { !model.busy }
        let b = try XCTUnwrap(model.definitions.last)
        model.requestMove(b, offset: -1); XCTAssertEqual(model.definitions.first?.clientUUID, a.clientUUID)
        model.confirmMove(); model.confirmMove(); try await settle { !model.busy }
        XCTAssertEqual(model.definitions.map(\.clientUUID), [b.clientUUID, a.clientUUID])
        model.edit(a); model.draft?.name = "改名甲"; model.draft?.iconSlot = 2; model.save()
        try await settle { !model.busy }
        let restarted = OfflineCustomCatalogModel(store: try OfflineStore(path: path, clock: { 1000 }))
        restarted.open(); try await settle { !restarted.busy }
        XCTAssertEqual(restarted.definitions.map(\.clientUUID), [b.clientUUID, a.clientUUID])
        let renamed = try XCTUnwrap(restarted.definitions.last)
        XCTAssertEqual(renamed.name, "改名甲"); XCTAssertEqual(renamed.iconSlot, 2)
        restarted.requestDelete(renamed); restarted.deleteTarget = nil
        XCTAssertEqual(restarted.definitions.count, 2)
        restarted.requestDelete(renamed); restarted.confirmDelete(); restarted.confirmDelete()
        try await settle { !restarted.busy }; XCTAssertEqual(restarted.definitions.map(\.clientUUID), [b.clientUUID])
        let finalStore = try OfflineStore(path: path)
        let tombstone = try await finalStore.customDefinition(a.clientUUID); XCTAssertNotNil(tombstone?.deletedAt)
    }
    func testCatalogRollbackDuplicateSaveAndDirtyDiscardRetainDraft() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let model = OfflineCustomCatalogModel(store: store); model.open(); try await settle { !model.busy }
        model.create(); model.draft?.name = "项目甲"
        let id = try XCTUnwrap(model.draft?.id)
        await store.failNextConfirmation(at: .beforeCommit)
        model.save(); model.save(); try await settle { !model.busy }
        XCTAssertEqual(model.draft?.id, id); XCTAssertEqual(model.draft?.name, "项目甲")
        let empty = try await store.customDefinitions(); XCTAssertTrue(empty.isEmpty)
        model.close(); XCTAssertTrue(model.discardRequested); XCTAssertTrue(model.isPresented)
        model.cancelDiscard(); model.save(); try await settle { !model.busy }
        XCTAssertEqual(model.definitions.count, 1)
        let old = try XCTUnwrap(model.definitions.first)
        model.edit(old); model.draft?.name = "旧版本修改"
        let generation = try await store.identity().generation
        _ = try await store.updateCustomDefinition(old.clientUUID, name: "外部改名", iconSlot: 1, generation: generation, expectedUpdatedAt: old.updatedAt)
        model.save(); try await settle { !model.busy }
        XCTAssertEqual(model.draft?.name, "旧版本修改")
        let current = try await store.customDefinition(old.clientUUID); XCTAssertEqual(current?.name, "外部改名")
        model.cancelEdit(); model.discard()
        model.create(); model.draft?.name = "未保存"; model.cancelEdit(); model.discard()
        XCTAssertNil(model.draft)
        let final = try await store.customDefinitions(); XCTAssertEqual(final.count, 1)
    }
    func testCatalogCloseDiscardAndCancelEditHaveDistinctSessionBoundIntent() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let model = OfflineCustomCatalogModel(store: store); model.open(); try await settle { !model.busy }
        model.create(); model.draft?.name = "未保存"
        model.cancelEdit(); model.discard(); XCTAssertNil(model.draft); XCTAssertTrue(model.isPresented)
        model.create(); model.draft?.name = "另一个"
        model.close(); model.discard(); XCTAssertNil(model.draft); XCTAssertFalse(model.isPresented)
        model.open(); try await settle { !model.busy }
        model.create(); model.draft?.name = "旧草稿"; model.close()
        // Replacement editor has its own session even when an old alert callback arrives.
        model.draft = nil; model.create(); model.draft?.name = "新草稿"
        model.discard(); XCTAssertEqual(model.draft?.name, "新草稿"); XCTAssertTrue(model.isPresented)
    }
    func testCatalogReadFailureRetainsDefinitionsAndDirtyDraft() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("catalog-read-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let generation = try await store.identity().generation
        let definition = try await store.createCustomDefinition(name: "原项目", iconSlot: 0, generation: generation)
        let model = OfflineCustomCatalogModel(store: store); model.open(); try await settle { !model.busy }
        model.edit(definition); model.draft?.name = "未保存修改"
        let draft = model.draft
        let db = try SQLiteConnection(path: path)
        try db.execute("ALTER TABLE custom_definitions RENAME TO unavailable_custom_definitions")
        model.refresh(); try await settle { !model.busy }
        XCTAssertEqual(model.definitions, [definition]); XCTAssertEqual(model.draft, draft)
        XCTAssertEqual(model.message, "本机目录操作失败，请重试。")
    }
    func testRecordModelOriginalBabySnapshotRenameDeleteHistoryAndRollback() async throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000) + 1000
        let store = try OfflineStore(path: ":memory:", clock: { now })
        let generation = try await store.identity().generation
        let a = try await store.createBaby(nickname: "测试甲", birthday: "2026-01-01")
        let b = try await store.createBaby(nickname: "测试乙", birthday: "2020-01-01")
        _ = try await store.selectBaby(a.clientUUID)
        let definition = try await store.createCustomDefinition(name: "项目甲", iconSlot: 0, generation: generation)
        let model = OfflinePeeModel(store: store); model.refresh(); try await settle { !model.busy }
        model.openEditor(definition: definition)
        let renamed = try await store.updateCustomDefinition(definition.clientUUID, name: "新目录名", iconSlot: 1, generation: generation, expectedUpdatedAt: definition.updatedAt)
        model.select(b.clientUUID); try await settle { !model.busy }
        XCTAssertEqual(model.editor?.draft.custom.title, "项目甲"); XCTAssertEqual(model.editor?.draft.custom.iconSlot, 0)
        XCTAssertEqual(model.editor?.draft.babyClientUUID, a.clientUUID)
        guard case let .confirmed(frozen)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        await store.failNextConfirmation(at: .beforeCommit)
        model.persist(frozen); model.persist(frozen)
        try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.custom.title, "项目甲")
        let empty = try await store.recordPage(baby: a.clientUUID); XCTAssertTrue(empty.records.isEmpty)
        guard case let .confirmed(retry)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(retry); try await settle { model.editor == nil && !model.busy }
        _ = try await store.deleteCustomDefinition(definition.clientUUID, generation: generation, expectedUpdatedAt: renamed.updatedAt)
        model.select(a.clientUUID); try await settle { !model.busy }
        let row = try XCTUnwrap(model.records.first)
        model.openEditor(record: row)
        var edit = try XCTUnwrap(model.editor?.draft.custom); edit.title = "修改历史"; edit.detail = "说明"
        model.editor?.edit(custom: edit)
        guard case let .confirmed(update)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(update); try await settle { model.editor == nil && !model.busy }
        let updated = try XCTUnwrap(model.records.first)
        XCTAssertEqual(updated.customDefinitionUUID, definition.clientUUID)
        XCTAssertEqual(updated.payloadDocument?.payload, .custom(titleSnapshot: "修改历史", detail: "说明", iconSlot: 0))
        model.openEditor(record: updated); model.requestDelete(); model.confirmDelete()
        try await settle { model.editor == nil && !model.busy }; XCTAssertTrue(model.records.isEmpty)
        // A stale captured definition cannot create a new fact after deletion.
        model.openEditor(definition: definition)
        guard case let .confirmed(stale)? = model.editor?.confirm(nowMillis: now) else { return XCTFail() }
        model.persist(stale); try await settle { model.editor?.session.phase == .editing }
        XCTAssertEqual(model.editor?.draft.target, .custom(definition.clientUUID))
        let final = try await store.recordPage(baby: a.clientUUID); XCTAssertTrue(final.records.isEmpty)
    }
}
#endif
