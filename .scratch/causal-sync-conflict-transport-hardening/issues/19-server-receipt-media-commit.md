# 19 — 实现 server receipt-bound media commit

**What to build:** 让服务端 media commit 只引用 durable receipt 与 canonical metadata，并原子产生 accepted/merged/branched 结果。

**Blocked by:** 17

**Status:** implemented (review/final gates pass)

## Contract slice

短 transaction 验证 receipt binding/metadata，不在主锁重读大对象。lost response 用相同 mutation/hash/receipt 返回原终态加 replay marker。

## Implementation sequence

1. 冻结 receipt reference 与 media metadata validation。
2. 原子 claim/attach receipt 并执行 commit merge/branch。
3. 持久 commit result/request hash 供 replay。
4. 暴露 consumed/expired/orphan 状态给清理 seam。

## Acceptance

- [x] accepted/merged/branched 引用精确上传 bytes
- [x] wrong/expired/foreign receipt fail closed
- [x] lost response replay 不重复版本或上传
- [x] branch 保留完整 media identity/bytes

## Validation

- [x] Store/API receipt/binding/replay tests 通过
- [x] Rust gates 与 isolated byte-equality smoke 通过

## Implementation evidence

- `causal_media_staging` 以 durable staging row 作为 receipt authority；完整 canonical manifest
  先验证 family、membership、SHA-256、byte size、TTL 与 existing media identity，再在同一
  `IMMEDIATE` transaction 内条件式 `staged → consumed`。任一 wrong/expired/foreign item 都在
  version、projection、terminal receipt 之前 fail closed，且不会消费同批其他 receipt；SQLite、
  stored JSON/status 与 I/O corruption 保持 typed Store error，不伪装为客户端终态 rejection。
- accepted、merged、branched 与 canonical-equivalent no-op 都在 claim 后原子落 version/projection/
  mutation receipt；exact replay 从原 durable version 选择精确 manifest，不新增 version 或上传。
  choice resolution 只复用已经 consumed 的 receipt，并在落 stable terminal 前验证 retained bytes。
- SQLite durable phase 与文件 publication 分离：HTTP 在 transaction 完成后显式释放 family mutex，
  再等待 bounded exact-manifest hash/link/copy/fsync。`Store` clones 共享 per-family publication owner，
  同时覆盖 ordinary Store façade、commit、resolution first/replay 与 startup repair；无媒体 commit
  不取该锁。branch commit 与 concurrent resolution 的 Router 回归以 durable resolved/terminal
  barrier 证明 serialization，双方响应收敛、exact replay 保留原 branch version、publication 唯一且
  bytes 相等；无关 corrupt consumed history 不再进入本批 commit/replay 路径。
- Public Store/API matrix 覆盖 wrong family、wrong byte size、`now == expires_at`、wrong digest、
  foreign principal、corrupt stored status/entity 与 promotion fault。所有 semantic binding rejection
  都保留 staged receipt，并对 family revision、version、terminal receipt、record/media projection 与
  media publication 证明 zero-write；修正 binding 或 startup GC 后 fresh receipt 可用同一 mutation
  成功 claim。旧 `verify_manifest`、`consume_manifest` 与 family-wide commit promoter 已删除。
- TDD/复核链：首个旧实现敏感 RED 暴露 promotion fault replay 返回
  `media_uuid_conflict`；后续 unrelated corrupt family row、stored status/JSON、family-lock large-I/O、
  branch/resolve publication race 均先 RED。竞态测试另以临时移除 Store guard 的 mutation check
  稳定 RED。最终 Standards 为 Hard 0 / Judgement 0，Spec code 为 Hard 0 / Scope 0 /
  Judgement 0。
- Final server gates：`cargo fmt --all -- --check`、282 lib tests、210 API tests、1 frozen contract
  fixture 与 `cargo clippy --all-targets --all-features -- -D warnings` 通过；sandbox 禁止 loopback
  bind 导致 TLS 三项 `PermissionDenied`，developer-local isolated rerun 3/3 通过（合计 496 Rust
  tests）。未接触 NAS、image、package、push 或 CD。
- 补充 Android 证据：本票没有 Android source diff；`test`/`lintDebug`/Debug + signed Release assemble
  与六个 `androidTest` compile（排除未归属本票的 `sync` unit task）共 1764 tasks 通过，`adb
  devices -l` 为 0 device。此前 broad `test` 的 disaster-restore failure 单测重跑通过；reconnect
  failure 在 clean base HEAD 复现；session-lifecycle timeout 保留为未归属本票的 flaky residual，未将
  其误报为 H19 acceptance。
- H19 保留 `status`/`expires_at`/`consumed_at` 与 exact manifest 作为 H24 cleanup seam，但没有启动
  settlement/GC/capability。显式 response `replay` marker 按冻结的 H10/H25 拆分仍由 H25 实现；H19
  已保证 replay 返回原终态与 version/request hash，不提前扩张 H20、H24、H25 或 H27。

## Out of scope

不实现 Android settlement 或清理 job。
