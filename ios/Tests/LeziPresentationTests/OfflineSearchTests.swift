import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI
#if canImport(SwiftUI)
import SwiftUI
import Combine
#endif

private func searchRevision(context: OfflineContext? = nil, actor: UUID = UUID()) -> OfflineSearchRevision {
    .init(context: context ?? .init(generation: UUID(), selectionRevision: 1, babyClientUUID: UUID()),
          localActorUUID: actor, changeRevision: UUID())
}
private func searchHit(_ revision: OfflineSearchRevision, id: UUID = UUID(), note: String = "原备注",
                       payload: RecordPayload? = .pee(), rawType: String = "pee", deletedAt: Int64? = nil) -> OfflineSearchHit {
    let document = payload.map { try! RecordPayloadDocument(payload: $0) }
    return .init(record: .init(clientUUID: id, babyClientUUID: revision.context.babyClientUUID,
        rawType: rawType, timestampMillis: 900, endTimestampMillis: nil, note: note,
        payloadJSON: "{}", payloadSchemaVersion: payload == nil ? 99 : 2, payloadDocument: document,
        updatedAt: 1000, syncDirty: true, deletedAt: deletedAt))
}

final class OfflineSearchStateTests: XCTestCase {
    func testPhasesScalarLimitAndExactAndroidCopy() throws {
        var state = OfflineSearchState()
        XCTAssertEqual(state.phase, .prompt)
        XCTAssertNil(state.setQuery(" \t\n")); XCTAssertEqual(state.phase, .prompt)
        let value = String(repeating: "😀", count: 101)
        let request = try XCTUnwrap(state.setQuery(value))
        XCTAssertEqual(state.query.unicodeScalars.count, 100)
        XCTAssertEqual(state.phase, .loading)
        let revision = searchRevision()
        XCTAssertTrue(state.prepare(revision, for: request))
        XCTAssertTrue(state.finish(.init(hits: [], nextCursor: nil, revision: revision), for: request))
        XCTAssertEqual(state.phase, .empty)
        XCTAssertEqual(OfflineSearchCopy.emptyMessage(value), "没有匹配「" + String(repeating: "😀", count: 30) + "…」的记录")
        XCTAssertEqual(OfflineSearchCopy.fieldLabel, "类型 / 详情 / 备注")
        XCTAssertEqual(OfflineSearchCopy.placeholder, "例如：睡眠、布洛芬、发烧")
        XCTAssertEqual(OfflineSearchCopy.stale, "未能刷新。上次结果可能已过期，暂仅供查看；可重试获取最新记录。")
    }
    func testSupersededQueryCannotPaintAndCanonicallyEquivalentQueryDiscardsSnapshot() throws {
        let revision = searchRevision(), hit = searchHit(searchRevision())
        var state = OfflineSearchState()
        let old = try XCTUnwrap(state.setQuery("é"))
        _ = state.prepare(revision, for: old)
        let correct = searchHit(revision)
        _ = state.finish(.init(hits: [correct], nextCursor: nil, revision: revision), for: old)
        let new = try XCTUnwrap(state.setQuery("e\u{301}"))
        XCTAssertTrue(state.hits.isEmpty)
        XCTAssertFalse(state.finish(.init(hits: [hit], nextCursor: nil, revision: revision), for: old))
        state.fail(new, verifiedRevision: revision)
        XCTAssertEqual(state.phase, .error); XCTAssertFalse(state.isReadOnly)
    }
    func testVerifiedSameScopeFailureRetainsReadOnlyButScopeEpochChangeClears() throws {
        let revision = searchRevision(), hit = searchHit(searchRevision())
        var state = OfflineSearchState()
        let first = try XCTUnwrap(state.setQuery("尿"))
        _ = state.prepare(revision, for: first)
        let original = searchHit(revision)
        _ = state.finish(.init(hits: [original], nextCursor: nil, revision: revision), for: first)
        let next = try XCTUnwrap(state.beginRefresh())
        let changed = searchRevision(context: revision.context, actor: revision.localActorUUID)
        _ = state.prepare(changed, for: next)
        state.fail(next, verifiedRevision: changed)
        XCTAssertEqual(state.hits, [original]); XCTAssertTrue(state.isReadOnly); XCTAssertTrue(state.hasFailed)
        XCTAssertEqual(state.phase, .results)
        let again = try XCTUnwrap(state.beginRefresh())
        let selectedAgain = searchRevision(context: .init(generation: revision.context.generation,
            selectionRevision: 3, babyClientUUID: revision.context.babyClientUUID), actor: revision.localActorUUID)
        _ = state.prepare(selectedAgain, for: again)
        XCTAssertTrue(state.hits.isEmpty)
        state.fail(again, verifiedRevision: selectedAgain)
        XCTAssertEqual(state.phase, .error)
        XCTAssertFalse(state.finish(.init(hits: [hit], nextCursor: nil, revision: revision), for: next))
    }
    func testUnknownScopeConcealsImmediatelyAndOnlyVerifiedSameScopeRestores() throws {
        let revision = searchRevision()
        var state = OfflineSearchState()
        let first = try XCTUnwrap(state.setQuery("尿"))
        _ = state.prepare(revision, for: first)
        let hit = searchHit(revision)
        _ = state.finish(.init(hits: [hit], nextCursor: nil, revision: revision), for: first)
        state.concealUntilScopeVerified()
        let retry = try XCTUnwrap(state.beginRefresh())
        XCTAssertTrue(state.hits.isEmpty); XCTAssertNil(state.snapshotRevision)
        _ = state.prepare(searchRevision(context: revision.context, actor: revision.localActorUUID), for: retry)
        XCTAssertEqual(state.hits, [hit]); XCTAssertTrue(state.isReadOnly)
        state.concealUntilScopeVerified()
        let failed = try XCTUnwrap(state.beginRefresh())
        state.fail(failed, verifiedRevision: nil)
        XCTAssertTrue(state.hits.isEmpty); XCTAssertNil(state.snapshotRevision)
    }
    func testWrongBabyAndDeletedHitsCannotEnterDisplay() throws {
        let revision = searchRevision()
        var state = OfflineSearchState()
        let request = try XCTUnwrap(state.setQuery("尿"))
        _ = state.prepare(revision, for: request)
        let good = searchHit(revision), foreign = searchHit(searchRevision())
        let deleted = searchHit(revision, deletedAt: 1001)
        _ = state.finish(.init(hits: [foreign, deleted, good, good], nextCursor: nil, revision: revision), for: request)
        XCTAssertEqual(state.hits, [good])
    }
    func testUnsupportedOrdinaryAndSleepRecordsAreReadOnly() {
        let revision = searchRevision()
        XCTAssertTrue(OfflineSearchState.supportsEditing(searchHit(revision)))
        XCTAssertFalse(OfflineSearchState.supportsEditing(searchHit(revision, payload: nil, rawType: "future")))
        XCTAssertFalse(OfflineSearchState.supportsEditing(searchHit(revision, payload: .sleep(), rawType: "sleep")))
    }
}

private enum SearchFixtureError: Error { case read }
private actor SearchFixture {
    var revision: OfflineSearchRevision
    var hits: [OfflineSearchHit]
    var pageFails = false
    var scopeFails = false
    private var holdPage = false
    private var holdScope = false
    private var pageContinuation: CheckedContinuation<Void, Never>?
    private var scopeContinuation: CheckedContinuation<Void, Never>?
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]
    private(set) var queries: [String] = []
    var isPageHeld: Bool { pageContinuation != nil }
    var isScopeHeld: Bool { scopeContinuation != nil }
    init(revision: OfflineSearchRevision, hits: [OfflineSearchHit]) { self.revision = revision; self.hits = hits }
    func source() -> OfflineSearchRepository {
        .init(currentRevision: { try await self.current() }, page: { query, revision, _, _ in
            try await self.page(query, revision: revision)
        }, hit: { id, revision in try await self.hit(id, revision: revision) },
        invalidations: { await self.stream() })
    }
    func current() async throws -> OfflineSearchRevision {
        let captured = revision
        if holdScope {
            holdScope = false
            await withCheckedContinuation { scopeContinuation = $0 }
        }
        if scopeFails { throw SearchFixtureError.read }
        return captured
    }
    func page(_ query: String, revision: OfflineSearchRevision) async throws -> OfflineSearchPage {
        queries.append(query)
        let captured = hits
        let fail = pageFails
        if holdPage {
            holdPage = false
            await withCheckedContinuation { pageContinuation = $0 }
        }
        if fail { throw SearchFixtureError.read }
        return .init(hits: captured, nextCursor: nil, revision: revision)
    }
    func hit(_ id: UUID, revision: OfflineSearchRevision) throws -> OfflineSearchHit? {
        if pageFails || self.revision != revision { throw SearchFixtureError.read }
        return hits.first { $0.record.clientUUID == id }
    }
    func stream() -> AsyncStream<Void> {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Void>.makeStream()
        listeners[id] = continuation
        continuation.onTermination = { @Sendable [weak self] _ in Task { await self?.remove(id) } }
        return stream
    }
    func remove(_ id: UUID) { listeners.removeValue(forKey: id) }
    func notify() { for continuation in listeners.values { continuation.yield(()) } }
    func configure(revision: OfflineSearchRevision? = nil, hits: [OfflineSearchHit]? = nil,
                   pageFails: Bool? = nil, scopeFails: Bool? = nil, notify: Bool = false) {
        if let revision { self.revision = revision }; if let hits { self.hits = hits }
        if let pageFails { self.pageFails = pageFails }; if let scopeFails { self.scopeFails = scopeFails }
        if notify { self.notify() }
    }
    func holdNextPage() { holdPage = true }
    func holdNextScope() { holdScope = true }
    func releasePage() { pageContinuation?.resume(); pageContinuation = nil }
    func releaseScope() { scopeContinuation?.resume(); scopeContinuation = nil }
}

private actor SearchDebounceGate {
    private(set) var durations: [UInt64] = []
    private var continuations: [CheckedContinuation<Void, Never>] = []
    func wait(_ nanoseconds: UInt64) async {
        durations.append(nanoseconds)
        await withCheckedContinuation { continuations.append($0) }
    }
    func releaseAll() { let pending = continuations; continuations = []; for continuation in pending { continuation.resume() } }
}

@MainActor
final class OfflineSearchModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () async -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !(await condition()), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        let result = await condition(); XCTAssertTrue(result)
    }
    private func model(_ fixture: SearchFixture) async -> OfflineSearchModel {
        OfflineSearchModel(repository: await fixture.source(), delay: { _ in await Task.yield() })
    }
    func testIdenticalQueryEchoPreservesFreshResultAndExplicitRetryStillRefreshes() async throws {
        let revision = searchRevision(), gate = SearchDebounceGate()
        let hit = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [hit])
        let model = OfflineSearchModel(repository: await fixture.source(), delay: { await gate.wait($0) })
        defer { model.stop() }
        model.setQuery("search-a_%")
        try await settle { await gate.durations.count == 1 }
        await gate.releaseAll()
        try await settle { model.state.hits == [hit] && !model.state.isSearching }
        let requestID = model.state.requestID
        // TextField may echo its unchanged value when focus is removed on a hit tap.
        model.setQuery("search-a_%")
        XCTAssertEqual(model.state.requestID, requestID)
        XCTAssertFalse(model.state.isSearching); XCTAssertFalse(model.state.isReadOnly)
        let opened = await model.requestOpenRecord(hit)
        XCTAssertEqual(opened?.hit, hit); XCTAssertNil(model.readOnlyDetail)
        let queries = await fixture.queries, delays = await gate.durations
        XCTAssertEqual(queries, ["search-a_%"]); XCTAssertEqual(delays, [200_000_000])
        model.retry()
        XCTAssertNotEqual(model.state.requestID, requestID)
        try await settle { await gate.durations.count == 2 }
        await gate.releaseAll()
        try await settle { !model.state.isSearching && !model.state.isReadOnly }
        let retriedQueries = await fixture.queries
        XCTAssertEqual(retriedQueries, ["search-a_%", "search-a_%"])
    }
    func testIdenticalQueryEchoDoesNotCancelPendingGuardedOpen() async throws {
        let revision = searchRevision(), hit = searchHit(searchRevision())
        let original = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [original]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿")
        try await settle { model.state.hits == [original] && !model.state.isSearching }
        let requestID = model.state.requestID
        await fixture.holdNextScope()
        let opening = Task { await model.requestOpenRecord(original) }
        try await settle { await fixture.isScopeHeld }
        model.setQuery("尿")
        XCTAssertEqual(model.state.requestID, requestID)
        await fixture.releaseScope()
        let opened = await opening.value
        XCTAssertEqual(opened?.hit, original); XCTAssertNil(model.readOnlyDetail)
        let unknown = await model.requestOpenRecord(hit)
        XCTAssertNil(unknown)
    }
    func testQueryEchoUsesExactUTF16AndStillCorrectsOverLimitInput() async throws {
        let revision = searchRevision(), hit = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [hit]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("é")
        try await settle { model.state.hits == [hit] && !model.state.isSearching }
        let composedRequest = model.state.requestID
        model.setQuery("e\u{301}")
        XCTAssertNotEqual(model.state.requestID, composedRequest)
        XCTAssertTrue(model.state.hits.isEmpty)
        XCTAssertTrue(model.state.query.utf16.elementsEqual("e\u{301}".utf16))
        try await settle { !model.state.isSearching }
        let long = String(repeating: "😀", count: 101)
        model.setQuery(long)
        try await settle { !model.state.isSearching }
        XCTAssertEqual(model.state.query.unicodeScalars.count, 100)
        let limitedRequest = model.state.requestID
        // A raw over-limit value differs from the stored correction and must
        // still publish the scalar-limited value back to the input binding.
        model.setQuery(long)
        XCTAssertNotEqual(model.state.requestID, limitedRequest)
        XCTAssertEqual(model.state.query.unicodeScalars.count, 100)
        try await settle { !model.state.isSearching }
        let correctedRequest = model.state.requestID
        model.setQuery(model.state.query)
        XCTAssertEqual(model.state.requestID, correctedRequest)
        XCTAssertFalse(model.state.isSearching)
    }
    func testTwoHundredMillisecondDebounceAndLateNonCooperativeQueryCannotPaint() async throws {
        let revision = searchRevision(), gate = SearchDebounceGate()
        let first = searchHit(revision, note: "第一条"), second = searchHit(revision, note: "第二条")
        let fixture = SearchFixture(revision: revision, hits: [first])
        let model = OfflineSearchModel(repository: await fixture.source(), delay: { await gate.wait($0) })
        defer { model.stop() }
        await fixture.holdNextPage()
        model.setQuery("A")
        try await settle { await gate.durations.count == 1 }
        let durations = await gate.durations; XCTAssertEqual(durations, [200_000_000])
        let initialQueries = await fixture.queries; XCTAssertTrue(initialQueries.isEmpty)
        await gate.releaseAll()
        try await settle { await fixture.isPageHeld }
        await fixture.configure(hits: [second])
        model.setQuery("B")
        try await settle { await gate.durations.count == 2 }
        await gate.releaseAll()
        try await settle { model.state.hits == [second] && !model.state.isSearching }
        await fixture.releasePage()
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(model.state.query, "B"); XCTAssertEqual(model.state.hits, [second])
    }
    func testSameScopeRefreshFailureReadOnlyOpenAndRetryRecovery() async throws {
        let revision = searchRevision(), hit = searchHit(searchRevision())
        let original = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [original]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { model.state.hits == [original] && !model.state.isSearching }
        let changed = searchRevision(context: revision.context, actor: revision.localActorUUID)
        await fixture.configure(revision: changed, pageFails: true, notify: true)
        try await settle { model.state.hasFailed }
        XCTAssertEqual(model.state.hits, [original]); XCTAssertTrue(model.state.isReadOnly)
        let opened = await model.requestOpenRecord(original)
        XCTAssertNil(opened); XCTAssertEqual(model.readOnlyDetail?.hit, original)
        XCTAssertEqual(model.readOnlyDetail?.reason, .stale)
        model.closeDetail()
        let recovered = searchHit(changed)
        await fixture.configure(hits: [recovered], pageFails: false)
        model.retry(); try await settle { !model.state.isSearching && model.state.hits == [recovered] }
        XCTAssertFalse(model.state.hasFailed); XCTAssertFalse(model.state.isReadOnly)
        let staleOpen = await model.requestOpenRecord(hit); XCTAssertNil(staleOpen)
        let currentOpen = await model.requestOpenRecord(recovered); XCTAssertEqual(currentOpen?.hit, recovered)
    }
    func testFailedGuardedReopenExposesRetryAfterReadOnlyDetailCloses() async throws {
        let revision = searchRevision(), hit = searchHit(searchRevision())
        let original = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [original]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { model.state.hits == [original] && !model.state.isSearching }
        await fixture.configure(pageFails: true)
        let opened = await model.requestOpenRecord(original)
        XCTAssertNil(opened); XCTAssertEqual(model.readOnlyDetail?.reason, .stale)
        model.closeDetail()
        XCTAssertTrue(model.state.isReadOnly); XCTAssertTrue(model.state.hasFailed)
        XCTAssertFalse(model.state.isSearching); XCTAssertEqual(model.state.hits, [original])
        await fixture.configure(pageFails: false)
        model.retry(); try await settle { !model.state.isSearching && !model.state.isReadOnly }
        XCTAssertFalse(model.state.hasFailed)
        let unknown = await model.requestOpenRecord(hit); XCTAssertNil(unknown)
        let fresh = await model.requestOpenRecord(original); XCTAssertEqual(fresh?.hit, original)
    }
    func testSceneActivationConcealsAndRechecksScopeWithoutCommitNotification() async throws {
        let revision = searchRevision(), original = searchHit(searchRevision())
        let hit = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [hit]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { model.state.hits == [hit] && !model.state.isSearching }
        let changed = searchRevision(context: revision.context, actor: UUID())
        await fixture.holdNextScope()
        await fixture.configure(revision: changed, hits: [original], pageFails: true)
        model.activate()
        XCTAssertTrue(model.state.hits.isEmpty)
        try await settle { await fixture.isScopeHeld }
        XCTAssertTrue(model.state.hits.isEmpty)
        await fixture.releaseScope()
        try await settle { model.state.hasFailed }
        XCTAssertTrue(model.state.hits.isEmpty); XCTAssertNil(model.readOnlyDetail)
    }
    func testScopeInvalidationConcealsResultsWhileDirectScopeReadIsSuspended() async throws {
        let revision = searchRevision(), hit = searchHit(searchRevision())
        let original = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [original]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { !model.state.isSearching && model.state.hits == [original] }
        await fixture.holdNextScope()
        let other = searchRevision()
        await fixture.configure(revision: other, hits: [hit], pageFails: true, notify: true)
        try await settle { await fixture.isScopeHeld }
        XCTAssertTrue(model.state.hits.isEmpty); XCTAssertNil(model.state.snapshotRevision)
        XCTAssertNil(model.readOnlyDetail)
        await fixture.releaseScope()
        try await settle { model.state.hasFailed }
        XCTAssertTrue(model.state.hits.isEmpty)
    }
    func testAwayAndBackEpochNeverPaintsSuspendedOldScope() async throws {
        let first = searchRevision(), original = searchHit(searchRevision())
        let oldHit = searchHit(first)
        let fixture = SearchFixture(revision: first, hits: [oldHit]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { model.state.hits == [oldHit] && !model.state.isSearching }
        await fixture.holdNextPage()
        model.retry(); try await settle { await fixture.isPageHeld }
        let other = searchRevision()
        await fixture.configure(revision: other, hits: [original], notify: true)
        try await settle { model.state.currentRevision == other }
        let back = searchRevision(context: .init(generation: first.context.generation,
            selectionRevision: 3, babyClientUUID: first.context.babyClientUUID), actor: first.localActorUUID)
        let fresh = searchHit(back)
        await fixture.configure(revision: back, hits: [fresh], notify: true)
        try await settle { model.state.hits == [fresh] && !model.state.isSearching }
        await fixture.releasePage()
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(model.state.hits, [fresh]); XCTAssertFalse(model.state.hits.contains(oldHit))
    }
    func testDelayedScopeWithoutNotificationIsRejectedBeforePaintingAndOnTap() async throws {
        let revision = searchRevision(), first = searchHit(searchRevision())
        let original = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [original]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { model.state.hits == [original] && !model.state.isSearching }
        await fixture.holdNextPage(); model.retry()
        try await settle { await fixture.isPageHeld }
        let changed = searchRevision()
        await fixture.configure(revision: changed, hits: [first], pageFails: true)
        await fixture.releasePage()
        try await settle { model.state.hasFailed }
        XCTAssertTrue(model.state.hits.isEmpty)
        let opened = await model.requestOpenRecord(original); XCTAssertNil(opened)
        await fixture.configure(revision: revision, hits: [original], pageFails: false)
        model.retry(); try await settle { model.state.hits == [original] && !model.state.isSearching }
        await fixture.configure(revision: changed)
        let late = await model.requestOpenRecord(original)
        XCTAssertNil(late); XCTAssertTrue(model.state.hits.isEmpty); XCTAssertNil(model.readOnlyDetail)
    }
    func testScopeReadFailureDropsCacheAndRetryRecovers() async throws {
        let revision = searchRevision(), original = searchHit(searchRevision())
        let good = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [good]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { model.state.hits == [good] && !model.state.isSearching }
        await fixture.configure(scopeFails: true, notify: true)
        try await settle { model.state.hasFailed }
        XCTAssertTrue(model.state.hits.isEmpty); XCTAssertFalse(model.state.isReadOnly)
        await fixture.configure(hits: [original], scopeFails: false)
        model.retry(); try await settle { !model.state.isSearching }
        XCTAssertTrue(model.state.hits.isEmpty) // Defensive baby filter, not a leaked other-baby row.
        XCTAssertFalse(model.state.hasFailed)
    }
    func testUnsupportedAndChangedDisplayedHitsNeverReachEditableCallback() async throws {
        let revision = searchRevision()
        let opaque = searchHit(revision, payload: nil, rawType: "future")
        let fixture = SearchFixture(revision: revision, hits: [opaque]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("备注"); try await settle { model.state.hits == [opaque] && !model.state.isSearching }
        let opened = await model.requestOpenRecord(opaque)
        XCTAssertNil(opened); XCTAssertEqual(model.readOnlyDetail?.reason, .unsupported)
        let other = searchRevision()
        await fixture.holdNextScope()
        await fixture.configure(revision: other, hits: [], notify: true)
        try await settle { await fixture.isScopeHeld }
        XCTAssertNil(model.readOnlyDetail); XCTAssertTrue(model.state.hits.isEmpty)
        await fixture.releaseScope(); try await settle { !model.state.isSearching }
        XCTAssertNil(model.readOnlyDetail)
    }
    func testDataChangeAtTapShowsOriginalReadOnlyDetailNotReplacementEditor() async throws {
        let revision = searchRevision(), original = searchHit(searchRevision())
        let first = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [first]), model = await model(fixture)
        defer { model.stop() }
        model.setQuery("尿"); try await settle { model.state.hits == [first] && !model.state.isSearching }
        let changed = searchRevision(context: revision.context, actor: revision.localActorUUID)
        await fixture.configure(revision: changed, hits: [original], pageFails: true)
        let opened = await model.requestOpenRecord(first)
        XCTAssertNil(opened); XCTAssertEqual(model.readOnlyDetail?.hit, first)
        XCTAssertEqual(model.readOnlyDetail?.reason, .stale)
        try await settle { model.state.hasFailed }
        XCTAssertEqual(model.readOnlyDetail?.hit, first)
    }
    func testQueryClearAndStopFencePendingReadsAndActivationRechecksScope() async throws {
        let revision = searchRevision(), hit = searchHit(searchRevision())
        let first = searchHit(revision)
        let fixture = SearchFixture(revision: revision, hits: [first]), model = await model(fixture)
        defer { model.stop() }
        await fixture.holdNextPage(); model.setQuery("尿")
        try await settle { await fixture.isPageHeld }
        model.setQuery(""); await fixture.releasePage()
        try await Task.sleep(for: .milliseconds(10))
        XCTAssertEqual(model.state.phase, .prompt); XCTAssertTrue(model.state.hits.isEmpty)
        model.setQuery("尿"); try await settle { model.state.hits == [first] && !model.state.isSearching }
        model.stop()
        let changed = searchRevision()
        await fixture.configure(revision: changed, hits: [hit], pageFails: true)
        model.start()
        XCTAssertTrue(model.state.hits.isEmpty)
        try await settle { model.state.hasFailed }
        XCTAssertTrue(model.state.hits.isEmpty)
    }
    func testRealHistorySkipsEmptyCandidateChunksAndRefreshesCommittedEditDelete() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 10_000 })
        let baby = try await store.createBaby(nickname: "搜索", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        var oldest: OfflineRecord?
        for number in 0..<35 {
            let draft = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
                item: .builtIn(.pee)!, timestampMillis: Int64(number + 1),
                note: number == 0 ? "深处%_\\记录" : "普通备注", payload: .pee())
            let row = try await store.confirmRecord(draft, context: context)
            if number == 0 { oldest = row }
        }
        let model = OfflineSearchModel(repository: .init(store: store), pageLimit: 3, delay: { _ in await Task.yield() })
        defer { model.stop() }
        model.setQuery("%_\\")
        try await settle { !model.state.isSearching }
        let row = try XCTUnwrap(oldest)
        XCTAssertEqual(model.state.hits.map(\.record.clientUUID), [row.clientUUID])
        XCTAssertNil(model.state.nextCursor)
        let edit = RecordConfirmationDraft(clientUUID: row.clientUUID, babyClientUUID: baby.clientUUID,
            item: .builtIn(.pee)!, timestampMillis: row.timestampMillis, note: "深处%_\\已修改", payload: .pee(amount: 3))
        let updated = try await store.confirmRecord(edit, context: context, expectedUpdatedAt: row.updatedAt)
        try await settle { model.state.hits.first?.record == updated && !model.state.isSearching }
        _ = try await store.deleteRecord(row.clientUUID, context: context, expectedUpdatedAt: updated.updatedAt)
        try await settle { model.state.hits.isEmpty && !model.state.isSearching }
        XCTAssertEqual(model.state.phase, .empty)
    }
    func testLoadMoreUsesOneSnapshotWithoutDuplicatesAndRefreshDropsOldCursor() async throws {
        let store = try OfflineStore(path: ":memory:", clock: { 10_000 })
        let baby = try await store.createBaby(nickname: "分页", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        for number in 0..<7 {
            _ = try await store.confirmRecord(.init(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
                item: .builtIn(.pee)!, timestampMillis: Int64(number + 1), note: "分页", payload: .pee()), context: context)
        }
        let model = OfflineSearchModel(repository: .init(store: store), pageLimit: 2, delay: { _ in await Task.yield() })
        defer { model.stop() }
        model.setQuery("分页"); try await settle { model.state.hits.count == 2 && !model.state.isSearching }
        let initial = model.state.snapshotRevision
        model.loadMore(); model.loadMore()
        try await settle { model.state.hits.count == 4 && !model.state.isSearching }
        XCTAssertEqual(model.state.snapshotRevision, initial)
        model.loadMore(); try await settle { model.state.hits.count == 6 && !model.state.isSearching }
        model.loadMore(); try await settle { model.state.hits.count == 7 && !model.state.isSearching }
        XCTAssertNil(model.state.nextCursor); XCTAssertEqual(Set(model.state.hits.map(\.record.clientUUID)).count, 7)
        _ = try await store.confirmRecord(.init(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.pee)!, timestampMillis: 100, note: "分页新记录", payload: .pee()), context: context)
        try await settle { model.state.hits.count == 2 && model.state.snapshotRevision != initial && !model.state.isSearching }
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineSearchAppleModelTests: XCTestCase {
    func testPublishedStateAndReadOnlyDetailDriveAppleViewWithoutWriteCapability() async throws {
        let revision = searchRevision()
        let opaque = searchHit(revision, payload: nil, rawType: "future")
        let fixture = SearchFixture(revision: revision, hits: [opaque])
        let model = OfflineSearchModel(repository: await fixture.source(), delay: { _ in await Task.yield() })
        defer { model.stop() }
        var states: [OfflineSearchState] = []
        var details: [OfflineSearchReadOnlyDetail?] = []
        let stateObservation = model.$state.sink { states.append($0) }
        let detailObservation = model.$readOnlyDetail.sink { details.append($0) }
        model.setQuery("备注")
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while model.state.isSearching, ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertEqual(model.state.hits, [opaque])
        let opened = await model.requestOpenRecord(opaque)
        XCTAssertNil(opened); XCTAssertEqual(details.last??.reason, .unsupported)
        XCTAssertTrue(states.contains { $0.phase == .loading })
        XCTAssertTrue(states.contains { $0.hits == [opaque] })
        model.closeDetail(); XCTAssertNil(model.readOnlyDetail)
        withExtendedLifetime((stateObservation, detailObservation)) {}
    }
}
#endif
