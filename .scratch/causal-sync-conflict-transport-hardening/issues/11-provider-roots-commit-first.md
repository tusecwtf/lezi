# 11 — 迁移 Baby 与 CustomItem commit-first

**What to build:** 将无媒体 Baby、CustomItem 及其不含媒体 membership 的 tombstone 迁移到 frozen commit-first，作为 dependent roots 的 provider 前置。

**Blocked by:** 10

**Status:** implemented (review/final gates pass; device Room execution residual)

## Contract slice

Eligibility 是完整 frozen media manifest 为空；不是“本次无 media diff”。保留排序、身份和引用语义；带 avatar 的 Baby 留在旧安全路径直到票 22。每个 eligible root 使用同一 envelope/settlement。

## Implementation sequence

1. 为 Baby 与 CustomItem 冻结 canonical envelope。
2. 接入 dependency provider ordering 与 commit-first。
3. 统一 live/tombstone terminal/retryable settlement。
4. 删除两类无媒体 reconcile path。

## Acceptance

- [x] 两类 eligible live/tombstone 无 reconcile、no pull、cursor unchanged
- [x] referenced IDs 与 local ordering 不变
- [x] lost response/replan/payload drift 正确

## Validation

- [x] 两类 engine/DAO/domain matrix 通过
- [x] 隔离真实 server 最小 smoke 通过

## Implementation receipt

- Standards review `Hard 0 / Judgement 0`；Spec code review `Hard 0 / Scope 0 / Judgement 0`。Baby/CustomItem pull 与 commit proof 共用一个 typed canonical decoder，并在任何 CAS、清 dirty 或删除 envelope 前 fail closed。
- Android fresh `test`：4076/4076，0 failure/error/skipped；provider-root targeted engine、RealSyncPort、DAO/domain/public-seam tests 通过。deleted Baby 的 dirty tombstoned avatar 与 pre-fix live orphan 继续走 H22 之前的 source repair path；纯无媒体 tombstone 直接 commit。
- `lintDebug`：0 error（89 warning / 0 informational）；Debug 与 signed Release build 通过。Release APK SHA-256 `84681ad0b4835563cf909c1e863b5e185797e7da2a362d628b9b902c21ac6bb5`，apksigner v2/v3 均为 true，证书 SHA-256 `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`；临时 signing symlink 已清理。
- `app`、`core:database`、`sync`、`family`、`log` androidTest Kotlin compile 通过；`domain` 为合法 `NO-SOURCE`。真实 Room instrumentation 已编译，但 `adb devices -l` 为 0 device，未执行 connected/device gate。
- Rust `fmt`、`clippy --all-targets --all-features -- -D warnings` 通过；lib 273、API 186、fixture 1 全通过。沙箱内 TLS listener bind 为 EPERM，精确提权重跑 TLS 2/2 通过；doc tests 0。隔离 Router smoke 覆盖 Baby/CustomItem provider-first、exact replay 与 payload drift。
- `git diff --check` clean。未获 CD 确认，因此未执行 NAS、image、package、push 或 CD。

## Out of scope

不发表 manifest 非空的 Baby、CarePlan 或 WakeObservation。
