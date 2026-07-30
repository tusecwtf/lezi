# 18 — 记录页分钟级时钟刷新

**What to build:** 为记录页建立生命周期感知的分钟时钟，使“现在”、相对时间、计划到期与逾期状态在页面停留期间持续准确更新。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** S–M

## Acceptance criteria

- [x] 页面处于可见活跃状态时，所有依赖当前时间的标签在分钟边界自动重算，无需数据流发生其他变化。
- [x] 页面离开前台或销毁后停止计时工作，恢复可见时立即用当前时刻刷新。
- [x] “刚刚”、分钟/小时相对时间、计划到期、逾期和日期跨日切换使用同一注入时钟。
- [x] ticker 对齐实际分钟边界，不采用持续高频轮询，也不会因重组启动多个并发循环。
- [x] 系统时钟或时区变化后，下一次恢复/刷新使用新环境且不显示负的伪相对时间。
- [x] 使用 fake clock 的确定性测试覆盖分钟边界、午夜、暂停/恢复和取消。

## Validation

运行记录页 ViewModel/Compose 时间测试，以及应用编译和静态检查；设备停留跨分钟 smoke。

## Documentation Gate

若相对时间显示阈值发生变化，同步更新产品文案规则。

## Evidence

[分钟时钟实现、TDD 与 API 35 跨分钟页面验收](../evidence/18/minute-driven-record-screen-clock.md)
