#if canImport(SwiftUI)
import Foundation
import SwiftUI
import LeziStorage

@MainActor
final class OfflineSleepModel: ObservableObject {
    @Published private(set) var editor: OfflineSleepState?
    @Published private(set) var loading = false
    @Published private(set) var message: String?
    @Published private(set) var completion: UUID?
    @Published var showingDiscard = false
    private var discardSession: UUID?
    private let store: OfflineStore
    private var task: Task<Void, Never>?
    var busy: Bool { loading || editor?.isBusy == true }
    init(store: OfflineStore) { self.store = store }
    deinit { task?.cancel() }
    func openStart(context: OfflineContext, babyName: String, now: Int64) {
        guard !busy, editor == nil else { return }
        message = nil
        editor = .init(context: context, babyName: babyName, timestampMillis: now)
    }
    func openWake(context: OfflineContext, babyName: String, now: Int64) {
        guard !busy, editor == nil else { return }
        loading = true; message = nil
        let store = store
        task = Task { [weak self] in
            do {
                let sleep = try await store.openSleep(baby: context.babyClientUUID)
                guard !Task.isCancelled, let self else { return }
                self.loading = false
                guard let sleep, OfflineSleepState.supports(sleep), sleep.interval.isWakeShortcutTarget else {
                    self.message = "没有可记录醒来的本机开放睡眠。"; return
                }
                self.editor = .init(context: context, babyName: babyName, timestampMillis: now,
                    action: .wake(sleepID: sleep.record.clientUUID, start: sleep.record.timestampMillis))
            } catch {
                guard !Task.isCancelled else { return }
                self?.loading = false; self?.message = "读取本机睡眠失败，请重试。"
            }
        }
    }
    func edit(timestamp: Int64? = nil, note: String? = nil) { editor?.edit(timestamp: timestamp, note: note) }
    func close() {
        guard !busy, let editor else { return }
        if editor.isDirty { discardSession = editor.sessionID; showingDiscard = true }
        else { self.editor = nil }
    }
    func cancelDiscard() { showingDiscard = false; discardSession = nil }
    func discard() {
        guard !busy, editor?.sessionID == discardSession, discardSession != nil else { return }
        editor = nil; cancelDiscard()
    }
    func confirm(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        guard !loading, let frozen = editor, let (attempt, command) = editor?.begin(now: now) else { return }
        let store = store
        task = Task { [weak self] in
            do {
                switch command {
                case let .start(draft): _ = try await store.createSleepStart(draft, context: frozen.context)
                case let .wake(draft): _ = try await store.recordWake(draft, context: frozen.context)
                }
                guard !Task.isCancelled, let self, self.editor?.sessionID == frozen.sessionID,
                      self.editor?.finish(attempt) == true else { return }
                self.editor = nil; self.cancelDiscard()
                self.message = frozen.action == .start ? "已保存本机睡下记录" : "已保存本机醒来观察"
                self.completion = attempt // COMMIT succeeded independently of the host's subsequent read.
            } catch {
                guard !Task.isCancelled, let self, self.editor?.sessionID == frozen.sessionID else { return }
                self.editor?.finish(attempt, error: "本机睡眠保存失败，原草稿已保留。请核对时间或重新读取睡眠状态后重试。")
            }
        }
    }
}
#endif
