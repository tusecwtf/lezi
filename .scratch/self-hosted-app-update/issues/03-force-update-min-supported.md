# 03 — 强制升级：minSupported 拒同步与全屏必须升

**What to build:** 当服务器元数据中 minSupportedVersionCode 高于本机 versionCode 时，用户无法继续权威同步写/拉，并进入全屏强制升级（无「稍后」绕过主功能），但仍能完成检查、下载与安装；有效会话下更新元数据与 APK 下载始终放行，避免死锁。

**Blocked by:** 02 — 可选更新下载安装与无残留

**Status:** done

## Acceptance criteria

- [x] 请求头 versionCode 缺失或 &lt; minSupported 时，权威同步路径（pull / bundle / media 等）失败，错误语义稳定可映射（如 client_update_required）
- [x] 同上条件下更新元数据与 APK 下载仍成功
- [x] versionCode ≥ minSupported 时同步门槛不误伤
- [x] 客户端将须升级错误映射为强制态，而非含糊网络失败
- [x] 强制全屏：无稍后绕过主功能；仍可走完下载安装（复用 02 管道）
- [x] 服务端与客户端有覆盖门槛与强制态的外部行为测试

## Comments

- Server: `require_supported_client` on pull / media GET / bundle stage+get+put+commit; 403 + `code=client_update_required`; app-update routes not gated; fail-open when metadata missing.
- Client: `AppUpdateCheckResult.ForcedUpdate`, `availableForcedAppUpdate()`, `ClientUpdateRequiredException` mapped from wire; root full-screen overlay in MainActivity; settings/family non-dismissible dialogs.
- Tests: `client_update_required_*` + fail-open in `tools/lezi-sync/tests/api.rs`; RealSyncPort forced/sync gate; AppUpdateCheckCopyTest force copy.
