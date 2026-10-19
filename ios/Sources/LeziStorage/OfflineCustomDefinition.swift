import Foundation

public struct OfflineCustomDefinition: Equatable, Sendable {
    public let clientUUID: UUID
    public let name: String
    public let iconSlot: Int
    public let updatedAt: Int64
    public let syncDirty: Bool
    public let deletedAt: Int64?
    /// Device-local layout, excluded from the definition's business revision.
    public let sortOrder: Int
}
