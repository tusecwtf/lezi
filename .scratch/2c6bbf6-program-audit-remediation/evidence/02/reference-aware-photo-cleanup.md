# Ticket 02 — 按活跃引用回收照片文件证据

日期：2026-07-30（Asia/Shanghai）

## 行为结果

- `PhotoAttachmentReconciler` 继续是 Record/CarePlan 媒体行的唯一 typed 写入器；普通编辑与
  整实体删除现在返回精确 `tombstonedClientUuids`，只有所属实体的行会进入候选。
- `ReferenceAwareMediaFileCleanup` 对每个精确候选使用一个 Room transaction lease：重新读
  tombstone、按同一 `localUri` 查询所有 active MediaAsset、必要时删除文件，并仅在删除成功
  或文件已缺失后清空 tombstone 的本机路径。MediaAsset tombstone 行本身不删除。
- Record/CarePlan 编辑、删除、Sleep 确认和 Record→CarePlan 转换均在根+媒体事务提交、
  sleep/calendar guard 释放后才请求回收。转换时新计划的 active 行会保护与旧 Record
  tombstone 相同的字节；履行仍保留计划 active 行并创建事实 Record active 行。
- public `SyncPort.cleanupTombstonedMedia` 在 `RealSyncPort` 的 `syncMutex` 外层执行；锁序固定为
  `syncMutex → Room transaction`。`ReplicaSyncEngine` 已复用同一回收核心，但在已有同步锁
  调用栈内不二次加锁。pending-upload active 行保护文件，pull 暂存和原子 apply 期间 public
  回收会等待 replica barrier。
- 回收器只依赖 MediaAsset DAO 和文件端口，不能删除 Outbox。原子 Record mutation 回归证明
  本机文件 marker 清理后，媒体 tombstone 仍随根原子包发布并 commit，不丢失远端删除意图。

## Ticket 01 草稿保护保留

- `sourcePhotos`、`borrowedPhotos`、`ownedDraftPhotos` 的 typed 分类未合并或降级。
- Composer 的 remove/abandon/保存后清理仍只直接删除 draft-owned 私有导入；履行借用的计划
  原图和 persisted source 不进入直接文件删除。
- Record 删除不再执行 `photoStore.delete(draft.photos)`；所有已持久化路径统一经过领域
  tombstone + active-reference 回收。
- 写入失败/重建/明确放弃的 Ticket 01 回归仍通过，借用照片不会因取消或异常被删除。

## RED → GREEN

按 public seam 逐条执行：

1. 真实临时文件双引用测试先因 `ReferenceAwareMediaFileCleanup` 不存在而编译 RED；补最小
   cleanup 核心与精确 active-reference 查询后 GREEN。
2. 普通编辑和整实体删除测试先因 reconciler 只返回 `Boolean`/`Unit` 而编译 RED；加入
   `PhotoAttachmentMutation` 精确 UUID 后 GREEN。
3. CarePlan/Record 编辑、删除、转换和 Sleep confirm 测试分别先因 CareLog 未交付候选而
   断言 RED；每条路径均在事务外接入 public SyncPort 后 GREEN。
4. replica barrier 测试先因 `RealSyncPort` 尚未接入共享 cleanup 依赖而编译 RED；接入同一
   `syncMutex` 后证明同步临界区内 cleanup 保持挂起，释放后才回收。
5. Composer edit 测试先证明 discarded persisted source 被直接删除（RED）；移除该旁路后，
   persisted source 保留给领域 GC，而 discarded draft-owned import 仍被删除（GREEN）。

真实临时文件覆盖：双引用、删一留一、最后引用删除、pending-upload active 引用、删除失败、
协调器重建重试、文件已缺失，以及物理删除后取消再幂等收敛。删除失败或取消时
`tombstone.localUri` 保留；若文件已删但 marker 未清，下一次 missing-file 删除仍可安全完成。

## 验证

定向 TDD、原子包与同步恢复测试通过：

```text
./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.ReferenceAwareMediaFileCleanupTest
./gradlew :domain:testDebugUnitTest --tests com.lezi.babylog.domain.PhotoAttachmentReconcilerTest
./gradlew :feature:log:testDebugUnitTest --tests com.lezi.babylog.feature.log.RecordComposerPhotoLifecycleTest
./gradlew :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.RealSyncPortTest.atomicRecordMutationAddRemoveReplaceAndTextOnlyUsesStableBundleId \
  --tests com.lezi.babylog.sync.ReplicaSyncEngineTest.remoteMediaTombstoneRetriesFileCleanupAfterProcessStops \
  --tests com.lezi.babylog.sync.ReplicaSyncEngineTest.remoteMediaTombstoneDoesNotDeletePathReusedByLiveMedia
```

模块回归最终均成功：

```text
./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest
./gradlew :feature:log:testDebugUnitTest
```

构建/静态门禁成功，共 775 个 Gradle task（198 executed、1 from cache、576 up-to-date）：

```text
./gradlew :core:database:kspDebugKotlin \
  :sync:testDebugUnitTest \
  :domain:testDebugUnitTest \
  :app:assembleDebug \
  :app:lintDebug
```

结果为 `BUILD SUCCESSFUL in 43s`；lint HTML 位于
`app/build/reports/lint-results-debug.html`。另一次稳定工作树上的
`:feature:log:testDebugUnitTest` 为 `BUILD SUCCESSFUL in 2s`（105 tasks）。

## 明确边界

- 本票不改 Room schema；`LeziDatabase` 仍为 v24，无 migration/schema 导出变化。
- 本票不改 NAS wire/server；只保留并验证既有 MediaAsset/Outbox tombstone 与 0–3 图原子包。
- 本票不占用模拟器，未运行 device smoke，因此不声明设备页验收。
- 本票不改应用版本号；Program 全部完成后由主任务统一升级到 0.3.0。
