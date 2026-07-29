# 04 — 原子包媒体机械与 customItemUuid 单 helper

**Parent:** [../spec.md](../spec.md)

**What to build:** 护理记录与护理计划的**原子同步打包机械**（筛媒体 → 准备上传元数据 → stage/put/commit 所需 draft）走同一 helper，两 root 仍分离（ADR-0005/0008）。解析自定义项目 `clientUuid` 供出箱与副本引擎的逻辑只保留一份，捕获与推送错误/空规则一致。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M  
**Theme:** C（R6 / R7）  
**Seams:** Outbox 原子打包；副本引擎自定义项引用解析

## Acceptance criteria

- [ ] record 与 care-plan 原子推送共享打包机械；root 类型参数化，不合并领域实体
- [ ] `recordCustomItemClientUuid`（或等价）仅一处实现，出箱与引擎均调用
- [ ] 失败窗口：媒体未齐不可部分可见的既有契约测试仍绿
- [ ] mime/byte_size 修补等 residual 不再各写一套互斥规则
- [ ] `:sync` 相关单元测试通过；新增或改写测试锁定「双 root 同 helper、uuid 解析一致」

## Out of scope

- 改 NAS 原子协议字段或 schema
- CareLog 照片 reconcile（05）
