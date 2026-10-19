#if canImport(SwiftUI)
import SwiftUI
import LeziCore

struct OfflineFoodFields: View {
    let kind: FoodKind
    @Binding var draft: OfflineFoodDraft
    let birthday: String?
    let timestampMillis: Int64
    let focus: FocusState<String?>.Binding
    private func field(_ keyPath: WritableKeyPath<OfflineFoodInput, String>) -> Binding<String> {
        Binding(get: { draft.input(kind: kind)[keyPath: keyPath] }, set: { text in
            var value = draft.input(kind: kind); value[keyPath: keyPath] = text
            draft.set(value, kind: kind)
        })
    }
    var body: some View {
        if kind == .babyFood,
           let guidance = BabyFoodGuidance.at(birthday: birthday, recordTimestampMillis: timestampMillis, zone: .current) {
            OfflineFoodGuidancePanel(guidance: guidance, selected: draft.input(kind: kind).content) { suggestion in
                draft.chooseSuggestion(suggestion)
            }.id(guidance.stage.id)
        }
        TextField(kind == .drink ? "内容，例如 温水" : "内容，例如 南瓜米糊", text: field(\.content))
            .focused(focus, equals: "food.content")
            .accessibilityIdentifier("offline.food.content")
        LeziText("\(draft.input(kind: kind).content.utf16.count)/80 · 内容必填", role: .label)
        TextField(kind == .drink ? "量，例如 80 ml" : "量，例如 半碗", text: field(\.amount))
            .focused(focus, equals: "food.amount")
            .accessibilityIdentifier("offline.food.amount")
        LeziText("\(draft.input(kind: kind).amount.utf16.count)/30 · 自由文本，无单位换算", role: .label)
    }
}

private struct OfflineFoodGuidancePanel: View {
    let guidance: BabyFoodGuidance
    let selected: String
    let choose: (String) -> Void
    @State private var expanded = false
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LeziText("\(guidance.ageLabel) · \(guidance.stage.title)", role: .label)
                .accessibilityIdentifier("offline.food.guidance.stage")
            if guidance.stage.suggestions.isEmpty {
                LeziText("此阶段一般仍以母乳或配方奶为主；满约 6 月龄再考虑泥糊起步。", role: .label)
            } else {
                LeziText("本阶段可尝试（点选填入内容）", role: .label)
                ScrollView(.horizontal) {
                    HStack {
                        ForEach(guidance.stage.suggestions, id: \.self) { suggestion in
                            Button(suggestion) { choose(suggestion) }
                                .accessibilityIdentifier("offline.food.suggestion.\(suggestion)")
                                .accessibilityValue(selected == suggestion ? "已选" : "未选")
                        }
                    }
                }
            }
            Button(expanded ? "收起阶段说明" : "为什么这样建议") { expanded.toggle() }
                .accessibilityIdentifier("offline.food.guidance.expand")
            if expanded {
                LeziText(guidance.stage.explanation, role: .label)
                    .accessibilityIdentifier("offline.food.guidance.explanation")
            }
            LeziText(BabyFoodGuidance.disclaimer, role: .label)
                .accessibilityIdentifier("offline.food.guidance.disclaimer")
        }
    }
}
#endif
