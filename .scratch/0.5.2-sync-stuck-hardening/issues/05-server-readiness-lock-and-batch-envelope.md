# 05: 服务端 0.5.2：readiness 探测移出锁，`InvalidCausalBatch` 不冒名回执（P1-R / P2）

**What to build:** `/ready`、握手与 commit 的 `is_ready` 不再互相排队等一次磁盘探测；
`InvalidCausalBatch` 返回不带 `mutation_id` 的 §9.5 信封，客户端按整批处理而不是给第一单元
写错回执。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] `readiness::is_ready`：锁内读缓存；未命中则释放锁再 `spawn_blocking` 探测，探测后短暂进锁
      写缓存（并发未命中允许重复探测一次）
- [x] `handlers/sync.rs`：`InvalidCausalBatch` → `Dispatch::Rejected { mutation_id: None, code: "invalid_domain" }`
- [x] `tests/api.rs`：readiness 缓存过期时两个并发 `/ready` 都在单次探测时长内返回；
      `InvalidCausalBatch` 响应无 `mutation_id`
- [x] `Forbidden*` 422 分支保留为防御，不改
- [x] Rust 三件套：`cargo fmt --all -- --check`、`cargo test --locked`、
      `cargo clippy --all-targets --all-features -- -D warnings`

## 证据

- `readiness.rs:28-64`
- `handlers/sync.rs:611-614`（`first_mutation_id` 顶替；仅空批或 >64 单元可触发，客户端
  `settleBatch` 保证 ≤64）
- `handlers/sync.rs:618-622` 不可达：`authorize_mutation` 的 Forbidden 已在 `causal.rs:2271-2285`
  转成 `forbidden_*` 信封
- 客户端 `CausalSettlement.recordTerminalRejection` 的 `mutationId == null` 分支已按整批处理

## Comments

`is_ready` 锁内只读 5s 缓存；未命中则释放锁再 `spawn_blocking` 探测（`health_check` + 可写
data/media），探测后短暂进锁写 `CachedReadiness`。并发未命中允许重复探测，不在 mutex 上
串行。`InvalidCausalBatch` 改为 `mutation_id: None`，`terminal_commit_rejection` 省略该键；
`Forbidden*` 422 分支未动。测试缝 `readiness_probe_blocking_hook` 与既有 media hook 同款
`#[doc(hidden)]`，不新增产品门面。

Ran: `cd tools/lezi-sync && cargo fmt --all -- --check`；`cargo test --locked` — lib 355 +
api 202 + conflict fixture 1 + tls 5 passed（含
`expired_readiness_probes_are_not_serialized_on_the_cache_lock`、
`causal_commit_empty_batch_rejects_without_mutation_id`；既有 oversized 65 断言加了无
`mutation_id`）；`cargo clippy --all-targets --all-features -- -D warnings` 通过。未跑 NAS
CD，未碰家庭 NAS，未 bump Cargo/version。
