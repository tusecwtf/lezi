import Foundation
import LeziCore

/// A stored fact, including opaque future documents. Raw JSON/schema/type survive
/// reads and soft deletion unchanged. Only a known current document is editable.
public struct OfflineRecord: Equatable, Sendable {
    public let clientUUID: UUID
    public let babyClientUUID: UUID
    public let rawType: String
    public var type: RecordType? { RecordType(rawValue: rawType) }
    public let timestampMillis: Int64
    public let endTimestampMillis: Int64?
    public let note: String?
    public let payloadJSON: String
    public let payloadSchemaVersion: Int
    public let payloadDocument: RecordPayloadDocument?
    public var isOpaque: Bool { payloadDocument?.isUnknown ?? true }
    public let updatedAt: Int64
    public let syncDirty: Bool
    public let deletedAt: Int64?
}

public struct OfflineRecordsPage: Equatable, Sendable {
    public let records: [OfflineRecord]
    public let nextCursor: OfflineRecordCursor?
}
