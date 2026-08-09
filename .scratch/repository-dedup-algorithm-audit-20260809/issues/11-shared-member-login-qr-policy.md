# 11 — 两个成员登录入口共享一个不可信 QR policy

Status: ready-for-agent

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- Family 在 `FamilyScreen.kt:268-283` 手写 trim/decode/expiry/copy；Onboarding 使用自己的
  `qr/OnboardingMemberLoginQrUi.kt:20-31` parser。相同凭据可能在过期等号边界或错误分类上漂移。
- 两入口又复制 CameraX/permission/ZXing options（`FamilyScreen.kt:284-310`、
  `OnboardingScreen.kt:182-215`）。这是认证输入与 scanner Adapter policy 的重复 owner。

## Interface boundary

在两个 feature 都可依赖的低层模块建立 typed `MemberLoginQrScanPolicy`，输入 raw + clock，输出
`Empty / Rejected(reason) / Ready(payload)`。共享 camera scanner Adapter 只集中硬件、权限与 decoder
options；各 feature 保留本地导航/文案，不得新增 feature→feature 依赖或 1:1 wrapper。

## Acceptance

- [ ] 同一 raw/time 在 Family 与 Onboarding 得到完全相同 typed outcome
- [ ] blank、malformed、wrong kind/version、expiry equality、expired、valid 都有表驱动合同
- [ ] 无相机、权限拒绝/永久拒绝、取消、重复帧与 lifecycle stop 由一个 scanner policy 处理
- [ ] codec/expiry 规则只有一个 owner；feature shell 不再自行 decode 或比较时间
- [ ] 保持现有 TOFU/member-login session flow，不把 token/QR raw 写日志或 saved state

## Validation

- [ ] shared policy unit tests 与两个入口的真实 surface/Compose tests 通过
- [ ] fake camera analyzer 证明重复帧只提交一次、dispose 后不回调
- [ ] Android JVM/lint/assemble 与至少一个相机/图片 QR device smoke 通过
