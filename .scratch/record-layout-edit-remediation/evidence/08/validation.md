# Ticket 08 validation

## Current disposition

目录项目与分类标题现在可以在长目录边缘持续、渐进地驱动主目录滚动；固定删除区与 Dock 保持独立和优先。实现、专项 API 35 connected 回归、JVM、androidTest 编译、lint 与 debug APK 组装均已通过。

## Test-first receipts

1. `LayoutEdgeAutoScrollPolicyTest` 首个 RED 因生产策略不存在而以 `Unresolved reference 'LayoutEdgeAutoScrollPolicy'` 编译失败；最小实现先建立上下边缘线性、封顶速度。
2. 非授权来源 RED 显示槽位/已删除来源仍会返回速度；GREEN 后只有目录项目与分类标题获准滚动。
3. 固定目标 RED 显示删除区、槽、锁定“更多”和 Dock 间隙仍会滚动；GREEN 后这些命中均优先返回零，目录项、分类标题和目录空白仍可继续滚动。
4. 小视口 RED 暴露上下边缘带重叠；GREEN 后有效边缘带限制为视口高度的一半。内容边界和指针离开视口也返回零。
5. 24 项、320×640、字体尺度 1.5 的 Compose 用例在无生产循环时无法看到末项；接入逐帧 `ScrollState.scrollBy` 后可以持续到末项，并在取消后保持纵向位置稳定且不产生误 intent。
6. 新滚动首先让既有“滚动后目录项 → 槽”设备回归变 RED。原因是旧拖动代码用 `positionInWindow + local offset`，在滚动/旋转后不是合法坐标变换；改用 `LayoutCoordinates.localToWindow` 并在坐标 detach 时取消后，精确 `AssignToSlot` 恢复 GREEN。

没有添加测试专用滚动回调、第二套 reducer 或固定帧数完成条件。临时诊断日志在定位测试坐标问题后已全部删除。

## Pure policy and session coverage

- `LayoutEdgeAutoScrollPolicyTest` 覆盖上下边缘单调/封顶速度、中间与视口外停止、内容首尾边界、小视口不重叠、两类授权来源、两类非授权来源和四类固定目标抢占。
- `LayoutDragSessionTest` 继续覆盖节点注销/移动后旧矩形失效、跨类别 no-op、类别与目录拖动域隔离、取消幂等和精确类别/项目 intent。
- `LayoutEdgeAutoScrollDeviceTest` 使用生产 `LayoutEditCanvas` 与真实长按，在小屏长目录中从首项滚到末项中心进入可用目录视口；取消后推进 500ms，目标纵向中心保持在 2px jiggle 包围盒容差内，且没有误提交。

## API 35 connected receipts

授权设备仅为 `emulator-5554`。最终专项命令：

```text
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.LayoutEdgeAutoScrollDeviceTest,\
com.lezi.babylog.feature.log.LayoutEditDropMatrixDeviceTest,\
com.lezi.babylog.feature.log.LayoutCategoryDragDeviceTest,\
com.lezi.babylog.feature.log.LayoutLocalDeletedDeviceTest
```

结果：`Starting 13 tests on lezi_api35(AVD) - 15`，`Finished 13 tests`，`BUILD SUCCESSFUL in 25s`。

这 13 项把长目录边缘滚动/取消与既有精确 Drop Matrix、类别首/中/末排序、本机删除区的固定布局和危险投放语义组合验证。另一次 focused edge + Drop Matrix 回归为 5/5 GREEN，包含滚动后 `bath` → 槽 0 的精确 `AssignToSlot`。

## Final gates

```text
./gradlew :core:ui:testDebugUnitTest \
  :feature:log:testDebugUnitTest \
  :feature:log:compileDebugAndroidTestKotlin \
  :feature:log:lintDebug \
  :app:assembleDebug
```

结果：`BUILD SUCCESSFUL in 25s`；591 tasks（102 executed，489 up-to-date）。lint HTML 写入 `feature/log/build/reports/lint-results-debug.html`。

## Verification boundary

- 上边缘、分类标题授权和内容首边界由同一纯策略覆盖；设备长目录用例实际执行下边缘持续滚动与取消稳定，类别设备套件验证精确类别排序 intent。没有把未单独执行的“实体手指顶部跨屏拖动”描述为设备 smoke。
- connected 只在 API 35 `emulator-5554` 执行；没有声称实体机、系统返回边缘手势或 spoken TalkBack 人工 smoke。
- 本票未修改版本、同步、数据库、家庭布局契约或护理 Record 写入路径；布局仍是 ADR-0006 的设备本地状态。
