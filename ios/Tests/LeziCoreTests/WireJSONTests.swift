import Foundation
import XCTest
@testable import LeziCore

final class WireJSONTests: XCTestCase {
    func testKotlinCompatibleWhitespacePreservesPrincipalAndCapabilitySemantics() throws {
        XCTAssertEqual(trimAndroidWireString("\u{001C}\u{001F}\u{00A0}capability\u{3000}"), "capability")
        XCTAssertEqual(trimAndroidWireString("\u{0085}capability\u{0085}"), "\u{0085}capability\u{0085}")
        let accepted = try WireJSON.parse(Data(#"["\u001ccausal_sync_v2\u001f"]"#.utf8))
        XCTAssertEqual(try accepted.strings("capabilities", max: 64), [SyncHandshakeRequest.capability])
        let untrimmed = try WireJSON.parse(Data(#"["\u0085causal_sync_v2\u0085"]"#.utf8))
        XCTAssertNotEqual(try untrimmed.strings("capabilities", max: 64), [SyncHandshakeRequest.capability])
    }

    func testIntegersPreserveInt64AndBeyondDoublePrecision() throws {
        for token in ["9007199254740993", "9223372036854775807", "-9223372036854775808", "0", "-0"] {
            XCTAssertEqual(try WireJSON.parse(Data(token.utf8)).integer("value"), Int64(token))
        }
        for token in ["1.0", "1e0", "1E+0", "\"1\"", "true", "false", "null", "9223372036854775808", "-9223372036854775809"] {
            XCTAssertThrowsError(try WireJSON.parse(Data(token.utf8)).integer("value"), token)
        }
    }

    func testMalformedJSONAndDuplicateKeysFailClosed() {
        for input in ["", "01", "+1", "1.", "1e", "[1,]", "{\"a\":1,}", "true false", "{\"a\":1,\"a\":2}",
                      "{\"a\":1,\"\\u0061\":2}", "\"unclosed", "\"\\x\"", "\"line\nbreak\"", "{\"a\":}"] {
            XCTAssertThrowsError(try WireJSON.parse(Data(input.utf8)), input)
        }
        XCTAssertThrowsError(try WireJSON.parse(Data([34, 255, 34])))
    }

    func testUnicodeNullAndDepthBound() throws {
        XCTAssertEqual(try WireJSON.parse(Data(#"{"name":"安安\uD83D\uDE00","clear":null}"#.utf8)),
                       .object(["name": .string("安安😀"), "clear": .null]))
        let nested = String(repeating: "[", count: 66) + "null" + String(repeating: "]", count: 66)
        XCTAssertThrowsError(try WireJSON.parse(Data(nested.utf8)))
        XCTAssertThrowsError(try WireJSON.parse(Data(repeating: 32, count: 8 * 1024 * 1024 + 1)))
    }

    func testHeartbeatRetainsRevisionAndAllowsDisasterRecoveryRollback() throws {
        func heartbeat(_ revision: String) throws -> SyncHeartbeat {
            try SyncHeartbeat.decode(Data("{\"generation\":\"install-1\",\"head_rev\":\(revision),\"directory_generation\":\"directory-1\"}".utf8))
        }
        XCTAssertEqual(try heartbeat("9007199254740993").headRevision, 9007199254740993)
        XCTAssertNotEqual(try heartbeat("42"), try heartbeat("41"))
        XCTAssertThrowsError(try heartbeat("42.0"))
        XCTAssertThrowsError(try heartbeat("\"42\""))
    }
}
