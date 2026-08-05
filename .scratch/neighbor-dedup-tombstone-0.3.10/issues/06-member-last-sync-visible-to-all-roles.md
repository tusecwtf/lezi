# 06 — 家庭成员页：各成员上次同步时间（全角色可见）

**What to build:** 在 **家庭成员与设备** 页，每位成员展示 **上次同步时间**；**Owner 与普通
Member** 均可获取并看到 **所有成员** 的该信息（非管理员专属）。不借此向普通成员放开他人
完整设备列表。属 **0.3.10** 产品面（含最小服务端 additive 投影）。

**Blocked by:** None — can start immediately（与 01–03、05 独立）。

**Status:** ready-for-agent

## Scope

### 服务端（最小 additive）

- `GET /v1/family/members` 的每个 `MemberView` 增加成员级字段（建议名 `last_sync_at`，
  epoch 秒，与现有 `last_used_at` 单位一致）。
- 语义：该 membership 下全部 **active** 设备的 `last_used_at` **最大值**；无 active 设备时
  为 `null`/省略。
- **所有已认证家庭成员**（owner / member）响应中都带每位成员的该字段。
- **设备数组 ACL 不变**：`devices` 仍仅 Owner 全量、普通成员仅 `is_self`；禁止为了本票
  把他人 `devices` 填给普通成员。

### 客户端

- `FamilyMember`（及 HTTP 解析）承载 `lastSyncAtEpochSeconds: Long?`（或等价）。
- `FamilyMemberRow`（或同源行）对 **每一位** 成员展示人话上次同步时间（复用/对齐
  `formatFamilyDeviceLastUsed` 气质）；无时间时明确空态（如「尚未同步」）。
- 刷新成员列表（进页 / 菜单刷新）后时间与服务器一致；不要求秒级推送。

### 权限与隐私

- Owner、普通 Member 打开成员页都能看到 **所有成员** 的上次同步摘要。
- 普通成员仍看不到他人设备名 / device_id / is_current 行；设备管理动作 ACL 不变。
- 文案不展示 IP、token、server id。

## Acceptance

- [ ] 成员 API：任意角色响应含每位成员 `last_sync_at`（或等价）；值 = active 设备
      `last_used_at` max
- [ ] 普通成员响应中他人 `devices` 仍为 null/省略，但 `last_sync_at` 有值（有设备时）
- [ ] UI：Owner 与普通成员在成员页均可见各成员上次同步时间
- [ ] 无 active 设备 / 从未连上：明确空态，不假装「刚刚」
- [ ] lezi-sync 回归：成员列表投影 + 角色可见性；Android 解析 + 成员行展示测试
- [ ] 文档：PRD/设计中成员列表权限表补充「上次同步摘要全员可读、设备明细仍分级」
  （可与 04 发版文档收口一起核对）

## Notes

- `last_used_at` 已在鉴权/refresh 路径更新；本票不要求新的「仅 push 成功才写」时钟。
- 多设备成员：只展示 **成员级** 一个时间（max），不在普通成员视图展开各设备时间。
- Spec：[`../spec.md`](../spec.md) Solution §4、US 49–50。
