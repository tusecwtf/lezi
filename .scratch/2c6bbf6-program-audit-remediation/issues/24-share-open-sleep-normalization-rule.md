# 24 — 共享开放睡眠修复决策

**What to build:** 把“同一宝宝存在多个开放睡眠时保留哪一条、如何关闭其余条目”的决策提炼为纯、确定性的共享规则，由本地维护与同步修复共同调用。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 共享规则只接收候选睡眠和显式时钟/排序输入，返回保留项与待关闭项，不直接读写数据库或系统时间。
- [x] 候选顺序变化时结果不变；相同时间戳有稳定 tie-break，不依赖集合迭代顺序。
- [x] 本地新增/编辑路径与远端 pull 修复路径使用同一决策，不能各自维护近似算法。
- [x] 本地适配器保留本地事务、时间来源与 dirty/outbox 语义；同步适配器保留远端作者、时间和不回推修复的既有契约。
- [x] 零条、一条、多条开放睡眠、跨日、未来异常时间和相同时间戳均有确定结果且不产生负区间。
- [x] 回归测试分别证明纯规则、本地适配和同步适配输出，使用注入时钟而非真实当前时间。

## Validation evidence

- RED：纯规则测试先因 `OpenSleepNormalization` / `normalizeOpenSleeps` 不存在而编译失败；
  本地适配器随后因 `CareLog` 尚未接收注入时钟而编译失败；同步相同时间测试在旧的 local-id
  tie-break 下行为失败。
- GREEN：`OpenSleepNormalizationTest` 覆盖 0/1/3 条、输入排列、跨日、相同时间、未来时刻和
  `Long.MAX_VALUE` 饱和；`CareLogTest` 与 `RealSyncPortTest` 分别证明本地事务适配和 pull
  适配都按 UUID 稳定裁决并使用注入时钟。
- 完整门禁与文件级证据见 `../evidence/24/shared-open-sleep-normalization.md`。

## Validation

已运行共享规则、CareLog 与同步全量测试，并通过应用 debug 编译和 lint。

## Documentation Gate

在数据模型中写明“每个宝宝至多一条开放睡眠”的修复规则和本地/同步副作用差异。
