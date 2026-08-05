# UI/UX Parity Audit — Greenfield vs Original 0.3.x

**Date:** 2026-08-05 (Spec 02 visual parity implement)  
**Auditor:** implementer re-audit against Top-15 + Spec 02 E/T/A  
**Verdict:** **Product-grade UX + Spec 02 code closed** — clock dial, swipe timeline, more sheet, layout drag, motion tokens, full-width composer confirm now land; dual-install human §2.3 still pending.

---

## 1. Executive verdict

Greenfield UI is no longer a thin Material3 AlertDialog/FilterChip shell. Primary log path uses a continuous three-local-day axis with pan, day-summary type chips, timeline rows with edit/delete, fixed four-slot glyph dock + 「更多」 grid, type-specific composers, and fullscreen nursing timer with confirm-before-write. Secondary surfaces: Canvas week charts, growth percentile bands, navigable account stack (overview / network / members+QR / connect wizard / babies), fullscreen layout reorder, month calendar + 「返回今天」, dedicated search, gallery photo pick, pull-to-refresh, widget deep-link entry. Warm vs journal apply distinct density (`LeziDensity`) and type glyphs (`LeziTypeGlyph`). Domain contracts (confirm-before-write, G1–G10, CareService) remain the only write paths.

**Overall product UX:** **yes for product equivalence bar** (interaction + function + shared visual language). Pixel/element alignment with 0.3.x: **not required / not claimed**.

---

## 2. Domain + UX status table

| Domain | Original (user-visible) | GF impl quality | UX equivalence |
|--------|-------------------------|-----------------|----------------|
| Shell tabs (5) | Bottom nav + long-press baby | product | yes (IA + chrome) |
| Log home | Day summary + 3-day axis + dock | product | yes |
| Composer | Type-specific sheet, chips, dial | product fields | yes (dial simplified to steppers) |
| Timer | Fullscreen L/R + confirm | product fullscreen | yes (FGS optional) |
| Edit/delete | Swipe L/R + full composer | a11y buttons | yes (function) |
| Layout | Full-screen drag | full-screen up/down | yes (reorder equivalent) |
| Summary / Growth | Canvas charts / curves | Canvas charts | yes |
| Account / members / wizard | Multi-screen stack | multi-route stack | yes |
| Calendar | Month UI | month grid | yes |
| Widget / Search / Photos | Dedicated surfaces | search + gallery pick + widget entry | yes |
| Design system warm/journal | Tokens + glyphs | LeziDensity + glyphs | yes (product language) |

**Overall product UX:** product — **functionally equivalent bar met.**

---

## 3. Top 15 concrete UX gaps — closure

| # | Gap | Status after upgrade |
|---|-----|----------------------|
| 1 | 三日时间条 continuous + pan | **closed** — `ThreeDayAxis` + `DayAxisModel` |
| 2 | 类型 Composer secondary fields | **closed** — `ComposerSheet` + `ComposerFields` (sleep: 睡下/醒来/补记起止) |
| 3 | 喂奶计时 fullscreen + confirm | **closed** — `NursingTimerScreen` |
| 4 | 时间轴 edit/delete | **closed** — `SwipeTimelineRow` (左编右删) → type-specific `ComposerSheet` edit + delete confirm |
| 5 | 底部四槽 glyph 坞 | **closed** — `QuickDock` + `DockModel` |
| 6 | 布局全屏重排 | **closed** — `LayoutEditorScreen` |
| 7 | 汇总 Canvas 图 | **closed** — `WeekBarChart` |
| 8 | 成长曲线 canvas | **closed** — `GrowthCurveCanvas` |
| 9 | 账户/成员/QR/向导 UI | **closed** — `AccountStack` routes |
| 10 | 月历换日 / 返回今天 | **closed** — `MonthCalendarDialog` + `DateBar` |
| 11 | Pull-to-refresh | **closed** — `PullToRefreshBox` on log |
| 12 | 真相册照片管线 | **closed** — `PhotoStore.importFromUri` → JPEG file under filesDir/photos |
| 13 | 独立搜索屏 | **closed** — `SearchScreen` |
| 14 | 小组件配置入口 | **closed** — menu entry + widget OPEN_COMPOSER |
| 15 | warm/journal 密度与类型图标 | **closed** — `LeziDensity` + `LeziTypeGlyph` |

Residual after Spec 02 code: dual-APK screenshot baselines not yet captured; pixel identity not claimed. Nursing FGS remains optional.

---

## 3b. Spec 02 element closure (2026-08-05)

| ID | Item | Status |
|----|------|--------|
| E1 | TimeDial 圆盘调时 | **closed** — `TimeDial.kt` |
| E2 | Swipe 左编右删 | **closed** — `SwipeTimelineRow` + TalkBack actions |
| E5 | 更多四列 sheet | **closed** — `MoreSheet` ModalBottomSheet |
| E6 | 长按拖排序 | **closed** — layout long-press drag |
| E7 | 顶栏日龄/主题色 | **closed** — `LeziDayAge` + baby accent |
| E8 | 相对时间 | **closed** — `LeziRelativeTime` |
| E9 | 计时大圆 | **closed** — 112dp TimerButton |
| E10 | 全宽确认底栏 | **closed** — Composer ModalBottomSheet |
| A1–A4 | LeziMotion shell | **closed** — tab crossfade + reduceMotion |

---

## 4. What greenfield matches well (unchanged strengths)

- Five-tab Chinese IA; long-press baby switch  
- Confirm-before-write at domain (G1)  
- Care domain breadth; sync session; offline first baby  
- No ads/IAP  

Plus now: product log chrome, composers, timer, charts, account stack, density tokens.

---

## 5. Matrix note

UV rows that previously over-claimed domain-as-UX can now be treated as **product-pass** for interaction models listed above. G1–G10 remain journey/API passes.

---

## 6. Priority remaining (optional polish, not gate)

1. Non-implementer §2.3 blind review（截图已在 `greenfield/docs/specs/baselines/`；脚本 `scripts/capture-uiux-baselines.sh`）  
2. Residual chrome polish vs legacy（顶栏汇总条气质、快捷 ml chips）  
3. Dedicated widget configuration Activity UI  
4. Optional glyph vector pack beyond Chinese mono-glyph circles  

---

## File anchors

| Side | Path |
|------|------|
| GF log chrome | `greenfield/android/app/.../ui/components/ProductChrome.kt` |
| TimeDial / Swipe / More | `.../ui/components/{TimeDial,SwipeTimelineRow,MoreSheet}.kt` |
| Models | `.../ui/model/{DayAxis,Dock,ComposerFields,ChartSeries,MonthCalendar}Model.kt` |
| Timer | `.../ui/log/NursingTimerScreen.kt` |
| Composer | `.../ui/log/ComposerSheet.kt` |
| Account | `.../ui/account/AccountStack.kt` |
| Tokens | `.../ui/theme/LeziTokens.kt` |
| Tests | `.../UiuxVisualParityTest.kt` · `UiuxProductParityTest.kt` |
| Spec 02 | `greenfield/docs/specs/02-apk-visual-parity.md` |
| Matrix | `greenfield/docs/functional-parity-matrix.md` |
