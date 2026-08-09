# 08 — 持久化冲突分页完整性

**What to build:** 让 Android 按 external 18 合同有界加载并持久化 snapshot pages，只有完整、无重复、无跳页的 snapshot 才标记 complete。

**Blocked by:** 05；[`external 18`](../../repository-dedup-algorithm-audit-20260809/issues/18-conflict-snapshot-receipt-pagination.md)

**Status:** ready-for-agent

## Contract slice

每页保存 receipt、continuation、page ordinal、count/byte evidence；resolver 只读 complete snapshot。异常页不污染现有 complete cache。

## Implementation sequence

1. 扩展 Room page/completeness 状态与恢复查询。
2. 校验 receipt、continuation 单调性、重复/跳页和预算。
3. 在逐页事务中暂存，最后原子 promote complete snapshot。
4. crash 后从最后 committed page 恢复或安全重启 snapshot。

## Acceptance

- [ ] large snapshot 不重不漏且 complete 前不可提交
- [ ] duplicate/skipped/non-monotonic/over-budget fail closed
- [ ] 旧 complete snapshot 在新加载失败时仍可离线读
- [ ] process restart 不丢 continuation/completeness

## Validation

- [ ] DAO/paging/fault/restart tests 通过
- [ ] Android JVM sync tests 通过

## Out of scope

不实现 freshness UI 或服务端分页。
