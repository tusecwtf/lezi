import Foundation

public struct BabyFoodStage: Equatable, Sendable {
    public let id: String
    public let minMonthInclusive: Int
    public let maxMonthExclusive: Int?
    public let title: String
    public let explanation: String
    public let suggestions: [String]
}

/// Exact product-content port of Android BabyFoodGuidance.kt, not an independent
/// medical or nutritional review. Always present the source disclaimer with tips.
public struct BabyFoodGuidance: Equatable, Sendable {
    public let monthAge: Int
    public let ageLabel: String
    public let stage: BabyFoodStage

    public static let disclaimer = "仅供参考，不构成医疗或营养诊疗建议。请结合宝宝发育与儿保/儿科指导调整。"
    public static let stages: [BabyFoodStage] = [
        .init(id: "pre6", minMonthInclusive: 0, maxMonthExclusive: 6,
              title: "6 月龄前 · 以奶为主",
              explanation: "多数公共营养指南建议满约 6 月龄再引入辅食，此前以母乳或配方奶满足营养。个别宝宝可按儿保医生评估提前或延后。若已开始尝试，请每次只加一种新食物，并观察皮肤、大便与精神状态。",
              suggestions: []),
        .init(id: "m6_7", minMonthInclusive: 6, maxMonthExclusive: 8,
              title: "6–7 月龄 · 泥糊起步",
              explanation: "此阶段可从每天 1 次、少量泥糊开始，优先富铁食物（如高铁米粉），再逐步尝试单一蔬果泥。新食物建议间隔数天再换下一种，便于观察耐受。奶量仍是主食，辅食是补充。",
              suggestions: ["高铁米粉", "南瓜泥", "西葫芦泥", "土豆泥", "苹果泥（蒸）", "胡萝卜泥"]),
        .init(id: "m8_9", minMonthInclusive: 8, maxMonthExclusive: 10,
              title: "8–9 月龄 · 增加性状与蛋白",
              explanation: "可把泥糊调稠，加入碎菜、蛋黄与肉泥等优质蛋白，并继续保证铁的摄入。一次仍以少量尝试为主，避免整粒坚果、整颗葡萄等易噎食物。",
              suggestions: ["稠粥", "蛋黄", "肉泥", "碎菜粥", "豆腐泥", "香蕉泥"]),
        .init(id: "m10_11", minMonthInclusive: 10, maxMonthExclusive: 12,
              title: "10–11 月龄 · 小块与手指食物",
              explanation: "可过渡到软饭、小块蔬菜与适合抓握的手指食物，练习咀嚼与手部协调。继续多样化，注意切小、煮软；家长陪同进食，警惕呛噎。",
              suggestions: ["软饭", "小块蔬菜", "手指食物", "碎肉末", "果泥", "鸡蛋羹"]),
        .init(id: "m12_plus", minMonthInclusive: 12, maxMonthExclusive: nil,
              title: "12 月龄及以上 · 家庭餐软食化",
              explanation: "可逐步靠拢家庭餐，把菜肴切小煮软，减少额外盐糖；奶类仍可保留。继续鼓励自主进食，留意食物多样性与过敏史。",
              suggestions: ["家庭餐软食", "软面条", "小馄饨", "碎菜肉末", "手指点心", "继续奶类"]),
    ]

    /// Java Period.between(birth, recordLocalDate).toTotalMonths for nonnegative
    /// age: a month is complete only when its day number reaches the birth day.
    /// Jan 31 → Feb 28 is zero months, unlike plusMonths month-end clamping.
    public static func at(birthday: String?, recordTimestampMillis: Int64, zone: TimeZone) -> Self? {
        guard let birth = GregorianRecordDate.birthday(birthday),
              let day = GregorianRecordDate.localDate(timestampMillis: recordTimestampMillis, zone: zone) else { return nil }
        let calendar = GregorianRecordDate.calendar
        let b = calendar.dateComponents([.year, .month, .day], from: birth)
        let d = calendar.dateComponents([.year, .month, .day], from: day)
        guard let by = b.year, let bm = b.month, let bd = b.day,
              let dy = d.year, let dm = d.month, let dd = d.day else { return nil }
        let months = day < birth ? 0 : max(0, (dy - by) * 12 + dm - bm - (dd < bd ? 1 : 0))
        let label = months <= 0 ? "未满 1 月龄" : months >= 36 ? "约 \(months / 12) 岁\(months % 12) 月龄" : "约 \(months) 月龄"
        let stage = stages.first { months >= $0.minMonthInclusive && ($0.maxMonthExclusive == nil || months < $0.maxMonthExclusive!) } ?? stages[stages.count - 1]
        return .init(monthAge: months, ageLabel: label, stage: stage)
    }
}
