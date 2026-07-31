# 17 — 升级并交付 0.3.1

**What to build:** 在跨设备发布候选验收完成后，将 Android、Rust 服务端和产品发布信息统一升级为 0.3.1，重新构建并交付该精确版本的最终产物。

**Blocked by:** 16 — 跨设备发布候选验收

**Status:** blocked

**Do not start.** Ticket 16 is still `partial` (dual Android clients on the current
Release APK, camera member-QR, local cleanup, and Android SPKI hard-block remain open).
Agents must not select this ticket as frontier while Status is `blocked` or while 16 is
incomplete—even if historical index text once said `ready-for-agent`.

- [ ] Android `versionName` 与 Rust crate/package 版本统一为 `0.3.1`，Android `versionCode` 单调递增，用户可见版本文档保持一致。
- [ ] 基于最终版本 HEAD 重新运行 Android 全量 JVM 测试、lint、Release APK 构建、签名检查、哈希、安装、启动和关键页面 smoke。
- [ ] 重新运行 Rust fmt/test/clippy，构建 `linux/amd64` 的 `lezi-sync:0.3.1` 镜像，并生成版本一致的 NAS 发布包。
- [ ] 在最终 0.3.1 服务端上复核 `/health`、`/ready` 和一条关键跨设备同步路径，不能沿用升级版本号之前的发布候选结果代替。
- [ ] 将最终 APK、镜像导出物和 NAS 包放入约定的 gitignored 发布目录，记录文件所有权、大小与 SHA-256；不得强制提交 APK、镜像 tar、真实家庭数据或秘密。
- [ ] 核对版本号、镜像 tag、包清单和交付说明完全一致，并报告精确 HEAD、产物路径、哈希、设备验证和任何明确未运行项。
- [ ] 只有最终 0.3.1 产物及上述证据齐全后，才将本规范和 tracker 视为完成。
