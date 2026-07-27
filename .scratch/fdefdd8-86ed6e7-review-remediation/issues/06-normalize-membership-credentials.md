# 06 — membership / credential 正规化

**What to build:** 在 fresh NAS store 中把持久家庭 membership 与 Bearer credential 分开。membership 持有 immutable ID、family、role、可选设备标签、display name；credential 持有 token hash 并指向 membership。自改称呼更新 membership，因此其全部 current credentials 与所有成员视图立即一致。

**Blocked by:** None — completed

**Status:** completed

**Size:** L
**Review finding:** P2 #6 — duplicate token coalescer 可能继续投影旧称呼
**Seam:** credential authentication → canonical membership principal

## Initial file surface

- `tools/lezi-sync/src/store.rs`
- `tools/lezi-sync/src/members.rs`
- `tools/lezi-sync/src/lib.rs` / authentication, leave
- `tools/lezi-sync/tests/api.rs` 与 store tests
- `tools/lezi-sync/README.md`（仅 schema/升级说明）
- `docs/prd/data-model.md`
- `docs/prd/sync-home-lan.md`（仅身份/吊销语义）

不得修改 Android sync interface、Record payload、family name 或 care-plan ACL；01 后续消费 principal。

## Interface contract

- `authenticate(token) -> Principal{membership_id, family_id, role, ...}`；调用方不感知 token row。
- `updateMyDisplayName(principal.membership_id, name)` 更新 canonical membership 一次。
- members projection 每个 membership 一行；`is_self` 按 membership equality，不按 token hash equality。
- leave 吊销当前 membership 的全部 credentials；credential rotation 单独吊销凭证但不改 membership identity（若尚无 rotation route，只锁 store invariant/test helper）。

## Fresh deployment policy

- 空 data root 直接创建 current membership/credential schema；不加载旧表、不归并 device 行、不生成 alias。
- membership ID 由 NAS 创建并持久化；credential rotation 不改变 membership identity。
- 新 join 总是由 NAS 创建明确 membership，不得因来宾自报与现有相同 `device_id` 就自动取得对方身份。

## Acceptance criteria

- [x] schema 明确分离 membership 与 credential；`token_hash` 不再是产品 membership 主键。
- [x] ~~两个历史 active token 迁移归并到同一 membership~~ — **superseded 历史 receipt**
- [x] current credential rotation/多 credential 均指向同一 membership；rename 后所有 current credential 与成员视图一致。
- [x] ~~旧 owner/member device 行迁移时不跨 role 合并~~ — **superseded 历史 receipt**
- [x] 新攻击者在 join body 声明他人 `device_id`，不会被合并到他人 membership、不能改他人称呼。
- [x] leave 撤销 canonical membership 的全部 credentials；旧重复 token 不能继续访问。
- [x] ~~migration 事务回滚、重复启动与旧 token 数据保留~~ — **superseded 历史 receipt**
- [x] members response 不暴露 token/token hash；可选 device label 不承担 identity 或 author authority。
- [ ] 空 data root 只创建 current schema，源码/镜像无旧表 migration、device coalescer 与 membership alias workaround
- [x] 替换当前 BTreeMap coalescer/“first non-null name”真源，不在其上再叠排序补丁。

## Validation

- `cargo test --manifest-path tools/lezi-sync/Cargo.toml`
- 空 data root → current schema create/restart/persistence 测试
- API tests：credential rotation rename、other-member view、same-device attacker、leave revocation
- `git diff --check`

## Documentation Gate

- 更新 `docs/prd/data-model.md` 当前“`token_hash` 为 membership 主键”和 runtime device coalescing 的陈旧描述。
- 对齐 `CONTEXT.md` 与 `docs/adr/0007-separate-family-membership-from-credentials.md`；若实现偏离 ADR，先更新决策而不是静默偏离。

## Out of scope

- 跨设备合并同一照护者、账号体系或管理员转让。
- Record 作者落地（01）与 custom/care-plan ACL。

## Comments

- 2026-07-27 fresh-only 覆盖：以下旧库迁移/归并/alias 结果只保留为 superseded 历史 receipt；current membership/credential 分离、rename 与 leave 语义仍有效。

- 单纯让 self row 在 BTreeMap 中赢只能修调用者视图；其他成员仍可能读到重复行中的旧 non-null name，因此不是闭环。
- 2026-07-27 红灯：`legacy_duplicate_credentials_migrate_to_one_membership_principal` 首次运行时，两个历史 token 仍投影为不同 `membership_id`。
- 旧库升级在单一 immediate transaction 中把 active 历史行按 family + role + device 归并；token hash 字典序稳定选择 canonical ID/有效称呼，其它既有 ID 写入 alias，缺失 ID 只 mint 一次。故障注入测试证明 schema 重命名、建表与搬迁会整体回滚。
- 认证只返回 canonical membership principal；members 每 membership 一行并按 membership equality 计算 self；rename 对全部 credentials 立即生效；leave 原子撤销 membership 的全部 credentials；运行时相同 device 的新 join 保持独立。
- 验证：`cargo test --manifest-path tools/lezi-sync/Cargo.toml --locked`（12 unit + 53 API + doc tests，全部通过）；`cargo clippy --manifest-path tools/lezi-sync/Cargo.toml --locked --all-targets --all-features -- -D warnings`、`cargo fmt --manifest-path tools/lezi-sync/Cargo.toml --all -- --check`、`git diff --check` 均通过。
- Documentation Gate：已更新 `tools/lezi-sync/README.md`、`docs/prd/data-model.md` 与 `docs/prd/sync-home-lan.md`；`CONTEXT.md` 和 ADR 0007 已与实现一致，无需修改。
- 独立复核未发现 Critical；follow-up 补上最老发布 schema（无 `membership_id` 列）的迁移/重启测试，验证 3 个旧 token、family/name/role/display name、稳定新 ID、2→3 凭证归并计数与 SQLite FK。同步修正文档中“不改变 schema”和“leave 只吊销本 token”的两处残留旧描述。follow-up 后全量为 13 unit + 53 API，全部通过。
