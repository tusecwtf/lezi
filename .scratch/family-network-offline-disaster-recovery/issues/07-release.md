# 07 — 0.3.3 发版与验收

**Status:** in-progress — awaiting NAS CD and LAN smoke

## Acceptance criteria

- [x] Android 0.3.3 code 10；server 0.3.3；Room 24、本地契约 v1、server schema 不变。
- [x] Android unit/lint/debug/release、串行 device gates 与 Rust fmt/test/clippy 通过或明确设备阻断。
- [x] 0.3.2→0.3.3 原地升级 quick_check=ok 且数据保留。
- [x] 隔离空服务完成灾难恢复联调；现有家庭 NAS 不执行恢复测试。
- [x] 签名 APK 与 app-update metadata/hash 一致，`dist/release-0.3.3/` 完整。
- [x] Rust gates 后仅提议 NAS CD；用户确认前不执行容器 replace。

## Local acceptance evidence

- Android：`test`、独立串行 `lintDebug`、Debug/Release 构建通过；最终固定点
  `connectedDebugAndroidTest` 在只读 API 35 AVD 上 3m09s 通过。
- 升级：真实签名 0.3.2 原地安装最终 0.3.3，Room `user_version=24`、
  `quick_check=ok`、业务哨兵保留，MainActivity 为 RESUMED 且无 AndroidRuntime fatal。
- Server：Rust fmt、138 unit、125 API、1 TLS、Clippy `-D warnings` 通过；当前
  `user_version=11` 未变。
- 隔离联调：真实本机 HTTPS 0.3.3 空服务完成 start/manifest/media/status/commit/pull，
  setup `empty → configured`、3 实体一次可见、历史作者归新 Owner、媒体 SHA-256 匹配、
  commit 重试幂等；临时服务和数据根已删除。
