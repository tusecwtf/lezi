# 01 — lezi-sync 骨架 + Docker 单卷

**Parent:** [../spec.md](../spec.md) · PRD `sync-home-lan` §7–§8
**Blocked by:** —
**Status:** done

## What to build

交付路径 `tools/lezi-sync/`：

- Python 3.12 + **FastAPI** + **Uvicorn**（单 worker）
- `Dockerfile` + `docker-compose.yml` + `build-image.sh`
- 环境变量 `LEZI_DATA_DIR`（默认 `/data`）
- 单数据根：`$DATA_DIR/lezi.db` + `$DATA_DIR/media/`
- `GET /health` → `{ok, version}`（可含 data_dir）

## 交付物

| 工程 | 可 `docker compose up` 的服务；README 部署/备份说明 |
| 用户可见 | 无（基础设施） |

## 验收标准（Must）

- [x] `./build-image.sh`（或 compose build）产出可运行镜像
- [x] 唯一 volume 挂载后，库文件与 `media/` 出现在**同一主机路径**下
- [x] `curl /health` 返回 200 且含 version
- [x] 进程以非 root 运行为宜（Dockerfile 使用 uid 10001 的 `lezi`）
- [x] README 写明示例地址 `http://192.168.50.4:8765`（文档示例，非 App 默认）

## 不在本票范围

- token / push-pull 业务（02–04）
- 媒体上传实现（04）
- Android

## Comments

- 2026-07-25：`tools/lezi-sync/` 已包含 FastAPI/Uvicorn/SQLite 服务、
  Dockerfile、Compose、`build-image.sh`、单 `/data` 挂载、非 root 用户及部署/备份说明。
- API 测试通过 ASGI seam 验证 `/health` 会在同一数据根创建 `lezi.db` 与 `media/`；
  shell 脚本语法也已校验。
- 2026-07-25 **runtime acceptance (docker-01)** — **Status → done**
  - **Rootless Docker** active (`DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock`,
    rootlesskit). Host user **cannot** readdir volume `_data` directly; single-volume
    proof used `docker run --rm -v lezi-sync-data:/data alpine …`.
  - Images already present: `lezi-sync:0.1.0`, `lezi-sync:latest`
    (`build-image.sh` not re-run this session).
  - Reused healthy container `lezi-sync` on host port **8765**, named volume
    `lezi-sync-data:/data`. Secondary healthy `lezi-sync-bind` on **8766** left running.
  - Commands / evidence (under `docs/reviews/home-lan-sync-docker-acceptance-2026-07-25/`):
    - `docker images` → `01-docker/docker-images.txt`
    - single volume `lezi.db` + `media/` → `01-docker/single-volume-alpine-ls.txt`,
      `01-docker/volume-inspect-lezi-sync-data.json`, `01-docker/container-mount-summary.txt`
    - `curl -sS http://127.0.0.1:8765/health` → `{"ok":true,"version":"0.1.0"}` HTTP 200
      (`01-docker/health.json`, `01-docker/health-http-code.txt`)
    - `docker exec lezi-sync id` → `uid=10001(lezi)` (`01-docker/non-root-id.txt`)
    - checklist: `01-docker/01-checklist.md`; bootstrap: `bootstrap-env.txt`
  - Prefer-also: `docker compose -p lezi-acc2 up` **blocked** by hardcoded
    `container_name: lezi-sync` conflict (documented in `01-docker/compose-acc2.txt`);
    not a Must failure.
  - **Not claimed:** NAS production deploy.
