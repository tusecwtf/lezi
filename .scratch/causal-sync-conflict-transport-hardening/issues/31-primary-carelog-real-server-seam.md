# 31 — 建立 CareLog 到真实服务主缝

**What to build:** 建立开发者自有隔离 TLS lezi-sync 与两个 joined clients，证明 client A 的一个无媒体 Record create/edit 经公共 façade settled，并进入 client B Room/domain。

**Blocked by:** 30

**Status:** implemented

## Contract slice

固定 `CareLog → SyncPort/RealSyncPort → ReplicaSyncEngine → real server → peer Room/domain`；不得以 Store/direct HTTP 替代。

## Implementation sequence

1. 建立 fresh data root、非生产端口和两个 joined sessions。
2. A 通过 CareLog create/edit 一个无媒体 Record 并触发 LocalWrite。
3. 证明 A mutation settled，B pull 到 canonical fact。
4. A 再 pull，证明 cursor 未被 publish 跳过且无意外回滚/重复。

## Acceptance

- [x] 主缝无 reconcile、LocalWrite 不 pull、publish 不移动 cursor
- [x] A settled，B Room/domain 与 canonical fact 一致
- [x] A 后续 pull 保持同一 stable version 且无 duplicate

## Validation

- [x] 可重复 E2E fixture/smoke 通过并记录 exact HEAD/schema
- [x] 不接触家庭 NAS/证书

## Out of scope

不覆盖冲突或媒体故障矩阵。

## Evidence

- **HEAD:** `f7aa8d841f31a04fc67388d6e85dace879edb38a`
- **Schema / release pins:** server schema `13`; Android `0.4.0` / versionCode `21`; Room `28`; local-data contract `5`; protocol floor `21`
- **Fixture path:** `domain/src/test/kotlin/com/lezi/babylog/domain/CareLogRealServerSeamTest.kt` +
  `CareLogRealServerSeamSupport.kt` + `IsolatedLeziSyncServer.kt`
- **Production fix:** `sync/.../ProviderRootWire.kt` — baby pull/stable_root decode tolerates server
  `created_by_membership_id` stamp from `stamp_root` (was fail-closed as unknown field)
- **How to run:**
  ```bash
  # ensure a current lezi-sync binary (shared cargo target-dir or LEZI_SYNC_BIN)
  (cd tools/lezi-sync && cargo build -p lezi-sync)
  ./gradlew :domain:testDebugUnitTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamTest
  ```
- **Related green suites:**
  - `:domain:testDebugUnitTest --tests CareLogRealServerSeamTest --tests CareLogRecordWriteTest`
  - `:sync:testDebugUnitTest --tests RealSyncPortLocalWriteNoPullTest --tests RealSyncPortPushPullTest --tests 'ReplicaSyncEngineCausal*'`
- **Isolation:** mktemp data root under process temp; openssl localhost cert; free `127.0.0.1` ports;
  bootstrap secret fixture-only; rejects NAS env vars; never touches family NAS paths/certs
- **Seam exercised:** CareLog.addRecord/updateRecord → requestLocalSync/LocalWrite → RealSyncPort →
  ReplicaSyncEngine → HttpSyncBackend (pinned TOFU SPKI) → isolated lezi-sync → peer Foreground pull →
  Room/domain fact match; owner republish cursor stable; no duplicate clientUuid rows
