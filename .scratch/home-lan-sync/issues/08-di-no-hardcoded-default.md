# 08 — DI：未配置 Disabled，去掉生产硬编码

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §13 · tech.md
**Blocked by:** 05
**Status:** done

## What to build

- 未配置 baseUrl / 未加入 → SyncPort **Disabled** 或等价 NoOp 行为（记账不堵）
- 移除/隔离 `SyncModule` / BuildConfig 将 `http://10.0.2.2:8765` 作为**默认生产注入**
- 开发可用 debug 覆盖（明确 flavor 或仅 debug），不得影响 release 默认
- domain→sync 依赖方向债可记 follow-up，本票至少行为正确

## 交付物

| 工程 | Hilt/DI 与默认策略 |
| 用户可见 | 新装 App 不自动打开发机 IP |

## 验收标准（Must）

- [x] release（或默认 DI）新鲜安装：无配置时不请求外网/固定开发 IP
- [x] 配置 baseUrl + 家网后可 health
- [x] 文档/注释标明 debug 如何指到模拟器 10.0.2.2

## 不在本票范围

- 完整 media UI

## Comments

- 2026-07-25：已删除 app/default DI 的 `10.0.2.2:8765`，DI 改为持久 session 驱动；
  新装无 baseUrl 时为 Disabled。模拟器调试地址须由账户页手填，不作为任何 build type 默认。
- `HomeNetworkPolicyTest`、`RealSyncPortTest` 与完整 App/Hilt 构建覆盖未配置
  no-op、配置后 health 及默认 DI 接线。
