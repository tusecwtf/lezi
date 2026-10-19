import XCTest
@testable import LeziCore

final class AndroidTextTests: XCTestCase {
    func testKotlinWhitespaceIncludesSeparatorsButNotNELOrNonBMP() {
        let whitespace = ["", " \t\n\r\u{000B}\u{000C}", "\u{001C}\u{001D}\u{001E}\u{001F}",
                          "\u{00A0}\u{1680}\u{2000}\u{2007}\u{202F}\u{205F}\u{3000}", "\u{2028}\u{2029}"]
        for text in whitespace {
            XCTAssertTrue(AndroidText.isBlank(text))
            XCTAssertEqual(AndroidText.trimmed(text), "")
            XCTAssertEqual(AndroidText.trimmedStart(text), "")
        }
        for text in ["\u{0085}", "\u{200B}", "\u{FEFF}", "👶", "𠀀", "\u{0301}"] {
            XCTAssertFalse(AndroidText.isBlank(text))
            XCTAssertEqual(AndroidText.trimmed("\u{001C} \t" + text + "\u{2029}"), text)
            XCTAssertEqual(AndroidText.trimmedStart("\u{001C} " + text + "\t"), text + "\t")
        }
    }

    func testSharedPredicateMatchesKotlinJVMCharacterCategoriesAcrossBMP() {
        // Kotlin JVM: Character.isWhitespace(char) || Character.isSpaceChar(char).
        // Combined set: Zs/Zl/Zp plus TAB..CR and U+001C..U+001F.
        for codePoint in UInt32(0)...0xFFFF {
            guard let scalar = Unicode.Scalar(codePoint) else { continue }
            let expected = (9...13).contains(codePoint) || (28...31).contains(codePoint)
                || [.spaceSeparator, .lineSeparator, .paragraphSeparator].contains(scalar.properties.generalCategory)
            XCTAssertEqual(AndroidText.isBlank(String(scalar)), expected, "U+\(String(codePoint, radix: 16))")
            let wrapped = String(scalar) + "x" + String(scalar)
            XCTAssertEqual(trimAndroidWireString(wrapped), expected ? "x" : wrapped)

        }
    }

    func testWireTrimRetainsExistingSemanticsThroughSharedImplementation() {
        for value in [" \tvalue\n", "\u{0085}value\u{0085}", "\u{001C}value\u{001F}",
                      "\u{00A0}👶\u{2029}", "value\u{2007}inside", ""] {
            XCTAssertEqual(trimAndroidWireString(value), AndroidText.trimmed(value))
        }
        XCTAssertEqual(trimAndroidWireString("\u{0085}"), "\u{0085}")
        XCTAssertEqual(trimAndroidWireString("\u{001C}family\u{001F}"), "family")
    }

    func testAllRequiredPayloadTextUsesKotlinBlankRulesWithoutChangingBody() throws {
        for (text, blank) in [("\u{0085}", false), ("👶", false), ("𠀀", false),
                              ("\u{001C}\u{001F}", true), ("\u{00A0}\u{2028}\u{2029}", true), (" \t\n", true)] {
            let payloads: [RecordPayload] = [.diary(body: text), .medicine(name: text), .hospital(reason: text),
                .food(kind: .babyFood, content: text), .food(kind: .snack, content: text), .food(kind: .drink, content: text),
                .vaccine(name: text), .custom(titleSnapshot: text)]
            for payload in payloads {
                XCTAssertEqual(payload.validationErrors().isEmpty, !blank, "\(payload.type)")
                let raw = try RecordPayloadCodec.encode(RecordPayloadDocument(payload: payload))
                XCTAssertEqual(RecordPayloadCodec.decode(type: payload.type, payloadJSON: raw, schemaVersion: 2).payload, payload)
            }
        }
    }

    func testNursingOptionalAmountAndNextFeedNotesUseSameBlankDefinition() {
        XCTAssertEqual(NursingConfirmInput(leftMinutes: "5", rightMinutes: "0", order: "L", amountMl: "\u{0085}").validationIssue()?.field, .amount)
        XCTAssertNil(NursingConfirmInput(leftMinutes: "5", rightMinutes: "0", order: "L", amountMl: "\u{001C}\u{001F}").validationIssue())
        let marker = NextFeedPlanNote.marker
        XCTAssertEqual(NextFeedPlanNote.encode(" \u{0085}\t"), marker + " \u{0085}")
        XCTAssertEqual(NextFeedPlanNote.visible(marker + " \u{0085}\t"), "\u{0085}\t")
        XCTAssertEqual(NextFeedPlanNote.encode("\u{001C}\u{001F}"), marker)
        XCTAssertNil(NextFeedPlanNote.visible(marker + "\u{001C}\u{001F}"))
    }
    func testFoodNormalizationAndParsingUseKotlinTrim() {
        XCTAssertEqual(FoodAmount.normalize("\u{0085}半碗\u{0085}"), "\u{0085}半碗\u{0085}")
        XCTAssertEqual(FoodAmount.normalize("\u{001C}半碗\u{001F}"), "0.5碗")
        XCTAssertNil(FoodAmount.parseValue("\u{0085}2.5勺"))
        XCTAssertEqual(FoodAmount.parseValue("\u{001C}2.5勺\u{001F}"), 2.5)
    }

    func testCustomUUIDNormalizationUseKotlinTrim() {
        let uuid = "abcdefab-1234-1234-1234-abcdefabcdef"
        XCTAssertNil(RecordItemIdentity.parseCatalogKey("custom:\u{0085}" + uuid + "\u{0085}"))
        XCTAssertEqual(RecordItemIdentity.parseCatalogKey("custom:\u{001C}" + uuid + "\u{001F}")?.familyCustomUUID, uuid)
    }

    func testEffectiveWakeSelectionUsesKotlinTrim() {
        let observations: [WakeObservationFact] = [
            .init(clientUUID: "first", wakeTimestamp: 100),
            .init(clientUUID: "chosen", wakeTimestamp: 200),
        ]
        let nel = SleepProjection.project(sleepClientUUID: "sleep", startTimestamp: 50,
            effectiveWakeObservationClientUUID: "\u{0085}chosen\u{0085}", observations: observations)
        XCTAssertEqual(nel.endObservationClientUUID, "first")
        XCTAssertEqual(nel.endSource, .provisional)
        let separator = SleepProjection.project(sleepClientUUID: "sleep", startTimestamp: 50,
            effectiveWakeObservationClientUUID: "\u{001C}chosen\u{001F}", observations: observations)
        XCTAssertEqual(separator.endObservationClientUUID, "chosen")
        XCTAssertEqual(separator.endSource, .effective)
    }

}
