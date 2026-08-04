# 32 — 退出设备/离开/删成员/删家庭（G10）

**What to build:** 角色正确的退出设备、离开家庭、删除成员、删除家庭路径；仅显式 wipe 原因清空本地家庭数据（G10）。

**Blocked by:** 20 — 成员申请加入与拉历史（G4）.

**Status:** ready-for-agent

- [ ] 退出本设备：服务器确认后清本地家庭数据，membership 可仍存在
- [ ] 离开/删成员：事实保留作者「家人」
- [ ] 删家庭：Owner 确认（含根密码意图）后服务器家庭数据清除
- [ ] 仅 device_removed/membership_deleted/family_deleted 等显式原因清本地家庭数据
- [ ] 普通 401 不误清空本地
- [ ] G10 可观察验收
