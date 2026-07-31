# 13 — SECURITY/AGENTS 等文档与 HTTPS 合同对齐

**What to build:** 更新仍描述 SSID 家庭门闩、cleartext 长效 Bearer、或公共口
`http://127.0.0.1:8765/health` 的运维/安全文档，使之与 trusted-sync 现状一致；不改产品
代码行为。

**Blocked by:** None.

**Status:** complete

**Severity:** Low
**Blocks release:** no
**Review ID:** F-13

## Must

- [x] `SECURITY.md` 中 Home-LAN/SSID 表述改为可信 HTTPS endpoint + 设备会话模型（或明确
      历史）。
- [x] `AGENTS.md` / lezi-sync 运维片段：公共口健康检查使用 HTTPS（或注明仅 internal
      明文 health）。
- [x] 与 `tools/lezi-sync/README.md` setup-status capabilities 列表一致（若仍漂移）。
- [x] 抽查：不在文档中重新鼓励生产 cleartext 8765。

## Evidence paths

- `SECURITY.md`, `AGENTS.md`, `tools/lezi-sync/README.md`, 相关 deploy 文档

## Comments

- 纯文档；可与 08 同批改 README。
