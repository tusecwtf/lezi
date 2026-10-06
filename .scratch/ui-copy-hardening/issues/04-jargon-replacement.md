# 04: 黑话替换——家长语言映射表

**What to build:** 按 spec 映射表替换约 15 处开发者/规范口吻：已自动对齐→多台设备记了同一件事、
合法时间→可用时间、上下界→最早到最晚、本机投影→仅保存在本机、接回家庭→重新连上家庭、
冲突未采纳·点此审计→N 条计划记录未对上·点开核对、履行→记录、权威事实→该计划的最终记录、
会话已失效→计时已过期、时长已冻结→时长已暂停、重试跳过→再试一次。
`LocalDataRecoveryScreen` 的 APK/迁移链/原地替换/永久兼容基线整体家长语言重写（保留必要事实）。
`GrowthScreen` 保留 WS/T 423 引用，加 P3/P50/P97 白话图例。

**Blocked by:** 无

**Status:** done

- [x] 映射表逐条落地（含 contentDescription 同步）
- [x] LocalDataRecoveryScreen 重写
- [x] GrowthScreen 图例
- [x] 文案断言测试更新

## Comments

- 2026-09-05：映射表全部落地（自动对齐/合法时间/上下界/本机投影/接回×3/会话失效/时长冻结/
  重试跳过/审计/履行/权威事实）。LocalDataRecoveryScreen 四处工程词汇重写为家长语言，
  快照空间需求人化为 MB/GB。GrowthScreen 保留 WS/T 423 引用并加 P3/P50/P97 白话图例。
  contentDescription 同步更新。
