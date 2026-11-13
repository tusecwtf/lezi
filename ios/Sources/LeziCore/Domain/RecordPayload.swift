import Foundation

/// Source: core/model/RecordType.kt. Raw keys are Android's existing type keys;
/// this enum is not a complete record wire DTO.
public enum RecordType: String, CaseIterable, Sendable {
    case nursing, formula, pumpedFeed = "pumped_feed", pumpExpress = "pump_express"
    case pee, poop, bothDiaper = "both_diaper", sleep, temperature, diary, bath, walk
    case cough, rash, vomit, injury, medicine, hospital, height, weight
    case babyFood = "baby_food", snack, drink, head, chest, footSize = "foot_size", vaccine, custom

    public static var availableForNewEntry: [Self] { allCases.filter { $0 != .custom } }
}

public enum MilkKind: Sendable { case formula, pumpedFeed, pumpExpress
    public var recordType: RecordType { switch self { case .formula: .formula; case .pumpedFeed: .pumpedFeed; case .pumpExpress: .pumpExpress } }
}
public enum SymptomKind: Sendable { case cough, rash, vomit, injury
    public var recordType: RecordType { switch self { case .cough: .cough; case .rash: .rash; case .vomit: .vomit; case .injury: .injury } }
}
public enum MeasurementKind: Sendable { case height, weight, head, chest, footSize
    public var recordType: RecordType { switch self { case .height: .height; case .weight: .weight; case .head: .head; case .chest: .chest; case .footSize: .footSize } }
}
public enum FoodKind: Sendable { case babyFood, snack, drink
    public var recordType: RecordType { switch self { case .babyFood: .babyFood; case .snack: .snack; case .drink: .drink } }
}

/// Typed, immutable in-process values. Deliberately not Codable: storage/wire
/// parsing must preserve unknown schema documents, not silently coerce them.
public enum RecordPayload: Equatable, Sendable {
    case nursing(leftMinutes: Int = 0, rightMinutes: Int = 0, order: String = "LR", amountMl: Int? = nil, recordMode: String = "end")
    case milk(kind: MilkKind, amountMl: Int = 0, preparedMl: Int? = nil, durationMinutes: Int? = nil)
    case pee(amount: Int = 2)
    case stool(amount: Int = 3, consistency: Int = 3, color: Int = 0)
    case bothDiaper(peeAmount: Int = 2, stoolAmount: Int = 3, stoolConsistency: Int = 3, stoolColor: Int = 0)
    case sleep(isNap: Bool = false, anomaly: Bool = false)
    case temperature(celsius: Double = 36.5)
    case diary(body: String = "")
    case bath, walk
    case symptom(kind: SymptomKind, severity: Int = 2, description: String? = nil)
    case medicine(name: String = "", dose: String? = nil)
    case hospital(reason: String = "", advice: String? = nil)
    case measurement(kind: MeasurementKind, value: Double, unit: String)
    case food(kind: FoodKind, content: String = "", amount: String? = nil)
    case vaccine(name: String = "", batch: String? = nil)
    case custom(titleSnapshot: String = "", detail: String? = nil, iconSlot: Int? = nil)

    public var type: RecordType {
        switch self {
        case .nursing: .nursing
        case let .milk(kind, _, _, _): kind.recordType
        case .pee: .pee
        case .stool: .poop
        case .bothDiaper: .bothDiaper
        case .sleep: .sleep
        case .temperature: .temperature
        case .diary: .diary
        case .bath: .bath
        case .walk: .walk
        case let .symptom(kind, _, _): kind.recordType
        case .medicine: .medicine
        case .hospital: .hospital
        case let .measurement(kind, _, _): kind.recordType
        case let .food(kind, _, _): kind.recordType
        case .vaccine: .vaccine
        case .custom: .custom
        }
    }

    /// Storage-shape invariants shared by input validation and the encoder.
    /// Native tightening: Android's general MilkPayload can hold formula-only
    /// fields even though its closed schema rejects them on the next read.
    var typedStructureErrors: [String] {
        switch self {
        case let .milk(kind, _, prepared, duration) where kind != .formula:
            return (prepared == nil ? [] : ["仅配方奶支持冲调量"])
                + (duration == nil ? [] : ["仅配方奶支持喂养时长"])
        case let .nursing(_, _, order, _, mode):
            return (NursingConfirmInput.orders.contains(order) ? [] : ["不支持的喂养顺序"])
                + (["start", "end"].contains(mode) ? [] : ["不支持的记录时刻模式"])
        default: return []
        }
    }

    /// Mirrors RecordPayloadCodec.validate, with the storage-shape tightening above,
    /// and remains less strict than nursing text confirmation (e.g. its optional
    /// milk estimate).
    public func validationErrors(allowIntentOnlyFeed: Bool = false) -> [String] {
        var errors = typedStructureErrors
        func stool(_ amount: Int, _ consistency: Int, _ color: Int) {
            if !(1...4).contains(amount) { errors.append("便量必须是 1–4") }
            if !(1...4).contains(consistency) { errors.append("软硬必须是 1–4") }
            if !(0...7).contains(color) { errors.append("颜色必须是 0–7") }
        }
        switch self {
        case let .nursing(left, right, _, _, _):
            if left < 0 || right < 0 { errors.append("喂养时长不能为负数") }
            if Double(left) + Double(right) <= 0 && !allowIntentOnlyFeed { errors.append("至少记录一侧时长") }
        case let .milk(kind, amount, prepared, duration):
            let intentOnly = allowIntentOnlyFeed && kind != .pumpExpress && amount == 0
            if !(1...999).contains(amount) && !intentOnly { errors.append("奶量需在 1–999 ml 之间") }
            if let prepared, !(0...999).contains(prepared) { errors.append("冲调量需在 0–999 ml 之间") }
            if let duration, !(0...1440).contains(duration) { errors.append("时长需在 0–1440 分钟之间") }
        case let .pee(amount):
            if !(1...3).contains(amount) { errors.append("尿量必须是 1–3") }
        case let .stool(amount, consistency, color): stool(amount, consistency, color)
        case let .bothDiaper(pee, amount, consistency, color):
            if !(1...3).contains(pee) { errors.append("尿量必须是 1–3") }; stool(amount, consistency, color)
        case let .temperature(value):
            if !(34.0...43.0).contains(value) { errors.append("体温超出可记录范围") }
        case let .measurement(kind, value, unit):
            if let error = GrowthMeasurementFacts.validationError(kind: kind, displayValue: GrowthMeasurementFacts.displayValue(kind: kind, value: value, unit: unit)) { errors.append(error) }
        case let .diary(body): if body.isDomainBlank { errors.append("正文不能为空") }
        case let .symptom(_, severity, _): if !(1...3).contains(severity) { errors.append("症状程度必须是 1–3") }
        case let .medicine(name, _): if name.isDomainBlank { errors.append("药品名称不能为空") }
        case let .hospital(reason, _): if reason.isDomainBlank { errors.append("就诊原因不能为空") }
        case let .food(_, content, _): if content.isDomainBlank { errors.append("内容不能为空") }
        case let .vaccine(name, _): if name.isDomainBlank { errors.append("疫苗名称不能为空") }
        case let .custom(title, _, _): if title.isDomainBlank { errors.append("自定义标题不能为空") }
        case .sleep, .bath, .walk: break
        }
        return errors
    }
}

extension String { var isDomainBlank: Bool { AndroidText.isBlank(self) } }

public enum GrowthMeasurementFacts {
    public static func validationError(kind: MeasurementKind, displayValue: Double?) -> String? {
        guard let value = displayValue, value.isFinite, value > 0 else { return "请填写有效数值" }
        if kind == .weight && value > 100 { return "体重需在 0–100 kg 之间" }
        if kind != .weight && value > 250 { return "测量值需在 0–250 cm 之间" }
        return nil
    }
    public static func payload(kind: MeasurementKind, displayValue: Double) -> RecordPayload? {
        guard validationError(kind: kind, displayValue: displayValue) == nil else { return nil }
        let stored = kind == .weight ? (displayValue * 1000).rounded(.toNearestOrAwayFromZero) : displayValue
        guard stored > 0 else { return nil }
        return .measurement(kind: kind, value: stored, unit: kind == .weight ? "g" : "cm")
    }
    public static func displayValue(kind: MeasurementKind, value: Double, unit: String) -> Double {
        kind == .weight && unit == "g" ? value / 1000 : value
    }
}
