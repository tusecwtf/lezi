# 04 — 构建并封装 0.3.12 发布候选

**What to build:** 将已完成的时间轴和全版本升级能力整理为一个可验证但尚未部署的 0.3.12 发布候选：Android、NAS 服务身份、签名 APK、更新 metadata、linux/amd64 镜像和受保护 NAS 包彼此一致且通过全部本地门禁。

**Blocked by:** 01 — 完成连续时间轴状态机与直接拖动；02 — 接入全局日期、筛选和完整页面体验；03 — 打通所有历史版本的无损 APK 升级。

**Status:** complete

- [x] 从 live HEAD 重新确认版本身份；Android 使用 versionName `0.3.12` 和尚未占用的单调 versionCode（预期 19），NAS package、镜像、health 和 lockfile 身份统一为 0.3.12
- [x] `min_supported_version_code` 仍为 16，0.3.11 继续是可选升级且保持同步兼容；本版本不引入 Room/local-data/wire/server schema 或家庭权限变化
- [x] 运行受影响模块测试、完整 Android JVM 测试、lint、debug 构建及可用设备门禁，并记录任何无法运行的 connected gate，而不是以 targeted/debug 结果替代发布证据
- [x] 构建最终签名 release APK，验证包名、versionName/versionCode、受控 signer digest 和 SHA-256；用该确切 APK 写入简明 0.3.12 release notes 与更新 metadata，再重新通过 metadata/package 校验
- [x] Rust fmt、locked test 和 Clippy 全部通过；构建并检查全新的 `linux/amd64 lezi-sync:0.3.12` 镜像，不复用未经证明的旧 tar
- [x] 生成闭合清单和校验和的 NAS 发布包，证明镜像、APK、metadata、部署 helpers、签名 pin 和本地数据契约一致；生成物、签名材料、secret、age identity/backup 和家庭数据不进入 git
- [x] 本票只产出本地发布候选和验证收据，不运行 NAS `push-and-deploy` 或替换家庭服务容器

## Evidence

- Built from live HEAD `e50776c9` with Android `0.3.12` / versionCode `19`,
  `lezi-sync` `0.3.12`, Room schema `26`, local-data contract `3` and sync floor `16`.
- Final APK: package `com.lezi.babylog`; signer certificate SHA-256
  `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`; APK SHA-256
  `8fda237a2970e747ad3598f22d8586cb57fba98a9c5cf1b2d33efb06b15cf4ee`.
  Gradle signature verification, metadata compatibility and package app-update checks passed.
- `./gradlew test lintDebug :app:assembleDebug` passed. API 35 device gates passed:
  App local-data/root-date 5 tests, feature timeline 5 tests and designsystem DST 1 test.
- Rust `cargo fmt --all -- --check`, `cargo test --locked` (167 unit + 159 API + 2 TLS)
  and Clippy with warnings denied passed.
- Fresh `linux/amd64 lezi-sync:0.3.12` image built. Closed package
  `dist/lezi-sync-0.3.12-nas/` validates with config digest
  `sha256:fa63d5211aaadab103b3dbd36090162b79421f86f28729ea609b711330d6ef06`;
  image tar SHA-256 is
  `49c077e2c2e373013b15df0f783dc923d77faf9e83f6ce828a4e71ab4edecac4`.
  No NAS push/deploy was run.
