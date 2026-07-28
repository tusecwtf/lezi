# 记录页就地布局编辑 · 票索引

Parent spec: [spec.md](./spec.md)

```text
01 ──┬── 02 ──┐
     ├── 03 ──┼── 05 ──┬── 06
     └── 04 ──┘        └── 07
```

Frontier: **none** (all complete)

| # | Ticket | Blocked by | Status |
|---|--------|------------|--------|
| 01 | [常用槽指派 / 唯一换位 / 清空](./issues/01-quick-slot-layout-policy.md) | — | complete |
| 02 | [本机已删除 ↔ 目录末尾恢复 + 清槽](./issues/02-local-trash-and-restore.md) | 01 | complete |
| 03 | [类内序 + 类别序](./issues/03-category-and-item-order.md) | 01 | complete |
| 04 | [固定坞序 + 空槽无操作](./issues/04-fixed-dock-order-empty-noop.md) | 01 | complete |
| 05 | [布局编辑态画布 + 拖放接线](./issues/05-layout-edit-mode-canvas.md) | 02, 03, 04 | complete |
| 06 | [自定义管理 + 日常更多去控件](./issues/06-custom-manage-and-clean-more.md) | 05 | complete |
| 07 | [菜单记录设置 + 拆除布局 hub](./issues/07-record-settings-menu.md) | 05 | complete |
