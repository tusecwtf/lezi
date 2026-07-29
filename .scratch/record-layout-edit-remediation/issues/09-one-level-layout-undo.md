# 09 — 高风险布局操作一层撤销

**What to build:** 在成功清空快捷槽或将项目移入本机已删除后提供一次短时撤销机会；撤销恢复该操作前的完整设备布局快照，而不是按字段猜测反向操作。

**Blocked by:** 05 — 有界、可读的本机已删除分区

**Status:** complete

## Acceptance criteria

- [x] 只有 `ClearSlot` 与 `MoveToLocalDeleted` 成功耐久后显示一层撤销；指派、换位、恢复、项目排序和类别排序不显示撤销提示。
- [x] 撤销通过 Ticket 02 的同一原子 writer 恢复操作前完整 `DeviceLayoutSnapshot`，包括精确四槽、隐藏集合、类内序、类别序和布局版本。
- [x] 同时只保留最近一次可撤销操作；任何后续布局 intent 会使旧撤销失效，旧快照不得覆盖更新布局。
- [x] 撤销成功后 UI、日常 Dock、更多目录和本机已删除立即一致；不会自动补槽、改写历史 Record 或影响家庭共享定义。
- [x] 原操作写入失败时不显示虚假撤销；撤销写入失败时保留当前有效快照并提供可处置的重试/失败反馈，不播报成功。
- [x] 退出编辑器会关闭当前撤销提示；重新进入或冷启动不恢复过期撤销，但已成功撤销的完整快照正常恢复。
- [x] 提示可由 TalkBack 与键盘操作，倒计时结束、替换、退出和失败状态都有真实语义，不只依赖颜色。
- [x] 并发与故障测试覆盖连续两次高风险操作、后续非撤销操作、原写失败、撤销写失败、超时和退出。

## Validation

- 运行布局 reducer、原子快照 writer、ViewModel 事件与撤销状态机定向测试。
- 运行 `:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、相关 Compose 测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 设备 smoke“清空→撤销”“隐藏占槽项目→撤销”“隐藏→排序→旧撤销不可用”并核对重进后的完整快照。
- 运行 `git diff --check`。

## Documentation Gate

在 UI PRD 与布局持久化说明中明确一层撤销只覆盖清空和本机隐藏、恢复完整快照、后续 intent 使旧撤销失效，以及它不构成多级布局历史。

## Completion receipts

- 原子 writer 返回绑定其规范化完整快照的独立完成回执；撤销状态机只在原操作 exact 回执成功且 UI 仍等于 `after` 时发布短时 token。
- Material 3 原生 Snackbar 提供“撤销”操作和关闭语义；有效提示期间 Ctrl+Z 交给同一 `onUndo(token)`，超时、替换或退出后不拦截。
- 撤销回写失败保留 `after` 并显示“撤销未完成”及同 token 重试；只在完整 `before` 回执成功后更新 UI 和播报“布局已撤销”。
- JVM/writer/reducer、AndroidTest 编译、lint、Debug APK、API 35 四项 connected 测试与真实 app 四步 smoke 均通过；完整命令、结果和验证边界见 [`../evidence/09/validation.md`](../evidence/09/validation.md)。
