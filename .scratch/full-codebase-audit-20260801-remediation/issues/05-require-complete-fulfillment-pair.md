# 05 — completed 计划必须原子绑定完整 pair

**What to build:** 服务端只接受带完整 `fulfilled_record_client_uuid` + `fulfilled_at` 的 completed CarePlan，并让该 pair 从首次 completed 写入起不可变。

**Source:** `AUDIT-20260801-P1-05`  
**Blocked by:** 04 — serialize the fulfillment-evidence server seam  
**Status:** blocked
**Size:** S–M

## Acceptance criteria

- [ ] `status=completed` 且两字段任一为空/缺失时在 model/API 边界拒绝，不能先 completed 后补绑。
- [ ] 两字段必须同时为空或同时非空；非 completed 状态携带 fulfillment pair 的合法性按 current contract 明确并 fail closed。
- [ ] 从 pending/missed 到 completed 的单个 atomic root 同时写完整 pair，并验证 record 存在、同家庭、同宝宝。
- [ ] 已持久化 completed pair 的 record UUID、fulfilled_at、清空与 partial rewrite 全部拒绝；精确 replay 幂等。
- [ ] stage 与 commit 间的并发 completed/rebind 仍由 commit 时冻结检查拦截。
- [ ] Android 正常管理者履行与 candidate 仲裁路径继续生成 current schema 可接受的完整 pair。

## Validation

运行 server model/store/API tests、Rust fmt/test/clippy，以及 Android fulfillment wire tests；live wire 需要时按维护窗流程提议 CD。

## Documentation Gate

更新 CarePlan wire 表，写明 completed 与 pair 的双向不变量及首次完成即冻结。

## Out of scope

不改变谁有权管理 CarePlan，也不重新设计 candidate winner 规则。
