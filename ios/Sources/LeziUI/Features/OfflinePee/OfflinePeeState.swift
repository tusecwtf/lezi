import Foundation
import LeziCore
import LeziStorage

/// Bounded supported record types; unsupported kinds never fall back to empty facts.
public enum OfflineRecordKind: String, CaseIterable, Hashable, Sendable {
    case pee, poop, bothDiaper = "both_diaper", diary, bath, walk, cough, rash, vomit, injury, medicine, hospital, vaccine, height, weight, head, chest, footSize = "foot_size", formula, pumpedFeed = "pumped_feed", pumpExpress = "pump_express"
    public static let excretionCases: [Self] = [.pee, .poop, .bothDiaper]
    public var recordType: RecordType {
        switch self { case .pee: .pee; case .poop: .poop; case .bothDiaper: .bothDiaper; case .diary: .diary; case .bath: .bath; case .walk: .walk; case .cough: .cough; case .rash: .rash; case .vomit: .vomit; case .injury: .injury; case .medicine: .medicine; case .hospital: .hospital; case .vaccine: .vaccine; case .height: .height; case .weight: .weight; case .head: .head; case .chest: .chest; case .footSize: .footSize; case .formula: .formula; case .pumpedFeed: .pumpedFeed; case .pumpExpress: .pumpExpress }
    }
    public var label: String {
        switch self { case .pee: "尿记录"; case .poop: "便记录"; case .bothDiaper: "尿+便记录"; case .diary: "日记"; case .bath: "洗澡记录"; case .walk: "散步记录"; case .cough: "咳嗽记录"; case .rash: "皮疹记录"; case .vomit: "呕吐记录"; case .injury: "受伤记录"; case .medicine: "用药记录"; case .hospital: "就医记录"; case .vaccine: "疫苗记录"; case .height: "身高"; case .weight: "体重"; case .head: "头围"; case .chest: "胸围"; case .footSize: "足长"; case .formula: "配方奶"; case .pumpedFeed: "母乳瓶喂"; case .pumpExpress: "挤奶" }
    }
    public var hasPee: Bool { self == .pee || self == .bothDiaper }
    public var hasStool: Bool { self == .poop || self == .bothDiaper }
}

/// Source compatibility for the earlier excretion-only checkpoint.
public typealias OfflineExcretionKind = OfflineRecordKind

/// Kotlin Char.isWhitespace/trim and UTF-16 limits, without lossy surrogate truncation.
public enum OfflineRecordText {
    public static func trimmed(_ text: String) -> String { AndroidText.trimmed(text) }
    public static func optional(_ text: String) -> String? {
        let value = trimmed(text)
        return value.isEmpty ? nil : value
    }
}

/// Copy aligned with Android RecordSummary.kt; unknown values never masquerade as defaults.
public enum OfflineExcretionLabels {
    public static func pee(_ value: Int) -> String { [1: "小", 2: "中", 3: "大"][value] ?? "未知" }
    public static func amount(_ value: Int) -> String { [1: "一点", 2: "偏少", 3: "正常", 4: "偏多"][value] ?? "未知" }
    public static func consistency(_ value: Int) -> String { [1: "稀", 2: "偏软", 3: "正常", 4: "偏硬"][value] ?? "未知" }
    public static func color(_ value: Int) -> String {
        [0: "未选", 1: "白", 2: "黄", 3: "橙", 4: "褐", 5: "绿", 6: "红", 7: "黑"][value] ?? "未知"
    }
}

/// Historical name retained for source compatibility. One immutable draft owns all
/// supported care kinds; inactive values survive a new-draft type switch back.
public struct OfflinePeeDraft: Equatable, Sendable {
    public let clientUUID: UUID
    public let babyClientUUID: UUID
    public let timestampMillis: Int64
    public let kind: OfflineRecordKind
    /// Urine amount; kept under its original property name for existing callers.
    public let amount: Int
    public let stoolAmount: Int
    public let stoolConsistency: Int
    public let stoolColor: Int
    public let note: String
    public let body: String
    public let health: OfflineHealthDraft
    public let growth: OfflineGrowthDraft
    public let milk: OfflineMilkDraft

    public init(clientUUID: UUID = UUID(), babyClientUUID: UUID, timestampMillis: Int64,
                amount: Int = 2, note: String = "", kind: OfflineRecordKind = .pee,
                stoolAmount: Int = 3, stoolConsistency: Int = 3, stoolColor: Int = 0, body: String = "", health: OfflineHealthDraft = .init(), growth: OfflineGrowthDraft = .init(), milk: OfflineMilkDraft = .init()) {
        self.clientUUID = clientUUID; self.babyClientUUID = babyClientUUID
        self.timestampMillis = timestampMillis; self.amount = amount; self.note = note
        self.kind = kind; self.stoolAmount = stoolAmount
        self.stoolConsistency = stoolConsistency; self.stoolColor = stoolColor; self.body = body; self.health = health; self.growth = growth; self.milk = milk
    }
    public func editing(timestampMillis: Int64? = nil, amount: Int? = nil, note: String? = nil,
                        kind: OfflineRecordKind? = nil, stoolAmount: Int? = nil,
                        stoolConsistency: Int? = nil, stoolColor: Int? = nil, body: String? = nil, health: OfflineHealthDraft? = nil, growth: OfflineGrowthDraft? = nil, milk: OfflineMilkDraft? = nil) -> Self {
        Self(clientUUID: clientUUID, babyClientUUID: babyClientUUID,
             timestampMillis: timestampMillis ?? self.timestampMillis,
             amount: amount ?? self.amount, note: note ?? self.note, kind: kind ?? self.kind,
             stoolAmount: stoolAmount ?? self.stoolAmount,
             stoolConsistency: stoolConsistency ?? self.stoolConsistency, stoolColor: stoolColor ?? self.stoolColor,
             body: body ?? self.body, health: health ?? self.health, growth: growth ?? self.growth, milk: milk ?? self.milk)
    }
    public var payload: RecordPayload {
        switch kind {
        case .pee: .pee(amount: amount)
        case .poop: .stool(amount: stoolAmount, consistency: stoolConsistency, color: stoolColor)
        case .bothDiaper: .bothDiaper(peeAmount: amount, stoolAmount: stoolAmount,
                                    stoolConsistency: stoolConsistency, stoolColor: stoolColor)
        case .diary: .diary(body: OfflineRecordText.trimmed(body))
        case .bath: .bath
        case .walk: .walk
        case .cough: .symptom(kind: .cough, severity: health.severity, description: OfflineRecordText.optional(health.description))
        case .rash: .symptom(kind: .rash, severity: health.severity, description: OfflineRecordText.optional(health.description))
        case .vomit: .symptom(kind: .vomit, severity: health.severity, description: OfflineRecordText.optional(health.description))
        case .injury: .symptom(kind: .injury, severity: health.severity, description: OfflineRecordText.optional(health.description))
        case .medicine: .medicine(name: OfflineRecordText.trimmed(health.medicineName), dose: OfflineRecordText.optional(health.medicineDose))
        case .hospital: .hospital(reason: OfflineRecordText.trimmed(health.hospitalReason), advice: OfflineRecordText.optional(health.hospitalAdvice))
        case .formula: milk.confirmationPayload(kind: .formula)
        case .pumpedFeed: milk.confirmationPayload(kind: .pumpedFeed)
        case .pumpExpress: milk.confirmationPayload(kind: .pumpExpress)
        case .height: growth.confirmationPayload(kind: .height)
        case .weight: growth.confirmationPayload(kind: .weight)
        case .head: growth.confirmationPayload(kind: .head)
        case .chest: growth.confirmationPayload(kind: .chest)
        case .footSize: growth.confirmationPayload(kind: .footSize)
        case .vaccine: .vaccine(name: OfflineRecordText.trimmed(health.vaccineName), batch: OfflineRecordText.optional(health.vaccineBatch))
        }
    }
    public var confirmation: RecordConfirmationDraft {
        .init(clientUUID: clientUUID, babyClientUUID: babyClientUUID, item: .builtIn(kind.recordType)!,
              timestampMillis: timestampMillis, note: OfflineRecordText.optional(note), payload: payload)
    }
}

/// Selection is deliberately not editor ownership. A different selected baby never retargets this state.
public struct OfflinePeeState: Equatable, Sendable {
    public private(set) var draft: OfflinePeeDraft
    public var session: LeziConfirmationSession
    public private(set) var milkStep: Int
    public let expectedUpdatedAt: Int64?
    private let initialDraft: OfflinePeeDraft
    public private(set) var deletionAttemptID: UUID?
    public private(set) var deletionFailure: String?
    public var isEditing: Bool { expectedUpdatedAt != nil }
    public var isDirty: Bool { draft != initialDraft }
    public var isBusy: Bool { session.isSaving || deletionAttemptID != nil }
    public enum DismissDecision: Equatable, Sendable { case close, confirmDiscard, ignoreBusy }
    public var dismissDecision: DismissDecision {
        isBusy ? .ignoreBusy : (isDirty ? .confirmDiscard : .close)
    }
    private var persistenceAttemptID: UUID?
    public init(babyClientUUID: UUID, babyName: String, timestampMillis: Int64, kind: OfflineRecordKind = .pee, milkRecent: [RecordType: [Int]] = [:], milkStep: Int = 5) {
        draft = .init(babyClientUUID: babyClientUUID, timestampMillis: timestampMillis, kind: kind, milk: .init(recent: milkRecent))
        self.milkStep = OfflineMilkDraft.step(milkStep)
        initialDraft = draft; expectedUpdatedAt = nil
        session = .init(context: .init(babyClientUUID: babyClientUUID, babyName: babyName))
    }
    /// Fail closed rather than discarding an unknown payload or unsupported interval.
    public static func canEdit(_ record: OfflineRecord) -> Bool {
        guard OfflineRecordKind(rawValue: record.rawType) != nil, !record.isOpaque,
              record.deletedAt == nil, record.endTimestampMillis == nil,
              let payload = record.payloadDocument?.payload, payload.type == record.type else { return false }
        if case .measurement = payload, !OfflineGrowthDraft.supports(payload) { return false }
        return payload.validationErrors().isEmpty
    }
    public init?(record: OfflineRecord, babyName: String, milkRecent: [RecordType: [Int]] = [:], milkStep: Int = 5) {
        guard Self.canEdit(record), let kind = OfflineRecordKind(rawValue: record.rawType),
              let payload = record.payloadDocument?.payload else { return nil }
        let pee: Int, stool: Int, consistency: Int, color: Int
        var body = ""
        switch payload {
        case let .pee(amount): (pee, stool, consistency, color) = (amount, 3, 3, 0)
        case let .stool(amount, soft, shade): (pee, stool, consistency, color) = (2, amount, soft, shade)
        case let .bothDiaper(amount, poop, soft, shade): (pee, stool, consistency, color) = (amount, poop, soft, shade)
        case let .diary(text):
            (pee, stool, consistency, color) = (2, 3, 3, 0); body = text
        case .bath, .walk, .symptom, .medicine, .hospital, .vaccine, .measurement, .milk: (pee, stool, consistency, color) = (2, 3, 3, 0)
        default: return nil
        }
        draft = .init(clientUUID: record.clientUUID, babyClientUUID: record.babyClientUUID,
                      timestampMillis: record.timestampMillis, amount: pee, note: record.note ?? "", kind: kind,
                      stoolAmount: stool, stoolConsistency: consistency, stoolColor: color, body: body, health: .init(payload: payload), growth: .init(payload: payload), milk: .init(payload: payload, recent: milkRecent))
        self.milkStep = OfflineMilkDraft.step(milkStep)
        initialDraft = draft; expectedUpdatedAt = record.updatedAt
        session = .init(context: .init(babyClientUUID: record.babyClientUUID, babyName: babyName))
    }
    public mutating func edit(timestampMillis: Int64? = nil, amount: Int? = nil, note: String? = nil,
                              kind: OfflineRecordKind? = nil, stoolAmount: Int? = nil,
                              stoolConsistency: Int? = nil, stoolColor: Int? = nil, body: String? = nil, health: OfflineHealthDraft? = nil, growth: OfflineGrowthDraft? = nil, milk: OfflineMilkDraft? = nil) {
        guard session.canInteract, !isBusy else { return }
        // Editing a stored fact never changes its type, including programmatic calls.
        guard !isEditing || kind == nil || kind == draft.kind else { return }
        draft = draft.editing(timestampMillis: timestampMillis, amount: amount, note: note, kind: kind,
                              stoolAmount: stoolAmount, stoolConsistency: stoolConsistency, stoolColor: stoolColor, body: body, health: health, growth: growth, milk: milk)
        session.draftEdited(); deletionFailure = nil
    }
    public mutating func setMilkStep(_ value: Int) {
        guard session.canInteract, !isBusy else { return }
        milkStep = OfflineMilkDraft.step(value)
    }
    public func featureIssue(nowMillis: Int64) -> LeziConfirmationIssue? {
        if draft.timestampMillis > nowMillis {
            return .init(message: "不能保存未来记录；此开发组件尚不支持护理计划", fieldID: "time")
        }
        if draft.note.utf16.count > 200 { return .init(message: "备注最多 200 字", fieldID: "note") }
        if draft.kind == .diary {
            if OfflineRecordText.trimmed(draft.body).isEmpty {
                return .init(message: "正文不能为空", fieldID: "body")
            }
            // Android loads legacy body without truncation; its input limit applies on change.
            if draft.body.utf16.count > 800 && !(isEditing && draft.body == initialDraft.body) {
                return .init(message: "日记正文最多 800 字", fieldID: "body")
            }
        }
        if let kind = draft.kind.milkKind { return draft.milk.issue(kind: kind) }
        if let kind = draft.kind.measurementKind { return draft.growth.issue(kind: kind) }
        return draft.health.issue(kind: draft.kind, original: isEditing ? initialDraft.health : nil)
    }
    public mutating func confirm(nowMillis: Int64) -> LeziConfirmationSession.TapResult {
        let issue = featureIssue(nowMillis: nowMillis)
        return session.confirm(draft: draft.confirmation, nowMillis: nowMillis, canWrite: true, externallyBusy: deletionAttemptID != nil, featureIssue: issue)
    }
    public func accepts(_ confirmation: LeziConfirmedRecord) -> Bool {
        session.phase == .saving(confirmation.attemptID) && confirmation.context == session.context &&
        confirmation.draft == draft.confirmation
    }
    /// The UI callback may be delivered twice; only one writer may claim an attempt.
    public mutating func beginPersistence(_ confirmation: LeziConfirmedRecord) -> Bool {
        guard accepts(confirmation), featureIssue(nowMillis: confirmation.confirmedAtMillis) == nil, deletionAttemptID == nil, persistenceAttemptID == nil else { return false }
        persistenceAttemptID = confirmation.attemptID
        return true
    }
    /// A storage transaction must have returned successfully before passing a nil failure.
    public mutating func finish(attemptID: UUID, failure: String? = nil) {
        guard session.phase == .saving(attemptID) else { return }
        persistenceAttemptID = nil
        session.finish(attemptID: attemptID, failureMessage: failure)
    }
    public mutating func beginDeletion() -> UUID? {
        guard isEditing, session.canInteract, !isBusy else { return nil }
        let attempt = UUID()
        deletionAttemptID = attempt; deletionFailure = nil
        return attempt
    }
    public mutating func finishDeletion(attemptID: UUID, failure: String? = nil) {
        guard deletionAttemptID == attemptID else { return }
        deletionAttemptID = nil; deletionFailure = failure
        if failure == nil { session.invalidate() }
    }
    public mutating func invalidate() { deletionAttemptID = nil; session.invalidate() }
}
