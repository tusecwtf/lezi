# 04 — 冻结履行候选业务证据

**What to build:** FulfillmentCandidate 首次由服务端接受后，把 plan、record、actual time 与提交者/确认时间作为一个不可变证据；后续只允许精确幂等 replay，不允许任何成员拼接改写。

**Source:** `AUDIT-20260801-P1-04`  
**Blocked by:** None — can start immediately  
**Status:** ready-for-agent  
**Size:** M

## Acceptance criteria

- [ ] 首次写入继续由认证 principal 覆盖 `submitter_membership_id`、role、`confirmed_at`。
- [ ] 同一 candidate UUID 后续改变 `care_plan_client_uuid`、`record_client_uuid` 或 `actual_timestamp` 时 fail closed；Owner 也不能改写历史证据。
- [ ] 原提交者、其它 active Member 与 Owner 的精确 replay 均幂等成功且不改变持久化内容/revision。
- [ ] candidate tombstone/resurrection 策略明确并测试，不能绕过字段冻结。
- [ ] 关联 plan/record 必须仍同家庭、同宝宝；ACL 错误不泄露其它家庭是否存在某 UUID。
- [ ] stage 与 commit 都重新执行冻结/ACL，角色变化或并发写不能利用 staged package 绕过。
- [ ] API 回归证明攻击者不能留下“原提交者戳 + 他人改写业务字段”的混合证据。

## Validation

在 `tools/lezi-sync` 运行 fmt、`cargo test --locked`、Clippy `-D warnings`；按 AGENTS.md 在需要 live wire 证明时先提议 NAS CD。

## Documentation Gate

同步 `CONTEXT.md`/data-model 中 FulfillmentCandidate 的不可变证据定义（若现有文字不足）。

## Out of scope

不改变 winner 仲裁次序，不把冲突未采纳事实加入正常统计。
