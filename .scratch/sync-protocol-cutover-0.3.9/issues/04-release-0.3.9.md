# 04 — 发布 0.3.9 APK、Docker 与 NAS CD 验收

**What to build:** 完成 0.3.9 前后端版本升级、签名 APK、linux/amd64 Docker 镜像、更新 metadata、受保护的 NAS CD 与已加入客户端联调，证明用户覆盖安装后自动进入新协议并恢复同步。

**Blocked by:** 03

**Status:** blocked — APK/image/NAS CD accepted on `ff80edbf`; no valid retained joined-client session available for the live two-client gate

- [x] Android 与服务端版本统一升级到 0.3.9，Android versionCode 单调增加；签名 Release APK 的应用 ID、版本、签名和 SHA-256 与更新 metadata 完全一致。
- [x] 先让 NAS 安装通道可下载并验证 0.3.9 APK，再提高最低支持版本并启用新协议门；0.3.8 客户端只能进入既有强制升级流程，不能继续按旧协议读写。
- [ ] 覆盖安装到保有家庭会话和历史 Room 数据的 0.3.8 客户端后，首次前台同步自动恢复；除 Android 系统安装确认外，无需清数据、退组重加、重新信任端点或人工修 NAS。（覆盖安装/Room 保留已证实；首次 0.3.9 启动时会话无效，且未留存升级前认证状态，无法证明 live 自动恢复。）
- [x] 通过 Android 单测、lint、Release 构建与签名门，Rust fmt、locked tests、Clippy，以及聚焦的隔离 0.3.8→0.3.9 协议升级 fixture。
- [x] 从当前源码重新构建并测量 linux/amd64 0.3.9 镜像和封闭 NAS 包；不得复用无法证明来自当前提交的 APK、镜像或旧 tar。
- [x] 只有获得独立的明确 CD 确认后才替换家庭 NAS 容器；CD 保留数据 bind、bootstrap secret、TLS 证书和 SPKI，并验证运行镜像、health、ready、版本及安装页 APK hash。
- [ ] 已加入管理员与第二客户端完成最小联调：管理员仍有目标 Record 时历史履行自动收敛且新成员可获得全部 payload；若 Record 已在所有设备丢失，则该履行保持延后但其它同步恢复。（现场无有效已加入 session；隔离 fixture 已通过。）
- [x] 验收报告固定记录提交、APK/镜像/package 标识、协议 generation、通过的 URL、安装覆盖结果、客户端联调结果及任何不可恢复的事实缺口。
