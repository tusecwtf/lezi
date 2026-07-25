# lezi-sync

乐记家庭局域网同步服务的 Rust 实现。Android 端继续使用既有 `/v1/*`
HTTP interface；服务端以 Axum + Tokio + rusqlite 运行，NAS 上只需要一个
Docker 容器和一个持久化目录。

## 数据兼容性

Rust 版本原位兼容既有数据根，不改变 SQLite schema、token hash 或
`server.secret` 的 HMAC 派生规则：

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

## NAS / Docker Compose

默认镜像为 `lezi-sync:0.2.0`，容器内 uid/gid 为 `10001:10001`：

```bash
cd tools/lezi-sync
./build-image.sh

install -d -m 700 /volume1/docker/lezi
chown 10001:10001 /volume1/docker/lezi

LEZI_DATA_HOST_PATH=/volume1/docker/lezi \
  docker compose up -d

docker compose ps
curl -fsS http://127.0.0.1:8765/health
```

Synology、QNAP 或其它 NAS 的数据路径不同，只需把
`LEZI_DATA_HOST_PATH` 换成实际目录。Compose 只挂载该目录到 `/data`，
并启用非 root、drop capabilities 和 `no-new-privileges`。

升级既有部署：

```bash
docker compose stop
cp -a /volume1/docker/lezi /volume1/backup/lezi-before-rust
./build-image.sh
LEZI_DATA_HOST_PATH=/volume1/docker/lezi docker compose up -d
docker compose ps
```

如果 NAS 不适合本机编译，可在开发机导出镜像：

```bash
docker save lezi-sync:0.2.0 | gzip > lezi-sync-0.2.0.tar.gz
# 把 tar.gz 复制到 NAS 后：
gzip -dc lezi-sync-0.2.0.tar.gz | docker load
```

构建脚本只把 Cargo 清单、锁文件、Dockerfile 与 `src/` 放进临时构建上下文，
不会读取宿主机上可能由容器 uid 拥有的 `data-*` 目录。

## 多架构镜像

在已配置 buildx 的机器上可直接为常见 NAS 架构构建：

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --build-arg LEZI_SYNC_VERSION=0.2.0 \
  -t your-registry/lezi-sync:0.2.0 \
  --push .
```

不使用 registry 时，在目标 NAS 上运行 `./build-image.sh` 会自动构建其原生
架构镜像。

## 配置

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `LEZI_DATA_DIR` | `/data` | SQLite、密钥和媒体的唯一数据根 |
| `LEZI_HOST` | `0.0.0.0` | 监听地址 |
| `LEZI_PORT` | `8765` | 监听端口 |
| `LEZI_SYNC_VERSION` | `0.2.0` | `/health` 返回的版本 |
| `LEZI_INVITE_TTL_HOURS` | `24` | 邀请有效期，范围 1–168 |
| `LEZI_MAX_MEDIA_BYTES` | `10485760` | 单个媒体最大字节数 |

服务本身只监听 HTTP。可信家庭局域网可以直接访问；需要 HTTPS 时，在 NAS
上使用 Caddy、Nginx 或系统自带反向代理终止 TLS，并只把容器端口暴露在私有
Docker 网络或家庭 LAN。不要把 8765 直接映射到公网。

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

除 `/health`、`/v1/family/create` 和 `/v1/join` 外，接口都要求
`Authorization: Bearer <family-token>`。

| 方法 | 路径 | 摘要 |
|---|---|---|
| GET | `/health` | `{ok, version}` |
| POST | `/v1/family/create` | 幂等创建家庭并返回 owner token |
| POST | `/v1/invite` | owner 创建一次性邀请码 |
| POST | `/v1/join` | 邀请码换 member token |
| POST | `/v1/leave` | member 吊销自身 token |
| POST | `/v1/family/delete` | owner 删除家庭及媒体 |
| POST | `/v1/push` | Baby、Record、Media 的严格 LWW push |
| GET | `/v1/pull?cursor=&generation=` | 单调 cursor 增量 pull |
| PUT/GET | `/v1/media/{client_uuid}` | 上传或下载媒体字节 |

服务每次启动生成新的 `generation`。客户端在发现 generation 变化或 cursor
领先时执行既有 `full_resync` 契约。Record 使用 `baby_client_uuid` 跨设备关联；
Media 的 kind 与关联创建后不可改变；member 可以写日志媒体，但头像 metadata
和字节只允许 owner 修改。

## 备份与恢复

```bash
docker compose stop
cp -a /volume1/docker/lezi /volume1/backup/lezi-$(date +%F)
docker compose start
```

恢复时同样替换整个数据根并重启容器。数据库回滚会由 cursor/generation
恢复协议通知 Android 执行全量重新汇合。
