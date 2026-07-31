# 08 — 内部明文口仅 health/ready

**What to build:** `LEZI_INTERNAL_PORT`（默认 8766）上的明文 HTTP 只暴露 `/health` 与
`/ready`（或等价 readiness），不得挂载完整业务 router。README 与实现一致。

**Blocked by:** None — can start immediately.

**Status:** complete

**Severity:** Medium
**Blocks release:** preferred
**Review ID:** F-08

## Must

- [x] 拆分 internal router：仅健康检查；create/login/refresh/pull/media 在 internal 上 404。
- [x] 公共 TLS 口行为不变。
- [x] 测试：internal 口 `/ready` 200；任选业务路径不可用。
- [x] README 删除「readiness-only」与实现不符的表述或改为与代码一致。

## Evidence paths

- `tools/lezi-sync/src/main.rs`
- `tools/lezi-sync/README.md`
- compose healthcheck 仍可用 internal 口

## Comments

- 容器内 loopback 为主；本票是 defense-in-depth，防误映射与文档脚枪。
