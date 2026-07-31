# 06 — QR 校验可取消并贯通 dataRecovery

**What to build:** 成员登录二维码确认流程在「正在确认家庭服务器」阶段必须可取消/可离线；
`claimMemberLoginQr` 不得丢弃 coordinator 计算的 `dataRecovery`，首次同步失败时 UI 须
可重试或明确失败，不得假装已完整加入。

**Blocked by:** None — can start immediately.

**Status:** complete

**Severity:** High
**Blocks release:** yes
**Review ID:** F-06

## Must

- [x] `FamilyDialog.VerifyingMemberLoginQr`：允许 dismiss/cancel；取消 in-flight
      `verifyEndpoint`；超时映射为可恢复文案 + 可改手动加入。
- [x] 不得用 `submitting=true` + 空 `onDismiss` 作为硬锁死模态。
- [x] `SyncPort.claimMemberLoginQr`（或等价 API）暴露 `dataRecovery`（与 Owner/批准路径
      同形）；UI/VM 在 `RetryRequired` 时提示并允许触发恢复同步。
- [x] 测试：verify 取消不留下半截 dialog 状态；claim 返回 RetryRequired 不被 port 丢弃。

## Evidence paths

- `feature/family/FamilyScreen.kt` (~562–571)
- `feature/family/FamilyDialogs.kt` MemberLoginQrConfirmDialog
- `sync/.../RealSyncPort.kt` `claimMemberLoginQr`
- `sync/.../FamilySessionCoordinator.kt` claim grant outcome

## Comments

- 批准路径 `MemberLoginCheckResult.Joined` 已带 dataRecovery；QR 应对齐。
