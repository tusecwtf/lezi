import Foundation
import LeziCore
import LeziStorage

/// Portable presentation state. A request identity fences late, non-cooperative
/// reads independently of cancellation and of the store's selection epoch.
public struct OfflineSearchState: Equatable, Sendable {
    public enum Phase: Equatable, Sendable { case prompt, loading, error, empty, results }
    struct Request: Equatable, Sendable {
        let id: UUID
        let query: String
        let append: Bool
        let before: OfflineSearchCursor?
    }
    public private(set) var query = ""
    public private(set) var hits: [OfflineSearchHit] = []
    public private(set) var nextCursor: OfflineSearchCursor?
    public private(set) var snapshotRevision: OfflineSearchRevision?
    public private(set) var currentRevision: OfflineSearchRevision?
    public private(set) var isSearching = false
    public private(set) var hasFailed = false
    public private(set) var isReadOnly = false
    private(set) var requestID = UUID()
    private struct ConcealedSnapshot: Equatable, Sendable {
        let hits: [OfflineSearchHit]
        let cursor: OfflineSearchCursor?
        let revision: OfflineSearchRevision
    }
    private var concealed: ConcealedSnapshot?

    public init() {}
    public var phase: Phase {
        if RecordSearchRules.normalizedQuery(query).isEmpty { return .prompt }
        if !hits.isEmpty { return .results }
        if isSearching { return .loading }
        return hasFailed ? .error : .empty
    }
    public var canLoadMore: Bool { nextCursor != nil && !isSearching && !isReadOnly }

    @discardableResult mutating func setQuery(_ raw: String) -> Request? {
        let limited = RecordSearchRules.limitQuery(raw)
        // Kotlin String equality is exact, not Swift's canonical equivalence.
        if !query.utf16.elementsEqual(limited.utf16) { discardSnapshot() }
        query = limited
        return beginRefresh()
    }
    mutating func beginRefresh() -> Request? {
        requestID = UUID(); hasFailed = false
        guard !RecordSearchRules.normalizedQuery(query).isEmpty else {
            discardSnapshot(); isSearching = false
            return nil
        }
        isSearching = true
        if !hits.isEmpty { isReadOnly = true }
        return Request(id: requestID, query: query, append: false, before: nil)
    }
    mutating func beginMore() -> Request? {
        guard canLoadMore, let nextCursor, snapshotRevision != nil else { return nil }
        requestID = UUID(); hasFailed = false; isSearching = true; isReadOnly = !hits.isEmpty
        return Request(id: requestID, query: query, append: true, before: nextCursor)
    }
    func accepts(_ request: Request) -> Bool {
        request.id == requestID && request.query.utf16.elementsEqual(query.utf16)
    }
    /// A new data revision may keep the old same-scope snapshot for viewing.
    /// A new selection, even A -> B -> A, must erase it before awaiting rows.
    @discardableResult mutating func prepare(_ revision: OfflineSearchRevision, for request: Request) -> Bool {
        guard accepts(request) else { return false }
        restoreVerifiedSnapshot(revision)
        if let snapshotRevision, !Self.sameScope(snapshotRevision, revision) { discardSnapshot() }
        currentRevision = revision
        return !request.append || snapshotRevision == revision
    }
    @discardableResult mutating func finish(_ page: OfflineSearchPage, for request: Request) -> Bool {
        guard accepts(request), currentRevision == page.revision,
              !request.append || snapshotRevision == page.revision else { return false }
        let safe = page.hits.filter {
            $0.record.babyClientUUID == page.revision.context.babyClientUUID && $0.record.deletedAt == nil
        }
        if request.append {
            var known = Set(hits.map(\.record.clientUUID))
            hits += safe.filter { known.insert($0.record.clientUUID).inserted }
        } else {
            var known = Set<UUID>()
            hits = safe.filter { known.insert($0.record.clientUUID).inserted }
        }
        concealed = nil
        snapshotRevision = page.revision; nextCursor = page.nextCursor
        isSearching = false; hasFailed = false; isReadOnly = false
        return true
    }
    mutating func fail(_ request: Request, verifiedRevision: OfflineSearchRevision?) {
        guard accepts(request) else { return }
        if let verifiedRevision { restoreVerifiedSnapshot(verifiedRevision) }
        let keep = verifiedRevision.flatMap { current in
            snapshotRevision.map { Self.sameScope($0, current) }
        } == true && !hits.isEmpty
        if !keep { discardSnapshot() }
        currentRevision = verifiedRevision
        isSearching = false; hasFailed = true; isReadOnly = keep
    }
    mutating func rejectScope() {
        requestID = UUID(); discardSnapshot(); currentRevision = nil
        isSearching = false; hasFailed = !RecordSearchRules.normalizedQuery(query).isEmpty
    }
    /// A Void notification does not prove which scope changed. Hide the old
    /// display synchronously, then restore it only after a direct scope check.
    mutating func concealUntilScopeVerified() {
        if let snapshotRevision, !hits.isEmpty {
            concealed = .init(hits: hits, cursor: nextCursor, revision: snapshotRevision)
        }
        hits = []; nextCursor = nil; snapshotRevision = nil
        currentRevision = nil; isReadOnly = false
    }
    private mutating func restoreVerifiedSnapshot(_ revision: OfflineSearchRevision) {
        guard let concealed else { return }
        self.concealed = nil
        guard Self.sameScope(concealed.revision, revision) else { return }
        hits = concealed.hits; nextCursor = concealed.cursor; snapshotRevision = concealed.revision
        isReadOnly = true
    }
    mutating func markReadOnly() {
        if !hits.isEmpty { isReadOnly = true; hasFailed = hasFailed || !isSearching }
    }
    mutating func suspend() {
        requestID = UUID(); isSearching = false
        if !hits.isEmpty { isReadOnly = true }
    }
    private mutating func discardSnapshot() {
        concealed = nil; hits = []; nextCursor = nil; snapshotRevision = nil; isReadOnly = false
    }
    static func sameScope(_ lhs: OfflineSearchRevision, _ rhs: OfflineSearchRevision) -> Bool {
        lhs.context == rhs.context && lhs.localActorUUID == rhs.localActorUUID
    }
    static func supportsEditing(_ hit: OfflineSearchHit) -> Bool {
        if let sleep = hit.sleep { return sleep.record == hit.record && OfflineSleepState.supports(sleep) }
        return OfflinePeeState.canEdit(hit.record)
    }
}

/// Search copy is copied from feature/search/res/values/strings.xml.
enum OfflineSearchCopy {
    static let title = "搜索"
    static let fieldLabel = "类型 / 详情 / 备注"
    static let placeholder = "例如：睡眠、布洛芬、发烧"
    static let clear = "清除搜索"
    static let promptTitle = "输入关键字"
    static let promptMessage = "搜索当前宝宝的记录类型、详情与备注"
    static let loadingTitle = "搜索中"
    static let loadingMessage = "正在查找…"
    static let errorTitle = "暂时无法搜索"
    static let errorMessage = "搜索没有完成，可重试"
    static let retry = "重试"
    static let retrying = "正在重试…"
    static let stale = "未能刷新。上次结果可能已过期，暂仅供查看；可重试获取最新记录。"
    static let emptyTitle = "无结果"
    static func emptyMessage(_ query: String) -> String { "没有匹配「\(RecordSearchRules.queryPreview(query))」的记录" }
}
