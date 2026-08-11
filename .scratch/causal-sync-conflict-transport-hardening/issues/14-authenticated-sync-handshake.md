# 14 — 建立认证同步握手

**What to build:** 在 endpoint trust 与家庭 session 后用一次认证握手取得 capability、readiness、principal/role、目录 generation、limits、compression 与 retry hints。

**Blocked by:** 12、13

**Status:** implemented (review/final gates pass; device instrumentation residual)

## Contract slice

普通同步不串行依赖 health/ready/setup；成员目录只在 generation 改变或显式刷新时读取。protocol mismatch 在 mutation 前 fail closed。

## Implementation sequence

1. 实现握手 wire 与 auth/not-ready/mismatch 错误。
2. 服务端从 session/ACL 权威派生 principal、role、limits。
3. 客户端每次 foreground sync 只消费一个 handshake result。
4. 以 directory generation 驱动刷新并保留 actor-ID fallback。

## Acceptance

- [x] 正常 sync 只有一次握手，无运维探针链
- [x] mismatch/not-ready/auth 在 mutation 前终结
- [x] generation 不变不下载成员，显式刷新可强制读取
- [x] trust/session/certificate 模型不改变

## Validation

- [x] server/client contract/auth/directory tests 通过
- [x] 隔离 TLS 服务 smoke 通过

## Implementation receipt

- Standards fixed-point `Hard 0 / Judgement 0`；Spec code fixed-point与最终窄 drift
  fixed-point均为 `Hard 0 / Scope 0 / Judgement 0`。最终 source/test freeze 的 tracked diff
  SHA-256 为 `d3d6f00c406bb1cb1b9550219a4aaccd09ac0e730e63b176bc4822002fa43c7f`；
  `git diff --check` clean。
- Android 普通同步在 remote gate 后只调用一次 mandatory authenticated handshake，不再串行调用匿名
  health/ready/setup；auth、not-ready、protocol/capability mismatch 以及 closed-codec 形状错误均在
  directory/pull/commit/reconcile 前终结。source capability 在 H27 前锁为 exact
  `causal_versions`、`wake_observation`、`source_relations`，额外 capability（含
  `causal_sync_v2`）同样 fail closed。
- 服务端握手从认证 session/ACL 派生 principal/role/readiness/limits/compression/retry hints；握手与成员
  route 共用单一 SQLite read snapshot loader。目录 generation 仅覆盖 membership/device 结构，缓存与成员
  snapshot 原子保存；generation 不变跳过下载，显式刷新仍强制读取。两阶段 barrier 回归锁定并发 ABA
  不产生 hybrid snapshot。
- Android fresh aggregate gate `test lintDebug :app:assembleDebug :app:assembleRelease` 与
  `app`、`core:database`、`domain`、`sync`、`feature:family`、`feature:log` 六个
  `compileDebugAndroidTestKotlin` task 通过（1,780 tasks，2m47s；`domain` 为合法
  `NO-SOURCE`）。JVM XML 4,128/4,128，0 failure/error/skipped；Release APK SHA-256
  `5cd5f28b8f47dfa4528b9fd5e88cc20e935eb8998a1a8020b69b60d4c6e159d2`，apksigner
  验证通过且证书 SHA-256 与 tracked pin 精确匹配；临时签名映射已清理。
- Rust `cargo fmt --all -- --check`、`cargo test --locked`（lib 274、API 191、fixture 1、
  TLS 3，共 469/469）与 `cargo clippy --all-targets --all-features -- -D warnings` 全绿。
  独立 Router handshake 正反合同 2/2、SQLite ABA barrier 1/1、mktemp data root/self-signed
  certificate 的隔离 TLS authenticated-handshake smoke 1/1 通过。
- `adb devices -l` 实测 0 device，因此未运行 connected/device instrumentation；所有相关
  androidTest source 已编译。未执行 NAS、image、package、push 或 CD，未改变生产 endpoint、TLS
  identity、家庭 session 或证书信任状态。

## Out of scope

不删除运维 health endpoints 或改变身份模型。
