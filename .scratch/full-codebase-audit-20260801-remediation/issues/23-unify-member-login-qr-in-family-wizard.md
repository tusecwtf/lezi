# 23 — 成员登录 QR 归一到家庭向导

**What to build:** 账户与引导扫描成员单次登录 QR 时复用一个 `FamilyWizardController`
状态机完成 verify/claim/cancel/retry，并默认显示同一套家庭同步错误文案。

**Source:** merged readability 02
**Blocked by:** None — can start immediately
**Status:** done
**Size:** M

## Acceptance criteria

- [x] verify、claim、取消进行中校验与重试由 `FamilyWizardController` 经既有 gateway 统一承担。
- [x] Account 与 Onboarding host 不再各维护一套 QR Job、恢复或错误分支。
- [x] 两个入口默认使用 `familySyncError`；只有纯本机信任写入等非同步失败可使用 product UI error。
- [x] 成功领取后恢复家庭向导/会话状态；取消不留下继续执行的 claim 或半可信 endpoint。
- [x] 控制器/public host 测试覆盖成功、失败、取消、重试和配置重建后的结果交付。
- [x] 邀请家人 QR 载荷、十分钟单次授权、TOFU/SPKI 与管理员不通过 QR 登录的合同不变。

## Validation

运行 domain、family、onboarding、sync 相关 tests、`:app:assembleDebug`、`lintDebug`；
补 Compose/device smoke 验证两个入口的错误与取消结果一致。

## Documentation Gate

若入口文案或调用流图发生稳定变化，同步现有 PRD/design；不改家庭领域术语。

## Out of scope

不在本票拆 Family 三 host（Ticket 24），不重做扫码视觉，不新增 feature→feature 依赖。

## Design notes (public seams)

- `FamilyWizardGateway.verifyMemberLoginEndpoint` / `claimMemberLoginQr` — narrow side-effect seam;
  production `SyncFamilyWizardGateway` maps to `SyncPort.verifyEndpoint` + scaffold + `claimMemberLoginQr`.
- `FamilyWizardController.verifyMemberLoginQr` / `claimMemberLoginQr` / `cancelMemberLoginQr` /
  `retryReclaimedDataRecovery` — single state machine for Account + Onboarding.
- States: `VerifyingMemberLoginQr`, `MemberLoginQrReady`, `MemberLoginQrVerificationFailed`,
  `ClaimingMemberLoginQr`, plus `Completed(MemberLoginQrClaimed)` / recovery via existing
  `RetryableFailure(committedOutcome)`.
- Hosts only thin-launch controller methods and project `familyWizardState`; no per-host QR Job.

## Implementation evidence

- Domain: gateway + controller own verify/claim/cancel; claim errors use `familySyncError`
  (except local trust write fixed copy and typed QR exceptions).
- FamilyViewModel / OnboardingViewModel: removed `memberLoginQrVerificationJob` and duplicate
  error branches; delegate to `FamilyWizardController`.
- UI projects controller QR states; owner **create** QR (invite) path unchanged.
- Tests: `FamilyWizardControllerTest` (success/fail/cancel/retry/consume-once), account +
  onboarding adapter outcome projections for `MemberLoginQrClaimed`.
