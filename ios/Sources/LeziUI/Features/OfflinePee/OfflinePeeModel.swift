#if canImport(SwiftUI)
import Foundation
import SwiftUI
import LeziStorage

/// A library feature owner, not an application/session or a sync coordinator.
@MainActor
final class OfflinePeeModel: ObservableObject {
    @Published private(set) var babies: [OfflineBaby] = []
    @Published private(set) var selected: OfflineContext?
    @Published private(set) var records: [OfflinePeeRecord] = []
    @Published private(set) var nextCursor: OfflineRecordCursor?
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published var editor: OfflinePeeState?
    private var editorContext: OfflineContext?
    private let store: OfflineStore
    private var operation: Task<Void, Never>?
    private var save: Task<Void, Never>?

    init(store: OfflineStore) { self.store = store }
    deinit { operation?.cancel(); save?.cancel() }

    struct Snapshot: Sendable {
        let babies: [OfflineBaby]
        let context: OfflineContext?
        let page: OfflineRecordPage?
    }
    private static func snapshot(_ store: OfflineStore) async throws -> Snapshot {
        let babies = try await store.babies()
        let context: OfflineContext?
        do { context = try await store.context() }
        catch OfflineStoreError.staleContext { context = nil }
        let page: OfflineRecordPage?
        if let context { page = try await store.records(baby: context.babyClientUUID) }
        else { page = nil }
        return Snapshot(babies: babies, context: context, page: page)
    }
    private func apply(_ snapshot: Snapshot) {
        babies = snapshot.babies; selected = snapshot.context
        records = snapshot.page?.records ?? []; nextCursor = snapshot.page?.nextCursor
    }
    func refresh() {
        guard !busy, editor?.session.isSaving != true else { return }
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
        guard !busy else { return }
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
        guard !busy else { return }
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
                let page = try await store.records(baby: selected.babyClientUUID, before: cursor)
                guard !Task.isCancelled, let self else { return }
                let known = Set(self.records.map(\.clientUUID))
                self.records += page.records.filter { !known.contains($0.clientUUID) }
                self.nextCursor = page.nextCursor; self.busy = false
            } catch { self?.fail(error) }
        }
    }
    func openEditor() {
        guard editor == nil, !busy, let selected,
              let baby = babies.first(where: { $0.clientUUID == selected.babyClientUUID }) else { return }
        editorContext = selected
        editor = .init(babyClientUUID: baby.clientUUID, babyName: baby.nickname,
                       timestampMillis: Int64(Date().timeIntervalSince1970 * 1000))
    }
    func closeEditor() {
        guard editor?.session.isSaving != true else { return }
        editor = nil; editorContext = nil
    }
    func persist(_ frozen: LeziConfirmedRecord) {
        guard editor?.accepts(frozen) == true, let context = editorContext,
              context.babyClientUUID == frozen.draft.babyClientUUID,
              editor?.beginPersistence(frozen) == true else { return }
        let store = store
        save = Task { [weak self] in
            do {
                // This returns only after SQLite COMMIT. Never complete on dispatch/enqueue.
                _ = try await store.confirmPee(frozen.draft, context: context)
                guard !Task.isCancelled, let self, self.editor?.accepts(frozen) == true else { return }
                self.editor?.finish(attemptID: frozen.attemptID)
                self.editor = nil; self.editorContext = nil
                self.message = "已保存到本机：\(frozen.context.babyName)的尿记录"
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
        case OfflineStoreError.duplicateNickname: "宝宝昵称已存在，请换一个昵称。"
        case OfflineStoreError.invalidDraft: "请检查输入：昵称最多 20 个 Unicode 字符，日期及记录内容须有效。"
        case OfflineStoreError.offlineOnly: "此数据已不允许离线写入。请保留草稿并关闭此开发组件。"
        case OfflineStoreError.staleContext, OfflineStoreError.missingBaby: "原宝宝或本机数据已变化，无法保存；草稿已保留。"
        default: "本机操作失败，请重试。未完成的记录草稿已保留。"
        }
    }
}
#endif
