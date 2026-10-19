import Foundation

/// A composer-only display choice. TemperaturePayload stores Celsius only.
public enum TemperatureUnit: Sendable { case celsius, fahrenheit }

/// Pure port of QuickRecordPurposeFields.TemperatureFields and
/// shouldShowInfantFeverAdvice; no diagnosis or additional clinical rules.
public enum TemperatureRules {
    public static let defaultCelsius = 36.5
    public static let infantFeverAdviceText = "低月龄发热请及时就医。本提示仅供参考，不构成医疗诊断。"

    /// No rounding here: the native composer preserves canonical-value precision.
    /// RecordPayload.temperature remains the single authority for stored ranges.
    public static func convert(_ value: Double, from: TemperatureUnit, to: TemperatureUnit) -> Double? {
        guard value.isFinite else { return nil }
        let converted = from == to ? value : to == .fahrenheit ? value * 9.0 / 5.0 + 32.0 : (value - 32.0) * 5.0 / 9.0
        return converted.isFinite ? converted : nil
    }

    /// Birthday is a strict Gregorian yyyy-MM-dd date, not a timezone-bound instant.
    /// The record's date is taken in the explicit device zone. Three calendar
    /// months use month-end clamping (LocalDate.plusMonths), never a 90-day age.
    /// Invalid/nonfinite inputs fail closed rather than showing advice for NaN.
    public static func shouldShowInfantFeverAdvice(birthday: String?, recordTimestampMillis: Int64,
                                                  celsius: Double, enabled: Bool = true, zone: TimeZone) -> Bool {
        guard enabled, celsius.isFinite, celsius >= 38, let birthday,
              birthday.utf8.count == 10,
              birthday.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil else { return false }
        let parts = birthday.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, parts[0] > 0 else { return false }
        // Date arithmetic uses UTC noon as a representation of Gregorian dates,
        // so a skipped local midnight/date cannot change the baby's birthday.
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let birthDate = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)) else { return false }
        let checked = calendar.dateComponents([.year, .month, .day], from: birthDate)
        guard checked.year == parts[0], checked.month == parts[1], checked.day == parts[2],
              let threeMonths = calendar.date(byAdding: .month, value: 3, to: birthDate) else { return false }
        var localCalendar = calendar
        localCalendar.timeZone = zone
        let instant = Date(timeIntervalSince1970: Double(recordTimestampMillis) / 1000)
        var recordComponents = localCalendar.dateComponents([.year, .month, .day], from: instant)
        recordComponents.hour = 12
        guard let recordDate = calendar.date(from: recordComponents) else { return false }
        return recordDate >= birthDate && recordDate < threeMonths
    }
}
