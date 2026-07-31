# 02 — 可选更新：下载、校验、系统安装、无残留

**What to build:** 用户在可选更新确认层选择立即更新后，从已信任家庭服务器鉴权下载 APK，校验摘要，经系统 PackageInstaller 完成安装确认；全程不在公共目录留包，成功/失败/取消后应用私有目录无 APK 残留。发版打包缺 release APK 或元数据不合法则失败，成功包内带可被服务端出的 APK。

**Blocked by:** 01 — 已加入设备能检查是否有新版本

**Status:** ready-for-agent

## Acceptance criteria

- [ ] NAS/部署打包 fail-closed：约定 release APK + 元数据齐全且 sha256 一致才产出包；缺 APK 则非 0 退出；runbook 说明输入约定
- [ ] 有效会话可鉴权下载 APK 字节，与元数据 sha256 一致
- [ ] 确认「立即更新」后：下载 → 校验 → PackageInstaller 会话；处理用户确认与未知应用来源授权检查
- [ ] 校验失败不进入安装，并清理暂存
- [ ] 成功、失败、取消后应用私有 cache/files 无残留 APK；不写公共 Download
- [ ] 受保护同步相关请求携带本机 versionCode 请求头（为 03 门槛铺路，本票不强制抬 minSupported）
- [ ] 端口级测试覆盖校验失败与清理；打包缺 APK 失败可脚本验证

## Comments

-
