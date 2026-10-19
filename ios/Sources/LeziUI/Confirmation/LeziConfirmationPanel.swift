#if canImport(SwiftUI)
import SwiftUI
import LeziCore

/// Scrollable real editor plus fixed full-width explicit confirmation. Does not implement storage.
public struct LeziConfirmationPanel<Editor: View>: View {
    @Environment(\.leziTheme) private var theme
    @Environment(\.colorScheme) private var colorScheme
    @Binding private var session: LeziConfirmationSession
    private let draft: RecordConfirmationDraft
    private let title: String
    private let confirmTitle: String
    private let canWrite: Bool
    private let externallyBusy: Bool
    private let featureIssue: LeziConfirmationIssue?
    private let nowMillis: () -> Int64
    private let onConfirm: (LeziConfirmedRecord) -> Void
    private let onCancel: () -> Void
    private let onFocusIssue: (LeziConfirmationIssue) -> Void
    private let editor: (Bool) -> Editor

    public init(session: Binding<LeziConfirmationSession>, draft: RecordConfirmationDraft,
                title: String, confirmTitle: String = "保存", canWrite: Bool, externallyBusy: Bool = false,
                featureIssue: LeziConfirmationIssue? = nil,
                nowMillis: @escaping () -> Int64,
                onConfirm: @escaping (LeziConfirmedRecord) -> Void,
                onCancel: @escaping () -> Void,
                onFocusIssue: @escaping (LeziConfirmationIssue) -> Void,
                @ViewBuilder editor: @escaping (Bool) -> Editor) {
        _session = session; self.draft = draft; self.title = title; self.confirmTitle = confirmTitle
        self.canWrite = canWrite; self.externallyBusy = externallyBusy; self.featureIssue = featureIssue
        self.nowMillis = nowMillis; self.onConfirm = onConfirm; self.onCancel = onCancel
        self.onFocusIssue = onFocusIssue; self.editor = editor
    }
    private var busy: Bool { externallyBusy || session.isSaving }
    private var editingDisabled: Bool { busy || !canWrite || !session.canInteract }
    private var palette: LeziPalette {
        .resolve(style: theme.style, dark: colorScheme == .dark, babyAccent: theme.babyAccent, elder: theme.elder)
    }
    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                LeziText(title, role: .title).accessibilityAddTraits(.isHeader)
                LeziText(session.context.babyName, role: .titleSmall)
                if !canWrite { LeziText("仅查看", role: .label) }
                editor(editingDisabled).disabled(editingDisabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(theme.density.panelContent)
        }
        .background(palette.background.color)
        .safeAreaInset(edge: .bottom, spacing: 0) { footer }
        .interactiveDismissDisabled(busy)
        .onChange(of: draft) { _, _ in session.draftEdited() }
        .onChange(of: featureIssue) { _, _ in session.clearExplanation() }
        .onChange(of: externallyBusy) { _, busy in if busy { session.clearExplanation() } }
        .onChange(of: canWrite) { _, writable in if !writable { session.clearExplanation() } }
    }
    private var footer: some View {
        VStack(spacing: 8) {
            if let issue = session.shownIssue {
                LeziText(issue.message)
                    .padding(theme.density.cardPad)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surface.color, in: RoundedRectangle(cornerRadius: theme.cardRadius))
                    .accessibilityIdentifier("confirmation.reason")
            }
            if let failure = session.saveFailure {
                LeziText(failure).foregroundStyle(palette.danger.color)
            }
            if canWrite && session.phase != .completed && session.phase != .invalidated {
                confirmButton
            }
            Button(action: { if !busy { onCancel() } }) {
                LeziText(canWrite ? "取消" : "关闭", role: .titleSmall)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .disabled(busy)
        }
        .padding(theme.density.panelContent)
        .background(palette.surface.color)
    }
    private var confirmButton: some View {
        let issue = session.issue(for: draft, nowMillis: nowMillis(), featureIssue: featureIssue)
        let explainedDisabled = issue != nil
        return Button(action: confirm) {
            HStack {
                if busy { ProgressView().accessibilityHidden(true) }
                LeziText(busy ? (session.isSaving ? "正在保存" : "正在处理") : confirmTitle, role: .titleSmall)
            }
            .frame(maxWidth: .infinity, minHeight: theme.primaryTouchMinimum)
            .foregroundStyle(explainedDisabled ? palette.muted.color : palette.onPrimary.color)
            .background(explainedDisabled ? palette.container.color : palette.primary.color,
                        in: RoundedRectangle(cornerRadius: theme.buttonRadius))
            .overlay {
                if explainedDisabled {
                    RoundedRectangle(cornerRadius: theme.buttonRadius).stroke(palette.border.color, lineWidth: 1)
                }
            }
        }
        .buttonStyle(.plain)
        // Invalid input stays actionable to reveal a concrete reason; busy is truly disabled.
        .disabled(busy || !session.canInteract)
        .accessibilityHint(issue?.message ?? "")
        .accessibilityIdentifier("confirmation.save")
    }
    private func confirm() {
        switch session.confirm(draft: draft, nowMillis: nowMillis(), canWrite: canWrite,
                               externallyBusy: externallyBusy, featureIssue: featureIssue) {
        case .ignored: break
        case .explanation(let issue): if let issue { onFocusIssue(issue) }
        case .confirmed(let frozen): onConfirm(frozen)
        }
    }
}
#endif
