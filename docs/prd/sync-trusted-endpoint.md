# 乐记 — 可信家庭服务器、身份与前台同步规格

> 决策锁定：2026-07-31（连续 grilling，用户逐项确认）
> 实现基线：`39426a037986df574d16b0512a3db10f7c225382`
> 架构决策：[ADR-0011](../adr/0011-root-admin-and-multi-device-membership.md)
> UI/UX：[家庭服务器与身份 UI](../design/2026-07-30-trusted-sync-onboarding-ui.md)
> 研究依据：[同步网络、鉴权与身份研究](../design/2026-07-30-sync-network-auth-architecture-research.md)

本文定义家庭同步下一条 fresh-current 产品合同。它取代
[`sync-home-lan.md`](./sync-home-lan.md) 中的 SSID 门闩、cleartext HTTP、长期 family token、
邀请码、一设备一 membership 与旧成员软删除行为；未明确改变的 Room/Outbox、本地优先、
同步实体、原子照片包、冲突裁决和 ACL 仍沿用该基线。

## 1. 一句话合同

乐记只在前台通过用户信任的 HTTPS endpoint 连接一个家庭服务器。根密码声索唯一管理员，
管理员批准普通成员和设备；每个成员可有多台独立凭证设备。连接失败不影响本地记录，SSID、
ping 和 health 都不再充当身份或同步门闩。

## 2. 范围与不变量

| 主题 | 目标合同 |
|------|----------|
| 产品拓扑 | 单家庭、单服务实例、同一时刻一个写入 authority |
| 客户端真相源 | Room；所有写入先落本地 Outbox，再在前台同步 |
| 服务器位置 | 家庭 NAS 或单实例 VPS；App 不感知部署类型 |
| 网络 | 任意可用网络；不读取、存储、展示或匹配 SSID/BSSID |
| 传输 | 生产 HTTPS only；VPS 用系统 PKI，家庭自签名服务用用户确认的 TOFU/SPKI |
| 管理员 | 每家庭唯一 Owner membership；根密码登录，可绑定多台 Owner 设备 |
| 普通成员 | 一个 membership 表示一个人，可绑定多台设备；称呼在当前家庭唯一 |
| 凭证 | 每设备 opaque 短 access + 轮换 refresh；ID、称呼和设备名都不是凭据 |
| 触发 | 仅前台：进入前台、本地写入、页面进入节流、记录/汇总/成长下拉刷新 |
| 兼容 | 一次 fresh-current 切换，不保留旧 HTTP、SSID、邀请码或长期 token 降级旁路 |

继续明确不做：后台轮询、FCM、永久前台服务、P2P、逐条伴侣通知、多家庭 SaaS、全局用户
中心、OAuth/OIDC、多主复制、客户端 NAS→VPS 迁移/恢复协议、产品级审计系统和登录历史页。

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

- 请求本身没有家庭数据权限，24 小时过期；服务端必须限制来源速率和家庭待处理总数。
- 申请设备只保存一个高熵 pending secret，用于查询、取消和批准后单次领取 session；公开或可猜
  request ID 不能领取凭证，也不能读取家庭信息。
- 普通成员看到「等待管理员确认」，可取消并继续离线；没有后台 push。
- 管理员下次前台打开 App 时，账户页显示「待确认设备」角标。
- 管理员可明确：绑定到现有 membership、用该唯一称呼新建 membership，或拒绝。
- 同名只帮助管理员识别；系统不能据此自动绑定。
- 审批通过后，申请设备在下次前台检查时领取自己的 session 并完整同步。

### 5.3 管理员二维码授权

- QR 只由管理员 App 在「家庭成员与设备」为一个目标 membership 生成；服务器只签发单次
  grant，不生成或显示 QR 图像。
- 同一个二维码同时携带 endpoint、固定信任材料和一次性设备登录 grant；没有第二张配置码。
- grant 十分钟过期、单次使用，且只能绑定目标 membership；根密码永不进入 QR。
- 普通成员扫描后仍可确认或编辑设备称呼，然后一次完成信任配置与设备登录。
- 管理员登录不提供 QR；服务端部署页面也不需要显示 QR。

### 5.4 Access 与 refresh

- access token 短时有效，目标 TTL 15 分钟，只放内存并随请求发送。
- refresh token 每次使用都轮换；服务端检测旧 token 重放。
- refresh 没有时间或不活跃自动过期：有效设备可长期静默续期，直到显式撤销、主动退出、token
  丢失或 replay 安全事件。
- replay 只撤销该设备 session，保留本地家庭数据，并要求重新走管理员批准。
- 普通 401、凭证丢失或 token 失效不等于设备/成员被删除；App 保留 Room、Outbox、media 与
  endpoint，显示重新申请入口。

## 6. 删除、退出与本地数据

### 6.1 撤销一台设备

管理员可撤销任意设备，普通成员可从当前设备执行「退出这台设备」。服务端删除/撤销该设备
session，不影响 membership 和其它设备。

- 当前设备主动退出：服务端确认后立即清空本地 Room、Outbox、media、endpoint 与凭证。
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

只保留以下触发器，并共用一个 sync mutex：

- App 回到前台；
- 本地事实写入成功；
- 记录、汇总、成长页进入时的节流刷新；
- 用户在记录、汇总、成长页下拉刷新。

前三项是静默自动同步，不是用户主动发起的「同步动作」，也不增加任何 UI 入口。用户可主动
触发的「立即同步」只有记录、汇总、成长三页的下拉刷新；账户页无同步按钮、独立同步页或
同步状态卡，也不提供「仅 Wi-Fi」偏好。网络失败后使用有界退避；本地写入永不等待同步成功。

### 7.2 浅状态

账户、记录、汇总、成长页可以显示同一结果向的一行状态：

- `已同步 · 2 分钟前`；
- `已保存在本机 · 待同步 3 项`；
- `暂时无法同步 · 下拉重试`；
- `等待管理员确认`。

不展示 IP、端口、token、server ID、trust mode、内部枚举或登录历史。详细错误应转译为用户能
处理的动作；信任不一致使用独立阻断页，而不是普通网络错误。

### 7.3 失败策略

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
| 非法 Outbox 操作 403 | 单项终止并解释；不阻断普通 pull |
| `client_update_required` | 本机 versionCode 缺失或低于服务器 `min_supported_version_code`；**映射为强制升级 UI**，不得呈现为普通网络/NAS 故障；本地 Room 与会话保留；更新检查与 APK 下载仍可用 |

## 7.4 客户端版本门槛与自托管更新

家庭服务器可对已部署的 release 客户端抬高最低 versionCode，避免破坏性协议下旧客户端半兼容
脏写。本通道是**自托管应用内更新**（`PackageInstaller` + 鉴权 APK），**不是** Google Play
In-App Updates。完整产品合同见 [tech.md §4.2](./tech.md)。

| 项 | 合同 |
|----|------|
| 请求头 | 权威同步与其它需门槛的受保护请求携带 `X-Lezi-Client-Version-Code`（十进制整数） |
| 门槛判定 | 服务器读取部署元数据 `min_supported_version_code`；头缺失/非法或 `< min` 时拒绝权威 sync 写/拉 |
| 错误语义 | HTTP 失败 body 含稳定 `code=client_update_required`；客户端映射强制升级，不重试当普通网络错误 |
| 放行 | 同一有效会话下 `GET /v1/app-update` 与 `GET /v1/app-update/apk` **不**走门槛拒绝，避免升级死锁 |
| 缺元数据 | 未部署 `app-update.json` 时同步** fail-open**（不砖掉家庭）；元数据/APK 路由对已鉴权调用诚实 404 |
| 资格 | 仅已加入且会话有效；无匿名/未信任 endpoint 的 APK 通道 |
| 发现 | 前台握手/同步顺带 best-effort 检查；菜单关于区手动检查；可选更新可横幅提示（会话内稍后抑制） |

比较语义只用整数 versionCode；versionName 仅展示。`versionCode` 与 lezi-sync Cargo 版本号
不要求数值相等。

## 8. 页面与权限

### 8.1 账户页

小白用户只看到：家庭名、我的称呼、一句同步结果和「家庭成员与设备」。管理员同时看到
「待确认设备」角标。页面底部按角色显示：

- 普通成员：`退出这台设备`、`退出家庭`；
- 管理员：`退出这台设备`、`删除家庭`。

服务器连接尚未完成时，从这里进入「连接家庭服务器」；管理员接管整合在「加入家庭」流程，
不单独建立恢复页。

### 8.2 家庭成员与设备

- 按成员称呼分组；所有成员可看到成员称呼。
- 普通成员只展开和管理自己的设备，可提交改名申请。
- 管理员查看全部设备、最近使用状态、待确认设备与改名申请，可添加/改名/删除成员、批准或
  拒绝申请、为成员生成 QR、重命名或撤销设备。
- 普通成员不能看到其他成员的设备详情，也不能看到服务器或凭证技术信息。

完整线框、文案和错误恢复见 UI/UX 设计文档。

## 9. 服务端 API 职责

具体字段由实现票固定，但不得削弱以下职责边界：

- `/health`：无敏感数据的 liveness；
- `/ready`：部署 readiness；
- `/v1/setup-status`：可信 HTTPS 后的协议/capability 与 `empty|configured`；
- owner setup/login/takeover：根密码验证与 Owner 设备 session；
- member request/status/approve/reject：无权限申请与管理员裁决；
- one-time grant：只签发管理员 App 可编码的目标 membership 单次授权；
- access refresh/logout/revoke：轮换、重放检测与独立设备撤销；
- membership/device rename/delete：唯一性、权限和硬删除；
- authenticated session summary：从 credential 返回 canonical family/membership/device/role；
- bundle push/pull/media：沿用原子同步和服务端 ACL；并在请求头 versionCode 低于 minSupported 时
  以 `client_update_required` 拒绝；
- authenticated app-update：`GET /v1/app-update` 返回部署元数据 JSON；`GET /v1/app-update/apk`
  在 sha256 与元数据一致时提供 release APK；均需设备会话，不设匿名旁路。

所有身份判断只从已验证 credential 与服务端状态推导，不能信任请求体自报 family、membership、
device 或 role。服务端不得记录根密码、access/refresh token、grant 或敏感请求体。

## 10. 服务端部署与地址改变

App 不提供 NAS→VPS 迁移、handoff、备份恢复、server identity 搬迁或自动 endpoint 替换。
服务器运维者可以按部署流程搬迁同一数据库和媒体；若 endpoint 发生变化：

- 所有 App 将其视为新的连接地址；
- 管理员用根密码登录；普通成员重新申请并获批或扫描管理员 QR；
- 旧 endpoint 凭证绝不能在信任新 endpoint 前发送；
- 同一家庭是否保留历史取决于服务端部署是否完整保留数据库，不由客户端迁移协议保证。

该部署工作不属于本产品 Spec 的 App/同步拆票范围。

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

## 12. 文档与代码处置

- 本文与 ADR-0011 是当前合同；ADR-0009、ADR-0010 已被取代。
- `sync-home-lan.md` 只保留历史处置说明，不得据其恢复旧 wire、配置或界面。
- 0.3.1 已完成 fresh-current 收口：生产只保留可信 HTTPS、每设备会话、成员申请/审批与
  单次成员登录授权；旧网络身份、邀请加入和长期家庭凭证不提供兼容旁路。
- `.scratch/trusted-sync-endpoint-auth/spec.md` 是拆票输入；旧票在重新拆分前不得实施。
