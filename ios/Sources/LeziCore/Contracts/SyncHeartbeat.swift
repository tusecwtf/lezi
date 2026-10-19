import Foundation

/// Additive setup-status capability `sync_heartbeat_v1`; never advertise it in
/// the frozen authenticated handshake. Comparing tips is not a sync algorithm.
public struct SyncHeartbeat: Equatable, Sendable {
    public let generation: String
    public let headRevision: Int64
    public let directoryGeneration: String

    public static func decode(_ data: Data) throws -> Self {
        let root = try WireJSON.parse(data).object(keys: ["generation", "head_rev", "directory_generation"])
        return try Self(
            generation: root.field("generation").string("generation", nonblank: true),
            headRevision: root.field("head_rev").integer("head_rev"),
            directoryGeneration: root.field("directory_generation").string("directory_generation", nonblank: true)
        )
    }
}
