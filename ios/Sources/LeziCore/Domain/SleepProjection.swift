import Foundation

public struct WakeObservationFact: Equatable, Sendable {
    public let clientUUID: String
    public let wakeTimestamp: Int64
    public let withdrawn: Bool
    public let deleted: Bool
    public init(clientUUID: String, wakeTimestamp: Int64, withdrawn: Bool = false, deleted: Bool = false) {
        self.clientUUID = clientUUID; self.wakeTimestamp = wakeTimestamp; self.withdrawn = withdrawn; self.deleted = deleted
    }
}
public enum SleepEndSource: Sendable { case effective, provisional, legacyEnd, open }
public struct SleepIntervalProjection: Equatable, Sendable {
    public let sleepClientUUID: String
    public let startTimestamp: Int64
    public let endTimestamp: Int64?
    public let endSource: SleepEndSource
    public let endObservationClientUUID: String?
    public let visibleObservations: [WakeObservationFact]
    public let isOverlapPending: Bool
    public var isOpen: Bool { endSource == .open }
    public var isProvisional: Bool { endSource == .provisional }
    public var isWakeShortcutTarget: Bool { isOpen && !isOverlapPending }
}
public enum SleepProjection {
    /// SleepProjection.kt precedence: legal selected observation, earliest legal
    /// observation, legacy end, open. Equal start/end is legal for wake facts.
    public static func project(sleepClientUUID: String, startTimestamp: Int64,
                               effectiveWakeObservationClientUUID: String? = nil,
                               observations: [WakeObservationFact] = [], legacyEndTimestamp: Int64? = nil,
                               peerOpenSleepStarts: [(clientUUID: String, timestamp: Int64)] = []) -> SleepIntervalProjection {
        let legal = observations.filter { !$0.deleted && !$0.withdrawn && $0.wakeTimestamp >= startTimestamp }
            .sorted { $0.wakeTimestamp == $1.wakeTimestamp ? $0.clientUUID < $1.clientUUID : $0.wakeTimestamp < $1.wakeTimestamp }
        let selected = effectiveWakeObservationClientUUID?.trimmingCharacters(in: .whitespacesAndNewlines)
        let effective = legal.first { $0.clientUUID == selected }
        let observation = effective ?? legal.first
        let source: SleepEndSource = effective != nil ? .effective : observation != nil ? .provisional : legacyEndTimestamp != nil ? .legacyEnd : .open
        let peers = peerOpenSleepStarts + [(sleepClientUUID, startTimestamp)]
        let latest = peers.max { $0.timestamp == $1.timestamp ? $0.clientUUID < $1.clientUUID : $0.timestamp < $1.timestamp }
        return .init(sleepClientUUID: sleepClientUUID, startTimestamp: startTimestamp,
                     endTimestamp: observation?.wakeTimestamp ?? legacyEndTimestamp, endSource: source,
                     endObservationClientUUID: observation?.clientUUID, visibleObservations: legal,
                     isOverlapPending: source == .open && latest?.clientUUID != sleepClientUUID)
    }
    public static func validateWakeTimestamp(sleepStart: Int64, wake: Int64) -> String? {
        wake < sleepStart ? "醒来时间不能早于入睡时间" : nil
    }
}
