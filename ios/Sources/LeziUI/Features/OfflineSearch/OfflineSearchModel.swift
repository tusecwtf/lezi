import Foundation
import LeziStorage
#if canImport(SwiftUI)
import SwiftUI
#endif

/// Only asynchronous reads live here. Closing search cancels those reads, never
/// an accepted editor write, which remains owned by the parent feature.
@MainActor
final class OfflineSearchModel {
#if canImport(SwiftUI)
    @Published
#endif
    private(set) var state = OfflineSearchState()
#if canImport(SwiftUI)
    @Published
#endif
    private(set) var readOnlyDetail: OfflineSearchReadOnlyDetail?
    private let repository: OfflineSearchRepository
    private let delay: @Sendable (UInt64) async throws -> Void
    private let pageLimit: Int
    private var operation: Task<Void, Never>?
    private var subscription: Task<AsyncStream<Void>, Never>?
    private var observation: Task<Void, Never>?
    private var observationID: UUID?
    private var openAttemptID: UUID?
    private var isDebouncing = false
    private var concealedDetail: OfflineSearchReadOnlyDetail?

    convenience init(store: OfflineStore) { self.init(repository: .init(store: store)) }
    init(repository: OfflineSearchRepository, pageLimit: Int = 100,
         delay: @escaping @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0) }) {
        self.repository = repository; self.pageLimit = min(200, max(1, pageLimit)); self.delay = delay
    }
    deinit { operation?.cancel(); subscription?.cancel(); observation?.cancel() }

    func start() {
        guard subscription == nil else { return }
        ensureObservation()
        if state.phase != .prompt { invalidate() }
    }
    func activate() {
        ensureObservation()
        invalidate()
    }
    func stop() {
        operation?.cancel(); operation = nil
        observationID = nil; observation?.cancel(); observation = nil
        subscription?.cancel(); subscription = nil
        openAttemptID = nil; isDebouncing = false; state.suspend()
    }
    func setQuery(_ value: String) {
        // Focus changes can echo the current TextField value. They are not a
        // query change or explicit retry and must not invalidate a guarded open.
        // Compare raw UTF-16 so canonical differences and over-limit corrections
        // still take the normal query-change path.
        guard !value.utf16.elementsEqual(state.query.utf16) else { return }
        operation?.cancel(); openAttemptID = nil; readOnlyDetail = nil; concealedDetail = nil
        guard let request = state.setQuery(value) else { isDebouncing = false; return }
        launch(request, debounced: true)
    }
    func retry() { refresh(debounced: true) }
    func loadMore() {
        guard let request = state.beginMore() else { return }
        launch(request, debounced: false)
    }
    func closeDetail() { readOnlyDetail = nil; concealedDetail = nil }

    private func invalidate() {
        openAttemptID = nil
        state.concealUntilScopeVerified()
        if let readOnlyDetail { concealedDetail = readOnlyDetail; self.readOnlyDetail = nil }
        refresh(debounced: isDebouncing)
    }

    private func ensureObservation() {
        guard subscription == nil else { return }
        let repository = repository
        let id = UUID(); observationID = id
        let source = Task { await repository.invalidations() }
        subscription = source
        observation = Task { [weak self] in
            let stream = await source.value
            for await _ in stream {
                guard !Task.isCancelled, let self, self.observationID == id else { return }
                // Fence in-flight reads before waiting for the direct scope read.
                self.invalidate()
            }
        }
    }
    private func refresh(debounced: Bool) {
        operation?.cancel()
        guard let request = state.beginRefresh() else { isDebouncing = false; return }
        launch(request, debounced: debounced)
    }
    private func launch(_ request: OfflineSearchState.Request, debounced: Bool) {
        ensureObservation()
        let source = subscription
        let delay = delay
        let repository = repository
        isDebouncing = debounced
        operation = Task { [weak self] in
            // Subscribe before taking the revision so no commit falls in the gap.
            _ = await source?.value
            do {
                if debounced {
                    // Even retry debounce must not retain a previous baby's rows.
                    let revision = try await repository.currentRevision()
                    guard !Task.isCancelled, let self, self.state.accepts(request) else { return }
                    self.clearDetailOutsideScope(revision)
                    _ = self.state.prepare(revision, for: request)
                    try await delay(200_000_000)
                }
                guard !Task.isCancelled, let self, self.state.accepts(request) else { return }
                self.isDebouncing = false
                await self.read(request)
            } catch {
                guard !Task.isCancelled, let self, self.state.accepts(request) else { return }
                self.isDebouncing = false
                self.state.fail(request, verifiedRevision: nil)
                self.readOnlyDetail = nil; self.concealedDetail = nil
            }
        }
    }
    private enum ReadError: Error { case inconsistentPage, repeatedCursor }
    private func read(_ request: OfflineSearchState.Request) async {
        let before: OfflineSearchRevision
        do { before = try await repository.currentRevision() }
        catch {
            guard !Task.isCancelled, state.accepts(request) else { return }
            state.fail(request, verifiedRevision: nil); readOnlyDetail = nil; concealedDetail = nil
            return
        }
        guard !Task.isCancelled, state.accepts(request) else { return }
        clearDetailOutsideScope(before)
        guard state.prepare(before, for: request) else {
            refresh(debounced: false)
            return
        }
        do {
            var cursor = request.before
            while true {
                let page = try await repository.page(request.query, before, cursor, pageLimit)
                guard !Task.isCancelled, state.accepts(request) else { return }
                let after = try await repository.currentRevision()
                guard !Task.isCancelled, state.accepts(request) else { return }
                if before != after {
                    changed(after, during: request)
                    return
                }
                guard page.revision == before else { throw ReadError.inconsistentPage }
                // Candidate chunks are not result pages. Never display "无结果"
                // until the stored history is exhausted, even across sparse chunks.
                if !page.hits.isEmpty || page.nextCursor == nil {
                    _ = state.finish(page, for: request)
                    return
                }
                guard page.nextCursor != cursor else { throw ReadError.repeatedCursor }
                cursor = page.nextCursor
            }
        } catch {
            guard !Task.isCancelled, state.accepts(request) else { return }
            // A failed row read cannot establish that the retained scope is safe.
            let verified = try? await repository.currentRevision()
            guard !Task.isCancelled, state.accepts(request) else { return }
            if let verified, verified != before {
                changed(verified, during: request)
                return
            }
            state.fail(request, verifiedRevision: verified)
            if verified == nil { readOnlyDetail = nil; concealedDetail = nil }
        }
    }
    private func changed(_ revision: OfflineSearchRevision, during request: OfflineSearchState.Request) {
        guard state.accepts(request) else { return }
        _ = state.prepare(revision, for: request)
        clearDetailOutsideScope(revision)
        refresh(debounced: false)
    }
    private func clearDetailOutsideScope(_ revision: OfflineSearchRevision) {
        if let concealedDetail {
            self.concealedDetail = nil
            if OfflineSearchState.sameScope(concealedDetail.revision, revision) { readOnlyDetail = concealedDetail }
        }
        guard let readOnlyDetail else { return }
        if !OfflineSearchState.sameScope(readOnlyDetail.revision, revision) {
            self.readOnlyDetail = nil
        } else if readOnlyDetail.revision != revision {
            self.readOnlyDetail = .init(hit: readOnlyDetail.hit, revision: readOnlyDetail.revision, reason: .stale)
        }
    }

    /// Verify the exact displayed snapshot, not a replacement loaded behind the
    /// user's tap. Stale same-scope snapshots may only open an immutable detail.
    func requestOpenRecord(_ displayed: OfflineSearchHit) async -> OfflineSearchRecordOpen? {
        guard openAttemptID == nil, let snapshot = state.snapshotRevision,
              let hit = state.hits.first(where: { $0.record.clientUUID == displayed.record.clientUUID }),
              hit == displayed else { return nil }
        let attempt = UUID(); openAttemptID = attempt
        let requestID = state.requestID
        defer { if openAttemptID == attempt { openAttemptID = nil } }
        func isCurrent() -> Bool {
            openAttemptID == attempt && state.requestID == requestID && state.snapshotRevision == snapshot
        }
        let before: OfflineSearchRevision
        do { before = try await repository.currentRevision() }
        catch {
            if isCurrent() { rejectOpenScope() }
            return nil
        }
        guard isCurrent() else { return nil }
        guard OfflineSearchState.sameScope(snapshot, before) else { rejectOpenScope(); return nil }
        if state.isReadOnly || state.isSearching || before != snapshot {
            showReadOnly(hit, revision: snapshot, reason: .stale)
            if before != snapshot { refresh(debounced: false) }
            return nil
        }
        do {
            let current = try await repository.hit(hit.record.clientUUID, snapshot)
            let after = try await repository.currentRevision()
            guard isCurrent() else { return nil }
            guard OfflineSearchState.sameScope(snapshot, after) else { rejectOpenScope(); return nil }
            guard after == snapshot, current == hit else {
                showReadOnly(hit, revision: snapshot, reason: .stale)
                refresh(debounced: false)
                return nil
            }
            guard OfflineSearchState.supportsEditing(hit) else {
                showReadOnly(hit, revision: snapshot, reason: .unsupported)
                return nil
            }
            return .init(hit: hit, revision: snapshot)
        } catch {
            let verified = try? await repository.currentRevision()
            guard isCurrent() else { return nil }
            guard let verified, OfflineSearchState.sameScope(snapshot, verified) else {
                rejectOpenScope(); return nil
            }
            showReadOnly(hit, revision: snapshot, reason: .stale)
            return nil
        }
    }
    private func showReadOnly(_ hit: OfflineSearchHit, revision: OfflineSearchRevision,
                              reason: OfflineSearchReadOnlyDetail.Reason) {
        if reason == .stale { state.markReadOnly() }
        readOnlyDetail = .init(hit: hit, revision: revision, reason: reason)
    }
    private func rejectOpenScope() {
        operation?.cancel(); state.rejectScope(); readOnlyDetail = nil; concealedDetail = nil
    }
}

#if canImport(SwiftUI)
extension OfflineSearchModel: ObservableObject {}
#endif
