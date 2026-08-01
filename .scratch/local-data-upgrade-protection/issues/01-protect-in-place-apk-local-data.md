# 01 · Protect in-place APK local data

Status: complete

## Acceptance

- [x] 契约账本、连续迁移规划、按域快照、日志恢复与失败保留有公开缝测试。
- [x] 0.3.0 / Room v24 数据原地替换后保留；基线前 schema 稳定阻断且文件未改变。
- [x] Application、主界面、提醒、开机与 Widget 不在门禁前构造业务持久化。
- [x] APK identity 与 `package-nas` 均拒绝不覆盖当前本地数据契约的目标 APK。
- [x] PRD、CONTEXT、ADR 与发布说明一致。
- [x] Android/Rust/签名 Release/安装启动/页面 smoke 与 `dist/` 制品完成。

## Comments

- 2026-08-01：用户确认历史数据已放弃；永久兼容承诺从 0.3.0 / contract v1 开始。
- 2026-08-01：API 35 设备测试 4/4 通过，含 WAL 中 schema 的只读识别与字节不变回归。
- 2026-08-01：真实签名 `0.3.0/code 6 -> 0.3.1/code 8` 原地安装通过；
  哨兵行保留、Room v24、`quick_check=ok`、contract v1 标记、正常欢迎页与无 FATAL 日志均已确认。
- 2026-08-01：`./gradlew test lintDebug :app:assembleDebug :app:assembleRelease`、签名校验、
  Rust fmt + 117 unit + 118 API + 1 TLS + Clippy、`package-nas` fail-closed smoke 通过。
- 2026-08-01：未执行 NAS CD；按仓库维护窗规则，线上容器替换仍需用户单独确认。
