# 01 — 护理记录 membership ACL（变更 + 时间轴能力）

**What to build:** 让家庭成员只能编辑、删除或「转为护理计划」**自己创建**的护理记录；家庭管理员可管理全部。时间轴上的编辑/删除入口与 domain 拒绝规则同源，不再对每条记录恒为可改可删。未加入家庭时，本机记录仍可按既有 creator-or-owner 规则管理（双空 membership 视为可管）。

**Blocked by:** None — can start immediately.

**Status:** done

**Severity:** P1  
**Lane:** immediate

- [x] 护理记录的更新、删除与「转为护理计划」在 domain 入口按 creator-or-owner（含 pending creator acknowledgement）拒绝越权，错误可被 UI 理解。
- [x] 时间轴记录行的 canEdit / canDelete 与上述规则同源计算，不再硬编码为 true。
- [x] 管理员（owner）可管理他人记录；普通成员不能管理他人记录。
- [x] 未加入家庭（actor 与 creator membership 皆空）时本机记录仍可管理，与计划侧既有规则一致。
- [x] 回归测试覆盖：异成员拒绝、owner 允许、离线双空允许；至少一条时间轴 capabilities 与 mutation 一致的断言。
- [x] **不**修改 NAS/wire 上家庭级 record LWW；该残差写在本 tracker spec，不在本票「修掉服务端」。
