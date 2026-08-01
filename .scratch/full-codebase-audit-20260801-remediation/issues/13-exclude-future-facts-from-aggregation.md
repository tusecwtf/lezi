# 13 — 聚合排除尚未发生的点事实

**What to build:** CareAggregation 的 day/range/week/window/widget 对非睡眠点事实统一应用聚合时钟，合法 `now + 5m` fulfillment 在时间真正到达前不计入统计。

**Source:** `AUDIT-20260801-P1-13`  
**Blocked by:** None — can start immediately  
**Status:** done  
**Size:** S–M

## Design notes (public seams)

1. `CareAggregation.day` / `range` / `week` / `window` / `widget` — single aggregation clock `now`; point facts count only when `timestamp <= now` (inclusive boundary fixed by tests); sleep clips to `[start, min(end, now)]`.
2. `sleepIntervalEnd(record, now)` — private helper shared by `aggregateDay` and `window`; open end and future closed end both clamp to `now`.
3. `SummaryAggregationEngine` / `buildSummaryUi` — already pass `now` into `CareAggregation`; inherit filter semantics without a second filter layer.
4. Fulfill write path unchanged: `RecordTime.FULFILLMENT_ACTUAL_TIME_SKEW_MILLIS` still allows `now+5m` actual times; aggregation alone delays cumulative visibility.

## Acceptance criteria

- [x] timestamp 大于 `now` 的 formula/pumped/nursing/diaper/temperature 等点事实不进入数量、量、温度与时段桶。
- [x] timestamp 等于 `now` 的事实按一个明确且全调用方一致的边界计入；测试固定该选择（`timestamp <= now` 计入）。
- [x] 时间到达记录 timestamp 后，无需改数据库即可在下一次聚合中自然出现。
- [x] `day`、`range`、`week`、`window`、`widget` 与 SummaryAggregation 使用同一过滤语义，不能仅修 widget/latest。
- [x] Sleep 继续按 `[start, min(end, now)]` 裁剪，未来开始的 sleep 不产生 segment/minutes。
- [x] DST 日界、历史日期与未来日期窗口回归不倒退。
- [x] 用 `fulfillCarePlan(now+5m)` 产出的真实 Record fixture 证明写入允许而聚合延迟可见。

## Validation

运行 CareAggregation/summary/log/widget tests、`:app:assembleDebug`、`lintDebug`。

## Documentation Gate

在汇总合同中写明“已确认但时间尚未来到”的点事实可见于时间轴但暂不计入累计（如 UI 当前会展示，需同步锁定）。

- [x] `docs/prd/data-model.md` §5.1 + `docs/prd/README.md` §4.5 + `CareAggregation` KDoc.

## Out of scope

不取消履行允许的 5 分钟设备时钟 skew。
