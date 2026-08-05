# Spec 02 baselines — 双装对照位图

**Status:** GF + legacy captured on emulator (2026-08-05)  
**Spec:** [02-apk-visual-parity.md](../02-apk-visual-parity.md) §3.1 / §5  
**Device:** AVD `lezi_api35` · `emulator-5554` · 1080×2400 · API 35  
**Automation:** `greenfield/scripts/capture-uiux-baselines.sh`

## Re-run

```bash
# emulator already running
cd greenfield/android && ./gradlew :app:assembleDebug
../scripts/capture-uiux-baselines.sh emulator-5554
```

## Inventory (this machine)

| Scene | GF | Legacy (0.3.x debug) |
|-------|----|----------------------|
| 记录首页 warm | `log-home-warm-gf.png` | `log-home-warm-legacy.png` |
| 记录首页 + 有数据 | `log-home-with-record-gf.png` | — |
| journal 模板 | `log-home-journal-gf.png` | — |
| Composer 配方奶 | `composer-formula-gf.png` | `composer-formula-legacy.png` |
| 计时 idle / active / confirm | `timer-running-*.png` / `timer-confirm-gf.png` | — |
| 更多四列 sheet | `more-sheet-gf.png` | — |
| 滑动编辑（满行程→编辑 Composer） | `timeline-swipe-edit-gf.png` | — |
| 滑动删除（满行程→确认框） | `timeline-swipe-delete-gf.png` | — |
| 布局编辑 + 拖动手柄 | `layout-editor-gf.png` | — |
| 菜单 | `menu-gf.png` | — |

## Spec 02 element evidence

| ID | Evidence file |
|----|----------------|
| E1 TimeDial | `composer-formula-gf.png` |
| E2 Swipe | `timeline-swipe-edit-gf.png` · `timeline-swipe-delete-gf.png` |
| E5 More 4-col | `more-sheet-gf.png` |
| E6 Layout drag handle | `layout-editor-gf.png` |
| E7 Theme accent top bar | `log-home-warm-gf.png` |
| E8 Relative time | `log-home-with-record-gf.png`（「N 分钟前」） |
| E9 Timer circles | `timer-running-active-gf.png` |
| E10 Full-width confirm | `composer-formula-gf.png` |

## Motion prototype (video)

Throwaway dual-APK **interaction/motion** capture (A1–A5), not static PNG:

```bash
./greenfield/scripts/prototype-capture-motion-clips.sh emulator-5554 both
```

Outputs under [`motion-prototype/`](./motion-prototype/) (`gf/*.mp4`, `legacy/*.mp4`, `strips/*`, `compare/*-side-by-side.mp4`). See [motion-prototype/INDEX.md](./motion-prototype/INDEX.md). Prefer reviewing video locally; large mp4s are for review, not default git commit.

## Multi-scene dual harness (post-1.0.0)

Per-scene capture for tickets 03–20:

```bash
./greenfield/scripts/dual-scene-capture.sh emulator-5554 composer-formula both
./greenfield/scripts/dual-scene-capture.sh emulator-5554 all both   # P0 scenes; long
```

Outputs under [`scenes/<scene-id>/`](./scenes/) (`gf`/`legacy`/`compare` mp4+png). Matrix/rubric: [`.scratch/spec-02-dual-scene-review/matrix.md`](../../../../.scratch/spec-02-dual-scene-review/matrix.md). mp4 gitignored.

## Review

See [review-notes.md](./review-notes.md).
