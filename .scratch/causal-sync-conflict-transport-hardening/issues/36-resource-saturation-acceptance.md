# 36 — 验收冲突资源饱和主缝

**What to build:** 消费 external 12/17/18/19 已有证据，并在公共主缝补一条 branch cap、paged snapshot 与 resolution retention 的跨层 smoke。

**Blocked by:** 31；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** ready-for-agent

## Contract slice

不重写资源矩阵；主缝只证明 saturation typed result 到 Android pending/inbox、完整分页仍可读、resolution 后 metadata 收缩且 replay 保留。

## Implementation sequence

1. 核对 external 12/17/18/19 exact-HEAD receipts。
2. 构造一个达到 branch cap 的 root 并观察 Android 状态。
3. 遍历全部 snapshot pages 后 resolution。
4. 触发 retention 并验证 replay/audit 与资源收缩。

## Acceptance

- [ ] cap 不隐藏 durable branches，Android 诚实 pending
- [ ] full-set pages 完整且 partial resolution 不可用
- [ ] retention 不破坏 stable fact/replay/provenance

## Validation

- [ ] 一条 isolated cross-layer saturation smoke 通过
- [ ] external receipts/HEAD/limits 被引用

## Out of scope

不重复外部高基数/query/GC 单测矩阵。
