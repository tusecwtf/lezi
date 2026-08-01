# 24 — Family 三 host 与目录对齐

**What to build:** `feature/family` 以 AccountOverview、MembersDevices、Wizard 三条调用流
承载页面状态和命令，并让目录与这些 seam 对齐；改成员/设备页不必读完整五流 God 表面。

**Source:** merged readability 03 + directory C4
**Blocked by:** 23 — QR 状态机先归一到 FamilyWizardController (done)
**Status:** ready-for-agent
**Size:** M–L

## Acceptance criteria

- [ ] AccountOverview host 只拥有账户概览 read model、同步状态一句和可选更新入口。
- [ ] MembersDevices host 只拥有 roster/设备刷新、审批、改名、撤销、删除与邀请家人入口。
- [ ] Wizard host 薄委托既有 `FamilyWizardController`，含 Ticket 23 的成员登录 QR 结果映射。
- [ ] 目录按 `overview/`、`members/`、`wizard/`、`baby/` 对齐；`FamilyScreen` 可留根作导航壳，
  真共享视觉可留根或进入 `components/`。
- [ ] 宝宝档案/头像与 app-update 保持薄委托，不新增第四个 deep port 或浅转发 manager。
- [ ] 账户概览仍只展示家庭名、本人家庭称呼、同步状态一句、成员与设备入口；技术凭证不回流。
- [ ] feature 模块仍互不依赖；test/androidTest package 与 host 行为测试同步更新。

## Validation

运行 domain/family/onboarding 相关 tests、`:feature:family:compileDebugKotlin`、
`:app:assembleDebug`、`lintDebug`；设备 smoke 账户概览、成员设备入口与家庭向导。

## Documentation Gate

若稳定 host ownership 与现有 PRD/tech 图不一致，按 current implementation 更新；不新增 ADR 除非形成难逆决策。

## Out of scope

不改变家庭权限、邀请合同、宝宝权威，不提取 AppUpdatePort。
