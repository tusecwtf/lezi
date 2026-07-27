# 02 — pre-join Record canonical 作者回执

**What to build:** 在 01 已建立 NAS 权威作者后，让建家前本机创建、加入后首次上传的 Record 通过 current ordinary push/atomic commit 回执收敛到 canonical membership 作者。Android 即使本地与回执 `updatedAt` 相等，也只合并 server-owned 作者 metadata，不制造业务编辑或重复上传。

**Blocked by:** 01 — NAS 权威记录作者

**Status:** in-progress（自动化完成，双设备 smoke pending）

**Size:** M–L
**Review finding:** P1 #2 — pre-join 本机记录上传后仍显示「家人」
**Seam:** canonical author acknowledgement + metadata-only replica merge

## Initial file surface

- `tools/lezi-sync/src/store.rs`
- `tools/lezi-sync/src/lib.rs`、push/atomic commit response model
- `tools/lezi-sync/tests/api.rs`
- `sync/.../SyncBackend.kt`、`HttpSyncBackend.kt`、`FakeSyncBackend.kt`
- `sync/.../RealSyncPort.kt`
- `core/database/.../Daos.kt`
- sync/Room regression tests

不得修改日图、账户 IA、family-name UI 或护理事实的业务时间。

## Current-only policy

- 新上传的建家前 Record 由 01 的 principal stamping 决定作者，不依赖客户端猜测。
- ordinary push 与 atomic commit 必须返回同形 canonical author ack；Android 只合并 membership metadata。
- current response 缺少必要 ack 或包含无效 membership 时 fail closed/可重试，不回退 device resolver。

## Acceptance criteria

- [x] ordinary push ack 与 atomic commit ack 能返回每个 Record 的 canonical author，且 ordinary/atomic 语义一致。
- [x] 本机建家前 Record 上传成功后，用 ack 回填 `createdByMembershipId`；无需等待下一次前台 pull，时间轴不再把自己的记录显示为「家人」。
- [x] `applyRecord` 在 `existing.updatedAt == remote.updatedAt` 时仍可合并 canonical author，但不覆盖 note/payload/time/photos/deleted state。
- [x] metadata-only merge 不改变 `updatedAt`、不设 `syncDirty`、不新增 Outbox、不回环 push。
- [x] remote `updatedAt` 更旧时不能用旧 metadata 回退已知 canonical author；冲突规则有测试。
- [x] ~~唯一 legacy device 映射迁移与歧义 unknown 处理~~ — **superseded 历史 receipt**
- [x] ~~旧 cursor 可拉迁移实体且 migration 重启幂等~~ — **superseded 历史 receipt**
- [x] ~~旧 NAS 无 canonical ack 时软降级并保留 legacy device resolver~~ — **superseded 历史 receipt**
- [x] current 测试覆盖 pre-join local、equal timestamp、remote newer、ordinary push 与 atomic bundle。
- [ ] 源码、current wire/schema 与 APK 负向检查不存在 device-author migration/resolver、旧 cursor rev 修补和 old-NAS ack fallback

## Validation

- `cargo test --manifest-path tools/lezi-sync/Cargo.toml`
- `./gradlew :sync:testDebugUnitTest --no-daemon`
- fresh/in-memory DB 测试覆盖 metadata-only update，且不要求旧 schema migration
- 双设备 smoke：A 的建家前记录上传后 A 不标作者，B 显示 A 当前称呼
- `git diff --check`

## Documentation Gate

- 更新 `docs/prd/data-model.md` 的 current canonical author 与 metadata-only merge。
- 更新 `docs/prd/sync-home-lan.md` 的 push/atomic canonical ack；不得把 pull 必然发生写成前提。

## Out of scope

- 猜测无法证明的历史作者。
- 为回填作者提高业务版本或修改护理内容。
- 展示作者历史称呼快照。

## Comments

- 2026-07-27 fresh-only 覆盖：下述历史作者 migration/旧 cursor/legacy retry 证据均已 superseded；ordinary/atomic canonical ack 与 pre-join metadata-only merge 仍是 current acceptance。

- 原实现 `applyRecord` 在 equal `updatedAt` 时直接跳过；仅在 capture wire payload 用 session device fallback 不会修复本地 Room 行。
- 2026-07-27 红灯：ordinary/atomic 响应无 `record_authors`；旧 cursor 在启动迁移后拉不到历史作者；Android equal-revision 与 commit-ack seam 均缺失。
- 2026-07-27 绿灯：NAS 仅迁移同家庭唯一 device→membership 映射并逐实体推进 rev；ordinary push、首次/幂等 atomic commit 返回同形 canonical 回执。Rust 13 unit + 68 API、clippy `-D warnings`、fmt check 通过。
- 2026-07-27 Android：完整 sync unit suite 通过；Room metadata-only DAO 在 emulator-5554 instrumentation 1/1 通过。回填不改变业务字段、`updatedAt`、dirty 或 Outbox，更旧/legacy payload 回归均通过。
- 2026-07-27 独立复审先发现 legacy committed bundle retry 读取旧 snapshot；补红测后改为优先读取迁移后的 canonical entity，复审确认无新增 Critical/Important。最终候选双设备 A/B 显示 smoke 尚未执行，因此状态保持未关闭。
