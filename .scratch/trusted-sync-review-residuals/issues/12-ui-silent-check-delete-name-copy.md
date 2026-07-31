# 12 — 检查结果静默、删家空名、相关文案

**What to build:** 修复合约/可用性边角：（1）成员「检查结果」网络失败须有可见文案且
不丢 pending；（2）删除家庭在 `familyName` 缓存为空时不得出现永远不可点的终态，须引导
同步或刷新名称；（3）家庭重命名「留空」文案与「必填」校验一致；（4）可选：浅同步状态
文案向 PRD 单行结果靠拢。

**Blocked by:** None.

**Status:** complete

**Severity:** Medium–Low
**Blocks release:** no
**Review ID:** F-12

## Must

- [x] `checkMemberApproval` 失败 → 用户可见可重试文案，pending 保留。
- [x] 删家 UI：`familyName` 空时阻断原因明确（例如先同步）或刷新后可确认；无死按钮。
- [x] 重命名家庭 placeholder/校验一致（禁止「留空则兜底」却 reject empty）。
- [x] 测试或语义测试覆盖失败检查文案；删家空名路径。

## Should

- [ ] 账户浅状态与 PRD `已同步 · …` / `待同步 N 项` 对齐（若工期紧可拆 follow-up）。

## Evidence paths

- `domain/FamilyWizardController.kt` checkMemberApproval
- `feature/family/FamilyUiPolicy.kt` canConfirmFamilyDeletion
- `feature/family/FamilyDialogs.kt` rename / waiting copy
- `docs/prd/sync-trusted-endpoint.md` / UI design shallow status

## Comments

- 不阻塞 0.3.1 最低集；删家空名为 Owner 运维边角。
- 2026-07-31：四项 Must 已完成。浅状态 Should 保留未勾选；last-sync time / pending
  outbox count 需要新增投影合同，不在本轮安全与正确性残差收口内。
