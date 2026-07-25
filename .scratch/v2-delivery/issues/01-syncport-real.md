# 01 — SyncPort 真实现

**Parent:** [../spec.md](../spec.md) · PRD §4.7 V2 · data-model SyncPort

**What to build:** Outbox + push/pull + 幂等/LWW/tombstone；SettingsLocal 不同步；离线记账。

**Blocked by:** V1 完成（接口可替换 NoOp）

**Status:** done  

> **Follow-up:** 规格对齐见 [../../home-lan-sync/](../../home-lan-sync/)（门闩/token/媒体/无硬编码默认）。本票为早期原型完成态，不代表 `sync-home-lan` 已交付。

## 交付物

| 工程 | RealSyncPort、Outbox、后端适配；集成测试用 fake |
| 用户可见 | 同步状态（同步中/失败）可感知（可在账户页） |

## 验收标准（Must）

- [x] **写路径**：addRecord 成功后 Outbox 有 pending 项（同步开启时）
- [x] **push**：fake server 收到载荷含 client_uuid
- [x] **pull**：远端一条记录合并进 Room，CareLog.dayRecords 可见
- [x] **幂等**：同一 client_uuid push 两次 → 本地/远端不双份
- [x] **LWW**：冲突取 updated_at 较新
- [x] **tombstone**：删除后 pull 对端不可见（或标记删除）
- [x] **SettingsLocal**：改 dark/图标顺序 **不** 出现在同步载荷
- [x] **离线**：断网 addRecord 成功；恢复网络后 outbox 减少
- [x] **无逐条推送**：默认不因远端新记录弹通知

## 不在本票范围

- 邀请 UI（02）、60s 真机（03）
