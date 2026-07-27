# 05 — 同步侧本地清除深模块

**What to build:** 从 `RealSyncPort` 提取同步侧本地清除深模块，统一 records-only / all-local 的 sync mutex 屏障、Outbox/receipt/media/file 收尾、提交确认和 `LocalClearCommittedException` 语义。`RealSyncPort` 只委托，不再拥有清除 implementation。

**Blocked by:** `.scratch/family-identity-account-overview/issues/05-timeline-uploader.md` 落地并形成干净基线

**Status:** completed

**Size:** M
**Review finding:** Standards 4（RealSyncPort）
**Seam:** sync internal local-clear module；复用现有 DAO、preferences 与文件 adapter

## Initial file surface

- 新增 `sync/.../LocalReplicaClearCoordinator.kt`
- 新增 `sync/src/test/.../LocalReplicaClearCoordinatorTest.kt`
- `sync/.../RealSyncPort.kt`
- `sync/src/test/.../RealSyncPortTest.kt`
- `sync/.../SyncPort.kt` 仅在收窄契约确有必要时触碰

## Acceptance criteria

- [x] module 的小 interface 同时支持 records-only 与 all-local，但 Outbox/media/file 差异由内部 scope policy 隐藏。
- [x] 权威领域删除仍与 pull/apply 共用同一 barrier；不得出现删除中间被 pull 复活的窗口。
- [x] 副本 marker 与领域删除同一 Room 事务提交；事务未提交时 fail closed。
- [x] commit 后任何收尾失败均转换为带 `familyServerRetained` 的 `LocalClearCommittedException`，并保留 suppressed failure。
- [x] retries 幂等：重复删除 Outbox、receipt、media row/file 不会转为假失败。
- [x] `RealSyncPort.clearLocalRecords/clearAllLocalData` 只做委托或由新 module 直接满足既有 interface。
- [x] 新 module interface 测试替代 `RealSyncPortTest` 中对应白盒设置；清除回归断言不得删除或放宽。
- [x] 本票只搬清除语义，禁止顺手改 push/pull、家庭身份或 wire。

## Validation

- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest --no-daemon`
- 清除屏障、提交后补偿、重复重试定向测试必须单独列出结果。
- `git diff --check`

## Documentation Gate

- 行为冻结时记录 N/A；若 `SyncPort` interface 或错误契约改变，同票更新 `docs/prd/sync-home-lan.md` 与测试。

## Out of scope

- Replica push/pull/media 主循环（06）。
- 家庭 create/join/leave/delete（07）。

## Comments

- 来源：固定范围审查 Standards finding 4；先等待当前 uploader/identity WIP，避免覆盖同一 Sync 文件面。
- 2026-07-27：新增 `LocalReplicaClearCoordinator.clear(scope, clearLocal)` 单一 interface；
  records-only / all-local 的 snapshot、Outbox/media/file/checkpoint policy 与提交后补偿均收进
  module。`RealSyncPort` 两个 clear 方法只映射 scope，并把现有 `syncMutex` 作为与
  pull/apply 共用的 barrier。
- TDD：首个公共 seam 测试先因 coordinator/scope 尚不存在在
  `compileDebugUnitTestKotlin` RED，提取后 GREEN。审查补强的 committed snapshot
  双文件失败用例先在外层 retry 路径断言 RED，修复为 snapshot 跨调用保留后 GREEN；
  真实文件 adapter 的 existing-delete-false 测试先因严格删除 seam 不存在编译 RED，
  修复后与 missing-file 幂等用例一并 GREEN。
- Replace-don't-layer：scope/fail-closed/suppressed/idempotence/media 分块/全量清除断言
  已迁到 10 个 coordinator interface 测试；`RealSyncPortTest` 仅保留 pull barrier、
  删除后不可重发与 generation recovery 的跨模块集成回归。`RealSyncPort.kt`
  2527 → 2436 行，原清除 implementation 已删除。
- Validation（2026-07-27）：
  `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest --rerun-tasks --no-daemon`
  BUILD SUCCESSFUL；清除 barrier、提交后补偿、双失败跨调用 retry 与真实文件删除边界
  均有单独定向 GREEN；`git diff --check` 通过。独立复核在修复两项 Important 后为
  Critical 0 / Important 0。
- Documentation Gate：N/A。`SyncPort` interface、`LocalClearCommittedException`
  类型/分类、产品文案、wire 与可观察清除范围均未改变；本票只收口既有 sync 清除
  implementation，并让真实文件失败遵守原 committed-failure 契约。
- 2026-07-27 Release 复验补强：内存 callback commit 标志已被 Room
  `pending_replica_cleanup` write-ahead marker 取代；marker 与领域删除同事务，文件、
  DataStore、跨 family Outbox 和精确媒体 UUID 收尾可跨进程恢复。该说明取代上方初始
  callback 方案，但不改变只清本机、保留家庭服务器历史的产品范围。
