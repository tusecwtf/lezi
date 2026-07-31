# 03 — 拒绝 minSupported > versionCode

**What to build:** 运维/打包若写出 `min_supported_version_code > version_code`，**打包失败**或服务**拒绝加载**该元数据，避免用户进入「必须升级却装不了（已是最新）」的死锁；合法配置下强制升级仍可安装 `version_code` 更高的包。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## Acceptance criteria

- [ ] `package-nas`（或校验函数）在 `min_supported > version_code` 时非 0 退出；`version_code >= 1` 与服务器 normalize 一致
- [ ] lezi-sync 加载/normalize 元数据时同样拒绝不一致组合（或启动/热加载失败并有明确日志）
- [ ] 集成或单元测试覆盖：非法元数据不可作为有效更新通道启用
- [ ] DEPLOY/runbook 一句说明该不变量

## Comments

- Review: B3（correctness + security）
