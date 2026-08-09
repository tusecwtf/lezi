# 13 — 限制 disaster-restore attacker-keyed lock 生命周期

Status: implemented

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- `tools/lezi-sync/src/lib.rs:299-333` 的 `family_locks` HashMap entry 永不删除。
- restore manifest/media/status/cancel/commit 在 journal existence 与 bearer authorization 前，用路径
  `batch_id` 创建 keyed lock（`handlers/disaster_restore.rs:197-310` 等）。
- LAN 未认证请求可不断请求随机合法 UUID；每个 404 都永久留下一个 `Arc<Mutex>`，内存只在进程
  重启时释放。

## Interface boundary

keyed serialization 必须只为 active family/batch 保留状态。使用 bounded/evicting pool、Weak entry 或
专用 restore lock seam 均可，但 authorization/existence 检查与锁获取顺序必须避免 TOCTOU，且不能让
攻击者 key 变成 durable process state。

## Acceptance

- [x] 100,000 个随机有效 batch UUID 的未授权/不存在请求后，lock cardinality 仍为
  `O(active families + active restore batches)`
- [x] 同一合法 batch 的 manifest/media/cancel/commit 仍严格串行；不同 batch 可按合同并行
- [x] entry 在最后 holder/waiter 释放后可回收；没有正在等待的 task 被 eviction 分裂到第二把锁
- [x] existence/auth 检查与 journal mutation 不引入 TOCTOU，错误响应仍不泄露 batch 是否存在
- [x] family 普通同步锁的现有互斥语义不退化

## Validation

- [x] cardinality stress、Weak/eviction race 与 barrier-based same-batch concurrency tests 通过
- [x] restore API auth/not-found response regressions 通过
- [x] Rust fmt/test/Clippy 通过

## Implementation evidence (validated worktree based on fixed HEAD `57ed61ab`)

- 新的 crate-private `RestoreLockPool` 是 restore batch serialization 的唯一 owner。registry 仅保存
  `Weak<tokio::sync::Mutex<()>>`；最后一个 holder/waiter 释放时以 identity check 回收 entry，避免 eviction
  把仍在等待的任务分裂到第二把锁。`OwnedMutexGuard` 与 lease 一并移入 blocking closure，所以 handler
  future 取消不会在实际 journal/store I/O 结束前提前解锁。普通 `family_locks` 与 provisioning 互斥未改。
- manifest/media/status/cancel/commit 的 bearer 认证、journal 读取与 mutation 位于同一 batch lease。
  私有 `credential.sha256` 只保存 recovery token hash，journal-first 原子发布并在同 request retry 补齐；
  legacy active batch 无 envelope 时继续由 journal projection 认证。不存在、损坏、协议不兼容、I/O 错误及
  valid-shape envelope drift 对错误 bearer 均返回相同 401，而正确 bearer 保留真实 500/409 运维语义。
- runtime GC 只在锁外枚举 canonical UUID candidate，再逐 batch 取得同一 restore lease，并在 lease 内重读
  journal/status/expiry 后删除已过期且未 committed 的 staging。它不持 provisioning/family lock 跨 batch
  等待；缺 journal/incomplete 目录只由 Router 开放前的 startup prepare 回收，避免删除并发构造中的 batch。
- 公开 API stress 以 100,000 个 deterministic canonical UUID 请求真实 restore status route，逐次验证统一
  401，随后完成真实 start + authorized status；private registry seam 同量验证最终 cardinality 为 0。
  barrier/race 回归覆盖 same-batch 串行、different-batch 并行、final waiter identity、handler cancel、真实
  manifest 冲突、runtime GC 等待 live holder 后重读、uncontended expiry，以及 runtime sentinel 保留与 startup
  incomplete GC 对照。
- Final gates：`cargo fmt --all -- --check` pass；`cargo test --locked` 在允许 TLS loopback bind 的隔离开发机
  环境中 227 unit + 181 API + 2 TLS pass；`cargo clippy --all-targets --all-features -- -D warnings` pass。
  Independent serial fixed-point review：Standards 0 hard / 0 judgement；Spec 0 hard / 0 scope / 0 judgement。
- 未构建 image/package、未 push、未触碰家庭 NAS，也未执行 CD/前后端联调；部署需另行获得用户确认。
