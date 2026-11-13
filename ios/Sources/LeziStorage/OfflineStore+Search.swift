import Foundation
import LeziCore

extension OfflineStore {
    /// A fresh selected scope, not an authorization fallback for blocked mode or
    /// missing selection. A reopened connection always issues different tokens.
    public func currentSearchRevision() throws -> OfflineSearchRevision {
        try readSearchSnapshot { dataVersion in
            try searchRevisionSnapshot(dataVersion: dataVersion)
        }
    }

    /// Scans at most limit stored rows (1...200) in timestamp/UUID descending order.
    /// Matching uses only decoded visible text, never serialized JSON. Sparse
    /// chunks may return no hits and a cursor; consumers continue until hits/end.
    public func searchPage(query: String, revision: OfflineSearchRevision,
                           before: OfflineSearchCursor? = nil, limit: Int = 100) throws -> OfflineSearchPage {
        guard (1...200).contains(limit) else { throw OfflineStoreError.invalidPageLimit }
        let normalizedQuery = RecordSearchRules.normalizedQuery(query)
        if let before {
            guard before.normalizedQuery.utf16.elementsEqual(normalizedQuery.utf16), before.revision == revision else {
                throw OfflineStoreError.staleContext
            }
        }
        return try readSearchSnapshot { dataVersion in
            try requireSearchRevision(revision, dataVersion: dataVersion)
            let page = try sleepPageSnapshot(baby: revision.context.babyClientUUID,
                before: before?.recordCursor, limit: limit, includeLatestOpenWhenEmpty: false)
            let hits = page.page.records.compactMap { record -> OfflineSearchHit? in
                let hit = OfflineSearchHit(record: record, sleep: page.sleeps[record.clientUUID])
                return hit.content.matches(normalizedQuery: normalizedQuery) ? hit : nil
            }
            let next = page.page.nextCursor.map {
                OfflineSearchCursor(normalizedQuery: normalizedQuery, revision: revision, recordCursor: $0)
            }
            return OfflineSearchPage(hits: hits, nextCursor: next, revision: revision)
        }
    }

    /// Reopen a hit only after a fresh full scope/content check, with its sleep
    /// evidence and latest-open peer read in the same snapshot. The returned local
    /// observer identity does not imply an Owner or any other family role.
    public func searchHit(recordID: UUID, revision: OfflineSearchRevision) throws -> OfflineSearchHit? {
        try readSearchSnapshot { dataVersion in
            try requireSearchRevision(revision, dataVersion: dataVersion)
            guard let record = try recordDetails(recordID), record.deletedAt == nil,
                  record.babyClientUUID == revision.context.babyClientUUID else { return nil }
            return OfflineSearchHit(record: record, sleep: try record.type == .sleep ? projectSleep(record) : nil)
        }
    }

    /// Actor-local commit notifications. No initial value, read notifications,
    /// polling, or rolled-back writes. Consumers acquire a fresh revision when
    /// notified and also on activation, since another connection may have changed.
    public func searchInvalidations() -> AsyncStream<Void> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
        searchInvalidationContinuations[id] = continuation
        continuation.onTermination = { @Sendable [weak self] _ in
            Task { await self?.removeSearchInvalidation(id) }
        }
        return stream
    }

    private func removeSearchInvalidation(_ id: UUID) {
        searchInvalidationContinuations.removeValue(forKey: id)
    }

    // Default write transactions all flow through here. total_changes includes
    // trigger writes but is consulted only after successful COMMIT. No-op replay,
    // failed body, and deferred-constraint COMMIT failure never publish a change.
    func writeTransaction<T>(_ body: () throws -> T) throws -> T {
        let before = db.totalChanges
        let result = try db.transaction(body)
        if db.totalChanges != before {
            searchChangeRevision = UUID()
            for continuation in searchInvalidationContinuations.values { continuation.yield(()) }
        }
        return result
    }

    private func searchRevisionSnapshot(dataVersion: Int64) throws -> OfflineSearchRevision {
        let selected = try context()
        let localIdentity = try identity()
        if let observed = searchObservedDataVersion, observed != dataVersion {
            searchChangeRevision = UUID()
        }
        searchObservedDataVersion = dataVersion
        return OfflineSearchRevision(context: selected, localActorUUID: localIdentity.localActorUUID,
            changeRevision: searchChangeRevision, storeIdentity: searchStoreIdentity, dataVersion: dataVersion)
    }

    private func requireSearchRevision(_ revision: OfflineSearchRevision, dataVersion: Int64) throws {
        guard try searchRevisionSnapshot(dataVersion: dataVersion) == revision else {
            throw OfflineStoreError.staleContext
        }
    }

    /// A single SQLite read snapshot covers roots, wake observations and peer
    /// context. Version checks outside both ends additionally reject an external
    /// connection committing at the snapshot boundary (including WAL mode).
    private func readSearchSnapshot<T>(_ body: (Int64) throws -> T) throws -> T {
        let version = try db.dataVersion
        let result = try db.transaction(begin: "BEGIN") {
            let result = try body(version)
            guard try db.dataVersion == version else { throw OfflineStoreError.staleContext }
            return result
        }
        guard try db.dataVersion == version else { throw OfflineStoreError.staleContext }
        return result
    }
}
