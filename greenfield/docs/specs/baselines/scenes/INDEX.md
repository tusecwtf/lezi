# Dual-scene capture index

**When:** 2026-08-05T17:38:35+08:00  
**Device:** `emulator-5554` · 1080x2400  
**Mode:** `both` · scene arg: `layout-editor-drag`

## Command

```bash
./greenfield/scripts/dual-scene-capture.sh emulator-5554 <scene-id|all> both
```

## Layout

`scenes/<scene-id>/{gf,legacy,compare}.{mp4,png}` · strips `*-strip.jpg`

## Matrix / rubric

- `.scratch/spec-02-dual-scene-review/matrix.md`
- `.scratch/spec-02-dual-scene-review/rubric.md`

## Policy

Large videos are **review-local** — do not force-add to git. Missing legacy package **fails closed**.
