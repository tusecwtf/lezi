# 01 — 护理记录墓碑永胜

**What to build:** 家庭权威图一旦接受某条护理记录的软删 tombstone（用户或管理员手删），任何设备再推送该 `client_uuid` 的 live 正文都不得把它复活。对端同步后时间轴保持删除；需要事实时只能新记一条（新 UUID）。权威 atomic commit 与权威对账对 record 与护理计划/自定义项目/履行候选的禁复活策略对齐。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] Atomic commit 在 record 已 tombstone 后拒绝或忽略清零 `deleted_at` 的 live 入站（更高 `updated_at` 亦然）
- [ ] 权威对账/head 路径对 record live-over-tombstone 给出与禁复活一致的终态，不得因 LWW 采用 live
- [ ] 客户端结算后：本机曾有更高 dirty live 时，仍收敛为权威 tombstone，时间轴不可见该条
- [ ] 误删恢复路径仅为新记一条；无静默 restore 产品出口
- [ ] PRD/合同中「record 可 LWW restore」的表述被移除或改为墓碑永胜（可与 04 文档收口重复核对，本票行为须已正确）
- [ ] 隔离 lezi-sync 上有 Store/API 回归：tombstone 后更高 live 不得复活；幂等 tombstone 重放安全
