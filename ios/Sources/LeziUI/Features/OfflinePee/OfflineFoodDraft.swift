import Foundation
import LeziCore

public struct OfflineFoodInput: Equatable, Sendable {
    public var content = ""
    public var amount = ""
    public init(content: String = "", amount: String = "") { self.content = content; self.amount = amount }
}

/// Raw per-type input plus the exact optional source amount. Reading old food never
/// normalizes its amount or invents a numeric value for legacy descriptive text.
public struct OfflineFoodDraft: Equatable, Sendable {
    private var babyFood = OfflineFoodInput()
    private var snack = OfflineFoodInput()
    private var drink = OfflineFoodInput()
    private var originalKind: FoodKind?
    private var originalInput: OfflineFoodInput?
    private var sourceAmount: String?
    public init() {}
    init(payload: RecordPayload) {
        if case let .food(kind, content, amount) = payload {
            let input = OfflineFoodInput(content: content, amount: amount ?? "")
            set(input, kind: kind)
            originalKind = kind; originalInput = input; sourceAmount = amount
        }
    }
    public func input(kind: FoodKind) -> OfflineFoodInput {
        switch kind { case .babyFood: babyFood; case .snack: snack; case .drink: drink }
    }
    public mutating func set(_ value: OfflineFoodInput, kind: FoodKind) {
        switch kind { case .babyFood: babyFood = value; case .snack: snack = value; case .drink: drink = value }
    }
    public mutating func chooseSuggestion(_ content: String) {
        babyFood.content = content
    }
    private func amountUntouched(kind: FoodKind) -> Bool {
        originalKind == kind && input(kind: kind).amount == originalInput?.amount
    }
    public func issue(kind: FoodKind) -> LeziConfirmationIssue? {
        let value = input(kind: kind)
        if AndroidText.isBlank(value.content) { return .init(message: "请填写内容", fieldID: "food.content") }
        if value.content.utf16.count > 80 && !(originalKind == kind && value.content == originalInput?.content) {
            return .init(message: "内容最多 80 字", fieldID: "food.content")
        }
        if value.amount.utf16.count > 30 && !amountUntouched(kind: kind) {
            return .init(message: "量最多 30 字", fieldID: "food.amount")
        }
        if kind == .babyFood && !amountUntouched(kind: kind) {
            if AndroidText.isBlank(value.amount) { return .init(message: "请填写量（如 50g）", fieldID: "food.amount") }
            if FoodAmount.parseValue(FoodAmount.normalize(value.amount)) == nil {
                return .init(message: "量需包含数字才能统计（「几口」无法统计）；「半碗」会自动记为 0.5碗", fieldID: "food.amount")
            }
        }
        return nil
    }
    public func payload(kind: FoodKind) -> RecordPayload? {
        guard issue(kind: kind) == nil else { return nil }
        let value = input(kind: kind)
        let amount = amountUntouched(kind: kind) ? sourceAmount : OfflineRecordText.optional(FoodAmount.normalize(value.amount))
        return .food(kind: kind, content: AndroidText.trimmed(value.content), amount: amount)
    }
    func confirmationPayload(kind: FoodKind) -> RecordPayload {
        // Invalid typed snapshot, never a fallback food fact. All writer paths also
        // require feature validation; the empty content fails domain/storage validation.
        payload(kind: kind) ?? .food(kind: kind, content: "", amount: nil)
    }
}

extension OfflineRecordKind {
    public var foodKind: FoodKind? {
        switch self { case .babyFood: .babyFood; case .snack: .snack; case .drink: .drink; default: nil }
    }
}
