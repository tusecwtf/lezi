# 13 — 迁移 WakeObservation commit-first

**What to build:** 将 WakeObservation 及 tombstone 迁移到 frozen commit-first，保留 Record source relation、事实语义与 aggregation 行为。

**Blocked by:** 11

**Status:** implemented (review/final gates pass; device Room execution residual)

## Contract slice

WakeObservation 是已发生事实，不是计划；本票只替换 transport path，不改变 effective Wake、overlap 或统计规则。

## Implementation sequence

1. 冻结 Wake canonical envelope 与 Record source identity。
2. 在 source dependency 满足后 commit-first/replan。
3. 统一 live/tombstone settlement 与 terminal errors。
4. 删除 Wake reconcile path。

## Acceptance

- [x] source relation 不丢失且无缺引用 commit
- [x] effective Wake/aggregation/domain projection 不变
- [x] lost response、branch、tombstone 与 contentEpoch CAS 正确

## Validation

- [x] Wake domain/engine/RealSyncPort/production-fake DAO JVM matrix 通过
- [ ] 真实 Room accepted/merged × current/superseded CAS/rollback instrumentation 执行（已编译；0 device residual）
- [x] isolated real-server smoke 通过

## Implementation receipt

- Standards review `Hard 0 / Judgement 0`；Spec code review `Hard 0 / Scope 0 / Judgement 0`。freeze、stored proof、stable proof 与 pull 共用唯一 typed Wake decoder/reference resolver，严格校验 source UUID、Sleep type、server observer 与 `wakeTimestamp >= Sleep.timestamp`，所有损坏 proof 均在 terminal CAS/产品写前 fail closed。
- Wake live/tombstone 使用 generic durable frozen envelope、provider-first order 与 accepted/merged/branched terminal CAS；Sleep 依赖、lost-response exact replay、content drift、branch/replan、source relation 与 server observer 由 engine、public `RealSyncPort` 与 production-fake DAO JVM matrix 锁定，真实 Room 四格 CAS/rollback matrix 仅完成编译。旧 Wake reconcile owner 已退出；含媒体 Wake 不进入旧 technical-media repair，在 H18/H20 之前保持逐字段零写。
- Android fresh `test` 证据：4110/4110，0 failure/error/skipped；H13 JVM engine/RealSyncPort/production-fake DAO public-seam targeted matrix 通过，真实 Room targeted source 仅完成 instrumentation 编译。`lintDebug`：0 error（81 warning / 4 informational）；Debug 与 signed Release build 通过。Release APK SHA-256 `586824f9cacc719956f5ae8f35072cac281b7c42738aff6c280dca0f20aa198b`，apksigner v2/v3 均为 true，证书 SHA-256 `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211` 与 tracked pin 精确匹配；临时 SDK/signing symlink 已清理。
- `app`、`core:database`、`sync`、`family`、`log` androidTest Kotlin compile 通过；`domain` 为合法 `NO-SOURCE`。真实 Room instrumentation 已编译，但 `adb devices` 为 0 device，未执行 connected/device gate。
- Rust `fmt`、`clippy --all-targets --all-features -- -D warnings` 通过；lib 273、API 188、fixture 1、TLS 2 全通过，doc tests 0。沙箱内 TLS listener bind 为 EPERM，精确提权完整重跑通过；隔离 Router Wake smoke 1/1 覆盖 exact replay、content drift、branch、tombstone 与 peer pull。
- `git diff --check` clean。未获 CD 确认，因此未执行 NAS、image、package、push 或 CD。

## Out of scope

不改变 Wake 产品语义或汇总 bounds。
