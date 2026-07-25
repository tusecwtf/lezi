# Spec: 乐记 V1 交付

Contract classification: `historical-delivery-contract`  
Status: ready-for-agent  
Feature: v1-delivery  
Product: 乐记 (`com.lezi.babylog`)  
Source: docs/prd/ (README, ui, data-model, tech) · conversation 2026-07-22  

---

## Problem Statement

新手父母（及共同照护者）需要在夜喂、换尿布等慌乱场景下，用最少点击记下喂养、睡眠、排泄等日常，并在当天快速回看「吃了多少、睡了多久、尿便几次」。市面上成熟产品功能全但夹带广告与会员；自己又需要一份**可装在 Android 上、纯本地、无付费墙**的家庭育儿日志。

当前仓库只有产品规格文档，**还没有可安装的应用**。需要交付 **V1：可安装的本地 APK**，覆盖主路径记账与今日回顾，为后期家庭同步预留接口但不依赖网络。

---

## Solution

交付 **乐记 V1**——一款简体中文 Android 育儿记录 App：

- 安装后无需登录即可创建宝宝并开始记录  
- 1～2 次点击完成常用记录；母乳支持左右计时（关 App 仍可跑）  
- 当日时间轴 + 日汇总 + 一日时间条，快速回看  
- 多宝宝、主题色、深色模式、下次喂奶本机提醒  
- 数据全在本机；账户/共享页可见但标明同步未开通（`SyncPort` 空实现）  
- **无广告、无会员、无内购**  
- 产出可安装的 debug（及建议 release）APK  

产品定位、画风与页面结构对齐 `docs/prd/`；本 spec 是 **V1 可执行交付范围**，不含 V1.5/V2。

---

## User Stories

### 引导与宝宝

1. As a 照护者, I want 首次打开应用时创建宝宝档案（昵称、性别、生日、主题色）, so that 后续记录都归属到正确的孩子  
2. As a 照护者, I want 不强制注册或登录即可使用, so that 夜喂时不被账号流程挡住  
3. As a 照护者, I want 应用使用简体中文主路径文案, so that 我能无障碍理解每个按钮  
4. As a 照护者, I want 看到应用名「乐记」与自有图标, so that 在桌面上能认出自己的 App  
5. As a 多孩家长, I want 添加第二个宝宝并切换当前宝宝, so that 不会把记录记错孩子  
6. As a 多孩家长, I want 每个宝宝有独立主题色, so that 一眼区分当前在记谁  
7. As a 多孩家长, I want 通过点昵称或长按底部相关 Tab 切换宝宝, so that 切换足够快  
8. As a 多孩家长, I want 长按昵称跳到兄/姐相同日龄的那天（若有多孩）, so that 方便对照成长节奏  
9. As a 照护者, I want 顶栏显示生后日数/月龄, so that 知道孩子多大了  
10. As a 照护者, I want 在设置里编辑宝宝信息与预产期字段（预产期可先存）, so that 档案可改  

### 快速记录（通用）

11. As a 夜喂中的父母, I want 点底部图标网格一键生成当前时间的记录, so that 尽量单手完成  
12. As a 照护者, I want 点时间轴条目进入编辑页改时间/量/备注, so that 事后可补全  
13. As a 照护者, I want 在编辑页删除单条记录并确认, so that 误记可清掉  
14. As a 照护者, I want 记录类型图标可排序与隐藏, so that 界面只留我常用的  
15. As a 纯配方奶用户, I want 关闭喂奶计时入口, so that 不被母乳计时打扰  
16. As a 照护者, I want 时间轴默认新记录在上, so that 最近发生的事最先看到  
17. As a 照护者, I want 时间轴显示相对时间（如「2 小时前」）, so that 不用心算间隔  
18. As a 照护者, I want 备注支持历史输入候选（本机）, so that 药名等地名少打字  
19. As a 照护者, I want 误触全清数据时经过多重确认, so that 不会一键毁掉全部历史  

### 喂养

20. As a 哺乳父母, I want 用左右大按钮分别计时并生成母乳记录, so that 知道每侧多久  
21. As a 哺乳父母, I want 看到「上次停在哪一侧」的提示, so that 下次从正确侧开始  
22. As a 哺乳父母, I want 关掉应用后计时仍继续, so that 中途锁屏也不丢时间  
23. As a 哺乳父母, I want 进程被杀后能恢复进行中的计时或安全结束, so that 系统杀后台不 silently 丢状态  
24. As a 哺乳父母, I want 选择把记录时刻记为开始或结束时间, so that 对齐自己的习惯  
25. As a 哺乳父母, I want 可选补录母乳量, so that 需要时有量可查  
26. As a 使用配方奶的照护者, I want 快速录入毫升数（**步进默认 5ml**，可改为 10/15；加减与芯片与设置同步；支持数字键盘）, so that 同量一键、异量手输  
27. As a 照护者, I want 记录喂挤出乳的毫升, so that 瓶喂也算进奶量  
28. As a 照护者, I want 记录挤奶事件的毫升（无库存余额）, so that 只记发生过的事不必维护奶库  
29. As a 照护者, I want 设定下次喂奶间隔并在到点收到本机通知, so that 不用另开闹钟  
30. As a 照护者, I want 记完母乳/配方奶后确认或调整下次提醒时间, so that 间隔可临时改  

### 睡眠与排泄

31. As a 照护者, I want 一点「睡下」和「醒来」自动算睡眠时长, so that 不用心算  
32. As a 慌乱中的父母, I want 连续两次「睡下」等异常仍能写入并标「!」, so that 不被弹窗堵住  
33. As a 照护者, I want 一键记尿尿（默认中量）, so that 换尿布极快  
33b. As a 照护者, I want 尿尿可选小/中/大并用图标选择, so that 知道尿量档位  
34. As a 照护者, I want 记便便并用图标选量/软硬/色, so that 夜喂靠图形辨认  
35. As a 照护者, I want 便便写属性时量·软硬·色三者齐全（若填写）, so that 摘要一致可读  
35b. As a 照护者, I want 时间轴用图标显示排泄档位, so that 一眼扫过去  
36. As a 照护者, I want 一键记尿+便（含尿量与便分档）, so that 一次换尿布记全  

### 健康与其它记录

37. As a 照护者, I want 手输体温（℃）, so that 发烧可追踪  
38. As a 照护者, I want 低月龄高热时看到可关闭的就医建议文案（含免责）, so that 有提示但不强迫  
39. As a 照护者, I want 记备注并可附照片, so that 碎片信息有处放  
40. As a 照护者, I want 写育儿日记并附照片, so that 留下当日叙述  
41. As a 照护者, I want 一键记洗澡、散步、咳嗽、发疹、呕吐、受伤, so that 日常事件不丢  
42. As a 照护者, I want 记用药（名称/剂量）与就医备注, so that 就医时能说清  
43. As a 照护者, I want 记「其他」自由文本, so that 未分类事项也能留下  

### 日视图与导航

44. As a 照护者, I want 在记录页看到当日日汇总（睡眠、尿、便、奶量等）, so that 一眼知道今天总量  
45. As a 照护者, I want 看到 0–24h 一日时间条（喂养/睡眠色带）, so that 理解一天节奏  
46. As a 照护者, I want 用日期栏与月历跳到历史某一天, so that 回看过去  
47. As a 照护者, I want 在非今天时点「返回今天」, so that 快速回到当前  
48. As a 照护者, I want 底部有「记录」作为主 Tab, so that 默认落在记账  
49. As a 照护者, I want 「汇总」「成长曲线」在 V1 可占位或标明稍后, so that 导航结构稳定不误导为已实现周图  
50. As a 照护者, I want 「账户」页说明家庭同步尚未开通, so that 不以为坏了  
51. As a 照护者, I want 「加入家庭/共享码」可见但是 Stub, so that 心智是家庭产品且不假装已联网同步  
52. As a 照护者, I want 菜单里管理设置、宝宝、关于, so that 次要能力有入口  
53. As a 夜喂父母, I want 一键深色模式, so that 屏幕不刺眼  

### 持久化、隐私与交付

54. As a 照护者, I want 强制停止应用再打开后记录仍在, so that 相信本地存储  
55. As a 照护者, I want 无网时全部记账功能可用, so that 医院电梯里也能记  
56. As a 照护者, I want 看不到广告与购买入口, so that 不被变现打扰  
57. As a 照护者, I want 拒绝通知权限后仍能记账（仅无提醒）, so that 权限不是门槛  
58. As a 开发者/自己打包用户, I want 得到可安装的 debug APK（及可选 release）, so that 装到真机使用  
59. As a 开发者, I want 包名 `com.lezi.babylog`、minSdk 26+, so that 与规格一致  
60. As a 开发者, I want 构建说明可复现 assembleDebug, so that 下次能再打出包  
61. As a 照护者, I want 大按钮与足够触控热区, so that 抱着孩子也能点中  
62. As a 未来同步用户, I want V1 数据模型带家庭/client_uuid 且 SyncPort 可替换, so that V2 接真同步时不推倒重来  

### 计时与系统边界

63. As a 哺乳父母, I want 计时中可以离开计时全屏去看时间轴, so that 不被锁在单页  
64. As a 哺乳父母, I want 系统重启后提醒或计时状态按设计恢复/安全失效, so that 行为可预期  
65. As a 照护者, I want 照片通过系统选择器添加并存在本机, so that 日记有图且不上传云（V1）  

---

## Implementation Decisions

### 产品与品牌

- 显示名 **乐记**；applicationId **`com.lezi.babylog`**  
- UI 语言 V1 **仅简体中文**  
- 无广告、IAP、Premium、会员页  
- 不使用 PiyoLog / ぴよログ / Piyo日志 商标与官方小鸡素材；画风遵循 `docs/prd/ui.md` 的结构与原则，视觉自有  

### 架构

- **Kotlin + Jetpack Compose**，Room 为本地真相源，DataStore 存本机设置  
- 多模块：core（model/database/datastore/ui）、domain、sync、feature（onboarding、log、timer、family、settings 等）  
- **本地优先**：所有写入先成功落库再刷新 UI  
- **`SyncPort`**：V1 提供 NoOp 实现（`isEnabled=false`；pull/push 成功空操作；邀请类返回明确未启用错误）；UI 账户/共享走 Stub 文案  
- **单测主 seam：`CareLog`（领域应用服务）**——创建/切换宝宝、增删改记录、完成计时写 nursing、查询某日时间轴/日汇总/时间条分段、睡眠异常标记、设置中影响记账的项；UI 与 FGS 不成为主测面  

### 领域模型（V1 必建）

- LocalUser（可匿名）、Family、Membership、Baby、Record（`client_uuid` 唯一）、MediaAsset、SettingsLocal  
- Record.type 覆盖 V1 类型表（nursing、formula、pumped_feed、pump_express、pee、poop、both_diaper、sleep、temperature、memo、diary、bath、walk、cough、rash、vomit、injury、medicine、hospital、other）  
- **不做** 挤奶库存表  
- SettingsLocal **永不**进入家庭同步域（含图标顺序、提醒时刻、深色、`amount_step_ml` 等）  
- 主题色存在 Baby 上；同步策略默认本机展示偏好（V1 无同步）  

### 新增/强化需求 A — 排泄分档与图标（PRD §3.1）→ Ticket **04**

| 类型 | 字段 | 规则 |
|------|------|------|
| `pee` | `pee_amount` ∈ {1,2,3} | **1 小 · 2 中 · 3 大**；默认 **2**；UI **三枚图标** 选择；一键可默认中 |
| `poop` | `stool_amount` 1–4、`stool_consistency` 1–4、`stool_color` 0–7 | 可整组省略；填任一项则三者齐；**每档有图标** |
| `both_diaper` | 上列尿尿 + 便便字段 | 日汇总尿次+1 且 便次+1 |

- 时间轴：排泄摘要 **优先图标**（尿一枚量档；便最多三枚：量/软硬/色）  
- 资源：`docs/prd/assets-notes.md`；自绘或合规素材；**禁止** Piyo 官方美术  
- 日汇总：尿/便仍按 **次数**；不按尿量加权  

### 新增/强化需求 B — 配方奶 5ml 步进与 UI 同步（PRD §4.1.1）→ Ticket **13**

| 项 | 规定 |
|----|------|
| 默认 | `SettingsLocal.amount_step_ml` **= 5** |
| 可配 | 设置选项 **5 / 10 / 15** |
| UI 同步 | 加减钮、快捷芯片、选择式列表间隔 = 当前步进；改设置后 **下次打开录入页立即生效** |
| 手输 | 数字键盘允许任意正整数 ml |
| 共用 | `formula`、`pumped_feed`、`pump_express` 的 ml 录入共用步进 |
| 上次量 | 选择式 **上次量居中**（实现固定一种对齐策略并单测） |

- Ticket **02** 只要求能记 formula ml 与汇总正确；**完整 5ml 步进 UI + 设置联动** 归 **13**  
- Ticket **08** 可提供设置入口，但步进默认值与录入页绑定验收以 **13** 为准（08 与 13 可联调）  

### 关键交互

- 图标网格一点 → 当前时间插入记录（需表单的类型可进入编辑）  
- 喂奶计时：前台服务 + 持久 TimerState；完成写入 nursing  
- 睡眠：软校验，异常标 `!` 不阻断  
- 日汇总与时间条由 CareLog 查询语义定义，UI 只渲染  
- 下次喂奶：本机通知 + 精确闹钟策略（系统不允许时降级）  
- 汇总/成长 Tab：V1 占位即可，不实现周图与百分位  
- 删除单条：编辑页确认；清除全部：多重确认  
- **排泄**：图标分档选择（见需求 A）  
- **配方奶 ml**：5ml 默认步进 UI（见需求 B）  

### 权限（V1）

- 通知、前台服务、开机恢复、相册/Photo Picker（日记）  
- 不申请麦克风、定位  
- 可不声明或不用 INTERNET（V1 无网能力）  

### 交付物

- `assembleDebug` 产出可安装 APK；建议同时可 `assembleRelease`（密钥不入库）  
- 根 README：如何构建、已知限制（无同步等）  
- minSdk 26，targetSdk 按当期 Play 政策（34+）  

### 规格溯源

- 功能边界与分期：`docs/prd/README.md`  
- 页面：`docs/prd/ui.md`  
- 数据与 SyncPort：`docs/prd/data-model.md`  
- 工程：`docs/prd/tech.md`  
- 冲突时以 **本 spec 的 V1 范围** 为准；V1.5/V2 不在此交付  

---

## Testing Decisions

### 什么是好测试

- **只断言外部行为**：经 `CareLog`（或同等单一应用端口）写入后，再查询得到的时间轴、汇总数字、标记、记录字段  
- **不测** Compose 节点树、ViewModel 内部字段名、Room DAO SQL 字符串、前台服务绑定细节  
- 计时：用可注入时钟/状态完成「开始→停止→完成写库」的领域路径；FGS 可用薄适配，核心逻辑在 domain  
- NoOp `SyncPort`：调用不炸、不污染本地业务表  

### 测哪些模块

| 优先 | 内容 |
|------|------|
| domain / CareLog | 日汇总、睡眠配对与 anomaly、便便枚举约束、`pee_amount`、client_uuid 幂等预埋、多宝宝隔离 |
| CareLog + Room in-memory | 杀进程语义的代理：关库再开同一数据仍在 |
| 配方奶步进 | `amount_step_ml` 默认 5；改为 15 后候选间隔为 15 |
| 排泄图标 | 资源键齐全（或测试用占位图）；payload 边界 |
| sync NoOp | isEnabled、invite 错误类型 |

### 无 prior art

- 仓库尚无测试代码；本 spec 建立首套约定：domain 单测优先，APK 冒烟人工或后续 instrumented  

### V1 验收冒烟（人工或脚本装包）

1. 安装 → 创建宝宝  
2. 记：配方奶（**5ml 步进加减**）、尿尿（**小/中/大图标**）、便便（**图标分档**）、睡下/醒来、母乳计时完成  
3. 设置步进改为 10 → 再打开配方奶录入，间隔为 10  
4. 核对日汇总与时间条  
5. force-stop → 再开 → 数据一致  
6. 深色模式、第二宝宝切换  
7. 账户页可见、无购买入口、无强制登录  
8. 中文主路径无崩溃  

---

## Out of Scope

以下 **不属于 V1 交付**（见 PRD V1.5/V2 或明确不做）：

- 真同步、共享码生效、双机 60s 可见  
- 周汇总四图、成长百分位曲线、身长体重录入进曲线（V1.5）  
- 搜索、TXT/PDF 导出、桌面 Widget  
- 自定义项目、辅食/点心/饮料类型、头围胸围足长、疫苗、日程  
- 广告、会员、IAP、视频日记  
- 奶库库存、部分字段共享、伴侣逐条推送  
- 育儿制度 CMS、保育端、姐妹 App、手表/语音助手/音波体温  
- iOS、上架商店元数据完备（可后做）  
- 日/英多语言切换  

---

## Further Notes

- **Issue tracker**：本地 `.scratch/`（见 `docs/agents/issue-tracker.md`）  
- **主测试 seam（已确认）**：单一 `CareLog` 领域应用服务；`SyncPort` 为薄 NoOp 契约  
- 实现顺序建议：工程骨架 → Room 模型 → CareLog 记账与日读模型 → 记录 UI → 计时 FGS → 提醒 → 家庭 Stub UI → 深色/多宝宝打磨 → 打 APK 冒烟  
- 拆票可用后续 `/to-tickets` 对本 spec 切片；本文件 `Status: ready-for-agent` 表示规格已足够 AFK 开工  

---

## Comments

- 2026-07-22: seams 确认 — 单主 seam CareLog；发布 ready-for-agent  
- 2026-07-22: `/to-tickets` 已发布 12 张票 → [ISSUES.md](./ISSUES.md) · `issues/01`…`12`  
- 2026-07-22: **PRD 增量**写入本 spec（排泄图标+尿量档、配方奶 5ml 步进）→ 票 **04** 强化、新票 **13**；12 冒烟依赖含 13  

> status: `historical-delivery-contract`  
> 本文只保留历史交付背景，不覆盖 `docs/prd/` 与
> `.scratch/offline-v2-conformance/spec.md` 的当前离线 APK 合同。
