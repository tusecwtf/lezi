# 17 — 签发流式媒体 preimage receipt

**What to build:** 服务端流式接收媒体，在 family/SQLite 写关键区外计算 hash/length，并以短事务签发 durable receipt。

**Blocked by:** 14、15

**Status:** implemented (review/final gates pass; device instrumentation residual)

## Contract slice

Receipt 绑定 family/principal/digest/length/TTL。此票负责 prepare 幂等、expiry 状态标记和一个可调用清理 seam；不负责 conflict metadata 或 Android spool GC。

## Implementation sequence

1. 冻结 upload limits、binding、TTL 与 receipt 状态。
2. 流式写 server-owned temp，避免整对象驻内存。
3. 在主写关键区外 hash/verify，再短事务持久 receipt。
4. 支持 lost prepare/duplicate，接入票 15 的 media-prepare retry budget，并暴露 expired/orphan cleanup seam。

## Acceptance

- [x] 大 IO/hash 不持有 family lock/SQLite write transaction
- [x] lost/duplicate prepare 返回同一可用语义
- [x] wrong digest/length/family/principal fail closed
- [x] 慢上传不阻塞并发小 commit

## Validation

- [x] streaming/memory/lock/replay tests 通过
- [x] Rust gates 与 isolated slow-upload smoke 通过

## Implementation evidence

- Android 将 causal media prepare 接入 H15 的 `MediaPrepare` retry budget，每次尝试复用同一
  `mediaUuid`/source/digest 冻结请求；HTTP streaming seam 同时应用 connect/response/elapsed
  watchdog。公开 retry 回归证明 503 后重试不改变 source 对象或请求字段。
- Router 在 body 之前执行可撤销的 membership/family inflight admission（2/8），超额返回
  typed `429` + `Retry-After`；body 流式写入 server-owned 随机隐藏 temp，不整体驻内存。
  verification 与 stage 由单一 blocking closure 持有 reservation/temp/verified file/owned family
  guard，因此 abort 不会在后台 hash/SQLite/rename 结束前提前释放资源或越锁。
- Store 只接受 typed verified-file/digest evidence，在 family lock/SQLite write transaction 外计算
  hash/length，并用短事务持久化 family/principal/digest/length/24h TTL/status。exact
  replay 维持同一 receipt 语义，并对同长 staged corruption 原子修复；expiry 标记与可调用
  orphan cleanup seam 已覆盖。
- Public-seam 回归覆盖默认 64/256/512 MiB 与 24h 合同、所有 equality/+1
  边界、wrong binding/digest/length、lost/duplicate prepare、commit 前重启后 exact replay、同长
  corruption repair、多慢流饱和/取消清理、verify/store 阶段 deterministic abort 以及慢上传不
  阻塞小 commit。Standards 复核为 Hard 0 / Judgement 0；Spec code 复核为 Hard 0 /
  Scope 0 / Judgement 0。
- Final gates：Android `clean test lintDebug :app:assembleDebug :app:assembleRelease` 与 6 个
  androidTest Kotlin compile 通过（1799 tasks，4188 JVM tests，0 failure/error/skip）；release APK
  SHA-256 `526fb64cc4f31aac4548540fa6aa94beafea2f5ced078b4aa397721e5f00b251`，
  v2/v3 signer SHA-256 与仓库 pin 一致。Rust `fmt`/`clippy -D warnings` 与 278 lib + 203 API +
  1 fixture + 3 TLS = 485 tests 通过；TLS 仅在 sandbox 遇到 loopback bind `PermissionDenied`，
  在 developer-local 隔离环境重跑 3/3 通过。prepare 7/7、abort 2/2 与 isolated
  slow-upload 1/1 定向用例通过。
- `adb devices -l` 为 0 device，因此未运行 connected instrumentation；本票无 UI/设备交互改动。
  未执行 NAS、image、package、push 或 CD，也未提前实现 H18/H19/H24/H27。

## Out of scope

不执行最终 media commit/GC policy。
