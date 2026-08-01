# 15 — 媒体文件删除移出 Room 写事务

**What to build:** 用可恢复 cleanup claim 与共享路径互斥把慢文件删除移出 Room 写事务，同时保持“最后活跃引用消失后才删”的并发安全。

**Source:** `AUDIT-20260801-P2-01`  
**Blocked by:** 02、07 — receipt/tombstone ownership must be stable first  
**Status:** done
**Size:** M

## Acceptance criteria

- [x] Reference-aware reclaim 路径的 `mediaFiles.delete` 不在 `DatabaseTransactionRunner.run` 或 Room 写租约内执行（审计源 P2-01 / CR-20260730-P1-03）。`LocalReplicaClearCoordinator` 仍可在写租约内删文件，不属本 ticket 的 reclaim 约束。
- [x] DB 阶段原子创建带 client UUID/path/revision 的 durable cleanup claim；文件阶段与 attach/import/复活共享路径级互斥或等价机制。
- [x] claim 后出现新 active reference 时不得删除其文件；ABA（同路径重新使用）由 revision/identity 防护。
- [x] 文件删除成功后只在 claim 和 tombstone 仍匹配时清 marker；失败/进程死亡保留可重试证据。
- [x] 已经不存在的文件视为幂等成功；权限/IO 失败不清 marker、不阻止已经提交的业务 tombstone。
- [x] 多个 tombstone 共享同一路径时只在最后 active reference 消失后回收，且重复 cleanup 安全。
- [x] 并发/崩溃测试使用真实临时文件，并证明 Room 写事务不覆盖慢 delete 时长。

## Validation

运行 media/Room/domain/Replica tests、`:app:assembleDebug`、`lintDebug`，加真实文件延迟与崩溃恢复测试。

Focused gates run:

```text
./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.ReferenceAwareMediaFileCleanupTest
./gradlew :domain:testDebugUnitTest --tests com.lezi.babylog.domain.PhotoAttachmentReconcilerTest --tests com.lezi.babylog.domain.CareLogTest
./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.ReplicaSyncEngineTest.remoteMediaTombstone* --tests com.lezi.babylog.sync.RealSyncPortTest.mediaCleanupWaitsForReplicaBarrierBeforeReclaimingBytes
```

## Documentation Gate

更新媒体 GC 两阶段状态与恢复责任说明。

- `docs/prd/data-model.md` §3.7：两阶段 claim → 事务外 delete → 匹配后清 marker；path gate 锁序。

## Out of scope

不改变用户可见照片数量或 NAS media GC。

## design_notes (self-confirmed seams)

Public seams:

1. `ReferenceAwareMediaFileCleanup.cleanupTombstones` / `cleanupPendingTombstones`
2. Reclaim-scoped caller invariant: `ReferenceAwareMediaFileCleanup` runs `SyncMediaFileStore.delete` outside any `DatabaseTransactionRunner` / Room write lease. **Do not** put a global depth==0 guard on shared `SyncMediaFileStore.delete` — `LocalReplicaClearCoordinator` intentionally deletes under a write lease while wiping the DB.
3. `MediaLocalPathGate` / `MediaFileCleanupClaim` (clientUuid + path + updatedAt + deletedAt). Global lock order: **path gate → sleepMutationMutex (when used) → Room**.
4. `PhotoAttachmentReconciler.withInvolvedPaths` — attach/import/revive outer exclusion before Room; must share the process-wide gate singleton with reclaim.
