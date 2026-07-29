# Ticket 22 — 批量时间轴元数据证据

日期：2026-07-30（Asia/Shanghai）

## 行为结果

- `TimelineWindowDao` 以一个 invalidation query 驱动固定三次批量读取：Record roots、
  CarePlan roots、相关活跃日志媒体。三次读取由同一 Room transaction 包裹，不接受每行
  ID 列表，也不按媒体或成员逐行查询。
- `TimelineWindowRepository` 发布一个不可变 revision：Record/CarePlan 根发布态、媒体
  本机齐备度、作者称呼、家庭/成员受众和 edit/delete/fulfill/skip 能力都带相同 revision。
  数据更新后只发布完整新 snapshot，不暴露新根 + 旧媒体/ACL 的中间组合。
- 计划能力精确复用既有 creator membership、owner 和 creator acknowledgement ACL；
  Record 保持原有可编辑/删除，计划履行仍允许家庭成员完成，跳过继续要求管理权。UI
  元数据缺失时不开放动作，没有因批量化扩权。
- Log 页面移除独立 Record/Plan/OpenSleep/member 组合和成员手工缓存，改为单一 timeline
  snapshot；下拉同步后只触发可取消的成员快照 refresh。
- 切换 baby、family 或新窗口请求会取消旧读取；更新的 refresh 取消旧成员结果；离开页面
  立即取消进行中装配，旧结果均不能覆盖新结果。

## TDD 与等价性

- 1 根与 500 根（每根 0–3 张照片）均断言 Room query count 为 4；家庭成员读取均为 1。
- invalidation 前后 snapshot 证明根状态、照片路径与 ACL 同 revision 原子替换。
- baby/family/refresh/leave 四个取消 tracer 均用挂起旧查询证明 cancellation 被观察，且
  只有新请求结果可发布。
- feature copy 回归覆盖零照片/含照片，以及 Ticket 16 的首次发布“等待家庭同步”、已有
  上一版“等待更新同步”和当前版无琥珀文案。

## 验证

定向 tracer 与 copy 测试通过：

```text
./gradlew :domain:testDebugUnitTest --tests com.lezi.babylog.domain.TimelineWindowRepositoryTest
./gradlew :feature:log:testDebugUnitTest --tests com.lezi.babylog.feature.log.TimelineUploaderLabelTest
./gradlew :feature:log:compileDebugKotlin
```

最终整组门禁成功，共 783 个 Gradle task（107 executed，676 up-to-date）：

```text
./gradlew :core:database:kspDebugKotlin \
  :core:database:compileDebugAndroidTestKotlin \
  :domain:testDebugUnitTest \
  :feature:log:testDebugUnitTest \
  :app:assembleDebug \
  :app:lintDebug
```

结果为 `BUILD SUCCESSFUL in 25s`；lint HTML 写入
`app/build/reports/lint-results-debug.html`。`LeziDatabase` 仍声明 schema 24，未生成 schema
变更或 migration。

## 明确边界

- 按并行任务约束不占用模拟器，不运行 `connectedDebugAndroidTest`，因此不声称设备页面
  smoke 或 Room 设备运行时验证。
- 本票不改 NAS wire/server，不改 Room schema（仍为 v24），不新增 migration。
- 本票不改应用版本号；Program 收口后的 0.3.0 版本升级由主任务统一处理。
