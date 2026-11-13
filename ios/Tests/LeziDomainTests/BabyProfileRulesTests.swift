import XCTest
import LeziCore

final class BabyProfileRulesTests: XCTestCase {
    func testBirthWeightPreservesAndroidDoubleTruncationAndOptionalInput() {
        let cases: [(String, Int?)] = [
            ("", nil), (" \t\n ", nil), ("\u{001C}\u{00A0}", nil),
            ("0.5", 500), (".5", 500), ("3", 3_000), ("3.20", 3_200),
            (" \t3.20\n ", 3_200), ("3.2019", 3_201), ("4.015", 4_014),
            ("9", 9_000), ("9.", 9_000), ("9.0009", 9_000),
            ("0003.200", 3_200),
        ]
        for (raw, grams) in cases {
            XCTAssertEqual(BabyProfileRules.parseBirthWeightKilograms(raw), .valid(grams), raw)
        }
    }

    func testBirthWeightRejectsUnsupportedCharactersWithoutRewritingThem() {
        for raw in [
            "-3.20", "+3.20", "3,20", "３.２０", "3.2kg", "3.2 千克",
            "3..20", ".", "3 20", "3\n20", "3e0", "3E0", "NaN", "Infinity",
            "١.٥", "三", String(repeating: "9", count: 400), "\u{0085}3.2",
            "3.2\0", "3.2\n4", "3.2\u{200B}",
        ] {
            XCTAssertEqual(BabyProfileRules.parseBirthWeightKilograms(raw),
                           .invalid("出生体重格式不正确，请填写数字（如 3.20）"), raw)
        }
    }

    func testBirthWeightRejectsRangeAfterTruncationWithoutIntegerOverflow() {
        for raw in ["0", "0.4999", "9.001", "99999999999999999", String(repeating: "9", count: 308)] {
            XCTAssertEqual(BabyProfileRules.parseBirthWeightKilograms(raw),
                           .invalid("出生体重需在 500–9000 克之间"), raw)
        }
        XCTAssertNil(BabyProfileRules.birthWeightValidationError(nil))
        for grams in [500, 3_200, 9_000] { XCTAssertNil(BabyProfileRules.birthWeightValidationError(grams)) }
        for grams in [Int.min, -1, 0, 499, 9_001, Int.max] {
            XCTAssertEqual(BabyProfileRules.birthWeightValidationError(grams), "出生体重需在 500–9000 克之间")
        }
    }

    func testSexUsesAndroidCanonicalAliasesAndUnknownIsAbsent() {
        for raw in ["male", "MALE", "m", " M ", "男", "男宝", "\u{001C}Male\u{00A0}"] {
            XCTAssertEqual(BabyProfileRules.normalizedSex(raw), "male", raw)
        }
        for raw in ["female", "FEMALE", "f", " F ", "女", "女宝", "\u{001F}Female\u{3000}"] {
            XCTAssertEqual(BabyProfileRules.normalizedSex(raw), "female", raw)
        }
        for raw in [nil, "", " \n", "UNKNOWN", "unknown", "未设置", "女孩", "\u{0085}female"] as [String?] {
            XCTAssertNil(BabyProfileRules.normalizedSex(raw))
        }
    }

    func testNicknameUsesAndroidWhitespaceFallbackAndScalarLimit() throws {
        XCTAssertEqual(try BabyProfileRules.normalizedNickname("\u{001C}\u{00A0} \n\u{3000}"), "年年")
        XCTAssertEqual(try BabyProfileRules.normalizedNickname("  ", fallback: "宝宝"), "宝宝")
        XCTAssertEqual(try BabyProfileRules.normalizedNickname("  ", fallback: "  宝宝  "), "  宝宝  ")
        XCTAssertEqual(try BabyProfileRules.normalizedNickname("\u{0085}"), "\u{0085}")
        XCTAssertEqual(try BabyProfileRules.normalizedNickname(" \u{200B} "), "\u{200B}")
        XCTAssertEqual(try BabyProfileRules.normalizedNickname("尾\0声"), "尾\0声")
        let twenty = String(repeating: "🌱", count: 20)
        XCTAssertEqual(try BabyProfileRules.normalizedNickname(" " + twenty + "\u{001F}"), twenty)
        for raw in [twenty + "🌱", String(repeating: "e\u{0301}", count: 11)] {
            XCTAssertThrowsError(try BabyProfileRules.normalizedNickname(raw)) {
                XCTAssertEqual($0 as? BabyProfileValidationError, .nicknameTooLong)
            }
            XCTAssertEqual(BabyProfileRules.nicknameValidationError(raw), "宝宝昵称最多 20 个字符")
        }
        XCTAssertNil(BabyProfileRules.nicknameValidationError(twenty))
        XCTAssertThrowsError(try BabyProfileRules.normalizedNickname("", fallback: twenty + "多"))
        // Kotlin does not NFC-normalize nicknames.
        XCTAssertEqual(Array(try BabyProfileRules.normalizedNickname("e\u{0301}").unicodeScalars), Array("e\u{0301}".unicodeScalars))
    }

    func testBirthdayIsStrictGregorianDateOnlyWithoutFutureRestriction() {
        for raw in ["0001-01-01", "1582-10-10", "1600-02-29", "2000-02-29", "2024-02-29",
                    "2026-10-20", "2099-12-31", "9999-12-31"] {
            XCTAssertTrue(BabyProfileRules.validBirthday(raw), raw)
        }
        for raw in ["", "0000-01-01", "10000-01-01", "1900-02-29", "2100-02-29",
                    "2025-02-29", "2026-04-31", "2026-00-01", "2026-13-01", "2026-01-00",
                    "2026-1-01", "2026-01-1", "2026/01/01", " 2026-01-01", "2026-01-01\n",
                    "２０２６-01-01", "2026-01-01T00:00:00Z", "2026-01-01\0"] {
            XCTAssertFalse(BabyProfileRules.validBirthday(raw), raw)
        }
    }
}
