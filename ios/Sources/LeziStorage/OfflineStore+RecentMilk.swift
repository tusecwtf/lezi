import Foundation
import LeziCore

extension OfflineStore {
    /// Recent valid distinct amounts for the captured baby and exact milk type.
    /// Fixed-size indexed pages bound memory, not history: duplicate/opaque pages
    /// must not hide older choices. Worst case reads all matching live records.
    public func recentMilkAmounts(baby: UUID, type: RecordType, limit: Int = 3) throws -> [Int] {
        guard [.formula, .pumpedFeed, .pumpExpress].contains(type) else { throw OfflineStoreError.invalidDraft }
        guard (0...3).contains(limit) else { throw OfflineStoreError.invalidPageLimit }
        return try db.transaction(begin: "BEGIN") {
            try requireLiveBaby(baby)
            return try readRecentMilkAmounts(baby: baby, type: type, limit: limit)
        }
    }

    /// All three choices share one read snapshot for a new editor draft.
    public func recentMilkAmounts(baby: UUID) throws -> [RecordType: [Int]] {
        try db.transaction(begin: "BEGIN") {
            try requireLiveBaby(baby)
            var result = [RecordType: [Int]]()
            for type: RecordType in [.formula, .pumpedFeed, .pumpExpress] {
                result[type] = try readRecentMilkAmounts(baby: baby, type: type, limit: 3)
            }
            return result
        }
    }

    private func readRecentMilkAmounts(baby: UUID, type: RecordType, limit: Int) throws -> [Int] {
        guard limit > 0 else { return [] }
        var result = [Int]()
        var cursor: (timestamp: Int64, uuid: UUID)?
        let pageSize = 64
        while result.count < limit {
            var values: [SQLiteConnection.Value] = [.text(baby.uuidString), .text(type.rawValue)]
            if let cursor { values += [.int(cursor.timestamp), .text(cursor.uuid.uuidString)] }
            values.append(.int(Int64(pageSize)))
            let batch = try db.query(Self.recentMilkPageSQL(hasCursor: cursor != nil), values).map(Self.decodeRecord)
            for record in batch {
                guard case let .milk(_, amount, _, _) = record.payloadDocument?.payload,
                      (1...999).contains(amount), !result.contains(amount) else { continue }
                result.append(amount)
                if result.count == limit { return result }
            }
            guard batch.count == pageSize, let last = batch.last else { return result }
            cursor = (last.timestampMillis, last.clientUUID)
        }
        return result
    }

    static func recentMilkPageSQL(hasCursor: Bool) -> String {
        "SELECT " + recordColumns + " FROM records WHERE baby_uuid=? AND type=? AND deleted_at IS NULL" +
        (hasCursor ? " AND (timestamp,uuid) < (?,?)" : "") +
        " ORDER BY timestamp DESC,uuid DESC LIMIT ?"
    }
}
