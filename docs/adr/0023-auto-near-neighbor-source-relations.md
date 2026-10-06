---
status: accepted
---

# 近邻同型自动写成来源关系

同一宝宝、精确同类型、主时间落入含边界 30 分钟窗的多条护理记录，由家庭服务器在
因果 commit 成功后 **自动写成 canonical 来源关系**：选一条展示版，其余永久保留为
来源。不 tombstone、不改写 payload、不把备注/照片揉进胜者。

本决策 **部分取代** [ADR-0021](./0021-wake-observation-and-nondestructive-duplicate-groups.md)
§2「组操作在显式授权之前不得写关系」。非破坏 provenance、禁止服务器近邻 tombstone、
禁止自动合并字段仍成立。关联 ADR-0019（服务器仍不裁决护理字段真相）、ADR-0021 §1
（多条开放 SleepStart 不得自动闭合）。

**Wire 字段与拒绝码的唯一权威**是 [`docs/spec/causal-sync-wire.md`](../spec/contracts/causal-sync-wire.md) §12。

## 决策

### 1. 自动收口，人可改选

- 完整连通分量（≥2 条 live Record、尚未成组或仅为被新分量完全包含的旧半边/小组）
  在家庭写锁内、commit 事务提交前写入 `source_relation`。
- `reason` 对外仍是 `owner_group_resolve`。**服务端** canonical 关系的 `mutation_id`
  以 `auto-near-neighbor:` 为前缀，pull `source_relation_summary.auto_aligned=true`
  加性标出；不新增 reason 枚举（0.4.7 Room 对未知 reason fail-closed）。
  **客户端**不把该前缀写进本地 `pull_summary` 行的 `mutation_id`（见 §5）。
- Owner 仍可用 `POST /source-relations/resolve-group` 改展示版。作者 declare 仍只允许
  声明自己写出的记录。

### 2. 归组键

- 同一 `baby_client_uuid`、精确 `type`、主时间差 ≤30 分钟（含边界）进同一分量。
- **同 membership（含多台设备、同机两次确认）也连边。**
- 全部 Record 类型参与。WakeObservation 不是 Record，不进组。
- 用药、疫苗：再按规范化名称分片；规范化后为空的名称 **各成一组**，不与有名条目合并。
- 自定义：再按 `custom_item_client_uuid` 分片；缺项各成一组。
- 喂养子类、排泄子类 **不** 互并。时间轴可提示「附近还有…」，不写关系、不改汇总。

### 3. 展示版（家庭内确定、可复算）

1. 作者为家庭 Owner membership 的记录优先；多条时取事件 `timestamp` 最早，再 `client_uuid`。
2. 组内没有 Owner 记录：按成员 `display_name_key`（已有正规化），再 `timestamp`，再 `client_uuid`。
3. 来源列表：其余成员按称呼键 → 事件时间 → `client_uuid`。
4. 不按「此刻哪台设备在线」选胜者。

### 4. 睡眠

- **投影开放的 SleepStart 不进自动近邻候选。** 与 ADR-0021 投影同一条：没有合法未撤回
  WakeObservation（`wake_timestamp >=` 该睡眠 `timestamp`），也没有历史遗留闭合
  `end_timestamp`。有效观察指针未设时，最早合法未撤回观察仍使睡眠投影闭合。种子自己是
  投影开放睡眠则本轮不对齐；剔除后再对其余记录连边。
- **闭合后才收口。** 自动收口仍以已提交的记录 UUID 为种子。WakeObservation 不是 Record；
  一次醒来（或把睡眠第一次投影闭合的睡眠 mutation）被接受后，用那条 SleepStart 的记录
  UUID 再跑同一套自动收口。同一 commit 同时带上 SleepStart 与合法醒来的，按已闭合处理。
  只醒一条、近邻另一条仍开放时分量不足 2，不写关系。
- **不拆存量关系。** 升级后的新规则只作用于之后的自动收口尝试，不扫描、不解散、不改写
  已存在的来源关系（含来源角色开放睡眠）。
- `sleep` 进组后，source-role 开睡不计入 `hasOpenSleep`，不是醒来快捷目标。
- 不自动闭合、不改写另一条 SleepStart，不把 WakeObservation 改绑到展示版。

## Considered Options

- **自动 tombstone 落选：** 替家庭删事实；拒绝（ADR-0021 / ADR-0018 已否）。
- **自动合并备注/照片进胜者：** 不可逆篡改 provenance；拒绝。
- **新 reason 枚举：** 旧 APK Room fail-closed；拒绝。用 `mutation_id` 前缀 + 加性
  `auto_aligned`。
- **本机抢写 resolve-group：** 与服务器自动收口双写；拒绝。对齐以 pull 关系为准。

### 5. 客户端本地存储与降级兼容（0.4.8 修订，评审 P2-5）

0.4.7 的 `applyPullSummary` 对本地 `pull_summary` 行只接受
`pull-$relationId:$成员集指纹` 与 legacy `pull-$relationId`，其余 fail-closed。因此：

- 客户端 **从不** 把 `auto-near-neighbor:` 写进本地 `pull_summary` 行的
  `mutation_id`；完整成员闭合证明（指纹）不被自动标记覆盖。自动属性改存
  `causal_transport_journal` 独立命名空间 `source-relation-auto:$relationId`，由
  `SourceRelationDao` 在与关系写入/清理相同的 `@Transaction` 内拥有，不新增表列，
  也不伪造成 `owner_group_resolve` reason 或塞进 `createdBy` 等字段。
- 自动徽章由 DAO 级投影/观察（`listAutoAlignedDisplayClientUuids` /
  `observeAutoAlignedDisplayClientUuids`）提供，调用方不再自行解析 mutation 前缀。
  非 `pull_summary` 行上的真实 canonical auto `mutation_id`（服务端 id）仍视为自动。
  展示成员行已被新组件吞掉的旧行投影为空，不产生孤儿徽章；手动 declare/resolve
  覆盖的自动 journal 随同一事务清理。
- 正常新写从第一条 source-only summary 起就保持 0.4.7 可消费格式。
- 存量修复 `SourceRelationDao.repairLegacyAutoAlignedSummaries()`（幂等 `@Transaction`，
  非迁移）：把旧 0.4.8 `pull_summary` auto 行的自动属性挪进 journal；完整关系按实际
  成员恢复指纹；未收到 display 的半边无法得知未知 peer 全集，不捏造 hash，回到已支持
  的 legacy `pull-$relationId`，由下一条真实 summary 冻结完整集合。成员、记录与媒体
  引用不动。
- **降级承诺边界：** 已被旧 0.4.8 写坏的库必须先升级到带修复的 0.4.8 并让其启动恢复
  完成一次 repair 之后，才承诺可回装 0.4.7；对坏库直接装旧 APK **不会** 自修复，
  下一次 pull 仍会 fail-closed。

## Consequences

- CONTEXT / data-model §5.2 / wire §12 必须写明：自动收口、全类型、同作者连边、名称分片。
- 未确认组只存在于离线或尚未 pull 到关系的窗口；一旦关系到达，主路径只计展示版。
- 开放睡眠测试：source-role 开睡不再挡住新开睡、不接快捷醒来。
