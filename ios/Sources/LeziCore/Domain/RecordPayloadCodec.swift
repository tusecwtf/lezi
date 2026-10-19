import Foundation

/// Android RecordPayload.kt storage schema, not a causal-sync root/wire codec.
/// Local numeric/boolean primitives may be quoted; portable wire validation must
/// remain strict and must exclude custom_item_id. No persistence is performed.
/// Unknown documents preserve input text exactly. Known JSON is rebuilt, not
/// byte-canonical: Foundation/Swift floating formatting can differ from JVM.
/// Defensive limits: nesting beyond 64 and escaped unpaired UTF-16 surrogates
/// and backslash followed by a literal control remain opaque, even where the
/// JVM parser can represent them (including its nonstandard escape mapping).
public struct PayloadKeySchema: Equatable, Sendable {
    public let required: Set<String>
    public let optional: Set<String>
    public let localOnly: Set<String>
    public var allowed: Set<String> { required.union(optional).union(localOnly) }
    public var portable: Set<String> { required.union(optional) }

    init(required: Set<String> = [], optional: Set<String> = [], localOnly: Set<String> = []) {
        self.required = required
        self.optional = optional
        self.localOnly = localOnly
    }
}

public enum RecordPayloadCodec {
    public static let currentSchemaVersion = 2

    public static func payloadKeySchema(_ type: RecordType) -> PayloadKeySchema {
        let stool: Set<String> = ["stool_amount", "stool_consistency", "stool_color"]
        switch type {
        case .nursing: return .init(required: ["left_min", "right_min", "order", "record_mode"], optional: ["amount_ml"])
        case .formula: return .init(required: ["amount_ml"], optional: ["prepared_ml", "duration_min"])
        case .pumpedFeed, .pumpExpress: return .init(required: ["amount_ml"])
        case .pee: return .init(optional: ["pee_amount"])
        case .poop: return .init(optional: stool)
        case .bothDiaper: return .init(optional: stool.union(["pee_amount"]))
        case .sleep: return .init(required: ["anomaly_flag"], optional: ["is_nap"])
        case .temperature: return .init(required: ["celsius"])
        case .diary: return .init(required: ["body"])
        case .bath, .walk: return .init()
        case .cough, .rash, .vomit, .injury: return .init(required: ["severity"], optional: ["description"])
        case .medicine: return .init(required: ["name"], optional: ["dose"])
        case .hospital: return .init(required: ["reason"], optional: ["advice"])
        case .height, .weight, .head, .chest, .footSize: return .init(required: ["value", "unit"])
        case .babyFood, .snack, .drink: return .init(required: ["content"], optional: ["amount"])
        case .vaccine: return .init(required: ["name"], optional: ["batch"])
        case .custom: return .init(required: ["title"], optional: ["detail", "icon_slot"], localOnly: ["custom_item_id"])
        }
    }
}

/// Opaque documents never expose an editable typed payload. Unlike Android's
/// sealed UnknownPayload, this wrapper keeps unknown state outside the existing
/// in-process enum. The row ID is local metadata, not a portable custom identity.
public struct RecordPayloadDocument: Equatable, Sendable {
    public let type: RecordType
    public let payload: RecordPayload?
    public let schemaVersion: Int
    public let rawJSON: String?
    public let unknownReason: String?
    public let localCustomItemID: Int64?
    public var isUnknown: Bool { payload == nil }

    public init(payload: RecordPayload, localCustomItemID: Int64? = nil) throws {
        guard localCustomItemID == nil || (payload.type == .custom && localCustomItemID! > 0) else {
            throw RecordPayloadCodecError.invalidLocalCustomItemID
        }
        self.type = payload.type
        self.payload = payload
        self.schemaVersion = RecordPayloadCodec.currentSchemaVersion
        self.rawJSON = nil
        self.unknownReason = nil
        self.localCustomItemID = localCustomItemID
    }

    fileprivate init(type: RecordType, payload: RecordPayload?, schemaVersion: Int,
                     rawJSON: String, reason: String? = nil, localCustomItemID: Int64? = nil) {
        self.type = type
        self.payload = payload
        self.schemaVersion = schemaVersion
        self.rawJSON = rawJSON
        self.unknownReason = reason
        self.localCustomItemID = localCustomItemID
    }
}

public enum RecordPayloadCodecError: Error, Equatable {
    case invalidLocalCustomItemID
    case invalidTypedPayload
}

extension RecordPayloadCodec {
    public static func decode(type: RecordType, payloadJSON: String, schemaVersion: Int) -> RecordPayloadDocument {
        func unknown(_ reason: String) -> RecordPayloadDocument {
            .init(type: type, payload: nil, schemaVersion: schemaVersion, rawJSON: payloadJSON, reason: reason)
        }
        guard schemaVersion == currentSchemaVersion else {
            return unknown(schemaVersion > currentSchemaVersion ? "future schema" : "unsupported schema")
        }
        var parser = LocalPayloadJSONParser(bytes: Array(payloadJSON.utf8))
        guard let parsed = try? parser.parse(), case let .object(object) = parsed else { return unknown("malformed JSON") }
        let schema = payloadKeySchema(type)
        guard Set(object.keys).isSubset(of: schema.allowed) else { return unknown("unknown fields") }
        guard schema.required.isSubset(of: Set(object.keys)) else { return unknown("missing required keys") }
        do {
            let fields = LocalPayloadFields(values: object)
            let payload = try decodeKnown(type, fields)
            let localID = try type == .custom ? fields.long("custom_item_id") : nil
            guard localID == nil || localID! > 0 else { return unknown("invalid typed payload") }
            return .init(type: type, payload: payload, schemaVersion: schemaVersion, rawJSON: payloadJSON, localCustomItemID: localID)
        } catch { return unknown("invalid typed payload") }
    }

    private static func decodeKnown(_ type: RecordType, _ f: LocalPayloadFields) throws -> RecordPayload {
        switch type {
        case .nursing:
            let order = try f.string("order"), mode = try f.string("record_mode")
            guard ["L", "R", "LR", "RL"].contains(order), ["start", "end"].contains(mode) else { throw RecordPayloadCodecError.invalidTypedPayload }
            return try .nursing(leftMinutes: f.int("left_min"), rightMinutes: f.int("right_min"), order: order, amountMl: f.optionalInt("amount_ml"), recordMode: mode)
        case .formula, .pumpedFeed, .pumpExpress:
            return try .milk(kind: type == .formula ? .formula : type == .pumpedFeed ? .pumpedFeed : .pumpExpress,
                             amountMl: f.int("amount_ml"), preparedMl: type == .formula ? f.optionalInt("prepared_ml") : nil,
                             durationMinutes: type == .formula ? f.optionalInt("duration_min") : nil)
        case .pee: return try .pee(amount: f.optionalInt("pee_amount") ?? 2)
        case .poop: return try .stool(amount: f.optionalInt("stool_amount") ?? 3, consistency: f.optionalInt("stool_consistency") ?? 3, color: f.optionalInt("stool_color") ?? 0)
        case .bothDiaper: return try .bothDiaper(peeAmount: f.optionalInt("pee_amount") ?? 2, stoolAmount: f.optionalInt("stool_amount") ?? 3, stoolConsistency: f.optionalInt("stool_consistency") ?? 3, stoolColor: f.optionalInt("stool_color") ?? 0)
        case .sleep: return try .sleep(isNap: f.optionalBool("is_nap") ?? false, anomaly: f.bool("anomaly_flag"))
        case .temperature: return try .temperature(celsius: f.double("celsius"))
        case .diary: return try .diary(body: f.string("body"))
        case .bath: return .bath
        case .walk: return .walk
        case .cough, .rash, .vomit, .injury:
            let kind: SymptomKind = switch type { case .cough: .cough; case .rash: .rash; case .vomit: .vomit; default: .injury }
            return try .symptom(kind: kind, severity: f.int("severity"), description: f.optionalString("description"))
        case .medicine: return try .medicine(name: f.string("name"), dose: f.optionalString("dose"))
        case .hospital: return try .hospital(reason: f.string("reason"), advice: f.optionalString("advice"))
        case .height, .weight, .head, .chest, .footSize:
            let kind: MeasurementKind = switch type { case .height: .height; case .weight: .weight; case .head: .head; case .chest: .chest; default: .footSize }
            return try .measurement(kind: kind, value: f.double("value"), unit: f.string("unit"))
        case .babyFood, .snack, .drink:
            return try .food(kind: type == .babyFood ? .babyFood : type == .snack ? .snack : .drink, content: f.string("content"), amount: f.optionalString("amount"))
        case .vaccine: return try .vaccine(name: f.string("name"), batch: f.optionalString("batch"))
        case .custom: return try .custom(titleSnapshot: f.string("title"), detail: f.optionalString("detail"), iconSlot: f.optionalInt("icon_slot"))
        }
    }

    /// Rebuilds known storage JSON; only opaque documents retain original text.
    /// This is NOT wire canonicalization and may emit the local custom row ID.
    public static func encode(_ document: RecordPayloadDocument) throws -> String {
        guard let payload = document.payload else { return document.rawJSON! }
        var fields: [(String, String)] = []
        let localStringEncoder = JSONEncoder()
        localStringEncoder.outputFormatting = [.withoutEscapingSlashes]
        func string(_ key: String, _ value: String?) throws {
            if let value { fields.append((key, String(decoding: try localStringEncoder.encode(value), as: UTF8.self))) }
        }
        func int(_ key: String, _ value: Int?) throws {
            if let value {
                guard Int32(exactly: value) != nil else { throw RecordPayloadCodecError.invalidTypedPayload }
                fields.append((key, String(value)))
            }
        }
        func double(_ key: String, _ value: Double, normalize: Bool = false) throws {
            guard value.isFinite else { throw RecordPayloadCodecError.invalidTypedPayload }
            if normalize && value.truncatingRemainder(dividingBy: 1) == 0 {
                // Kotlin Double.toLong saturates when outside signed 64-bit range.
                let integer = value >= Double(Int64.max) ? Int64.max : value <= Double(Int64.min) ? Int64.min : Int64(value)
                fields.append((key, String(integer)))
            } else { fields.append((key, String(value))) }
        }
        func bool(_ key: String, _ value: Bool) { fields.append((key, value ? "true" : "false")) }
        func stool(_ amount: Int, _ consistency: Int, _ color: Int) throws {
            try int("stool_amount", amount); try int("stool_consistency", consistency); try int("stool_color", color)
        }
        switch payload {
        case let .nursing(left, right, order, amount, mode):
            try int("left_min", left); try int("right_min", right); try string("order", order); try int("amount_ml", amount); try string("record_mode", mode)
        case let .milk(_, amount, prepared, duration):
            try int("amount_ml", amount); try int("prepared_ml", prepared); try int("duration_min", duration)
        case let .pee(amount): try int("pee_amount", amount)
        case let .stool(amount, consistency, color): try stool(amount, consistency, color)
        case let .bothDiaper(pee, amount, consistency, color): try int("pee_amount", pee); try stool(amount, consistency, color)
        case let .sleep(nap, anomaly): bool("is_nap", nap); bool("anomaly_flag", anomaly)
        case let .temperature(value): try double("celsius", value)
        case let .diary(body): try string("body", body)
        case .bath, .walk: break
        case let .symptom(_, severity, description): try int("severity", severity); try string("description", description)
        case let .medicine(name, dose): try string("name", name); try string("dose", dose)
        case let .hospital(reason, advice): try string("reason", reason); try string("advice", advice)
        case let .measurement(_, value, unit): try double("value", value, normalize: true); try string("unit", unit)
        case let .food(_, content, amount): try string("content", content); try string("amount", amount)
        case let .vaccine(name, batch): try string("name", name); try string("batch", batch)
        case let .custom(title, detail, icon):
            try string("title", title); try string("detail", detail)
            if let localID = document.localCustomItemID { fields.append(("custom_item_id", String(localID))) }
            try int("icon_slot", icon)
        }
        return "{" + fields.map { "\"\($0.0)\":\($0.1)" }.joined(separator: ",") + "}"
    }
}

private struct LocalPayloadFields {
    let values: [String: WireJSON]
    func content(_ key: String) throws -> String {
        switch values[key] {
        case let .string(value), let .number(value): return value
        case let .bool(value): return value ? "true" : "false"
        default: throw RecordPayloadCodecError.invalidTypedPayload
        }
    }
    func int(_ key: String) throws -> Int {
        let text = try content(key)
        guard let value = Int32(exactly: try localAndroidInteger(text)) else { throw RecordPayloadCodecError.invalidTypedPayload }
        return Int(value)
    }
    func optionalInt(_ key: String) throws -> Int? { try values[key] == nil ? nil : int(key) }
    func long(_ key: String) throws -> Int64? {
        guard values[key] != nil else { return nil }
        let text = try content(key)
        var ascii = ""
        for (index, scalar) in text.unicodeScalars.enumerated() {
            if index == 0 && (scalar == "+" || scalar == "-") { ascii.unicodeScalars.append(scalar) }
            else if scalar.value <= 0xFFFF, scalar.properties.numericType == .decimal,
                    let digit = scalar.properties.numericValue { ascii += String(Int(digit)) }
            else { throw RecordPayloadCodecError.invalidTypedPayload }
        }
        guard let value = Int64(ascii) else { throw RecordPayloadCodecError.invalidTypedPayload }
        return value
    }
    func double(_ key: String) throws -> Double {
        let text = try content(key).trimmingCharacters(in: CharacterSet(charactersIn: Unicode.Scalar(0)!...Unicode.Scalar(32)! ))
        let grammar = #"^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?|0[xX](?:[0-9a-fA-F]+(?:\.[0-9a-fA-F]*)?|\.[0-9a-fA-F]+)[pP][+-]?[0-9]+)[fFdD]?$"#
        guard text.range(of: grammar, options: .regularExpression) != nil else { throw RecordPayloadCodecError.invalidTypedPayload }
        let cleaned = text.last.map { "fFdD".contains($0) } == true ? String(text.dropLast()) : text
        guard let value = Double(cleaned), value.isFinite else { throw RecordPayloadCodecError.invalidTypedPayload }
        return value
    }
    func bool(_ key: String) throws -> Bool {
        switch try content(key) {
        case "true": return true
        case "false": return false
        default: throw RecordPayloadCodecError.invalidTypedPayload
        }
    }
    func optionalBool(_ key: String) throws -> Bool? { try values[key] == nil ? nil : bool(key) }
    func string(_ key: String) throws -> String {
        guard case let .string(value) = values[key] else { throw RecordPayloadCodecError.invalidTypedPayload }
        return value
    }
    func optionalString(_ key: String) throws -> String? { try values[key] == nil ? nil : string(key) }
}

// Separate local parser: kotlinx Json's tree reader retains unquoted primitive
// content and resolves duplicate keys last-wins. Never reuse this on wire roots.
private struct LocalPayloadJSONParser {
    mutating func parse() throws -> WireJSON {
        let result = try value(depth: 0)
        whitespace()
        guard index == bytes.count else { throw WireContractError.malformedJSON }
        return result
    }

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
        default: return try primitive()
        }
    }

    mutating func string() throws -> String {
        let start = index
        guard take(34) else { throw WireContractError.malformedJSON }
        while index < bytes.count {
            let byte = bytes[index]
            index += 1
            if byte == 34 {
                do {
                    // Kotlin's string reader retains literal control characters.
                    // Escape them only for Foundation's string decoder.
                    var normalized: [UInt8] = []
                    for byte in bytes[start..<index] {
                        if byte < 32 { normalized += Array(String(format: "\\u%04x", byte).utf8) }
                        else { normalized.append(byte) }
                    }
                    return try JSONDecoder().decode(String.self, from: Data(normalized))
                }
                catch { throw WireContractError.malformedJSON }
            }
            if byte == 92 {
                guard index < bytes.count, bytes[index] >= 32 else { throw WireContractError.malformedJSON }
                index += 1
            }
        }
        throw WireContractError.malformedJSON
    }

    mutating func primitive() throws -> WireJSON {
        let start = index
        while index < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) {
            guard bytes[index] > 32, ![34, 58, 91, 92, 123].contains(bytes[index]) else { throw WireContractError.malformedJSON }
            index += 1
        }
        guard index > start else { throw WireContractError.malformedJSON }
        let token = String(decoding: bytes[start..<index], as: UTF8.self)
        switch token {
        case "true": return .bool(true)
        case "false": return .bool(false)
        case "null": return .null
        default: return .number(token)
        }
    }
}

// Sources: https://github.com/Kotlin/kotlinx.serialization/blob/v1.6.3/formats/json/commonMain/src/kotlinx/serialization/json/JsonElement.kt
// https://github.com/Kotlin/kotlinx.serialization/blob/v1.6.3/formats/json/commonMain/src/kotlinx/serialization/json/internal/lexer/AbstractJsonLexer.kt
// Pinned Android dependency: kotlinx.serialization 1.6.3 JsonElement.intOrNull
// delegates to StringJsonLexer.consumeNumericLiteral (not Kotlin toIntOrNull).
// That local API accepts integer exponents and stops at JSON token delimiters.
// Overflow and repeated exponent-sign behavior below is deliberately local-only,
// matching the pinned dependency, never permission to relax wire integers.
// custom_item_id deliberately uses the separate strict toLongOrNull path above.
private func localAndroidInteger(_ text: String) throws -> Int64 {
    let chars = Array(text.utf8)
    var index = 0
    while index < chars.count && [9, 10, 13, 32].contains(chars[index]) { index += 1 }
    let quoted = index < chars.count && chars[index] == 34
    if quoted { index += 1 }
    let start = index
    var negative = false, exponent = false, positiveExponent = false
    var accumulator: Int64 = 0, power: Int64 = 0
    while index < chars.count {
        let c = chars[index]
        if (c == 101 || c == 69) && !exponent {
            guard index != start else { throw RecordPayloadCodecError.invalidTypedPayload }
            exponent = true; positiveExponent = true; index += 1; continue
        }
        if exponent && (c == 45 || c == 43) {
            positiveExponent = c == 43; index += 1; continue
        }
        if c == 45 {
            guard index == start else { throw RecordPayloadCodecError.invalidTypedPayload }
            negative = true; index += 1; continue
        }
        if c < 33 || [34, 44, 58, 91, 93, 123, 125, 92].contains(c) { break }
        guard (48...57).contains(c) else { throw RecordPayloadCodecError.invalidTypedPayload }
        let digit = Int64(c - 48)
        if exponent { power = power &* 10 &+ digit }
        else {
            accumulator = accumulator &* 10 &- digit
            guard accumulator <= 0 else { throw RecordPayloadCodecError.invalidTypedPayload }
        }
        index += 1
    }
    guard index > start, !(negative && index == start + 1) else { throw RecordPayloadCodecError.invalidTypedPayload }
    if quoted {
        guard index < chars.count, chars[index] == 34 else { throw RecordPayloadCodecError.invalidTypedPayload }
    }
    if exponent {
        let scaled = Double(accumulator) * pow(10, positiveExponent ? Double(power) : -Double(power))
        guard scaled.isFinite, scaled >= Double(Int64.min), scaled <= Double(Int64.max), floor(scaled) == scaled else {
            throw RecordPayloadCodecError.invalidTypedPayload
        }
        accumulator = scaled >= Double(Int64.max) ? Int64.max : Int64(scaled)
    }
    if negative { return accumulator }
    guard accumulator != Int64.min else { throw RecordPayloadCodecError.invalidTypedPayload }
    return -accumulator
}
