import Foundation
import XCTest
@testable import LeziCore

final class RecordItemIdentityTests: XCTestCase {
    func testCustomDefinitionFactoryCreatesCanonicalStableIdentityWithoutLocalID() throws {
        let id = try XCTUnwrap(UUID(uuidString: "ABCDEFAB-1234-5678-9ABC-DEF012345678"))
        let identity = RecordItemIdentity.customDefinition(id)
        XCTAssertEqual(identity.recordType, .custom)
        XCTAssertEqual(identity.catalogKey, "custom:abcdefab-1234-5678-9abc-def012345678")
        XCTAssertEqual(identity.familyCustomUUID, "abcdefab-1234-5678-9abc-def012345678")
        XCTAssertNil(identity.legacyLocalCustomID)
        XCTAssertEqual(identity, RecordItemIdentity.customDefinition(id))
        XCTAssertEqual(identity, RecordItemIdentity.parseCatalogKey(identity.catalogKey))
        XCTAssertEqual(identity, RecordItemIdentity.parseCatalogKey("custom:" + id.uuidString))
        XCTAssertEqual(Set([identity, RecordItemIdentity.customDefinition(id)]).count, 1)
    }

    func testFactoryDoesNotChangeBuiltInBareCustomOrLegacyParsing() throws {
        XCTAssertNil(RecordItemIdentity.builtIn(.custom))
        XCTAssertNil(RecordItemIdentity.parseCatalogKey("custom"))
        XCTAssertNil(RecordItemIdentity.parseCatalogKey("custom:not-a-uuid"))
        XCTAssertEqual(RecordItemIdentity.builtIn(.pee)?.catalogKey, "pee")
        let legacy = try XCTUnwrap(RecordItemIdentity.parseCatalogKey("custom:42"))
        XCTAssertEqual(legacy.legacyLocalCustomID, 42)
        XCTAssertNil(legacy.familyCustomUUID)
        let first = try XCTUnwrap(UUID(uuidString: "00000000-0000-0000-0000-000000000000"))
        let second = try XCTUnwrap(UUID(uuidString: "00000000-0000-0000-0000-000000000001"))
        XCTAssertNotEqual(RecordItemIdentity.customDefinition(first), RecordItemIdentity.customDefinition(second))
        XCTAssertEqual(RecordItemIdentity.parseCatalogKey(RecordItemIdentity.customDefinition(first).catalogKey), .customDefinition(first))
    }
}
