# 03 — 记录首页 · 空态 IA

**Status:** done

## Parent

[spec-02-dual-scene-review/spec.md](../spec.md) · freeze [../spec-02-apk-1.0-freeze/spec.md](../../spec-02-apk-1.0-freeze/spec.md)

## What to build

Dual-install video (and optional still) of **empty-data 记录 home** after cold start / offline baby path.

Script both apps to 记录 tab with no 护理记录. Capture ≥2s dwell. Score structure: five tabs, dock four slots + 更多, empty-state copy guiding to dock, top utility entries if present. Motion optional (static ok). Do not fail on top-bar color language or day-summary five chips (non-compare).


## Acceptance criteria

- [x] Dual mp4 (or still+short clip) for empty 记录 home on GF and legacy → `baselines/scenes/log-home-empty-ia/`
- [x] 结构: five tabs + dock 4+更多 + readable empty state — scored **pass**
- [x] Non-compare residuals (top bar / E4 chips / icons) not marked fail
- [x] Rubric row for scene 03 filled; residual line written


## Blocked by

02 — 双装录制 harness + 种子数据约定
