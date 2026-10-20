import Foundation
import XCTest
@testable import LeziCore

final class BabyCanonicalTests: XCTestCase {
    private let uuid = "11111111-1111-1111-1111-111111111111"
    private let avatar = "22222222-2222-2222-2222-222222222222"
    private var valid: String { #"{"nickname":"安安","sex":null,"birthday":null,"birth_weight_grams":null,"avatar_media_uuid":null,"updated_at":1000}"# }
    private func root(_ text: String) throws -> BabyCanonicalRoot { try .decode(Data(text.utf8)) }
    private func media(_ width: String = "null", _ height: String = "null") throws -> BabyCanonicalMedia {
        try .decode(Data("{\"media_uuid\":\"\(avatar)\",\"role\":\"avatar\",\"sha256\":\"\(String(repeating: "a", count: 64))\",\"byte_size\":12,\"mime\":\"image/jpeg\",\"width\":\(width),\"height\":\(height)}".utf8))
    }

    func testSharedBabyCorpusUsesSemanticDecoder() throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        guard case let .object(corpus) = try WireJSON.parse(Data(contentsOf: repository.appendingPathComponent("config/conflict-v2-golden.json"))),
              case let .array(cases) = corpus["cases"] else { return XCTFail("Missing corpus") }
        let ids = ["canonical-explicit-null", "canonical-missing-known-field", "canonical-unknown-field"]
        var seen = Set<String>()
        for fixture in cases {
            guard case let .object(test) = fixture, case let .string(id) = test["id"], ids.contains(id),
                  case let .object(input) = test["input"], let value = input["root"] else { continue }
            seen.insert(id)
            if id == ids[0] {
                let decoded = try BabyCanonicalRoot.decode(value)
                XCTAssertEqual(decoded.nickname, "安安")
                XCTAssertNil(decoded.birthday)
                XCTAssertNil(decoded.birthWeightGrams)
                XCTAssertNil(decoded.avatarMediaUUID)
                XCTAssertEqual(decoded.updatedAt, 1000)
                XCTAssertEqual(try WireJSON.parse(decoded.encoded()), value)
            } else {
                XCTAssertThrowsError(try BabyCanonicalRoot.decode(value)) {
                    XCTAssertEqual($0 as? WireContractError, id == ids[1] ? .missingField("birthday") : .unknownField)
                }
            }
        }
        XCTAssertEqual(seen, Set(ids))
    }

    func testMutationAndStableStampBoundaries() throws {
        let stamped = String(valid.dropLast()) + #", "created_by_membership_id":"member-a"}"#
        XCTAssertThrowsError(try root(stamped))
        XCTAssertThrowsError(try BabyStableRoot.decode(Data(valid.utf8)))
        let stable = try BabyStableRoot.decode(Data(stamped.utf8))
        XCTAssertEqual(stable.createdByMembershipID, "member-a")
        XCTAssertEqual(try BabyStableRoot.decode(stable.encoded()), stable)
        for value in ["null", "12", "\"\"", "\"\(String(repeating: "a", count: 65))\""] {
            XCTAssertThrowsError(try BabyStableRoot.decode(Data(stamped.replacingOccurrences(of: "\"member-a\"", with: value).utf8)))
        }
    }

    func testDomainRejectsCoercionsMissingKnownAndNoncanonicalValues() throws {
        for (old, replacements) in [
            ("\"安安\"", ["\" \"", "\" 安安\"", "\"安安\\u001c\"", "\"\(String(repeating: "😀", count: 21))\"", "true"]),
            ("\"sex\":null", ["\"sex\":\"other\"", "\"sex\":1"]),
            ("\"birthday\":null", ["\"birthday\":\"2023-02-29\"", "\"birthday\":\"2024-2-01\"", "\"birthday\":\"-0000-01-01\""]),
            ("\"birth_weight_grams\":null", ["\"birth_weight_grams\":100001", "\"birth_weight_grams\":-1", "\"birth_weight_grams\":1.0", "\"birth_weight_grams\":1e0", "\"birth_weight_grams\":true", "\"birth_weight_grams\":\"1\""]),
            ("\"avatar_media_uuid\":null", ["\"avatar_media_uuid\":\"invalid\"", "\"avatar_media_uuid\":\"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA\""]),
            ("1000", ["-0", "-1", "1.0", "1e0", "true", "\"1000\"", "9223372036854775808"]),
        ] {
            for replacement in replacements { XCTAssertThrowsError(try root(valid.replacingOccurrences(of: old, with: replacement)), replacement) }
        }
        XCTAssertThrowsError(try root(valid.replacingOccurrences(of: "\"birth_weight_grams\":null,", with: "")))
        XCTAssertThrowsError(try root(valid.replacingOccurrences(of: "\"sex\":null", with: "\"sex\":null,\"sex\":null")))
        for date in ["2024-02-29", "0000-02-29", "-0001-01-01", "+10000-01-01"] {
            XCTAssertEqual(try root(valid.replacingOccurrences(of: "\"birthday\":null", with: "\"birthday\":\"\(date)\"")).birthday, date)
        }
        // Count Unicode scalars, not graphemes or UTF-16 units; preserve Unicode.
        let twenty = String(repeating: "😀", count: 20)
        XCTAssertEqual(try root(valid.replacingOccurrences(of: "安安", with: twenty)).nickname, twenty)
        XCTAssertThrowsError(try root(valid.replacingOccurrences(of: "安安", with: String(repeating: "e\u{301}", count: 11))))
        XCTAssertEqual(try root(valid.replacingOccurrences(of: "安安", with: "e\u{301}")).nickname.unicodeScalars.count, 2)
    }

    func testMediaExplicitNullsBoundsAndReferenceValidation() throws {
        let item = try media()
        guard case let .object(fields) = try WireJSON.parse(item.encoded()) else { return XCTFail("object") }
        XCTAssertEqual(fields["width"], .null)
        XCTAssertEqual(fields["height"], .null)
        var missing = fields; missing.removeValue(forKey: "width")
        XCTAssertThrowsError(try BabyCanonicalMedia.decode(.object(missing)))
        for (key, value) in [("role", WireJSON.string("log")), ("sha256", .string(String(repeating: "A", count: 64))),
                             ("byte_size", .number("0")), ("width", .number("0")), ("height", .number("2147483648")),
                             ("mime", .string("")), ("extra", .null)] {
            var changed = fields; changed[key] = value
            XCTAssertThrowsError(try BabyCanonicalMedia.decode(.object(changed)))
        }
        _ = try media("1", "2147483647")
        let withAvatar = try root(valid.replacingOccurrences(of: "\"avatar_media_uuid\":null", with: "\"avatar_media_uuid\":\"\(avatar)\""))
        XCTAssertThrowsError(try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: withAvatar, media: []))
        XCTAssertThrowsError(try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: withAvatar, media: [item, item]))
        _ = try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: withAvatar, media: [item])
    }

    func testExistingNoMediaRustKotlinRecordHashGolden() throws {
        // Exact legacy golden from causal_merge.rs; this is not a current record decoder.
        let projection = #"{"entity_type":"record","client_uuid":"11111111-1111-1111-1111-111111111111","base_version":"v-r0","deleted":false,"media":[],"root":{"baby_client_uuid":"22222222-2222-2222-2222-222222222222","custom_item_client_uuid":null,"note":null,"payload_json":{"amount_ml":90},"schema_version":2,"timestamp":100,"type":"formula","updated_at":100}}"#
        XCTAssertEqual(try integerCanonicalDigest(WireJSON.parse(Data(projection.utf8))), "ff2cec4612265f208e3c3a06029fdd1e24c0d75a2081c89e8468812720e9a83a")
    }

    func testHashHasSixFieldsAndChangesWithEveryInput() throws {
        let baby = try root(valid)
        let baseline = try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: baby, media: [])
        let expected: WireJSON = .object(["entity_type": .string("baby"), "client_uuid": .string(uuid), "base_version": .null,
                                        "deleted": .bool(false), "root": baby.wire, "media": .array([])])
        XCTAssertEqual(baseline, try integerCanonicalDigest(expected))
        // Independent Python hashlib/sorted compact JSON vectors, local synthetic data.
        XCTAssertEqual(baseline, "7c808ab59c3baa1815370f7ab05e0d5fe8db7a4633258f0eb491cae9c86ea9ea")
        XCTAssertEqual(try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: baby, media: [media()]),
                       "d5893e09eeebce6640d091e682711afcd07f38e70f475b1d5901e088544c0afd")
        XCTAssertNotEqual(baseline, try BabyMutationCanonicalHash.digest(clientUUID: avatar, baseVersion: nil, deleted: false, root: baby, media: []))
        XCTAssertNotEqual(baseline, try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: "v1", deleted: false, root: baby, media: []))
        XCTAssertNotEqual(baseline, try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: true, root: baby, media: []))
        XCTAssertNotEqual(baseline, try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: root(valid.replacingOccurrences(of: "1000", with: "1001")), media: []))
        XCTAssertNotEqual(baseline, try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: baby, media: [media()]))
    }

    func testHashIgnoresJSONFormattingButPreservesUnicodeScalars() throws {
        let compact = try root(valid)
        let spaced = try root(valid.replacingOccurrences(of: ",", with: ", \n"))
        let a = try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: compact, media: [])
        let b = try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: spaced, media: [])
        XCTAssertEqual(a, b)
        let decomposed = try root(valid.replacingOccurrences(of: "安安", with: "e\u{301}"))
        let composed = try root(valid.replacingOccurrences(of: "安安", with: "é"))
        XCTAssertNotEqual(decomposed, composed)
        XCTAssertNotEqual(try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: decomposed, media: []),
                          try BabyMutationCanonicalHash.digest(clientUUID: uuid, baseVersion: nil, deleted: false, root: composed, media: []))
    }

    func testCanonicalEscapingSortingAndIntegerOnlyBoundary() throws {
        let value: WireJSON = .object(["z": .array([.null, .bool(true), .number("0")]), "a": .string("安/😀\"\\\n\t\u{01}\u{2028}")])
        XCTAssertEqual(try integerCanonicalJSON(value), "{\"a\":\"安/😀\\\"\\\\\\n\\t\\u0001\u{2028}\",\"z\":[null,true,0]}")
        for number in ["-0", "1.0", "1e0", "9223372036854775808"] { XCTAssertThrowsError(try integerCanonicalJSON(.number(number))) }
        XCTAssertEqual(try integerCanonicalJSON(.number("-9223372036854775808")), "-9223372036854775808")
    }
}
