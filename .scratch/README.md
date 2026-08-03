# Scratch · 工作项

本目录是 **active** 规格与票单的工作区（约定见
[`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)）。

**当前 active：**

| Tracker | 说明 |
|---------|------|
| [`sync-publish-reconcile-first`](./sync-publish-reconcile-first/) | **complete** — 客户端发表计划改为对账优先、临时 plan；Room 26 迁移保留旧 APK 数据与发布意图 |
| [`family-sync-hang-and-account-fidelity`](./family-sync-hang-and-account-fidelity/) | **implementation-complete** — 0.3.5 已从 `149b7ae6` 部署；7 项保留 live/NAS/多设备验收 |
| [`family-network-offline-disaster-recovery`](./family-network-offline-disaster-recovery/) | **acceptance — awaiting joined-device smoke** — live 已前进至 0.3.5；票 07 真机补传待完成 |
| [`codebase-audit-20260802-remediation`](./codebase-audit-20260802-remediation/) | **complete** — implementation `6b278242`; ticket 07 regression `d160fe68` |

产品权威仍在 [`docs/prd/`](../docs/prd/)、[`docs/adr/`](../docs/adr/)、
[`CONTEXT.md`](../CONTEXT.md)。已关闭票的实现与证据在 git 历史中。

---

## 2026-08-03 清理与新建

**删除（complete，索引保留）：**

| Tracker | 说明 |
|---------|------|
| `family-account-sync-usability-0.3.4` | 0.3.4 成员申请超时 / local-first 放弃 / 账户卡 — 已在 tree |
| `full-codebase-audit-20260801-remediation` | 32 done + 1 wontfix — 已在 tree |
| `local-data-upgrade-protection` | 本地契约 v1 门禁 — 已在 tree |

**新建：** `family-sync-hang-and-account-fidelity`（诊断 + 多轮审计 + dedup + 饱和）。

**保留：** `family-network-offline-disaster-recovery`（07 未勾完）。

---

## 以后怎么用 `.scratch/`

1. 新工作：`.scratch/<feature-slug>/spec.md` + `issues/01-….md` + `ISSUES.md`
2. Tracker **complete** 且证据进 git 后：可删目录，README 留一行索引
3. 翻旧票：`git log -- .scratch/<slug>` / `git show <commit>:.scratch/...`
