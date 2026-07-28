# 家庭管理员移除成员

| 字段 | 值 |
|------|-----|
| **Date** | 2026-07-28 |
| **Status** | Implemented |
| **Related** | `docs/prd/sync-home-lan.md` §4.3 / §9.11；`docs/prd/ui.md` §5.7 成员二级 |

## Overview

家庭管理员（owner）可在家人名单中移除普通成员。服务端复用 leave 语义（`left_at` + 吊销全部 credentials），**不**删除历史护理记录；客户端仅 owner 对非本人 member 行显示「移除」并二次确认。

## 规则

| 规则 | 行为 |
|------|------|
| 谁可操作 | 仅 owner |
| 可移除对象 | 同家庭 active `role=member` |
| 不可移除 | 自己、owner、已离开/未知 membership |
| 历史记录 | 保留；`created_by_membership_id` 仍指向已离开 membership |
| 被移除设备 | 下次 API → 401；无推送 |

## API

`POST /v1/family/members/remove`  
Auth: owner Bearer  
Body: `{ "membership_id": "<uuid>" }`  
Response: `{ "ok": true, "membership_id": "…" }`

## 客户端路径

- `SyncBackend.removeMember` → `HttpSyncBackend` POST  
- `FamilySessionCommand.RemoveMember` → `FamilySessionCoordinator`  
- `SyncPort.removeMember` → `FamilyViewModel.removeMember`  
- UI：`FamilyMembersListSheet` / `RemoveMemberConfirmDialog`

## Non-goals

- 管理员转移 / 多 owner  
- 删除被移除成员写过的记录  
- 远程推送“你已被移出”  
