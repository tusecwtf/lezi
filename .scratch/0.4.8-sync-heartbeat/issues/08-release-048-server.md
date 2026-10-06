# 08: 0.4.8 发布：NAS 配对与部署窗口

**What to build:** VPS 部署说明按用户要求暂停（2026-09-05）。先核对 0.4.8
服务端（四流，含心跳）+ 07 的 APK 配对和 NAS 回滚通道，再提出 NAS 部署窗口。
权限修复由用户执行；**未经另行确认不得替换容器。**

**Blocked by:** 06, 07。

**Status:** superseded（0.4.8 服务器随 0.5.0 重新配对发布：NAS 部署清单并入
0.5 交接件的发布项；VPS 部署线 2026-09-06 随工具链删除放弃，本文其余内容仅作历史）

- [ ] 修复后重新通过 cargo 三门、Android 回归与 release APK/服务端精确配对校验；已有旧包的通过记录不复用为新代码的发布证明
- [ ] 用户确认 NAS 部署窗口后才执行 guarded push-and-deploy（默认 `ssh -p 10000 13096920600@192.168.50.4`）
- [ ] LAN HTTPS `/health`、`/ready`、`/v1/setup-status` 全绿，版本与配对一致、setup-status 含 `sync_heartbeat_v1`，前后 TLS 证书 SHA-256 与 SPKI 相同
- [ ] 部署后一台真机 smoke：前台心跳生效、探活统一行为符合 06 结论
- [ ] NAS 同版本恢复与软件降级分别验收：0.4.8 配对包可恢复；0.4.8 写过自动对齐的本地库退回 0.4.7 后仍可 pull，保留数据且不形成交叉态

## 本地修复验证（2026-09-05；不是部署证明）

- `cargo fmt --all -- --check`、`cargo test --locked`、Clippy 全通过；Rust 529 项。
  首轮 TLS restart 测试 readiness 超时，定点及 TLS 套件连续五轮通过，随后全套重跑通过。
- `:sync:testDebugUnitTest` 954 项、`:domain:testDebugUnitTest` 498 项、
  `:core:database:testDebugUnitTest` 47 项全通过。
- API 35 隔离模拟器 `CausalRoomTransactionTest` 25 项通过，覆盖旧版 reader、
  存量 auto 标记修复、半边关系、手动覆盖与清理；Room 28 导出未变化。
- `test-nas-release-identity.sh` 完整隔离打包及重封签篡改拒绝通过；
  `test-package-nas-app-update.sh`、`test-remote-deploy-app-update-atomic.sh` 通过。
  此处 APK/签名工具是测试夹具，**不是修复后签名 release APK 的验收**。
- 未连接或变更 NAS/VPS；权限命令由用户执行，容器替换仍须单独确认。
