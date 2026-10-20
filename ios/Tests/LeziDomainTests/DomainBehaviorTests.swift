import Foundation
import XCTest
@testable import LeziCore

final class DomainBehaviorTests: XCTestCase {
    func testFoodAmountsNormalizeOnlyUnambiguousLeadingNumbers() {
        XCTAssertEqual(FoodAmount.normalize("  半碗  "), "0.5碗")
        XCTAssertEqual(FoodAmount.normalize("２。５勺"), "2.5勺")
        XCTAssertEqual(FoodAmount.normalize("十几口"), "十几口")
        XCTAssertEqual(FoodAmount.normalize("一两勺"), "一两勺")
        XCTAssertEqual(FoodAmount.normalize("半2"), "半2")
        XCTAssertEqual(FoodAmount.normalize("半²"), "0.5²")
        XCTAssertEqual(FoodAmount.parseValue("2.5勺"), 2.5)
        XCTAssertNil(FoodAmount.parseValue("半碗"))
        XCTAssertNil(FoodAmount.parseValue("1,000g"))
        XCTAssertNil(FoodAmount.parseValue("1.2.3勺"))
        XCTAssertNil(FoodAmount.parseValue("1000001g"))
        XCTAssertEqual(FoodAmount.parseValue("1000000g"), 1000000)
    }
    func testConcreteCatalogPreservesAllAndroidTypesButRejectsBareCustom() {
        XCTAssertEqual(RecordType.allCases.count, 28)
        XCTAssertEqual(RecordType.availableForNewEntry.count, 27)
        XCTAssertNil(RecordItemIdentity.parseCatalogKey("custom"))
        XCTAssertNil(RecordItemIdentity.parseCatalogKey("custom:0"))
        XCTAssertNil(RecordItemIdentity.parseCatalogKey("custom:garbage"))
        XCTAssertEqual(RecordItemIdentity.parseCatalogKey("custom:42")?.legacyLocalCustomID, 42)
        XCTAssertEqual(RecordItemIdentity.parseCatalogKey("custom:４２")?.legacyLocalCustomID, 42)
        XCTAssertEqual(RecordItemIdentity.parseCatalogKey("custom: ABCDEFAB-1234-1234-1234-ABCDEFABCDEF ")?.catalogKey,
                       "custom:abcdefab-1234-1234-1234-abcdefabcdef")
        XCTAssertEqual(RecordItemIdentity.parseCatalogKey("pumped_feed")?.recordType, .pumpedFeed)
    }
    func testPayloadAndConfirmationValidationHaveDifferentIntentionalBoundaries() {
        XCTAssertTrue(RecordPayload.nursing(leftMinutes: 1500, amountMl: -1).validationErrors().isEmpty)
        XCTAssertEqual(NursingConfirmInput(leftMinutes: "1500", rightMinutes: "0", order: "LR").validationIssue()?.field, .duration)
        XCTAssertEqual(NursingConfirmInput(leftMinutes: "5", rightMinutes: "0", order: "LR", amountMl: "1000").validationIssue()?.field, .amount)
        XCTAssertNotNil(NursingConfirmInput(leftMinutes: "1.5", rightMinutes: "0", order: "LR").validationIssue())
        XCTAssertNil(NursingConfirmInput(leftMinutes: "１２", rightMinutes: "０", order: "L").validationIssue())
        XCTAssertNil(NursingConfirmInput(leftMinutes: "0", rightMinutes: "0", order: "R").validationIssue(allowIntentOnly: true))
        XCTAssertEqual(NursingConfirmInput(leftMinutes: "5", rightMinutes: "0", order: "L", amountMl: " ").payload(),
                       .nursing(leftMinutes: 5, rightMinutes: 0, order: "L", amountMl: nil, recordMode: "end"))
    }
    func testMilkIntentExcludesExpressionAndRangesMatchAndroid() {
        XCTAssertTrue(RecordPayload.milk(kind: .formula).validationErrors(allowIntentOnlyFeed: true).isEmpty)
        XCTAssertTrue(RecordPayload.milk(kind: .pumpedFeed).validationErrors(allowIntentOnlyFeed: true).isEmpty)
        XCTAssertEqual(RecordPayload.milk(kind: .pumpExpress).validationErrors(allowIntentOnlyFeed: true).count, 1)
        XCTAssertEqual(RecordPayload.milk(kind: .formula, amountMl: 1000, preparedMl: -1, durationMinutes: 1441).validationErrors().count, 3)
        XCTAssertTrue(RecordPayload.milk(kind: .formula, amountMl: 999, preparedMl: 0, durationMinutes: 1440).validationErrors().isEmpty)
    }
    func testStoolAndTemperatureBoundaries() {
        XCTAssertTrue(RecordPayload.bothDiaper(peeAmount: 3, stoolAmount: 4, stoolConsistency: 1, stoolColor: 7).validationErrors().isEmpty)
        XCTAssertEqual(RecordPayload.bothDiaper(peeAmount: 0, stoolAmount: 5, stoolConsistency: 0, stoolColor: 8).validationErrors().count, 4)
        XCTAssertFalse(RecordPayload.temperature(celsius: .nan).validationErrors().isEmpty)
        XCTAssertFalse(RecordPayload.temperature(celsius: 43.1).validationErrors().isEmpty)
        XCTAssertTrue(RecordPayload.temperature(celsius: 34).validationErrors().isEmpty)
    }
    func testWeightUsesGramsAndDoesNotInventConversionsForLegacyUnits() {
        XCTAssertEqual(GrowthMeasurementFacts.payload(kind: .weight, displayValue: 3.4565), .measurement(kind: .weight, value: 3457, unit: "g"))
        XCTAssertNil(GrowthMeasurementFacts.payload(kind: .weight, displayValue: 0.0001))
        XCTAssertNil(GrowthMeasurementFacts.payload(kind: .weight, displayValue: 100.1))
        XCTAssertNil(GrowthMeasurementFacts.payload(kind: .height, displayValue: .infinity))
        XCTAssertEqual(GrowthMeasurementFacts.displayValue(kind: .weight, value: 3000, unit: "g"), 3)
        XCTAssertEqual(GrowthMeasurementFacts.displayValue(kind: .weight, value: 3, unit: "kg"), 3)
    }
    func testDraftIsImmutableSnapshotAndRejectsFutureFactsAndWrongType() throws {
        let item = try XCTUnwrap(RecordItemIdentity.builtIn(.pee))
        let draft = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: UUID(), item: item,
            timestampMillis: 101, payload: .bath, photoClientUUIDs: (0..<4).map { _ in UUID() })
        XCTAssertEqual(draft.validationErrors(nowMillis: 100).count, 3)
        for end: Int64 in [49, 101] {
            let invalidInterval = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: UUID(), item: item,
                timestampMillis: 50, endTimestampMillis: end, payload: .pee())
            XCTAssertEqual(invalidInterval.validationErrors(nowMillis: 100).count, 1)
        }
    }
    func testWakeProjectionFiltersAndUsesDeterministicPrecedence() {
        let observations: [WakeObservationFact] = [
            .init(clientUUID: "before", wakeTimestamp: 99),
            .init(clientUUID: "withdrawn", wakeTimestamp: 100, withdrawn: true),
            .init(clientUUID: "deleted", wakeTimestamp: 100, deleted: true),
            .init(clientUUID: "b", wakeTimestamp: 100), .init(clientUUID: "a", wakeTimestamp: 100),
            .init(clientUUID: "chosen", wakeTimestamp: 200)]
        let selected = SleepProjection.project(sleepClientUUID: "s", startTimestamp: 100,
            effectiveWakeObservationClientUUID: " chosen ", observations: observations, legacyEndTimestamp: 300)
        XCTAssertEqual(selected.endTimestamp, 200)
        XCTAssertEqual(selected.endSource, .effective)
        XCTAssertEqual(selected.visibleObservations.map(\.clientUUID), ["a", "b", "chosen"])
        let fallback = SleepProjection.project(sleepClientUUID: "s", startTimestamp: 100,
            effectiveWakeObservationClientUUID: "withdrawn", observations: observations, legacyEndTimestamp: 300)
        XCTAssertEqual(fallback.endObservationClientUUID, "a")
        XCTAssertTrue(fallback.isProvisional)
        XCTAssertNil(SleepProjection.validateWakeTimestamp(sleepStart: 100, wake: 100))
        XCTAssertNotNil(RecordTime.intervalError(start: 100, end: 100, now: 100))
    }
    func testOpenOverlapAndLegacyEndDoNotRewriteFacts() {
        let projection = SleepProjection.project(sleepClientUUID: "a", startTimestamp: 100, peerOpenSleepStarts: [("b", 100)])
        XCTAssertTrue(projection.isOpen)
        XCTAssertTrue(projection.isOverlapPending)
        XCTAssertFalse(projection.isWakeShortcutTarget)
        let legacy = SleepProjection.project(sleepClientUUID: "a", startTimestamp: 100, legacyEndTimestamp: 50)
        XCTAssertEqual(legacy.endSource, .legacyEnd) // Android preserves historical fallback as supplied.
        XCTAssertEqual(legacy.endTimestamp, 50)
    }
    func testActualAndPlanTimeGates() {
        XCTAssertNotNil(RecordTime.pointError(timestamp: 101, now: 100))
        XCTAssertNil(RecordTime.pointError(timestamp: 300_100, now: 100, maxFutureSkewMillis: RecordTime.fulfillmentActualTimeSkewMillis))
        XCTAssertNotNil(RecordTime.pointError(timestamp: 300_101, now: 100, maxFutureSkewMillis: RecordTime.fulfillmentActualTimeSkewMillis))
        XCTAssertEqual(RecordTime.futureEventError(eventAt: 200, remindAt: 200, now: 100), .reminderNotBeforeEvent)
        XCTAssertEqual(RecordTime.shiftStartPreservingDuration(oldStart: 100, oldEnd: 150, newStart: 200), 250)
        XCTAssertNil(RecordTime.shiftStartPreservingDuration(oldStart: 100, oldEnd: 99, newStart: 200))
        XCTAssertEqual(RecordTime.snap(hour: 23, minute: 59, step: 5).minute, 55)
        XCTAssertEqual(RecordTime.snap(hour: 8, minute: 3, step: 5).minute, 5)
    }
    func testDSTGapOverlapAndNaturalDayDuration() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        XCTAssertNil(RecordTime.resolve(year: 2026, month: 3, day: 8, hour: 2, minute: 30, zone: zone))
        let early = try XCTUnwrap(RecordTime.resolve(year: 2026, month: 11, day: 1, hour: 1, minute: 30, zone: zone))
        let late = try XCTUnwrap(RecordTime.resolve(year: 2026, month: 11, day: 1, hour: 1, minute: 30, zone: zone, preferredOffsetSeconds: -18000))
        XCTAssertEqual(late.timeIntervalSince(early), 3600)
        XCTAssertEqual(RecordTime.dayInterval(containing: early, zone: zone)?.duration, 25 * 3600)
        let spring = try XCTUnwrap(RecordTime.resolve(year: 2026, month: 3, day: 8, hour: 12, minute: 0, zone: zone))
        XCTAssertEqual(RecordTime.dayInterval(containing: spring, zone: zone)?.duration, 23 * 3600)
    }
    func testPlanMarkerStatusAndPublicationReceiptRemainDistinct() {
        XCTAssertEqual(NextFeedPlanNote.encode("  喂奶  "), "[[lezi:next-feed:v1]] 喂奶")
        XCTAssertFalse(NextFeedPlanNote.isNextFeed(" [[lezi:next-feed:v1]]"))
        XCTAssertEqual(NextFeedPlanNote.visible("[[lezi:next-feed:v1]]  喂奶  "), "喂奶  ")
        XCTAssertEqual(CarePlanStatus.pending.effectiveStatus(scheduledAt: 100, now: 100), .pending)
        XCTAssertEqual(CarePlanStatus.pending.effectiveStatus(scheduledAt: 100, now: 101), .missed)
        XCTAssertEqual(CarePlanStatus.completed.effectiveStatus(scheduledAt: 100, now: 101), .completed)
        XCTAssertEqual(RootPublicationState.resolve(localUpdatedAt: 100, familyPublishedUpdatedAt: 101), .neverPublished)
        XCTAssertEqual(RootPublicationState.resolve(localUpdatedAt: 100, familyPublishedUpdatedAt: 99), .previousVersionPublished)
        XCTAssertEqual(RootPublicationState.resolve(localUpdatedAt: 100, familyPublishedUpdatedAt: 100), .currentVersionPublished)
    }
}
