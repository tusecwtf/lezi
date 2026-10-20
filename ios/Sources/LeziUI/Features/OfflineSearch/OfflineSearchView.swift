#if canImport(SwiftUI)
import SwiftUI
import LeziStorage

/// Push inside the host's NavigationStack. Editors and their accepted writes
/// remain host-owned; stale or unsupported snapshots never reach that callback.
@MainActor
public struct OfflineSearchView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.leziTheme) private var theme
    @StateObject private var model: OfflineSearchModel
    @FocusState private var queryFocused: Bool
    private let onOpenRecord: (OfflineSearchHit, OfflineSearchRevision) -> Void

    public init(store: OfflineStore,
                onOpenRecord: @escaping (OfflineSearchHit, OfflineSearchRevision) -> Void) {
        _model = StateObject(wrappedValue: OfflineSearchModel(store: store))
        self.onOpenRecord = onOpenRecord
    }
    public var body: some View {
        VStack(alignment: .leading, spacing: theme.density.sectionGap) {
            HStack {
                Button { dismiss() } label: { Label("返回", systemImage: "chevron.left") }
                    .frame(minWidth: theme.primaryTouchMinimum, minHeight: theme.primaryTouchMinimum)
                    .accessibilityIdentifier("offline.search.close")
                Spacer()
                LeziText(OfflineSearchCopy.title, role: .title)
                Spacer()
            }
            VStack(alignment: .leading, spacing: 8) {
                LeziText(OfflineSearchCopy.fieldLabel, role: .label)
                HStack {
                    Image(systemName: "magnifyingglass").accessibilityHidden(true)
                    TextField(OfflineSearchCopy.placeholder, text: Binding(
                        get: { model.state.query }, set: { model.setQuery($0) }))
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .focused($queryFocused)
                        .submitLabel(.search)
                        .onSubmit { queryFocused = false }
                        .accessibilityLabel(OfflineSearchCopy.fieldLabel)
                        .accessibilityIdentifier("offline.search.field")
                    if !model.state.query.isEmpty {
                        Button { model.setQuery("") } label: { Image(systemName: "xmark.circle.fill") }
                            .frame(minWidth: theme.primaryTouchMinimum, minHeight: theme.primaryTouchMinimum)
                            .accessibilityLabel(OfflineSearchCopy.clear)
                            .accessibilityIdentifier("offline.search.clear")
                    }
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                    switch model.state.phase {
                    case .prompt:
                        status(OfflineSearchCopy.promptTitle, message: OfflineSearchCopy.promptMessage)
                    case .loading:
                        status(OfflineSearchCopy.loadingTitle, message: OfflineSearchCopy.loadingMessage)
                        ProgressView().accessibilityLabel(OfflineSearchCopy.loadingMessage)
                    case .error:
                        status(OfflineSearchCopy.errorTitle, message: OfflineSearchCopy.errorMessage)
                        retryButton
                    case .empty:
                        status(OfflineSearchCopy.emptyTitle, message: OfflineSearchCopy.emptyMessage(model.state.query))
                    case .results:
                        results
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, theme.density.sectionGap)
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .padding(theme.pageInset)
        // The host hides its own navigation bar. This page owns an explicit Back
        // control rather than depending on inherited toolbar visibility.
        .toolbar(.hidden, for: .navigationBar)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.activate() } }
        .sheet(item: Binding(get: { model.readOnlyDetail }, set: { if $0 == nil { model.closeDetail() } })) { detail in
            OfflineSearchReadOnlyView(detail: detail, onClose: model.closeDetail)
        }
    }
    private func status(_ title: String, message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            LeziText(title, role: .titleSmall)
            LeziText(message)
        }
    }
    private var retryButton: some View {
        Button(model.state.isSearching ? OfflineSearchCopy.retrying : OfflineSearchCopy.retry) { model.retry() }
            .disabled(model.state.isSearching)
            .frame(minWidth: theme.primaryTouchMinimum, minHeight: theme.primaryTouchMinimum)
            .accessibilityIdentifier("offline.search.retry")
    }
    private var results: some View {
        VStack(alignment: .leading, spacing: theme.density.sectionGap) {
            if model.state.isReadOnly && model.state.hasFailed {
                LeziText(OfflineSearchCopy.stale).accessibilityIdentifier("offline.search.stale")
                retryButton
            } else if model.state.isSearching {
                ProgressView(OfflineSearchCopy.loadingMessage)
            }
            LazyVStack(alignment: .leading, spacing: 8) {
                ForEach(model.state.hits, id: \.record.clientUUID) { hit in
                    Button {
                        queryFocused = false
                        Task {
                            if let open = await model.requestOpenRecord(hit) {
                                onOpenRecord(open.hit, open.revision)
                            }
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            LeziText(hit.content.label, role: .titleSmall)
                                .accessibilityIdentifier("offline.search.result.title")
                            Text(Date(timeIntervalSince1970: Double(hit.record.timestampMillis) / 1000),
                                 format: .dateTime.year().month().day().hour().minute())
                                .font(.caption)
                            if !hit.content.visibleText.isEmpty {
                                LeziText(hit.content.visibleText)
                                    .accessibilityIdentifier("offline.search.result.text")
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: theme.primaryTouchMinimum, alignment: .leading)
                        .padding(theme.density.cardPad)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(actionLabel(hit))
                    .accessibilityIdentifier("offline.search.row.\(hit.record.clientUUID.uuidString)")
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("offline.search.rows")
            if model.state.nextCursor != nil {
                Button("加载更多") { model.loadMore() }
                    .disabled(!model.state.canLoadMore)
                    .frame(minWidth: theme.primaryTouchMinimum, minHeight: theme.primaryTouchMinimum)
                    .accessibilityIdentifier("offline.search.loadMore")
            }
        }
    }
    private func actionLabel(_ hit: OfflineSearchHit) -> String {
        let editing = !model.state.isReadOnly && !model.state.isSearching && OfflineSearchState.supportsEditing(hit)
        let action = (editing ? "编辑" : "查看") + hit.content.label
        return hit.content.visibleText.isEmpty ? action : action + "，" + hit.content.visibleText
    }
}

@MainActor
private struct OfflineSearchReadOnlyView: View {
    @Environment(\.leziTheme) private var theme
    let detail: OfflineSearchReadOnlyDetail
    let onClose: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: theme.density.sectionGap) {
            HStack {
                LeziText(detail.hit.content.label, role: .title)
                Spacer()
                Button("关闭", action: onClose)
                    .frame(minWidth: theme.primaryTouchMinimum, minHeight: theme.primaryTouchMinimum)
                    .accessibilityIdentifier("offline.search.detail.close")
            }
            ScrollView {
                VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                    LeziText("仅供查看", role: .titleSmall)
                        .accessibilityIdentifier("offline.search.readonly")
                    LeziText(detail.reason == .stale ? OfflineSearchCopy.stale : "此记录暂不支持安全编辑，原始内容已保留。")
                    Text(Date(timeIntervalSince1970: Double(detail.hit.record.timestampMillis) / 1000),
                         format: .dateTime.year().month().day().hour().minute())
                    LeziText(detail.hit.content.visibleText)
                        .accessibilityIdentifier("offline.search.detail.text")
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }.padding(theme.pageInset)
    }
}
#endif
