# 02 — 历史与建家前作者回填

**What to build:** 在 01 已建立 NAS 权威作者后，让升级前、建家前以及本机已上传但等版本的 Record 收敛到 canonical membership 作者。NAS 只迁移可证明的历史映射；push/atomic commit 回执返回 canonical author；Android 即使本地与远端 `updatedAt` 相等，也只合并 server-owned 作者 metadata，不制造业务编辑或重复上传。

**Blocked by:** 01 — NAS 权威记录作者

**Status:** in-progress（自动化完成，双设备 smoke pending）

**Size:** M–L
**Review finding:** P1 #2 — 旧记录/建家前记录在本机显示「家人」
**Seam:** canonical author acknowledgement + metadata-only replica merge

## Initial file surface

- `tools/lezi-sync/src/store.rs` / migration helpers
- `tools/lezi-sync/src/lib.rs`、push/atomic commit response model
- `tools/lezi-sync/tests/api.rs`
- `sync/.../SyncBackend.kt`、`HttpSyncBackend.kt`、`FakeSyncBackend.kt`
- `sync/.../RealSyncPort.kt`
- `core/database/.../Daos.kt`
- sync/Room regression tests

不得修改日图、账户 IA、family-name UI 或护理事实的业务时间。

## Migration policy

- 已存旧 Record 若 `created_by_device_id` 在同家庭只映射到一个 canonical membership，可补 `created_by_membership_id`。
- device 映射重复、跨 role 冲突、membership 已无法确定或字段为空时，作者保持 unknown；不得选择排序第一项伪造确定身份。
- 实际修改 NAS entity payload 时推进对应 entity/family rev，使已经越过旧 cursor 的客户端仍可拉到。
- 新上传的建家前 Record 由 01 的 principal stamping 决定作者，不依赖客户端猜测。

## Acceptance criteria

- [x] ordinary push ack 与 atomic commit ack 能返回每个 Record 的 canonical author（具体 wire 形状可设计，但 ordinary/atomic 语义一致且旧客户端可忽略）。
- [x] 本机建家前 Record 上传成功后，用 ack 回填 `createdByMembershipId`；无需等待下一次前台 pull，时间轴不再把自己的记录显示为「家人」。
- [x] `applyRecord` 在 `existing.updatedAt == remote.updatedAt` 时仍可合并 canonical author，但不覆盖 note/payload/time/photos/deleted state。
- [x] metadata-only merge 不改变 `updatedAt`、不设 `syncDirty`、不新增 Outbox、不回环 push。
- [x] remote `updatedAt` 更旧时不能用旧 metadata 回退已知 canonical author；冲突规则有测试。
- [x] 唯一 legacy device 映射可迁移；重复/未知映射保持 unknown 并用安全 UI 兜底。
- [x] NAS 迁移后的实体可由旧 cursor 客户端拉到；重启 migration 幂等，不重复推进 rev。
- [x] 旧 NAS 不返回 canonical ack/field 时客户端不清空已有 membership 作者、不崩溃，并保留 legacy device resolver。
- [x] 测试覆盖 pre-join local、equal timestamp、remote newer、ambiguous legacy device、ordinary push、atomic bundle 六类路径。

## Validation

- `cargo test --manifest-path tools/lezi-sync/Cargo.toml`
- `./gradlew :sync:testDebugUnitTest --no-daemon`
- Room migration/in-memory DB 测试覆盖 metadata-only update
- 双设备 smoke：A 的建家前记录上传后 A 不标作者，B 显示 A 当前称呼
- `git diff --check`

## Documentation Gate

- 更新 `docs/prd/data-model.md` 的历史迁移、unknown 语义与 metadata-only merge。
- 更新 `docs/prd/sync-home-lan.md` 的 push/atomic canonical ack；不得把 pull 必然发生写成前提。

## Out of scope

- 猜测无法证明的历史作者。
- 为回填作者提高业务版本或修改护理内容。
- 展示作者历史称呼快照。

## Comments

- 原实现 `applyRecord` 在 equal `updatedAt` 时直接跳过；仅在 capture wire payload 用 session device fallback 不会修复本地 Room 行。
- 2026-07-27 红灯：ordinary/atomic 响应无 `record_authors`；旧 cursor 在启动迁移后拉不到历史作者；Android equal-revision 与 commit-ack seam 均缺失。
- 2026-07-27 绿灯：NAS 仅迁移同家庭唯一 device→membership 映射并逐实体推进 rev；ordinary push、首次/幂等 atomic commit 返回同形 canonical 回执。Rust 13 unit + 68 API、clippy `-D warnings`、fmt check 通过。
- 2026-07-27 Android：完整 sync unit suite 通过；Room metadata-only DAO 在 emulator-5554 instrumentation 1/1 通过。回填不改变业务字段、`updatedAt`、dirty 或 Outbox，更旧/legacy payload 回归均通过。
- 2026-07-27 独立复审先发现 legacy committed bundle retry 读取旧 snapshot；补红测后改为优先读取迁移后的 canonical entity，复审确认无新增 Critical/Important。最终候选双设备 A/B 显示 smoke 尚未执行，因此状态保持未关闭。
