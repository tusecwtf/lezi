# 02 — 共享家庭名

**What to build:** 家庭有一份**全员一致**的共享家庭名；建家可填可空（空则客户端兜底展示）；仅管理员可改；加入与会话能拿到当前家庭名。须在现有家庭/共享可见处能看到名字（避免纯 API 横切），但**不做**账户首屏整页改版。

**Blocked by:** None — can start immediately（与 01 并行时须遵守 create 契约 expand-contract，避免双改同一请求体签名）。

**Status:** partial

**Size:** M  
**Seams:** S2, S4

- [x] NAS 持久化共享家庭名；create 接受可选 `family_name`；create/join/会话回传
- [x] 仅 owner 可改家庭名；成员只读
- [ ] owner 改名后，已经加入的其他设备在下一次前台同步、下拉刷新或等价会话刷新时取得新家庭名；不得只更新发起改名设备的本地 session 缓存
- [x] 空名客户端兜底：「我的家庭」或「{宝宝昵称}的家庭」
- [x] 旧 NAS/无字段时客户端兜底，不崩溃
- [x] 现有共享相关表面至少一处可见家庭名（最小垂直 demo），完整家庭卡留给 03
- [x] Expand-contract：服务端先接受字段，再让客户端发送；注意 deny_unknown 与 01 的 create body 协调
- [x] Out of scope：账户 IA 重排、称呼必填（01）、邀请/向导（04）
- [x] S2/S4 测试覆盖持久化、权限、空名、回传

## Notes

- Route: `POST /v1/family/name` (owner-only rename); documented in `docs/prd/sync-home-lan.md` §9.5.2
- 当前阻塞：冷启动只读取本地 `family_name` session 缓存（create/join/本机 rename 回写）；owner 改名后，既有成员没有 GET、会话摘要或同步实体收敛路径
- Idempotent create requires matching `family_name` on retry (like display_name)

## Comments

- 2026-07-26 只读审计：重开为 `partial`；持久化和权限已实现，仍缺跨设备改名收敛。
