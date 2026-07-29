# 05 — 有界、可读的本机已删除分区

**What to build:** 把“本机已删除”建成与编辑目录连续、容量有界且语义可读的分区；即使全部项目被隐藏，小屏和字体放大下仍能访问所有项目并保持 Dock 可见。

**Blocked by:**
- 02 — 原子持久化 DeviceLayoutSnapshot
- 03 — 当前可见且互斥的拖放目标

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 分区有可读标题、当前状态和恢复含义；空态不只显示无法解释的图形，读屏能识别这是本机可逆隐藏区。
- [ ] 静止态使用与“添加记录”连续的中性表面；只有合法拖动悬停时才通过危险色、边框及非颜色反馈共同强调。
- [ ] 0、1 和全部记录项目进入分区时布局均有明确上限和滚动策略；分区不会无限长高、挤出或覆盖底部 Dock。
- [ ] 小屏、横向空间紧张和系统字体放大时，标题、项目和 Dock 都可达，主操作不裁切。
- [ ] 目录/槽项目拖入分区会本机隐藏该项目并清空所有指向它的槽；不删除历史 Record、家庭自定义定义或远端数据。
- [ ] 已删除项目拖出分区会恢复到所属类别当前可见末尾，不自动回填任何常用槽；释放在分区内为 no-op。
- [ ] 删除与恢复只命中 Ticket 03 注册的当前边界；项目移入/移出后旧边界被注销，反馈和结果保持一致。
- [ ] 删除、清槽、恢复和顺序变化通过 Ticket 02 的一个完整快照提交；完成/返回/重启后结果一致。
- [ ] 逻辑和 Compose 测试覆盖空态、全量隐藏、已占槽项目隐藏、恢复类末尾、不自动回槽、小屏/字体放大和重启。

## Validation

- 运行本机已删除策略、四槽不变量、快照持久化和 Compose 布局测试。
- 运行 `:core:ui:testDebugUnitTest`、`:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、相关 connected/Compose 测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 在小屏模拟器以默认和放大字体 smoke“全部隐藏 → 滚动浏览 → 恢复末项”，确认 Dock 全程可达。
- 运行 `git diff --check`。

## Documentation Gate

更新产品术语和 UI PRD：本机已删除是设备本地、可逆的入口隐藏，不是事实删除或家庭自定义定义 tombstone；记录其容量和恢复语义。
