# Design notes — 06 record density + empty language

## Public seams under test

Observed without reaching private helpers. Expected values from ticket 06 /
`LeziDensity` product literals / empty-day copy already shipped on the log list.

1. **Template structural density consumption (record high-frequency paths)**
   - Top bar horizontal inset: `LeziThemeExt.density.topBarHorizontal`
     (not legacy `LeziSpacing.TopBarHorizontal`) on record-facing chrome:
     - `PageComponents` (`LeziDetailTopBar`, `AppBrandBar`)
     - `AppHeaderBar` (main record-tab top bar)
   - Three-day timeline chrome (`TimelineRailCard`):
     - content pad → `density.panelContent` (warm open > journal compact)
     - section gap → `density.sectionGap`
     - no off-grid ad-hoc 18/10/6 content/section pads on those roles
   - Quick dock structural outer inset: `density.dockOuterHorizontal`
     (warm = top-bar role; journal full-bleed `0.dp` in the density table —
     no local `if (journal)` pad branch)
   - Empty/loading `StateContainer` card shell: `density.cardPad`
   - Confirm reason card pad: `density.cardPad` (replaces off-grid 14/10)
   - Log list section spacing (warm): `density.sectionGap`
   - Touch targets remain ≥ `LeziSpacing.Touch` (48dp); density does not
     shrink interactive mins.

2. **Warm more open than journal (same roles)**
   - Policy already encoded in `LeziDensity.Warm` / `Journal` (ticket 01).
   - Call sites must route through density so the open-vs-compact difference
     is visible on record chrome, not only in the token table.

3. **Empty day ≠ loading**
   - Log day empty branch: `StateKind.Empty`, title `"还没有记录"`, guidance to
     dock; loading branch: `StateKind.Loading` with spinner only.
   - `StateContainer` empty mark is a ring (not `CircularProgressIndicator`)
     and uses a quieter color than loading so empty does not read as busy.
   - Timeline axis / hit-testing / marker layout math are not redesigned.

## Out of scope

- Summary chart density / empty vs calculating (ticket 07)
- Shell motion + reduce-motion (ticket 10)
- Timeline axis DST math, hit radii, mark layout formulas
- CareLog writes, sync wire, Room schema
