# 04 — 探测/信任后冻结 origin，去掉可改 host 双路径

**What to build:** 端点 probe/trust 成功后，创建/加入提交使用的 origin 必须来自已验证
的 `TrustedEndpointProfile`，用户不得通过「上一步」或独立网络对话框把 host/port 改成
未再探测的地址并写入 session。Onboarding 创建表单去掉可编辑 host/port（只读展示 origin）。

**Blocked by:** None — can start immediately（与 05 建议同一执行者）。

**Status:** complete

**Severity:** High
**Blocks release:** yes
**Review ID:** F-04

## Must

- [x] 账户向导：`EndpointReady` 之后不得再进入可写 host/port 的网络步；删除或禁用
      post-probe 的 `FamilyWizardEndpointDialog` / `advanceWizardNetwork` 绕路。
- [x] Domain `submit*`：创建/Owner 登录/成员申请在发送秘密前要求
      `verifiedEndpoint` 存在且与 snapshot origin 匹配；否则失败且不写 session。
- [x] Onboarding CreateFamily：host/port 只读或移除字段，提交用 verified origin。
- [x] 测试：探测成功后改 draft host 不能 `saveEndpointConfig` 成新地址并带 secret 调用
      gateway；domain 无 verified 时不调用 create/login。
- [x] 清理死路径：`openWizard(mode)` 预探测 Create/Join 入口若仍存在且违反 connect-first，
      删除或改为 connect-first。

## Evidence paths

- `feature/family/FamilyScreen.kt`, `FamilyDialogs.kt`, `FamilyUiPolicy.kt`
- `feature/onboarding/OnboardingScreen.kt`
- `domain/FamilyWizardController.kt` submit paths
- Design: `docs/design/2026-07-30-trusted-sync-onboarding-ui.md`
- PRD: `docs/prd/sync-trusted-endpoint.md`（草稿不污染可信状态）

## Comments

- 下层 `matchesOrigin` fail-closed **不能**替代本票；session host 污染与合同违反仍是缺陷。
