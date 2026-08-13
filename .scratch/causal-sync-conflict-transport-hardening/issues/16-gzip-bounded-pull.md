# 16 — 实现 gzip 有界增量 pull

**What to build:** 按握手协商 gzip 的普通增量 pull page，并限制 encoded/decoded bytes、item/page count 与 pull continuation 单调性。

**Blocked by:** 14、15

**Status:** implemented (review/final gates pass; device Room instrumentation residual)

## Contract slice

本票的 pull continuation 与 ConflictSnapshot receipt/continuation 独立。cursor 只在完整 page 的 Room transaction 提交后前进；坏 gzip/页整体 fail closed。

## Implementation sequence

1. 冻结普通 pull 的 encoding、budget 与 continuation errors。
2. 服务端按页执行 item/encoded budget 后编码。
3. 客户端流式解压并执行 decoded/item/page budget。
4. 将 page facts 与 cursor/continuation 原子落盘。

## Acceptance

- [x] gzip/identity 语义一致
- [x] truncated/corrupt/bomb/over-budget 不写部分 cursor
- [x] duplicate/skipped/non-monotonic pull page fail closed
- [x] crash 后从最后 committed page 恢复

## Validation

- [x] compression/budget/cursor transaction tests 通过
- [x] fault proxy 大页/坏 gzip smoke 通过

## Out of scope

不复用 conflict snapshot token，不更换 JSON。

## Implementation receipt

- `SyncBackend.pull(session, PullPageRequest)` 是唯一普通 pull seam；同一个握手冻结
  gzip/identity、200 entities、9 MiB encoded、8 MiB decoded 与 500 pages，HTTP adapter 与
  engine 共享 typed single-page validator，跨页 continuation/cursor ownership 只在 engine。
- Android loopback regressions 覆盖 gzip/identity 等价、缺失/错误 `Content-Encoding`、坏或截断
  gzip、解压炸弹、带/不带 `Content-Length` 的 encoded cap、item/page cap、页内/跨页重复、
  skipped/non-monotonic cursor、H15 retry 保持同一 `PullPageRequest`；拒绝发生在
  parse/apply/checkpoint 前。
- Rust Router 覆盖 `Vary: Accept-Encoding`、gzip/identity 等价、unsupported/invalid/q=0
  fail closed 与 page 499/500；family guard 释放后，serialize/budget/gzip 在 blocking worker
  完成，不占 Tokio executor worker。
- `PullCheckpointRoomReplayTest` 使用 file-backed `LeziDatabase`、production transaction runner
  与 file DataStore：第二页 malformed entity 触发真实 Room rollback，重建后从最后 committed
  cursor 重放第一页且保持实体幂等。当前 `adb devices -l` 为 0 device，因此本次只完成该
  instrumentation source 的编译；真机/模拟器执行保留为明确 residual。
- source freeze 的 Standards review 为 Hard 0 / Judgement 0，Spec code review 为
  Code Hard 0 / Scope 0 / Judgement 0。
- fresh Android aggregate：`clean test lintDebug :app:assembleDebug :app:assembleRelease` 加
  app/core-database/domain/sync/feature-family/feature-log 六个 `compileDebugAndroidTestKotlin`
  全绿（1799 tasks；JVM XML 4186 tests、0 failures/errors/skips）。release APK 通过 apksigner
  v2/v3 校验且 signer digest 匹配 tracked pin；SHA-256
  `47866cc891084a43ebd54d6f8f77805ccfee0898a74dada675285258828f0c08`。
- Rust final gates：`cargo fmt --all -- --check`、`cargo test --locked`（274 lib + 193 API +
  1 fixture + 3 isolated TLS）、`cargo clippy --all-targets --all-features -- -D warnings` 全绿；
  两条 H16 Router contract test 另行 exact 重跑各 1/1 通过。第一次 TLS gate 在 sandbox 因
  loopback socket `PermissionDenied` 停止，按证书测试隔离规则在 developer-owned local
  loopback 非 sandbox 重跑 3/3 通过。
- 未执行 NAS、image、package、push 或 CD；H17 media receipt 与 H27 capability activation
  均未提前实现。
