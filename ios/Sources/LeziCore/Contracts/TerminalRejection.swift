import Foundation

/// Frozen conflict-v2 terminal vocabulary plus authenticated-handshake readiness.
/// Transport authentication failures remain the future session transport's concern.
public enum TerminalErrorCode: String, CaseIterable, Sendable {
    case unknownField = "unknown_field"
    case missingField = "missing_field"
    case wrongType = "wrong_type"
    case nonCanonicalValue = "non_canonical_value"
    case invalidDomain = "invalid_domain"
    case contentDrift = "content_drift"
    case unauthenticated, forbidden
    case capabilityMismatch = "capability_mismatch"
    case invalidSnapshotToken = "invalid_snapshot_token"
    case snapshotExpired = "snapshot_expired"
    case snapshotStale = "snapshot_stale"
    case invalidChoice = "invalid_choice"
    case duplicateChoice = "duplicate_choice"
    case incompleteChoices = "incomplete_choices"
    case missingRestoreBase = "missing_restore_base"
    case incompleteRestoreBase = "incomplete_restore_base"
    case missingRestoreMedia = "missing_restore_media"
    case casMismatch = "cas_mismatch"
    case notReady = "not_ready"
}

public struct TerminalRejection: Equatable, Sendable {
    public let code: TerminalErrorCode

    public static func decode(_ data: Data) throws -> Self {
        try decode(WireJSON.parse(data))
    }

    static func decode(_ json: WireJSON) throws -> Self {
        let root = try json.object(keys: ["status", "error"])
        guard try root.field("status").string("status") == "rejected" else {
            throw WireContractError.invalidValue("status")
        }
        let error = try root.field("error").object(keys: ["code", "retryable"])
        guard try !error.field("retryable").boolean("retryable"),
              let code = TerminalErrorCode(rawValue: try error.field("code").string("code")) else {
            throw WireContractError.invalidValue("terminal_error")
        }
        return Self(code: code)
    }

    public static func decodeHandshake(_ data: Data, httpStatus: Int) throws -> Self {
        let rejection = try decode(data)
        switch (rejection.code, httpStatus) {
        case (.capabilityMismatch, 409), (.notReady, 503): return rejection
        default: throw WireContractError.invalidValue("handshake_http_status")
        }
    }
}
