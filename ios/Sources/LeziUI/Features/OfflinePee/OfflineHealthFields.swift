#if canImport(SwiftUI)
import SwiftUI
import LeziCore

struct OfflineHealthFields: View {
    let kind: OfflineRecordKind
    @Binding var draft: OfflineHealthDraft
    let focus: FocusState<String?>.Binding
    var body: some View {
        if kind.symptomKind != nil {
            Picker("程度", selection: $draft.severity) {
                ForEach(1...3, id: \.self) { value in
                    Text(OfflineHealthDraft.severityLabel(value)).tag(value)
                        .accessibilityIdentifier("offline.health.severity.\(value)")
                }
            }.accessibilityIdentifier("offline.health.severity")
        }
        ForEach(kind.healthFields, id: \.id) { field in
            TextField(field.title + (field.required ? "" : "（可选）"), text: Binding(
                get: { draft[keyPath: field.keyPath] },
                set: { draft[keyPath: field.keyPath] = $0 }
            ), axis: field.limit >= 200 ? .vertical : .horizontal)
            .focused(focus, equals: field.id)
            .accessibilityIdentifier("offline.health.\(field.id)")
            LeziText("\(draft[keyPath: field.keyPath].utf16.count)/\(field.limit)", role: .label)
        }
    }
}

struct OfflineHealthSummary: View {
    let payload: RecordPayload
    var body: some View {
        switch payload {
        case let .symptom(_, severity, description):
            LeziText("程度：\(OfflineHealthDraft.severityLabel(severity))")
                .accessibilityIdentifier("offline.record.health.severity")
            if let description { LeziText(description).accessibilityIdentifier("offline.record.health.description") }
        case let .medicine(name, dose):
            LeziText("药品名称：\(name)").accessibilityIdentifier("offline.record.health.medicine.name")
            if let dose { LeziText("剂量：\(dose)").accessibilityIdentifier("offline.record.health.medicine.dose") }
        case let .hospital(reason, advice):
            LeziText("就诊原因：\(reason)").accessibilityIdentifier("offline.record.health.hospital.reason")
            if let advice { LeziText("医嘱：\(advice)").accessibilityIdentifier("offline.record.health.hospital.advice") }
        case let .vaccine(name, batch):
            LeziText("疫苗名称：\(name)").accessibilityIdentifier("offline.record.health.vaccine.name")
            if let batch { LeziText("批次 / 针次：\(batch)").accessibilityIdentifier("offline.record.health.vaccine.batch") }
        default: EmptyView()
        }
    }
}
#endif
