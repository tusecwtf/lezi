# 18 — 按能力拆分 SyncPort/RealSyncPort

**What to build:** 在行为回归固定后，以 expand–migrate–contract 把更新、账户/家庭会话、replica trigger/status 与媒体/本机清理拆成窄端口，降低 feature 对整个 `RealSyncPort` 的依赖。

**Source:** `AUDIT-20260801-P2-04`  
**Blocked by:** 01、02、03、16  
**Status:** ready-for-agent  
**Size:** L

## Acceptance criteria

- [ ] 先以现有 public behavior tests 固定强制更新、家庭会话、同步触发、媒体 cleanup 与 local clear 合同。
- [ ] 至少形成独立的 AppUpdate、FamilySession/Account、ReplicaSync、Media/LocalClear 能力接口；命名可按实际 ownership 调整，但禁止一个新 facade 继续聚合全部方法。
- [ ] domain 只依赖写后触发、session/clear 等所需最小 seam；各 feature 只注入自身使用的能力。
- [ ] Real 实现可共享内部 coordinator/mutex，但锁顺序、CUR、terminal clear 与 foreground gate 保持单一 owner。
- [ ] 迁移期适配器有删除条件；所有调用者归零后删除旧宽 `SyncPort`，不长期保留双 API。
- [ ] Hilt bindings、NoOp/offline 实现和 tests 按能力拆分；不增加 feature↔feature 依赖。
- [ ] 文件体量下降只是结果，闭票依据是职责/调用边界和回归，不以任意行数阈值替代。
- [ ] current client/server wire、错误映射、outbox/cursor 与用户文案无行为漂移。

## Validation

运行全量 JVM tests、`lintDebug`、`:app:assembleDebug`；同步行为做 current server 集成。若触及 live wire 证明，按 AGENTS.md 提议 CD。

## Documentation Gate

更新模块图、DI ownership 与关键锁顺序；如形成稳定架构决策，补 ADR。

## Out of scope

不改 wire、数据库 schema、产品权限或同步时机。
