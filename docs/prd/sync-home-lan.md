# 乐记 — 家庭同步历史基线（已取代）

> 状态：**已取代，不是当前产品或实现合同。**

0.3.0 曾以家庭网络名称、明文传输和长期家庭凭证作为同步基线。该协议、配置、
权限和界面已经在 0.3.1 的 fresh-current 切换中整体退役，不提供兼容路由、迁移写入或
降级入口。

当前唯一权威合同是
[`sync-trusted-endpoint.md`](./sync-trusted-endpoint.md)：用户确认可信 HTTPS endpoint，
管理员以根密码建家或登录，普通成员通过审批或管理员签发的单次登录授权取得独立设备
会话；同步仅在 App 前台触发，且不读取网络名称。

历史决策背景可查阅 [ADR-0010](../adr/0010-trust-server-identity-not-network-name.md) 与
[ADR-0011](../adr/0011-root-admin-and-multi-device-membership.md)。不得依据本文件恢复旧 wire、
旧持久化字段或旧产品入口。
