import Foundation

/// Concrete catalog identity; bare custom never denotes an entry target.
/// Device-local numeric custom IDs remain parseable for migration only.
public struct RecordItemIdentity: Equatable, Hashable, Sendable {
    public let catalogKey: String
    public let recordType: RecordType
    public let legacyLocalCustomID: Int64?
    public let familyCustomUUID: String?

    private init(key: String, type: RecordType, localID: Int64? = nil, familyUUID: String? = nil) {
        catalogKey = key; recordType = type; legacyLocalCustomID = localID; familyCustomUUID = familyUUID
    }
    public static func builtIn(_ type: RecordType) -> Self? {
        guard type != .custom else { return nil }
        return Self(key: type.rawValue, type: type)
    }
    public static func parseCatalogKey(_ key: String) -> Self? {
        if key.hasPrefix("custom:") {
            let suffix = String(key.dropFirst(7))
            if let localID = kotlinDecimalInt64(suffix) {
                guard localID > 0 else { return nil }
                return Self(key: "custom:\(localID)", type: .custom, localID: localID)
            }
            let normalized = AndroidText.trimmed(suffix).lowercased()
            guard let uuid = UUID(uuidString: normalized), uuid.uuidString.lowercased() == normalized else { return nil }
            return Self(key: "custom:\(normalized)", type: .custom, familyUUID: normalized)
        }
        return RecordType(rawValue: key).flatMap(builtIn)
    }
}

public struct NursingConfirmIssue: Equatable, Sendable {
    public enum Field: Sendable { case duration, order, amount }
    public let field: Field
    public let message: String
}

/// Editable text retained until explicit confirmation, matching NursingConfirmInput.kt.
public struct NursingConfirmInput: Equatable, Sendable {
    public static let orders: Set<String> = ["L", "R", "LR", "RL"]
    public let leftMinutes: String
    public let rightMinutes: String
    public let order: String
    public let amountMl: String
    public init(leftMinutes: String, rightMinutes: String, order: String, amountMl: String = "") {
        self.leftMinutes = leftMinutes; self.rightMinutes = rightMinutes; self.order = order; self.amountMl = amountMl
    }
    public func validationIssue(allowIntentOnly: Bool = false) -> NursingConfirmIssue? {
        guard let left = kotlinDecimalInt32(leftMinutes), let right = kotlinDecimalInt32(rightMinutes), (0...1440).contains(left), (0...1440).contains(right) else {
            return .init(field: .duration, message: "左右时长需为 0–1440 分钟的整数")
        }
        if left + right <= 0 && !allowIntentOnly { return .init(field: .duration, message: "请填写左侧或右侧喂养时长") }
        if !Self.orders.contains(order) { return .init(field: .order, message: "请选择喂养顺序") }
        if !amountMl.isDomainBlank && !(kotlinDecimalInt32(amountMl).map { (1...999).contains($0) } ?? false) {
            return .init(field: .amount, message: "奶量需在 1–999 ml 之间")
        }
        return nil
    }
    /// No persistence side effect. Call validationIssue before presenting confirmation.
    public func payload(recordMode: String = "end") -> RecordPayload {
        .nursing(leftMinutes: Int(kotlinDecimalInt32(leftMinutes) ?? 0), rightMinutes: Int(kotlinDecimalInt32(rightMinutes) ?? 0), order: order,
                 amountMl: amountMl.isDomainBlank ? nil : kotlinDecimalInt32(amountMl).map(Int.init), recordMode: recordMode)
    }
}

/// An immutable confirmation snapshot, NOT a queued mutation or wire envelope.
/// Creating it never writes a fact; storage transactions and ACL remain unimplemented.
public struct RecordConfirmationDraft: Equatable, Sendable {
    public static let maximumPhotos = 3
    public let clientUUID: UUID
    public let babyClientUUID: UUID
    public let item: RecordItemIdentity
    public let timestampMillis: Int64
    public let endTimestampMillis: Int64?
    public let note: String?
    public let payload: RecordPayload
    public let photoClientUUIDs: [UUID]
    public init(clientUUID: UUID, babyClientUUID: UUID, item: RecordItemIdentity,
                timestampMillis: Int64, endTimestampMillis: Int64? = nil, note: String? = nil,
                payload: RecordPayload, photoClientUUIDs: [UUID] = []) {
        self.clientUUID = clientUUID; self.babyClientUUID = babyClientUUID; self.item = item
        self.timestampMillis = timestampMillis; self.endTimestampMillis = endTimestampMillis
        self.note = note; self.payload = payload; self.photoClientUUIDs = photoClientUUIDs
    }
    public func validationErrors(nowMillis: Int64) -> [String] {
        var errors = payload.validationErrors()
        if item.recordType != payload.type { errors.append("记录类型与内容不一致") }
        if photoClientUUIDs.count > Self.maximumPhotos { errors.append("最多添加 3 张照片") }
        let timeError = RecordTime.intervalError(start: timestampMillis, end: endTimestampMillis, now: nowMillis)
        if let timeError { errors.append(timeError) }
        return errors
    }
}

public enum CarePlanStatus: String, Sendable {
    case pending, missed, completed, skipped
    public static func fromStorage(_ raw: String) -> Self { Self(rawValue: raw.lowercased()) ?? .pending }
    public func effectiveStatus(scheduledAt: Int64, now: Int64) -> Self {
        self == .pending && scheduledAt < now ? .missed : self
    }
}

public enum NextFeedPlanNote {
    public static let marker = "[[lezi:next-feed:v1]]"
    public static func isNextFeed(_ note: String?) -> Bool { note?.hasPrefix(marker) ?? false }
    public static func visible(_ note: String?) -> String? {
        guard let note else { return nil }
        let remainder = note.hasPrefix(marker) ? String(note.dropFirst(marker.count)) : note
        let leadingTrimmed = AndroidText.trimmedStart(remainder)
        return leadingTrimmed.isDomainBlank ? nil : leadingTrimmed
    }
    public static func encode(_ visible: String?) -> String {
        let text = visible.map(AndroidText.trimmed) ?? ""
        return text.isEmpty ? marker : marker + " " + text
    }
}

public enum RootPublicationState: Sendable {
    case neverPublished, previousVersionPublished, currentVersionPublished
    public static func resolve(localUpdatedAt: Int64, familyPublishedUpdatedAt: Int64?) -> Self {
        guard let receipt = familyPublishedUpdatedAt, receipt > 0 else { return .neverPublished }
        if receipt == localUpdatedAt { return .currentVersionPublished }
        return receipt < localUpdatedAt ? .previousVersionPublished : .neverPublished
    }
}

// Kotlin's integer parser accepts BMP Unicode decimal digits, but neither
// whitespace nor fractions. Normalize those digits before Swift range checking.
func kotlinDecimalInt64(_ text: String) -> Int64? {
    var normalized = ""
    for (index, scalar) in text.unicodeScalars.enumerated() {
        if index == 0 && (scalar == "+" || scalar == "-") { normalized.unicodeScalars.append(scalar); continue }
        guard scalar.value <= 0xFFFF, scalar.properties.generalCategory == .decimalNumber,
              let value = Character(String(scalar)).wholeNumberValue else { return nil }
        normalized += String(value)
    }
    return Int64(normalized)
}
func kotlinDecimalInt32(_ text: String) -> Int32? { kotlinDecimalInt64(text).flatMap(Int32.init(exactly:)) }
