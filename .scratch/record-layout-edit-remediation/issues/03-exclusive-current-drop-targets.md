# 03 — 当前可见且互斥的拖放目标

**What to build:** 建立一个只消费当前可见节点边界的权威拖放解析器，使高亮目标和最终布局 intent 完全一致，并修复 Dock、“更多”、删除来源及陈旧矩形的误命中。

**Blocked by:** 01 — 延续“添加记录”的四列分类卡片视觉

**Status:** ready-for-agent

## Acceptance criteria

- [ ] 拖放解析器显式区分整个 Dock、4 个槽、“更多”、目录当前项目和本机已删除区；任一 pointer 时刻最多产生一个当前目标。
- [ ] 从已绑定槽释放到“更多”或 Dock 内部间隙为 no-op，不清空源槽，也不把“更多”当成槽。
- [ ] 只有已绑定槽释放在整个 Dock 外、且不在本机已删除区时才产生 `ClearSlot`；目录项目拖空白处不清槽、不隐藏。
- [ ] 已删除来源永远不能直接绑定常用槽；其离开删除区只执行恢复，Dock 槽不得显示会绑定的虚假高亮。
- [ ] 目录项目到槽执行指派/替换，已绑定槽到另一槽执行换位，拖回自身为 no-op；视觉热区与 reducer 结果逐项一致。
- [ ] 节点离开组合树、被隐藏、移动分区或因滚动/重组不再可见时，其旧矩形立即注销；陈旧边界不能参与排序或投放。
- [ ] 同类别项目落点产生精确目标 index；测试断言具体排序结果，不接受仅验证 index 非负。
- [ ] 跨类别项目投放保持 no-op；重构命中模型不得引入跨领域分类移动。
- [ ] 纯逻辑和 Compose 测试覆盖“更多”、Dock 间隙、真正坞外、已删除到槽、节点注销、滚动重组、重叠矩形和精确类内排序。

## Validation

- 运行拖放解析、布局 reducer、目录排序和 Compose 节点生命周期定向测试。
- 运行 `:core:ui:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、相关 connected/Compose 测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 设备 smoke 至少覆盖槽→更多、槽→Dock 间隙、槽→真正坞外、删除项→槽以及滚动后再拖动。
- 运行 `git diff --check`，确认没有靠额外 magic padding 修补命中范围。

## Documentation Gate

更新交互矩阵，明确“更多/Dock 内间隙 no-op”“只有坞外 clear”“已删除来源只恢复”和“仅当前可见节点可命中”。
