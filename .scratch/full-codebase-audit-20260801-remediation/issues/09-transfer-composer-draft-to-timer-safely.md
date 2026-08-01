# 09 — Composer→Timer 安全转移草稿

**What to build:** 用显式 timer seed/ownership handoff 代替“先 close Composer 再传四个标量”；可转移字段与照片在 Timer 接管前不得被 Composer cleanup，不能转移的脏字段须先明确确认。

**Source:** `AUDIT-20260801-P1-09`  
**Blocked by:** 08 — timer fulfillment photo ownership must be defined  
**Status:** ready-for-agent  
**Size:** M

## Acceptance criteria

- [ ] handoff 至少携带 baby、carePlan、note、amount、照片顺序及每张照片的 borrowed/Composer-owned 所有权；不再只传四个松散参数。
- [ ] Timer 确认接管 seed 后 Composer 才关闭；导航失败或接管失败保持原草稿与照片可编辑。
- [ ] Composer-owned import 在 Timer 完成后归 Record，在 Timer 显式丢弃后回收；CarePlan borrowed photo 永不由草稿/Timer 丢弃路径物理删除。
- [ ] 已输入但无法映射到 Timer 的 manual duration/order/time 等字段触发明确确认，不得静默丢弃。
- [ ] handoff 跨配置/进程重建恢复同一 seed，不能重复接管、重复删除或生成两次完成记录。
- [ ] Timer 完成把 seed photos 与 Ticket 08 的当前计划照片去重并限制 0–3 张；超限在离开 Composer 前给出可操作提示。
- [ ] 回归覆盖新 nursing、履行 nursing、含导入照片、导航取消/失败、Timer 丢弃和完成。

## Validation

运行 feature:log/feature:timer/app 导航与照片生命周期测试、`:app:assembleDebug`、`lintDebug`；设备旋转 smoke 交接、丢弃与完成。

## Documentation Gate

在 UI/技术文档写明 Composer→Timer 是草稿所有权转移，不是隐式放弃。

## Out of scope

不让 EditRecord、EditPlan 或 future ScheduleCare 进入 Timer。
