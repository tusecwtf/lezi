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

    func testPoopAndCombinedDiaperRetainKindsAndIndependentRecords() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Diaper-002"
        let poopNote = "ui-poop"
        let bothNote = "ui-both"
        app.launch()
        tap(app.buttons["offline.baby.new"])
        let nickname = app.textFields["offline.baby.nickname"]
        tap(nickname)
        nickname.typeText(babyName)
        tap(app.buttons["offline.baby.create"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已在本机创建宝宝：\(babyName)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        tap(app.buttons["offline.baby.new"])
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        openDiaper(kind: "poop", in: app)
        chooseDiaperOption("offline.stool.amount", value: 1, in: app)
        chooseDiaperOption("offline.stool.consistency", value: 1, in: app)
        chooseDiaperOption("offline.stool.color", value: 1, in: app)
        revealDiaperControl(noteField(in: app), in: app)
        enterNote(poopNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "便记录")
        let poopRow = diaperRow(in: app, note: poopNote)
        waitFor(poopRow, "exists == true")
        let poopRowID = poopRow.identifier
        assertDiaperRow(poopRow, kind: "便记录", amount: "一点", consistency: "稀", color: "白")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        let persistedPoop = app.otherElements[poopRowID]
        assertDiaperRow(persistedPoop, kind: "便记录", amount: "一点", consistency: "稀", color: "白")
        tap(persistedPoop.buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：便记录（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        chooseDiaperOption("offline.stool.amount", value: 4, in: app)
        chooseDiaperOption("offline.stool.consistency", value: 4, in: app)
        chooseDiaperOption("offline.stool.color", value: 6, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "便记录")
        assertDiaperRow(app.otherElements[poopRowID], kind: "便记录", amount: "偏多", consistency: "偏硬", color: "红")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        openDiaper(kind: "both_diaper", in: app)
        chooseDiaperOption("offline.pee.amount", value: 3, in: app)
        chooseDiaperOption("offline.stool.amount", value: 2, in: app)
        chooseDiaperOption("offline.stool.consistency", value: 2, in: app)
        chooseDiaperOption("offline.stool.color", value: 3, in: app)
        revealDiaperControl(noteField(in: app), in: app)
        enterNote(bothNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "尿+便记录")
        let bothRow = diaperRow(in: app, note: bothNote)
        waitFor(bothRow, "exists == true")
        let bothRowID = bothRow.identifier
        XCTAssertNotEqual(bothRowID, poopRowID)
        assertDiaperRow(bothRow, kind: "尿+便记录", amount: "偏少", consistency: "偏软", color: "橙", pee: "大")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertDiaperRow(app.otherElements[poopRowID], kind: "便记录", amount: "偏多", consistency: "偏硬", color: "红")
        assertDiaperRow(app.otherElements[bothRowID], kind: "尿+便记录", amount: "偏少", consistency: "偏软", color: "橙", pee: "大")
        waitFor(app.otherElements[poopRowID].staticTexts["offline.record.note"], "label == %@", poopNote)
        waitFor(app.otherElements[bothRowID].staticTexts["offline.record.note"], "label == %@", bothNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)
        tap(app.otherElements[bothRowID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：尿+便记录（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        revealDiaperControl(app.buttons["offline.delete.request"], in: app)
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.confirm"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已删除本机尿+便记录")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        waitFor(app.otherElements[bothRowID], "exists == false")
        assertDiaperRow(app.otherElements[poopRowID], kind: "便记录", amount: "偏多", consistency: "偏硬", color: "红")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        XCTAssertFalse(app.otherElements[bothRowID].exists)
        XCTAssertFalse(app.staticTexts[bothNote].exists)
        assertDiaperRow(app.otherElements[poopRowID], kind: "便记录", amount: "偏多", consistency: "偏硬", color: "红")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)
    }

    func testDiaryBodyAndPointEventsPersistWithoutCrossRecordChanges() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Daily-003"
        let diaryNote = "ui-diary-note"
        let diaryBody = "ui-body"
        let editedBody = "ui-body-edited"
        let bathNote = "ui-bath"
        let walkNote = "ui-walk"
        app.launch()
        tap(app.buttons["offline.baby.new"])
        let nickname = app.textFields["offline.baby.nickname"]
        tap(nickname)
        nickname.typeText(babyName)
        tap(app.buttons["offline.baby.create"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已在本机创建宝宝：\(babyName)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        tap(app.buttons["offline.baby.new"])
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        openDiaper(kind: "diary", in: app)
        revealDiaperControl(noteField(in: app), in: app)
        enterNote(diaryNote, in: app)
        tap(app.buttons["confirmation.save"])
        // A note is metadata, not a substitute for the required diary body.
        waitFor(app.staticTexts["confirmation.reason"], "label == %@", "正文不能为空")
        waitFor(noteField(in: app), "value == %@", diaryNote)
        XCTAssertTrue(app.buttons["confirmation.save"].exists)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 0)
        let body = app.descendants(matching: .any)
            .matching(identifier: "offline.diary.body").firstMatch
        tap(body)
        body.typeText(diaryBody)
        waitFor(body, "value == %@", diaryBody)
        saveDiaper(in: app, babyName: babyName, kindLabel: "日记")
        let diaryRow = diaperRow(in: app, note: diaryNote)
        waitFor(diaryRow, "exists == true")
        let diaryID = diaryRow.identifier
        assertDiaryRow(diaryRow, body: diaryBody, note: diaryNote)

        tap(diaryRow.buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：日记（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        waitFor(body, "value == %@", diaryBody)
        waitFor(body, "exists == true AND hittable == true")
        // The body reserves four lines. Target the trailing edge of the first
        // line containing this short value, not the blank vertical midpoint.
        body.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.1)).tap()
        body.typeText("-edited")
        waitFor(body, "value == %@", editedBody)
        waitFor(noteField(in: app), "value == %@", diaryNote)
        saveDiaper(in: app, babyName: babyName, kindLabel: "日记")
        assertDiaryRow(app.otherElements[diaryID], body: editedBody, note: diaryNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        openDiaper(kind: "bath", in: app)
        assertPointEditor(app)
        enterNote(bathNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "洗澡记录")
        let bathRow = diaperRow(in: app, note: bathNote)
        waitFor(bathRow, "exists == true")
        let bathID = bathRow.identifier
        assertPointRow(bathRow, kind: "洗澡记录", note: bathNote)

        openDiaper(kind: "walk", in: app)
        assertPointEditor(app)
        enterNote(walkNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "散步记录")
        let walkRow = diaperRow(in: app, note: walkNote)
        waitFor(walkRow, "exists == true")
        let walkID = walkRow.identifier
        XCTAssertEqual(Set([diaryID, bathID, walkID]).count, 3)
        assertPointRow(walkRow, kind: "散步记录", note: walkNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 3)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertDiaryRow(app.otherElements[diaryID], body: editedBody, note: diaryNote)
        assertPointRow(app.otherElements[bathID], kind: "洗澡记录", note: bathNote)
        assertPointRow(app.otherElements[walkID], kind: "散步记录", note: walkNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 3)
        tap(app.otherElements[walkID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：散步记录（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        assertPointEditor(app)
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.confirm"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已删除本机散步记录")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        waitFor(app.otherElements[walkID], "exists == false")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertDiaryRow(app.otherElements[diaryID], body: editedBody, note: diaryNote)
        assertPointRow(app.otherElements[bathID], kind: "洗澡记录", note: bathNote)
        XCTAssertFalse(app.otherElements[walkID].exists)
        XCTAssertFalse(app.staticTexts[walkNote].exists)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)
    }

    func testHealthFormsAndSymptomEditsPersistWithDeletionIsolation() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Health-004"
        let forms = [
            HealthForm(kind: "medicine", label: "用药记录", primary: "name", secondary: "dose",
                       primaryValue: "synthetic-med", secondaryValue: "synthetic-dose-text",
                       primaryCaption: "药品名称", secondaryCaption: "剂量", requiredMessage: "药品名称不能为空"),
            HealthForm(kind: "hospital", label: "就医记录", primary: "reason", secondary: "advice",
                       primaryValue: "synthetic-reason", secondaryValue: "synthetic-advice",
                       primaryCaption: "就诊原因", secondaryCaption: "医嘱", requiredMessage: "就诊原因不能为空"),
            HealthForm(kind: "vaccine", label: "疫苗记录", primary: "name", secondary: "batch",
                       primaryValue: "synthetic-vaccine", secondaryValue: "synthetic-batch",
                       primaryCaption: "疫苗名称", secondaryCaption: "批次 / 针次", requiredMessage: "疫苗名称不能为空"),
        ]
        var rowIDs: [String: String] = [:]
        app.launch()
        tap(app.buttons["offline.baby.new"])
        let nickname = app.textFields["offline.baby.nickname"]
        tap(nickname)
        nickname.typeText(babyName)
        tap(app.buttons["offline.baby.create"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已在本机创建宝宝：\(babyName)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        tap(app.buttons["offline.baby.new"])
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        for (index, form) in forms.enumerated() {
            openDiaper(kind: form.kind, in: app)
            enterHealthText("offline.health.\(form.kind).\(form.secondary)", text: form.secondaryValue, in: app)
            revealHealthControl(noteField(in: app), in: app)
            enterNote(form.note, in: app)
            tap(app.buttons["confirmation.save"])
            // Optional fields and a note cannot substitute for this kind's required field.
            waitFor(app.staticTexts["confirmation.reason"], "label == %@", form.requiredMessage)
            waitFor(noteField(in: app), "value == %@", form.note)
            let primary = healthField("offline.health.\(form.kind).\(form.primary)", in: app)
            tap(primary) // The validation action focuses and reveals the required field.
            primary.typeText(form.primaryValue)
            waitFor(primary, "value == %@", form.primaryValue)
            saveDiaper(in: app, babyName: babyName, kindLabel: form.label)
            let row = diaperRow(in: app, note: form.note)
            waitFor(row, "exists == true")
            rowIDs[form.kind] = row.identifier
            assertHealthRow(row, form: form)
            XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, index + 1)
        }

        // The four symptom kinds share portable matrix coverage. Exercise one
        // complete representative UI lifecycle here instead of repeating restarts.
        let symptomNote = "ui-cough-note"
        let description = "ui-symptom"
        let editedDescription = "ui-symptom-edited"
        openDiaper(kind: "cough", in: app)
        chooseDiaperOption("offline.health.severity", value: 3, in: app)
        enterHealthText("offline.health.description", text: description, in: app)
        revealHealthControl(noteField(in: app), in: app)
        enterNote(symptomNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "咳嗽记录")
        let symptomRow = diaperRow(in: app, note: symptomNote)
        waitFor(symptomRow, "exists == true")
        let symptomID = symptomRow.identifier
        assertSymptomRow(symptomRow, severity: "明显", description: description, note: symptomNote)
        tap(symptomRow.buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：咳嗽记录（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        chooseDiaperOption("offline.health.severity", value: 1, in: app)
        let symptomDescription = healthField("offline.health.description", in: app)
        revealHealthControl(symptomDescription, in: app)
        waitFor(symptomDescription, "value == %@", description)
        symptomDescription.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.1)).tap()
        symptomDescription.typeText("-edited")
        waitFor(symptomDescription, "value == %@", editedDescription)
        waitFor(noteField(in: app), "value == %@", symptomNote)
        saveDiaper(in: app, babyName: babyName, kindLabel: "咳嗽记录")
        assertSymptomRow(app.otherElements[symptomID], severity: "轻微", description: editedDescription, note: symptomNote)
        XCTAssertEqual(Set(rowIDs.values).union([symptomID]).count, 4)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 4)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        for form in forms {
            assertHealthRow(app.otherElements[rowIDs[form.kind]!], form: form)
        }
        assertSymptomRow(app.otherElements[symptomID], severity: "轻微", description: editedDescription, note: symptomNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 4)
        tap(app.otherElements[symptomID].buttons["offline.record.edit"])
        revealHealthControl(app.buttons["offline.delete.request"], in: app)
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.confirm"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已删除本机咳嗽记录")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        waitFor(app.otherElements[symptomID], "exists == false")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 3)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        for form in forms {
            assertHealthRow(app.otherElements[rowIDs[form.kind]!], form: form)
        }
        XCTAssertFalse(app.otherElements[symptomID].exists)
        XCTAssertFalse(app.staticTexts[symptomNote].exists)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 3)
    }

    func testGrowthValuesPersistInDisplayUnitsWithTypeLockedEdits() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Growth-005"
        let weightNote = "ui-weight-note"
        let heightNote = "ui-height-note"
        app.launch()
        tap(app.buttons["offline.baby.new"])
        let nickname = app.textFields["offline.baby.nickname"]
        tap(nickname)
        nickname.typeText(babyName)
        tap(app.buttons["offline.baby.create"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已在本机创建宝宝：\(babyName)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        tap(app.buttons["offline.baby.new"])
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        openDiaper(kind: "weight", in: app)
        let measurement = healthField("offline.growth.value", in: app)
        enterHealthText("offline.growth.value", text: "0", in: app)
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["confirmation.reason"], "label == %@", "请填写有效数值")
        waitFor(measurement, "value == %@", "0")
        // Invalid input must remain in the editor rather than saving a default.
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "exists == false")
        replaceGrowthValue("0", with: "12.345", in: app)
        revealHealthControl(noteField(in: app), in: app)
        enterNote(weightNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "体重")
        let weightRow = diaperRow(in: app, note: weightNote)
        waitFor(weightRow, "exists == true")
        let weightID = weightRow.identifier
        assertGrowthRow(weightRow, kind: "体重", value: "12.345 kg", note: weightNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertGrowthRow(app.otherElements[weightID], kind: "体重", value: "12.345 kg", note: weightNote)
        tap(app.otherElements[weightID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：体重（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        replaceGrowthValue("12.345", with: "12.678", in: app)
        waitFor(noteField(in: app), "value == %@", weightNote)
        saveDiaper(in: app, babyName: babyName, kindLabel: "体重")
        assertGrowthRow(app.otherElements[weightID], kind: "体重", value: "12.678 kg", note: weightNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        openDiaper(kind: "height", in: app)
        enterHealthText("offline.growth.value", text: "87.6", in: app)
        revealHealthControl(noteField(in: app), in: app)
        enterNote(heightNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "身高")
        let heightRow = diaperRow(in: app, note: heightNote)
        waitFor(heightRow, "exists == true")
        let heightID = heightRow.identifier
        XCTAssertNotEqual(weightID, heightID)
        assertGrowthRow(heightRow, kind: "身高", value: "87.6 cm", note: heightNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertGrowthRow(app.otherElements[weightID], kind: "体重", value: "12.678 kg", note: weightNote)
        assertGrowthRow(app.otherElements[heightID], kind: "身高", value: "87.6 cm", note: heightNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)
        tap(app.otherElements[weightID].buttons["offline.record.edit"])
        waitFor(measurement, "value == %@", "12.678")
        revealHealthControl(app.buttons["offline.delete.request"], in: app)
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.confirm"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已删除本机体重")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        waitFor(app.otherElements[weightID], "exists == false")
        assertGrowthRow(app.otherElements[heightID], kind: "身高", value: "87.6 cm", note: heightNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        XCTAssertFalse(app.otherElements[weightID].exists)
        XCTAssertFalse(app.staticTexts[weightNote].exists)
        assertGrowthRow(app.otherElements[heightID], kind: "身高", value: "87.6 cm", note: heightNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)
    }

    private func replaceGrowthValue(_ oldValue: String, with newValue: String, in app: XCUIApplication) {
        let field = healthField("offline.growth.value", in: app)
        revealHealthControl(field, in: app)
        waitFor(field, "value == %@", oldValue)
        // A single-line field tapped at its trailing edge places the caret after
        // the checked value; delete exactly that value, then verify replacement.
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: oldValue.count) + newValue)
        waitFor(field, "value == %@", newValue)
    }

    private func assertGrowthRow(_ row: XCUIElement, kind: String, value: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", kind)
        waitFor(row.staticTexts["offline.record.growth.value"], "label == %@", value)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    func testMilkFactsRecentAmountsAndLocalStepPersistIndependently() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Milk-006"
        let formulaNote = "ui-formula-note"
        let bottleNote = "ui-pumped-feed-note"
        app.launch()
        tap(app.buttons["offline.baby.new"])
        let nickname = app.textFields["offline.baby.nickname"]
        tap(nickname)
        nickname.typeText(babyName)
        tap(app.buttons["offline.baby.create"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已在本机创建宝宝：\(babyName)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        tap(app.buttons["offline.baby.new"])
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        openDiaper(kind: "formula", in: app)
        revealHealthControl(app.buttons["offline.milk.step"], in: app)
        tap(app.buttons["offline.milk.step"])
        tap(app.buttons["offline.milk.step.10"])
        replaceMilkAmount("120", with: "0", in: app)
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["confirmation.reason"], "label == %@", "奶量需在 1–999 ml 之间")
        waitFor(healthField("offline.milk.amount", in: app), "value == %@", "0")
        replaceMilkAmount("0", with: "147", in: app)
        enterHealthText("offline.milk.prepared", text: "180", in: app)
        enterHealthText("offline.milk.duration", text: "10", in: app)
        revealHealthControl(noteField(in: app), in: app)
        enterNote(formulaNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "配方奶")
        let formulaRow = diaperRow(in: app, note: formulaNote)
        waitFor(formulaRow, "exists == true")
        let formulaID = formulaRow.identifier
        assertMilkRow(formulaRow, kind: "配方奶", amount: 147, note: formulaNote, prepared: 180, duration: 10)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertMilkRow(app.otherElements[formulaID], kind: "配方奶", amount: 147, note: formulaNote, prepared: 180, duration: 10)
        tap(app.otherElements[formulaID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：配方奶（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        waitFor(healthField("offline.milk.amount", in: app), "value == %@", "147")
        // The local 10 ml preference survives relaunch and drives real adjustment.
        revealHealthControl(app.buttons["offline.milk.plus"], in: app)
        waitFor(app.buttons["offline.milk.plus"], "label == %@", "+10")
        tap(app.buttons["offline.milk.plus"])
        waitFor(healthField("offline.milk.amount", in: app), "value == %@", "157")
        waitFor(healthField("offline.milk.prepared", in: app), "value == %@", "180")
        waitFor(healthField("offline.milk.duration", in: app), "value == %@", "10")
        saveDiaper(in: app, babyName: babyName, kindLabel: "配方奶")
        assertMilkRow(app.otherElements[formulaID], kind: "配方奶", amount: 157, note: formulaNote, prepared: 180, duration: 10)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        openDiaper(kind: "formula", in: app)
        waitFor(healthField("offline.milk.amount", in: app), "value == %@", "157")
        // Move far away from the recent value: 157 cannot be a 10 ml neighbor
        // of 300, so this shortcut must come from the saved formula fact.
        replaceMilkAmount("157", with: "300", in: app)
        revealHealthControl(app.buttons["offline.milk.quick.157"], in: app)
        tap(app.buttons["offline.milk.quick.157"])
        waitFor(healthField("offline.milk.amount", in: app), "value == %@", "157")
        enterHealthText("offline.milk.prepared", text: "190", in: app)
        enterHealthText("offline.milk.duration", text: "11", in: app)
        revealHealthControl(app.buttons["offline.excretion.kind"], in: app)
        tap(app.buttons["offline.excretion.kind"])
        tap(app.buttons["offline.excretion.kind.pumped_feed"])
        XCTAssertFalse(healthField("offline.milk.prepared", in: app).exists)
        XCTAssertFalse(healthField("offline.milk.duration", in: app).exists)
        waitFor(healthField("offline.milk.amount", in: app), "value == %@", "120")
        XCTAssertFalse(app.buttons["offline.milk.quick.157"].exists)
        revealHealthControl(app.buttons["offline.milk.plus"], in: app)
        tap(app.buttons["offline.milk.plus"])
        waitFor(healthField("offline.milk.amount", in: app), "value == %@", "130")
        revealHealthControl(noteField(in: app), in: app)
        enterNote(bottleNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "母乳瓶喂")
        let bottleRow = diaperRow(in: app, note: bottleNote)
        waitFor(bottleRow, "exists == true")
        let bottleID = bottleRow.identifier
        XCTAssertNotEqual(formulaID, bottleID)
        assertMilkRow(bottleRow, kind: "母乳瓶喂", amount: 130, note: bottleNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertMilkRow(app.otherElements[formulaID], kind: "配方奶", amount: 157, note: formulaNote, prepared: 180, duration: 10)
        assertMilkRow(app.otherElements[bottleID], kind: "母乳瓶喂", amount: 130, note: bottleNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)
        tap(app.otherElements[bottleID].buttons["offline.record.edit"])
        revealHealthControl(app.buttons["offline.delete.request"], in: app)
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.confirm"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已删除本机母乳瓶喂")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        waitFor(app.otherElements[bottleID], "exists == false")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        XCTAssertFalse(app.otherElements[bottleID].exists)
        assertMilkRow(app.otherElements[formulaID], kind: "配方奶", amount: 157, note: formulaNote, prepared: 180, duration: 10)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)
    }

    private func replaceMilkAmount(_ oldValue: String, with newValue: String, in app: XCUIApplication) {
        let field = healthField("offline.milk.amount", in: app)
        revealHealthControl(field, in: app)
        waitFor(field, "value == %@", oldValue)
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: oldValue.count) + newValue)
        waitFor(field, "value == %@", newValue)
    }

    private func assertMilkRow(_ row: XCUIElement, kind: String, amount: Int, note: String,
                               prepared: Int? = nil, duration: Int? = nil) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", kind)
        waitFor(row.staticTexts["offline.record.milk.amount"], "label == %@", "奶量：\(amount) ml")
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
        if let prepared {
            waitFor(row.staticTexts["offline.record.milk.prepared"], "label == %@", "冲调量：\(prepared) ml")
        } else {
            XCTAssertFalse(row.staticTexts["offline.record.milk.prepared"].exists)
        }
        if let duration {
            waitFor(row.staticTexts["offline.record.milk.duration"], "label == %@", "耗时：\(duration) 分钟")
        } else {
            XCTAssertFalse(row.staticTexts["offline.record.milk.duration"].exists)
        }
    }

    private struct HealthForm {
        let kind: String
        let label: String
        let primary: String
        let secondary: String
        let primaryValue: String
        let secondaryValue: String
        let primaryCaption: String
        let secondaryCaption: String
        let requiredMessage: String
        var note: String { "ui-\(kind)-note" }
    }

    private func healthField(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    private func enterHealthText(_ identifier: String, text: String, in app: XCUIApplication) {
        let field = healthField(identifier, in: app)
        revealHealthControl(field, in: app)
        tap(field)
        field.typeText(text)
        waitFor(field, "value == %@", text)
    }

    private func revealHealthControl(_ element: XCUIElement, in app: XCUIApplication) {
        waitFor(element, "exists == true")
        let editorScroll = app.scrollViews.containing(.any, identifier: "offline.pee.note").firstMatch
        // isHittable can be true for a field partly covered by the fixed footer.
        // Require the whole control above that footer before a single tap/type.
        // Derive both the reveal condition and drag path from the current UI;
        // the ScrollView's accessibility frame can extend behind the keyboard.
        for attempt in 0...4 {
            let viewport = editorScroll.frame
            let top = viewport.minY + 8
            var bottom = min(viewport.maxY, app.buttons["confirmation.save"].frame.minY) - 12
            let reason = app.staticTexts["confirmation.reason"]
            if reason.exists { bottom = min(bottom, reason.frame.minY - 12) }
            if app.keyboards.firstMatch.exists {
                bottom = min(bottom, app.keyboards.firstMatch.frame.minY - 12)
            }
            let field = element.frame
            print("Health reveal \(element.identifier) attempt \(attempt): field=\(field), viewport=\(viewport), safeY=\(top)...\(bottom)")
            if element.isHittable && field.minY >= top && field.maxY <= bottom { return }
            if attempt == 4 { break }
            let availableHeight = bottom - top
            guard availableHeight > 60 else {
                XCTFail("The editor has no unobscured scroll region for \(element.identifier)")
                return
            }
            let scrollUp = field.maxY > bottom
            let needed = scrollUp ? field.maxY - bottom : top - field.minY
            let distance = min(max(needed + 16, 40), availableHeight * 0.6)
            let startY = scrollUp ? bottom - 8 : top + 8
            let endY = startY + (scrollUp ? -distance : distance)
            // Start in the measured content padding, not inside a focused
            // TextField whose own gesture recognizer may consume the drag.
            let leftPadding = field.minX - viewport.minX
            let rightPadding = viewport.maxX - field.maxX
            let trackX: CGFloat
            if leftPadding >= 8 {
                trackX = viewport.minX + leftPadding / 2
            } else if rightPadding >= 8 {
                trackX = field.maxX + rightPadding / 2
            } else {
                XCTFail("No measured padding track outside \(element.identifier)")
                return
            }
            print("Health reveal drag: (\(trackX), \(startY)) -> (\(trackX), \(endY))")
            let origin = editorScroll.coordinate(withNormalizedOffset: .zero)
            let start = origin.withOffset(CGVector(dx: trackX - viewport.minX, dy: startY - viewport.minY))
            let end = origin.withOffset(CGVector(dx: trackX - viewport.minX, dy: endY - viewport.minY))
            start.press(forDuration: 0.1, thenDragTo: end)
        }
        XCTFail("Could not reveal \(element.identifier) above the confirmation footer")
    }

    private func assertHealthRow(_ row: XCUIElement, form: HealthForm) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", form.label)
        waitFor(row.staticTexts["offline.record.health.\(form.kind).\(form.primary)"], "label == %@",
                "\(form.primaryCaption)：\(form.primaryValue)")
        waitFor(row.staticTexts["offline.record.health.\(form.kind).\(form.secondary)"], "label == %@",
                "\(form.secondaryCaption)：\(form.secondaryValue)")
        waitFor(row.staticTexts["offline.record.note"], "label == %@", form.note)
    }

    private func assertSymptomRow(_ row: XCUIElement, severity: String, description: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", "咳嗽记录")
        waitFor(row.staticTexts["offline.record.health.severity"], "label == %@", "程度：\(severity)")
        waitFor(row.staticTexts["offline.record.health.description"], "label == %@", description)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    private func assertDiaryRow(_ row: XCUIElement, body: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", "日记")
        waitFor(row.staticTexts["offline.record.body"], "label == %@", body)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
        XCTAssertFalse(row.staticTexts["offline.record.amount"].exists)
        XCTAssertFalse(row.staticTexts["offline.record.stool.amount"].exists)
    }

    private func assertPointEditor(_ app: XCUIApplication) {
        waitFor(app.staticTexts["offline.editor.point"], "label == %@", "单次记录 · 无持续时长")
        XCTAssertFalse(app.buttons["offline.pee.amount"].exists)
        XCTAssertFalse(app.buttons["offline.stool.amount"].exists)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "offline.diary.body").firstMatch.exists)
    }

    private func assertPointRow(_ row: XCUIElement, kind: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", kind)
        waitFor(row.staticTexts["offline.record.point"], "label == %@", "单次记录 · 无持续时长")
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
        XCTAssertFalse(row.staticTexts["offline.record.body"].exists)
        XCTAssertFalse(row.staticTexts["offline.record.amount"].exists)
        XCTAssertFalse(row.staticTexts["offline.record.stool.amount"].exists)
    }

    private func openDiaper(kind: String, in app: XCUIApplication) {
        tap(app.buttons["offline.pee.new"])
        tap(app.buttons["offline.excretion.kind"])
        tap(app.buttons["offline.excretion.kind.\(kind)"])
    }

    private func revealDiaperControl(_ element: XCUIElement, in app: XCUIApplication) {
        waitFor(element, "exists == true")
        let editorScroll = app.scrollViews.containing(.any, identifier: "offline.pee.note").firstMatch
        // Bounded content scrolling, not a timed wait. The combined editor is
        // taller than pee-only; scope gestures to its scroll view, never the sheet header.
        for _ in 0..<4 {
            if element.isHittable { return }
            editorScroll.swipeUp()
        }
        waitFor(element, "hittable == true")
    }

    private func diaperRow(in app: XCUIApplication, note: String) -> XCUIElement {
        app.otherElements.matching(NSPredicate(format: "identifier BEGINSWITH %@", "offline.record.row."))
            .containing(.staticText, identifier: note).firstMatch
    }

    private func chooseDiaperOption(_ identifier: String, value: Int, in app: XCUIApplication) {
        revealDiaperControl(app.buttons[identifier], in: app)
        tap(app.buttons[identifier])
        tap(app.buttons["\(identifier).\(value)"])
    }

    private func saveDiaper(in app: XCUIApplication, babyName: String, kindLabel: String) {
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已保存到本机：\(babyName)的\(kindLabel)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
    }

    private func assertDiaperRow(_ row: XCUIElement, kind: String, amount: String,
                                 consistency: String, color: String, pee: String? = nil) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", kind)
        waitFor(row.staticTexts["offline.record.stool.amount"], "label == %@", "便量：\(amount)")
        waitFor(row.staticTexts["offline.record.stool.consistency"], "label == %@", "软硬：\(consistency)")
        waitFor(row.staticTexts["offline.record.stool.color"], "label == %@", "颜色：\(color)")
        if let pee {
            waitFor(row.staticTexts["offline.record.amount"], "label == %@", "尿量：\(pee)")
        } else {
            XCTAssertFalse(row.staticTexts["offline.record.amount"].exists)
        }
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
