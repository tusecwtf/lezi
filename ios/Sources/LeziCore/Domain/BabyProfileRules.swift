import Foundation

public enum BirthWeightInputResult: Equatable, Sendable {
    case valid(Int?)
    case invalid(String)
}

public enum BabyProfileValidationError: Error, Equatable, Sendable, LocalizedError {
    case nicknameTooLong

    public var errorDescription: String? { "宝宝昵称最多 20 个字符" }
}

/// Android BabyNickname/BabyProfileConstraints and canonical sex rules, without
/// a platform UI, persistence model, family role, or network dependency.
public enum BabyProfileRules {
    public static let maximumNicknameScalars = 20
    public static let minimumBirthWeightGrams = 500
    public static let maximumBirthWeightGrams = 9_000
    private static let weightFormatMessage = "出生体重格式不正确，请填写数字（如 3.20）"

    public static func normalizedSex(_ raw: String?) -> String? {
        guard let raw else { return nil }
        switch AndroidText.trimmed(raw).lowercased() {
        case "male", "m", "男", "男宝": return "male"
        case "female", "f", "女", "女宝": return "female"
        default: return nil
        }
    }

    public static func normalizedNickname(_ raw: String, fallback: String = "年年") throws -> String {
        let trimmed = AndroidText.trimmed(raw)
        let nickname = trimmed.isEmpty ? fallback : trimmed
        guard nickname.unicodeScalars.count <= maximumNicknameScalars else {
            throw BabyProfileValidationError.nicknameTooLong
        }
        return nickname
    }

    public static func nicknameValidationError(_ raw: String, fallback: String = "年年") -> String? {
        do {
            _ = try normalizedNickname(raw, fallback: fallback)
            return nil
        } catch {
            return BabyProfileValidationError.nicknameTooLong.errorDescription
        }
    }

    public static func birthWeightValidationError(_ grams: Int?) -> String? {
        guard let grams else { return nil }
        return (minimumBirthWeightGrams...maximumBirthWeightGrams).contains(grams)
            ? nil : "出生体重需在 500–9000 克之间"
    }

    /// Do not use Decimal or round to the nearest gram: Android intentionally
    /// converts Double then truncates, including 4.015 kg becoming 4014 g.
    public static func parseBirthWeightKilograms(_ raw: String) -> BirthWeightInputResult {
        let text = AndroidText.trimmed(raw)
        guard !text.isEmpty else { return .valid(nil) }
        guard text.range(of: #"\A(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)\z"#, options: .regularExpression) != nil,
              let kilograms = Double(text), kilograms.isFinite else {
            return .invalid(weightFormatMessage)
        }
        let grams = (kilograms * 1_000).rounded(.towardZero)
        // Kotlin Double.toInt saturates out-of-range values before validation.
        // Check the accepted interval before Swift's otherwise trapping cast.
        guard grams.isFinite,
              grams >= Double(minimumBirthWeightGrams),
              grams <= Double(maximumBirthWeightGrams) else {
            return .invalid("出生体重需在 500–9000 克之间")
        }
        return .valid(Int(grams))
    }

    /// Strict proleptic Gregorian yyyy-MM-dd. Date-only input is independent of
    /// the device's calendar, time zone, DST, and the current day.
    public static func validBirthday(_ value: String) -> Bool {
        let bytes = Array(value.utf8)
        guard bytes.count == 10, bytes[4] == 45, bytes[7] == 45,
              bytes.enumerated().allSatisfy({ index, byte in
                  index == 4 || index == 7 || (48...57).contains(byte)
              }) else { return false }
        let year = Int(bytes[0] - 48) * 1_000 + Int(bytes[1] - 48) * 100
            + Int(bytes[2] - 48) * 10 + Int(bytes[3] - 48)
        let month = Int(bytes[5] - 48) * 10 + Int(bytes[6] - 48)
        let day = Int(bytes[8] - 48) * 10 + Int(bytes[9] - 48)
        guard year > 0, (1...12).contains(month) else { return false }
        let leap = year.isMultiple(of: 4) && (!year.isMultiple(of: 100) || year.isMultiple(of: 400))
        let lengths = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        return (1...lengths[month - 1]).contains(day)
    }
}
