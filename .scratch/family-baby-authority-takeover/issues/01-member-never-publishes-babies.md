# 01 — 成员不同步宝宝实体（客户端 + NAS 护栏）

**Parent:** [../spec.md](../spec.md)

**What to build:** 已加入且角色为**成员**的设备，在家庭同步时**绝不**把本机宝宝档案（新建、更新、软删/tombstone、头像指针）作为 `baby` 实体推上家庭服务器；家庭服务器也**拒绝**成员对 `baby` 的写入。管理员与未加入行为不变。用户可感知结果：成员带着本机宝宝加入已有家庭后，**不会在家庭服务器上多出一个管理员从未创建的宝宝**（止血「同步生成两个宝宝」的上行半边）。

**Blocked by:** None — can start immediately（可与 02 并行）

**Status:** complete

## Acceptance criteria

- [x] 成员会话下出站同步快照**不包含**任何 `type=baby` 实体（含脏新建、档案更新、tombstone、avatar 指针变更）
- [x] 成员会话下对从未上过家庭服务器的本机孤宝宝，即使 `syncDirty`，也**不得**进入 baby outbox / push
- [x] 成员会话下对本机软删孤宝宝，**不得**向 NAS 发送该 `client_uuid` 的 baby tombstone
- [x] 管理员会话下宝宝 push / LWW / tombstone 行为与现网一致（建家后本机宝宝仍可成为家庭权威）
- [x] NAS（及测试用 Fake 后端）对成员 push `type=baby` 返回 forbidden；管理员 push baby 成功
- [x] 成员仍可 pull 并 apply 家庭宝宝；仍可 push 护理记录 / 计划 / 日志媒体等非 baby 实体（本票不改这些 ACL）
- [x] 头像 media 写限 owner 的现行行为保持
- [x] JVM/集成单测覆盖：成员出站无 baby、成员 push baby 被拒、管理员仍可 push baby、成员 pull 仍可得到权威宝宝
- [x] 同步相关 PRD 权限矩阵中「Baby 档案字段 push」改为仅 owner（与本票行为一致；完整 UI/孤宝宝叙述可随 02/03 补全）

## Comments

- Tracer：防双宝宝的**上行**止血；本机列表仍可能短暂看到孤宝宝，由 03/04 收敛。
- 2026-07-28 完成：成员 capture/outbox 不再发布 Baby/头像/孤宝宝关联数据；NAS 与 Fake 后端拒绝成员 Baby 写，Owner 路径保持。
- 验收：同步 JVM 回归、Rust API 全套、Android 全测试/lint/Debug 构建及双轴代码审查通过。
