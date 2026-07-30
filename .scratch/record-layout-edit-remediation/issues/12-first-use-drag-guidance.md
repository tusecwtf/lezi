# 12 — 首次拖放引导与可重访帮助

**What to build:** 首次进入布局编辑时显示一条不遮挡操作区的可关闭短提示，帮助用户理解长按、常用槽和清空行为；成功拖放或主动关闭后不再自动出现，但顶栏帮助入口始终可以重新查看。

**Blocked by:** 06 — 无障碍布局动作与真实空槽文案

**Status:** complete

## Acceptance criteria

- [x] 首次进入布局编辑自动显示短提示“长按卡片拖到常用槽；拖出槽位可清空。”，不使用长教程、模态弹窗或覆盖目录/本机已删除/Dock。
- [x] 提示可以主动关闭；第一次成功产生并耐久提交拖放布局 intent 后也自动关闭并记录已完成，no-op、取消或写入失败不算成功。
- [x] 完成标记仅保存在本机，不进入家庭同步；后续进入不再自动显示，也不因自定义项目、槽位或主题变化被重置。
- [x] 编辑顶栏保留可发现的帮助入口，随时可以再次显示同一权威提示；手动重访不会把首次完成标记改回未完成。
- [x] 提示与帮助入口具有 TalkBack 名称、焦点顺序和关闭动作；放大字体、小屏、warm/journal、浅色/深色下不遮挡“完成”或固定 Dock。
- [x] 提示内容与实际行为一致：短按不写 Record，“更多”不是投放槽，拖出清空只适用于已绑定槽；不得引导到已退役设置入口。
- [x] 配置重建期间提示的当前显示状态保持；真正冷启动按本机完成标记决定是否自动出现。
- [x] 状态与 Compose 测试覆盖首次进入、主动关闭、成功拖放、no-op/失败、再次进入、帮助重访、配置重建和本机偏好持久化。

## Validation

- 运行首次帮助状态、本机偏好、拖放成功事件、无障碍语义和 Compose 展示定向测试。
- 运行 `:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、相关 connected/Compose 测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 在小屏与放大字体下执行“首次进入→no-op→仍显示→成功拖放→重进不显示→帮助重访”，并核对 TalkBack。
- 运行 `git diff --check`。

## Documentation Gate

在 UI PRD 与布局设计中记录一次性提示文案、完成条件、本机持久化和帮助重访入口；删除任何声称空槽短按打开设置或短按直接写 Record 的教学文案。

## Implementation and validation checkpoint

- `SettingsLocal.layoutDragGuidanceCompleted` 是独立于 `DeviceLayoutSnapshot` 的单调设备偏好；DataStore 缺键为 false，标记写入幂等且跨进程重建、主题与布局写入保持。
- retained `LayoutEditSession` 保存 `Auto / Manual / Hidden` 当前可见性；顶栏帮助和内联提示共用同一 reducer，配置重建不重置，冷启动按耐久标记重新初始化。
- `LayoutEditCanvas` 将触摸拖放与替代输入分流；只有 changed touch drag 的布局 receipt 成功后才请求完成标记，标记也成功后才自动隐藏。主动关闭立即隐藏 session，并异步耐久完成；失败不伪装成功。
- 定向状态、DataStore、Compose、API 35、小屏 1.5× 字体、lint 与 Debug 组装证据见 `evidence/12/validation.md`。
