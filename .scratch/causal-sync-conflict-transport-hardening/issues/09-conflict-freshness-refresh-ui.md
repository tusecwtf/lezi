# 09 — 完成冲突 freshness 刷新体验

**What to build:** 将 expired/stale/new-branch 结果映射为 refresh 状态机，重载完整 snapshot，并在 offline、旋转和进程重建后保持诚实可理解的 resolver 状态。

**Blocked by:** 06、07、08；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)

**Status:** ready-for-agent

## Contract slice

Stale 只终止当前 resolution request，不终止事实；刷新可生成新 choice IDs，客户端从新 snapshot 重建选择而不猜测旧 ID 等价。

## Implementation sequence

1. 定义 loading/offline/stale/refreshing/complete/error UI state。
2. 对 stale/expired/new branch 触发 fresh receipt 与全页重载。
3. 用新 snapshot 清空或明确重建用户选择。
4. 支持 configuration change 与 process recreation。

## Acceptance

- [ ] offline snapshot 可读但不可提交
- [ ] stale refresh 不复用旧 choice IDs
- [ ] refresh failure 不误报解决成功或丢旧只读证据
- [ ] configuration change/process recreation 状态一致

## Validation

- [ ] state-machine/Compose/offline/recreation tests 通过
- [ ] connected interaction 留至票 42

## Out of scope

不改变分页原语或 resolution 语义。
