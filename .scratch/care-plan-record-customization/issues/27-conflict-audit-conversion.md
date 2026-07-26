# 27 — 管理员冲突审计与转独立记录

**What to build:** 为管理员提供冲突未采纳履行的可审计详情，并在确认两次护理确实都发生时把落选内容转换为一条新的独立护理记录。

**Blocked by:** 04 — 所有记录类型的通用三张照片; 26 — 并发履行的确定性权威裁决.

**Status:** done

- [x] 只有管理员可从计划同步历史进入冲突未采纳详情；普通成员不能通过直接路由执行管理操作
- [x] 详情展示提交者、确认时间、实际发生时间、记录类型、文字、照片和未采纳原因
- [x] 照片支持与普通记录一致的本机预览，不通过系统日历或公开链接暴露
- [x] “转为独立记录”需要明确确认，并创建新的 Record 身份而非把落选候选改为第二权威履行
- [x] 新 Record 进入普通时间轴/汇总/搜索；原计划的权威履行关系和冲突审计保持不变
- [x] 转换事务或同步失败可幂等重试，不产生两个独立 Record
- [x] 管理员权限在服务端再次校验，客户端按钮隐藏不是唯一保护
- [x] 领域、权限和 UI 测试覆盖审计隔离、照片、转换成功/失败及重复请求

Notes (care-w15):
- Domain: `listConflictNotAdoptedAudits` / `getConflictNotAdoptedAudit` / `convertConflictNotAdoptedToIndependentRecord` admin-gated; non-admin empty/null + `ConflictAuditPermissionException`.
- Convert creates new Record clientUuid + new media ownership; never flips `adoptionStatus` or `CarePlan.fulfilledRecordClientUuid`.
- Room v17 `convertedRecordClientUuid` on `fulfillment_candidates` (local-only idempotency pointer; preserved across sync apply).
- Calendar: completed plans with conflicts open admin audit list/detail + confirm convert; in-app photo pager preview.
- `FulfillmentAuthority.notAdoptedReason` pure copy for audit detail.
- Docs: data-model.md + sync-home-lan.md note local convert pointer / multi-device double-convert acceptance.
- Tests: FulfillmentAuthorityTest reason; CareLog conflictAuditListAndConvertAreAdminOnlyAndIdempotent; ConflictAuditEntryPolicyTest; migration 16→17 (connected).
