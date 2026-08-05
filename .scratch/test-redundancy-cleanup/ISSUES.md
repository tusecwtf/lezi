# Issues — test redundancy cleanup

| ID | Title | Status |
|----|-------|--------|
| 00 | Policy grill lock + inventory | **done** (spec + inventory) |
| 01 | Wave 1 PROCESS-FIX dirty family/composer | **done** |
| 02 | Wave 1 DELETE Structure/scan suites | **done** |
| 03 | Wave 1 optional REWRITE (bottom-nav / cleartext / BareMaterial lint) | skipped (accepted gap under Q3/Q7) |
| 04 | Wave 2 layout/calendar device compress | pending |
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
