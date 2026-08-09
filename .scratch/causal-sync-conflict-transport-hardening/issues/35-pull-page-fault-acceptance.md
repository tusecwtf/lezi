# 35 — 验收 gzip、分页与 cursor 原子性

**What to build:** 用 fault proxy 证明 truncated/corrupt/over-budget gzip 与 duplicate/skipped/non-monotonic pull pages 不会推进 cursor 或写入 partial facts。

**Blocked by:** 16、31

**Status:** ready-for-agent

## Contract slice

Cases：identity/gzip happy path、truncated JSON、corrupt gzip、decoded bomb、item/page over budget、重复/跳页/倒退 continuation、Room transaction crash。

## Implementation sequence

1. 建立上述固定 response fixtures。
2. 逐例运行 decode/budget/continuation validation。
3. 在 page Room transaction 前后注入 crash。
4. 恢复后比较 facts/cursor/continuation。

## Acceptance

- [ ] 坏页整体 fail closed，无 partial cursor/facts
- [ ] committed page crash recovery 单调且不跳事实
- [ ] gzip/identity stable projection 等价

## Validation

- [ ] fixed fault case table 通过
- [ ] 记录 encoded/decoded/count/page receipts

## Out of scope

不覆盖 ConflictSnapshot pagination 或 retry jitter。
