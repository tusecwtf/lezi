# 01 — 完成连续时间轴状态机与直接拖动

**What to build:** 家长在记录页看到由同一个时间轴交互状态控制的连续视窗：今天默认实时显示最近 24 小时，历史日期默认显示完整本地自然日；横向拖动直接、线性、无惯性地浏览时间，释放时可连续进入相邻日期，并在真正到达现在时重新吸附实时视窗。

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] 今天初始化为按真实经过时长计算的 `[现在−24h, 现在]`，选中日仍为今天；实时模式随时钟推进，并在本地跨午夜时推进到新今天
- [x] 历史日期初始化为完整本地自然日，春季跳时和秋季重复时分别保留真实 23/25 小时跨度、可区分的重复时刻以及正确的跨午夜区间裁剪
- [x] 一个纯 `TimelineInteraction` seam 以绝对 instant、实时/浏览模式和事件归约统一表达初始化、时钟、外部日期、拖动、恢复、换日与筛选生命周期，并只通过明确 effect 提交选中日
- [x] 内容随手指线性移动；一次满有效宽度拖动等于当前视窗跨度，释放速度不参与计算，无 fling、衰减或惯性，结束或取消后立即停止
- [x] 历史浏览按释放时视窗中心跨本地零点进入相邻日；实时今天按右边界跨今天零点进入昨天；手势期间不提交日期，单次手势最多进入一个相邻日
- [x] 三日工作轴可围绕新日期反复重建且保持相同绝对可见区间；从历史进入今天但尚未到现在时保持浏览，到达现在才吸附，任何输入都不能把视窗推进未来
- [x] 表驱动 JVM 合约覆盖默认视窗、DST、拖动比例、快慢释放等价、无释放后位移、相邻日上限、绝对视窗重建、未来夹紧、当前时间吸附和两种跨午夜模式

## Evidence

- `TimelineInteractionTest` covers the pure reducer contract with fixed clocks and zones.
- Existing `ThreeDayTimelineAxisTest` / `DstThreeDayTimelineAxisTest` remain the geometry contract for
  repeated local hours, instant mapping, interval clipping, and four-midnight work windows.
- `./gradlew :feature:log:testDebugUnitTest` passed on 2026-08-08.
- `./gradlew test lintDebug :app:assembleDebug` passed on 2026-08-08.
