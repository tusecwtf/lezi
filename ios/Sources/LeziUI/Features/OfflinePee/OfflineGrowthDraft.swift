import Foundation
import LeziCore

/// Raw decimal input, separate from the immutable original measurement.
/// An unchanged edit preserves its stored value/unit without a lossy display round trip.
public struct OfflineGrowthDraft: Equatable, Sendable {
    public var input = ""
    private let originalInput: String?
    private let originalPayload: RecordPayload?
    public init() { originalInput = nil; originalPayload = nil }
    init(payload: RecordPayload) {
        if case let .measurement(kind, value, unit) = payload {
            input = Self.number(GrowthMeasurementFacts.displayValue(kind: kind, value: value, unit: unit))
            originalInput = input; originalPayload = payload
        } else { originalInput = nil; originalPayload = nil }
    }
    public static func number(_ value: Double) -> String {
        let value = String(value)
        return value.hasSuffix(".0") ? String(value.dropLast(2)) : value
    }
    static func supports(_ payload: RecordPayload) -> Bool {
        guard case let .measurement(kind, _, unit) = payload else { return false }
        return kind == .weight ? (unit == "g" || unit == "kg") : unit == "cm"
    }
    private func unchanged(kind: MeasurementKind) -> Bool {
        input == originalInput && originalPayload?.type == kind.recordType
    }
    public func issue(kind: MeasurementKind) -> LeziConfirmationIssue? {
        if unchanged(kind: kind), let originalPayload {
            return originalPayload.validationErrors().first.map { .init(message: $0, fieldID: "growth.value") }
        }
        // Android filters and takes seven UTF-16 units. Preserve invalid input here;
        // never turn a minus sign, pasted unit or comma into a different measurement.
        guard !input.isEmpty, input.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }),
              input.filter({ $0 == "." }).count <= 1, let value = Double(input) else {
            return .init(message: "请填写有效数值", fieldID: "growth.value")
        }
        if input.utf16.count > 7 { return .init(message: "测量数值最多 7 个字符", fieldID: "growth.value") }
        if let error = GrowthMeasurementFacts.validationError(kind: kind, displayValue: value) {
            return .init(message: error, fieldID: "growth.value")
        }
        guard GrowthMeasurementFacts.payload(kind: kind, displayValue: value) != nil else {
            return .init(message: "请填写有效数值", fieldID: "growth.value")
        }
        return nil
    }
    public func payload(kind: MeasurementKind) -> RecordPayload? {
        guard issue(kind: kind) == nil else { return nil }
        if unchanged(kind: kind) { return originalPayload }
        guard let value = Double(input) else { return nil }
        return GrowthMeasurementFacts.payload(kind: kind, displayValue: value)
    }
    func confirmationPayload(kind: MeasurementKind) -> RecordPayload {
        // The existing panel requires a typed snapshot even before a value exists.
        // Infinity is explicitly invalid, not a default measurement; feature validation,
        // domain validation and encoding all reject it. No zero/previous-value fallback.
        payload(kind: kind) ?? .measurement(kind: kind, value: .infinity, unit: kind == .weight ? "g" : "cm")
    }
}

extension OfflineRecordKind {
    public var measurementKind: MeasurementKind? {
        switch self {
        case .height: .height; case .weight: .weight; case .head: .head
        case .chest: .chest; case .footSize: .footSize; default: nil
        }
    }
    public var measurementUnit: String { self == .weight ? "kg" : "cm" }
}
