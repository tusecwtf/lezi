---
status: partially superseded by ADR-0012 for Android local data; NAS fresh-current and fail-closed startup still apply; sole NAS offline cutover exception is documented in ADR-0013 (does not overturn this ADR’s startup contract)
---

# 全产品只支持 fresh-current 契约

> Android 本地持久化部分自 0.3.0 基线起由 [ADR-0012](./0012-preserve-android-local-data-across-in-place-upgrades.md)
> 取代；NAS schema 与家庭同步 wire 仍按本文 fresh-current、fail-closed。
> 已授权 **offline-migrate**（备份上的离线 CLI + 维护窗切割切换）是本文的唯一 NAS
> 例外，见 [ADR-0013](./0013-offline-migrate-is-maintenance-window-cutover.md)；它不是
> 服务启动自动迁移，也不构成一般滚动 schema 兼容，**不**推翻本文的 fail-closed 启动合同。

Android、家庭同步协议与 NAS 服务只支持当前版本创建的数据和当前版本之间的交互，
不再维护从旧 Room schema、旧 payload、旧计时/提醒状态、旧 NAS schema 或旧 wire
滚动升级的平行路径。这个决定取代 ADR-0001 的历史 `CalendarEvent` 后果，完整取代
ADR-0002 对历史 `memo`、`other` 与裸 `custom` Record/快捷引用的保留，取代 ADR-0003
对旧 payload 照片的兼容后果，并取代 ADR-0005 的旧客户端协议兼容后果和 ADR-0007
的旧库身份迁移后果；这些 ADR 保留为历史记录。ADR-0002 中“新建入口只允许具体项目”
的产品方向由本文重新确认，但不再附带旧记录或旧快捷引用读取路径。

## 当前合同

- Android 在 0.3.0 之前只从当前 Room schema 新建数据库；0.3.0 起使用独立本地数据契约和
  相邻迁移链承诺原地升级保留。基线之前的旧 Room schema 仍不是受支持输入，但必须在业务
  数据打开前稳定阻断且不得自动删除。
- 当前记录目录不含 `memo`、`other` 或无具体项目身份的裸 `custom`；具体自定义项目仍以
  `custom_item_id` 创建 current `custom` Record。照片只使用当前 Record/MediaAsset 关联。
- 护理记录表示已发生事实，护理计划表示未来意图。乐记日历只展示 `CarePlan`，系统日历
  只保存当前设备授权后的单向副本；不再提供 `CalendarEvent` 的显示、编辑、提醒或转换入口。
- NAS 只初始化空目录、不存在或零字节的数据库为**精确 current schema**
  （`PRAGMA user_version` 等于当前 `DATABASE_SCHEMA_VERSION` 且表、索引、约束形状完全
  匹配）。已有数据库只有在精确 current 时才可重启并保留家庭、凭证、实体与媒体；
  非空旧版、未来版或形状不匹配的数据库在任何目录、权限或 sidecar 变更前 fail closed。
  服务启动 / `Store::open` **不得**探测旧库后自动迁移、destructive fallback 或部分原地
  改写；拒绝前不得创建 `media/`、`server.secret` 或 SQLite sidecar。
- HTTP 只接受当前 wire。所有 Record（含零照片）、CarePlan、Baby、CustomItemDef 与
  FulfillmentCandidate 都只经 atomic bundle 发布；`/v1/push` 和普通媒体上传已退役，
  `log` 只能作为 Record/CarePlan 包成员，`avatar` 只能作为 Baby 包成员。pull 发出 live
  Record/CarePlan 时须在同页共组其 live `log` 媒体。客户端不向旧 NAS 降级，服务端也不
  接受为旧客户端保留的字段、别名或 ordinary 发布旁路。
- Android 为 Record/CarePlan 持久化独立于媒体的本机根发布回执。它只在 atomic commit
  成功或 pull/apply 已提交根后前进；媒体上传 URI 不证明根已发布，过期回执也不能确认
  较新的本地修订。跨家庭边界必须清空该回执。
- 独立 log 媒体包与 avatar-only Baby 包仍以完整 atomic root 发布，并把 root `updatedAt`
  抬到 `max(nextPackageVersion(localRoot), media…)`。成功 commit 后必须用**同一**
  `rootUpdatedAt` CAS 确认本地根：Record/CarePlan 前进 `family_published_updated_at`
  并在内容 epoch 未变时把本地 `updated_at` 对齐到该修订；Baby 无独立回执列，等价水印
  是 CAS 前进本地 `updated_at` 并 `markSynced`。并发根编辑不得覆盖新内容、不得错误清
  dirty，较新根须保留/重建 outbox；较旧回执不得倒退较新水印。不引入媒体-only wire。
- 客户端只要求 `/health` 为 `ok` 且 capabilities 至少包含 `atomic_bundle` 与
  `record_membership_author`；允许服务端增加能力，展示用 `version` 不参与兼容门闩。
- `membership_id` 是记录作者与 ACL 的唯一家庭身份。`device_id` 只用于当前建家、加入与
  token 会话绑定，不进入 members 响应或 Record 作者 payload。当前 Record 合同允许任一
  active 家庭成员按 LWW 编辑或删除任意护理记录；CarePlan 与 CustomItemDef 仍遵循
  creator-or-owner 权限。

## 保留的兼容与恢复边界

fresh-current 不取消已声明的平台兼容：Android 继续支持 `minSdk` 范围内的系统版本、
系统日历 provider 的权限和厂商差异；Docker 继续支持明确交付的目标架构和 NAS 文件系统
能力差异。这些平台差异不能重新引入旧产品 schema 或旧 wire。

同样保留当前格式内的故障恢复：事务回滚、WAL/文件持久化、原子媒体发布、幂等重试、
当前提醒与计时状态的进程重启恢复、游标/generation 全量校准、权限撤销与 provider 失败
恢复。NAS/wire Release 证据仍来自 fresh-current 端到端；Android Release 另须证明从已承诺
本地数据契约原地替换后数据保持、失败无破坏，以及目标 APK 的契约兼容门禁。

## NAS 离线切割例外（非启动路径）

家庭 NAS 上若仍存在历史 **v3** 数据根，**不得**靠服务启动自动升级。唯一允许的出路是
[ADR-0013](./0013-offline-migrate-is-maintenance-window-cutover.md) 规定的两阶段路径：
在独立备份上用显式 `lezi-sync offline-migrate` CLI（dry-run / migrate / validate，
不触碰 live bind），再经**已授权维护窗** stop → dual backup → copy-back → TLS CD →
health 切换 data bind。发布二进制可包含该子命令，**不**表示支持一般滚动兼容；
普通 CD **不得**执行该子命令。权威步骤见
[`tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md`](../../tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md)。
