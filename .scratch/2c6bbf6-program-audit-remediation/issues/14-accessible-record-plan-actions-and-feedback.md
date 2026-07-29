# 14 — 无障碍记录/计划管理与结果反馈

**What to build:** 为 Record 与 CarePlan 的编辑、删除、跳过等管理动作提供不依赖滑动手势的语义入口，并对忙碌、成功和失败给出真实反馈。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] 每个用户有权管理的 Record/CarePlan 都暴露可聚焦、可朗读的编辑/删除/跳过动作；无权限动作不伪装成可用。
- [ ] 原有滑动快捷动作可保留，但与语义入口共享确认、授权和执行状态，不存在行为分叉。
- [ ] 破坏性动作在必要时确认，确认内容明确目标及不可逆结果。
- [ ] 跳过计划和删除操作在执行中禁用重复提交，并在成功后只更新一次界面。
- [ ] 数据层返回 false、冲突或异常时不静默关闭；用户看到可操作错误且原条目保持正确状态。
- [ ] 反馈不仅依赖颜色或瞬时动画，TalkBack 能获知忙碌、成功或失败。
- [ ] Compose/状态测试覆盖触摸、语义动作、权限差异、重复点击和错误结果等价性。

## Validation

运行 Record/CarePlan 管理、权限与 Compose 语义测试，完成应用编译、静态检查和 TalkBack smoke。

## Documentation Gate

在交互规格中列出手势之外的等价管理入口和结果反馈。
