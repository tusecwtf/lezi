import Foundation
import LeziCore
import LeziStorage

/// One in-memory edit session. Its original baby, row revision and database
/// context are immutable, even if the host changes the current baby later.
struct OfflineBabyProfileState: Equatable, Sendable {
    let sessionID = UUID()
    let original: OfflineBaby
    let context: OfflineContext
    private(set) var nickname: String
    private(set) var sex: String?
    private(set) var birthday: String
    private(set) var weightText: String
    private let originalWeightText: String
    private(set) var nicknameError: String?
    private(set) var sexError: String?
    private(set) var birthdayError: String?
    private(set) var weightError: String?
    private(set) var formError: String?
    private(set) var saveAttemptID: UUID?

    init?(baby: OfflineBaby, context: OfflineContext) {
        guard baby.clientUUID == context.babyClientUUID else { return nil }
        original = baby
        self.context = context
        nickname = baby.nickname
        sex = baby.sex
        birthday = baby.birthday
        // Matches the Android draft's kilogram display. Untouched display text
        // must not re-truncate an existing integer gram value when another field
        // is saved (for example 4015 g -> "4.015" -> legacy parsing yields 4014).
        originalWeightText = baby.birthWeightGrams.map { String(Double($0) / 1000) } ?? ""
        weightText = originalWeightText
    }

    var isBusy: Bool { saveAttemptID != nil }
    var isDirty: Bool {
        let weightChanged: Bool
        if AndroidText.trimmed(weightText) == originalWeightText { weightChanged = false }
        else {
            switch BabyProfileRules.parseBirthWeightKilograms(weightText) {
            case let .valid(grams): weightChanged = grams != original.birthWeightGrams
            case .invalid: weightChanged = true
            }
        }
        return !AndroidText.trimmed(nickname).utf8.elementsEqual(original.nickname.utf8)
            || sex != original.sex || birthday != original.birthday || weightChanged
    }

    mutating func editNickname(_ value: String) {
        guard !isBusy else { return }
        nickname = value; nicknameError = nil; formError = nil
    }
    mutating func editSex(_ value: String?) {
        guard !isBusy else { return }
        sex = BabyProfileRules.normalizedSex(value); sexError = nil; formError = nil
    }
    mutating func editBirthday(_ value: String) {
        guard !isBusy else { return }
        birthday = value; birthdayError = nil; formError = nil
    }
    mutating func editWeight(_ value: String) {
        guard !isBusy else { return }
        weightText = value; weightError = nil; formError = nil
    }

    mutating func beginSave() -> OfflineBabyProfileSave? {
        guard !isBusy else { return nil }
        let trimmedName = AndroidText.trimmed(nickname)
        nicknameError = trimmedName.isEmpty ? "请填写昵称" :
            (trimmedName.unicodeScalars.count > 20 ? "宝宝昵称最多 20 个字符" : nil)
        sexError = sex == nil || sex == "female" || sex == "male" ? nil : "请选择性别，或明确选择未设置"
        birthdayError = BabyProfileRules.validBirthday(birthday) ? nil : "请选择有效的出生日期"
        let grams: Int?
        switch BabyProfileRules.parseBirthWeightKilograms(weightText) {
        case let .valid(parsed):
            grams = AndroidText.trimmed(weightText) == originalWeightText ? original.birthWeightGrams : parsed
            weightError = nil
        case let .invalid(message):
            grams = nil; weightError = message
        }
        guard nicknameError == nil, sexError == nil, birthdayError == nil, weightError == nil else { return nil }
        let attempt = UUID()
        saveAttemptID = attempt; formError = nil
        return .init(attemptID: attempt, babyClientUUID: original.clientUUID,
                     nickname: trimmedName, sex: sex, birthday: birthday,
                     birthWeightGrams: grams, context: context, expectedUpdatedAt: original.updatedAt)
    }

    @discardableResult
    mutating func finishSave(attemptID: UUID, failure: String? = nil) -> Bool {
        guard saveAttemptID == attemptID else { return false }
        saveAttemptID = nil; formError = failure
        return true
    }
}

struct OfflineBabyProfileSave: Equatable, Sendable {
    let attemptID: UUID
    let babyClientUUID: UUID
    let nickname: String
    let sex: String?
    let birthday: String
    let birthWeightGrams: Int?
    let context: OfflineContext
    let expectedUpdatedAt: Int64
}

/// Date-only persistence must not depend on the user's locale, calendar or DST.
/// Android's profile picker likewise interprets its selected day in UTC.
enum OfflineBabyBirthday {
    static var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        return value
    }
    private static var formatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.isLenient = false
        return formatter
    }
    static func date(_ day: String) -> Date? {
        // Earlier native calendar values use Julian rules, unlike LocalDate.
        // Keep those days in the view's exact text editor instead.
        guard BabyProfileRules.validBirthday(day), day >= "1582-10-15" else { return nil }
        let formatter = formatter
        guard let date = formatter.date(from: day), formatter.string(from: date) == day else { return nil }
        return date
    }
    static func day(_ date: Date) -> String { formatter.string(from: date) }
}
