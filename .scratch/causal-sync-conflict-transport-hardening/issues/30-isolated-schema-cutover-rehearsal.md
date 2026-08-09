# 30 — 演练 0.4.0 schema cutover 与 rollback

**What to build:** 在开发者自有隔离实例完整演练 schema 11/12→13 的 0.4.0 CD，证明成功路径与每个开放写入前失败点都能恢复旧 image/data/TLS/app-update pair。

**Blocked by:** 29

**Status:** ready-for-agent

## Contract slice

使用 mktemp data roots、非生产端口和测试证书；不得触碰家庭 NAS。成功后比较事实/branch/conflict/media/session inventory，失败注入覆盖 backup、copy-out、migrate、validate、copy-back、start、health 与 APK hash。

## Implementation sequence

1. 分别用 schema 11/12 数据根和旧 0.3.13 image/package 启动隔离 source。
2. 执行 guarded cutover 到 0.4.0/schema13，验证 APK pair、TLS 与数据。
3. 在各阶段注入失败，执行 rollback 并用旧 image 重开原 source。
4. 记录 source/out hashes、row/media inventories、user_version、image IDs 与 health receipts。

## Acceptance

- [ ] 两种 source 都无损切到 schema13 且 0.4.0 ready
- [ ] 开放写入前任一失败均恢复原 image/data/app-update/TLS
- [ ] 迁移输出失败绝不覆盖 source，rollback 后旧服务可读全部事实
- [ ] 无生产地址、凭据、证书或家庭数据进入测试/报告

## Validation

- [ ] isolated success/failure/rollback matrix 通过
- [ ] Rust/deploy gates 通过并留下可复验 receipts

## Out of scope

不申请或执行生产维护窗口。
