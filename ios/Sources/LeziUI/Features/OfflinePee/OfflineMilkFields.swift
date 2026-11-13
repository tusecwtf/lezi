#if canImport(SwiftUI)
import SwiftUI
import LeziCore

struct OfflineMilkFields: View {
    let kind: MilkKind
    @Binding var draft: OfflineMilkDraft
    @Binding var step: Int
    let focus: FocusState<String?>.Binding
    private var amount: Int? {
        OfflineMilkDraft.integer(draft.input(kind: kind).amount).flatMap { (1...999).contains($0) ? $0 : nil }
    }
    var body: some View {
        Picker("奶量步长（本机偏好）", selection: $step) {
            ForEach([5, 10, 15], id: \.self) { value in
                Text("\(value) ml").tag(value).accessibilityIdentifier("offline.milk.step.\(value)")
            }
        }.accessibilityIdentifier("offline.milk.step")
        HStack {
            Button("−\(step)") { adjust(-step) }.disabled(amount == nil).accessibilityIdentifier("offline.milk.minus")
            Spacer()
            Button("+\(step)") { adjust(step) }.disabled(amount == nil).accessibilityIdentifier("offline.milk.plus")
        }
        TextField("任意奶量 ml（1–999）", text: binding(\.amount))
            .focused(focus, equals: "milk.amount")
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .accessibilityIdentifier("offline.milk.amount")
        ScrollView(.horizontal) {
            HStack {
                ForEach(draft.quickAmounts(kind: kind, step: step), id: \.self) { amount in
                    Button("\(amount)ml") { binding(\.amount).wrappedValue = String(amount) }
                        .accessibilityIdentifier("offline.milk.quick.\(amount)")
                }
            }
        }.accessibilityIdentifier("offline.milk.quick")
        if kind == .formula {
            TextField("冲调量 ml（可选）", text: binding(\.prepared))
                .focused(focus, equals: "milk.prepared")
                #if os(iOS)
                .keyboardType(.numberPad)
                #endif
                .accessibilityIdentifier("offline.milk.prepared")
            TextField("耗时（分，可选）", text: binding(\.duration))
                .focused(focus, equals: "milk.duration")
                #if os(iOS)
                .keyboardType(.numberPad)
                #endif
                .accessibilityIdentifier("offline.milk.duration")
        }
        LeziText("快捷奶量含此宝宝同类型的真实近期值；步长是本机偏好。", role: .label)
    }
    private func binding(_ key: WritableKeyPath<OfflineMilkInput, String>) -> Binding<String> {
        Binding(get: { draft.input(kind: kind)[keyPath: key] }, set: {
            var value = draft.input(kind: kind); value[keyPath: key] = $0; draft.set(value, kind: kind)
        })
    }
    private func adjust(_ delta: Int) {
        guard let amount else { return }
        binding(\.amount).wrappedValue = String(min(999, max(1, amount + delta)))
    }
}
struct OfflineMilkSummary: View {
    let amount: Int
    let prepared: Int?
    let duration: Int?
    var body: some View {
        LeziText("奶量：\(amount) ml").accessibilityIdentifier("offline.record.milk.amount")
        if let prepared { LeziText("冲调量：\(prepared) ml").accessibilityIdentifier("offline.record.milk.prepared") }
        if let duration { LeziText("耗时：\(duration) 分钟").accessibilityIdentifier("offline.record.milk.duration") }
    }
}
#endif
