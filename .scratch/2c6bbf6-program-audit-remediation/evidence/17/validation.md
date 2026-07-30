# Ticket 17 — 统一用户可见记录类型标签证据

日期：2026-07-30（Asia/Shanghai）

## 行为结果

- `RecordType.businessLabel()` 是内建类型唯一中文权威，Kotlin `when` 穷举保证新增 enum 未补标签时编译失败。
- `RecordType.presentation.label` 统一委托该权威；列表/详情继续用 `Record.displayLabel()`，搜索、导出、护理聚合和 Widget 不再维护重复标签映射。
- 自定义 Record 的列表、搜索、导出和 Widget 均读取记录 payload 中保存的标题快照，定义改名或 tombstone 不回写历史名称。
- 持久化/wire key 的未知值显示“未知记录”，不会把 enum 名、storage key 或英文调试标识直接暴露给用户。

## Widget 兼容边界

旧 Widget 快照的 `lastLabel` 以内部 key 开头，新快照保存已经规范化的业务/自定义标签。快照新增向后兼容的 `lastLabelIsCanonical` 标志：

- 新刷新写 `true`，展示时原样保留自定义快照；即使合法自定义标题恰为 `bath` 或蛇形英文，也不会被误翻译。
- 旧 JSON 缺字段时按 `false` 解码，只在该迁移模式下把已知 key 翻译为权威标签、未知内部形态降级为“未知记录”。
- codec round-trip、旧 JSON 默认值、旧 key 迁移、未知 key、新中文快照和英文形态自定义快照均有单测。

## Test receipts

首次定向门禁：

```text
./gradlew :core:model:test \
  :core:ui:testDebugUnitTest \
  :domain:testDebugUnitTest \
  :feature:widget:testDebugUnitTest
```

结果：`BUILD SUCCESSFUL in 1s`；120 tasks（14 executed，106 up-to-date）。加入显式 Widget 快照版本标志后重跑同命令，结果 `BUILD SUCCESSFUL in 2s`；120 tasks（10 executed，110 up-to-date）。

覆盖包括：逐 enum 标签完整性与无 key 泄漏、presentation 一致性、搜索首个 canonical term、内建/自定义导出、护理聚合自定义快照、Widget 快捷标签、旧/新快照迁移和未知 key 降级。

Android 模块 lint：

```text
./gradlew :core:ui:lintDebug :domain:lintDebug :feature:widget:lintDebug
```

结果：`BUILD SUCCESSFUL in 4s`；304 tasks（18 executed，286 up-to-date）。跨模块应用编译执行 `./gradlew :app:assembleDebug`，结果 `BUILD SUCCESSFUL in 5s`；408 tasks（17 executed，391 up-to-date）。该次组装同时编译了当前工作区已接入但尚未提交的分钟时钟代码；本票精确暂存不包含该 WIP。

## Documentation and scope

- `docs/prd/README.md` 的记录类型表是唯一产品术语表，并明确所有消费入口、自定义历史快照及未知类型规则；当前正文统一使用“母乳瓶喂”。搜索别名仍保留“喂挤出乳”，仅用于命中旧称，不是显示权威。
- 本票不改变 storage/wire key、Room schema、同步合同、历史数据、版本或发布产物。
