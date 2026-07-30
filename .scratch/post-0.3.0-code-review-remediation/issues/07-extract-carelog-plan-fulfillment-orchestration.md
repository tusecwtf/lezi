# 07 — 抽离 CareLog 计划与履行协调 seam

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-02`
**Severity:** P1 structural
**Status:** ready-for-agent
**Blocked by:** 01、06
**Activation:** satisfied — 01 and 06 complete; user requested all scratch issues
**Size:** L

## What to build

把 CarePlan create/next-feed schedule/fulfill/update/skip/delete、candidate 仲裁及 reminder/calendar
协调迁到 `CarePlanCoordinator`（或等价 seam）。继续复用 `FulfillmentSurface`、
`CarePlanReminderProjection`、权限 helper 和照片 reconciler；CareLog 保留稳定 facade。

## Acceptance criteria

- [ ] 01 的 next-feed reconciliation seam 原样迁移，不重新引入 UI/DB 真相窗口。
- [ ] 计划 CRUD、履行唯一事实、冲突未采纳审计与 creator/owner 权限逐项等价。
- [ ] reminder/calendar 仍为设备本地投影，Provider 失败不阻塞家庭事实。
- [ ] 照片原子包、candidate 顺序与 requestLocalSync/cleanup 顺序不变。
- [ ] 四个 CareLog 结构票完成后主文件低于 1,000 行；不得以 delegate 堆叠或未迁移的双实现伪造达标。

## Validation

运行 domain CarePlan/fulfillment/conflict/reminder/calendar 全量测试、feature/log/timer/settings 调用回归、
sync current-wire 测试、相关 lint 与 `:app:assembleDebug`。

## Documentation gate

保持计划/事实、履行仲裁、系统日历单向投影和家庭同步契约。结构票不修改产品决策。
