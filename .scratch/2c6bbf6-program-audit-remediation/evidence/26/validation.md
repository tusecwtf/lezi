# Ticket 26 account/settings affordance validation

Date: 2026-07-30 (Asia/Shanghai)

Scope: account family overview affordances and menu/settings rows only. Ticket 09 remains the
sole account family-wizard route; this change does not add a settings-side family modal or branch.

## TDD and connected semantics

The two new public Compose seams were each compiled before implementation. The expected RED
failures were unresolved `SettingsBabyRow`, then unresolved `FamilySyncStatusEntry` and
`FamilyMemberRosterEntry`.

Connected tests on `emulator-5554`, AVD `lezi_api35`, Android API 35:

- `SettingsAffordanceSemanticsTest`: 3 tests, 0 failures.
  - read-only baby information has no click action, role or chevron;
  - an actionable row exposes one whole-row button action and a matching action label;
  - a baby row opens that baby's local settings through the single action.
- `FamilyAccountAffordanceSemanticsTest`: 2 tests, 0 failures.
  - the offline sync entry exposes its visible state, Button role, `打开网络设置` action label,
    callback result and a touch height of at least 48dp;
  - traversal order is member roster then sync status.

Commands:

```text
./gradlew --no-daemon -Pksp.incremental=false \
  :feature:settings:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.settings.SettingsAffordanceSemanticsTest

./gradlew --no-daemon -Pksp.incremental=false \
  :feature:family:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.family.FamilyAccountAffordanceSemanticsTest
```

## Static and build gates

The following single invocation completed successfully:

```text
./gradlew --no-daemon -Pksp.incremental=false \
  :feature:settings:testDebugUnitTest \
  :feature:family:testDebugUnitTest \
  :feature:settings:lintDebug \
  :feature:family:lintDebug \
  :app:assembleDebug
```

Built APK: `app/build/outputs/apk/debug/app-debug.apk`

```text
size:   28464270 bytes
sha256: 13379e4a80430cdffb504904953237599734d1475d613da817594246b92fab32
```

## Accessibility/device boundary

The API 35 AVD does not contain a TalkBack package and
`enabled_accessibility_services` was `null`. The connected semantics tests therefore verify the
TalkBack-facing name, role, state, action label, focus traversal and effective bounds, but are not
claimed as a spoken TalkBack smoke. Full-app touch smoke is recorded separately when the shared
emulator is available.
