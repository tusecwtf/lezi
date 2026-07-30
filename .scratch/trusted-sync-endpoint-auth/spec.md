# Spec · 可信家庭服务器、管理员根密码与多设备成员

**Status:** ready-for-agent

**Source:** 2026-07-30—2026-07-31 grilling（用户逐项确认）

**Validated product HEAD:** `39426a037986df574d16b0512a3db10f7c225382`

**Product authority:** [`docs/prd/sync-trusted-endpoint.md`](../../docs/prd/sync-trusted-endpoint.md)

**Architecture:** [`ADR-0011`](../../docs/adr/0011-root-admin-and-multi-device-membership.md)

**UI/UX:** [`trusted-sync-onboarding-ui.md`](../../docs/design/2026-07-30-trusted-sync-onboarding-ui.md)

## Problem Statement

当前 0.3 同步把「前台 + Wi-Fi + SSID 精确命中 + HTTP health」串成同步门闩，再使用长期
family Bearer token。SSID 既不能认证家庭服务器，也限制蜂窝、VPN 和 VPS；明文 HTTP 又会
暴露凭证与家庭数据。直接删掉 SSID 后 ping IP/端口或检查 `/health` 仍然只证明某个进程可达，
不能证明它是用户信任的服务器。

旧身份模型还把一台设备当作一个 membership。同一成员换机或多机时会变成多个「人」，仅凭
称呼或 membership ID 自动补发 token 又会让公开标识变成密码。管理员恢复、普通成员换机、
设备独立撤销、彻底删除身份和小白可理解的账户 UI 因而没有一致合同。

目标不是增加一个复杂账号中心，而是给单家庭自托管产品建立最小而完整的边界：可信 HTTPS
服务器、唯一管理员根密码、一个成员多台独立设备、管理员批准普通成员、长期可轮换设备会话、
显式删除语义，以及不暴露技术细节的前台同步体验。

## Solution

建立 single-family / single-authority 的 fresh-current 同步合同：

1. 生产只连接 HTTPS endpoint。VPS 使用系统 PKI；NAS 自签名服务在角色选择和 setup probe
   之前展示指纹，由当前连接者明确点击「信任此证书」并固定 SPKI，不需要额外校验码。任何
   秘密都必须在 transport trust 成立后才能发送。
2. `GET /v1/setup-status` 只返回协议/capability 和 `empty|configured`，让 UI 在确认地址后
   决定显示「新建家庭」还是「加入家庭」。任何阶段可保持离线。
3. 每家庭只有一个 Owner membership。`LEZI_BOOTSTRAP_SECRET` 是 Owner 登录/恢复与删除家庭
   的根密码，但只用于换取每设备 opaque session，不作为日常 Bearer token。
4. membership 表示一个人并可绑定多台 Device；每台 Device 有独立 access/rotating refresh。
   membership ID、称呼和设备名称都不是凭据。
5. 普通成员通过手动无权限申请让管理员批准，或扫描管理员 App 为目标 membership 生成的十
   分钟单次 QR。QR 同时携带 endpoint trust 配置与授权，服务器不显示 QR，管理员不用 QR 登录。
6. 家庭称呼在当前家庭内唯一；加入、绑定与改名均由管理员裁决。同名绝不自动登录。
7. 明确区分凭证失效、设备删除、成员硬删除和家庭删除，分别执行保留或清除本地数据合同。
8. 移除全部 SSID/BSSID/仅 Wi-Fi 代码与 UI。同步保持前台、本地优先；记录/汇总/成长下拉刷新
   是唯一显式立即同步动作，账户只显示一句结果。
9. App 不实现 NAS→VPS 迁移/恢复协议或 UI。服务器部署搬迁数据；地址变化后设备普通重登。

## User Stories

### US-01 · 连接前不猜建家或加入

作为未连接家庭的用户，我先输入家庭服务器 HTTPS 地址，由 App 验证后告诉我这里是空实例还是
已有家庭，而不是要求我预先选择「新建」或「加入」。

**Must:**

- 初始 action 只有「连接家庭服务器」和「暂不连接，保持离线」。
- probe 为 `empty` 时只出现「新建家庭」；为 `configured` 时只出现「加入家庭」。
- 不出现 NAS/VPS 选择、Wi-Fi/SSID 或迁移恢复入口。

### US-02 · 连接失败仍可离线

作为小白用户，地址错误、服务器未启动、版本不兼容或维护时，我能理解下一步并继续离线记录。

**Must:**

- 非 Lezi/不可达、需要更新、维护中分别提供可操作文案。
- 每个失败页均可修改地址、重试或保持离线（按状态适用）。
- 失败和取消不清 Room、Outbox、media、可信 endpoint 或有效 session。

### US-03 · 地址草稿不污染可信状态

作为已有或曾验证服务器的用户，我尝试一个错误地址时，不希望它覆盖原本可信配置。

**Must:**

- 地址仅在 trust + setup probe 成功后成为可记住 endpoint。
- 未验证草稿离开即丢弃。
- 已通过 probe 但未登录的 endpoint 可以记住、继续或明确忘记，但不得同步数据。

### US-04 · 系统 PKI 服务器自动验证

作为 VPS 用户，我用正常 HTTPS 域名连接时，由 Android 系统证书链验证服务器。

**Must:**

- 主机名、有效期和证书链失败均阻断。
- release 不回退 cleartext HTTP 或忽略 TLS 错误。
- 信任成立前不发送根密码、grant、refresh token 或家庭数据。

### US-05 · 自签名服务器由管理员首次确认

作为当前连接者，我能查看证书指纹并决定是否信任，不需要额外校验码。

**Must:**

- 首次自签名连接展示完整可复制指纹、风险说明与「信任此证书」。
- 当前连接者点击信任后立即固定 SPKI；确认发生在角色选择、setup probe 和发送根密码之前。
- 不要求额外校验码或配对码；管理员登录本身仍不使用 QR。
- 文档承认 TOFU 首连 MITM 风险，不伪称自动证明了服务器身份。

### US-06 · 证书变化硬阻断

作为家庭用户，当已信任服务器的证书公钥变化时，我不会把凭证发给未知对端。

**Must:**

- SPKI mismatch 时发送秘密和同步请求的次数为零。
- 没有「忽略并继续」。
- 唯一恢复路径是忘记服务器后重新连接、确认并登录/申请。

### US-07 · 最小 setup-status 不泄露家庭

作为服务器拥有者，我希望陌生人即使建立 TLS 连接也只能知道能否建家，而看不到家庭成员。

**Must:**

- unauthenticated 响应只含协议、capabilities 与 `empty|configured`。
- 不含 family/server/membership/device ID、家庭名、成员数或称呼。
- `/health`、`/ready` 与 setup status 职责分离。

### US-08 · 根密码创建唯一家庭

作为服务器部署者，我用根密码在空实例创建家庭并成为唯一管理员。

**Must:**

- 仅 `empty` 可创建；并发第二次创建 fail closed 并转向加入。
- 输入家庭名、管理员称呼、设备称呼与根密码。
- 成功后得到 Owner membership 下当前设备的独立 session，再首次同步。

### US-09 · 根密码登录管理员新设备

作为管理员，我在新设备上输入同一个根密码即可登录同一个 Owner membership。

**Must:**

- 默认行为是新增 Owner device，不创建第二 Owner membership。
- 根密码验证后只返回该设备 session；日常 API 不接受根密码 Bearer。
- 多台 Owner 设备可同时有效并独立撤销。

### US-10 · 丢失设备后接管管理员

作为丢失全部管理员设备的用户，我能在登录时选择接管，撤销所有旧 Owner 设备。

**Must:**

- 接管是与普通登录分离的危险 action，有后果说明和二次确认。
- 成功原子撤销旧 Owner device/session，再签发当前设备 session。
- 普通成员的 membership 和 session 不受影响。

### US-11 · 更换根密码恢复管理员控制

作为服务器运维者，我在所有管理员设备和旧根密码均丢失时可通过部署改密恢复。

**Must:**

- 根密码只能在服务器部署环境改变，App 不提供查看或改密。
- 改密并重启后全部 Owner session 失效；普通成员 session 保持有效。
- 普通成员不能投票继承或被提升为 Owner。

### US-12 · 根密码不落盘不泄露

作为管理员，我的唯一根密码不会因为 App 日常使用或诊断被暴露。

**Must:**

- 不写 QR、DB session、日志、analytics、SavedState、系统备份或剪贴板默认值。
- 输入默认遮蔽，页面离开和进程重建后清空。
- 服务端日志不输出 Authorization、token、grant 或敏感请求体。

### US-13 · 成员手动申请登录

作为普通成员，我输入家庭称呼与设备称呼，向管理员请求在这台设备登录。

**Must:**

- 请求没有读取家庭数据的权限，24 小时过期。
- 服务端对来源和家庭待处理数做限速/上限。
- 客户端显示等待确认，可取消并保持离线；无 FCM 或后台轮询。

### US-14 · 管理员看见待确认角标

作为管理员，我下次前台打开 App 时能在账户页看到待确认设备角标。

**Must:**

- 角标有数量和 TalkBack 语义。
- 进入「家庭成员与设备」后列出声明称呼、设备称呼和申请时间。
- 普通成员看不到全家庭待确认列表。

### US-15 · 管理员裁决新成员或旧成员

作为管理员，我能明确决定申请设备属于新成员、既有成员，或应被拒绝。

**Must:**

- action 为「绑定到现有 membership」「以唯一称呼新增 membership」「拒绝」。
- 系统不能只凭同名、membership ID 或设备名自动绑定。
- 通过后请求设备下次前台检查领取自己的 session。

### US-16 · 管理员为目标成员生成单次 QR

作为管理员，我能给某个现有成员生成一张同时包含配置和登录授权的二维码。

**Must:**

- 管理员 App 请求目标 membership 的十分钟单次 grant，并在本机编码 QR。
- QR 同时包含 endpoint、trust material 与 grant；不含根密码。
- 服务器不生成/展示 QR；不创建第二张网络配置 QR。

### US-17 · 普通成员扫码一次登录

作为普通成员，我扫描管理员给的 QR 后，只确认设备称呼即可完成配置与登录。

**Must:**

- grant 只可绑定指定 membership，单次使用，十分钟过期。
- 已用、过期或目标已删除时不签发 session，并可转为手动申请。
- 管理员没有 QR 登录入口。

### US-18 · 一个成员拥有多台设备

作为家庭成员，我的手机和平板可以绑定同一 membership，而不在成员列表变成两个人。

**Must:**

- membership 1:N devices，device 1:N session lineage。
- 同 membership 多设备共享「本人」记录的编辑/删除权限。
- 新设备同步完整家庭历史和照片。

### US-19 · membership ID 不能换 token

作为家庭拥有者，我希望知道数据库 ID 的人不能冒充成员。

**Must:**

- 所有受保护 API 从已验证 credential 解析 family/membership/device/role。
- 请求体自报的 ID 或 role 不参与授权。
- token 丢失时即使 membership ID 匹配也必须重新获管理员批准。

### US-20 · 家庭称呼唯一且规范化

作为家庭成员，我希望成员列表中的称呼不会因为空格或 Unicode 变体看起来重复。

**Must:**

- trim、折叠连续空白、Unicode normalize 后执行当前家庭唯一约束。
- 称呼为空或冲突时不能创建新 membership 或批准改名。
- 同名申请可以被管理员明确绑定既有 membership，但不能自动处理。

### US-21 · 普通成员申请改名

作为普通成员，我能申请修改家庭称呼，但不能绕过管理员直接改。

**Must:**

- 提交后旧称呼继续生效，管理员可批准或拒绝。
- 申请过期/撤销不改变旧称呼。
- 管理员可主动改名；新名称仍满足唯一性。

### US-22 · 管理员主动添加成员称呼

作为管理员，我能先添加一个无设备成员，再为其生成 QR。

**Must:**

- 新称呼符合唯一性。
- 新 membership 在设备登录前没有 credential 或数据调用权限。
- 管理员以后可以改名或删除它。

### US-23 · 设备称呼默认取 Android 名称

作为用户，我不想每次手输设备名称，但可以辨认并修改它。

**Must:**

- API 25+ 优先 `Settings.Global.DEVICE_NAME`，空/不可用时回退 `Build.MODEL`。
- 登录或申请前可编辑，登录后可改名。
- 名称仅在同一 membership 内唯一，不要求全家庭唯一。

### US-24 · 角色控制设备可见性

作为普通成员，我能看见所有家人的称呼，但只看见并展开自己的设备。

**Must:**

- 普通成员不能读取其它成员的设备列表、last-used 或凭证状态。
- 管理员可查看全部设备、最近使用、重命名和撤销。
- 日常页面不展示 token、membership ID、server ID 或 endpoint。

### US-25 · access 短期且 refresh 长期轮换

作为有效设备用户，我可以长期静默登录，又不需要持有永久 access token。

**Must:**

- access 目标 TTL 15 分钟且只在内存持有。
- refresh 每次成功使用都轮换，原 token 不再可正常复用。
- refresh 没有时间或 inactivity 自动过期，直到显式撤销、退出、丢失或 replay。

### US-26 · refresh replay 只隔离一台设备

作为家庭成员，一台设备 token 被复制不应让整个成员或家庭退出。

**Must:**

- replay 撤销该 device/session lineage，不撤销同 membership 其它设备。
- 本机 Room、Outbox、media 与 endpoint 保留。
- 设备回到等待管理员批准流程，不能凭 membership ID 自动恢复。

### US-27 · 普通鉴权丢失保留本地数据

作为凭证损坏或 App 恢复异常的用户，我不希望 401 把尚未同步的记录清掉。

**Must:**

- 普通 invalid/expired/missing refresh 只清 credential。
- 保留本地家庭数据、Outbox、media 和可信 endpoint。
- UI 显示重新申请，而不是「设备已删除」。

### US-28 · 管理员撤销单台设备

作为管理员，我能只撤销一台丢失设备而不删除成员。

**Must:**

- 其它 devices/session 和 membership 不受影响。
- 被撤销设备下次可信连接收到明确 `device_removed` 并清空本地家庭数据。
- 产品说明不承诺离线设备即时远程擦除；设备以后可重新申请。

### US-29 · 用户退出这台设备

作为普通成员或管理员，我能主动让当前设备退出，同时保留 membership 和其它设备。

**Must:**

- 服务端确认 logout/revoke 后，本机立即清 Room、Outbox、media、endpoint、session。
- 离线或服务端失败时不先清本机，允许稍后重试。
- 管理员退出一台设备不会删除 Owner membership。

### US-30 · 管理员彻底删除成员

作为管理员，我删除成员时希望身份彻底消失，而不是留下「旧成员」记录。

**Must:**

- 原子删除 membership、全部 devices/sessions、pending/rename requests。
- 称呼立即可复用，不保留身份墓碑或恢复入口。
- 其它设备下次可信连接收到 `membership_deleted` 并清本地家庭数据。

### US-31 · 成员自行退出家庭

作为普通成员，我能彻底删除自己的 membership，而不只是退出当前设备。

**Must:**

- 「退出家庭」与「退出这台设备」是两个清楚分开的 action。
- 退出家庭执行 US-30 同一硬删除合同并清当前设备。
- 管理员没有退出 Owner membership 的 action，只能退出设备或删除家庭。

### US-32 · 删除成员后匿名化历史作者

作为家庭成员，我希望护理事实不因成员删除消失，同时也不保留被删除身份。

**Must:**

- 记录/计划/照片等事实保留，所有对该 membership 的作者引用置 null/等价匿名值。
- UI 统一显示「家人」，不能从客户端恢复旧称呼或旧 membership。
- 匿名事实此后只有 Owner 可管理。

### US-33 · 管理员删除整个家庭

作为管理员，我能在明确确认后删除服务器上的整个家庭数据集。

**Must:**

- 入口位于账户页底部，要求输入规范化后相同的家庭名并重输根密码。
- 服务端成功后当前设备清空；其它设备下次收到 `family_deleted` 后清空。
- 失败或超时不先清本机；不存在普通成员删除整个家庭的入口。

### US-34 · 账户首页保持简单

作为小白用户，我在账户页只看见身份和同步结果，而不是服务器运维术语。

**Must:**

- 只展示家庭名、我的称呼、一句同步结果、「家庭成员与设备」。
- Owner 额外看待确认设备角标。
- 不展示 IP、token、server ID、trust mode、登录历史或独立同步卡片。

### US-35 · 家庭成员与设备集中管理

作为管理员，我在一个二级页面完成成员与设备管理。

**Must:**

- 按 membership/称呼分组。
- 管理员处理 pending、rename、添加/改名/删除成员、QR、设备改名/撤销。
- 普通成员在同页只管理自己的设备与改名申请，不另建技术设置页。

### US-36 · 任意网络均可前台同步

作为家庭用户，我在 Wi-Fi、蜂窝或 VPN 上只要能到达可信 endpoint 就能同步。

**Must:**

- 删除 SSID/BSSID 读取、存储、权限、白名单和 `wifi_only` 代码/UI。
- 不增加 ping；直接执行可信 HTTPS/session/实际同步请求。
- 仍然只在前台同步，不增加后台轮询、FCM、常驻 FGS 或 P2P。

### US-37 · 记录/汇总/成长下拉立即同步

作为日常用户，我在数据页面下拉即可刷新本地视图并尝试同步。

**Must:**

- 三个页面共用现有 sync mutex；重复触发不并发跑第二轮。
- 显示最后成功、待同步数、暂时失败或等待确认的一句浅状态。
- 账户页没有「立即同步」按钮或独立 Family Sync 页面。

### US-38 · 网络错误不阻断本地记录

作为离线用户，我仍可创建和查看记录，稍后再同步。

**Must:**

- 本地写入不等待网络。
- timeout/offline/5xx 保留 endpoint、session、Room、Outbox 和 media，并有界退避。
- 页面错误文案不盖住记录主 action，不把普通离线渲染成安全警报。

### US-39 · 明确删除原因与普通 401 分流

作为客户端，我能确定什么时候必须清本地，什么时候必须保留。

**Must:**

- 协议提供 `device_removed`、`membership_deleted`、`family_deleted` 明确结果。
- 只有这些显式结果或本机已确认的主动删除执行清除；generic 401 不清数据。
- 每种清除结果可幂等重放，崩溃恢复后不会留下半清状态。

### US-40 · 地址变化走普通重新登录

作为从 NAS 搬到 VPS 的家庭，我接受服务器部署完成数据搬迁后每台设备重新登录。

**Must:**

- App 没有迁移/恢复/handoff UI 或协议。
- 新 endpoint 必须先独立建立 trust；绝不把旧 token 先发过去验证。
- Owner 用根密码登录；普通成员重新申请获批或使用成员 QR。

### US-41 · 不新增产品审计系统

作为产品维护者，我不为本次身份设计引入新的审计事件或用户可见登录记录。

**Must:**

- 不增加 audit ledger、登录历史页或对应票据。
- 现有运维日志保持必要可诊断性，但不得记录秘密、token、grant 或敏感 body。
- 短期 token-hash revocation marker 不携带成员身份，且不超过 token 原有效期。

### US-42 · 首次同步失败不重复身份操作

作为刚建家、登录或获批的用户，初次拉取失败时我只重试同步，不重复创建 membership 或 session。

**Must:**

- session 在身份操作成功后先耐久保存。
- full pull 可独立幂等重试。
- UI 说明「已登录，记录尚未同步完成」，并允许稍后离线继续。

## Implementation Decisions

### Architecture boundaries

- 保持单家庭、单 authority、SQLite、Room + Outbox、本地优先与 atomic bundle。
- fresh-current 切换遵循 ADR-0008；不保留旧 SSID/HTTP/invite/family-token wire 双栈。
- ADR-0011 取代 ADR-0009 的一设备一 membership 与 ADR-0010 的 optional Wi-Fi/client migration。
- 服务端授权只从 credential lookup 得到 canonical family/membership/device/role。

### Server model and wire

- 新增/重构 `memberships`、`devices`、`device_sessions`、pending login、rename request 与 one-time
  grant 的显式关系；唯一约束与硬删除在 store 层事务化。
- access 15m；refresh 轮换且无时间/inactivity expiry；rotation lineage 支持 replay detection。
- 手动申请返回高熵、仅限 status/cancel/一次领取 session 的 pending secret；它不授予家庭数据
  权限，不能只用可猜 request ID 查询或领取。批准后首次成功领取即失效。
- 根密码验证与 ordinary session middleware 分离；root change generation/等价机制只使 Owner
  session 失效。
- `setup-status` 最小响应；`health` liveness，`ready` ops readiness，authenticated session
  summary 与 sync API 分层。
- 删除成员时在同一事务匿名化共享事实作者并删除身份；不保留 identity tombstone。
- 明确的 removal reason 必须能在没有有效普通 session 的情况下安全返回给已被撤销 token，
  但响应不得泄露其它家庭信息。

### Android model

- 只有一个 family auth state machine/reducer，供 Onboarding 和 Account caller 复用。
- Endpoint draft、trusted endpoint、pending request、device session 与本地 family data 分别持久化；
  清理范围由显式状态决定。
- access 只在内存，refresh 使用 Android 安全存储；根密码与 QR grant 不跨进程持久化。
- 自签名 pin 采用 SPKI；system PKI 使用平台 hostname verifier，不自建宽松 TrustManager。
- 自签名证书确认发生在角色选择前；当前连接者明确接受后持久化 pin，不依赖后续 Owner 根登录
  才生效。
- 设备名默认 `Settings.Global.DEVICE_NAME`，fallback `Build.MODEL`，两者均经过显示名规范化。
- 删除旧 HomeNetworkPolicy/SSID/BSSID/wifi-only state、权限、文案和测试，不留下永远 false 的兼容层。

### UI composition

- 账户页只做概览；「家庭成员与设备」是唯一成员/设备管理二级页。
- 「连接家庭服务器」由 probe state 决定 create/join；管理员接管并入管理员加入 flow。
- 日常 sync action 只在记录/汇总/成长 pull-to-refresh；所有页面消费同一浅状态 formatter。
- 危险动作分别确认其真实范围，不使用模糊「退出登录」。

### Operational boundaries

- App/协议不做 NAS→VPS migration ticket。服务器数据搬迁由既有部署/runbook 范围另行处理。
- release 生产 cleartext 关闭；NAS 自签证书的生成/部署属于服务器 TLS 交付，但服务器不负责
  生成二维码图像。
- 不增加产品审计表/event；只对现有日志做 secret redaction 负向验证。

## Testing Decisions

### Highest public seams

1. **Rust Router black-box:** 扩展 `tools/lezi-sync/tests/api.rs`，经真实 Axum Router、SQLite 与
   HTTP request/response 测全部身份、授权、删除和同步合同；不以私有 helper 单测代替 API 证据。
2. **Android shared state machine:** 经 family login reducer/coordinator、`SyncPort` 与 repository
   公共 seam 测 trust/probe/login/pending/session/clear-preserve 行为；Onboarding 与 Account 不复制测试矩阵。
3. **Compose/device semantics:** 经账户、成员设备、连接/登录 flow 的公开 semantics 测角色可见性、
   角标、危险确认、TalkBack 和大字体。
4. **Cross-device black-box:** 两个 Android client + 最终 TLS server 完成完整 happy/negative path。

### Server contract matrix

- empty/configured setup-status、无元数据泄漏、协议不兼容与 readiness 分流。
- root create/login/add/takeover/wrong-secret/change-secret；证明 ordinary session 不受改密影响。
- member pending expiry/rate/cap、approve-new、bind-existing、reject；同名/ID 自动绑定负向。
- QR grant target、TTL、single-use、server-no-QR-output 与 root-secret absence。
- display/device name normalization and uniqueness scopes。
- access expiry、refresh rotation、old-token replay、single-device isolation、revoke/logout。
- multi-device self ACL、完整历史与原子照片包可见性。
- device remove、membership hard-delete + author anonymization、family delete 的事务与 reason。
- 日志捕获负向：不得出现 root secret、access/refresh、grant、Authorization 或敏感 body。

### Android contract matrix

- System PKI、TOFU accept、SPKI mismatch hard block、no-secret-before-trust。
- 地址草稿取消/失败/成功持久化；empty/configured state-driven routing；任意阶段离线。
- root/admin flow、member manual/QR flow、pending foreground status、first-pull retry 幂等。
- credential loss/replay 保留本地；explicit removal/delete 清本地；崩溃恢复幂等。
- Account shallow state、Owner badge、member/device visibility、rename/delete actions。
- Record/Summary/Growth pull-to-refresh 共用 mutex；无独立 sync UI。
- release manifest/network config 无 SSID/location dependency 和生产 cleartext。

### End-to-end acceptance

- 最终 release APK + 最终 server image/package，不使用 debug-only 旁路。
- 两设备完成：TLS trust → create → member request/approval → same-membership second device → 双向记录与
  0–3 照片 atomic bundle → refresh rotation → single-device revoke → member hard delete。
- 额外验证：Owner add/takeover、root rotation、QR single-use、SPKI mismatch、family delete。
- Android gates：受影响 JVM/Compose tests、`./gradlew test`、`lintDebug`、Release build、安装/启动和页面
  smoke；服务端 gates：fmt、locked tests、Clippy、image/package、health/ready。
- 本 Spec 不要求客户端迁移演练；服务器部署搬迁不是该 tracker 的完成条件。

## Out of Scope

- 全局 User、多家庭账号中心、邮箱/手机密码、OAuth/OIDC、第三方身份提供方。
- 多 Owner membership、普通成员继承/投票恢复、管理员权限委派。
- Android 设备私钥、DPoP、请求签名、hardware attestation 或生物识别作为服务器身份凭据。
- 后台 sync、WorkManager polling、FCM、常驻 FGS、P2P、伴侣逐条通知。
- SSID/BSSID、家庭 Wi-Fi、仅 Wi-Fi 偏好、ICMP ping 或额外 TCP 探测。
- mDNS/NSD 自动发现、双主/双写/federation、跨服务器自动合并。
- App 端 NAS→VPS migration/handoff/restore、服务器端展示 QR。
- 产品级 audit event、登录历史 UI、被删除成员 tombstone 或身份恢复。
- 部分历史、按类型可见性、访客/只读角色，以及改变现有同步实体/冲突/原子包语义。
- 旧数据库、旧 wire 与旧 QR 兼容迁移；遵循 fresh-current。

## Further Notes

### Documentation disposition

- 本 Spec、目标 PRD 与 ADR-0011 已同步；`CONTEXT.md` 已改为一成员多设备术语。
- ADR-0009、ADR-0010 保留历史正文但标记 superseded；研究稿仅保留来源和被推翻方案。
- `sync-home-lan.md` 继续描述 0.3 已实现事实，不是下一版实现依据。

### Code disposition

- 发布本 Spec 不代表实现完成。当前代码仍包含 SSID、HTTP、邀请码、长期 credential 与一设备
  membership，必须由后续票按 fresh-current fixed point 切换。
- 迁移 ticket 不得重新出现；部署文档变化如另有需求，应使用独立 server deployment scope。

### Tickets disposition

- 本 Spec 已由 `$to-tickets` 拆为 17 个本地 ticket，索引见
  [`.scratch/trusted-sync-endpoint-auth/ISSUES.md`](./ISSUES.md)；单票文件在 `issues/`。
- 全部 ticket 当前为 `ready-for-agent`；状态与验收证据以各票文件为准，实现完成前不得把后继票视为完成。
- 初始 frontier 只有 01；blocking graph、执行顺序与明确不拆票范围以 `ISSUES.md` 为准。
- 标题以各票文件 `# NN — …` 为准；`ISSUES.md` 索引与票内 **Blocked by** 引用必须与之保持一致。
