import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Hashing is limited to the validated baby integer/null/string/bool projection.
/// This is not an arbitrary-root JSON canonicalizer or a mutation transport.
public enum BabyMutationCanonicalHash {
    public static func digest(clientUUID: String, baseVersion: String?, deleted: Bool,
                              root: BabyCanonicalRoot, media: [BabyCanonicalMedia]) throws -> String {
        try validateBabyUUID(clientUUID, "client_uuid")
        if let baseVersion, baseVersion.isEmpty { throw WireContractError.invalidValue("base_version") }
        guard media.count <= 1 else { throw WireContractError.invalidValue("media") }
        if let avatar = root.avatarMediaUUID, !media.contains(where: { $0.mediaUUID == avatar }) {
            throw WireContractError.invalidValue("avatar_media_uuid")
        }
        // Keep explicit-null dimensions per wire §4.6 and Rust serialization.
        // Android currently omits nil dimensions in its hash projection; compatibility
        // for that case is pending, not established by the shared no-media golden.
        let projection: WireJSON = .object([
            "entity_type": .string("baby"), "client_uuid": .string(clientUUID),
            "base_version": baseVersion.map(WireJSON.string) ?? .null, "deleted": .bool(deleted),
            "root": root.wire, "media": .array(media.sorted { $0.mediaUUID < $1.mediaUUID }.map(\.wire)),
        ])
        return try integerCanonicalDigest(projection)
    }
}

// Internal seam for the existing Rust/Kotlin no-media record golden only. No
// public generic hash API: decimals/exponents and out-of-range numbers fail closed.
func integerCanonicalDigest(_ value: WireJSON) throws -> String {
    SHA256.hash(data: Data(try integerCanonicalJSON(value).utf8)).map { String(format: "%02x", $0) }.joined()
}

func integerCanonicalJSON(_ value: WireJSON) throws -> String {
    switch value {
    case let .object(fields):
        return "{" + (try fields.keys.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }.map {
            try canonicalJSONString($0) + ":" + integerCanonicalJSON(fields[$0]!)
        }).joined(separator: ",") + "}"
    case let .array(values): return "[" + (try values.map(integerCanonicalJSON)).joined(separator: ",") + "]"
    case let .string(value): return canonicalJSONString(value)
    case .number:
        guard value != .number("-0") else { throw WireContractError.invalidValue("canonical_integer") }
        return String(try value.integer("canonical_integer"))
    case let .bool(value): return value ? "true" : "false"
    case .null: return "null"
    }
}

/// serde_json-compatible escaping: Unicode and slash stay literal; JSON control
/// characters use short escapes where defined, otherwise lowercase hex.
private func canonicalJSONString(_ value: String) -> String {
    var result = "\""
    for scalar in value.unicodeScalars {
        switch scalar.value {
        case 0x22: result += "\\\""
        case 0x5c: result += "\\\\"
        case 8: result += "\\b"
        case 9: result += "\\t"
        case 10: result += "\\n"
        case 12: result += "\\f"
        case 13: result += "\\r"
        case 0...31: result += String(format: "\\u%04x", scalar.value)
        default: result.unicodeScalars.append(scalar)
        }
    }
    return result + "\""
}
