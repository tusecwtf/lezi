# 09 — 计时：状态 / ViewModel / Service / UI 分离

**Parent:** [../spec.md](../spec.md)

**What to build:** 在行为冻结下把计时拆成状态（含编解码）、ViewModel、前台计时服务、纯 UI 路由。完成草稿/完成半屏保持既有边界。不改变 02 已定的软删失败关闭语义。

**Blocked by:** 02 — 计时完成软删失败关闭

**Status:** complete

## Acceptance criteria

- [x] 状态、ViewModel、前台服务、UI 分界清晰；服务与 Compose 不再同文件捆绑
- [x] 完成路径仍遵守 02：软删冲突失败且不清空装成功
- [x] 既有计时恢复/序列化类测试全绿（路径可随搬家更新，语义不变）
- [x] 纯机械搬家：无新产品行为

## Comments

- R2 定稿。
