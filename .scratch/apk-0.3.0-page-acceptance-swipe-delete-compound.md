# UIQA-20260730-P2-05 · Timeline swipe half-reveal compound

| Field | Value |
|-------|-------|
| **Date** | 2026-07-30 |
| **APK** | `dist/lezi-0.3.0-release.apk` (on device `versionName=0.3.0`, `versionCode=6`) |
| **Device** | `emulator-5554` · 1080×2400 · Android SDK x86_64 |
| **Ticket** | `UIQA-20260730-P2-05` (grill: half-progress green/red layer not captured cleanly) |
| **Verdict** | **B-evidence** |

## Method

1. **Static:** Read `docs/design/2026-07-29-timeline-swipe-edit-delete.md`, PRD `docs/prd/ui.md` §5.2/§7, `designsystem/.../SwipeEditDeleteRow.kt`, settle unit tests, `LogTimelineList` / `LogDialogHost` wiring.
2. **Device:** Relaunch installed 0.3.0; log tab with one amber “尿尿 / 同步失败” row (`content-desc="同步状态尿尿"`, bounds `[42,1433][1038,1623]`, width **996px**). Controlled `adb shell input swipe` at ratios **~0.30–0.45** (half) and **~0.62** (full), L and R. Capture uiautomator hierarchy + screenshot after settle (~700ms). Also tap revealed strip.
3. **Evidence root:** `/var/tmp/zhangtianshu-tmp/uiqa-p2-05-swipe/`

## Code facts

| Constant / behavior | Value / note |
|---------------------|--------------|
| `SWIPE_REVEAL_RATIO` | **0.28** (half settle target; design ±5%) |
| `SWIPE_COMMIT_RATIO` | **0.55** (full-travel commit; design ±5%) |
| `SWIPE_SETTLE_MS` | **200** |
| `SWIPE_LABEL_MIN_RATIO` | **0.16** (icon+label once strip wide enough) |
| Drag clamp | `coerceIn(-width*0.55, +width*0.55)` — max drag is exactly commit edge |
| Sign convention | **Finger left** (negative offset) → green **edit** fill from **right**; **finger right** (positive) → red **delete** fill from **left** |
| LTR / hand | **Absolute screen directions**; comment + design: never mirror with `preferredHand` |
| Visual model | Card **does not translate**; in-card color strip grows with progress |
| Half settle | `RevealedEdit` / `RevealedDelete` → animate to ±`revealRatio * width`; `open=true` |
| Full settle | `CommitEdit` / `CommitDelete` → animate closed then `onEdit()` / `onDelete()` |
| Tap revealed strip | `closeAnimated(onEdit)` or `closeAnimated(onDelete)` |
| Call sites | `LogTimelineList`: plans + records wrapped in `SwipeEditDeleteRow` |
| Edit path | `openEditFromSwipe` → collapse reveal → composer `Edit` / `EditPlan` |
| Delete path | `requestListDelete` → collapse reveal → `ListDeleteTarget` → **list-level** `AlertDialog` (`LogDialogHost`); confirm then domain soft-delete; **not** direct delete |
| Amber / publish rows | Design + code: left still edits; right still list-delete if `canDelete` |
| Unit tests | `SwipeEditDeleteSettleTest` covers closed / half / full / disabled sides / haptic cross |

Key pure settle function:

```81:100:designsystem/src/main/kotlin/com/lezi/babylog/designsystem/SwipeEditDeleteRow.kt
fun settleSwipeEditDelete(
    offsetRatio: Float,
    revealRatio: Float = SWIPE_REVEAL_RATIO,
    commitRatio: Float = SWIPE_COMMIT_RATIO,
    editEnabled: Boolean = true,
    deleteEnabled: Boolean = true,
): SwipeEditDeleteSettle {
    val absRatio = abs(offsetRatio)
    return when {
        offsetRatio > 0f && deleteEnabled && absRatio >= commitRatio ->
            SwipeEditDeleteSettle.CommitDelete
        offsetRatio < 0f && editEnabled && absRatio >= commitRatio ->
            SwipeEditDeleteSettle.CommitEdit
        offsetRatio > 0f && deleteEnabled && absRatio >= revealRatio ->
            SwipeEditDeleteSettle.RevealedDelete
        offsetRatio < 0f && editEnabled && absRatio >= revealRatio ->
            SwipeEditDeleteSettle.RevealedEdit
        else -> SwipeEditDeleteSettle.SettledClosed
    }
}
```

## Device results

| Case | Gesture | Result | Evidence |
|------|---------|--------|----------|
| Left half ~35% | swipe L, release in reveal band | **Green** strip settle ~28% width on **right**; `content-desc="编辑"`; bounds `[759,1433][1038,1623]` (279px ≈ 0.280×996) | `21-left-half-35.png`, `ui-21-left-half-35.xml` |
| Left half ~45% | same | Same settled green reveal | `26-left-half-45.png` |
| Left full ~62% | swipe L past commit | **Edit composer** (“编辑记录”, sheet also has “删除”) | `22-left-full-62.png` |
| Right half ~30–45% | swipe R, release in reveal band | **Red** strip settle ~28% on **left**; `content-desc="删除"`; bounds `[42,1433][321,1623]` | `23-right-half-35.png`, `25-right-half-45.png`, `27-right-half-30-slow.png` |
| Right full ~62% | swipe R past commit | **AlertDialog** “删除这条记录？” / “确认删除” / “取消”; row closed under scrim | `24-right-full-62.png` |
| Tap red after half | tap ~`(180,1528)` | Opens same list delete confirm | `40-tap-delete-from-reveal.png` |
| Tap green after half | tap ~`(900,1528)` | Opens edit composer | `41-tap-edit-from-reveal.png` |

**Not blocked by onboarding.** Amber “同步失败” row still supports left edit + right delete (matches design matrix).

### Why prior QA missed half-state

- Half state only exists **after release** inside **`[0.28, 0.55)`** of row width; drag is **clamped at 0.55**, so any “full-looking” swipe commits and skips a stable half frame.
- Prior walk documented left-full → editor sheet and used sheet “删除” as delete proof; right half was never isolated with a sub-commit displacement + post-settle dump.
- Settle animation is only **200ms**; dumps mid-gesture or mid-scroll-collapse lose the strip. Hierarchy needs `content-desc="删除"` / `"编辑"` (testTags are Compose tags, not always resource-ids).
- Automation timing/coords — **not** missing product chrome.

## Verdict

**B-evidence:** Implementation matches PRD / design 2026-07-29. Half-reveal (green edit / red delete), full-travel commit (edit sheet / delete confirm), absolute directions, and list-level delete confirm all work on 0.3.0. Prior page-acceptance gap was capture/automation, not a product half-layer defect.

## Recommended next ticket shape

**Test-only / residual — no product fix ticket for half-reveal “broken”.**

Must-haves if opening a QA/harness residual:

1. Page-acceptance script steps: half L/R with **dx ∈ [0.30, 0.48]×rowWidth**, wait **≥300ms** after up, assert `content-desc` 编辑/删除 and optional color band; full L/R with **dx ≥ 0.60×width** → composer / `确认删除`.
2. Optional instrumentation: Compose UI test on `SwipeEditDeleteRow` tags `timeline_swipe_edit_*` / `timeline_swipe_delete_*` for settled reveal + tap.
3. Do **not** treat editor-sheet “删除” as the only timeline delete path in acceptance (list right-swipe is REAL).

**No A-product fix** for settle thresholds, direction mirroring, or missing red/green layer based on this repro.

## Evidence paths

- Durable note: `/home/zhangtianshu/lezi/.scratch/apk-0.3.0-page-acceptance-swipe-delete-compound.md`
- Screenshots + hierarchy: `/var/tmp/zhangtianshu-tmp/uiqa-p2-05-swipe/`
  - Half: `21-left-half-35.png`, `23-right-half-35.png`, `25-right-half-45.png`, `26-left-half-45.png`
  - Full: `22-left-full-62.png`, `24-right-full-62.png`
  - Tap from reveal: `40-tap-delete-from-reveal.png`, `41-tap-edit-from-reveal.png`
- Source: `designsystem/src/main/kotlin/com/lezi/babylog/designsystem/SwipeEditDeleteRow.kt`
- Design: `docs/design/2026-07-29-timeline-swipe-edit-delete.md`
- Prior audit row: `docs/reviews/2026-07-30-apk-page-acceptance-audit.md` (`UIQA-20260730-P2-05`)
