# 02 — Android：同一建家入口接回并全量 pull

**Parent:** [../spec.md](../spec.md)

**What to build:** 客户端继续使用现有「创建家庭」向导与 `createFamily` /
`FamilySessionCoordinator` 路径（bootstrap 只进请求头、不落盘）。在 01 落地后：

1. 服务端对「已有家庭 reclaim」返回与 create 成功同形的会话字段时，客户端按
   **Joined owner** 落盘：`familyId`、`token`、`membershipId`、`role`、`deviceId`、
   `generation`、`familyName` 等；**cursor 从 0 或等价空检查点** 开始，避免沿用已擦除的旧游标
2. 成功后触发现有前台同步（`LocalWrite` / 随后 `PullToRefresh` 或 engine 内 full-resync），
   经现行 pull 分页 + 媒体下载，按 `client_uuid` 幂等合入本机 Room
3. UI：同一表单，无独立「恢复」入口；成功文案可区分首次建家与接回
   （例如「已接回家庭，正在同步数据」），失败仍映射口令错误 / 网络门闩等
4. **不**为 reclaim 做专用本机数据合并；接回前本机已写内容走既有 LWW / 权威宝宝规则
5. 成员加入流程不改；不得对成员暴露 bootstrap 抢权

**Blocked by:** 01（依赖 NAS create→reclaim 契约）

**Status:** complete

## Acceptance criteria

- [x] 空 NAS：创建家庭路径与今相同
- [x] 已有家庭 + 正确部署口令：create 成功 → 本机会话 `isJoined` 且 `role=Owner`，
      `membershipId` 与 NAS 原 owner 一致（可由 members/`is_self` 或测试夹具断言）
- [x] 接回后前台/下拉同步：NAS 上已有 baby/record/care_plan/media/custom_item 等按
      `client_uuid` 出现在本机；不依赖用户第二次「神秘操作」
- [x] bootstrap **不**写入 DataStore/session/邀请载荷；成功后输入框清空（保持现行为）
- [x] 口令错误：可重试，不落半套「已加入」会话
- [x] 本机在 reclaim 前已有不同 uuid 的本地数据：不崩溃、不专用合并；行为与现网
      LWW/孤宝宝规则一致（文档一句即可，不强制新 UX）
- [x] 成功/失败文案可读；接回成功不谎称「服务器已清空」或「已删除旧家庭」
- [x] JVM：Fake/HTTP backend 模拟 reclaim 响应；Coordinator/Port 落盘与触发同步；
      必要 engine 测证明 cursor0 全量 pull
- [x] Documentation Gate：`docs/prd/sync-home-lan.md` 客户端接回叙述；若文案进 PRD 则
      `docs/prd/ui.md` 账户建家一句

## Comments

- Grill：入口=同一创建家庭；数据=标准 full-resync；仅 owner。
- 实现时注意 push 的 `device_id` 必须与 reclaim 后会话一致（服务端已更新 membership.device_id）。
