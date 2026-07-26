# post-0.2.4 固定范围审查整改 — 票索引

Spec: [spec.md](./spec.md)
Status: ready-for-agent
Source: `afb0af7..81e9610` Standards / Spec 审查
Ticket count: 7
Frontier count: 2

## 依赖图

```text
01 Settings 清除文案（frontier）

03 typed reminder store（frontier） ──► 04 domain clear coordinator

family-identity-account-overview/04 ──► 02 shared Join use case

family-identity-account-overview/05 ──► 05 sync local-clear module
05 ──► 06 Replica Engine
06 + family-identity-account-overview/04 ──► 07 family-session module
```

**可立即开工（frontier）：** 01 · 03

## 票列表

| ID | 标题 | Blocked by | Size | 状态 |
|---|---|---|---|---|
| [01](./issues/01-context-aware-clear-copy.md) | 未加入家庭的清除文案 | — | S | ready-for-agent |
| [02](./issues/02-shared-join-use-case.md) | 双入口共享 Join 用例 | external family-identity 04 | M | ready-for-agent |
| [03](./issues/03-typed-reminder-cleanup-store.md) | typed 提醒收尾持久化 | — | M | ready-for-agent |
| [04](./issues/04-local-data-clear-coordinator.md) | 统一领域清除协调 | 03 | M | ready-for-agent |
| [05](./issues/05-sync-local-clear-module.md) | 同步侧本地清除深模块 | external family-identity 05 | M | ready-for-agent |
| [06](./issues/06-replica-sync-engine.md) | Replica Engine 深模块 | 05 | L | ready-for-agent |
| [07](./issues/07-family-session-module.md) | 家庭会话深模块与 RealSyncPort 收口 | 06 + external family-identity 04 | M–L | ready-for-agent |

## 过程纪律

- 不重写旧 20 commits；历史 Standards 违规以本 tracker 的依赖图和交付门禁向前修复。
- 一票一 PR/提交序列；禁止 mega-commit。
- 契约、Room schema、产品文案变化同票更新文档与测试；N/A 必须写明理由。
- blocked 票不得提前夹入；02、05、07 不得覆盖当前家庭身份 WIP。
- 05→06→07 采用 replace-don't-layer：新 module 通过 interface 测绿后，删除 `RealSyncPort` 中旧 implementation。

