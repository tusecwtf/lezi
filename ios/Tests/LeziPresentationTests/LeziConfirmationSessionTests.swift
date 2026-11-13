import Foundation
import XCTest
import LeziCore
@testable import LeziUI

final class LeziConfirmationSessionTests: XCTestCase {
    private let baby = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
    private func draft(baby: UUID? = nil, amount: Int = 120) -> RecordConfirmationDraft {
        .init(clientUUID: UUID(), babyClientUUID: baby ?? self.baby, item: .builtIn(.formula)!,
              timestampMillis: 1_000, payload: .milk(kind: .formula, amountMl: amount))
    }
    private func session() -> LeziConfirmationSession {
        .init(context: .init(babyClientUUID: baby, babyName: "测试宝宝"))
    }
    func testOpeningAndEditingDoNotCreateAnAttempt() {
        var state = session()
        XCTAssertEqual(state.phase, .editing)
        state.draftEdited()
        XCTAssertEqual(state.phase, .editing)
        XCTAssertFalse(state.isSaving)
    }
    func testReadOnlyAndExternalBusyCannotConfirmOrShowReasons() {
        for (canWrite, busy) in [(false, false), (true, true), (false, true)] {
            var state = session()
            XCTAssertEqual(state.confirm(draft: draft(amount: 0), nowMillis: 2_000,
                                         canWrite: canWrite, externallyBusy: busy), .ignored)
            XCTAssertNil(state.shownIssue)
            XCTAssertEqual(state.phase, .editing)
        }
    }
    func testConfirmationFreezesEditorBabyAndEntireDraftAndBlocksDoubleTap() throws {
        var state = session()
        let original = draft()
        guard case .confirmed(let frozen) = state.confirm(draft: original, nowMillis: 2_000, canWrite: true, externallyBusy: false) else {
            return XCTFail("Expected explicit confirmation")
        }
        XCTAssertEqual(frozen.context, state.context)
        XCTAssertEqual(frozen.draft, original)
        XCTAssertEqual(frozen.confirmedAtMillis, 2_000)
        XCTAssertEqual(state.confirm(draft: draft(), nowMillis: 3_000, canWrite: true, externallyBusy: false), .ignored)
        XCTAssertEqual(frozen.draft.babyClientUUID, baby)
        state.finish(attemptID: UUID())
        XCTAssertTrue(state.isSaving, "Unrelated completion must not unlock the real save")
        state.finish(attemptID: frozen.attemptID)
        XCTAssertEqual(state.phase, .completed)
        XCTAssertEqual(state.confirm(draft: original, nowMillis: 3_000, canWrite: true, externallyBusy: false), .ignored)
    }
    func testDraftEditDuringSavePreservesBusyPhaseAndFrozenSnapshot() {
        var state = session()
        let original = draft(amount: 120)
        guard case .confirmed(let frozen) = state.confirm(draft: original, nowMillis: 2_000, canWrite: true, externallyBusy: false) else {
            return XCTFail("Expected confirmation")
        }
        let edited = draft(amount: 180)
        state.draftEdited()
        XCTAssertEqual(state.phase, .saving(frozen.attemptID))
        XCTAssertTrue(state.isSaving)
        XCTAssertEqual(frozen.context.babyClientUUID, baby)
        XCTAssertEqual(frozen.draft, original)
        XCTAssertNotEqual(frozen.draft, edited)
        XCTAssertEqual(state.confirm(draft: edited, nowMillis: 3_000, canWrite: true, externallyBusy: false), .ignored)
        state.finish(attemptID: frozen.attemptID)
        XCTAssertEqual(state.phase, .completed)
        XCTAssertEqual(frozen.draft.payload, .milk(kind: .formula, amountMl: 120))
    }
    func testChangedBabyCannotRetargetAnOpenEditor() {
        var state = session()
        let wrongBaby = draft(baby: UUID())
        guard case .explanation(let issue) = state.confirm(draft: wrongBaby, nowMillis: 2_000, canWrite: true, externallyBusy: false) else {
            return XCTFail("Wrong baby must be explained, never saved")
        }
        XCTAssertNotNil(issue)
        XCTAssertEqual(state.context.babyClientUUID, baby)
        XCTAssertEqual(state.phase, .editing)
    }
    func testInvalidTapTogglesReasonAndEditsClearIt() {
        var state = session()
        let invalid = draft(amount: 0)
        guard case .explanation(let issue) = state.confirm(draft: invalid, nowMillis: 2_000, canWrite: true, externallyBusy: false) else {
            return XCTFail("Invalid confirmation must reveal the real issue")
        }
        XCTAssertNotNil(issue)
        XCTAssertEqual(state.confirm(draft: invalid, nowMillis: 2_000, canWrite: true, externallyBusy: false), .explanation(nil))
        _ = state.confirm(draft: invalid, nowMillis: 2_000, canWrite: true, externallyBusy: false)
        state.draftEdited()
        XCTAssertNil(state.shownIssue)
        XCTAssertEqual(state.phase, .editing)
    }
    func testFeatureValidationKeepsItsFocusIdentity() {
        var state = session()
        let issue = LeziConfirmationIssue(message: "请填写奶量", fieldID: "milkAmount")
        XCTAssertEqual(state.confirm(draft: draft(), nowMillis: 2_000, canWrite: true, externallyBusy: false, featureIssue: issue), .explanation(issue))
    }
    func testFailureAllowsExplicitRetryButInvalidationRejectsLateCompletion() {
        var state = session()
        let original = draft()
        guard case .confirmed(let first) = state.confirm(draft: original, nowMillis: 2_000, canWrite: true, externallyBusy: false) else {
            return XCTFail("Expected confirmation")
        }
        state.finish(attemptID: first.attemptID, failureMessage: "本机保存未完成，草稿仍保留")
        XCTAssertEqual(state.phase, .editing)
        XCTAssertNotNil(state.saveFailure)
        guard case .confirmed(let second) = state.confirm(draft: original, nowMillis: 3_000, canWrite: true, externallyBusy: false) else {
            return XCTFail("Expected explicit retry")
        }
        XCTAssertEqual(first.draft.clientUUID, second.draft.clientUUID)
        XCTAssertNotEqual(first.attemptID, second.attemptID)
        XCTAssertNil(state.saveFailure)
        state.invalidate()
        state.finish(attemptID: second.attemptID)
        XCTAssertEqual(state.phase, .invalidated)
        XCTAssertEqual(state.confirm(draft: original, nowMillis: 4_000, canWrite: true, externallyBusy: false), .ignored)
    }
}
