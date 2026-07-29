# 记录页布局编辑整改 · 票索引

Parent spec: [spec.md](./spec.md)
Ticket 04 validation commit: `0791eb73c621a9bd47414e478fecd91aebebcaf5`

```text
01 ──→ 03 ──┬──→ 04 ──→ 08 边缘滚动 ──────────────┐
            ├──→ 05 ──→ 09 一层撤销 ──────────────┤
            └──→ 10 动效与触觉 ───────────────────┤
02 ─────────┬──→ 04 ───────────────────────────────┤
            ├──→ 05 ───────────────────────────────┤
            └──→ 11 配置变更恢复 ──────────────────┤
04 + 05 ───────→ 06 ──┬──→ 12 首次引导 ───────────┤──→ 13 完整回归
                       └──→ P1/02 清理 ─────────────┘
```

Frontier: **07、09、10、11、12**

| # | Ticket | Blocked by | Status |
|---|--------|------------|--------|
| 01 | [延续“添加记录”的四列分类卡片视觉](./issues/01-align-editor-with-add-record-visuals.md) | — | complete |
| 02 | [原子持久化 DeviceLayoutSnapshot](./issues/02-atomic-device-layout-snapshot.md) | — | complete |
| 03 | [当前可见且互斥的拖放目标](./issues/03-exclusive-current-drop-targets.md) | 01 | complete |
| 04 | [分类标题拖动与可靠类别排序](./issues/04-drag-category-headings-durably.md) | 02, 03 | complete |
| 05 | [有界、可读的本机已删除分区](./issues/05-bounded-readable-local-deleted.md) | 02, 03 | complete |
| 06 | [无障碍布局动作与真实空槽文案](./issues/06-accessible-layout-actions-truthful-empty-slot.md) | 04, 05 | complete |
| 07 | [删除重复与旧布局表面（canonical P1/02）](../p1-redundancy-ui-debt/issues/02-layout-migration-residue-cleanup.md) | 06 | ready-for-agent |
| 08 | [目录拖动边缘自动滚动](./issues/08-drag-edge-auto-scroll.md) | 04 | complete |
| 09 | [高风险布局操作一层撤销](./issues/09-one-level-layout-undo.md) | 05 | ready-for-agent |
| 10 | [克制的编辑动效与限频触觉反馈](./issues/10-bounded-motion-and-haptics.md) | 03 | ready-for-agent |
| 11 | [配置变更保持布局编辑会话](./issues/11-preserve-editor-across-configuration.md) | 02 | ready-for-agent |
| 12 | [首次拖放引导与可重访帮助](./issues/12-first-use-drag-guidance.md) | 06 | ready-for-agent |
| 13 | [完整手势、重进与设备回归](./issues/13-full-layout-edit-regression.md) | P1/02, 08–12 | ready-for-agent |

## 执行说明

- 01 与 02 的初始文件表面分别偏 UI/设计和 DataStore/状态写入，可并行实施。
- 04 与 05 在逻辑依赖上可并行，但都会修改最终编辑画布。为避免 Compose 文件冲突，进入同一 frontier 后仍应**串行实施，一次只领取一票**。
- 07 只映射到现有 P1/02；布局旧入口、兼容包装器和重复 normalize 仅在那里清理，不在本 tracker 重复实施。
- 08、09、10、11 分别在自己的行为 blocker 完成后进入 frontier；它们触及同一编辑宿主时仍应串行领取，不用虚假依赖代替冲突协调。
- 12 与 P1/02 都由 06 解锁，可独立实施；13 必须等待 P1/02 与新增 08–12 全部完成。
- 每票从自己的直接 blocker 开始判断；不要重复列传递依赖。
- 历史草稿或未进入当前分支的实现不能将任何票标记完成；必须在当前分支重新实现并按票内 Validation 取证。
