# 40 — 验收 APK 数据升级保留

**What to build:** 验收正式 APK 数据到 0.4.0/code 21/Room 28 的非破坏性原地升级，覆盖永久基线与当前 0.3.13 APK 数据。

**Blocked by:** 27

**Status:** implemented (local and API 35 device acceptance pass)

## Contract slice

正式 device upgrade 覆盖永久基线 versionCode 6/Room 24 与当前 source 0.3.13/code 20/Room 27；JVM migration fixtures 覆盖 catalog 中唯一 source schemas 24/25/26/27→28。状态至少含 live/tombstone、pending no-media、pending media/spool、conflict cache、family session/credentials、endpoint origin 与 TLS trust。

## Implementation sequence

1. 为 Room 24/25/26/27 构建真实 schema fixtures 并执行到 28 的连续 migration。
2. 以同签名 code 6 与 code 20 APK 原地安装 code 21 Release candidate。
3. 比较 facts/tombstone、pending/envelopes/conflicts、media/spool、session/credentials/trust。
4. force-stop/restart 后再次核对并运行 `PRAGMA quick_check`。

## Acceptance

- [x] code 6 与当前 code 20 均可同签名原地升级到 code 21
- [x] 不清事实、不丢 pending/conflict/media/spool、不强制 rejoin/trust reset
- [x] 24/25/26/27→28 migration fixtures 行/字节/关系保持，derived cache 可重建
- [x] upgrade 后 force-stop/restart 与 quick_check 正常

## Validation

- [x] Room migration acceptance fixture 已扩展并在 API 35 设备执行通过
- [x] signed Release APK、hash/signer/source-target version/schema 与 app-update metadata 对齐

## Out of scope

不迁移 server DB，不覆盖 conflict process-death 或完整 UI。

## Evidence

- **Fixed starting HEAD:** `3961398400139a3b4b32716e5a605db157ca9dff`（H39 Evidence pin；本票只改 H40 测试、发布 metadata 与本票 tracker）
- **Implementation:** 扩展 `app/src/androidTest/kotlin/com/lezi/babylog/LocalDataContractMigrationDeviceTest.kt` 的真实 `MigrationTestHelper` fixtures：24→25→26→27→28、25→26→27→28、26→27→28、27→28；每条路径在目标 Room 28 reopen 后执行 `PRAGMA quick_check` 并要求结果为 `ok`。27→28 fixture 保留 canonical conflict snapshot，并把 `frozen-mutation`（pending no-media）、`frozen-media-spool`、`conflict-page-stage` 与 `replica-reset-receipt` 迁入 `causal_transport_journal`；既有 fixture 同时核对 live/tombstone、dirty pending、media bytes/relations、family/session、credential 与 HTTPS TOFU endpoint。测试未改变生产 migration 语义。
- **Release receipt:** fresh clean-HEAD `dist/lezi-0.4.0-release.apk`：package `com.lezi.babylog`，versionName `0.4.0`，versionCode `21`，Room `28` / local-data contract `5`；APK SHA-256 `1ebab3fedadcd87c75c3341fd173741b52ae701c27b746eaf30c07be0d5c1d2c`；`apksigner verify --verbose --print-certs` passed，certificate SHA-256 `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`，matches `config/release-apk-signer-sha256.txt`；artifact built from committed `ed084501` in an isolated worktree and `tools/lezi-sync/deploy/app-update.json` matches the same APK SHA-256.
- **Commands/results:**
  ```text
  ./gradlew :app:compileDebugAndroidTestKotlin --no-daemon                 PASS
  ./gradlew :app:assembleRelease --no-daemon                               PASS; signed APK verified
  LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 .../package-nas.sh                  PASS; app-update inputs OK
  ./gradlew :app:testDebugUnitTest --no-daemon                              PASS
  ./gradlew :sync:testDebugUnitTest --tests com.lezi.babylog.sync.appupdate.AndroidReleaseCompatibilityCatalogTest --tests com.lezi.babylog.sync.appupdate.AppUpdateApkIdentityTest --tests com.lezi.babylog.sync.appupdate.AppUpdateInstallStatusPolicyTest --no-daemon
                                                                            PASS; 50 tests
  ./gradlew :app:lintDebug --no-daemon                                      PASS
  ./gradlew :app:connectedDebugAndroidTest --no-daemon                      FAIL: No connected devices!
  adb devices -l                                                            empty device list
  ./gradlew test --no-parallel --no-daemon                                  FAIL: 4 existing sync causal/local-write tests
  git diff --check                                                          PASS
  ```
- **Historical residual closure:** the four JVM fixture mismatches were corrected in
  `0c252be9`; the full JVM suite subsequently passed. The device residual is closed by the
  receipts below. Server schema/NAS cutover remains owned by release/CD work, not H40.

### 2026-08-13 device closure

- API 35 software emulator ran `LocalDataContractMigrationDeviceTest`: `OK (5 tests)`.
  The real Room 24/25/26/27→28 fixtures reopened Room 28, retained the catalogued
  facts/tombstones/pending/media/conflict/transport/session/trust evidence, and returned
  `PRAGMA quick_check = ok`.
- Same signer `ce1438c…2211` upgrade receipts passed for both permanent sources:
  code 6 (`0.3.0`) → code 21 (`0.4.0`) and a freshly built fixed-source code 20
  (`0.3.13`, source `99b71308`) → code 21. Both old and upgraded APKs cold-launched;
  `adb install -r` succeeded, `firstInstallTime` stayed unchanged across each upgrade,
  and the code 20 path cold-launched again after `force-stop`.
- The temporary code 20 worktree, copied signing material, and build outputs were removed
  immediately after the receipt. No APK or signing material was added to Git.
