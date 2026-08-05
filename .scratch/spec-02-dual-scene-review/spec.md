# Spec 02 follow-on · Dual APK multi-scene video + UI behavior review

**Status:** ready-for-agent  
**Parent freeze:** [`.scratch/spec-02-apk-1.0-freeze/spec.md`](../spec-02-apk-1.0-freeze/spec.md) (S-freeze · 1.0.0 shell)  
**Engineering companion:** `greenfield/docs/specs/02-apk-visual-parity.md` (E/T/A · §2.3 deferred)  
**Evidence home:** `greenfield/docs/specs/baselines/` (+ motion clips under `motion-prototype/` or successor path)

---

## Problem Statement

1.0.0 is frozen on **S-freeze** (empty 记录 home + 配方奶 Composer structure). That does **not** prove multi-scene interaction or motion language parity with 0.3.x. Caregivers still hit Tab switches, other Composers, timeline swipe, timer, more sheet, and layout editor daily. Without a **scene-by-scene dual-install video + behavior rubric**, agents cannot systematically accept or residual those paths—or know when §2.3 blind chrome is worth retrying.

## Solution

Run a **post-1.0.0, acceptance-first** campaign: dual-install GF 1.0.0 vs legacy 0.3.x on emulator, **per refined scene**, capture **video** (optional stills), score **structure / interaction / motion language**, and write residuals. Default is **review-only**—UI code changes are separate polish tickets unless a scene ticket explicitly says otherwise.

## Scope of tickets

See [`ISSUES.md`](./ISSUES.md). **21** vertical slices: matrix+rubric → harness → per-scene capture/review → rollup.

## Defaults locked at publish

| Decision | Choice |
|----------|--------|
| Granularity | **21** tickets (Composer split by type; swipe split half/full edit/delete) |
| P1 surfaces | **18–20 kept** (summary / growth / template) as optional later; still listed |
| Swipe | **11 / 12 / 13** separate |
| 母乳 vs 计时 | **10** separate from **15–16** |
| Ticket 21 | **Acceptance-only** rollup; no Compose edits in-ticket |
| Pixel CI / NAS CD / Spec 01 | Out of scope |

## Relationship to 1.0.0

- Does **not** reopen S-freeze gate.  
- Does **not** claim 无感替换 complete.  
- May later recommend §2.3 retry after residuals shrink.

## Deliverables (agent)

- [uiux-diff-spec.md](./uiux-diff-spec.md) — **canonical UI/UX 差异 inventory**  
- [matrix.md](./matrix.md) · [residuals.md](./residuals.md) · [rubric.md](./rubric.md)  
- Engineering pointer: `greenfield/docs/specs/03-uiux-diff-inventory.md`  
- Evidence: `greenfield/docs/specs/baselines/scenes/` (P0 03–17 dual capture)  
- **Acceptance re-verify (gaps):** [../spec-02-acceptance-verify/](../spec-02-acceptance-verify/)

## Comments

- 2026-08-05: Breakdown approved at 21-ticket granularity; remaining quiz defaults applied (P1 kept, swipe split, 母乳 separate, rollup acceptance-only).  
- 2026-08-05: P0 dual capture + uiux-diff-spec published; ticket 21 rollup done; §2.3 still deferred.
