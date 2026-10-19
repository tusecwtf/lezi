import Foundation
import LeziCore

/// Display text is separate from its exact Celsius anchor. Unit-only changes never
/// round-trip a stored fact through a rounded display value.
public struct OfflineTemperatureDraft: Equatable, Sendable {
    public var input: String
    public private(set) var unit: TemperatureUnit = .celsius
    private var anchorInput: String
    private var anchorCelsius: Double
    public init() {
        input = "36.5"; anchorInput = input; anchorCelsius = TemperatureRules.defaultCelsius
    }
    init(payload: RecordPayload) {
        self.init()
        if case let .temperature(value) = payload {
            input = OfflineGrowthDraft.number(value); anchorInput = input; anchorCelsius = value
        }
    }
    public var celsius: Double? {
        if input == anchorInput { return anchorCelsius }
        guard !input.isEmpty, input.utf16.count <= 7,
              input.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }),
              input.filter({ $0 == "." }).count <= 1, let value = Double(input) else { return nil }
        return TemperatureRules.convert(value, from: unit, to: .celsius)
    }
    public var issue: LeziConfirmationIssue? {
        guard let celsius, celsius.isFinite else {
            return .init(message: "请填写有效体温（最多 7 个字符）", fieldID: "temperature.value")
        }
        return RecordPayload.temperature(celsius: celsius).validationErrors().first.map {
            .init(message: $0, fieldID: "temperature.value")
        }
    }
    public var payload: RecordPayload? {
        guard issue == nil, let celsius else { return nil }
        return .temperature(celsius: celsius)
    }
    /// Invalid raw text stays visible and cannot be reinterpreted in another unit.
    public mutating func selectUnit(_ next: TemperatureUnit) {
        guard next != unit, issue == nil, let celsius,
              let display = TemperatureRules.convert(celsius, from: .celsius, to: next) else { return }
        unit = next; input = OfflineGrowthDraft.number(display)
        anchorInput = input; anchorCelsius = celsius
    }
    var confirmationPayload: RecordPayload { payload ?? .temperature(celsius: .infinity) }
}
