# 03 — 对齐独立媒体包的根发布回执

**What to build:** 独立 log/avatar 媒体发布抬高 root `updatedAt` 时，用同一个 `rootUpdatedAt` 条件确认本地根；本地水印、dirty/outbox 与 NAS LWW 不能分叉。

**Source:** `AUDIT-20260801-P1-03`  
**Blocked by:** 02 — media receipt CAS must be stable first  
**Status:** ready-for-agent
**Size:** M

## Acceptance criteria

- [ ] Record、CarePlan 的 standalone log bundle commit 后记录精确 `rootUpdatedAt` 的家庭根回执，不再只 ack media。
- [ ] avatar-only Baby bundle 同样以发布使用的 `rootUpdatedAt` 确认 Baby 根，而不是对不存在的 `babyRow` 静默跳过或用旧 outbox 时间。
- [ ] 根确认必须 CAS 期望本地 revision/content epoch；并发根编辑时不覆盖新内容、不错误清 dirty，并为较新根保留/重建 outbox。
- [ ] 成功确认后下一次本地根编辑一定产生严格大于 NAS 已发布根的修订。
- [ ] 注入“远端 commit 成功、进程在本地 ack 前崩溃”后，重试用确定 bundle ID 收敛且不重复/丢失媒体。
- [ ] Record/CarePlan 的 `familyPublishedUpdatedAt`、Baby 的等价水印及 `markSynced` 语义由真实 DAO 测试覆盖。
- [ ] 同一根多组 standalone media 的顺序不会让较旧回执倒退较新水印。

## Validation

运行 Outbox/Replica/Room 回归、`:sync:test`、`:app:assembleDebug`、`lintDebug`，并用 current server 做 log 与 avatar-only 重试 smoke。

## Documentation Gate

更新 ADR-0008/数据模型的根发布回执说明，使 Baby 与 Record/CarePlan 的合成根行为明确。

## Out of scope

不引入媒体-only wire；仍保持 Baby/Record/CarePlan 为 atomic root。
