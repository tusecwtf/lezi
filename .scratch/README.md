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
| Post-0.3.0 code-review 残差 | [spec](./post-0.3.0-code-review-remediation/spec.md) | [票索引](./post-0.3.0-code-review-remediation/ISSUES.md) | complete · 9/9 · fixed-point gates passed |
| 0.3.0 APK 逐页验收整改 | [spec](./apk-0.3.0-page-acceptance-remediation/spec.md) | [票索引](./apk-0.3.0-page-acceptance-remediation/ISSUES.md) | complete · 1/1 · fixed-point gates passed |
| 可信同步 endpoint、鉴权与 NAS→VPS 迁移 | [spec](./trusted-sync-endpoint-auth/spec.md) | [ISSUES](./trusted-sync-endpoint-auth/ISSUES.md)（17 票） | active · 14/17 · frontier 15 |
| 离线模式（先本机记账，账户再连家庭） | [spec](./offline-mode/spec.md) | [票索引](./offline-mode/ISSUES.md) | complete · 6/6 |
| 审查残差 P1（domain/composer + 网络层 0.3.1 复核） | [spec](./p1-review-residuals/spec.md) | [ISSUES](./p1-review-residuals/ISSUES.md)（8 票） | ready-for-agent · frontier 01/02/04；05–08 planned 等 0.3.1 |
| 自托管应用内更新（侧载 APK + NAS 部署带包） | [spec](./self-hosted-app-update/spec.md) | [ISSUES](./self-hosted-app-update/ISSUES.md)（5 票 · 垂直切片） | ready-for-agent · frontier 01 |

产品契约已写回 [`docs/prd/`](../docs/prd/)；本轮最终构建、设备与 current-wire 结果见
[`release-0.3.0/validation.md`](./2c6bbf6-program-audit-remediation/evidence/release-0.3.0/validation.md)。

## 2026-07-30 静态验收收口

- 对 0.3.0 及更早已完成实现、且静态/自动化证据已满足票内 Must 的项目，不再仅因物理 NAS、
  物理手机、相机扫码或 spoken TalkBack 环境不可得而保持未关闭。
- 这些目标环境门禁仍是明确的**未执行证据边界**，不得改写为已通过；对应票按昨晚固定 HEAD
  静态复核结果维持 `complete`。
- Post-0.3.0 correctness 01/02 与结构票 03–09 已闭合；tracker 9/9 complete；最终固定点
  JVM、Lint、Debug APK、Rust/current-wire 与 API 35 全量设备门禁均通过，见
  [`final/validation.md`](./post-0.3.0-code-review-remediation/evidence/final/validation.md)。

## 2026-07-30 APK 逐页验收（grill 已收口）

- 审计：[`docs/reviews/2026-07-30-apk-page-acceptance-audit.md`](../docs/reviews/2026-07-30-apk-page-acceptance-audit.md)
- Tracker：[apk-0.3.0-page-acceptance-remediation](./apk-0.3.0-page-acceptance-remediation/spec.md) · **01 complete**
- Residual：右滑删除 [compound](./apk-0.3.0-page-acceptance-swipe-delete-compound.md)
- 最终门禁：与 Post-0.3.0 tracker 共用
  [`final/validation.md`](./post-0.3.0-code-review-remediation/evidence/final/validation.md)。
