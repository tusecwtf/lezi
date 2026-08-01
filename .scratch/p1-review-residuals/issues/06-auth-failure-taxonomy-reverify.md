# 06 — 鉴权失败与 leave 401 分类（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认 0.3.1 之后，客户端能区分：会话过期/需重新申请、设备被撤销、成员被删除、家庭被删除、以及普通网络/鉴权错误；**leave/delete 不得再把「任意 HTTP 401」一律当成「会话已在服务端不存在」而本地清凭证成功**。普通 401 不得误清空未同步的本地家庭数据。

**Blocked by:** 0.3.1 发布（trusted-sync 会话轮换、撤销与错误码合同）

**Status:** planned

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- 明确错误码/结果（如 device_removed、membership 删除、family 删除）与 generic 401 分流。
- 普通 invalid refresh：清凭证、保留本地数据并引导重新申请（非「已删除」）。
- leave/delete：仅在服务端确认或显式「已不存在」语义下完成本地退出。

## 0.3.0 残差（复核对照）

- leave/delete 将任意 401 视为 session gone。
- 日常 sync 401 仅 Error，无 re-auth / 分类 wipe。

## Re-verify checklist（0.3.1 后执行）

- [ ] 错误分类表与客户端分支是否存在且有测试。
- [ ] leave 在「凭证已废但 membership 仍在」时是否仍错误地报告成功离开。
- [ ] generic 401 是否误 wipe Room。
- [ ] **若已满足：** `Status: cancelled` + 证据。  
- [ ] **若仍不满足：** 升为 `ready-for-agent` 再实现。
