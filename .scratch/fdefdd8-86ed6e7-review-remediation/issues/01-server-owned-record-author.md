# 01 — NAS 权威记录作者

**What to build:** 把护理记录作者从客户端可写的 `created_by_device_id` 迁到 NAS 控制的 `created_by_membership_id`。NAS 在首次接受新 Record 时从认证 principal 写入作者；普通 `/v1/push` 与 atomic record bundle 走同一 canonicalization seam；后续编辑和删除保留首次作者。Android 以 membership 解析当前家庭称呼，device 关联仅保留旧 NAS/旧实体回退。

**Blocked by:** 06 — membership / credential 正规化

**Status:** ready-for-agent

**Size:** L
**Review finding:** P1 #1 — uploader identity 可由 record payload 冒充
**Seam:** authenticated record ingest + membership-first uploader resolution

## Initial file surface

- `tools/lezi-sync/src/model.rs`
- `tools/lezi-sync/src/store.rs`
- `tools/lezi-sync/src/lib.rs`（含 ordinary push / atomic bundle 接线）
- `tools/lezi-sync/tests/api.rs`
- `sync/.../SyncBackend.kt`
- `sync/.../HttpSyncBackend.kt`
- `sync/.../RealSyncPort.kt`
- `sync/.../FamilyUploaderResolution.kt`
- `sync/.../SyncPort.kt` / `SyncPreferences.kt`（仅 capability/session identity 所需）
- `core/database/.../Entities.kt`、Room migration/schema export、DAO
- `core/model/.../Models.kt`
- `domain/.../CareLog.kt`
- 对应 Rust / sync / domain / migration 测试

不得夹带 care-plan、自定义项目或家庭名刷新；这些分别由其它 tracker/03 处理。

## Interface contract

- 认证 seam 输出包含 immutable `membership_id` 的 principal；record ingest 接收 principal 与 incoming entity，返回 canonical entity。
- 对**新 Record**：缺省作者由 NAS 填 principal membership；显式声明其他 membership 必须拒绝或被规范化为 principal，绝不能存成被冒充者。
- 对**已有 Record**：作者取已存 canonical value；其他成员可以按现有权限编辑内容，但不能借编辑重写首次作者。
- ordinary push 与 atomic bundle 只能调用同一 server-owned-field implementation，不得各写一套规则。

## Acceptance criteria

- [ ] NAS 新 Record 的 `created_by_membership_id` 只来自认证 principal，不来自 `created_by_device_id`、请求 `device_id` 或任意 payload claim。
- [ ] A 以自己的 token 提交 B 的 `device_id` / `membership_id`，存储和后续 pull 都不能把作者显示成 B；契约测试覆盖 ordinary push 与 atomic bundle。
- [ ] A 创建、B 编辑、B 删除/恢复同一 Record 后，作者仍为 A；LWW 内容语义不变。
- [ ] Record Room/model/wire 加法支持 `createdByMembershipId`；新版本数据库 migration 保留全部旧记录，旧字段不被破坏。
- [ ] 已加入且 NAS 提供 membership identity 时，本机新建 Record 立即带当前 session membership，NAS 仍重新校验而非信任它。
- [ ] uploader resolver 优先 `createdByMembershipId → FamilyMember.membershipId`；self 以 membership 判断；无法使用新字段时才回退 legacy device key。
- [ ] UI 继续只显示非本人当前家庭称呼；不显示 membership/device 原始值，不写称呼快照。
- [ ] 新 record key 对旧 NAS 使用明确 capability gate；不得向会因 unknown field 返回 422 的旧服务盲发。
- [ ] members 的 `device_id` 若为旧客户端暂留，必须标注 legacy-only；新解析路径不再依赖它承担 authority。
- [ ] current dirty tree 中仅“token row 有 membership_id”的实现不得作为本票完成证据。

## Validation

- `cargo test --manifest-path tools/lezi-sync/Cargo.toml`
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest --no-daemon`
- Room migration instrumentation：旧 schema → 新 schema，记录数/内容不丢；无设备时明确 blocker
- `git diff --check`

## Documentation Gate

- 同票更新 `docs/prd/data-model.md`、`docs/prd/sync-home-lan.md` 的 Record wire、capability、作者权限与 legacy fallback。
- `docs/adr/0002` 的“按 membership 解析”保持；不得恢复 device-authority wording。

## Out of scope

- 历史 NAS payload 的批量作者映射与本地等版本回填（02）。
- 显示最后编辑者、编辑历史或称呼快照。
- care-plan/custom-item ACL 扩展。

## Comments

- Review evidence：`validate_record` 只检查 `created_by_device_id` 长度，而 `/v1/push` 只绑定 request-level device；两者不是同一个信任面。
