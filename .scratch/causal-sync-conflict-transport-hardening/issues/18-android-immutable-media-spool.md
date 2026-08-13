# 18 — 建立 Android 不可变媒体 spool

**What to build:** 冻结发表时只读一次来源 URI，原子复制到应用私有 spool，并持久化 mutation-bound manifest。

**Blocked by:** 10、17

**Status:** implemented (review/final gates pass; device instrumentation residual)

## Contract slice

Pending/branched manifest 绝不按时间删除；durable terminal settlement 立即 eligible。每个 spool 由独立 sidecar journal 绑定 `mutation ID + media UUID/slot + digest + length + canonical metadata`；媒体文件本体不加前缀，上传原始 bytes。Cold start 用 sidecar 补建完整 manifest。多媒体 mutation 只有全部 slots promote 且 Room manifest 原子提交后才可发表；容量压力只暂停新发表。

## Implementation sequence

1. 顺序读取来源并同步计算 digest/length 到 temp。
2. fsync/atomic promote 每个 media 与 sidecar，全部 slots 完成后在 Room 原子持久 group manifest。
3. upload/retry 只打开 immutable spool。
4. 启动时先把可证明属于 pending mutation 的无 reference 文件重新绑定，再回收其余 orphan。

## Acceptance

- [x] 来源每 frozen mutation 只消费一次
- [x] URI 变化/消失/失权后 retry bytes 不变
- [x] multi-media partial group 不可发表；crash 后可从 sidecars 完整重建 manifest
- [x] 容量不足 fail closed 且保留既有证据

## Validation

- [x] source/digest/crash/restart/orphan tests 通过
- [ ] Android storage permission device test 通过

## Implementation evidence

- `FileImmutableMediaSpool` 在 application-private root 以 group + slot write-ahead intent 冻结
  source-once bytes；每槽同步 digest/length/canonical metadata，file/sidecar 分别 fsync + atomic
  promote，root/child directory entry 也同步。五个 durable fault window（含 slot-intent temp）均可
  cold recover，`Partial`/`Complete` typed recovery 不会把不完整 group 暴露为可发表。
- 所有 root/directory/file/list/read/sweep 路径均用 NOFOLLOW、exact-child containment 与
  fail-closed enumeration；unretained symlink 只 unlink，不遍历 external sentinel。单一 closed
  validator 在 recover/sweep/upload 前绑定 canonical mutation/media UUID、Room key/payload、
  filename/slot、role/cardinality、digest/length。journal 另有 8 KiB write/pre-size/stream byte
  budget 与 255-byte MIME 闭包；8192 equality/8193、同长 bytes、digest 与 byte-size corruption
  回归均保留证据并 fail closed。
- `CausalMediaPolicy` 成为 entity→role、role cardinality（avatar 1，其余 3）与 8 MiB slot 的唯一
  typed owner。`CausalSettlement` 在 fact-CAS transaction 内原子写 Room group manifest；engine
  在 mutable source repair 前 recovery/rebind，upload/retry 只从 immutable spool 打开。若当前
  Room facts 在 partial/complete journal 后移除任一 slot，public Engine matrix 保证 zero
  reconcile/preimage/commit、Room manifest 不落盘且 spool 证据保留。
- production instrumentation 使用 real Room、production `CausalSettlement`/engine、
  `AndroidSyncMediaFileStore` 与 file spool，覆盖 media-temp crash、原子 manifest、transaction
  rollback、DB reopen、source 删除/失权后的 exact bytes；并沿 production adapter path 检查
  storage permission。0 device 环境仅完成编译，未宣称执行。
- TDD/复核链：旧 public engine 首次 RED 证明同一 frozen source 被打开两次；后续 external
  symlink、slot-intent temp、corrupt journal 与 removed-slot matrix 均先暴露旧 seam。final gate
  又发现 minSdk 26 不接受 `Channels.newReader`，改为同一 NOFOLLOW channel 的 bounded byte
  reader；并把 reconnect consumer 的异步 cursor/generation/lastSuccess race 收敛为两种合法
  时序，stable identity/endpoint/credentials 仍 exact。最终 Standards 为 Hard 0 / Judgement 0，
  Spec code 为 Hard 0 / Scope 0 / Judgement 0。
- Final gates：fresh Android `clean test lintDebug :app:assembleDebug :app:assembleRelease` 与 6 个
  androidTest Kotlin compile 通过（1799 tasks，4216 JVM tests / 604 suites，0
  failure/error/skip）；release APK SHA-256
  `14dd89e426102b7a746a4cec6f6ab5708f06a474421d839878178cda696c6332`，v2/v3 signer
  SHA-256 与仓库 pin `ce1438c8c50fe75f04f89ae2092631a46660480764cd52071cc5c08707462211`
  一致。Rust production tree 无 diff；当前树 `fmt`、485 tests 与 `clippy -D warnings` 通过，
  isolated TLS 仅在 sandbox 遇到 bind `PermissionDenied`，developer-local 临时端口重跑 3/3
  通过。
- `adb devices -l` 为 0 device，因此 Android storage/Room instrumentation 未执行；本票保留该
  device residual。未执行 NAS、image、package、push 或 CD，也未提前实现 H19/H20/H24/H27。

## Out of scope

不决定 server retention 或 terminal settlement。
