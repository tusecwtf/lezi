#if canImport(SwiftUI)
import SwiftUI
import LeziCore

struct OfflineGrowthFields: View {
    let kind: OfflineRecordKind
    @Binding var draft: OfflineGrowthDraft
    let focus: FocusState<String?>.Binding
    var body: some View {
        if kind.measurementKind != nil {
            TextField("\(kind.label)（\(kind.measurementUnit)）", text: $draft.input)
                .focused(focus, equals: "growth.value")
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .accessibilityIdentifier("offline.growth.value")
            LeziText("单位：\(kind.measurementUnit) · 新输入最多 7 个字符", role: .label)
                .accessibilityIdentifier("offline.growth.unit")
            if kind == .weight {
                LeziText("保存时按克四舍五入；未改动的旧数值保持原精度。", role: .label)
            }
        }
    }
}

struct OfflineGrowthSummary: View {
    let kind: MeasurementKind
    let value: Double
    let unit: String
    var body: some View {
        let displayed = GrowthMeasurementFacts.displayValue(kind: kind, value: value, unit: unit)
        let displayUnit = kind == .weight && unit == "g" ? "kg" : unit
        LeziText("\(OfflineGrowthDraft.number(displayed)) \(displayUnit)")
            .accessibilityIdentifier("offline.record.growth.value")
    }
}
#endif
