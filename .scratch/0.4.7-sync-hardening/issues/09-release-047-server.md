# 09: 服务端 0.4.7 发布与维护窗部署——活集零改写的部署证据

**What to build:** 家庭服务器升级到带普查信封的 0.4.7：cargo 门禁与打包按 CD 规则执行，部署
前后对活数据做逐字节等价校验（计数、键摘要、抽样内容哈希）——「激活数据（非墓碑）不可改」
以部署证据落地而非口头承诺。旧 APK 对升级后服务端行为不变。部署走 propose-confirm 窗口，
不触 TLS 身份等既有红线。

**Blocked by:** 06: 服务端普查信封；08: 客户端出包（联调验证需要两端就位）

**Status:** done

- [x] cargo fmt / test --locked / clippy 门禁全绿；打包按仓库 CD 规则
- [x] pre/post 活集等价校验：部署前后活数据行逐字节等价（计数+摘要+抽样内容哈希一致）
- [x] 旧 APK（0.4.5/0.4.6）对升级后服务端的回归探针通过
- [x] 新 APK 普查核对行为在真实服务端联调通过（对应 AGENTS 联调三步）
- [x] NAS 部署经用户确认窗口；不违反 TLS 与密钥红线

## Comments

- 2026-08-30 NAS CD（用户确认窗口；VPS 不在本票，见本地未跟踪留存的 vps-public-authority 票）：
  - 门禁：`cargo fmt --check` + `test --locked` 511 + `clippy -D warnings`；`./build-image.sh` → `lezi-sync:0.4.7` linux/amd64 config digest `sha256:5e8ce13dcda79675569ff2efaa7684279f9cdd91b5203101e7e595c125902612`；`./deploy/push-and-deploy.sh` 完结。
  - 健康：LAN HTTPS `/health` `/ready` `version=0.4.7` `server_schema=13`；`docker exec lezi-sync lezi-sync healthcheck` exit 0；容器 `lezi-sync:0.4.7` healthy。
  - TLS 连续：cert file sha256 `75023c71…e362da9e`、SPKI `bd07d864…c0cb3215` 部署前后相等；remote-deploy 打印 pre/post 相同。
  - 活集：schema 13、1 家庭、live 513 / tombstone 43 / head 784；七类 count+key_digest 与 payload content digest `b4c85020…a712c6c6` 部署前后全等；`lezi.db` sha256 `30bd9db9…2a0b0cb` 字节级相同。
  - 旧 APK：渠道替换前为 0.4.5 / code 26；替换后 `min_supported=21` 未抬；`GET /v1/pull` 带或不带 `include_live_census` 均为 401 `Invalid or revoked token`。
- 2026-08-31：用户确认新 APK 对 NAS 0.4.7 联调完成（普查核对）。
