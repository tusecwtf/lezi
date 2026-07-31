# 05 — 仅 probe Ready 后 durable remember endpoint

**What to build:** `trustCertificate` 不得在 setup probe 失败时留下 durable
`verifiedEndpoint` resume 状态。用户点「信任此证书」后，只有 probe 得到 `Ready` 才
应作为「已确认服务器、可继续登录」的 resume profile。

**Blocked by:** None — can start immediately（与 04 建议同一执行者）。

**Status:** complete

**Severity:** High
**Blocks release:** yes
**Review ID:** F-05

## Must

- [x] `RealSyncPort.trustCertificate`：仅当 `setupProbe.probe` 返回 `Ready` 时
      `rememberEndpoint`；失败路径不留下 verified resume（或显式 forget）。
- [x] 若产品需要「点信任即 pin、再 probe」两阶段，pin 候选与 resume profile 必须分离；
      账户「继续登录 / 已找到服务器」只认 Ready 后的 profile。
- [x] 证书变更 / NotLezi / Unreachable 后不得误显示可继续登录的 verified 状态。
- [x] 单测：trust 后 probe 失败 → `verifiedEndpoint` null 或非 resume；Ready → 已记住。

## Evidence paths

- `sync/.../RealSyncPort.kt` `trustCertificate`
- `domain/.../FamilyWizardController.kt` trust/probe 流程
- `SyncPreferences.rememberEndpoint` / `forgetEndpoint`

## Comments

- 与 04 同属 transport trust 合同；实现时避免重复 remember/forget 竞态。
