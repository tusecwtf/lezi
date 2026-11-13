import XCTest
import LeziCore
@testable import LeziStorage
@testable import LeziUI

final class OfflineBabyProfileStateTests: XCTestCase {
    private func fixture(weight: Int? = nil) -> (OfflineBaby, OfflineContext) {
        let id = UUID()
        let baby = OfflineBaby(clientUUID: id, nickname: "原宝宝", birthday: "2024-02-29",
                               themeColor: 0xff123456, sortOrder: 7, sex: "female",
                               birthWeightGrams: weight, updatedAt: 1000, syncDirty: false)
        return (baby, OfflineContext(generation: UUID(), selectionRevision: 9, babyClientUUID: id))
    }

    func testOriginalIdentityContextRevisionAndCleanBaselineAreImmutable() throws {
        let (baby, context) = fixture(weight: 3200)
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        XCTAssertFalse(state.isDirty)
        state.editNickname(" 新宝宝 "); state.editSex("male")
        state.editBirthday("2025-03-01"); state.editWeight("3.45")
        XCTAssertTrue(state.isDirty)
        let frozen = try XCTUnwrap(state.beginSave())
        XCTAssertEqual(frozen.babyClientUUID, baby.clientUUID)
        XCTAssertEqual(frozen.context, context)
        XCTAssertEqual(frozen.expectedUpdatedAt, 1000)
        XCTAssertEqual(frozen.nickname, "新宝宝")
        XCTAssertEqual(frozen.sex, "male")
        XCTAssertEqual(frozen.birthday, "2025-03-01")
        XCTAssertEqual(frozen.birthWeightGrams, 3450)
        XCTAssertEqual(state.original, baby)
    }

    func testMismatchedBabyCannotOpenAndNormalizationAloneIsNotDirty() throws {
        let (baby, context) = fixture(weight: 3200)
        XCTAssertNil(OfflineBabyProfileState(baby: baby, context:
            .init(generation: context.generation, selectionRevision: 9, babyClientUUID: UUID())))
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        state.editNickname("\u{001c}原宝宝\u{00a0}")
        state.editSex("FEMALE")
        state.editWeight("3.200")
        XCTAssertFalse(state.isDirty)
        state.editNickname("e\u{0301}")
        XCTAssertTrue(state.isDirty)
    }

    func testIndependentErrorsRetainOriginalInvalidTextUntilCorrected() throws {
        let (baby, context) = fixture()
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        state.editNickname("\u{001c} \u{00a0}")
        state.editBirthday("2025-02-29")
        state.editWeight(" 3,20 kg ")
        XCTAssertNil(state.beginSave())
        XCTAssertEqual(state.nicknameError, "请填写昵称")
        XCTAssertEqual(state.birthdayError, "请选择有效的出生日期")
        XCTAssertEqual(state.weightError, "出生体重格式不正确，请填写数字（如 3.20）")
        XCTAssertEqual(state.weightText, " 3,20 kg ")
        XCTAssertFalse(state.isBusy)
        state.editNickname("合法")
        XCTAssertNil(state.nicknameError)
        XCTAssertNotNil(state.weightError)
        XCTAssertNil(state.beginSave())
        state.editWeight(".5")
        XCTAssertNil(state.weightError)
        XCTAssertNil(state.beginSave())
        state.editBirthday("2024-02-29")
        XCTAssertNotNil(state.beginSave())
    }

    func testNicknameUsesCodePointsNotGraphemesOrUTF16AndKeepsInvalidInput() throws {
        let (baby, context) = fixture()
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        state.editNickname(String(repeating: "😀", count: 20))
        let valid = try XCTUnwrap(state.beginSave())
        state.finishSave(attemptID: valid.attemptID, failure: "retry")
        let invalid = String(repeating: "e\u{0301}", count: 11)
        state.editNickname(invalid)
        XCTAssertNil(state.beginSave())
        XCTAssertEqual(state.nickname, invalid)
        XCTAssertEqual(state.nicknameError, "宝宝昵称最多 20 个字符")
        state.editNickname("\u{0085}")
        XCTAssertNotNil(state.beginSave(), "NEL is not Android whitespace")
    }

    func testBusySaveFreezesAllFieldsAndStaleCompletionCannotUnlockRetry() throws {
        let (baby, context) = fixture(weight: 3200)
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        state.editNickname("草稿")
        let first = try XCTUnwrap(state.beginSave())
        let frozenState = state
        state.editNickname("丢弃"); state.editSex(nil)
        state.editBirthday("2030-01-01"); state.editWeight("")
        XCTAssertEqual(state, frozenState)
        XCTAssertNil(state.beginSave())
        XCTAssertFalse(state.finishSave(attemptID: UUID(), failure: "old callback"))
        XCTAssertTrue(state.isBusy)
        XCTAssertTrue(state.finishSave(attemptID: first.attemptID, failure: "保存失败"))
        XCTAssertEqual(state.formError, "保存失败")
        let retry = try XCTUnwrap(state.beginSave())
        XCTAssertNotEqual(retry.attemptID, first.attemptID)
        XCTAssertFalse(state.finishSave(attemptID: first.attemptID))
        XCTAssertTrue(state.isBusy)
        XCTAssertTrue(state.finishSave(attemptID: retry.attemptID))
        XCTAssertFalse(state.isBusy)
    }

    func testUntouched4015GramsSurviveOtherFieldEditsButAuthored4015UsesAndroidTruncation() throws {
        let (baby, context) = fixture(weight: 4015)
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        XCTAssertEqual(state.weightText, "4.015")
        XCTAssertFalse(state.isDirty)
        state.editNickname("仅改昵称")
        state.editWeight(" 4.015 ")
        XCTAssertEqual(try XCTUnwrap(state.beginSave()).birthWeightGrams, 4015)
        let (emptyBaby, emptyContext) = fixture()
        var authored = try XCTUnwrap(OfflineBabyProfileState(baby: emptyBaby, context: emptyContext))
        authored.editWeight("4.015")
        XCTAssertEqual(try XCTUnwrap(authored.beginSave()).birthWeightGrams, 4014)
    }

    func testOptionalClearAndWeightRangeDoNotInventValues() throws {
        let (baby, context) = fixture(weight: 9000)
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        state.editWeight("9.001")
        XCTAssertNil(state.beginSave())
        XCTAssertEqual(state.weightError, "出生体重需在 500–9000 克之间")
        state.editWeight(" \n "); state.editSex(nil)
        let frozen = try XCTUnwrap(state.beginSave())
        XCTAssertNil(frozen.birthWeightGrams)
        XCTAssertNil(frozen.sex)
        // "unknown" is a raw historical value, never the nil picker sentinel.
        let legacy = OfflineBaby(clientUUID: baby.clientUUID, nickname: baby.nickname, birthday: baby.birthday,
            themeColor: baby.themeColor, sortOrder: baby.sortOrder, sex: "unknown", updatedAt: baby.updatedAt)
        var unknown = try XCTUnwrap(OfflineBabyProfileState(baby: legacy, context: context))
        XCTAssertNil(unknown.beginSave())
        XCTAssertEqual(unknown.sex, "unknown")
        XCTAssertNotNil(unknown.sexError)
        unknown.editSex(nil)
        XCTAssertNil(try XCTUnwrap(unknown.beginSave()).sex)
    }

    func testDateOnlyRoundtripCoversLeapDSTAndFutureWithoutLocalCalendarDependence() throws {
        XCTAssertEqual(OfflineBabyBirthday.calendar.timeZone.secondsFromGMT(), 0)
        XCTAssertEqual(OfflineBabyBirthday.calendar.identifier, .gregorian)
        for day in ["1900-03-01", "2000-02-29", "2024-02-29", "2026-03-08", "2026-11-01", "2035-01-01"] {
            let date = try XCTUnwrap(OfflineBabyBirthday.date(day), day)
            XCTAssertEqual(OfflineBabyBirthday.day(date), day)
            XCTAssertEqual(date.timeIntervalSince1970.truncatingRemainder(dividingBy: 86400), 0)
        }
        for invalid in ["2025-02-29", "1900-02-29", "2024-13-01", "2024-2-09", "2024-01-01T00:00:00Z", ""] {
            XCTAssertNil(OfflineBabyBirthday.date(invalid), invalid)
        }
    }

    func testGregorianCutoverDayNeverSilentlyShiftsAndRemainsEditableAsExactText() throws {
        // Native Calendar/DateFormatter cannot represent these LocalDate days
        // without a calendar cutover shift. The view uses an exact ISO field.
        for day in ["0001-01-01", "1500-02-28", "1582-10-04", "1582-10-05", "1582-10-10", "1582-10-14"] {
            XCTAssertTrue(BabyProfileRules.validBirthday(day))
            XCTAssertNil(OfflineBabyBirthday.date(day))
            let (original, context) = fixture()
            let baby = OfflineBaby(clientUUID: original.clientUUID, nickname: original.nickname,
                birthday: day, themeColor: original.themeColor, sortOrder: original.sortOrder,
                sex: original.sex, updatedAt: original.updatedAt)
            var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
            XCTAssertFalse(state.isDirty)
            state.editNickname("只改昵称")
            XCTAssertEqual(try XCTUnwrap(state.beginSave()).birthday, day)
        }
    }

    @MainActor
    func testInvalidHistoricalBirthdaySexAndWeightAreNotSilentlyRepairedByNameEdit() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("profile-invalid-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let baby = try await store.createBaby(nickname: "旧档案", birthday: "2024-02-29")
        let context = try await store.selectBaby(baby.clientUUID)
        let db = try SQLiteConnection(path: path)
        try db.execute("UPDATE babies SET birthday='2025-02-29',sex='legacy-value',birth_weight_grams=400,sync_dirty=0 WHERE uuid=?",
                       [.text(baby.clientUUID.uuidString)])
        let before = try db.query("SELECT * FROM babies")
        let loaded = try await store.babyProfile(baby.clientUUID)
        let original = try XCTUnwrap(loaded)
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: original, context: context))
        XCTAssertFalse(state.isDirty)
        XCTAssertNil(OfflineBabyBirthday.date(state.birthday))
        state.editNickname("仅改名字")
        XCTAssertNil(state.beginSave())
        XCTAssertEqual(state.birthday, "2025-02-29")
        XCTAssertEqual(state.weightText, "0.4")
        XCTAssertEqual(state.sex, "legacy-value")
        XCTAssertNotNil(state.birthdayError)
        XCTAssertNotNil(state.sexError)
        XCTAssertNotNil(state.weightError)
        XCTAssertEqual(try db.query("SELECT * FROM babies"), before)
        state.editBirthday("2024-02-29"); state.editWeight(".5")
        XCTAssertNil(state.beginSave(), "An unrecognized old sex must not silently become nil")
        state.editSex(nil)
        XCTAssertNotNil(state.beginSave(), "Only explicit corrections make the draft saveable")
    }

    @MainActor
    func testRealStoreFrozenEditKeepsOriginalBabySelectionHistoryAndUntouchedGramsOnRelaunch() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("profile-state-\(UUID())")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let path = folder.appendingPathComponent("facts.sqlite").path
        let store = try OfflineStore(path: path, clock: { 1000 })
        let first = try await store.createBaby(nickname: "原宝宝", birthday: "2024-02-29")
        let second = try await store.createBaby(nickname: "二宝", birthday: "2020-01-01")
        let context = try await store.selectBaby(first.clientUUID)
        let baby = try await store.updateBabyProfile(first.clientUUID, nickname: first.nickname, sex: "female",
            birthday: first.birthday, birthWeightGrams: 4015, context: context, expectedUpdatedAt: first.updatedAt)
        let record = try await store.confirmPee(
            OfflinePeeDraft(babyClientUUID: first.clientUUID, timestampMillis: 900, note: "原记录").confirmation,
            context: context)
        var state = try XCTUnwrap(OfflineBabyProfileState(baby: baby, context: context))
        let secondContext = try await store.selectBaby(second.clientUUID)
        state.editNickname("改名"); state.editBirthday("2025-03-01")
        let frozen = try XCTUnwrap(state.beginSave())
        _ = try await store.updateBabyProfile(frozen.babyClientUUID, nickname: frozen.nickname,
            sex: frozen.sex, birthday: frozen.birthday, birthWeightGrams: frozen.birthWeightGrams,
            context: frozen.context, expectedUpdatedAt: frozen.expectedUpdatedAt)
        let reopened = try OfflineStore(path: path, clock: { 1000 })
        let saved = try await reopened.babyProfile(first.clientUUID)
        let retainedRecord = try await reopened.record(record.clientUUID)
        let selection = try await reopened.context()
        let babies = try await reopened.babies()
        XCTAssertEqual(saved?.nickname, "改名")
        XCTAssertEqual(saved?.birthWeightGrams, 4015)
        XCTAssertEqual(saved?.birthday, "2025-03-01")
        XCTAssertEqual(saved?.sex, "female")
        XCTAssertEqual(retainedRecord, record)
        XCTAssertEqual(selection, secondContext)
        XCTAssertEqual(babies.count, 2)
    }
}

#if canImport(SwiftUI)
@MainActor
final class OfflineBabyProfileModelTests: XCTestCase {
    private func settle(_ condition: @MainActor () -> Bool) async throws {
        let deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), ContinuousClock().now < deadline { try await Task.sleep(for: .milliseconds(1)) }
        XCTAssertTrue(condition())
    }
    private func fixture() async throws -> (OfflineStore, OfflineBaby, OfflineContext, OfflineBabyProfileModel) {
        let store = try OfflineStore(path: ":memory:", clock: { 1000 })
        let baby = try await store.createBaby(nickname: "原宝宝", birthday: "2024-02-29")
        let context = try await store.selectBaby(baby.clientUUID)
        let model = OfflineBabyProfileModel(store: store)
        model.open(baby: baby, context: context)
        return (store, baby, context, model)
    }

    func testCleanCloseDirtyKeepDiscardAndStaleDiscardCallbackWriteNothing() async throws {
        let (store, baby, context, model) = try await fixture()
        model.close(); XCTAssertNil(model.editor)
        let historicalName = String(repeating: "旧", count: 21)
        let historical = OfflineBaby(clientUUID: baby.clientUUID, nickname: historicalName,
            birthday: baby.birthday, themeColor: baby.themeColor, sortOrder: baby.sortOrder,
            updatedAt: baby.updatedAt)
        model.open(baby: historical, context: context)
        XCTAssertEqual(model.editor?.nickname, historicalName, "Only user input is clipped, never initial persisted data")
        model.save()
        XCTAssertEqual(model.editor?.nickname, historicalName)
        XCTAssertNotNil(model.editor?.nicknameError)
        XCTAssertFalse(model.busy)
        model.close(); XCTAssertNil(model.editor)
        model.open(baby: baby, context: context)
        model.editNickname("草稿"); model.editWeight("3,20")
        model.close(); XCTAssertTrue(model.discardRequested)
        model.keepEditing(); model.discard()
        XCTAssertEqual(model.editor?.nickname, "草稿")
        XCTAssertEqual(model.editor?.weightText, "3,20")
        model.close(); model.discardRequested = false; model.discard()
        XCTAssertNil(model.editor)
        model.open(baby: baby, context: context); model.editNickname("新草稿")
        model.discard()
        XCTAssertEqual(model.editor?.nickname, "新草稿")
        let after = try await store.babyProfile(baby.clientUUID)
        XCTAssertEqual(after, baby)
    }

    func testInvalidDuplicateAndRollbackPreserveDraftThenRetryCommitsOnce() async throws {
        let (store, baby, _, model) = try await fixture()
        _ = try await store.createBaby(nickname: "占用昵称", birthday: "2026-01-01")
        model.editNickname(String(repeating: "😀", count: 21))
        XCTAssertEqual(model.editor?.nickname, String(repeating: "😀", count: 20))
        model.editNickname(String(repeating: "e\u{0301}", count: 11))
        XCTAssertEqual(model.editor?.nickname, String(repeating: "e\u{0301}", count: 10))
        model.editNickname(""); model.editWeight("3,20"); model.save()
        XCTAssertFalse(model.busy)
        XCTAssertNotNil(model.editor?.nicknameError)
        XCTAssertNotNil(model.editor?.weightError)
        model.editNickname("占用昵称"); model.editWeight("3.45"); model.save()
        try await settle { !model.busy }
        XCTAssertEqual(model.editor?.formError, "宝宝昵称已存在，请换一个昵称。")
        XCTAssertEqual(model.editor?.weightText, "3.45")
        model.editNickname("新名字"); model.editSex("male")
        model.editBirthday("2025-03-01")
        await store.failNextConfirmation(at: .beforeCommit)
        model.save(); model.save()
        model.close(); model.discard()
        model.editNickname("不能改")
        XCTAssertEqual(model.editor?.nickname, "新名字")
        XCTAssertFalse(model.discardRequested)
        try await settle { !model.busy }
        let unchanged = try await store.babyProfile(baby.clientUUID)
        XCTAssertEqual(unchanged, baby)
        XCTAssertEqual(model.completion, 0)
        XCTAssertNil(model.message)
        XCTAssertEqual(model.editor?.weightText, "3.45")
        model.save(); model.save()
        try await settle { model.editor == nil }
        let saved = try await store.babyProfile(baby.clientUUID)
        XCTAssertEqual(saved?.updatedAt, baby.updatedAt + 1)
        XCTAssertEqual(saved?.nickname, "新名字")
        XCTAssertEqual(saved?.sex, "male")
        XCTAssertEqual(saved?.birthWeightGrams, 3450)
        XCTAssertEqual(saved?.birthday, "2025-03-01")
        XCTAssertEqual(model.completion, 1)
        XCTAssertEqual(model.message, "已保存本机宝宝档案：新名字")
    }

    func testConcurrentNewerProfileIsNeverReloadedAndOverwrittenByStaleSave() async throws {
        let (store, baby, context, model) = try await fixture()
        model.editNickname("旧草稿"); model.editWeight("4.015")
        let newer = try await store.updateBabyProfile(baby.clientUUID, nickname: "较新名字", sex: "female",
            birthday: baby.birthday, birthWeightGrams: 4015, context: context, expectedUpdatedAt: baby.updatedAt)
        model.save(); try await settle { !model.busy }
        XCTAssertEqual(model.editor?.nickname, "旧草稿")
        XCTAssertEqual(model.editor?.weightText, "4.015")
        XCTAssertEqual(model.editor?.original.updatedAt, baby.updatedAt)
        XCTAssertNotNil(model.editor?.formError)
        XCTAssertEqual(model.completion, 0)
        model.save(); try await settle { !model.busy }
        let actual = try await store.babyProfile(baby.clientUUID)
        XCTAssertEqual(actual, newer)
        model.close(); model.discard()
        model.open(baby: newer, context: context)
        XCTAssertEqual(model.editor?.original.updatedAt, newer.updatedAt)
        XCTAssertEqual(model.editor?.weightText, "4.015")
    }

    func testSelectionSwitchAndRepeatedOpenCannotRetargetActiveDraft() async throws {
        let (store, baby, _, model) = try await fixture()
        let other = try await store.createBaby(nickname: "二宝", birthday: "2026-01-01")
        let otherContext = try await store.selectBaby(other.clientUUID)
        model.open(baby: other, context: otherContext)
        XCTAssertEqual(model.editor?.original.clientUUID, baby.clientUUID)
        model.editNickname("原宝宝改名"); model.save()
        try await settle { model.editor == nil }
        let unchangedOther = try await store.babyProfile(other.clientUUID)
        let selection = try await store.context()
        let savedOriginal = try await store.babyProfile(baby.clientUUID)
        XCTAssertEqual(unchangedOther, other)
        XCTAssertEqual(selection, otherContext)
        XCTAssertEqual(savedOriginal?.nickname, "原宝宝改名")
    }

    func testBlockedStoreRetainsDraftAndNeverReportsSuccess() async throws {
        let (store, baby, _, model) = try await fixture()
        model.editNickname("不能保存")
        try await store.blockOfflineWrites()
        model.save(); try await settle { !model.busy }
        XCTAssertEqual(model.editor?.nickname, "不能保存")
        XCTAssertEqual(model.editor?.formError, "此数据不允许离线写入，草稿已保留。")
        XCTAssertNil(model.message)
        XCTAssertEqual(model.completion, 0)
        let unchanged = try await store.babyProfile(baby.clientUUID)
        XCTAssertEqual(unchanged, baby)
    }
}
#endif
