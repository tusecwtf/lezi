---
status: accepted
---

# offline-migrate 是已授权维护窗切割的离线工具族

`lezi-sync offline-migrate` 是面向**家庭 NAS 运维**的一次性 v3→current **离线** CLI
族。它**不是** server startup 或 runtime 的自动 schema 迁移，也**不**推翻
[ADR-0008](./0008-support-only-fresh-current-product-contracts.md) 对 NAS schema 的
fresh-current / fail-closed 合同（Android 本地持久化仍见
[ADR-0012](./0012-preserve-android-local-data-across-in-place-upgrades.md)）。

- **切割切换**（copy-back、停服、TLS 发版替换）只在**已授权维护窗**中进行。
- **离线准备**（copy-out、`dry-run` / `migrate` / `validate`）在维护窗**之前**对
  独立备份完成，**不**要求此时停服，也**不**触碰现网 data bind。

发布二进制可以包含该子命令；包含本身**不**表示产品支持一般滚动 schema 兼容。
普通 CD（`package-nas` / `push-and-deploy` / 容器重启）**不得**执行
`offline-migrate`。

## 与 ADR-0008 的关系

ADR-0008 仍是 NAS 日常合同：

- 服务启动 / `Store::open` 只接受**精确 current schema**
  （`PRAGMA user_version` 等于当前 `DATABASE_SCHEMA_VERSION` 且表、索引、约束形状
  完全匹配），或空目录 / 不存在 / 零字节库的初始化。
- 非空旧版、未来版或形状不匹配的数据库在任何目录、权限或 sidecar 变更前
  **fail closed**。
- 启动路径**不得**：探测旧库后自动迁移、destructive fallback、部分原地改写，或
  在拒绝前创建 `media/`、`server.secret`、SQLite sidecar。

本文允许的**唯一**例外是下文两阶段流水线：把源备份**读出**并写入**独立临时目标**，
验证通过后再由运维在维护窗内切换 data bind——服务进程本身始终只打开 current。

## 架构不变量（全程成立，非步骤顺序）

下列条目是**架构边界**，不是可重排的运维序号：

| 不变量 | 含义 |
|--------|------|
| 显式 CLI | 运维在开发机/运维机执行 `lezi-sync offline-migrate …`；非服务启动钩子 |
| 固定源 → current | 源为测量到的 v3 备份（`user_version=3`）；目标为当前 `DATABASE_SCHEMA_VERSION` |
| 独立临时目标 | `--in` 只读备份；`--out` 与 `--in` 独立且空（或尚不存在）；禁止原地改写 live bind |
| 验证后再切换 | `validate --out` 通过后，才允许维护窗内 copy-back 与 TLS 部署 |
| 进程只开 current | 服务启动 / `Store::open` 仍只接受精确 current；切割不改变该合同 |
| 停服仅属切割 | stop/rm 现网容器是**维护窗切割**的一步；**不是** `migrate`/`dry-run`/`validate` 的前置 |

## 两阶段运维流水线

权威细节、默认控制面与回滚：

- [`tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md`](../../tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md)
- copy-out：[`tools/lezi-sync/deploy/copy-out-nas-data.sh`](../../tools/lezi-sync/deploy/copy-out-nas-data.sh)
- copy-back：[`tools/lezi-sync/deploy/copy-back-nas-data.sh`](../../tools/lezi-sync/deploy/copy-back-nas-data.sh)
- 普通 CD（不含 offline-migrate）：[`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md)

机器可读 inventory / disposition：`tools/lezi-sync/src/offline_migrate/inventory.rs`。

### 阶段 A — 离线准备（维护窗前；可在线服务仍运行）

对**独立 copy-out 备份**与**独立临时 `--out`** 执行；**不** stop 现网容器，**不**
写 live data bind：

1. copy-out 只读备份（脚本 / 运维）
2. `offline-migrate dry-run --in <backup>`
3. `offline-migrate migrate --in <backup> --out <out>`
4. `offline-migrate validate --out <out>` → `validate ok`
5. 准备 current TLS 发版包（**不**在确认维护窗前 `push-and-deploy`）

`migrate` / `dry-run` / `validate` **不** stop 现网容器，也**不**执行 copy-back。

### 阶段 B — 维护窗切割（固定顺序，不得重排）

**仅**下列切割步骤声明「固定顺序，不得重排」——与
`offline_migrate::cutover::cutover_maintenance_steps()` / runbook
「Fixed step order」一致：

1. stop live container（**不**删除宿主 data bind 目录）
2. confirm dual backup（local copy-out + NAS-side）
3. copy-back upgraded `out/` to NAS data bind
4. start current TLS deploy（CD）
5. health/ready by actual protocol

`copy-back-help` / `live-cutover-help` 只打印维护顺序与证据清单，**不**声明 live 成功。
失败则按 runbook 回滚到维护前可用 v3（copy-out + pre-cutover 镜像），而非半成品 `out/`。

### CLI 子命令（须与 `--help` 一致）

```text
lezi-sync offline-migrate migrate   --in <backup_data_dir> --out <out_data_dir> --new-root-password <secret>
lezi-sync offline-migrate dry-run   --in <backup_data_dir> --new-root-password <secret>
lezi-sync offline-migrate validate  --out <out_data_dir>
lezi-sync offline-migrate copy-out-help
lezi-sync offline-migrate copy-back-help   # alias: cutover-help
lezi-sync offline-migrate live-cutover-help
lezi-sync offline-migrate help
```

密码亦可来自环境变量 `LEZI_MIGRATE_NEW_ROOT_PASSWORD`（≥16 字符，与
`LEZI_BOOTSTRAP_SECRET` 规则一致）。文档与脚本**不得**打印 bootstrap / 根密码明文。

## Secret、data bind、备份与回滚

| 主题 | 合同 |
|------|------|
| 迁移期新根密码 | 运维选定；写入目标 `owner_root_fingerprint`；cutover 后作为 `LEZI_BOOTSTRAP_SECRET` |
| 预 cutover 容器 secret | **void**；不得在 cutover 时 inherit 预迁移 secret（会错绑指纹并撤销设备） |
| `server.secret` | 目标侧**始终重新生成**；禁止从备份复制 HMAC 材料 |
| Data bind | 宿主路径（默认见 DEPLOY / runbook）bind 到容器 `/data`；uid `10001:10001`；stop/rm **不**删宿主目录 |
| 双备份 | 本地 copy-out + NAS 侧备份；rollback 恢复 **copy-out v3** 与 pre-cutover 镜像，而非半成品 `out/` |
| 目标校验 | `offline-migrate validate --out`：current schema 全量 preflight + `server.secret` 长度门禁（≠ 完整 `/ready`） |
| 再认证 | 无静默恢复会话；Owner 用迁移期新根密码；成员走当前申请/审批或 login-grant |

Cutover 部署可选用 `LEZI_FORWARD_BOOTSTRAP_SECRET=1` 转发迁移期 secret；**普通 CD 不得**
设置该标志（见 DEPLOY.md）。

## Departed membership 变换（hard-delete disposition）

与 live `hard_delete_membership` 对齐（审计票 14）：

- **Active** membership（v3 `left_at IS NULL`）才复制到目标库。
- **Departed** membership（v3 `left_at IS NOT NULL`）按 hard-delete **丢弃**：不复制
  membership 行、不占用 display name、不复制 device / credential / request / session。
- 保留的 Record / CarePlan / CustomItem / FulfillmentCandidate 与 committed bundle 上
  指向 departed membership 的作者 / 提交者 / stager 引用置 null / 空串（匿名事实），
  禁止悬空 FK 或伪归因。
- dry-run 与 migrate 共用同一 disposition；报告区分 `memberships`（copied）与
  `discarded_departed_memberships` / `anonymized_membership_refs`。
- 目标库无 departed 身份墓碑；迁移后 members API **无需也不能**再“清理”旧 departed 行。
- Active Owner 唯一性与 active display-name 冲突仍 fail closed；departed 行不参与计数。

## 明确禁止

- 将 `offline-migrate` 描述为滚动升级 / 多版本 schema 兼容路径。
- 服务启动时调用 migrator、探测 `user_version` 后自动升级、或对 live `lezi.db` 部分原地改写。
- 普通发版 CD 流水线中自动执行 offline-migrate 子命令。
- 第二套并行迁移工具或“启动时 destructive recreate 旧库”旁路。
- 在日志、帮助文本或 runbook 示例中打印真实 bootstrap secret。
- 把「停服」写成 `migrate`/`dry-run`/`validate` 的前置条件（那会拉长停机、与 runbook 冲突）。

## 术语

| 用语 | 含义 |
|------|------|
| **offline-migrate** | 显式 CLI 子命令族；私有 NAS v3→current 离线流水线 |
| **离线准备 / offline prep** | 维护窗前：copy-out + dry-run/migrate/validate 于独立备份与临时 `out/` |
| **维护窗切割 / maintenance-window cutover** | 已授权停服后：双备份确认 → copy-back → TLS CD → health |
| **fresh-current（NAS）** | 日常只接受精确 current schema；非空旧库 fail closed |
| **copy-out / copy-back** | 只读拉备份 / 将已 validate 的 `out/` 写回 data bind |
| **authoritative runbook** | `copy-back-tls-cutover-runbook.md`（步骤与回滚）；普通 CD 见 `DEPLOY.md` |
