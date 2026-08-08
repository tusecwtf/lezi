# 04 — 构建并封装 0.3.12 发布候选

**What to build:** 将已完成的时间轴和全版本升级能力整理为一个可验证但尚未部署的 0.3.12 发布候选：Android、NAS 服务身份、签名 APK、更新 metadata、linux/amd64 镜像和受保护 NAS 包彼此一致且通过全部本地门禁。

**Blocked by:** 01 — 完成连续时间轴状态机与直接拖动；02 — 接入全局日期、筛选和完整页面体验；03 — 打通所有历史版本的无损 APK 升级。

**Status:** ready-for-agent

- [ ] 从 live HEAD 重新确认版本身份；Android 使用 versionName `0.3.12` 和尚未占用的单调 versionCode（预期 19），NAS package、镜像、health 和 lockfile 身份统一为 0.3.12
- [ ] `min_supported_version_code` 仍为 16，0.3.11 继续是可选升级且保持同步兼容；本版本不引入 Room/local-data/wire/server schema 或家庭权限变化
- [ ] 运行受影响模块测试、完整 Android JVM 测试、lint、debug 构建及可用设备门禁，并记录任何无法运行的 connected gate，而不是以 targeted/debug 结果替代发布证据
- [ ] 构建最终签名 release APK，验证包名、versionName/versionCode、受控 signer digest 和 SHA-256；用该确切 APK 写入简明 0.3.12 release notes 与更新 metadata，再重新通过 metadata/package 校验
- [ ] Rust fmt、locked test 和 Clippy 全部通过；构建并检查全新的 `linux/amd64 lezi-sync:0.3.12` 镜像，不复用未经证明的旧 tar
- [ ] 生成闭合清单和校验和的 NAS 发布包，证明镜像、APK、metadata、部署 helpers、签名 pin 和本地数据契约一致；生成物、签名材料、secret、age identity/backup 和家庭数据不进入 git
- [ ] 本票只产出本地发布候选和验证收据，不运行 NAS `push-and-deploy` 或替换家庭服务容器
