# 05 — HomeNetworkPolicy + SyncPreferences

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §2.2、§3、§1#9
**Blocked by:** —（可与 01 并行）
**Status:** done

## What to build

Android：

- `HomeNetworkPolicy`：`TRANSPORT_WIFI` + `GET {baseUrl}/health`（短超时）→ `allowSync`
- **非 Wi‑Fi / health 失败 → 禁止** 一切 NAS API
- `SyncPreferences`（DataStore 等）持久化：`baseUrl`、`familyId`、`familyToken`、`deviceId`、`pullCursor`
- **无内置默认 baseUrl**（禁止写死 `10.0.2.2` / `192.168.50.4` 为生产默认）
- health 失败退避（禁止固定高频 ping）

## 交付物

| 工程 | policy + prefs 模块/API |
| 用户可见 | 无直接 UI（07 消费状态） |

## 验收标准（Must）

- [x] 单测或仪器：模拟非 Wi‑Fi → allowSync=false
- [x] baseUrl 空 → 不发起 health
- [x] cursor/token 进程杀死后仍在
- [x] 代码库默认路径无「未配置仍指向开发机」的生产 DI（配合 08）

## 不在本票范围

- 完整 push 调度（06）
- 账户 UI（07）

## Comments

- 2026-07-25：Android 已实现 DataStore session（baseUrl/family/token/device/cursor/role/last success）、
  Wi-Fi + health 门闩及 30s→2min→10min 退避。
- `HomeNetworkPolicyTest` 与 `SyncPreferencesTest` 覆盖非 Wi‑Fi/空地址、
  health 退避、后台门闩、服务器切换与会话重建持久化。
