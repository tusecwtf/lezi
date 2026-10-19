#if canImport(SwiftUI)
import SwiftUI
import LeziStorage

/// Embed in an Apple host with its real file-backed OfflineStore and LeziTheme.
/// Development slice only: no networking, membership, other record kinds, media, or app navigation.
@MainActor
public struct OfflinePeeView: View {
    @Environment(\.leziTheme) private var theme
    @StateObject private var model: OfflinePeeModel
    @State private var nickname = ""
    @State private var birthday = Date()
    @State private var creatingBaby = false

    public init(store: OfflineStore) { _model = StateObject(wrappedValue: OfflinePeeModel(store: store)) }

    public var body: some View {
        VStack(alignment: .leading, spacing: theme.density.sectionGap) {
            LeziText("离线开发组件 · 宝宝与尿记录", role: .title)
            LeziText("仅保存在此设备。当前仅支持宝宝昵称、生日和无照片尿记录。", role: .label)
            if let message = model.message { LeziText(message).accessibilityIdentifier("offline.result") }
            babySelection
            ScrollView {
                VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                        Button(creatingBaby ? "收起新建宝宝" : "新建宝宝") { creatingBaby.toggle() }
                            .accessibilityIdentifier("offline.baby.new")
                        if creatingBaby { babyForm }
                        Button("记录尿") { model.openEditor() }
                            .accessibilityIdentifier("offline.pee.new")
                            .disabled(model.selected == nil || model.busy)
                            .frame(minHeight: theme.primaryTouchMinimum)
                        recordList
                    }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(theme.pageInset)
        .sheet(isPresented: Binding(get: { model.editor != nil }, set: { if !$0 { model.closeEditor() } })) {
            if let editor = model.editor {
                VStack(spacing: 0) {
                    HStack {
                        Spacer()
                        Button("关闭") { model.closeEditor() }
                            .disabled(model.busy || editor.isBusy)
                            .accessibilityIdentifier("offline.editor.close")
                    }
                    .padding(theme.pageInset)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("offline.editor.dragRegion")
                    confirmation(editor)
                }
                // Native SwiftUI cannot turn an attempted swipe into a custom confirmation.
                // Dirty/busy swipes are disabled; explicit close/cancel use the same decision gate.
                .interactiveDismissDisabled(editor.isDirty || editor.isBusy || model.busy)
                .alert("放弃未保存的修改？", isPresented: $model.showingDiscardConfirmation) {
                    Button("继续编辑", role: .cancel) { model.cancelDiscard() }
                        .accessibilityIdentifier("offline.discard.keep")
                    Button("放弃修改", role: .destructive) { model.discardChanges() }
                        .accessibilityIdentifier("offline.discard.confirm")
                } message: { Text("这些修改尚未写入本机。") }
                .alert("删除这条尿记录？", isPresented: $model.showingDeleteConfirmation) {
                    Button("取消", role: .cancel) { model.cancelDelete() }
                        .accessibilityIdentifier("offline.delete.cancel")
                    Button("确认删除", role: .destructive) { model.confirmDelete() }
                        .accessibilityIdentifier("offline.delete.confirm")
                } message: { Text("确认后将从本机记录列表移除；取消不会改动记录。") }
            }
        }
        .task { model.refresh() }
    }
    private var babySelection: some View {
        VStack(alignment: .leading) {
            Picker("当前宝宝", selection: Binding<UUID?>(
                get: { model.selected?.babyClientUUID },
                set: { if let id = $0 { model.select(id) } }
            )) {
                Text("请选择宝宝").tag(nil as UUID?)
                ForEach(model.babies, id: \.clientUUID) { baby in
                    Text(baby.nickname).tag(Optional(baby.clientUUID))
                }
            }.disabled(model.busy || model.editor?.isBusy == true)
                .accessibilityIdentifier("offline.baby.selection")
            if model.busy { ProgressView("正在处理本机数据") }
            if let editor = model.editor, editor.draft.babyClientUUID != model.selected?.babyClientUUID {
                LeziText("此草稿仍属于\(editor.session.context.babyName)，切换宝宝不会改变保存对象。", role: .label)
            }
        }
    }
    private var babyForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("昵称（留空为年年）", text: $nickname)
                .accessibilityIdentifier("offline.baby.nickname")
            LeziText("最多 20 个 Unicode 字符；前后空白会去除，同名宝宝不能重复创建。", role: .label)
            DatePicker("生日", selection: $birthday, displayedComponents: .date)
            Button("确认创建并选择宝宝") {
                let formatter = DateFormatter()
                formatter.calendar = Calendar(identifier: .gregorian)
                formatter.locale = Locale(identifier: "en_US_POSIX")
                formatter.timeZone = .current; formatter.dateFormat = "yyyy-MM-dd"
                model.createBaby(nickname: nickname, birthday: formatter.string(from: birthday))
            }.frame(minHeight: theme.primaryTouchMinimum)
                .accessibilityIdentifier("offline.baby.create")
        }.disabled(model.busy)
    }
    private var recordList: some View {
        VStack(alignment: .leading, spacing: 12) {
            LeziText("本机记录（当前仅支持编辑尿记录）", role: .titleSmall)
            if model.records.isEmpty {
                LeziText(model.selected == nil ? "创建或选择宝宝后查看记录" : "尚无本机记录")
                    .accessibilityIdentifier("offline.records.empty")
            }
            ForEach(model.records, id: \.clientUUID) { record in
                VStack(alignment: .leading, spacing: 4) {
                    Text(Date(timeIntervalSince1970: Double(record.timestampMillis) / 1000), format: .dateTime)
                    if case let .pee(amount)? = record.payloadDocument?.payload {
                        LeziText("尿量：\(Self.amountLabel(amount))")
                            .accessibilityIdentifier("offline.record.amount")
                    } else {
                        LeziText(record.isOpaque ? "暂不支持的记录格式 · 只读" : "其他记录类型 · 只读")
                    }
                    if let note = record.note, !note.isEmpty {
                        LeziText(note).accessibilityIdentifier("offline.record.note")
                    }
                    if OfflinePeeState.canEdit(record) {
                        Button("编辑尿记录") { model.openEditor(record: record) }
                            .disabled(model.busy)
                            .accessibilityIdentifier("offline.record.edit")
                    } else {
                        LeziText("原始内容已保留，当前组件不提供修改或删除。", role: .label)
                    }
                }
                .padding(theme.density.cardPad)
                .frame(maxWidth: .infinity, alignment: .leading)
                Divider()
            }
            if model.nextCursor != nil {
                Button("加载更早的记录") { model.loadMore() }.disabled(model.busy)
            }
            Button("重新读取本机记录") { model.refresh() }.disabled(model.busy)
                .accessibilityIdentifier("offline.records.reload")
        }
    }
    private func confirmation(_ editor: OfflinePeeState) -> some View {
        LeziConfirmationPanel(
            session: Binding(get: { model.editor?.session ?? editor.session },
                             set: { model.editor?.session = $0 }),
            draft: editor.draft.confirmation, title: editor.isEditing ? "编辑尿记录 · 本机" : "记录尿 · 本机",
            confirmTitle: editor.isEditing ? "保存修改" : "确认保存到本机",
            canWrite: true, externallyBusy: model.busy || editor.deletionAttemptID != nil,
            preventDismiss: editor.isDirty, nowMillis: { Int64(Date().timeIntervalSince1970 * 1000) },
            onConfirm: model.persist, onCancel: model.closeEditor, onFocusIssue: { _ in }
        ) { _ in
            VStack(alignment: .leading, spacing: 16) {
                DatePicker("记录时间", selection: Binding(
                    get: { Date(timeIntervalSince1970: Double(model.editor?.draft.timestampMillis ?? editor.draft.timestampMillis) / 1000) },
                    set: { model.editor?.edit(timestampMillis: Int64($0.timeIntervalSince1970 * 1000)) }
                ))
                Picker("尿量", selection: Binding(
                    get: { model.editor?.draft.amount ?? editor.draft.amount },
                    set: { model.editor?.edit(amount: $0) }
                )) { ForEach(1...3, id: \.self) { Text(Self.amountLabel($0)).tag($0).accessibilityIdentifier("offline.pee.amount.\($0)") } }
                .accessibilityIdentifier("offline.pee.amount")
                TextField("备注", text: Binding(
                    get: { model.editor?.draft.note ?? editor.draft.note },
                    set: { model.editor?.edit(note: $0) }
                ), axis: .vertical)
                .accessibilityIdentifier("offline.pee.note")
                LeziText("按确认后才写入本机；有未保存修改时，请用取消或关闭确认放弃。", role: .label)
                if let failure = editor.deletionFailure {
                    LeziText(failure).accessibilityIdentifier("offline.delete.error")
                }
                if editor.deletionAttemptID != nil { ProgressView("正在删除") }
                if editor.isEditing {
                    Button("删除记录", role: .destructive) { model.requestDelete() }
                        .accessibilityIdentifier("offline.delete.request")
                }
            }
        }
    }
    private static func amountLabel(_ amount: Int) -> String {
        switch amount { case 1: "小"; case 2: "中"; case 3: "大"; default: "未知" }
    }
}
#endif
