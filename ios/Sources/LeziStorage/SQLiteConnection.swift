import CSQLite
import Foundation

public enum OfflineStoreError: Error, Equatable, Sendable {
    case sqlite(Int32, String)
    case incompatibleDatabase
    case unsupportedSleepHistory
    case sleepStateChanged
    case missingWake
    case wakePermission
    case offlineOnly
    case staleContext
    case duplicateNickname
    case missingBaby
    case invalidPageLimit
    case invalidDraft
    case identityConflict
    case opaqueRecord
    case deletedRecord
    case staleEdit
    case revisionOverflow
    case injectedFailure
}

// Exclusively owned by OfflineStore. No reference escapes the actor, and no
// transaction body suspends. Sendability permits actor initialization/deinit.
final class SQLiteConnection: @unchecked Sendable {
    private var handle: OpaquePointer?
    enum Value { case text(String), int(Int64), null }
    init(path: String) throws {
        let result = sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        guard result == SQLITE_OK else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "open failed"
            if let handle { sqlite3_close(handle) }; handle = nil
            throw OfflineStoreError.sqlite(result, message)
        }
        sqlite3_busy_timeout(handle, 3_000)
        try execute("PRAGMA foreign_keys=ON")
        try execute("PRAGMA synchronous=FULL")
    }
    deinit { if let handle { sqlite3_close_v2(handle) } }
    func execute(_ sql: String, _ values: [Value] = []) throws {
        _ = try query(sql, values)
    }
    func query(_ sql: String, _ values: [Value] = []) throws -> [[String?]] {
        var statement: OpaquePointer?
        try check(sqlite3_prepare_v2(handle, sql, -1, &statement, nil))
        defer { sqlite3_finalize(statement) }
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            switch value {
            case let .int(number): try check(sqlite3_bind_int64(statement, index, number))
            case .null: try check(sqlite3_bind_null(statement, index))
            case let .text(text):
                try text.withCString { pointer in
                    try check(sqlite3_bind_text(statement, index, pointer, Int32(text.utf8.count), unsafeBitCast(-1, to: sqlite3_destructor_type.self)))
                }
            }
        }
        var rows = [[String?]]()
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { return rows }
            guard result == SQLITE_ROW else { try check(result); return rows }
            rows.append((0..<sqlite3_column_count(statement)).map { index in
                guard sqlite3_column_type(statement, index) != SQLITE_NULL,
                      let pointer = sqlite3_column_text(statement, index) else { return nil }
                return String(decoding: UnsafeBufferPointer(start: pointer, count: Int(sqlite3_column_bytes(statement, index))), as: UTF8.self)
            })
        }
    }
    private func check(_ code: Int32) throws {
        guard code == SQLITE_OK else { throw OfflineStoreError.sqlite(code, String(cString: sqlite3_errmsg(handle))) }
    }
    func transaction<T>(begin: String = "BEGIN IMMEDIATE", _ body: () throws -> T) throws -> T {
        try execute(begin)
        do {
            let value = try body()
            try execute("COMMIT")
            return value // Never report success before COMMIT succeeds.
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }
}
