# 03: 游离时长迁移——15 处毫秒字面量接入 LeziMotion

**What to build:** 把绕过 token 的显式毫秒迁到 `leziMotionMillis(LeziMotion.*)`，使
reduce-motion 归零策略对它们生效（清单见 [../inventory.md](../inventory.md) §六加粗行）：

- `NextFeedPlanFlow.kt:89`：`tween(120)`/`tween(150)` → Fast（150/120 同档收敛为 Fast 入出各一）
- `TimelineComponents.kt:179,185`：`tween(150)` → Fast
- `LocalDataRecoveryScreen.kt:113`：`tween(200)` → Base
- `SwipeEditDeleteRow.kt:71`：`SWIPE_SETTLE_MS=200` → 由 `leziMotionMillis(LeziMotion.Base)`
  解析（常量可保留名字作锚点，值从 token 来）；6 处 `animateTo` 同步
- `LogTimelineList.kt:540`：`tween(250)` → Base（250 与 200 视觉不可辨，收敛避免特例）

**不迁**（有意保留）：装饰循环节奏 `SleepCapBobMs=1400`（AppHeader.kt:421）、
bob/pulse 1400/1000（QuickRecordPurposeFields.kt:545,554）——装饰循环不属于交互过渡
token 档位；非补间的节奏/阈值（浅同步 5s、搜索防抖 200ms、loading 300ms、undo 4s、
timer tick 200ms）也不属迁移范围。这两类在 inventory.md 已标注。

**Blocked by:** 无

**Status:** done（0.5.4 票 14 收编，2026-09-13）

- [x] 上述 5 个文件迁移完成，动画上下文不再出现 120/150/200/250 字面量（装饰循环除外）
- [x] 行为不变更：各处时长档位迁移后视觉档不变（120→150 属同档收敛，记录偏差）
- [x] `./gradlew test` 全绿（涉及模块单测）

## Comments

- 2026-09-13（0.5.4 票 14）：五文件全部迁到 `leziMotionMillis(LeziMotion.*)`——
  NextFeedPlanFlow 入/出各 Fast（120→150 同档收敛，入出同一档）；TimelineComponents
  两处 Fast；LocalDataRecoveryScreen Base；SwipeEditDeleteRow `SWIPE_SETTLE_MS` 改为
  `= LeziMotion.Base` 锚点（去 `const`），六处 `animateTo` 经一次组合级
  `leziMotionMillis` 解析；LogTimelineList Base（250→200 收敛）。偏差记录：120→150、
  250→200 均为同档收敛。静态契约断言进 `MotionDensityTokensTest`（防回潮）。
