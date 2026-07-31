# Issues · trusted-sync-endpoint-auth

**Status:** ready-for-agent

**Spec:** [`spec.md`](./spec.md)

**Architecture:** [ADR-0011](../../docs/adr/0011-root-admin-and-multi-device-membership.md)

**UI design:** [可信同步接入与家庭身份 UI](../../docs/design/2026-07-30-trusted-sync-onboarding-ui.md)

本 tracker 将当前 Spec 拆为 17 个可依赖执行的本地 ticket。状态以各 ticket 文件为准；实现完成和验收证据齐全前，不得把后继票视为已完成。

| # | Ticket | Blocked by | Status |
|---|--------|------------|--------|
| [01](./issues/01-system-pki-server-probe.md) | 通过 HTTPS 探测家庭服务器 | — | complete |
| [02](./issues/02-self-signed-tofu-spki.md) | 确认自签名证书并固定 SPKI | 01 | complete |
| [03](./issues/03-owner-create-device-session.md) | 根密码创建家庭和管理员设备会话 | 02 | complete |
| [04](./issues/04-rotating-session-reauth.md) | 轮换设备会话并安全处理凭证丢失 | 03 | complete |
| [05](./issues/05-owner-login-takeover-root-rotation.md) | 管理员新设备登录、接管与根密码轮换 | 04 | complete |
| [06](./issues/06-member-request-approval.md) | 普通成员申请并由管理员批准 | 04 | complete |
| [07](./issues/07-bind-existing-member-device.md) | 绑定既有成员与多设备权限 | 06 | complete |
| [08](./issues/08-member-login-qr.md) | 成员登录二维码 | 02, 07 | complete |
| [09](./issues/09-family-members-devices-page.md) | 家庭成员与设备页面 | 05, 07 | complete |
| [10](./issues/10-member-device-names.md) | 成员与设备称呼生命周期 | 08, 09 | complete |
| [11](./issues/11-device-revoke-exit.md) | 撤销设备与退出当前设备 | 04, 09 | complete |
| [12](./issues/12-member-hard-delete-anonymize.md) | 成员彻底删除与共享数据匿名化 | 10, 11 | complete |
| [13](./issues/13-delete-family.md) | 删除家庭 | 05, 11 | complete |
| [14](./issues/14-foreground-sync-no-ssid.md) | 去除 Wi-Fi 绑定并收敛前台同步体验 | 08, 10, 11, 12, 13 | complete |
| [15](./issues/15-retire-legacy-network-auth.md) | 收口旧网络与鉴权模型 | 14 | complete |
| [16](./issues/16-cross-device-release-acceptance.md) | 跨设备发布候选验收 | 15 | ready-for-agent |
| [17](./issues/17-release-0.3.1.md) | 升级并交付 0.3.1 | 16 | ready-for-agent |

## 执行顺序

- 初始 frontier 只有 01。
- 04 完成后，05 与 06 可并行；其余票须按表中的全部 blocking 关系推进。
- 15 是 expand-contract 的 contract 阶段：新路径完整可用后，才删除旧 HTTP、SSID、邀请和长期 token 路径。
- 16 在固定候选 HEAD 上完成跨端验收；17 仅在 16 的证据全部通过后升级版本并重新构建最终 0.3.1 产物。
- 每次实现从当前无阻塞票中选取，完成代码、测试和票内证据后再更新状态；不要仅凭部分测试关闭 ticket。

## 明确不拆票

- App 端 NAS→VPS migration、handoff 或 restore；迁移由管理员在服务器部署侧完成。
- 可选仅 Wi-Fi、SSID/BSSID 家庭网络门闩或 ping 探活同步。
- 独立同步页、安全设置页、管理员设备登录历史或额外产品审计事件。
- 管理员二维码登录、服务器生成或展示二维码、额外校验码。
- 旧客户端或旧数据库迁移；当前 schema 与 wire 不匹配时失败关闭。
