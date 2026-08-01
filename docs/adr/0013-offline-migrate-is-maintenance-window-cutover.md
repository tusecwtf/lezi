---
status: accepted
---

# offline-migrate 是已授权维护窗中的离线切割工具

`lezi-sync offline-migrate` 是面向**家庭 NAS 运维**的一次性 v3→current 离线切割
CLI，只在**已授权停服维护窗**中使用。它**不是** server startup 或 runtime 的自动
schema 迁移，也**不**推翻 [ADR-0008](./0008-support-only-fresh-current-product-contracts.md)
对 NAS schema 的 fresh-current / fail-closed 合同（Android 本地持久化仍见
[ADR-0012](./0012-preserve-android-local-data-across-in-place-upgrades.md)）。

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

本文允许的**唯一**例外是下文固定流水线；它把源备份**读出**并写入**独立临时目标**，
验证通过后再由运维在维护窗内切换 data bind——服务进程本身始终只打开 current。

## 允许的唯一流水线（固定顺序，不得重排语义）

| 步 | 含义 | 权威表面 |
|----|------|----------|
| 1. 显式 CLI | 运维在开发机/运维机执行 `lezi-sync offline-migrate …`；非服务启动钩子 | `lezi-sync offline-migrate help` |
| 2. 停服 | 维护窗内 stop/rm 现网 `lezi-sync` 容器；**不**删除宿主 data bind 目录 | copy-back runbook |
| 3. 固定源 schema → current | 源为测量到的 v3 备份（`user_version=3`）；目标为当前 `DATABASE_SCHEMA_VERSION` | `inventory` / migrator |
| 4. 临时目标 | `--in` 只读备份；`--out` 与 `--in` 独立且空（或尚不存在）；禁止原地改写 live bind | CLI 校验 |
| 5. 验证后切换 | `validate --out` 通过后，copy-back 到 data bind，再启动 current TLS 部署；失败则按 runbook 回滚到维护前可用 v3 | runbook + scripts |

权威运维 runbook（步骤细节、默认控制面、回滚）：

- [`tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md`](../../tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md)
- copy-out：[`tools/lezi-sync/deploy/copy-out-nas-data.sh`](../../tools/lezi-sync/deploy/copy-out-nas-data.sh)
- copy-back：[`tools/lezi-sync/deploy/copy-back-nas-data.sh`](../../tools/lezi-sync/deploy/copy-back-nas-data.sh)
- 普通 CD（不含 offline-migrate）：[`tools/lezi-sync/deploy/DEPLOY.md`](../../tools/lezi-sync/deploy/DEPLOY.md)

机器可读 inventory / disposition：`tools/lezi-sync/src/offline_migrate/inventory.rs`。

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

`migrate` / `dry-run` / `validate` **不** stop 现网容器，也**不**执行 copy-back；
`copy-back-help` / `live-cutover-help` 只打印维护顺序与证据清单，**不**声明 live 成功。

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

## 术语

| 用语 | 含义 |
|------|------|
| **offline-migrate** | 显式 CLI 子命令族；私有 NAS v3→current 离线流水线 |
| **维护窗切割 / maintenance-window cutover** | 已授权停服、validate 后切换 data bind 的运维动作 |
| **fresh-current（NAS）** | 日常只接受精确 current schema；非空旧库 fail closed |
| **copy-out / copy-back** | 只读拉备份 / 将已 validate 的 `out/` 写回 data bind |
| **authoritative runbook** | `copy-back-tls-cutover-runbook.md`（步骤与回滚）；普通 CD 见 `DEPLOY.md` |
