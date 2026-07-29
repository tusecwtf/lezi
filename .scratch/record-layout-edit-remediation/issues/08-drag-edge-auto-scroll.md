# 08 — 目录拖动边缘自动滚动

**What to build:** 让目录项目与分类标题在长目录中拖到可滚动区域上下边缘时持续、可控地滚动，从而完成跨屏类内排序和类别排序；固定的本机已删除分区与 Dock 仍保持稳定、优先且不被滚动误触发。

**Blocked by:** 04 — 分类标题拖动与可靠类别排序

**Status:** complete

## Acceptance criteria

- [x] 只有目录项目和分类标题作为拖动来源时，主目录上下边缘才可触发自动滚动；快捷槽和本机已删除来源永远不触发。
- [x] 指针进入上/下边缘带后按距边缘程度渐进滚动，离开边缘带、到达内容边界、取消或放下后立即停止，不残留后台滚动任务。
- [x] 指针进入本机已删除分区、4 个快捷槽、固定“更多”或 Dock 内部区域时，当前目标优先并立即停止自动滚动。
- [x] 自动滚动过程中只保留当前可见节点的边界；滚出组合树的旧矩形及时注销，高亮与最终 reducer intent 始终一致。
- [x] 同类别项目可以从当前视口跨屏移动到首、中、末落点；分类标题可以跨屏移动到首、中、末类别位置。
- [x] 跨类别项目仍为 no-op，自动滚动不会把滚动本身解释成投放、清槽、隐藏或恢复。
- [x] 小屏、字体放大和长目录下保持手指跟随与可预测速度；普通纵向浏览、系统返回手势和固定 Dock 不受影响。
- [x] 纯滚动控制与 Compose 测试覆盖上下边缘、离开边缘、内容边界、目标优先、取消、节点注销及非授权来源负例。

## Validation

- 运行目录排序、类别排序、当前目标解析、滚动控制和 Compose 拖动定向测试。
- 运行 `:core:ui:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、相关 connected/Compose 测试、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 在小屏模拟器构造跨屏目录，分别完成项目与类别的顶部/底部跨屏排序，并验证进入删除区或 Dock 立即停止。
- 运行 `git diff --check`，确认没有依赖固定像素拖距或阻塞式循环。

## Documentation Gate

在布局编辑设计与 UI PRD 中记录自动滚动的授权来源、边缘启停、固定目标优先级和跨屏排序语义；不得暗示快捷槽或已删除来源会带动目录滚动。

## Evidence

- 纯速度策略、Compose 小屏长目录、Drop Matrix、类别排序、删除区、JVM/lint/assemble 的命令与结果见 [`evidence/08`](../evidence/08/validation.md)。
- 生产循环只由目录项目/分类标题的有效 drag token 驱动；固定目标、边界、取消、放下或坐标失效均 fail-closed 停止。
- 滚动/旋转后的触摸点统一使用 `LayoutCoordinates.localToWindow`，既有滚动后目录项 → 槽位精确投放回归保持 GREEN。
