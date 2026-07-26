# 06 — P1 文档最小补丁（清除 / 分页 / health / 威胁模型）

**Parent:** [../spec.md](../spec.md)

**What to build:** 在行为票落地后，用最小 PRD/README 补丁写清：已加入时清除仅本机且可能被同步拉回；pull 页上限与触顶无 `has_more` 的 fail-closed；health 禁重定向与限长摘要；默认家网 HTTP 的威胁边界（同网可读、非 TLS 远程不在支持范围）。不做完整 SyncPort 参数表（P3）。

**Blocked by:** 01 — 清除屏障；03 — 分页 fail-closed；04 — health hardening

**Status:** complete

## Acceptance criteria

- [x] 文档写明：已加入清除 = 本机；服务器保留；下次家网同步可能重新下载
- [x] 文档写明：pull 页硬上限；触顶且无 `has_more` 时失败而非静默成功
- [x] 文档一行级摘要：health 不跟随重定向 + 限长
- [x] 威胁模型：默认家网 HTTP；同网可嗅探；非 TLS 公网/远程不在支持范围
- [x] 不含完整 SyncPort/members/scheme 长表（留给 15）
- [x] 与 01/03/04 已合并行为一致，不编造未实现承诺

## Comments

- R2：从原 05 拆出，避免 Result 与文档横切杂烩。02 不硬挡文档。
