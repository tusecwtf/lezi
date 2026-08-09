# 28 — 非破坏性迁移 server schema 11/12→13

**What to build:** 扩展显式 `offline-migrate`，将完整、验证通过的 server schema 11 或 12 copy-out 到新的 schema 13 data root，源目录始终只读可回滚。

**Blocked by:** 27

**Status:** ready-for-agent

## Contract slice

只接受 user_version 11/12 与各自冻结 shape；不在 startup、ordinary CD 或 NAS 上 build。迁移保留 family facts、versions/branches/conflicts、media refs/bytes、membership/session/token hashes、server secret 与 TLS identity；不完整 WAL/SHM/media fail closed。

## Implementation sequence

1. 冻结 v11/v12 source inventory、shape 与 schema-13 mapping。
2. dry-run 完整验证 source，并复制到新的 out data root。
3. 在 out 中迁移/重建 derived state，设置 user_version 13。
4. validate 行数、关键关系、media digest、quick/integrity check；源目录零写入。

## Acceptance

- [ ] v11 与 v12 fixtures 均无损产生可打开的 schema 13
- [ ] wrong version/shape/WAL/media/secret/TLS 状态不创建可 promote 输出
- [ ] source tree hash 迁移前后完全一致
- [ ] schema 13 Store 能读取全部事实、branch/conflict、identity 与 media

## Validation

- [ ] dry-run/migrate/validate/failure/large-fixture tests 通过
- [ ] Rust fmt/test/clippy 与 isolated source→13 smoke 通过

## Out of scope

不 copy-back 家庭 NAS，不改变 ordinary server startup。
