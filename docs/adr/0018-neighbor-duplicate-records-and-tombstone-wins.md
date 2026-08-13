---
status: partially superseded by ADR-0021 (and ADR-0019/0020) for 0.3.13+ new causal data — server neighbor auto-lose and universal “tombstone forever / only new UUID” as the sole recovery story; retained as historical 0.3.10–0.3.12 delivery and for unclassifiable historical tombstones that stay hidden
---

# 跨成员近邻重复由服务器隐式落选，护理记录墓碑永胜

## 0.3.10–0.3.12 已交付范围（历史合同）

家庭同步里两类「看起来像冲突」必须分开处理：**同 `client_uuid` 的身份修订** 与
**不同 UUID 的语义双记**。前者不再允许 live 用更高 `updated_at` 清零 `deleted_at`；
后者由家庭服务器在权威 commit 同一事务内，对白名单类型的跨 membership 近邻重复组做
隐式采纳并 tombstone 落选。术语见根目录 [`CONTEXT.md`](../../CONTEXT.md)
（近邻重复护理记录、家庭近邻裁决、记录墓碑永胜等）。调研见
[`docs/research/2026-08-05-offline-first-conflict-resolution.md`](../research/2026-08-05-offline-first-conflict-resolution.md)
与
[`docs/research/2026-08-05-sync-conflict-and-duplicate-resolution.md`](../research/2026-08-05-sync-conflict-and-duplicate-resolution.md)。

## 0.3.13 规划 supersession（非已交付）

[ADR-0021](./0021-wake-observation-and-nondestructive-duplicate-groups.md) **废止**
本 ADR 对 **新因果数据** 的：

- 服务器隐式近邻采纳与落选 tombstone；
- `neighbor_losers` 提示信号作为跨 UUID 产品出口；
- 将「误删/落选后只能新 UUID、永不显式 restore」写成与因果 CAS restore 冲突的唯一故事。

**保留：** 迁移后无法证明原因的历史 Record tombstone 继续隐藏，不批量复活；同 membership
窗内多记不作为疑似重复；白名单与 30 分钟窗作为 **客户端疑似重复提示** 输入被复用。
同 UUID 删除的因果语义见 ADR-0020，不再依赖「更高 updated_at live 永胜 tombstone」的
LWW 修补叙述作为目标架构。

## 历史决策（0.3.10–0.3.12 已交付）

> 以下各节为 **已交付** 架构叙述，现在时仅描述 0.3.10–0.3.12 运行时。
> **0.3.13 目标合同** 见 ADR-0021 与 [`causal-sync-wire.md`](../prd/causal-sync-wire.md)；
> 勿将本节服务器近邻 tombstone / `neighbor_loser` 读成新能力代要求。

### 1. 记录墓碑永胜（问题 B）

护理记录一旦在家庭权威图上接受软删 tombstone（用户/管理员手删，或下文近邻落选），
**禁止**任何后续 live 推送清零 `deleted_at` 复活该 `client_uuid`。这与
`care_plan` / `custom_item` / `fulfillment_candidate` 已有策略对齐，废止 record
注释中「可 restore」的 LWW 语义。误删或落选后需要事实时，用户 **新记一条**
（新 UUID），不提供静默 LWW restore。

权威 reconcile 与 atomic commit 对 record 的入站 live-over-tombstone 必须
fail closed 或忽略为不可复活（与现有 `*TombstoneResurrection` 同族），不得再因
`updated_at` 更大而采用 live。

### 2. 家庭近邻裁决（问题 A）

当多条 **live** 护理记录同时满足：

- 同一宝宝；
- 精确同一 `RecordType` wire 键（非 UI 大类）；
- 主时间 `|Δtimestamp| ≤ 30` 分钟（含边界；只比事件主时间）；
- 作者 membership **不同** 且均有效（空作者不参与）；
- 类型落在固定白名单：
  `nursing`, `formula`, `pumped_feed`, `pump_express`, `baby_food`, `snack`,
  `drink`, `pee`, `poop`, `both_diaper`, `temperature`, `bath`, `medicine`；

则构成近邻重复。服务器在跨 membership 且窗内连边后取 **连通分量** 为近邻重复组；
组内至多保留一条 live，其余为近邻落选墓碑。

**采纳次序：** 作者为 **裁决时刻** 当前家庭唯一 Owner membership 者优先 → 否则
主时间最早 → 再 `client_uuid` 字典序更小者。不向用户展示冲突选择或合并 UI；
不把落选备注/照片拼进胜者。

**同 membership 豁免：** 同一人（含其多台设备）窗内多条不构成近邻重复，以保留
真实连喂等事实。落选后再新建窗内跨成员近邻条可再次落选；要独立事实须拉开主时间
或编辑胜者那条。

**履行写出的白名单记录** 同等参与；落选 **不** 自动回滚计划完成态或作废履行候选证据。

**胜者再被软删** 时，落选墓碑 **不** 翻案复活。

### 3. 执行位置与可重入

- 仅 **家庭服务器** 在权威提交路径裁决；客户端不做同规则乐观本地 tombstone。
- 与触发它的 atomic commit **同一数据库事务** 写入落选；禁止 commit 后异步 job、
  禁止仅在 pull 路径写权威落选（避免权威图长期双 live）。
- **可重入** 指：规则是 live 集合上的不变量，同一分片被再次写入时必须再收敛；
  **不是** 每次 push 对全家全历史或全部白名单类型做全表扫描。
- **默认实现范围（触达分片 + 时间邻域）：**
  - 仅当本轮 commit 使某白名单护理记录的 live 集合或
    `baby` / `RecordType` / 主时间 / 删除态发生变化时运行近邻；
  - 只重算本轮 **触达的** `(baby, RecordType)` 分片；
  - 候选行限定为该分片内、相对触达事件时间落在近邻邻域
    （实现上对触及时间点取约 `[t−30min, t+30min]`，连通分量仍按两两
    `|Δt|≤30min`）的 **live** 行；
  - 未触达分片跳过；无白名单 record live 变更的 commit 整段跳过。
- 历史双条在对应分片再次被写路径碰到时自然收敛。若产品要求升级后立刻清掉
  全部旧双条，用 **一次性** 分片回填（版本门闩或首包维护路径），不要把
  「每次 push 全表扫」当常态。已 tombstone 行不参与、不因重算翻案。

### 4. 客户端提示

仅当服务端 **本轮显式** 给出近邻落选信号（如本轮 `neighbor_loser` `client_uuid`
集合），且其中含 **本机曾 live、作者为当前 membership** 的记录时，展示一次不阻断
轻提示（每同步周期最多一条汇总）。不得启发式把普通远端手删当成近邻文案；旁观设备
不提示。

## Considered Options

- **只加强同 UUID LWW / 字段 CRDT：** 管不了两条新建；拒绝作为 A 的解。
- **全文 CRDT（Automerge 等）：** 过重，且不消除「两条实体」的产品语义；拒绝。
- **服务器静默按到达序吞第二条，无类型/时间窗模型：** 不可测、易误吞真连喂；拒绝，
  改用显式近邻定义 + 同 membership 豁免。
- **跨作者弹「可能重复 / 保留两条 / 合并」：** 更安全但违背已选「隐式」产品目标；拒绝。
- **先到权威图者胜、或字段更完整者胜：** 弱网与「堆字段抢赢」不稳；拒绝，采用
  Owner → 最早主时间 → `client_uuid`。
- **仅近邻墓碑禁复活、手删仍 LWW restore：** 两套删除合同；离线更高 live 仍可打穿
  落选；拒绝，统一 **记录墓碑永胜**。
- **客户端乐观近邻预判 / 各端各自发墓碑：** 与 Owner 视图漂移和双写窗口打架；拒绝，
  只信服务器（ADR-0017 权威骨架）。
- **履行记录豁免或落选回滚履行：** 留下抢记洞，或硬碰履行证据冻结；拒绝。
- **commit 后异步近邻扫：** 权威图中间态双 live；拒绝。
- **每次 push 全库/全白名单类型重算：** 正确性不需要，家庭规模下也浪费；拒绝作为
  默认实现。可重入用触达分片 + 时间邻域表达即可。

## Consequences

- **协议 / Store：** record 入站必须实现 tombstone 禁复活；近邻裁决挂在 commit 事务的
  **触达分片** 上（时间邻域装载 live 候选，内存连通分量 + 采纳）；回包或增量需携带
  本轮近邻落选信号供客户端提示。既有「record 可 restore」测试与 PRD 表述必须改为
  永胜，并与 `care_plan` 等对称回归。索引/生成列可按需加，但不是正确性前提。
- **产品：** 时间轴在同步后对白名单类型跨成员近邻至多一条 live；离线可短暂双条。
  真·半小时内两次事件靠 **同人连记** 或 **主时间拉开 >30 分钟**；窗内对着胜者反复
  插入会被再次落选。
- **统计与履行：** 落选不进汇总；计划可能已完成而时间轴无对应可见记录（窄窗口，接受）。
- **不取代：** 开放睡眠域不变量、履行冲突未采纳、原子记录同步包、ADR-0017 对账周期
  仍然独立有效。近邻是 **额外域不变量**，不是通用合并引擎。
- **实现前：** 产品细节可再写入 `docs/prd/`；本 ADR 冻结架构取舍。实现另开任务，
  本决策记录本身不包含代码变更。
