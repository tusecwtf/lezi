import Foundation
import LeziCore
import LeziStorage

public enum OfflineRecordTarget: Equatable, Sendable {
    case builtIn(OfflineRecordKind)
    case custom(UUID)
    public var kind: OfflineRecordKind? { if case let .builtIn(kind) = self { kind } else { nil } }
    public var item: RecordItemIdentity {
        switch self {
        case let .builtIn(kind): .builtIn(kind.recordType)!
        case let .custom(id): .customDefinition(id)
        }
    }
}

public struct OfflineCustomDraft: Equatable, Sendable {
    public var title = ""
    public var detail = ""
    public let iconSlot: Int?
    public init(title: String = "", detail: String = "", iconSlot: Int? = nil) {
        self.title = title; self.detail = detail; self.iconSlot = iconSlot
    }
    init(payload: RecordPayload) {
        if case let .custom(title, detail, icon) = payload { self.init(title: title, detail: detail ?? "", iconSlot: icon) }
        else { self.init() }
    }
    func issue(original: Self?) -> LeziConfirmationIssue? {
        if AndroidText.isBlank(title) { return .init(message: "请填写项目名称", fieldID: "custom.title") }
        if title.utf16.count > 30 && title != original?.title { return .init(message: "项目名称最多 30 字", fieldID: "custom.title") }
        if detail.utf16.count > 200 && detail != original?.detail { return .init(message: "详情最多 200 字", fieldID: "custom.detail") }
        return nil
    }
    var payload: RecordPayload { .custom(titleSnapshot: AndroidText.trimmed(title), detail: OfflineRecordText.optional(detail), iconSlot: iconSlot) }
}

public struct OfflineCustomDefinitionDraft: Equatable, Sendable {
    public let id: UUID
    public let revision: Int64?
    public var name: String
    public var iconSlot: Int
    private let originalName: String
    private let originalIcon: Int
    public init() { id = UUID(); revision = nil; name = ""; iconSlot = 0; originalName = ""; originalIcon = 0 }
    public init(_ definition: OfflineCustomDefinition) {
        id = definition.clientUUID; revision = definition.updatedAt; name = definition.name; iconSlot = definition.iconSlot
        originalName = name; originalIcon = iconSlot
    }
    public var isDirty: Bool { Array(name.utf16) != Array(originalName.utf16) || iconSlot != originalIcon }
    public var issue: String? {
        if AndroidText.isBlank(name) { return "请输入名称" }
        if name.utf16.count > 20 && !(revision != nil && Array(name.utf16) == Array(originalName.utf16)) { return "名称最多 20 字" }
        if !(0...7).contains(iconSlot) { return "请选择有效图标" }
        return nil
    }
}
