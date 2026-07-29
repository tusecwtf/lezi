# Scratch · 当前规格与票单

本目录只保留**尚未闭合、仍可执行**的本地票单。使用方式见
[`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)。

| 主题 | Spec | 票索引 | 状态 |
|------|------|--------|------|
| 文档–代码 gap 整改（原子同步 / UI / 契约） | [spec](./doc-code-gap-remediation/spec.md) | [ISSUES](./doc-code-gap-remediation/ISSUES.md)（7 票） | ready-for-agent · frontier 02/04/05/06/07 → 03 |
| 2026-07-29 P1 冗余与 UI 还债（A–F） | [spec.md](./p1-redundancy-ui-debt/spec.md) | [ISSUES.md](./p1-redundancy-ui-debt/ISSUES.md)（10 票） | ready-for-agent |
| 记录页布局编辑整改 | [spec](./record-layout-edit-remediation/spec.md) | [票索引](./record-layout-edit-remediation/ISSUES.md) | ready-for-agent · 14 findings / 12 本地票 + P1/02 · frontier 2 |
| 当前程序综合审计整改 | [spec](./2c6bbf6-program-audit-remediation/spec.md) | [票索引](./2c6bbf6-program-audit-remediation/ISSUES.md) | ready-for-agent · 26 findings / 24 本地票 + 2 布局权威票 · audit frontier 14 |

已完成的实施过程、审查报告、截图和运行 dump 不在仓库中长期归档；产品结论应写回
[`docs/prd/`](../docs/prd/)。

闭合票目录在验收关闭后删除，避免 `.scratch` 堆积历史 tracker。
