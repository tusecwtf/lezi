# 40 — 验收 APK 数据升级保留

**What to build:** 验收正式 APK 数据到 0.4.0/code 21/Room 28 的非破坏性原地升级，覆盖永久基线与当前 0.3.13 APK 数据。

**Blocked by:** 27

**Status:** implemented (H40 local gates pass; device upgrade residual; unrelated sync JVM residual)

## Contract slice

正式 device upgrade 覆盖永久基线 versionCode 6/Room 24 与当前 source 0.3.13/code 20/Room 27；JVM migration fixtures 覆盖 catalog 中唯一 source schemas 24/25/26/27→28。状态至少含 live/tombstone、pending no-media、pending media/spool、conflict cache、family session/credentials、endpoint origin 与 TLS trust。

## Implementation sequence

1. 为 Room 24/25/26/27 构建真实 schema fixtures 并执行到 28 的连续 migration。
2. 以同签名 code 6 与 code 20 APK 原地安装 code 21 Release candidate。
3. 比较 facts/tombstone、pending/envelopes/conflicts、media/spool、session/credentials/trust。
4. force-stop/restart 后再次核对并运行 `PRAGMA quick_check`。

## Acceptance

- [ ] code 6 与当前 code 20 均可同签名原地升级到 code 21（ADB 无设备，安装/launch 未执行）
- [ ] 不清事实、不丢 pending/conflict/media/spool、不强制 rejoin/trust reset（真实 fixture 执行待设备）
- [ ] 24/25/26/27→28 migration fixtures 行/字节/关系保持，derived cache 可重建（fixture 已编译，运行待设备）
- [ ] upgrade 后 force-stop/restart 与 quick_check 正常（connected gate 待设备）

## Validation

- [x] Room migration acceptance fixture 已扩展并通过 AndroidTest 编译；device execution residual
- [x] signed Release APK、hash/signer/source-target version/schema 与 app-update metadata 对齐

## Out of scope

不迁移 server DB，不覆盖 conflict process-death 或完整 UI。

## Evidence

- **Fixed starting HEAD:** `3961398400139a3b4b32716e5a605db157ca9dff`（H39 Evidence pin；本票只改 H40 测试、发布 metadata 与本票 tracker）
- **Implementation:** 扩展 `app/src/androidTest/kotlin/com/lezi/babylog/LocalDataContractMigrationDeviceTest.kt` 的真实 `MigrationTestHelper` fixtures：24→25→26→27→28、25→26→27→28、26→27→28、27→28；每条路径在目标 Room 28 reopen 后执行 `PRAGMA quick_check` 并要求结果为 `ok`。27→28 fixture 保留 canonical conflict snapshot，并把 `frozen-mutation`（pending no-media）、`frozen-media-spool`、`conflict-page-stage` 与 `replica-reset-receipt` 迁入 `causal_transport_journal`；既有 fixture 同时核对 live/tombstone、dirty pending、media bytes/relations、family/session、credential 与 HTTPS TOFU endpoint。测试未改变生产 migration 语义。
- **Release receipt:** fresh `app/build/outputs/apk/release/app-release.apk`：package `com.lezi.babylog`，versionName `0.4.0`，versionCode `21`，Room `28` / local-data contract `5`；APK SHA-256 `712bc58c10cc065ab5d9f927ac85375b54277e29c7435ea882a0baf3c30cf625`；`apksigner verify --verbose --print-certs` passed，certificate SHA-256 `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`，matches `config/release-apk-signer-sha256.txt`；`tools/lezi-sync/deploy/app-update.json` was corrected to the same APK SHA-256 and package gate passed.
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
- **Full JVM residual detail:** `lostCarePlanMediaResponseReplaysExactGroupBeforeReplanningLaterEdit` rejects duplicate durable bytes; three `ReplicaSyncEngineLocalWriteNoPullTest` cases observe the extra `causal_media_preimage_started` event. These are outside H40-owned files and were not changed.
- **Device residual:** same-signer code 6→21 and code 20→21 install/launch, Room fixture execution, force-stop/process reopen and on-device quick-check remain unrun because `adb devices -l` has no devices. H41 process-death matrix, H42 UI interaction, server DB migration, NAS/CD and production cutover were not run.
