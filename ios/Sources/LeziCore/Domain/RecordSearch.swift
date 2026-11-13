import Foundation

/// Storage-independent search rules from Android's RecordSearch.kt and
/// SearchScreen.kt. Query limits count Unicode code points, not graphemes.
public enum RecordSearchRules {
    public static func normalizedQuery(_ raw: String) -> String {
        lowercase(AndroidText.trimmed(raw))
    }

    public static func limitQuery(_ raw: String, maxScalars: Int = 100) -> String {
        String(raw.unicodeScalars.prefix(max(0, maxScalars)))
    }

    public static func queryPreview(_ raw: String) -> String {
        let limited = limitQuery(raw, maxScalars: 30)
        return limited.utf16.elementsEqual(raw.utf16) ? limited : limited + "…"
    }

    // Foundation's locale-pinned full case mapping preserves contextual casing
    // such as Greek final sigma, unlike Swift's locale-free simple lowercase.
    fileprivate static func lowercase(_ value: String) -> String {
        value.lowercased(with: Locale(identifier: "en_US_POSIX"))
    }

    /// Match Java String's literal UTF-16 sequences, not Swift's canonical
    /// equivalence (for example, precomposed é must not match e + acute).
    fileprivate static func literalContains(_ text: String, _ query: String) -> Bool {
        text.range(of: query, options: .literal) != nil
    }

    static func typeTermMatchesQuery(_ term: String, query: String) -> Bool {
        let term = lowercase(term)
        let query = lowercase(query)
        guard !term.isEmpty, !query.isEmpty else { return false }
        let isLatinAlias = term.unicodeScalars.allSatisfy {
            $0 == " " || (97...122).contains($0.value) || (48...57).contains($0.value)
        }
        guard isLatinAlias else {
            return literalContains(term, query) || literalContains(query, term)
        }
        func prefixMatches(_ word: String) -> Bool {
            word.utf16.starts(with: query.utf16) || query.utf16.starts(with: word.utf16)
        }
        if prefixMatches(term) { return true }
        if term.split(separator: " ").contains(where: { prefixMatches(String($0)) }) { return true }
        return query.utf16.count > term.utf16.count && literalContains(query, term)
    }
}

/// Chinese business copy and final visible-text matching. The caller supplies
/// the projected Sleep end, never a stale root end. A nil payload is opaque:
/// raw JSON, keys, identifiers, and unsupported fields never enter search text.
public struct RecordSearchContent: Equatable, Sendable {
    public let label: String
    public let summary: String
    public let visibleText: String
    private let type: RecordType?

    public init(rawType: String, payload: RecordPayload?, timestampMillis: Int64,
                endTimestampMillis: Int64?, note: String?) {
        type = RecordType(rawValue: rawType)
        let businessLabel = type?.searchBusinessLabel ?? "未知记录"
        if case let .custom(title, _, _) = payload {
            let trimmedTitle = AndroidText.trimmed(title)
            label = trimmedTitle.isEmpty ? businessLabel : trimmedTitle
        } else {
            label = businessLabel
        }
        summary = Self.summary(payload, start: timestampMillis, end: endTimestampMillis)
        visibleText = Self.joinVisible([summary, AndroidText.trimmed(note ?? "")])
    }

    public func matches(normalizedQuery query: String) -> Bool {
        // CareLog.search returns no rows for a normalized empty query.
        guard !query.isEmpty else { return false }
        if type?.searchTerms.contains(where: {
            RecordSearchRules.typeTermMatchesQuery($0, query: query)
        }) == true { return true }
        return !AndroidText.isBlank(visibleText)
            && RecordSearchRules.literalContains(RecordSearchRules.lowercase(visibleText), query)
    }

    private static func joinVisible(_ parts: [String]) -> String {
        parts.filter { !AndroidText.isBlank($0) }.joined(separator: " · ")
    }

    private static func summary(_ payload: RecordPayload?, start: Int64, end: Int64?) -> String {
        guard let payload else { return "未知格式（原始数据已保留）" }
        switch payload {
        case let .milk(_, amount, prepared, duration):
            return [
                "\(amount)ml",
                prepared.map { "冲调量\($0)ml" },
                duration.map { "耗时 " + durationText(UInt64(max(0, $0))) },
            ].compactMap { $0 }
                .joined(separator: " · ")
        case let .nursing(left, right, order, amount, _):
            let orderLabel: String?
            switch order {
            case "L": orderLabel = "仅左侧"
            case "R": orderLabel = "仅右侧"
            case "LR": orderLabel = "先左后右"
            case "RL": orderLabel = "先右后左"
            default: orderLabel = nil
            }
            return (["左\(left)分", "右\(right)分"] + [orderLabel, amount.map { "\($0)ml" }].compactMap { $0 })
                .joined(separator: " · ")
        case let .pee(amount):
            return "尿量" + peeAmountLabel(amount)
        case let .stool(amount, consistency, color):
            return stoolSummary(amount: amount, consistency: consistency, color: color)
        case let .bothDiaper(pee, amount, consistency, color):
            return "尿量" + peeAmountLabel(pee) + " · "
                + stoolSummary(amount: amount, consistency: consistency, color: color)
        case let .sleep(isNap, _):
            let prefix = isNap ? "午睡 · " : ""
            guard let end, end >= start else { return prefix + "进行中" }
            // Java Long subtraction wraps. Preserve that historical display
            // behavior without a Swift trap: overflowed/nonpositive minutes
            // are rendered as 0m by Android's formatRecordDuration.
            let minutes = (end &- start) / 60_000
            return prefix + "时长 " + durationText(UInt64(max(0, minutes)))
        case let .temperature(celsius):
            return normalizedNumber(celsius) + "℃"
        case let .diary(body):
            return body
        case .bath, .walk:
            return ""
        case let .symptom(_, severity, description):
            let severityLabel = [1: "轻微", 2: "一般", 3: "明显"][severity] ?? ""
            return joinVisible([severityLabel, description ?? ""])
        case let .medicine(name, dose):
            return joinVisible([name, dose ?? ""])
        case let .hospital(reason, advice):
            return joinVisible([reason, advice ?? ""])
        case let .measurement(kind, value, unit):
            if kind == .weight {
                return normalizedNumber(unit == "g" ? value / 1_000 : value) + "kg"
            }
            return normalizedNumber(value) + unit
        case let .food(_, content, amount):
            return joinVisible([content, amount ?? ""])
        case let .vaccine(name, batch):
            return joinVisible([name, batch ?? ""])
        case let .custom(title, detail, _):
            return joinVisible([title, detail ?? ""])
        }
    }

    private static func peeAmountLabel(_ level: Int) -> String {
        ["小", "中", "大"][min(3, max(1, level)) - 1]
    }

    private static func stoolSummary(amount: Int, consistency: Int, color: Int) -> String {
        let amount = ["一点", "偏少", "正常", "偏多"][min(4, max(1, amount)) - 1]
        let consistency = ["稀", "偏软", "正常", "偏硬"][min(4, max(1, consistency)) - 1]
        let color = ["未选", "白", "黄", "橙", "褐", "绿", "红", "黑"][min(7, max(0, color))]
        return "便量\(amount) · \(consistency) · \(color)"
    }

    private static func durationText(_ minutes: UInt64) -> String {
        let hours = minutes / 60
        let remaining = minutes % 60
        if hours == 0 { return "\(remaining)m" }
        if remaining == 0 { return "\(hours)h" }
        return "\(hours)h\(remaining)m"
    }

    /// RecordSummary.kt's Double.normalizedText: integral values use Java's
    /// saturating Double.toLong; fractions use decimal HALF_UP to two places.
    /// Binary printf rounding differs at values such as 2.675 and 36.555.
    private static func normalizedNumber(_ value: Double) -> String {
        if value.isNaN { return "NaN" }
        if value == .infinity { return "Infinity" }
        if value == -.infinity { return "-Infinity" }
        if value.truncatingRemainder(dividingBy: 1) == 0 {
            if value >= Double(Int64.max) { return String(Int64.max) }
            if value <= Double(Int64.min) { return String(Int64.min) }
            return String(Int64(value))
        }
        // Also avoids Decimal's limited exponent range for tiny subnormals.
        if abs(value) < 0.005 { return value < 0 ? "-0" : "0" }
        // Every remaining value is finite, nonintegral, and in Decimal's range.
        var decimal = Decimal(string: String(value), locale: Locale(identifier: "en_US_POSIX"))!
        var rounded = Decimal()
        NSDecimalRound(&rounded, &decimal, 2, .plain)
        return NSDecimalNumber(decimal: rounded).stringValue
    }
}

private extension RecordType {
    var searchBusinessLabel: String {
        switch self {
        case .nursing: "母乳"
        case .formula: "配方奶"
        case .pumpedFeed: "母乳瓶喂"
        case .pumpExpress: "挤奶"
        case .pee: "尿尿"
        case .poop: "便便"
        case .bothDiaper: "尿+便"
        case .sleep: "睡眠"
        case .temperature: "体温"
        case .diary: "日记"
        case .bath: "洗澡"
        case .walk: "散步"
        case .cough: "咳嗽"
        case .rash: "发疹"
        case .vomit: "呕吐"
        case .injury: "受伤"
        case .medicine: "用药"
        case .hospital: "就医"
        case .height: "身高"
        case .weight: "体重"
        case .babyFood: "辅食"
        case .snack: "点心"
        case .drink: "饮料"
        case .head: "头围"
        case .chest: "胸围"
        case .footSize: "足长"
        case .vaccine: "疫苗"
        case .custom: "自定义"
        }
    }

    var searchTerms: [String] {
        let aliases: [String]
        switch self {
        case .nursing: aliases = ["哺乳", "亲喂", "nursing"]
        case .formula: aliases = ["奶粉", "奶量", "formula"]
        case .pumpedFeed: aliases = ["喂挤出乳", "瓶喂母乳", "pumped feed"]
        case .pumpExpress: aliases = ["吸奶", "pump express"]
        case .pee: aliases = ["小便", "换尿布", "pee"]
        case .poop: aliases = ["大便", "换尿布", "poop"]
        case .bothDiaper: aliases = ["尿便", "换尿布", "both diaper"]
        case .sleep: aliases = ["睡觉", "午睡", "sleep"]
        case .temperature: aliases = ["温度", "temperature"]
        case .diary: aliases = ["正文", "diary"]
        case .bath: aliases = ["沐浴", "bath"]
        case .walk: aliases = ["外出", "walk"]
        case .cough: aliases = ["cough"]
        case .rash: aliases = ["皮疹", "rash"]
        case .vomit: aliases = ["吐奶", "vomit"]
        case .injury: aliases = ["外伤", "injury"]
        case .medicine: aliases = ["药物", "medicine"]
        case .hospital: aliases = ["医院", "看医生", "hospital"]
        case .height: aliases = ["height"]
        case .weight: aliases = ["weight"]
        case .babyFood: aliases = ["baby food"]
        case .snack: aliases = ["零食", "snack"]
        case .drink: aliases = ["喝水", "drink"]
        case .head: aliases = ["head"]
        case .chest: aliases = ["chest"]
        case .footSize: aliases = ["脚长", "foot size"]
        case .vaccine: aliases = ["接种", "vaccine"]
        case .custom: aliases = ["custom"]
        }
        return [searchBusinessLabel] + aliases
    }
}
