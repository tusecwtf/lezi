# 13 — Screenshot matrix + program close-out

**What to build:** Capture warm/journal × light/dark screenshots for record,
summary, growth, family/account, and menu; store under this tracker’s smoke
folder; run program gates; mark the program complete in scratch index. Reviewers
can accept polish from evidence, not memory.

**Blocked by:** 02 — Bounded local photo LRU; 07 — Summary density; 09 — Family lazy members; 11 — Weak surfaces; 12 — Presentation format (or wontfix).

**Status:** done

- [x] Matrix images present under `.scratch/ui-drawing-polish-20260804/smoke/` with stable names
- [x] `smoke/README.md` records device/API and build identity
- [x] Dark cells still show readable status bar / nav / semantic accents (no contrast regression)
- [x] `./gradlew :app:assembleDebug test lintDebug` green
- [x] Parent ISSUES/spec status and `.scratch/README.md` updated to complete when accepted

### Close-out note (2026-08-04, amended after review)

Captured full warm/journal × light/dark × five-surface matrix (20 PNGs) on AVD
`lezi_api35` API 35 with `com.lezi.babylog.debug` `0.3.6-debug` at tree
`b7f8991c`. Evidence is **human-only** under tracker `smoke/`; no permanent
StructureTest over scratch paths (tech.md §2.1 / AGENTS.md ban).

**Corpus:** empty-day / local-only (no joined family, no records, no growth
rows, no summary series). Chosen deliberately for Phase F empty-path coverage;
with-data polish from blockers stays on their unit/device tests, not this matrix.

**Dark contrast (capture-time observation, all five surfaces × both templates):**
status bar icons readable light-on-dark; bottom nav selected pill + labels
readable; semantic accents (dock chips, summary metric tints, destructive menu
red) remain distinct from surface. Surfaces checked: record, summary, growth,
family, menu on both warm-dark and journal-dark.

**Empty vs loading (capture-time observation):** empty cells use static full
stroke rings + empty copy (`StateKind.Empty`); loading is a separate
`CircularProgressIndicator` path with primary color (tickets 06/07 contracts).
Rings can still feel track-like next to primary metric accents on light cells;
they are not animated spinners. No empty-glyph product change in this ticket.

Program 01–13 complete.
