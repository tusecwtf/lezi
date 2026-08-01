# 20 — 布局 undo 跨配置重建

**What to build:** 把 layout undo candidate、写入阶段、token/expiry 与失败状态移入 LogViewModel 的可观察会话状态；旋转不再把已提供的撤销机会重置为 Idle。

**Source:** `AUDIT-20260801-P2-06`  
**Blocked by:** None — can start immediately  
**Status:** ready-for-agent  
**Size:** S–M

## Acceptance criteria

- [ ] clear slot / local delete 成功出现 undo 后旋转，剩余有效期内仍可撤销同一 before snapshot。
- [ ] 原始写或 undo 写正在进行时旋转，新 composition 继续显示正确 busy/result，不依赖旧 scope callback。
- [ ] token、candidate、current snapshot 与 durable write receipt 校验保持现有 reducer 不变量；陈旧 completion 不改变新状态。
- [ ] expiry 使用稳定 deadline 而不是重建后重新计满；到期后不恢复陈旧 undo。
- [ ] 退出 editor 仍显式丢弃 available/failed undo；进程死亡若不承诺恢复则安全归零且不逆写布局。
- [ ] restore failed 的重试跨旋转可用，失败提示和无障碍 announcement 不重复/丢失。
- [ ] `LogScreen` 不再用 composition-local `remember` 作为 undo 真相源。

## Validation

运行 LayoutUndo reducer/ViewModel/Compose recreation tests、`:app:assembleDebug`、`lintDebug`，并设备旋转 smoke。

## Documentation Gate

若改变布局编辑会话生命周期，更新 UI 规格的 undo 时限与退出语义。

## Out of scope

不扩展为多级 undo，不改变四槽 + More 布局合同。
