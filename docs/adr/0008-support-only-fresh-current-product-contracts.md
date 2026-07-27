---
status: accepted
---

# 全产品只支持 fresh-current 契约

Android、家庭同步协议与 NAS 服务只支持当前版本创建的数据和当前版本之间的交互，
不再维护从旧 Room schema、旧 payload、旧计时/提醒状态、旧 NAS schema 或旧 wire
滚动升级的平行路径。这个决定取代 ADR-0001 的历史 `CalendarEvent` 后果，完整取代
ADR-0002 对历史 `memo`、`other` 与裸 `custom` Record/快捷引用的保留，取代 ADR-0003
对旧 payload 照片的兼容后果，并取代 ADR-0005 的旧客户端协议兼容后果和 ADR-0007
的旧库身份迁移后果；这些 ADR 保留为历史记录。ADR-0002 中“新建入口只允许具体项目”
的产品方向由本文重新确认，但不再附带旧记录或旧快捷引用读取路径。

## 当前合同

- Android 只从当前 Room schema 新建数据库，并只读写当前 payload。当前 schema 的数据库
  可在同版本进程重启后继续使用；旧 Room schema、旧 payload 与旧计时/提醒状态不是受支持输入。
- 当前记录目录不含 `memo`、`other` 或无具体项目身份的裸 `custom`；具体自定义项目仍以
  `custom_item_id` 创建 current `custom` Record。照片只使用当前 Record/MediaAsset 关联。
- 护理记录表示已发生事实，护理计划表示未来意图。乐记日历只展示 `CarePlan`，系统日历
  只保存当前设备授权后的单向副本；不再提供 `CalendarEvent` 的显示、编辑、提醒或转换入口。
- NAS 只初始化空目录、不存在或零字节的数据库为精确 SQLite schema v3。已有数据库只有
  在 `user_version=3` 且 schema 形状完全匹配时才可重启并保留家庭、凭证、实体与媒体；
  非空旧版、未来版或形状不匹配的数据库在任何目录、权限或 sidecar 变更前 fail closed。
- HTTP 只接受当前 wire。`/v1/push` 与 `/v1/media` 是当前 ordinary 实体/媒体路径，
  带照片的 Record 与全部 CarePlan 使用 atomic bundle；客户端不向旧 NAS 降级，服务端也不
  接受为旧客户端保留的字段或别名。
- `membership_id` 是记录作者与 ACL 的唯一家庭身份。`device_id` 只用于当前建家、加入与
  token 会话绑定，不进入 members 响应或 Record 作者 payload。

## 保留的兼容与恢复边界

fresh-current 不取消已声明的平台兼容：Android 继续支持 `minSdk` 范围内的系统版本、
系统日历 provider 的权限和厂商差异；Docker 继续支持明确交付的目标架构和 NAS 文件系统
能力差异。这些平台差异不能重新引入旧产品 schema 或旧 wire。

同样保留当前格式内的故障恢复：事务回滚、WAL/文件持久化、原子媒体发布、幂等重试、
当前提醒与计时状态的进程重启恢复、游标/generation 全量校准、权限撤销与 provider 失败
恢复。Release 证据必须来自 fresh install、同一 current schema 重启持久化、current wire
端到端和这些故障路径；旧版本升级或旧入口 smoke 不再是门禁，也不能冒充当前验收。
