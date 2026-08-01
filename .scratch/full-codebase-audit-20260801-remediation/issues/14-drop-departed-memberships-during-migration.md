# 14 — 离线迁移丢弃 departed membership

**What to build:** v3 offline-migrate 把旧 `left_at IS NOT NULL` 解释为已删除身份：不复制 membership，并把保留事实中的作者/提交者引用匿名化，符合 current hard-delete contract。

**Source:** `AUDIT-20260801-P1-14`  
**Blocked by:** None — can start immediately  
**Status:** ready-for-agent  
**Size:** M

## Acceptance criteria

- [ ] `copy_memberships` 只复制 active membership；目标库不含 `left_at` 身份墓碑，也不占用旧 display name。
- [ ] 指向 departed membership 的 Record/CarePlan/CustomItem/FulfillmentCandidate 等保留实体按 current contract 将作者/提交者置 null，而不是留下悬空 FK 或伪归因。
- [ ] active Owner 唯一性与 active display-name 冲突检查保持 fail closed；departed rows 不参与 active 计数。
- [ ] departed membership 的 device/credential/request/session 数据不复制，且报告区分 copied 与 anonymized/dropped 数量。
- [ ] fixture 覆盖 departed Member 有历史事实、candidate、同名 active 新成员、以及 departed Owner 的非法源库。
- [ ] 迁移后 current `hard_delete_membership`/members API 不需要也不能再次“清理”旧 departed 行。
- [ ] dry-run/inventory 与实际 transform 使用同一 disposition，文档不再声明 `left_at` keep。

## Validation

运行 offline_migrate 全套 tests、Rust fmt/test/clippy，并对临时 SQLite 源/目标做 row/FK/schema 检查；若用于家庭 NAS cutover，按 AGENTS.md 先提议维护窗 CD。

## Documentation Gate

更新 migration inventory/README，明确 departed 身份按 hard-delete + anonymous fact 转换。

## Out of scope

不把 offline-migrate 变成服务启动时自动迁移；架构边界由 Ticket 21 收口。
