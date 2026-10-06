---
title: 0.4.8 发布窗交叉审核（仅 P2+）
date: 2026-09-05
pin: d7d1650d..HEAD（0.4.7 发布钉 → 当前 tree）
status: 只记录，不改代码
---

# 0.4.8 交叉审核：P2 及以上

对照 11 路并行审查（兼容矩阵、wire 冻结、心跳 spec、其余 0.4.8 spec、CD 钉、VPS/03 门、Room 升级、近邻 schema、Standards、Security、Bugbot）以及现网只读探测。每一条都回代码或 live 再核过；只列 **P2 及以上**。Security / Bugbot 未交出终稿，不把「它们没报」当成通过。

**分级**

| 级 | 含义 |
|---|---|
| P1 | 按现网状态跑这次生产 CD，会失败，或打到错误的家庭权威 |
| P2 | 升级/回滚/兼容会出现用户可感故障，或文档里的某条 CD 路径会 abort |

P3 及以下（文案漂移、README 目录树漏 `generation`、`LEZI_GENERATION` 环境变量未接、ui-copy Tier A 未迁完、Standards 气味、后台循环「下一拍再拆」而不是立刻 `job.cancel()`）已剔除，见文末。

---

## P1

### 1. `invite.example.invalid` 公网 A 记录不存在

- **交叉：** VPS 切过审查、DEPLOY-VPS 门、现网 DoH。
- **证据：** Cloudflare DoH 对 `invite.example.invalid` 返回 NXDOMAIN。`tools/lezi-sync/deploy/vps-control-plane.sh` 要求未劫持 A 必须是 `203.0.113.10`；对不上就 `lezi_vps_fail`。本机 `dig @1.1.1.1` 仍被 fake-ip 成 `198.18.0.78`，不能当验收。
- **为何是 P1：** `push-and-deploy-vps.sh` 的成功门是公网 `/health` `/ready` `/v1/setup-status` `/join` `/download/lezi.apk`。DNS 不在，08 即使前面都过也会在最后 probe 炸。
- **不是：** 0.4.8 程序缺陷。是发布窗前置。

### 2. 08 当「普通 VPS replace」跑，会在密钥门就停；03 没做

- **交叉：** VPS 切过审查、`push-and-deploy-vps.sh`、ticket 03/08、vps-host 只读。
- **证据：** vps-host 上 `lezi` / `lezi-caddy` 都是 inactive；`/var/lib/lezi-sync/data`、`config` 不存在。普通路径 `export-vps-credentials.sh` 在 unit 不在时 exit 3 → 备份失败 → 未设 `LEZI_FORWARD_BOOTSTRAP_SECRET` / `LEZI_ALLOW_SECRET_RECOVERY` 就拒绝替换（`push-and-deploy-vps.sh` 密钥/备份段）。
- **为何是 P1：** 08 假定生产 VPS **已经存在**（同一家庭、同一根密码、可 age 备份）。03（NAS→VPS 搬家、拆 1.0.0、DNS、手打 rsync）仍是 `ready-for-human`。只跑 08 **不会**把现网家庭搬过去；普通脚本今天在密钥门 abort。
- **不是：** 0.4.8 协议/schema 不兼容。

### 3. vps-host 上 1.0.0 仍在 Tailscale `:8765` / `:8767` 冒充家庭服务器

- **交叉：** 现网探测、ticket 03、`remote-deploy-vps.sh` `assert_loopback_only`。
- **证据：** `/opt/lezi-sync/current` → `releases/1.0.0/…`；进程听 `192.0.2.36:8765` 和 `:8767`。health 是 `version=1.0.0`、`server_schema=2`、`family_access_v1` / `typed_replica_v1`，与 0.4 `causal_sync_v2` / schema 13 **协议不兼容**。新 0.4.8 绑 `127.0.0.1:8765`，和 1.0.0 **不抢端口**，所以两者可以并存。
- **为何是 P1：** 03 写明必须拆掉，否则空的 `family-roots` 会冒充家庭服务器。若有人把手机指到 Tailscale `:8765`，会打到错误协议。若强行越过密钥门做 replace，非 loopback `:8765` 会让 `assert_loopback_only` 失败。
- **不是：** NAS 现网问题。NAS 仍是 0.4.7 / schema 13 / `configured`。

---

## P2

### 4. NAS 打包/校验没有 0.4.8 版本对，默认回滚 CD 会 abort

- **交叉：** CD 钉审查、`package-nas.sh`、`validate-nas-package.sh`、ticket 08「NAS 回滚说明核对」。
- **证据：** `package-nas.sh` 的 `case "${version}"` 最高只写到 `0.4.7`（schema 13、回滚源 0.4.6/13）。`0.4.8` 落到 `*`：不显式给 `LEZI_PACKAGE_SERVER_SCHEMA` + `LEZI_PACKAGE_ROLLBACK_SOURCE_VERSION` + `LEZI_PACKAGE_ROLLBACK_SOURCE_SERVER_SCHEMA` 就 `unknown releases… exit 1`。`validate-nas-package.sh` 只钉死 0.4.7 身份，没有 0.4.8 配对断言。
- **为何是 P2：** 08 要求「0.4.8 APK+服务端可整体回滚，无交叉态」。默认 `./deploy/push-and-deploy.sh`（NAS 冻结回滚入口）会在打包阶段停。VPS 包走 `LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1`，**不吃**这个 `case`，所以这条不挡 VPS 钉校验，但挡文档里的 NAS 回滚通道。
- **不是：** APK sha / Cargo / catalog 对不齐。那些是 PASS。

### 5. 0.4.8 写过自动对齐后，回滚到 0.4.7 APK，下一次 pull 会 fail-closed

- **交叉：** Room 升级审查、近邻 schema 审查、0.4.7 `CausalDaos` 原文。
- **证据：** 0.4.8 把自动对齐的 `mutationId` 写成 `auto-near-neighbor:$relationId`（`CausalDaos.kt` 现树 875–881）。0.4.7 的 `applyPullSummary` 只接受 `pull-$relationId:…` 和 `pull-$relationId`，其它一律 `throw IllegalArgumentException("source relation peer set drift")`（`d7d1650d` `CausalDaos.kt` 828–835）。reason 仍是本地 `PULL_SUMMARY`，所以会走进这条分支。
- **触发：** 0.4.8 服务端对一次 record commit 做了 auto-align → 0.4.8 客户端收下并落库 → 该机再装回 0.4.7 → 再 pull 同一条 relation。成对回滚服务端到 0.4.7 **救不了**：库里的 relation 还在，0.4.7 客户端照样撞本地前缀。
- **为何是 P2：** 08 明确「无交叉态回滚」。正向 0.4.7→0.4.8 不受影响（0.4.8 认识旧 `pull-` 前缀）。这是 **回滚/降级** 雷，不是升上去打不开库。
- **不是：** 新 reason 枚举。服务端 CHECK 仍是 `author_declare` / `owner_group_resolve`；0.4.7 Room 不会因为 reason 炸。

### 6. 零进度熔断之后，心跳仍会按节拍再开一轮同步

- **交叉：** 心跳 spec 审查、`RealSyncPort` 现树、research §9.6。
- **证据：** research：「熔断后探针继续但不再触发轮（直到出现变化信号）。」实现里 `NeedsSync` 每次都 `requestSync(SyncTrigger.Foreground)`（`RealSyncPort.kt` 1026–1030）。`shouldAutoContinueForeground` 的计数器只罩 **同一簇** `sync()`；簇结束后计数清零。心跳在 `Syncing` 时会跳过探针，但熔断结束、状态离开 Syncing 后，若 `head_rev != cursor` 仍成立，30–120s 后再开 **新簇**。
- **为何是 P2：** 水位卡住（拉不动、零进度）时，0.4.7 要等人回前台或下拉；0.4.8 会在前台每半分钟到两分钟空转一轮。这是规格缺口，也是用户可感的「一直在同步」。
- **不是：** 握手/404 永停写错。那两条多路审查都确认实现符合矩阵。

### 7. NAS 操作者没有 docker，默认 NAS CD 和活库 copy-out 走不通

- **交叉：** 现网 SSH、`copy-out-nas-data.sh`、VPS/NAS 审查。
- **证据：** 数据 bind `700` / uid `10001`。`docker` 组是空的。SSH 用户 `13096920600` 不在该组，`sudo -n docker` 要密码。copy-out 在 host-path 失败后回落到 `docker exec lezi-sync tar`，这条现在也会失败。
- **为何是 P2：** 冻结的 NAS `push-and-deploy.sh` 要 `docker exec` 读 uid 10001 的 TLS/健康。现网操作者身份跑默认脚本会停在权限，而不是停在 0.4.8 程序。活库行级复核同样做不了（schema 打开风险已用「现网 0.4.7 已打开 + 0.4.7/0.4.8 SQL 逐字节相同 + 2026-08-13 schema-13 快照 53 对象全文匹配」代替，不升格为 schema blocker）。
- **不是：** schema 13 形状变了。没变。

---

## 交叉后明确不升到 P2 的

这些有人报过，回代码或对照规格后降级，**不列入上表**。

| 候选 | 降级原因 |
|---|---|
| 后台心跳未立刻 `job.cancel()` | `refreshHeartbeatLoop` 注释写明「下一拍再拆、零流量」；`runHeartbeatLoop` 每次 wake 先看 `isForeground`。smoke：HOME 150s 零探针。与「进入后台停止探测」的可观察合同一致。最多算实现与 US4「立即取消」字面差一截（P3）。 |
| 0.4.8 APK × 0.4.7 服务端，404 禁用到进程死 | 规格如此；内存门闩，不进 DataStore。升完服务端后划掉应用即可。重叠窗口残余，不是缺陷。 |
| 首次 0.4.8 启动新建 `{data}/generation` → 一次 409 | 与 0.4.7 **每次**进程重启重铸 generation 同类；之后重启复用文件。预期行为。 |
| 升级后第一次 commit 把历史近邻收进组 | ADR-0023 本意；不改 payload、不 tombstone、reason 仍是 `owner_group_resolve`。产品变化，不是兼容破裂。 |
| 握手 / setup-status / pull 信封 | wire 审查：`WIRE-FROZEN: yes`，`BLOCKER: none`。`sync_heartbeat_v1` 只在 setup-status。 |
| APK sha / signer / catalog / versionCode 29 | 与 `app-update.json` 逐字节一致。catalog 缺 0.4.8 released 行是发布前正确形状（target = last+1）。 |
| 时间轴徽章文案 vs「已自动对齐 · 可展开来源」 | UI/文档不一致，不影响同步或打开库（P3）。 |
| `LEZI_GENERATION` 环境变量未接 | 生产走 load-or-create；覆盖只在 `ServerConfig.generation`（测试/显式构造）。CD 不用它（P3）。 |
| Standards 重复代码 / 包位置 | 0 条硬违规。 |

---

## 对这次窗口的含义（仍不改代码）

| 路径 | 和上表的关系 |
|---|---|
| 现在就跑 `push-and-deploy-vps.sh` | 被 P1-1、P1-2 挡住；P1-3 在强行越过时还会再挡一层 |
| 只发 0.4.8 APK、服务端仍是 NAS 0.4.7 | 程序兼容 GO；心跳不会工作（预期）。不触发 P2-5，直到服务端也变成 0.4.8 并写过自动对齐 |
| NAS 原地 0.4.7→0.4.8 | 协议/schema GO；默认脚本被 P2-4、P2-7 挡住，需要配对 stanza + 能 docker 的操作者。预期一次 generation 409 |
| 发过 0.4.8 之后再整包回 0.4.7 | 被 P2-5 挡住：只要自动对齐已经落过本地，0.4.7 APK 再 pull 会炸 |

未改任何代码或配置。
