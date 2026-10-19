import XCTest
@testable import LeziCore

final class RecordPayloadCodecTests: XCTestCase {
    private func decode(_ type: RecordType, _ raw: String, version: Int = 2) -> RecordPayloadDocument {
        RecordPayloadCodec.decode(type: type, payloadJSON: raw, schemaVersion: version)
    }

    private func fixture(_ type: RecordType) -> String {
        switch type {
        case .nursing: return #"{"left_min":1,"right_min":2,"order":"LR","record_mode":"end"}"#
        case .formula, .pumpedFeed, .pumpExpress: return #"{"amount_ml":120}"#
        case .pee: return #"{"pee_amount":2}"#
        case .poop: return #"{"stool_amount":3,"stool_consistency":3,"stool_color":2}"#
        case .bothDiaper: return #"{"pee_amount":2,"stool_amount":3,"stool_consistency":3,"stool_color":2}"#
        case .sleep: return #"{"anomaly_flag":false}"#
        case .temperature: return #"{"celsius":36.8}"#
        case .diary: return #"{"body":"正文"}"#
        case .bath, .walk: return "{}"
        case .cough, .rash, .vomit, .injury: return #"{"severity":2}"#
        case .medicine: return #"{"name":"药"}"#
        case .hospital: return #"{"reason":"复诊"}"#
        case .height, .head, .chest, .footSize: return #"{"value":66.5,"unit":"cm"}"#
        case .weight: return #"{"value":6350,"unit":"g"}"#
        case .babyFood, .snack, .drink: return #"{"content":"内容"}"#
        case .vaccine: return #"{"name":"乙肝"}"#
        case .custom: return #"{"title":"自定义","custom_item_id":1}"#
        }
    }

    func testAll28AndroidFixturesDecodeAndReencode() throws {
        XCTAssertEqual(RecordType.allCases.count, 28)
        for type in RecordType.allCases {
            let raw = fixture(type), doc = decode(type, raw)
            XCTAssertFalse(doc.isUnknown, "\(type)")
            XCTAssertEqual(doc.payload?.type, type)
            XCTAssertEqual(doc.rawJSON, raw)
            let encoded = try RecordPayloadCodec.encode(doc)
            let roundtrip = decode(type, encoded)
            XCTAssertEqual(roundtrip.payload, doc.payload)
            XCTAssertEqual(roundtrip.localCustomItemID, doc.localCustomItemID)
        }
    }

    func testOpaqueVersionsPreserveExactText() throws {
        for version in [-1, 0, 1, 3, 99] {
            let raw = " \n{\"amount_ml\":120,\"future\": [1,2]}\t"
            let doc = decode(.formula, raw, version: version)
            XCTAssertTrue(doc.isUnknown)
            XCTAssertNil(doc.payload)
            XCTAssertEqual(doc.schemaVersion, version)
            XCTAssertEqual(doc.unknownReason, version > 2 ? "future schema" : "unsupported schema")
            XCTAssertEqual(try RecordPayloadCodec.encode(doc), raw)
        }
    }

    func testMalformedRootsRemainOpaqueAndByteStable() throws {
        for raw in ["", "{", #"{"amount_ml":"#, "[]", "null", "120", "{} {}", #"{"amount_ml":1,}"#] {
            let doc = decode(.formula, raw)
            XCTAssertNil(doc.payload, raw)
            XCTAssertEqual(try RecordPayloadCodec.encode(doc), raw)
        }
    }

    func testUnknownKeysAndRemovedAliasesStayOpaque() throws {
        let cases: [(RecordType, String)] = [
            (.formula, #"{"amount_ml":120,"future":{"v":2}}"#),
            (.temperature, #"{"value":36.8}"#),
            (.diary, #"{"body":"正文","photos":["a.jpg"]}"#),
            (.pumpedFeed, #"{"amount_ml":120,"prepared_ml":150}"#),
            (.pumpExpress, #"{"amount_ml":120,"duration_min":5}"#),
        ]
        for (type, raw) in cases {
            let doc = decode(type, raw)
            XCTAssertEqual(doc.unknownReason, "unknown fields")
            XCTAssertNil(doc.payload)
            XCTAssertEqual(try RecordPayloadCodec.encode(doc), raw)
        }
    }

    func testEveryRequiredKeyIsRequired() throws {
        for type in RecordType.allCases {
            let original = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(fixture(type).utf8)) as? [String: Any])
            for key in RecordPayloadCodec.payloadKeySchema(type).required {
                var fields = original
                fields.removeValue(forKey: key)
                let raw = String(decoding: try JSONSerialization.data(withJSONObject: fields), as: UTF8.self)
                XCTAssertEqual(decode(type, raw).unknownReason, "missing required keys", "\(type):\(key)")
            }
        }
    }

    func testEveryExplicitNullIsInvalidIncludingOptionalFields() throws {
        for type in RecordType.allCases {
            let original = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(fixture(type).utf8)) as? [String: Any])
            for key in RecordPayloadCodec.payloadKeySchema(type).allowed {
                var fields = original
                fields[key] = NSNull()
                let raw = String(decoding: try JSONSerialization.data(withJSONObject: fields), as: UTF8.self)
                XCTAssertEqual(decode(type, raw).unknownReason, "invalid typed payload", "\(type):\(key)")
            }
        }
    }

    func testMissingOptionalDefaultsAndNormalizedEncoding() throws {
        XCTAssertEqual(decode(.pee, "{}").payload, .pee(amount: 2))
        XCTAssertEqual(decode(.poop, "{}").payload, .stool())
        XCTAssertEqual(decode(.bothDiaper, "{}").payload, .bothDiaper())
        let sleep = decode(.sleep, #"{"anomaly_flag":false}"#)
        XCTAssertEqual(sleep.payload, .sleep())
        XCTAssertEqual(try RecordPayloadCodec.encode(sleep), #"{"is_nap":false,"anomaly_flag":false}"#)
        XCTAssertEqual(try RecordPayloadCodec.encode(decode(.weight, #"{"value":6350.0,"unit":"g"}"#)), #"{"value":6350,"unit":"g"}"#)
    }

    func testLocalPrimitiveCoercionIsNotWireValidation() {
        XCTAssertEqual(decode(.formula, #"{"amount_ml":"120"}"#).payload, .milk(kind: .formula, amountMl: 120))
        XCTAssertEqual(decode(.sleep, #"{"anomaly_flag":"true","is_nap":"false"}"#).payload, .sleep(isNap: false, anomaly: true))
        XCTAssertEqual(decode(.temperature, #"{"celsius":"36.8"}"#).payload, .temperature(celsius: 36.8))
        XCTAssertThrowsError(try WireJSON.parse(Data(#""120""#.utf8)).integer("amount_ml"))
        XCTAssertThrowsError(try WireJSON.parse(Data(#""true""#.utf8)).boolean("anomaly_flag"))
    }

    func testLocalDuplicateKeysKeepLastValueWithoutWeakeningWire() throws {
        let raw = #"{"amount_ml":10,"amount_\u006dl":120}"#
        XCTAssertEqual(decode(.formula, raw).payload, .milk(kind: .formula, amountMl: 120))
        XCTAssertThrowsError(try WireJSON.parse(Data(raw.utf8)))
        XCTAssertNil(decode(.formula, #"{"amount_ml":120,"amount_ml":null}"#).payload)
    }

    func testIntegersAreSigned32BitAndRejectFractionalValues() {
        for value in ["1.0", "1e-1", "true", "2147483648", "-2147483649", "[]", "{}"] {
            XCTAssertNil(decode(.formula, "{\"amount_ml\":\(value)}").payload, value)
        }
        for value in [Int32.min, Int32.max] {
            XCTAssertEqual(decode(.formula, "{\"amount_ml\":\(value)}").payload, .milk(kind: .formula, amountMl: Int(value)))
        }
    }

    // Pinned source: kotlinx.serialization v1.6.3 JsonElement.intOrNull and
    // internal/lexer/AbstractJsonLexer.consumeNumericLiteral, not strict wire.
    func testPinnedAndroidLocalIntegerLexerQuirks() {
        for token in ["1e2", #""100""#, #"" 100 trailing""#, "0100", "100e", "100e+-0"] {
            XCTAssertEqual(decode(.formula, "{\"amount_ml\":\(token)}").payload,
                           .milk(kind: .formula, amountMl: 100), token)
        }
        XCTAssertNil(decode(.formula, #"{"amount_ml":+100}"#).payload)
        XCTAssertNil(decode(.custom, #"{"title":"x","custom_item_id":" 100"}"#).payload)
        XCTAssertNil(decode(.custom, #"{"title":"x","custom_item_id":1e2}"#).payload)
        XCTAssertEqual(decode(.custom, #"{"title":"x","custom_item_id":"+100"}"#).localCustomItemID, 100)
        XCTAssertEqual(decode(.custom, #"{"title":"x","custom_item_id":"１２３"}"#).localCustomItemID, 123)
        for raw in [#"{"amount_ml":100 trailing}"#, #"{"amount_ml":100} trailing"#, "{\"amount_ml\":100\u{1}}"] {
            XCTAssertNil(decode(.formula, raw).payload)
        }
    }

    func testPinnedLocalIntegerWrappingAndRepeatedExponentSigns() {
        let vectors: [(String, Int)] = [("18446744073709551616", 0), ("1e-+2", 100), ("100e+-2", 1)]
        // 1e+-2 is fractional, hence opaque; final sign wins in the other case.
        XCTAssertNil(decode(.formula, "{\"amount_ml\":1e+-2}").payload)
        for (token, expected) in vectors {
            XCTAssertEqual(decode(.formula, "{\"amount_ml\":\(token)}").payload,
                           .milk(kind: .formula, amountMl: expected))
        }
    }

    func testBackslashThenLiteralControlStaysOpaqueWithoutRewriting() throws {
        for control in UInt8(0)...UInt8(31) {
            let raw = String(decoding: Array(#"{"body":"a"#.utf8) + [92, control] + Array(#"b"}"#.utf8), as: UTF8.self)
            let doc = decode(.diary, raw)
            XCTAssertNil(doc.payload, "control \(control)")
            XCTAssertEqual(try RecordPayloadCodec.encode(doc), raw)
        }
    }

    func testAndroidLocalDoubleSpellingsAndLiteralControls() {
        for token in [#"" 36.5 ""#, #""36.5f""#, #""0x1.24p5""#] {
            XCTAssertEqual(decode(.temperature, "{\"celsius\":\(token)}").payload, .temperature(celsius: 36.5))
        }
        XCTAssertEqual(decode(.diary, "{\"body\":\"a\nb\"}").payload, .diary(body: "a\nb"))
    }

    func testTypedDecodeDoesNotPerformUserInputValidation() {
        let doc = decode(.formula, #"{"amount_ml":-1}"#)
        XCTAssertNotNil(doc.payload)
        XCTAssertFalse(doc.payload!.validationErrors().isEmpty)
        XCTAssertNotNil(decode(.babyFood, #"{"content":"米糊","amount":"几口"}"#).payload)
        XCTAssertNotNil(decode(.diary, #"{"body":""}"#).payload)
        XCTAssertNil(decode(.diary, #"{"body":1}"#).payload)
    }

    func testNursingEnumsAndNonfiniteNumbersFailClosed() {
        for raw in [#"{"left_min":1,"right_min":2,"order":"LL","record_mode":"end"}"#,
                    #"{"left_min":1,"right_min":2,"order":"LR","record_mode":"other"}"#] {
            XCTAssertNil(decode(.nursing, raw).payload)
        }
        for value in ["1e999", #""NaN""#, #""Infinity""#, "true"] {
            XCTAssertNil(decode(.temperature, "{\"celsius\":\(value)}").payload)
        }
    }

    func testLocalCustomIDIsLosslessAndOptional() throws {
        let raw = #"{"title":"抚触","detail":"轻柔","custom_item_id":9223372036854775807,"icon_slot":2}"#
        let doc = decode(.custom, raw)
        XCTAssertEqual(doc.localCustomItemID, Int64.max)
        XCTAssertEqual(doc.payload, .custom(titleSnapshot: "抚触", detail: "轻柔", iconSlot: 2))
        XCTAssertEqual(try RecordPayloadCodec.encode(doc), raw)
        let portableValue = try RecordPayloadDocument(payload: XCTUnwrap(doc.payload))
        XCTAssertFalse(try RecordPayloadCodec.encode(portableValue).contains("custom_item_id"))
        XCTAssertEqual(try RecordPayloadCodec.encode(decode(.custom, #"{"title":"仅标题"}"#)), #"{"title":"仅标题"}"#)
        for value in ["0", "-1", "9223372036854775808", "1.0", "null"] {
            XCTAssertNil(decode(.custom, "{\"title\":\"x\",\"custom_item_id\":\(value)}").payload)
        }
        XCTAssertThrowsError(try RecordPayloadDocument(payload: .bath, localCustomItemID: 1))
        XCTAssertThrowsError(try RecordPayloadDocument(payload: .custom(), localCustomItemID: 0))
    }

    func testDefensiveParserLimitsPreserveRawRatherThanRepair() throws {
        let deep = "{\"future\":" + String(repeating: "[", count: 65) + "0" + String(repeating: "]", count: 65) + "}"
        for raw in [deep, #"{"body":"\uD800"}"#] {
            let doc = decode(.diary, raw)
            XCTAssertNil(doc.payload)
            XCTAssertEqual(try RecordPayloadCodec.encode(doc), raw)
        }
    }

    func testClosedSchemaSeparatesLocalAndPortableKeys() {
        let custom = RecordPayloadCodec.payloadKeySchema(.custom)
        XCTAssertEqual(custom.required, ["title"])
        XCTAssertEqual(custom.optional, ["detail", "icon_slot"])
        XCTAssertEqual(custom.localOnly, ["custom_item_id"])
        XCTAssertFalse(custom.portable.contains("custom_item_id"))
        XCTAssertEqual(RecordPayloadCodec.payloadKeySchema(.formula).optional, ["prepared_ml", "duration_min"])
    }

    func testTypedOptionalFieldsAndEscapedTextRoundtrip() throws {
        let payloads: [RecordPayload] = [
            .nursing(leftMinutes: 3, rightMinutes: 4, order: "RL", amountMl: 50, recordMode: "start"),
            .milk(kind: .formula, amountMl: 120, preparedMl: 150, durationMinutes: 10),
            .symptom(kind: .rash, severity: 2, description: "a/b\n\"中文\""),
            .medicine(name: "药", dose: "1"), .hospital(reason: "复诊", advice: "休息"),
            .food(kind: .drink, content: "水", amount: "几口"), .vaccine(name: "疫苗", batch: "A"),
        ]
        for payload in payloads {
            let raw = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
            XCTAssertEqual(decode(payload.type, raw).payload, payload)
        }
    }
}
