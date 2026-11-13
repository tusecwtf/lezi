import Foundation
import LeziCore

public struct OfflineMilkInput: Equatable, Sendable {
    public var amount: String
    public var prepared = ""
    public var duration = ""
    public init(amount: String) { self.amount = amount }
}

/// Three bounded inputs retain their own values when a new draft switches type.
public struct OfflineMilkDraft: Equatable, Sendable {
    private var formula: OfflineMilkInput
    private var pumpedFeed: OfflineMilkInput
    private var pumpExpress: OfflineMilkInput
    public let recent: [RecordType: [Int]]
    public init(recent: [RecordType: [Int]] = [:]) {
        self.recent = recent.mapValues { values in
            var seen = Set<Int>()
            return Array(values.filter { (1...999).contains($0) && seen.insert($0).inserted }.prefix(3))
        }
        formula = .init(amount: String(self.recent[.formula]?.first ?? 120))
        pumpedFeed = .init(amount: String(self.recent[.pumpedFeed]?.first ?? 120))
        pumpExpress = .init(amount: String(self.recent[.pumpExpress]?.first ?? 60))
    }
    init(payload: RecordPayload, recent: [RecordType: [Int]] = [:]) {
        self.init(recent: recent)
        if case let .milk(kind, amount, prepared, duration) = payload {
            var input = OfflineMilkInput(amount: String(amount))
            input.prepared = prepared.map(String.init) ?? ""
            input.duration = duration.map(String.init) ?? ""
            set(input, kind: kind)
        }
    }
    public func input(kind: MilkKind) -> OfflineMilkInput {
        switch kind { case .formula: formula; case .pumpedFeed: pumpedFeed; case .pumpExpress: pumpExpress }
    }
    public mutating func set(_ value: OfflineMilkInput, kind: MilkKind) {
        switch kind { case .formula: formula = value; case .pumpedFeed: pumpedFeed = value; case .pumpExpress: pumpExpress = value }
    }
    /// Android Char.isDigit + toIntOrNull accepts BMP decimal digits, not whitespace/signs.
    public static func integer(_ text: String) -> Int? {
        guard !text.isEmpty, text.utf16.count <= 4 else { return nil }
        var normalized = ""
        for scalar in text.unicodeScalars {
            guard scalar.value <= 0xFFFF, scalar.properties.generalCategory == .decimalNumber,
                  let digit = Character(String(scalar)).wholeNumberValue else { return nil }
            normalized += String(digit)
        }
        return Int(normalized)
    }
    public static func step(_ value: Int) -> Int { [5, 10, 15].contains(value) ? value : 5 }
    public func issue(kind: MilkKind) -> LeziConfirmationIssue? {
        let value = input(kind: kind)
        guard let amount = Self.integer(value.amount), (1...999).contains(amount) else {
            return .init(message: "奶量需在 1–999 ml 之间", fieldID: "milk.amount")
        }
        if kind != .formula {
            guard value.prepared.isEmpty && value.duration.isEmpty else {
                return .init(message: "此奶类不支持冲调量或耗时", fieldID: "milk.amount")
            }
            return nil
        }
        if !AndroidText.isBlank(value.prepared) && !(Self.integer(value.prepared).map { (0...999).contains($0) } ?? false) {
            return .init(message: "冲调量需在 0–999 ml 之间", fieldID: "milk.prepared")
        }
        if !AndroidText.isBlank(value.duration) && !(Self.integer(value.duration).map { (0...1440).contains($0) } ?? false) {
            return .init(message: "时长需在 0–1440 分钟之间", fieldID: "milk.duration")
        }
        return nil
    }
    public func payload(kind: MilkKind) -> RecordPayload? {
        guard issue(kind: kind) == nil, let amount = Self.integer(input(kind: kind).amount) else { return nil }
        let value = input(kind: kind)
        return .milk(kind: kind, amountMl: amount,
                     preparedMl: kind == .formula ? Self.integer(value.prepared) : nil,
                     durationMinutes: kind == .formula ? Self.integer(value.duration) : nil)
    }
    func confirmationPayload(kind: MilkKind) -> RecordPayload {
        // Explicitly invalid typed snapshot for the nonoptional panel API; never a saved default.
        payload(kind: kind) ?? .milk(kind: kind, amountMl: -1)
    }
    public func quickAmounts(kind: MilkKind, step: Int) -> [Int] {
        var values = recent[kind.recordType] ?? []
        if let current = Self.integer(input(kind: kind).amount), (1...999).contains(current) {
            let step = Self.step(step)
            values += [current - step, current, current + step, current + step * 2].map { min(999, max(1, $0)) }
        }
        var seen = Set<Int>()
        return values.filter { seen.insert($0).inserted }
    }
}
extension OfflineRecordKind {
    public var milkKind: MilkKind? {
        switch self { case .formula: .formula; case .pumpedFeed: .pumpedFeed; case .pumpExpress: .pumpExpress; default: nil }
    }
}
