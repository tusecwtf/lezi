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
claimed as a spoken TalkBack smoke.

## Fixed-APK full-app touch smoke

The shared API 35 `emulator-5554` was available after the connected tests. The installed fixed APK
was built from `e6742f1a3bcde05443b68dcb170b83b16ab69ccb` (which contains Ticket 26 implementation
commit `6263af4`):

```text
package: com.lezi.babylog.debug
version: 0.2.6-debug
size: 28257340 bytes
sha256: 8b6cca08e1ddad2610ad769ecc7a4b4a0d8bf2ebbe9998bffe31ef9e7355fc2e
```

- Account rendered the read-only baby row `计时宝宝（当前）` without a chevron or click semantics.
  A physical tap on the row left the page on `账户`; no editor or unrelated route opened.
- The offline sync entry exposed `同步状态：还没和家人一起记`, Button semantics and bounds
  `[79,1786][1001,1912]`. At physical density 420 this is exactly 48dp high. A physical tap on the
  row opened `家庭网络设置`, whose visible result included server, two Wi-Fi fields and
  `保存家庭网络与服务器`.
- Menu rendered the genuinely actionable baby row with `计时宝宝（当前）`, the explanatory result
  `出生 2026-07-30 · 本机外观与顺序` and a chevron. A physical whole-row tap opened the single
  matching result `计时宝宝的本机设置`, including the local-only explanation and theme controls.
- The app remained focused in `MainActivity`; no crash or parallel family wizard appeared.

The AVD still reports `enabled_accessibility_services=null`, `accessibility_enabled=0`, and only the
system accessibility menu package—not TalkBack. Therefore closure combines connected semantic
coverage with real full-app touch routing and continues to make no spoken-TalkBack claim.
