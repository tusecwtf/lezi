import Foundation
import LeziCore
import LeziStorage

/// Immutable values replaced on edit. This type cannot represent media or another record kind.
public struct OfflinePeeDraft: Equatable, Sendable {
    public let clientUUID: UUID
    public let babyClientUUID: UUID
    public let timestampMillis: Int64
    public let amount: Int
    public let note: String

    public init(clientUUID: UUID = UUID(), babyClientUUID: UUID, timestampMillis: Int64,
                amount: Int = 2, note: String = "") {
        self.clientUUID = clientUUID; self.babyClientUUID = babyClientUUID
        self.timestampMillis = timestampMillis; self.amount = amount; self.note = note
    }
    public func editing(timestampMillis: Int64? = nil, amount: Int? = nil, note: String? = nil) -> Self {
        Self(clientUUID: clientUUID, babyClientUUID: babyClientUUID,
             timestampMillis: timestampMillis ?? self.timestampMillis,
             amount: amount ?? self.amount, note: note ?? self.note)
    }
    public var confirmation: RecordConfirmationDraft {
        .init(clientUUID: clientUUID, babyClientUUID: babyClientUUID, item: .builtIn(.pee)!,
              timestampMillis: timestampMillis, note: note.isEmpty ? nil : note, payload: .pee(amount: amount))
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
    public init(babyClientUUID: UUID, babyName: String, timestampMillis: Int64) {
        draft = .init(babyClientUUID: babyClientUUID, timestampMillis: timestampMillis)
        initialDraft = draft; expectedUpdatedAt = nil
        session = .init(context: .init(babyClientUUID: babyClientUUID, babyName: babyName))
    }
    /// Fail closed rather than discarding an unknown payload or unsupported interval.
    public static func canEdit(_ record: OfflineRecord) -> Bool {
        guard record.type == .pee, !record.isOpaque, record.deletedAt == nil,
              record.endTimestampMillis == nil,
              case let .pee(amount)? = record.payloadDocument?.payload else { return false }
        return (1...3).contains(amount)
    }
    public init?(record: OfflineRecord, babyName: String) {
        guard Self.canEdit(record), case let .pee(amount)? = record.payloadDocument?.payload else { return nil }
        draft = .init(clientUUID: record.clientUUID, babyClientUUID: record.babyClientUUID,
                      timestampMillis: record.timestampMillis, amount: amount, note: record.note ?? "")
        initialDraft = draft; expectedUpdatedAt = record.updatedAt
        session = .init(context: .init(babyClientUUID: record.babyClientUUID, babyName: babyName))
    }
    public mutating func edit(timestampMillis: Int64? = nil, amount: Int? = nil, note: String? = nil) {
        guard session.canInteract, !isBusy else { return }
        draft = draft.editing(timestampMillis: timestampMillis, amount: amount, note: note)
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
