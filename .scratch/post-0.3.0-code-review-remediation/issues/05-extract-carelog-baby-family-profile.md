# 05 — 抽离 CareLog 宝宝与家庭档案 seam

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-02`
**Severity:** P1 structural
**Status:** planned
**Blocked by:** 04
**Size:** L

## What to build

把宝宝创建/修改/删除、当前宝宝、本机主题/排序、家庭 scaffold、孤宝宝 reconcile 与宝宝合并
从 `CareLog` 抽到 `BabyFamilyProfileCoordinator`（或等价 seam）。`CareLog` 继续提供现有 facade；
家庭 owner/member 权限、权威 Baby 与本机偏好边界不变。

## Acceptance criteria

- [ ] 宝宝/家庭档案写入只存在于新 coordinator，不保留 CareLog 平行算法。
- [ ] merge/reconcile 的 Record、CarePlan、媒体、提醒与 UUID 迁移保持同一事务/顺序。
- [ ] owner/member、重复昵称、当前宝宝与本机 theme/sort 行为等价。
- [ ] public API 和 DI 调用者无需迁移到 DAO；CareLog facade 保持稳定。
- [ ] 记录 CareLog 前后行数并锁住职责不回流。

## Validation

运行 baby/profile/merge/reclaim/full-pull 相关 domain、family、onboarding 与 sync 测试，相关 lint
及 `:app:assembleDebug`。结构搬迁不得用 mock happy path替代真实 transaction fixture。

## Documentation gate

保持 NAS 权威 Baby、本机布局偏好和 membership 边界；行为变化必须另开票。
