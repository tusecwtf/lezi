import Foundation
import LeziCore

extension OfflineStore {
    public func customDefinitions() throws -> [OfflineCustomDefinition] {
        try db.query(Self.customColumns + " WHERE d.deleted_at IS NULL ORDER BY p.sort_order,p.creation_ordinal").map(Self.decodeCustom)
    }
    public func customDefinition(_ id: UUID) throws -> OfflineCustomDefinition? {
        try db.query(Self.customColumns + " WHERE d.uuid=?", [.text(id.uuidString)]).first.map(Self.decodeCustom)
    }
    public func createCustomDefinition(clientUUID: UUID = UUID(), name: String, iconSlot: Int, generation: UUID) throws -> OfflineCustomDefinition {
        let name = try Self.customName(name, icon: iconSlot)
        return try writeTransaction {
            try requireCustomGeneration(generation)
            if let existing = try customDefinition(clientUUID) {
                guard existing.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
                return existing
            }
            try requireUniqueCustomName(name, excluding: clientUUID)
            let items = try customDefinitions()
            guard items.count < 10 else { throw OfflineStoreError.customDefinitionLimit }
            try db.execute("INSERT INTO custom_definitions VALUES (?,?,?,?,1,NULL)", [.text(clientUUID.uuidString), .text(name), .int(Int64(iconSlot)), .int(clock())])
            // Includes tombstones: local creation order is never reused. This
            // matches Android's id tie-break without making that order identity.
            let maximumText = try db.query("SELECT MAX(creation_ordinal) FROM custom_preferences").first?[0]
            let maximum = maximumText.flatMap(Int64.init) ?? 0
            guard maximum < Int64.max else { throw OfflineStoreError.revisionOverflow }
            try db.execute("INSERT INTO custom_preferences VALUES (?,?,?)", [.text(clientUUID.uuidString), .int(Int64(items.count)), .int(maximum + 1)])
            try inject(.afterFact); try inject(.beforeCommit)
            return try requireCustom(clientUUID)
        }
    }
    public func updateCustomDefinition(_ id: UUID, name: String, iconSlot: Int, generation: UUID, expectedUpdatedAt: Int64) throws -> OfflineCustomDefinition {
        let name = try Self.customName(name, icon: iconSlot)
        return try writeTransaction {
            try requireCustomGeneration(generation)
            let old = try requireCustom(id)
            guard old.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
            guard old.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
            try requireUniqueCustomName(name, excluding: id)
            // Swift String == folds canonical equivalence; Kotlin equality does not.
            if Array(old.name.utf16) != Array(name.utf16) || old.iconSlot != iconSlot {
                let revision = try Self.nextRevision(after: old.updatedAt, now: clock())
                try db.execute("UPDATE custom_definitions SET name=?,icon_slot=?,updated_at=?,sync_dirty=1 WHERE uuid=?", [.text(name), .int(Int64(iconSlot)), .int(revision), .text(id.uuidString)])
            }
            try inject(.afterFact); try inject(.beforeCommit)
            return try requireCustom(id)
        }
    }
    public func deleteCustomDefinition(_ id: UUID, generation: UUID, expectedUpdatedAt: Int64) throws -> Bool {
        try writeTransaction {
            try requireCustomGeneration(generation)
            let old = try requireCustom(id)
            if old.deletedAt != nil { return false }
            guard old.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
            let revision = try Self.nextRevision(after: old.updatedAt, now: clock())
            try db.execute("UPDATE custom_definitions SET deleted_at=?,updated_at=?,sync_dirty=1 WHERE uuid=?", [.int(revision), .int(revision), .text(id.uuidString)])
            try inject(.afterFact); try inject(.beforeCommit)
            return true
        }
    }
    /// Complete live-catalog ordering, at most ten UUIDs. Never modifies facts/dirty.
    public func reorderCustomDefinitions(_ ids: [UUID], generation: UUID) throws {
        try writeTransaction {
            try requireCustomGeneration(generation)
            let live = try customDefinitions()
            guard ids.count == live.count, Set(ids).count == ids.count,
                  Set(ids) == Set(live.map(\.clientUUID)) else { throw OfflineStoreError.staleContext }
            for (index, id) in ids.enumerated() {
                try db.execute("UPDATE custom_preferences SET sort_order=? WHERE definition_uuid=?", [.int(Int64(index)), .text(id.uuidString)])
            }
            try inject(.beforeCommit)
        }
    }

    /// Concrete UUID identity is mandatory. Title/icon are confirmation snapshots,
    /// not live joins: catalog renames never silently rewrite record history.
    public func confirmCustomRecord(_ draft: RecordConfirmationDraft, context: OfflineContext, expectedUpdatedAt: Int64? = nil) throws -> OfflineRecord {
        let now = clock()
        guard draft.payload.type == .custom, draft.item.recordType == .custom,
              let identity = draft.item.familyCustomUUID, let definitionID = UUID(uuidString: identity),
              draft.photoClientUUIDs.isEmpty, draft.validationErrors(nowMillis: now).isEmpty else { throw OfflineStoreError.invalidDraft }
        let json = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: draft.payload))
        return try writeTransaction {
            try requireOfflineContext(context)
            guard draft.babyClientUUID == context.babyClientUUID else { throw OfflineStoreError.staleContext }
            try requireLiveBaby(context.babyClientUUID)
            let old = try recordDetails(draft.clientUUID)
            if let old {
                guard old.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
                guard old.type == .custom, old.babyClientUUID == draft.babyClientUUID,
                      old.customDefinitionUUID == definitionID else { throw OfflineStoreError.identityConflict }
                if expectedUpdatedAt == nil { return old }
                guard old.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
                guard !old.isOpaque else { throw OfflineStoreError.opaqueRecord }
            } else {
                guard expectedUpdatedAt == nil else { throw OfflineStoreError.staleEdit }
                guard try requireCustom(definitionID).deletedAt == nil else { throw OfflineStoreError.deletedRecord }
            }
            let revision = try old.map { try Self.nextRevision(after: $0.updatedAt, now: now) } ?? now
            let values: [SQLiteConnection.Value] = [.int(draft.timestampMillis), draft.endTimestampMillis.map(SQLiteConnection.Value.int) ?? .null,
                draft.note.map(SQLiteConnection.Value.text) ?? .null, .text(json), .int(revision)]
            if old == nil {
                try db.execute("INSERT INTO records (timestamp,end_timestamp,note,payload_json,updated_at,uuid,baby_uuid,custom_definition_uuid,type,payload_schema_version,sync_dirty) VALUES (?,?,?,?,?,?,?,?,'custom',2,1)", values + [.text(draft.clientUUID.uuidString), .text(draft.babyClientUUID.uuidString), .text(definitionID.uuidString)])
            } else {
                try db.execute("UPDATE records SET timestamp=?,end_timestamp=?,note=?,payload_json=?,updated_at=?,sync_dirty=1 WHERE uuid=?", values + [.text(draft.clientUUID.uuidString)])
            }
            try inject(.afterFact); try inject(.beforeCommit)
            guard let result = try recordDetails(draft.clientUUID) else { throw OfflineStoreError.incompatibleDatabase }
            return result
        }
    }

    private func requireCustomGeneration(_ generation: UUID) throws {
        try requireOffline()
        guard try identity().generation == generation else { throw OfflineStoreError.staleContext }
    }
    private static func customName(_ raw: String, icon: Int) throws -> String {
        let name = AndroidText.trimmed(raw)
        guard !name.isEmpty, (0...7).contains(icon) else { throw OfflineStoreError.invalidDraft }
        return name // Catalog source imposes no length limit or case folding.
    }
    private func requireUniqueCustomName(_ name: String, excluding id: UUID) throws {
        guard try db.query("SELECT uuid FROM custom_definitions WHERE deleted_at IS NULL AND name=? COLLATE BINARY AND uuid<>?", [.text(name), .text(id.uuidString)]).isEmpty else { throw OfflineStoreError.duplicateCustomName }
    }
    private func requireCustom(_ id: UUID) throws -> OfflineCustomDefinition {
        guard let definition = try customDefinition(id) else { throw OfflineStoreError.missingCustomDefinition }
        return definition
    }
    private static let customColumns = "SELECT d.uuid,d.name,d.icon_slot,d.updated_at,d.sync_dirty,d.deleted_at,p.sort_order FROM custom_definitions d JOIN custom_preferences p ON p.definition_uuid=d.uuid"
    private static func decodeCustom(_ row: [String?]) throws -> OfflineCustomDefinition {
        guard let id = UUID(uuidString: row[0] ?? ""), let name = row[1], let icon = Int(row[2] ?? ""),
              let revision = Int64(row[3] ?? ""), let order = Int(row[6] ?? "") else { throw OfflineStoreError.incompatibleDatabase }
        return .init(clientUUID: id, name: name, iconSlot: icon, updatedAt: revision, syncDirty: row[4] == "1", deletedAt: row[5].flatMap(Int64.init), sortOrder: order)
    }
}
