# Motion prototype — dual APK interaction clips

**PROTOTYPE** (throwaway capture harness). Spec 02 A1–A5 / §2.3 motion language review.  
**When:** 2026-08-05T16:41:02+08:00  
**Device:** `emulator-5554` · 1080x2400  
**Mode:** `both`

## Question this answers

Can emulator `screenrecord` + short scripted gestures give **broader** dual-APK evidence than static PNGs — enough for 1:1 **interaction/motion** review (Tab crossfade, Composer sheet, swipe, timer, more, layout)?

## Scenes

| Scene | Token intent | GF | Legacy |
|-------|--------------|----|--------|
| tab-crossfade | A2 Base | `gf/tab-crossfade.mp4` | `legacy/tab-crossfade.mp4` |
| composer-open | A4 Base sheet | `gf/composer-open.mp4` | `legacy/composer-open.mp4` |
| timeline-swipe | A5 Fast + commit | `gf/timeline-swipe.mp4` | `legacy/timeline-swipe.mp4` |
| more-sheet | A4 | `gf/more-sheet.mp4` | `legacy/more-sheet.mp4` |
| timer-open | A4 | `gf/timer-open.mp4` | `legacy/timer-open.mp4` |
| layout-editor | A3 Emphasized | `gf/layout-editor.mp4` | — |

Contact sheets: `strips/*`. Side-by-side: `compare/*-side-by-side.mp4`.

## Re-run

```bash
./greenfield/scripts/prototype-capture-motion-clips.sh emulator-5554 both
```

## Limits (honest)

- Emulator timing ≠ physical 60fps feel; use for **language** (duration class, ease, reveal) not pixel-perfect duration audit.
- Legacy automation is best-effort (welcome / dock labels drift; `更多`/`计时` miss after empty/seed drift in this run).
- Tab crossfade is often **subtle** on contact strips (fps=2 tile) — scrub the mp4; strip is for coarse presence.
- Not a substitute for non-implementer §2.3 blind review with live dual-install.
- Large binaries (~7MB this run): **do not commit** unless product wants them; keep harness, wipe clips after review or LFS.

## First-run notes (2026-08-05 emulator-5554)

| Clip | GF | Legacy | Review signal |
|------|----|--------|---------------|
| tab-crossfade | ok ~5.5s | ok | A2: content swap present; ease hard to judge on strip alone |
| composer-open | ok | partial (long clip / welcome residual risk) | A4 sheet open/close |
| timeline-swipe | **strong** (~7.4s) green 编辑 reveal in strip | weak (no seeded row) | A5 GF evidence good; re-seed legacy before re-run |
| more / timer | ok | miss → idle chrome only | fix: force log-home + exact dock before tap |
| layout-editor | ok | n/a this run | A3 enter/drag/exit |

**Prototype answer:** yes — `screenrecord` + scripted gestures + ffmpeg strips/side-by-side is enough for **broader motion review** than static PNGs, and for **1:1 interaction language** (reveal, sheet, tab swap) on GF. True “一比一” duration/easing still needs human scrub of dual mp4s (or live dual-install), not pixel CI.

## Verdict placeholder

- [x] Harness answers “can we capture broader dual interaction?” → **yes**
- [ ] Motion language GF ≈ legacy on A2/A4/A5 (pass / residual) — human scrub `compare/*`
- [ ] Keep harness / fold into `capture-uiux-baselines.sh` / delete clips
