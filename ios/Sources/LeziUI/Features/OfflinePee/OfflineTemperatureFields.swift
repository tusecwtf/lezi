#if canImport(SwiftUI)
import SwiftUI
import LeziCore

struct OfflineTemperatureFields: View {
    @Binding var draft: OfflineTemperatureDraft
    @Binding var adviceEnabled: Bool
    let birthday: String?
    let timestampMillis: Int64
    let focus: FocusState<String?>.Binding
    var body: some View {
        TextField("体温", text: $draft.input)
            .focused(focus, equals: "temperature.value")
            #if os(iOS)
            .keyboardType(.decimalPad)
            #endif
            .accessibilityIdentifier("offline.temperature.value")
        Picker("体温单位", selection: Binding(get: { draft.unit }, set: { draft.selectUnit($0) })) {
            Text("℃").tag(TemperatureUnit.celsius).accessibilityIdentifier("offline.temperature.unit.celsius")
            Text("℉").tag(TemperatureUnit.fahrenheit).accessibilityIdentifier("offline.temperature.unit.fahrenheit")
        }.accessibilityIdentifier("offline.temperature.unit")
        LeziText("新输入最多 7 个字符；切换单位保留原体温精度。", role: .label)
        Toggle("低月龄发热提示", isOn: $adviceEnabled)
            .accessibilityIdentifier("offline.temperature.advice.enabled")
        LeziText("仅记录时不足 3 个月且体温 ≥38℃", role: .label)
        if draft.issue == nil, let value = draft.celsius,
           TemperatureRules.shouldShowInfantFeverAdvice(birthday: birthday, recordTimestampMillis: timestampMillis,
                                                       celsius: value, enabled: adviceEnabled, zone: .current) {
            LeziText(TemperatureRules.infantFeverAdviceText)
                .accessibilityIdentifier("offline.temperature.advice")
        }
    }
}
#endif
