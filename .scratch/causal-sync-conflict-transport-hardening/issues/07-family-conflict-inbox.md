# 07 — 建立家庭冲突 inbox 与 badge

**What to build:** 在家庭页同步状态行显示 open-conflict badge/入口，并列出 Baby、Record、CarePlan、CustomItem、WakeObservation 五类根及其 tombstone 状态。

**Blocked by:** 06

**Status:** ready-for-agent

## Contract slice

Badge 计数单位是 open root conflict；列表按 server receipt/update time 降序，再以稳定 conflict ID 打破平局。家庭与 Record 入口进入同一 shared resolver route。

## Implementation sequence

1. 建立跨根 summary projection、count 与稳定排序。
2. 接入家庭同步状态行 badge 和 inbox route。
3. 映射五类根标签、宝宝、actor fallback、media/deleted 摘要。
4. 将列表项与 Record 上下文入口路由到 shared resolver。

## Acceptance

- [ ] 五类根/tombstone 均可发现且 count 不重复
- [ ] 排序在相同时间下仍稳定
- [ ] actor 名称缺失显示稳定 ID
- [ ] 无权限用户可审阅但 resolver submit disabled

## Validation

- [ ] projection/count/sort/navigation/Compose tests 通过
- [ ] 相关 connected test 留至票 42

## Out of scope

不实现多页 snapshot 或改变家庭成员模型。
