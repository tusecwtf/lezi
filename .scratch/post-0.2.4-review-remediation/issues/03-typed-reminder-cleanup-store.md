# 03 — typed 提醒收尾持久化

**What to build:** 用 `PendingReminderCleanupStore` 深模块封装 records-clear 提醒收尾的持久状态。调用者只接触 typed operation、去重后的 `Set<Long>` 与 `familyServerRetained`；不得再解析/拼接逗号字符串，也不得在坏值时静默丢 ID 后删除 pending 行。

**Blocked by:** None — frontier；但开始前先落地当前 CareLog 身份 WIP，禁止覆盖未提交改动。

**Status:** ready-for-agent

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

- [ ] domain/feature 调用方不再知道 Room entity、operation 字符串常量或 ID 序列化格式。
- [ ] store 的 `load/upsert/delete`（或等价小 interface）使用 typed snapshot。
- [ ] 合并旧/新 ID 时去重、稳定排序；`familyServerRetained` 只可从 false 升为 true。
- [ ] 空集合行为明确且有测试；不得留下永远无法完成的空 pending。
- [ ] legacy 合法数据可迁移/读取；损坏数据 fail closed、保留 pending、不得执行 delete-as-success。
- [ ] migration 测试覆盖旧库 pending 行，不得依赖 destructive migration。
- [ ] 新测试通过后删除 CareLog 中旧 encode/decode 私有函数及其实现细节测试。

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

