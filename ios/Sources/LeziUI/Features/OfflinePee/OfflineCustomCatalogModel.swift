#if canImport(SwiftUI)
import Foundation
import SwiftUI
import LeziStorage

@MainActor
final class OfflineCustomCatalogModel: ObservableObject {
    @Published private(set) var definitions: [OfflineCustomDefinition] = []
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published var isPresented = false
    @Published var draft: OfflineCustomDefinitionDraft?
    @Published var discardRequested = false
    @Published var showingDelete = false
    @Published var showingMove = false
    @Published var deleteTarget: OfflineCustomDefinition?
    @Published var reorderTarget: [UUID]?
    private var draftSession: UUID?
    private var pendingDiscard: (session: UUID, close: Bool)?
    private var generation: UUID?
    private let store: OfflineStore
    private var task: Task<Void, Never>?
    init(store: OfflineStore) { self.store = store }
    deinit { task?.cancel() }
    func open() { guard !busy else { return }; isPresented = true; refresh() }
    func refresh() {
        guard !busy else { return }; busy = true
        let store = store
        task = Task { [weak self] in
            do {
                let identity = try await store.identity()
                let definitions = try await store.customDefinitions()
                guard !Task.isCancelled else { return }
                self?.generation = identity.generation; self?.definitions = definitions; self?.busy = false
            } catch { self?.failed(error) }
        }
    }
    func create() { guard !busy, draft == nil, definitions.count < 10 else { return }; draft = .init(); draftSession = UUID() }
    func edit(_ definition: OfflineCustomDefinition) { guard !busy, draft == nil else { return }; draft = .init(definition); draftSession = UUID() }
    func close() {
        guard !busy else { return }
        if draft?.isDirty == true, let draftSession { pendingDiscard = (draftSession, true); discardRequested = true } else { draft = nil; self.draftSession = nil; isPresented = false }
    }
    func cancelEdit() {
        guard !busy else { return }
        if draft?.isDirty == true, let draftSession { pendingDiscard = (draftSession, false); discardRequested = true } else { draft = nil; self.draftSession = nil }
    }
    func cancelDiscard() { pendingDiscard = nil; discardRequested = false }
    func discard() {
        guard !busy, let pendingDiscard, pendingDiscard.session == draftSession else { return }
        draft = nil; draftSession = nil
        if pendingDiscard.close { isPresented = false }
        cancelDiscard()
    }
    enum Command: Sendable {
        case save(OfflineCustomDefinitionDraft)
        case delete(OfflineCustomDefinition)
        case reorder([UUID])
    }
    func save() {
        guard let draft, !busy else { return }
        if let issue = draft.issue { message = issue; return }
        perform(.save(draft))
    }
    func requestDelete(_ item: OfflineCustomDefinition) { guard !busy, draft == nil else { return }; deleteTarget = item; showingDelete = true }
    func confirmDelete() { guard let item = deleteTarget, !busy else { return }; deleteTarget = nil; showingDelete = false; perform(.delete(item)) }
    func requestMove(_ item: OfflineCustomDefinition, offset: Int) {
        guard !busy, draft == nil, let index = definitions.firstIndex(where: { $0.clientUUID == item.clientUUID }),
              definitions.indices.contains(index + offset) else { return }
        var ids = definitions.map(\.clientUUID); ids.swapAt(index, index + offset); reorderTarget = ids; showingMove = true
    }
    func confirmMove() { guard let ids = reorderTarget, !busy else { return }; reorderTarget = nil; showingMove = false; perform(.reorder(ids)) }
    private func perform(_ command: Command) {
        guard !busy, let generation else { return }; busy = true; message = nil
        let store = store
        task = Task { [weak self] in
            do {
                let success: String
                switch command {
                case let .save(draft):
                    if let revision = draft.revision {
                        _ = try await store.updateCustomDefinition(draft.id, name: draft.name, iconSlot: draft.iconSlot, generation: generation, expectedUpdatedAt: revision)
                    } else {
                        _ = try await store.createCustomDefinition(clientUUID: draft.id, name: draft.name, iconSlot: draft.iconSlot, generation: generation)
                    }
                    success = "已保存本机自定义项目"
                case let .delete(item):
                    _ = try await store.deleteCustomDefinition(item.clientUUID, generation: generation, expectedUpdatedAt: item.updatedAt)
                    success = "已删除本机自定义项目；历史记录保留"
                case let .reorder(ids):
                    try await store.reorderCustomDefinitions(ids, generation: generation)
                    success = "已保存本机项目顺序"
                }
                guard !Task.isCancelled else { return }
                // Only after COMMIT may a successful edit clear its captured draft.
                if case let .save(saved) = command, self?.draft?.id == saved.id { self?.draft = nil; self?.draftSession = nil; self?.cancelDiscard() }
                self?.message = success
                do {
                    let items = try await store.customDefinitions()
                    guard !Task.isCancelled else { return }
                    self?.definitions = items; self?.busy = false
                } catch { self?.failed(error, committed: true) }
            } catch { self?.failed(error) }
        }
    }
    private func failed(_ error: Error, committed: Bool = false) {
        guard !Task.isCancelled else { return }; busy = false
        let reason: String
        switch error {
        case OfflineStoreError.duplicateCustomName: reason = "项目名称已存在，请换一个名称。"
        case OfflineStoreError.customDefinitionLimit: reason = "最多添加 10 个自定义项目。"
        case OfflineStoreError.staleEdit, OfflineStoreError.staleContext: reason = "本机目录已变化，未覆盖。请保留草稿并重新核对。"
        case OfflineStoreError.offlineOnly: reason = "此数据不允许离线写入。"
        case OfflineStoreError.deletedRecord, OfflineStoreError.missingCustomDefinition: reason = "该定义已不可用，草稿已保留。"
        default: reason = "本机目录操作失败，请重试。"
        }
        message = committed ? (message ?? "操作已提交") + "。重新读取失败。" : reason
    }
}
#endif
