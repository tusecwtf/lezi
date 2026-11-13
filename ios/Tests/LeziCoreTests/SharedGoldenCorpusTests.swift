import Foundation
import XCTest
@testable import LeziCore

/// Reads the repository's single source of truth in-place. No copied fixtures,
/// downloads or resource fallback that could silently test an outdated corpus.
final class SharedGoldenCorpusTests: XCTestCase {
    private func corpus() throws -> [String: WireJSON] {
        let repository = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: repository.appendingPathComponent("config/conflict-v2-golden.json"))
        return try WireJSON.parse(data).object(keys: [
            "$schema", "contract", "version", "release", "capability", "terminal_error_codes",
            "terminal_error_examples", "cases", "dataflow_01_sleep_wake", "dataflow_02_care_plan",
            "dataflow_03_portable_identity", "dataflow_04_fulfillment_candidate", "dataflow_05_custom_item",
            "dataflow_06_media", "dataflow_07_record_non_sleep",
        ])
    }

    func testFrozenCorpusIdentityAndCapabilityMatchHandshake() throws {
        let root = try corpus()
        XCTAssertEqual(try root.field("contract").string("contract"), "lezi.conflict-v2-golden")
        XCTAssertEqual(try root.field("version").integer("version"), 2)
        let capability = try root.field("capability").object(keys: ["key", "advertise_after_ticket", "mixed_generation"])
        XCTAssertEqual(try capability.field("key").string("key"), SyncHandshakeRequest.capability)
        XCTAssertEqual(try capability.field("mixed_generation").string("mixed_generation"), "reject_before_mutation")
    }

    func testEverySharedTerminalReceiptDecodesWithExactVocabulary() throws {
        let root = try corpus()
        let codes = try root.field("terminal_error_codes").strings("terminal_error_codes", max: 64)
        XCTAssertEqual(codes, Set(TerminalErrorCode.allCases.filter { $0 != .notReady }.map(\.rawValue)))
        guard case let .array(examples) = try root.field("terminal_error_examples") else { return XCTFail("Missing examples") }
        var seen = Set<String>()
        for example in examples {
            let object = try example.object(keys: ["code", "response"])
            let expected = try object.field("code").string("code")
            let response = try TerminalRejection.decode(object.field("response"))
            XCTAssertEqual(response.code.rawValue, expected)
            XCTAssertTrue(seen.insert(expected).inserted)
        }
        XCTAssertEqual(seen, codes)
    }

    func testTerminalReceiptsRejectUnknownKeysTypesAndNonterminalRetry() throws {
        let examples = [
            #"{"status":"rejected","error":{"code":"future_code","retryable":false}}"#,
            #"{"status":"rejected","error":{"code":"unknown_field","retryable":true}}"#,
            #"{"status":"rejected","error":{"code":"unknown_field","retryable":"false"}}"#,
            #"{"status":"accepted","error":{"code":"unknown_field","retryable":false}}"#,
            #"{"status":"rejected","error":{"code":"unknown_field","retryable":false,"detail":"private"}}"#,
            #"{"status":"rejected","error":{"code":"unknown_field"}}"#,
            #"{"status":"rejected","error":{"code":"unknown_field","retryable":false},"generation":"x"}"#,
        ]
        for example in examples { XCTAssertThrowsError(try TerminalRejection.decode(Data(example.utf8))) }
    }

    /// This tests closed-object/null mechanics with three canonical corpus cases;
    /// it is intentionally not a baby domain or full canonicalization validator.
    func testSharedBabyCasesPreserveExplicitNullAndRejectShapeDrift() throws {
        let root = try corpus()
        guard case let .array(cases) = try root.field("cases") else { return XCTFail("Missing cases") }
        let keys: Set<String> = ["nickname", "sex", "birthday", "birth_weight_grams", "avatar_media_uuid", "updated_at"]
        var seen = Set<String>()
        for fixture in cases {
            guard case let .object(test) = fixture,
                  case let .string(id) = test["id"],
                  ["canonical-explicit-null", "canonical-missing-known-field", "canonical-unknown-field"].contains(id),
                  case let .object(input) = test["input"], let value = input["root"] else { continue }
            seen.insert(id)
            switch id {
            case "canonical-explicit-null":
                let baby = try value.object(keys: keys)
                XCTAssertEqual(baby["birthday"], .null)
                XCTAssertEqual(try baby.field("updated_at").integer("updated_at"), 1000)
            case "canonical-missing-known-field":
                XCTAssertThrowsError(try value.object(keys: keys)) { XCTAssertEqual($0 as? WireContractError, .missingField("birthday")) }
            default:
                XCTAssertThrowsError(try value.object(keys: keys)) { XCTAssertEqual($0 as? WireContractError, .unknownField) }
            }
        }
        XCTAssertEqual(seen.count, 3)
    }
}
