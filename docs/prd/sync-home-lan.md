# 乐记 — 家庭局域网同步规格（V2）

> 决策锁定：2026-07-25（grilling）
> 主 PRD：[`README.md`](./README.md) · 数据契约：[`data-model.md`](./data-model.md) · Android：[`tech.md`](./tech.md)
> 本文定义 **中心化 NAS + 仅前台 + 硬家庭 Wi‑Fi** 的完整架构与 NAS Docker 后端。
>
> **交付状态（2026-07-25）：** Android 与 `tools/lezi-sync` 实现及自动化已完成；
> 本机 rootless Docker 和双模拟器 formula/pee 前台交叉可见已验证；严格使用
> `192.168.50.4:8765` 的服务端还完成了建家、邀请码加入、双向协议记录与头像 ACL。
> Ticket 09 仍为 partial：相机扫码、日志图跨端 UI、蜂窝/回家冲刷、通知与独立设置待验；
> 不宣称物理 NAS 生产部署或全部设备级 Must 通过。

---

## 0. 一句话

本地 Room 为 UI 真相源；在 **家庭 Wi‑Fi 且 NAS 可达** 时，**仅前台** 用 **family token** 与 NAS 上的 **`lezi-sync`（Docker）** 做中心化同步。无 P2P、无后台同步、无伴侣即时通知。

---

## 1. 已锁定决策

| # | 主题 | 结论 |
|---|------|------|
| 1 | 网络门闩 | **硬家庭局域网**：**本机 SSID 白名单（1～2 个名字，典型 2.4G/5G）** + 当前为 Wi‑Fi + 当前 SSID **精确命中** 白名单 + 配置的 NAS `/health` 可达，才允许一切 NAS API |
| 2 | 鉴权 | 建家使用 NAS 部署时设置的**一次性初始化口令**；成员用**邀请码进门**；加入后使用长期 **family token**；无有效凭证 → 401/403 |
| 3 | 建家 / 发码 / 加码 / 同步 | **全部** 受同一门闩约束（含 SSID；仅在家） |
| 4 | 传输 | 默认 **HTTP + token**；可选 HTTPS 由 NAS 的 Caddy/Nginx/系统反向代理终止，服务本身不接收证书环境变量 |
| 5 | 同步实体（首版） | **Baby + Record + 日志 MediaAsset** |
| 5b | 写权限 | **宝宝头像：仅管理员（owner）**；**日志媒体：家庭内可同步** |
| 6 | 媒体字节 | NAS 本地文件；API 上传/下载；DB 只存元数据 |
| 6b | 落盘布局 | **data 与 media 同一数据根路径**（单 volume） |
| 7 | 多家庭 | **一家一栈** 交付；schema 保留 `family_id`，不多租户产品化 |
| 8 | 触发 | **回前台 + 下拉 + 前台写成功后 push**；无后台、无 60s 定时、无推送拉同步 |
| 9 | 服务器地址 | **单一** host（IP/域名）+ **端口**（默认 8765）→ `baseUrl=http://{host}:{port}`；**SSID 白名单最多 2**（共用该 host:port，不为每个 SSID 记不同 IP）；**邀请 QR 含 baseUrl/host/port + code + 可选 ssids≤2**；可手改 |
| 9b | 小白默认（空态预填） | host 预填 **`192.168.50.4`**，端口 **8765**，SSID 第一格预填 **当前连接 Wi‑Fi 名**（可读时）；预填 ≠ 已保存，保存/加入成功后才持久化 |
| 10 | 退出 / 删除 | member 可 leave；owner 二次确认后**删除家庭数据**；**leave 与 delete 成功后本机均清空** host/port、SSID 白名单与会话 token |
| 11 | 持久化引擎 | 首版 **SQLite**；API 不绑死引擎，可替换 |
| 12 | 拓扑 | **仅中心化 NAS**；P2P 不在范围内 |

**明确不做**

- 后台 WorkManager / 闹钟轮询同步
- 为同步常驻 FGS / WakeLock
- 伴侣新记录推送 / FCM 拉起同步
- 公网强制云、强制账号体系
- 字段级部分共享

**已批准的后续扩展**

- 家庭同步 `CustomItemDef`（`custom_item` legacy push）已落地：共享 UUID/名称/图标/创建者/tombstone；布局与槽位仍本机。
- 家庭同步 `CarePlan`、计划照片以及计划原子包仍待后续票。
- 通用 `CalendarEvent` 不进入家庭同步；护理计划与 Android 系统日历副本是不同概念。
- 自定义项目显隐/排序/常用槽位、系统日历 ID/权限/披露级别与提醒偏好继续只存本机。

---

## 2. 架构模型

### 2.1 逻辑拓扑

```text
┌──────────────────┐         仅：Wi‑Fi + NAS health OK + 前台
│  Android 乐记     │ ─────────────────────────────────────┐
│  Room + Outbox   │   HTTPS? 默认 HTTP + Bearer token      │
│  SyncPort        │                                       ▼
└──────────────────┘                              ┌─────────────────┐
        ▲                                         │  NAS Docker      │
        │ pull 合入                                │  lezi-sync       │
        │                                         │  :8765           │
┌──────────────────┐                              │  DATA_DIR 单根   │
│  成员设备 B…      │◄──── 同样只连 NAS ───────────│   db + media/    │
└──────────────────┘                              └─────────────────┘
```

- 成员之间 **不直连**；一致性经 NAS。
- UI **永不**以 NAS 为同步阻塞：先写 Room，再 Outbox，门闩过后再 push。

### 2.2 客户端模块职责

| 组件 | 职责 |
|------|------|
| `HomeNetworkPolicy` | 前台 + host/port 已配 + **SSID 白名单非空** + `TRANSPORT_WIFI` + **当前 SSID 精确命中白名单** + `GET {baseUrl}/health` → `allowSync` |
| `SyncPort` | push / pull / invite / join / leave / media 协调 |
| `Outbox` | 待上行实体队列（baby / record / media 元数据） |
| `SyncPreferences` | `serverHost`、`serverPort`、`allowedSsids`（≤2，**仅本机**）、会话 `baseUrl`（派生）、`familyId`、`familyToken`、`deviceId`、`pullCursor`、`pullGeneration` |
| 触发器 | `ProcessLifecycle` ON_START、下拉、前台写成功 |

### 2.3 写与同步路径

```text
用户写记录 / 改宝宝 / 加日志图
  → Room 立刻成功（UI 刷新）
  → enqueue Outbox（sync 已配置且 isEnabled 语义下）
  → 若 前台 && allowSync && 已加入
        → push（entities + 缺的 media blob）
        → （回前台/下拉时）pull 增量 + 下载缺媒体

蜂窝 / 非家 Wi‑Fi / health 失败
  → 只本地；Outbox 保留；UI 可轻提示「回家后同步」
```

### 2.4 状态（扩展 data-model）

| SyncStatus | 含义 |
|------------|------|
| `Disabled` | 未配置服务器 / 未加入家庭 / 用户关闭同步 |
| `BlockedOfflineHome` | 已配置但不在家网或 health 失败（可选细分，或并入 Error 文案） |
| `Idle` | 可同步，当前无进行中 |
| `Syncing` | push/pull/媒体传输中 |
| `Error` | 最近一次失败（保留可读原因） |

---

## 3. 网络门闩（硬家庭局域网）

### 3.1 配置模型

| 字段 | 规则 |
|------|------|
| `serverHost` | 非空；IPv4 / IPv6 / 域名；保存时若用户粘贴完整 URL 则解析出 host/port |
| `serverPort` | 1–65535，默认 **8765** |
| `allowedSsids` | **0～2** 个非空字符串（trim、去重）；**精确匹配**（大小写敏感）；典型登记家里 2.4G 与 5G 两个名字 |
| 派生 `baseUrl` | `http://{serverHost}:{serverPort}`（无尾 `/`） |

SSID 白名单 **仅存本机**，不随家庭同步到 NAS。两台手机可登记不同 SSID 名。

**小白空态预填（未持久化前）**：host=`192.168.50.4`，port=`8765`，SSID 第一格=当前连接 Wi‑Fi 名（系统可读时）；第二格留给 5G 名。

### 3.2 允许调用 NAS 的条件

以下 **create / invite / join / push / pull / media** 全部同一套：

1. App **前台**（自动触发）；用户下拉视为前台手势。
2. 已保存完整 **host + port**。
3. **`allowedSsids` 至少 1 个**（空名单 **禁止**，即使 health 通）。
4. 活跃网络 **`TRANSPORT_WIFI`**（蜂窝不兜底）。
5. 能读到当前 SSID，且 **trim 后精确等于** 白名单之一；读不到（无权限 / `<unknown ssid>`）→ **禁止** 并在用户发起家庭同步操作时引导开启位置权限与系统定位服务（**不**降级为「仅 Wi‑Fi」）。Android 将 SSID 视为位置敏感字段；应用不读取坐标、不上传 SSID。
6. `GET {baseUrl}/health` 在短超时内（建议 ≤3s）成功；客户端不跟随重定向，
   最多读取 64 KiB 响应体，并只接受小型健康 JSON。
7. 需会话的 API 另需有效 family token（join/create 前无 token）。

### 3.3 失败与其它

- health 失败：指数退避（30s → 2min → 10min），禁止固定高频 ping。
- 白名单已满 2 个时自动绑定新 SSID：**不覆盖**，提示用户手动改。
- 已加入后改 host/port：**允许**；保留 token/family 与 SSID 名单；**cursor/generation 按 full_resync 语义重置**。
- leave / owner 删除家庭成功：本机 **清空** host/port、SSID 白名单与会话。
- 旧版仅 `sync_base_url` 迁移：解析 host/port；SSID 空 → 禁止同步直至用户绑定。
- 不因同步失败回滚 Room 写入。
- **不做** BSSID 绑定、多于 2 个 SSID、每 SSID 独立 IP。

---

## 4. 身份与鉴权

### 4.1 角色

| 角色 | 说明 |
|------|------|
| `owner` | 家庭创建者 / 管理员 |
| `member` | 邀请加入的成员 |

### 4.2 凭证

| 凭证 | 用途 |
|------|------|
| **初始化口令** | NAS 运维者通过 `LEZI_BOOTSTRAP_SECRET` 设置；仅随建家请求发送，Android 不持久化、不写日志、不放入邀请载荷 |
| **邀请码** | 短时效（默认 24h）；用于 `join`；QR 载荷同时携带 `baseUrl` |
| **family token** | join/create 成功后下发；后续 API `Authorization: Bearer <token>` |
| **device_id** | 客户端生成的稳定设备标识；登记成员，不代替 token |

### 4.3 权限矩阵（首版）

| 操作 | owner | member |
|------|-------|--------|
| push/pull Record | ✓ | ✓ |
| push/pull Baby 档案字段 | ✓ | ✓（建议：成员可改自己可见档案；冲突 LWW） |
| **改宝宝头像（avatar media）** | ✓ | **拒绝** |
| 日志 MediaAsset 增删改 | ✓ | ✓（建议仅关联自己创建的 Record；服务端至少校验 token∈家庭） |
| createInvite | ✓ | × |
| delete family data | ✓ | × |
| leave | ×（首版无管理员转移，避免产生无管理员家庭） | ✓（退出） |

头像与日志媒体的区分：见 §6.3。

### 4.4 加入前文案

首次同步/加入前明示：**将在家庭服务器共享全部已同步的育儿记录与日志图片**（设置与提醒不同步）。

---

## 5. 同步域与冲突

### 5.1 同步 / 不同步

| 同步（首版） | 不同步 |
|--------------|--------|
| Baby（含头像引用；头像写限 owner） | SettingsLocal 全部 |
| Record（含软删） | 下次喂奶提醒、Widget |
| 日志 MediaAsset 元数据 + 字节 | 本机-only 路径、主题排序等 |
| | CustomItemDef / CarePlan（现行首版未实现，见下方已批准扩展）及通用 CalendarEvent |

后续已批准但尚未实现的同步域为 `CustomItemDef`、`CarePlan` 与计划媒体；
通用 `CalendarEvent` 仍不同步。带照片的 Record/CarePlan 必须在发送、服务端
发布和接收应用阶段以完整照片包原子可见，不能先展示实体再补照片。

### 5.2 冲突

- 实体主键（逻辑）：`(family_id, entity_type, client_uuid)`
- 同 uuid：**幂等 upsert**
- 不同版本：比较 **`updated_at`，LWW**
- 删除：**tombstone**（`deleted_at`）随实体传播

### 5.3 跨机引用

- Record **不得**依赖对端本地自增 `baby_id`。
- 同步载荷使用 **`baby_client_uuid`**（及 record/media 自身 `client_uuid`）。
- 客户端 apply 时映射到本地 row id。

### 5.4 验收（取代「后台 60s」）

| 用例 | 期望 |
|------|------|
| A、B 均在家 Wi‑Fi，均打开 App | 回前台或下拉后数据一致；A 前台新写后 push，B 回前台/下拉可见 |
| A 记账后杀进程，稍后打开 | 打开时 push 未送出 Outbox |
| 非家网 | 不调用 NAS；本地可用 |
| 无推送 | 伴侣新记录不弹通知 |

### 5.5 已加入家庭时清除本机记录

「清除全部记录」只清当前设备的护理记录及其本机副本数据，不删除 NAS 上的家庭
记录，也不代表退出或删除家庭。清除期间同步与本地事务共用屏障；一旦领域事务已
提交，后续副本清理失败必须补偿或可安全重试，且遗留 Outbox 不得把已清记录复活
上传。由于服务器仍保留数据，下一次在家网前台同步时，这些记录可能重新下载到本机。

当前验收闭合了本机 Docker、双模拟器 formula/pee UI 交叉可见，以及严格 live
服务端上的建家、邀请码加入、双向协议记录与头像 ACL。相机扫码、日志图 UI、
蜂窝/回家冲刷、伴侣通知和双端独立设置仍未闭合；完整状态以
[Ticket 09](../../.scratch/home-lan-sync/issues/09-dual-device-foreground-acceptance.md) 为准。

---

## 6. 媒体

### 6.1 两类媒体

| 类 | 关联 | 读 | 写 |
|----|------|----|----|
| **avatar** | Baby | 家庭内 | **仅 owner** |
| **log** | Record（日记等） | 家庭内 | 家庭成员可同步 |

### 6.2 客户端

- 上传前压缩（长边约 1200–2000px，与 ui 规格一致）。
- 本地可先写 `local_uri`；同步后写 `remote` 标识（`client_uuid` / 相对路径）。
- **禁止**把设备绝对路径当同步主键。

### 6.3 服务端

- 元数据进 SQLite；字节进 **同一 `DATA_DIR` 下的 `media/`**。
- 非 owner push `kind=avatar` → **403**。
- 删除媒体：元数据 tombstone + 可选延迟物理删（首版可在 delete family 时统一清文件）。

---

## 7. NAS 后端：技术选型（明确）

### 7.1 选型表

| 层 | 选型 | 理由 |
|----|------|------|
| 语言 | **Rust 2021** | 单二进制、低常驻内存、无解释器运行时，适合 NAS 长期运行 |
| Web 框架 | **Axum** | typed extractor、流式 body 与 Tokio 生态；HTTP interface 易于直接测试 |
| 异步运行时 | **Tokio**（单进程） | family 写路径由应用锁串行，SQLite 事务继续保证原子性 |
| DB 访问 | **rusqlite + bundled SQLite** | 镜像不依赖外部数据库或宿主 SQLite 版本 |
| 鉴权 | Bearer token，哈希存库（如 SHA-256） | 不存明文 token |
| 容器基座 | **Rust builder + `debian:bookworm-slim` runtime** | 最终镜像只含 Rust 二进制和最小运行时 |
| 编排 | **Docker Compose 单服务** | 一家一栈 |
| 反向代理 | **可选**（Caddy/Nginx 做 HTTPS） | 默认不强制；规格预留 |
| 队列 / Redis / Postgres | **不引入（首版）** | 降低 NAS 复杂度 |

**否决（首版）**

| 选项 | 原因 |
|------|------|
| 保留多语言并行实现 | 两套服务器会产生协议漂移和 NAS 误部署；Rust 是唯一交付实现 |
| PostgreSQL 必选 | 家用过重；见决策 11，引擎可替换但不默认 |
| 与 App 同仓强制用 Kotlin 后端 | NAS 部署与镜像生态更差 |
| 多进程 + SQLite | 跨进程 family 锁失效；当前交付保持单进程 |

### 7.2 仓库布局（交付）

```text
tools/
  lezi-sync/                 # NAS 交付后端（规格名）
    Dockerfile
    docker-compose.yml
    Cargo.toml
    Cargo.lock
    README.md                # 部署：端口、DATA_DIR、备份
    src/
      main.rs                # 进程入口与内置 healthcheck
      lib.rs                 # HTTP interface + 安全文件/媒体实现
      model.rs               # 严格 wire 校验
      store.rs               # SQLite 事务、LWW、ACL
    tests/
      api.rs                 # 与客户端相同的 Router/HTTP interface
```

### 7.3 依赖（已实现）

```text
axum + tokio
rusqlite (bundled SQLite)
serde + uuid + hmac/sha2
```

### 7.4 进程与配置

| 变量 | 默认 | 说明 |
|------|------|------|
| `LEZI_DATA_DIR` | `/data` | **唯一数据根**（库 + 媒体） |
| `LEZI_HOST` | `0.0.0.0` | |
| `LEZI_PORT` | `8765` | |
| `LEZI_INVITE_TTL_HOURS` | `24` | |
| `LEZI_MAX_MEDIA_BYTES` | `10485760` | 单媒体大小上限 |

服务进程：`lezi-sync`。容器健康检查调用 `lezi-sync healthcheck`，不依赖 shell、
curl 或解释器。HTTPS 由 NAS 的 Caddy/Nginx/系统反向代理终止；容器端口只暴露
在家庭 LAN 或私有 Docker 网络。

---

## 8. 数据根路径（data 与 media 合并）

**一个目录、一个 volume**，禁止拆成两个互不相关的根路径。

```text
$LEZI_DATA_DIR/                    # 例：/data 或 /volume1/docker/lezi
├── lezi.db                        # SQLite（entities、invites、tokens、rev/cursor 元数据）
├── lezi.db-wal / lezi.db-shm      # 若启用 WAL
├── server.secret                  # token 幂等派生密钥；须保密并随整根备份
└── media/
    └── {family_uuid}/
        └── {media_client_uuid}     # 原始字节
```

### 8.1 Docker Compose 示例

```yaml
services:
  lezi-sync:
    image: lezi-sync:latest   # 或 build: .
    ports:
      - "8765:8765"
    environment:
      LEZI_DATA_DIR: /data
      LEZI_PORT: "8765"
    volumes:
      - lezi_data:/data         # 唯一数据卷
    restart: unless-stopped

volumes:
  lezi_data:
```

绑定主机路径示例（NAS 常见）：

```yaml
volumes:
  - /volume1/docker/lezi:/data
```

### 8.2 备份

拷贝 **整个 `LEZI_DATA_DIR`**（含 `lezi.db*` 与 `media/`）。
不要求在线热备；可文档建议停止容器后拷贝或 SQLite backup API。

---

## 9. HTTP API（契约）

Base：`{baseUrl}`，JSON UTF-8。除 `/health`、`/ready` 外均需 Bearer
（`create`/`join` 按下方说明）。

### 9.1 `GET /health`

- 无鉴权
- `200 {"ok": true, "version": "<semver>", "capabilities": ["atomic_bundle", "record_membership_author"]}`
- `capabilities` 为**加法**字段：旧客户端可忽略；新客户端用
  `atomic_bundle` 识别原子同步包，用 `record_membership_author` 识别 NAS 是否接受并
  权威化 Record 的 membership 作者字段
- 不支持 `atomic_bundle` 的旧 NAS：客户端**不得**把带照片的记录/计划静默降级为 metadata-first 推送；应保留本机并提示升级服务端
- 不支持 `record_membership_author` 的旧 NAS：客户端不得盲发未知
  `created_by_membership_id` key；保留本机作者并发送旧 payload，时间轴仅在新字段
  不可用时回退 legacy device 关联
- 客户端门闩探测用；响应体保持小体积（健康探测上限 64 KiB）

### 9.2 `POST /v1/family/create`

- 门闩：客户端仅在家调用
- Body：`{ "create_request_id", "device_id", "display_name", "family_name?" }`
  - `display_name`：**家庭称呼**，产品层必填；校验同 join
  - `family_name`：共享家庭名，可空；trim 后空则存 null，由客户端兜底展示
  - 客户端在成功落盘会话前必须复用同一高熵 `create_request_id`
- 响应：`{ "family_id", "token", "role": "owner", "membership_id", "generation", "family_name"? }`
  - `membership_id`：服务端生成的不可变 membership UUID；幂等重试返回同一值
- 同一创建请求重试幂等恢复相同响应；一家一栈已有其它创建请求时返回 `409`

### 9.3 `POST /v1/invite`

- Auth：owner token
- Body：`{ "family_id" }`（可从 token 推导则可不传）
- 响应：`{ "code", "expires_at" }`，`expires_at` 为 Unix epoch 秒
- 客户端生成 QR：建议载荷 JSON（含 host/port 与本机已保存的 Wi‑Fi 名，便于对方预填）
  `{ "v":1, "baseUrl":"http://192.168.50.4:8765", "host":"192.168.50.4", "port":8765, "ssids":["Home-2.4G","Home-5G"], "code":"ABCD1234" }`
  - `ssids` 最多 2 个，可选；扫码端写入本机白名单（仍不上传服务器）
  - 兼容旧载荷：仅 `baseUrl`+`code` 或纯邀请码

### 9.4 `POST /v1/join`

- Body：`{ "code", "device_id", "display_name" }`
- `display_name`：**家庭称呼**，产品层必填。服务端 trim；空白返回 `422`（不再
  静默收成 null）；最长 128 个 Unicode 字符；控制字符或 Unicode 双向文本格式
  控制符返回 `422`。客户端本地占位名“我（本机）”**不得**上传
- 响应：`{ "family_id", "token", "role":"member", "membership_id", "entities":[], "cursor":0, "generation", "family_name"? }`
  （首包可空，随后 pull；或 join 时带全量，实现二选一，**须幂等**；幂等重试返回相同 `membership_id`）

### 9.5 `GET /v1/family/members`

- Auth：任一有效 owner/member family token
- 作用域：只查询 Bearer principal 所在家庭且 `left_at IS NULL` 的 membership；
  Bearer credential 本身必须 `revoked_at IS NULL`
- 响应：
  `{"members":[{"display_name":"妈妈","role":"owner","is_self":true,"device_id":"…","membership_id":"…"}]}`
- 返回规范化后的 `display_name`、`role`、`is_self`、服务器生成的 `membership_id`
  （不可变公开身份，供作者权限与冲突裁决引用），以及客户端链路键
  `device_id`（只为旧 NAS/旧 Record 的 `created_by_device_id` 回退保留）。时间轴
  优先以 `membership_id` 解析 Record 作者的**当前**家庭称呼；`device_id` 不承担
  identity authority。`is_self` 由服务端比较返回行与当前 principal 的
  `membership_id` 得出；**绝不**返回 token、`token_hash` 或 `family_id`。
  `device_id` **不得**在产品 UI 中展示给用户
- owner-first；其余按规范化名称与服务端内部稳定键排序。`display_name=null`
  表示历史 null/空/不安全名称，客户端按角色/「家人」兜底（且不得把「我（本机）」
  展示给其他成员）
- membership 与 credential 分表；members 每个 active membership 只展示一行，
  credential 轮换不会生成新身份。旧库仅在迁移事务中按 family + role + device
  归并历史 active token，并保留其它既有 membership ID 为 alias；owner/member
  role 冲突不合并。迁移后绝不按 `device_id` 合并，新 join 即使声明相同 device 也
  创建独立 membership。退出会标记当前 membership 离开并吊销其全部 credentials；
  单 credential 轮换/吊销不改变 membership identity

### 9.5.1 `POST /v1/family/display-name`

- Auth：任一有效 owner/member family token
- Body：`{ "display_name" }` — **仅更新调用者自己的** membership 称呼；校验同
  join（必填、trim、禁「我（本机）」、最长 128、禁控制符/双向控制符）
- 响应：`{ "ok": true, "display_name": "…" }`
- 不能改他人称呼；无成员 id 参数

### 9.5.2 `POST /v1/family/name`

- Auth：**仅 owner** family token（member → `403`）
- Body：`{ "family_name"? }` — 共享家庭名；trim 后空/`null` 存 null（客户端兜底
  展示「我的家庭」/「{宝宝昵称}的家庭」）；最长 64 个 Unicode 字符；控制字符或
  Unicode 双向文本格式控制符 → `422`
- 响应：`{ "ok": true, "family_name": "…" | null }`
- 与 create 的可选 `family_name` 同一规范化规则。改名发起端立即回写本机会话缓存；
  其他已加入成员在下一次允许的前台/下拉 pull 中收敛；不另提供 GET 读路径

### 9.6 `POST /v1/push`

- Auth：member/owner token
- Body：

```json
{
  "device_id": "...",
  "generation": "...",
  "entities": [
    {
      "type": "record",
      "client_uuid": "...",
      "payload": { },
      "updated_at": 0,
      "deleted_at": null
    }
  ]
}
```

- `type`：`baby` | `record` | `media`
- `generation`：客户端已知服务代际；不匹配时服务端先返回结构化 `409`，
  不应用任何实体
- LWW：请求 `updated_at` 小于库中则 skip
- Record 作者是 server-owned field：首次接受新 Record 时，NAS 从认证 principal
  写入 `created_by_membership_id`，并保留 `created_by_device_id` 作为旧客户端回退；
  客户端伪造的 membership/device claim 不会成为作者。后续编辑、软删与恢复保留
  已存首次作者。ordinary push 与 atomic bundle 必须调用同一 canonicalization 规则
- 旧数据只在同家庭的 legacy device 可唯一映射到一个历史 membership 时回填作者；
  回填推进实体与家庭 revision 且重启幂等，重复/未知映射保持 unknown
- avatar 类 media：非 owner → `403`
- 响应：
  `{ "applied": N, "record_authors": [{ "client_uuid": "…", "created_by_membership_id": "…" }] }`
  `record_authors` 是可忽略的加法字段，只列出已知 canonical 作者；equal/LWW skip 也可
  返回已存作者，使建家前本机 Record 无需等待下一次 pull 即可完成 metadata-only 回填

### 9.7 `GET /v1/pull?cursor=&generation=`

- Auth：token
- 响应：
  `{ "entities":[...], "cursor": <本页已扫描 rev>, "generation": "...", "has_more": true|false, "family_name": "…"|null }`
- 新服务端每个成功页（包括零实体页）都返回 NAS 权威、已规范化的 `family_name`。
  客户端要求同轮多页中所有 present 值一致；若并发改名导致页间值变化，本轮失败
  并从最后完整检查点重试，不能静默采用最后一页。客户端必须区分三态：旧 NAS
  省略字段时保留本地缓存；显式 `null` 清空缓存并让 UI 使用「我的家庭」/宝宝昵称兜底；字符串覆盖
  缓存。家庭名与该页 `cursor` / `generation` 在同一次 DataStore edit 中发布，不能
  用整份旧 session 覆盖并发更新的 membership、SSID 等字段。full-resync 延迟发布
  cursor 时，家庭名也随最终完整检查点一起发布；中途失败不发布半轮 metadata。
- 服务端按实体数（默认最多 200）与序列化体积（目标最多 8 MiB）双重分页；
  `has_more=true` 时客户端必须用本页 `cursor` 继续拉取。每页 apply 与缺失媒体落盘
  全部成功后才持久化该页 cursor，任一页失败只重试未完成页。full-resync 在重新
  push 本地副本前的权威拉取阶段例外：页间进度只保存在内存，全部页与成员头像
  权威对账完成后才一次发布 cursor，避免中途重启后过早 push。
- 新实体 payload 需小于页体积目标的三分之一，确保最深的
  Media → Record → Baby 依赖组仍可完整放进一页；超限 push 以 `422` 原子拒绝。
- 全量恢复时，若 Record/Media 所依赖的 Baby/Record 当前 revision 落在后页，
  服务端会在当前页附带该依赖（后页允许幂等重复），避免分页切断引用。
- `has_more` 为向后兼容的增量字段：旧服务端缺少该字段且本页少于 200 个实体时，
  新版客户端按单页处理；满 200 个实体却缺少该字段时失败关闭，不能把可能截断的
  快照当作成功。单轮 pull 最多 500 页，触顶时失败并保留到最后完整落盘页的 cursor。
  旧客户端忽略该字段时仍只推进到本页 cursor，下一次前台/下拉会继续而不会跳过。
- `cursor` 在一个服务进程代际内单调；客户端同时持久化 `cursor` 与 `generation`
- 服务重启会更换 `generation`。客户端携带旧代际时服务端返回结构化 `409`
  `generation_changed/full_resync`，避免备份恢复后 revision 恰好复用而漏拉。
  既有安装若只有非零 cursor、尚无 generation，也会先从 cursor 0 校准。
  因此恢复整个数据根后必须重启服务；普通重启也会触发一次安全的全量校准。

### 9.8 媒体字节

| 方法 | 路径 | 说明 |
|------|------|------|
| `PUT` | `/v1/media/{client_uuid}` | body=bytes；需先/同时有 media 元数据 entity；鉴权 + avatar 规则 |
| `GET` | `/v1/media/{client_uuid}` | 下载；家庭 token |
| `DELETE` | `/v1/media/{client_uuid}` | 可选；或仅走 entity tombstone |

### 9.8.1 原子同步包（`atomic_bundle`）

根实体（`record` 或 `care_plan`）与完整媒体清单只能一起对其它设备可见。旧的
`/v1/push` + `/v1/media` metadata-first 路径**继续兼容**（头像、旧客户端）；
带照片的护理记录/计划应走本协议。

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v1/bundles` | 暂存包：`bundle_id`（客户端 UUID）、`root`、`media[]`、可选 `generation` |
| `PUT` | `/v1/bundles/{bundle_id}/media/{client_uuid}` | 上传清单内媒体字节到暂存区 |
| `POST` | `/v1/bundles/{bundle_id}/commit` | 单事务发布完整包；幂等 |
| `GET` | `/v1/bundles/{bundle_id}` | 查询 `status` / `missing_media` / `staged_media` |

规则摘要：

- **暂存不可见**：commit 前根实体与媒体均不出现在普通 `GET /v1/pull`
- **清单完整**：live media 必须声明正 `byte_size`；commit 前全部字节校验通过；tombstone media 不需字节
- **幂等 commit**：重复 commit / 丢失响应可安全重试；已 commit 的 `bundle_id` 内容冲突 → `409`
- **canonical 回执**：Record 根的首次 commit 与幂等 retry 都使用与 ordinary push
  同形的 `record_authors` 加法数组；旧客户端忽略，Android 只在请求对应的本地版本仍
  存在时合并 membership 作者，不改护理内容、dirty 状态或 Outbox
- **提交者绑定**：暂存包绑定 stage 时认证到的 membership；其它 membership 不得代为
  commit，从而保证 server-owned Record/CarePlan 作者、内容 hash 与幂等重试一致
- **稳定 UUID**：Android 以命名空间、根类型、根实体 `client_uuid` 与 `updated_at` 确定性生成合法 UUID；同一版本重试复用同一 `bundle_id`，Record 与 CarePlan 不共享身份
- **旧版本保留**：新版本编辑在 commit 前不覆盖已发布完整版本；根 `updated_at` 落后于已发布 → commit `409`
- **LWW 与 legacy**：commit 与 `/v1/push` 共享实体键 LWW；不得用半套 legacy 写穿破原子可见性
- **根类型通用**：`record` 与 `care_plan` 共用同一 HTTP/Store 契约
- **CarePlan ACL**：创建时服务端从认证 principal 盖章 `created_by_membership_id`（忽略客户端伪造）；任意成员可创建；普通成员仅可修改/跳过/删除自己创建的计划，管理员可管理全部；作者离开后管理员仍可管理。计划媒体引用、宝宝、具体项目与家庭必须一致，跨家庭引用以冲突错误拒绝
- **不经 legacy push**：`care_plan` 不得走 `/v1/push`，必须经 atomic bundle，避免半套包
- **履行候选**（`fulfillment_candidate`，legacy push）：任意活动成员可提交；服务端在候选首次接受时固定认证 `submitter_membership_id`、`submitter_role` 与不可编辑 `confirmed_at`，后续请求/幂等重放不得改写；客户端用盖章字段按管理员 → 较早确认时间 → 候选 UUID 裁决唯一权威事实，落选标记 conflict-not-adopted 并排除于普通记录表面；管理员本机冲突审计与「转为独立记录」不改写候选盖章字段，也不通过 wire 同步 `adoptionStatus` / `convertedRecordClientUuid`
- **旧客户端**：未知 `entity_type`（含 `care_plan` / `fulfillment_candidate`）应忽略整行，不得半应用
- **暂存上限**：每包最多 8 个 media；每家庭最多 64 个 open staging bundle（防 NAS 磁盘无界）

### 9.9 `POST /v1/family/delete`

- Auth：owner
- 多重确认由客户端 UI；服务端执行后清空 entities、tokens、invites，并删除 `media/` 下文件（保留空目录）。

### 9.10 `POST /v1/leave`

- Auth：member token；owner 调用返回 `403`
- 标记当前 canonical membership 离开，并原子吊销指向它的全部 credentials；
  **不**删家庭数据。单 credential 轮换/吊销不改变 membership。owner 必须使用
  `/v1/family/delete`，首版不提供“停止共享但保留无管理员家庭”的语义。

---

## 10. Entity payload 约定（首版）

### 10.1 `baby`

```json
{
  "nickname": "...",
  "sex": "...",
  "birthday": "...",
  "due_date": null,
  "avatar_media_uuid": null
}
```

- 不含本机 `avatarPath`
- `sort_order` 与 `theme_color` 为本机展示字段，不进入 wire payload
- `avatar_media_uuid` 指向 `type=media` 且 `kind=avatar` 的实体
- `due_date` 仅为滚动升级兼容字段；现行客户端不采集、展示或参与计算，
  Android 仍发送旧值或 `null`，避免新旧 APK 与 NAS 服务混用时破坏同步

### 10.2 `record`

```json
{
  "baby_client_uuid": "...",
  "type": "formula",
  "timestamp": 0,
  "end_timestamp": null,
  "note": null,
  "payload_json": {},
  "created_by_membership_id": "...",
  "created_by_device_id": "..."
}
```

- `created_by_membership_id` 是 NAS 在首次接受 Record 时从认证 principal 盖章并在
  后续版本中冻结的作者；客户端字段只是可被忽略/改写的 claim
- `created_by_device_id` 只为旧 NAS/旧实体回退保留，不用于 membership authority
- Android 仅在 health capability 含 `record_membership_author` 时发送 additive
  membership key；旧服务缺 capability 时省略该 key

### 10.3 `media`

```json
{
  "kind": "log" | "avatar",
  "record_client_uuid": null,
  "baby_client_uuid": null,
  "mime": "image/jpeg",
  "width": 0,
  "height": 0,
  "byte_size": 0
}
```

- `kind=log` → 需 `record_client_uuid`
- `kind=avatar` → 需 `baby_client_uuid`；写限 owner

---

## 11. 客户端产品与 UI 要点

| 项 | 要求 |
|----|------|
| 服务器 | host+端口（空态预填 192.168.50.4:8765）+ SSID 白名单≤2（预填当前 SSID）；扫码可填入 host+port+code 与可选 SSID≤2 |
| 建家 | 输入 NAS 部署时设置的一次性初始化口令；仅随本次请求发送，结束后立即清除 |
| 引导 | 账户**首屏**为家庭概览（家庭名、成员人数、宝宝、一句结果向同步状态）；未加入用「新建/加入」家庭向导；网络/SSID/技术原因在**网络设置**二次界面；不用首屏三步条或独立 PRD 说明段落 |
| 身份 | 建家/加入硬必填**家庭称呼**（自由文本，引导「我是宝宝的？」）；共享**家庭名**仅 owner 可改；成员列表管理员标 ★；见 ADR-0002 |
| 共享范围 | 加入前用结构化元素展示“育儿记录与日志图片会共享、个人设置留本机”；不用整段警示文字 |
| 下拉 | 记录页下拉 → 若 allowSync 则 pull+push |
| 错误 | health 失败 → 「无法连接家庭服务器，请确认在家中 Wi‑Fi」；禁止堆栈/IP 英文裸奔为主文案；首屏只用结果向短句，细节进网络设置 |
| 账户页 | 概览：家庭名、人数（点进成员名单）、宝宝、状态胶囊、管理员「邀请家人」；网络设置内：上次成功时间、立即同步、退出、owner 删除家庭 |
| 时间轴 | 非本人记录展示上传者当前家庭称呼；未加入家庭不展示 |

---

## 12. 安全

| 项 | 要求 |
|----|------|
| 部署 | **不**将端口无鉴权暴露到公网；家用局域网 |
| Token | 高熵；仅哈希落库；leave/delete 可吊销 |
| 邀请码 | 短 TTL；使用次数可限制（默认 1～N） |
| HTTP | 默认家网 HTTP 的 bearer 与内容可被同网攻击者嗅探；需要机密性时由 NAS 反代终止 TLS。未启用 TLS 的公网/远程暴露不在支持范围 |
| 日志 | 服务端不打印 note/payload 健康明细到 stdout 默认级别 |
| 访客 Wi‑Fi | 无 token 不能 pull |

---

## 13. 与旧文档 / 原型的关系

| 项 | 关系 |
|----|------|
| `data-model.md` SyncPort | 仍成立；V2 规则以 **本文** 为网络与触发权威 |
| 旧「约 60s 可见」 | **废止为后台 SLA**；改为 §5.4 前台验收 |
| `tools/lezi-sync` | 本文 NAS API 的交付实现；本机 Docker 已验，物理 NAS 生产部署未宣称 |
| `RealSyncPort` | 已对齐持久会话、无默认 baseUrl、家网/前台门闩、Outbox 与媒体 |
| 双端 P2P | 不在范围 |

---

## 14. 分期实现清单（工程）

> **票单（issue tracker）：** [`.scratch/home-lan-sync/ISSUES.md`](../../.scratch/home-lan-sync/ISSUES.md)

### 14.1 NAS `lezi-sync`

- [x] Rust/Axum 项目骨架 + 多阶段 Dockerfile + compose（静态单卷 `/data` 配置）
- [x] SQLite schema：entities、invites、tokens、meta.rev
- [x] health / create / invite / join / members / push / pull / media / leave / delete
- [x] avatar 写权限
- [x] README：部署、备份、示例 `192.168.50.4:8765`
- [x] 在本机全局 Docker 构建并启动 Rust 镜像，核对单卷、非 root、`/health`
  与旧数据卷升级兼容（未宣称 NAS 生产）

### 14.2 Android

- [x] `HomeNetworkPolicy` + 持久化 SyncPreferences
- [x] 前台触发器（ON_START、下拉、写成功 push）
- [x] Outbox 覆盖 baby/record/media；`baby_client_uuid` 引用
- [x] 媒体上传下载与压缩
- [x] 账户 UI：服务器配置、相机扫码/粘贴载荷、状态文案
- [x] 默认 DI：未配置时 NoOp/Disabled，禁止开发地址写死为生产默认

### 14.3 测试

- [x] 服务端 LWW / token / avatar 403
- [x] 客户端门闩：非 Wi‑Fi 不请求
- [x] 客户端 Fake backend / mock health 前台同步与媒体自动化
- [ ] 完成 Ticket 09 剩余设备 Must：相机扫码、日志图跨端 UI、蜂窝/回家冲刷、
  通知与独立设置（formula/pee 双模拟器路径已通过）

---

## 15. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-07-26 | 严格 live 服务端完成双模拟器建家、邀请码加入、双向协议与头像 ACL；Ticket 09 仍 partial |
| 2026-07-25 | NAS 服务原位迁移为 Rust/Axum/Tokio/rusqlite；保留 HTTP、SQLite 与 token 派生兼容，删除重复原型入口 |
| 2026-07-25 | 验收校正：本机 Docker 与双模拟器 formula/pee 已验；Ticket 09 保持 partial |
| 2026-07-25 | 实现收口：Android、`tools/lezi-sync` 与自动化完成；明确 NAS/完整设备环境验收待补 |
| 2026-07-25 | grilling 锁定 Wi‑Fi/NAS 策略；DATA_DIR 合并 db+media；写入本规格 |
