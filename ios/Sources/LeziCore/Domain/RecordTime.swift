import Foundation

/// Pure policies from RecordTime.kt. All numeric instants are epoch milliseconds.
public enum RecordTime {
    public static let fulfillmentActualTimeSkewMillis: Int64 = 300_000
    public static func normalizedMinuteStep(_ step: Int) -> Int { step == 5 ? 5 : 1 }
    public static func snap(hour: Int, minute: Int, step: Int) -> (hour: Int, minute: Int) {
        let step = normalizedMinuteStep(step)
        let total = min(23, max(0, hour)) * 60 + min(59, max(0, minute))
        let rounded = min(((total + step / 2) / step) * step, 1440 - step)
        return (rounded / 60, rounded % 60)
    }
    public static func pointError(timestamp: Int64, now: Int64, maxFutureSkewMillis: Int64 = 0) -> String? {
        let (limit, overflow) = now.addingReportingOverflow(max(0, maxFutureSkewMillis))
        return !overflow && timestamp > limit ? "不能选未来时刻" : nil
    }
    public static func intervalError(start: Int64, end: Int64?, now: Int64, maxFutureSkewMillis: Int64 = 0) -> String? {
        if let error = pointError(timestamp: start, now: now, maxFutureSkewMillis: maxFutureSkewMillis) { return error }
        guard let end else { return nil }
        if end <= start { return "醒来须晚于睡下" }
        return pointError(timestamp: end, now: now, maxFutureSkewMillis: maxFutureSkewMillis)
    }
    public static func shiftStartPreservingDuration(oldStart: Int64, oldEnd: Int64?, newStart: Int64) -> Int64? {
        guard let oldEnd, oldEnd > oldStart else { return nil }
        let (duration, durationOverflow) = oldEnd.subtractingReportingOverflow(oldStart)
        let (end, endOverflow) = newStart.addingReportingOverflow(duration)
        return durationOverflow || endOverflow ? nil : end
    }
    public enum FutureEventError: Sendable { case eventNotFuture, reminderNotFuture, reminderNotBeforeEvent }
    public static func futureEventError(eventAt: Int64, remindAt: Int64?, now: Int64) -> FutureEventError? {
        if eventAt <= now { return .eventNotFuture }
        if let remindAt, remindAt <= now { return .reminderNotFuture }
        if let remindAt, remindAt >= eventAt { return .reminderNotBeforeEvent }
        return nil
    }
    /// Calendar-based day boundaries: never assumes a day has 86,400 seconds.
    public static func dayInterval(containing instant: Date, zone: TimeZone) -> DateInterval? {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        return calendar.dateInterval(of: .day, for: instant)
    }
    /// Rejects nonexistent wall times; overlap defaults to earlier occurrence,
    /// preserving a supplied valid UTC offset like ZonedDateTime.ofLocal.
    public static func resolve(year: Int, month: Int, day: Int, hour: Int, minute: Int,
                               zone: TimeZone, preferredOffsetSeconds: Int? = nil) -> Date? {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        var components = DateComponents()
        components.year = year; components.month = month; components.day = day
        components.hour = hour; components.minute = minute; components.second = 0
        guard let approximate = calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12)),
              let interval = calendar.dateInterval(of: .day, for: approximate) else { return nil }
        let before = interval.start.addingTimeInterval(-1)
        let first = calendar.nextDate(after: before, matching: components, matchingPolicy: .strict, repeatedTimePolicy: .first)
        let last = calendar.nextDate(after: before, matching: components, matchingPolicy: .strict, repeatedTimePolicy: .last)
        let candidates = [first, last].compactMap { $0 }.filter { date in
            let c = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
            return c.year == year && c.month == month && c.day == day && c.hour == hour && c.minute == minute
        }
        if let preferredOffsetSeconds, let preferred = candidates.first(where: { zone.secondsFromGMT(for: $0) == preferredOffsetSeconds }) { return preferred }
        return candidates.min()
    }
}
