# 02 — 模块命名、库级默认与竖切骨架

**What to build:** 钉下实现期模块/库默认，并长出可编译的能力竖切 + 同步会话 + 共享内核骨架与 composition root。

**Blocked by:** 01 — 绿场空壳、隔离与基础门禁.

**Status:** ready-for-agent

- [ ] 文档或代码中锁定 Gradle/crate 命名与 DI/本地持久化等库级默认
- [ ] 共享内核、至少两个能力竖切占位、同步会话竖切占位可编译
- [ ] 竖切之间无编译依赖；仅 composition root 组装
- [ ] 同步会话竖切是唯一计划承载 wire 客户端的位置（可仍为空实现）
