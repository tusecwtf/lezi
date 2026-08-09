# 13 — 限制 disaster-restore attacker-keyed lock 生命周期

Status: ready-for-agent

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

- [ ] 100,000 个随机有效 batch UUID 的未授权/不存在请求后，lock cardinality 仍为
  `O(active families + active restore batches)`
- [ ] 同一合法 batch 的 manifest/media/cancel/commit 仍严格串行；不同 batch 可按合同并行
- [ ] entry 在最后 holder/waiter 释放后可回收；没有正在等待的 task 被 eviction 分裂到第二把锁
- [ ] existence/auth 检查与 journal mutation 不引入 TOCTOU，错误响应仍不泄露 batch 是否存在
- [ ] family 普通同步锁的现有互斥语义不退化

## Validation

- [ ] cardinality stress、Weak/eviction race 与 barrier-based same-batch concurrency tests 通过
- [ ] restore API auth/not-found response regressions 通过
- [ ] Rust fmt/test/Clippy 通过
