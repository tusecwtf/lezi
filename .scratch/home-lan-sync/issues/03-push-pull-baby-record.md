# 03 — push/pull：Baby + Record

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §5、§9.5–9.6、§10
**Blocked by:** 02
**Status:** done

## What to build

- `POST /v1/push`：Bearer；entities `type=baby|record`；LWW by `updated_at`
- `GET /v1/pull?cursor=&generation=`：增量 `rev`；返回新 cursor + 服务进程代际
- 逻辑键 `(family_id, entity_type, client_uuid)`
- Record payload 使用 **`baby_client_uuid`**（禁止依赖对端本地自增 baby id）
- tombstone：`deleted_at`
- Settings / 提醒类字段 **不得**作为同步实体

## 交付物

| 工程 | 核心同步 API + SQLite entities/meta.rev |
| 用户可见 | 无 |

## 验收标准（Must）

- [x] 同 client_uuid 重复 push 不产生两行
- [x] 较旧 updated_at 的 push 被 skip（LWW）
- [x] pull 按 cursor 只返回更新
- [x] 服务重启/备份恢复后 generation 变化触发结构化全量校准
- [x] push 在 mutation 前校验 generation；旧会话空 generation + 非零 cursor
  一次性从 0 校准
- [x] 软删后对端 pull 可见 tombstone / 不可见业务行（实现二选一，须一致并文档化）
- [x] record 载荷含 baby_client_uuid 字段约定

## 不在本票范围

- media blob（04）
- Android Outbox（06）

## Comments

- 2026-07-25：服务端 API 测试覆盖幂等、LWW、单调 cursor、tombstone 与
  portable 引用及 generation 恢复；Android Fake 后端与 wire mapper 测试覆盖
  push/pull 合并和成员头像权限恢复。
