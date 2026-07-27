# 04 — 统一领域清除协调

**What to build:** 提取 `LocalDataClearCoordinator` 深模块，以清除范围 command 统一 `clearRecordsOnly` 与 `clearAllLocalData` 的领域事务、同步屏障回调、pending reminder hand-off、设置清理、恢复和失败分类。删除 `CareLog` 中两套重复骨架。

**Blocked by:** 03 — typed 提醒收尾持久化（completed）

**Status:** completed

**Size:** M
**Review findings:** Standards 4（CareLog）、Standards 5
**Seam:** domain `LocalDataClearCoordinator` interface

## Initial file surface

- 新增 `domain/.../LocalDataClearCoordinator.kt`
- 新增 `domain/src/test/.../LocalDataClearCoordinatorTest.kt`
- `domain/.../CareLog.kt`
- `domain/src/test/.../CareLogTest.kt`
- `app/.../LeziApp.kt` 仅在恢复入口迁移时触碰

## Interface contract

- `clear(scope)`：scope 至少包含 `RecordsOnly` / `AllLocalData`。
- `recoverPendingReminderCleanup()`：进程启动恢复。
- interface 明确取消、领域提交前失败、提交后失败及 family-server-retained 信息；调用者无需知道事务与补偿顺序。

## Acceptance criteria

- [x] 两种 clear 共用一个 orchestration implementation，不再重复 try/catch、commit callback、NonCancellable 收尾与异常转换。
- [x] scope 只决定需要删除的领域表/设置集合；成功后置和失败分类完全共享。
- [x] 领域提交前失败：本机行/提醒不变；提交后失败：pending 保留、提醒收尾可恢复、UI 得到 committed failure。
- [x] `CancellationException` 即使被 cause 包裹仍原样传播。
- [x] `CareLog` 只保留兼容 façade 或调用方全部迁移后删除旧方法；不得留两套 implementation。
- [x] 新测试在 coordinator interface 覆盖 records/all、成功、提交前失败、提交后失败、恢复、取消。
- [x] 删除被替代的 CareLog 私有 helper 与重复白盒测试；保留少量 façade 委托测试即可。
- [x] `CareLog.kt` 行数净下降，且不得把新功能继续加回该文件。

## Validation

- `./gradlew :domain:testDebugUnitTest :app:testDebugUnitTest --no-daemon`
- `rg -n "suspend fun clearRecordsOnly|suspend fun clearAllLocalData" domain/src/main` 确认只有 façade/单一 implementation。
- `git diff --check`

## Documentation Gate

- 产品语义冻结，通常 N/A；若异常文案或可观察恢复行为改变，同票更新 `docs/prd/ui.md` 与 `docs/prd/data-model.md`。

## Out of scope

- 修改 SyncPort 屏障 implementation（留给 05）。
- 服务器 tombstone、自动 leave 或清除后暂停同步。

## Comments

- 来源：固定范围审查 Standards findings 4/5。
- 2026-07-27 红灯：先新增 coordinator interface 契约测试；首次精确运行在 `compileDebugUnitTestKotlin` 因 scope、interface、persistence/settings seam 尚不存在而失败。
- 2026-07-27 绿灯：`RecordsOnly` / `AllLocalData` 共享同一同步屏障、事务、pending hand-off、设置清理、NonCancellable 提醒收尾与失败分类；cause 链中的原始取消异常保持原样传播。
- `CareLog` 仅保留三个兼容 façade，并与日程写入共享同一 mutation guard；删除重复 helper 与八个内部白盒用例，保留实际 DAO façade、提醒效果、跨锁并发及全量清除测试。`CareLog.kt` 从 3148 行降至 2959 行。
- 验证：`./gradlew :domain:testDebugUnitTest :app:testDebugUnitTest --rerun-tasks --no-daemon` BUILD SUCCESSFUL（321 tasks executed）；精确 coordinator 测试 5 项通过；两个 clear 搜索仅命中 `CareLog` façade；`git diff --check` 通过。
- Documentation Gate：产品语义、异常文案与可观察恢复行为均未改变，因此 `docs/prd/ui.md` / `docs/prd/data-model.md` N/A。
- 2026-07-27 Release 复验补强：pending hand-off 现在按 `RecordsOnly` / `AllLocalData`
  使用独立 operation，并在删除 marker 前恢复完成系统日历副本、scope 设置和应用提醒；
  该可观察恢复语义已同步到 `docs/prd/ui.md` 与 `docs/prd/data-model.md`，取代上一条 N/A。
