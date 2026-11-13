import Foundation
import LeziStorage

/// Read-only, test-injectable repository. No writer or family authority is
/// exposed to the search list or its retained read-only detail.
struct OfflineSearchRepository: Sendable {
    var currentRevision: @Sendable () async throws -> OfflineSearchRevision
    var page: @Sendable (String, OfflineSearchRevision, OfflineSearchCursor?, Int) async throws -> OfflineSearchPage
    var hit: @Sendable (UUID, OfflineSearchRevision) async throws -> OfflineSearchHit?
    var invalidations: @Sendable () async -> AsyncStream<Void>

    init(currentRevision: @escaping @Sendable () async throws -> OfflineSearchRevision,
         page: @escaping @Sendable (String, OfflineSearchRevision, OfflineSearchCursor?, Int) async throws -> OfflineSearchPage,
         hit: @escaping @Sendable (UUID, OfflineSearchRevision) async throws -> OfflineSearchHit?,
         invalidations: @escaping @Sendable () async -> AsyncStream<Void> = { AsyncStream { _ in } }) {
        self.currentRevision = currentRevision; self.page = page; self.hit = hit; self.invalidations = invalidations
    }
    init(store: OfflineStore) {
        currentRevision = { try await store.currentSearchRevision() }
        page = { query, revision, cursor, limit in
            try await store.searchPage(query: query, revision: revision, before: cursor, limit: limit)
        }
        hit = { id, revision in try await store.searchHit(recordID: id, revision: revision) }
        invalidations = { await store.searchInvalidations() }
    }
}

struct OfflineSearchReadOnlyDetail: Equatable, Identifiable, Sendable {
    enum Reason: Equatable, Sendable { case stale, unsupported }
    let hit: OfflineSearchHit
    let revision: OfflineSearchRevision
    let reason: Reason
    var id: UUID { hit.record.clientUUID }
}

struct OfflineSearchRecordOpen: Equatable, Sendable {
    let hit: OfflineSearchHit
    let revision: OfflineSearchRevision
}
