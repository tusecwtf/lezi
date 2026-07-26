# 03 — typed 提醒收尾持久化

**What to build:** 用 `PendingReminderCleanupStore` 深模块封装 records-clear 提醒收尾的持久状态。调用者只接触 typed operation、去重后的 `Set<Long>` 与 `familyServerRetained`；不得再解析/拼接逗号字符串，也不得在坏值时静默丢 ID 后删除 pending 行。

**Blocked by:** None — completed

**Status:** completed

**Size:** M
**Review finding:** Standards 6
**Seam:** `PendingReminderCleanupStore` interface；in-memory Room 为测试 adapter

## Initial file surface

- `core/database/.../V2Entities.kt`
- `core/database/.../LeziDatabase.kt`
- `core/database/.../DatabaseModule.kt`
- 新增 `core/database/.../PendingReminderCleanupStore.kt`
- core/database migration / store tests
- `domain/.../CareLog.kt` 仅切换持久化调用面

## Confirmed storage direction

- 序列化格式是 module implementation 细节；优先使用 typed rows 或 Kotlin serialization 的严格列表格式。
- 若需要 Room schema bump，提供显式 migration 并保留已有 pending 数据。
- 对 legacy CSV 只允许全量严格解析：任一非空 token 非法时返回可诊断失败并保留 pending，不允许 `mapNotNull` 后继续当作成功。

## Acceptance criteria

- [x] domain/feature 调用方不再知道 Room entity、operation 字符串常量或 ID 序列化格式。
- [x] store 的 `load/upsert/delete`（或等价小 interface）使用 typed snapshot。
- [x] 合并旧/新 ID 时去重、稳定排序；`familyServerRetained` 只可从 false 升为 true。
- [x] 空集合行为明确且有测试；不得留下永远无法完成的空 pending。
- [x] legacy 合法数据可迁移/读取；损坏数据 fail closed、保留 pending、不得执行 delete-as-success。
- [x] migration 测试覆盖旧库 pending 行，不得依赖 destructive migration。
- [x] 新测试通过后删除 CareLog 中旧 encode/decode 私有函数及其实现细节测试。

## Validation

- `./gradlew :core:database:testDebugUnitTest :domain:testDebugUnitTest --no-daemon`
- 如仓库已有 Room migration instrumentation gate，运行对应 connected/migration 测试；设备不可用时明确报告未运行。
- `git diff --check`

## Documentation Gate

- Room schema 变化时同票更新 `docs/prd/data-model.md` 与导出的 schema；若只替换内部 codec 且 schema 不变，记录 N/A。

## Out of scope

- 改变闹钟调度产品行为。
- 在此票搬走完整 clear orchestration（留给 04）。

## Comments

- 来源：固定范围审查 Standards finding 6。
- 2026-07-27 红灯：批准 seam 的精确测试先在 `compileDebugUnitTestKotlin` 因 typed store/operation/snapshot/corruption 类型尚不存在而失败。
- 2026-07-27 绿灯：合法 legacy CSV 严格读取，合并后 ID 去重稳定排序，retained 单向提升；坏 token 抛出可诊断异常且不取消、不删除 pending；空集合完成后显式删除。
- 验证：`:core:database:testDebugUnitTest :domain:testDebugUnitTest` BUILD SUCCESSFUL；`:core:database:connectedDebugAndroidTest` 在 emulator-5554 与 emulator-5556 各 15 项通过；`git diff --check` 通过。
- Documentation Gate：Room 表结构与 schema version 未变化，只把既有 codec/DAO 封装进 typed adapter，因此 `docs/prd/data-model.md` 与导出 schema 均 N/A；v8→v17 migration 测试证明 legacy pending 行保留。
