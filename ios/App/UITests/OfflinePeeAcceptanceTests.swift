import XCTest

/// Run only on a fresh, ephemeral development Simulator sandbox with synthetic data.
/// No reset flags, database injection, network access, or production data are used.
/// XCTest distributes classes across workers; each flow owns its synthetic baby.
@MainActor
final class OfflinePeeAcceptanceTests: OfflineAcceptanceSupport {
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
}

@MainActor
final class OfflineDiaperAcceptanceTests: OfflineAcceptanceSupport {
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
}

@MainActor
final class OfflineDailyAcceptanceTests: OfflineAcceptanceSupport {
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
}

@MainActor
final class OfflineHealthAcceptanceTests: OfflineAcceptanceSupport {
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
}

@MainActor
final class OfflineGrowthAcceptanceTests: OfflineAcceptanceSupport {
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
}

@MainActor
final class OfflineMilkAcceptanceTests: OfflineAcceptanceSupport {
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

        openExtendedRecord(kind: "formula", in: app)
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

        openExtendedRecord(kind: "formula", in: app)
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
        selectExtendedRecordKind("pumped_feed", currentKind: "formula", in: app)
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
}

@MainActor
final class OfflineTemperatureAcceptanceTests: OfflineAcceptanceSupport {
    func testTemperatureUnitConversionAndAdvicePreferencePreserveSavedFacts() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Temperature-007"
        let note = "ui-temperature-note"
        let adviceText = "低月龄发热请及时就医。本提示仅供参考，不构成医疗诊断。"
        app.launch()
        tap(app.buttons["offline.baby.new"])
        let nickname = app.textFields["offline.baby.nickname"]
        tap(nickname)
        nickname.typeText(babyName)
        // The ordinary creation form defaults birthday to today, keeping this
        // synthetic baby below three months without hardcoding a calendar date.
        tap(app.buttons["offline.baby.create"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已在本机创建宝宝：\(babyName)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        tap(app.buttons["offline.baby.new"])
        assertSelectedBaby(app, name: babyName)
        assertEmptyHistory(app)

        openExtendedRecord(kind: "temperature", in: app)
        replaceTemperatureValue("36.5", with: "0", in: app)
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["confirmation.reason"], "label == %@", "体温超出可记录范围")
        let temperature = healthField("offline.temperature.value", in: app)
        waitFor(temperature, "value == %@", "0")
        selectTemperatureUnit("fahrenheit", in: app)
        waitFor(temperature, "value == %@", "0") // Invalid switching cannot replace the input.
        replaceTemperatureValue("0", with: "37", in: app)
        selectTemperatureUnit("fahrenheit", in: app)
        waitFor(temperature, "value == %@", "98.6")
        replaceTemperatureValue("98.6", with: "100.4", in: app)
        waitFor(app.staticTexts["offline.temperature.advice"], "label == %@", adviceText)
        selectTemperatureUnit("celsius", in: app)
        waitFor(temperature, "value == %@", "38")
        selectTemperatureUnit("fahrenheit", in: app)
        waitFor(temperature, "value == %@", "100.4")
        revealHealthControl(noteField(in: app), in: app)
        enterNote(note, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "体温")
        let row = diaperRow(in: app, note: note)
        waitFor(row, "exists == true")
        let recordID = row.identifier
        assertTemperatureRow(row, value: "38 ℃", note: note)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertTemperatureRow(app.otherElements[recordID], value: "38 ℃", note: note)
        tap(app.otherElements[recordID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：体温（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        // Display units are draft-only: reopening reads canonical Celsius.
        waitFor(temperature, "value == %@", "38")
        waitFor(app.staticTexts["offline.temperature.advice"], "label == %@", adviceText)
        replaceTemperatureValue("38", with: "38.2", in: app)
        let adviceToggle = app.switches["offline.temperature.advice.enabled"]
        revealHealthControl(adviceToggle, in: app)
        waitFor(adviceToggle, "value == %@", "1")
        tap(adviceToggle)
        waitFor(adviceToggle, "value == %@", "0")
        waitFor(app.staticTexts["offline.temperature.advice"], "exists == false")
        waitFor(temperature, "value == %@", "38.2")
        saveDiaper(in: app, babyName: babyName, kindLabel: "体温")
        assertTemperatureRow(app.otherElements[recordID], value: "38.2 ℃", note: note)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertTemperatureRow(app.otherElements[recordID], value: "38.2 ℃", note: note)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)
        tap(app.otherElements[recordID].buttons["offline.record.edit"])
        waitFor(temperature, "value == %@", "38.2")
        waitFor(adviceToggle, "value == %@", "0")
        XCTAssertFalse(app.staticTexts["offline.temperature.advice"].exists)
        tap(app.buttons["offline.editor.close"])
        waitFor(temperature, "exists == false")
        assertTemperatureRow(app.otherElements[recordID], value: "38.2 ℃", note: note)
    }
}

@MainActor
final class OfflineFoodAcceptanceTests: OfflineAcceptanceSupport {
    func testFoodAmountsAndIndependentKindsSurviveEditsAndDeletion() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Food-008"
        let foodNote = "ui-food-note"
        let snackNote = "ui-snack-note"
        let drinkNote = "ui-drink-note"
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

        openExtendedRecord(kind: "baby_food", in: app)
        waitFor(app.staticTexts["offline.food.guidance.stage"], "label == %@", "未满 1 月龄 · 6 月龄前 · 以奶为主")
        waitFor(app.staticTexts["offline.food.guidance.disclaimer"], "label == %@",
                "仅供参考，不构成医疗或营养诊疗建议。请结合宝宝发育与儿保/儿科指导调整。")
        XCTAssertEqual(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "offline.food.suggestion.")).count, 0)
        revealHealthControl(noteField(in: app), in: app)
        enterNote(foodNote, in: app)
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["confirmation.reason"], "label == %@", "请填写内容")
        waitFor(noteField(in: app), "value == %@", foodNote)
        enterHealthText("offline.food.content", text: "ui-food-content", in: app)
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["confirmation.reason"], "label == %@", "请填写量（如 50g）")
        enterHealthText("offline.food.amount", text: "few-bites", in: app)
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["confirmation.reason"], "label == %@",
                "量需包含数字才能统计（「几口」无法统计）；「半碗」会自动记为 0.5碗")
        waitFor(healthField("offline.food.amount", in: app), "value == %@", "few-bites")
        replaceFoodField("offline.food.amount", oldValue: "few-bites", newValue: "半碗", in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "辅食")
        let foodRow = diaperRow(in: app, note: foodNote)
        waitFor(foodRow, "exists == true")
        let foodID = foodRow.identifier
        assertFoodRow(foodRow, kind: "辅食", content: "ui-food-content", amount: "0.5碗", note: foodNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        openExtendedRecord(kind: "snack", in: app)
        enterHealthText("offline.food.content", text: "ui-snack-content", in: app)
        enterHealthText("offline.food.amount", text: "few-bites", in: app)
        revealHealthControl(app.buttons["offline.excretion.kind"], in: app)
        selectExtendedRecordKind("drink", currentKind: "snack", in: app)
        // Both types share controls but must retain separate raw inputs.
        waitFor(healthField("offline.food.content", in: app), "value != %@", "ui-snack-content")
        waitFor(healthField("offline.food.amount", in: app), "value != %@", "few-bites")
        selectExtendedRecordKind("snack", currentKind: "drink", in: app)
        waitFor(healthField("offline.food.content", in: app), "value == %@", "ui-snack-content")
        waitFor(healthField("offline.food.amount", in: app), "value == %@", "few-bites")
        revealHealthControl(noteField(in: app), in: app)
        enterNote(snackNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "点心")
        let snackRow = diaperRow(in: app, note: snackNote)
        waitFor(snackRow, "exists == true")
        let snackID = snackRow.identifier
        assertFoodRow(snackRow, kind: "点心", content: "ui-snack-content", amount: "few-bites", note: snackNote)

        openExtendedRecord(kind: "drink", in: app)
        enterHealthText("offline.food.content", text: "ui-drink-content", in: app)
        revealHealthControl(noteField(in: app), in: app)
        enterNote(drinkNote, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "饮料")
        let drinkRow = diaperRow(in: app, note: drinkNote)
        waitFor(drinkRow, "exists == true")
        let drinkID = drinkRow.identifier
        assertFoodRow(drinkRow, kind: "饮料", content: "ui-drink-content", amount: "未填量", note: drinkNote)
        XCTAssertEqual(Set([foodID, snackID, drinkID]).count, 3)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 3)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertFoodRow(app.otherElements[foodID], kind: "辅食", content: "ui-food-content", amount: "0.5碗", note: foodNote)
        assertFoodRow(app.otherElements[snackID], kind: "点心", content: "ui-snack-content", amount: "few-bites", note: snackNote)
        assertFoodRow(app.otherElements[drinkID], kind: "饮料", content: "ui-drink-content", amount: "未填量", note: drinkNote)
        tap(app.otherElements[foodID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：辅食（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        waitFor(healthField("offline.food.amount", in: app), "value == %@", "0.5碗")
        replaceFoodField("offline.food.content", oldValue: "ui-food-content", newValue: "ui-food-edited", in: app)
        waitFor(healthField("offline.food.amount", in: app), "value == %@", "0.5碗")
        waitFor(noteField(in: app), "value == %@", foodNote)
        saveDiaper(in: app, babyName: babyName, kindLabel: "辅食")
        assertFoodRow(app.otherElements[foodID], kind: "辅食", content: "ui-food-edited", amount: "0.5碗", note: foodNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 3)
        tap(app.otherElements[drinkID].buttons["offline.record.edit"])
        revealHealthControl(app.buttons["offline.delete.request"], in: app)
        tap(app.buttons["offline.delete.request"])
        tap(app.buttons["offline.delete.confirm"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已删除本机饮料")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
        waitFor(app.otherElements[drinkID], "exists == false")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        XCTAssertFalse(app.otherElements[drinkID].exists)
        assertFoodRow(app.otherElements[foodID], kind: "辅食", content: "ui-food-edited", amount: "0.5碗", note: foodNote)
        assertFoodRow(app.otherElements[snackID], kind: "点心", content: "ui-snack-content", amount: "few-bites", note: snackNote)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 2)
    }
}

@MainActor
final class OfflineNursingAcceptanceTests: OfflineAcceptanceSupport {
    func testManualNursingFactsRetainModeAndIdentityAcrossEdits() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Nursing-009"
        let note = "ui-nursing-note"
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

        // The 26th option needs a longer single menu-contained reveal than the
        // earlier types. Existing callers retain their original gesture endpoint.
        openExtendedRecord(kind: "nursing", menuDragEndFraction: 0.12, in: app)
        waitFor(app.staticTexts["offline.nursing.scope"], "label == %@",
                "手动填写已发生的哺乳事实；不含计时器或进行中状态。")
        waitFor(app.staticTexts["offline.nursing.mode"], "label == %@", "记录模式：结束；记录时间不从左右时长倒推。")
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["confirmation.reason"], "label == %@", "请填写左侧或右侧喂养时长")
        waitFor(healthField("offline.nursing.left", in: app), "value == %@", "0")
        waitFor(healthField("offline.nursing.right", in: app), "value == %@", "0")
        replaceNursingMinutes("offline.nursing.left", oldValue: "0", newValue: "5", in: app)
        revealHealthControl(app.buttons["offline.nursing.order"], in: app)
        tap(app.buttons["offline.nursing.order"])
        tap(app.buttons["offline.nursing.order.L"])
        revealHealthControl(noteField(in: app), in: app)
        enterNote(note, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "母乳")
        let row = diaperRow(in: app, note: note)
        waitFor(row, "exists == true")
        let recordID = row.identifier
        assertNursingRow(row, rightMinutes: 0, order: "仅左", amount: "奶量未填写", note: note)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertNursingRow(app.otherElements[recordID], rightMinutes: 0, order: "仅左", amount: "奶量未填写", note: note)
        tap(app.otherElements[recordID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：母乳（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        waitFor(app.staticTexts["offline.nursing.scope"], "label == %@",
                "手动填写已发生的哺乳事实；不含计时器或进行中状态。")
        waitFor(healthField("offline.nursing.left", in: app), "value == %@", "5")
        replaceNursingMinutes("offline.nursing.right", oldValue: "0", newValue: "3", in: app)
        revealHealthControl(app.buttons["offline.nursing.order"], in: app)
        tap(app.buttons["offline.nursing.order"])
        tap(app.buttons["offline.nursing.order.RL"])
        enterHealthText("offline.nursing.amount", text: "80", in: app)
        waitFor(noteField(in: app), "value == %@", note)
        saveDiaper(in: app, babyName: babyName, kindLabel: "母乳")
        assertNursingRow(app.otherElements[recordID], rightMinutes: 3, order: "先右后左", amount: "奶量：80 ml", note: note)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertNursingRow(app.otherElements[recordID], rightMinutes: 3, order: "先右后左", amount: "奶量：80 ml", note: note)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)
    }
}

@MainActor
final class OfflineCustomAcceptanceTests: OfflineAcceptanceSupport {
    func testCustomCatalogChangesPreserveHistoricalSnapshotsAndAssociation() {
        continueAfterFailure = false
        let app = XCUIApplication()
        let babyName = "UI-Custom-010"
        let nameA = "UI-Custom-A"
        let nameB = "UI-Custom-B"
        let renamed = "UI-Custom-Renamed"
        let note = "ui-custom-note"
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
        tap(app.buttons["offline.catalog.open"])
        createCatalogDefinition(nameA, icon: 0, in: app)
        let rowA = catalogRow(name: nameA, in: app)
        waitFor(rowA, "exists == true")
        let idA = rowA.identifier
        let definitionID = String(idA.dropFirst("offline.catalog.row.".count))
        createCatalogDefinition(nameB, icon: 1, in: app)
        let rowB = catalogRow(name: nameB, in: app)
        waitFor(rowB, "exists == true")
        let idB = rowB.identifier
        XCTAssertNotEqual(idA, idB)
        assertCatalogOrder(first: app.otherElements[idA], second: app.otherElements[idB])
        moveCatalogRow(app.otherElements[idA], direction: "down", in: app)
        assertCatalogOrder(first: app.otherElements[idB], second: app.otherElements[idA])
        moveCatalogRow(app.otherElements[idA], direction: "up", in: app)
        assertCatalogOrder(first: app.otherElements[idA], second: app.otherElements[idB])
        moveCatalogRow(app.otherElements[idB], direction: "up", in: app)
        assertCatalogOrder(first: app.otherElements[idB], second: app.otherElements[idA])

        tap(app.otherElements[idA].buttons["offline.catalog.record.new"])
        waitFor(healthField("offline.custom.title", in: app), "value == %@", nameA)
        waitFor(app.staticTexts["offline.custom.icon"], "label == %@", "图标快照：星星")
        waitFor(app.staticTexts["offline.custom.definition"], "value == %@", definitionID)
        enterHealthText("offline.custom.detail", text: "ui-custom-detail", in: app)
        revealHealthControl(noteField(in: app), in: app)
        enterNote(note, in: app)
        saveDiaper(in: app, babyName: babyName, kindLabel: "自定义记录")
        let record = diaperRow(in: app, note: note)
        waitFor(record, "exists == true")
        let recordID = record.identifier
        assertCustomRow(record, title: nameA, detail: "ui-custom-detail", note: note, definitionID: definitionID)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        tap(app.buttons["offline.catalog.open"])
        tap(app.otherElements[idA].buttons["offline.catalog.edit"])
        let nameField = healthField("offline.catalog.editor.name", in: app)
        revealCatalogControl(nameField, in: app)
        waitFor(nameField, "value == %@", nameA)
        nameField.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        nameField.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: nameA.count) + renamed)
        waitFor(nameField, "value == %@", renamed)
        chooseCatalogIcon(3, in: app)
        saveCatalogDefinition(in: app)
        waitFor(app.otherElements[idA].staticTexts["offline.catalog.name"], "label == %@", renamed)
        waitFor(app.otherElements[idA].staticTexts["offline.catalog.icon"], "label == %@", "图标：月亮")
        tap(app.buttons["offline.catalog.close"])
        assertCustomRow(app.otherElements[recordID], title: nameA, detail: "ui-custom-detail", note: note, definitionID: definitionID)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertCustomRow(app.otherElements[recordID], title: nameA, detail: "ui-custom-detail", note: note, definitionID: definitionID)
        tap(app.buttons["offline.catalog.open"])
        assertCatalogOrder(first: app.otherElements[idB], second: app.otherElements[idA])
        waitFor(app.otherElements[idA].staticTexts["offline.catalog.name"], "label == %@", renamed)
        waitFor(app.otherElements[idA].staticTexts["offline.catalog.icon"], "label == %@", "图标：月亮")
        tap(app.otherElements[idA].buttons["offline.catalog.delete"])
        tap(app.buttons["offline.catalog.delete.cancel"])
        waitFor(app.otherElements[idA].buttons["offline.catalog.record.new"], "exists == true AND enabled == true")
        tap(app.otherElements[idA].buttons["offline.catalog.delete"])
        tap(app.buttons["offline.catalog.delete.confirm"])
        waitFor(app.staticTexts["offline.catalog.result"], "label == %@", "已删除本机自定义项目；历史记录保留")
        waitFor(app.otherElements[idA], "exists == false")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.catalog.record.new").count, 1)
        waitFor(app.otherElements[idB].buttons["offline.catalog.record.new"], "exists == true AND enabled == true")
        tap(app.buttons["offline.catalog.close"])
        assertCustomRow(app.otherElements[recordID], title: nameA, detail: "ui-custom-detail", note: note, definitionID: definitionID)
        tap(app.otherElements[recordID].buttons["offline.record.edit"])
        waitFor(app.staticTexts["offline.excretion.kind.fixed"], "label == %@", "类型：自定义记录（不可更改）")
        XCTAssertFalse(app.buttons["offline.excretion.kind"].exists)
        waitFor(app.staticTexts["offline.custom.icon"], "label == %@", "图标快照：星星")
        waitFor(app.staticTexts["offline.custom.definition"], "value == %@", definitionID)
        let detail = healthField("offline.custom.detail", in: app)
        revealHealthControl(detail, in: app)
        waitFor(detail, "value == %@", "ui-custom-detail")
        detail.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.1)).tap()
        detail.typeText("-edited")
        waitFor(detail, "value == %@", "ui-custom-detail-edited")
        saveDiaper(in: app, babyName: babyName, kindLabel: "自定义记录")
        assertCustomRow(app.otherElements[recordID], title: nameA, detail: "ui-custom-detail-edited", note: note, definitionID: definitionID)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)

        relaunch(app)
        assertSelectedBaby(app, name: babyName)
        assertCustomRow(app.otherElements[recordID], title: nameA, detail: "ui-custom-detail-edited", note: note, definitionID: definitionID)
        XCTAssertEqual(app.buttons.matching(identifier: "offline.record.edit").count, 1)
        tap(app.buttons["offline.catalog.open"])
        XCTAssertFalse(app.otherElements[idA].exists)
        waitFor(app.otherElements[idB].staticTexts["offline.catalog.name"], "label == %@", nameB)
        waitFor(app.otherElements[idB].staticTexts["offline.catalog.icon"], "label == %@", "图标：爱心")
        XCTAssertEqual(app.buttons.matching(identifier: "offline.catalog.record.new").count, 1)
        XCTAssertFalse(app.otherElements[idB].buttons["offline.catalog.up"].isEnabled)
        XCTAssertFalse(app.otherElements[idB].buttons["offline.catalog.down"].isEnabled)
        tap(app.buttons["offline.catalog.close"])
    }
}

/// Shared UI operations only: no test methods to inherit or execute twice.
@MainActor
class OfflineAcceptanceSupport: XCTestCase {
    fileprivate func replaceGrowthValue(_ oldValue: String, with newValue: String, in app: XCUIApplication) {
        let field = healthField("offline.growth.value", in: app)
        revealHealthControl(field, in: app)
        waitFor(field, "value == %@", oldValue)
        // A single-line field tapped at its trailing edge places the caret after
        // the checked value; delete exactly that value, then verify replacement.
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: oldValue.count) + newValue)
        waitFor(field, "value == %@", newValue)
    }

    fileprivate func assertGrowthRow(_ row: XCUIElement, kind: String, value: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", kind)
        waitFor(row.staticTexts["offline.record.growth.value"], "label == %@", value)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    fileprivate func replaceMilkAmount(_ oldValue: String, with newValue: String, in app: XCUIApplication) {
        let field = healthField("offline.milk.amount", in: app)
        revealHealthControl(field, in: app)
        waitFor(field, "value == %@", oldValue)
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: oldValue.count) + newValue)
        waitFor(field, "value == %@", newValue)
    }

    fileprivate func assertMilkRow(_ row: XCUIElement, kind: String, amount: Int, note: String,
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

    fileprivate func selectTemperatureUnit(_ unit: String, in app: XCUIApplication) {
        revealHealthControl(app.buttons["offline.temperature.unit"], in: app)
        tap(app.buttons["offline.temperature.unit"])
        tap(app.buttons["offline.temperature.unit.\(unit)"])
    }

    fileprivate func replaceTemperatureValue(_ oldValue: String, with newValue: String, in app: XCUIApplication) {
        let field = healthField("offline.temperature.value", in: app)
        revealHealthControl(field, in: app)
        waitFor(field, "value == %@", oldValue)
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: oldValue.count) + newValue)
        waitFor(field, "value == %@", newValue)
    }

    fileprivate func assertTemperatureRow(_ row: XCUIElement, value: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", "体温")
        waitFor(row.staticTexts["offline.record.temperature.value"], "label == %@", value)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    fileprivate func openExtendedRecord(kind: String, menuDragEndFraction: CGFloat = 0.25, in app: XCUIApplication) {
        tap(app.buttons["offline.pee.new"])
        selectExtendedRecordKind(kind, currentKind: "pee", menuDragEndFraction: menuDragEndFraction, in: app)
    }

    fileprivate func selectExtendedRecordKind(_ kind: String, currentKind: String, menuDragEndFraction: CGFloat = 0.25, in app: XCUIApplication) {
        tap(app.buttons["offline.excretion.kind"])
        // The captured Simulator hierarchy exposes the long Picker popup as a
        // CollectionView. Its last options exist below the clipped menu viewport.
        // Scope one reveal gesture to that menu, never the underlying editor.
        let menu = app.collectionViews.containing(.button, identifier: "offline.excretion.kind.\(currentKind)").firstMatch
        waitFor(menu, "exists == true")
        let option = app.buttons["offline.excretion.kind.\(kind)"]
        if !option.exists || !option.isHittable {
            let visible = menu.frame.intersection(app.frame)
            XCTAssertFalse(visible.isNull)
            XCTAssertGreaterThan(visible.height, 100)
            print("Record menu reveal \(kind): collection=\(menu.frame), visible=\(visible), dragEndFraction=\(menuDragEndFraction)")
            let origin = menu.coordinate(withNormalizedOffset: .zero)
            let start = origin.withOffset(CGVector(dx: visible.midX - menu.frame.minX,
                                                   dy: visible.minY + visible.height * 0.7 - menu.frame.minY))
            let end = origin.withOffset(CGVector(dx: visible.midX - menu.frame.minX,
                                                 dy: visible.minY + visible.height * menuDragEndFraction - menu.frame.minY))
            start.press(forDuration: 0.1, thenDragTo: end)
        }
        tap(option)
    }

    fileprivate func replaceFoodField(_ identifier: String, oldValue: String, newValue: String, in app: XCUIApplication) {
        let field = healthField(identifier, in: app)
        revealHealthControl(field, in: app)
        waitFor(field, "value == %@", oldValue)
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: oldValue.count) + newValue)
        waitFor(field, "value == %@", newValue)
    }

    fileprivate func assertFoodRow(_ row: XCUIElement, kind: String, content: String, amount: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", kind)
        waitFor(row.staticTexts["offline.record.food.content"], "label == %@", content)
        waitFor(row.staticTexts["offline.record.food.amount"], "label == %@", amount)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    fileprivate func replaceNursingMinutes(_ identifier: String, oldValue: String, newValue: String, in app: XCUIApplication) {
        let field = healthField(identifier, in: app)
        revealHealthControl(field, in: app)
        waitFor(field, "value == %@", oldValue)
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: oldValue.count) + newValue)
        waitFor(field, "value == %@", newValue)
    }

    fileprivate func assertNursingRow(_ row: XCUIElement, rightMinutes: Int, order: String, amount: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", "母乳")
        waitFor(row.staticTexts["offline.record.nursing.duration"], "label == %@", "左侧：5 分钟 · 右侧：\(rightMinutes) 分钟")
        waitFor(row.staticTexts["offline.record.nursing.order"], "label == %@", "顺序：\(order)")
        waitFor(row.staticTexts["offline.record.nursing.amount"], "label == %@", amount)
        waitFor(row.staticTexts["offline.record.nursing.mode"], "label == %@", "记录模式：结束")
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    fileprivate func catalogRow(name: String, in app: XCUIApplication) -> XCUIElement {
        app.otherElements.matching(NSPredicate(format: "identifier BEGINSWITH %@", "offline.catalog.row."))
            .containing(.staticText, identifier: name).firstMatch
    }

    fileprivate func createCatalogDefinition(_ name: String, icon: Int, in app: XCUIApplication) {
        revealCatalogControl(app.buttons["offline.catalog.add"], in: app)
        tap(app.buttons["offline.catalog.add"])
        let field = healthField("offline.catalog.editor.name", in: app)
        revealCatalogControl(field, in: app)
        tap(field)
        field.typeText(name)
        waitFor(field, "value == %@", name)
        chooseCatalogIcon(icon, in: app)
        saveCatalogDefinition(in: app)
    }

    fileprivate func chooseCatalogIcon(_ icon: Int, in app: XCUIApplication) {
        revealCatalogControl(app.buttons["offline.catalog.editor.icon"], in: app)
        tap(app.buttons["offline.catalog.editor.icon"])
        tap(app.buttons["offline.catalog.editor.icon.\(icon)"])
    }

    fileprivate func saveCatalogDefinition(in app: XCUIApplication) {
        revealCatalogControl(app.buttons["offline.catalog.save"], in: app)
        tap(app.buttons["offline.catalog.save"])
        waitFor(app.staticTexts["offline.catalog.result"], "label == %@", "已保存本机自定义项目")
        waitFor(healthField("offline.catalog.editor.name", in: app), "exists == false")
        waitFor(app.buttons["offline.catalog.add"], "exists == true AND enabled == true")
    }

    fileprivate func moveCatalogRow(_ row: XCUIElement, direction: String, in app: XCUIApplication) {
        tap(row.buttons["offline.catalog.\(direction)"])
        tap(app.buttons["offline.catalog.move.confirm"])
        waitFor(app.staticTexts["offline.catalog.result"], "label == %@", "已保存本机项目顺序")
        waitFor(app.buttons["offline.catalog.add"], "enabled == true")
    }

    fileprivate func assertCatalogOrder(first: XCUIElement, second: XCUIElement) {
        waitFor(first, "exists == true")
        waitFor(second, "exists == true")
        XCTAssertLessThan(first.frame.minY, second.frame.minY)
        XCTAssertFalse(first.buttons["offline.catalog.up"].isEnabled)
        XCTAssertFalse(second.buttons["offline.catalog.down"].isEnabled)
    }

    fileprivate func revealCatalogControl(_ element: XCUIElement, in app: XCUIApplication) {
        waitFor(element, "exists == true")
        let scroll = app.scrollViews.containing(.any, identifier: element.identifier).firstMatch
        waitFor(scroll, "exists == true")
        // The catalog has no confirmation footer or inner horizontal padding.
        // Clip its real viewport to the keyboard; use visible, non-interactive
        // icon/counter labels as drag anchors instead of an active text field.
        for attempt in 0...4 {
            let viewport = scroll.frame.intersection(app.frame)
            let top = viewport.minY + 8
            let bottom = min(viewport.maxY, app.keyboards.firstMatch.exists ? app.keyboards.firstMatch.frame.minY : viewport.maxY) - 12
            let field = element.frame
            print("Catalog reveal \(element.identifier) attempt \(attempt): field=\(field), viewport=\(viewport), safeY=\(top)...\(bottom)")
            if element.isHittable && field.minY >= top && field.maxY <= bottom { return }
            if attempt == 4 { break }
            let up = field.maxY > bottom
            let anchors = scroll.staticTexts.matching(NSPredicate(
                format: "identifier == %@ OR identifier == %@", "offline.catalog.icon", "offline.catalog.editor.count"
            )).allElementsBoundByIndex.filter {
                $0.isHittable && $0.frame.minY >= top && $0.frame.maxY <= bottom
                    && (up ? $0.frame.midY - top : bottom - $0.frame.midY) > 40
            }.sorted { up ? $0.frame.midY > $1.frame.midY : $0.frame.midY < $1.frame.midY }
            guard bottom - top > 60, let anchor = anchors.first else {
                XCTFail("Catalog has no visible non-interactive scroll anchor for \(element.identifier)")
                return
            }
            let needed = up ? field.maxY - bottom : top - field.minY
            let startY = anchor.frame.midY
            let distance = min(max(needed + 16, 40), up ? startY - top - 8 : bottom - startY - 8)
            let x = anchor.frame.midX
            let endY = startY + (up ? -distance : distance)
            print("Catalog anchor \(anchor.identifier): \(anchor.frame), dragY=\(startY)...\(endY)")
            let origin = scroll.coordinate(withNormalizedOffset: .zero)
            origin.withOffset(CGVector(dx: x - scroll.frame.minX, dy: startY - scroll.frame.minY))
                .press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: x - scroll.frame.minX, dy: endY - scroll.frame.minY)))
        }
        XCTFail("Could not reveal catalog control \(element.identifier)")
    }

    fileprivate func assertCustomRow(_ row: XCUIElement, title: String, detail: String, note: String, definitionID: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", "自定义记录")
        waitFor(row.staticTexts["offline.record.custom.title"], "label == %@", title)
        waitFor(row.staticTexts["offline.record.custom.detail"], "label == %@", detail)
        waitFor(row.staticTexts["offline.record.custom.icon"], "label == %@", "图标快照：星星")
        waitFor(row.staticTexts["offline.record.custom.definition"], "value == %@", definitionID)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    fileprivate struct HealthForm {
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

    fileprivate func healthField(_ identifier: String, in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    fileprivate func enterHealthText(_ identifier: String, text: String, in app: XCUIApplication) {
        let field = healthField(identifier, in: app)
        revealHealthControl(field, in: app)
        tap(field)
        field.typeText(text)
        waitFor(field, "value == %@", text)
    }

    fileprivate func revealHealthControl(_ element: XCUIElement, in app: XCUIApplication) {
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

    fileprivate func assertHealthRow(_ row: XCUIElement, form: HealthForm) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", form.label)
        waitFor(row.staticTexts["offline.record.health.\(form.kind).\(form.primary)"], "label == %@",
                "\(form.primaryCaption)：\(form.primaryValue)")
        waitFor(row.staticTexts["offline.record.health.\(form.kind).\(form.secondary)"], "label == %@",
                "\(form.secondaryCaption)：\(form.secondaryValue)")
        waitFor(row.staticTexts["offline.record.note"], "label == %@", form.note)
    }

    fileprivate func assertSymptomRow(_ row: XCUIElement, severity: String, description: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", "咳嗽记录")
        waitFor(row.staticTexts["offline.record.health.severity"], "label == %@", "程度：\(severity)")
        waitFor(row.staticTexts["offline.record.health.description"], "label == %@", description)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
    }

    fileprivate func assertDiaryRow(_ row: XCUIElement, body: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", "日记")
        waitFor(row.staticTexts["offline.record.body"], "label == %@", body)
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
        XCTAssertFalse(row.staticTexts["offline.record.amount"].exists)
        XCTAssertFalse(row.staticTexts["offline.record.stool.amount"].exists)
    }

    fileprivate func assertPointEditor(_ app: XCUIApplication) {
        waitFor(app.staticTexts["offline.editor.point"], "label == %@", "单次记录 · 无持续时长")
        XCTAssertFalse(app.buttons["offline.pee.amount"].exists)
        XCTAssertFalse(app.buttons["offline.stool.amount"].exists)
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "offline.diary.body").firstMatch.exists)
    }

    fileprivate func assertPointRow(_ row: XCUIElement, kind: String, note: String) {
        waitFor(row, "exists == true")
        waitFor(row.staticTexts["offline.record.kind"], "label == %@", kind)
        waitFor(row.staticTexts["offline.record.point"], "label == %@", "单次记录 · 无持续时长")
        waitFor(row.staticTexts["offline.record.note"], "label == %@", note)
        XCTAssertFalse(row.staticTexts["offline.record.body"].exists)
        XCTAssertFalse(row.staticTexts["offline.record.amount"].exists)
        XCTAssertFalse(row.staticTexts["offline.record.stool.amount"].exists)
    }

    fileprivate func openDiaper(kind: String, in app: XCUIApplication) {
        tap(app.buttons["offline.pee.new"])
        tap(app.buttons["offline.excretion.kind"])
        tap(app.buttons["offline.excretion.kind.\(kind)"])
    }

    fileprivate func revealDiaperControl(_ element: XCUIElement, in app: XCUIApplication) {
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

    fileprivate func diaperRow(in app: XCUIApplication, note: String) -> XCUIElement {
        app.otherElements.matching(NSPredicate(format: "identifier BEGINSWITH %@", "offline.record.row."))
            .containing(.staticText, identifier: note).firstMatch
    }

    fileprivate func chooseDiaperOption(_ identifier: String, value: Int, in app: XCUIApplication) {
        revealDiaperControl(app.buttons[identifier], in: app)
        tap(app.buttons[identifier])
        tap(app.buttons["\(identifier).\(value)"])
    }

    fileprivate func saveDiaper(in app: XCUIApplication, babyName: String, kindLabel: String) {
        tap(app.buttons["confirmation.save"])
        waitFor(app.staticTexts["offline.result"], "label == %@", "已保存到本机：\(babyName)的\(kindLabel)")
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
    }

    fileprivate func assertDiaperRow(_ row: XCUIElement, kind: String, amount: String,
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

    fileprivate func swipeEditorDown(in app: XCUIApplication) {
        // Start on the dedicated, non-interactive header rather than a field,
        // picker, button, or scroll view. Derive the drag from actual UI geometry.
        let header = app.descendants(matching: .any)
            .matching(identifier: "offline.editor.dragRegion").firstMatch
        waitFor(header, "exists == true AND hittable == true")
        let start = header.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9))
        start.press(forDuration: 0.1, thenDragTo: end)
    }

    fileprivate func tap(_ element: XCUIElement) {
        waitFor(element, "exists == true AND hittable == true AND enabled == true")
        element.tap()
    }

    fileprivate func relaunch(_ app: XCUIApplication) {
        app.terminate()
        app.launch()
        waitFor(app.buttons["offline.pee.new"], "exists == true AND enabled == true AND hittable == true")
    }

    fileprivate func noteField(in app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(identifier: "offline.pee.note").firstMatch
    }

    fileprivate func enterNote(_ note: String, in app: XCUIApplication) {
        // SwiftUI's vertically growing TextField is exposed as a text view on iOS.
        // Query by ID across element types to avoid coupling to that implementation detail.
        let field = noteField(in: app)
        waitFor(field, "exists == true AND hittable == true")
        field.tap()
        field.typeText(note)
        waitFor(field, "value == %@", note)
    }

    fileprivate func assertSelectedBaby(_ app: XCUIApplication, name: String) {
        let selection = app.descendants(matching: .any)
            .matching(identifier: "offline.baby.selection").firstMatch
        waitFor(selection, "exists == true AND (label CONTAINS %@ OR value CONTAINS %@)", name, name)
    }

    fileprivate func assertEmptyHistory(_ app: XCUIApplication) {
        waitFor(app.staticTexts["offline.records.empty"], "label == %@", "尚无本机记录")
        XCTAssertEqual(app.staticTexts.matching(identifier: "offline.record.amount").count, 0)
        XCTAssertEqual(app.staticTexts.matching(identifier: "offline.record.note").count, 0)
    }

    fileprivate func assertOneSavedRecord(_ app: XCUIApplication, note: String, amount: String = "中") {
        let notes = app.staticTexts.matching(identifier: "offline.record.note")
        waitFor(notes.firstMatch, "exists == true AND label == %@", note)
        XCTAssertEqual(notes.count, 1)
        let amounts = app.staticTexts.matching(identifier: "offline.record.amount")
        XCTAssertEqual(amounts.count, 1)
        waitFor(amounts.firstMatch, "label == %@", "尿量：\(amount)")
        XCTAssertFalse(app.staticTexts["offline.records.empty"].exists)
        XCTAssertTrue(notes.firstMatch.isHittable, "The persisted record must be visible")
    }

    fileprivate func waitFor(_ object: Any, _ format: String, _ arguments: CVarArg...,
                         file: StaticString = #filePath, line: UInt = #line) {
        let predicate = NSPredicate(format: format, argumentArray: arguments)
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: object)
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 15), .completed,
                       "Timed out waiting for \(format)", file: file, line: line)
    }
}
