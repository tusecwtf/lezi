# Scratch · 当前规格与票单

本目录保留当前 active tracker、审查残差与仍需引用的验收上下文；使用方式见
[`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)。

| 主题 | Spec | 票索引 | 状态 |
|------|------|--------|------|
| 文档–代码 gap 整改（原子同步 / UI / 契约） | [spec](./doc-code-gap-remediation/spec.md) | [ISSUES](./doc-code-gap-remediation/ISSUES.md)（7 票） | complete · 7/7 |
| Post-0.3.0 code-review 残差 | [spec](./post-0.3.0-code-review-remediation/spec.md) | [票索引](./post-0.3.0-code-review-remediation/ISSUES.md) | complete · 9/9 · fixed-point gates passed |
| 0.3.0 APK 逐页验收整改 | [spec](./apk-0.3.0-page-acceptance-remediation/spec.md) | [票索引](./apk-0.3.0-page-acceptance-remediation/ISSUES.md) | complete · 1/1 · fixed-point gates passed |
| 可信同步 endpoint、鉴权与 NAS→VPS 迁移 | [spec](./trusted-sync-endpoint-auth/spec.md) | [ISSUES](./trusted-sync-endpoint-auth/ISSUES.md)（17 票） | **complete · 17/17** · 0.3.1 artifacts · [evidence/17](./trusted-sync-endpoint-auth/evidence/17/validation.md) |
| Trusted-sync 实现后 code-review 残差 | [spec](./trusted-sync-review-residuals/spec.md) | [ISSUES](./trusted-sync-review-residuals/ISSUES.md)（13 票） | complete · 13/13 Must · fixed-point gates passed |
| 审查残差 P1（domain/composer + 网络层 0.3.1 复核） | [spec](./p1-review-residuals/spec.md) | [ISSUES](./p1-review-residuals/ISSUES.md)（8 票） | **complete · 01–04 done · 05–08 cancelled**（0.3.1 cutover 覆盖） |
| 自托管应用内更新（侧载 APK + NAS 部署带包） | [spec](./self-hosted-app-update/spec.md) | [ISSUES](./self-hosted-app-update/ISSUES.md)（5 票 · 垂直切片） | complete · 5/5 · [evidence/05](./self-hosted-app-update/evidence/05/validation.md) |
| 应用内更新审查残差（SyncStatus/安装/minSupported/强制 UI） | [spec](./app-update-review-residuals/spec.md) | [ISSUES](./app-update-review-residuals/ISSUES.md)（6 票） | complete · 6/6 · [evidence/06](./app-update-review-residuals/evidence/06/validation.md) |
| NAS v3 离线一次升级（拷出→本机转换→拷回） | [spec](./nas-v3-offline-migrate/spec.md) | [ISSUES](./nas-v3-offline-migrate/ISSUES.md)（7 票） | **complete · 7/7** · 现网 HTTPS 0.3.0 · [evidence/07](./nas-v3-offline-migrate/evidence/07/RESULT.md) · 实现多在 worktree（skip_commit；01–04 部分已提交） |

产品契约已写回 [`docs/prd/`](../docs/prd/)；残差与验收边界以各 tracker 的当前证据为准。

## 2026-07-30 APK 逐页验收（grill 已收口）

- 审计：[`docs/reviews/2026-07-30-apk-page-acceptance-audit.md`](../docs/reviews/2026-07-30-apk-page-acceptance-audit.md)
- Tracker：[apk-0.3.0-page-acceptance-remediation](./apk-0.3.0-page-acceptance-remediation/spec.md) · **01 complete**
- Residual：右滑删除 [compound](./apk-0.3.0-page-acceptance-swipe-delete-compound.md)
- 最终门禁：与 Post-0.3.0 tracker 共用
  [`final/validation.md`](./post-0.3.0-code-review-remediation/evidence/final/validation.md)。
