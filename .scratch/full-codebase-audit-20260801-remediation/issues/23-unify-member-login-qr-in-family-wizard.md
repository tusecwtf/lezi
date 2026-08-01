# 23 — 成员登录 QR 归一到家庭向导

**What to build:** 账户与引导扫描成员单次登录 QR 时复用一个 `FamilyWizardController`
状态机完成 verify/claim/cancel/retry，并默认显示同一套家庭同步错误文案。

**Source:** merged readability 02
**Blocked by:** None — can start immediately
**Status:** ready-for-agent
**Size:** M

## Acceptance criteria

- [ ] verify、claim、取消进行中校验与重试由 `FamilyWizardController` 经既有 gateway 统一承担。
- [ ] Account 与 Onboarding host 不再各维护一套 QR Job、恢复或错误分支。
- [ ] 两个入口默认使用 `familySyncError`；只有纯本机信任写入等非同步失败可使用 product UI error。
- [ ] 成功领取后恢复家庭向导/会话状态；取消不留下继续执行的 claim 或半可信 endpoint。
- [ ] 控制器/public host 测试覆盖成功、失败、取消、重试和配置重建后的结果交付。
- [ ] 邀请家人 QR 载荷、十分钟单次授权、TOFU/SPKI 与管理员不通过 QR 登录的合同不变。

## Validation

运行 domain、family、onboarding、sync 相关 tests、`:app:assembleDebug`、`lintDebug`；
补 Compose/device smoke 验证两个入口的错误与取消结果一致。

## Documentation Gate

若入口文案或调用流图发生稳定变化，同步现有 PRD/design；不改家庭领域术语。

## Out of scope

不在本票拆 Family 三 host（Ticket 24），不重做扫码视觉，不新增 feature→feature 依赖。
