import Foundation
import LeziCore
import LeziStorage

/// Bounded record types; no fallback can turn another care kind into excretion.
public enum OfflineExcretionKind: String, CaseIterable, Hashable, Sendable {
    case pee, poop, bothDiaper = "both_diaper"
    public var recordType: RecordType {
        switch self { case .pee: .pee; case .poop: .poop; case .bothDiaper: .bothDiaper }
    }
    public var label: String {
        switch self { case .pee: "尿记录"; case .poop: "便记录"; case .bothDiaper: "尿+便记录" }
    }
    public var hasPee: Bool { self != .poop }
    public var hasStool: Bool { self != .pee }
}

/// Copy aligned with Android RecordSummary.kt; unknown values never masquerade as defaults.
public enum OfflineExcretionLabels {
    public static func pee(_ value: Int) -> String { [1: "小", 2: "中", 3: "大"][value] ?? "未知" }
    public static func amount(_ value: Int) -> String { [1: "一点", 2: "偏少", 3: "正常", 4: "偏多"][value] ?? "未知" }
    public static func consistency(_ value: Int) -> String { [1: "稀", 2: "偏软", 3: "正常", 4: "偏硬"][value] ?? "未知" }
    public static func color(_ value: Int) -> String {
        [0: "未选", 1: "白", 2: "黄", 3: "橙", 4: "褐", 5: "绿", 6: "红", 7: "黑"][value] ?? "未知"
    }
}

/// Historical name retained for source compatibility. One immutable draft owns all
/// three excretion kinds; inactive values survive a new-draft type switch back.
public struct OfflinePeeDraft: Equatable, Sendable {
    public let clientUUID: UUID
    public let babyClientUUID: UUID
    public let timestampMillis: Int64
    public let kind: OfflineExcretionKind
    /// Urine amount; kept under its original property name for existing callers.
    public let amount: Int
    public let stoolAmount: Int
    public let stoolConsistency: Int
    public let stoolColor: Int
    public let note: String

    public init(clientUUID: UUID = UUID(), babyClientUUID: UUID, timestampMillis: Int64,
                amount: Int = 2, note: String = "", kind: OfflineExcretionKind = .pee,
                stoolAmount: Int = 3, stoolConsistency: Int = 3, stoolColor: Int = 0) {
        self.clientUUID = clientUUID; self.babyClientUUID = babyClientUUID
        self.timestampMillis = timestampMillis; self.amount = amount; self.note = note
        self.kind = kind; self.stoolAmount = stoolAmount
        self.stoolConsistency = stoolConsistency; self.stoolColor = stoolColor
    }
    public func editing(timestampMillis: Int64? = nil, amount: Int? = nil, note: String? = nil,
                        kind: OfflineExcretionKind? = nil, stoolAmount: Int? = nil,
                        stoolConsistency: Int? = nil, stoolColor: Int? = nil) -> Self {
        Self(clientUUID: clientUUID, babyClientUUID: babyClientUUID,
             timestampMillis: timestampMillis ?? self.timestampMillis,
             amount: amount ?? self.amount, note: note ?? self.note, kind: kind ?? self.kind,
             stoolAmount: stoolAmount ?? self.stoolAmount,
             stoolConsistency: stoolConsistency ?? self.stoolConsistency, stoolColor: stoolColor ?? self.stoolColor)
    }
    public var payload: RecordPayload {
        switch kind {
        case .pee: .pee(amount: amount)
        case .poop: .stool(amount: stoolAmount, consistency: stoolConsistency, color: stoolColor)
        case .bothDiaper: .bothDiaper(peeAmount: amount, stoolAmount: stoolAmount,
                                    stoolConsistency: stoolConsistency, stoolColor: stoolColor)
        }
    }
    public var confirmation: RecordConfirmationDraft {
        .init(clientUUID: clientUUID, babyClientUUID: babyClientUUID, item: .builtIn(kind.recordType)!,
              timestampMillis: timestampMillis, note: note.isEmpty ? nil : note, payload: payload)
    }
}

/// Selection is deliberately not editor ownership. A different selected baby never retargets this state.
public struct OfflinePeeState: Equatable, Sendable {
    public private(set) var draft: OfflinePeeDraft
    public var session: LeziConfirmationSession
    public let expectedUpdatedAt: Int64?
    private let initialDraft: OfflinePeeDraft
    public private(set) var deletionAttemptID: UUID?
    public private(set) var deletionFailure: String?
    public var isEditing: Bool { expectedUpdatedAt != nil }
    public var isDirty: Bool { draft != initialDraft }
    public var isBusy: Bool { session.isSaving || deletionAttemptID != nil }
    public enum DismissDecision: Equatable, Sendable { case close, confirmDiscard, ignoreBusy }
    public var dismissDecision: DismissDecision {
        isBusy ? .ignoreBusy : (isDirty ? .confirmDiscard : .close)
    }
    private var persistenceAttemptID: UUID?
    public init(babyClientUUID: UUID, babyName: String, timestampMillis: Int64, kind: OfflineExcretionKind = .pee) {
        draft = .init(babyClientUUID: babyClientUUID, timestampMillis: timestampMillis, kind: kind)
        initialDraft = draft; expectedUpdatedAt = nil
        session = .init(context: .init(babyClientUUID: babyClientUUID, babyName: babyName))
    }
    /// Fail closed rather than discarding an unknown payload or unsupported interval.
    public static func canEdit(_ record: OfflineRecord) -> Bool {
        guard OfflineExcretionKind(rawValue: record.rawType) != nil, !record.isOpaque,
              record.deletedAt == nil, record.endTimestampMillis == nil,
              let payload = record.payloadDocument?.payload, payload.type == record.type else { return false }
        return payload.validationErrors().isEmpty
    }
    public init?(record: OfflineRecord, babyName: String) {
        guard Self.canEdit(record), let kind = OfflineExcretionKind(rawValue: record.rawType),
              let payload = record.payloadDocument?.payload else { return nil }
        let pee: Int, stool: Int, consistency: Int, color: Int
        switch payload {
        case let .pee(amount): (pee, stool, consistency, color) = (amount, 3, 3, 0)
        case let .stool(amount, soft, shade): (pee, stool, consistency, color) = (2, amount, soft, shade)
        case let .bothDiaper(amount, poop, soft, shade): (pee, stool, consistency, color) = (amount, poop, soft, shade)
        default: return nil
        }
        draft = .init(clientUUID: record.clientUUID, babyClientUUID: record.babyClientUUID,
                      timestampMillis: record.timestampMillis, amount: pee, note: record.note ?? "", kind: kind,
                      stoolAmount: stool, stoolConsistency: consistency, stoolColor: color)
        initialDraft = draft; expectedUpdatedAt = record.updatedAt
        session = .init(context: .init(babyClientUUID: record.babyClientUUID, babyName: babyName))
    }
    public mutating func edit(timestampMillis: Int64? = nil, amount: Int? = nil, note: String? = nil,
                              kind: OfflineExcretionKind? = nil, stoolAmount: Int? = nil,
                              stoolConsistency: Int? = nil, stoolColor: Int? = nil) {
        guard session.canInteract, !isBusy else { return }
        // Editing a stored fact never changes its type, including programmatic calls.
        guard !isEditing || kind == nil || kind == draft.kind else { return }
        draft = draft.editing(timestampMillis: timestampMillis, amount: amount, note: note, kind: kind,
                              stoolAmount: stoolAmount, stoolConsistency: stoolConsistency, stoolColor: stoolColor)
        session.draftEdited(); deletionFailure = nil
    }
    public mutating func confirm(nowMillis: Int64) -> LeziConfirmationSession.TapResult {
        session.confirm(draft: draft.confirmation, nowMillis: nowMillis, canWrite: true, externallyBusy: deletionAttemptID != nil)
    }
    public func accepts(_ confirmation: LeziConfirmedRecord) -> Bool {
        session.phase == .saving(confirmation.attemptID) && confirmation.context == session.context &&
        confirmation.draft == draft.confirmation
    }
    /// The UI callback may be delivered twice; only one writer may claim an attempt.
    public mutating func beginPersistence(_ confirmation: LeziConfirmedRecord) -> Bool {
        guard accepts(confirmation), deletionAttemptID == nil, persistenceAttemptID == nil else { return false }
        persistenceAttemptID = confirmation.attemptID
        return true
    }
    /// A storage transaction must have returned successfully before passing a nil failure.
    public mutating func finish(attemptID: UUID, failure: String? = nil) {
        guard session.phase == .saving(attemptID) else { return }
        persistenceAttemptID = nil
        session.finish(attemptID: attemptID, failureMessage: failure)
    }
    public mutating func beginDeletion() -> UUID? {
        guard isEditing, session.canInteract, !isBusy else { return nil }
        let attempt = UUID()
        deletionAttemptID = attempt; deletionFailure = nil
        return attempt
    }
    public mutating func finishDeletion(attemptID: UUID, failure: String? = nil) {
        guard deletionAttemptID == attemptID else { return }
        deletionAttemptID = nil; deletionFailure = failure
        if failure == nil { session.invalidate() }
    }
    public mutating func invalidate() { deletionAttemptID = nil; session.invalidate() }
}
