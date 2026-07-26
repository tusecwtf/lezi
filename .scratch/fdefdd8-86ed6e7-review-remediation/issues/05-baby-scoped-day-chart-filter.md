# 05 — baby-scoped 日图筛选状态

**What to build:** 将记录页临时日图筛选的上下文从仅 `day` 改为 `baby identity + day`。切换宝宝（即使同一天且新宝宝也有同类记录）立即回到未筛选；同宝宝同日的数据刷新仍按现有 `reconcileSelection` 保持或在类别消失时清空。

**Blocked by:** None — frontier

**Status:** in-progress（自动化完成，device smoke pending）

**Size:** S
**Review finding:** P2 #5 — `remember(state.day)` 让筛选跨宝宝泄漏
**Seam:** Log page transient filter context

## Initial file surface

- `feature/log/.../LogScreen.kt`
- `feature/log/src/test/.../DayChartFilterWiringTest.kt` 或实际状态 seam 的新测试

不得修改 designsystem 命中算法（04）、domain 分类表、DataStore 或宝宝切换流程。

## Acceptance criteria

- [x] selection state 的 identity key 同时包含当前 baby stable identity 与 selected day；baby 为空也形成可清空上下文。
- [x] A 宝宝同日选「尿」后切到 B 宝宝，B 即使也有尿记录仍显示全量列表/未选中图例。
- [x] B 再切回 A 不恢复之前筛选；筛选不是每宝宝记忆或持久偏好。
- [x] 仅同宝宝同日下拉刷新：类别仍存在则保留，类别消失则现有 reconciler 清空。
- [x] 换日仍清空，warm/journal 行为一致。
- [x] 回归测试驱动实际 state seam（Compose/state owner 均可），不得只断言一个与产品接线无关的 data class equals 或 grep 源码。

## Validation

- `./gradlew :feature:log:testDebugUnitTest --no-daemon`
- 手工/Compose smoke：A 选类 → 切 B 同日 → 未筛选 → 切回 A → 未筛选
- `git diff --check`

## Documentation Gate

- 现有 `.scratch/day-chart-type-filter/spec.md` 已规定临时、非持久、换上下文不保留；若权威 `docs/prd/ui.md` 无冲突可记录 N/A。

## Out of scope

- 记住每个宝宝最后筛选、进程恢复或 DataStore 持久化。
- 修改宝宝切换导航、记录查询或日图类型集合。

## Comments

- 只靠 `reconcileSelection(selected, newBaby.records)` 不够：新宝宝若恰好也存在相同 category，旧 selection 会被合法保留。
- 2026-07-27 红灯：实际页面 reducer 的 context/action 类型缺失先导致编译失败；补 context 后，refresh action 缺失再次失败，证明测试同时锁定切换与同上下文刷新。
- 2026-07-27 绿灯：`babyId + day` 变化一律生成无 selection 新状态；同 context 才 reconcile records。`DayChartFilterWiringTest` 8 项通过，`:feature:log:testDebugUnitTest :feature:log:assembleDebug --rerun-tasks --no-daemon` 120 个 task 全执行，BUILD SUCCESSFUL；`git diff --check` 通过。
- Documentation Gate：行为与 `.scratch/day-chart-type-filter/spec.md` 的“临时、非持久、上下文切换不保留”一致，`docs/prd/ui.md` 无冲突，因此 N/A。
- 最终候选 APK 的 A→B→A 同日切换 smoke 尚未执行，本票保持未关闭。
