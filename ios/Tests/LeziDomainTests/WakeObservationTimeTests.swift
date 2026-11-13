import Foundation
import XCTest
@testable import LeziCore

final class WakeObservationTimeTests: XCTestCase {
    // Expected epoch milliseconds were independently executed with Java 21:
    // var formatter = DateTimeFormatter.ofPattern("HH:mm");
    // var original = Instant.ofEpochMilli(originalMillis).atZone(zone);
    // original.toLocalDate().atTime(LocalTime.parse(text, formatter))
    //     .atZone(zone).toInstant().toEpochMilli();

    func testDisplayIsZeroPaddedHHmmInTheSuppliedZone() throws {
        let shanghai = try zone("Asia/Shanghai")
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: 1_792_440_896_789, zone: shanghai), "04:14")
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: 1_792_425_600_000, zone: shanghai), "00:00")
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: 1_792_511_940_000, zone: shanghai), "23:59")
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: 1_792_458_896_789,
                                                zone: try zone("America/Los_Angeles")), "18:14")
    }

    func testCorrectionKeepsOriginalLocalDateOnEitherSideOfUTC() throws {
        // Original 2026-10-19T20:14:56.789Z is October 20 in Shanghai.
        let shanghai = try zone("Asia/Shanghai")
        XCTAssertEqual(WakeObservationTime.resolve("00:00", originalTimestampMillis: 1_792_440_896_789,
                                                  zone: shanghai), 1_792_425_600_000)
        XCTAssertEqual(WakeObservationTime.resolve("23:59", originalTimestampMillis: 1_792_440_896_789,
                                                  zone: shanghai), 1_792_511_940_000)
        // Original 2026-10-20T01:14:56.789Z is October 19 in Los Angeles.
        XCTAssertEqual(WakeObservationTime.resolve("07:03", originalTimestampMillis: 1_792_458_896_789,
                                                  zone: try zone("America/Los_Angeles")), 1_792_418_580_000)
    }

    func testSmart24HourMidnightDoesNotAdvanceOriginalDate() throws {
        let shanghai = try zone("Asia/Shanghai")
        let original: Int64 = 1_792_440_896_789
        let midnight = WakeObservationTime.resolve("00:00", originalTimestampMillis: original, zone: shanghai)
        XCTAssertEqual(WakeObservationTime.resolve("24:00", originalTimestampMillis: original, zone: shanghai),
                       midnight)
        XCTAssertEqual(midnight, 1_792_425_600_000)
    }

    func testParserRejectsAnythingJavaHHmmRejectsWithoutTrimming() throws {
        let invalid = [
            "", "7:03", "07:3", "007:03", "2400", "24:01", "25:00", "23:60",
            " 07:03", "07:03 ", "07:03\n", "07:03:00", "+07:03", "-01:00",
            "０７:０３", "٠٧:٠٣", "07：03",
        ]
        let shanghai = try zone("Asia/Shanghai")
        for text in invalid {
            XCTAssertNil(WakeObservationTime.resolve(text, originalTimestampMillis: 1_792_440_896_789,
                                                    zone: shanghai), "Unexpectedly accepted \(text.debugDescription)")
        }
    }

    func testSpringGapMovesForwardByTheGapInsteadOfRejectingOrClamping() throws {
        // New York 2026-03-08 02:30 becomes 03:30, not 03:00.
        let newYork = try zone("America/New_York")
        let resolved = try XCTUnwrap(WakeObservationTime.resolve("02:30",
            originalTimestampMillis: 1_772_986_496_789, zone: newYork))
        XCTAssertEqual(resolved, 1_772_955_000_000)
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: resolved, zone: newYork), "03:30")
        XCTAssertNil(RecordTime.resolve(year: 2026, month: 3, day: 8, hour: 2, minute: 30, zone: newYork))
    }

    func testOverlapChoosesEarlierOffsetEvenIfOriginalUsesLaterOffset() throws {
        let newYork = try zone("America/New_York")
        // Original 2026-11-01T06:45:56.789Z is 01:45 at the later -05:00 offset.
        let resolved = try XCTUnwrap(WakeObservationTime.resolve("01:30",
            originalTimestampMillis: 1_793_515_556_789, zone: newYork))
        XCTAssertEqual(resolved, 1_793_511_000_000) // 01:30 at -04:00.
        XCTAssertEqual(newYork.secondsFromGMT(for: Date(timeIntervalSince1970: Double(resolved) / 1000)), -14_400)
    }

    func testNonHourGapAndOverlapMatchJava() throws {
        let lordHowe = try zone("Australia/Lord_Howe")
        // 2026-10-04 skips 02:00...02:29; 02:15 moves forward 30 minutes.
        let gap = try XCTUnwrap(WakeObservationTime.resolve("02:15",
            originalTimestampMillis: 1_791_075_600_000, zone: lordHowe))
        XCTAssertEqual(gap, 1_791_042_300_000)
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: gap, zone: lordHowe), "02:45")
        // 2026-04-05 repeats 01:30...01:59; choose +11:00, not +10:30.
        XCTAssertEqual(WakeObservationTime.resolve("01:45", originalTimestampMillis: 1_775_316_356_789,
                                                  zone: lordHowe), 1_775_313_900_000)
    }

    func testMidnightGapPreservesMinutes() throws {
        let havana = try zone("America/Havana")
        let resolved = try XCTUnwrap(WakeObservationTime.resolve("00:30",
            originalTimestampMillis: 1_772_989_200_000, zone: havana))
        XCTAssertEqual(resolved, 1_772_947_800_000)
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: resolved, zone: havana), "01:30")
    }

    func testCorrectionDiscardsOriginalSecondsAndMillisecondsIncludingBeforeEpoch() throws {
        let utc = try zone("UTC")
        let original: Int64 = -1 // 1969-12-31T23:59:59.999Z.
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: original, zone: utc), "23:59")
        XCTAssertEqual(WakeObservationTime.resolve("23:59", originalTimestampMillis: original, zone: utc), -60_000)
        let resolved = try XCTUnwrap(WakeObservationTime.resolve("20:14",
            originalTimestampMillis: 1_792_440_896_789, zone: utc))
        XCTAssertEqual(resolved, 1_792_440_840_000)
        XCTAssertEqual(resolved % 60_000, 0)
    }

    func testUnrepresentableOriginalDatesFailClosedInsteadOfUsingClampedDates() throws {
        for identifier in ["UTC", "Asia/Shanghai", "America/New_York"] {
            let zone = try zone(identifier)
            for timestamp: Int64 in [.min, .max, -9_000_000_000_000_000_000, 9_000_000_000_000_000_000,
                                      -1_000_000_000_000_000, 10_000_000_000_000_001] {
                XCTAssertEqual(WakeObservationTime.text(timestampMillis: timestamp, zone: zone), "")
                XCTAssertNil(WakeObservationTime.resolve("23:59", originalTimestampMillis: timestamp, zone: zone))
            }
        }
    }

    func testFractionalComponentRoundingDoesNotRejectRepresentableOriginal() throws {
        // Native nanosecond component roundtripping can differ by nanoseconds
        // near Foundation's reference epoch, without losing the source millisecond.
        let utc = try zone("UTC")
        XCTAssertEqual(WakeObservationTime.text(timestampMillis: 969_397_941_642, zone: utc), "21:12")
        XCTAssertEqual(WakeObservationTime.resolve("12:00", originalTimestampMillis: 969_397_941_642, zone: utc),
                       969_364_800_000)
    }

    func testHistoricalDateAndEraPreserveTheSameLocalDayAsJava() throws {
        // Native Gregorian labels before the reform can differ from Java ISO
        // labels. Extracting and reconstructing that same date still preserves
        // the correct day; the era must not be discarded.
        let vectors: [(Int64, Int64)] = [
            (-14_826_626_703_211, -14_826_585_660_000), // 1500-03-01.
            (-12_219_333_903_211, -12_219_292_860_000), // 1582-10-14.
            (-12_219_247_503_211, -12_219_206_460_000), // 1582-10-15.
            (-62_161_989_903_211, -62_161_948_860_000), // ISO year 0000.
            (-62_193_612_303_211, -62_193_571_260_000), // ISO year -0001.
        ]
        let utc = try zone("UTC")
        for (original, expected) in vectors {
            XCTAssertEqual(WakeObservationTime.text(timestampMillis: original, zone: utc), "12:34")
            XCTAssertEqual(WakeObservationTime.resolve("23:59", originalTimestampMillis: original, zone: utc), expected)
        }
        XCTAssertEqual(WakeObservationTime.resolve("23:59", originalTimestampMillis: -14_826_626_703_211,
                                                  zone: try zone("Asia/Shanghai")), -14_826_614_803_000)
    }

    private func zone(_ identifier: String) throws -> TimeZone {
        try XCTUnwrap(TimeZone(identifier: identifier))
    }
}
