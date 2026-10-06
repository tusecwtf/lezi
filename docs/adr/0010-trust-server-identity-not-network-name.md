---
status: superseded by ADR-0011
---

# 家庭同步信任服务器身份而不是网络名称

Lezi 将家庭同步的安全边界从“SSID 精确命中 + HTTP 可达 + 长期 family token”迁移到
“HTTPS transport trust + 持久 server identity + 可轮换设备会话”。Endpoint 地址只是
可替换路由；SSID 只剩可选的“仅 Wi‑Fi”网络偏好，不再证明服务器或家庭身份。

同一家庭服务可部署在 NAS 或单实例 VPS，但任一时刻只有一个 authority。完整迁移保留
`server_id`、`dataset_epoch`、family、membership、change history 和 media；计划迁移使用
旧服务器签名的短期 handoff，灾难恢复要求完整身份备份与 Owner 明确确认。双主、双写和
自动合并两个 authority 不受支持。

## Identity and credential model

- 安装级 Ed25519 公钥指纹是稳定 `server_id`，与 URL、IP、DNS、TLS 证书续期和进程生命
  周期分离；非空数据集缺失身份私钥时 fail closed。
- TLS 必须通过系统 PKI 或 QR 固定的 SPKI 验证；health 响应、server ID 文本、ping 和 SSID
  都不能替代 transport authentication。
- `membership_id` 继续表示一个家庭中的一台设备身份；不增加全局 User，同一自然人多机
  仍是多个 membership。
- access/refresh token 只是可过期、轮换、重放检测和吊销的 `DeviceSession` credential，
  服务端从 credential 解析 membership、family 与 role，不信任客户端自报 ID。
- `dataset_epoch` 属于数据集并持久化；普通重启和 endpoint 迁移不改变它，只有历史被替换
  或回退时才改变并触发 full resync。

## Considered Options

- **直接删除 SSID，只检查 IP:port 和 `/health`：** 可用性更宽，但未解决明文 Bearer 泄露、
  同地址冒充或服务身份替换；拒绝。
- **继续硬绑定 SSID：** 实现已存在，但 SSID 是位置敏感、可伪造且不支持蜂窝/VPN/VPS；
  不能作为长期安全边界。
- **只用 public CA：** 对 VPS 简洁，但要求家庭 NAS 持有可公开验证域名；保留为一种模式，
  同时支持 QR-pinned SPKI。
- **引入全局用户/OAuth 平台：** 有利于多家庭 SaaS，但扩大产品、隐私和运维范围；本阶段
  采用自发行 opaque session。
- **迁移时直接改 URL 并复用 token：** 容易把 credential 发给错误服务；拒绝，必须先验证
  transport、server identity 与 migration authorization。
- **NAS/VPS 双主过渡：** 需要冲突仲裁和复制协议，违背单一家庭 authority；拒绝。

## Consequences

- 生产 cleartext HTTP、QR v1、SSID 白名单和长期 family token wire 将在一次 fresh-current
  cutover 中退役，不提供混合版本降级。
- Server data root 必须把 DB、media、server identity、dataset epoch 及 pinned TLS key
  当作同一恢复单元；bootstrap secret 仍独立保管。
- Android 必须先建立 EndpointProfile trust，再发送邀请码、bootstrap secret、refresh token
  或家庭数据；trust mismatch 是硬阻断而不是可忽略警告。
- 服务端与客户端增加 session rotation、device revoke、migration handoff 和细分失败状态；
  这是换取公网 VPS 可迁移性和消除 SSID 伪安全边界的必要复杂度。
- 前台、本地优先、Outbox、原子包、单家庭、SQLite 和一设备一 membership 保持不变。

产品级完整合同见
[`docs/spec/sync-trusted-endpoint.md`](../spec/contracts/sync-trusted-endpoint.md)。
