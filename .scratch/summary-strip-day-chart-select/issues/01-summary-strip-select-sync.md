# 01 — 日汇总五格可点选并与日图类型筛选同步加重

**Parent:** [../spec.md](../spec.md)

**What to build:** 记录页顶部五格日汇总（奶 / 母乳 / 睡眠 / 尿 / 便）可点，进入与时间条/图例同一套**日图类型筛选**；选中格按钮式加重，并与时间条标记点击加重预览同源同步。数字仍为全日合计；临时非持久；warm 与 journal 同语义。

**Blocked by:** （无；依赖已落地的日图类型模型与页级 `selected`）

**Status:** complete

## Acceptance criteria

- [x] 五格分别映射到日图类型：奶、母乳、睡眠、尿、便；映射有纯函数单测
- [x] 点有数据的汇总格 C ≡ 点图例 C：toggle 进入/取消/切换；页级 `selected` 唯一真源
- [x] 选中后：该格加重；时间条该类标记高亮、其它弱化；图例对应选中；明细 `filterRecords`；类型提示条（时间条可见时）与现网一致
- [x] 从时间条或图例进入筛选时，对应汇总格同步加重；取消筛选后格恢复未选
- [x] 日汇总五格数字不随 `selected` 改变
- [x] 当日无该类日图类型数据时点格不出现 0 条 sticky 死态
- [x] 点格不打开 Composer；编辑仍走明细行
- [x] 换日、换宝宝、`reconcileSelection` 清除后加重消失
- [x] 状态不持久化；重进记录页为未筛选
- [x] warm `SummaryMetric` 五格与 journal `RecordSummaryStrip` 五格均具备可点与选中加重
- [x] 无障碍：选中/可筛语义可读（对齐图例文案风格）
- [x] 既有 `DayChartCategories` / `DayChartFilterWiring` 行为不回退；新增映射与接线单测通过

## Comments

- 2026-07-28 完成：五格映射、同源 toggle/reconcile、warm/journal 选中态、整格热区与 selected 语义已落地。
- 验收：DayChartFilterWiring 测试、Android 全测试/lint/Debug 构建及双轴代码审查通过。
