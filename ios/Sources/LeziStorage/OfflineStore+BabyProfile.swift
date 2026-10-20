import Foundation
import LeziCore

extension OfflineStore {
    static let babyProfileColumns = "b.uuid,b.nickname,b.birthday,p.theme_color,p.sort_order,b.sex,b.birth_weight_grams,b.updated_at,b.sync_dirty"

    /// Read the live original baby, not whichever profile is selected now.
    public func babyProfile(_ id: UUID) throws -> OfflineBaby? {
        guard let row = try db.query("SELECT " + Self.babyProfileColumns + " FROM babies b JOIN baby_preferences p ON b.uuid=p.baby_uuid WHERE b.uuid=? AND b.deleted_at IS NULL", [.text(id.uuidString)]).first else { return nil }
        return try Self.decodeBabyProfile(row)
    }

    /// Explicit-confirm-only profile mutation. A captured selection identifies
    /// the original baby, but later selection changes never retarget this edit.
    public func updateBabyProfile(_ id: UUID, nickname: String, sex: String?, birthday: String,
                                  birthWeightGrams: Int?, context captured: OfflineContext,
                                  expectedUpdatedAt: Int64) throws -> OfflineBaby {
        guard let nickname = try? BabyProfileRules.normalizedNickname(nickname),
              BabyProfileRules.validBirthday(birthday),
              BabyProfileRules.birthWeightValidationError(birthWeightGrams) == nil else {
            throw OfflineStoreError.invalidDraft
        }
        let sex = BabyProfileRules.normalizedSex(sex)
        let now = clock()
        return try writeTransaction {
            try requireOfflineContext(captured)
            guard id == captured.babyClientUUID else { throw OfflineStoreError.staleContext }
            try requireLiveBaby(id)
            guard let existing = try babyProfile(id) else { throw OfflineStoreError.incompatibleDatabase }
            guard existing.updatedAt == expectedUpdatedAt else { throw OfflineStoreError.staleEdit }
            // Same BEGIN IMMEDIATE transaction as the update, including across
            // connections. Equality is SQLite BINARY, like Android String equality.
            guard try db.query("SELECT uuid FROM babies WHERE nickname=? AND uuid<>? AND deleted_at IS NULL",
                               [.text(nickname), .text(id.uuidString)]).isEmpty else {
                throw OfflineStoreError.duplicateNickname
            }
            let revision = try Self.nextRevision(after: existing.updatedAt, now: now)
            try db.execute("UPDATE babies SET nickname=?,sex=?,birthday=?,birth_weight_grams=?,updated_at=?,sync_dirty=1 WHERE uuid=?", [
                .text(nickname), sex.map(SQLiteConnection.Value.text) ?? .null, .text(birthday),
                birthWeightGrams.map { .int(Int64($0)) } ?? .null, .int(revision), .text(id.uuidString)])
            try inject(.afterFact)
            try inject(.beforeCommit)
            guard let saved = try babyProfile(id) else { throw OfflineStoreError.incompatibleDatabase }
            return saved
        }
    }

    static func decodeBabyProfile(_ row: [String?]) throws -> OfflineBaby {
        guard let id = UUID(uuidString: row[0] ?? ""), let name = row[1], let birthday = row[2],
              let theme = Int64(row[3] ?? ""), let order = Int64(row[4] ?? ""),
              let updatedAt = Int64(row[7] ?? ""),
              row[8] == "0" || row[8] == "1" else { throw OfflineStoreError.incompatibleDatabase }
        let weight: Int?
        if let raw = row[6] {
            guard let parsed = Int(raw) else { throw OfflineStoreError.incompatibleDatabase }
            weight = parsed
        } else {
            weight = nil
        }
        return OfflineBaby(clientUUID: id, nickname: name, birthday: birthday, themeColor: theme, sortOrder: order,
                           sex: row[5], birthWeightGrams: weight, updatedAt: updatedAt, syncDirty: row[8] == "1")
    }
}
