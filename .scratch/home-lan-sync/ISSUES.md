# 家庭局域网同步 · 票单索引

Parent: [spec.md](./spec.md)
PRD: [`docs/prd/sync-home-lan.md`](../../docs/prd/sync-home-lan.md)

## 分波

```text
波次 A — NAS 后端（可先于 App 联调）
  01  lezi-sync 骨架 + Docker 单卷
  02  家庭/邀请/token/leave/delete
  03  push/pull 实体（baby+record）
  04  媒体字节 + avatar ACL

波次 B — Android 客户端（依赖 A 的 API 契约，可用 mock）
  05  门闩 + SyncPreferences（无默认 baseUrl）
  06  前台触发 + Outbox baby/record/media
  07  账户 UI（地址/扫码/状态/退出/删家庭）
  08  DI 去硬编码 · 未配置即 Disabled

波次 C — 验收
  09  双机前台同步验收（家网）
  10  自动化测试（服务端+门闩+假后端）
```

## 关键路径

```text
01 → 02 → 03 → 04
05 → 06 → 07 → 08
         ↘
04 + 08 → 09
03 + 04 + 05 → 10（自动化可与实现并行）
```

## 票列表

| # | 文件 | Blocked by | Status |
|---|------|------------|--------|
| 01 | [issues/01-lezi-sync-skeleton-docker.md](./issues/01-lezi-sync-skeleton-docker.md) | — | done |
| 02 | [issues/02-family-auth-invite.md](./issues/02-family-auth-invite.md) | 01 | done |
| 03 | [issues/03-push-pull-baby-record.md](./issues/03-push-pull-baby-record.md) | 02 | done |
| 04 | [issues/04-media-blob-avatar-acl.md](./issues/04-media-blob-avatar-acl.md) | 03 | done |
| 05 | [issues/05-home-network-policy-prefs.md](./issues/05-home-network-policy-prefs.md) | — | done |
| 06 | [issues/06-foreground-outbox-sync.md](./issues/06-foreground-outbox-sync.md) | 05；API 契约 02–04 | done |
| 07 | [issues/07-account-ui-server-qr.md](./issues/07-account-ui-server-qr.md) | 05, 06 | done |
| 08 | [issues/08-di-no-hardcoded-default.md](./issues/08-di-no-hardcoded-default.md) | 05 | done |
| 09 | [issues/09-dual-device-foreground-acceptance.md](./issues/09-dual-device-foreground-acceptance.md) | 04, 07, 08 | done — dual-emu create+invite join + formula/pee UI cross-visible (QR camera / cellular / notify / dark dual-UI not claimed) |
| 10 | [issues/10-sync-automated-tests.md](./issues/10-sync-automated-tests.md) | 03+；可并行 | done |

## 验证边界（2026-07-25）

- 实现与自动化：Android 单元测试、`lezi-sync` API/pytest、`:sync` / `:feature:family`
  模块测试已通过（见 `docs/reviews/home-lan-sync-docker-acceptance-2026-07-25/regression/`）。
- **01 Docker runtime done（本机 rootless Docker，非 NAS 生产）**：镜像
  `lezi-sync:0.1.0`/`latest` 存在；容器 `lezi-sync` 健康监听 `:8765`；命名卷
  `lezi-sync-data` 下同一路径含 `lezi.db` + `media/`（alpine 挂载证明；host 无法直接
  readdir rootless volume `_data`）；`curl /health` → 200 + `version` 0.1.0；进程
  uid 10001。证据：`…/01-docker/`。未宣称 NAS 生产部署。compose 第二栈因固定
  `container_name` 冲突未起（已记录，非 Must 失败）。
- **09 dual-emu done（双模拟器前台路径）**：证据根
  `docs/reviews/device-family-dual-emu-09/`（`REPORT.md` / `summary.json`）。
  - 卷 wipe 后 `lezi-sync:0.1.0` 健康 `:8765`；`emulator-5554` + `emulator-5556`
    （AVD `lezi_api35` / 克隆 `lezi_api35_b`）装包清数据。
  - A UI **新建家庭** → family `51d1cdf6-…`，邀请码 `TBAYSUCVKNTU` + QR 截图；
    B UI **输入邀请码** 加入为 Member（扫码 UI 存在；**双机相机扫码未自动化**）。
  - 前台交叉可见：A formula 120ml → B 同步后可见（需 **设为当前** 家庭宝宝）；
    B pee@18:42 → A 尿 1→2次。协议校验同家庭 **22/22**（含日志图/头像 ACL）。
  - 运维注记：双 AVD 下 `10.0.2.2` 不稳，UI 交叉前曾 offline 改写 DataStore baseUrl；
    首次 create/join 遇 HomeNetworkPolicy 退避需 force-stop 重试。
  - 蜂窝门闩 / 伴侣系统通知 / 独立深色双端 UI 仍 **env-blocked**（Must 未勾；单测替代）。
  - **不宣称**：双物理机、双机相机扫码 E2E、NAS 生产、息屏 60s。
- 早期 dual-path（单 emu + live HTTP 18/18）仍见
  `docs/reviews/home-lan-sync-docker-acceptance-2026-07-25/09-dual-path/`（已被本轮双 emu 覆盖）。
- 因此特性为「实现完成 + Docker 本机 + **双模拟器前台同步已验**；物理机/蜂窝硬件与
  相机扫码自动化仍可补」，不表示已部署 NAS 生产。

## 与旧 V2 票关系

| 旧票 (v2-delivery) | 关系 |
|--------------------|------|
| 01 SyncPort 真实现 | 已由本特性 05–08 重做并对齐门闩、token、实体与媒体 |
| 02 邀请加入退出 | **由 02+07 取代**（含 baseUrl QR、仅在家） |
| 03 双机 60s | **作废后台 SLA**；由 **09 前台验收** 取代 |
| 04–08 非同步 | 仍属 v2-delivery，与本特性并行、不阻塞关键路径 |
