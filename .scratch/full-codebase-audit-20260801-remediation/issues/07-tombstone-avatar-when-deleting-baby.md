# 07 — 删除宝宝同时 tombstone 头像

**What to build:** Owner 软删家庭权威宝宝时，在同一 Room 事务中清根头像指针并 tombstone 其 active avatar MediaAsset；提交后再走引用感知文件回收。

**Source:** `AUDIT-20260801-P1-07`  
**Blocked by:** 02 — media tombstone/receipt CAS first  
**Status:** ready-for-agent  
**Size:** M

## Acceptance criteria

- [ ] `deleteBaby` 同一事务写 Baby tombstone、清 `avatarMediaUuid`/`avatarPath`，并以不倒退的时间 tombstone 所有 active avatar media。
- [ ] 事务失败时 Baby、头像指针、MediaAsset 与文件全部保持原状。
- [ ] outbox/atomic bundle 发布 deleted Baby root 与对应 media tombstone，不再把 live avatar 带进墓碑包。
- [ ] pull/apply 后其它设备得到 deleted Baby + 非活跃 avatar，不会由 pointer repair 复活头像。
- [ ] 物理文件只在提交后且无其它 active 引用时删除；失败保留 durable cleanup marker 可重试。
- [ ] 删除、同步失败重试、commit-response 丢失和进程恢复均不会复活头像或丢 tombstone。
- [ ] 回归覆盖 legacy 多个 active avatar 的防御性收口，而不只处理 pointer 指向的一行。

## Validation

运行 domain/media/Replica/Room 测试、`:app:assembleDebug`、`lintDebug`，并做双客户端删除/拉取 smoke；需要 live server 时先提议 CD。

## Documentation Gate

更新 Baby/avatar 生命周期与删除包语义。

## Out of scope

不改变“至少保留一个 active 宝宝”的产品约束。
