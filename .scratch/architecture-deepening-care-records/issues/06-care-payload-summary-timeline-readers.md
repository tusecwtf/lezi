# 06 — Care Record Payload → Summary / Timeline readers

**Parent:** [../spec.md](../spec.md)

**What to build:** 让已经统一的 Care Summary module 与 Log Timeline/lane projection 通过 Care Record Payload interface 读取记录含义，不再直接解析 `amount_ml`、`left_min`、`is_nap` 等字段。

**Blocked by:** [03-care-summary-widget-cleanup.md](./03-care-summary-widget-cleanup.md), [05-care-payload-composer-writes.md](./05-care-payload-composer-writes.md)

**Status:** done

**Dependency category:** in-process

## Seam and deletion test

- Care Summary 与 Timeline 是两个真实 caller，共享同一 payload seam。
- 时间窗口/聚合仍属于 Care Summary；lane geometry 仍属于 Log implementation。
- 删除 payload module 时，字段 schema 会回流到两个 caller；删除旧 payload helper 不应影响它们。

## Acceptance criteria

- [x] Care Summary 通过 payload interface 得到喂养、排泄、体温与睡眠附加事实
- [x] Log Timeline/buildLanes 通过同一 interface 得到 nap、容量、左右分钟与 anomaly
- [x] Summary 的日/周/月数字与 Ticket 03 closure 基线一致
- [x] Timeline 的 lane、点位、摘要、异常标记与进行中睡眠行为保持
- [x] Care Summary 与 Log production code 不再出现任意字符串 key 读取 `payloadJson`
- [x] 原 `payloadInt` / `payloadDouble` / `payloadBool` 可暂留给未迁移 caller，但不再被本票 caller 使用
- [x] 新测试通过 payload 与 summary/timeline interface 断言结果，不测试 Regex 内部结构

## Validation

- [x] Targeted：payload、Care Summary、feature:log
- [x] Compile：domain、feature:summary、feature:log 与 app debug
- [x] Static：`git diff --check`

## Out of scope

- Growth reader（Ticket 09）
- Search/Presentation/Export 与最终 parser 删除（Ticket 10）
- 时间窗口或 lane 视觉重设计

## Comments

- Care Summary 与 Log Timeline 均读取 typed Milk/Nursing/Sleep/Diaper/Temperature payload；任意字符串 key reader 已从业务 caller 删除。
- 验证：payload/Care Summary/Log 定向测试、app debug 与 `./gradlew test assembleDebug -q`（exit 0）；`git diff --check` 通过。
- Documentation: N/A；Timeline 与 Summary 可见口径未改。
