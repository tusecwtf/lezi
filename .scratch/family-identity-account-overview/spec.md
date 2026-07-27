# Spec: 家庭身份与账户概览

Status: partial  
Feature: family-identity-account-overview  
Product: 乐记 (`com.lezi.babylog`)  
Source: grill-with-docs 决策锁定 · `/to-spec` 2026-07-26  
Related:

- `CONTEXT.md`（家庭称呼、家庭名、账户概览、记录上传者、家庭向导、邀请家人、同步状态）
- `docs/adr/0002-family-identity-and-account-overview.md`
- `docs/prd/ui.md` §5.7
- `docs/prd/data-model.md` §3.1–3.3、§6.2
- `docs/prd/sync-home-lan.md` §9.2 / §9.4 / §11

实施状态：

- 票 01 / 03 / 04 / 05 已实现。
- 票 02 为 `partial`：create/join/owner rename 与跨设备 pull 收敛的实现和自动化已完成，等待最终候选双设备 UI/Docker smoke 后关闭。
- 2026-07-26 只读 spec 审计后重开；2026-07-27 完成自动化整改。

Fresh-deployment override（2026-07-27）：Android 与 NAS 只支持 current protocol。记录上传者只由
server-owned `created_by_membership_id` 解析；`createdByUserId` / `created_by_device_id` author
fallback 与旧 NAS `family_name` 字段省略软兼容已 superseded。current `family_name` 必须为 value
或 explicit null；缺字段是协议错误。历史 completed receipt 不替代最终候选的负向 surface 验证。

---

## Problem Statement

多设备家庭一起记时，照护者需要知道：**这是谁家、家里几个人、我是谁、谁记的这条、同步好了没有**。

当前账户页把宝宝档案、设备 ID、SSID/服务器摘要、三步「网络 → 身份 → 同步」引导、成员列表和运维操作堆在同一屏。小白看到的是「未配置服务器 · 未绑定家庭 Wi‑Fi」这类网络运维话术，而不是家庭身份。家庭没有共享名称；自己的称呼可空且易退化成「我（本机）」；时间轴几乎不展示记录上传者。结果是：家人无法相互识别，账户页像路由器设置，而不是「我们家的宝宝本」。

---

## Solution

把 **人/家庭身份** 从 **网络运维** 里拆开（仅改账户页与相关契约；**不改**记录/汇总/成长全局顶栏）。

### 用户可感知结果

1. **账户首屏 = 家庭卡 + 独立宝宝区**
   - **家庭卡**：共享家庭名、成员人数、本人家庭称呼（管理员 ★）、一句结果向同步状态
   - **宝宝区**：沿用当前宝宝卡与档案列表（编辑/添加/切换等），不塞进家庭卡
2. **成员名单**不在首屏堆叠；点「N 位家人」进入二级列表（称呼 + 管理员 ★；本人可改自己的称呼）
3. **网络细节**（服务器、Wi‑Fi、立即同步、上次成功时间、技术原因、离开/删除）在 **网络设置二次界面**
4. **未加入**：首屏主行动「新建家庭 / 加入家庭」→ **家庭向导**（内含必要时配网；称呼硬必填；家庭名可空兜底）
5. **已加入管理员**：主行动「邀请家人」；不把邀请藏进网络设置
6. **时间轴**：仅当上传者不是本机时，次要信息显示对方**当前**家庭称呼；未加入家庭不显示上传者

### 文案原则

- 概览同步状态用结果向人话：还没和家人一起记 / 家人记录已对齐 / 连上家里 Wi‑Fi 后才能同步 / 正在同步… / 同步遇到问题
- 不在账户首屏暴露 SSID、host、Idle、BlockedOfflineHome、设备 ID
- 不得把「我（本机）」上传或展示给其他成员

---

## User Stories

### 账户概览（小白首屏）

1. As a 照护者, I want 打开账户 Tab 先看到家庭与宝宝而不是服务器地址, so that 我感觉这是家庭本而不是网络面板  
2. As a 照护者, I want 首屏家庭卡显示共享家庭名, so that 全家对「我们家叫什么」一致  
3. As a 照护者, I want 首屏看到成员人数（如「3 位家人」）, so that 我知道几个人在一起记  
4. As a 照护者, I want 首屏看到自己的家庭称呼（管理员带 ★）, so that 我知道别人会怎么认出我  
5. As a 照护者, I want 首屏只有一句结果向同步状态, so that 我知道能不能和家人对齐而不被术语吓到  
6. As a 照护者, I want 点同步状态进入网络设置, so that 需要排障时能找到详细项  
7. As a 照护者, I want 宝宝信息仍在独立卡片/列表里编辑, so that 改宝宝不会和家庭网络搅在一起  
8. As a 照护者, I want 首屏不再看到设备 ID / SSID / 服务器摘要 / 三步同步条, so that 页面保持安静  
9. As a 未加入的照护者, I want 首屏主按钮是新建或加入家庭, so that 我知道下一步怎么和家人一起记  
10. As a 已加入的管理员, I want 首屏主按钮是邀请家人, so that 我能马上让另一台手机加入  
11. As a 成员, I want 没有「邀请家人」和「删除家庭数据」主入口, so that 我不会误碰管理员能力  
12. As a 照护者, I want 网络设置作为次要入口仍可打开, so that 换 Wi‑Fi 或改服务器时有地方去  

### 家庭名

13. As a 管理员, I want 建家时可以填写共享家庭名, so that 全家看到同一个名字  
14. As a 管理员, I want 家庭名可留空并用兜底（「我的家庭」或「{宝宝昵称}的家庭」）, so that 起名不卡住建家  
15. As a 管理员, I want 在账户家庭卡上改家庭名, so that 改名不必进网络设置  
16. As a 成员, I want 只能只读看到家庭名, so that 名字不会被各人改乱  
17. As a 照护者, I want 全员设备上家庭名一致, so that 「相互了解」不会各说各话  

### 家庭称呼（自己的名字）

18. As a 照护者, I want 建家或加入时用「我是宝宝的？」引导填写自由称呼, so that 我知道填的是家里人对我的叫法  
19. As a 照护者, I want 不填称呼就无法完成建家/加入, so that 成员列表和上传者不会大面积空白  
20. As a 照护者, I want 称呼是自由文本而不是强制亲属芯片, so that 月嫂、干妈等也能写清楚  
21. As a 照护者, I want 加入后能改自己的称呼, so that 写错或想换称呼时不用重加家庭  
22. As a 照护者, I want 不能改别人的称呼, so that 身份不被他人冒充改写  
23. As a 照护者, I want 系统永不把「我（本机）」当作真实称呼上传, so that 别人手机上看不到本机占位文案  
24. As a 照护者, I want 历史空名在别人眼里用「家庭管理员 / 家庭成员」等角色兜底, so that 列表仍可读  

### 成员列表与管理员标记

25. As a 照护者, I want 点成员人数打开二级名单, so that 首屏保持简洁仍能认人  
26. As a 照护者, I want 名单上看到每人的家庭称呼, so that 我知道谁在家里  
27. As a 照护者, I want 管理员行标 ★, so that 一眼区分管理员与成员  
28. As a 照护者, I want 名单不显示 token、设备 ID、家庭 UUID, so that 安全投影不被破坏  
29. As a 照护者, I want 不在家网时名单有可理解提示而不是崩掉, so that 我知道要回家网再刷新  
30. As a 产品, I want 本版不做踢人, so that 范围不膨胀  

### 家庭向导与网络设置

31. As a 未加入的照护者, I want 新建/加入走分步向导且必要时先配网, so that 我不用在首屏填 host 和 SSID  
32. As a 管理员, I want 向导建家时填写称呼（必填）、家庭名（可空）、初始化口令, so that 一次完成身份与建家  
33. As a 成员, I want 向导加入时填写称呼（必填）并扫码/输码, so that 加入后别人能认出我  
34. As a 照护者, I want 向导可中断且已保存的服务器/SSID 保留, so that 我不必一次做完  
35. As a 照护者, I want 在网络设置里改服务器与最多 2 个家庭 Wi‑Fi 名, so that 家网门闩仍可用  
36. As a 照护者, I want 在网络设置里看到上次成功时间、立即同步与技术原因, so that 排障有细节  
37. As a 成员, I want 在网络设置里离开家庭并确认, so that 停止共享是明确操作  
38. As a 管理员, I want 在网络设置里删除家庭数据并双重确认, so that 高风险操作不在首屏误触  

### 邀请

39. As a 管理员, I want 从账户首屏生成邀请码/二维码, so that 邀请是家庭动作不是网络运维  
40. As a 管理员, I want 二维码仍携带 baseUrl 与 code（及可选 SSID）, so that 对方扫码能预填  
41. As a 管理员, I want 展示邀请时 FLAG_SECURE 等既有保护保留, so that 敏感载荷不进截图库  

### 记录上传者

42. As a 照护者, I want 时间轴上别人记的记录显示其当前家庭称呼, so that 我知道是谁喂的/谁记的  
43. As a 照护者, I want 自己写的记录不标上传者, so that 单机使用时不刷屏  
44. As a 照护者, I want 未加入家庭时时间轴不显示上传者, so that 单机模式保持干净  
45. As a 照护者, I want 对方改称呼后历史展示跟当前称呼, so that 与成员列表一致且实现简单  
46. As a 照护者, I want 无法解析称呼时看到「家人」或角色兜底而不是设备 ID, so that 不泄露技术标识  

### 身份模型边界

47. As a 照护者, I want 每台设备各自有一个 membership 称呼, so that 不需要复杂账号体系  
48. As a 同人双机的照护者, I want 理解两台手机会显示为两个成员, so that 预期与设备绑定模型一致  
49. As a 维护者, I want 不引入跨设备「照护者实体」, so that 首版不重做身份层  

### 无障碍与错误

50. As a 照护者, I want 建家/加入称呼为空时表单就地报错, so that 我知道缺什么  
51. As a 照护者, I want 同步失败时首屏短句可点进网络设置看原因, so that 排障路径短  
52. As a 照护者, I want 产品错误文案继续避免堆栈/路径裸奔, so that 小白不被技术细节淹没  

---

## Implementation Decisions

### 领域与术语

- 采用 ADR-0002：身份 = 设备 membership 上的 **家庭称呼**；家庭 = 另有共享 **家庭名**。
- 不引入照护者一等身份；同人多机 = 多个成员。
- 账户 IA：家庭卡 + 宝宝区分离；网络二次界面；邀请在首屏（管理员）。

### 数据与同步契约

- **Family** 增加共享 `name`（可空）；NAS 持久化并在 create/join/会话摘要中回传。
- **仅 owner** 可改家庭名（`renameFamily` 或等价 API）。
- **create / join** 请求体中 `display_name`（家庭称呼）产品层 **必填**；空白不得成功；拒绝控制字符/双向控制符；最长 128 Unicode；禁止上传「我（本机）」。
- create 增加可选 `family_name`。
- 成员需能 **更新自己的 display_name**（`updateMyDisplayName` 或等价）；不能改他人。
- 成员列表安全投影不变：仅 `display_name`、`role`、`is_self`（及客户端所需的展示派生）；管理员 UI 标 ★。
- 本地 `LocalUser.displayName` 与当前 membership 称呼对齐缓存；未加入时可空。
- 记录只以 server-owned `createdByMembershipId` / wire `created_by_membership_id` 归因；展示时解析为当前 membership 称呼（**无历史快照**），不得回退 device key。

### SyncPort / 客户端模块（概念面，非文件清单）

- `createFamily(displayName, familyName?, bootstrapSecret?)`
- `joinWithCode/Payload(..., displayName)` 必填称呼
- `renameFamily` / `updateMyDisplayName`
- 会话或专用读路径暴露共享家庭名与本人称呼
- 账户 UI 策略层输出概览模型：家庭卡字段、同步短句、主 CTA、是否显示邀请/网络入口
- 家庭向导：未配网 → 网络步 → 建家或加入步；可中断
- 网络设置二次界面承载运维控件（既有能力搬家，不扩踢人）

### UI 交互

| 表面 | 内容 |
|------|------|
| 账户首屏家庭卡 | 家庭名、人数、本人称呼(+★)、同步短句 |
| 账户首屏宝宝区 | 当前宝宝 + 档案列表（现有能力） |
| 成员二级 | 名单、★、改自己称呼 |
| 家庭向导 | 新建/加入；称呼必填；家庭名可空；口令/邀请 |
| 网络设置 | host/port、SSID≤2、立即同步、上次成功、技术原因、离开/删除 |
| 时间轴 cell | 非本人次要行显示当前称呼 |
| 全局顶栏 | **不改** |

### 兜底

- 家庭名空：客户端展示「我的家庭」或「{当前宝宝昵称}的家庭」
- 他人称呼空/不安全：角色兜底（家庭管理员 / 家庭成员）或「家人」
- 未加入：同步短句「还没和家人一起记」；无上传者标注

### 与并行工作的关系

- 可与 `post-0.2.4-followup` 并行，但本 feature 允许 **产品行为变化**（身份与账户 IA），不是结构票的行为冻结。
- 若 Join 表单/ endpoint 真源仍在 followup 票中收敛，本 feature 的向导应坐在同一套网络配置真源上，避免第三套加入实现。

---

## Testing Decisions

### 什么是好测试

- 只断言 **外部行为**（文案策略输出、API 请求/响应、展示解析结果），不绑 Compose 树结构或私有函数名。
- 优先 **纯函数策略** 与 **Port/HTTP 契约**；UI 截图不作为主验收。
- 回归时禁止账户概览策略再输出设备 ID / SSID / host 作为主状态句。

### 接缝（已确认）

| ID | 接缝 | 覆盖 |
|----|------|------|
| **S1** | 家庭 UI 文案/可见性策略 | 概览短句、人数摘要、★、主 CTA、网络细节不出现在概览输出 |
| **S2** | SyncPort / HTTP 契约 | 称呼必填、家庭名可空与回显、改名、members 投影 |
| **S3** | 记录展示解析 | 仅 membership：非本人显示当前称呼；本人不标；未加入不标；无 device author fallback |
| **S4** | lezi-sync API 集成 | `family_name` 持久化；空 `display_name` → 422；members 无 token/device |

### Prior art

- 家庭文案与可见性：既有 Family UI policy / error copy 单测风格
- 同步：Http backend / RealSyncPort 对 display_name 与 members 的测试
- NAS：lezi-sync API 测试中成员名规范化与拒绝不安全名
- 时间轴：Record presentation 单测风格

### 最低验收场景（跨接缝）

1. 未加入 → 概览无技术网络句；主 CTA 为新建/加入  
2. 建家缺称呼 → 失败；有称呼无家庭名 → 成功且兜底展示  
3. 加入后成员列表见称呼；owner ★  
4. 改自己称呼 → 列表与后续上传者展示更新  
5. 非本人记录在时间轴显示对方当前称呼；本人记录不显示  
6. 网络设置仍可保存 SSID/服务器并立即同步（能力不丢）
7. current response 缺 `family_name` 或 Record 缺 canonical membership author 时 fail closed/可重试，不走旧 NAS/device fallback

---

## Out of Scope

- 修改记录/汇总/成长 **全局顶栏**（AppHeader）的日龄或布局  
- 跨设备合并的「照护者」一等身份  
- 管理员踢人 / 转让管理员  
- 上传者写入时名称快照（历史跟当前称呼）  
- 成员关系芯片枚举（爸爸/妈妈必选）  
- 字段级 ACL、保育只读角色、云账号登录  
- 会员/订阅  
- 把宝宝合并/删除流程重做（仅保持在宝宝区可用）  
- 系统日历、护理计划相关改动  
- 纯结构拆文件且零行为变化的 followup 票（除非本 feature 实现时不得不碰）

---

## Further Notes

### 决策摘要（grill）

| 题 | 结论 |
|----|------|
| 身份绑定 | 设备 membership 名（A） |
| 称呼形态 | 自由文本 + 引导「我是宝宝的？」（C） |
| 家庭名 | 共享，仅管理员可改（A）；可空+兜底 |
| 账户首屏 | 纯概览；名单二级；网络二次（A） |
| 时间轴上传者 | 仅非本人（A）；跟当前称呼 |
| 称呼必填 | 建家/加入硬必填（B） |
| 未加入引导 | 首屏 CTA + 家庭向导（A） |
| 邀请位置 | 已加入管理员首屏主行动（A） |
| 同步文案 | 结果导向（A） |
| 账户布局 | 家庭卡 + 独立宝宝区（B） |
| 顶栏 | 不改 |

### 实现票（to-tickets · 多 agent 审查后）

见 [ISSUES.md](./ISSUES.md)：

1. **01** 家庭称呼可认（S2/S4 + 解析源）  
2. **02** 共享家庭名（S2/S4）  
3. **03** 账户首屏骨架（S1；含门禁文案）  
4. **04** 向导 + 邀请主行动（S1；含 Onboarding 真 join）  
5. **05** 时间轴上传者（S3；blocked by 01 only）  

### 风险

- current membership 引用暂时无法解析：使用角色/「家人」展示兜底，但不猜测 device→作者
- create/join 的 `display_name` 必填；current 测试夹具与假后端保持同协议
- current pull 的家庭名允许 explicit null；字段缺失必须作为协议错误处理
- 向导与 onboarding「加入家庭」应共用规则，防止第三套加入表单
