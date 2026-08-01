# 16 — 删除忽略 familyId 的 pull/push 门面

**What to build:** 移除 `SyncPort.pull(familyId)` / `push(familyId)` 这组忽略参数的死 API，所有调用只通过带明确 trigger 的当前会话同步入口。

**Source:** `AUDIT-20260801-P2-02`  
**Blocked by:** 01 — finish RealSyncPort update-state behavior before contracting its surface  
**Status:** ready-for-agent  
**Size:** S–M

## Acceptance criteria

- [ ] 生产 `SyncPort`、Real/NoOp 实现与 tests 不再暴露会被忽略的 `familyId` pull/push。
- [ ] 当前调用者迁移到 `sync(SyncTrigger)` 或更窄端口；LocalWrite 与 PullToRefresh 语义保持不变。
- [ ] 不允许用无参数 `pull()`/`push()` 复制同一死门面；当前 session family 是唯一 authority。
- [ ] 若发现外部/测试调用依赖任意 family ID，改为显式 backend/session test seam，而不是产品端口越权选择家庭。
- [ ] 编译期证明不存在旧签名与未使用 family 参数；joined/unjoined/reauth 行为回归不变。

## Validation

运行 `:sync:test`、相关 feature/domain tests、`:app:assembleDebug`、`lintDebug`。

## Documentation Gate

更新技术文档与示例，只描述 trigger 驱动的当前会话同步。

## Out of scope

不拆分整个 SyncPort；能力拆分由 Ticket 18 处理。
