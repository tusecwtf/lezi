import Foundation
import LeziCore

/// Only the seven supported health forms. Values are copied into the immutable owner draft.
public struct OfflineHealthDraft: Equatable, Sendable {
    public var severity = 2
    public var description = ""
    public var medicineName = ""
    public var medicineDose = ""
    public var hospitalReason = ""
    public var hospitalAdvice = ""
    public var vaccineName = ""
    public var vaccineBatch = ""
    public init() {}
    public static func severityLabel(_ value: Int) -> String {
        [1: "轻微", 2: "一般", 3: "明显"][value] ?? "未知"
    }
    init(payload: RecordPayload) {
        self.init()
        switch payload {
        case let .symptom(_, severity, description): self.severity = severity; self.description = description ?? ""
        case let .medicine(name, dose): medicineName = name; medicineDose = dose ?? ""
        case let .hospital(reason, advice): hospitalReason = reason; hospitalAdvice = advice ?? ""
        case let .vaccine(name, batch): vaccineName = name; vaccineBatch = batch ?? ""
        default: break
        }
    }
    func issue(kind: OfflineRecordKind, original: Self?) -> LeziConfirmationIssue? {
        if kind.symptomKind != nil && !(1...3).contains(severity) {
            return .init(message: "症状程度必须是 1–3", fieldID: "severity")
        }
        for field in kind.healthFields {
            let value = self[keyPath: field.keyPath]
            if field.required && AndroidText.isBlank(value) {
                return .init(message: "\(field.title)不能为空", fieldID: field.id)
            }
            if value.utf16.count > field.limit && original?[keyPath: field.keyPath] != value {
                return .init(message: "\(field.title)最多 \(field.limit) 字", fieldID: field.id)
            }
        }
        return nil
    }
}

struct OfflineHealthField {
    let id: String
    let title: String
    let limit: Int
    let required: Bool
    let keyPath: WritableKeyPath<OfflineHealthDraft, String>
}

extension OfflineRecordKind {
    public var symptomKind: SymptomKind? {
        switch self { case .cough: .cough; case .rash: .rash; case .vomit: .vomit; case .injury: .injury; default: nil }
    }
    var healthFields: [OfflineHealthField] {
        switch self {
        case .cough, .rash, .vomit, .injury:
            [.init(id: "description", title: "情况描述", limit: 200, required: false, keyPath: \.description)]
        case .medicine:
            [.init(id: "medicine.name", title: "药品名称", limit: 50, required: true, keyPath: \.medicineName),
             .init(id: "medicine.dose", title: "剂量", limit: 30, required: false, keyPath: \.medicineDose)]
        case .hospital:
            [.init(id: "hospital.reason", title: "就诊原因", limit: 100, required: true, keyPath: \.hospitalReason),
             .init(id: "hospital.advice", title: "医嘱", limit: 300, required: false, keyPath: \.hospitalAdvice)]
        case .vaccine:
            [.init(id: "vaccine.name", title: "疫苗名称", limit: 80, required: true, keyPath: \.vaccineName),
             .init(id: "vaccine.batch", title: "批次 / 针次", limit: 50, required: false, keyPath: \.vaccineBatch)]
        default: []
        }
    }
}
