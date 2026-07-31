# 01 — 安装/校验失败不污染同步状态

**What to build:** 用户在可选或强制升级流程中下载失败、sha256 不匹配或 PackageInstaller 启动失败时，**只**在更新 UI 上看到失败文案，**不会**把家庭同步状态变成「同步遇到问题」或其它泛同步错误。

**Blocked by:** None — can start immediately

**Status:** done

## Acceptance criteria

- [x] `installAvailableAppUpdate`（或等价）失败路径不再调用会把 `SyncStatus` 设为 Error 的同步失败钩子（审查点：`onFailure(::updateFailureStatus)`）
- [x] 检查更新失败、握手发现失败、安装失败三者均不把已加入设备的同步态误导为 NAS/同步挂掉
- [x] 单测：至少覆盖 sha256 拒绝后 `SyncStatus` 仍为 Idle（或安装前的稳定成功态），并保留「未调安装器 / 暂存已清」断言
- [x] 相关 UI 仍通过现有更新结果/对话框展示失败原因

## Comments

- Review: B1（correctness + tests + plan 三方确认）
- Fix round 1: install gate no longer calls `requireAllowed` (status-neutral like check); Family install uses `productUiError` not `familySyncError`; download/installer/gate Idle tests + [evidence/01](../evidence/01/validation.md)
