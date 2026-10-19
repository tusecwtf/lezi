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
            if let editor = model.editor {
                confirmation(editor)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                        Button(creatingBaby ? "收起新建宝宝" : "新建宝宝") { creatingBaby.toggle() }
                        if creatingBaby { babyForm }
                        Button("记录尿") { model.openEditor() }
                            .disabled(model.selected == nil || model.busy)
                            .frame(minHeight: theme.primaryTouchMinimum)
                        recordList
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .padding(theme.pageInset)
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
            }.disabled(model.busy || model.editor?.session.isSaving == true)
            if model.busy { ProgressView("正在处理本机数据") }
            if let editor = model.editor, editor.draft.babyClientUUID != model.selected?.babyClientUUID {
                LeziText("此草稿仍属于\(editor.session.context.babyName)，切换宝宝不会改变保存对象。", role: .label)
            }
        }
    }
    private var babyForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("昵称（留空为年年）", text: $nickname)
            LeziText("最多 20 个 Unicode 字符；前后空白会去除，同名宝宝不能重复创建。", role: .label)
            DatePicker("生日", selection: $birthday, displayedComponents: .date)
            Button("确认创建并选择宝宝") {
                let formatter = DateFormatter()
                formatter.calendar = Calendar(identifier: .gregorian)
                formatter.locale = Locale(identifier: "en_US_POSIX")
                formatter.timeZone = .current; formatter.dateFormat = "yyyy-MM-dd"
                model.createBaby(nickname: nickname, birthday: formatter.string(from: birthday))
            }.frame(minHeight: theme.primaryTouchMinimum)
        }.disabled(model.busy)
    }
    private var recordList: some View {
        VStack(alignment: .leading, spacing: 12) {
            LeziText("本机尿记录（只读）", role: .titleSmall)
            if model.records.isEmpty { LeziText(model.selected == nil ? "创建或选择宝宝后查看记录" : "尚无本机尿记录") }
            ForEach(model.records, id: \.clientUUID) { record in
                VStack(alignment: .leading, spacing: 4) {
                    Text(Date(timeIntervalSince1970: Double(record.timestampMillis) / 1000), format: .dateTime)
                    LeziText("尿量：\(Self.amountLabel(record.amount))")
                    if let note = record.note, !note.isEmpty { LeziText(note) }
                }
                .padding(theme.density.cardPad)
                .frame(maxWidth: .infinity, alignment: .leading)
                Divider()
            }
            if model.nextCursor != nil {
                Button("加载更早的记录") { model.loadMore() }.disabled(model.busy)
            }
            Button("重新读取本机记录") { model.refresh() }.disabled(model.busy)
        }
    }
    private func confirmation(_ editor: OfflinePeeState) -> some View {
        LeziConfirmationPanel(
            session: Binding(get: { model.editor?.session ?? editor.session },
                             set: { model.editor?.session = $0 }),
            draft: editor.draft.confirmation, title: "记录尿 · 本机", confirmTitle: "确认保存到本机",
            canWrite: true, externallyBusy: model.busy, nowMillis: { Int64(Date().timeIntervalSince1970 * 1000) },
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
                )) { ForEach(1...3, id: \.self) { Text(Self.amountLabel($0)).tag($0) } }
                TextField("备注", text: Binding(
                    get: { model.editor?.draft.note ?? editor.draft.note },
                    set: { model.editor?.edit(note: $0) }
                ), axis: .vertical)
                LeziText("按确认后才写入本机；取消会放弃此草稿。", role: .label)
            }
        }
    }
    private static func amountLabel(_ amount: Int) -> String {
        switch amount { case 1: "小"; case 2: "中"; case 3: "大"; default: "未知" }
    }
}
#endif
