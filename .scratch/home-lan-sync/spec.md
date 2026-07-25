# Spec: 家庭局域网同步（Home LAN Sync）

Status: implementation-complete; docker-01 runtime done (rootless, not NAS prod); 09 dual-path partial (live dual-HTTP + single-emu; dual-phone pending)
Feature: home-lan-sync
Product: 乐记 (`com.lezi.babylog`)
Prerequisite: V1 本地记账可用；账户页入口存在
Source: **[`docs/prd/sync-home-lan.md`](../../docs/prd/sync-home-lan.md)**（权威）
Supersedes: `.scratch/v2-delivery` 中同步相关票 01–03 的「公网/后台 60s」假设

---

## Problem Statement

家庭需要同一份育儿日志，但不接受公网强制云、后台常醒同步、伴侣逐条推送。
已锁定方案：**NAS 中心化 + 硬家庭 Wi‑Fi + 仅前台 + 邀请码/family token**。
交付实现位于 Android `:sync` / `:feature:family` 与
`tools/lezi-sync/`：已覆盖 token、家网门闩、前台触发、Outbox、
媒体字节及规格 API。

---

## Solution

1. NAS 部署 **`lezi-sync`**（Python 3.12 + FastAPI + Uvicorn + SQLite，Docker 单卷 `DATA_DIR` = db + media）
2. Android：`HomeNetworkPolicy` + 持久化 `baseUrl`/token/cursor；**无内置默认地址**；QR 可带 baseUrl+code
3. 触发：回前台、下拉、前台写成功 push；**无**后台轮询、**无**推送拉同步
4. 同步域首版：**Baby + Record + 日志 MediaAsset**；头像仅 owner 可改
5. 冲突：client_uuid 幂等 + updated_at LWW + tombstone

### Seams

| Seam | 职责 |
|------|------|
| **CareLog / Outbox** | 本地写立刻成功并标记 dirty；同步触发将 baby/record/media 快照入队 |
| **SyncPort** | create/invite/join/leave/push/pull/media；尊重门闩 |
| **HomeNetworkPolicy** | Wi‑Fi + health → allowSync |
| **lezi-sync** | 家庭服务器权威副本 |

### Explicit non-goals

- P2P、后台 60s SLA、伴侣记录推送、公网默认云、字段级 ACL、CustomItem/Calendar 同步（后置）

---

## Acceptance (feature-level)

见 [ISSUES.md](./ISSUES.md) 关键路径末票与各票 Must。产品验收表：`sync-home-lan.md` §5.4。

### 交付状态（2026-07-25）

- Android 与服务端实现、单元/API 自动化测试已完成（含 pytest 与 Gradle 模块回归）。
- **01 Docker runtime done（本机 rootless Docker）**：镜像存在；`lezi-sync` 健康于
  `:8765`；单卷 `lezi-sync-data` 含 `lezi.db` + `media/`；`/health` 200 + version。
  **未**宣称 NAS 生产部署。证据：`docs/reviews/home-lan-sync-docker-acceptance-2026-07-25/01-docker/`。
- **09 partial**：live 双 Client HTTP A/B 路径 18/18 通过；仅单模拟器经邀请码加入。
  双物理机扫码、蜂窝硬件门闩、伴侣通知观察仍未验收。证据：`…/09-dual-path/`。
