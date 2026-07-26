# 12 — 家网 endpoint 真源：host + port + scheme

**Parent:** [../spec.md](../spec.md)

**What to build:** 持久化以 host、port、scheme 为权威；`baseUrl` 仅派生。清理「baseUrl 与三字段双真源」残留；旧键迁移；邀请与保存网络共用同一配置构造规则。照护者改地址后实际连接可预期。

**Blocked by:** 08 — 家庭文件拆分（网络表单边界稳定）

**Status:** complete

## Acceptance criteria

- [x] 读写权威为 host/port/scheme；baseUrl 纯派生
- [x] 旧存储迁移有测（接缝 S7）
- [x] 邀请载荷与保存网络使用同一规范化构造
- [x] 已加入会话迁移后仍能正确连原服务器
- [x] 不在本票重做 Join 命令对象（见 13）

## Comments

- R2：不硬等 13；顺序上先于共用 Join 表单（14）。
