# 04 — 实现因果 tombstone restore

**What to build:** 为纯 tombstone 冲突提供可审计恢复选择，只恢复 tombstone 明确声明的直接完整 live base 及其媒体。

**Blocked by:** 03

**Status:** ready-for-agent

## Contract slice

不得搜索祖先、猜测多 parent、补造缺失 bytes，或在一次 resolution 中 restore-and-edit。

## Implementation sequence

1. 从 tombstone mutation 解析唯一 direct base identity。
2. 验证 base 是完整 live root 且全部媒体可用。
3. 生成同 root/media 的 restore choice 并走 choice-only validation。
4. 将恢复写为独立稳定版本与 provenance。

## Acceptance

- [ ] complete direct base 可原样恢复 root/media
- [ ] missing parent/bytes、多 parent、新 branch 和 restore+edit 被拒绝
- [ ] delete/edit 双顺序、delete/delete 与 stale replay 不静默复活

## Validation

- [ ] Store/API restore decision table 通过
- [ ] 媒体字节一致性与 provenance assertions 通过

## Out of scope

不支持历史墓碑批量复活或智能推断原事实。
