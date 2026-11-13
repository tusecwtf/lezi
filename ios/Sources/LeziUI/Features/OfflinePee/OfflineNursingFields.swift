#if canImport(SwiftUI)
import SwiftUI
import LeziCore

struct OfflineNursingFields: View {
    @Binding var draft: OfflineNursingDraft
    let focus: FocusState<String?>.Binding
    var body: some View {
        LeziText("手动填写已发生的哺乳事实；不含计时器或进行中状态。", role: .label)
            .accessibilityIdentifier("offline.nursing.scope")
        TextField("左侧（分钟）", text: $draft.leftMinutes)
            .focused(focus, equals: "nursing.duration")
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .accessibilityIdentifier("offline.nursing.left")
        TextField("右侧（分钟）", text: $draft.rightMinutes)
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .accessibilityIdentifier("offline.nursing.right")
        Picker("喂养顺序", selection: $draft.order) {
            ForEach(OfflineNursingDraft.orders, id: \.self) { order in
                Text(OfflineNursingDraft.orderLabel(order)).tag(order)
                    .accessibilityIdentifier("offline.nursing.order.\(order)")
            }
        }.focused(focus, equals: "nursing.order")
            .accessibilityIdentifier("offline.nursing.order")
        TextField("奶量 ml（可选）", text: $draft.amountMl)
            .focused(focus, equals: "nursing.amount")
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .accessibilityIdentifier("offline.nursing.amount")
        LeziText("左右各 0–1440 分钟，至少一侧大于 0；可选奶量 1–999 ml。", role: .label)
        LeziText(draft.recordMode == "start" ? "保留原记录模式：开始；不推算结束时间。" : "记录模式：结束；记录时间不从左右时长倒推。", role: .label)
            .accessibilityIdentifier("offline.nursing.mode")
    }
}

struct OfflineNursingSummary: View {
    let left: Int
    let right: Int
    let order: String
    let amount: Int?
    let mode: String
    var body: some View {
        LeziText("左侧：\(left) 分钟 · 右侧：\(right) 分钟")
            .accessibilityIdentifier("offline.record.nursing.duration")
        LeziText("顺序：\(OfflineNursingDraft.orderLabel(order))")
            .accessibilityIdentifier("offline.record.nursing.order")
        LeziText(amount.map { "奶量：\($0) ml" } ?? "奶量未填写")
            .accessibilityIdentifier("offline.record.nursing.amount")
        LeziText("记录模式：\(mode == "start" ? "开始" : mode == "end" ? "结束" : "未知")", role: .label)
            .accessibilityIdentifier("offline.record.nursing.mode")
    }
}
#endif
