# Spec 03 — UI/UX 差异 inventory（GF 1.0.0 vs 0.3.x）

**Status:** agent inventory complete (P0 dual-install)  
**Authority (tracker):** [`.scratch/spec-02-dual-scene-review/uiux-diff-spec.md`](../../../.scratch/spec-02-dual-scene-review/uiux-diff-spec.md)  
**Matrix:** [`.scratch/spec-02-dual-scene-review/matrix.md`](../../../.scratch/spec-02-dual-scene-review/matrix.md)  
**Parent visual parity:** [02-apk-visual-parity.md](./02-apk-visual-parity.md)  
**Evidence:** [baselines/scenes/](./baselines/scenes/)

This file is the **engineering pointer**. Full problem/solution, scored matrix, R-* catalog, polish backlog, and reproduce commands live in the tracker document above.

## Snapshot (do not diverge)

| Gate | Status |
|------|--------|
| S-freeze / 1.0.0 shell | PASS |
| P0 dual-scene capture (03–17) | done |
| Formula default 120 + chips + 确认记录 | aligned |
| §2.3 blind chrome | deferred fail |
| User-visible debug (sleep epoch, summary D0 dump) | **open P0 polish** |

## Next polish (from inventory)

1. Sleep Composer: no raw epoch  
2. Summary: no D0–D6 debug dump  
3. Plan/PENDING Chinese product copy  
4. Harness: mid-gesture stills; dismiss legacy next-feed before shot  

## Out of scope here

Pixel CI · NAS CD · Spec 01 migration · reopening S-freeze for top-bar/E3/E4 alone
