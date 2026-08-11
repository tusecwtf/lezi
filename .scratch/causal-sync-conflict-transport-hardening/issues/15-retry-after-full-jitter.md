# 15 — 实现 Retry-After 与有界 full jitter

**What to build:** 建立一个可注入 clock/random 的 typed retry policy，优先合法 Retry-After，否则执行 capped exponential full jitter，并按唯一预算表终结。

**Blocked by:** 14

**Status:** implemented (review/final gates pass; device instrumentation residual)

## Contract slice

预算表：handshake/detail `3s connect, 10s response, 3 attempts, 30s elapsed`；pull/commit/resolution `3s, 20s, 3, 60s`；预留 media prepare `5s, 90s, 3, 240s`。本票接入已存在操作；media prepare 由票 17 接入。stale/expired 终止当前 resolution request 并转票 09 refresh，不终止事实。

## Implementation sequence

1. 实现服务端错误类别与 Retry-After 解析。
2. 实现 deterministic full-jitter 与上述预算表。
3. 将策略接入现有 handshake/detail/pull/commit/resolution；只有幂等请求可自动重试。
4. 映射诚实 pending/terminal UI 与无内容 telemetry。

## Acceptance

- [x] 合法 Retry-After 优先，非法值回退 jitter
- [x] delay/attempt/elapsed/timeout 均符合表中硬上限
- [x] auth/capability/ACL/canonical 不盲重试
- [x] stale fact 保留并进入 refresh，不标永久失败

## Validation

- [x] deterministic clock/random/error matrix tests 通过
- [x] 429/503/timeout 隔离 fault smoke 通过

## Implementation receipt

- Standards fixed-point 为 `Hard 0 / Judgement 0`；Spec code fixed-point 为
  `Hard 0 / Scope 0 / Judgement 0`。最终源码复审 freeze 的 tracked diff SHA-256 为
  `814f0a9e2a2cdca179fc8b6394b3e75db1f8e058943981294def1504467277fd`；四个新增 retry
  source/test 文件另以逐文件 SHA-256 固定，`git diff --check` clean。
- `sync/backend/retry` 成为唯一前台 transport retry owner：五个已存在的幂等 operation 按冻结预算
  消费合法 Retry-After，否则执行 deterministic capped exponential full jitter。deadline 同时约束
  coroutine、晚启动 socket timeout 与 blocking `HttpURLConnection` disconnect；caller cancellation、
  auth/capability/ACL/canonical/stale/expired 保持终态，401 仍只由 refresh-once owner 处理。
- `RealSyncPort` 的旧 30s/2min/10min 广域失败调度及其 policy/tests 已删除；source reconcile 不重试，
  media prepare 只冻结预算而未在 H17 前接线。stale/expired resolution 继续交给 H09 refresh 状态机，
  不删除旧事实；telemetry 只含 operation/category/attempt/delay。
- server causal-commit admission saturation 返回 typed 429 与 `Retry-After: 60`，header/body 均不含
  family/root/member/care content。Android deterministic matrix 与 fault tests 覆盖 valid delta/date、
  invalid header、1001/2001 jitter bounds、429/503/timeout、deadline equality/disconnect、1..64 commit、
  cancellation、401 composition 及五 operation attempts。
- Android fresh aggregate gate `test lintDebug :app:assembleDebug :app:assembleRelease` 与 `app`、
  `core:database`、`domain`、`sync`、`feature:family`、`feature:log` 六个
  `compileDebugAndroidTestKotlin` task 通过（1,799 tasks，2m13s；`domain` 为合法 `NO-SOURCE`）。
  首轮不限制 worker 的 fresh aggregate 曾出现两个既有 `RealSyncPort` 并发时序失败：
  `failedFirstPullKeepsOwnerSessionAndRestartRetriesWithoutCreatingAgain` 超时留下未完成 coroutine，
  `configuredCandidateForSameFamilySwitchesEndpointAndSessionTogether` 命中 endpoint/session 等值竞争。
  两项精确 isolated rerun 在未改任何 source/test 的情况下 2/2 通过；它们均通过 `SyncRig` 的
  `RecordingSyncBackend`，不经过本票新增的 production retry decorator。结合随后 `--max-workers=2`
  的 fresh 全量 gate 固定点全绿，判定为 aggregate concurrency timing flakes，而非 H15 retry policy
  的确定性失败。
  JVM XML 4,164/4,164，0 failure/error/skipped；Release APK SHA-256
  `37093567036090f44be7417425934211ed3abd2c06a6e9ee5a2fb5832d29989e`，apksigner
  验证通过且证书 SHA-256 与 tracked pin 精确匹配；临时签名 links 已清理。
- Rust `cargo fmt --all -- --check`、`cargo test --locked`（lib 274、API 191、fixture 1、TLS 3，
  共 469/469）与 `cargo clippy --all-targets --all-features -- -D warnings` 全绿；独立 Router
  saturation/Retry-After 回归 1/1 通过。TLS gate 只绑定 developer-local isolated loopback。
- `adb devices -l` 实测 0 device，因此未运行 connected/device instrumentation；相关 androidTest
  source 已编译。未执行 NAS、image、package、push 或 CD，未改变生产 endpoint、TLS identity、
  家庭 session 或证书信任状态。

## Out of scope

不引入后台调度。
