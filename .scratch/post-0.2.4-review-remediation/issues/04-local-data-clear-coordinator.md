# 04 — 统一领域清除协调

**What to build:** 提取 `LocalDataClearCoordinator` 深模块，以清除范围 command 统一 `clearRecordsOnly` 与 `clearAllLocalData` 的领域事务、同步屏障回调、pending reminder hand-off、设置清理、恢复和失败分类。删除 `CareLog` 中两套重复骨架。

**Blocked by:** 03 — typed 提醒收尾持久化

**Status:** ready-for-agent

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

- [ ] 两种 clear 共用一个 orchestration implementation，不再重复 try/catch、commit callback、NonCancellable 收尾与异常转换。
- [ ] scope 只决定需要删除的领域表/设置集合；成功后置和失败分类完全共享。
- [ ] 领域提交前失败：本机行/提醒不变；提交后失败：pending 保留、提醒收尾可恢复、UI 得到 committed failure。
- [ ] `CancellationException` 即使被 cause 包裹仍原样传播。
- [ ] `CareLog` 只保留兼容 façade 或调用方全部迁移后删除旧方法；不得留两套 implementation。
- [ ] 新测试在 coordinator interface 覆盖 records/all、成功、提交前失败、提交后失败、恢复、取消。
- [ ] 删除被替代的 CareLog 私有 helper 与重复白盒测试；保留少量 façade 委托测试即可。
- [ ] `CareLog.kt` 行数净下降，且不得把新功能继续加回该文件。

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

