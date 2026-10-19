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
    public let customDefinitionUUID: UUID?
    public let effectiveWakeObservationClientUUID: UUID?

    public init(clientUUID: UUID, babyClientUUID: UUID, rawType: String, timestampMillis: Int64,
                endTimestampMillis: Int64?, note: String?, payloadJSON: String, payloadSchemaVersion: Int,
                payloadDocument: RecordPayloadDocument?, updatedAt: Int64, syncDirty: Bool, deletedAt: Int64?,
                effectiveWakeObservationClientUUID: UUID? = nil, customDefinitionUUID: UUID? = nil) {
        self.clientUUID = clientUUID; self.babyClientUUID = babyClientUUID; self.rawType = rawType
        self.timestampMillis = timestampMillis; self.endTimestampMillis = endTimestampMillis; self.note = note
        self.payloadJSON = payloadJSON; self.payloadSchemaVersion = payloadSchemaVersion; self.payloadDocument = payloadDocument
        self.updatedAt = updatedAt; self.syncDirty = syncDirty; self.deletedAt = deletedAt
        self.customDefinitionUUID = customDefinitionUUID
        self.effectiveWakeObservationClientUUID = effectiveWakeObservationClientUUID
    }
}

public struct OfflineRecordsPage: Equatable, Sendable {
    public let records: [OfflineRecord]
    public let nextCursor: OfflineRecordCursor?
}
