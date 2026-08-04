# 12 — Presentation format convergence (conditional)

**What to build:** If, after record and family surface work, the same baby still
shows disagreeing birthday/weight/duration formats across settings/family/log,
converge through existing presentation helpers so one journey never shows two
formats. If no drift remains, close as wontfix with a short note.

**Blocked by:** 06 — Record surface density + empty language; 09 — Family: lazy members/devices.

**Status:** done

- [x] Audit baby meta / duration display on settings, family overview, and log-adjacent surfaces
- [x] Either formats share one helper path, or ticket marked wontfix with evidence of consistency
- [x] No second formatting library; no wire/schema changes
- [x] Targeted tests if a helper changes behavior

### Close-out note (2026-08-04, amended after review)

Audit first closed as wontfix, but review found **real duration drift** on the
log day-summary nursing chip (`Nm` journal / `Nmin` warm vs sleep
`formatRecordDuration`). Amended implementation:

1. **Duration totals (fixed):** journal + warm nursing day chips and spoken
   values use `logDaySummaryDuration` → `formatRecordDuration`, matching sleep
   and SummaryScreen nursing. Milk payload total duration also uses the helper
   (`耗时 8m` / `耗时 1h5m`). Widget sleep totals aligned to the same helper.
2. **Birthday/weight:** already shared via `babyMetaLine`; form/onboarding/export
   date labels now call `formatBabyBirthday` (single pattern owner). Settings
   subtitle via `settingsBabyLocalSubtitle`; family cards via
   `familyCurrentBabyMeta` / `familyListBabyMeta`.
3. **Intentional non-collisions (documented, not “no drift”):** live timer
   `m:ss`; nursing **per-side** `左N分/右N分` (payload + chart detail, searchable);
   growth measurement kg. See `design_notes_12_presentation_format.md`.

No second formatting library; no wire/schema changes.
