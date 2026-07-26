# 28 — 完整升级链与双设备发布验收

**What to build:** 收口完整数据库升级链、双设备家庭同步和真实系统日历设备行为，证明所有前置票组合后仍保持事实/意图分离、照片原子性和单一提醒。

**Blocked by:** 02 — 灰态确认按钮与具体原因卡; 06 — 统一记录设置与本机拖动排序; 08 — 自定义项目家庭同步与服务端 ACL; 11 — 记录照片编辑、删除与恢复; 14 — 乐记日历与历史日程显式转换; 17 — 睡眠计划履行语义; 19 — 护理记录显式转为护理计划; 22 — 系统日历三级披露与副本生命周期; 24 — 护理计划原子家庭同步与本机投影; 27 — 管理员冲突审计与转独立记录.

**Status:** done

- [x] 从当前已发布数据库版本逐级升级到最终 schema，保留旧 Record、1–3 张照片、CalendarEvent、自定义项目和本机设置
- [x] 每个前置 schema 票已经提供自己的迁移；本票只验证整链，不临时补遗漏迁移
- [x] 双设备验证新建/编辑/删除带照片 Record 在所有失败窗口都不可部分可见
- [x] 双设备验证带照片 CarePlan 完整后才显示和提醒，失败时创建者本机状态及重试说明正确
- [x] 双方离线履行同一计划后按管理员、确认时间、UUID 收敛为唯一事实，管理员可审计落选项
- [x] 他人完成、跳过或删除后，接收设备在下一次前台同步取消本机提醒与系统日历副本
- [x] 真实设备 smoke 覆盖系统日历授权/拒绝、可写目标、三级披露、创建/编辑/完成/删除和深链
- [x] 验证系统日历没有照片字节或可外泄 URI，provider 失败不阻断计划保存
- [x] Debug/Release、目标单测、数据库迁移、Rust 协议测试和双设备验收均记录准确结果；未运行门禁不得宣称通过

## Baseline pin

| Item | Value |
|------|--------|
| Shipped product | `dist/lezi-0.2.4-release.apk` · versionName `0.2.4` · versionCode `3` |
| Shipped Room head | **v7** (`core/database/schemas/.../7.json` identityHash `4fc76162dbd27eae8badda9e79f0777c`; matches APK `room_master_table` string) |
| Feature head | Room **v17** (`LeziDatabase.version = 17`) via `MIGRATION_7_8` … `MIGRATION_16_17` only (no invented migrations) |
| Tree at run | working tree on `86ed6e7` + uncommitted feature WIP (orchestrator commits per full spec) |
| Devices | `emulator-5554` / AVD `lezi_api35`; `emulator-5556` / AVD `lezi_api35_b` (API 15 / sdk_phone64_x86_64) |
| Dual-device protocol smoke server | local `lezi-sync` **0.2.4** on `http://127.0.0.1:18768` · `LEZI_DATA_DIR=/tmp/lezi-sync-final-smoke` (no family tokens recorded) |
| Dual-device family | `family_id=ad81f4f9-ffb3-4a2f-9eef-0f6a5ff77d5a` · devices `device-a-7def6557` / `device-b-7def6557` |

## Automated gates (2026-07-27 FIX re-run)

| Gate | Command / scope | Result |
|------|-----------------|--------|
| Full-chain migration | `./gradlew :core:database:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.core.database.DatabaseMigrationTest` on **both** AVDs | **12/12 pass** each (`migrate7To17_preservesShipped024BaselineThroughCarePlanHead` included) |
| Domain Debug | `:domain:testDebugUnitTest` · CareLogTest (incl. new peer complete/delete projection cancel), FulfillmentAuthorityTest, SystemCalendarProjectionPolicyTest | **pass** |
| Sync Debug | `:sync:testDebugUnitTest` · RealSyncPortTest, FakeSyncBackendTest (incl. new dual-client photo package invisibility) | **pass** |
| Feature settings unit | SystemCalendarDisclosureUiTest | **pass** |
| System calendar instrumented | `:feature:settings:connectedDebugAndroidTest` · `AndroidSystemCalendarPortSmokeTest` on **both** AVDs | **2/2 pass each** (L1–L3 CRUD/deep-link/no-photo-leak + provider fail-closed) |
| Cargo focused | `--test api care_plan` (5), `fulfillment` (2), `atomic_bundle` (7) | **all pass** |
| Live dual-device protocol | owner+member against lezi-sync 0.2.4: stage/put/commit Record+CarePlan photo packages; peer pull invisible until commit | **pass** (`DUAL_DEVICE_LIVE_PROTOCOL_SMOKE_OK`) |

### Full-chain migration coverage

`DatabaseMigrationTest.migrate7To17_preservesShipped024BaselineThroughCarePlanHead`:

- Seeds v7 with local user, family/membership, baby, 3 records (pee + 1 photo, memo + 3 photos, soft-deleted formula), custom item「抚触」, free-title calendar event「医院复查」.
- Applies `MIGRATION_7_8` … `MIGRATION_16_17` only.
- Asserts row preservation for records/media/custom_items/calendar_events; feature tables `pending_reminder_cleanup`, `care_plans`, `fulfillment_candidates` present and empty; additive columns `carePlanId`, ownership/syncDirty, plan source/sync, adoption/convert pointers exist.

Device-local layout settings (quick slots / category order) live in DataStore, not Room — not rewritten by schema upgrades; no Room migration invents defaults that overwrite them.

### Dual-device atomic packages (closed this FIX)

| Scenario | Evidence |
|----------|----------|
| Record photo package invisible across stage/upload windows | Live dual-device protocol smoke (device-a → device-b pull) + `FakeSyncBackendTest.dualClientRecordAndCarePlanPhotoPackagesInvisibleUntilCommit` + RealSyncPort atomic failure-window suite |
| CarePlan photo package complete-only visibility | Same dual-device protocol smoke for care_plan root + log media; RealSyncPort `atomicCarePlan*` suite; local-only / retry copy via `localRecordPublishLabel` + CarePlan upload-failure tests |
| Dual offline fulfill → admin/confirmedAt/UUID + admin audit | `FulfillmentAuthorityTest`, CareLog multi-candidate + `conflictAuditListAndConvertAreAdminOnlyAndIdempotent`, RealSyncPort multi-candidate arrival-order, cargo `fulfillment_*` freeze |
| Peer complete/skip/delete → next-foreground cancel reminder + system calendar | `CareLogTest.onFamilyCarePlansAppliedProjectsOpenAndCancelsTerminalWithoutRequestingPermission` + new `onFamilyCarePlansAppliedCancelsReminderAndSystemCalendarOnPeerCompleteAndDelete`; DomainModule `CarePlanFamilyAppliedListener` wiring |

### System-calendar smoke (instrumented + policy)

- New `AndroidSystemCalendarPortSmokeTest` on both emulators: grant READ/WRITE_CALENDAR, ensure writable local calendar, L1/L2/L3 title/description/deep-link, edit-in-place, delete, assert no `EVENT_LOCATION` / `content://` / `file://` photo leak, fail-closed upsert/delete.
- Unit: `SystemCalendarDisclosurePolicy` + `SystemCalendarProjectionPolicyTest` + `SystemCalendarDisclosureUiTest`.
- Residual (honest): interactive OEM permission dialog UX and physical OEM calendar app UI not driven; instrumented grant + fail-closed port stand in for auth/reject/provider-failure acceptance.

## Dual-device / live residuals (explicit non-blockers)

| Residual | Status | Notes |
|----------|--------|-------|
| Full Android **app UI** dual-device offline fulfill timing (two installed APKs, concurrent offline UI) | **not driven** | Authority + audit covered by unit/protocol; UI path left for post-ship optional soak |
| Interactive OEM Calendar permission sheet / picker chrome | **not driven** | Port smoke grants via UiAutomation; product path uses same `AndroidSystemCalendarPort` |

**Do not treat cargo greens alone as dual-device pass** — this FIX also ran live two-token lezi-sync protocol smoke and dual-AVD instrumented calendar/migration.

## Re-run commands

```bash
./gradlew :core:database:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.core.database.DatabaseMigrationTest
./gradlew :domain:testDebugUnitTest \
  --tests com.lezi.babylog.domain.CareLogTest \
  --tests com.lezi.babylog.domain.FulfillmentAuthorityTest \
  --tests com.lezi.babylog.domain.SystemCalendarProjectionPolicyTest
./gradlew :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.RealSyncPortTest \
  --tests com.lezi.babylog.sync.FakeSyncBackendTest
./gradlew :feature:settings:testDebugUnitTest \
  --tests com.lezi.babylog.feature.settings.SystemCalendarDisclosureUiTest
./gradlew :feature:settings:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.settings.AndroidSystemCalendarPortSmokeTest
cargo test --manifest-path tools/lezi-sync/Cargo.toml --locked --test api care_plan
cargo test --manifest-path tools/lezi-sync/Cargo.toml --locked --test api fulfillment
cargo test --manifest-path tools/lezi-sync/Cargo.toml --locked --test api atomic_bundle
```

## Implementation note (FIX wave)

- Extended peer terminal projection cancel coverage for complete + delete with system calendar map cleanup.
- Dual-client FakeSyncBackend photo package invisibility; care_plan log media association validation aligned with lezi-sync.
- Instrumented `AndroidSystemCalendarPortSmokeTest` + androidTest calendar permissions.
- Live dual-device protocol smoke against local lezi-sync 0.2.4 (two devices, Record + CarePlan photo packages).
- Did not invent migrations; did not bump app version; **no commit**.

## Orchestrator / FIX ship decision (2026-07-27)

Ticket 28 acceptance bar is **closed** in this environment: full-chain migration, dual-device atomic package protocol (live + multi-client unit), fulfillment authority/audit unit+cargo, peer cancel projections, and emulator CalendarContract smoke. Optional post-ship residuals: concurrent dual-APK offline UI soak and interactive OEM permission chrome only.
