# 07 — 离开/删家本机擦除与跨家庭隔离（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认 0.3.1 之后：用户主动退出当前设备或管理员删除家庭后，本机按产品合同清理家庭副本、Outbox、媒体与会话；**不会**把上一家庭的护理事实/照片在加入下一家庭时重新发布到新家庭。产品文案与真实擦除行为一致。

**Blocked by:** 0.3.1 发布（trusted-sync 退出/删家/硬删成员合同）

**Status:** planned

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- 主动退出当前设备：服务端确认后清 Room、Outbox、media、endpoint、session。
- 删除家庭：全设备安全退出并清本地家庭数据。
- 跨家庭：新家庭不得继承旧家庭 outbox 实体当「自己的待发布」。

## 0.3.0 残差（复核对照）

- clearSession 主要清 sync 配置与 outbox 索引，护理副本可残留。
- 存在「切家庭后 requeue 再发布」类测试编码的行为，与目标隐私合同冲突。

## Re-verify checklist（0.3.1 后执行）

- [ ] 退出/删家后本机是否仍可读到原家庭护理记录与照片。
- [ ] 退出后再加入/新建另一家庭，是否出现旧家庭事实被 push。
- [ ] UI 文案是否仍暗示「已删光」却未擦除（或相反）。
- [ ] **若已满足：** `Status: cancelled` + 证据。  
- [ ] **若仍不满足：** 升为 `ready-for-agent` 再实现。
