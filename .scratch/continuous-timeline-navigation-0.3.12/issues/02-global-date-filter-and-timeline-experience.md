# 02 — 接入全局日期、筛选和完整页面体验

**What to build:** 家长通过时间轴、TopBar 或日历浏览时，整个应用共享同一个选中日；手动历史视窗、类型筛选和空态按既定生命周期稳定保留，空白时间段也始终提供可操作的连续轨道，并且 warm/journal 两种视觉模板具有相同行为。

**Blocked by:** 01 — 完成连续时间轴状态机与直接拖动。

**Status:** complete

- [x] 时间轴释放产生的日期 effect 更新 App 全局日期，并同步驱动 TopBar、记录页日汇总/明细以及随后进入的 Summary、Growth、Calendar 日期锚点
- [x] TopBar 和日历的外部日期选择通过同一交互路径重建时间轴，不产生反馈循环、重复换日或 Log 私有日期漂移；今天的下一日操作继续禁用
- [x] 手动浏览视窗和实时/浏览模式在 tab 切换、配置变化和短暂后台期间随 ViewModel 生命周期保留；冷重建时今天恢复实时，历史日恢复完整自然日，不持久化任意像素偏移
- [x] 同一宝宝跨日保留当前类型筛选和高亮；目标日无匹配事实时保留筛选并显示分类空态；切换宝宝才清除，A2 空类型首选门禁和重复点击清除继续成立
- [x] 即使三日范围没有记录也显示完整空轨道、时间刻度、日期边界和手势命中区；现在 instant 位于视窗内时才显示现在线
- [x] 标记点击不会被误判为拖动，横向时间轴不会抢占纵向列表或下拉刷新；warm/journal 的导航、筛选和绘制语义一致，TopBar 继续作为 TalkBack/键盘日期导航替代入口
- [x] Root、feature JVM/Robolectric 与 Compose/device 验收覆盖全局日期传播、外部日期回流、生命周期、空轨道、筛选、手势仲裁、未来夹紧和双模板一致性

## Evidence

- `TimelineInteractionTest` and `TimelineExperienceProjectionTest` cover the retained absolute
  viewport, external-day flow, live/browsing restoration, future clamp, filter lifecycle, DST
  projection and category empty state through the feature seam.
- `TimelineExperienceDeviceTest` passed all 3 warm/journal, empty-rail, direct-drag/tap and vertical
  arbitration cases on `lezi_api35` API 35 emulator on 2026-08-08.
- `TimelineDstDeviceTest` passed on the same emulator after the rendering adapter switched to raw
  cumulative pan input.
- `./gradlew test lintDebug :app:assembleDebug` passed on 2026-08-08; the built debug APK installed
  over the emulator and cold-launched `MainActivity` successfully.
