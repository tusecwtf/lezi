# 02 — 冻结已完成计划的 fulfillment 绑定

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-01`
**Severity:** P1
**Status:** ready-for-agent
**Blocked by:** none
**Size:** M

## What to build

在 Rust store 的 CarePlan 更新授权中冻结已完成计划的履行绑定。一旦持久化计划已有非空
`fulfilled_record_client_uuid` 与 `fulfilled_at`，后续更新只能精确保留两者；禁止清空、改绑
到其他 Record 或改变履行时间。这样 tombstone 自定义定义的“一次合法履行事实”不能被计划
改绑扩展成任意新事实旁路。

## Acceptance criteria

- [ ] RED API 回归复现：completed plan → fulfillment R1 → rebind R2 → publish R2。
- [ ] 已绑定计划改 `fulfilled_record_client_uuid`、清空该字段或改变 `fulfilled_at` 均返回稳定 4xx，且原计划/Record 不变。
- [ ] 首次从 pending/open 到 completed + R1 仍合法；R1 的精确 replay 仍幂等。
- [ ] tombstone 定义下的历史同 UUID 编辑与第一次显式 fulfillment 继续合法。
- [ ] 纯新 Record、纯新 CarePlan 及 rebind R2 继续被拒，不能借同 bundle 排序绕过。
- [ ] owner 与 creator 使用相同冻结规则；ACL 权限不能绕过不可变绑定。
- [ ] 错误映射稳定为 `409` 或 `422`，不泄露 token、family 或 payload 内容。

## Primary seams

- `tools/lezi-sync/src/store.rs`
- `tools/lezi-sync/tests/api.rs`
- 必要时 `tools/lezi-sync/src/error.rs` 或现有 HTTP 错误映射

## Validation

- Store 单测覆盖字段冻结、精确 replay 和事务回滚。
- API 测试覆盖合法 R1、非法 R2、清空、改时间、owner/creator 两种权限。
- 运行 `cargo fmt --all -- --check`、`cargo clippy --all-targets --all-features -- -D warnings`、
  `cargo test --locked`。
- 运行 fresh temporary data-root current-wire smoke，确认 `/health`、首次 fulfillment 与失败后 pull 真相。

## Documentation gate

更新 sync/data-model 契约，明确 completed CarePlan 的 fulfillment Record UUID 与确认时间一经
持久化不可变；这属于 current-wire 约束收紧，不引入旧协议兼容路径。

## Out of scope

- 改写 fulfillment winner 仲裁、Record ACL 或 membership 身份模型。
- 物理 NAS 部署。
