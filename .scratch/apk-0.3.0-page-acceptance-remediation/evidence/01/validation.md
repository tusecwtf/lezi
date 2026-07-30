# UIQA ticket 01 validation · Snackbar above quick dock

Baseline: `3baa887`

## RED / GREEN

- `RootRoutingPolicyTest` first failed to compile because the root Snackbar inset policy and the
  quick-dock-derived clearance seam did not exist.
- `QuickDockVisualSpec` is the single geometry source: 82 dp occupied dock height plus 8 dp safety
  spacing produces a 90 dp Snackbar bottom inset.
- The inset applies only to the everyday Log route while layout edit is inactive. Timer, search,
  export, calendar, summary, growth, family, settings, and layout edit retain 0 dp.
- Snackbar text/duration and quick-dock size/position were not changed.

## Automated validation

- `./gradlew :feature:log:testDebugUnitTest` — PASS, 256 tests.
- `./gradlew :app:testDebugUnitTest` — PASS, 12 tests including the real routing/inset policy.
- `./gradlew :feature:log:lintDebug :app:lintDebug :app:assembleDebug` — PASS.
- `git diff --check` — PASS.

## API 35 device evidence

- Device: `emulator-5554`, Android API 35, 1080 x 2400 at 420 dpi.
- Installed `com.lezi.babylog.debug`, created an isolated virtual family/baby against a temporary
  local server, saved a Pee record, then saved an edit to trigger `已保存修改`.
- UI hierarchy: Snackbar container `[32,1670][1048,1796]`; quick-dock container
  `[21,1859][1059,2053]` and cells begin at y=1872. The rectangles do not overlap; the nearest
  container edges retain a 63 px gap.
- While the Snackbar was live, tapping the More cell at `(950,1950)` opened the `添加记录` sheet,
  proving the hot area remained reachable.
- Screenshot: `snackbar-above-dock.png`, SHA-256
  `72df58372480c32ffb1bb289ebe7a65d83e11c1f53b2dc81529bc1b1c487fa35`.

## Evidence boundary

- This is emulator evidence, not a physical-phone or spoken TalkBack claim. The temporary server
  and virtual family contain no real family, SSID, or user data.
