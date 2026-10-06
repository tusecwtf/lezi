---
status: superseded by ADR-0008
---

# 停止新建泛化记录但保留历史兼容

“备注”“其他”和泛化的“自定义”入口与日记、通用备注字段及具体自定义项目重复，因此不再出现在快捷栏或“更多”中。历史 `memo`、`other`、`custom` 记录仍可读取和编辑，不做破坏性迁移；已有具体自定义项目直接作为独立记录按钮展示，引用被移除泛化入口的常用槽位变为空槽。

## 具体记录项目身份

新建目录、Composer、保存回显与后续快捷槽位使用稳定的 `RecordItemIdentity`：

| 形态 | 目录键 | 存储 `RecordType` |
|------|--------|-------------------|
| 内建项目 | `RecordType.key`（如 `pee`） | 对应类型 |
| 具体自定义项目 | `custom:<localId>` | `custom`，payload 含 `custom_item_id` 与名称/图标快照 |

- 裸键 `memo` / `other` / `custom` 为**失效的新建引用**（`RecordItemIdentity.isInvalidNewEntryReference` / `isRetiredGenericCatalogKey`），供快捷槽位显示为空而不迁移历史记录。
- Composer `RecordComposerRequest.New` 对自定义项目携带 `customItemId`，禁止“打开泛化 custom 再默认第一项”的路径。
