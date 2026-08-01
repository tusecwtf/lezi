# Scratch · 工作项收口总结（2026-08-01）

本目录是 **active** 规格与票单的工作区（约定见
[`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)）。

**当前 active：**

| Tracker | 说明 |
|---------|------|
| [`full-codebase-audit-20260801-remediation`](./full-codebase-audit-20260801-remediation/) | 全库审计 P1/P2 正确性与结构债 |
| [`architecture-readability-optimization`](./architecture-readability-optimization/) | 架构可读性：StructureTest 删除、Family 三缝、QR 下沉、SyncPort 死表面、tech.md 边（与审计 16/17 协调；不做 18 大拆） |

`local-data-upgrade-protection` 已完成实现与本机验收，保留到下一次严格清理。

产品权威仍在 [`docs/prd/`](../docs/prd/)、[`docs/adr/`](../docs/adr/)、
[`CONTEXT.md`](../CONTEXT.md)。已关闭票的实现与证据在 git 历史中，不在本目录长期堆放。
---

## 本轮清掉了什么

2026-08-01 清理时，下列 tracker 均为 **complete**（或 05–08 **cancelled**），已从
`.scratch/` 删除：

| Tracker | 结果 | 代表交付 |
|---------|------|----------|
| `trusted-sync-endpoint-auth` | 17/17 complete | 可信 HTTPS、设备会话、成员/设备生命周期；**0.3.1** 发版 |
| `trusted-sync-review-residuals` | 13/13 complete | 实现后 review 阻断项（pending clobber、限流、SPKI freeze 等） |
| `p1-review-residuals` | 01–04 done · 05–08 cancelled | domain/composer 即时修；网络层由 0.3.1 cutover 覆盖后复核取消 |
| `nas-v3-offline-migrate` | 7/7 complete | 家庭 NAS v3→current 离线一次升级 + TLS cutover |
| `self-hosted-app-update` | 5/5 complete | 侧载 APK 自托管更新通道 |
| `app-update-review-residuals` | 6/6 complete | 安装/minSupported/强制 UI 等审查残差 |
| `doc-code-gap-remediation` | 7/7 complete | 原子包 / ordinary 退役 / PRD hygiene |
| `post-0.3.0-code-review-remediation` | 9/9 complete | next-feed / CareLog seams / composer 拆分 |
| `apk-0.3.0-page-acceptance-remediation` | 1/1 complete | Snackbar 不压快捷坞 |
| `record-layout-edit-remediation` | complete | 布局编辑拖放/撤销/无障碍 |
| `p1-redundancy-ui-debt` | 10/10 complete | 确认面/照片/LogScreen 拆分等还债 |
| `offline-mode` | 6/6 complete | 引导离线模式与稍后建家/加入 |
| `2c6bbf6-program-audit-remediation` | 26/26 complete | 程序审计全量整改 |
| `self-hosted-app-update-review-residuals` | 空壳 | 无票，已删 |

一并删除的散文件：

- `apk-0.3.0-page-acceptance-audit.md`
- `apk-0.3.0-page-acceptance-swipe-delete-compound.md`（accepted residual / B-evidence，非 open 票）
- `post-0.3.0-code-review-p0-p1.md`

---

## 当前产品落点（0.3.1）

| 面 | 状态 |
|----|------|
| App | `versionName=0.3.1` · `versionCode=8` |
| Server | `lezi-sync` crate/image `0.3.1` |
| 同步合同 | 可信 HTTPS + 每设备会话；旧 SSID/明文/长期 family token 已退役 |
| 本地交付物（gitignored） | `dist/lezi-sync-0.3.1-nas/` · `dist/release-0.3.1/` |
| 收口提交 | `aa7d3df` release bump + tracker close；细节以该 commit 与前后历史为准 |

已完成：[`local-data-upgrade-protection`](./local-data-upgrade-protection/) 将 Android
0.3.0 / 本地数据契约 v1 固化为永久兼容基线，并为后续 APK 原地替换增加无破坏升级门禁。

## 当前 active tracker

| Tracker | 状态 | Frontier | 说明 |
|---------|------|----------|------|
| [`full-codebase-audit-20260801-remediation`](./full-codebase-audit-20260801-remediation/) | ready-for-agent | 见该目录 ISSUES | 21 票 P1/P2；与本表下一行在 16/17 上 once-only |
| [`architecture-readability-optimization`](./architecture-readability-optimization/) | ready-for-agent | 01、02、04、05、06 | 审查面压缩；03 依赖 02；lezi-sync 文件拆分 Later |

**未自动做的运维：** 家庭 NAS 上的 `push-and-deploy.sh` 容器替换仍须按根目录
`AGENTS.md` 维护窗确认后再跑。

---

## 以后怎么用 `.scratch/`

1. 新工作：`.scratch/<feature-slug>/spec.md` + `issues/01-….md` + `ISSUES.md`
2. Tracker **complete** 且证据已进 git / PRD 后：可删目录，只在本 README 或 commit
   里留一行索引（避免再堆 closed 考古层）
3. 需要翻旧票：`git log -- .scratch/<slug>` / `git show <commit>:.scratch/...`

### 历史检索提示

```bash
# 0.3.1 发版与 trusted-sync / p1 收口
git show aa7d3df --stat

# NAS v3 离线迁移收口
git log --oneline --grep='nas-v3'

# 某已删路径的最后内容
git log --all --full-history -- .scratch/trusted-sync-endpoint-auth/
```
