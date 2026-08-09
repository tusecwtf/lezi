# 26 — 删除 legacy reconcile 路径

**What to build:** 在所有 roots/media 已迁移后删除普通发表 reconcile route、server/client state、adapters、recordings 和旧 lossy conflict cache。

**Blocked by:** 25

**Status:** ready-for-agent

## Contract slice

只删除已被 commit-first 替代的路径；只读预览不是当前产品合同。不存在 dual-read/dual-write fallback。

## Implementation sequence

1. 枚举所有 route/call/state/cache 引用与观察行为。
2. 删除 server handler/Store surface 与 Android planner/adapter。
3. 删除旧 recordings/tests/cache schema usage。
4. 用 absence tests 证明普通 publish 不能调用 reconcile。

## Acceptance

- [ ] 无可达普通 reconcile route/call/state
- [ ] 无旧 client-supplied resolved root/media adapter
- [ ] no-pull/cursor independence 回归保持
- [ ] unrelated reset/full-resync seam 保留

## Validation

- [ ] compile、route absence、recording-backend tests 通过
- [ ] Android/Rust relevant gates 通过

## Out of scope

不做 schema cleanup migration 或 capability advertise。
