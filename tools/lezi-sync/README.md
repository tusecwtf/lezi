# lezi-sync

乐记家庭局域网同步服务。它是
[`docs/prd/sync-home-lan.md`](../../docs/prd/sync-home-lan.md) 的 NAS 交付实现：
Python 3.12、FastAPI、Uvicorn 单 worker、SQLite，一家一栈。

## 数据与配置

服务只使用一个数据根：

```text
$LEZI_DATA_DIR/
├── lezi.db
├── lezi.db-wal / lezi.db-shm
├── server.secret     # owner/member token 幂等派生密钥；须随数据备份并保密
└── media/
    └── {family_uuid}/{media_uuid}
```

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `LEZI_DATA_DIR` | `/data` | SQLite 与媒体共用的唯一数据根 |
| `LEZI_HOST` | `0.0.0.0` | 容器监听地址 |
| `LEZI_PORT` | `8765` | 容器监听端口 |
| `LEZI_INVITE_TTL_HOURS` | `24` | 邀请码有效小时数（1–168） |
| `LEZI_MAX_MEDIA_BYTES` | `10485760` | 单个媒体最大字节数 |
| `LEZI_TLS_CERTFILE` | 空 | 可选：容器内 TLS 证书路径 |
| `LEZI_TLS_KEYFILE` | 空 | 可选：容器内 TLS 私钥路径；必须与证书同时设置 |

## NAS / Docker Compose

示例家庭地址是 `http://192.168.50.4:8765`。这只是部署示例，App
不得把它或 `10.0.2.2` 作为生产默认地址。

```bash
./build-image.sh
LEZI_DATA_HOST_PATH=/volume1/docker/lezi docker compose up -d
curl -s http://192.168.50.4:8765/health
```

Compose 只挂载一个主机目录到 `/data`。容器进程以非 root 用户
`lezi`（uid 10001）运行；NAS 目录必须允许该 uid 写入。

备份时停止容器并复制整个 `LEZI_DATA_HOST_PATH`，不能只复制
`lezi.db` 而漏掉 WAL/SHM 或 `media/`：

```bash
docker compose stop
cp -a /volume1/docker/lezi /volume1/backup/lezi-$(date +%F)
docker compose start
```

## 本地运行与测试

```bash
python3.12 -m venv .venv
. .venv/bin/activate
pip install -e '.[test]'
LEZI_DATA_DIR=/tmp/lezi-sync-data \
  uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8765 --workers 1

pytest
python -m py_compile app/*.py tests/*.py
```

## API 与语义

除 `/health`、`/v1/family/create` 和 `/v1/join` 外，接口都要求
`Authorization: Bearer <family-token>`。

| 方法 | 路径 | 摘要 |
|---|---|---|
| GET | `/health` | `{ok, version}` |
| POST | `/v1/family/create` | 以稳定创建请求 ID 创建唯一家庭并返回 owner token |
| POST | `/v1/invite` | owner 创建邀请码；默认 24 小时 |
| POST | `/v1/join` | 一次性邀请码换 member token |
| POST | `/v1/leave` | member 吊销自身 token；owner 返回 403 |
| POST | `/v1/family/delete` | owner 删除家庭、实体、成员与媒体 |
| POST | `/v1/push` | 严格接收 `baby|record|media` |
| GET | `/v1/pull?cursor=N&generation=G` | 按进程代际内单调 `rev` 增量拉取 |
| PUT/GET | `/v1/media/{client_uuid}` | 上传/下载媒体字节 |

同步键是 `(family_id, type, client_uuid)`。只有更大的 `updated_at`
会产生新 revision；相同重试、相同时间戳和旧版本均为 no-op。删除以
`deleted_at` tombstone 继续经 pull 传播。Record payload 必须使用
`baby_client_uuid`、`type`、`timestamp` 和对象类型的 `payload_json`，
不得发送设备本地自增 `baby_id`。日志媒体必须用
`record_client_uuid` 关联已存在或同批 Record；头像必须用
`baby_client_uuid` 关联已存在或同批 Baby。Media 的 `kind` 与关联创建后
不可改变；member 可写日志媒体，但头像 metadata 与字节都只允许 owner 写。
批次会先按 LWW 选出每个同步键真正生效的赢家，再执行关联与权限校验；
因此已被服务端更新覆盖的旧 Baby 快照不会阻塞同批较新的 Record。

`/v1/pull` 成功响应同时返回 `generation`。服务每次启动都会生成新代际；
客户端须与 cursor 一起持久化，并在后续 push/pull 回传。push 会在写入前
拒绝旧代际；旧代际会得到
`generation_changed/full_resync`，因此即使 NAS 旧备份复用了相同 revision，
客户端也不会静默漏拉。既有安装出现“非零 cursor + 空 generation”时会先从
cursor 0 校准；普通服务重启会保守地触发一次全量校准。

若 NAS 从旧备份恢复，客户端保存的 cursor 也可能高于服务端当前 cursor。
此时 `/v1/pull` 返回 HTTP 409 和同一机器可读恢复契约：

```json
{
  "detail": {
    "code": "cursor_ahead",
    "action": "full_resync",
    "reset_cursor": 0,
    "server_cursor": 12,
    "server_generation": "..."
  }
}
```

客户端只应在 `code`（`cursor_ahead` 或 `generation_changed`）与 `action`
同时匹配时进入恢复流程：在同步互斥区内把本地 pull cursor 持久化为
`reset_cursor` 并清空旧 generation。owner 重新排队本地仍保留且有权写入的
实体与媒体再全量 pull；member 先全量 pull 取得 owner 的头像权威，再重建
Outbox 和 push。不能直接把 `server_cursor` 当作新 cursor，否则会跳过恢复后
服务端仍存在的 revision。恢复整个数据根后必须重启服务。
此契约只能帮助各客户端重新汇合其仍持有的数据；服务器和所有客户端都已
丢失的内容仍需从其它备份恢复。

创建家庭请求必须携带客户端稳定生成的高熵 `create_request_id`（32–128
个 URL-safe 字符）。客户端在收到成功响应前必须复用同一个 ID；即使响应
丢失或服务重启，重试也会得到相同 `family_id` 和 owner token。服务已有
家庭时，其他创建 ID 仍返回 409。`create_request_id` 具有恢复 owner token
的能力，应像凭据一样仅保存在发起设备上，成功持久化家庭会话后即可删除。

邀请码只允许一个设备消费；过期返回 410。同一设备因响应丢失而重试时，
会得到相同 member token；其它设备重放已使用邀请码返回 409。邀请码只使用
Android 可直接输入的 `A-Z0-9`。

只有 member 可以调用 `/v1/leave`，该操作只吊销调用方 token，不删除家庭
数据。owner 不能 leave，以免留下没有管理员的家庭；owner 必须调用
`/v1/family/delete` 显式删除整个家庭。

## 安全边界

- owner/member token 都由高熵服务密钥稳定派生，并且只把 SHA-256 hash
  写入 SQLite；创建请求 ID 也只落 SHA-256，不保存原文。
- `server.secret` 仅用于幂等派生 token，本身不是 family token；权限为
  `0600`，必须与整个数据根一起备份且不得公开。
- 服务以 `umask 077` 运行，并主动把数据根、媒体目录设为 `0700`，把
  SQLite、WAL/SHM、媒体和 `server.secret` 设为 `0600`；挂载 NAS 卷时
  仍应限制卷目录只允许容器 uid 10001 访问。
- 媒体路径由经过 UUID 校验的 family/media id 组成，并有大小上限。
- 默认 HTTP 只适用于可信家庭局域网，**不要把 8765 直接暴露到公网**。
- 需要跨不可信网络时，首选 Caddy/Nginx TLS 反向代理并配置 NAS 防火墙。
  镜像也可直接启用 Uvicorn TLS：把证书和私钥只读挂载进容器，并同时设置
  `LEZI_TLS_CERTFILE` 与 `LEZI_TLS_KEYFILE`。例如 Compose override：

  ```yaml
  services:
    lezi-sync:
      environment:
        LEZI_TLS_CERTFILE: /run/tls/fullchain.pem
        LEZI_TLS_KEYFILE: /run/tls/privkey.pem
      volumes:
        - /volume1/docker/lezi-tls:/run/tls:ro
  ```

  只设置其中一个变量会拒绝启动；启用后 healthcheck 自动使用 HTTPS。
- leave 后 token 保持吊销；同设备重放旧邀请码不会借幂等逻辑恢复会话。
- 删除家庭会清空服务端数据，部署前应配置整目录备份。
