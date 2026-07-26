# 0.2.4 后跟进 — 票索引（R2）

Spec: [spec.md](./spec.md)
Status: complete
Ticket plan: **R2**（四路 agent 审查后修订 · 15 票）

## 依赖图

```text
01 清除屏障 ─────────┐
03 分页 fail-closed ─┼──► 10 Sync 三分离 ──► 13 Join 命令 Port ──┐
                     │                         │                 │
02 软删失败 ──► 09 计时拆分                    │                 ├──► 14 共用加入表单
                     │                         │                 │
04 health（∥）        │                         v                 │
05 保存 Result ──► 07 家庭 expand ──► 08 家庭 contract ──► 12 endpoint 真源
                     │                                           │
06 文档 ← 01,03,04   │                                           └──► 15 软解析+名+PRD
11 lezi-sync（∥，无硬边）
```

**可立即开工（frontier）：** 01 · 02 · 03 · 04 · 05 · 11

**关键路径（约）：** `05 → 07 → 08 → 12 → 14` 与 `01/03 → 10 → 13 → 14/15`

## 票列表

| ID | 标题 | Blocked by | 阶段 | 状态 |
|----|------|------------|------|------|
| [01](./issues/01-clear-barrier-compensation.md) | 清除屏障补偿与诚实失败 | — | P1 | complete |
| [02](./issues/02-nursing-soft-delete-fail-closed.md) | 计时完成软删失败关闭 | — | P1 | complete |
| [03](./issues/03-pull-page-fail-closed.md) | 分页上限 + 无 has_more 触顶 fail-closed | — | P1 | complete |
| [04](./issues/04-health-probe-hardening.md) | health 禁重定向 + 限长 | — | P1 | complete |
| [05](./issues/05-network-save-result.md) | 网络保存显式 Result | — | P1 | complete |
| [06](./issues/06-p1-docs-min-patch.md) | P1 文档最小补丁 | 01, 03, 04 | P1 | complete |
| [07](./issues/07-family-strategy-sealed-dialog.md) | 家庭策略 + sealed 对话框 | 05 | P2 | complete |
| [08](./issues/08-family-file-split.md) | 家庭文件拆分 + 死路径 | 07 | P2 | complete |
| [09](./issues/09-timer-decompose.md) | 计时四分离 | 02 | P2 | complete |
| [10](./issues/10-sync-client-tri-split.md) | Fake / Http / 编排三分离 | 01, 03 | P2 | complete |
| [11](./issues/11-lezi-sync-thin-modules.md) | lezi-sync 薄模块 | — | P2 | complete |
| [12](./issues/12-endpoint-host-port-scheme.md) | endpoint 真源 host+port+scheme | 08 | P3 | complete |
| [13](./issues/13-join-command-port.md) | Join 命令 + bootstrap Port | 10 | P3 | complete |
| [14](./issues/14-shared-join-form.md) | 引导/家庭共用加入 | 08, 12, 13 | P3 | complete |
| [15](./issues/15-members-soft-parse-name-prd.md) | 成员软解析 + 名对齐 + PRD | 12, 13 | P3 | complete |

## 过程纪律

- 一 PR 一票主题；禁止 mega-commit。
- 契约/wire 变更同 PR 更新文档与测试。
- 结构票（07–11）**行为冻结**，且不得削弱 P1 接缝 S1–S4 测试。
- 从 frontier 取票；用 `/implement` 一次一张，清 context 再开下一张。

## 相对 R1/原 12 票的变更摘要

| 变更 | 原因 |
|------|------|
| 去掉 05←01 | 假依赖 |
| 文档独立为 06，blocked by 01+03+04 | 避免 Result+文档杂烩 |
| 家庭拆 07 expand + 08 contract | 单 context 红区 |
| 10 硬依赖 01+03，不硬依赖 04 | RealSyncPort 冲突面 |
| Join 拆 13 Port + 14 UI | 跨模块过大 |
| endpoint 12 在共用表单 14 之前 | 表单坐在真源上 |
| 15 不硬等 11 | 客户端软解析 ≠ Rust 搬家 |
