# 12 — Presentation format convergence

Conditional ticket: converge birthday/weight/duration when surfaces disagree
after 06/09. Review found residual **duration** drift on the log day strip;
this close-out **fixes** that path and documents intentional non-collisions.

## Public seams

1. **Baby meta (birthday + birth weight)** — `core/ui`  
   - `formatBabyBirthday(epochDay)` → `yyyy年M月d日` (sole pattern owner)  
   - `formatBirthWeightKg(grams)` → `3kg` / `3.20kg`  
   - `babyMetaLine(...)` → `…出生 · 出生体重 …`  
   - `settingsBabyLocalSubtitle(...)` → settings baby row
2. **Historical duration totals** — `core.model.formatRecordDuration(minutes)`  
   - Compact: `0m` / `Nm` / `Nh` / `NhNm`  
   - Day summary: `logDaySummaryDuration` (journal + warm nursing & sleep)  
   - Sleep payload summary, composer draft, summary feed window nursing, widget sleep
3. **Baby age** — `domain.carelog.babyAgeLabel` (header + family cards)
4. **Family meta assembly** — `familyCurrentBabyMeta` / `familyListBabyMeta`  
   (compose age + `babyMetaLine` + sex/dup; no re-format of birthday/weight)

## Call-site map (verified)

| Surface | What | Helper path |
|---------|------|-------------|
| Settings baby row | birthday + birth weight | `settingsBabyLocalSubtitle` → `babyMetaLine` |
| Family overview cards | birthday + birth weight (+ age/sex) | `family*BabyMeta` → `babyMetaLine` |
| Form / onboarding / export date labels | birthday | `formatBabyBirthday` |
| Log day strip nursing **total** | minutes | `logDaySummaryDuration` → `formatRecordDuration` |
| Log day strip sleep total | minutes | same |
| Summary feed window nursing | minutes | `formatRecordDuration` (via `formatFeedWindowTotal`) |
| Sleep payload / composer interval | minutes | `formatRecordDuration` |
| Milk payload **total** duration | minutes | `formatRecordDuration` (`耗时 8m` / `耗时 1h5m`) |
| Widget sleep total | minutes | `formatRecordDuration` |

## Intentional non-collisions (not drift)

| Role | Format | Why |
|------|--------|-----|
| Live nursing timer | `m:ss` (`formatTimerMs`) | Live clock, not historical total |
| Nursing **per-side** minutes (payload + day chart detail) | `左N分` / `右N分` | Side-split labels; searchable (`左10分`); always whole minutes next to L/R |
| Growth / care-record measurement weight | `normalizedText()kg` | Measurement fact, not birth-weight meta |
| Relative time / clock labels | separate helpers | Not duration totals |

## Decision

**Done (converged):** birthday/weight single-sourced; day-total and milk-total
durations use `formatRecordDuration` on log ↔ summary ↔ widget journeys.
Per-side nursing `分` and live timer `m:ss` stay documented exceptions.

## Evidence tests

- `LogDaySummaryDurationTest` — nursing/sleep day totals + spoken share tokens
- `FormatRecordDurationTest` / `RecordPresentationTest.recordDurationFormatterBoundaryCases`
- `RecordSummaryTest` milk duration uses compact helper
- `BabyMetaLineTest` + `settingsBabyLocalSubtitle`
- `FamilyOverviewFormattingTest` meta assembly (not duplicate pure-helper asserts alone)
- `WidgetModelsTest` sleep uses compact duration
