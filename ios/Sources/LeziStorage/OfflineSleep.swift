import Foundation
import LeziCore

/// Explicit wake intent captures its original sleep and baby. This is not wire data.
public struct OfflineWakeDraft: Equatable, Sendable {
    public let clientUUID: UUID
    public let sleepClientUUID: UUID
    public let babyClientUUID: UUID
    public let timestampMillis: Int64
    public let note: String?
    public init(clientUUID: UUID = UUID(), sleepClientUUID: UUID, babyClientUUID: UUID,
                timestampMillis: Int64, note: String? = nil) {
        self.clientUUID = clientUUID; self.sleepClientUUID = sleepClientUUID
        self.babyClientUUID = babyClientUUID; self.timestampMillis = timestampMillis; self.note = note
    }
}
public struct OfflineWakeObservation: Equatable, Sendable {
    public let clientUUID: UUID
    public let sleepClientUUID: UUID
    public let timestampMillis: Int64
    public let note: String?
    public let withdrawn: Bool
    /// Device-local provenance only. Never serialize this as observer_membership_id.
    public let localObserverUUID: UUID
    public let updatedAt: Int64
    public let syncDirty: Bool
    public let deletedAt: Int64?
    var projectionFact: WakeObservationFact {
        .init(clientUUID: clientUUID.uuidString, wakeTimestamp: timestampMillis, withdrawn: withdrawn, deleted: deletedAt != nil)
    }
}
public struct OfflineSleep: Equatable, Sendable {
    public let record: OfflineRecord
    public let interval: SleepIntervalProjection
    /// Includes withdrawn/deleted evidence; consumers must label/filter it explicitly.
    public let observations: [OfflineWakeObservation]
}
public struct OfflineSleepPage: Equatable, Sendable {
    public let page: OfflineRecordsPage
    public let sleeps: [UUID: OfflineSleep]
    public let latestOpenSleepClientUUID: UUID?
}
