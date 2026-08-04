# 04 — 0.3.6 升级验收、双端联调与发布门

**What to build:** 从已发布 0.3.6/local-contract-3 的真实 dirty 形状升级，在隔离真实
lezi-sync 上证明所有冻结单元终态、pending=0、peer canonical 一致，再按签名更新与 NAS
CD 门发布。

**Blocked by:** 01, 02, 03.

**Status:** implementation-complete — live acceptance pending

- [x] device migration fixture 保留 Room 护理数据与媒体 bytes，并覆盖当前会被角色/结构过滤的 dirty
- [ ] 两客户端 + isolated real server 覆盖 confirmed/publish/adopt/local-only/discard/crash/concurrency
- [ ] quiescent complete-cycle invariant：冻结集全部终态、A pending=0、B 拉到相同 canonical state
- [ ] additive server capability 先部署；客户端要求 capability 前有可恢复 rollout 顺序
- [x] 若提高 min_supported，先验证 signed Release APK、metadata、hash、signer 与安装通道
- [x] Android test/lint/Debug+Release、API35 migration、Rust fmt/test/clippy 与协议 smoke 通过
- [x] server/wire runtime 触及后，Rust gates 通过即按 AGENTS 流程提出 NAS CD 并等待确认
- [ ] CD 后验证版本、HTTPS health/ready、image id、TLS cert/SPKI 不变及 joined-client feature smoke
