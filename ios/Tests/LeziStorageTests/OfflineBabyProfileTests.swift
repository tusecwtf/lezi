import Foundation
import XCTest
import LeziCore
@testable import LeziStorage

final class OfflineBabyProfileTests: XCTestCase, @unchecked Sendable {
    private func path() -> String {
        FileManager.default.temporaryDirectory.appendingPathComponent("lezi-profile-\(UUID()).sqlite").path
    }

    private func setup(_ file: String) async throws -> (OfflineStore, OfflineBaby, OfflineContext) {
        let store = try OfflineStore(path: file, clock: { 1_000 })
        let baby = try await store.createBaby(nickname: "宝宝", birthday: "2026-01-01")
        return (store, baby, try await store.selectBaby(baby.clientUUID))
    }

    private func expect(_ error: OfflineStoreError, _ action: () async throws -> Void) async {
        do { try await action(); XCTFail("Expected \(error)") }
        catch let actual { XCTAssertEqual(actual as? OfflineStoreError, error) }
    }

    private func outcome(_ action: @Sendable () async throws -> OfflineBaby) async -> Result<OfflineBaby, OfflineStoreError> {
        do { return .success(try await action()) }
        catch { return .failure(error as? OfflineStoreError ?? .incompatibleDatabase) }
    }

    func testCreateStillLeavesOptionalProfileUnsetAndReadIsNonmutating() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, created, _) = try await setup(file)
        XCTAssertNil(created.sex); XCTAssertNil(created.birthWeightGrams)
        XCTAssertEqual(created.updatedAt, 1_000); XCTAssertTrue(created.syncDirty)
        let db = try SQLiteConnection(path: file)
        let before = try db.query("SELECT * FROM babies")
        let identity = try db.query("SELECT * FROM local_identity")
        let profile = try await store.babyProfile(created.clientUUID)
        let missing = try await store.babyProfile(UUID())
        let all = try await store.babies()
        XCTAssertEqual(profile, created); XCTAssertEqual(all, [created]); XCTAssertNil(missing)
        XCTAssertEqual(try db.query("SELECT * FROM babies"), before)
        XCTAssertEqual(try db.query("SELECT * FROM local_identity"), identity)
    }

    func testUpdateNormalizesPersistsAndPreservesEveryHistoryIdentityAndPreference() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, baby, context) = try await setup(file)
        let record = RecordConfirmationDraft(clientUUID: UUID(), babyClientUUID: baby.clientUUID,
            item: .builtIn(.pee)!, timestampMillis: 100, note: "历史\0保留", payload: .pee(amount: 2))
        _ = try await store.confirmPee(record, context: context)
        try await store.setPreferences(baby: baby.clientUUID, themeColor: 0xFF123456, sortOrder: -4)
        let db = try SQLiteConnection(path: file)
        try db.execute("INSERT INTO records (uuid,baby_uuid,type,timestamp,note,payload_json,payload_schema_version,updated_at,sync_dirty,deleted_at) VALUES (?,?,'future',200,?,' { opaque bytes } ',99,42,0,43)", [
            .text(UUID().uuidString), .text(baby.clientUUID.uuidString), .text("不可解码\0🌱")])
        try db.execute("UPDATE babies SET sync_dirty=0 WHERE uuid=?", [.text(baby.clientUUID.uuidString)])
        let identity = try db.query("SELECT * FROM local_identity")
        let preferences = try db.query("SELECT * FROM baby_preferences")
        let records = try db.query("SELECT * FROM records ORDER BY uuid")
        let saved = try await store.updateBabyProfile(baby.clientUUID, nickname: "\u{001C} 新名 \u{00A0}",
            sex: " 女宝 ", birthday: "2030-02-01", birthWeightGrams: 4_014,
            context: context, expectedUpdatedAt: baby.updatedAt)
        XCTAssertEqual(saved.clientUUID, baby.clientUUID)
        XCTAssertEqual(saved.nickname, "新名"); XCTAssertEqual(saved.sex, "female")
        XCTAssertEqual(saved.birthday, "2030-02-01"); XCTAssertEqual(saved.birthWeightGrams, 4_014)
        XCTAssertEqual(saved.updatedAt, 1_001); XCTAssertTrue(saved.syncDirty)
        XCTAssertEqual(saved.themeColor, 0xFF123456); XCTAssertEqual(saved.sortOrder, -4)
        XCTAssertEqual(try db.query("SELECT * FROM local_identity"), identity)
        XCTAssertEqual(try db.query("SELECT * FROM baby_preferences"), preferences)
        XCTAssertEqual(try db.query("SELECT * FROM records ORDER BY uuid"), records)
        let reopened = try OfflineStore(path: file, clock: { 100 })
        let recovered = try await reopened.babyProfile(baby.clientUUID)
        let all = try await reopened.babies()
        XCTAssertEqual(recovered, saved); XCTAssertEqual(all, [saved])
        let cleared = try await reopened.updateBabyProfile(baby.clientUUID, nickname: " ", sex: "UNKNOWN",
            birthday: "2000-02-29", birthWeightGrams: nil, context: context, expectedUpdatedAt: saved.updatedAt)
        XCTAssertEqual(cleared.nickname, "年年"); XCTAssertNil(cleared.sex); XCTAssertNil(cleared.birthWeightGrams)
        XCTAssertEqual(cleared.updatedAt, 1_002)
        XCTAssertEqual(try db.query("SELECT * FROM records ORDER BY uuid"), records)
    }

    func testOriginalBabySurvivesSwitchAndReselectionAcrossRestartButCannotRetarget() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, babyA, contextA) = try await setup(file)
        let babyB = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        let contextB = try await store.selectBaby(babyB.clientUUID)
        await expect(.staleContext) {
            _ = try await store.updateBabyProfile(babyB.clientUUID, nickname: "错目标", sex: nil, birthday: babyB.birthday,
                birthWeightGrams: nil, context: contextA, expectedUpdatedAt: babyB.updatedAt)
        }
        await expect(.staleContext) {
            _ = try await store.updateBabyProfile(babyA.clientUUID, nickname: "错上下文", sex: nil, birthday: babyA.birthday,
                birthWeightGrams: nil, context: contextB, expectedUpdatedAt: babyA.updatedAt)
        }
        let reopened = try OfflineStore(path: file, clock: { 500 })
        let updated = try await reopened.updateBabyProfile(babyA.clientUUID, nickname: "大宝", sex: "m", birthday: babyA.birthday,
            birthWeightGrams: 500, context: contextA, expectedUpdatedAt: babyA.updatedAt)
        let selected = try await reopened.context()
        let untouched = try await reopened.babyProfile(babyB.clientUUID)
        XCTAssertEqual(selected, contextB); XCTAssertEqual(untouched, babyB)
        _ = try await reopened.selectBaby(babyA.clientUUID)
        let reselected = try await reopened.updateBabyProfile(babyA.clientUUID, nickname: "大宝", sex: "male", birthday: babyA.birthday,
            birthWeightGrams: 500, context: contextA, expectedUpdatedAt: updated.updatedAt)
        XCTAssertEqual(reselected.updatedAt, 1_002)
    }

    func testGenerationForeignStoreMissingAndDeletedBabyAndBlockedModeFailClosed() async throws {
        let file = path(), otherFile = path()
        defer { try? FileManager.default.removeItem(atPath: file); try? FileManager.default.removeItem(atPath: otherFile) }
        let (store, baby, context) = try await setup(file)
        let other = try OfflineStore(path: otherFile, clock: { 1_000 })
        _ = try await other.createBaby(clientUUID: baby.clientUUID, nickname: baby.nickname, birthday: baby.birthday)
        _ = try await other.selectBaby(baby.clientUUID)
        await expect(.staleContext) {
            _ = try await other.updateBabyProfile(baby.clientUUID, nickname: "异库", sex: nil, birthday: baby.birthday,
                birthWeightGrams: nil, context: context, expectedUpdatedAt: baby.updatedAt)
        }
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE local_identity SET generation=?", [.text(UUID().uuidString)])
        await expect(.staleContext) {
            _ = try await store.updateBabyProfile(baby.clientUUID, nickname: "旧代", sex: nil, birthday: baby.birthday,
                birthWeightGrams: nil, context: context, expectedUpdatedAt: baby.updatedAt)
        }
        let fresh = try await store.context()
        try db.execute("UPDATE babies SET deleted_at=2000")
        let deleted = try await store.babyProfile(baby.clientUUID); XCTAssertNil(deleted)
        await expect(.missingBaby) {
            _ = try await store.updateBabyProfile(baby.clientUUID, nickname: "墓碑", sex: nil, birthday: baby.birthday,
                birthWeightGrams: nil, context: fresh, expectedUpdatedAt: baby.updatedAt)
        }
        // A missing original root is distinct from an attempt to retarget.
        let absent = OfflineContext(generation: fresh.generation, selectionRevision: fresh.selectionRevision, babyClientUUID: UUID())
        await expect(.missingBaby) {
            _ = try await store.updateBabyProfile(absent.babyClientUUID, nickname: "无宝宝", sex: nil, birthday: baby.birthday,
                birthWeightGrams: nil, context: absent, expectedUpdatedAt: 0)
        }
        try db.execute("UPDATE babies SET deleted_at=NULL")
        try await store.blockOfflineWrites()
        let blocked = try OfflineStore(path: file)
        await expect(.offlineOnly) {
            _ = try await blocked.updateBabyProfile(baby.clientUUID, nickname: "被阻止", sex: nil, birthday: baby.birthday,
                birthWeightGrams: nil, context: fresh, expectedUpdatedAt: baby.updatedAt)
        }
        let unchanged = try await blocked.babyProfile(baby.clientUUID)
        XCTAssertEqual(unchanged, baby)
    }

    func testSameNameExcludesSelfButOtherLiveNameIsTransactionalAndTombstoneNameReusable() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, baby, context) = try await setup(file)
        let other = try await store.createBaby(nickname: "二宝", birthday: "2026-02-01")
        let selfEdit = try await store.updateBabyProfile(baby.clientUUID, nickname: " 宝宝 ", sex: nil, birthday: baby.birthday,
            birthWeightGrams: nil, context: context, expectedUpdatedAt: baby.updatedAt)
        XCTAssertEqual(selfEdit.updatedAt, 1_001)
        await expect(.duplicateNickname) {
            _ = try await store.updateBabyProfile(baby.clientUUID, nickname: " 二宝 ", sex: nil, birthday: baby.birthday,
                birthWeightGrams: nil, context: context, expectedUpdatedAt: selfEdit.updatedAt)
        }
        let afterFailure = try await store.babyProfile(baby.clientUUID); XCTAssertEqual(afterFailure, selfEdit)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE babies SET deleted_at=1 WHERE uuid=?", [.text(other.clientUUID.uuidString)])
        let reused = try await store.updateBabyProfile(baby.clientUUID, nickname: "二宝", sex: nil, birthday: baby.birthday,
            birthWeightGrams: nil, context: context, expectedUpdatedAt: selfEdit.updatedAt)
        XCTAssertEqual(reused.nickname, "二宝")
    }

    func testConcurrentEditsAndConcurrentCreateCannotCommitDuplicateNicknames() async throws {
        for createPeer in [false, true] {
            let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
            let (first, babyA, contextA) = try await setup(file)
            let babyB = try await first.createBaby(nickname: "二宝", birthday: "2026-02-01")
            let contextB = try await first.selectBaby(babyB.clientUUID)
            let second = try OfflineStore(path: file, clock: { 1_000 })
            async let a = outcome {
                try await first.updateBabyProfile(babyA.clientUUID, nickname: "同名", sex: nil, birthday: babyA.birthday,
                    birthWeightGrams: nil, context: contextA, expectedUpdatedAt: babyA.updatedAt)
            }
            async let b = outcome {
                if createPeer {
                    return try await second.createBaby(nickname: "同名", birthday: babyB.birthday)
                }
                return try await second.updateBabyProfile(babyB.clientUUID, nickname: "同名", sex: nil, birthday: babyB.birthday,
                    birthWeightGrams: nil, context: contextB, expectedUpdatedAt: babyB.updatedAt)
            }
            let results = await [a, b]
            XCTAssertEqual(results.filter { if case .success = $0 { true } else { false } }.count, 1)
            XCTAssertEqual(results.filter { if case .failure(.duplicateNickname) = $0 { true } else { false } }.count, 1)
            let db = try SQLiteConnection(path: file)
            XCTAssertEqual(try db.query("SELECT count(*) FROM babies WHERE nickname='同名' AND deleted_at IS NULL")[0][0], "1")
        }
    }

    func testStaleEditorAndRevisionOverflowDoNotOverwriteNewerProfile() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, baby, context) = try await setup(file)
        let other = try OfflineStore(path: file, clock: { 2_000 })
        let winner = try await other.updateBabyProfile(baby.clientUUID, nickname: "最新", sex: "female", birthday: "2026-03-01",
            birthWeightGrams: 3_000, context: context, expectedUpdatedAt: baby.updatedAt)
        await expect(.staleEdit) {
            _ = try await store.updateBabyProfile(baby.clientUUID, nickname: "旧编辑", sex: "male", birthday: baby.birthday,
                birthWeightGrams: 4_000, context: context, expectedUpdatedAt: baby.updatedAt)
        }
        let afterStale = try await store.babyProfile(baby.clientUUID); XCTAssertEqual(afterStale, winner)
        XCTAssertEqual(winner.updatedAt, 2_000)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE babies SET updated_at=?", [.int(Int64.max)])
        let before = try db.query("SELECT * FROM babies")
        await expect(.revisionOverflow) {
            _ = try await store.updateBabyProfile(baby.clientUUID, nickname: "溢出", sex: nil, birthday: baby.birthday,
                birthWeightGrams: nil, context: context, expectedUpdatedAt: Int64.max)
        }
        XCTAssertEqual(try db.query("SELECT * FROM babies"), before)
    }

    func testInvalidNicknameDateOrWeightDoesNotWriteAnyProfileField() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, baby, context) = try await setup(file)
        let cases: [(String, String, Int?)] = [
            (String(repeating: "🌱", count: 21), baby.birthday, nil),
            ("名字", "2025-02-29", nil), ("名字", "2026-1-01", nil),
            ("名字", baby.birthday, 499), ("名字", baby.birthday, 9_001),
            ("名字", baby.birthday, Int.max), ("名字", baby.birthday, Int.min),
        ]
        for (name, birthday, grams) in cases {
            await expect(.invalidDraft) {
                _ = try await store.updateBabyProfile(baby.clientUUID, nickname: name, sex: "male", birthday: birthday,
                    birthWeightGrams: grams, context: context, expectedUpdatedAt: baby.updatedAt)
            }
        }
        let unchanged = try await store.babyProfile(baby.clientUUID); XCTAssertEqual(unchanged, baby)
    }

    func testBothRollbackSeamsRestoreAllFieldsRevisionAndDirtyAcrossRestart() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, baby, context) = try await setup(file)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE babies SET sync_dirty=0")
        let before = try await store.babyProfile(baby.clientUUID)
        for point in [OfflineStore.FailurePoint.afterFact, .beforeCommit] {
            await store.failNextConfirmation(at: point)
            await expect(.injectedFailure) {
                _ = try await store.updateBabyProfile(baby.clientUUID, nickname: "撤销", sex: "male", birthday: "2024-02-29",
                    birthWeightGrams: 4_014, context: context, expectedUpdatedAt: baby.updatedAt)
            }
            let reopened = try OfflineStore(path: file)
            let recovered = try await reopened.babyProfile(baby.clientUUID); XCTAssertEqual(recovered, before)
        }
        let saved = try await store.updateBabyProfile(baby.clientUUID, nickname: "保存", sex: "male", birthday: "2024-02-29",
            birthWeightGrams: 4_014, context: context, expectedUpdatedAt: baby.updatedAt)
        XCTAssertEqual(saved.updatedAt, 1_001); XCTAssertTrue(saved.syncDirty)
    }

    func testRealCommitFailureReturnsNoSuccessAndRestoresProfileDirtyAndPreferences() async throws {
        let file = path(); defer { try? FileManager.default.removeItem(atPath: file) }
        let (store, baby, context) = try await setup(file)
        let db = try SQLiteConnection(path: file)
        try db.execute("UPDATE babies SET sync_dirty=0")
        let before = try db.query("SELECT * FROM babies")
        let identity = try db.query("SELECT * FROM local_identity")
        let preferences = try db.query("SELECT * FROM baby_preferences")
        try db.execute("CREATE TABLE profile_commit_parent (id INTEGER PRIMARY KEY)")
        try db.execute("CREATE TABLE profile_commit_guard (id INTEGER REFERENCES profile_commit_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        try db.execute("CREATE TRIGGER fail_profile_commit AFTER UPDATE ON babies BEGIN INSERT INTO profile_commit_guard VALUES (1); END")
        do {
            _ = try await store.updateBabyProfile(baby.clientUUID, nickname: "不能成功", sex: "female", birthday: "2030-01-01",
                birthWeightGrams: 9_000, context: context, expectedUpdatedAt: baby.updatedAt)
            XCTFail("Deferred-constraint COMMIT must fail")
        } catch let error as OfflineStoreError {
            guard case .sqlite = error else { return XCTFail("Unexpected error: \(error)") }
        }
        XCTAssertEqual(try db.query("SELECT * FROM babies"), before)
        XCTAssertEqual(try db.query("SELECT * FROM local_identity"), identity)
        XCTAssertEqual(try db.query("SELECT * FROM baby_preferences"), preferences)
        XCTAssertEqual(try db.query("SELECT count(*) FROM profile_commit_guard")[0][0], "0")
        try db.execute("DROP TRIGGER fail_profile_commit")
        let saved = try await store.updateBabyProfile(baby.clientUUID, nickname: "重试成功", sex: "female", birthday: "2030-01-01",
            birthWeightGrams: 9_000, context: context, expectedUpdatedAt: baby.updatedAt)
        XCTAssertEqual(saved.updatedAt, 1_001)
    }
}
