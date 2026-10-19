import Foundation
import LeziCore

/// Native schema 1. Not Android Room schema 29; not a family-sync repository.
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

/// Opaque keyset position tied to one baby and one durable store generation.
public struct OfflineRecordCursor: Equatable, Sendable {
    fileprivate let baby: UUID
    fileprivate let generation: UUID
    fileprivate let timestamp: Int64
    fileprivate let recordUUID: UUID
}
public struct OfflineRecordPage: Equatable, Sendable {
    public let records: [OfflinePeeRecord]
    public let nextCursor: OfflineRecordCursor?
}

/// Explicit-confirm-only, no-media pee slice. Reads are snapshots, not streams.
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
        try db.transaction {
            let version = try db.query("PRAGMA user_version").first?.first ?? nil
            let application = try db.query("PRAGMA application_id").first?.first ?? nil
            if version == "0" {
                guard application == "0", try db.query("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").isEmpty else {
                    throw OfflineStoreError.incompatibleDatabase
                }
                try db.execute("CREATE TABLE local_identity (id INTEGER PRIMARY KEY CHECK(id=1), actor_uuid TEXT NOT NULL, generation TEXT NOT NULL, mode TEXT NOT NULL CHECK(mode IN ('offline','blocked')), selection_revision INTEGER NOT NULL, selected_baby TEXT)")
                try db.execute("CREATE TABLE babies (uuid TEXT PRIMARY KEY, nickname TEXT NOT NULL, birthday TEXT NOT NULL, updated_at INTEGER NOT NULL, sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN (0,1)), deleted_at INTEGER)")
                try db.execute("CREATE TABLE baby_preferences (baby_uuid TEXT PRIMARY KEY REFERENCES babies(uuid), theme_color INTEGER NOT NULL, sort_order INTEGER NOT NULL)")
                try db.execute("CREATE TABLE pee_records (uuid TEXT PRIMARY KEY, baby_uuid TEXT NOT NULL REFERENCES babies(uuid), timestamp INTEGER NOT NULL, note TEXT, amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 3), updated_at INTEGER NOT NULL, sync_dirty INTEGER NOT NULL CHECK(sync_dirty IN (0,1)), deleted_at INTEGER)")
                try db.execute("CREATE INDEX pee_timeline ON pee_records(baby_uuid,timestamp DESC,uuid DESC) WHERE deleted_at IS NULL")
                try db.execute("INSERT INTO local_identity VALUES (1, ?, ?, 'offline', 0, NULL)", [.text(UUID().uuidString), .text(UUID().uuidString)])
                try db.execute("PRAGMA application_id=1279613513") // LEZI, distinct native file.
                try db.execute("PRAGMA user_version=1")
            } else {
                guard version == "1", application == "1279613513" else { throw OfflineStoreError.incompatibleDatabase }
            }
        }
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
    /// nil expectedUpdatedAt means create/replay. Edit requires the captured row
    /// revision; UUID, baby and type cannot be changed by an editor.
    public func confirmPee(_ draft: RecordConfirmationDraft, context captured: OfflineContext, expectedUpdatedAt: Int64? = nil) throws -> OfflinePeeRecord {
        let now = clock()
        guard draft.validationErrors(nowMillis: now).isEmpty,
              draft.item == .builtIn(.pee), case let .pee(amount) = draft.payload,
              draft.endTimestampMillis == nil, draft.photoClientUUIDs.isEmpty else { throw OfflineStoreError.invalidDraft }
        return try db.transaction {
            try requireOffline()
            guard try identity().generation == captured.generation, draft.babyClientUUID == captured.babyClientUUID else { throw OfflineStoreError.staleContext }
            try requireLiveBaby(captured.babyClientUUID)
            let existing = try record(draft.clientUUID)
            if let existing {
                guard existing.deletedAt == nil else { throw OfflineStoreError.deletedRecord }
                guard existing.babyClientUUID == draft.babyClientUUID else { throw OfflineStoreError.identityConflict }
                if expectedUpdatedAt == nil { return existing } // Create replay never overwrites.
                guard existing.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
            } else if expectedUpdatedAt != nil { throw OfflineStoreError.staleEdit }
            let revision: Int64
            if let existing {
                guard existing.updatedAt < Int64.max else { throw OfflineStoreError.revisionOverflow }
                revision = max(now, existing.updatedAt + 1)
            } else { revision = now }
            if existing == nil {
                try db.execute("INSERT INTO pee_records VALUES (?,?,?,?,?,?,0,NULL)", [.text(draft.clientUUID.uuidString), .text(draft.babyClientUUID.uuidString), .int(draft.timestampMillis), draft.note.map(SQLiteConnection.Value.text) ?? .null, .int(Int64(amount)), .int(revision)])
            } else {
                try db.execute("UPDATE pee_records SET timestamp=?,note=?,amount=?,updated_at=? WHERE uuid=?", [.int(draft.timestampMillis), draft.note.map(SQLiteConnection.Value.text) ?? .null, .int(Int64(amount)), .int(revision), .text(draft.clientUUID.uuidString)])
            }
            try inject(.afterFact)
            try db.execute("UPDATE pee_records SET sync_dirty=1 WHERE uuid=?", [.text(draft.clientUUID.uuidString)])
            try inject(.beforeCommit)
            guard let saved = try record(draft.clientUUID) else { throw OfflineStoreError.incompatibleDatabase }
            return saved
        }
    }
    /// Bounded newest-first keyset page. Each page is a read-only SQLite snapshot;
    /// later pages reflect later edits, not an immutable history export. UI selection
    /// is irrelevant: an editor/list may keep reading its originally captured baby.
    public func records(baby: UUID, before: OfflineRecordCursor? = nil, limit: Int = 100) throws -> OfflineRecordPage {
        guard (1...200).contains(limit) else { throw OfflineStoreError.invalidPageLimit }
        return try db.transaction(begin: "BEGIN") {
            let generation = try identity().generation
            if let before {
                guard before.baby == baby, before.generation == generation else { throw OfflineStoreError.staleContext }
            }
            try requireLiveBaby(baby)
            let sql = Self.timelinePageSQL(hasCursor: before != nil)
            var values: [SQLiteConnection.Value] = [.text(baby.uuidString)]
            if let before {
                values += [.int(before.timestamp), .text(before.recordUUID.uuidString)]
            }
            values.append(.int(Int64(limit + 1)))
            let fetched = try db.query(sql, values).map(Self.decodeRecord)
            let page = Array(fetched.prefix(limit))
            let cursor: OfflineRecordCursor?
            if fetched.count > limit, let last = page.last {
                cursor = OfflineRecordCursor(baby: baby, generation: generation, timestamp: last.timestampMillis, recordUUID: last.clientUUID)
            } else { cursor = nil }
            return OfflineRecordPage(records: page, nextCursor: cursor)
        }
    }
    // Shared with EXPLAIN QUERY PLAN tests so the tested plan is the shipped SQL.
    static func timelinePageSQL(hasCursor: Bool) -> String {
        "SELECT uuid,baby_uuid,timestamp,note,amount,updated_at,sync_dirty,deleted_at FROM pee_records WHERE baby_uuid=? AND deleted_at IS NULL" +
        (hasCursor ? " AND (timestamp,uuid) < (?,?)" : "") +
        " ORDER BY timestamp DESC,uuid DESC LIMIT ?"
    }
    public func record(_ id: UUID) throws -> OfflinePeeRecord? {
        guard let row = try db.query("SELECT uuid,baby_uuid,timestamp,note,amount,updated_at,sync_dirty,deleted_at FROM pee_records WHERE uuid=?", [.text(id.uuidString)]).first else { return nil }
        return try Self.decodeRecord(row)
    }
    private static func decodeRecord(_ row: [String?]) throws -> OfflinePeeRecord {
        guard let uuid = UUID(uuidString: row[0] ?? ""), let baby = UUID(uuidString: row[1] ?? ""), let time = Int64(row[2] ?? ""), let amount = Int(row[4] ?? ""), let updated = Int64(row[5] ?? "") else { throw OfflineStoreError.incompatibleDatabase }
        return OfflinePeeRecord(clientUUID: uuid, babyClientUUID: baby, timestampMillis: time, note: row[3], amount: amount, updatedAt: updated, syncDirty: row[6] == "1", deletedAt: row[7].flatMap(Int64.init))
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
