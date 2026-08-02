# 03 — Android 0.3.4 发版与验收

Status: complete

## Acceptance criteria

- [x] Android `0.3.4` / versionCode `11`；Room v24、本地契约 v1。
- [x] lezi-sync 保持 `0.3.3` / SQLite `user_version=11`，Android 0.3.4 对它的现有 wire 联调通过。
- [x] 本机宝宝 → 申请 → 断网本地放弃 → 重复申请的 JVM 与隔离服务回归通过。
- [x] 0.3.3 pending 与护理数据原地升级保留；Room `quick_check=ok`，无 AndroidRuntime fatal。
- [x] 账户概览的 Compose/semantics 与设备页面 smoke 通过。
- [x] Android unit、lint、Debug/Release build、签名和串行 device gates 通过或记录真实设备阻断。
- [x] 最终签名 APK 产生后，`app-update.json` 的 code/name/release notes/SHA-256 与 APK 一致，
  `min_supported_version_code=6`。
- [x] 只在用户确认 NAS CD 后发布 APK/metadata；不借 Android-only 修复轮换 TLS 身份或
  修改 server 镜像版本。
