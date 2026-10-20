#if canImport(SwiftUI)
import SwiftUI

@MainActor
struct OfflineSleepEditor: View {
    @ObservedObject var model: OfflineSleepModel
    @Environment(\.leziTheme) private var theme
    @State private var endCandidate: Date?
    private func isWake(_ state: OfflineSleepState) -> Bool {
        if case .wake = state.action { return true }; return false
    }
    var body: some View {
        if let state = model.editor {
            VStack(spacing: theme.density.sectionGap) {
                HStack {
                    LeziText(state.title, role: .title)
                    Spacer()
                    Button("关闭") { model.close() }.accessibilityIdentifier("offline.sleep.close")
                }.padding(theme.pageInset)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("offline.sleep.dragRegion")
                ScrollView {
                    VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                        LeziText("保存对象：\(state.babyName)").accessibilityIdentifier("offline.sleep.baby")
                        if case let .wake(_, start) = state.action {
                            Text("睡下：\(Date(timeIntervalSince1970: Double(start) / 1000).formatted())")
                            LeziText("本次只添加独立醒来观察；不会修改睡下时间或睡眠备注。", role: .label)
                        }
                        DatePicker(isWake(state) ? "醒来时间" : "睡下时间", selection: Binding(
                            get: { Date(timeIntervalSince1970: Double(state.timestampMillis) / 1000) },
                            set: { model.edit(timestamp: Int64($0.timeIntervalSince1970 * 1000)) }
                        )).accessibilityIdentifier("offline.sleep.time")
                        if state.hasEnd {
                            if let end = state.endTimestampMillis {
                                DatePicker("醒来时间", selection: Binding(
                                    get: { Date(timeIntervalSince1970: Double(end) / 1000) },
                                    set: { model.edit(end: Int64($0.timeIntervalSince1970 * 1000)) }
                                )).accessibilityIdentifier("offline.sleep.end")
                                LeziText(OfflineSleepState.durationLabel(start: state.timestampMillis, end: end), role: .label)
                            } else {
                                if let candidate = endCandidate {
                                    DatePicker("选择醒来时刻", selection: Binding(
                                        get: { candidate }, set: { endCandidate = $0 }
                                    )).accessibilityIdentifier("offline.sleep.end.candidate")
                                    Button("确认醒来时刻") {
                                        if model.chooseEnd(Int64(candidate.timeIntervalSince1970 * 1000)) {
                                            endCandidate = nil
                                        }
                                    }.accessibilityIdentifier("offline.sleep.end.accept")
                                } else {
                                    Button("选择醒来时刻") {
                                        endCandidate = Date(timeIntervalSince1970: Double(state.timestampMillis) / 1000)
                                    }.accessibilityIdentifier("offline.sleep.end.choose")
                                }
                            }
                        }
                        if state.revision != nil {
                            LeziText("此处修改睡眠备注；醒来观察备注保持原样。", role: .label)
                        }
                        TextField("备注", text: Binding(get: { state.note }, set: { model.edit(note: $0) }), axis: .vertical)
                            .lineLimit(2...4).accessibilityIdentifier("offline.sleep.note")
                        LeziText("\(state.note.utf16.count)/200", role: .label)
                        if let error = state.error { LeziText(error).accessibilityIdentifier("offline.sleep.error") }
                    }.padding(theme.pageInset)
                }
                if state.isBusy { ProgressView("正在保存到本机") }
                if state.revision != nil {
                    Button("删除睡眠记录", role: .destructive) { model.requestDelete() }
                        .accessibilityIdentifier("offline.sleep.delete.request")
                }
                Button(state.confirmLabel) { model.confirm() }
                    .frame(minHeight: theme.primaryTouchMinimum).accessibilityIdentifier("offline.sleep.confirm")
                    .padding(theme.pageInset)
            }
            .disabled(state.isBusy)
            .interactiveDismissDisabled(state.isDirty || state.isBusy)
            .alert("放弃未保存的修改？", isPresented: $model.showingDiscard) {
                Button("继续编辑", role: .cancel) { model.cancelDiscard() }.accessibilityIdentifier("offline.sleep.discard.keep")
                Button("放弃修改", role: .destructive) { model.discard() }.accessibilityIdentifier("offline.sleep.discard.confirm")
            } message: { Text("这些修改尚未写入本机。") }
            .alert("删除这条睡眠记录？", isPresented: $model.showingDelete) {
                Button("取消", role: .cancel) { model.cancelDelete() }.accessibilityIdentifier("offline.sleep.delete.cancel")
                Button("确认删除", role: .destructive) { model.confirmDelete() }.accessibilityIdentifier("offline.sleep.delete.confirm")
            } message: { Text("睡眠记录将从本机列表移除，独立的醒来观察保留。未保存的修改不会写入。") }
        }
    }
}
#endif
