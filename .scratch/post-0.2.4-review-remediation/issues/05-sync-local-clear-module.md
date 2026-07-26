# 05 — 同步侧本地清除深模块

**What to build:** 从 `RealSyncPort` 提取同步侧本地清除深模块，统一 records-only / all-local 的 sync mutex 屏障、Outbox/receipt/media/file 收尾、提交确认和 `LocalClearCommittedException` 语义。`RealSyncPort` 只委托，不再拥有清除 implementation。

**Blocked by:** `.scratch/family-identity-account-overview/issues/05-timeline-uploader.md` 落地并形成干净基线

**Status:** ready-for-agent

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

- [ ] module 的小 interface 同时支持 records-only 与 all-local，但 Outbox/media/file 差异由内部 scope policy 隐藏。
- [ ] 权威领域删除仍与 pull/apply 共用同一 barrier；不得出现删除中间被 pull 复活的窗口。
- [ ] callback 未确认 commit 时 fail closed。
- [ ] commit 后任何收尾失败均转换为带 `familyServerRetained` 的 `LocalClearCommittedException`，并保留 suppressed failure。
- [ ] retries 幂等：重复删除 Outbox、receipt、media row/file 不会转为假失败。
- [ ] `RealSyncPort.clearLocalRecords/clearAllLocalData` 只做委托或由新 module 直接满足既有 interface。
- [ ] 新 module interface 测试替代 `RealSyncPortTest` 中对应白盒设置；清除回归断言不得删除或放宽。
- [ ] 本票只搬清除语义，禁止顺手改 push/pull、家庭身份或 wire。

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

