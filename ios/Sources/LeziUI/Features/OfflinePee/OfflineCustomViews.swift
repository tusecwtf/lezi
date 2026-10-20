#if canImport(SwiftUI)
import SwiftUI
import LeziStorage

enum OfflineCustomIcons {
    static let names = ["星星", "爱心", "太阳", "月亮", "音符", "圆形", "三角形", "菱形"]
    static let symbols = ["star", "heart", "sun.max", "moon", "music.note", "circle", "triangle", "diamond"]
    static func symbol(_ slot: Int) -> String { symbols.indices.contains(slot) ? symbols[slot] : "questionmark" }
    static func label(_ slot: Int?) -> String { slot.flatMap { names.indices.contains($0) ? names[$0] : nil } ?? "未指定" }
}

struct OfflineCustomCatalogView: View {
    @ObservedObject var model: OfflineCustomCatalogModel
    let canCreateRecord: Bool
    let onCreateRecord: (OfflineCustomDefinition) -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                LeziText("本机自定义项目", role: .title)
                Spacer()
                Button("关闭") { model.close() }.accessibilityIdentifier("offline.catalog.close")
            }
            LeziText("此目录适用于本机所有宝宝。改名或换图标不改历史记录；不含家庭共享或同步。", role: .label)
            if let message = model.message { LeziText(message).accessibilityIdentifier("offline.catalog.result") }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(model.definitions.enumerated()), id: \.element.clientUUID) { index, item in
                        VStack(alignment: .leading) {
                            Label(item.name, systemImage: OfflineCustomIcons.symbol(item.iconSlot))
                                .accessibilityIdentifier("offline.catalog.name")
                            LeziText("图标：\(OfflineCustomIcons.label(item.iconSlot))", role: .label)
                                .accessibilityIdentifier("offline.catalog.icon")
                            Button("用此项目新建记录") { onCreateRecord(item) }
                                .disabled(!canCreateRecord || model.draft != nil)
                                .accessibilityIdentifier("offline.catalog.record.new")
                            HStack {
                                Button("修改") { model.edit(item) }.accessibilityIdentifier("offline.catalog.edit")
                                Button("上移") { model.requestMove(item, offset: -1) }
                                    .disabled(index == 0).accessibilityIdentifier("offline.catalog.up")
                                Button("下移") { model.requestMove(item, offset: 1) }
                                    .disabled(index == model.definitions.count - 1).accessibilityIdentifier("offline.catalog.down")
                                Button("删除", role: .destructive) { model.requestDelete(item) }
                                    .accessibilityIdentifier("offline.catalog.delete")
                            }.disabled(model.draft != nil)
                        }.accessibilityElement(children: .contain)
                            .accessibilityIdentifier("offline.catalog.row.\(item.clientUUID.uuidString)")
                        Divider()
                    }
                    if let draft = model.draft {
                        TextField("项目名称", text: Binding(get: { model.draft?.name ?? draft.name }, set: { model.draft?.name = $0 }))
                            .accessibilityIdentifier("offline.catalog.editor.name")
                        LeziText("\(draft.name.utf16.count)/20", role: .label).accessibilityIdentifier("offline.catalog.editor.count")
                        Picker("选择图标", selection: Binding(get: { model.draft?.iconSlot ?? draft.iconSlot }, set: { model.draft?.iconSlot = $0 })) {
                            ForEach(0..<8, id: \.self) { slot in
                                Label(OfflineCustomIcons.names[slot], systemImage: OfflineCustomIcons.symbols[slot])
                                    .tag(slot).accessibilityIdentifier("offline.catalog.editor.icon.\(slot)")
                            }
                        }.accessibilityIdentifier("offline.catalog.editor.icon")
                        Button("确认保存项目") { model.save() }.accessibilityIdentifier("offline.catalog.save")
                        Button("取消修改") { model.cancelEdit() }.accessibilityIdentifier("offline.catalog.cancel")
                    } else {
                        Button("添加项目") { model.create() }.disabled(model.definitions.count >= 10)
                            .accessibilityIdentifier("offline.catalog.add")
                    }
                }
            }
        }.padding().disabled(model.busy)
            .interactiveDismissDisabled(model.busy || model.draft?.isDirty == true)
            .alert("放弃目录草稿？", isPresented: $model.discardRequested) {
                Button("继续编辑", role: .cancel) { model.cancelDiscard() }.accessibilityIdentifier("offline.catalog.discard.keep")
                Button("放弃修改", role: .destructive) { model.discard() }.accessibilityIdentifier("offline.catalog.discard.confirm")
            }
            .alert("删除本机自定义项目？", isPresented: $model.showingDelete) {
                Button("取消", role: .cancel) { model.deleteTarget = nil }.accessibilityIdentifier("offline.catalog.delete.cancel")
                Button("确认删除", role: .destructive) { model.confirmDelete() }.accessibilityIdentifier("offline.catalog.delete.confirm")
            } message: { Text("删除后不能用此定义新建记录；已有记录保留原名称和图标，仍可编辑。") }
            .alert("保存新的本机项目顺序？", isPresented: $model.showingMove) {
                Button("取消", role: .cancel) { model.reorderTarget = nil }.accessibilityIdentifier("offline.catalog.move.cancel")
                Button("确认排序") { model.confirmMove() }.accessibilityIdentifier("offline.catalog.move.confirm")
            }
    }
}

struct OfflineCustomRecordFields: View {
    @Binding var draft: OfflineCustomDraft
    let focus: FocusState<String?>.Binding
    var body: some View {
        TextField("项目名称", text: $draft.title).focused(focus, equals: "custom.title")
            .accessibilityIdentifier("offline.custom.title")
        LeziText("\(draft.title.utf16.count)/30 · 本条记录的名称快照", role: .label)
        TextField("详情（可选）", text: $draft.detail, axis: .vertical).lineLimit(2...6)
            .focused(focus, equals: "custom.detail").accessibilityIdentifier("offline.custom.detail")
        LeziText("\(draft.detail.utf16.count)/200", role: .label)
        LeziText("图标快照：\(OfflineCustomIcons.label(draft.iconSlot))", role: .label)
            .accessibilityIdentifier("offline.custom.icon")
        LeziText("项目关联不可更改；修改这些字段不修改目录定义。", role: .label)
    }
}
#endif
