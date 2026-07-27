# 23 — 护理计划、履行候选与服务端 ACL 契约

**What to build:** 在 NAS 端加法建立 CarePlan、计划照片和履行候选的权威同步契约，并从第一版就执行作者/管理员权限和不可变提交者证据。

**Blocked by:** 03 — 服务器认证的稳定家庭成员身份; 08 — 自定义项目家庭同步与服务端 ACL; 09 — NAS 原子同步包协议扩展; 13 — 计划状态、日期分组与管理操作.

**Status:** done

- [x] 服务端接受并验证 CarePlan、计划媒体、履行候选及关联所需的版本化 wire 表示
- [x] 创建计划时从认证 principal 固定创建 membership；客户端不能提交他人身份或管理员角色
- [x] 任意成员可创建计划；普通成员只能修改、跳过或删除自己创建的计划，管理员可管理全部
- [x] 创建者离开后管理员仍可管理；普通成员不会因作者离开获得权限
- [x] 任意活动成员可提交任意计划的履行候选，服务端固定提交 membership、当时认证角色和不可编辑确认时间
- [x] CarePlan/媒体引用、宝宝、具体项目和家庭必须一致；跨家庭引用以原子错误拒绝
- [x] tombstone、幂等请求、分页、游标和旧客户端未知类型处理有明确兼容契约
- [x] 服务端模型/Store/HTTP 测试覆盖 ACL、伪造拒绝、作者离开、关联完整性和原子失败

Notes (residual fix 2026-07-26):
- API: care_plan_creator_leave_admin_still_manages_member_does_not + care_plan_media_integrity_rejects_bad_refs_and_baby_mismatch.
- FIX re-verified: cargo test --test api care_plan (5) + fulfillment (1) all green; no further gaps.

Notes (Release revalidation 2026-07-27):
- Fresh-family device smoke exposed the atomic-stage reference contract directly. Store now rejects a CarePlan whose `custom_item_client_uuid` is missing or belongs only to another family; the failed stage leaves no bundle row.
- API regressions `atomic_bundles_wait_for_baby_without_leaving_staging_rows` and `atomic_care_plan_waits_for_its_custom_item_definition` cover Record/CarePlan Baby prerequisites, CustomItem family isolation, no partial staging, and successful retry after prerequisites publish.
