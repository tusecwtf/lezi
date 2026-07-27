# 02 — 共享家庭名

**What to build:** 家庭有一份**全员一致**的共享家庭名；建家可填可空（空则客户端兜底展示）；仅管理员可改；加入与会话能拿到当前家庭名。须在现有家庭/共享可见处能看到名字（避免纯 API 横切），但**不做**账户首屏整页改版。

**Blocked by:** None — current client/server 必须同版本交付。

**Status:** in-progress（自动化完成，双设备 smoke pending）

**Size:** M  
**Seams:** S2, S4

- [x] NAS 持久化共享家庭名；create 接受可选 `family_name`；create/join/会话回传
- [x] 仅 owner 可改家庭名；成员只读
- [x] owner 改名后，已经加入的其他客户端在下一次前台同步、下拉刷新或等价会话刷新时取得新家庭名；不得只更新发起改名设备的本地 session 缓存（Fake 双客户端已证，最终双设备 smoke 待办）
- [x] 空名客户端兜底：「我的家庭」或「{宝宝昵称}的家庭」
- [x] ~~旧 NAS/无字段时客户端兜底，不崩溃~~ — **superseded 历史 receipt**
- [x] 现有共享相关表面至少一处可见家庭名（最小垂直 demo），完整家庭卡留给 03
- [x] ~~旧版 expand-contract 部署顺序~~ — **superseded 历史 receipt；fresh 部署要求 current client/server 同步交付**
- [x] Out of scope：账户 IA 重排、称呼必填（01）、邀请/向导（04）
- [x] S2/S4 测试覆盖持久化、权限、空名、回传

## Notes

- Route: `POST /v1/family/name` (owner-only rename); documented in `docs/prd/sync-home-lan.md` §9.5.2
- current pull envelope 在每个成功页（含零实体页）必须返回 `family_name`；explicit null 清空，value 覆盖，并与 cursor/generation 原子发布；omitted 为协议错误并可重试
- Idempotent create requires matching `family_name` on retry (like display_name)

## Comments

- 2026-07-26 只读审计：重开为 `partial`；持久化和权限已实现，仍缺跨设备改名收敛。
- 2026-07-27 原自动化 receipt 含 legacy omitted/expand-contract 兼容，相关结论已 superseded；value/null 收敛仍需最终 current Docker + 双设备 smoke，且须补缺字段 fail-closed 的负向证据后再回写 done。
