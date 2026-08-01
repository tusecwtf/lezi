# 02 — 用条件回写确认媒体 commit

**What to build:** 把 atomic media prepare/commit 后的整行 `@Update` 改为基于被发布修订的条件更新，只写媒体探测字段与 `remoteUri` 回执，绝不覆盖传输期间的 tombstone、复活、换路径或新版本。

**Source:** `AUDIT-20260801-P1-02`  
**Blocked by:** None — can start immediately  
**Status:** ready-for-agent  
**Size:** M

## Acceptance criteria

- [ ] 回归覆盖长上传期间同一 MediaAsset 被 tombstone、复活、替换 `localUri` 和产生更高 `updatedAt`；旧 prepare 快照不能覆盖任何新字段。
- [ ] prepare 得到的 mime/尺寸/byteSize 只在 client UUID、期望修订、删除态与源路径仍匹配时合并；失败保持当前行。
- [ ] commit 成功后的 receipt 只在同一被发布修订仍有效时写入 `remoteUri`，不使用 prepare 时的整行 `copy` 回写。
- [ ] CAS 不匹配时媒体保持 dirty/outbox 可重入，不能因远端已经 commit 就删除本地新修订的待同步证据。
- [ ] 未发生并发写时，探测元数据、receipt、`markSynced` 与 outbox 删除仍一次收敛。
- [ ] commit 失败、取消或上传失败不写 receipt；已打开的媒体 source 全部关闭。
- [ ] DAO 使用受约束 `UPDATE`/事务返回受影响行数，并有 Room 或等价真实 DAO 回归，不只用 map fake。

## Validation

运行 media/Replica/RealSyncPort 测试、`:sync:test`、`:app:assembleDebug`、`lintDebug`，并做一次上传中编辑/删除的 current client/server 集成 smoke。

## Documentation Gate

更新媒体生命周期说明，明确 prepare metadata、root commit receipt 与 domain revision 的 CAS 边界。

## Out of scope

不改变 atomic bundle wire 或允许 ordinary media PUT。
