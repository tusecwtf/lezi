# 01 — 向导 pending 恢复不得覆盖活跃/Completed 状态

**What to build:** 当 `pendingMemberLogin != null` 时，账户/引导侧的 restore 逻辑不得
把任意非 `Submitting` 的向导状态强制写成 `WaitingForMemberApproval`。只允许在空闲或
已是等待态时恢复；`Completed`、探测、证书确认、EndpointReady、身份提交相关状态必须
保留。

**Blocked by:** None — can start immediately.

**Status:** complete

**Severity:** Critical
**Blocks release:** yes
**Review ID:** F-01

## Must

- [x] `FamilyViewModel` / Onboarding 中对 `pendingMemberLogin` 的 collector 不再无条件
      调用 restore 覆盖当前状态。
- [x] `FamilyWizardController.restorePendingMemberApproval` 拒绝覆盖
      `Completed`、`ProbingEndpoint`、`CertificateApprovalRequired`、`EndpointReady`、
      身份提交与 `Submitting`（及审查确认的同类活跃态）。
- [x] 批准成功路径：即使 pending 短暂仍非 null，也不会把 `Completed` 拽回等待页。
- [x] 冷启动/账户打开时，仍有 pending 的设备能正确进入等待批准 UI。
- [x] 回归测试：pending 存在时强制注入 `Completed` / probing / ready 不被覆盖；
      空闲态仍可 restore。

## Evidence paths

- `feature/family/.../FamilyViewModel.kt` init combine
- `feature/onboarding/.../OnboardingScreen.kt` pending collector
- `domain/.../FamilyWizardController.kt` `restorePendingMemberApproval`

## Comments

- 2026-07-31 review：仅跳过 `Submitting` 不够；Onboarding 同类问题一并修。
