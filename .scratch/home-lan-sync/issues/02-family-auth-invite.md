# 02 — 家庭创建 / 邀请 / token / leave / delete

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §4、§9.2–9.4、§9.8–9.9
**Blocked by:** 01
**Status:** done

## What to build

服务端：

- `POST /v1/family/create` → `family_id` + **owner token**
- `POST /v1/invite`（Bearer owner）→ 短码 + `expires_at`（默认 24h）
- `POST /v1/join` → member token + family_id
- `POST /v1/leave` → 仅 member；吊销本 token/device，**不删**业务数据；
  owner 因首版无管理员转移而返回 403
- `POST /v1/family/delete` → 仅 owner；清空 entities + media 文件
- Token **哈希存库**；除 `/health` 外业务接口校验 Bearer
- schema 含 `family_id`（一家一栈交付，不多租户产品化）

## 交付物

| 工程 | 鉴权与家庭生命周期 API |
| 用户可见 | 无（经 App 07 暴露） |

## 验收标准（Must）

- [x] 无 token 调用 push/pull（若已存在）→ 401/403
- [x] join 无效码 404；过期 410（或等价）
- [x] leave 后旧 token 失效；NAS 上 entities 仍在
- [x] owner delete 后 entities 与 media 目录清空（或空库）
- [x] 非 owner 调 invite / family/delete → 403；owner 调 leave → 403
- [x] 邀请码默认约 24h TTL

## 不在本票范围

- 实体 LWW 细节（03）
- 媒体 ACL（04）
- QR 载荷客户端（07）

## Comments

- 2026-07-25：`tools/lezi-sync/tests/test_api.py` 覆盖建家重试、token 哈希、
  邀请/过期/重复消费、角色权限、退出吊销与 owner 删除；API 自动化通过。
