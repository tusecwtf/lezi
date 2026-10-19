import Foundation
import LeziCore
import LeziStorage

/// SleepStart and WakeObservation are separate facts; a wake is never an ordinary draft.
struct OfflineSleepState: Equatable, Sendable {
    enum Action: Equatable, Sendable { case start; case wake(sleepID: UUID, start: Int64) }
    enum Command: Equatable, Sendable {
        case start(RecordConfirmationDraft)
        case wake(OfflineWakeDraft)
    }
    let sessionID = UUID()
    let clientUUID: UUID
    let context: OfflineContext
    let babyName: String
    let action: Action
    private(set) var timestampMillis: Int64
    private(set) var note = ""
    private let originalTimestamp: Int64
    private(set) var attemptID: UUID?
    private(set) var error: String?
    var isBusy: Bool { attemptID != nil }
    var isDirty: Bool { timestampMillis != originalTimestamp || !note.isEmpty }
    init(context: OfflineContext, babyName: String, timestampMillis: Int64, action: Action = .start, clientUUID: UUID = UUID()) {
        self.context = context; self.babyName = babyName; self.action = action
        self.timestampMillis = timestampMillis; originalTimestamp = timestampMillis; self.clientUUID = clientUUID
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
    mutating func edit(timestamp: Int64? = nil, note: String? = nil) {
        guard !isBusy else { return }
        if let timestamp { timestampMillis = timestamp }
        if let note { self.note = note }
        error = nil
    }
    func issue(now: Int64) -> String? {
        if let issue = RecordTime.pointError(timestamp: timestampMillis, now: now) { return issue }
        if case let .wake(_, start) = action,
           let issue = SleepProjection.validateWakeTimestamp(sleepStart: start, wake: timestampMillis) { return issue }
        if note.utf16.count > 200 { return "备注最多 200 个 UTF-16 字符" }
        return nil
    }
    mutating func begin(now: Int64) -> (UUID, Command)? {
        guard !isBusy else { return nil }
        if let issue = issue(now: now) { error = issue; return nil }
        let trimmed = AndroidText.trimmed(note)
        let note: String? = trimmed.isEmpty ? nil : trimmed
        let command: Command
        switch action {
        case .start:
            command = .start(.init(clientUUID: clientUUID, babyClientUUID: context.babyClientUUID,
                item: .builtIn(.sleep)!, timestampMillis: timestampMillis, note: note, payload: .sleep()))
        case let .wake(sleepID, _):
            command = .wake(.init(clientUUID: clientUUID, sleepClientUUID: sleepID,
                babyClientUUID: context.babyClientUUID, timestampMillis: timestampMillis, note: note))
        }
        let attempt = UUID(); attemptID = attempt; error = nil
        return (attempt, command)
    }
    @discardableResult mutating func finish(_ attempt: UUID, error: String? = nil) -> Bool {
        guard attemptID == attempt else { return false }
        attemptID = nil; self.error = error; return true
    }
}
