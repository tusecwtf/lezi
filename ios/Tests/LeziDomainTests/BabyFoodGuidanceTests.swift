import Foundation
import XCTest
@testable import LeziCore

final class BabyFoodGuidanceTests: XCTestCase {
    private let utc = TimeZone(secondsFromGMT: 0)!

    func testCompletedMonthsFollowJavaPeriodRatherThanClampedAnniversaries() throws {
        let cases: [(String, String, Int)] = [
            ("2023-01-31", "2023-02-28", 0), ("2024-01-31", "2024-02-29", 0),
            ("2024-01-31", "2024-03-01", 1), ("2024-01-31", "2024-03-30", 1),
            ("2024-01-31", "2024-03-31", 2), ("2024-02-29", "2025-02-28", 11),
            ("2024-02-29", "2025-03-01", 12), ("2024-01-15", "2024-02-14", 0),
            ("2024-01-15", "2024-02-15", 1), ("2024-01-15", "2024-01-15", 0),
            ("2024-01-15", "2024-01-14", 0),
        ]
        for (birthday, day, expected) in cases {
            XCTAssertEqual(try guidance(birthday, day).monthAge, expected, "\(birthday) → \(day)")
        }
    }

    func testEveryStageBoundaryAndAgeLabels() throws {
        let cases = [("2024-06-30", 5, "pre6"), ("2024-07-01", 6, "m6_7"),
                     ("2024-08-31", 7, "m6_7"), ("2024-09-01", 8, "m8_9"),
                     ("2024-10-31", 9, "m8_9"), ("2024-11-01", 10, "m10_11"),
                     ("2024-12-31", 11, "m10_11"), ("2025-01-01", 12, "m12_plus")]
        for (day, age, stage) in cases {
            let value = try guidance("2024-01-01", day)
            XCTAssertEqual(value.monthAge, age)
            XCTAssertEqual(value.stage.id, stage)
            XCTAssertEqual(value.ageLabel, "约 \(age) 月龄")
        }
        XCTAssertEqual(try guidance("2024-01-01", "2024-01-01").ageLabel, "未满 1 月龄")
        XCTAssertEqual(try guidance("2024-01-01", "2026-12-01").ageLabel, "约 35 月龄")
        XCTAssertEqual(try guidance("2024-01-01", "2027-01-01").ageLabel, "约 3 岁0 月龄")
        XCTAssertEqual(try guidance("2024-01-01", "2027-03-01").ageLabel, "约 3 岁2 月龄")
    }

    func testRecordLocalDayControlsStageAcrossTimeZones() throws {
        let instant = millis("2024-07-01T01:00:00Z")
        let la = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let shanghai = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let earlier = try XCTUnwrap(BabyFoodGuidance.at(birthday: "2024-01-01", recordTimestampMillis: instant, zone: la))
        let later = try XCTUnwrap(BabyFoodGuidance.at(birthday: "2024-01-01", recordTimestampMillis: instant, zone: shanghai))
        XCTAssertEqual(earlier.monthAge, 5)
        XCTAssertEqual(earlier.stage.id, "pre6")
        XCTAssertEqual(later.monthAge, 6)
        XCTAssertEqual(later.stage.id, "m6_7")
    }

    func testMissingOrInvalidBirthdayHasNoGuidanceButFutureBirthdayHasZeroMonths() throws {
        for birthday: String? in [nil, "", "2023-02-29", "2024-02-30", "2024-1-01", "２０２４-01-01"] {
            XCTAssertNil(BabyFoodGuidance.at(birthday: birthday, recordTimestampMillis: millis("2024-03-01T12:00:00Z"), zone: utc))
        }
        let future = try guidance("2025-01-01", "2024-01-01")
        XCTAssertEqual(future.monthAge, 0)
        XCTAssertEqual(future.stage.id, "pre6")
    }

    func testProductStageCopyAndDisclaimerMatchAndroid() {
        let stages = BabyFoodGuidance.stages
        XCTAssertEqual(stages.map(\.id), ["pre6", "m6_7", "m8_9", "m10_11", "m12_plus"])
        XCTAssertEqual(stages.map(\.minMonthInclusive), [0, 6, 8, 10, 12])
        XCTAssertEqual(stages.map(\.maxMonthExclusive), [6, 8, 10, 12, nil])
        XCTAssertEqual(stages.map(\.title), ["6 月龄前 · 以奶为主", "6–7 月龄 · 泥糊起步", "8–9 月龄 · 增加性状与蛋白", "10–11 月龄 · 小块与手指食物", "12 月龄及以上 · 家庭餐软食化"])
        XCTAssertEqual(stages.map(\.suggestions), [[],
            ["高铁米粉", "南瓜泥", "西葫芦泥", "土豆泥", "苹果泥（蒸）", "胡萝卜泥"],
            ["稠粥", "蛋黄", "肉泥", "碎菜粥", "豆腐泥", "香蕉泥"],
            ["软饭", "小块蔬菜", "手指食物", "碎肉末", "果泥", "鸡蛋羹"],
            ["家庭餐软食", "软面条", "小馄饨", "碎菜肉末", "手指点心", "继续奶类"]])
        XCTAssertEqual(BabyFoodGuidance.disclaimer, "仅供参考，不构成医疗或营养诊疗建议。请结合宝宝发育与儿保/儿科指导调整。")
        XCTAssertEqual(stages.map(\.explanation), [
            "多数公共营养指南建议满约 6 月龄再引入辅食，此前以母乳或配方奶满足营养。个别宝宝可按儿保医生评估提前或延后。若已开始尝试，请每次只加一种新食物，并观察皮肤、大便与精神状态。",
            "此阶段可从每天 1 次、少量泥糊开始，优先富铁食物（如高铁米粉），再逐步尝试单一蔬果泥。新食物建议间隔数天再换下一种，便于观察耐受。奶量仍是主食，辅食是补充。",
            "可把泥糊调稠，加入碎菜、蛋黄与肉泥等优质蛋白，并继续保证铁的摄入。一次仍以少量尝试为主，避免整粒坚果、整颗葡萄等易噎食物。",
            "可过渡到软饭、小块蔬菜与适合抓握的手指食物，练习咀嚼与手部协调。继续多样化，注意切小、煮软；家长陪同进食，警惕呛噎。",
            "可逐步靠拢家庭餐，把菜肴切小煮软，减少额外盐糖；奶类仍可保留。继续鼓励自主进食，留意食物多样性与过敏史。"
        ])
    }

    private func guidance(_ birthday: String, _ day: String) throws -> BabyFoodGuidance {
        try XCTUnwrap(BabyFoodGuidance.at(birthday: birthday, recordTimestampMillis: millis(day + "T12:00:00Z"), zone: utc))
    }
    private func millis(_ instant: String) -> Int64 {
        Int64(ISO8601DateFormatter().date(from: instant)!.timeIntervalSince1970 * 1000)
    }
}
