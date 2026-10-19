#if canImport(SwiftUI)
import Foundation
import SwiftUI
import LeziStorage
import LeziCore

/// A library feature owner, not an application/session or a sync coordinator.
@MainActor
final class OfflinePeeModel: ObservableObject {
    @Published private(set) var babies: [OfflineBaby] = []
    @Published private(set) var selected: OfflineContext?
    @Published private(set) var records: [OfflineRecord] = []
    @Published private(set) var sleeps: [UUID: OfflineSleep] = [:]
    @Published private(set) var latestOpenSleepClientUUID: UUID?
    @Published private(set) var nextCursor: OfflineRecordCursor?
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published var editor: OfflinePeeState?
    @Published var showingDiscardConfirmation = false
    @Published var showingDeleteConfirmation = false
    private var milkRecent: [RecordType: [Int]] = [:]
    private var editorContext: OfflineContext?
    private var pendingDiscardSession: UUID?
    private var pendingDeleteSession: UUID?
    private let store: OfflineStore
    private var operation: Task<Void, Never>?
    private var save: Task<Void, Never>?

    init(store: OfflineStore) { self.store = store }
    deinit { operation?.cancel(); save?.cancel() }

    struct Snapshot: Sendable {
        let babies: [OfflineBaby]
        let context: OfflineContext?
        let page: OfflineSleepPage?
        let milkRecent: [RecordType: [Int]]
    }
    private static func snapshot(_ store: OfflineStore) async throws -> Snapshot {
        let babies = try await store.babies()
        let context: OfflineContext?
        do { context = try await store.context() }
        catch OfflineStoreError.staleContext { context = nil }
        let page: OfflineSleepPage?
        if let context { page = try await store.sleepPage(baby: context.babyClientUUID) }
        else { page = nil }
        let milkRecent: [RecordType: [Int]]
        if let context { milkRecent = try await store.recentMilkAmounts(baby: context.babyClientUUID) }
        else { milkRecent = [:] }
        return Snapshot(babies: babies, context: context, page: page, milkRecent: milkRecent)
    }
    private func apply(_ snapshot: Snapshot) {
        milkRecent = snapshot.milkRecent
        babies = snapshot.babies; selected = snapshot.context
        records = snapshot.page?.page.records ?? []; nextCursor = snapshot.page?.page.nextCursor
        sleeps = snapshot.page?.sleeps ?? [:]; latestOpenSleepClientUUID = snapshot.page?.latestOpenSleepClientUUID
    }
    func refresh() {
        guard !busy, editor?.isBusy != true else { return }
        busy = true
        let store = store
        operation = Task { [weak self] in
            do {
                let snapshot = try await Self.snapshot(store)
                guard !Task.isCancelled else { return }
                self?.apply(snapshot); self?.busy = false
            } catch { self?.fail(error) }
        }
    }
    func select(_ id: UUID) {
        guard !busy, editor?.isBusy != true else { return }
        busy = true; message = nil
        let store = store
        operation = Task { [weak self] in
            do {
                _ = try await store.selectBaby(id)
                let snapshot = try await Self.snapshot(store)
                guard !Task.isCancelled else { return }
                // Do not replace editor or editorContext when selection changes.
                self?.apply(snapshot); self?.busy = false
            } catch { self?.fail(error) }
        }
    }
    func createBaby(nickname: String, birthday: String) {
        guard !busy, editor?.isBusy != true else { return }
        busy = true; message = nil
        let store = store
        operation = Task { [weak self] in
            do {
                let baby = try await store.createBaby(nickname: nickname, birthday: birthday)
                // Creation has committed. Report that even if subsequent selection/read fails.
                self?.message = "已在本机创建宝宝：\(baby.nickname)"
                _ = try await store.selectBaby(baby.clientUUID)
                let snapshot = try await Self.snapshot(store)
                guard !Task.isCancelled else { return }
                self?.apply(snapshot); self?.busy = false
            } catch { self?.fail(error, preserveMessage: true) }
        }
    }
    func loadMore() {
        guard !busy, let selected, let cursor = nextCursor else { return }
        busy = true
        let store = store
        operation = Task { [weak self] in
            do {
                let page = try await store.sleepPage(baby: selected.babyClientUUID, before: cursor)
                guard !Task.isCancelled, let self else { return }
                let known = Set(self.records.map(\.clientUUID))
                self.records += page.page.records.filter { !known.contains($0.clientUUID) }
                self.nextCursor = page.page.nextCursor
                self.sleeps.merge(page.sleeps) { _, new in new }
                self.latestOpenSleepClientUUID = page.latestOpenSleepClientUUID; self.busy = false
            } catch { self?.fail(error) }
        }
    }
    func openEditor(milkStep: Int = 5) {
        guard editor == nil, !busy, let selected,
              let baby = babies.first(where: { $0.clientUUID == selected.babyClientUUID }) else { return }
        editorContext = selected
        editor = .init(babyClientUUID: baby.clientUUID, babyName: baby.nickname,
                       timestampMillis: Int64(Date().timeIntervalSince1970 * 1000), milkRecent: milkRecent, milkStep: milkStep, babyBirthday: baby.birthday)
    }
    func openEditor(definition: OfflineCustomDefinition) {
        guard editor == nil, !busy, definition.deletedAt == nil, let selected,
              let baby = babies.first(where: { $0.clientUUID == selected.babyClientUUID }) else { return }
        editorContext = selected
        editor = .init(babyClientUUID: baby.clientUUID, babyName: baby.nickname,
                       timestampMillis: Int64(Date().timeIntervalSince1970 * 1000), definition: definition, babyBirthday: baby.birthday)
    }
    func openEditor(record: OfflineRecord, milkStep: Int = 5) {
        guard editor == nil, !busy, let selected,
              selected.babyClientUUID == record.babyClientUUID,
              let baby = babies.first(where: { $0.clientUUID == record.babyClientUUID }) else { return }
        guard let state = OfflinePeeState(record: record, babyName: baby.nickname, milkRecent: milkRecent, milkStep: milkStep, babyBirthday: baby.birthday) else {
            message = "此记录暂不支持安全编辑，原始内容已保留。"
            return
        }
        // Capture the displayed revision, never silently reload and overwrite a newer row.
        editorContext = selected; editor = state
    }
    func closeEditor() {
        guard !busy, let editor else { return }
        switch editor.dismissDecision {
        case .ignoreBusy: return
        case .confirmDiscard:
            pendingDiscardSession = editor.session.context.sessionID
            showingDiscardConfirmation = true
        case .close: clearEditor()
        }
    }
    func discardChanges() {
        guard let editor, pendingDiscardSession == editor.session.context.sessionID,
              !busy, !editor.isBusy else { return }
        clearEditor()
    }
    func cancelDiscard() { pendingDiscardSession = nil; showingDiscardConfirmation = false }
    func cancelDelete() { pendingDeleteSession = nil; showingDeleteConfirmation = false }
    private func clearEditor() {
        editor = nil; editorContext = nil
        cancelDiscard(); cancelDelete()
    }
    func requestDelete() {
        guard !busy, editor?.isEditing == true, editor?.isBusy == false,
              editor?.session.canInteract == true else { return }
        pendingDeleteSession = editor?.session.context.sessionID
        showingDeleteConfirmation = true
    }
    func confirmDelete() {
        guard !busy, let context = editorContext,
              let current = editor, pendingDeleteSession == current.session.context.sessionID,
              let revision = current.expectedUpdatedAt,
              current.draft.babyClientUUID == context.babyClientUUID,
              let attempt = editor?.beginDeletion() else { return }
        cancelDelete()
        let store = store
        let editorID = current.session.context.sessionID
        save = Task { [weak self] in
            do {
                let deleted = try await store.deleteRecord(current.draft.clientUUID, context: context,
                                                           expectedUpdatedAt: revision)
                guard !Task.isCancelled, let self,
                      self.editor?.session.context.sessionID == editorID,
                      self.editor?.deletionAttemptID == attempt else { return }
                self.editor?.finishDeletion(attemptID: attempt)
                self.clearEditor()
                self.message = deleted ? "已删除本机\(current.draft.label)" : "这条记录已不在本机列表中"
                self.refresh()
            } catch {
                guard !Task.isCancelled, let self,
                      self.editor?.session.context.sessionID == editorID else { return }
                self.editor?.finishDeletion(attemptID: attempt, failure: Self.explanation(error))
            }
        }
    }
    func persist(_ frozen: LeziConfirmedRecord) {
        guard !busy, editor?.accepts(frozen) == true, let context = editorContext,
              context.babyClientUUID == frozen.draft.babyClientUUID,
              editor?.beginPersistence(frozen) == true else { return }
        let store = store
        let label = editor?.draft.label ?? "记录"
        let revision = editor?.expectedUpdatedAt
        save = Task { [weak self] in
            do {
                // This returns only after SQLite COMMIT. Never complete on dispatch/enqueue.
                if frozen.draft.item.recordType == .custom {
                    _ = try await store.confirmCustomRecord(frozen.draft, context: context, expectedUpdatedAt: revision)
                } else {
                    _ = try await store.confirmRecord(frozen.draft, context: context, expectedUpdatedAt: revision)
                }
                guard !Task.isCancelled, let self, self.editor?.accepts(frozen) == true else { return }
                self.editor?.finish(attemptID: frozen.attemptID)
                self.clearEditor()
                self.message = "已保存到本机：\(frozen.context.babyName)的\(label)"
                self.refresh()
            } catch {
                guard !Task.isCancelled, let self, self.editor?.accepts(frozen) == true else { return }
                self.editor?.finish(attemptID: frozen.attemptID, failure: Self.explanation(error))
            }
        }
    }
    private func fail(_ error: Error, preserveMessage: Bool = false) {
        guard !Task.isCancelled else { return }
        busy = false
        let detail = Self.explanation(error)
        message = preserveMessage && message != nil ? message! + "。" + detail : detail
    }
    private static func explanation(_ error: Error) -> String {
        switch error {
        case OfflineStoreError.staleEdit: "这条记录已变化，未覆盖现有内容。草稿已保留；请关闭后重新打开核对。"
        case OfflineStoreError.deletedRecord: "这条记录已被删除，未重新创建。草稿已保留。"
        case OfflineStoreError.opaqueRecord: "此记录暂不支持安全编辑，原始内容与草稿已保留。"
        case OfflineStoreError.duplicateNickname: "宝宝昵称已存在，请换一个昵称。"
        case OfflineStoreError.invalidDraft: "请检查输入：昵称最多 20 个 Unicode 字符，日期及记录内容须有效。"
        case OfflineStoreError.offlineOnly: "此数据已不允许离线写入。请保留草稿并关闭此开发组件。"
        case OfflineStoreError.staleContext, OfflineStoreError.missingBaby: "原宝宝或本机数据已变化，无法保存；草稿已保留。"
        default: "本机操作失败，请重试。未完成的记录草稿已保留。"
        }
    }
}
#endif
