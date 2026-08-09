# 12 — 限制 causal commit 与 open branch admission

Status: implemented

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Dependencies satisfied: 04 与
[`causal hardening 01`](../../causal-sync-conflict-transport-hardening/issues/01-freeze-conflict-v2-contract.md)
均已 implemented、reviewed、fully gated。

## What to build

为 causal commit 建立按 principal/family 的稳定准入预算，并为每个 root 建立 open branch 上限。
饱和时返回可识别的 429/typed 结果，保留所有已 durable branch 的可发现性，不静默丢事实。

## Implementation sequence

1. 在本票内冻结 limiter key、窗口、数值上限、branch 计数口径和 typed 饱和错误码。
2. 在创建新 durable branch 前原子检查上限；精确 replay 不重复占用预算。
3. 让 HTTP 与 Store 使用同一准入结果，并提供不含家庭内容的观测分类。
4. 用边界与并发测试证明上限不会覆盖或隐藏既有 branch。

## Frozen admission contract

- **预算单位**：一次已认证 `causal_commit` Store/HTTP batch 若至少含一个非精确 replay 的 unit，
  在执行内容校验前计一次；全 batch 都命中同 principal/root/mutation/hash 的 durable receipt 时免计，
  混合 replay/new batch 仍计一次。业务校验拒绝也属于已消耗尝试。
- **key 与窗口**：principal key 为 `(family_id, membership_id)`，明确不含可轮换的 `device_id`；
  family key 为 `family_id`。半开滑动窗口为 60 秒，边界 `t + 60` 释放 `t` 的样本；服务器时钟
  回拨时丢弃未来样本。每 principal 120 次/窗口，每 family 1,200 次/窗口。窗口是进程内保护，
  restart 可清空；它不承担 durable correctness。
- **branch 口径**：同 `(family_id, entity_type, client_uuid)` 的所有 `status='open'` conflict 中，
  `conflict_branches` durable 行合计最多 64。空 branch-set 的 tombstone restore handle 计 0，
  resolved conflict 计 0；既有超限数据不删除也不隐藏。创建第 65 条前在同一个 SQLite Immediate
  transaction 内检查并拒绝。
- **typed 429**：principal/family/root 分别使用
  `causal_commit_principal_rate_limited`、`causal_commit_family_rate_limited`、
  `causal_open_branch_limit_reached`；body 为既有 `{code, detail}` error shape，`detail` 只含
  `scope=principal|family|root` 与 `retryable=true`，日志也只记录相同分类，不记录家庭内容。
  Retry-After/客户端退避仍由 hardening 15 所有。

## Acceptance

- [x] principal/family commit rate budget 对所有 causal roots 一致
- [x] 每 root 的 `limit-1/limit/limit+1` 行为确定，精确 replay 不增加 branch
- [x] 饱和返回稳定、可分类的 429/typed saturation；客户端消费留给 hardening 票
- [x] 已 durable branch 仍可被后续 detail/resolution 发现

## Validation

- [x] Store/API limiter、branch 边界、并发与 replay tests 通过
- [x] Rust format/test/clippy 与隔离服务 saturation smoke 通过

## Implementation evidence

- `causal_admission` 是唯一固定 owner：production 使用 60 秒、120/1,200 次与 64 branch；
  自定义阈值构造器仅在 `cfg(test)` 存在，未新增 env/config/capability/version/schema seam。
- Store 回归覆盖 device rotation、family/root-type 共享、精确 replay、mixed/rejected/oversize 计费、
  `t+60`、时钟回拨、restart，以及同 root 多个 open conflict 合计、空 tombstone handle、resolved
  handle、`63/64/65`、64 branch detail + resolution、resolution 后重新准入和并发不超配。
- 隔离 Axum/TempDir/真实 SQLite smoke：
  `cargo test --locked --test api causal_commit_http_ -- --nocapture`，2 passed；使用 production 固定
  120/64 阈值验证 typed 429、oversize 计费、exact replay 与 durable detail，无 NAS/外部服务。
- 最终 Rust gates（2026-08-10）：`cargo fmt --all -- --check`；`cargo test --locked`
  （234 unit、183 API、1 conflict-v2 fixture、2 TLS，全部通过）；
  `cargo clippy --all-targets --all-features --locked -- -D warnings` 通过。
- Standards review：Hard 0 / Judgement 0；Spec review：Hard 0 / Scope 0 / Judgement 0。

## Out of scope

ConflictSnapshot 分页、batch query、snapshot token 和 resolution retention 分别由外部票 17/18 负责。
