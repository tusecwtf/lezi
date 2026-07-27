# 01 — NAS 权威记录作者

**What to build:** 护理记录作者只使用 NAS 控制的 `created_by_membership_id`。NAS 在首次接受新 Record 时从认证 principal 写入作者；普通 `/v1/push` 与 atomic record bundle 走同一 canonicalization seam；后续编辑和删除保留首次作者。Android 只以 membership 解析当前家庭称呼，不保留 device 作者回退。

**Blocked by:** 06 — membership / credential 正规化

**Status:** current membership authority completed；fresh-only negative-surface revalidation pending

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

- [x] NAS 新 Record 的 `created_by_membership_id` 只来自认证 principal，不来自 `created_by_device_id`、请求 `device_id` 或任意 payload claim。
- [x] A 以自己的 token 提交 B 的 `device_id` / `membership_id`，存储和后续 pull 都不能把作者显示成 B；契约测试覆盖 ordinary push 与 atomic bundle。
- [x] A 创建、B 编辑、B 删除/恢复同一 Record 后，作者仍为 A；LWW 内容语义不变。
- [x] ~~Record Room/model/wire 加法支持 `createdByMembershipId` 且 migration 保留旧字段~~ — **superseded 历史 receipt**；fresh schema 只保留 current membership 作者模型
- [x] 已加入且 NAS 提供 membership identity 时，本机新建 Record 立即带当前 session membership，NAS 仍重新校验而非信任它。
- [x] uploader resolver 通过 `createdByMembershipId → FamilyMember.membershipId` 解析，self 以 membership 判断。
- [x] UI 继续只显示非本人当前家庭称呼；不显示 membership/device 原始值，不写称呼快照。
- [x] ~~新 record key 对旧 NAS 使用 capability gate~~ — **superseded 历史 receipt**
- [x] ~~members 的 `device_id` 作为 legacy projection 暂留~~ — **superseded 历史 receipt**；current author path 不得消费 device key
- [x] current dirty tree 中仅“token row 有 membership_id”的实现不得作为本票完成证据。
- [ ] fresh current Room/wire/APK 负向检查不存在 `created_by_device_id`/`createdByUserId` 作者字段、device resolver fallback 和旧 NAS capability 分支

## Validation

- `cargo test --manifest-path tools/lezi-sync/Cargo.toml`
- `./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest --no-daemon`
- fresh Room instrumentation：空库创建 current schema，且导出/运行时 schema 不含 legacy author/device 列
- `git diff --check`

## Documentation Gate

- 同票更新 `docs/prd/data-model.md`、`docs/prd/sync-home-lan.md` 的 current Record wire 与作者权限，并删除 capability/legacy fallback 描述。
- `docs/adr/0002` 的“按 membership 解析”保持；不得恢复 device-authority wording。

## Out of scope

- pre-join 本机 Record 的 canonical ack 与等版本 metadata 回填（02）。
- 显示最后编辑者、编辑历史或称呼快照。
- care-plan/custom-item ACL 扩展。

## Comments

- 2026-07-27 fresh-only 覆盖：以下 migration、legacy device 与 capability 结果仅为历史 receipt，不是当前 Release 证据；current ordinary/atomic principal canonicalization 语义仍有效。

- Review evidence：`validate_record` 只检查 `created_by_device_id` 长度，而 `/v1/push` 只绑定 request-level device；两者不是同一个信任面。
- 2026-07-27：Room 17→18 采用加法列且 migration instrumentation 保留旧记录；schema 17 未漂移，schema 18 SHA-256 为 `fb0c1b86968b578c5c4ae19799f9a438101664172626b6e24f6aadc1024197c3`。
- 2026-07-27：NAS ordinary push 与 atomic bundle 共用 principal canonicalization；staging、commit、media PUT 均绑定 membership，legacy 歧义失败关闭；13 unit + 66 API tests、clippy `-D warnings`、fmt check 通过。
- 2026-07-27：Android database/sync/domain/log targeted gate 通过；两台 API 35 模拟器的 migration/connected tests 各 16/16。旧会话 self membership hydration 与 capability 降级竞态均有回归测试。
- 2026-07-27：两轮独立 Android/Rust 复审的 Critical/Important findings 均为 none；pre-join 同版本作者回填仍由 Ticket 02 承接。
