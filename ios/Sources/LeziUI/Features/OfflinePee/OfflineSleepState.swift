import Foundation
import LeziCore
import LeziStorage

/// SleepStart and WakeObservation are separate facts; a wake is never an ordinary draft.
struct OfflineSleepState: Equatable, Sendable {
    enum Action: Equatable, Sendable {
        case start, completedCreate
        case wake(sleepID: UUID, start: Int64)
        case editStart(revision: Int64)
        case editCompleted(revision: Int64, wakeID: UUID, wakeRevision: Int64)
    }
    enum Command: Equatable, Sendable {
        case start(RecordConfirmationDraft)
        case wake(OfflineWakeDraft)
        case completedCreate(RecordConfirmationDraft)
        case editStart(RecordConfirmationDraft, revision: Int64)
        case editCompleted(RecordConfirmationDraft, wakeID: UUID, revision: Int64, wakeRevision: Int64)
        case delete(UUID, revision: Int64)
    }
    let sessionID = UUID()
    let clientUUID: UUID
    let context: OfflineContext
    let babyName: String
    let action: Action
    private(set) var timestampMillis: Int64
    private(set) var note: String
    private(set) var endTimestampMillis: Int64?
    let payload: RecordPayload
    private let originalNote: String
    private let originalEnd: Int64?
    private let originalTimestamp: Int64
    private(set) var attemptID: UUID?
    private(set) var error: String?
    var isBusy: Bool { attemptID != nil }
    var isDirty: Bool { timestampMillis != originalTimestamp || endTimestampMillis != originalEnd || Array(note.utf16) != Array(originalNote.utf16) }
    var revision: Int64? {
        switch action { case let .editStart(revision), let .editCompleted(revision, _, _): revision; default: nil }
    }
    var hasEnd: Bool {
        switch action { case .completedCreate, .editCompleted: true; default: false }
    }
    var title: String {
        switch action { case .start: "睡下"; case .wake: "醒来"; case .completedCreate: "补记睡眠";
        case .editStart: "编辑睡下"; case .editCompleted: "编辑睡眠起止时间" }
    }
    var confirmLabel: String {
        switch action { case .start: "确认睡下"; case .wake: "确认醒来"; case .completedCreate: "确认补记睡眠";
        case .editStart, .editCompleted: "确认保存修改" }
    }
    var successMessage: String {
        switch action { case .start: "已保存本机睡下记录"; case .wake: "已保存本机醒来观察";
        case .completedCreate: "已保存本机补记睡眠"; case .editStart, .editCompleted: "已更新本机睡眠记录" }
    }
    init(context: OfflineContext, babyName: String, timestampMillis: Int64, action: Action = .start, clientUUID: UUID = UUID(), endTimestampMillis: Int64? = nil, note: String = "", payload: RecordPayload = .sleep()) {
        self.context = context; self.babyName = babyName; self.action = action
        self.timestampMillis = timestampMillis; originalTimestamp = timestampMillis; self.clientUUID = clientUUID
        self.endTimestampMillis = endTimestampMillis; originalEnd = endTimestampMillis
        self.note = note; originalNote = note; self.payload = payload
    }
    init?(sleep: OfflineSleep, context: OfflineContext, babyName: String, completed: Bool, localActorUUID: UUID) {
        guard Self.supports(sleep), sleep.record.babyClientUUID == context.babyClientUUID,
              let payload = sleep.record.payloadDocument?.payload else { return nil }
        let action: Action
        var end: Int64?
        if completed {
            guard let wake = Self.editableProjectedWake(sleep, localActorUUID: localActorUUID),
                  wake.timestampMillis > sleep.record.timestampMillis else { return nil }
            action = .editCompleted(revision: sleep.record.updatedAt, wakeID: wake.clientUUID, wakeRevision: wake.updatedAt)
            end = wake.timestampMillis
        } else { action = .editStart(revision: sleep.record.updatedAt) }
        self.init(context: context, babyName: babyName, timestampMillis: sleep.record.timestampMillis,
            action: action, clientUUID: sleep.record.clientUUID, endTimestampMillis: end,
            note: sleep.record.note ?? "", payload: payload)
    }
    static func editableProjectedWake(_ sleep: OfflineSleep, localActorUUID: UUID) -> OfflineWakeObservation? {
        guard supports(sleep), let id = sleep.interval.endObservationClientUUID else { return nil }
        return sleep.observations.first { $0.clientUUID.uuidString == id && $0.deletedAt == nil && !$0.withdrawn &&
            $0.localObserverUUID == localActorUUID && $0.syncDirty }
    }
    static func durationLabel(start: Int64, end: Int64) -> String {
        let (difference, overflow) = end.subtractingReportingOverflow(start)
        return overflow || difference < 0 ? "时长超出可显示范围" : "时长 \(difference / 60_000) 分钟"
    }
    static func supports(_ sleep: OfflineSleep) -> Bool {
        let record = sleep.record
        guard record.type == .sleep, record.deletedAt == nil, record.endTimestampMillis == nil,
              !record.isOpaque, case .sleep? = record.payloadDocument?.payload else { return false }
        return true
    }
    mutating func edit(timestamp: Int64? = nil, end: Int64? = nil, note: String? = nil) {
        guard !isBusy else { return }
        if let timestamp {
            if hasEnd { endTimestampMillis = RecordTime.shiftStartPreservingDuration(oldStart: timestampMillis,
                oldEnd: endTimestampMillis, newStart: timestamp) ?? endTimestampMillis }
            timestampMillis = timestamp
        }
        if hasEnd, let end { endTimestampMillis = end }
        if let note { self.note = note }
        error = nil
    }
    @discardableResult mutating func chooseEnd(_ end: Int64, now: Int64) -> Bool {
        guard !isBusy, hasEnd else { return false }
        if let issue = RecordTime.intervalError(start: timestampMillis, end: end, now: now) {
            error = issue; return false
        }
        endTimestampMillis = end; error = nil; return true
    }
    func issue(now: Int64) -> String? {
        if let issue = RecordTime.pointError(timestamp: timestampMillis, now: now) { return issue }
        if case let .wake(_, start) = action,
           let issue = SleepProjection.validateWakeTimestamp(sleepStart: start, wake: timestampMillis) { return issue }
        if hasEnd {
            guard let endTimestampMillis else { return "请填写醒来时间" }
            if let issue = RecordTime.intervalError(start: timestampMillis, end: endTimestampMillis, now: now) { return issue }
        }
        if note.utf16.count > 200 { return "备注最多 200 个 UTF-16 字符" }
        return nil
    }
    mutating func begin(now: Int64) -> (UUID, Command)? {
        guard !isBusy else { return nil }
        if let issue = issue(now: now) { error = issue; return nil }
        let trimmed = AndroidText.trimmed(note)
        let note: String? = trimmed.isEmpty ? nil : trimmed
        let command: Command
        let draft = RecordConfirmationDraft(clientUUID: clientUUID, babyClientUUID: context.babyClientUUID,
            item: .builtIn(.sleep)!, timestampMillis: timestampMillis,
            endTimestampMillis: hasEnd ? endTimestampMillis : nil, note: note, payload: payload)
        switch action {
        case .start:
            command = .start(draft)
        case .completedCreate: command = .completedCreate(draft)
        case let .editStart(revision): command = .editStart(draft, revision: revision)
        case let .editCompleted(revision, wakeID, wakeRevision):
            command = .editCompleted(draft, wakeID: wakeID, revision: revision, wakeRevision: wakeRevision)
        case let .wake(sleepID, _):
            command = .wake(.init(clientUUID: clientUUID, sleepClientUUID: sleepID,
                babyClientUUID: context.babyClientUUID, timestampMillis: timestampMillis, note: note))
        }
        let attempt = UUID(); attemptID = attempt; error = nil
        return (attempt, command)
    }
    mutating func beginDeletion() -> (UUID, Command)? {
        guard !isBusy, let revision else { return nil }
        let attempt = UUID(); attemptID = attempt; error = nil
        return (attempt, .delete(clientUUID, revision: revision))
    }
    @discardableResult mutating func finish(_ attempt: UUID, error: String? = nil) -> Bool {
        guard attemptID == attempt else { return false }
        attemptID = nil; self.error = error; return true
    }
}
