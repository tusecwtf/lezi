# 家庭同步网络边界、鉴权与身份架构研究

- 日期：2026-07-30
- 代码基线：`57e20122e598abec8b79e59aa700a3e95183dbfc`
- Status：Folded / Partly superseded
- 性质：初始研究依据。2026-07-31 后续 grilling 推翻了可选仅 Wi-Fi、一设备一 membership、
  server identity 客户端迁移与服务器 QR 等目标；最终合同以
  [`sync-trusted-endpoint.md`](../prd/sync-trusted-endpoint.md) 和
  [ADR-0011](../adr/0011-root-admin-and-multi-device-membership.md) 为准。下文保留为研究历史，
  不可直接据此拆票。

## 2026-07-31 supersession

- 完全移除 SSID/BSSID/仅 Wi-Fi，不再保留 optional Wi-Fi preference。
- membership 表示一个人并拥有多台独立凭证设备；管理员根密码声索唯一 Owner membership。
- 普通成员通过管理员审批或管理员 App 生成的目标 membership 单次 QR 登录。
- App 不实现 NAS→VPS handoff/migration/restore；地址改变后重新登录或申请。
- 不增加产品级审计系统、独立同步/安全页或管理员 QR 登录。

## 初始决策落点（2026-07-30，已部分被取代）

- 单家庭、单 authority；同一服务可在 NAS 或 VPS 部署。
- 生产只用 HTTPS：系统 PKI 或 QR-pinned SPKI；SSID 降为可选“仅 Wi‑Fi”。
- 持久 Ed25519 server identity、持久 dataset epoch；普通重启与迁移均保持。
- 每设备 opaque access/rotating refresh session；一设备一 membership，不增加全局 User。
- NAS→VPS 使用签名 handoff 或完整身份备份恢复；不做双主。
- 具体 TTL、失败语义、QR v2、备份单元与验收边界以已锁定 PRD 为准。

## 结论

不建议在当前实现上直接把“SSID 白名单”替换成“IP/端口可达 + `/health`”。

SSID 不是可靠的安全边界，长期应从强制同步条件降为可选的网络偏好；但当前链路允许
明文 HTTP，并携带长期 Bearer token，而 `/health` 只证明某个兼容进程可达，不能证明
它就是用户配对过的 Lezi 服务器。若先移除 SSID，客户端可能在其它网络上把凭据发给
同地址或伪装成 Lezi 的服务。

推荐顺序是：

1. 用 HTTPS 公共 PKI 或配对时固定的服务器公钥验证服务器身份。
2. 用受认证的 session/whoami 接口验证当前凭据对应的 membership 与 family。
3. 再把 SSID 从硬门闩改为可选的“仅家庭网络/仅 Wi-Fi”策略。
4. 将 endpoint 地址与稳定的 server identity、family session 解耦，为 NAS 到 VPS 迁移留出路径。

不需要额外 ICMP ping。ping 不检查端口；TCP connect 也已包含在 TLS/HTTP 请求中。
同步 API 自身仍必须处理连接后失败，所以单独 ping 只增加一次往返和竞态。

## 当前实现判断

当前设计不是“只认 Wi-Fi 名称”，而是以下串联门闩：

```text
前台
  -> 已配置 host/port 和 SSID 白名单
  -> TRANSPORT_WIFI
  -> 当前 SSID 精确命中
  -> GET /health 成功
  -> capability 合同匹配
  -> Bearer token 认证
  -> membership/role 授权
  -> push/pull
```

对应事实：

- `HomeNetworkPolicy` 在 health 前依次检查服务器、SSID、前台和 Wi-Fi。
- `/health` 仅返回 `ok`、`version`、`capabilities`，不检查 SQLite 或媒体目录，也不返回
  稳定 server identity。
- `/ready` 才检查 SQLite 和数据目录可写；它仍是 readiness，不是服务器或客户端身份认证。
- 受保护 API 从 Bearer token 的 hash 查找未吊销 credential，再得到 active
  `membership_id`、`family_id`、`role`。这条方向是正确的：服务端没有把客户端自报的
  `device_id` 当作权限依据。
- `membership_id` 是身份主键，token 是凭据；知道 membership ID 本身不产生权限。
- `baseUrl` 变化时客户端会清理旧 family credential/cursor。这能防止把旧 token 发给
  任意新地址，但也说明 NAS 到 VPS 不能靠直接改 IP 完成。
- Android release 全局允许 cleartext，以支持动态私网 IP；长期 Bearer 与家庭数据因此
  缺少传输机密性和服务器认证。

### SSID 到底提供了什么

| 能力 | SSID 精确匹配能否提供 |
|---|---|
| 降低在非家庭网络上无意义的探测 | 能 |
| 表达“只在家同步”的产品策略 | 能 |
| 证明目标 IP 是家庭 NAS | 不能 |
| 防止同名热点或链路中间人 | 不能 |
| 证明调用方是合法家庭成员 | 不能 |
| 支持 VPS/蜂窝/VPN | 不能 |

Android 将 SSID 视为位置敏感信息；权限或系统状态不满足时可能返回 unknown SSID。
当前代码还通过已废弃的 `WifiManager.getConnectionInfo()` 路径读取 SSID。移除硬 SSID
门闩可以减少权限和设置开关带来的失败面，但只有在传输信任补齐后才安全。

官方资料：

- [Android `WifiInfo`](https://developer.android.com/reference/android/net/wifi/WifiInfo)
- [Android `WifiManager.getConnectionInfo`](https://developer.android.com/reference/android/net/wifi/WifiManager#getConnectionInfo())
- [Android cleartext communication risks](https://developer.android.com/privacy-and-security/risks/cleartext-communications)

## 五个不能混在一起的概念

| 问题 | 应使用的机制 | 不应使用的替代物 |
|---|---|---|
| 到哪里连接 | endpoint URL、DNS、QR、可选 mDNS/NSD | SSID 充当地址 |
| 服务是否可用 | health/readiness、实际 API 结果、退避 | ICMP ping 充当应用探测 |
| 对端是不是正确服务器 | HTTPS 证书或已固定服务器公钥 | health JSON、server ID 文本 |
| 调用方是谁 | token、设备私钥签名、受验证的登录会话 | device/family/membership ID |
| 调用方能做什么 | server-side membership、role、对象 ACL | 客户端请求体中的 role/family ID |

TLS 服务身份验证的标准模型见 [RFC 9525](https://www.rfc-editor.org/rfc/rfc9525.html)。
Android NSD/DNS-SD 可以用于发现局域网服务，但发现结果只解决地址问题，不能建立信任：
[Android NSD](https://developer.android.com/develop/connectivity/wifi/use-nsd)。

## 推荐目标架构

### 1. Endpoint 与服务器身份分离

```text
ServerInstallation
  server_id                 稳定、公开标识
  public_key                服务器身份密钥，随受支持备份迁移

EndpointProfile
  server_id
  origin                    https://host:port
  mode                      LAN | REMOTE
  trust                     system PKI | pinned public key
  network_policy            ANY | WIFI_ONLY | HOME_ONLY(optional)
```

`server_id` 仍只是标识符。它必须由可信 TLS 或服务器私钥签名证明，不能因为 `/health`
返回相同字符串就接受对端。

NAS 配对 QR 可包含：HTTPS endpoint、一次性 invite code、server ID、公钥指纹。Android
首次配对让用户确认并固定公钥。mDNS 以后可以更新地址候选，但只有能证明持有原服务器
私钥的候选才可接替 endpoint。

VPS 使用正常域名和公共 CA 证书。若从 NAS 搬迁同一个单家庭服务，可保留 server/family/
membership/entity ID；但新 endpoint 必须由旧服务器签名声明，或由 owner 明确重新授权。
不能先把旧 Bearer 发给新 URL，再用响应判断它是不是正确服务器。

### 2. 健康、合同与 session 分层

建议接口职责：

```text
GET /health
  仅 liveness + protocol capabilities；无敏感身份信息

GET /ready
  运维/readiness；DB、媒体根、依赖是否可服务

GET /v1/session (authenticated)
  server_id、protocol、credential/session_id、membership_id、family_id、role

POST /v1/bundles / GET /v1/pull
  实际同步；仍独立处理超时、503、401、409 与幂等重试
```

已有 session 时，无需每轮固定执行“ping -> health -> sync”。在可信 TLS 建连后，可用
`/v1/session` 或实际 pull/push 同时完成可达性与认证检查，缓存能力合同，并保留现有指数
退避。新配对或服务版本变化时再强制刷新 capability。

### 3. 身份、membership 与 credential 分离

用户提出的“服务端通过 ID 校验客户身份”需要修正为：服务端通过凭据验证调用方，再从
服务端状态推导 ID。ID 可以公开、复制或猜测，不是身份凭据。

面向多用户 VPS 的完整模型：

```text
User
  user_id                    稳定的人类主体；外部 OIDC 可用 (iss, sub)

Device
  device_id
  user_id
  public_key, status, last_seen

Family
  family_id

Membership
  membership_id
  family_id, user_id
  role, status, joined_at

Credential / DeviceSession
  credential_id
  device_id, membership_id
  token_hash 或 public-key binding
  expires_at, revoked_at, last_used_at
```

服务端处理请求时：

```text
credential / device proof
  -> active DeviceSession
  -> User
  -> active Membership
  -> Family + role + object ACL
```

Record 作者继续存不可变 `membership_id`。设备丢失只撤销该设备 session；用户退出家庭
则撤销 membership 下全部 session，但保留历史作者归属。

如果 VPS 只是“把当前单家庭 NAS 搬到公网”，不必立刻引入 User/OIDC。当前 opaque
token -> membership 模型可以继续使用，优先补 HTTPS、每设备 credential、过期/轮换、
设备会话列表和单独撤销。只有决定做多家庭托管、同人多设备合并或跨家庭账号时，才引入
全局 User。

### 4. 凭据建议

短期 NAS：

- bootstrap secret 只用于首次 provision/reclaim，不是日常用户密码。
- 邀请码保持短时、单次、限流。
- join 后签发每设备独立、高熵、可吊销的 opaque credential；服务端只存 hash。
- credential 只通过可信 HTTPS 传输。
- 不必为了使用 JWT 而使用 JWT；单体服务的 opaque token 更容易即时吊销。

托管 VPS：

- Android 使用系统浏览器的 OIDC/OAuth Authorization Code + PKCE，不使用内嵌 WebView。
- 以 OIDC `(iss, sub)` 作为外部用户稳定键，不以可变 email/手机号作主键。
- access token 短时且限制 audience/scope；refresh token 使用轮换或 sender constraint。
- 风险需要时，用 DPoP 将 token 与 Android Keystore 设备私钥绑定，降低 bearer 泄漏后的
  跨设备重放风险。
- 邀请码的作用是授予 family membership，不应同时充当长期登录凭据。

标准依据：

- [RFC 8252: OAuth 2.0 for Native Apps](https://www.rfc-editor.org/rfc/rfc8252.html)
- [RFC 9700: OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/rfc/rfc9700.html)
- [RFC 9449: DPoP](https://www.rfc-editor.org/rfc/rfc9449.html)
- [OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html)

## 同步内核评估

Lezi 现有以下设计应保留：

- Room 是 UI 的本地真相源，离线写先成功。
- durable Outbox 保存待发布变更。
- server revision/cursor + 分页 pull 支持增量恢复。
- tombstone 传播删除。
- client UUID 与 bundle ID 支持重试幂等。
- Record/CarePlan 与 0-3 张照片以 atomic bundle 一次可见。
- 作者和 ACL 由认证 principal 在服务端盖章。

这些做法与 CouchDB 的 changes feed、checkpoint、小批次复制和显式恢复思路一致：
[CouchDB Replication Protocol](https://docs.couchdb.org/en/stable/replication/protocol.html)。
不需要为了借鉴这些原则，把现有服务整体换成 CouchDB。

面向 VPS 应继续强化：

1. `generation` 改为持久化 dataset epoch，只在变更历史失效、备份恢复等语义事件时变化，
   不随普通进程重启变化。多实例共享同一个 durable cursor/history。
2. mutation 带 operation/request ID，服务端保存幂等结果，避免超时重试重复应用。
3. 不把客户端墙上时钟 `updated_at` 作为唯一冲突裁决。对可编辑实体增加 base revision/
   optimistic concurrency，并按领域定义 merge、冲突或覆盖规则。
4. cursor 是服务端签发的 opaque checkpoint；客户端不自行构造或跳进度。
5. 继续采用有界分页、引用依赖共组、完整落盘后推进 checkpoint、历史失效时 full resync。
6. 多实例 VPS 将 SQLite 本机事务/进程内 family lock 替换为共享数据库和一致的事务边界；
   大媒体可放对象存储，但只有 DB commit 后才对家庭可见，并保留 staging GC。

Syncthing 的关键借鉴点不是它的协议细节，而是把地址发现与密码学身份分开。它的 Device
ID 实际来自证书公钥，连接时仍需在 TLS 握手中证明持有对应私钥；静态地址、局域网发现和
全球发现都只是找到地址：[Syncthing Device IDs](https://docs.syncthing.net/dev/device-ids.html)。

## NAS 到 VPS 的推荐演进顺序

### 阶段 A：补齐可信传输

- NAS 持久化服务器身份密钥。
- 支持 HTTPS；QR 带 server ID/公钥指纹。
- 客户端先验证 TLS/固定公钥，再发送 invite/bootstrap/Bearer。
- 带凭据请求继续禁止重定向；生产公网 HTTP 从“警告”改为拒绝。

### 阶段 B：解耦网络策略

- 引入 `EndpointProfile`。
- 把 SSID 从必填字段和硬门闩中移除。
- 可保留 `WIFI_ONLY` 或 `HOME_ONLY` 用户偏好，但不参与身份认证。
- LAN 动态地址可选 NSD；手工地址和 QR 继续可用。

### 阶段 C：凭据与会话治理

- 每设备 credential/session。
- session 列表、最后使用时间、单设备撤销、全 membership 撤销。
- token 过期/轮换；必要时绑定设备公钥。
- 新增受认证 `/v1/session`。

### 阶段 D：单权威迁移到 VPS

- 冻结 NAS 写入。
- 复制 DB、media、server identity 和校验 manifest。
- 保持 family/membership/entity ID；仅在变更历史不连续时切 dataset epoch。
- 用旧服务器签名迁移声明或 owner 重新授权新 endpoint。
- 新端验证通过、客户端换发 endpoint-bound credential 后恢复同步。
- 首版不要做 NAS 与 VPS 双主同步；否则会引入脑裂、双写冲突与回切协议。

### 阶段 E：只有需要托管多家庭时才增加账号平台

- 接成熟 OIDC 身份提供方。
- 引入 User、Device、Membership、Credential 分层。
- 做 tenant 隔离、配额、审计、备份恢复、密钥轮换、删除与导出流程。

## 最终建议

产品目标可从“硬家庭 Wi-Fi”调整为“可信家庭服务器”：

```text
旧：SSID 匹配 + health + Bearer

新：可信 endpoint(TLS/pin)
      + authenticated session(credential -> membership)
      + protocol capability
      + foreground/retry policy
      + optional network preference
```

因此，取消强制 SSID 是合理方向；“只要 health 通就同步”不是完整方案。正确替代物不是
更多网络探测，而是可验证的服务器身份、可撤销的客户端凭据、服务端 membership 授权，
以及与地址解耦的 endpoint/session 模型。
