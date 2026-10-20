import Foundation
import LeziCore
import LeziStorage

/// Only the projection's legal observations are actionable. Retained withdrawn
/// evidence is not a recovery surface, and local provenance is not family ACL.
struct OfflineWakeObservationListState: Equatable, Sendable {
    let sessionID: UUID
    let sleep: OfflineSleep
    let context: OfflineContext
    let babyName: String
    let localActorUUID: UUID
    let zone: TimeZone

    init?(sleep: OfflineSleep, context: OfflineContext, babyName: String, localActorUUID: UUID,
          zone: TimeZone, sessionID: UUID = UUID()) {
        guard OfflineSleepState.supports(sleep), sleep.record.babyClientUUID == context.babyClientUUID else { return nil }
        self.sessionID = sessionID; self.sleep = sleep; self.context = context
        self.babyName = babyName; self.localActorUUID = localActorUUID; self.zone = zone
    }
    var observations: [OfflineWakeObservation] {
        let legal = Set(sleep.interval.visibleObservations.map(\.clientUUID))
        return sleep.observations.filter {
            legal.contains($0.clientUUID.uuidString) && $0.sleepClientUUID == sleep.record.clientUUID &&
                !$0.withdrawn && $0.deletedAt == nil && $0.timestampMillis >= sleep.record.timestampMillis
        }.sorted {
            $0.timestampMillis == $1.timestampMillis
                ? $0.clientUUID.uuidString < $1.clientUUID.uuidString : $0.timestampMillis < $1.timestampMillis
        }
    }
    func canEdit(_ wake: OfflineWakeObservation) -> Bool {
        observations.contains(wake) && wake.localObserverUUID == localActorUUID && wake.syncDirty
    }
    func refreshed(_ sleep: OfflineSleep) -> Self? {
        guard sleep.record.clientUUID == self.sleep.record.clientUUID else { return nil }
        return .init(sleep: sleep, context: context, babyName: babyName, localActorUUID: localActorUUID,
                     zone: zone, sessionID: sessionID)
    }
    func status(_ wake: OfflineWakeObservation) -> String {
        guard sleep.interval.endObservationClientUUID == wake.clientUUID.uuidString else { return "保留的观察" }
        return sleep.interval.endSource == .effective ? "有效观察" : "暂定"
    }
}

/// Android's wake correction form is HH:mm on the original wake's local date.
/// It is not the completed-sleep editor and has no sleep-note length cap.
struct OfflineWakeObservationState: Equatable, Sendable {
    struct Command: Equatable, Sendable {
        let draft: OfflineWakeDraft
        let context: OfflineContext
        let sleepRevision: Int64
        let wakeRevision: Int64
    }
    let sessionID = UUID()
    let clientUUID: UUID
    let sleepClientUUID: UUID
    let context: OfflineContext
    let babyName: String
    let sleepTimestampMillis: Int64
    let originalTimestampMillis: Int64
    let sleepRevision: Int64
    let wakeRevision: Int64
    let zone: TimeZone
    private let originalTimeText: String
    private let originalNote: String
    private(set) var timeText: String
    private(set) var note: String
    private(set) var attemptID: UUID?
    private(set) var error: String?
    var isBusy: Bool { attemptID != nil }
    var isDirty: Bool { timeText != originalTimeText || Array(note.utf16) != Array(originalNote.utf16) }

    init?(wake: OfflineWakeObservation, list: OfflineWakeObservationListState) {
        guard list.canEdit(wake) else { return nil }
        let time = WakeObservationTime.text(timestampMillis: wake.timestampMillis, zone: list.zone)
        guard !time.isEmpty else { return nil } // Never open a form around a clamped native date.
        clientUUID = wake.clientUUID; sleepClientUUID = list.sleep.record.clientUUID
        context = list.context; babyName = list.babyName; zone = list.zone
        sleepTimestampMillis = list.sleep.record.timestampMillis; originalTimestampMillis = wake.timestampMillis
        sleepRevision = list.sleep.record.updatedAt; wakeRevision = wake.updatedAt
        timeText = time; originalTimeText = time
        note = wake.note ?? ""; originalNote = wake.note ?? ""
    }
    mutating func edit(timeText: String? = nil, note: String? = nil) {
        guard !isBusy else { return }
        if let timeText { self.timeText = timeText }
        if let note { self.note = note }
        error = nil
    }
    func issue(now: Int64) -> String? {
        guard let timestamp = WakeObservationTime.resolve(timeText, originalTimestampMillis: originalTimestampMillis, zone: zone) else {
            return "时间请填写为 HH:mm"
        }
        if let issue = RecordTime.pointError(timestamp: timestamp, now: now) { return issue }
        return SleepProjection.validateWakeTimestamp(sleepStart: sleepTimestampMillis, wake: timestamp)
    }
    mutating func begin(now: Int64) -> (UUID, Command)? {
        guard !isBusy else { return nil }
        if let issue = issue(now: now) { error = issue; return nil }
        guard let timestamp = WakeObservationTime.resolve(timeText, originalTimestampMillis: originalTimestampMillis, zone: zone) else { return nil }
        let trimmed = AndroidText.trimmed(note)
        let command = Command(draft: .init(clientUUID: clientUUID, sleepClientUUID: sleepClientUUID,
            babyClientUUID: context.babyClientUUID, timestampMillis: timestamp, note: trimmed.isEmpty ? nil : trimmed),
            context: context, sleepRevision: sleepRevision, wakeRevision: wakeRevision)
        let attempt = UUID(); attemptID = attempt; error = nil
        return (attempt, command)
    }
    @discardableResult mutating func finish(_ attempt: UUID, error: String? = nil) -> Bool {
        guard attemptID == attempt else { return false }
        attemptID = nil; self.error = error; return true
    }
}
