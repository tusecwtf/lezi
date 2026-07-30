# 17 — 统一用户可见记录类型标签

**What to build:** 建立一个面向用户的记录类型标签权威入口，供列表、搜索、导出、组件和辅助功能共同使用；自定义项目继续显示保存时快照。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** S–M

## Acceptance criteria

- [x] 所有内建 Record 类型在权威标签映射中穷举覆盖，新增类型缺少标签时由测试明确失败。
- [x] 记录列表、搜索结果、详情、分享/导出、桌面组件及内容描述使用同一用户可见标签。
- [x] 用户可见输出不泄漏内部枚举名、存储 key 或英文调试标识。
- [x] 自定义项目使用记录时保存的名称/图标快照；定义改名或 tombstone 不改写历史显示。
- [x] 未知前向兼容类型有中性、可本地化的降级标签，而不是崩溃或空字符串。
- [x] 本地化与导出测试逐类对照各消费入口，并覆盖自定义与未知类型。

## Validation

运行标签、搜索、导出、Widget 与 UI 测试，以及应用编译和静态检查。

## Documentation Gate

产品术语表只保留一组记录类型名称，并注明历史自定义快照规则。

## Completion receipts

- `RecordType.businessLabel()` 保持穷举权威；`RecordType.presentation`、搜索、导出、聚合和 Widget 均委托该权威或 `Record.displayLabel()`，自定义记录保留创建时标题快照。
- 持久化/wire key 的未知值经 `recordTypeLabel` 降级为“未知记录”。Widget 快照显式标记新标签已规范化，只有无标记旧快照才迁移 key，因而名为 `bath` 等合法自定义标题不会被误翻译。
- 四模块单元测试、lint 与应用组装命令及边界见 [`../evidence/17/validation.md`](../evidence/17/validation.md)。
