# 02 — 日图类型筛选（点选 · 高亮 · 明细 · 图例 · 提示条）

**Parent:** [../spec.md](../spec.md)

**What to build:** 在 01 已提供日图类型纯模型与「无类则隐藏时间条」的前提下，照护者可在一日时间条上通过**色块/圆点**或**图例**进入**日图类型筛选**：选中类的全部标记高亮、其它类弱化；记录明细只显示该类；图下出现类型级提示条（类名 · 条数 · 再点取消）。再点同类、点空白、点提示条、换日、或该类当日数据消失时解除筛选。图例只列当日有数据的**日图类型名**（非「喂养/护理」轨名），与色块共享同一选中态。日汇总始终全日。warm 与 journal 同一交互语义。段级 tip 改为类型筛选语义；点时间条不打开编辑。`selected` 仅页级临时状态，不持久化。

**Blocked by:** 01 — 无日图类型则隐藏时间条

**Status:** partial

## Acceptance criteria

- [x] 选中态提升到记录页状态层（与列表同源），时间条 UI 不私藏筛选真源；不进 DataStore/设置；离开页或进程结束后不恢复
- [x] 命中与高亮按**日图类型集合**（非单段 tip）：点属于类 C 的色块/圆点 → `toggleSelection` 进入/取消 C；点另一类 → 切换；午睡∈睡眠，配方/瓶喂挤出乳∈奶
- [ ] 同一时刻存在多个可选标记时按实际绘制位置命中；尤其 `BOTH_DIAPER` 拆出的尿/便两个标记必须都能直接点中对应类型，不得因时间距离并列而固定选中列表第一项
- [x] 点图例类 C 与点色块等价；图例项为当日有数据的日图类型名（奶、母乳、睡眠、尿、便），**不得**用「喂养」「护理」等物理轨名顶替；有明确选中态
- [x] 筛选 C 时：图上属于 C 的全部标记高亮，其它标记（含同轨另一日图类型、非日图类型如吸奶）弱化；非日图类型标记不可作为选中目标
- [x] 筛选 C 时记录明细只含 `categoriesOf` 命中 C 的护理记录；未筛选时明细为当日全量（含非日图类型）；排序规则不变；筛选后非空列表可点行编辑
- [x] 尿+便在筛尿或筛便时均出现；筛「奶」不含吸奶
- [x] 类型级提示条：类名 + 条数（= 当日 `filterRecords(records, C)` 数量）+ 可取消暗示；点提示条取消筛选
- [x] 点空白/未命中、换日 → 取消筛选
- [x] records 刷新（如下拉同步）后：若选中类仍在当日 `legendCategories` 中则**保持**筛选；仅当该类消失时 `reconcileSelection` → null（不得卡在 0 条死态）
- [x] 非今日选中日与今日同一套筛选行为
- [x] 日汇总/摘要条不随筛选变化
- [x] 点时间条不打开编辑 Composer；编辑仍走明细行
- [x] warm 与 journal 共用同一选中/筛选语义
- [x] 行为由 01 的纯模型驱动；本票以接线与可演示交互为主，不重复发明第二套归类

## Comments

- 并行审查（2026-07-26）：approve-with-nits — 已补刷新保持、非持久、图例=日图类型名、条数语义、非今日一致。体量 large but OK，不拆票。
- 2026-07-26 implement：`TimelineLaneSegment.dayChartCategoryKey` + 页级 `selectedDayChart`；`TimelineRailCard` 外置 selected/legend/tip；`buildLanes` 按 `DayChartCategory.name` 打标；列表 `filterRecords`；`DayChartFilterWiringTest`。
- 2026-07-26 只读审计：重开为 `partial`；当前 warm/journal 点击命中未使用 `eventSlotOffset` 后的实际标记位置，`BOTH_DIAPER` 的第二个类型标记不可可靠直点。
