# 16 — 实现 gzip 有界增量 pull

**What to build:** 按握手协商 gzip 的普通增量 pull page，并限制 encoded/decoded bytes、item/page count 与 pull continuation 单调性。

**Blocked by:** 14、15

**Status:** ready-for-agent

## Contract slice

本票的 pull continuation 与 ConflictSnapshot receipt/continuation 独立。cursor 只在完整 page 的 Room transaction 提交后前进；坏 gzip/页整体 fail closed。

## Implementation sequence

1. 冻结普通 pull 的 encoding、budget 与 continuation errors。
2. 服务端按页执行 item/encoded budget 后编码。
3. 客户端流式解压并执行 decoded/item/page budget。
4. 将 page facts 与 cursor/continuation 原子落盘。

## Acceptance

- [ ] gzip/identity 语义一致
- [ ] truncated/corrupt/bomb/over-budget 不写部分 cursor
- [ ] duplicate/skipped/non-monotonic pull page fail closed
- [ ] crash 后从最后 committed page 恢复

## Validation

- [ ] compression/budget/cursor transaction tests 通过
- [ ] fault proxy 大页/坏 gzip smoke 通过

## Out of scope

不复用 conflict snapshot token，不更换 JSON。
