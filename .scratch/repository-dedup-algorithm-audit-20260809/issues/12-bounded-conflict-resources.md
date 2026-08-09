# 12 — 限制 causal commit 与 open branch admission

Status: ready-for-agent

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 04（已 implemented）；活动 blocker 为
[`causal hardening 01`](../../causal-sync-conflict-transport-hardening/issues/01-freeze-conflict-v2-contract.md)。

## What to build

为 causal commit 建立按 principal/family 的稳定准入预算，并为每个 root 建立 open branch 上限。
饱和时返回可识别的 429/typed 结果，保留所有已 durable branch 的可发现性，不静默丢事实。

## Implementation sequence

1. 在本票内冻结 limiter key、窗口、数值上限、branch 计数口径和 typed 饱和错误码。
2. 在创建新 durable branch 前原子检查上限；精确 replay 不重复占用预算。
3. 让 HTTP 与 Store 使用同一准入结果，并提供不含家庭内容的观测分类。
4. 用边界与并发测试证明上限不会覆盖或隐藏既有 branch。

## Acceptance

- [ ] principal/family commit rate budget 对所有 causal roots 一致
- [ ] 每 root 的 `limit-1/limit/limit+1` 行为确定，精确 replay 不增加 branch
- [ ] 饱和返回稳定、可分类的 429/typed saturation；客户端消费留给 hardening 票
- [ ] 已 durable branch 仍可被后续 detail/resolution 发现

## Validation

- [ ] Store/API limiter、branch 边界、并发与 replay tests 通过
- [ ] Rust format/test/clippy 与隔离服务 saturation smoke 通过

## Out of scope

ConflictSnapshot 分页、batch query、snapshot token 和 resolution retention 分别由外部票 17/18 负责。
