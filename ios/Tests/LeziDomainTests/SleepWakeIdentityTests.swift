import Foundation
import XCTest
@testable import LeziCore

final class SleepWakeIdentityTests: XCTestCase {
    private let sleep = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    func testRFCNameBasedUUIDv5VectorAndBits() throws {
        // RFC 9562 Appendix A.4: https://www.rfc-editor.org/rfc/rfc9562.html#appendix-A.4
        let dns = try XCTUnwrap(UUID(uuidString: "6ba7b810-9dad-11d1-80b4-00c04fd430c8"))
        let result = nameBasedUUIDv5(namespace: dns, name: "www.example.com")
        XCTAssertEqual(result.uuidString.lowercased(), "2ed6657d-e927-568b-95e1-2665a8aea6a2")
        XCTAssertEqual(result.uuid.6 >> 4, 5)
        XCTAssertEqual(result.uuid.8 & 0xc0, 0x80)
    }

    func testExistingAndroidGenericUUIDv5Vector() throws {
        // core/common/CausalIdentityTest.uuidV5MatchesKnownMigrationBaseVersionVector.
        let namespace = try XCTUnwrap(UUID(uuidString: "3d4f8a21-6b9c-4e11-9f2a-0cd4e87155b0"))
        XCTAssertEqual(nameBasedUUIDv5(namespace: namespace, name: "entity_version_v12:fam:record:uuid:3:abc").uuidString.lowercased(),
                       "6889ea37-f11d-50be-b32f-118459107281")
    }

    func testNewClosedSleepUsesSleepNamespaceAndIsCaseIndependent() throws {
        // Independently checked with Python stdlib uuid.uuid5(UUID(sleep),
        // "lezi-closed-sleep-wake"); Android uses precisely this formula.
        let lower = try XCTUnwrap(UUID(uuidString: sleep))
        let upper = try XCTUnwrap(UUID(uuidString: sleep.uppercased()))
        let result = SleepWakeIdentity.closedSleepObservationUUID(sleepClientUUID: lower)
        XCTAssertEqual(result.uuidString.lowercased(), "6c1755b9-c1ca-5ba4-99a5-53b16d1a7c55")
        XCTAssertEqual(SleepWakeIdentity.closedSleepObservationUUID(sleepClientUUID: upper), result)
        XCTAssertEqual(SleepWakeIdentity.closedSleepObservationUUID(sleepClientUUID: lower), result)
        XCTAssertNotEqual(result, SleepWakeIdentity.legacyObservationUUID(originalSleepClientUUID: sleep, legacyUpdatedAt: 1_700_000_000_000))
    }

    func testHistoricalIdentityMatchesAndroidVectorAndPreservesOriginalCase() {
        // core/common/CausalIdentityTest.wakeObservationClientUuidMatchesServerWireFormula.
        XCTAssertEqual(legacy(sleep, 1_700_000_000_000), "420ef57e-f8ac-5704-871e-e3cc857ceba2")
        XCTAssertEqual(legacy(sleep, 1_700_000_000_001), "05e4771d-2248-51be-9e5f-e975f7d85c29")
        // Uppercase is intentionally different: the name is original UTF-8 text.
        XCTAssertEqual(legacy(sleep.uppercased(), 1_700_000_000_000), "53c66c42-efa4-5e05-a205-f76cf6279b5d")
    }

    func testHistoricalTimestampUsesExactSignedInt64Decimal() {
        // Extra boundary vectors independently checked with Python uuid.uuid5.
        let vectors: [(Int64, String)] = [
            (0, "175dc0d0-1a2b-537e-9348-c96af5d5617a"),
            (-1, "4a068805-a0ce-511d-9548-2b7f8df8ee84"),
            (.min, "9365ba2f-46a9-5793-8757-bf1535380ca0"),
            (.max, "ca408dc0-09b2-5d54-9962-affd821b3bd5"),
        ]
        for (timestamp, expected) in vectors {
            XCTAssertEqual(legacy(sleep, timestamp), expected)
        }
    }

    private func legacy(_ original: String, _ updatedAt: Int64) -> String {
        SleepWakeIdentity.legacyObservationUUID(originalSleepClientUUID: original, legacyUpdatedAt: updatedAt).uuidString.lowercased()
    }
}
