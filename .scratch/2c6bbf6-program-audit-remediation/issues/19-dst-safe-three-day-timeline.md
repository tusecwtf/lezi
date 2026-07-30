# 19 — 夏令时安全的三日时间轴

**What to build:** 以真实本地日界线构造前一天、当天和后一天的时间轴，使夏令时切换窗口可以是 71、72 或 73 小时，并让绘制、命中、遮罩与平移使用同一几何模型。

**Blocked by:** 18 — 记录页分钟级时钟刷新

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 三日窗口由连续四个本地 midnight 边界构成，不以固定 `72h` 替代日历天。
- [x] 普通日总时长为 72 小时；春季跳时与秋季回拨所在窗口按真实时区得到 71 或 73 小时。
- [x] Record 区间、当前时刻线、午夜分隔、邻日遮罩与小时标签都通过同一“瞬时 ↔ 轴坐标”转换；当前 PRD 将 CarePlan/逾期态固定为时间轴上方的行，不存在 CarePlan 轴标记或逾期轴遮罩，因此未新增平行坐标路径。
- [x] 点击/拖动命中、焦点高亮、自动居中和左右平移与可见绘制位置一致，不在 DST 缺口或重复小时偏移。
- [x] 跨午夜、开放区间及恰落边界的记录按真实本地日裁剪，不重复也不丢失。
- [x] 时区切换或日期跨日后重新建立窗口，并继承 Ticket 18 的生命周期时钟而不另起 ticker。
- [x] 确定性测试覆盖 `America/New_York` 春秋切换与 `Asia/Shanghai` 非 DST 基线，包括几何、命中、拖动和截图断言。

## Implementation notes

- `ThreeDayTimelineAxis` 从 D−1、D、D+1、D+2 四个本地午夜生成唯一经过时间轴；春跳窗口为 4260 分钟，秋回窗口为 4380 分钟，上海基线为 4320 分钟。
- `TimelineWindowGeometry` 将真实内容长度、主日范围和两条午夜边界送入三条 lane；分隔线、邻日降淡、睡眠切片、事件绘制/命中与拖动夹取不再读取固定 1440/2880/4320 边界。
- `LogScreen` 的记录/开放睡眠裁剪、当前时刻、默认视口、今日居中和小时标签全部由同一个 axis 实例生成。日期或时区变化会通过 `remember(state.day, zone)` 重建；时钟仍只使用 Ticket 18 的生命周期分钟流。
- PRD 明确三本地日通常为 72 小时、DST 为 71/73 小时；计划继续按既定产品结构显示在事实时间轴上方，不把未来意图混入 Record rail。

## Validation

运行时间轴纯逻辑、Compose 命中与截图测试，以及应用编译和静态检查；模拟器切换 DST 日期做平移 smoke。

## Documentation Gate

把“三个本地日而非固定 72 小时”写入时间轴规格与测试约定。

## Evidence

[DST 三日轴 TDD、API 35 Compose 命中/拖动/截图与构建静态门](../evidence/19/dst-safe-three-day-timeline.md)
