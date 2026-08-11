# 24 — 回收 server media staging

**What to build:** 为 media receipt/staging 建立 TTL、consumed、orphan、branched/live reachability policy 与有界批次 GC，支持 crash/restart 且不删可达证据。

**Blocked by:** 19、23；[`external 19`](../../repository-dedup-algorithm-audit-20260809/issues/19-resolution-metadata-retention.md)；[`external R20`](../../repository-dedup-algorithm-audit-20260809/issues/20-version-offline-migration-schema-contracts.md)（implemented）

**Status:** implemented (review/final gates pass)

## Contract slice

Unconsumed expired/orphan staging 可回收；consumed bytes 只有在无 live/branch/version reference 且 replay receipt 不再需要内容时 eligible。GC 状态 durable，批次有硬上限。

## Implementation sequence

1. 冻结 TTL、reachability、audit/replay 保留字段与 batch limit。
2. 从票 17/19 seam 标记 expired/consumed/orphan candidates。
3. 事务标记 GC pending，再删除对象并确认终态。
4. 注入 crash/restart，重试 incomplete batches。

## Acceptance

- [x] 不删除 live/branched/version-referenced bytes
- [x] replay/audit evidence 保留，unreachable staging 有界收缩
- [x] GC crash/restart 幂等，批次不阻塞家庭 commit

## Validation

- [x] TTL/reachability/batch/crash tests 通过
- [x] Rust gates 与 isolated GC smoke 通过

## Implementation evidence

- Fixed implementation base `b146ada916542f9bab597ec4c9f4e24874d446b0`。Fresh Store schema
  现在是 exact `user_version=13`，新增 `(family_id, media_uuid, version_id)` reverse reachability
  index、durable staging/upload keyset cursor、server-owned upload sequence 与 publication-confirmed marker。
  Wrong current shape/version 继续 fail closed；R20 legacy v3/v11 offline migrator 的 frozen schema-12
  authority 与 105 个 focused tests 未漂移。H27 仍拥有 0.4.0/version/floor/Room/capability activation，
  H28 仍独占 11/12→13 copy-out mapping。
- Public Store TDD 覆盖 TTL equality、live/publication/version reference retention、branched/version bytes、
  replay/audit row retention、durable cursor restart/wrap、512-row inspection window、8-action batch、
  `gc_pending` crash replay、unlink 后 parent-fsync failure/NotFound retry、9-row startup publication 的 8+1
  restart progress，以及 expired upload-history cap 的 bounded self-heal。Eligibility 在 candidate scan 与
  `IMMEDIATE` writer transaction 内均重新验证；object unlink + parent fsync 成功后才删除 terminal row。
- 每个 streamed PUT 在 body 前获得 durable sequence reservation；正常 length/digest/store error 会按
  temp unlink + parent fsync 后 terminal-abort，task/process cancellation 保留 marker 供 restart GC。
  Active upload marker 阻止 GC 领取同 media 的 writing/staged row，writer 又在 rename 前重载 receipt
  ownership，关闭 GC delete→writer rename 的无 marker UUID orphan race。
- 成功 PUT 与成功 family commit 都只调度 detached bounded family sweep；每 family single-flight。
  `Weak<AppState>` 的 60 秒串行 global maintenance 在无 commit、无 restart 时仍推进 durable keyset，
  missed tick 跳过且 global/family lease 双向互斥，不堆积 blocking tasks，也不让 family commit 等待 GC。
- Focused GREEN：`cargo test --locked causal_media --lib` 24/24；
  `cargo test --locked causal_media --test api` 20/20；schema 5/5；R20 legacy offline-migrate 105/105。
  Public API isolated GC smoke 证明第二次成功 PUT 的响应不等待、随后回收同 family 已过期 receipt/bytes。
- Fresh Rust gates：`cargo fmt --all -- --check`、`cargo test --locked`（299 lib + 219 API +
  1 contract fixture + 3 TLS，共 522）与
  `cargo clippy --all-targets --all-features -- -D warnings` 通过。TLS 3 项首次仅因 sandbox loopback bind
  `Operation not permitted` 失败，随后只在开发机隔离 loopback/ephemeral ports 窄重跑 3/3；未访问
  family NAS。R20 shell guard `deploy/test-copy-back-nas-data.sh` 17 checks 及三个相关 `bash -n` 通过。
- Fresh full-repo `./gradlew test lintDebug :app:assembleDebug` 通过；JUnit XML 608 suites /
  4260 tests，0 failure/error/skipped；debug APK 29,376,406 bytes。门禁使用两个临时只读 signing
  symlink，未读取/复制签名内容且已清除。`adb devices -l` sandbox daemon 因 socket
  `Operation not permitted` 失败，开发机只读窄重跑为 0 device；本票未改 Android，未执行或宣称
  instrumentation/device runtime。
- 12 个 source/test/doc path 的冻结 binary diff SHA-256 为
  `4a4e4c10b8731aee5b8f541cec18e9a89862f8be70f956c1bf567909592a22fa`。Sequential final review：
  Standards Hard 0 / Judgement 0；Spec Hard 0 / Judgement 0 / Unclear 0。未运行 image、package、push、
  NAS 或 CD；未回收 Android spool/conflict metadata，也未实现 H25+。

## Out of scope

不回收 Android spool 或 conflict metadata。
