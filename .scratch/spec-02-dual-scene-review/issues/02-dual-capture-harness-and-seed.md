# 02 — 双装录制 harness + 种子数据约定

**Status:** done

## Parent

[spec-02-dual-scene-review/spec.md](../spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../../spec-02-apk-1.0-freeze/spec.md)

## What to build

Harden dual-install capture so any scene ticket can re-run without tribal knowledge.

Deliver a maintainable harness (evolve the motion prototype; do not require pixel CI) that:
- Targets GF `com.lezi.babylog.gf` 1.0.0 and legacy debug package on a booted emulator
- Records short mp4 per scene id for gf and legacy; optional side-by-side and contact strips
- Documents **seed profiles**: empty baby; ≥1 配方奶 护理记录; multi-row for swipe; data-ish states for summary/growth if needed
- Survives uiautomator text pitfalls (exact text; avoid false-positive 布局/记录 matches)
- Never skips confirm-before-write when seeding; never prints secrets


## Acceptance criteria

- [x] One documented command re-runs dual capture for a scene id (or documented scene list)  
      `./greenfield/scripts/dual-scene-capture.sh [serial] [scene-id|all] [both|gf|legacy]`
- [x] Seed profiles documented and used by dependent tickets (empty / seeded row / multi-row)
- [x] GF + legacy packages resolved; missing legacy fails closed with clear message
- [x] Output layout stable for tickets 03–20 evidence paths → `baselines/scenes/<id>/`
- [x] Large binaries policy stated (local review; no force-add of dist-like blobs)


## Blocked by

01 — 场景矩阵 + 统一 rubric
