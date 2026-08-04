# Design notes — 08 growth lazy history + surface polish

## Public seams under test

Observed without reaching private Compose helpers. Expected values from ticket 08
/ record-timeline lazy patterns (`LogTimelineList`, `SearchScreen`) / growth
product copy already on the page.

**Test ownership (AGENTS / tech.md §2.1):** do **not** gate LazyColumn /
`items` / `animateItem` / chrome symbol presence via product-less source-layout
StructureTests. JVM unit coverage for this ticket is the pure order helper only;
edit/save/delete stays on `GrowthMeasurementWriteCoordinatorTest`; bare Material
chrome ownership stays on designsystem whitelist suites (ticket 05).

1. **History is a lazy list with stable keys** (implementation seam)
   - Measurement history rows are composed via full-page `LazyColumn` +
     `items` with `key = { point.recordId }` and `contentType = { "history_row" }`.
   - Chart / chrome items use distinct `contentType` values so mixed heavy chart
     and many history rows recycle separately.
   - History is **not** built with `history.forEach` / `forEachIndexed` as the
     scrolling list body.
   - Page scroll uses that lazy list under pull-to-refresh (not non-recycling
     `Column` + `verticalScroll` that materializes every history row).

2. **Item animation for insert/remove** (implementation seam)
   - History row modifiers use foundation `Modifier.animateItem()` inside the
     lazy item scope — same public API as timeline/search when rows appear or
     leave.

3. **Edit / save / delete + addressability preserved**
   - `openEditMeasurement` / draft open from history row click remains.
   - Write path still routes through `saveMeasurement` /
     `requestMeasurementDelete` / `deleteMeasurement` (coordinator seams
     covered by existing write-coordinator tests).
   - Test tag `growth_shallow_sync_status` remains on the shallow sync line.
   - Do not label the full-page list `growth_history_list` (overclaims hero/
     chart/footer).

4. **Chrome stays on Lezi controls**
   - Growth route chrome continues to use `LeziPrimaryButton`,
     `LeziSecondaryButton`, `LeziTextButton`, `LeziAlertDialog`,
     `LeziTextField`, `LeziDatePickerDialog`, `LeziClockDialDialog` — no bare
     Material `Button` / `TextButton` / `OutlinedButton` product calls.
   - Regression ownership: designsystem bare-Material whitelist (ticket 05), not
     a growth-local import scan.

5. **No schema or growth reference-curve math changes**
   - `parseGrowthReferenceBands` / reference catalog and measurement write
     coordinator semantics are out of scope; this ticket only changes list
     composition.

## Pure helper (unit seam)

- `growthHistoryNewestFirst`: `sortedByDescending(MeasurePoint::measuredAt)` —
  newest at top. Covered by `GrowthLazyHistoryContractTest`.

## Out of scope

- Family members/devices lazy lists (ticket 09)
- Summary/record density (tickets 06–07)
- CareLog writes, sync wire, Room schema, reference curve data
- Product-less StructureTests that read GrowthScreen.kt source text
