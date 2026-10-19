import XCTest

/// Run only on a fresh, ephemeral development Simulator sandbox with synthetic data.
/// No reset flags, database injection, network access, or production data are used.
@MainActor
final class OfflinePeeAcceptanceTests: XCTestCase {
    func testCancelConfirmAndRelaunchPersistsExactlyOnePee() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Offline-001"
        let cancelledNote = "synthetic-cancelled-draft"
        let savedNote = "synthetic-confirmed-record"
        app.launch()

        let newBaby = app.buttons["offline.baby.new"]
        waitFor(newBaby, "exists == true AND hittable == true")
        newBaby.tap()
        let nickname = app.textFields["offline.baby.nickname"]
        waitFor(nickname, "exists == true AND hittable == true")
        nickname.tap()
        nickname.typeText(babyName)
        app.buttons["offline.baby.create"].tap()

        let result = app.staticTexts["offline.result"]
        waitFor(result, "label == %@", "已在本机创建宝宝：\(babyName)")
        let newPee = app.buttons["offline.pee.new"]
        waitFor(newPee, "exists == true AND enabled == true")
        // Collapse the creation form (and its keyboard) before accessing record history.
        newBaby.tap()
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        newPee.tap()
        enterNote(cancelledNote, in: app)
        let cancel = app.buttons["取消"]
        waitFor(cancel, "exists == true AND hittable == true AND enabled == true")
        cancel.tap()
        waitFor(newPee, "exists == true AND enabled == true")
        assertEmptyHistory(app)

        // Read from SQLite again so cancellation is checked beyond an unchanged UI snapshot.
        app.buttons["offline.records.reload"].tap()
        waitFor(newPee, "exists == true AND enabled == true")
        assertEmptyHistory(app)
        XCTAssertFalse(app.staticTexts[cancelledNote].exists)

        newPee.tap()
        enterNote(savedNote, in: app)
        let confirm = app.buttons["confirmation.save"]
        waitFor(confirm, "exists == true AND hittable == true AND enabled == true")
        confirm.tap()
        // This message is emitted only after confirmPee returns from SQLite COMMIT.
        // Also await the post-save readback before terminating: dispatch is not completion.
        waitFor(result, "label == %@", "已保存到本机：\(babyName)的尿记录")
        waitFor(newPee, "exists == true AND enabled == true")
        assertOneSavedRecord(app, note: savedNote)
        XCTAssertFalse(app.staticTexts[cancelledNote].exists)

        app.terminate()
        app.launch()
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true")
        assertSelectedBaby(app, name: babyName)
        assertOneSavedRecord(app, note: savedNote)
        XCTAssertFalse(app.staticTexts[cancelledNote].exists)
        XCTAssertFalse(app.buttons["confirmation.save"].exists)
    }

    private func enterNote(_ note: String, in app: XCUIApplication) {
        // SwiftUI's vertically growing TextField is exposed as a text view on iOS.
        // Query by ID across element types to avoid coupling to that implementation detail.
        let field = app.descendants(matching: .any)
            .matching(identifier: "offline.pee.note").firstMatch
        waitFor(field, "exists == true AND hittable == true")
        field.tap()
        field.typeText(note)
        waitFor(field, "value == %@", note)
    }

    private func assertSelectedBaby(_ app: XCUIApplication, name: String) {
        let selection = app.descendants(matching: .any)
            .matching(identifier: "offline.baby.selection").firstMatch
        waitFor(selection, "exists == true AND (label CONTAINS %@ OR value CONTAINS %@)", name, name)
    }

    private func assertEmptyHistory(_ app: XCUIApplication) {
        waitFor(app.staticTexts["offline.records.empty"], "label == %@", "尚无本机尿记录")
        XCTAssertEqual(app.staticTexts.matching(identifier: "offline.record.amount").count, 0)
        XCTAssertEqual(app.staticTexts.matching(identifier: "offline.record.note").count, 0)
    }

    private func assertOneSavedRecord(_ app: XCUIApplication, note: String) {
        let notes = app.staticTexts.matching(identifier: "offline.record.note")
        waitFor(notes.firstMatch, "exists == true AND label == %@", note)
        XCTAssertEqual(notes.count, 1)
        XCTAssertEqual(app.staticTexts.matching(identifier: "offline.record.amount").count, 1)
        XCTAssertFalse(app.staticTexts["offline.records.empty"].exists)
        XCTAssertTrue(notes.firstMatch.isHittable, "The persisted record must be visible")
    }

    private func waitFor(_ object: Any, _ format: String, _ arguments: CVarArg...,
                         file: StaticString = #filePath, line: UInt = #line) {
        let predicate = NSPredicate(format: format, argumentArray: arguments)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: object)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 15), .completed,
                       "Timed out waiting for \(format)", file: file, line: line)
    }
}
