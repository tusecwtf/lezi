# 15 — 媒体文件删除移出 Room 写事务

**What to build:** 用可恢复 cleanup claim 与共享路径互斥把慢文件删除移出 Room 写事务，同时保持“最后活跃引用消失后才删”的并发安全。

**Source:** `AUDIT-20260801-P2-01`  
**Blocked by:** 02、07 — receipt/tombstone ownership must be stable first  
**Status:** blocked
**Size:** M

## Acceptance criteria

- [ ] `mediaFiles.delete` 不在 `DatabaseTransactionRunner.run` 或 Room 写租约内执行。
- [ ] DB 阶段原子创建带 client UUID/path/revision 的 durable cleanup claim；文件阶段与 attach/import/复活共享路径级互斥或等价机制。
- [ ] claim 后出现新 active reference 时不得删除其文件；ABA（同路径重新使用）由 revision/identity 防护。
- [ ] 文件删除成功后只在 claim 和 tombstone 仍匹配时清 marker；失败/进程死亡保留可重试证据。
- [ ] 已经不存在的文件视为幂等成功；权限/IO 失败不清 marker、不阻止已经提交的业务 tombstone。
- [ ] 多个 tombstone 共享同一路径时只在最后 active reference 消失后回收，且重复 cleanup 安全。
- [ ] 并发/崩溃测试使用真实临时文件，并证明 Room 写事务不覆盖慢 delete 时长。

## Validation

运行 media/Room/domain/Replica tests、`:app:assembleDebug`、`lintDebug`，加真实文件延迟与崩溃恢复测试。

## Documentation Gate

更新媒体 GC 两阶段状态与恢复责任说明。

## Out of scope

不改变用户可见照片数量或 NAS media GC。
