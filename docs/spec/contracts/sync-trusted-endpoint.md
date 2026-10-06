# 乐记 — 可信家庭服务器、身份与前台同步规格

> 决策锁定：2026-07-31（连续 grilling，用户逐项确认）
> 实现基线：`39426a037986df574d16b0512a3db10f7c225382`
> 架构决策：[ADR-0011](../../adr/0011-root-admin-and-multi-device-membership.md)、
> [ADR-0014](../../adr/0014-owner-device-restores-only-empty-family-servers.md)。
> ADR-0017 仅为 settle skeleton 历史；当前发布是 [ADR-0022](../../adr/0022-commit-first-choice-only-conflict-snapshots.md) commit-first。
> （0.3.13 因果代见 ADR-0019/0020/0021）
> UI/UX：[家庭服务器与身份 UI](../../design/2026-07-30-trusted-sync-onboarding-ui.md)

> 当前 tree 是 **0.5.3**；协议代 / wire 身份仍是 **0.4.0**（code 21 / Room 28 / 契约 5）。

> 已按 [`causal-sync-wire.md`](./causal-sync-wire.md) 启用 `causal_sync_v2`
> commit-first、完整 ConflictSnapshot 与 choice-only resolution。家庭 NAS 是否已到该代以 live health 为准。

本文定义家庭同步下一条 fresh-current 产品合同。它取代已退役的家局域网/SSID/明文/长期 family token 合同（旧文 `sync-home-lan`，见 git 历史）；Room 本地优先、同步实体、原子照片包、冲突裁决和 ACL 仍沿用既有基线。普通发布走 ADR-0022 commit-first，字段权威见 [`causal-sync-wire.md`](./causal-sync-wire.md)。

## 1. 一句话合同

乐记只在前台通过用户信任的 HTTPS endpoint 连接一个家庭服务器。根密码声索唯一管理员，
管理员批准普通成员和设备；每个成员可有多台独立凭证设备。连接失败不影响本地记录，SSID、
ping 和 health 都不再充当身份或同步门闩。

## 2. 范围与不变量

| 主题 | 目标合同 |
|------|----------|
| 产品拓扑 | 单家庭、单服务实例、同一时刻一个写入 authority |
| 客户端真相源 | Room；所有写入先落 Room；普通发布走 commit-first |
| 服务器位置 | 家庭 NAS 或单实例 VPS；App 不感知部署类型 |
| 网络 | 任意可用网络；不读取、存储、展示或匹配 SSID/BSSID |
| 传输 | 生产 HTTPS only；VPS 用系统 PKI，家庭自签名服务用用户确认的 TOFU/SPKI |
| 管理员 | 每家庭唯一 Owner membership；根密码登录，可绑定多台 Owner 设备 |
| 普通成员 | 一个 membership 表示一个人，可绑定多台设备；称呼在当前家庭唯一 |
| 凭证 | 每设备 opaque 短 access + 轮换 refresh；ID、称呼和设备名都不是凭据 |
| 触发 | 仅前台：进入前台、本地写入、页面进入节流、记录/汇总/成长下拉刷新 |
| 兼容 | 一次 fresh-current 切换，不保留旧 HTTP、SSID、邀请码或长期 token 降级旁路 |

继续明确不做：后台轮询、FCM、永久前台服务、P2P、逐条伴侣通知、多家庭 SaaS、全局用户
中心、OAuth/OIDC、多主复制、两个已配置家庭的迁移/合并、产品级审计系统和登录历史页。

## 3. 身份、设备与凭证

```text
Family
  family_id, name
  one Owner Membership

Membership
  membership_id, family_id
  role = owner | member
  unique display_name
  0..n Devices

Device
  device_id, membership_id
  device_name             unique within membership
  status, last_used_at
  0..n DeviceSessions

DeviceSession
  token hashes, rotation lineage, revoked reason
```

### 3.1 管理员根密码

- `LEZI_BOOTSTRAP_SECRET` 是唯一管理员根密码，只由服务器部署环境管理。
- App 可以在管理员登录、接管或删除家庭确认时提交根密码，但不能查看、修改、导出或保存它。
- 根密码不作为日常 Bearer token，不写入二维码、日志、analytics、SavedState、系统备份或数据库
  日常会话。
- 登录成功后，服务器为当前管理员设备签发独立 access/refresh token。
- 默认登录为「添加管理员设备」；显式「丢失设备并接管」会撤销此前全部管理员设备凭证。
- 更换部署环境中的根密码并重启服务器会撤销全部管理员会话；普通成员会话继续有效。
- 管理员设备与根密码都丢失时，只能由服务器运维者更换根密码；普通成员不能继承、投票或被
  自动提升为管理员。

### 3.2 普通成员与多设备

- membership 表示一个家庭成员，不表示单台设备。
- 同一 membership 的多台设备共享「本人」权限：可编辑、删除该 membership 写出的记录。
- 每台设备使用独立凭证，撤销一台设备不影响同成员的其它设备。
- `membership_id` 是服务端分配的标识，不是密码；客户端持有它不能获得新 token。
- App 重装、凭证丢失或 refresh replay 后，普通成员必须重新申请并由管理员批准；只要
  membership 仍存在，管理员可把新设备绑定回原 membership。
- 新批准的成员或设备获得完整家庭历史和照片，不提供部分历史、时间窗或按类型可见性。

### 3.3 家庭称呼与设备称呼

- 家庭称呼是当前家庭内唯一的成员显示名。比较前执行 Unicode normalization、首尾 trim 与
  连续空白折叠；规范化后相同即冲突。
- 新成员在申请时声明自己的称呼。管理员可批准为新 membership、明确绑定到既有 membership，
  或拒绝；不得仅凭同名自动绑定。
- 普通成员改名需申请并由管理员批准；管理员可主动添加称呼、改名或删除成员。
- 设备称呼仅在同一 membership 内唯一。默认优先读取 Android
  `Settings.Global.DEVICE_NAME`（API 25+），不可用时用 `Build.MODEL`；提交前后都可编辑。
- 成员可改自己设备的称呼；管理员可查看、改名或撤销任意设备。

## 4. 服务器连接与信任

### 4.1 连接不是身份

不额外执行 ICMP ping 或 TCP 探测。TLS/HTTP 请求本身已经验证端口可达；生产同步只能在
transport trust 成立后继续。`/health` 仅证明兼容进程活着，不证明家庭、管理员或调用方身份。

| 机制 | 只负责 |
|------|--------|
| HTTPS endpoint | 路由到哪里 |
| 系统 PKI / TOFU-SPKI | 对端 transport 是否可信 |
| `/health`、`/ready` | liveness / 运维 readiness |
| `/v1/setup-status` | 可信连接后的最小建家状态 |
| access/refresh token | 当前设备属于哪个 membership、拥有什么权限 |

### 4.2 系统 PKI 与 TOFU

- 公网 VPS 使用系统 PKI；证书主机名、有效期和链必须通过平台验证。
- NAS 自签名 endpoint 使用 Trust On First Use：首次连接显示证书指纹，当前连接者明确点击
  「信任此证书」后，客户端固定 SPKI；不要求额外校验码或配对码。
- 该确认发生在角色选择、setup probe 与发送根密码之前；不要求先登录 Owner 才能点信任。
- 本设计接受首次连接时用户可能确认到错误证书的 TOFU 风险。
- 后续 SPKI 不一致必须硬阻断，不能「仍然继续」。用户只能忘记该服务器，再重新连接和确认。
- 在 transport trust 建立前，不发送根密码、登录授权、refresh token 或家庭数据。
- 开发/测试可对 loopback 使用 HTTP，但不能载入真实家庭凭证；release 不允许生产 cleartext。

### 4.3 最小 setup probe

可信 HTTPS 建立后，App 调用 unauthenticated `GET /v1/setup-status`。响应只能包含：

- 当前协议版本与客户端所需 capability；
- `family_state = empty | configured`。

不得泄露 family ID、家庭名、成员数、称呼、设备、server ID 或其它家庭元数据。

状态路由固定为：

| Probe 结果 | 下一步 |
|------------|--------|
| 无法连接或不是 Lezi | 修改地址、重试，或保持离线 |
| Lezi 但协议不兼容 | 提示更新服务器；不能尝试登录 |
| Lezi 暂未 ready | 提示服务器维护中；可重试或离线 |
| `empty` | 只显示「新建家庭」；需根密码 |
| `configured` | 只显示「加入家庭」；再选管理员或普通成员 |

用户不在连接前猜测「新建」还是「加入」。任何阶段都可「暂不连接，保持离线」。未通过信任与
probe 的地址草稿离开即丢弃，不能覆盖上一次可信 endpoint、有效 session 或本地家庭数据。
通过 probe 但尚未登录的 endpoint 可仅在本机记住，之后继续或忘记；此状态不上传任何数据。

## 5. 登录与加入家庭

### 5.1 管理员登录

管理员不使用二维码。`configured` 家庭选择「我是家庭管理员」后：

1. 确认可信服务器摘要；
2. 输入根密码和设备称呼；
3. 选择普通「登录此设备」，或进入明确的「丢失设备并接管」危险流程；
4. 服务端验证根密码并签发当前设备的 opaque session；
5. 首次完整 pull，成功后进入账户页。

错误根密码不能透露家庭内部信息。接管必须再次说明会让所有旧管理员设备退出并二次确认。

### 5.2 普通成员手动申请

`configured` 家庭选择「我是家庭成员」后，用户填写家庭称呼和设备称呼并提交：

- 请求本身没有家庭数据权限，24 小时过期；服务端必须限制来源速率，以及家庭内 pending +
  approved-unclaimed 开放申请总数。
- 申请设备只保存一个高熵 pending secret，用于查询、取消和批准后单次领取 session；公开或可猜
  request ID 不能领取凭证，也不能读取家庭信息。
- 普通成员看到「等待管理员确认」，可取消并继续离线；没有后台 push。
- 管理员下次前台打开 App 时，账户页显示「设备登录申请」角标。
- 管理员可明确：绑定到现有 membership、用该唯一称呼新建 membership，或拒绝。
- 同名只帮助管理员识别；系统不能据此自动绑定。
- 审批通过后，申请设备在下次前台检查时领取自己的 session 并完整同步。
- 已批准但尚未领取的申请继续对管理员显示「等待设备领取」；管理员可撤销批准并立即释放
  尚未落成 membership 的家庭称呼保留。申请领取或 24 小时到期后不再显示。
- Owner 列表维持旧客户端的 pending-only 响应；只有显式发送
  `X-Lezi-Member-Request-View: open-v1` 的客户端才收到 pending + approved-unclaimed 与
  `status`。不得仅按 versionCode 推断能力，因为同一 versionCode 可能存在预发布构建。

### 5.3 管理员二维码授权

- QR 只由管理员 App 在「家庭成员与设备」为一个目标 membership 生成；服务器只签发单次
  grant，不生成或显示 QR 图像。
- 同一个二维码同时携带 endpoint、固定信任材料和一次性设备登录 grant；没有第二张配置码。
- 服务器启用邀请安装页时，管理员 App 把原邀请载荷包装为系统相机可识别的 LAN URL；浏览器
  同时承担 APK 首装与历史版本恢复升级。首装后仍须回到乐记重新扫描同一码；同包名覆盖升级
  保留本地记录、家庭会话与 endpoint/SPKI 信任，不要求重新扫码。未启用时继续生成旧 raw
  JSON QR。
- grant 十分钟过期、单次使用，且只能绑定目标 membership；根密码永不进入 QR。
- 普通成员扫描后仍可确认或编辑设备称呼，然后一次完成信任配置与设备登录。
- 管理员登录不提供 QR；服务端部署页面也不需要显示 QR。

### 5.4 Access 与 refresh

- access token 短时有效，目标 TTL 15 分钟，只放内存并随请求发送。
- refresh token 每次使用都轮换；服务端检测旧 token 重放。
- refresh 没有时间或不活跃自动过期：有效设备可长期静默续期，直到显式撤销、主动退出、token
  丢失或 replay 安全事件。
- replay 只撤销该设备 session，保留本地家庭数据，并要求重新走管理员批准。
- 普通 401、凭证丢失或 token 失效不等于设备/成员被删除；App 保留 Room dirty、media 与
  endpoint，显示重新申请入口。

## 6. 删除、退出与本地数据

### 6.1 撤销一台设备

管理员可撤销任意设备，普通成员可从当前设备执行「退出这台设备」。服务端删除/撤销该设备
session，不影响 membership 和其它设备。

- 当前设备主动退出：服务端确认后立即清空本地 Room、media、endpoint 与凭证。
- 管理员远程撤销：设备下一次可信连接收到明确 `device_removed`，然后清空同一范围。
- 离线设备无法保证即时远程擦除；UI 和安全声明不得暗示已经远程销毁。
- 被撤销设备以后可重新提交加入申请。

### 6.2 删除普通成员 / 退出家庭

管理员「删除成员」或普通成员「退出家庭」执行同一个身份硬删除合同：

- 删除 membership、全部 devices、sessions、待审批和改名申请；
- 当前家庭称呼立即释放，可被新 membership 使用；
- 不保留旧成员、历史称呼或可恢复身份墓碑；
- 已有家庭记录、计划与照片仍保留，但所有 membership 作者引用置空，UI 显示「家人」；
- 这些匿名事实此后只有管理员可以管理；
- 其它被删除设备下次连接收到 `membership_deleted` 并清空本地家庭数据。

仅允许为拒绝已失效 token 保留不含身份信息的 token hash/reason，且不超过相关 token 原本的
有效期。不得借此重建成员历史。

### 6.3 删除整个家庭

- 入口只在账户页底部，对管理员显示。
- 用户必须输入家庭名并重新输入根密码。
- 成功后服务端删除整个家庭数据集；当前设备立即清空本地家庭数据。
- 其它设备下次可信连接收到 `family_deleted` 后清空。
- 删除失败或网络中断不能先清本地数据；用户可继续离线使用现有数据并重试。

管理员可以退出一台管理员设备，但没有「退出 Owner membership」；要结束家庭只能删除家庭。

## 7. 前台同步行为

### 7.1 触发

只保留以下触发器，并共用一个 sync mutex 与前台 availability 协调器：

- App 回到前台；
- 本地事实写入成功（只通知“有待对账修改”，不在写入调用栈执行网络）；
- 记录、汇总、成长页进入时的节流刷新；
- 用户在记录、汇总、成长页下拉刷新；
- 账户页失败浅状态点按，以及该说明里的再试 / 检查后重试 / 稍后再试（同一条 `PullToRefresh`）。

健康探测不要求公网 `VALIDATED`，只要求 Android 网络可用与可信 TLS。`/health`、`/ready`
和 setup capability 匿名并行验证，总等待不超过 8 秒且不得携带 access/refresh token 或家庭
数据。健康成功产生 30 秒租约；失败使用 30 秒/2 分钟/10 分钟退避。本地写事件只合并待对账
信号，不打破失败退避；回前台、网络恢复和下拉刷新可以立即探测。进入后台停止探测和同步。

匿名探测的「无凭证、无家庭数据」禁令不变；0.4.8 起另有一条**认证**探针
`GET /v1/sync/heartbeat`（wire 见 [`causal-sync-wire.md`](./causal-sync-wire.md) §1.5）：
它本来就要携带已加入设备的 access token，并按设计返回家庭级元数据
（generation/`head_rev`/`directory_generation`）与活性——这是已授权会话内的只读探针，
属于上述禁令的显式许可例外。网络设置页在已加入态的手动刷新改发单次认证心跳；匿名三连
保留给未加入/无 token 场景。认证心跳失败按既有传输失败分类降级，不进同步失败 UI。

前三项是静默自动同步，不是用户主动发起的「同步动作」，也不增加任何 UI 入口。前台完整周期
若未收敛（`has_more` 还在、待发表非终态项还在、或单轮 120 秒封顶但 cursor/待发表已前进），
在应用仍在前台时立刻再入队同一条 `Foreground` 周期；**零进度续跑有熔断上限**——同一
会话/端点身份 × 同一观测信号在连续 ≤3 轮静默零进度后续跑熔断（`ForegroundRoundFuse`），
不再为同信号开新数据轮；三键信号任一变化（含水位回退）、真实用户触发（回前台/本地写/
下拉/网络恢复）或耐久进度出现即解除并重置计数，会话/端点/成员身份切换整体作废旧熔断；
心跳探到的变化与内部续跑不经真实用户触发缝，不得当作熔断解除条件。续跑与熔断是
「回前台 / 网络恢复」静默同步的
延续，不是新入口，也**不是**锁屏或切走之后的后台轮询。切到后台仍停止探测和同步。用户可主动
触发的「立即同步」是记录、汇总、成长三页的下拉刷新，以及账户失败浅状态的点按与说明里的
再试按钮；二者走同一条 `PullToRefresh`。账户页无下拉手势、无独立同步按钮或同步页，也不提供
「仅 Wi-Fi」偏好。改名 / 邀请 / 成员名单等带 resume 的说明不冒充整轮对账。登录已失效只重新
登录，不发对账。网络失败后使用有界退避；本地写入永不等待同步成功。未完成的副本周期不得显示
成「填写的内容不对 / 称呼空了」。

### 7.2 权威裁决与收敛

健康租约只允许发起同步，不证明家庭事实已结算。完整周期先 pull，再冻结当前待对账 atomic units。LocalWrite 走 commit-first：不 pull、不推进 cursor。commit 返回 `accepted|merged|branched`；`branched` 是耐久冲突，不是传输重试。字段、枚举与 closed key 以 [`causal-sync-wire.md`](./causal-sync-wire.md) 为权威。暂时网络/5xx/authority 未知保留待对账，但该周期不得宣称已收敛。静止周期完成后冻结集没有未终态单元，浅层 pending 为零。

### 7.3 浅状态

账户、记录、汇总、成长页可以显示同一结果向的一行状态：

- `已同步 · 2 分钟前`；
- `已保存在本机 · 待同步 3 项`；
- 记录 / 汇总 / 成长：`暂时无法同步 · 下拉重试`；账户同一 Error 事实写 `点此重试`（这页没有下拉）；
- `等待管理员确认`。

不展示 IP、端口、token、server ID、trust mode、内部枚举或登录历史。详细错误应转译为用户能
处理的动作；信任不一致使用独立阻断页，而不是普通网络错误。

待同步数量按未终态 atomic units 计算，不直接求和 Room dirty 行；本机保留内容、系统日历、
cleanup marker、已裁决技术残留不进入该数字。

### 7.4 失败策略

| 失败 | 本地与会话处理 |
|------|----------------|
| 超时、断网、5xx | 保留全部数据与会话；稍后前台重试 |
| access 过期 | refresh 一次、轮换并重试一次 |
| refresh 丢失/无效/replay | 清该设备凭证；保留本地数据和 endpoint；重新申请 |
| TLS/SPKI 不一致 | 不发送凭证；硬阻断，要求忘记服务器后重连 |
| 申请待审批 | 保持离线可用；前台轮询审批结果 |
| `device_removed` | 清当前设备的全部本地家庭数据 |
| `membership_deleted` | 清当前设备的全部本地家庭数据 |
| `family_deleted` | 清当前设备的全部本地家庭数据 |
| 永久 ACL/非法发布 | 有远端 head 则采用远端；无远端的用户事实转本机保留，纯技术残留清理；不得永久 dirty 重试 |
| `client_update_required` | 本机 versionCode 缺失或低于服务器 `min_supported_version_code`；**映射为强制升级 UI**（有包 → 可安装强制态；元数据暂缺 → `PackageUnknown` 强制壳 + 重试检查），不得呈现为普通网络/NAS 故障或无强制层的「假正常」；本地 Room 与会话保留；更新检查与 APK 下载仍可用 |

## 7.5 客户端版本门槛与自托管更新

家庭服务器可对已部署的 release 客户端抬高最低 versionCode，避免破坏性协议下旧客户端半兼容
脏写。本通道是**自托管应用内更新**（`PackageInstaller` + 鉴权 APK），**不是** Google Play
In-App Updates。完整产品合同见 [platform.md §4.2](../platform.md)。

| 项 | 合同 |
|----|------|
| 请求头 | 权威同步与其它需门槛的受保护请求携带 `X-Lezi-Client-Version-Code`（十进制整数） |
| 门槛判定 | 仅当**已验证**自托管更新通道可用（部署元数据 + 与 `sha256` 一致的 APK）时读取 `min_supported_version_code`；头缺失/非法或 `< min` 时拒绝权威 sync 写/拉 **与灾难恢复写路径**。原子 mid-promote 完整性暂败时保留 last-known-good floor，直至新 pair 验证成功 |
| 诚实客户端闸 | 见 [platform.md §4.2 诚实客户端闸](../platform.md)（本表不重复展开，避免双源漂移） |
| 错误语义 | pull、bundle、media 与灾难恢复写路径的 HTTP 失败 body 含稳定 `code=client_update_required`。`POST /v1/causal/commit` 把同一门槛折叠成 409 终态 `capability_mismatch`。客户端两种都映射强制升级，不重试当普通网络错误 |
| 放行 | 同一有效会话下 `GET /v1/app-update` 与 `GET /v1/app-update/apk` **不**走门槛拒绝，避免升级死锁；两路由均要求已验证 pair（半通道不返回可 dual-tier 的 200 元数据） |
| 缺通道 / 半通道 | 未部署 `app-update.json`、从未验证成功过、或通道文件消失时同步/restore **fail-open**；仅有元数据无 APK 或 APK 完整性失败且无 last-known-good 时同样 fail-open（不砖掉家庭进强制升级却无包可装）；元数据/APK 路由对已鉴权调用诚实 404/500，**不**在半通道下广告 `min_supported` |
| 客户端强制壳 | 权威路径已返回 `client_update_required` 时：拉到元数据且 **versionCode &gt; local** → 可安装强制全屏（即使双档会判 optional）；拉元数据失败或无更高 versionCode → 仍发布 **PackageUnknown** 强制壳（重试检查更新），`SyncStatus` 保持 Idle，文案区分于泛同步故障。强制壳上的「重试检查更新」走同一 `checkAppUpdate`：已有壳时 `versionCode &gt; local` 升为可安装 Forced，否则不得拆壳或展示 optional；Result 通道与 `availableForcedAppUpdate` 同为 force-honest（`ForcedPackageUnknown` / `ForcedUpdate`），不得 bare UpToDate/Optional 文案；失败同步（网络/5xx）不得 piggyback 拆壳；权威同步成功后的 piggyback 才可清过期壳。壳内允许 reauth；APK 下载 access **401** 时 refresh 后重试下载 **一次**；鉴权通道不可用且 origin 已知时给出 LAN **8767** 邀请安装引导 |
| wire-break | 破坏封闭 wire / schema / allowlist 前必须先抬 min + 发布已验证包；**无** dual-read。见 [platform.md §4.2.1](../platform.md) |
| 资格 | 本节应用内更新仅限已加入且会话有效；ADR-0015 的 LAN 邀请首装是隔离的匿名分发面，不得用于同步或既有设备更新 |
| 发现 | 前台握手/同步顺带 best-effort 检查（0.5 起节流：用户真实触发（回前台/下拉）必查；心跳 kick/续跑等非用户轮距上次**成功**检查 &gt;1h 才补查，时刻耐久存储，失败不武装窗口；**不新增任何唤醒源**——发现只搭既有轮）；菜单关于区手动检查；可选更新可横幅提示（会话内稍后抑制） |

比较语义只用整数 versionCode；versionName 仅展示。`versionCode` 与 lezi-sync Cargo 版本号
不要求数值相等。

## 7.6 实现注记（0.4.2 节拍；不改 0.4.0 wire）

空增量税：本机不再为从不应用远端的周期扫完全部媒体与宝宝行；空页不再把历史缺图一次性拖进来；游标没动就不重写本机进度。

历史缺图仍然会补，但只在完整周期里按稳定顺序消耗有界队列（每周期最多三张或一张上传上限那么大）。本地写入路径永不补历史缺图。

LocalWrite 解耦：本地写入撞上世代冲突或权威证明失败时，本机只留下待对账和一条耐久未决，不当次全量恢复。

wire 身份仍是 0.4.0。详见 [`causal-sync-wire.md`](./causal-sync-wire.md) §16。

## 7.7 实现注记（0.4.4 等待 fail-fast；不改 0.4.0 wire / H15）

探测约 8s、会话命令约 12s、整轮对账约 120s、本地数据门闩约 45s。与 H15 前台单请求表分列。
完整超时表见 [`layers/sync.md`](../layers/sync.md)（原 tech.md §3.2）。不改 0.4.0 黄金身份。

## 8. 页面与权限

### 8.1 账户页

小白用户只看到：家庭名、我的称呼、一句同步结果、「家庭成员与设备」和「家庭网络设置」。
管理员同时看到
「设备登录申请」角标。页面底部按角色显示：

- 普通成员：`退出这台设备`、`退出家庭`；
- 管理员：`退出这台设备`、`删除家庭`。

服务器连接尚未完成时，从这里进入「连接家庭服务器」；管理员接管整合在「加入家庭」流程，
已加入设备则从「退出这台设备」上方进入「家庭网络设置」。该子页只展示当前 HTTPS 地址、
结果向连接状态、最近健康时间和必要操作，不展示 token、server ID、登录历史或“立即同步”。

### 8.2 家庭成员与设备

- 按成员称呼分组；所有成员可看到成员称呼。
- 普通成员只展开和管理自己的设备，可提交改名申请。
- 管理员查看全部设备、最近使用状态、开放的设备登录申请与改名申请，可添加/改名/删除成员、
  批准、拒绝或撤销已批准未领取的申请，为成员生成 QR、重命名或撤销设备。
- 普通成员不能看到其他成员的设备详情，也不能看到服务器或凭证技术信息。

完整线框、文案和错误恢复见 UI/UX 设计文档。

## 9. 服务端 API 职责

具体字段由实现票固定，但不得削弱以下职责边界：

- `/health`：无敏感数据的 liveness；
- `/ready`：部署 readiness；
- `/v1/setup-status`：可信 HTTPS 后的协议/capability 与 `empty|configured`；
- owner setup/login/takeover：根密码验证与 Owner 设备 session；
- member request/status/approve/reject：无权限申请与管理员裁决；
- one-time grant：只签发管理员 App 可编码的目标 membership 单次授权；启用 LAN 首装时可附加
  非敏感 `landing_url`，仍由 App 生成 QR；
- access refresh/logout/revoke：轮换、重放检测与独立设备撤销；
- membership/device rename/delete：唯一性、权限和硬删除；
- authenticated session summary：从 credential 返回 canonical family/membership/device/role；
- bundle push/pull/media：沿用原子同步和服务端 ACL；并在请求头 versionCode 低于 minSupported 时
  以 `client_update_required` 拒绝；
- authenticated commit：普通发表只走 commit-first，返回 `accepted|merged|branched`、一个 batch generation、原终态与 replay marker；不调用 reconcile、不推进 cursor。低于 floor 的 commit 是 409 `capability_mismatch`（其它权威写路径才是 `client_update_required`，见 §7.5）。字段权威见 [`causal-sync-wire.md`](./causal-sync-wire.md)；
- conflict detail / resolution CAS：0.4.0 返回持久 receipt 支撑的完整 ConflictSnapshot pages；
  resolution 只提交 token + mutation ID + 每 path 一个 choice ID，服务端独立重建和 full-set CAS；
- authenticated app-update：`GET /v1/app-update` 返回部署元数据 JSON；`GET /v1/app-update/apk`
  在 sha256 与元数据一致时提供 release APK；均需设备会话，不设匿名旁路；
- invite install：独立 LAN HTTP Router 只能提供无家庭信息的 `/join` 与经同一 SHA-256 校验的
  `/download/lezi.apk`，不能暴露任何 `/v1/*`、健康、ready、数据库、媒体或凭证接口。

所有身份判断只从已验证 credential 与服务端状态推导，不能信任请求体自报 family、membership、
device 或 role。服务端不得记录根密码、access/refresh token、grant 或敏感请求体。

## 10. 家庭网络设置、地址改变与灾难恢复

服务器运维者仍可按部署流程完整搬迁同一数据库、媒体和 TLS 身份。已加入设备同时提供安全
重连路径：地址编辑只产生候选配置；候选必须在不发送旧凭证的前提下通过可信 TLS、Lezi
`/health` + `/ready` + setup probe。失败、取消或进程中断不改变旧 endpoint、session、Room、
Room dirty/发布回执或 media。

- 地址或 TOFU 证书变化都视为新信任边界；自签名变化显示旧/新指纹并二次确认，接受后仍须
  重新登录/审批。系统 PKI 地址继续只按平台证书校验。
- configured 候选走普通 Owner 登录或成员申请；新会话 `family_id` 必须与旧会话相同才原子
  切换。不同 configured 家庭直接阻断，不提供合并。
- empty 候选只在本机仍保留旧 Owner 身份时提供「从本机恢复家庭」。用户输入新服务器根密码、
  查看恢复摘要并最终二次确认；普通成员不能恢复。
- 恢复有效护理数据：宝宝、记录、计划、履行关系、自定义项目、照片与尚未发布的本机修改；
  排除旧 membership/device/session/申请/设置/墓碑/游标/凭证，历史作者统一归新 Owner。
- 服务端提供版本化 start/manifest/media/status/commit/cancel API。根密码只在 start/commit 验证
  且不落盘；中段用高熵、限时、可撤销恢复凭证。`/data` staging/journal 校验引用、大小与
  SHA-256 后一次激活，24 小时过期；request ID 幂等，重启与 commit 回包丢失后可查询续传。
- batch UUID 只在请求持有或等待 journal 临界区期间占用恢复专用 keyed lock；最后一个
  holder/waiter 离开即回收，不能由随机 UUID 变成进程级持久状态。lease 与 owned guard 由实际
  blocking journal task 持有到 I/O 完成，请求 future 取消不能提前释放。每个新 batch 另存只含
  恢复 token 哈希的私有 auth envelope，journal existence、凭证校验与
  manifest/media/status/cancel/commit 读写仍在同一 lease 内完成；合法但不存在的 UUID、已存在
  batch 的错误凭证以及未授权探测到的损坏 journal 返回相同未授权响应，不提供 existence oracle。
  正确凭证仍获得真实 protocol/storage 错误。普通 family 同步锁与 provisioning 锁维持原有独立语义。
  运行时过期回收只在锁外枚举 canonical batch ID，随后逐 batch 取得同一 lease、重读 journal 后
  决定删除；缺 journal 的创建中目录留给启动期单线程清理，不能与请求 I/O 并发误删。
- staging 提交前不能加入或普通同步。commit 成功后客户端在 sync mutex 内切换 endpoint/session、
  替换成员目录并按精确 Room 修订退休发布回执。现有 configured 数据根升级 0.3.5 不进入恢复路径。
- 普通 NAS CD、回滚与容器重启必须保留已有 TLS 证书/私钥并在部署前后得到相同 SPKI；证书
  缺失、无效、错配或不可读时失败关闭。只有经确认的全新空数据根可首次生成证书；有意轮换
  属于独立授权维护。证书变化相关测试只允许使用自建隔离服务和临时数据根，真实家庭 NAS
  只做只读证书/SAN/指纹核验。

## 11. 验收标准

1. Release App 不再申请或读取 SSID 所需的位置/Wi-Fi 权限，代码与 UI 无 SSID/BSSID/
   `wifi_only` 门闩。
2. 未信任 TLS 前，测试能证明 App 不发送根密码、token、grant 或家庭数据。
3. 空/已配置服务器分别只能进入建家/加入；探测失败和任意取消均可保持离线且不破坏可信状态。
4. 根密码可添加 Owner 设备；接管会撤销全部旧 Owner 设备；改密只撤销 Owner 会话。
5. 同一 membership 可有多台独立设备，称呼唯一、设备名仅 membership 内唯一，并共享本人权限。
6. 普通成员只有管理员批准或目标 membership 单次 QR grant 后才能获得 session；同名不能自动
   绑定，membership ID 不能换 token。
7. refresh 长期轮换且 replay 只撤销单设备；普通凭证失效保留本地数据。
8. 设备删除、成员硬删除和家庭删除分别返回明确原因并执行不同范围；成员删除会匿名化既有
   作者并立即释放称呼。
9. 账户与成员设备页面符合角色可见性；记录/汇总/成长下拉刷新并只显示浅同步状态。
10. 两台 Android 客户端可在 HTTPS 服务上完成建家、成员申请/批准、多设备绑定、双向原子同步、
    撤销与删除负向路径；最终 Release APK 与最终服务端产物通过各自发布 gate。
11. 自托管更新：无会话不能拉元数据/APK；已加入可检查；低于 minSupported 时权威同步失败且
    UI 进入强制升级，同时仍可下载安装；打包缺 release APK 失败；升级后应用私有目录无 APK 残留
    （系统安装器缓存不在承诺范围）。
12. NAS 断开或 roster 请求永久挂起时，Room 首屏与全部本地护理写入仍完成；恢复后完成经过
    认证的权威裁决，冻结 atomic units 全部终态且静止 pending=0。健康成功但裁决未落库不能
    误报已同步；候选地址失败可证明旧配置未变，不同 family ID 被阻断。
13. 隔离空 0.3.5 服务可完成 Owner 设备灾难恢复、重启续传与幂等 commit；非空服务、普通成员、
    manifest/media 篡改和过期批次都 fail closed，且不在现有家庭 NAS 执行破坏性恢复测试。
14. 普通 NAS CD 前后 TLS SPKI 完全一致；缺失/无效/错配/不可读身份使部署失败。证书生成、
    轮换与 TOFU 变化测试只在自建隔离服务执行，不触碰真实家庭 NAS 或其数据 bind。

## 12. 文档与代码处置

- 本文、ADR-0011 与 ADR-0014 是 **当前已交付** 传输/身份/前台同步合同。
- **0.4.0** wire 以 [`causal-sync-wire.md`](./causal-sync-wire.md) 与 ADR-0022 为权威；ADR-0019/0021 的服务器/事实边界保留，ADR-0020 的 immutable version/branch 保留。不得把 reconcile-first 当 v2 fallback。
- LocalWrite no-pull 快速路径已启用，且不推进 pull cursor。
- 旧 `sync-home-lan` 合同已删除；不得据 git 历史中的旧文恢复旧 wire、配置或界面。
- 生产只保留可信 HTTPS、每设备会话、成员申请/审批与单次成员登录授权；旧网络身份、邀请加入和长期家庭凭证不提供兼容旁路。
