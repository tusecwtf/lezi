import Foundation
import XCTest
@testable import LeziCore

final class TemperatureRulesTests: XCTestCase {
    private let utc = TimeZone(secondsFromGMT: 0)!

    func testCelsiusFahrenheitConversionAndStoredRangeRemainSeparate() throws {
        XCTAssertEqual(TemperatureRules.defaultCelsius, 36.5)
        XCTAssertEqual(try XCTUnwrap(TemperatureRules.convert(36.5, from: .celsius, to: .fahrenheit)), 97.7, accuracy: 1e-10)
        XCTAssertEqual(try XCTUnwrap(TemperatureRules.convert(100.4, from: .fahrenheit, to: .celsius)), 38, accuracy: 1e-10)
        XCTAssertEqual(TemperatureRules.convert(38, from: .celsius, to: .celsius), 38)
        let feverCelsius = try XCTUnwrap(TemperatureRules.convert(100.4, from: .fahrenheit, to: .celsius))
        XCTAssertTrue(advice("2024-01-01", "2024-01-02T12:00:00Z", feverCelsius))
        for celsius in [34.0, 43.0] {
            let fahrenheit = try XCTUnwrap(TemperatureRules.convert(celsius, from: .celsius, to: .fahrenheit))
            let roundtrip = try XCTUnwrap(TemperatureRules.convert(fahrenheit, from: .fahrenheit, to: .celsius))
            XCTAssertEqual(roundtrip, celsius, accuracy: 1e-10)
            XCTAssertTrue(RecordPayload.temperature(celsius: roundtrip).validationErrors().isEmpty)
        }
        XCTAssertFalse(RecordPayload.temperature(celsius: 33.9).validationErrors().isEmpty)
        XCTAssertFalse(RecordPayload.temperature(celsius: 43.1).validationErrors().isEmpty)
    }

    func testNonfiniteConversionsAndAdviceFailClosed() {
        for value in [Double.nan, .infinity, -.infinity] {
            XCTAssertNil(TemperatureRules.convert(value, from: .celsius, to: .fahrenheit))
            XCTAssertNil(TemperatureRules.convert(value, from: .fahrenheit, to: .celsius))
            XCTAssertNil(TemperatureRules.convert(value, from: .celsius, to: .celsius))
            XCTAssertFalse(advice("2024-01-01", "2024-01-02T12:00:00Z", value))
            XCTAssertFalse(RecordPayload.temperature(celsius: value).validationErrors().isEmpty)
        }
        XCTAssertNil(TemperatureRules.convert(.greatestFiniteMagnitude, from: .celsius, to: .fahrenheit))
    }

    func testExactThreeMonthsAndMonthEndClampingNotNinetyDays() {
        XCTAssertTrue(advice("2024-01-31", "2024-04-29T23:59:59Z"))
        XCTAssertFalse(advice("2024-01-31", "2024-04-30T00:00:00Z"))
        XCTAssertTrue(advice("2023-11-30", "2024-02-28T12:00:00Z"))
        XCTAssertFalse(advice("2023-11-30", "2024-02-29T00:00:00Z"))
        XCTAssertTrue(advice("2024-11-30", "2025-02-27T12:00:00Z"))
        XCTAssertFalse(advice("2024-11-30", "2025-02-28T00:00:00Z"))
        XCTAssertTrue(advice("2024-02-29", "2024-05-28T23:59:59Z"))
        XCTAssertFalse(advice("2024-02-29", "2024-05-29T00:00:00Z"))
        // July through September is 92 days; age 91 days is still under 3 months.
        XCTAssertTrue(advice("2024-07-01", "2024-09-30T12:00:00Z"))
    }

    func testBirthdayInclusionFutureBirthdayThresholdAndToggle() {
        XCTAssertTrue(advice("2024-01-01", "2024-01-01T00:00:00Z"))
        XCTAssertFalse(advice("2024-01-02", "2024-01-01T23:59:59Z"))
        XCTAssertFalse(advice("2024-01-01", "2024-01-02T12:00:00Z", 38.nextDown))
        XCTAssertTrue(advice("2024-01-01", "2024-01-02T12:00:00Z", 38))
        XCTAssertFalse(TemperatureRules.shouldShowInfantFeverAdvice(birthday: "2024-01-01", recordTimestampMillis: millis("2024-01-02T12:00:00Z"), celsius: 38, enabled: false, zone: utc))
        XCTAssertEqual(TemperatureRules.infantFeverAdviceText, "低月龄发热请及时就医。本提示仅供参考，不构成医疗诊断。")
    }

    func testRecordDateUsesExplicitTimeZoneAcrossBirthdayAndThreeMonthBoundary() throws {
        let la = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let shanghai = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let atBirthday = millis("2024-01-01T01:00:00Z")
        XCTAssertFalse(TemperatureRules.shouldShowInfantFeverAdvice(birthday: "2024-01-01", recordTimestampMillis: atBirthday, celsius: 38, zone: la))
        XCTAssertTrue(TemperatureRules.shouldShowInfantFeverAdvice(birthday: "2024-01-01", recordTimestampMillis: atBirthday, celsius: 38, zone: shanghai))
        let atThreeMonths = millis("2024-03-31T20:00:00Z")
        XCTAssertTrue(TemperatureRules.shouldShowInfantFeverAdvice(birthday: "2024-01-01", recordTimestampMillis: atThreeMonths, celsius: 38, zone: la))
        XCTAssertFalse(TemperatureRules.shouldShowInfantFeverAdvice(birthday: "2024-01-01", recordTimestampMillis: atThreeMonths, celsius: 38, zone: shanghai))
    }

    func testAbsentOrInvalidBirthdaysDoNotBecomeNormalizedDates() {
        for birthday: String? in [nil, "", "2023-02-29", "2024-02-30", "2024-13-01", "2024-00-01", "0000-01-01", "2024-1-01", "２０２４-01-01"] {
            XCTAssertFalse(TemperatureRules.shouldShowInfantFeverAdvice(birthday: birthday, recordTimestampMillis: millis("2024-03-01T12:00:00Z"), celsius: 38, zone: utc))
        }
    }

    private func advice(_ birthday: String, _ instant: String, _ celsius: Double = 38) -> Bool {
        TemperatureRules.shouldShowInfantFeverAdvice(birthday: birthday, recordTimestampMillis: millis(instant), celsius: celsius, zone: utc)
    }
    private func millis(_ instant: String) -> Int64 {
        Int64(ISO8601DateFormatter().date(from: instant)!.timeIntervalSince1970 * 1000)
    }
}
