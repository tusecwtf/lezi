# 乐记 Offline V2 Beta 可执行验收合同

status: `current-local-external-contract`  
versionName: `0.2.0-offline-v2-beta`  
baseline: `a15f1ca03150de89a72c8dbea478a9275bd24680`  
decision-date: `2026-07-25`

本合同是本地外部验收合同，不属于基线 HEAD。产品意图以 `docs/prd/` 为先；
发生冲突时先修改 PRD，再修改本合同和实现。同步、服务端、Outbox、RealSync 与
网络 hardening 均排除。

## 1. 记录写入与数据安全

| Must | 自动证据 | 人工冒烟 |
|------|----------|----------|
| 所有新增入口只打开预填 Composer，确认前不落库 | `QuickRecordDraftTest`; `WidgetComposerContractTest`; `tools/verify-review-remediation.sh` | 点任一快捷入口后取消，时间轴记录数不变 |
| 启动不得按昵称改名或合并宝宝 | `CareLogTest` 启动/读取测试 | 建两个昵称相近宝宝，重启后 ID、昵称、记录归属不变 |
| 合并只能显式预览、取消或确认 | `CareLog.previewBabyMerge` / `mergeBabyProfiles` | 账户页发起合并，核对迁移计数，取消后无变化 |
| 散步是点记录，可带备注，不要求结束时间 | `QuickRecordDraftTest` | 新增散步不填写结束时间仍可确认 |
| ClockDial 确认读取当前唯一 picker state | `ClockDial.kt`; App 编译 | 改时分后立即确认，记录时刻与圆盘一致 |

## 2. Typed Record Payload

| Must | 自动证据 | 人工冒烟 |
|------|----------|----------|
| 每种 `RecordType` 只接受匹配 payload | `RecordPayloadCodecTest` | 逐一新增所有离线类型并回看摘要 |
| v1 兼容读，新增/编辑写 v2 | `RecordPayloadCodecTest`; `QuickRecordDraftTest` | 编辑一条 v1 测试记录后检查 schemaVersion=2 |
| extensions 原样保留 | `RecordPayloadCodecTest` | 编辑带未知字段记录后字段仍在 |
| 畸形/未来格式成为 `UnknownPayload`，原始字节不丢 | `RecordPayloadCodecTest` | 打开异常记录只能安全显示/阻止破坏性覆盖 |
| 搜索、时间轴、汇总、导出、Widget 共用 typed payload 与中文摘要 | `rg` 源码门禁；相关模块单测 | 同一记录在三处显示同一业务摘要 |

## 3. 记录、媒体与自定义项目

| Must | 自动证据 | 人工冒烟 |
|------|----------|----------|
| `pump_express` 可从更多项目打开 | App 编译；源码门禁 | 新增一条挤奶记录 |
| 项目显隐、更多项目排序、时间轴正倒序有真实消费者 | feature log/settings 单测与编译 | 改设置后返回记录页核对 |
| 备注历史按宝宝+类型隔离 | `CareLog.recentNotes` | 两宝宝同类型写不同备注，候选不串 |
| 配方奶保留 5/10/15 步进并允许 1–999 任意整数与最近量 | `QuickRecordDraftTest`; `CareLogTest` | 输入 127ml 保存并再次打开看到候选 |
| Memo/Diary 最多 9 图，复制到私有目录；编辑/删除清理生命周期 | `RecordPhotoStore`; payload codec tests | 选多图、删除一张、删除记录并检查展示/导出 |
| 自定义项目最多 10，固定 8 图标槽，可改名/排序/删除 | `CareLogTest.customItemsRejectEleventhAndKeepStableSnapshots` | 管理项目并新增自定义记录 |
| 删除目录项不改写历史标题/图标快照 | typed payload test / `CustomPayload` | 删除项目后旧记录摘要仍在 |
| 后续宝宝拥有昵称、性别、生日、预产期、主题色、出生体重、头像能力 | App 编译 | 添加第二宝宝并在账户页编辑头像 |

## 4. 计时、通知与日程

| Must | 自动证据 | 人工冒烟 |
|------|----------|----------|
| TimerState 绑定启动时宝宝并跨进程恢复 | `TimerStateRestorationTest.snapshotRoundTripPreservesBoundBaby` | 计时中切宝宝、force-stop、恢复并完成，记录归原宝宝 |
| 母乳按设置存开始或结束时刻，payload 保存 `record_mode` | `CareLogTest.completeNursing_recordModeControlsStoredTimestamp` | 两种设置各完成一次 |
| 保存喂养后再询问确认/调整/不提醒 | App 编译 | 三个动作分别验证 |
| 首次启用提醒请求 `POST_NOTIFICATIONS`；拒绝不影响记录/日程保存 | settings/log lint + App 编译 | 拒绝权限后保存并看到明确降级文案 |
| 只用非精准本地闹钟；不得申请精准闹钟权限或保留精准/回退双路径 | `rg 'setExact|canScheduleExact|SCHEDULE_EXACT_ALARM'`; lint | 允许通知后等待普通本地提醒 |
| 日程可增改删、调度、取消并开机恢复 | settings 单测/编译；BootReceiver | 创建、修改、删除，再重启核对 |

## 5. 汇总、成长、搜索、导出与 Widget

| Must | 自动证据 | 人工冒烟 |
|------|----------|----------|
| `CareAggregation` 一次产生日桶、范围汇总、24h 条和 Widget 摘要 | domain/summary/widget tests | 构造跨午夜和进行中睡眠，四处一致 |
| Summary 支持周起始、历史周、上周对比、平均睡眠 | summary tests | 切周与开关 |
| 体温提示按记录时刻月龄：不足 3 个月且 ≥38℃，可关闭 | `InfantFeverAdviceTest` | 边界值与开关 |
| 成长支持预产期、修正月龄持久设置、按性别 WHO 0–24 月离线表 | growth tests/App 编译；资产 JSON 来源 | 切宝宝性别与重启后开关 |
| 搜索、时间轴、导出共用中文摘要 | domain/search/export 编译与单测 | 搜索后与导出正文核对 |
| TXT/PDF 支持任意合法日期范围；PDF 可选记录图像 | export 编译 | 导出自然月并分享 |
| Widget 实例独立绑定宝宝/快捷类型，展示统一摘要 | widget 12 个单测 + lint | 配置两个实例，点快捷入口只打开 Composer |

## 6. 最终门禁

必须记录真实结果，不得把未运行写成通过：

```text
./gradlew test lint :app:assembleDebug :app:assembleRelease
connected database/app/designsystem tests（有设备时）
tools/verify-review-remediation.sh
tools/verify-template-v2.sh
git diff --check
rg 'setExact|canScheduleExact|SCHEDULE_EXACT_ALARM' app feature
```

APK 冒烟：安装、双宝宝隔离、全类型新增、编辑删除、图片与自定义项目、计时恢复、
跨午夜汇总、成长、搜索、TXT/PDF、通知拒绝/允许/重启、深色/左右手/排序与隐藏、
Widget 配置和快捷 Composer，最后检查 Logcat 无 fatal。
