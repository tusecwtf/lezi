# 04 — 绘制与命中共享标记布局

**What to build:** 抽取时间条 event marker 的唯一纯布局 seam，输出聚类后实际轴向中心与可命中目标；warm 横轨与 journal 纵轨的绘制、边缘 clamp 和 hit-test 都消费该结果。`BOTH_DIAPER` 同时刻拆出的尿/便两个标记必须能分别按画出来的位置直接点中。

**Blocked by:** None — frontier

**Status:** in-progress（自动化完成，device smoke pending）

**Size:** M
**Review finding:** P2 #4 — hit-test 使用原时间而绘制使用 `eventSlotOffset`
**Seam:** pure marker layout / hit target module

## Initial file surface

- `designsystem/.../TimelineComponents.kt`
- 可新增 `designsystem/.../TimelineMarkerLayout.kt`
- `designsystem/src/test/.../TimelineMarkerLayoutTest.kt`
- 原 `.scratch/day-chart-type-filter/issues/02-day-chart-type-filter-ux.md` 只在完成后回写状态/证据

不得修改 `LogScreen` 的分类/筛选状态、domain `DayChartCategories` 或记录列表行为。

## Interface contract

- 输入：同一 lane 的 segments、轴长度、cluster window、slot spacing、边缘 inset。
- 输出：每个 event segment 的实际 center（或 normalized position）、hit radius/target 与稳定 z-order。
- orientation 不进入业务算法；warm 把 position 映射 X，journal 映射 Y。
- interval 命中继续按区间行为，但不得复制 event 聚类算法。

## Acceptance criteria

- [x] 绘制不再单独调用私有 `eventSlotOffset` 后让命中按原时间计算；二者使用同一 layout result。
- [x] 两个相同 timestamp 的 PEE/POOP 标记得到两个实际中心；点各中心分别产生 `PEE` / `POOP` selection。
- [x] 三个以上近邻 marker 的顺序、slot spacing 与 tie-break 稳定，不依赖列表偶然遍历或 title/detail 是否相同。
- [x] 轴首尾 cluster 的 clamp 与 hit target 一致；不会画在 A 处却需点 B。
- [x] warm 横轨与 journal 纵轨共享同一纯算法，仅 orientation adapter 不同。
- [x] blank/non-selectable mark 仍按现有规则清空或忽略；interval 与 day-chart category 语义不回归。
- [x] 纯 JVM 测试覆盖 identical timestamp、near cluster、edge clamp、tie、blank halo；断言布局/命中行为，不测 Canvas 像素或主题颜色。
- [ ] 原 day-chart ticket 02 的未完成 acceptance 勾选并以回归测试/交互 smoke 关闭。

## Validation

- `./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest --no-daemon`
- warm 与 journal 人工/设备 smoke：直接点击 BOTH_DIAPER 两个偏移标记
- `git diff --check`

## Documentation Gate

- 产品语义未变时 PRD 可 N/A，但票内需引用 `.scratch/day-chart-type-filter/spec.md` 的 BOTH_DIAPER 双属与可点要求。

## Out of scope

- 改三轨为五轨、重画视觉、改变 marker 颜色/大小。
- 修改筛选分类、列表过滤或汇总口径。

## Comments

- 增大时间 halo 仍会让同 timestamp 两项距离并列并固定取第一项，不能解决本问题。
- 2026-07-27 红灯：在短轴密集边缘 cluster 用例稳定复现旧/初版布局的非法 clamp，目标测试失败；同时间双标记、近邻稳定顺序、首尾 clamp、blank halo 用例一并锁定。
- 2026-07-27 绿灯：warm 横轨与 journal 纵轨的绘制/命中都消费 `layoutTimelineEventMarkers`；`./gradlew :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest :designsystem:assembleDebug --rerun-tasks --no-daemon` 125 个 task 全执行，BUILD SUCCESSFUL。
- Documentation Gate：产品五类筛选和 `BOTH_DIAPER` 双属/可点语义未变，N/A；权威引用为 `.scratch/day-chart-type-filter/spec.md` 的“日图类型”与“与现网绘制的衔接”。
- 最终候选 APK 的 warm/journal 直接点击双标记 smoke 尚未执行，因此最后一项与本票状态保持未关闭。
