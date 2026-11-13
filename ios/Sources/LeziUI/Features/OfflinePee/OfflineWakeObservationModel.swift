#if canImport(SwiftUI)
import Foundation
import SwiftUI
import LeziStorage

@MainActor
final class OfflineWakeObservationModel: ObservableObject {
    @Published private(set) var details: OfflineWakeObservationListState?
    @Published private(set) var editor: OfflineWakeObservationState?
    @Published private(set) var busy = false
    @Published private(set) var message: String?
    @Published private(set) var completion: UUID?
    @Published var showingDiscard = false
    private var pendingDiscard: (session: UUID, close: Bool)?
    private var operationID: UUID?
    private let store: OfflineStore
    private var task: Task<Void, Never>?

    init(store: OfflineStore) { self.store = store }
    deinit { task?.cancel() }
    func open(sleep: OfflineSleep, context: OfflineContext, babyName: String,
              localActorUUID: UUID, zone: TimeZone = .current) {
        guard !busy, details == nil else { return }
        guard let details = OfflineWakeObservationListState(sleep: sleep, context: context,
            babyName: babyName, localActorUUID: localActorUUID, zone: zone) else {
            message = "此睡眠暂不支持安全修改醒来观察，原始内容已保留。"; return
        }
        self.details = details; message = nil
    }
    func openEditor(_ wake: OfflineWakeObservation) {
        guard !busy, editor == nil, let details else { return }
        editor = .init(wake: wake, list: details); message = nil
    }
    func edit(timeText: String? = nil, note: String? = nil) { editor?.edit(timeText: timeText, note: note) }
    func close() { closeEditor(closeSurface: true) }
    func cancelEdit() { closeEditor(closeSurface: false) }
    private func closeEditor(closeSurface: Bool) {
        guard !busy else { return }
        if let editor, editor.isDirty {
            pendingDiscard = (editor.sessionID, closeSurface); showingDiscard = true
        } else {
            editor = nil; cancelDiscard()
            if closeSurface { details = nil }
        }
    }
    func cancelDiscard() { pendingDiscard = nil; showingDiscard = false }
    func discard() {
        guard !busy, let pendingDiscard, editor?.sessionID == pendingDiscard.session else { return }
        editor = nil
        if pendingDiscard.close { details = nil }
        cancelDiscard()
    }
    func confirm(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        guard !busy, let details, let frozen = editor, let (attempt, command) = editor?.begin(now: now) else { return }
        busy = true; operationID = attempt; message = nil
        let store = store
        task = Task { [weak self] in
            do {
                _ = try await store.updateWake(command.draft, context: command.context,
                    expectedUpdatedAt: command.wakeRevision, expectedSleepUpdatedAt: command.sleepRevision)
                guard !Task.isCancelled, let self, self.operationID == attempt,
                      self.details?.sessionID == details.sessionID,
                      self.editor?.sessionID == frozen.sessionID,
                      self.editor?.finish(attempt) == true else { return }
                self.editor = nil; self.cancelDiscard()
                await self.committed(attempt, details: details, message: "醒来观察已修正")
            } catch {
                guard !Task.isCancelled, let self, self.operationID == attempt,
                      self.editor?.sessionID == frozen.sessionID else { return }
                self.editor?.finish(attempt, error: Self.failure(error))
                self.operationID = nil; self.busy = false
            }
        }
    }
    /// Android's card withdraws directly. It is not deletion or a restore toggle.
    func withdraw(_ wake: OfflineWakeObservation) {
        guard !busy, editor == nil, let details, details.canEdit(wake) else { return }
        let attempt = UUID(); operationID = attempt; busy = true; message = nil
        let store = store
        task = Task { [weak self] in
            do {
                _ = try await store.withdrawWake(wake.clientUUID, sleepClientUUID: details.sleep.record.clientUUID,
                    context: details.context, expectedUpdatedAt: wake.updatedAt,
                    expectedSleepUpdatedAt: details.sleep.record.updatedAt)
                guard !Task.isCancelled, let self, self.operationID == attempt,
                      self.details?.sessionID == details.sessionID else { return }
                await self.committed(attempt, details: details, message: "醒来观察已撤回")
            } catch {
                guard !Task.isCancelled, let self, self.operationID == attempt else { return }
                self.message = Self.failure(error); self.operationID = nil; self.busy = false
            }
        }
    }
    func refresh() {
        guard !busy, editor == nil, let details else { return }
        let attempt = UUID(); operationID = attempt; busy = true
        task = Task { [weak self] in
            guard let self else { return }
            await self.readDetails(details, attempt: attempt, committed: false)
        }
    }
    private func committed(_ attempt: UUID, details: OfflineWakeObservationListState, message: String) async {
        // Report the successful COMMIT independently of the subsequent refresh.
        self.message = message; completion = attempt
        await readDetails(details, attempt: attempt, committed: true)
    }
    private func readDetails(_ previous: OfflineWakeObservationListState, attempt: UUID, committed: Bool) async {
        do {
            let sleep = try await store.sleepDetails(previous.sleep.record.clientUUID)
            guard !Task.isCancelled, operationID == attempt, details?.sessionID == previous.sessionID else { return }
            if let sleep, let updated = previous.refreshed(sleep) { details = updated }
            else {
                details = nil
                message = committed ? (message ?? "操作已提交") + "。关联睡眠已不可用。" : "关联睡眠已不可用，未作修改。"
            }
        } catch {
            guard !Task.isCancelled, operationID == attempt else { return }
            message = committed ? (message ?? "操作已提交") + "。重新读取失败，请重新读取本机观察。"
                                : "重新读取本机观察失败，请重试。"
        }
        operationID = nil; busy = false
    }
    private static func failure(_ error: Error) -> String {
        switch error {
        case OfflineStoreError.staleEdit, OfflineStoreError.staleContext:
            "睡眠或醒来观察已变化，未覆盖。请保留草稿并重新核对。"
        case OfflineStoreError.wakePermission:
            "此醒来观察不允许本机修改，原始内容已保留。"
        case OfflineStoreError.deletedRecord, OfflineStoreError.missingWake, OfflineStoreError.sleepStateChanged:
            "睡眠或醒来观察已不可用，未作修改。"
        case OfflineStoreError.offlineOnly:
            "此数据不允许离线写入。"
        default:
            "本机醒来观察保存失败，原草稿已保留。请核对时间或重新读取后重试。"
        }
    }
}
#endif
