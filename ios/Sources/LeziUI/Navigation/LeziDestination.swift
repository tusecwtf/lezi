public enum LeziDestination: String, CaseIterable, Hashable, Sendable {
    case log, summary, growth, family, settings
    /// Current MainActivity.TopDest copy; the UI spec's longer growth label is screen-level copy.
    public var title: String {
        switch self { case .log: "记录"; case .summary: "汇总"; case .growth: "成长"; case .family: "账户"; case .settings: "菜单" }
    }
    public var systemImage: String {
        switch self { case .log: "square.grid.2x2"; case .summary: "chart.bar"; case .growth: "chart.xyaxis.line"; case .family: "person"; case .settings: "ellipsis" }
    }
}
