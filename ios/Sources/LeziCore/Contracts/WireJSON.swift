import Foundation

/// Errors deliberately contain field names, never received care data or credentials.
public enum WireContractError: Error, Equatable, Sendable {
    case malformedJSON
    case resourceLimit
    case duplicateKey
    case missingField(String)
    case unknownField
    case wrongType(String)
    case invalidValue(String)
    case principalMismatch
}

/// Lossless JSON syntax tree. Numbers retain their original token: `1.0`, `1e0`,
/// `true`, and `"1"` must never become a wire integer by Foundation coercion.
indirect enum WireJSON: Equatable {
    case object([String: WireJSON]), array([WireJSON]), string(String)
    case number(String), bool(Bool), null

    static func parse(_ data: Data) throws -> WireJSON {
        guard data.count <= 8 * 1024 * 1024 else { throw WireContractError.resourceLimit }
        var parser = Parser(bytes: Array(data))
        let result = try parser.value(depth: 0)
        parser.whitespace()
        guard parser.index == parser.bytes.count else { throw WireContractError.malformedJSON }
        return result
    }

    func object(keys: Set<String>) throws -> [String: WireJSON] {
        guard case let .object(value) = self else { throw WireContractError.wrongType("object") }
        guard Set(value.keys).isSubset(of: keys) else { throw WireContractError.unknownField }
        for key in keys.sorted() where value[key] == nil { throw WireContractError.missingField(key) }
        return value
    }

    func string(_ field: String, nonblank: Bool = false) throws -> String {
        guard case let .string(value) = self else { throw WireContractError.wrongType(field) }
        if !nonblank { return value }
        let trimmed = trimAndroidWireString(value)
        guard !trimmed.isEmpty else { throw WireContractError.invalidValue(field) }
        return trimmed
    }

    func integer(_ field: String) throws -> Int64 {
        guard case let .number(token) = self,
              !token.contains("."), !token.contains("e"), !token.contains("E"),
              let result = Int64(token) else { throw WireContractError.wrongType(field) }
        return result
    }

    func boolean(_ field: String) throws -> Bool {
        guard case let .bool(value) = self else { throw WireContractError.wrongType(field) }
        return value
    }

    func strings(_ field: String, max: Int, trim: Bool = true) throws -> Set<String> {
        guard case let .array(values) = self else { throw WireContractError.wrongType(field) }
        guard !values.isEmpty, values.count <= max else { throw WireContractError.invalidValue(field) }
        let strings = try values.map { value -> String in
            let string = try value.string(field, nonblank: trim)
            guard !string.isEmpty, string.utf16.count <= 64 else { throw WireContractError.invalidValue(field) }
            return string
        }
        guard Set(strings).count == strings.count else { throw WireContractError.invalidValue(field) }
        return Set(strings)
    }
}

extension Dictionary where Key == String, Value == WireJSON {
    func field(_ key: String) throws -> WireJSON {
        guard let value = self[key] else { throw WireContractError.missingField(key) }
        return value
    }
}

private struct Parser {
    let bytes: [UInt8]
    var index = 0

    mutating func whitespace() {
        while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
    }

    mutating func take(_ byte: UInt8) -> Bool {
        guard index < bytes.count, bytes[index] == byte else { return false }
        index += 1
        return true
    }

    mutating func value(depth: Int) throws -> WireJSON {
        guard depth <= 64 else { throw WireContractError.resourceLimit }
        whitespace()
        guard index < bytes.count else { throw WireContractError.malformedJSON }
        switch bytes[index] {
        case 123:
            index += 1
            whitespace()
            var result: [String: WireJSON] = [:]
            if take(125) { return .object(result) }
            repeat {
                whitespace()
                let key = try string()
                guard result[key] == nil else { throw WireContractError.duplicateKey }
                whitespace()
                guard take(58) else { throw WireContractError.malformedJSON }
                result[key] = try value(depth: depth + 1)
                whitespace()
                if take(125) { return .object(result) }
                guard take(44) else { throw WireContractError.malformedJSON }
            } while true
        case 91:
            index += 1
            whitespace()
            var result: [WireJSON] = []
            if take(93) { return .array(result) }
            repeat {
                result.append(try value(depth: depth + 1))
                whitespace()
                if take(93) { return .array(result) }
                guard take(44) else { throw WireContractError.malformedJSON }
            } while true
        case 34: return .string(try string())
        case 116: try literal("true"); return .bool(true)
        case 102: try literal("false"); return .bool(false)
        case 110: try literal("null"); return .null
        default: return try number()
        }
    }

    mutating func literal(_ text: String) throws {
        for byte in text.utf8 {
            guard take(byte) else { throw WireContractError.malformedJSON }
        }
    }

    mutating func string() throws -> String {
        let start = index
        guard take(34) else { throw WireContractError.malformedJSON }
        while index < bytes.count {
            let byte = bytes[index]
            index += 1
            if byte == 34 {
                do { return try JSONDecoder().decode(String.self, from: Data(bytes[start..<index])) }
                catch { throw WireContractError.malformedJSON }
            }
            if byte == 92 {
                guard index < bytes.count else { throw WireContractError.malformedJSON }
                index += 1
            }
        }
        throw WireContractError.malformedJSON
    }

    mutating func number() throws -> WireJSON {
        let start = index
        _ = take(45)
        if !take(48) {
            guard index < bytes.count, (49...57).contains(bytes[index]) else { throw WireContractError.malformedJSON }
            digits()
        }
        if take(46) {
            let before = index
            digits()
            guard index > before else { throw WireContractError.malformedJSON }
        }
        if take(101) || take(69) {
            if !take(43) { _ = take(45) }
            let before = index
            digits()
            guard index > before else { throw WireContractError.malformedJSON }
        }
        return .number(String(decoding: bytes[start..<index], as: UTF8.self))
    }

    mutating func digits() {
        while index < bytes.count, (48...57).contains(bytes[index]) { index += 1 }
    }
}

/// Preserve the existing Kotlin-compatible wire normalization contract.
func trimAndroidWireString(_ value: String) -> String {
    AndroidText.trimmed(value)
}
