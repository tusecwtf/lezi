# 25 — 跨成员履行的原子共享闭环

**What to build:** 让任意家庭成员都能履行任意成员创建的计划，并把履行候选、实际护理记录、照片和计划关联作为一个不会部分可见的共享结果。

**Blocked by:** 11 — 记录照片编辑、删除与恢复; 12 — 内建项目本地护理计划 tracer; 24 — 护理计划原子家庭同步与本机投影.

**Status:** done

- [x] 任意活动成员可打开和履行任意家庭 CarePlan，不受计划作者限制
- [x] 一次履行产生稳定候选 UUID、不可编辑确认时间、实际 Record、最终 Record 照片和计划关联
- [x] 出站结果原子发布；接收端不会先看到 completed 计划却没有 Record，也不会先看到 Record 而计划仍 pending
- [x] 完整应用后才从普通待履行区移除计划，并取消当前设备提醒/系统日历副本
- [x] 上传或应用失败时创建者本机结果可恢复，其他设备继续显示旧 pending 完整版本
- [x] 同一设备重试同一履行使用稳定候选身份，不产生重复 Record
- [x] 普通成员履行他人计划不会获得修改、跳过或删除该计划的管理权限
- [x] 双成员集成测试覆盖无照片/带照片履行、响应丢失、重复重试和原子可见性

Notes (care-w13):
- Room v15 `fulfillment_candidates` table + `FulfillmentCandidateDao` / domain model.
- `CareLog.fulfillCarePlan` + `completeNursing`/`completeOpenCarePlanWithRecord` write durable candidate (stable UUID, frozen confirmedAt) with linked Record/completed plan; manage ACL unchanged.
- RealSyncPort unit: completed care_plan atomic → record atomic → fulfillment_candidate residual (ticket 25: no Record-while-plan-pending NAS window); receive co-gates completed plan on fulfilled record presence and candidates on plan+record; same-page apply is one txn; projection hook cancels reminders on completed apply.
- Tests: CareLog cross-member fulfill + stable retry; RealSyncPort fulfill unit push/pull/gate/retry; SyncWireMapper; migration 14→15; cargo api fulfillment.

FIX (care-w13): Re-verified all acceptance boxes against code. Focused tests green (CareLog foreign fulfill / stable candidate / nursing plan complete; RealSyncPort fulfillUnit+fulfillReceive; SyncWireMapper; cargo fulfillment). Polish only: clarify RealSyncPort outbox vs atomic commit-order comment; rename fulfillUnit test to match plan→record→candidate commit order. No remaining gaps; Status stays done.
