# 30 — 演练 0.4.0 schema cutover 与 rollback

**What to build:** 在开发者自有隔离实例完整演练 schema 11/12→13 的 0.4.0 CD，证明成功路径与每个开放写入前失败点都能恢复旧 image/data/TLS/app-update pair。

**Blocked by:** 29

**Status:** implemented (review/final gates pass)

## Contract slice

使用 mktemp data roots、非生产端口和测试证书；不得触碰家庭 NAS。成功后比较事实/branch/conflict/media/session inventory，失败注入覆盖 backup、copy-out、migrate、validate、copy-back、start、health 与 APK hash。

## Implementation sequence

1. 分别用 schema 11 的最后兼容旧 image（0.3.12）和 schema 12 的 0.3.13 image/package 启动隔离 source。
2. 执行 guarded cutover 到 0.4.0/schema13，验证 APK pair、TLS 与数据。
3. 在各阶段注入失败，执行 rollback 并用旧 image 重开原 source。
4. 记录 source/out hashes、row/media inventories、user_version、image IDs 与 health receipts。

## Acceptance

- [x] 两种 source 都无损切到 schema13 且 0.4.0 ready
- [x] 开放写入前任一失败均恢复原 image/data/app-update/TLS
- [x] 迁移输出失败绝不覆盖 source，rollback 后旧服务可读全部事实
- [x] 无生产地址、凭据、证书或家庭数据进入测试/报告

## Validation

- [x] isolated success/failure/rollback matrix 通过
- [x] Rust/deploy gates 通过并留下可复验 receipts

## Evidence (2026-08-12, isolated developer host)

- Public seam: `deploy/test-schema-cutover-rehearsal.sh`; matrix entrypoint:
  `deploy/schema-cutover-rehearsal.sh`. Both reject production/NAS routing and require loopback plus
  a mode-700 `/tmp` or `/var/tmp` root carrying the developer-owned synthetic-fixture marker. The
  isolated adapter drives the shipped H29 `schema-cutover.sh` state machine; it replaces only its
  production SSH/NAS phase executor with local Docker operations.
- Real Docker matrix: 18/18 — schema 11 and 12 each completed one schema-13/0.4.0 success plus exact
  rollback after injected `apk_hash`, `backup`, `copy_out`, `migrate`, `validate`, `copy_back`, `start`,
  and `health` failures. Matrix SHA-256:
  `ae14d787825c90a61a4fd717a19b41795e83310cd9a64c819174490145ac03f0`.
  Closed receipt-set SHA-256: `ff12d08cf1f63699747b2fbc3bcedccc0bed42841f10201632a3b16fd754a887`.
- Source images: schema 11 uses the last compatible 0.3.12 image
  `sha256:46dea62ae2140ef06f8d5421f96dbe7a5a5f849ed9c5cdc2a40e0b3af156c9a8`; schema 12
  `sha256:94cdb3b561337bdbcbaeb539cb289b988682c2fb00eef7e4392509eccd80f262`.
  Target image: `sha256:a35fa3a8f51474aef7cb952d54509eadc8ca977185f53ed2166ea0346af1af96`
  (`linux/amd64`). The rollback APK was built from fixed 0.3.13 source `c5211559`, passed release
  signature verification, and its signer digest matched the tracked pin; APK SHA-256:
  `b91bde9de6315a0523db6baad221ffea2ab4dff7a9584ffb3ca5fe082174e05a`.
- Schema-11 inventory `families=1;facts=2;versions=0;branches=0;conflicts=0;media=1;sessions=1`
  became schema 13 with exactly two migration-base versions. Schema-12 inventory
  `families=1;facts=2;versions=2;branches=1;conflicts=1;media=1;sessions=1` was unchanged.
  Both retained exact facts, identity/session rows, media bytes/references, `server.secret`, TLS
  certificate and SPKI. Schema 12 additionally retained exact stable-head/branch/conflict causal
  associations. Rollback receipts retained exact source data, TLS, APK/metadata, semantic inventory,
  image ID, then the old image passed health/readiness and an authenticated pull returned both facts.
- Target APK SHA-256:
  `49a24dd4192e688740bb60bf4cf97de2219f21c330d159b5407857365669d000`; isolated matching metadata
  SHA-256: `b454e83f7b714c6407c20e43a090523785b1a63e62e1ffa98db8e7a70df50b5d`.
- The rehearsal caught two boundary defects and their regressions are covered: immutable SQLite opens
  prevent WAL/SHM creation on frozen sources, and the H29 staged root now contains the already-attested
  target APK/metadata before validation.

## Out of scope

不申请或执行生产维护窗口。
