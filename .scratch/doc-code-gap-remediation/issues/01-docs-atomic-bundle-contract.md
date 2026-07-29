# 01 — 文档：原子包与 ordinary 退役契约

**What to build:** 把「全部 Record（含 0 图）与全部 CarePlan 走 atomic bundle；ordinary `/v1/push` 退役；log 媒体仅 bundle 成员；pull 共组；health 超集」写进现行文档，消除「带照片才 bundle / 无照片 Record ordinary」残留。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 更新 `docs/adr/0008-support-only-fresh-current-product-contracts.md`：ordinary 关闭集合、atomic 根类型、log 仅 bundle、pull 共组、health 超集、Record 任成员可改（与 07 交叉处可一句链到 data-model）
- [ ] 更新 `docs/prd/sync-home-lan.md` §5 / §9.1 / §9.6 / §9.8.1 与 ordinary 相关 bullet，删除「无照片 Record ordinary」矛盾句
- [ ] 更新 `tools/lezi-sync/README.md` 原子包与 ordinary 描述
- [ ] 全文搜「带照片」「无照片 Record」「ordinary」确认无冲突叙述
