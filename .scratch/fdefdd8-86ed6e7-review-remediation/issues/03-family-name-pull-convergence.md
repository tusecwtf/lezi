# 03 — 家庭名随 pull 跨设备收敛

**What to build:** 让共享家庭名成为每次前台/下拉 pull 都能刷新到的 NAS 权威 session metadata。pull response envelope 加法返回 presence-aware `family_name`；Android 区分旧 NAS 省略字段与新 NAS 明确 null，并与 cursor/generation 一起更新本地 session cache。owner 改名后，已加入成员无需重新加入即可在下一次允许的 pull 收敛。

**Blocked by:** 02；07（均为交付排序依赖，避免同时修改 sync response/session surface）

**Status:** ready-for-agent

**Size:** M
**Review finding:** P2 #3 — family name 只更新改名发起设备
**Seam:** existing pull envelope + session metadata patch

## Initial file surface

- `tools/lezi-sync/src/lib.rs` / pull response
- `tools/lezi-sync/src/store.rs`（仅读取权威 family name 所需）
- `tools/lezi-sync/tests/api.rs`
- `sync/.../SyncBackend.kt`
- `sync/.../HttpSyncBackend.kt`
- `sync/.../FakeSyncBackend.kt`
- `sync/.../RealSyncPort.kt`
- `sync/.../SyncPreferences.kt`
- 对应 sync/Rust 测试
- 原 `.scratch/family-identity-account-overview/issues/02-shared-family-name.md` 只在完成后回写状态/证据

不得新建 `GET /family-name` 加 Android pass-through port；复用每次前台已有的 pull interface。

## Interface contract

- 新 NAS 的每个 pull response（包括 0 entity）携带 `family_name` 字段，值可为 string 或 null。
- Android wire model 必须表达三态：field omitted、field present null、field present value。
- omitted 保留旧 cache；present null 清空 cache并触发「我的家庭」/宝宝名 fallback；present value 规范化后覆盖 cache。

## Acceptance criteria

- [ ] owner A rename 后，member B 在下一次 Foreground/PullToRefresh 成功 pull 后看到新家庭名，无需重加、清数据或打开成员列表。
- [ ] pull cursor 已在最新且返回 0 entity 时仍刷新家庭名。
- [ ] owner 将家庭名清空时，B 收到 explicit null 并使用产品 fallback；不能因 Kotlin nullable 混淆而保留旧名。
- [ ] 旧 NAS 省略字段时保留本地 cache，不误清空、不崩溃。
- [ ] family-name cache 与 pull cursor/generation 的写入顺序不会把新名字与旧 session 覆盖；并发 rename/local session save 有测试。
- [ ] 多页 pull 任一页返回的 family metadata 一致；服务端测试锁定，客户端不以最后一页偶然字段决定语义。
- [ ] Fake backend 能模拟 omitted/null/value，用于 RealSyncPort 双客户端测试。
- [ ] 原 family-identity ticket 02 的“全员一致”acceptance 完成并回写 `partial → done`，附测试/双设备证据。

## Validation

- `cargo test --manifest-path tools/lezi-sync/Cargo.toml`
- `./gradlew :sync:testDebugUnitTest :feature:family:testDebugUnitTest --no-daemon`
- 双客户端自动化：A rename value/null，B zero-entity pull 后收敛
- `./gradlew test :app:assembleDebug --no-daemon`
- `git diff --check`

## Documentation Gate

- 更新 `docs/prd/sync-home-lan.md` pull envelope、旧 NAS 兼容与刷新时机。
- 更新 `docs/prd/data-model.md` 的 `SyncSession.familyName` 权威/缓存语义。

## Out of scope

- 后台轮询、推送通知或离家网刷新。
- 家庭名冲突编辑、成员改名权限或账户 IA 重做。

## Comments

- 当前 create/join/owner rename 只写发起设备 DataStore；members list 也不保证在前台同步执行，因此不能承担收敛。
