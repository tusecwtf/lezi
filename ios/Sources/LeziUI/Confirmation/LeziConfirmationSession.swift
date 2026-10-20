import Foundation
import LeziCore

public struct LeziEditorContext: Equatable, Sendable {
    public let sessionID: UUID
    public let babyClientUUID: UUID
    public let babyName: String
    public init(sessionID: UUID = UUID(), babyClientUUID: UUID, babyName: String) {
        self.sessionID = sessionID; self.babyClientUUID = babyClientUUID; self.babyName = babyName
    }
}

public struct LeziConfirmationIssue: Equatable, Sendable {
    public let message: String
    /// Feature-owned field identity, passed back for focus/highlight. No second validation engine.
    public let fieldID: String?
    public init(message: String, fieldID: String? = nil) { self.message = message; self.fieldID = fieldID }
}

public struct LeziConfirmedRecord: Equatable, Sendable {
    public let context: LeziEditorContext
    public let draft: RecordConfirmationDraft
    public let confirmedAtMillis: Int64
    /// Identity for this presentation attempt only; NOT a causal-sync mutation ID.
    public let attemptID: UUID
}

/// In-memory presentation gate, not persistence, authorization, or a durable write queue.
/// Owner must revalidate ACL and baby scope transactionally when implementing the save callback.
public struct LeziConfirmationSession: Equatable, Sendable {
    public enum Phase: Equatable, Sendable { case editing, saving(UUID), completed, invalidated }
    public enum TapResult: Equatable, Sendable {
        case ignored
        case explanation(LeziConfirmationIssue?)
        case confirmed(LeziConfirmedRecord)
    }
    public let context: LeziEditorContext
    public private(set) var phase: Phase = .editing
    public private(set) var shownIssue: LeziConfirmationIssue?
    public private(set) var saveFailure: String?
    public init(context: LeziEditorContext) { self.context = context }
    public var isSaving: Bool { if case .saving = phase { true } else { false } }
    public var canInteract: Bool { phase == .editing }

    public func issue(for draft: RecordConfirmationDraft, nowMillis: Int64, featureIssue: LeziConfirmationIssue? = nil) -> LeziConfirmationIssue? {
        guard draft.babyClientUUID == context.babyClientUUID else {
            return .init(message: "宝宝信息已变化，请关闭后重新打开记录")
        }
        if let featureIssue { return featureIssue }
        return draft.validationErrors(nowMillis: nowMillis).first.map { .init(message: $0) }
    }

    /// This is the explicit confirmation boundary. Opening/editing never invokes it.
    public mutating func confirm(draft: RecordConfirmationDraft, nowMillis: Int64, canWrite: Bool,
                                 externallyBusy: Bool, featureIssue: LeziConfirmationIssue? = nil) -> TapResult {
        guard canInteract, canWrite, !externallyBusy else { return .ignored }
        if let issue = issue(for: draft, nowMillis: nowMillis, featureIssue: featureIssue) {
            shownIssue = shownIssue == nil ? issue : nil
            return .explanation(shownIssue)
        }
        let attemptID = UUID()
        phase = .saving(attemptID); shownIssue = nil; saveFailure = nil
        return .confirmed(.init(context: context, draft: draft, confirmedAtMillis: nowMillis, attemptID: attemptID))
    }
    public mutating func draftEdited() { shownIssue = nil; saveFailure = nil }
    public mutating func clearExplanation() { shownIssue = nil }
    /// Call only after the real writer reports its outcome. Stale completions are ignored.
    public mutating func finish(attemptID: UUID, failureMessage: String? = nil) {
        guard phase == .saving(attemptID) else { return }
        phase = failureMessage == nil ? .completed : .editing
        saveFailure = failureMessage
    }
    /// Owner calls this when the local permission/editor scope is no longer valid.
    public mutating func invalidate() { phase = .invalidated; shownIssue = nil; saveFailure = nil }
}
