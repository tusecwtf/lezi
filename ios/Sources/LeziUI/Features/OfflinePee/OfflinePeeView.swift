#if canImport(SwiftUI)
import SwiftUI
import LeziCore
import LeziStorage

/// Embed in an Apple host with its real file-backed OfflineStore and LeziTheme.
/// Development host only: no family networking, media, or complete product navigation.
@MainActor
public struct OfflinePeeView: View {
    @Environment(\.leziTheme) private var theme
    @StateObject private var model: OfflinePeeModel
    @StateObject private var catalog: OfflineCustomCatalogModel
    @State private var nickname = ""
    @State private var birthday = Date()
    @State private var creatingBaby = false
    @AppStorage("lezi.offline.milkAmountStepMl") private var storedMilkStep = 5
    @AppStorage("lezi.offline.infantFeverAdviceEnabled") private var infantFeverAdviceEnabled = true
    @FocusState private var focusedField: String?

    public init(store: OfflineStore) {
        _model = StateObject(wrappedValue: OfflinePeeModel(store: store))
        _catalog = StateObject(wrappedValue: OfflineCustomCatalogModel(store: store))
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: theme.density.sectionGap) {
            LeziText("离线开发组件 · 宝宝与本机护理记录", role: .title)
            LeziText("仅保存在此设备。支持无照片排泄、日常、症状、用药、就医、疫苗、成长测量、手动母乳、奶量、体温与辅食/点心/饮料记录；护理计划尚未开放。", role: .label)
            if let message = model.message { LeziText(message).accessibilityIdentifier("offline.result") }
            babySelection
            ScrollView {
                VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                        Button(creatingBaby ? "收起新建宝宝" : "新建宝宝") { creatingBaby.toggle() }
                            .accessibilityIdentifier("offline.baby.new")
                        if creatingBaby { babyForm }
                        Button("新建记录") { model.openEditor(milkStep: storedMilkStep) }
                            .accessibilityIdentifier("offline.pee.new")
                            .disabled(model.selected == nil || model.busy)
                            .frame(minHeight: theme.primaryTouchMinimum)
                        Button("本机自定义项目") { catalog.open() }
                            .disabled(model.busy).accessibilityIdentifier("offline.catalog.open")
                        recordList
                    }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(theme.pageInset)
        .sheet(isPresented: Binding(get: { model.editor != nil || catalog.isPresented }, set: { if !$0 { if model.editor != nil { model.closeEditor() } else { catalog.close() } } })) {
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
                .alert("删除这条\(model.editor?.draft.label ?? "记录")？", isPresented: $model.showingDeleteConfirmation) {
                    Button("取消", role: .cancel) { model.cancelDelete() }
                        .accessibilityIdentifier("offline.delete.cancel")
                    Button("确认删除", role: .destructive) { model.confirmDelete() }
                        .accessibilityIdentifier("offline.delete.confirm")
                } message: { Text("确认后将从本机记录列表移除；取消不会改动记录。") }
            } else if catalog.isPresented {
                OfflineCustomCatalogView(model: catalog, canCreateRecord: model.selected != nil && !model.busy) { definition in
                    model.openEditor(definition: definition)
                    if model.editor != nil { catalog.isPresented = false }
                }
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
            LeziText("本机记录（当前支持排泄、日常、健康、成长、手动母乳、奶量、体温与辅食/点心/饮料记录）", role: .titleSmall)
            if model.records.isEmpty {
                LeziText(model.selected == nil ? "创建或选择宝宝后查看记录" : "尚无本机记录")
                    .accessibilityIdentifier("offline.records.empty")
            }
            ForEach(model.records, id: \.clientUUID) { record in
                VStack(alignment: .leading, spacing: 4) {
                    Text(Date(timeIntervalSince1970: Double(record.timestampMillis) / 1000), format: .dateTime)
                    recordSummary(record)
                    if let note = record.note, !note.isEmpty {
                        LeziText(note).accessibilityIdentifier("offline.record.note")
                    }
                    if OfflinePeeState.canEdit(record) {
                        Button("编辑\(OfflineRecordKind(rawValue: record.rawType)?.label ?? "记录")") { model.openEditor(record: record, milkStep: storedMilkStep) }
                            .disabled(model.busy)
                            .accessibilityIdentifier("offline.record.edit")
                    } else {
                        LeziText("原始内容已保留，当前组件不提供修改或删除。", role: .label)
                    }
                }
                .padding(theme.density.cardPad)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("offline.record.row.\(record.clientUUID.uuidString)")
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
            draft: editor.draft.confirmation, title: "\(editor.isEditing ? "编辑" : "新建")\(editor.draft.label) · 本机",
            confirmTitle: editor.isEditing ? "保存修改" : "确认保存到本机",
            canWrite: true, externallyBusy: model.busy || editor.deletionAttemptID != nil,
            preventDismiss: editor.isDirty,
            featureIssue: editor.featureIssue(nowMillis: Int64(Date().timeIntervalSince1970 * 1000)), nowMillis: { Int64(Date().timeIntervalSince1970 * 1000) },
            onConfirm: model.persist, onCancel: model.closeEditor, onFocusIssue: { focusedField = $0.fieldID }
        ) { _ in
            VStack(alignment: .leading, spacing: 16) {
                DatePicker("记录时间", selection: Binding(
                    get: { Date(timeIntervalSince1970: Double(model.editor?.draft.timestampMillis ?? editor.draft.timestampMillis) / 1000) },
                    set: { model.editor?.edit(timestampMillis: Int64($0.timeIntervalSince1970 * 1000)) }
                ))
                if editor.isEditing || editor.draft.kind == nil {
                    LeziText("类型：\(editor.draft.label)（不可更改）", role: .label)
                        .accessibilityIdentifier("offline.excretion.kind.fixed")
                } else {
                    Picker("记录类型", selection: Binding(
                        get: { model.editor?.draft.kind ?? editor.draft.kind ?? .pee },
                        set: { model.editor?.edit(kind: $0) }
                    )) {
                        ForEach(OfflineRecordKind.allCases, id: \.self) { kind in
                            Text(kind.label).tag(kind).accessibilityIdentifier("offline.excretion.kind.\(kind.rawValue)")
                        }
                    }.accessibilityIdentifier("offline.excretion.kind")
                }
                excretionFields(editor)
                if let kind = editor.draft.kind?.milkKind {
                    OfflineMilkFields(kind: kind, draft: Binding(
                        get: { model.editor?.draft.milk ?? editor.draft.milk },
                        set: { model.editor?.edit(milk: $0) }
                    ), step: Binding(
                        get: { model.editor?.milkStep ?? editor.milkStep },
                        set: { value in
                            guard model.editor?.isBusy == false, !model.busy else { return }
                            model.editor?.setMilkStep(value)
                            storedMilkStep = OfflineMilkDraft.step(value)
                        }
                    ), focus: $focusedField)
                }
                if editor.draft.kind == .nursing {
                    OfflineNursingFields(draft: Binding(
                        get: { model.editor?.draft.nursing ?? editor.draft.nursing },
                        set: { model.editor?.edit(nursing: $0) }
                    ), focus: $focusedField)
                }
                if let kind = editor.draft.kind?.foodKind {
                    OfflineFoodFields(kind: kind, draft: Binding(
                        get: { model.editor?.draft.food ?? editor.draft.food },
                        set: { model.editor?.edit(food: $0) }
                    ), birthday: editor.babyBirthday, timestampMillis: editor.draft.timestampMillis, focus: $focusedField)
                }
                if editor.draft.kind == .temperature {
                    OfflineTemperatureFields(draft: Binding(
                        get: { model.editor?.draft.temperature ?? editor.draft.temperature },
                        set: { model.editor?.edit(temperature: $0) }
                    ), adviceEnabled: Binding(
                        get: { infantFeverAdviceEnabled },
                        set: { value in
                            guard model.editor?.isBusy == false, !model.busy else { return }
                            infantFeverAdviceEnabled = value
                        }
                    ), birthday: editor.babyBirthday, timestampMillis: editor.draft.timestampMillis, focus: $focusedField)
                }
                if let kind = editor.draft.kind {
                OfflineGrowthFields(kind: kind, draft: Binding(
                    get: { model.editor?.draft.growth ?? editor.draft.growth },
                    set: { model.editor?.edit(growth: $0) }
                ), focus: $focusedField)
                OfflineHealthFields(kind: kind, draft: Binding(
                    get: { model.editor?.draft.health ?? editor.draft.health },
                    set: { model.editor?.edit(health: $0) }
                ), focus: $focusedField)
                }
                if case let .custom(definitionID) = editor.draft.target {
                    LeziText("具体项目关联已固定", role: .label)
                        .accessibilityIdentifier("offline.custom.definition")
                        .accessibilityValue(definitionID.uuidString)
                    OfflineCustomRecordFields(draft: Binding(get: { model.editor?.draft.custom ?? editor.draft.custom }, set: { model.editor?.edit(custom: $0) }), focus: $focusedField)
                }
                if editor.draft.kind == .diary {
                    TextField("日记正文", text: Binding(
                        get: { model.editor?.draft.body ?? editor.draft.body },
                        set: { model.editor?.edit(body: $0) }
                    ), axis: .vertical)
                    .lineLimit(4...7)
                    .focused($focusedField, equals: "body")
                    .accessibilityIdentifier("offline.diary.body")
                    LeziText("\(editor.draft.body.utf16.count)/800 · 正文必填", role: .label)
                }
                if editor.draft.kind == .bath || editor.draft.kind == .walk {
                    LeziText("单次记录 · 无持续时长", role: .label)
                        .accessibilityIdentifier("offline.editor.point")
                }
                TextField("备注", text: Binding(
                    get: { model.editor?.draft.note ?? editor.draft.note },
                    set: { model.editor?.edit(note: $0) }
                ), axis: .vertical)
                .accessibilityIdentifier("offline.pee.note")
                .focused($focusedField, equals: "note")
                LeziText("\(editor.draft.note.utf16.count)/200", role: .label)
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
    @ViewBuilder
    private func excretionFields(_ editor: OfflinePeeState) -> some View {
        if editor.draft.kind?.hasPee == true {
            choice("尿量", id: "offline.pee.amount", values: 1...3, label: OfflineExcretionLabels.pee,
                   selection: Binding(get: { model.editor?.draft.amount ?? editor.draft.amount },
                                      set: { model.editor?.edit(amount: $0) }))
        }
        if editor.draft.kind?.hasStool == true {
            choice("便量", id: "offline.stool.amount", values: 1...4, label: OfflineExcretionLabels.amount,
                   selection: Binding(get: { model.editor?.draft.stoolAmount ?? editor.draft.stoolAmount },
                                      set: { model.editor?.edit(stoolAmount: $0) }))
            choice("软硬", id: "offline.stool.consistency", values: 1...4, label: OfflineExcretionLabels.consistency,
                   selection: Binding(get: { model.editor?.draft.stoolConsistency ?? editor.draft.stoolConsistency },
                                      set: { model.editor?.edit(stoolConsistency: $0) }))
            choice("颜色", id: "offline.stool.color", values: 0...7, label: OfflineExcretionLabels.color,
                   selection: Binding(get: { model.editor?.draft.stoolColor ?? editor.draft.stoolColor },
                                      set: { model.editor?.edit(stoolColor: $0) }))
        }
    }
    private func choice(_ title: String, id: String, values: ClosedRange<Int>,
                        label: @escaping (Int) -> String, selection: Binding<Int>) -> some View {
        Picker(title, selection: selection) {
            ForEach(Array(values), id: \.self) { value in
                Text(label(value)).tag(value).accessibilityIdentifier("\(id).\(value)")
            }
        }.accessibilityIdentifier(id)
    }
    @ViewBuilder
    private func recordSummary(_ record: OfflineRecord) -> some View {
        if record.type == .custom, !record.isOpaque {
            LeziText("自定义记录", role: .titleSmall).accessibilityIdentifier("offline.record.kind")
            if let id = record.customDefinitionUUID {
                LeziText("具体项目关联已固定", role: .label).accessibilityIdentifier("offline.record.custom.definition")
                    .accessibilityValue(id.uuidString)
            }
        }
        if let kind = OfflineRecordKind(rawValue: record.rawType), !record.isOpaque {
            LeziText(kind.label, role: .titleSmall).accessibilityIdentifier("offline.record.kind")
        }
        switch record.payloadDocument?.payload {
        case let .pee(amount):
            LeziText("尿量：\(OfflineExcretionLabels.pee(amount))").accessibilityIdentifier("offline.record.amount")
        case let .stool(amount, consistency, color):
            stoolSummary(amount: amount, consistency: consistency, color: color)
        case let .bothDiaper(pee, amount, consistency, color):
            LeziText("尿量：\(OfflineExcretionLabels.pee(pee))").accessibilityIdentifier("offline.record.amount")
            stoolSummary(amount: amount, consistency: consistency, color: color)
        case let payload? where [.cough, .rash, .vomit, .injury, .medicine, .hospital, .vaccine].contains(payload.type):
            OfflineHealthSummary(payload: payload)
        case let .milk(_, amount, prepared, duration):
            OfflineMilkSummary(amount: amount, prepared: prepared, duration: duration)
        case let .custom(title, detail, icon):
            LeziText(title).accessibilityIdentifier("offline.record.custom.title")
            if let detail { LeziText(detail).accessibilityIdentifier("offline.record.custom.detail") }
            LeziText("图标快照：\(OfflineCustomIcons.label(icon))", role: .label).accessibilityIdentifier("offline.record.custom.icon")
        case let .nursing(left, right, order, amount, mode):
            OfflineNursingSummary(left: left, right: right, order: order, amount: amount, mode: mode)
        case let .food(_, content, amount):
            LeziText(content).accessibilityIdentifier("offline.record.food.content")
            LeziText(amount ?? "未填量").accessibilityIdentifier("offline.record.food.amount")
        case let .temperature(value):
            LeziText("\(OfflineGrowthDraft.number(value)) ℃")
                .accessibilityIdentifier("offline.record.temperature.value")
        case let .measurement(kind, value, unit):
            OfflineGrowthSummary(kind: kind, value: value, unit: unit)
        case let .diary(body):
            LeziText(body).accessibilityIdentifier("offline.record.body")
        case .bath, .walk:
            LeziText("单次记录 · 无持续时长").accessibilityIdentifier("offline.record.point")
        default:
            LeziText(record.isOpaque ? "暂不支持的记录格式 · 只读" : "其他记录类型 · 只读")
        }
    }
    private func stoolSummary(amount: Int, consistency: Int, color: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            LeziText("便量：\(OfflineExcretionLabels.amount(amount))").accessibilityIdentifier("offline.record.stool.amount")
            LeziText("软硬：\(OfflineExcretionLabels.consistency(consistency))").accessibilityIdentifier("offline.record.stool.consistency")
            LeziText("颜色：\(OfflineExcretionLabels.color(color))").accessibilityIdentifier("offline.record.stool.color")
        }
    }
}
#endif
