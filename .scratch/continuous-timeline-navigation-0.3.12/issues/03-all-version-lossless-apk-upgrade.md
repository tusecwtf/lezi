# 03 — 打通所有历史版本的无损 APK 升级

**What to build:** 任一既有正式版本都能在不放宽同步协议或安全校验的前提下取得并原地安装最新同签名 APK；升级路径与同步最低支持版本解耦，并为所有已发布持久化边界提供可重复的无损升级验证。

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] 建立全部既有正式 versionCode、Room schema 和本地数据契约边界的受控枚举，后续更新策略、路由测试和升级夹具共用该清单
- [x] `min_supported_version_code` 保持 16 且只控制同步 API；每个更低正式版本仍能获得同一个最新 APK，低于 16 的客户端可在升级前继续被 `client_update_required` 拒绝同步
- [x] 已有认证 `/v1/app-update` metadata/APK 流在适用时保持可达；不能使用该流的历史客户端可通过 LAN HTTP `8767` 下载恢复包，且该端口不扩展到家庭数据、session API 或公网
- [x] 更新资格不削弱 `com.lezi.babylog` 包名、递增 versionCode、APK SHA-256、签名 lineage 或安装身份校验，也不允许旧客户端绕过 TLS/SPKI 或使用不受支持的同步 wire
- [x] 审计从每个已发布持久化边界到当前 Room schema 的完整前向迁移链；只补齐缺失覆盖，不使用 destructive migration、清数据、重新加入家庭或重置信任
- [x] 升级夹具覆盖代表性记录、照片、布局偏好、计时器/未结束睡眠、家庭 membership/session、设备凭证、endpoint 与 TLS SPKI 信任，并能验证安装后由新客户端正常完成 TLS 校验和同步
- [x] 自动化测试覆盖完整版本枚举的更新资格、认证路由、LAN 恢复下载、同步门禁独立性以及所有不同 Room/本地契约边界的迁移与初始化

## Evidence

- `config/android-release-compatibility.json` separately enumerates immutable upgrade sources
  versionCode 6–18 and the explicit 0.3.12 / versionCode 19 upgrade target. Gradle validates
  contiguous identities and all Room/local-contract boundaries before builds without requiring
  post-build APK metadata to exist first.
- `AndroidReleaseCompatibilityCatalogTest` and lezi-sync's
  `every_released_android_version_keeps_apk_recovery_independent_of_sync_floor` cover every source,
  same-target APK identity, authenticated metadata, anonymous LAN recovery and the floor-16 sync
  split. Existing authenticated APK and LAN surface-isolation tests remain green.
- `LocalDataContractMigrationDeviceTest` passed 4/4 on `lezi_api35` API 35, including the v1→v2→v3
  fixture with records, open sleep, media bytes, layout, timer, family session, refresh credential,
  endpoint/SPKI trust and opaque credential storage.
- `./gradlew test lintDebug :app:assembleDebug` passed on 2026-08-08. Under `tools/lezi-sync`,
  `cargo fmt --all -- --check`, `cargo test --locked` (167 unit + 159 API + 2 TLS) and
  `cargo clippy --all-targets --all-features -- -D warnings` passed.
- Final signed APK build, historical `adb install -r` acceptance, linux/amd64 packaging, NAS CD and
  live TLS/sync smoke remain explicitly owned by tickets 04–06.
