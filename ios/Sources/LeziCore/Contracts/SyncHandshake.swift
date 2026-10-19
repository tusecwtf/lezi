import Foundation

public enum FamilyRole: String, Equatable, Sendable { case owner, member }

public struct SyncPrincipal: Equatable, Sendable {
    public let membershipID: String
    public let deviceID: String
    public let role: FamilyRole

    public init(membershipID: String, deviceID: String, role: FamilyRole) throws {
        guard !trimAndroidWireString(membershipID).isEmpty,
              !trimAndroidWireString(deviceID).isEmpty else {
            throw WireContractError.invalidValue("principal")
        }
        self.membershipID = membershipID
        self.deviceID = deviceID
        self.role = role
    }
}

/// Only this frozen body is valid for the authenticated handshake. This is not
/// the additive setup-status capability list and does not claim a client version.
public struct SyncHandshakeRequest: Equatable, Sendable {
    public static let capability = "causal_sync_v2"
    public let protocolVersion = 1
    public let requiredCapabilities: Set<String> = [capability]

    public init() {}

    public func encoded() -> Data {
        Data(#"{"protocol_version":1,"required_capabilities":["causal_sync_v2"]}"#.utf8)
    }

    public static func decode(_ data: Data) throws -> Self {
        let root = try WireJSON.parse(data).object(keys: ["protocol_version", "required_capabilities"])
        guard try root.field("protocol_version").integer("protocol_version") == 1,
              try root.field("required_capabilities").strings("required_capabilities", max: 64, trim: false) == [capability] else {
            throw WireContractError.invalidValue("handshake_request")
        }
        return Self()
    }
}

public struct SyncLimits: Equatable, Sendable {
    public let pullPageMaxEntities: Int64
    public let pullPageMaxEncodedBytes: Int64
    public let pullPageMaxDecodedBytes: Int64
    public let pullMaxPages: Int64
    public let commitBatchMaxUnits: Int64
    public let mediaMaxBytes: Int64
}

/// A successful decode proves the entire handshake, including the authenticated
/// session binding, before any caller can obtain its negotiated limits.
public struct AuthenticatedSyncHandshake: Equatable, Sendable {
    public var ready: Bool { true }
    public var capabilities: Set<String> { [SyncHandshakeRequest.capability] }
    public let protocolVersion: Int
    public let serverVersion: String
    public let principal: SyncPrincipal
    public let directoryGeneration: String
    public let limits: SyncLimits
    public let pullResponseEncodings: Set<String>
    public let supportsRetryAfter: Bool

    public static func decode(_ data: Data, expectedPrincipal: SyncPrincipal) throws -> Self {
        let root = try WireJSON.parse(data).object(keys: [
            "protocol_version", "server_version", "ready", "capabilities", "principal",
            "directory_generation", "limits", "compression", "retry_hints",
        ])
        guard try root.field("protocol_version").integer("protocol_version") == 1,
              try root.field("ready").boolean("ready"),
              try root.field("capabilities").strings("capabilities", max: 64) == [SyncHandshakeRequest.capability] else {
            throw WireContractError.invalidValue("handshake")
        }
        let version = try root.field("server_version").string("server_version", nonblank: true)
        guard version.utf16.count <= 64 else { throw WireContractError.invalidValue("server_version") }
        let rawPrincipal = try root.field("principal").object(keys: ["membership_id", "device_id", "role"])
        guard let role = FamilyRole(rawValue: try rawPrincipal.field("role").string("role")) else {
            throw WireContractError.invalidValue("role")
        }
        let principal = try SyncPrincipal(
            membershipID: rawPrincipal.field("membership_id").string("membership_id", nonblank: true),
            deviceID: rawPrincipal.field("device_id").string("device_id", nonblank: true), role: role
        )
        guard principal == expectedPrincipal else { throw WireContractError.principalMismatch }
        let directory = try root.field("directory_generation").string("directory_generation", nonblank: true)
        guard isSHA256(directory) else { throw WireContractError.invalidValue("directory_generation") }
        let rawLimits = try root.field("limits").object(keys: [
            "pull_page_max_entities", "pull_page_max_encoded_bytes", "pull_page_max_decoded_bytes",
            "pull_max_pages", "commit_batch_max_units", "media_max_bytes",
        ])
        func limit(_ key: String, max: Int64) throws -> Int64 {
            let value = try rawLimits.field(key).integer(key)
            guard (1...max).contains(value) else { throw WireContractError.invalidValue(key) }
            return value
        }
        let limits = try SyncLimits(
            pullPageMaxEntities: limit("pull_page_max_entities", max: 200),
            pullPageMaxEncodedBytes: limit("pull_page_max_encoded_bytes", max: 9 * 1024 * 1024),
            pullPageMaxDecodedBytes: limit("pull_page_max_decoded_bytes", max: 8 * 1024 * 1024),
            pullMaxPages: limit("pull_max_pages", max: 500),
            commitBatchMaxUnits: limit("commit_batch_max_units", max: 64),
            mediaMaxBytes: limit("media_max_bytes", max: 10 * 1024 * 1024)
        )
        guard limits.pullPageMaxEncodedBytes >= limits.pullPageMaxDecodedBytes else {
            throw WireContractError.invalidValue("pull_byte_limits")
        }
        let compression = try root.field("compression").object(keys: ["pull_response"])
        let encodings = try compression.field("pull_response").strings("pull_response", max: 2)
        guard encodings == ["gzip", "identity"] else { throw WireContractError.invalidValue("pull_response") }
        let retry = try root.field("retry_hints").object(keys: ["retry_after"])
        guard try retry.field("retry_after").boolean("retry_after") else { throw WireContractError.invalidValue("retry_after") }
        return Self(protocolVersion: 1, serverVersion: version, principal: principal,
                    directoryGeneration: directory, limits: limits,
                    pullResponseEncodings: encodings, supportsRetryAfter: true)
    }
}

func isSHA256(_ value: String) -> Bool {
    value.utf8.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
}
