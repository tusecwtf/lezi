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
        let savedNote = "ui-saved"
        let editedNote = "ui-saved-edited"
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
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        // Collapse the creation form (and its keyboard) before accessing record history.
        newBaby.tap()
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        // An untouched sheet can be dismissed by dragging its non-interactive
        // header. The same gesture below must not discard an edited draft.
        newPee.tap()
        swipeEditorDown(in: app)
        waitFor(noteField(in: app), "exists == false")
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        assertEmptyHistory(app)

        newPee.tap()
        enterNote(cancelledNote, in: app)
        swipeEditorDown(in: app)
        waitFor(noteField(in: app), "exists == true AND hittable == true AND value == %@", cancelledNote)
        XCTAssertTrue(app.buttons["confirmation.save"].exists)
        let cancel = app.buttons["取消"]
        waitFor(cancel, "exists == true AND hittable == true AND enabled == true")
        cancel.tap()
        // Cancelling a dirty editor must ask before discarding. Keeping it preserves
        // the draft; only the second explicit discard returns to empty history.
        tap(app.buttons["offline.discard.keep"])
        waitFor(noteField(in: app), "value == %@", cancelledNote)
        tap(cancel)
        tap(app.buttons["offline.discard.confirm"])
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        assertEmptyHistory(app)

        // Read from SQLite again so cancellation is checked beyond an unchanged UI snapshot.
        app.buttons["offline.records.reload"].tap()
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
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
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        assertOneSavedRecord(app, note: savedNote)
        XCTAssertFalse(app.staticTexts[cancelledNote].exists)

        app.terminate()
        app.launch()
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        assertSelectedBaby(app, name: babyName)
        assertOneSavedRecord(app, note: savedNote)
        XCTAssertFalse(app.staticTexts[cancelledNote].exists)
        XCTAssertFalse(app.buttons["confirmation.save"].exists)

        tap(app.buttons["offline.record.edit"])
        waitFor(noteField(in: app), "value == %@", savedNote)
        tap(app.buttons["offline.pee.amount"])
        tap(app.buttons["offline.pee.amount.3"])
        // The short synthetic note fits on one line. Tapping the trailing edge
        // puts the insertion point after it without locale-dependent edit menus.
        let note = noteField(in: app)
        waitFor(note, "exists == true AND hittable == true")
        note.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        note.typeText("-edited")
        waitFor(note, "value == %@", editedNote)
        tap(app.buttons["confirmation.save"])
        waitFor(result, "label == %@", "已保存到本机：\(babyName)的尿记录")
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        assertOneSavedRecord(app, note: editedNote, amount: "大")

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertOneSavedRecord(app, note: editedNote, amount: "大")
        XCTAssertFalse(app.staticTexts[savedNote].exists)

        tap(app.buttons["offline.record.edit"])
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.cancel"])
        waitFor(noteField(in: app), "value == %@", editedNote)
        tap(app.buttons["offline.editor.close"])
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        tap(app.buttons["offline.records.reload"])
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        assertOneSavedRecord(app, note: editedNote, amount: "大")

        tap(app.buttons["offline.record.edit"])
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.confirm"])
        waitFor(result, "label == %@", "已删除本机尿记录")
        waitFor(newPee, "exists == true AND enabled == true AND hittable == true")
        assertEmptyHistory(app)
        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)
        XCTAssertFalse(app.staticTexts[editedNote].exists)
        XCTAssertFalse(app.buttons["offline.record.edit"].exists)
        XCTAssertFalse(app.buttons["offline.delete.request"].exists)
    }

    private func swipeEditorDown(in app: XCUIApplication) {
        // Start on the dedicated, non-interactive header rather than a field,
        // picker, button, or scroll view. Derive the drag from actual UI geometry.
        let header = app.descendants(matching: .any)
            .matching(identifier: "offline.editor.dragRegion").firstMatch
        waitFor(header, "exists == true AND hittable == true")
        let start = header.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9))
        start.press(forDuration: 0.1, thenDragTo: end)
    }

    private func tap(_ element: XCUIElement) {
        waitFor(element, "exists == true AND hittable == true AND enabled == true")
        element.tap()
    }

    private func relaunch(_ app: XCUIApplication) {
        app.terminate()
        app.launch()
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
    }

    private func noteField(in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(identifier: "offline.pee.note").firstMatch
    }

    private func enterNote(_ note: String, in app: XCUIApplication) {
        // SwiftUI's vertically growing TextField is exposed as a text view on iOS.
        // Query by ID across element types to avoid coupling to that implementation detail.
        let field = noteField(in: app)
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
        waitFor(app.staticTexts["offline.records.empty"], "label == %@", "尚无本机记录")
        XCTAssertEqual(app.staticTexts.matching(identifier: "offline.record.amount").count, 0)
        XCTAssertEqual(app.staticTexts.matching(identifier: "offline.record.note").count, 0)
    }

    private func assertOneSavedRecord(_ app: XCUIApplication, note: String, amount: String = "中") {
        let notes = app.staticTexts.matching(identifier: "offline.record.note")
        waitFor(notes.firstMatch, "exists == true AND label == %@", note)
        XCTAssertEqual(notes.count, 1)
        let amounts = app.staticTexts.matching(identifier: "offline.record.amount")
        XCTAssertEqual(amounts.count, 1)
        waitFor(amounts.firstMatch, "label == %@", "尿量：\(amount)")
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
