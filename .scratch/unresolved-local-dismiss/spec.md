---
triage: done
title: 未对齐项并入冲突解决：本机去掉而不通知家里
tracker: .scratch
decisions: 2026-09-13 owner——UX 与现有冲突收件箱/裁决页合并，不另开未收下动作面；本机去掉不 push 墓碑；活键仅 mismatch 类 opt-in
---

# 未对齐项并入冲突解决

## Problem Statement

管理员机活集醒来 148、服务器 147。`4a50406b` 已停止为同一 snapshot 重复全量重拉，
但偏差仍在：`abandonMutation` 只停发表、活行还在，普查永远对不齐。家庭页同时有
「冲突 N」徽章和只读「N 项未收下」对话框，用户没法按条从本机清掉未对齐项。

## Solution

一个待处理面：徽章计数 = 未解决 branched 冲突 + 可处理未对齐项。点徽章或
「N 项未收下」进同一底栏。未对齐项用同一套裁决页皮肤，**不伪造 `conflict_id`**。

三类 dismiss，禁止一种删除打天下：

| 形态 | 动作 | 不做什么 |
|---|---|---|
| 本机多出来的活行 / 家里终态拒绝 | 本机墓碑 + `abandoned`，**不 push** 这条墓碑 | 不写成家庭删除 |
| 引用不全 / 拉取洞 | 只撤 skip 回执，写 `dismissed-skip` 以免立刻再现 | 不墓碑（本机没有该行） |
| 家里已墓碑、本机仍活 | 不进收件箱用户删除 | pull 必须吃下远端墓碑 |
| 宝宝 / 家庭 / 无名普查聚合 | 列表可看 | 裁决页不给「从本机去掉」 |

差集身份：先启发式（活行且无 `familyPublished`/`baseVersion`），再 opt-in
`include_live_keys` 仅对 mismatch 类要精确活键。

## User Stories

1. 作为管理员，我希望那条本机多出来的醒来出现在待处理里，去掉后徽章与浅状态清掉。
2. 作为家长，我希望「从本机去掉」不会让家里其它手机丢掉这条事实。
3. 作为家长，我希望引用不全的拉取洞去掉后，对端补齐仍可 apply。
4. 作为家长，我不希望未对齐项走冲突 CAS，以免污染 v2 解决。

## Implementation Decisions

- 入口合并：`ConflictInbox` 增加 `kind`（`Branched` / `LocalExtra` / `Rejected` /
  `PullHole`）。未对齐导航键 `local:<kind>:<entityType>:<clientUuid>`，不是
  `conflict_id`。
- 普查聚合回执（空 uuid 的 `live_census`）只给引擎跳过重复重拉，不进收件箱。
- `dismissUnresolvedLocally` 是 SyncPort/CareLog 新 seam；扩展 abandon 必须墓碑且
  `syncDirty=false`。
- Wire：`include_live_keys` + `live_key_types` 缺省关；只在 mismatch 重走时按类要键。
- 不做自动静默删；不恢复每轮 cursor-0 全史。

## Acceptance

管理员机那条醒来出现在待处理里，去掉后本机普查对齐、不向家里发删除、徽章与
「N 项未收下」清掉。
