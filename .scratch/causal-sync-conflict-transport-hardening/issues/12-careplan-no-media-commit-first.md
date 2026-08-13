# 12 — 迁移无媒体 CarePlan commit-first

**What to build:** 将完整 frozen media manifest 为空的 CarePlan 及 tombstone 迁移到 commit-first，保留 Baby/CustomItem/fulfilled Record dependency、fulfillment 与 replan。

**Blocked by:** 11

**Status:** implemented (review/final gates pass; device Room execution residual)

## Contract slice

带 attachment 的 CarePlan 留在旧安全路径直到票 23；不重开事实/未来意图、fulfillment authority 或 source relation。

## Implementation sequence

1. 冻结 CarePlan canonical envelope 与依赖集合。
2. 在 providers/fulfilled facts settled 后 commit-first/replan。
3. 统一 live/tombstone settlement 与 terminal errors。
4. 删除 eligible CarePlan reconcile path。

## Acceptance

- [x] dependency/replan 不产生缺引用 commit
- [x] fulfillment/source relation 结果不变
- [x] lost response、branch、tombstone 与 contentEpoch CAS 正确

## Validation

- [x] CarePlan domain/engine/DAO dependency matrix 通过
- [x] isolated real-server no-media smoke 通过

## Implementation receipt

- Standards review `Hard 0 / Judgement 0`；Spec code review `Hard 0 / Scope 0 / Judgement 0`。freeze、pull 与 stable proof 共用 CarePlan canonical decoder/reference resolver；causal UUID canonical、fulfilled Record 同 Baby、wrong-type/partial proof 均在 envelope/CAS/写入前 fail closed。
- 完整 frozen manifest 为空的 CarePlan live/tombstone 使用 generic durable envelope、typed proof、terminal CAS 与 provider-first order；attachment 仍走 H23 之前的 source reconcile path。Baby/CustomItem/fulfilled Record 依赖、lost response exact replay、content drift、branch/replan、source relation 与 calendar local projection 均由 public engine/RealSyncPort matrix 锁定。
- Android fresh `test`：4096/4096，0 failure/error/skipped；H12 targeted engine/RealSyncPort public-seam 33/33 通过。`lintDebug`：0 error（108 warning / 4 informational）；Debug 与 signed Release build 通过。Release APK SHA-256 `0e5236d2c8bf2b682a195eb0847ecca39791a1f368d7428070e5c9bf5307d55f`，apksigner v2/v3 均为 true，证书 SHA-256 `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211` 与 tracked pin 精确匹配；临时 SDK/signing symlink 已清理。
- `app`、`core:database`、`sync`、`family`、`log` androidTest Kotlin compile 通过；`domain` 为合法 `NO-SOURCE`。真实 Room instrumentation 已编译，但 `adb devices -l` 为 0 device，未执行 connected/device gate。
- Rust `fmt`、`clippy --all-targets --all-features --locked -- -D warnings` 通过；lib 273、API 187、fixture 1 全通过。沙箱内 TLS listener bind 为 EPERM，精确提权重跑 TLS 2/2 通过；doc tests 0。隔离 Router 精确 smoke 1/1 覆盖 no-media CarePlan provider/fulfilled Record batch、exact replay、content drift 与 tombstone/pull proof。
- `git diff --check` clean。未获 CD 确认，因此未执行 NAS、image、package、push 或 CD。

## Out of scope

不迁移 attachment 或改变 CarePlan 产品语义。
