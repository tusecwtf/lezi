# 04 — 「现在」线（窗口内）+ 今日视口对准现在

**What to build:** 「现在」辅助线表示 **wall-clock 绝对时刻**在 72h 轴上的位置：仅当该时刻落在当前窗口 `[D−1 0:00, D+1 24:00]` 内时绘制，否则不画。当选中日 **D = 今天** 时，初始视口以「现在」为中心（在窗口边界内夹紧）。换日或「返回今天」后按规则重置视口。历史日仍使用 02 的 D+窥视默认，不假装有一条「现在」。

**Blocked by:** 02 — 72h 内容轴 + 非今日默认视口 + 跨夜连续睡

**Status:** done

- [x] 现在落在 72h 窗口内时绘制辅助线；落在窗外时不绘制
- [x] D = 今天时，进入/回到该日的初始视口以现在为中心（边界处夹紧，不露出窗口外空白时间）
- [x] D ≠ 今天且窗口不含现在时，无「现在」线；默认视口仍为 D 为主+窥视（02）
- [x] 时间流逝后「现在」线位置可更新（与现网刷新节奏一致即可，不强制秒级动画）
- [x] 自动化或可测逻辑覆盖：窗口内/外是否画线、今日初始偏移夹紧

## Comments

- 与 03、05 可并行。07 的手势平移硬依赖本票的今日/非今日默认视口语义。
- 2026-07-29：`TimelineAxis.contentMinuteIfInWindow` + `todayCenteredViewportStartMinutes`；LogRoute 接线今日居中 / 非今日 peek；feature 层 `nowContentMinuteInWindow` / `todayThreeDayViewportStartMinutes` 单测。
