import Foundation

/// Strict frozen baby mutation root. No ingress trimming/defaulting: hashes cover
/// received canonical values, before the server's ingress normalization.
public struct BabyCanonicalRoot: Equatable, Sendable {
    public let nickname: String
    public let sex: String?
    public let birthday: String?
    public let birthWeightGrams: Int64?
    public let avatarMediaUUID: String?
    public let updatedAt: Int64

    public static func == (lhs: Self, rhs: Self) -> Bool {
        // Swift String equality normalizes Unicode; canonical wire identity does not.
        lhs.nickname.utf8.elementsEqual(rhs.nickname.utf8) && lhs.sex == rhs.sex &&
            lhs.birthday == rhs.birthday && lhs.birthWeightGrams == rhs.birthWeightGrams &&
            lhs.avatarMediaUUID == rhs.avatarMediaUUID && lhs.updatedAt == rhs.updatedAt
    }

    static let keys: Set<String> = ["nickname", "sex", "birthday", "birth_weight_grams", "avatar_media_uuid", "updated_at"]

    public static func decode(_ data: Data) throws -> Self { try decode(WireJSON.parse(data)) }

    static func decode(_ value: WireJSON) throws -> Self {
        let fields = try value.object(keys: keys)
        let nickname = try fields.field("nickname").string("nickname")
        guard !nickname.isEmpty, trimAndroidWireString(nickname) == nickname,
              nickname.unicodeScalars.count <= 20 else { throw WireContractError.invalidValue("nickname") }
        let sex = try nullableString(fields.field("sex"), "sex")
        guard sex == nil || sex == "female" || sex == "male" else { throw WireContractError.invalidValue("sex") }
        let birthday = try nullableString(fields.field("birthday"), "birthday")
        if let birthday { try validateCanonicalBabyDate(birthday) }
        let weight = try nullableInteger(fields.field("birth_weight_grams"), "birth_weight_grams")
        if let weight, !(0...100_000).contains(weight) { throw WireContractError.invalidValue("birth_weight_grams") }
        let avatar = try nullableString(fields.field("avatar_media_uuid"), "avatar_media_uuid")
        if let avatar { try validateBabyUUID(avatar, "avatar_media_uuid") }
        let updatedAt = try babyInteger(fields.field("updated_at"), "updated_at")
        guard updatedAt >= 0 else { throw WireContractError.invalidValue("updated_at") }
        return Self(nickname: nickname, sex: sex, birthday: birthday, birthWeightGrams: weight,
                    avatarMediaUUID: avatar, updatedAt: updatedAt)
    }

    var wire: WireJSON {
        .object(["nickname": .string(nickname), "sex": sex.map(WireJSON.string) ?? .null,
                 "birthday": birthday.map(WireJSON.string) ?? .null,
                 "birth_weight_grams": birthWeightGrams.map { .number(String($0)) } ?? .null,
                 "avatar_media_uuid": avatarMediaUUID.map(WireJSON.string) ?? .null,
                 "updated_at": .number(String(updatedAt))])
    }

    public func encoded() throws -> Data { Data(try integerCanonicalJSON(wire).utf8) }
}

/// Stamped stable/snapshot root, deliberately distinct from a mutation root.
/// This does not decode a complete stable receipt or incremental pull envelope.
public struct BabyStableRoot: Equatable, Sendable {
    public let root: BabyCanonicalRoot
    public let createdByMembershipID: String

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.root == rhs.root && lhs.createdByMembershipID.utf8.elementsEqual(rhs.createdByMembershipID.utf8)
    }

    public static func decode(_ data: Data) throws -> Self {
        var fields = try WireJSON.parse(data).object(keys: BabyCanonicalRoot.keys.union(["created_by_membership_id"]))
        let creator = try fields.field("created_by_membership_id").string("created_by_membership_id")
        guard (1...64).contains(creator.utf16.count) else { throw WireContractError.invalidValue("created_by_membership_id") }
        fields.removeValue(forKey: "created_by_membership_id")
        return Self(root: try BabyCanonicalRoot.decode(.object(fields)), createdByMembershipID: creator)
    }

    public func encoded() throws -> Data {
        guard case var .object(fields) = root.wire else { throw WireContractError.malformedJSON }
        fields["created_by_membership_id"] = .string(createdByMembershipID)
        return Data(try integerCanonicalJSON(.object(fields)).utf8)
    }
}

/// Baby-only media boundary: one avatar at most, required explicit-null dimensions.
public struct BabyCanonicalMedia: Equatable, Sendable {
    public let mediaUUID: String
    public let sha256: String
    public let byteSize: Int64
    public let mime: String
    public let width: Int64?
    public let height: Int64?

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.mediaUUID == rhs.mediaUUID && lhs.sha256 == rhs.sha256 && lhs.byteSize == rhs.byteSize &&
            lhs.mime.utf8.elementsEqual(rhs.mime.utf8) && lhs.width == rhs.width && lhs.height == rhs.height
    }

    public static func decode(_ data: Data) throws -> Self { try decode(WireJSON.parse(data)) }

    static func decode(_ value: WireJSON) throws -> Self {
        let fields = try value.object(keys: ["media_uuid", "role", "sha256", "byte_size", "mime", "width", "height"])
        let uuid = try fields.field("media_uuid").string("media_uuid")
        try validateBabyUUID(uuid, "media_uuid")
        guard try fields.field("role").string("role") == "avatar" else { throw WireContractError.invalidValue("role") }
        let sha = try fields.field("sha256").string("sha256")
        guard sha.utf8.count == 64, sha.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else {
            throw WireContractError.invalidValue("sha256")
        }
        let size = try babyInteger(fields.field("byte_size"), "byte_size")
        guard size > 0 else { throw WireContractError.invalidValue("byte_size") }
        let mime = try fields.field("mime").string("mime")
        // Bounded to the intersection of server byte and strict snapshot limits.
        guard !mime.isEmpty, mime.utf8.count <= 128 else { throw WireContractError.invalidValue("mime") }
        let width = try nullableInteger(fields.field("width"), "width")
        let height = try nullableInteger(fields.field("height"), "height")
        for (field, dimension) in [("width", width), ("height", height)] {
            if let dimension, !(1...Int64(Int32.max)).contains(dimension) { throw WireContractError.invalidValue(field) }
        }
        return Self(mediaUUID: uuid, sha256: sha, byteSize: size, mime: mime, width: width, height: height)
    }

    var wire: WireJSON {
        .object(["media_uuid": .string(mediaUUID), "role": .string("avatar"), "sha256": .string(sha256),
                 "byte_size": .number(String(byteSize)), "mime": .string(mime),
                 "width": width.map { .number(String($0)) } ?? .null,
                 "height": height.map { .number(String($0)) } ?? .null])
    }

    public func encoded() throws -> Data { Data(try integerCanonicalJSON(wire).utf8) }
}

func validateBabyUUID(_ value: String, _ field: String) throws {
    guard let uuid = UUID(uuidString: value), uuid.uuidString.lowercased() == value else {
        throw WireContractError.invalidValue(field)
    }
}

private func nullableString(_ value: WireJSON, _ field: String) throws -> String? {
    if value == .null { return nil }; return try value.string(field)
}

private func nullableInteger(_ value: WireJSON, _ field: String) throws -> Int64? {
    if value == .null { return nil }; return try babyInteger(value, field)
}

/// Proleptic Gregorian canonical ISO LocalDate spelling, including year zero.
private func validateCanonicalBabyDate(_ value: String) throws {
    let parts = value.split(separator: "-", omittingEmptySubsequences: false)
    let negative = value.hasPrefix("-")
    let p = negative ? Array(parts.dropFirst()) : parts
    guard p.count == 3, let yearMagnitude = Int(p[0]), let month = Int(p[1]), let day = Int(p[2]),
          (1...12).contains(month), (0...999_999_999).contains(yearMagnitude) else {
        throw WireContractError.invalidValue("birthday")
    }
    let year = negative ? -yearMagnitude : yearMagnitude
    let yearString: String
    if year < 0 { yearString = "-" + String(format: "%04d", -year) }
    else if year > 9999 { yearString = "+" + String(year) }
    else { yearString = String(format: "%04d", year) }
    let canonical = yearString + String(format: "-%02d-%02d", month, day)
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    let days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    guard canonical == value, (1...days[month - 1]).contains(day) else { throw WireContractError.invalidValue("birthday") }
}

private func babyInteger(_ value: WireJSON, _ field: String) throws -> Int64 {
    // serde_json treats negative zero specially; exclude it from this integer-only boundary.
    if value == .number("-0") { throw WireContractError.invalidValue(field) }
    return try value.integer(field)
}
