import Foundation
import LeziCore

/// Native schema 2 (preserves and migrates native schema 1). Not Android Room schema 29; not a family-sync repository.
public struct OfflineContext: Equatable, Sendable {
    public let generation: UUID
    /// UI selection state only; never authorization to retarget an open editor.
    public let selectionRevision: Int64
    public let babyClientUUID: UUID
}
public struct OfflineIdentity: Equatable, Sendable {
    public let localActorUUID: UUID
    public let generation: UUID
}
public struct OfflineBaby: Equatable, Sendable {
    public let clientUUID: UUID
    public let nickname: String
    public let birthday: String
    public let themeColor: Int64
    public let sortOrder: Int64
}
public struct OfflinePeeRecord: Equatable, Sendable {
    public let clientUUID: UUID
    public let babyClientUUID: UUID
    public let timestampMillis: Int64
    public let note: String?
    public let amount: Int
    public let updatedAt: Int64
    public let syncDirty: Bool
    public let deletedAt: Int64?
}

/// Opaque keyset position tied to baby, durable generation, and all/pee filter.
public struct OfflineRecordCursor: Equatable, Sendable {
    fileprivate let baby: UUID
    fileprivate let generation: UUID
    fileprivate let peeOnly: Bool
    fileprivate let timestamp: Int64
    fileprivate let recordUUID: UUID
}
public struct OfflineRecordPage: Equatable, Sendable {
    public let records: [OfflinePeeRecord]
    public let nextCursor: OfflineRecordCursor?
}

/// Explicit-confirm-only, no-media ordinary-record storage. Reads are snapshots, not streams.
/// The durable dirty bit is a local fact marker, NOT a frozen mutation/outbox.
/// Joining a family must first block this API; there is no ACL bypass fallback.
public actor OfflineStore {
    private let db: SQLiteConnection
    private let clock: @Sendable () -> Int64
    // Internal deterministic rollback seams; not a public persistence option.
    enum FailurePoint: Sendable { case afterFact, beforeCommit }
    private var nextFailure: FailurePoint?
    func failNextConfirmation(at point: FailurePoint) { nextFailure = point }

    public init(path: String, clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) throws {
        self.clock = clock
        db = try SQLiteConnection(path: path)
        try OfflineSchema.prepare(db)
    }
    public func identity() throws -> OfflineIdentity {
        let row = try metadata()
        guard let actor = UUID(uuidString: row[0] ?? ""), let generation = UUID(uuidString: row[1] ?? "") else { throw OfflineStoreError.incompatibleDatabase }
        return OfflineIdentity(localActorUUID: actor, generation: generation)
    }
    /// No fabricated family/session is created. Block persists across restart and
    /// cannot be undone through this offline-only API.
    public func blockOfflineWrites() throws {
        try db.transaction { try db.execute("UPDATE local_identity SET mode='blocked' WHERE id=1") }
    }
    public func createBaby(clientUUID: UUID = UUID(), nickname: String, birthday: String) throws -> OfflineBaby {
        let nickname = try Self.normalizedNickname(nickname)
        guard Self.validDay(birthday) else { throw OfflineStoreError.invalidDraft }
        return try db.transaction {
            try requireOffline()
            guard try db.query("SELECT uuid FROM babies WHERE uuid=?", [.text(clientUUID.uuidString)]).isEmpty else { throw OfflineStoreError.identityConflict }
            guard try db.query("SELECT uuid FROM babies WHERE nickname=? AND deleted_at IS NULL", [.text(nickname)]).isEmpty else { throw OfflineStoreError.duplicateNickname }
            try db.execute("INSERT INTO babies VALUES (?, ?, ?, ?, 1, NULL)", [.text(clientUUID.uuidString), .text(nickname), .text(birthday), .int(clock())])
            try db.execute("INSERT INTO baby_preferences VALUES (?, 4278221742, 0)", [.text(clientUUID.uuidString)])
            return OfflineBaby(clientUUID: clientUUID, nickname: nickname, birthday: birthday, themeColor: 4278221742, sortOrder: 0)
        }
    }
    public func babies() throws -> [OfflineBaby] {
        try db.query("SELECT b.uuid,b.nickname,b.birthday,p.theme_color,p.sort_order FROM babies b JOIN baby_preferences p ON b.uuid=p.baby_uuid WHERE b.deleted_at IS NULL ORDER BY p.sort_order,b.uuid").map { row in
            guard let id = UUID(uuidString: row[0] ?? ""), let name = row[1], let birthday = row[2], let theme = Int64(row[3] ?? ""), let order = Int64(row[4] ?? "") else { throw OfflineStoreError.incompatibleDatabase }
            return OfflineBaby(clientUUID: id, nickname: name, birthday: birthday, themeColor: theme, sortOrder: order)
        }
    }
    public func setPreferences(baby: UUID, themeColor: Int64, sortOrder: Int64) throws {
        guard (0...0xFFFFFFFF).contains(themeColor) else { throw OfflineStoreError.invalidDraft }
        try db.transaction {
            try requireOffline(); try requireLiveBaby(baby)
            try db.execute("UPDATE baby_preferences SET theme_color=?,sort_order=? WHERE baby_uuid=?", [.int(themeColor), .int(sortOrder), .text(baby.uuidString)])
        }
    }
    public func selectBaby(_ baby: UUID) throws -> OfflineContext {
        try db.transaction {
            try requireOffline(); try requireLiveBaby(baby)
            let row = try metadata()
            guard let revision = Int64(row[3] ?? ""), revision < Int64.max else { throw OfflineStoreError.revisionOverflow }
            try db.execute("UPDATE local_identity SET selected_baby=?,selection_revision=? WHERE id=1", [.text(baby.uuidString), .int(revision + 1)])
            return try context()
        }
    }
    public func context() throws -> OfflineContext {
        let row = try metadata()
        guard row[2] == "offline" else { throw OfflineStoreError.offlineOnly }
        guard let generation = UUID(uuidString: row[1] ?? ""), let revision = Int64(row[3] ?? ""), let baby = UUID(uuidString: row[4] ?? "") else { throw OfflineStoreError.staleContext }
        try requireLiveBaby(baby)
        return OfflineContext(generation: generation, selectionRevision: revision, babyClientUUID: baby)
    }
    /// nil expectedUpdatedAt means create/replay. An edit requires the captured
    /// row revision. UUID, original baby, and exact type cannot be changed.
    /// This writes past facts only; future entries need the separate plan flow.
    public func confirmRecord(_ draft: RecordConfirmationDraft, context captured: OfflineContext, expectedUpdatedAt: Int64? = nil) throws -> OfflineRecord {
        let now = clock()
        guard OfflineStorageScope.supportedRecordTypes.contains(draft.payload.type),
              draft.item == .builtIn(draft.payload.type), draft.photoClientUUIDs.isEmpty,
              draft.validationErrors(nowMillis: now).isEmpty else { throw OfflineStoreError.invalidDraft }
        let document = try RecordPayloadDocument(payload: draft.payload)
        let payloadJSON = try RecordPayloadCodec.encode(document)
        return try db.transaction {
            try requireOfflineContext(captured)
            guard draft.babyClientUUID == captured.babyClientUUID else { throw OfflineStoreError.staleContext }
            try requireLiveBaby(captured.babyClientUUID)
            let existing = try recordDetails(draft.clientUUID)
            if let existing {
                guard existing.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
                guard existing.babyClientUUID == draft.babyClientUUID,
                      existing.rawType == draft.payload.type.rawValue else { throw OfflineStoreError.identityConflict }
                if expectedUpdatedAt == nil { return existing } // Replay never overwrites a fact.
                guard existing.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
                guard !existing.isOpaque else { throw OfflineStoreError.opaqueRecord }
            } else if expectedUpdatedAt != nil { throw OfflineStoreError.staleEdit }
            let revision = try existing.map { try Self.nextRevision(after: $0.updatedAt, now: now) } ?? now
            if existing == nil {
                try db.execute("INSERT INTO records (uuid,baby_uuid,type,timestamp,end_timestamp,note,payload_json,payload_schema_version,updated_at,sync_dirty) VALUES (?,?,?,?,?,?,?,?,?,0)", [
                    .text(draft.clientUUID.uuidString), .text(draft.babyClientUUID.uuidString), .text(draft.payload.type.rawValue),
                    .int(draft.timestampMillis), draft.endTimestampMillis.map(SQLiteConnection.Value.int) ?? .null,
                    draft.note.map(SQLiteConnection.Value.text) ?? .null, .text(payloadJSON), .int(Int64(document.schemaVersion)), .int(revision)])
            } else {
                try db.execute("UPDATE records SET timestamp=?,end_timestamp=?,note=?,payload_json=?,payload_schema_version=?,updated_at=? WHERE uuid=?", [
                    .int(draft.timestampMillis), draft.endTimestampMillis.map(SQLiteConnection.Value.int) ?? .null,
                    draft.note.map(SQLiteConnection.Value.text) ?? .null, .text(payloadJSON), .int(Int64(document.schemaVersion)), .int(revision), .text(draft.clientUUID.uuidString)])
            }
            try inject(.afterFact)
            try db.execute("UPDATE records SET sync_dirty=1 WHERE uuid=?", [.text(draft.clientUUID.uuidString)])
            try inject(.beforeCommit)
            guard let saved = try recordDetails(draft.clientUUID) else { throw OfflineStoreError.incompatibleDatabase }
            return saved
        }
    }
    /// Compatibility adapter for the original pee-only feature. One writer owns
    /// all identities/transactions; an existing UUID of another type is a conflict.
    public func confirmPee(_ draft: RecordConfirmationDraft, context captured: OfflineContext, expectedUpdatedAt: Int64? = nil) throws -> OfflinePeeRecord {
        guard draft.item == .builtIn(.pee), draft.endTimestampMillis == nil else { throw OfflineStoreError.invalidDraft }
        return try Self.peeRecord(confirmRecord(draft, context: captured, expectedUpdatedAt: expectedUpdatedAt))
    }
    /// Metadata-only soft deletion preserves even opaque JSON and unknown types.
    /// Like Android deleteRecord, it does not require payload decoding or a live
    /// baby. The original identity, offline mode and generation still gate writes.
    @discardableResult
    public func deleteRecord(_ id: UUID, context captured: OfflineContext, expectedUpdatedAt: Int64? = nil) throws -> Bool {
        let now = clock()
        return try db.transaction {
            try requireOfflineContext(captured)
            guard let row = try db.query("SELECT baby_uuid,updated_at,deleted_at FROM records WHERE uuid=?", [.text(id.uuidString)]).first else { return false }
            guard row[0] == captured.babyClientUUID.uuidString else { throw OfflineStoreError.identityConflict }
            if row[2] != nil { return false }
            guard let previous = Int64(row[1] ?? "") else { throw OfflineStoreError.incompatibleDatabase }
            if let expectedUpdatedAt, previous != expectedUpdatedAt { throw OfflineStoreError.staleEdit }
            let revision = try Self.nextRevision(after: previous, now: now)
            try db.execute("UPDATE records SET deleted_at=?,updated_at=? WHERE uuid=?", [.int(revision), .int(revision), .text(id.uuidString)])
            try inject(.afterFact)
            try db.execute("UPDATE records SET sync_dirty=1 WHERE uuid=?", [.text(id.uuidString)])
            try inject(.beforeCommit)
            return true
        }
    }
    /// Generic bounded timeline, including unknown type/schema documents.
    public func recordPage(baby: UUID, before: OfflineRecordCursor? = nil, limit: Int = 100) throws -> OfflineRecordsPage {
        try readPage(baby: baby, before: before, limit: limit, peeOnly: false)
    }
    /// Compatibility pee-only timeline; its cursor cannot be used in the all-type timeline.
    public func records(baby: UUID, before: OfflineRecordCursor? = nil, limit: Int = 100) throws -> OfflineRecordPage {
        let page = try readPage(baby: baby, before: before, limit: limit, peeOnly: true)
        return OfflineRecordPage(records: try page.records.map(Self.peeRecord), nextCursor: page.nextCursor)
    }
    /// Each page is a read-only snapshot, not a frozen history export. Selection
    /// is irrelevant; later pages can reflect later edits to the stored facts.
    private func readPage(baby: UUID, before: OfflineRecordCursor?, limit: Int, peeOnly: Bool) throws -> OfflineRecordsPage {
        guard (1...200).contains(limit) else { throw OfflineStoreError.invalidPageLimit }
        return try db.transaction(begin: "BEGIN") {
            let generation = try identity().generation
            if let before {
                guard before.baby == baby, before.generation == generation,
                      before.peeOnly == peeOnly else { throw OfflineStoreError.staleContext }
            }
            try requireLiveBaby(baby)
            var values: [SQLiteConnection.Value] = [.text(baby.uuidString)]
            if let before { values += [.int(before.timestamp), .text(before.recordUUID.uuidString)] }
            values.append(.int(Int64(limit + 1)))
            let fetched = try db.query(Self.timelinePageSQL(hasCursor: before != nil, peeOnly: peeOnly), values).map(Self.decodeRecord)
            let page = Array(fetched.prefix(limit))
            let cursor = fetched.count > limit ? page.last.map {
                OfflineRecordCursor(baby: baby, generation: generation, peeOnly: peeOnly, timestamp: $0.timestampMillis, recordUUID: $0.clientUUID)
            } : nil
            return OfflineRecordsPage(records: page, nextCursor: cursor)
        }
    }
    private static let recordColumns = "uuid,baby_uuid,type,timestamp,end_timestamp,note,payload_json,payload_schema_version,updated_at,sync_dirty,deleted_at"
    // Shared with EXPLAIN tests so they exercise the shipped SQL.
    static func timelinePageSQL(hasCursor: Bool, peeOnly: Bool = true) -> String {
        "SELECT " + recordColumns + " FROM records WHERE baby_uuid=? AND deleted_at IS NULL" +
        (peeOnly ? " AND type='pee'" : "") + (hasCursor ? " AND (timestamp,uuid) < (?,?)" : "") +
        " ORDER BY timestamp DESC,uuid DESC LIMIT ?"
    }
    public func recordDetails(_ id: UUID) throws -> OfflineRecord? {
        guard let row = try db.query("SELECT " + Self.recordColumns + " FROM records WHERE uuid=?", [.text(id.uuidString)]).first else { return nil }
        return try Self.decodeRecord(row)
    }
    public func record(_ id: UUID) throws -> OfflinePeeRecord? {
        guard let record = try recordDetails(id), record.type == .pee else { return nil }
        return try Self.peeRecord(record)
    }
    private static func decodeRecord(_ row: [String?]) throws -> OfflineRecord {
        guard let uuid = UUID(uuidString: row[0] ?? ""), let baby = UUID(uuidString: row[1] ?? ""),
              let rawType = row[2], let time = Int64(row[3] ?? ""), let json = row[6],
              let schema = Int(row[7] ?? ""), let updated = Int64(row[8] ?? "") else { throw OfflineStoreError.incompatibleDatabase }
        let document = RecordType(rawValue: rawType).map { RecordPayloadCodec.decode(type: $0, payloadJSON: json, schemaVersion: schema) }
        return OfflineRecord(clientUUID: uuid, babyClientUUID: baby, rawType: rawType, timestampMillis: time,
            endTimestampMillis: row[4].flatMap(Int64.init), note: row[5], payloadJSON: json,
            payloadSchemaVersion: schema, payloadDocument: document, updatedAt: updated,
            syncDirty: row[9] == "1", deletedAt: row[10].flatMap(Int64.init))
    }
    private static func peeRecord(_ record: OfflineRecord) throws -> OfflinePeeRecord {
        guard record.type == .pee, case let .pee(amount)? = record.payloadDocument?.payload else { throw OfflineStoreError.opaqueRecord }
        return OfflinePeeRecord(clientUUID: record.clientUUID, babyClientUUID: record.babyClientUUID,
            timestampMillis: record.timestampMillis, note: record.note, amount: amount,
            updatedAt: record.updatedAt, syncDirty: record.syncDirty, deletedAt: record.deletedAt)
    }
    private static func nextRevision(after previous: Int64, now: Int64) throws -> Int64 {
        guard previous < Int64.max else { throw OfflineStoreError.revisionOverflow }
        return max(now, previous + 1)
    }
    private func requireOfflineContext(_ captured: OfflineContext) throws {
        try requireOffline()
        guard try identity().generation == captured.generation else { throw OfflineStoreError.staleContext }
    }
    private func metadata() throws -> [String?] {
        guard let row = try db.query("SELECT actor_uuid,generation,mode,selection_revision,selected_baby FROM local_identity WHERE id=1").first else { throw OfflineStoreError.incompatibleDatabase }
        return row
    }
    private func requireOffline() throws { guard try metadata()[2] == "offline" else { throw OfflineStoreError.offlineOnly } }
    private func requireLiveBaby(_ id: UUID) throws {
        guard !(try db.query("SELECT uuid FROM babies WHERE uuid=? AND deleted_at IS NULL", [.text(id.uuidString)])).isEmpty else { throw OfflineStoreError.missingBaby }
    }
    private func inject(_ point: FailurePoint) throws {
        if nextFailure == point { nextFailure = nil; throw OfflineStoreError.injectedFailure }
    }
    private static func normalizedNickname(_ raw: String) throws -> String {
        // Kotlin Char.isWhitespace = Java whitespace OR space separator.
        // NEL U+0085 is intentionally not whitespace; U+001C...U+001F is.
        func whitespace(_ scalar: Unicode.Scalar) -> Bool {
            (9...13).contains(scalar.value) || (28...32).contains(scalar.value) ||
            [.spaceSeparator, .lineSeparator, .paragraphSeparator].contains(scalar.properties.generalCategory)
        }
        let trimmed = raw.unicodeScalars.drop(while: whitespace).reversed().drop(while: whitespace).reversed()
        let nickname = trimmed.isEmpty ? "年年" : String(String.UnicodeScalarView(trimmed))
        guard nickname.unicodeScalars.count <= 20 else { throw OfflineStoreError.invalidDraft }
        return nickname
    }
    private static func validDay(_ text: String) -> Bool {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateFormat = "yyyy-MM-dd"; formatter.isLenient = false
        guard let date = formatter.date(from: text) else { return false }
        return formatter.string(from: date) == text
    }
}
