# Design notes — 07 summary density + empty/calculating honesty

## Public seams under test

Observed without reaching private helpers. Expected values from ticket 07 /
`LeziDensity` product literals / empty language already on chart empty branches /
`LeziMotion` tiers from ticket 01/10.

1. **Summary chart card structural density**
   - Chart / week / temperature panel shells pass
     `contentPadding = PaddingValues(density.cardPad)` (or
     `LeziThemeExt.density.cardPad`) — not legacy `LeziSpacing.CardPad` alone.
   - Warm KPI metric cards (`CompactMetricCard`) use density `cardPad` for
     structural pad (no off-grid 14dp `CardPad` vertical).
   - Journal week grid panel uses density `panelContent` (or `cardPad`) for
     content pad — not ad-hoc `LeziSpacing.Sm` as the only structural pad.
   - Vertical section spacing between summary blocks uses
     `density.sectionGap` (warm open / journal compact) rather than
     `if (journal) 0.dp else LeziSpacing.Sm`.
   - Page-level `LeziSpacing.Page` inset may remain (page role, not chart-card
     density).

2. **Chart mark sizes use density conversion**
   - Bar corner radii, line-chart dots/strokes, journal week-grid mark radii
     are expressed as `N.dp.toPx()` (or `N.sp.toPx()` for label paint) — no
     raw float radii / stroke widths for chart marks (e.g. ban
     `Stroke(if (journal) 1.5f else 2f)` style).
   - Bar slot geometry stays pure (`calculateBarSlotLayout`); aggregation math
     unchanged.

3. **Empty range ≠ calculating**
   - Calculating branch: `StateKind.Loading`, title `"正在计算汇总"`, spinner
     path; test tag `summary_calculating`.
   - Per-metric empty branches: `StateKind.Empty` with range-empty copy
     (`范围内暂无…`), not Loading / not calculating title; addressable tags
     `summary_chart_empty_feed` / `_sleep` / `_diaper`.
   - Empty mark language remains the shared ring (designsystem), not a spinner.

4. **Range / content transitions use motion tokens**
   - Calculating ↔ content `Crossfade` duration from
     `leziMotionMillis(LeziMotion.Base)` (not bare default / magic ms).
   - Range `AnimatedContent` enter/exit fades use `leziMotionMillis` with
     `LeziMotion.Base` / `LeziMotion.Fast` (reduce-motion → 0 via existing
     policy).

5. **Aggregation math unchanged**
   - Existing `SummaryAggregation*` tests remain the contract for totals /
     day buckets / chart windows; this ticket does not alter them.

## Out of scope

- Record surface density (ticket 06)
- Shell reduce-motion wiring beyond consuming `leziMotionMillis` (ticket 10)
- Growth lazy history (ticket 08)
- Merging SummaryMetric and RecordSummaryStrip
- CareLog writes, sync wire, Room schema
