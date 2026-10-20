#if canImport(SwiftUI)
import SwiftUI
import LeziCore
import LeziStorage

@MainActor
struct OfflineWakeObservationView: View {
    @ObservedObject var model: OfflineWakeObservationModel
    @Environment(\.leziTheme) private var theme

    var body: some View {
        Group {
            if let editor = model.editor { correction(editor) }
            else if let details = model.details { observations(details) }
        }
        .accessibilityIdentifier("offline.wake.sheet")
        .disabled(model.busy)
        .interactiveDismissDisabled(model.busy || model.editor?.isDirty == true)
        .alert("放弃未保存的修改？", isPresented: $model.showingDiscard) {
            Button("继续编辑", role: .cancel) { model.cancelDiscard() }
                .accessibilityIdentifier("offline.wake.discard.keep")
            Button("放弃修改", role: .destructive) { model.discard() }
                .accessibilityIdentifier("offline.wake.discard.confirm")
        } message: { Text("这些修改尚未写入本机。") }
    }
    private func observations(_ details: OfflineWakeObservationListState) -> some View {
        VStack(spacing: theme.density.sectionGap) {
            HStack {
                LeziText("醒来观察", role: .title)
                Spacer()
                Button("关闭") { model.close() }.accessibilityIdentifier("offline.wake.close")
            }.padding(theme.pageInset)
            ScrollView {
                VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                    LeziText("保存对象：\(details.babyName)", role: .label)
                    if let message = model.message {
                        LeziText(message).accessibilityIdentifier("offline.wake.result")
                    }
                    if details.sleep.interval.isOverlapPending {
                        LeziText("这条较早的睡眠仍保持开放，没有被后来记录自动闭合。", role: .label)
                    } else if details.sleep.interval.isProvisional {
                        LeziText("还没有核对到确定的醒来时间，暂按最早的一次记录显示。", role: .label)
                    }
                    if details.observations.isEmpty {
                        LeziText("尚无醒来观察。").accessibilityIdentifier("offline.wake.empty")
                    }
                    ForEach(details.observations, id: \.clientUUID) { wake in
                        observation(wake, details: details)
                    }
                    Button("重新读取本机观察") { model.refresh() }
                        .accessibilityIdentifier("offline.wake.reload")
                }.frame(maxWidth: .infinity, alignment: .leading).padding(theme.pageInset)
            }
            if model.busy { ProgressView("正在处理本机观察") }
        }
    }
    private func observation(_ wake: OfflineWakeObservation, details: OfflineWakeObservationListState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            LeziText(wake.localObserverUUID == details.localActorUUID ? "本机醒来观察" : "保留的醒来观察", role: .titleSmall)
            if WakeObservationTime.text(timestampMillis: wake.timestampMillis, zone: details.zone).isEmpty {
                LeziText("原观察时间超出本机可编辑范围，原始内容已保留。", role: .label)
                    .accessibilityIdentifier("offline.wake.time.unsupported")
            } else {
                Text(Date(timeIntervalSince1970: Double(wake.timestampMillis) / 1000),
                     format: Date.FormatStyle(date: .abbreviated, time: .omitted, timeZone: details.zone))
                    .accessibilityIdentifier("offline.wake.date")
                    .accessibilityValue(localDay(wake.timestampMillis, zone: details.zone))
                LeziText(WakeObservationTime.text(timestampMillis: wake.timestampMillis, zone: details.zone))
                    .accessibilityIdentifier("offline.wake.time")
            }
            LeziText(details.status(wake), role: .label).accessibilityIdentifier("offline.wake.status")
            if let note = wake.note, !note.isEmpty {
                LeziText(note).accessibilityIdentifier("offline.wake.note")
            }
            if details.canEdit(wake) {
                HStack {
                    Button("修正") { model.openEditor(wake) }.accessibilityIdentifier("offline.wake.edit")
                        .disabled(WakeObservationTime.text(timestampMillis: wake.timestampMillis, zone: details.zone).isEmpty)
                    Button("撤回", role: .destructive) { model.withdraw(wake) }
                        .accessibilityIdentifier("offline.wake.withdraw")
                }
            }
        }
        .padding(theme.density.cardPad).frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("offline.wake.row.\(wake.clientUUID.uuidString)")
    }
    private func correction(_ state: OfflineWakeObservationState) -> some View {
        VStack(spacing: theme.density.sectionGap) {
            HStack {
                LeziText("修正醒来观察", role: .title)
                Spacer()
                Button("关闭") { model.cancelEdit() }.accessibilityIdentifier("offline.wake.editor.close")
            }.padding(theme.pageInset)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("offline.wake.editor.dragRegion")
            ScrollView {
                VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                    LeziText("保存对象：\(state.babyName)").accessibilityIdentifier("offline.wake.editor.baby")
                    LeziText("只修改原观察日期内的时间与观察备注；睡下记录保持不变。", role: .label)
                    TextField("时间 HH:mm", text: Binding(get: { state.timeText }, set: { model.edit(timeText: $0) }))
                        .accessibilityIdentifier("offline.wake.editor.time")
                    TextField("备注", text: Binding(get: { state.note }, set: { model.edit(note: $0) }), axis: .vertical)
                        .lineLimit(2...4).accessibilityIdentifier("offline.wake.editor.note")
                    if let error = state.error ?? (WakeObservationTime.resolve(state.timeText,
                        originalTimestampMillis: state.originalTimestampMillis, zone: state.zone) == nil ? "时间请填写为 HH:mm" : nil) {
                        LeziText(error).accessibilityIdentifier("offline.wake.editor.error")
                    }
                }.padding(theme.pageInset)
            }
            if model.busy { ProgressView("正在保存到本机") }
            Button("保存修正") { model.confirm() }
                .disabled(WakeObservationTime.resolve(state.timeText, originalTimestampMillis: state.originalTimestampMillis, zone: state.zone) == nil)
                .frame(minHeight: theme.primaryTouchMinimum)
                .accessibilityIdentifier("offline.wake.editor.confirm").padding(theme.pageInset)
        }
    }
    private func localDay(_ timestamp: Int64, zone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        let date = Date(timeIntervalSince1970: Double(timestamp) / 1000)
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}
#endif
