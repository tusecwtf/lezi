import Foundation

/// The HH:mm-only correction used by Android's WakeObservationEditSheet.
/// Unlike RecordTime.resolve, a time in a clock gap moves forward by the gap.
public enum WakeObservationTime {
    /// An unsupported native date is blank rather than a fabricated clamped time.
    public static func text(timestampMillis: Int64, zone: TimeZone) -> String {
        let calendar = calendar(zone: zone)
        guard let components = originalComponents(timestampMillis, calendar: calendar),
              let hour = components.hour, let minute = components.minute else { return "" }
        return "\(hour / 10)\(hour % 10):\(minute / 10)\(minute % 10)"
    }

    /// Keeps the observation's original local date, discards seconds and
    /// milliseconds, and chooses the earlier occurrence of an overlapping time.
    /// Java's SMART HH:mm parser accepts 24:00 as LocalTime.MIDNIGHT; its excess
    /// day is not retained when the sheet combines that LocalTime with the date.
    /// Returns nil if Foundation cannot faithfully represent the original date.
    public static func resolve(_ text: String, originalTimestampMillis: Int64,
                               zone: TimeZone) -> Int64? {
        let bytes = Array(text.utf8)
        guard bytes.count == 5, bytes[2] == 58,
              [0, 1, 3, 4].allSatisfy({ (48...57).contains(bytes[$0]) }) else { return nil }
        let hour = Int(bytes[0] - 48) * 10 + Int(bytes[1] - 48)
        let minute = Int(bytes[3] - 48) * 10 + Int(bytes[4] - 48)
        guard minute < 60, hour < 24 || (hour == 24 && minute == 0) else { return nil }

        let calendar = calendar(zone: zone)
        guard var components = originalComponents(originalTimestampMillis, calendar: calendar) else { return nil }
        components.hour = hour % 24
        components.minute = minute
        components.second = 0
        components.nanosecond = 0
        // Date construction matches LocalDateTime.atZone: earlier overlap
        // occurrence, and gap-length forward adjustment (including 30-minute
        // gaps). nextDate's preserving-smaller-components policy does not
        // resolve every such gap, and RecordTime.resolve intentionally rejects it.
        guard let resolved = calendar.date(from: components) else { return nil }
        return Int64(exactly: (resolved.timeIntervalSince1970 * 1000).rounded())
    }

    private static func originalComponents(_ timestampMillis: Int64, calendar: Calendar) -> DateComponents? {
        let original = Date(timeIntervalSince1970: Double(timestampMillis) / 1000)
        guard Int64(exactly: (original.timeIntervalSince1970 * 1000).rounded()) == timestampMillis else { return nil }
        let components = calendar.dateComponents([.era, .year, .month, .day, .hour, .minute, .second, .nanosecond],
                                                  from: original)
        var roundtrip = components
        // Preserve the source offset only for this representability check, so a
        // valid later-overlap instant is not rejected. The correction itself
        // still uses the zone's earlier overlap. Foundation can silently clamp
        // dates outside its native range; Java's supported range is larger.
        roundtrip.timeZone = TimeZone(secondsFromGMT: calendar.timeZone.secondsFromGMT(for: original))
        guard let checked = calendar.date(from: roundtrip),
              abs(checked.timeIntervalSince(original)) < 0.000_001 else { return nil }
        // Sub-microsecond tolerance accounts for nanosecond component rounding;
        // it cannot hide a change to a millisecond-resolution source timestamp.
        return components
    }

    private static func calendar(zone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return calendar
    }
}
