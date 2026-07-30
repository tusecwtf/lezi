# 09 validation — RecordComposer state, ViewModel and UI split

## Fixed point and scope

- Implementation base: `dc38b0a` (ticket 09 code was prepared on the preceding frozen structural worktree).
- Ticket-start `RecordComposer.kt` after correctness 01: 1,332 → 493 lines for the sole Modal/Host UI.
- Dedicated contract/saved-state 202, ViewModel/write coordination 603 and completion helper 42 lines;
  every owned responsibility file remains below 1,000 lines.
- Six moved source segments were byte-compared with the pre-split host; this is a same-package mechanical
  move with no interaction, string, persistence or navigation rewrite.

## TDD and structure

- RED: `RecordComposerStructureTest` failed because `RecordComposerContract.kt` did not exist.
- GREEN: the structure test locks unique request, saved-state, ViewModel, Host, completion helper and existing
  `RecordComposerSessionGate` seams and rejects responsibility collapse.
- Correctness 01 remains in `RecordComposerViewModel.reconcileNextFeedPlan`; the sole Host still passes
  `onReconcile = vm::reconcileNextFeedPlan` into the shared flow.

## Regression

- Frozen `./gradlew :feature:log:testDebugUnitTest` — 256 tests, 0 failures.
- `./gradlew :feature:log:lintDebug :app:assembleDebug` — PASS.
- Root-exclusive `./gradlew :feature:log:connectedDebugAndroidTest` on `emulator-5554`, API 35 —
  45/45 PASS, 0 skipped, 0 failed.
- Existing Composer/session/photo/discard/interval/next-feed tests cover add/edit/fulfill/convert,
  confirm-before-persist, photo ownership and stale async result rejection.
- `git diff --check` — PASS.

## Evidence boundary

- The emulator validates current Compose interactions; physical camera import and spoken TalkBack remain
  outside this structural ticket.
- Unified Composer, fact-versus-plan and ClockDial contracts did not change, so PRD/ADR were untouched.
