# Ticket 16 — 根发布回执与真实文案证据

日期：2026-07-30（Asia/Shanghai）

## 行为结果

- Record/CarePlan 的根发布证据持久化在本机 `familyPublishedUpdatedAt`，与照片
  `remoteUri` 完全独立。
- 只有正数回执等于本地 `updatedAt` 才是当前已发布；较小正数表示家庭仍看到上一完整
  版本；缺失、零/负值与未来回执均按从未发布 fail closed。
- 回执严格在 atomic commit 成功后写入。媒体已上传但 commit 失败、取消或超时不产生根
  回执；Outbox 保留并以同一 bundle 重试。过期 commit 可保存上一版证据，但不会清除较新
  本地修订的 `syncDirty`；未来回执被 DAO 拒绝。
- pull/apply 的远端根已经是服务端提交版本，因此以远端 `updatedAt` 写入当前回执。同版
  重放可补齐回执；跨家庭边界或本机同步回执整体失效时清空旧回执。
- UI 不再按根逐条读取媒体回执。首次发布显示“仅本机 · 等待家庭同步”，并说明其他成员
  暂不可见；后续本地更新说明其他成员仍看到上一完整版本。CarePlan 同时说明远端不会提醒。
- Record/CarePlan 与 0–3 张照片仍只经既有 atomic bundle 发布；根回执不改变服务端
  commit 前不可见与接收端完整包一次落库的门闩。

## TDD

各 public seam 均先观察 RED，再补最小实现：

- `RootPublicationStateTest`：分类器未定义导致编译失败；后续零/负同值边界先断言失败。
- `RealSyncPortTest` 根回执组：实体字段/DAO seam 未定义导致编译失败；随后覆盖零照片首发、
  commit 失败重试、重建后的持久值、媒体已上传但根 commit 失败、并发编辑后的 stale
  receipt、未来 receipt 拒绝、取消/超时不提前落库，以及零照片 CarePlan 更新。
- pull/apply 测试先因回执为空失败，再证明 Record/CarePlan 收到远端已提交根后为 current。
- UI 文案测试先因旧 `hasPriorFamilyRevision` 签名编译失败，再证明首次/上一版/当前版分支。
- domain 映射与跨家庭回执清理分别先断言失败，再通过。

## Android 验证

下列整组命令成功，共 443 个 Gradle task（65 executed，378 up-to-date）：

```text
./gradlew :core:model:test :domain:testDebugUnitTest :sync:testDebugUnitTest \
  :feature:log:testDebugUnitTest :core:database:compileDebugAndroidTestKotlin \
  :app:assembleDebug
```

覆盖 core model、领域映射、Outbox/atomic bundle、失败重试/重放、pull/apply、HTTP client
合同与 UI 文案；`FreshDatabaseTest` 已同步为 schema 24 并完成 instrumentation 编译。

静态检查成功，共 704 个 Gradle task（192 executed，1 from cache，511 up-to-date）：

```text
./gradlew :app:lintDebug
```

导出的 `core/database/schemas/.../24.json` 通过 JSON 解析，且相对 schema 23 只增加
Record/CarePlan 的可空根回执列并更新 identity hash/version。

## Current server contract 验证

```text
cd tools/lezi-sync
cargo test --locked
```

结果：36 个 Rust 单元测试与 85 个 API 集成测试通过；覆盖零照片/含照片 atomic bundle、
commit 前不可见、编辑保留上一完整版本、commit 幂等重试、过期根拒绝、pull 完整包共组与
current schema 重启。Android `:sync:testDebugUnitTest` 同时覆盖 current HTTP bundle
stage/upload/commit 响应和客户端回执落库时序。

## 明确边界

- 按并行任务约束未占用模拟器，未运行 `connectedDebugAndroidTest`，因此不声称本次执行了
  Room 设备运行时或页面设备 smoke；`FreshDatabaseTest` 仅完成编译。
- 未改 NAS wire/schema，也未运行物理 NAS。服务端 current-contract 证据来自仓库内 Rust
  API 集成测试。
- 本票不改应用版本号；Program 收口后的 0.3.0 版本升级由主任务统一处理。
