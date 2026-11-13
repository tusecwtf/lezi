import Foundation
import XCTest
@testable import LeziCore

final class SyncHandshakeTests: XCTestCase {
    private var principal: SyncPrincipal {
        get throws { try SyncPrincipal(membershipID: "member-1", deviceID: "device-1", role: .owner) }
    }
    private var valid: String {
        #"{"protocol_version":1,"server_version":"0.5.5","ready":true,"capabilities":["causal_sync_v2"],"principal":{"membership_id":"member-1","device_id":"device-1","role":"owner"},"directory_generation":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","limits":{"pull_page_max_entities":200,"pull_page_max_encoded_bytes":9437184,"pull_page_max_decoded_bytes":8388608,"pull_max_pages":500,"commit_batch_max_units":64,"media_max_bytes":10485760},"compression":{"pull_response":["gzip","identity"]},"retry_hints":{"retry_after":true}}"#
    }
    private func decode(_ source: String) throws -> AuthenticatedSyncHandshake {
        try AuthenticatedSyncHandshake.decode(Data(source.utf8), expectedPrincipal: principal)
    }

    func testFrozenRequestRoundTripsAndRejectsMixedGeneration() throws {
        let request = SyncHandshakeRequest()
        XCTAssertEqual(try SyncHandshakeRequest.decode(request.encoded()), request)
        for body in [
            #"{"protocol_version":1,"required_capabilities":["causal_sync_v2"],"device_id":"spoof"}"#,
            #"{"protocol_version":1,"required_capabilities":["causal_sync_v2","causal_sync_v2"]}"#,
            #"{"protocol_version":1,"required_capabilities":["causal_sync_v2","sync_heartbeat_v1"]}"#,
            #"{"protocol_version":1,"required_capabilities":[" causal_sync_v2 "]}"#,
            #"{"protocol_version":1,"required_capabilities":[]}"#,
            #"{"protocol_version":2,"required_capabilities":["causal_sync_v2"]}"#,
        ] { XCTAssertThrowsError(try SyncHandshakeRequest.decode(Data(body.utf8)), body) }
    }

    func testNegotiatesFrozenContractBoundToSession() throws {
        let result = try decode(valid)
        XCTAssertEqual(result.principal, try principal)
        XCTAssertEqual(result.protocolVersion, 1)
        XCTAssertTrue(result.ready)
        XCTAssertEqual(result.capabilities, [SyncHandshakeRequest.capability])
        XCTAssertEqual(result.serverVersion, "0.5.5")
        XCTAssertEqual(result.limits.pullPageMaxEntities, 200)
        XCTAssertEqual(result.limits.pullPageMaxEncodedBytes, 9 * 1024 * 1024)
        XCTAssertEqual(result.limits.pullPageMaxDecodedBytes, 8 * 1024 * 1024)
        XCTAssertEqual(result.limits.pullMaxPages, 500)
        XCTAssertEqual(result.limits.commitBatchMaxUnits, 64)
        XCTAssertEqual(result.limits.mediaMaxBytes, 10 * 1024 * 1024)
        XCTAssertEqual(result.pullResponseEncodings, ["gzip", "identity"])
        XCTAssertTrue(result.supportsRetryAfter)
    }

    func testEveryClosedObjectRejectsAdditionalAndMissingFields() throws {
        let base = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(valid.utf8)) as? [String: Any])
        for path in ["", "principal", "limits", "compression", "retry_hints"] {
            var root = base
            var object = path.isEmpty ? root : try XCTUnwrap(root[path] as? [String: Any])
            object["future_field"] = true
            if path.isEmpty { root = object } else { root[path] = object }
            XCTAssertThrowsError(try AuthenticatedSyncHandshake.decode(JSONSerialization.data(withJSONObject: root), expectedPrincipal: principal), path)
            let original = path.isEmpty ? base : try XCTUnwrap(base[path] as? [String: Any])
            for key in original.keys {
                root = base
                object = original
                object.removeValue(forKey: key)
                if path.isEmpty { root = object } else { root[path] = object }
                XCTAssertThrowsError(try AuthenticatedSyncHandshake.decode(JSONSerialization.data(withJSONObject: root), expectedPrincipal: principal), "\(path).\(key)")
            }
        }
    }

    func testRejectsIncompatibleCapabilitiesReadinessAndPrincipal() throws {
        let changes = [
            ("[\"causal_sync_v2\"]", "[]"),
            ("[\"causal_sync_v2\"]", "[\"causal_versions\",\"wake_observation\",\"source_relations\"]"),
            ("[\"causal_sync_v2\"]", "[\"causal_sync_v2\",\"sync_heartbeat_v1\"]"),
            ("[\"causal_sync_v2\"]", "[\"causal_sync_v2\",\"causal_sync_v2\"]"),
            ("\"ready\":true", "\"ready\":false"),
            ("\"protocol_version\":1", "\"protocol_version\":2"),
            ("member-1", "member-2"), ("device-1", "device-2"),
            ("\"owner\"", "\"member\""), ("\"owner\"", "\"admin\""),
            ("\"0.5.5\"", "\"   \""),
            (String(repeating: "a", count: 64), String(repeating: "A", count: 64)),
            ("[\"gzip\",\"identity\"]", "[\"gzip\",\"gzip\"]"),
            ("\"retry_after\":true", "\"retry_after\":false"),
        ]
        for (from, to) in changes { XCTAssertThrowsError(try decode(valid.replacingOccurrences(of: from, with: to)), "\(from) -> \(to)") }
    }

    func testLimitsRejectZeroOverflowAndInvertedByteBudget() throws {
        for (key, maximum) in [("pull_page_max_entities", 200), ("pull_page_max_encoded_bytes", 9437184),
                               ("pull_page_max_decoded_bytes", 8388608), ("pull_max_pages", 500),
                               ("commit_batch_max_units", 64), ("media_max_bytes", 10485760)] {
            for value in ["0", "-1", String(maximum + 1), "9223372036854775808", "true", "\"1\"", "1.0", "1e0", "null"] {
                XCTAssertThrowsError(try decode(valid.replacingOccurrences(of: "\"\(key)\":\(maximum)", with: "\"\(key)\":\(value)")), "\(key)=\(value)")
            }
        }
        XCTAssertThrowsError(try decode(valid.replacingOccurrences(of: "9437184", with: "1")))
        let reduced = valid.replacingOccurrences(of: "9437184", with: "1024").replacingOccurrences(of: "8388608", with: "1024")
        XCTAssertEqual(try decode(reduced).limits.pullPageMaxEncodedBytes, 1024)
    }

    func testAndroidResponseWhitespaceAndCompressionOrderingBehavior() throws {
        let padded = valid.replacingOccurrences(of: "\"causal_sync_v2\"", with: "\" causal_sync_v2 \"")
            .replacingOccurrences(of: "[\"gzip\",\"identity\"]", with: "[\"identity\",\"gzip\"]")
        XCTAssertEqual(try decode(padded), try decode(valid))
    }

    func testProtocolIntegerFidelityAndServerVersionBound() throws {
        for value in ["1.0", "1e0", "true", "\"1\"", "9223372036854775808"] {
            XCTAssertThrowsError(try decode(valid.replacingOccurrences(of: "\"protocol_version\":1", with: "\"protocol_version\":\(value)")))
        }
        XCTAssertEqual(try decode(valid.replacingOccurrences(of: "0.5.5", with: String(repeating: "x", count: 64))).serverVersion.count, 64)
        XCTAssertThrowsError(try decode(valid.replacingOccurrences(of: "0.5.5", with: String(repeating: "x", count: 65))))
    }

    func testTerminalHandshakeHTTPMapping() throws {
        for (code, status) in [("capability_mismatch", 409), ("not_ready", 503)] {
            let data = Data("{\"status\":\"rejected\",\"error\":{\"code\":\"\(code)\",\"retryable\":false}}".utf8)
            XCTAssertEqual(try TerminalRejection.decodeHandshake(data, httpStatus: status).code.rawValue, code)
            for wrong in [200, 400, 401, status == 409 ? 503 : 409] {
                XCTAssertThrowsError(try TerminalRejection.decodeHandshake(data, httpStatus: wrong))
            }
        }
    }
}
