# 10 — 同步自动化测试

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §14.3
**Blocked by:** 03+（可与实现并行补齐）
**Status:** done

## What to build

- 服务端：LWW、token 401、avatar 403、invite 过期
- 客户端：门闩单测（非 Wi‑Fi 不请求）；Fake 后端 push/pull 幂等
- 可选：compose 健康检查脚本

## 交付物

| 工程 | CI 可跑的测试 |
| 用户可见 | 无 |

## 验收标准（Must）

- [x] `./gradlew` 相关模块单测通过（门闩 / Outbox / Fake sync）
- [x] lezi-sync 侧有 Rust interface 测试覆盖 LWW + avatar 403 + 无 token
- [x] 测试不依赖真实公网

## 不在本票范围

- 厂商真机矩阵

## Comments

- 2026-07-25：Android 测试覆盖 SyncPreferences、Wi-Fi/health/backoff、
  InvitePayloadCodec、三类前台 trigger、Outbox 批次/依赖、Fake/HTTP wire、
  pull apply、媒体上传下载及 CareLog 本地写/清除边界。
- `tools/lezi-sync/tests/api.rs` 覆盖 LWW、cursor/generation 回滚恢复、
  token 401、avatar 403、delete/upload 竞态、invite 过期/重放、媒体与家庭
  生命周期；测试通过 Axum Router seam，不依赖公网。
- Docker 容器运行与双真机路径分别由 01、09 跟踪，不影响本票自动化完成状态。
