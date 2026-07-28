# Spec: 下次喂养进入护理计划日程

Status: complete
Feature: next-feed-into-care-plan
Product: 乐记 (`com.lezi.babylog`)
Source: 调试会话 2026-07-28 · `/to-spec`「让下次喂奶自动进入日程 包含母乳和配方奶」；范围确认为喂养三件套 + CareLog 领域缝 + 家庭共享护理计划

---

## Problem Statement

照护者在记录一次母乳、配方奶或喂挤出乳后，会看到「设置下次喂养提醒？」并选择确认或调整间隔。当前实现只把时刻写入本机 `nextFeedAt` 与非精确闹钟，**不会**产生护理计划，因此：

- 乐记日历与首页「待履行」日程区不会出现下一次喂养；
- 用户在日程视角核对时以为「没生效」；
- 本机下次喂养闹钟与护理计划提醒是两套机制，冷启动只重挂后者，可靠性与可理解性都差；
- 若用户本就用未来时间「安排护理」，保存后仍可能再弹下次喂养，语义叠床架屋。

用户期望：确认「下次喂养」后，该意图自动出现在日程里（与母乳、配方奶、喂挤出乳一致），到点可提醒并可履行，而不是只有看不见的后台闹钟。

## Solution

将「喂养记录保存后确认的下次喂养」收敛为创建一条**未来单次护理计划**（家庭共享 `CarePlan`），类型与刚完成的喂养事实一致（母乳 / 配方奶 / 喂挤出乳），计划时刻为所选提醒时刻。

- 乐记日历、首页待履行、护理计划本机提醒（及用户已配置时的系统日历投影）全部走现有护理计划路径；
- 领域层提供单一用例接缝，由记录 Composer 与母乳计时完成页在确认提醒时调用；
- 同一宝宝同一时刻只保留一条有效的「下次喂养」类开放计划（替换/更新语义），避免连记多次堆叠一堆相同待履行项；
- 确认提醒成功后，**不再依赖**独立的 next-feed 专用闹钟作为权威提醒源（可退役或仅作迁移期兼容，以单一提醒源为准）；
- 用户选择「不提醒」时行为与今日一致：只保留已保存的护理记录，不创建护理计划。

## User Stories

1. As a 照护者, I want 记录配方奶并确认下次喂养后在乐记日历看到对应条目, so that 我能在日程里核对下一次喂养。
2. As a 照护者, I want 记录母乳并确认下次喂养后同样进入日程, so that 瓶喂与亲喂路径一致。
3. As a 照护者, I want 记录喂挤出乳并确认下次喂养后进入日程, so that 当前所有会弹出下次喂养的类型行为一致。
4. As a 照护者, I want 在首页「待履行」看到该下次喂养计划, so that 不必先打开日历 Tab。
5. As a 照护者, I want 使用默认间隔确认提醒时按设置中的下次喂奶间隔生成计划时刻, so that 与现有文案「按设置间隔」一致。
6. As a 照护者, I want 调整为 60 或 120 分钟后计划时刻与所选间隔一致, so that 我能快速改间隔。
7. As a 照护者, I want 选择「不提醒」时不创建护理计划, so that 我可以只记账不安排。
8. As a 照护者, I want 下次喂养计划的类型与刚记录的喂养类型相同, so that 日程上显示配方奶/母乳/喂挤出乳而不是泛化标题。
9. As a 照护者, I want 计划时刻在未来, so that 它是护理计划而不是误写的护理记录。
10. As a 照护者, I want 到点收到护理计划提醒（本机或系统日历策略下的单一来源）, so that 我不会错过下次喂养。
11. As a 照护者, I want 点提醒或待履行项进入履行面板, so that 我能确认实际喂养并生成护理记录。
12. As a 照护者, I want 履行后该计划从待履行消失并出现关联事实记录, so that 意图与事实分离仍成立。
13. As a 照护者, I want 连续两次确认下次喂养时日程上只保留最新一条有效下次喂养计划, so that 不会堆满过时的相同计划。
14. As a 照护者, I want 更新下次喂养时旧计划被跳过/替换且旧提醒取消, so that 不会双响。
15. As a 照护者, I want 在已加入家庭时该计划对其他成员可见（家庭同步）, so that 家人也能看到下次安排。
16. As a 照护者, I want 其他成员可以履行我创建的下次喂养计划, so that 谁在现场谁记录。
17. As a 照护者, I want 未配置系统日历时仍能保存计划并用乐记本机提醒, so that 系统日历不是前置条件。
18. As a 照护者, I want 已配置系统日历且计划默认投影开启时副本出现在所选日历, so that 与现有护理计划行为一致。
19. As a 照护者, I want 投影失败时计划仍在乐记内且提醒降级策略与现有护理计划一致, so that 保存不被阻断。
20. As a 照护者, I want 通知权限未授予时计划仍保存并看到降级说明, so that 我知道为何可能收不到通知。
21. As a 照护者, I want 母乳计时完成后的「设置下次喂养提醒」与记录面板走同一领域结果, so that 入口一致。
22. As a 照护者, I want 若我本就用未来时间安排了喂养护理计划, 不再被诱导再设一套独立下次喂养闹钟, so that 不会双提醒。
23. As a 照护者, I want 编辑或跳过/删除该下次喂养计划的权限与普通护理计划相同, so that 管理规则不分裂。
24. As a 照护者, I want 清除本机记录时该计划与提醒按现有 CarePlan 清理路径处理, so that 不残留幽灵提醒。
25. As a 照护者, I want 进程重启或回前台后仍能恢复开放护理计划的提醒, so that 比旧 next-feed 更稳（复用现有 reschedule）。
26. As a 照护者, I want 成功文案明确「已安排/已设置下次喂养」且能在日程验证, so that 我知道生效了。
27. As a 照护者, I want 失败时看到可理解的错误且已保存的喂养记录不被回滚, so that 记账优先于安排。
28. As a 照护者, I want 计划默认不携带虚假奶量要求阻碍履行, so that 到点可以按实际再填。
29. As a 照护者, I want 跨时区设备对计划时刻的展示与现有护理计划一致, so that 家庭同步语义不变。
30. As a 开发者代理, I want 单测在 CareLog 接缝验证「确认下次喂养 → 开放 CarePlan 出现在观察范围」, so that 不依赖 AlarmManager 真机。
31. As a 开发者代理, I want 回归保证时间轴汇总不被未履行计划污染, so that ADR 事实/意图分离仍成立。
32. As a 照护者, I want 历史仅 next-feed 闹钟的设备在升级后行为有明确定义（见实现决策）, so that 不会静默丢提醒或双响。

## Implementation Decisions

### 权威模型

1. **日程权威 = 护理计划。** 「下次喂养进入日程」= 创建/更新一条 `CarePlan`，不是给 `nextFeedAt` 画 UI，也不是平行本机日程模型。
2. **类型范围：** 与当前 `offerReminder` 喂养集合一致：`nursing`（母乳）、`formula`（配方奶）、`pumped_feed`（喂挤出乳）。挤奶 `pump_express` 等不在此弹窗路径内，本 spec 不扩展。
3. **家庭共享：** 新计划与手动「安排护理」相同：可家庭同步、可被成员履行；提醒与系统日历投影仍是设备本地副作用。

### 领域接缝（唯一主缝）

4. **在 CareLog（或等价领域门面）增加单一用例**，语义示例：
   `scheduleNextFeedCarePlan(babyId, feedType, scheduledAt, …)`
   - 前置：`feedType` 属于喂养三件套；`scheduledAt` 必须严格晚于现在；宝宝有效。
   - 效果：确保该宝宝至多一条「由下次喂养产生的开放计划」处于待执行/已错过；写入或更新为给定类型与计划时刻；触发与 `createCarePlan` 相同的提醒/系统日历投影与同步请求。
   - **不**要求调用方再调 `FeedReminderPort.scheduleAfterFeed` 才能进日程或提醒。
5. **替换语义：** 同一宝宝已存在由本功能创建的开放喂养计划时，更新其 `scheduledAt`/类型（或跳过旧计划再创建——二选一须在实现中固定，优先更新同一 client 身份以减少同步垃圾），并重挂提醒。
   - 不得留下两条同时开放的「下次喂养」计划。
   - 用户手动创建的、非本路径产生的喂养护理计划：实现须定义是否参与替换（推荐：**仅替换本路径标记的计划**，避免误伤用户手建的未来安排；可用 payload 标记、source 字段或稳定约定备注键——优先结构化标记而非文案）。
6. **与独立 next-feed 闹钟：** 本功能上线后，确认提醒的权威副作用是护理计划。`FeedReminderPort` / `nextFeedAt` 应停止作为该路径的提醒权威；实现可选：
   - **A（推荐）** 确认提醒只调领域用例，不再 `scheduleAfterFeed`；清除或忽略遗留 `nextFeedAt`；
   - **B** 短期双写但 CarePlan 提醒成功则取消 next-feed，禁止双响。
   测试以「单一提醒源 + 日程可见」为通过标准。
7. **已是安排护理 / 已在编辑计划：** 若本次保存本身已是 `CreateCarePlan` / `UpdateCarePlan` / 转换计划，**不得**再弹「下次喂养」或再创建第二条计划。`offerReminder` 仅保留在「新增喂养护理记录（事实）」成功之后。

### UI / 入口

8. **Record Composer：** 喂养事实保存成功 → 现有提醒对话框；确认/调整间隔 → 调用领域用例；成功文案体现已安排下次喂养（可与现有「提醒已设置」合并为更准确的「已安排下次喂养」类文案）。
9. **母乳计时完成页：** 与 Composer 同一领域用例与替换语义。
10. **间隔来源：** `null` → 设置中的下次喂奶间隔分钟；60/120 分钟按钮 → 固定偏移。计划时刻 = 确认时的墙钟 + 间隔（与现 next-feed 计算一致）。
11. **「不提醒」：** 不调用领域用例；不创建计划。

### 计划内容

12. **Payload：** 新建下次喂养计划使用该类型的合法最小当前 schema 载荷（满足 create 校验即可）；**不**强制复制上一餐奶量作为必须履行值；备注可空。母乳计划保持「意图 only」：不因创建计划而启动计时。
13. **系统日历投影开关：** 默认与现有 Composer 安排护理默认一致（当前产品默认开启投影意图；未配置则领域 fallback 乐记提醒）。

### 升级与清理

14. **升级时：** 若设备仍有未来的 `nextFeedAt` 且尚无对应开放计划，实现应在首次合适时机（如回前台 reschedule）**迁移为一条护理计划并取消 next-feed 闹钟**，或文档化「一次性丢弃旧 next-feed」——推荐迁移以免用户丢提醒。
15. **Local clear：** 继续走 CarePlan + 提醒清理；next-feed 清理逻辑在退役后可收窄。
16. **用语：** 对外与 CONTEXT 一致：日程条目称**护理计划**；乐记日历 / 待履行；提醒为护理计划本机提醒或系统日历副本；不把 next-feed 再宣传为独立日程。

### 模块边界（不写路径）

17. 领域：CareLog 用例、CarePlan 持久化、提醒投影、同步脏标记。
18. 表现：记录 Composer 提醒对话框结果处理；母乳计时完成提醒结果处理；成功/失败文案。
19. 设置：下次喂奶间隔仍驱动默认计划时刻；「护理计划本机提醒」开关仍门禁计划提醒。
20. **不**改家庭 wire 契约字段集合，除非需要可选的「下次喂养来源」标记且已有扩展点；优先本机可识别标记 + 普通 CarePlan 同步。

## Testing Decisions

### 何为好测试

- 只断言**外部行为**：给定宝宝与喂养类型与未来时刻，调用领域用例后，观察范围内出现开放护理计划；类型与时刻正确；再次调用仍只有一条开放「下次喂养」计划；计划出现在日历/待履行所用的同一观察 API。
- 不断言 AlarmManager 内部 request code、PendingIntent extras 等实现细节。
- 提醒：可用现有 fake `ReminderCleanupPort` 断言 schedule/cancel 次数与计划 id，与 `createCarePlanSchedulesReminder…` 同风格。

### 主缝与覆盖

- **主缝：CareLog 领域测试**（`CareLogTest` 既有 CarePlan 生命周期为 prior art）。
  - 配方奶 / 母乳 / 喂挤出乳均可 `scheduleNextFeedCarePlan`。
  - 非法类型或非未来时刻失败且不写脏计划。
  - 替换语义：第二次安排更新时刻，开放集合大小为 1。
  - 不污染护理记录列表/汇总（无履行则无新 record）。
  - 履行后计划完成并产生记录（复用 fulfill）。
- **表现层轻量：** Composer/计时「确认提醒」调用领域缝而非 `FeedReminderPort` 的契约测试（若已有 SavedState/completion 测试可扩展；避免重 UI 仪表测试作为主证据）。
- **offerReminder 门闩：** 仅 AddRecord 喂养事实为 true；CreateCarePlan 路径为 false（防双计划）——可用 draft/writeDecision 或 completion 标志单测。

### Prior art

- `CareLogTest` 中 create/update/fulfill/skip CarePlan 与 reminder fake。
- `RecordComposerSavedStateTest` 中 post-save reminder ordering。
- `QuickRecordDraftTest` 中 workMode / writeDecision。
- 系统日历投影策略测试保持回归：未配置时仍 schedule 乐记提醒。

## Out of Scope

- 把任意类型的「提醒」都自动变护理计划（仅喂养三件套下次喂养路径）。
- 重复规则（每天/每隔 N 小时自动连环）——仍为单次计划；下一次需再次确认或再记一笔后确认。
- 改变护理记录可写未来时间的规则。
- 强制申请 `SCHEDULE_EXACT_ALARM` 或承诺秒级准时。
- 在账户页做「下次喂养」独立设置页大改版（间隔设置可沿用）。
- 桌面 Widget 展示下次喂养（除非顺带几乎零成本，否则不做）。
- 修复 OPPO 厂商冻进程本身；可靠性收益来自复用 CarePlan 前台/启动重挂，而非厂商白名单工程。
- 历史自由标题日历事项迁移。

## Further Notes

- 调试结论摘要：旧 next-feed **能**在后台响，但不可见日程 + 非精确 + 厂商延迟造成「从没提醒」的体感；本 spec 用护理计划解决**可见性与单一权威**，顺带获得启动/回前台 reschedule。
- 与 ADR「护理计划与护理记录分离」一致：下次喂养是意图，必须履行才成事实。
- 与 ADR「乐记日历只呈现 CarePlan」一致：进入日程 = CarePlan，不是第二套日历实体。
- 实现时注意：母乳计划 fulfill 可走计时或补记；不要在 schedule 时启动计时服务。
- 产品文案建议将「提醒已设置」改为同时覆盖日程的表述（如「已安排下次喂养」），避免用户仍以为只有闹钟。

## Seam confirmation (2026-07-28)

- 测试接缝：CareLog 领域单一用例（已确认）。
- 权威形态：家庭共享护理计划（已确认）。
- 类型范围：母乳 + 配方奶 + 喂挤出乳（已确认；宽于最初口令中的两项）。

## Comments

- 2026-07-28 完成：Composer 与母乳计时入口统一调用 CareLog，三类喂养生成单次开放 CarePlan；旧 next-feed 权威退役，intent-only payload、提醒/日历投影、家庭同步和合并重键均闭环。
- 并发验收覆盖双端先 stage 与 NAS winner 已发布后再 stage；loser whole-package no-op，媒体/manifest/publication 精确清理并支持重启补偿。
- 验收：Android 全测试/lint/Debug 构建、Rust 113 项测试与 clippy、Standards/Spec 双轴审查均通过。
