# lezi-sync

乐记家庭局域网同步服务的 Rust 实现。Android 端使用当前 `/v1/*`
HTTPS interface；服务端以 Axum + Tokio + rustls + rusqlite 运行，NAS 上只需要一个
Docker 容器和一个持久化目录。

## 源码布局（crate-private）

公开 crate 入口仍由 `build_app` / `build_apps` 保持兼容；主进程通过
`build_server_apps` / `ServerApps` 取得 HTTPS、内部 readiness 和可选 LAN 首装 Router。
`ServerConfig` 统一验证三个面的配置；路由组装与 `AppState` 留在 `src/lib.rs`。
Route handlers 按职责落在 crate-private 模块，**不**扩大
公开 API surface：

| 路径 | 职责 |
|---|---|
| `src/lib.rs` | `AppState`、共享鉴权/引导、`build_server_apps` 三路 Router 组装（按 `handlers::<domain>::…` 组装，无 barrel 转发清单） |
| `src/handlers/{health,app_update,lan_apk,identity,sync,media}.rs` | `/health`、已加入更新、隔离邀请首装、建家/登录/会话、pull、media/bundle；`media` 另含 media-root 启动清理 |
| `src/members.rs` | 家庭成员与设备管理路由（既有内聚，不回并） |
| `src/readiness.rs` | `/ready` 与 readiness 缓存 |
| `src/store/{mod,schema,identity/*,pull,media,bundles}.rs` | SQLite 持久化；单一 `Store` 事务 façade；identity 再按 session/login/membership_admin/anonymize 分区；bundle 行与 LWW 加载器在 `bundles` |
| `src/offline_migrate/` | 离线 v3→current 维护工具（非 live HTTP API；不并入 runtime store） |

HTTPS `/v1/*` 合同只对成员登录 grant 增加可选 `landing_url`；新的 8767 HTTP
只有 `/join` 和 `/download/lezi.apk`。本 README 不宣称 live NAS 已验证。

## 数据目录合同

服务仅支持 **fresh-current** 部署，当前 SQLite `PRAGMA user_version=11`。空数据目录、
不存在的 `lezi.db` 或零字节空库会初始化为当前 v11 schema；已有数据目录只有在
`user_version=11` 且表、索引、约束完全匹配当前 schema 时才允许重启并保留数据。

任何非空旧版本、未来版本、或声称 v11 但形状不匹配的数据库都在只读预检阶段
fail closed；不会原位迁移，不会创建 `media/`、`server.secret`、SQLite sidecar，也不会
改变数据根或数据库权限。旧版本数据不是受支持的日常部署输入；部署时必须选择新的空数据根
（历史 v3 仅允许维护窗前的离线 `offline-migrate` + 已授权切割，见下文与 ADR-0013）。

```text
$LEZI_DATA_DIR/
├── lezi.db
├── lezi.db-wal / lezi.db-shm
├── server.secret
├── tls/
│   ├── server.crt
│   └── server.key
└── media/
    └── {family_uuid}/{media_uuid}
```

启动时服务只清理 `media/` 下名称为 UUID、且 SQLite 已无对应家庭的孤儿目录。
非 UUID 运维目录、仍存在的家庭目录和符号链接不会被启动清理触碰。

## NAS / Docker Compose

SSH 自动部署（打包 + scp + 极空间 **zdocker 自带 compose**，secret 从现网容器继承）见
[`deploy/DEPLOY.md`](deploy/DEPLOY.md)：

```bash
# 可选：先构建镜像
LEZI_SYNC_VERSION=0.3.5 ./build-image.sh
./deploy/push-and-deploy.sh
```

默认镜像为 `lezi-sync:0.3.5`。**默认安全基线：**

| 项 | 默认 | 说明 |
|---|---|---|
| 容器用户 | `10001:10001`（`lezi`） | 非 root |
| 主机端口映射 | `127.0.0.1:8765:8765` | 仅 loopback；手机经反代或显式覆盖访问 |
| TLS identity | `/data/tls/` | 自签名证书和私钥随数据卷持久化；不进入镜像或 release manifest |
| 引导密钥 | Compose 必填 | 缺失或空值时 Compose 拒绝启动（见下） |

普通 NAS CD、回滚和容器重启不得替换已有 TLS identity；下面的 `init-tls.sh` 生成路径只用于
经确认的全新空数据根。已有家庭的数据根若证书缺失、无效、错配或容器内不可读，应停止部署，
不得借 CD 自动补证书。部署前后完整证书文件 SHA-256 与 SPKI 必须完全一致；完整约束见
[`deploy/DEPLOY.md`](deploy/DEPLOY.md) § TLS identity。

```bash
cd tools/lezi-sync
./build-image.sh

# 数据目录需可被 uid 10001 写；示例：
mkdir -p /volume1/docker/lezi
sudo chown -R 10001:10001 /volume1/docker/lezi

# Compose 必填：长随机 bootstrap（≥16 字符）。APK 建家时输入同一一次性口令。
export LEZI_BOOTSTRAP_SECRET="$(openssl rand -hex 24)"
export LEZI_DATA_HOST_PATH=/volume1/docker/lezi

# 仅首次、已确认全新空数据根：显式授权生成；以后不设置该变量，只验证并复用。
LEZI_ALLOW_TLS_BOOTSTRAP=1 \
  ./deploy/init-tls.sh "${LEZI_DATA_HOST_PATH}" lezi-sync:0.3.5 nas.example.lan

docker compose up -d
docker compose ps
curl --cacert "${LEZI_DATA_HOST_PATH}/tls/server.crt" -fsS https://127.0.0.1:8765/health
curl --cacert "${LEZI_DATA_HOST_PATH}/tls/server.crt" -fsS https://127.0.0.1:8765/ready
```

### 端口发布覆盖

默认只绑定宿主 loopback。手机要直连时，显式发布 LAN/全接口（仍勿映射公网）：

```bash
# 仅示例：192.168.50.4 是这台宿主的 LAN IP。
LEZI_SYNC_PUBLISH=0.0.0.0:8765 \
  LEZI_LAN_APK_DOWNLOAD_PUBLISH=0.0.0.0:8767 \
  LEZI_LAN_APK_DOWNLOAD_ORIGIN=http://192.168.50.4:8767 \
  LEZI_DATA_HOST_PATH=/volume1/docker/lezi \
  LEZI_BOOTSTRAP_SECRET=... \
  docker compose up -d
```

8765 本身只提供 TLS；不要在其前方增加会把家庭 API 明文发布到 LAN 的反向代理。
8767 是另一个有意使用明文 HTTP 的邀请首装面；`ORIGIN` 必须写扫码手机可达的同一宿主 LAN IP，
不能写 `127.0.0.1`。

### NAS 无法 chown 时的 root profile（非默认）

部分 NAS 绑定目录无法改成 uid 10001。**不要改默认服务**，改用独立 profile
（与默认 `lezi-sync` 二选一，勿共用同一数据目录同时启动）：

```bash
LEZI_DATA_HOST_PATH=/volume1/docker/lezi \
  LEZI_BOOTSTRAP_SECRET=... \
  docker compose --profile nas-root up -d lezi-sync-nas-root
```

该 profile 以 `user: "0:0"` 运行，并补回 `DAC_OVERRIDE`/`FOWNER` 以兼容
NAS ACL；这是刻意隔离的兼容路径，不是默认安全基线。

Synology、QNAP 或其它 NAS 的数据路径不同，只需把 `LEZI_DATA_HOST_PATH`
换成实际目录。Compose 只挂载该目录到 `/data`，默认路径删除全部 capabilities
并启用 `no-new-privileges`。不要开启特权模式，也不要挂载 Docker socket 或其它
宿主目录。

服务会把私有目录和文件收紧为 `0700`/`0600`。默认
`LEZI_ALLOW_PERMISSION_HARDENING_SKIP=0`，任何 `chmod` 失败都会 fail-closed。
只有先确认 NAS 挂载确实可创建、读取和写入文件，但其 ACL/共享文件系统单独拒绝
`chmod` 时，才显式设置 `LEZI_ALLOW_PERMISSION_HARDENING_SKIP=1`；此时仅
`EPERM`、`EACCES`、`EOPNOTSUPP` 会记录警告后继续。只读挂载以及实际持久化 I/O
失败无论是否开启 skip 都会阻止启动。

如果 NAS 不适合本机编译，可在开发机导出镜像：

```bash
docker save lezi-sync:0.3.5 | gzip > lezi-sync-0.3.5.tar.gz
# 把 tar.gz 复制到 NAS 后：
gzip -dc lezi-sync-0.3.5.tar.gz | docker load
```

构建脚本只把 Cargo 清单、锁文件、Dockerfile 与 `src/` 放进临时构建上下文，
不会读取宿主机上可能由容器 uid 拥有的 `data-*` 目录。

## 多架构镜像

在已配置 buildx 的机器上可直接为常见 NAS 架构构建：

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --build-arg LEZI_SYNC_VERSION=0.3.5 \
  -t your-registry/lezi-sync:0.3.5 \
  --push .
```

不使用 registry 时，在目标 NAS 上运行 `./build-image.sh` 会自动构建其原生
架构镜像。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `LEZI_DATA_DIR` | `/data` | SQLite、密钥和媒体的唯一数据根 |
| `LEZI_HOST` | `0.0.0.0` | 容器内监听地址（宿主暴露面由 compose 端口映射控制） |
| `LEZI_PORT` | `8765` | 监听端口 |
| `LEZI_INTERNAL_PORT` | `8766` | 仅监听 `127.0.0.1` 的容器内 HTTP readiness 端口，不发布到宿主 |
| `LEZI_TLS_CERTFILE` | 必填 | PEM certificate；NAS 包固定为 `/data/tls/server.crt` |
| `LEZI_TLS_KEYFILE` | 必填 | PEM private key；NAS 包固定为 `/data/tls/server.key` |
| `LEZI_SYNC_VERSION` | `0.3.5` | `/health` 返回的版本 |
| `LEZI_LAN_APK_DOWNLOAD_ORIGIN` | 未设置 | 可选 `http://<同一 IPv4 或解析到 IPv4 的 DNS 主机>:8767`；设置后成员登录 QR 包装为邀请安装页 URL，本版不支持 IPv6 分发 |
| `LEZI_LAN_APK_DOWNLOAD_PUBLISH` | `127.0.0.1:8767` | local compose 宿主侧发布地址；NAS 包固定 LAN `0.0.0.0:8767` |
| `LEZI_MAX_MEDIA_BYTES` | `10485760` | 单个媒体最大字节数 |
| `LEZI_BOOTSTRAP_SECRET` | Compose 必填；`cargo run` 可空 | 唯一 Owner 根密码；create、Owner 登录/接管要求同值 `X-Lezi-Bootstrap-Secret`；Compose 缺失或空值时拒绝启动 |
| `LEZI_CREATE_RATE_LIMIT` | `20` | 每台 device 每窗口的 create 尝试上限 |
| `LEZI_MEMBER_REQUEST_RATE_LIMIT` | `10` | 每个来源地址每窗口的成员申请上限 |
| `LEZI_MEMBER_REQUEST_TTL_HOURS` | `24` | 成员申请有效期；当前协议固定为 24 |
| `LEZI_MAX_PENDING_MEMBER_REQUESTS` | `32` | 单家庭最多开放的 pending + approved-unclaimed 成员申请数 |
| `LEZI_RATE_LIMIT_WINDOW_SECONDS` | `60` | create/成员申请限流窗口秒数 |
| `LEZI_SYNC_PUBLISH` | `127.0.0.1:8765` | compose 宿主侧发布地址（仅 docker compose） |
| `LEZI_ALLOW_PERMISSION_HARDENING_SKIP` | Compose `0`；`cargo run` 未设置 | 仅显式设为 `1` 时，chmod 在 EPERM/EACCES/EOPNOTSUPP 上 warn 并继续；默认 fail-closed |

8765 只监听 HTTPS。容器内另有仅 loopback 可见的 readiness HTTP 8766
供 `HEALTHCHECK` 使用，不映射到宿主。可选 LAN HTTP 8767 只提供 `/join` 和
`/download/lezi.apk`，不提供 `/v1`、health 或 ready。NAS 打包默认按
`LEZI_TLS_HOST` 启用该 origin；直接 `cargo run` 不设置时则不监听 8767。明文分发可被同网段中间人
替换页面/APK 并窃取 QR fragment；这是 ADR-0015 记录的显式风险接受。不要把 8765/8767 映射到公网。

### 生产 Owner 根密码（fail-closed）

- **Compose 部署必须设置** `LEZI_BOOTSTRAP_SECRET`（≥16 字符随机串）；缺失或
  空值时 `docker compose` 会在启动前报错。
- 本地 `cargo run` 仍可不设置，便于开发；此时服务会打 warn，空库上的 create
  接口对本机网络开放，不能作为生产配置。
- 设置后，建家请求需额外请求头：

```http
POST /v1/family/create
X-Lezi-Bootstrap-Secret: <same as LEZI_BOOTSTRAP_SECRET>
Content-Type: application/json
```

- Android 建家、Owner 新设备登录和接管时输入同一根密码；客户端仅将其短暂放入
  对应请求头，不写入 session、SavedState、邀请载荷或本地持久化。缺失或错误时失败并
  允许重新输入。根密码从不作为日常 API Bearer。
- `POST /v1/family/create` 只用于空服务器初始化；实例配置后，即使根密码正确也固定
  返回冲突，不能借 create 接回管理员或重新签发凭证。管理员新设备登录与接管使用
  独立的认证路径。
- 保持数据目录不变，仅更换部署环境中的 `LEZI_BOOTSTRAP_SECRET` 并重启，会在启动事务中
  撤销全部旧 Owner DeviceSession；Member DeviceSession 保持有效。App 不提供查看或修改根密码。

## 本地开发

```bash
cargo fmt --all -- --check
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings

tmp_data="$(mktemp -d)"
LEZI_ALLOW_TLS_BOOTSTRAP=1 LEZI_TLS_USE_HOST_OPENSSL=1 \
  ./deploy/init-tls.sh "${tmp_data}" ignored localhost
LEZI_DATA_DIR="${tmp_data}" \
  LEZI_TLS_CERTFILE="${tmp_data}/tls/server.crt" \
  LEZI_TLS_KEYFILE="${tmp_data}/tls/server.key" \
  cargo run --release
```

证书生成、替换、过期、错配、TOFU 变化和证书重连测试必须沿用这类临时数据根和开发者自建
服务，并使用非生产端口；禁止把 `tmp_data` 或测试配置改指向真实家庭 NAS、现网容器或其
数据 bind。真实家庭 NAS 只允许只读检查证书/SAN/公开指纹和比较 CD 前后完整证书哈希与 SPKI。

容器健康检查调用同一个 Rust 二进制：

```bash
lezi-sync healthcheck
```

## HTTP interface

除 `/health`、`/ready`、`/v1/setup-status`、`/v1/family/create`、`/v1/owner/login`、
`/v1/owner/takeover`、`/v1/session/refresh` 以及成员申请方使用 pending
secret 的 request/status/cancel/claim 外，接口都要求
`Authorization: Bearer <device-session-access-token>`。`create` 与成员申请受进程内速率限制；
生产启动必须配置高熵随机 `LEZI_BOOTSTRAP_SECRET`，`create` 还要求匹配的 bootstrap 头。
错误根密码/bootstrap 进入按来源分桶的失败预算，达到上限后返回 429；成功建家另有独立
预算和局部上限 10 倍的全局兜底。无效凭据不消耗成功建家预算。家庭已配置后，create 对
正确、错误或缺失口令返回同一冲突，避免口令 oracle。

| 方法 | 路径 | 摘要 |
|---|---|---|
| GET | `/health` | 廉价进程存活检查，正常 `{ok, version, capabilities:["atomic_bundle","record_membership_author"]}`，不访问 DB/文件系统 |
| GET | `/ready` | DB 与数据目录就绪检查；结果缓存 5 秒，异常返回 `503 {ok:false,status:"degraded",version}` |
| GET | `/v1/setup-status` | 可信连接后的最小无鉴权探测；就绪时只返回 `protocol_version`、完整 trusted-sync capabilities（`trusted_https_endpoint_v1`、`device_sessions_v1`、`membership_devices_v1`、`atomic_bundle`、`record_membership_author`）与 `family_state:empty\|configured`，维护中返回无正文 503 |
| POST | `/v1/family/create` | 仅空服务器可用；根密码幂等创建唯一家庭、Owner membership、首台 Device 与 DeviceSession |
| POST | `/v1/owner/login` | configured 家庭用根密码幂等新增一个 Device 到唯一 Owner membership；旧 Owner Device 不受影响 |
| POST | `/v1/owner/takeover` | 明确接管：原子撤销全部旧 Owner DeviceSession 后为当前 Device 签发 session；Member session 不受影响 |
| POST | `/v1/session/refresh` | 用当前 refresh credential 原子轮换 access/refresh；请求不携带 access Bearer |
| POST | `/v1/member/requests` | 无鉴权提交家庭称呼与设备称呼；返回仅限该申请的 pending secret，24 小时失效 |
| POST | `/v1/member/requests/status` | 仅用 pending secret 查询申请状态；公开 request ID 不可查询 |
| POST | `/v1/member/requests/cancel` | 仅用 pending secret 取消申请 |
| POST | `/v1/member/requests/claim` | 已获批申请用 pending secret 单次领取独立 member session |
| GET | `/v1/member/requests` | 仅 Owner；默认保持旧客户端 pending-only 响应，显式发送 `X-Lezi-Member-Request-View: open-v1` 时列出未过期的 pending + approved-unclaimed 并返回 `status`；普通 Member 返回 403 |
| POST | `/v1/member/requests/{id}/approve-new` | 仅 Owner 用唯一家庭称呼批准为新 membership |
| POST | `/v1/member/requests/{id}/bind-existing` | 仅 Owner 显式把申请设备绑定到指定的既有普通 membership；不会按同名自动绑定 |
| POST | `/v1/member/requests/{id}/reject` | 仅 Owner 拒绝 pending 申请，或撤销 approved-unclaimed 申请并释放称呼保留；不创建身份或凭证 |
| POST | `/v1/member/login-grants` | 仅 Owner 为指定 active 普通 membership 创建十分钟、单次兑换的短期 grant；只存哈希 |
| POST | `/v1/member/login-grants/claim` | 经已确认 HTTPS/SPKI 连接，用 grant 与设备称呼领取绑定目标 membership 的独立 DeviceSession |
| GET | `/v1/family/members` | 当前家庭的 active 成员与设备安全视图；Owner 看全部设备，Member 只收到自己的设备明细 |
| POST | `/v1/family/members` | 仅 Owner 创建一个尚未绑定设备的普通 membership；称呼按家庭唯一 |
| POST | `/v1/family/members/{id}/display-name` | 仅 Owner 直接修改任一 active membership 的家庭称呼 |
| POST | `/v1/family/devices/{id}/display-name` | Member 修改自己的设备称呼；Owner 可修改任一设备；同 membership 内唯一 |
| POST | `/v1/family/devices/{id}/revoke` | 仅 Owner 幂等撤销指定 Device 的全部 session；membership 与其它设备不受影响 |
| POST | `/v1/device/logout` | 当前 Owner 或 Member 主动撤销这台 Device；服务端确认后客户端才清本机 |
| POST | `/v1/family/members/remove` | Owner 彻底删除另一 active Member（不能删除自己/Owner），匿名化并保留其共享事实 |
| POST | `/v1/family/display-name` | Owner 自己立即改名；普通 Member 只提交待审批改名申请，旧称呼继续生效 |
| GET | `/v1/family/rename-requests` | 仅 Owner 列出当前家庭待处理改名申请 |
| POST | `/v1/family/rename-requests/{id}/approve` | 仅 Owner 原子重检唯一性并批准改名 |
| POST | `/v1/family/rename-requests/{id}/reject` | 仅 Owner 拒绝改名，旧称呼不变 |
| POST | `/v1/family/rename-requests/cancel` | 普通 Member 撤回自己的待处理改名，旧称呼不变 |
| POST | `/v1/family/name` | Owner 改非空共享家庭名 |
| POST | `/v1/leave` | 普通 Member 彻底删除自身 membership；Owner 不可用此接口退出 |
| POST | `/v1/family/delete` | Owner 以家庭名 + 根密码永久删除整个家庭图及媒体 |
| POST | `/v1/push` | 已退役；固定 `422`，实体只经 atomic bundle 发布 |
| GET | `/v1/pull?cursor=&generation=` | 有界分页、单调 cursor 增量 pull |
| PUT/GET | `/v1/media/{client_uuid}` | PUT 已退役；GET 保留为已发布媒体下载 |
| POST | `/v1/bundles` | 原子包暂存：根实体 + 媒体清单（commit 前不可 pull） |
| PUT | `/v1/bundles/{id}/media/{uuid}` | 原子包媒体字节暂存 |
| POST | `/v1/bundles/{id}/commit` | 单事务发布完整包（幂等） |
| GET | `/v1/bundles/{id}` | 查询 staging/committed 与 missing_media |

服务每次启动生成新的 `generation`。客户端在发现 generation 变化或 cursor
领先时执行既有 `full_resync` 契约。Record 使用 `baby_client_uuid` 跨设备关联；
Media 的 kind 与关联创建后不可改变；member 可以写日志媒体，但头像 metadata
和字节只允许 owner 修改。

`GET /v1/family/members` 返回 owner-first 的稳定列表。授权可见的 membership 带 active
`devices`，每项仅含 opaque `device_id`、设备称呼、`last_used_at` 与 `is_current`；Owner
获得全家庭设备，普通 Member 的其它成员行省略整个 `devices` 字段，不泄露设备数量、
最近使用或凭证状态。
服务端只按 Bearer principal 的 `family_id` 查询 active memberships，并按返回行的
`membership_id` 是否等于 principal membership 计算 `is_self`；响应绝不包含 token、
`token_hash`、session 状态、IP、端口或 `family_id`。
`membership_id` 是服务端生成的**不可变** membership 公开身份（UUID），创建/加入时
写入，token 轮换、地址变化或进程重启均不改变；供计划作者、自定义定义与履行冲突
等 ACL 引用。建家时客户端只提交可编辑的 `device_name`，canonical `device_id` 由
服务器生成并从 access credential 解析，不能由客户端声明权限身份。建家/加入时
`display_name`（家庭称呼）**必填**：
trim 后空白、省略字段、或本机 UI 占位名“我（本机）”均返回 `422`，不再静默收成
null；最长 128 个 Unicode 字符，并拒绝控制符与双向文本格式控制符。

所有成员/设备称呼先执行 Unicode NFKC、首尾 trim、连续空白折叠，再按规范键比较。
家庭称呼在 family 内唯一；设备称呼只在同一 membership 的 active 设备中唯一。
规范化冲突返回可重试 `409`，不会覆盖设备、复制 membership 或按同名自动登录。

`POST /v1/family/display-name` body 为 `{"display_name":"…"}`。Owner 自己改名立即返回
`status=updated`；普通 Member 返回 `202 status=pending`，审批前列表与历史作者仍使用旧
称呼。Owner 的 approve 在同一事务内重新检查目标 membership 与唯一性；拒绝、撤回、
七天过期都不会改称呼。Owner 也可先 `POST /v1/family/members` 创建零设备 member，随后
沿用目标 membership 单次 QR 或显式绑定开放的设备登录申请。

`POST /v1/family/create` 要求非空 `family_name`、`display_name`、`device_name` 和
`create_request_id`；名字均 trim，家庭名最长 64 Unicode 字符，成员/设备称呼最长
128 个字符，并拒绝控制符与双向文本格式控制符。同一请求 ID 的网络重试返回同一
DeviceSession；不同请求在家庭已存在时返回 `409`，不会把 create 降级成管理员登录。
响应返回短期 `access_token`、`access_expires_at` 和长期轮换 `refresh_token`。根密码
只验证本次创建，不会成为 Bearer credential，也不会写入数据库。幂等 create 只可
重放仍是当前值的初始 session 响应；一旦该 session 已轮换，原请求 ID 固定返回冲突，
不能成为凭证恢复通道。

`POST /v1/session/refresh` body 只含 `{"refresh_token":"…"}`。成功时 access 有效期
为 15 分钟，并返回同时轮换的新 refresh；refresh 本身没有时间或 inactivity 到期。
服务端只保存 credential hash 与已使用 refresh 的 rotation lineage。旧 refresh 重放
返回 `401`/`refresh_replay` 并只撤销它所属的 Device；同一 membership 的其它 Device、
其它 membership 和家庭均保持有效。未知或已撤销 refresh 返回
`401`/`invalid_refresh`。family/membership/device ID 和称呼均不能换取 credential。

显式设备撤销把该 Device 标记为 revoked，并为它的 access/refresh 返回
`401`/`device_removed`；响应不包含家庭、成员或其它设备信息。同一 membership 的其它
Device 继续有效。远程撤销不承诺让离线设备即时擦除；Android 仅在下次连接已信任
endpoint 收到这个明确 code，或当前设备 logout 已获 2xx 确认后，才进入可恢复的
Room、Outbox、media、endpoint 与 credential 清理。普通 401、网络错误和 refresh
失效不会触发该清理；被撤销设备以后必须以新申请或目标 membership 单次登录 grant
重新绑定，并获得全新 Device/session。

删除普通 membership（Owner 删除成员或 Member 主动退出家庭）在一个事务中彻底删除
该 membership、全部 Device/session、待领取登录 grant、已绑定待确认申请和改名申请；
规范化家庭称呼随即释放。已提交的 Record、CarePlan、履行候选及完整媒体包仍是家庭
共享事实，但作者/提交者引用置空；只有 Owner 可继续管理匿名事实。之后创建的同名称
成员是全新身份，不能恢复旧作者或所有权。

服务端只为旧 access/refresh credential 的哈希保留不含 family、membership 或 Device
身份的终止原因。被删除成员的离线设备在下次连接已信任 endpoint 时稳定收到
`401`/`membership_deleted`，Android 随后以可恢复流程清理 Room、Outbox、媒体、endpoint
信任和会话材料；普通 401、网络错误或 refresh 失效均不得触发此清理。“退出这台设备”
只撤销当前 Device，“退出家庭”与 Owner“删除成员”则删除整个普通 membership。

`POST /v1/family/name`（Auth：**仅 owner**）改共享家庭名；body
`{"family_name":"…"}` 必须为 trim 后非空的当前家庭名；member 返回 `403`；响应
`{"ok":true,"family_name":"…"}`。保持非空使删除家庭的家庭名确认始终可达。客户端冷启动依赖本机会话缓存（create/login/rename
回写），无独立 GET。

`POST /v1/family/delete` 仅接受当前 Owner bearer，并同时要求 body
`{"family_name":"…"}` 与请求头 `X-Lezi-Bootstrap-Secret`。家庭名规范化后必须与
当前名完全一致，根密码只参与本次常量时间校验。服务端先在单个 SQLite 事务中为全部
旧 access/refresh hash 留下不含身份引用的 `family_deleted` 终止原因，再级联删除整个
家庭图；提交后才清媒体。丢失成功响应的原设备及其它离线设备重试时稳定收到
`401`/`family_deleted`，客户端据此执行可恢复的本地全量清理；普通 401 或网络失败不清理。

NAS 持久化明确分为 `families`、`memberships`、`devices` 和 `device_sessions`：
membership 是家庭角色身份，一个 membership 可拥有多台 Device；每台 Device 的
DeviceSession 保存 access/refresh 的 hash、过期时间和吊销状态，不保存明文 credential。
受保护 API 只从 access hash 解析 canonical family、membership、device 与 role。
运行时 members 每个 active membership 投影一行；普通 membership 退出或被删除时会
原子硬删除身份及全部设备会话，并匿名化保留的家庭共享事实。管理员删除家庭时由外键
级联清除全部身份与会话。

`POST /v1/family/create`、管理员登录和普通成员 claim 响应均含 `membership_id`、canonical
`device_id` 与设备 session credential；同一建家 request、登录 request 或单次授权兑换
按各自合同幂等。角色与写者身份只来自 Bearer principal：member 不能冒充 owner 调用
成员管理或改名等接口；请求内与 credential 不符的身份声明返回 `403`。

pull 响应包含当前字段 `has_more`。每页最多扫描 200 个实体，并以约 8 MiB
序列化实体为体积目标；响应 `cursor` 只前进到本页已扫描的 revision。客户端在
该页实体和媒体全部落地后保存 cursor，再以新 cursor 连续请求，直到
`has_more=false`。全量页会附带页内实体所需、但 revision 位于后页的 Baby/Record
依赖；这些依赖后续可幂等重复。
客户端执行 full-resync 时，重新 push 本地副本之前的权威拉取阶段会把分页 cursor
只保存在内存；全部页与成员头像对账成功后才一次持久化，防止中途重启后用半份
服务器快照提前 push。
为保证最深的 Media → Record → Baby 依赖组总能装入一页，单个新实体的
序列化 payload 上限约为页目标的三分之一；超限 push 返回 `422`，不会写入一条
永远无法 pull 的数据。

### 媒体完整性（半上传）

`POST /v1/push` 的 media 元数据与 `PUT /v1/media/{uuid}` 字节分两阶段提交。
为避免客户端在字节尚未落盘时对 `GET /media` 404 并卡住 pull cursor：

- **pull 会省略尚未落盘的 live media**（tombstone 仍正常下发）；响应 cursor
  仍为家庭全局 rev。
- 首次成功 `PUT` 字节后服务端会 **提升该 media 的 rev**，对端后续 pull 才能
  看到实体并安全下载。
- `byte_size` 若存在必须大于 0；PUT body 必须非空，并与声明字节数完全一致。
- pull/GET 会核对落盘文件非空且与声明大小一致；发现零字节或大小不符时拒绝下发
  并清理损坏项，非普通文件同样拒绝，下一次正确 PUT 会重新发布该 media。
- PUT 以临时文件写入并同步文件，原子替换后再同步父目录，确保成功响应前 rename
  已进入文件系统持久化边界。

### 原子同步包（`atomic_bundle`）

`GET /health` 广告
`capabilities: ["atomic_bundle", "record_membership_author"]`。当前客户端要求 health
为 `ok` 且 capabilities 至少包含这两项；允许增加能力，`version` 仅展示、不参与门闩。
公网 `8765` 只提供 HTTPS；容器健康检查使用仅绑定 `127.0.0.1:8766` 的明文
`/health`、`/ready` 路由，该内部 listener 不挂载任何 `/v1/*` 业务接口。
所有实体发布前必须确认 `atomic_bundle`；不存在 metadata-first 回退路径。
`record_membership_author` 表示服务端接受并回执 membership
作者字段。

典型发送流程：

1. `POST /v1/bundles` — body
   `{ "bundle_id", "root": {type: record|care_plan|baby|custom_item|fulfillment_candidate, ...}, "media": [...], "generation"? }`
   live media 须带正 `byte_size`；响应
   `{bundle_id, status:"staging", missing_media, staged_media}`
2. 对每个 missing media：`PUT /v1/bundles/{bundle_id}/media/{uuid}`（原始字节）
3. `POST /v1/bundles/{bundle_id}/commit` — 先把暂存字节原子安装到
   `media/{family}/{uuid}` 并同步文件、家庭目录与 `media/` 根目录，再以单个 SQLite 事务写入
   entities + 提升 rev；重复 commit 安全幂等

规则：

- commit 前普通 `GET /v1/pull` **看不到**包内任何实体
- 每次 bundle media 上传同时持久化声明尺寸与 SHA-256；commit 必须同时匹配
  精确摘要和尺寸
- 媒体优先进入持久化边界，SQLite 后发布引用；DB 失败或进程中断只会留下
  不可见字节，不会暴露缺字节的实体。已提交 bundle 在暂存清理后重试时会逐个
  核对已发布文件的摘要与尺寸，并再次 fsync 文件、家庭目录和 `media/` 根目录
- 同一数据根优先用 hard link 做 no-replace 发布；NAS 文件系统不支持 hard link
  时，改用已 fsync 的暂存副本 + no-replace rename，不覆盖冲突字节
- 编辑新版本：另开 `bundle_id` 暂存；commit 前 pull 仍返回旧完整版本
- 根 `updated_at` 落后于已发布版本 → commit `409`
- tombstone 包（root/media 带 `deleted_at`）不需上传字节即可 commit
- 每包最多 8 个 media；每家庭最多 64 个 open staging bundle
- `record`/`care_plan` 只允许 `kind=log` 的媒体成员，`baby` 只允许 `kind=avatar`；
  `custom_item`/`fulfillment_candidate` 必须使用空媒体清单
- 零照片 Record/CarePlan 仍提交空媒体清单的包；所有根执行当前字段、引用与成员 ACL 校验
- 活动 `custom_item` 才可用于新建 custom Record/CarePlan；同家庭 tombstone 仅保留历史
  引用完整性，允许既有根的编辑/删除和已完成计划明确关联的履行 Record，不会被任意新根选择
- CarePlan fulfillment pair 双向不变量：`status=completed` 必须同时带非空
  `fulfilled_record_client_uuid` 与 `fulfilled_at`；`pending`/`missed`/`skipped` 必须两字段
  皆空；残缺 pair 或非 completed 携带 pair → model `422`。首次 completed 写入即冻结完整
  pair；后续清空/残缺 rewrite → `422`，完整但改绑/改时 → `409`。精确 replay 与同 bundle
  retry 幂等；stage→commit 竞态 rebind 由 commit 冻结检查拦截
- `/v1/push` 与普通媒体 PUT 固定 `422`；GET 媒体下载保留
- pull 发出 live Record/CarePlan 时，同页共组其全部 live `log` 媒体；客户端仍逐页完整 apply

家庭删除先提交 SQLite 外键级联删除，再清理该家庭媒体目录。数据库删除失败时
媒体保持完整；数据库已删除但文件清理失败或进程中断时，该 UUID 目录作为孤儿
保留，并在下一次启动按上述有限规则重试清理。

## 备份与恢复

```bash
export LEZI_BOOTSTRAP_SECRET="<deployment bootstrap secret>"
docker compose stop
cp -a /volume1/docker/lezi /volume1/backup/lezi-$(date +%F)
docker compose start
```

恢复时同样替换整个数据根并重启容器。数据库回滚会由 cursor/generation
恢复协议通知 Android 执行全量重新汇合。

### 离线 v3→current 切割（`lezi-sync offline-migrate`）

**架构边界（权威）：** [ADR-0013](../../docs/adr/0013-offline-migrate-is-maintenance-window-cutover.md)
——离线 CLI 族 + 已授权**维护窗切割**；**不是** server startup / runtime 自动迁移，也
**不**推翻 [ADR-0008](../../docs/adr/0008-support-only-fresh-current-product-contracts.md)
的 NAS fresh-current / fail-closed 合同。日常启动仍只接受精确 current schema
（`user_version` = `DATABASE_SCHEMA_VERSION` 且形状匹配）；探测旧库后自动迁移、
destructive fallback 或部分原地改写均被禁止。

发布二进制可包含该子命令；**不**表示支持一般滚动 schema 兼容。**普通 CD**
（`package-nas` / `push-and-deploy` / 容器重启）**不得执行** `offline-migrate`。

| 主题 | 合同 |
|------|------|
| 阶段 A — 离线准备（维护窗前） | 对独立备份：copy-out → `dry-run` / `migrate` / `validate` 于独立 `--out`；**不** stop 现网、**不**写 live bind |
| 阶段 B — 维护窗切割（固定顺序，不得重排） | stop → dual backup confirm → copy-back → TLS CD → health/ready（见 runbook / `cutover_maintenance_steps`） |
| 架构不变量 | 显式 CLI；固定源 v3→current；独立临时 `--out`；`validate` 后再切换；进程只开 current |
| Secret | 运维选定 `LEZI_MIGRATE_NEW_ROOT_PASSWORD` / `--new-root-password`（≥16）；cutover 后作 `LEZI_BOOTSTRAP_SECRET`；**禁止**文档/日志打印明文；目标 `server.secret` 始终重生成 |
| Data bind | 宿主路径 bind → `/data`；uid `10001:10001`；stop/rm **不**删宿主目录 |
| 备份 / 回滚 | 本地 copy-out + NAS 侧双备份；失败恢复 **v3 copy-out** 与 pre-cutover 镜像，非半成品 `out/` |
| 目标校验 | `offline-migrate validate --out`（current preflight + `server.secret` 长度；≠ 完整 `/ready`） |

**权威运维 runbook（步骤与回滚）：**
[`deploy/copy-back-tls-cutover-runbook.md`](deploy/copy-back-tls-cutover-runbook.md)
copy-out / copy-back 脚本：[`deploy/copy-out-nas-data.sh`](deploy/copy-out-nas-data.sh)、
[`deploy/copy-back-nas-data.sh`](deploy/copy-back-nas-data.sh)。
普通发版 CD（不含 offline-migrate）：[`deploy/DEPLOY.md`](deploy/DEPLOY.md)。

```bash
# 阶段 A — 维护窗前（服务可仍运行）；与 `lezi-sync offline-migrate help` 一致
# 勿在命令中嵌入真实 secret
export LEZI_MIGRATE_NEW_ROOT_PASSWORD='…ops-chosen ≥16 chars…'
lezi-sync offline-migrate dry-run  --in "$BACKUP_DIR"
lezi-sync offline-migrate migrate  --in "$BACKUP_DIR" --out "$OUT_DIR"
lezi-sync offline-migrate validate --out "$OUT_DIR"
# 阶段 B — 维护窗切割（stop → dual backup → copy-back → TLS CD → health）：
# 见权威 runbook；help 指针：
# lezi-sync offline-migrate copy-out-help | copy-back-help | live-cutover-help
```

机器可读 inventory 在 `src/offline_migrate/inventory.rs`。与 live
`hard_delete_membership` 对齐的 **departed membership** 变换（hard-delete disposition）：

- **Active membership**（`left_at IS NULL`）才复制到目标库。
- **Departed membership**（v3 `left_at IS NOT NULL`）按 hard-delete 丢弃：不复制
  membership 行、不占用 display name、不复制 device/credential/request/session。
- 保留的 Record / CarePlan / CustomItem / FulfillmentCandidate 以及 committed
  bundle 的作者/提交者/stager 引用若指向 departed membership，置 null / 空串
  （匿名事实），禁止悬空 FK 或伪归因。
- dry-run 与 migrate 共用同一 disposition；报告区分 `memberships`（copied）与
  `discarded_departed_memberships` / `anonymized_membership_refs`。
- 迁移后 members API 无需也不能再“清理”旧 departed 行——目标库已无 tombstone。
