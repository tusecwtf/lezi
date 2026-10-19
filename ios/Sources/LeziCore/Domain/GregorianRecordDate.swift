import Foundation

/// Shared Gregorian date-only representation. UTC noon keeps birthdays independent
/// of skipped local midnights; only an event's date is extracted in its given zone.
enum GregorianRecordDate {
    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    static func birthday(_ text: String?) -> Date? {
        guard let text, text.utf8.count == 10,
              text.range(of: #"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"#, options: .regularExpression) != nil else { return nil }
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, parts[0] > 0,
              let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)) else { return nil }
        let checked = calendar.dateComponents([.year, .month, .day], from: date)
        guard checked.year == parts[0], checked.month == parts[1], checked.day == parts[2] else { return nil }
        return date
    }

    static func localDate(timestampMillis: Int64, zone: TimeZone) -> Date? {
        var local = calendar
        local.timeZone = zone
        let instant = Date(timeIntervalSince1970: Double(timestampMillis) / 1000)
        var components = local.dateComponents([.year, .month, .day], from: instant)
        components.hour = 12
        return calendar.date(from: components)
    }
}
