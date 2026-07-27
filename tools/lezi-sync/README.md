# lezi-sync

乐记家庭局域网同步服务的 Rust 实现。Android 端继续使用既有 `/v1/*`
HTTP interface；服务端以 Axum + Tokio + rusqlite 运行，NAS 上只需要一个
Docker 容器和一个持久化目录。

## 数据兼容性

Rust 版本原位兼容既有数据根；当前 SQLite `PRAGMA user_version=1`。旧的
`user_version=0` 数据根会幂等升级并保留既有家庭、实体、token hash 与
`server.secret` 的 HMAC 派生规则。当前版本尚未正式发布，因此
`sync_bundle_media.staged_sha256` 通过幂等列迁移补入，`user_version` 仍为 1。
若数据卷版本高于服务端支持版本，启动会先对既有数据库执行只读预检并 fail
closed；预检失败不会创建 `media/`、`server.secret`、SQLite sidecar，也不会
修改数据根或数据库权限：

```text
$LEZI_DATA_DIR/
├── lezi.db
├── lezi.db-wal / lezi.db-shm
├── server.secret
└── media/
    └── {family_uuid}/{media_uuid}
```

从旧镜像升级前，停止旧容器并备份整个数据根。不要只复制 `lezi.db`，否则会
遗漏 WAL/SHM、服务密钥或媒体字节。

启动时服务只清理 `media/` 下名称为 UUID、且 SQLite 已无对应家庭的孤儿目录。
非 UUID 运维目录、仍存在的家庭目录和符号链接不会被启动清理触碰。

## NAS / Docker Compose

默认镜像为 `lezi-sync:0.2.5`。**默认安全基线：**

| 项 | 默认 | 说明 |
|---|---|---|
| 容器用户 | `10001:10001`（`lezi`） | 非 root |
| 主机端口映射 | `127.0.0.1:8765:8765` | 仅 loopback；手机经反代或显式覆盖访问 |
| 引导密钥 | Compose 必填 | 缺失或空值时 Compose 拒绝启动（见下） |

```bash
cd tools/lezi-sync
./build-image.sh

# 数据目录需可被 uid 10001 写；示例：
mkdir -p /volume1/docker/lezi
sudo chown -R 10001:10001 /volume1/docker/lezi

# Compose 必填：长随机 bootstrap（≥16 字符）。APK 建家时输入同一一次性口令。
export LEZI_BOOTSTRAP_SECRET="$(openssl rand -hex 24)"
export LEZI_DATA_HOST_PATH=/volume1/docker/lezi

docker compose up -d
docker compose ps
curl -fsS http://127.0.0.1:8765/health
curl -fsS http://127.0.0.1:8765/ready
```

### 端口发布覆盖

默认只绑定宿主 loopback。手机要直连时，显式发布 LAN/全接口（仍勿映射公网）：

```bash
# 仅示例：发布到所有宿主接口的 8765
LEZI_SYNC_PUBLISH=0.0.0.0:8765 \
  LEZI_DATA_HOST_PATH=/volume1/docker/lezi \
  LEZI_BOOTSTRAP_SECRET=... \
  docker compose up -d
```

更稳妥的是在 NAS 上用 Caddy/Nginx/系统反代终结 TLS，并把 compose 保持
`127.0.0.1:8765`。

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

升级既有部署：

```bash
export LEZI_BOOTSTRAP_SECRET="<deployment bootstrap secret>"
docker compose stop
cp -a /volume1/docker/lezi /volume1/backup/lezi-before-rust
./build-image.sh
# 若原数据为 root 拥有，先 chown 10001 或改用 nas-root profile
sudo chown -R 10001:10001 /volume1/docker/lezi
LEZI_DATA_HOST_PATH=/volume1/docker/lezi \
  docker compose up -d
docker compose ps
```

如果 NAS 不适合本机编译，可在开发机导出镜像：

```bash
docker save lezi-sync:0.2.5 | gzip > lezi-sync-0.2.5.tar.gz
# 把 tar.gz 复制到 NAS 后：
gzip -dc lezi-sync-0.2.5.tar.gz | docker load
```

构建脚本只把 Cargo 清单、锁文件、Dockerfile 与 `src/` 放进临时构建上下文，
不会读取宿主机上可能由容器 uid 拥有的 `data-*` 目录。

## 多架构镜像

在已配置 buildx 的机器上可直接为常见 NAS 架构构建：

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --build-arg LEZI_SYNC_VERSION=0.2.5 \
  -t your-registry/lezi-sync:0.2.5 \
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
| `LEZI_SYNC_VERSION` | `0.2.5` | `/health` 返回的版本 |
| `LEZI_INVITE_TTL_HOURS` | `24` | 邀请有效期，范围 1–168 |
| `LEZI_MAX_MEDIA_BYTES` | `10485760` | 单个媒体最大字节数 |
| `LEZI_BOOTSTRAP_SECRET` | Compose 必填；`cargo run` 可空 | `POST /v1/family/create` 要求同值 `X-Lezi-Bootstrap-Secret`；Compose 缺失或空值时拒绝启动 |
| `LEZI_CREATE_RATE_LIMIT` | `20` | 每台 device 每窗口的 create 尝试上限 |
| `LEZI_JOIN_RATE_LIMIT` | `60` | 每个邀请码每窗口的 join 尝试上限 |
| `LEZI_RATE_LIMIT_WINDOW_SECONDS` | `60` | create/join 限流窗口秒数 |
| `LEZI_SYNC_PUBLISH` | `127.0.0.1:8765` | compose 宿主侧发布地址（仅 docker compose） |
| `LEZI_ALLOW_PERMISSION_HARDENING_SKIP` | Compose `0`；`cargo run` 未设置 | 仅显式设为 `1` 时，chmod 在 EPERM/EACCES/EOPNOTSUPP 上 warn 并继续；默认 fail-closed |

服务本身只监听 HTTP。可信家庭局域网可以直接访问；需要 HTTPS 时，在 NAS
上使用 Caddy、Nginx 或系统自带反向代理终止 TLS，并只把容器端口暴露在私有
Docker 网络或家庭 LAN。不要把 8765 直接映射到公网。

### 生产 bootstrap（fail-closed）

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

- Android 建家页输入相同的一次性初始化口令；客户端仅将其放入该请求头，不写入
  session、邀请载荷或本地持久化。缺失或错误时建家失败并允许重新输入。

## 本地开发

```bash
cargo fmt --all -- --check
cargo test --locked
cargo clippy --all-targets --all-features -- -D warnings

LEZI_DATA_DIR=/tmp/lezi-sync-data cargo run --release
```

容器健康检查调用同一个 Rust 二进制：

```bash
lezi-sync healthcheck
```

## HTTP interface

除 `/health`、`/ready`、`/v1/family/create` 和 `/v1/join` 外，接口都要求
`Authorization: Bearer <family-token>`。`create`/`join` 受进程内速率限制；
`create` 在配置了 `LEZI_BOOTSTRAP_SECRET` 时还要求 bootstrap 头。局部分桶达到
上限时只阻断同一 device/邀请码；轮换标识仍受局部上限 10 倍的全局兜底限制。
无效 bootstrap 或格式错误的请求不消耗有效建家调用的额度。

| 方法 | 路径 | 摘要 |
|---|---|---|
| GET | `/health` | 廉价进程存活检查，正常 `{ok, version, capabilities:["atomic_bundle"]}`，不访问 DB/文件系统 |
| GET | `/ready` | DB 与数据目录就绪检查；结果缓存 5 秒，异常返回 `503 {ok:false,status:"degraded",version}` |
| POST | `/v1/family/create` | 幂等创建家庭并返回 owner token；可选 `family_name` |
| GET | `/v1/family/members` | 当前家庭的 active 成员安全视图；owner/member 均可读 |
| POST | `/v1/family/display-name` | 成员更新自己的家庭称呼 |
| POST | `/v1/family/name` | owner 改共享家庭名 |
| POST | `/v1/invite` | owner 创建一次性邀请码 |
| POST | `/v1/join` | 邀请码换 member token（响应含 `family_name`） |
| POST | `/v1/leave` | member 退出自身 membership 并吊销其全部凭证 |
| POST | `/v1/family/delete` | owner 删除家庭及媒体 |
| POST | `/v1/push` | Baby、Record、Media、CustomItem 的严格 LWW push（兼容路径） |
| GET | `/v1/pull?cursor=&generation=` | 有界分页、单调 cursor 增量 pull |
| PUT/GET | `/v1/media/{client_uuid}` | 上传或下载媒体字节（兼容路径） |
| POST | `/v1/bundles` | 原子包暂存：根实体 + 媒体清单（commit 前不可 pull） |
| PUT | `/v1/bundles/{id}/media/{uuid}` | 原子包媒体字节暂存 |
| POST | `/v1/bundles/{id}/commit` | 单事务发布完整包（幂等） |
| GET | `/v1/bundles/{id}` | 查询 staging/committed 与 missing_media |

服务每次启动生成新的 `generation`。客户端在发现 generation 变化或 cursor
领先时执行既有 `full_resync` 契约。Record 使用 `baby_client_uuid` 跨设备关联；
Media 的 kind 与关联创建后不可改变；member 可以写日志媒体，但头像 metadata
和字节只允许 owner 修改。

`GET /v1/family/members` 返回 owner-first 的稳定列表：
`{"members":[{"display_name":"妈妈","role":"owner","is_self":true,"device_id":"…","membership_id":"…"}]}`。
服务端只按 Bearer principal 的 `family_id` 查询 active memberships，并按返回行的
`membership_id` 是否等于 principal membership 计算 `is_self`；响应绝不包含 token、
`token_hash` 或 `family_id`。
`membership_id` 是服务端生成的**不可变** membership 公开身份（UUID），创建/加入时
写入，token 轮换、地址变化或进程重启均不改变；供计划作者、自定义定义与履行冲突
等 ACL 引用。`device_id` 仅作客户端把记录 `created_by_device_id` 解析为当前家庭
称呼的**链路键**，产品 UI 不得展示。建家/加入时 `display_name`（家庭称呼）**必填**：
trim 后空白、省略字段、或本机 UI 占位名“我（本机）”均返回 `422`，不再静默收成
null；最长 128 个 Unicode 字符，并拒绝控制符与双向文本格式控制符。历史库中的空名
或不安全名字在读取时降级为 `null`，客户端按角色/「家人」兜底。

`POST /v1/family/display-name`（Auth：任一有效家庭 token）允许成员**仅更新自己的**
`display_name`；body `{"display_name":"…"}`，校验规则同建家/加入；响应
`{"ok":true,"display_name":"…"}`。

`POST /v1/family/create` 另接受可选 `family_name`（共享家庭名）：trim 后空则存
`null`；最长 64 Unicode 字符；禁控制符/双向控制符。create/join 响应均含
`family_name`（可 null）。同一 `create_request_id` 幂等重试须匹配相同
`family_name`，否则 `409`。

`POST /v1/family/name`（Auth：**仅 owner**）改共享家庭名；body
`{"family_name":"…"}`（空/`null` 清除）；member 返回 `403`；响应
`{"ok":true,"family_name":…}`。客户端冷启动依赖本机会话缓存（create/join/rename
回写），无独立 GET。

NAS 持久化将 membership 与 credential 分开：`memberships.membership_id` 是产品身份
主键；`membership_credentials.token_hash` 只用于认证并指向 membership，同一
membership 可持有多个可独立轮换/吊销的凭证。旧库升级在单一事务中仅一次按
`(family_id, role, device_id)` 归并历史 active token，按 token hash 字典序稳定选择
既有 membership ID 和首个安全非占位称呼；其它既有 ID 保留为 ACL alias，缺失 ID
只生成一次。不同 role 永不合并。运行时 members 每个 active membership 投影一行，
新 join 即使声明相同 `device_id` 也创建独立 membership；退出标记当前 membership
离开并吊销其全部凭证。管理员删除家庭时由外键级联清除全部 memberships 与凭证。

`POST /v1/family/create` 与 `POST /v1/join` 响应均含 `membership_id`；同一
`create_request_id` / 同一邀请码幂等重试返回**相同** `membership_id`。角色与写者
身份只来自 Bearer principal：member 不能冒充 owner 调用邀请/改名等接口；push 中
与 token 不符的 `device_id` 返回 `403`。

pull 响应新增兼容字段 `has_more`。每页最多扫描 200 个实体，并以约 8 MiB
序列化实体为体积目标；响应 `cursor` 只前进到本页已扫描的 revision。客户端在
该页实体和媒体全部落地后保存 cursor，再以新 cursor 连续请求，直到
`has_more=false`。全量页会附带页内实体所需、但 revision 位于后页的 Baby/Record
依赖；这些依赖后续可幂等重复。旧客户端虽然不会同轮连续拉取，但因收到的不是
全局 cursor，下一次前台/下拉仍会继续，避免跳过数据。
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

线协议字段形状不变，旧客户端只需不再收到不完整 media 即可前进 cursor。

### 原子同步包（`atomic_bundle`）

`GET /health` 广告 `capabilities: ["atomic_bundle"]`。新客户端在发布带照片的
记录/计划前必须确认该能力；**不得**对旧 NAS 静默回退到 metadata-first push。

典型发送流程：

1. `POST /v1/bundles` — body
   `{ "bundle_id", "root": {type: record|care_plan, ...}, "media": [...], "generation"? }`
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
- 旧库中摘要为空的已提交 bundle 只在原暂存文件仍存在、且与已发布文件完全一致时
  补写摘要；暂存已清理时无法追溯原始内容，重试会保守拒绝，不会信任仅尺寸相同的文件
- 同一数据根优先用 hard link 做 no-replace 发布；NAS 文件系统不支持 hard link
  时，改用已 fsync 的暂存副本 + no-replace rename，不覆盖冲突字节
- 编辑新版本：另开 `bundle_id` 暂存；commit 前 pull 仍返回旧完整版本
- 根 `updated_at` 落后于已发布版本 → commit `409`
- tombstone 包（root/media 带 `deleted_at`）不需上传字节即可 commit
- 每包最多 8 个 media；每家庭最多 64 个 open staging bundle
- `care_plan` 根类型与 `record` 共用契约；完整 CarePlan 字段/ACL 在后续票单扩展
- 既有 `/v1/push` 与 `/v1/media` 保持兼容（头像与旧客户端）

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
