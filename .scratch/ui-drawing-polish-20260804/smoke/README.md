# Smoke matrix — ui-drawing-polish-20260804

Capture metadata for ticket **13** (screenshot matrix + program close-out).
Screenshots are **human review evidence only** — not a golden CI pixel gate and
not a permanent production test root (`.scratch/` trackers may be removed after
close-out; see `docs/prd/tech.md` §2.1).

## Device / API

| Field | Value |
|-------|--------|
| AVD | `lezi_api35` (`emulator-5554`) |
| Product | `sdk_phone64_x86_64` / `Android SDK built for x86_64` |
| ABI | `x86_64` |
| API | **API 35** (Android 15) |
| Package | `com.lezi.babylog.debug` |

## Build identity

| Field | Value |
|-------|--------|
| versionName | `0.3.6-debug` |
| versionCode | `13` |
| Base tree (capture time) | `b7f8991c58d4b09943e79786dde8a11a9fa996cb` (`b7f8991c`) |
| Capture date | 2026-08-04 |

Rebuild note: install with `./gradlew :app:installDebug` on the same AVD when
re-capturing; update this table if the tree or versionName changes.

## Naming

Stable cell names:

```text
{style}-{mode}-{surface}.png
```

- **style:** `warm` \| `journal`
- **mode:** `light` \| `dark`
- **surface:** `record` \| `summary` \| `growth` \| `family` \| `menu`

Example: `warm-light-record.png`, `journal-dark-menu.png`.

Full matrix: **2 × 2 × 5 = 20** PNGs in this folder.

## Corpus

All cells were captured on a **local-only empty-day device** (no joined family,
no care records, no growth measurements, no summary series). Phase F allows
empty record; this program close-out deliberately uses that empty/local-only
corpus as the acceptance matrix rather than a with-data dual-device set.
With-data polish from earlier tickets remains covered by their own unit /
device tests (lazy growth keys, members list, photo LRU, summary empty≠calculating).

## How captured

1. Emulator `lezi_api35` with debug APK foreground.
2. Menu → 显示设置: select 温暖卡片 / 紧凑记录簿 and 浅色 / 深色; dismiss with 完成.
3. Bottom nav: 记录 / 汇总 / 成长 / 账户 / 菜单; `adb shell screencap -p` per cell.

Human acceptance judgments (contrast, empty-vs-loading readability) live in the
ticket close-out note, not in this capture README.
