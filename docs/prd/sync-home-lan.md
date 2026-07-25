# 乐记 — 家庭局域网同步规格（V2）

> 决策锁定：2026-07-25（grilling）
> 主 PRD：[`README.md`](./README.md) · 数据契约：[`data-model.md`](./data-model.md) · Android：[`tech.md`](./tech.md)
> 本文定义 **中心化 NAS + 仅前台 + 硬家庭 Wi‑Fi** 的完整架构与 NAS Docker 后端。
>
> **交付状态（2026-07-25）：** Android 与 `tools/lezi-sync` 实现及自动化已完成；
> 本机 rootless Docker 和双模拟器 formula/pee 前台交叉可见已验证。Ticket 09
> 仍为 partial：相机扫码、日志图跨端 UI、蜂窝/回家冲刷、通知与独立设置待验；
> 不宣称 NAS 生产部署或全部设备级 Must 通过。

---

## 0. 一句话

本地 Room 为 UI 真相源；在 **家庭 Wi‑Fi 且 NAS 可达** 时，**仅前台** 用 **family token** 与 NAS 上的 **`lezi-sync`（Docker）** 做中心化同步。无 P2P、无后台同步、无伴侣即时通知。

---

## 1. 已锁定决策

| # | 主题 | 结论 |
|---|------|------|
| 1 | 网络门闩 | **硬家庭局域网**：当前为 Wi‑Fi，且配置的 NAS `/health` 可达，才允许一切 NAS API |
| 2 | 鉴权 | **邀请码进门** + 长期 **family token**；无 token → 401/403 |
| 3 | 建家 / 发码 / 加码 / 同步 | **全部** 受门闩约束（仅在家） |
| 4 | 传输 | 默认 **HTTP + token**；镜像预留可选 HTTPS（环境变量证书） |
| 5 | 同步实体（首版） | **Baby + Record + 日志 MediaAsset** |
| 5b | 写权限 | **宝宝头像：仅管理员（owner）**；**日志媒体：家庭内可同步** |
| 6 | 媒体字节 | NAS 本地文件；API 上传/下载；DB 只存元数据 |
| 6b | 落盘布局 | **data 与 media 同一数据根路径**（单 volume） |
| 7 | 多家庭 | **一家一栈** 交付；schema 保留 `family_id`，不多租户产品化 |
| 8 | 触发 | **回前台 + 下拉 + 前台写成功后 push**；无后台、无 60s 定时、无推送拉同步 |
| 9 | 服务器地址 | **客户端无内置默认 baseUrl**；须配置；**邀请 QR 含 baseUrl + code**；可手改 |
| 10 | 退出 / 删除 | member 可退出且不删 NAS；首版无管理员转移，owner 不能 leave，只能二次确认后**删除家庭数据**；本机清除默认仅本地 |
| 11 | 持久化引擎 | 首版 **SQLite**；API 不绑死引擎，可替换 |
| 12 | 拓扑 | **仅中心化 NAS**；P2P 不在范围内 |

**明确不做**

- 后台 WorkManager / 闹钟轮询同步
- 为同步常驻 FGS / WakeLock
- 伴侣新记录推送 / FCM 拉起同步
- 公网强制云、强制账号体系
- 字段级部分共享
- 首版 CustomItemDef / CalendarEvent 同步（后置）

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
| `HomeNetworkPolicy` | `TRANSPORT_WIFI` + `GET {baseUrl}/health` 成功 → `allowSync` |
| `SyncPort` | push / pull / invite / join / leave / media 协调 |
| `Outbox` | 待上行实体队列（baby / record / media 元数据） |
| `SyncPreferences` | `baseUrl`、`familyId`、`familyToken`、`deviceId`、`pullCursor`、`pullGeneration`（**持久化**） |
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

**允许调用 NAS** 当且仅当同时满足：

1. 用户已保存 **非空 `baseUrl`**，且已持有有效会话（加入后的 token；创建家庭后的 token）。
2. 活跃网络具备 **`NetworkCapabilities.TRANSPORT_WIFI`**（不要用蜂窝兜底）。
3. `GET {baseUrl}/health` 在短超时内（建议 ≤3s）返回成功。
4. App 处于 **前台**（对自动触发而言）；用户下拉可视为前台手势。

**失败策略**

- health 失败：指数退避（例如 30s → 2min → 10min），**禁止**固定高频 ping。
- 不因同步失败回滚 Room 写入。

**不采用** SSID 作为主判定（可选高级「绑定 BSSID」，非必须）。

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
| | CustomItem / CalendarEvent（后置） |

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

当前证据仅闭合双模拟器 formula/pee 前台路径；完整设备级状态以
`.scratch/home-lan-sync/issues/09-dual-device-foreground-acceptance.md` 为准。

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

Base：`{baseUrl}`，JSON UTF-8。除 `/health` 外均需 Bearer（`create`/`join` 按下方说明）。

### 9.1 `GET /health`

- 无鉴权
- `200 {"ok": true, "version": "<semver>"}`
- 客户端门闩探测用

### 9.2 `POST /v1/family/create`

- 门闩：客户端仅在家调用
- Body：`{ "create_request_id", "device_id", "display_name?" }`；客户端在成功
  落盘会话前必须复用同一高熵 `create_request_id`
- 响应：`{ "family_id", "token", "role": "owner", "generation" }`
- 同一创建请求重试幂等恢复相同响应；一家一栈已有其它创建请求时返回 `409`

### 9.3 `POST /v1/invite`

- Auth：owner token
- Body：`{ "family_id" }`（可从 token 推导则可不传）
- 响应：`{ "code", "expires_at" }`，`expires_at` 为 Unix epoch 秒
- 客户端生成 QR：建议载荷 JSON
  `{ "v":1, "baseUrl":"http://192.168.50.4:8765", "code":"ABCD1234" }`

### 9.4 `POST /v1/join`

- Body：`{ "code", "device_id", "display_name?" }`
- 响应：`{ "family_id", "token", "role":"member", "entities":[], "cursor":0, "generation" }`
  （首包可空，随后 pull；或 join 时带全量，实现二选一，**须幂等**）

### 9.5 `POST /v1/push`

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
- avatar 类 media：非 owner → `403`
- 响应：`{ "applied": N }`

### 9.6 `GET /v1/pull?cursor=&generation=`

- Auth：token
- 响应：`{ "entities":[...], "cursor": <rev>, "generation": "..." }`
- `cursor` 在一个服务进程代际内单调；客户端同时持久化 `cursor` 与 `generation`
- 服务重启会更换 `generation`。客户端携带旧代际时服务端返回结构化 `409`
  `generation_changed/full_resync`，避免备份恢复后 revision 恰好复用而漏拉。
  既有安装若只有非零 cursor、尚无 generation，也会先从 cursor 0 校准。
  因此恢复整个数据根后必须重启服务；普通重启也会触发一次安全的全量校准。

### 9.7 媒体字节

| 方法 | 路径 | 说明 |
|------|------|------|
| `PUT` | `/v1/media/{client_uuid}` | body=bytes；需先/同时有 media 元数据 entity；鉴权 + avatar 规则 |
| `GET` | `/v1/media/{client_uuid}` | 下载；家庭 token |
| `DELETE` | `/v1/media/{client_uuid}` | 可选；或仅走 entity tombstone |

### 9.8 `POST /v1/family/delete`

- Auth：owner
- 多重确认由客户端 UI；服务端执行后清空 entities、tokens、invites，并删除 `media/` 下文件（保留空目录）。

### 9.9 `POST /v1/leave`

- Auth：member token；owner 调用返回 `403`
- 吊销**本 device** 或本 token；**不**删家庭数据。owner 必须使用
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

### 10.2 `record`

```json
{
  "baby_client_uuid": "...",
  "type": "formula",
  "timestamp": 0,
  "end_timestamp": null,
  "note": null,
  "payload_json": {},
  "created_by_device_id": "..."
}
```

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
| 服务器 | 首次无默认；表单填写 baseUrl；扫码可填入 baseUrl+code |
| 文案 | 「仅在连接家庭 Wi‑Fi 且能访问家庭服务器时同步」 |
| 文案 | 「不会在对方记录时推送通知；打开乐记后更新」 |
| 下拉 | 记录页下拉 → 若 allowSync 则 pull+push |
| 错误 | health 失败 → 「无法连接家庭服务器，请确认在家中 Wi‑Fi」；禁止堆栈/IP 英文裸奔为主文案 |
| 账户页 | 同步状态、上次成功时间、立即同步、生成邀请、退出、owner 删除家庭 |

---

## 12. 安全

| 项 | 要求 |
|----|------|
| 部署 | **不**将端口无鉴权暴露到公网；家用局域网 |
| Token | 高熵；仅哈希落库；leave/delete 可吊销 |
| 邀请码 | 短 TTL；使用次数可限制（默认 1～N） |
| HTTP | 默认可接受于硬家网；可选 TLS |
| 日志 | 服务端不打印 note/payload 健康明细到 stdout 默认级别 |
| 访客 Wi‑Fi | 无 token 不能 pull |

---

## 13. 与旧文档 / 原型的关系

| 项 | 关系 |
|----|------|
| `data-model.md` SyncPort | 仍成立；V2 规则以 **本文** 为网络与触发权威 |
| 旧「约 60s 可见」 | **废止为后台 SLA**；改为 §5.4 前台验收 |
| `tools/lezi-sync` | 本文 NAS API 的交付实现；Docker/NAS 运行仍需有容器环境验收 |
| `RealSyncPort` | 已对齐持久会话、无默认 baseUrl、家网/前台门闩、Outbox 与媒体 |
| 双端 P2P | 不在范围 |

---

## 14. 分期实现清单（工程）

> **票单（issue tracker）：** [`.scratch/home-lan-sync/ISSUES.md`](../../.scratch/home-lan-sync/ISSUES.md)

### 14.1 NAS `lezi-sync`

- [x] Rust/Axum 项目骨架 + 多阶段 Dockerfile + compose（静态单卷 `/data` 配置）
- [x] SQLite schema：entities、invites、tokens、meta.rev
- [x] health / create / invite / join / push / pull / media / leave / delete
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
| 2026-07-25 | NAS 服务原位迁移为 Rust/Axum/Tokio/rusqlite；保留 HTTP、SQLite 与 token 派生兼容，删除重复原型入口 |
| 2026-07-25 | 验收校正：本机 Docker 与双模拟器 formula/pee 已验；Ticket 09 保持 partial |
| 2026-07-25 | 实现收口：Android、`tools/lezi-sync` 与自动化完成；明确 NAS/完整设备环境验收待补 |
| 2026-07-25 | grilling 锁定 Wi‑Fi/NAS 策略；DATA_DIR 合并 db+media；写入本规格 |
