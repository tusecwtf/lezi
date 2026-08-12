# 37 — 验收媒体来源与 spool 故障

**What to build:** 证明来源只读一次，URI 消失/失权、copy 中断、容量不足和 Android 进程死亡后 immutable spool 仍保留精确可重试 bytes。

**Blocked by:** 18、31

**Status:** implemented

## Contract slice

Cases：source changes after freeze、URI disappears、permission revoked、partial temp、post-promote/pre-Room crash、pending restart、capacity pressure。

## Implementation sequence

1. 用可计数/可变 source 冻结并记录 digest。
2. 在 copy/promote/Room commit 注入 faults。
3. 重启并恢复 referenced manifests/清理 orphan temp。
4. 从 spool 重传并比较 bytes。

## Acceptance

- [x] source consume count=1，retry digest 不变
- [x] partial/orphan 不成为可发表 mutation
- [x] pending/branched 不被时间/容量清除
- [x] 容量不足只阻止新发表

## Validation

- [x] Android JVM source/spool fault matrix 通过（device Room residual 诚实记录）
- [x] 记录 source count/digest/cleanup evidence

## Out of scope

不覆盖 server receipt/commit disconnect。

## Evidence

- **HEAD:** _(pinned after feat commit)_
- **Deterministic seed:** `H37_FAULT_SEED = 0x371831` (`0x37_18_31L`)
- **Production owner:** `FileImmutableMediaSpool` (H18)
- **Fixture path:**
  - `sync/src/test/kotlin/com/lezi/babylog/sync/media/MediaSourceSpoolFaultAcceptanceTest.kt`
  - H18 anchors: `ImmutableMediaSpoolTest`, `FileImmutableMediaSpool`
  - device Room residual: `sync/src/androidTest/.../ImmutableMediaSpoolRoomRecoveryDeviceTest.kt`
- **How to run:**
  ```bash
  ./gradlew :sync:testDebugUnitTest \
    --tests com.lezi.babylog.sync.media.MediaSourceSpoolFaultAcceptanceTest \
    --tests com.lezi.babylog.sync.media.ImmutableMediaSpoolTest
  ```
- **Result:** H37 10/10 green; H18 `ImmutableMediaSpoolTest` 11/11 green.
- **Case coverage table:**

  | Case ID | Kind | Seam | Source opens | Digest / cleanup |
  |---|---|---|---|---|
  | `C0-seed-contract` | pin | unit | — | seed `0x371831`; 5 fault windows; journal 8 KiB |
  | `C1-source-changes` | source-once | freeze→mutate→restart freeze | 1 | frozen `1a835ed8734f86355ca5b835d824d486993aabf1913cd3a011b7446c0514b7c9`; retry ≡ frozen |
  | `C2-uri-disappears` | source-once | freeze→missing→recover/open | 1 (freeze); probe +1 only on deliberate `prepareUpload` | `9909ec831e2cf6d0c73fb5480f31945a80987a13faee005704166cb53a26ceca`; spool open still exact after probe fails |
  | `C3-permission-revoked` | source-once | freeze→`SecurityException`→recover/open | 1 (freeze); probe +1 only on deliberate `prepareUpload` | `3b019a7c842d000bcb5ea12519b5d0b68af857f1952df9ba7e447aaf485faef0`; spool open still exact after deny |
  | `C4-partial-temp` | fail-closed | 4 durable windows mid-copy/promote | 1 each | `AfterSlotIntentTempSync` → `Partial` empty, not openable; later windows → `Complete` exact bytes (ordinal digests below) |
  | `C5-post-promote-pre-Room` | process-death | crash after `AfterSidecarPromote` | 1 | `88599a4b7666c3b24c78cf6493abe33c0ad5072e21c3ca1648e4636e4fbacc16`; cold `freezeGroup` reuses slot, no second open |
  | `C6-pending-restart` | retain/sweep | `recoverAndSweep({pending})` | pending 1 / orphan 1 | pending digest `5df182348e7672a6aebc8c384d40305638e23ee2ee3d715e6db3fbe76fb683ff` retained; orphan `61cf92d4afaeaa70f39933a3bf38e1fe001390bc92acc10f41973a3c530de6e8` swept (`recoverGroup` null) |
  | `C7-capacity-pressure` | capacity | capacity=4 / reservation=4 | kept 1; new never opened | kept `9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a`; `MediaSpoolCapacityException` only blocks new freeze; pending survives sweep |
  | `C8-multi-media-partial` | partial group | second source fails mid-group | one=1; two=2 after resume | partial not openable; complete digests one `9dcf97a184f32623d11a73124ceb99a5709b083721e878a16d78f596718ba7b2` / two `970bec7483984728e413a0ff65986310f4bbe8fff5f02c58e6064856262d8476` |
  | `C9-every-fault-window` | matrix | all 5 `ImmutableMediaSpoolFaultPoint` | 1 each | complete digests all `2cbf7d63b0faec62b8ec2270d22d9a3bbff6a5a35b4c324fc31c71beab20268a`; slot-intent-temp only incomplete |

- **C4 ordinal digests (bytes `[0x70,0x41,0x52,ordinal]`):**
  - `AfterSlotIntentTempSync` → Partial (no openable digest evidence)
  - `AfterMediaTempSync` → `9b2f4a24e4b45769f56ab4553031a8057fd6b855944ae28a84a0ff632c125e7d`
  - `AfterSidecarTempSync` → `d94bc4fa8f4727b59f51c2cd51a8f3b262079886ae9771dce3758c9cf11a1c40`
  - `AfterMediaPromote` → `5f1501d1a6054bdf2fc538b8c31a8cb438f8a65a0985f3c2031aa1c0ac3b49fa`
- **Related green:** H18 `ImmutableMediaSpoolTest` re-run with the matrix (0 failures).
- **Isolation:** no NAS/production certs; temp-dir spool + controllable source only.
- **Residuals:** `adb devices -l` reported 0 devices, so Android Room/process-death instrumentation
  (`ImmutableMediaSpoolRoomRecoveryDeviceTest`) was not executed. JVM C5/C9 prove durable
  sidecar-promote recovery and source-once across every fault window on the production file
  spool; device residual remains H18/H41 ownership. Server receipt/commit disconnect is H38.
