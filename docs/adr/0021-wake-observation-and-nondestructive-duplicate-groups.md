---
status: accepted (0.3.13 planning freeze; not runtime-delivered)
---

# WakeObservation 与非破坏性疑似重复分组

睡眠的「睡下」与「醒来」拆为独立家庭事实；跨成员、不同 UUID 的近邻记录改为客户端
**疑似重复组** 提示与显式确认，不再由服务器自动 tombstone 落选。

本决策 **取代** [ADR-0018](./0018-neighbor-duplicate-records-and-tombstone-wins.md)
对 **新因果数据** 的自动近邻落选与「record 误删只能新 UUID」中与因果 restore 冲突的
部分叙述；**不** 批量复活历史 tombstone。关联
[ADR-0019](./0019-server-validates-constraints-not-care-truth.md)、
[ADR-0020](./0020-stable-projection-immutable-versions-and-branches.md)。

**Wire 字段、枚举、迁移 ID 公式与 API shape 的唯一权威**是
[`docs/prd/causal-sync-wire.md`](../prd/causal-sync-wire.md)；本 ADR 不另造标识符方言。

## 决策

### 1. SleepStart 与 WakeObservation 分离

- 新 Sleep wire 根只含 SleepStart 字段，**禁止** `end_timestamp` 键（含 null）作为醒来竞争场。
- 每次醒来是独立 **WakeObservation** 原子根（字段见 wire §4.5）：
  `sleep_record_client_uuid`、`wake_timestamp`、`withdrawn`、服务器盖章
  `observer_membership_id`、备注与 0–3 张 `wake` 媒体。
- 多个观察全部保留。未设 `effective_wake_observation_client_uuid` 前：投影用最早合法观察
  （`wake_timestamp >=` 对应 Sleep 的 `timestamp`，且 `withdrawn=false`）作暂定 end，并展示
  全部未撤回观察。
- Sleep 作者或 Owner 通过对 **sleep record** 的因果 mutation 写入
  `effective_wake_observation_client_uuid`；观察者可纠正字段或设 `withdrawn=true` 撤回。
- 非法 `wake_timestamp` 拒绝；永不产生负区间。
- 新建或 pull 另一条开放 SleepStart **不得** 自动闭合、编辑或 tombstone 较旧开放睡眠。
- 历史已闭合睡眠迁移：WakeObservation `client_uuid` 与字段转移按 wire §11 的 UUIDv5 公式
  （server 与 Android **同式**）；历史开放睡眠只建立 SleepStart 版本。

### 2. 非破坏性疑似重复组

- **移除** 新能力代的服务器近邻裁决、Owner/最早/UUID 赢家、`neighbor_losers` 回包语义。
- 客户端复用：精确类型白名单、跨 membership、含边界 30 分钟窗 → 软 **疑似重复组**。
- 组操作在显式授权声明/resolution **之前** 不得改写任何源记录。
- 作者仅可声明自己写的记录与另一来源相同；Owner 可解决整组。
- 同一事件 resolution：选择一个展示版本；其它根与媒体存为 **来源关系**（永久保留，
  **不是** 普通 record tombstone）。
- 未确认组的汇总：对所有合法解释运行既有聚合语义，展示 **上下界**；确认后只按展示版
  单值聚合，来源/照片仍保留。

### 3. 历史 tombstone

- 现存无法证明删除原因的历史 Record tombstone（含近邻落选与手删）迁移后保持
  **隐藏稳定 tombstone**。
- **不** 合成冲突分支，**不** 提供批量「复活近邻落选」入口。
- 新因果删除/restore 规则只作用于可证明 base 的新数据与显式 resolution（ADR-0020）。

### 4. 与 0.3.11 家庭 wake / B1 的关系

- 0.3.11 整行闭合 + 本机 B1 纠错是 **已交付** 过渡行为。
- 0.3.13 规划以 WakeObservation 取代整行 end 竞争与 portable closer；ACL 仍是
  membership 自我或 Owner，观察者只能改自己的观察。

## Considered Options

- **保留服务器近邻 tombstone，仅改 UI 文案：** 仍替家庭删事实；拒绝。
- **自动合并备注/照片进胜者：** 不可逆篡改 provenance；拒绝。
- **睡眠继续 end_timestamp LWW + open→closed 单向：** 无法保留多观察；拒绝。
- **复活全部历史近邻落选：** 无可信原因分类，会制造幽灵双条；拒绝。

## Consequences

- CONTEXT / PRD 必须交叉区分：近邻历史术语 vs 疑似重复组；家庭 wake 历史 vs
  WakeObservation；记录墓碑永胜历史 vs 因果删除/显式 restore。
- 汇总、时间轴、导出不得把来源关系当成已删除；未确认疑似重复不得假装精确单次计数。
- 开放睡眠修复测试在实现票中须反转：多条开放 SleepStart 保持不变。
- ADR-0018 状态改为 partial supersession；其「实现前产品细节」段不再约束 0.3.13+。

## 实现状态

规划目标随 0.3.13 因果切割一并交付。本 ADR 为文档冻结，不含运行时证明。
