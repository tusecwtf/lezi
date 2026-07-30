# Ticket 25 — contract-superseded surface closure

- Date: 2026-07-30 (Asia/Shanghai)
- Validation base: `2d69aeab0e819c67c183c572cb8b8683eec4877c`
- Device: `emulator-5554`, Android API 35

## Blocker and caller audit

Tickets 09, 10, P1/02, 17 and 24 were `complete` before this contract pass. Their retained
automated/device receipts prove the migrated behavior. The current production caller audit shows:

- Onboarding and Account each construct `FamilyWizardController`; the retired
  `OnboardingOwnerEntryController` has no production definition or call site.
- RecordComposer and NursingTimer each render `LeziNextFeedPlanFlow`; the independent
  `FeedReminderPort`, `NextFeedScheduler`, `NextFeedReceiver` and `scheduleAfterFeed` chain had no
  caller and is now absent.
- `RecordType.businessLabel()` has one production declaration. Historical custom/unknown Widget
  snapshots still use the Ticket 17 compatibility codec; no storage or display contract was
  removed.
- `normalizeOpenSleeps` has one pure declaration and exactly two production adapters: CareLog and
  replica pull.
- P1/02's deleted layout dialog, `settings/quick-records` route and compatibility shims remain
  absent. Four explicit quick slots plus More still use the current layout snapshot contract.

The unused `LeziGlyph`/`LeziGlyphIcon` surface and v1 `care_widget_info.xml` had no production
reference and were deleted. The manifest continues to reference `care_widget_info_v2`.

## Red to green

The contract regression was added first:

```text
./gradlew :app:testDebugUnitTest \
  --tests com.lezi.babylog.ContractSupersededSurfacesTest
```

RED result: 3 tests ran and 2 failed while the independent next-feed alarm and unused
glyph/widget-v1 resources were still present.

After the mechanical deletion, the combined Green gate passed:

```text
./gradlew :app:testDebugUnitTest \
  --tests com.lezi.babylog.ContractSupersededSurfacesTest \
  :core:database:testDebugUnitTest \
  :core:datastore:testDebugUnitTest \
  :domain:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL`; all four targets passed.

## Compatibility boundary

- `SettingsLocal` and DataStore no longer expose the unreachable independent next-feed timestamp
  or epoch. Existing stored preference keys are ignored; no user fact or CarePlan is rewritten.
- Room's `nextFeedAt` / `nextFeedEpoch` columns remain frozen in the current entity and exported
  schemas. Production always writes null/empty and never reads them, preserving the Room schema
  while removing the retired behavior contract.
- CarePlan, Record, family-sync wire payloads, historical custom snapshots, open-sleep repair,
  system-calendar projections and four-slot layout models are unchanged.
- Boot recovery remains registered and now reschedules the sole authoritative CarePlan reminders.

## Full gates

```text
./gradlew test lintDebug assembleDebug
```

Result: `BUILD SUCCESSFUL in 35s`; 1387 tasks (461 executed, 14 from cache, 912 up-to-date).
This includes Debug and Release JVM tests for all modules, every module's `lintDebug`, and Debug
assembly. `git diff --check` also passed.

API 35 regression smokes passed serially on the same emulator:

```text
./gradlew :designsystem:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.designsystem.NextFeedPlanFlowDeviceTest
# 3/3 passed

./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.LayoutAccessibilityDeviceTest
# 8/8 passed

./gradlew :feature:family:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.family.FamilyAccountAffordanceSemanticsTest
# 2/2 passed
```

Family create/reclaim/join equivalence, canonical labels and both open-sleep adapters are covered
again by the successful full JVM gate. Their previously captured real-entry/pure-rule receipts
remain under evidence directories 09, 17 and 24; this mechanical contract ticket does not claim a
new NAS interaction or spoken TalkBack run.
