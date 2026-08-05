# Issues — test redundancy cleanup

| ID | Title | Status |
|----|-------|--------|
| 00 | Policy grill lock + inventory | **done** (spec + inventory) |
| 01 | Wave 1 PROCESS-FIX dirty family/composer | **done** |
| 02 | Wave 1 DELETE Structure/scan suites | **done** |
| 03 | Wave 1 optional REWRITE (bottom-nav / cleartext / BareMaterial) | **done** — cleartext `NetworkSecurityConfigTest`; BareMaterial suite restored; bottom-nav behavior via `bottomNavLongPressOnly` + `BottomNavShortPressDeviceTest` |
| 04 | Wave 2 layout/calendar device compress | **done** |
| 04b | Wave 3 guidance thin + owner-takeover JVM + network security | **done** |
| 05 | Kitchen-sink method inventory (out of round unless reopened) | deferred |
| 06 | Rust lezi-sync test inventory | out of scope |

## Wave 1 landed (2026-08-05)

**PROCESS-FIX**
- Dropped product-only `testTag("members_devices_list")`; empty-state uses `performScrollTo()` on public copy「暂无设备」.
- Kept `RecordComposerDiscardDeviceTest` `material3.Button` import (harness compile).

**DELETE**
- `ContractSupersededSurfacesTest`
- `BareMaterialWhitelistContractTest` + `DesignsystemSourceFixtures`
- `UiAuditPathContractTest`
- `ClockDialNursingChromeContractTest`, `NursingConfirmSurfaceContractTest`
- `NextFeedPhotoQrChromeContractTest`, `PhotoPreviewDialogTest`
- `SummaryDensityEmptyContractTest`

**KEEP-PARTIAL**
- `WeakSurfacesContractTest`, `JournalThemeAndDockPolicyContractTest`, `RecordDensityEmptyContractTest`, `LeziTextFieldLineModeTest` — token/runtime only

**Gates run:** `:designsystem:testDebugUnitTest`, `:feature:widget:testDebugUnitTest`, `:feature:summary:testDebugUnitTest`, `:app:testDebugUnitTest`, `:feature:family:compileDebugKotlin`, `:feature:log:compileDebugAndroidTestKotlin` — green.

**Known accepted gaps:** no automated bare-Material chrome scan; no source-string dead-surface / bottom-nav wiring tests.

## Wave 2 landed (2026-08-05)

**COMPRESS (device → JVM receipts)**

| Deleted device suite | Retained coverage |
|----------------------|-------------------|
| `LayoutCategoryDragDeviceTest` | `LayoutDragSessionTest` category heading drop/no-op/domain + `LocalLayoutEditPolicyTest` `MoveCategoryToIndex` |
| `LayoutEditDropMatrixDeviceTest` | `LayoutDragSessionTest` locked-more/dock-gap/clear/restore/assign matrix |
| `LayoutEdgeAutoScrollDeviceTest` | `LayoutEdgeAutoScrollPolicyTest` edge bands / authorize / cancel-at-boundary |
| `CalendarEmptyDayStateTest` | New JVM `CalendarEmptyDayChromeTest` + pure helpers; date schedule policy in `CalendarMonthStateTest` |

**Product:** `calendarEmptyDayMessage` / `calendarEmptyDayActionLabel` extracted for L1 seam (no user-visible change).

**Kept device (not compressed):** layout a11y, config recreation, haptics, local-deleted fontScale/viewport, undo snackbar, drag guidance, target registration.

**Gates:** layout + calendar JVM unit tests green; androidTest compile for log still green.

## Wave 3 landed (2026-08-05)

| Change | Receipt / note |
|--------|----------------|
| Thin `LayoutDragGuidanceDeviceTest` → recreation-only | Policy/completion in `LayoutDragGuidanceTest`; drop touch-channel device smoke (same class as Wave 2 gap) |
| Delete `OwnerTakeoverConfirmationDeviceTest` | `OwnerTakeoverChrome` + `OwnerTakeoverChromeTest` |
| Add `NetworkSecurityConfigTest` | Release cleartext off + debug loopback-only domains (XML/manifest data, not Kotlin Structure scan) |

## Gate restore landed (2026-08-05)

| Gate | Rewrite |
|------|---------|
| BareMaterial Phase A | Restored `DesignsystemSourceFixtures` + `BareMaterialWhitelistContractTest` (intentional chrome whitelist) |
| Bottom-nav short-press | `Modifier.bottomNavLongPressOnly` used by `MainActivity`; `BottomNavShortPressDeviceTest` proves short-press fires `onClick`, long-press does not steal it |
| Cleartext | already `NetworkSecurityConfigTest` |

**Still deferred:** kitchen sinks (Q2=A), Rust (Q6=A), detekt alternative for BareMaterial (suite is the gate).
