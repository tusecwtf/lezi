import Foundation
import LeziCore

/// Manual point facts only. No timer session or inferred interval is represented.
public struct OfflineNursingDraft: Equatable, Sendable {
    public var leftMinutes = "0"
    public var rightMinutes = "0"
    public var order = "LR"
    public var amountMl = ""
    public private(set) var recordMode = "end"
    public init() {}
    init(payload: RecordPayload) {
        if case let .nursing(left, right, order, amount, mode) = payload {
            leftMinutes = String(left); rightMinutes = String(right)
            self.order = order; amountMl = amount.map(String.init) ?? ""; recordMode = mode
        }
    }
    public static let orders = ["L", "LR", "RL", "R"]
    public static func orderLabel(_ order: String) -> String {
        ["L": "仅左", "LR": "先左后右", "RL": "先右后左", "R": "仅右"][order] ?? "未知"
    }
    static func supports(_ payload: RecordPayload) -> Bool {
        guard case let .nursing(_, _, order, _, mode) = payload else { return false }
        return NursingConfirmInput.orders.contains(order) && ["start", "end"].contains(mode)
    }
    private var input: NursingConfirmInput {
        .init(leftMinutes: leftMinutes, rightMinutes: rightMinutes, order: order, amountMl: amountMl)
    }
    private static func digits(_ text: String, limit: Int) -> Bool {
        !text.isEmpty && text.utf16.count <= limit && text.unicodeScalars.allSatisfy {
            $0.value <= 0xFFFF && $0.properties.generalCategory == .decimalNumber
        }
    }
    public var issue: LeziConfirmationIssue? {
        guard Self.digits(leftMinutes, limit: 4), Self.digits(rightMinutes, limit: 4) else {
            return .init(message: "左右时长需为 0–1440 分钟的整数", fieldID: "nursing.duration")
        }
        // Validate source order/duration before the optional amount, as Android does.
        if let issue = input.validationIssue() {
            let field: String = switch issue.field { case .duration: "duration"; case .order: "order"; case .amount: "amount" }
            return .init(message: issue.message, fieldID: "nursing.\(field)")
        }
        if !AndroidText.isBlank(amountMl) && !Self.digits(amountMl, limit: 3) {
            return .init(message: "奶量需在 1–999 ml 之间", fieldID: "nursing.amount")
        }
        guard ["start", "end"].contains(recordMode) else {
            return .init(message: "不支持的记录时刻模式", fieldID: "nursing.duration")
        }
        return nil
    }
    public var payload: RecordPayload? {
        guard issue == nil else { return nil }
        return input.payload(recordMode: recordMode)
    }
    var confirmationPayload: RecordPayload {
        // Explicitly invalid snapshot; never call the Core text adapter's fallback
        // before strict validation succeeds. Feature and storage gates reject this.
        payload ?? .nursing(leftMinutes: -1, rightMinutes: -1, order: order, amountMl: nil, recordMode: recordMode)
    }
}
