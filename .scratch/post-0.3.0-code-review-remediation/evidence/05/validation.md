# 05 validation — Baby and family profile coordinator

## Fixed point and scope

- Implementation base: `b528731`.
- `CareLog.kt`: 2,759 → 2,267 lines; `BabyFamilyProfileCoordinator.kt`: 602 lines.
- All Baby create/update/delete/current, local theme/order, family scaffold, orphan reconciliation and merge
  algorithms moved behind the stable `CareLog` facade.
- A 535-line mechanical equivalence comparison against the pre-split implementation differed only by the
  coordinator boundary/visibility separator; no data, permission or ordering rule was rewritten.

## TDD and structure

- RED: `BabyFamilyProfileCoordinatorStructureTest` failed because the coordinator file did not exist.
- GREEN: the structure test locks 21 facade delegates, coordinator `<1000`, CareLog `<2500`, and rejects
  Baby observation/write, merge transaction and reminder reprojection logic returning to the facade.
- Remaining Record/CarePlan mutations call the same `babyProfiles.requireActiveBaby` authority seam.

## Transaction and regression evidence

- Merge still holds the existing sleep mutex and one database transaction for Record, CarePlan, media,
  authority Baby and UUID migration writes; current-Baby reassignment, reminder/calendar cleanup/reprojection
  and local sync remain post-commit in the original order.
- `./gradlew :domain:testDebugUnitTest :sync:testDebugUnitTest
  :feature:family:testDebugUnitTest :feature:onboarding:testDebugUnitTest` — 600/600 PASS
  (domain 278, sync 302, family 15, onboarding 5; no failures/errors/skips).
- `./gradlew :domain:lintDebug :sync:lintDebug :feature:family:lintDebug
  :feature:onboarding:lintDebug :app:assembleDebug` — PASS.
- `git diff --check` — PASS.

## Evidence boundary

- This is a domain-only structural move; it adds no device, physical NAS or UI behavior claim.
- NAS-authoritative Baby, member orphan reconciliation and local-only appearance/order contracts did not
  change, so PRD/ADR were intentionally untouched.
