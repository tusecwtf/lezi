# 03 validation — LayoutEditMode host split

## Fixed point and scope

- Implementation base: `ff02cbf` (ticket 03 code was prepared on the preceding frozen structural worktree).
- `LayoutEditMode.kt`: 1,655 → 736 lines.
- New single-purpose units: alternative input 175, catalog/deleted surface 569, Dock 248 and drag
  modifiers 83 lines; `LayoutEditCanvas` remains the only canvas entry.
- Pure same-package movement only: reducer/session/hit rules, strings, semantics and intent types are unchanged.

## TDD and structure

- RED: the new structure contract failed because `LayoutEditAlternativeInput.kt` did not exist.
- GREEN: `LayoutEditHostStructureTest` 4/4; it locks alternative input, catalog/deleted surface, Dock and
  the single `<1000`-line host boundary.
- Touch, TalkBack and keyboard paths still emit only the existing `LayoutEditIntent`; no writer or reducer
  implementation was copied.

## Regression

- Targeted layout reducer/session/undo/guidance/drag JVM set: 79/79 PASS.
- Frozen feature/log JVM suite: 256 tests, 0 failures.
- `./gradlew :feature:log:lintDebug :app:assembleDebug` — PASS.
- Root-exclusive `./gradlew :feature:log:connectedDebugAndroidTest` on `emulator-5554`, API 35 —
  45/45 PASS, 0 skipped, 0 failed.
- `git diff --check` — PASS.

## Evidence boundary

- The emulator regression covers Compose interaction/configuration/a11y semantics; no physical-phone haptic
  quality or spoken TalkBack listening claim is made.
- Behavior did not change, so PRD/ADR were intentionally untouched.
