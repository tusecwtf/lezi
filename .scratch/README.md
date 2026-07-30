# Scratch · 当前规格与票单

本目录保留当前整改 tracker、逐票证据与最终验收记录。0.3.0 的四组整改已闭合，
post-0.3.0 独立审查残差另开 tracker；使用方式见
[`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)。

| 主题 | Spec | 票索引 | 状态 |
|------|------|--------|------|
| 文档–代码 gap 整改（原子同步 / UI / 契约） | [spec](./doc-code-gap-remediation/spec.md) | [ISSUES](./doc-code-gap-remediation/ISSUES.md)（7 票） | complete · 7/7 |
| 2026-07-29 P1 冗余与 UI 还债（A–F） | [spec.md](./p1-redundancy-ui-debt/spec.md) | [ISSUES.md](./p1-redundancy-ui-debt/ISSUES.md)（10 票） | complete · 10/10 |
| 记录页布局编辑整改 | [spec](./record-layout-edit-remediation/spec.md) | [票索引](./record-layout-edit-remediation/ISSUES.md) | complete · 12 本地票 + P1/02 |
| 当前程序综合审计整改 | [spec](./2c6bbf6-program-audit-remediation/spec.md) | [票索引](./2c6bbf6-program-audit-remediation/ISSUES.md) | complete · 26/26 · Release 0.3.0 |
| Post-0.3.0 code-review 残差 | [spec](./post-0.3.0-code-review-remediation/spec.md) | [票索引](./post-0.3.0-code-review-remediation/ISSUES.md) | ready-for-agent · 2 immediate + 7 planned · frontier 01/02 |

产品契约已写回 [`docs/prd/`](../docs/prd/)；本轮最终构建、设备与 current-wire 结果见
[`release-0.3.0/validation.md`](./2c6bbf6-program-audit-remediation/evidence/release-0.3.0/validation.md)。

## 2026-07-30 静态验收收口

- 对 0.3.0 及更早已完成实现、且静态/自动化证据已满足票内 Must 的项目，不再仅因物理 NAS、
  物理手机、相机扫码或 spoken TalkBack 环境不可得而保持未关闭。
- 这些目标环境门禁仍是明确的**未执行证据边界**，不得改写为已通过；对应票按昨晚固定 HEAD
  静态复核结果维持 `complete`。
- 此规则不适用于新发现的 Post-0.3.0 残差：01–02 仍是 immediate frontier，03–09 仍是
  planned，必须各自完成验收后才能关闭。
