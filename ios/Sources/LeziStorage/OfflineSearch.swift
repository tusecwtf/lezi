import Foundation
import LeziCore

/// A selected offline read scope and its content version. Neither the local actor
/// nor this token grants family membership or permission to write.
public struct OfflineSearchRevision: Equatable, Sendable {
    public let context: OfflineContext
    public let localActorUUID: UUID
    public let changeRevision: UUID
    let storeIdentity: UUID?
    let dataVersion: Int64?

    /// Useful for injected read repositories. Only a token issued by this store's
    /// currentSearchRevision can authorize a real store search or reopen.
    public init(context: OfflineContext, localActorUUID: UUID, changeRevision: UUID) {
        self.context = context
        self.localActorUUID = localActorUUID
        self.changeRevision = changeRevision
        storeIdentity = nil
        dataVersion = nil
    }

    init(context: OfflineContext, localActorUUID: UUID, changeRevision: UUID,
         storeIdentity: UUID, dataVersion: Int64) {
        self.context = context
        self.localActorUUID = localActorUUID
        self.changeRevision = changeRevision
        self.storeIdentity = storeIdentity
        self.dataVersion = dataVersion
    }
}

/// Opaque ordered scan position bound to its normalized query, selected baby,
/// selection epoch, connection, and unchanged content. Never a persisted token.
public struct OfflineSearchCursor: Equatable, Sendable {
    let normalizedQuery: String
    let revision: OfflineSearchRevision
    let recordCursor: OfflineRecordCursor
}

public struct OfflineSearchHit: Equatable, Sendable {
    public let record: OfflineRecord
    public let sleep: OfflineSleep?
    public var content: RecordSearchContent {
        RecordSearchContent(rawType: record.rawType, payload: record.payloadDocument?.payload,
            timestampMillis: record.timestampMillis,
            endTimestampMillis: sleep == nil ? record.endTimestampMillis : sleep?.interval.endTimestamp,
            note: record.note)
    }

    public init(record: OfflineRecord, sleep: OfflineSleep? = nil) {
        self.record = record
        self.sleep = sleep
    }
}

public struct OfflineSearchPage: Equatable, Sendable {
    public let hits: [OfflineSearchHit]
    /// A nonnil cursor means more stored rows remain, even when hits is empty.
    public let nextCursor: OfflineSearchCursor?
    public let revision: OfflineSearchRevision

    public init(hits: [OfflineSearchHit], nextCursor: OfflineSearchCursor?, revision: OfflineSearchRevision) {
        self.hits = hits
        self.nextCursor = nextCursor
        self.revision = revision
    }
}
