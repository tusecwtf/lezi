# 03 — 打通所有历史版本的无损 APK 升级

**What to build:** 任一既有正式版本都能在不放宽同步协议或安全校验的前提下取得并原地安装最新同签名 APK；升级路径与同步最低支持版本解耦，并为所有已发布持久化边界提供可重复的无损升级验证。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 建立全部既有正式 versionCode、Room schema 和本地数据契约边界的受控枚举，后续更新策略、路由测试和升级夹具共用该清单
- [ ] `min_supported_version_code` 保持 16 且只控制同步 API；每个更低正式版本仍能获得同一个最新 APK，低于 16 的客户端可在升级前继续被 `client_update_required` 拒绝同步
- [ ] 已有认证 `/v1/app-update` metadata/APK 流在适用时保持可达；不能使用该流的历史客户端可通过 LAN HTTP `8767` 下载恢复包，且该端口不扩展到家庭数据、session API 或公网
- [ ] 更新资格不削弱 `com.lezi.babylog` 包名、递增 versionCode、APK SHA-256、签名 lineage 或安装身份校验，也不允许旧客户端绕过 TLS/SPKI 或使用不受支持的同步 wire
- [ ] 审计从每个已发布持久化边界到当前 Room schema 的完整前向迁移链；只补齐缺失覆盖，不使用 destructive migration、清数据、重新加入家庭或重置信任
- [ ] 升级夹具覆盖代表性记录、照片、布局偏好、计时器/未结束睡眠、家庭 membership/session、设备凭证、endpoint 与 TLS SPKI 信任，并能验证安装后由新客户端正常完成 TLS 校验和同步
- [ ] 自动化测试覆盖完整版本枚举的更新资格、认证路由、LAN 恢复下载、同步门禁独立性以及所有不同 Room/本地契约边界的迁移与初始化
