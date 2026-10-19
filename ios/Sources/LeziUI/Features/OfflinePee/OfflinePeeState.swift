import Foundation
import LeziCore

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
    private var persistenceAttemptID: UUID?
    public init(babyClientUUID: UUID, babyName: String, timestampMillis: Int64) {
        draft = .init(babyClientUUID: babyClientUUID, timestampMillis: timestampMillis)
        session = .init(context: .init(babyClientUUID: babyClientUUID, babyName: babyName))
    }
    public mutating func edit(timestampMillis: Int64? = nil, amount: Int? = nil, note: String? = nil) {
        guard session.canInteract else { return }
        draft = draft.editing(timestampMillis: timestampMillis, amount: amount, note: note)
        session.draftEdited()
    }
    public mutating func confirm(nowMillis: Int64) -> LeziConfirmationSession.TapResult {
        session.confirm(draft: draft.confirmation, nowMillis: nowMillis, canWrite: true, externallyBusy: false)
    }
    public func accepts(_ confirmation: LeziConfirmedRecord) -> Bool {
        session.phase == .saving(confirmation.attemptID) && confirmation.context == session.context &&
        confirmation.draft == draft.confirmation
    }
    /// The UI callback may be delivered twice; only one writer may claim an attempt.
    public mutating func beginPersistence(_ confirmation: LeziConfirmedRecord) -> Bool {
        guard accepts(confirmation), persistenceAttemptID == nil else { return false }
        persistenceAttemptID = confirmation.attemptID
        return true
    }
    /// A storage transaction must have returned successfully before passing a nil failure.
    public mutating func finish(attemptID: UUID, failure: String? = nil) {
        guard session.phase == .saving(attemptID) else { return }
        persistenceAttemptID = nil
        session.finish(attemptID: attemptID, failureMessage: failure)
    }
    public mutating func invalidate() { session.invalidate() }
}
