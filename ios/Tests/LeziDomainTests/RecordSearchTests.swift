import XCTest
@testable import LeziCore

final class RecordSearchTests: XCTestCase {
    private func content(_ payload: RecordPayload, start: Int64 = 0, end: Int64? = nil,
                         note: String? = nil) -> RecordSearchContent {
        RecordSearchContent(rawType: payload.type.rawValue, payload: payload,
            timestampMillis: start, endTimestampMillis: end, note: note)
    }

    private func matches(_ value: RecordSearchContent, _ query: String) -> Bool {
        value.matches(normalizedQuery: RecordSearchRules.normalizedQuery(query))
    }

    func testEveryTypeSearchesItsCanonicalLabelAndAliases() {
        let fixtures: [(RecordType, String, [String])] = [
            (.nursing, "母乳", ["哺乳", "亲喂", "nursing"]),
            (.formula, "配方奶", ["奶粉", "奶量", "formula"]),
            (.pumpedFeed, "母乳瓶喂", ["喂挤出乳", "瓶喂母乳", "pumped feed"]),
            (.pumpExpress, "挤奶", ["吸奶", "pump express"]),
            (.pee, "尿尿", ["小便", "换尿布", "pee"]),
            (.poop, "便便", ["大便", "换尿布", "poop"]),
            (.bothDiaper, "尿+便", ["尿便", "换尿布", "both diaper"]),
            (.sleep, "睡眠", ["睡觉", "午睡", "sleep"]),
            (.temperature, "体温", ["温度", "temperature"]),
            (.diary, "日记", ["正文", "diary"]),
            (.bath, "洗澡", ["沐浴", "bath"]),
            (.walk, "散步", ["外出", "walk"]),
            (.cough, "咳嗽", ["cough"]),
            (.rash, "发疹", ["皮疹", "rash"]),
            (.vomit, "呕吐", ["吐奶", "vomit"]),
            (.injury, "受伤", ["外伤", "injury"]),
            (.medicine, "用药", ["药物", "medicine"]),
            (.hospital, "就医", ["医院", "看医生", "hospital"]),
            (.height, "身高", ["height"]),
            (.weight, "体重", ["weight"]),
            (.babyFood, "辅食", ["baby food"]),
            (.snack, "点心", ["零食", "snack"]),
            (.drink, "饮料", ["喝水", "drink"]),
            (.head, "头围", ["head"]),
            (.chest, "胸围", ["chest"]),
            (.footSize, "足长", ["脚长", "foot size"]),
            (.vaccine, "疫苗", ["接种", "vaccine"]),
            (.custom, "自定义", ["custom"]),
        ]
        XCTAssertEqual(Set(fixtures.map(\.0)), Set(RecordType.allCases))
        for (type, label, aliases) in fixtures {
            let value = RecordSearchContent(rawType: type.rawValue, payload: nil,
                timestampMillis: 0, endTimestampMillis: nil, note: nil)
            XCTAssertEqual(value.label, label, type.rawValue)
            for query in [label] + aliases {
                XCTAssertTrue(matches(value, query), "\(type): \(query)")
            }
        }
    }

    func testLatinAliasesUsePrefixesAndWordsNotShortMiddleSubstrings() {
        let cases: [(String, String, Bool)] = [
            ("pee", "e", false), ("sleep", "e", false), ("formula", "a", false),
            ("bath", "a", false), ("pee", "p", true), ("pee", "pe", true),
            ("pee", "pee", true), ("formula", "form", true), ("both diaper", "diaper", true),
            ("pumped feed", "pump", true), ("pumped feed", "feed", true),
            ("baby food", "food", true), ("foot size", "size", true),
            ("ml", "120ml", true), ("kg", "6.35kg", true), ("ml", "ml", true),
            ("ml", "m", true), ("ml", "l", false), ("pee", "sleepee", true),
            ("pee", "peeing", true), ("pee", "", false), ("", "pee", false),
            ("PEE", "Pe", true),
        ]
        for (term, query, expected) in cases {
            XCTAssertEqual(RecordSearchRules.typeTermMatchesQuery(term, query: query),
                expected, "\(term): \(query)")
        }
        XCTAssertFalse(matches(content(.pee()), "e"))
        XCTAssertFalse(matches(content(.bath), "a"))
        XCTAssertTrue(matches(content(.milk(kind: .pumpedFeed)), "feed"))
    }

    func testChineseAliasesKeepBidirectionalSubstringMatching() {
        for (term, query, expected) in [
            ("尿尿", "尿", true), ("换尿布", "尿布", true),
            ("配方奶", "配方", true), ("睡眠", "xyz", false),
            ("配方奶", "记录配方奶", true),
        ] {
            XCTAssertEqual(RecordSearchRules.typeTermMatchesQuery(term, query: query), expected)
        }
        XCTAssertTrue(matches(content(.bothDiaper()), "尿布"))
        XCTAssertTrue(matches(content(.sleep()), "午睡")) // Alias also applies to non-nap Sleep.
    }

    func testKnownPayloadsProduceAndroidBusinessSummaries() {
        let fixtures: [(RecordPayload, String)] = [
            (.nursing(leftMinutes: 10, rightMinutes: 5, order: "LR", amountMl: 120),
             "左10分 · 右5分 · 先左后右 · 120ml"),
            (.milk(kind: .formula, amountMl: 120, preparedMl: 150, durationMinutes: 8),
             "120ml · 冲调量150ml · 耗时 8m"),
            (.milk(kind: .pumpedFeed, amountMl: 120), "120ml"),
            (.milk(kind: .pumpExpress, amountMl: 90), "90ml"),
            (.pee(amount: 3), "尿量大"),
            (.stool(amount: 2, consistency: 1, color: 2), "便量偏少 · 稀 · 黄"),
            (.bothDiaper(peeAmount: 1, stoolAmount: 4, stoolConsistency: 4, stoolColor: 7),
             "尿量小 · 便量偏多 · 偏硬 · 黑"),
            (.sleep(isNap: true, anomaly: true), "午睡 · 进行中"),
            (.temperature(celsius: 37.5), "37.5℃"),
            (.diary(body: " 今天发烧了\n"), " 今天发烧了\n"),
            (.bath, ""), (.walk, ""),
            (.symptom(kind: .cough, severity: 1, description: "夜间"), "轻微 · 夜间"),
            (.symptom(kind: .rash, severity: 2), "一般"),
            (.symptom(kind: .vomit, severity: 3, description: "奶后"), "明显 · 奶后"),
            (.symptom(kind: .injury, severity: 1), "轻微"),
            (.medicine(name: "布洛芬", dose: "2.5ml"), "布洛芬 · 2.5ml"),
            (.hospital(reason: "复诊", advice: "观察"), "复诊 · 观察"),
            (.measurement(kind: .height, value: 66.5, unit: "cm"), "66.5cm"),
            (.measurement(kind: .weight, value: 6350, unit: "g"), "6.35kg"),
            (.measurement(kind: .head, value: 40, unit: "cm"), "40cm"),
            (.measurement(kind: .chest, value: 41, unit: "cm"), "41cm"),
            (.measurement(kind: .footSize, value: 10.2, unit: "cm"), "10.2cm"),
            (.food(kind: .babyFood, content: "米糊", amount: "半碗"), "米糊 · 半碗"),
            (.food(kind: .snack, content: "饼干", amount: "2块"), "饼干 · 2块"),
            (.food(kind: .drink, content: "水", amount: "30ml"), "水 · 30ml"),
            (.vaccine(name: "乙肝", batch: "批次A"), "乙肝 · 批次A"),
            (.custom(titleSnapshot: "旧标题", detail: "旧详情", iconSlot: 3), "旧标题 · 旧详情"),
        ]
        XCTAssertEqual(Set(fixtures.map { $0.0.type }), Set(RecordType.allCases))
        for (payload, expected) in fixtures {
            let value = content(payload)
            XCTAssertEqual(value.summary, expected, payload.type.rawValue)
            XCTAssertEqual(value.visibleText, expected, payload.type.rawValue)
            if !expected.isEmpty { XCTAssertTrue(matches(value, expected), expected) }
        }
    }

    func testNursingOrderCopyAndDurationModesDoNotLeakInternalFields() {
        for (order, expected) in [("L", "仅左侧"), ("R", "仅右侧"), ("LR", "先左后右"), ("RL", "先右后左")] {
            let value = content(.nursing(leftMinutes: 10, rightMinutes: 0, order: order, recordMode: "start"))
            XCTAssertEqual(value.summary, "左10分 · 右0分 · \(expected)")
            XCTAssertTrue(matches(value, "左10分"))
            XCTAssertTrue(matches(value, expected))
            for query in ["left_min", "right_min", "record_mode", "start", "amount_ml"] {
                XCTAssertFalse(matches(value, query), query)
            }
        }
        XCTAssertEqual(content(.nursing(order: "unknown")).summary, "左0分 · 右0分")
    }

    func testDiaperLabelsClampAllHistoricalOutOfRangeValues() {
        XCTAssertEqual(content(.pee(amount: .min)).summary, "尿量小")
        XCTAssertEqual(content(.pee(amount: .max)).summary, "尿量大")
        XCTAssertEqual(content(.stool(amount: .min, consistency: .min, color: .min)).summary, "便量一点 · 稀 · 未选")
        XCTAssertEqual(content(.stool(amount: .max, consistency: .max, color: .max)).summary, "便量偏多 · 偏硬 · 黑")
        for (amount, expected) in [(1, "一点"), (2, "偏少"), (3, "正常"), (4, "偏多")] {
            XCTAssertTrue(content(.stool(amount: amount)).summary.hasPrefix("便量" + expected))
        }
        for (color, expected) in ["未选", "白", "黄", "橙", "褐", "绿", "红", "黑"].enumerated() {
            XCTAssertTrue(content(.stool(color: color)).summary.hasSuffix(" · " + expected))
        }
    }

    func testCustomHistoryUsesSavedTitleWithoutDefinitionLookup() {
        let value = content(.custom(titleSnapshot: " \u{001C}旧名称\u{001F} ", detail: " 旧详情 ", iconSlot: 8))
        XCTAssertEqual(value.label, "旧名称")
        XCTAssertEqual(value.summary, " \u{001C}旧名称\u{001F}  ·  旧详情 ")
        XCTAssertTrue(matches(value, "旧名称"))
        XCTAssertTrue(matches(value, "旧详情"))
        XCTAssertTrue(matches(value, "自定义"))
        for query in ["新名称", "title", "detail", "icon_slot"] {
            XCTAssertFalse(matches(value, query), query)
        }
        // This happens to start with the public "custom" alias. It matches the
        // type, not a raw JSON key; the key itself is never in visible content.
        XCTAssertTrue(matches(value, "custom_item_id"))
        XCTAssertFalse(value.visibleText.contains("custom_item_id"))
        XCTAssertEqual(content(.custom(titleSnapshot: "\u{001C} \t")).label, "自定义")
        XCTAssertEqual(content(.custom(titleSnapshot: "\u{0085}")).label, "\u{0085}")
    }

    func testVisibleTextTrimsNotesButPreservesPayloadTextAndDropsOnlyAndroidBlankParts() {
        let value = content(.medicine(name: " 布洛芬 ", dose: "\u{001C}\t"), note: "\u{001C}  晚间  \u{001F}")
        XCTAssertEqual(value.summary, " 布洛芬 ")
        XCTAssertEqual(value.visibleText, " 布洛芬  · 晚间")
        XCTAssertTrue(matches(value, "布洛芬"))
        XCTAssertTrue(matches(value, "晚间"))
        XCTAssertFalse(matches(value, "布洛芬晚间"))
        XCTAssertEqual(content(.bath, note: " \t\u{001C}").visibleText, "")
        XCTAssertEqual(content(.bath, note: " \u{0085} ").visibleText, "\u{0085}")
        XCTAssertEqual(content(.symptom(kind: .cough, severity: 0, description: " \t")).summary, "")
        XCTAssertEqual(content(.food(kind: .snack, content: "\u{001C}", amount: "\u{0085}")).summary, "\u{0085}")
        XCTAssertEqual(content(.hospital(reason: "\t", advice: " 观察 ")).summary, " 观察 ")
        XCTAssertEqual(content(.vaccine(name: "乙肝", batch: " \t")).summary, "乙肝")
    }

    func testOpaqueDataExposesOnlySafeCopyAndVisibleNote() {
        let raw = #"{"amount_ml":120,"private_key":"hidden-secret","future":{"value":6350}}"#
        for version in [2, 99] {
            let doc = RecordPayloadCodec.decode(type: .formula, payloadJSON: raw, schemaVersion: version)
            XCTAssertNil(doc.payload)
            let value = RecordSearchContent(rawType: "formula", payload: doc.payload,
                timestampMillis: 0, endTimestampMillis: nil, note: "可见备注")
            XCTAssertEqual(value.label, "配方奶")
            XCTAssertEqual(value.summary, "未知格式（原始数据已保留）")
            XCTAssertEqual(value.visibleText, "未知格式（原始数据已保留） · 可见备注")
            for query in ["未知格式", "原始数据已保留", "配方奶", "可见备注"] {
                XCTAssertTrue(matches(value, query), query)
            }
            for query in ["amount_ml", "private_key", "hidden-secret", "future", "6350", "120ml"] {
                XCTAssertFalse(matches(value, query), query)
            }
            XCTAssertEqual(doc.rawJSON, raw)
        }
        let unknown = RecordSearchContent(rawType: "future_private_type", payload: nil,
            timestampMillis: 0, endTimestampMillis: nil, note: nil)
        XCTAssertEqual(unknown.label, "未知记录")
        XCTAssertFalse(matches(unknown, "future_private_type"))
        XCTAssertTrue(matches(unknown, "未知格式"))
    }

    func testUnitsMatchOnlyFinalVisibleCopyNotCandidateFieldsOrConversions() {
        let milk = content(.milk(kind: .formula, amountMl: 120))
        XCTAssertTrue(matches(milk, "120ml"))
        XCTAssertTrue(matches(milk, "ML"))
        XCTAssertFalse(matches(milk, "120毫升"))
        XCTAssertFalse(matches(milk, "amount_ml"))
        let weight = content(.measurement(kind: .weight, value: 6350, unit: "g"))
        for query in ["6.35", "6.35kg", "KG"] { XCTAssertTrue(matches(weight, query), query) }
        for query in ["6350", "6350g", "6.35公斤", "unit"] { XCTAssertFalse(matches(weight, query), query) }
        XCTAssertEqual(content(.measurement(kind: .weight, value: 6.35, unit: "kg")).summary, "6.35kg")
        // Android does not invent a conversion for any historical unit except "g".
        XCTAssertEqual(content(.measurement(kind: .weight, value: 6.35, unit: "lb")).summary, "6.35kg")
        XCTAssertEqual(content(.measurement(kind: .weight, value: 6350, unit: "G")).summary, "6350kg")
        XCTAssertEqual(content(.measurement(kind: .height, value: 50, unit: "厘米")).summary, "50厘米")
    }

    func testNormalizedNumbersUseJavaDecimalHalfUpAndTrimZeros() {
        let cases: [(Double, String)] = [
            (36, "36"), (36.5, "36.5"), (36.555, "36.56"), (2.675, "2.68"),
            (1.005, "1.01"), (-2.675, "-2.68"), (2.6749999999999994, "2.67"),
            (0.005, "0.01"), (-0.005, "-0.01"), (0.004999999999999999, "0"),
            (-0.004, "-0"), (-0.0, "0"), (.leastNonzeroMagnitude, "0"),
            (-.leastNonzeroMagnitude, "-0"), (99.999, "100"),
            (.greatestFiniteMagnitude, "9223372036854775807"),
            (-.greatestFiniteMagnitude, "-9223372036854775808"),
            (Double(Int64.max), "9223372036854775807"), (Double(Int64.min), "-9223372036854775808"),
            (.nan, "NaN"), (.infinity, "Infinity"), (-.infinity, "-Infinity"),
        ]
        for (number, expected) in cases {
            XCTAssertEqual(content(.temperature(celsius: number)).summary, expected + "℃", "\(number)")
        }
        XCTAssertEqual(content(.measurement(kind: .weight, value: 2675, unit: "g")).summary, "2.68kg")
    }

    func testSleepSummariesUseProjectedEndAndCompactTruncatedDuration() {
        let open = content(.sleep(), start: 1_000)
        XCTAssertEqual(open.summary, "进行中")
        XCTAssertTrue(matches(open, "进行中"))
        let closed = content(.sleep(), start: 1_000, end: 5_401_000)
        XCTAssertEqual(closed.summary, "时长 1h30m")
        XCTAssertFalse(matches(closed, "进行中"))
        for query in ["0", "30", "30m", "1h", "1h30m", "h30", "h", "m", "长 1h30m"] {
            XCTAssertTrue(matches(closed, query), query)
        }
        for query in ["home", "mother", "30分钟", "30.0", "is_nap", "anomaly_flag"] {
            XCTAssertFalse(matches(closed, query), query)
        }
        XCTAssertEqual(content(.sleep(isNap: true), start: 1, end: 1).summary, "午睡 · 时长 0m")
        XCTAssertEqual(content(.sleep(), start: 1, end: 60_000).summary, "时长 0m")
        XCTAssertEqual(content(.sleep(), start: 0, end: 60_000).summary, "时长 1m")
        XCTAssertEqual(content(.sleep(), start: 0, end: 7_200_000).summary, "时长 2h")
        XCTAssertEqual(content(.sleep(isNap: true), start: 10, end: 9).summary, "午睡 · 进行中")
    }

    func testSleepDurationPreservesJavaWrappingWithoutSwiftOverflowTrap() {
        XCTAssertEqual(content(.sleep(), start: .min, end: .max).summary, "时长 0m")
        XCTAssertEqual(content(.sleep(), start: .min, end: 0).summary, "时长 0m")
        XCTAssertEqual(content(.sleep(), start: 0, end: .max).summary, "时长 2562047788015h12m")
        XCTAssertEqual(content(.sleep(), start: .min, end: .min + 60_000).summary, "时长 1m")
        XCTAssertEqual(content(.sleep(), start: .max - 60_000, end: .max).summary, "时长 1m")
        XCTAssertEqual(content(.sleep(), start: .max, end: .min).summary, "进行中")
        XCTAssertEqual(content(.sleep(), start: -30_000, end: 30_000).summary, "时长 1m")
    }

    func testFormulaDurationUsesSharedCompactBusinessCopy() {
        for (minutes, expected) in [(0, "0m"), (-3, "0m"), (45, "45m"), (120, "2h"), (65, "1h5m"), (130, "2h10m")] {
            XCTAssertEqual(content(.milk(kind: .formula, amountMl: 120, durationMinutes: minutes)).summary,
                "120ml · 耗时 " + expected)
        }
        XCTAssertEqual(content(.milk(kind: .formula, amountMl: 120, preparedMl: 0, durationMinutes: 0)).summary,
            "120ml · 冲调量0ml · 耗时 0m")
    }

    func testSearchMetacharactersRemainLiteralIncludingSlashAndBackslash() {
        let value = content(.bath, note: #"配方 100% 浓度 a_b path\file and /folder/file"#)
        for query in ["100%", "100", "a_b", #"path\file"#, "%", "_", "\\", "/folder/"] {
            XCTAssertTrue(matches(value, query), query)
        }
        let other = content(.bath, note: "100ml formula axb pathfile folderfile")
        for query in ["100%", "a_b", #"path\file"#, "%", "_", "\\", "/folder/"] {
            XCTAssertFalse(matches(other, query), query)
        }
        XCTAssertFalse(matches(value, ""))
        XCTAssertFalse(matches(value, " \t\u{001C}"))
    }

    func testNormalizationUsesAndroidTrimAndLocaleIndependentFullLowercase() {
        XCTAssertEqual(RecordSearchRules.normalizedQuery("\u{001C} FORMULA \u{001F}"), "formula")
        XCTAssertEqual(RecordSearchRules.normalizedQuery("\u{00A0}𐐀 İ ΟΣ\u{2029}"), "𐐨 i\u{0307} ος")
        XCTAssertEqual(RecordSearchRules.normalizedQuery("\u{0085}A\u{0085}"), "\u{0085}a\u{0085}")
        XCTAssertEqual(RecordSearchRules.normalizedQuery("A  B"), "a  b")
        XCTAssertTrue(matches(content(.diary(body: "ΟΣ")), "ος"))
        XCTAssertFalse(matches(content(.diary(body: "ΟΣ")), "οσ"))
        XCTAssertTrue(matches(content(.diary(body: "İ")), "i\u{0307}"))
        XCTAssertTrue(matches(content(.diary(body: "𐐀")), "𐐨"))
    }

    func testLiteralMatchingDoesNotFoldCanonicalEquivalenceOrDiacritics() {
        let precomposed = content(.diary(body: "É"))
        let decomposed = content(.diary(body: "E\u{0301}"))
        XCTAssertTrue(matches(precomposed, "é"))
        XCTAssertFalse(matches(precomposed, "e\u{0301}"))
        XCTAssertFalse(matches(precomposed, "e"))
        XCTAssertTrue(matches(decomposed, "e\u{0301}"))
        XCTAssertTrue(matches(decomposed, "\u{0301}"))
        XCTAssertTrue(matches(decomposed, "e"))
        XCTAssertFalse(matches(decomposed, "é"))
        XCTAssertFalse(matches(content(.diary(body: "Straße")), "strasse"))
        XCTAssertFalse(matches(content(.diary(body: "ＡＢＣ")), "abc"))
    }

    func testQueryLimitsCountScalarsNotGraphemesOrUTF16Units() {
        let supplementary = String(repeating: "👶", count: 101)
        XCTAssertEqual(RecordSearchRules.limitQuery(supplementary), String(repeating: "👶", count: 100))
        XCTAssertEqual(RecordSearchRules.limitQuery(supplementary).unicodeScalars.count, 100)
        let combining = String(repeating: "e\u{0301}", count: 51)
        XCTAssertEqual(RecordSearchRules.limitQuery(combining).unicodeScalars.count, 100)
        XCTAssertEqual(RecordSearchRules.limitQuery("e\u{0301}", maxScalars: 1), "e")
        XCTAssertEqual(RecordSearchRules.limitQuery("👩‍👩‍👧‍👦", maxScalars: 2).unicodeScalars.map(\.value), [0x1F469, 0x200D])
        XCTAssertEqual(RecordSearchRules.limitQuery("abc", maxScalars: 0), "")
        XCTAssertEqual(RecordSearchRules.limitQuery("abc", maxScalars: -1), "")
        XCTAssertEqual(RecordSearchRules.limitQuery("abc", maxScalars: .max), "abc")
        XCTAssertEqual(RecordSearchRules.limitQuery(""), "")
        // Normalization and UI limiting are separate; case mapping may expand.
        XCTAssertEqual(RecordSearchRules.normalizedQuery(String(repeating: "İ", count: 100)).unicodeScalars.count, 200)
        XCTAssertEqual(RecordSearchRules.normalizedQuery(String(repeating: "A", count: 101)).count, 101)
    }

    func testPreviewAddsEllipsisOnlyWhenScalarLimitActuallyTruncates() {
        XCTAssertEqual(RecordSearchRules.queryPreview(""), "")
        XCTAssertEqual(RecordSearchRules.queryPreview(String(repeating: "👶", count: 30)), String(repeating: "👶", count: 30))
        XCTAssertEqual(RecordSearchRules.queryPreview(String(repeating: "👶", count: 31)), String(repeating: "👶", count: 30) + "…")
        let raw = String(repeating: "a", count: 29) + "e\u{0301}"
        XCTAssertEqual(RecordSearchRules.queryPreview(raw), String(repeating: "a", count: 29) + "e…")
        XCTAssertEqual(RecordSearchRules.queryPreview("  原样保留  "), "  原样保留  ")
    }
}
