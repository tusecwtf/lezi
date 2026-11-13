import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

final class OfflineWakeRevisionTests: XCTestCase, @unchecked Sendable {
    private enum Mutation: CaseIterable, Equatable, Sendable { case edit, withdraw }
    private struct Fixture: Sendable {
        let file: String
        let store: OfflineStore
        let context: OfflineContext
        let sleep: OfflineSleep
        var wake: OfflineWakeObservation { sleep.observations[0] }
    }

    private func fixture(selected: Bool = false) async throws -> Fixture {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("wake-cas-\(UUID()).sqlite").path
        let store = try OfflineStore(path: file, clock: { 1_000 })
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        let context = try await store.selectBaby(baby.clientUUID)
        let draft = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.sleep)!, timestampMillis: 100, endTimestampMillis: 200,
            note: "睡眠备注", payload: .sleep())
        var sleep = try await store.recordCompletedSleep(draft, context: context)
        if selected {
            sleep = try await store.selectEffectiveWake(sleep.observations[0].clientUUID,
                sleepClientUUID: sleep.record.clientUUID, context: context, expectedUpdatedAt: sleep.record.updatedAt)
        }
        return Fixture(file: file, store: store, context: context, sleep: sleep)
    }

    private func mutate(_ mutation: Mutation, fixture: Fixture, store: OfflineStore? = nil,
                        sleepID: UUID? = nil, context: OfflineContext? = nil) async throws -> OfflineWakeObservation {
        let store = store ?? fixture.store
        let context = context ?? fixture.context
        // Commands use only the original captured fixture, never a fresh database revision.
        let sleepID = sleepID ?? fixture.sleep.record.clientUUID
        let wakeRevision = fixture.wake.updatedAt
        let sleepRevision = fixture.sleep.record.updatedAt
        switch mutation {
        case .edit:
            return try await store.updateWake(.init(clientUUID: fixture.wake.clientUUID,
                sleepClientUUID: sleepID, babyClientUUID: context.babyClientUUID,
                timestampMillis: 250, note: "观察备注"), context: context,
                expectedUpdatedAt: wakeRevision, expectedSleepUpdatedAt: sleepRevision)
        case .withdraw:
            return try await store.withdrawWake(fixture.wake.clientUUID, sleepClientUUID: sleepID,
                context: context, expectedUpdatedAt: wakeRevision, expectedSleepUpdatedAt: sleepRevision)
        }
    }

    private func assertRejected(_ mutation: Mutation, fixture: Fixture, error expected: OfflineStoreError,
                                sleepID: UUID? = nil, context: OfflineContext? = nil,
                                file: StaticString = #filePath, line: UInt = #line) async throws {
        let beforeSleep = try await fixture.store.recordDetails(fixture.sleep.record.clientUUID)
        let beforeWake = try await fixture.store.wakeDetails(fixture.wake.clientUUID)
        do {
            _ = try await mutate(mutation, fixture: fixture, sleepID: sleepID, context: context)
            XCTFail("Expected \(expected) for \(mutation)", file: file, line: line)
        } catch {
            XCTAssertEqual(error as? OfflineStoreError, expected, file: file, line: line)
        }
        let afterSleep = try await fixture.store.recordDetails(fixture.sleep.record.clientUUID)
        let afterWake = try await fixture.store.wakeDetails(fixture.wake.clientUUID)
        XCTAssertEqual(afterSleep, beforeSleep, "Parent and effective pointer must not change", file: file, line: line)
        XCTAssertEqual(afterWake, beforeWake, "Wake evidence must not change", file: file, line: line)
    }

    func testWakeEditRejectsOnlyStaleParentRevision() async throws {
        let f = try await fixture()
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let concurrent = try OfflineStore(path: f.file, clock: { 500 })
        let changed = try await concurrent.editSleepStart(.init(clientUUID: f.sleep.record.clientUUID,
            babyClientUUID: f.context.babyClientUUID, item: .builtIn(.sleep)!, timestampMillis: 110,
            note: "新的睡眠备注", payload: .sleep()), context: f.context,
            expectedUpdatedAt: f.sleep.record.updatedAt)
        XCTAssertNotEqual(changed.record.updatedAt, f.sleep.record.updatedAt)
        XCTAssertEqual(changed.observations[0], f.wake)
        try await assertRejected(.edit, fixture: f, error: .staleEdit)
    }

    func testWakeWithdrawalRejectsOnlyStaleParentAndPreservesNewEffectivePointer() async throws {
        let f = try await fixture()
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let concurrent = try OfflineStore(path: f.file, clock: { 500 })
        let changed = try await concurrent.selectEffectiveWake(f.wake.clientUUID,
            sleepClientUUID: f.sleep.record.clientUUID, context: f.context,
            expectedUpdatedAt: f.sleep.record.updatedAt)
        XCTAssertEqual(changed.record.effectiveWakeObservationClientUUID, f.wake.clientUUID)
        XCTAssertEqual(changed.observations[0], f.wake)
        try await assertRejected(.withdraw, fixture: f, error: .staleEdit)
    }

    func testWakeEditRejectsOnlyStaleWakeRevision() async throws {
        let f = try await fixture(selected: true)
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let concurrent = try OfflineStore(path: f.file, clock: { 500 })
        let changed = try await mutate(.edit, fixture: f, store: concurrent)
        XCTAssertNotEqual(changed.updatedAt, f.wake.updatedAt)
        let parent = try await f.store.recordDetails(f.sleep.record.clientUUID)
        XCTAssertEqual(parent, f.sleep.record)
        try await assertRejected(.edit, fixture: f, error: .staleEdit)
    }

    func testWakeWithdrawalRejectsOnlyStaleWakeAndPreservesEffectivePointer() async throws {
        let f = try await fixture(selected: true)
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let concurrent = try OfflineStore(path: f.file, clock: { 500 })
        _ = try await mutate(.edit, fixture: f, store: concurrent)
        let parent = try await f.store.recordDetails(f.sleep.record.clientUUID)
        XCTAssertEqual(parent, f.sleep.record)
        try await assertRejected(.withdraw, fixture: f, error: .staleEdit)
    }

    func testConcurrentWithdrawalCannotBeOverwrittenOrRepeatedWithCapturedWakeRevision() async throws {
        let f = try await fixture()
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let concurrent = try OfflineStore(path: f.file, clock: { 500 })
        let withdrawn = try await mutate(.withdraw, fixture: f, store: concurrent)
        XCTAssertTrue(withdrawn.withdrawn)
        let parent = try await f.store.recordDetails(f.sleep.record.clientUUID)
        XCTAssertEqual(parent, f.sleep.record, "Provisional withdrawal leaves the parent revision unchanged")
        for mutation in Mutation.allCases {
            try await assertRejected(mutation, fixture: f, error: .staleEdit)
        }
        let projected = try await f.store.sleepDetails(f.sleep.record.clientUUID)
        XCTAssertEqual(projected?.interval.isOpen, true)
    }

    func testConcurrentParentDeletionRejectsBothMutationsAndRetainsWakeEvidence() async throws {
        let f = try await fixture(selected: true)
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let concurrent = try OfflineStore(path: f.file, clock: { 500 })
        _ = try await concurrent.deleteRecord(f.sleep.record.clientUUID, context: f.context,
            expectedUpdatedAt: f.sleep.record.updatedAt)
        for mutation in Mutation.allCases {
            try await assertRejected(mutation, fixture: f, error: .deletedRecord)
        }
        let wake = try await f.store.wakeDetails(f.wake.clientUUID)
        XCTAssertEqual(wake, f.wake)
    }

    func testConcurrentEditAndWithdrawalWithSameSnapshotHaveExactlyOneWinner() async throws {
        let f = try await fixture(selected: true)
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let editor = try OfflineStore(path: f.file, clock: { 500 })
        let withdrawer = try OfflineStore(path: f.file, clock: { 500 })
        let edit = Task { try await self.mutate(.edit, fixture: f, store: editor) }
        let withdrawal = Task { try await self.mutate(.withdraw, fixture: f, store: withdrawer) }
        let outcomes = await [edit.result, withdrawal.result]
        var winners: [OfflineWakeObservation] = []
        for outcome in outcomes {
            switch outcome {
            case let .success(wake): winners.append(wake)
            case let .failure(error): XCTAssertEqual(error as? OfflineStoreError, .staleEdit)
            }
        }
        XCTAssertEqual(winners.count, 1)
        let winner = try XCTUnwrap(winners.first)
        let savedWake = try await f.store.wakeDetails(f.wake.clientUUID)
        let savedSleep = try await f.store.sleepDetails(f.sleep.record.clientUUID)
        XCTAssertEqual(savedWake, winner)
        XCTAssertEqual(savedSleep?.interval.isOpen, winner.withdrawn)
        XCTAssertEqual(savedSleep?.record.effectiveWakeObservationClientUUID,
            winner.withdrawn ? nil : f.wake.clientUUID)
    }

    func testWakeMutationFailureRollsBackBothRootsAndEffectivePointer() async throws {
        for mutation in Mutation.allCases {
            let points: [OfflineStore.FailurePoint] = mutation == .edit
                ? [.afterWake, .beforeCommit] : [.afterWake, .afterSleep, .beforeCommit]
            for point in points {
                let f = try await fixture(selected: true)
                defer { try? FileManager.default.removeItem(atPath: f.file) }
                await f.store.failNextConfirmation(at: point)
                try await assertRejected(mutation, fixture: f, error: .injectedFailure)
                let reopened = try OfflineStore(path: f.file, clock: { 500 })
                let preserved = try await reopened.sleepDetails(f.sleep.record.clientUUID)
                XCTAssertEqual(preserved, f.sleep)
                // A failed transaction consumes neither captured revision.
                let saved = try await mutate(mutation, fixture: f, store: reopened)
                XCTAssertEqual(saved.clientUUID, f.wake.clientUUID)
                XCTAssertEqual(saved.sleepClientUUID, f.sleep.record.clientUUID)
                let final = try await reopened.sleepDetails(f.sleep.record.clientUUID)
                if mutation == .withdraw {
                    XCTAssertTrue(saved.withdrawn)
                    XCTAssertNil(final?.record.effectiveWakeObservationClientUUID)
                    XCTAssertEqual(final?.interval.isOpen, true)
                } else {
                    XCTAssertEqual(final?.record, f.sleep.record)
                    XCTAssertEqual(final?.interval.endTimestamp, 250)
                    XCTAssertEqual(saved.note, "观察备注")
                }
            }
        }
    }

    func testWakeEditRealCommitFailureRollsBackWithoutClearingEffectivePointer() async throws {
        let f = try await fixture(selected: true)
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let db = try SQLiteConnection(path: f.file)
        try db.execute("CREATE TABLE commit_parent(id INTEGER PRIMARY KEY)")
        try db.execute("CREATE TABLE commit_guard(id INTEGER REFERENCES commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try db.execute("CREATE TRIGGER fail_wake_edit AFTER UPDATE ON wake_observations BEGIN INSERT INTO commit_guard VALUES (1); END")
        do {
            _ = try await mutate(.edit, fixture: f)
            XCTFail("Deferred foreign key must fail at COMMIT")
        } catch {
            guard case .sqlite = error as? OfflineStoreError else { return XCTFail("Expected SQLite commit failure, got \(error)") }
        }
        let preserved = try await f.store.sleepDetails(f.sleep.record.clientUUID)
        XCTAssertEqual(preserved, f.sleep)
        try db.execute("DROP TRIGGER fail_wake_edit")
        _ = try await mutate(.edit, fixture: f)
    }

    func testWakeMutationPreservesParentIdentityBabyAndContextGuards() async throws {
        let f = try await fixture()
        defer { try? FileManager.default.removeItem(atPath: f.file) }
        let otherSleep = try await f.store.createSleepStart(.init(clientUUID: UUID(),
            babyClientUUID: f.context.babyClientUUID, item: .builtIn(.sleep)!, timestampMillis: 300,
            payload: .sleep()), context: f.context)
        let otherBaby = try await f.store.createBaby(nickname: "二宝", birthday: "2026-01-01")
        let otherContext = try await f.store.selectBaby(otherBaby.clientUUID)
        for mutation in Mutation.allCases {
            try await assertRejected(mutation, fixture: f, error: .identityConflict,
                sleepID: otherSleep.record.clientUUID)
            try await assertRejected(mutation, fixture: f, error: .identityConflict, context: otherContext)
        }
        try await f.store.blockOfflineWrites()
        for mutation in Mutation.allCases {
            try await assertRejected(mutation, fixture: f, error: .offlineOnly)
        }
    }

    func testWakeMutationPreservesOpaqueLegacyObserverDirtyAndTombstoneGuards() async throws {
        let cases: [(sql: String, error: OfflineStoreError)] = [
            ("UPDATE records SET payload_schema_version=999", .opaqueRecord),
            ("UPDATE records SET end_timestamp=200", .unsupportedSleepHistory),
            ("UPDATE wake_observations SET local_observer_uuid='\(UUID().uuidString)'", .wakePermission),
            ("UPDATE wake_observations SET sync_dirty=0", .wakePermission),
            ("UPDATE wake_observations SET deleted_at=1001", .deletedRecord),
            ("UPDATE local_identity SET generation='\(UUID().uuidString)'", .staleContext),
        ]
        for item in cases {
            let f = try await fixture()
            defer { try? FileManager.default.removeItem(atPath: f.file) }
            let db = try SQLiteConnection(path: f.file)
            try db.execute(item.sql)
            for mutation in Mutation.allCases {
                try await assertRejected(mutation, fixture: f, error: item.error)
            }
        }
    }
}
