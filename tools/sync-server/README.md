# lezi sync-server（开发 / NAS 原型）

Stdlib Python 家庭同步服务。规格目标见 [`docs/prd/sync-home-lan.md`](../../docs/prd/sync-home-lan.md)；本目录为可 Docker 部署的原型实现（尚无 family token / 媒体 HTTP，后续演进为 `lezi-sync`）。

## 数据目录（单根）

```text
$LEZI_DATA_DIR/
├── lezi.db          # SQLite（entities / invites / meta）
└── media/           # 媒体字节预留目录（与 db 同卷）
```

| 变量 | 默认 | 说明 |
|------|------|------|
| `LEZI_DATA_DIR` | 脚本目录 | 数据根 |
| `LEZI_SYNC_DB` | `$LEZI_DATA_DIR/lezi.db` | 可覆盖库路径 |
| `LEZI_SYNC_HOST` | `0.0.0.0` | |
| `LEZI_SYNC_PORT` | `8765` | |
| `LEZI_SYNC_VERSION` | `0.1.0-dev` | `/health` 返回 |

## 本地直接跑

```bash
python3 server.py
curl -s http://127.0.0.1:8765/health
```

## 构建镜像

```bash
chmod +x build-image.sh
./build-image.sh
# 指定版本：
# LEZI_SYNC_VERSION=0.1.0 ./build-image.sh
# 或：./build-image.sh -t 0.1.0
```

产物默认：

- `lezi-sync:0.1.0-dev`
- `lezi-sync:latest`

## Compose 启动（推荐 NAS）

```bash
./build-image.sh
docker compose up -d
curl -s http://127.0.0.1:8765/health
```

主机数据落在 `./data`（可改环境变量）：

```bash
# 示例：挂到 NAS 共享目录
LEZI_DATA_HOST_PATH=/volume1/docker/lezi docker compose up -d
```

`docker-compose.yml` 环境变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `LEZI_SYNC_IMAGE` | `lezi-sync:0.1.0-dev` | 使用的镜像 tag |
| `LEZI_SYNC_PORT` | `8765` | 主机端口 |
| `LEZI_DATA_HOST_PATH` | `./data` | 挂载到容器 `/data` |

仅 `run` 不 compose：

```bash
docker run --rm -p 8765:8765 \
  -e LEZI_DATA_DIR=/data \
  -v "$PWD/data:/data" \
  lezi-sync:0.1.0-dev
```

## API（原型）

| 方法 | 路径 | 鉴权 |
|------|------|------|
| GET | `/health` | 无 |
| POST | `/v1/push` | 无（原型） |
| GET | `/v1/pull?family_id=&cursor=` | 无（原型） |
| POST | `/v1/invite` | 无（原型） |
| POST | `/v1/join` | 无（原型） |

生产规格要求 Bearer family token、媒体上传、硬家网门闩等，见 PRD；**勿将本原型无鉴权暴露到公网**。

## 模拟器

宿主机跑容器后，模拟器访问：`http://10.0.2.2:8765`。
