#if canImport(SwiftUI)
import SwiftUI

@MainActor
struct OfflineSleepEditor: View {
    @ObservedObject var model: OfflineSleepModel
    @Environment(\.leziTheme) private var theme
    var body: some View {
        if let state = model.editor {
            VStack(spacing: theme.density.sectionGap) {
                HStack {
                    LeziText(state.action == .start ? "睡下" : "醒来", role: .title)
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
                        DatePicker(state.action == .start ? "睡下时间" : "醒来时间", selection: Binding(
                            get: { Date(timeIntervalSince1970: Double(state.timestampMillis) / 1000) },
                            set: { model.edit(timestamp: Int64($0.timeIntervalSince1970 * 1000)) }
                        )).accessibilityIdentifier("offline.sleep.time")
                        TextField("备注", text: Binding(get: { state.note }, set: { model.edit(note: $0) }), axis: .vertical)
                            .lineLimit(2...4).accessibilityIdentifier("offline.sleep.note")
                        LeziText("\(state.note.utf16.count)/200", role: .label)
                        if let error = state.error { LeziText(error).accessibilityIdentifier("offline.sleep.error") }
                    }.padding(theme.pageInset)
                }
                if state.isBusy { ProgressView("正在保存到本机") }
                Button(state.action == .start ? "确认睡下" : "确认醒来") { model.confirm() }
                    .frame(minHeight: theme.primaryTouchMinimum).accessibilityIdentifier("offline.sleep.confirm")
                    .padding(theme.pageInset)
            }
            .disabled(state.isBusy)
            .interactiveDismissDisabled(state.isDirty || state.isBusy)
            .alert("放弃未保存的修改？", isPresented: $model.showingDiscard) {
                Button("继续编辑", role: .cancel) { model.cancelDiscard() }.accessibilityIdentifier("offline.sleep.discard.keep")
                Button("放弃修改", role: .destructive) { model.discard() }.accessibilityIdentifier("offline.sleep.discard.confirm")
            } message: { Text("这些修改尚未写入本机。") }
        }
    }
}
#endif
