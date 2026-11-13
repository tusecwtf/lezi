#if canImport(SwiftUI)
import Foundation
import SwiftUI
import LeziStorage

@MainActor
final class OfflineBabyProfileModel: ObservableObject {
    @Published private(set) var editor: OfflineBabyProfileState?
    @Published private(set) var message: String?
    @Published private(set) var completion = 0
    @Published var discardRequested = false
    private var pendingDiscardSession: UUID?
    private let store: OfflineStore
    private var task: Task<Void, Never>?

    init(store: OfflineStore) { self.store = store }
    deinit { task?.cancel() }
    var busy: Bool { editor?.isBusy == true }

    func open(baby: OfflineBaby, context: OfflineContext) {
        guard editor == nil else { return }
        editor = .init(baby: baby, context: context)
        if editor != nil { message = nil }
    }
    func editNickname(_ value: String) {
        // Android's editable field clips at 20 code points, not UTF-16 units
        // or user-perceived graphemes. Defensive draft/store validation remains.
        editor?.editNickname(String(String.UnicodeScalarView(value.unicodeScalars.prefix(20))))
    }
    func editSex(_ value: String?) { editor?.editSex(value) }
    func editBirthday(_ value: String) { editor?.editBirthday(value) }
    func editWeight(_ value: String) { editor?.editWeight(value) }
    func close() {
        guard let editor, !editor.isBusy else { return }
        if editor.isDirty {
            pendingDiscardSession = editor.sessionID; discardRequested = true
        } else { clearEditor() }
    }
    func keepEditing() { pendingDiscardSession = nil; discardRequested = false }
    func discard() {
        guard !busy, let editor, pendingDiscardSession == editor.sessionID else { return }
        clearEditor()
    }
    private func clearEditor() { editor = nil; keepEditing() }

    func save() {
        guard let sessionID = editor?.sessionID, let frozen = editor?.beginSave() else { return }
        keepEditing()
        let store = store
        task = Task { [weak self] in
            do {
                let saved = try await store.updateBabyProfile(
                    frozen.babyClientUUID, nickname: frozen.nickname, sex: frozen.sex,
                    birthday: frozen.birthday, birthWeightGrams: frozen.birthWeightGrams,
                    context: frozen.context, expectedUpdatedAt: frozen.expectedUpdatedAt
                )
                guard !Task.isCancelled, let self, self.editor?.sessionID == sessionID,
                      self.editor?.finishSave(attemptID: frozen.attemptID) == true else { return }
                // No enqueue/dispatch success. Clear only after SQLite COMMIT.
                self.clearEditor()
                self.message = "已保存本机宝宝档案：\(saved.nickname)"
                self.completion += 1
            } catch {
                guard !Task.isCancelled, let self, self.editor?.sessionID == sessionID else { return }
                self.editor?.finishSave(attemptID: frozen.attemptID, failure: Self.explanation(error))
            }
        }
    }

    private static func explanation(_ error: Error) -> String {
        switch error {
        case OfflineStoreError.duplicateNickname: return "宝宝昵称已存在，请换一个昵称。"
        case OfflineStoreError.staleEdit, OfflineStoreError.staleContext:
            return "宝宝档案或本机数据已变化，未覆盖。草稿已保留，请关闭后重新核对。"
        case OfflineStoreError.missingBaby: return "此宝宝档案已不可用，草稿已保留。"
        case OfflineStoreError.offlineOnly: return "此数据不允许离线写入，草稿已保留。"
        case OfflineStoreError.invalidDraft: return "档案内容不符合要求，请检查后重试。"
        default: return "保存失败，草稿已保留，请重试。"
        }
    }
}
#endif
