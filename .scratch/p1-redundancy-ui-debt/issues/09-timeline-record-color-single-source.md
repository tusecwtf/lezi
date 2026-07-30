# 09 — 时间条与记录语义色单源

**Parent:** [../spec.md](../spec.md)

**What to build:** 日视图时间条色块/圆点与记录类型强调色从**同一 designsystem / 主题扩展色板**读取；去掉构建 lanes 时的硬编码 ARGB 与绘制阶段再覆盖的双轨。warm 与 journal、浅/深色下图例、轨、列表类型色一致（PRD §2.1 两模板同一绘制、仅外壳不同）。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M  
**Theme:** F（U3）  
**Seams:** designsystem 记录/轨色；记录页出图

## Acceptance criteria

- [x] 时间条 segments 颜色来自主题 token / `leziRecordColor`（或等价单源），无生产路径 `Color(0xFF…)` 喂养/护理语义色表并行
- [x] 图例、轨标记、快捷类型色在同一模板下一致；切换 journal/warm 不出现轨与图例各用一套 hex
- [x] 日图类型筛选高亮/弱化仍可读
- [x] designsystem + feature/log 相关测试或色映射单测通过

## Implementation notes

- `LeziRecordColorRole` + `resolveLeziRecordColor` 是 light/dark 记录语义色的唯一纯
  authority；Compose `leziRecordColor`、时间线绘制与 legacy lane aliases 均委托它。
- `TimelineLaneSegment` 与 `TimelineLegendEntry` 只携带语义角色，不再携带 raw `Color`。
  `buildTimelineLanes` 从 `RecordType.presentation.colorRole` 建立普通记录角色；尿+便显式拆为
  Pee / Poop 两个标记。时间线与图例在 designsystem 内按当前深浅模式解析同一 palette。
- LogScreen 删除 `Color.Unspecified` 占位与渲染前 `copy(color = ext...)` 二次染色，也不再把
  `sun` 临时当便便色；warm / journal 只改变外壳，语义色保持一致。
- 旧 `LaneSleep` / `LaneFeed` / `LaneCare` / `JournalFeed` 并行 token 已移除；现存
  `LeziExtendedColors.lane*` 兼容别名由同一角色 resolver 派生，避免 Summary 等消费者漂移。
- 筛选 key、命中、选中 focus ring、其他类型弱化与邻日弱化算法未改变；既有 marker / A2
  filter tests 保持 GREEN，并新增 lane / legend role characterization。

## Validation evidence

- 三个有效 TDD RED 分别锁定：缺失纯 color resolver、旧 theme lane palette 与 record
  palette 不同、lane 数据契约缺少 `colorRole`；每轮仅补最小 authority 后恢复 GREEN。
- `./gradlew :designsystem:testDebugUnitTest --no-daemon`：通过（17 tasks，5s）。
- `./gradlew :feature:log:testDebugUnitTest --tests com.lezi.babylog.feature.log.BuildTimelineLanesTest --tests com.lezi.babylog.feature.log.DayChartFilterWiringTest --no-daemon`：
  通过（105 tasks，6s）。
- `./gradlew :designsystem:lintDebug :feature:log:lintDebug :app:assembleDebug --no-daemon`：
  通过（573 tasks，33s）。
- 完整回执见 `../evidence/09/validation.md`；未改 Layout10、版本、Program WIP 或睡眠装饰色。

## Out of scope

- journal 是否用宝宝色做 primary CTA（10）
- 睡眠装饰 moon/sun 散落色（审查 P2，可顺手仅当同一 token 表自然覆盖）
