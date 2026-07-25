# 10 — Care Record Payload 剩余 readers 与 parser cleanup

**Parent:** [../spec.md](../spec.md)

**What to build:** 完成 Care Record Payload epic：迁移 Search、RecordPresentation、Export 等剩余 caller，确认 Growth/Care Summary/Timeline 已无 raw key reader，然后删除全仓重复 payload Regex/helper；原始 JSON 只留在明确 adapter implementation。

**Blocked by:** [06-care-payload-summary-timeline-readers.md](./06-care-payload-summary-timeline-readers.md), [09-growth-caller-migration.md](./09-growth-caller-migration.md)

**Status:** done

**Dependency category:** in-process；Room/outbox/sync/export wire 为 adapter implementation

## Seam and deletion test

- 所有业务 caller 通过 Care Record Payload interface 获得记录含义。
- raw JSON 仅存在于持久化、同步或格式化输出 adapter，不形成可选业务路径。
- 删除 payload module 时 schema 知识会回流到六类以上 caller；删除旧 Regex 后不应损失能力。

## Acceptance criteria

- [x] Search 通过记录含义匹配现有中文/数值/类型查询，不按任意 key 扫描业务字段
- [x] RecordPresentation 的所有 RecordType 完成迁移，Ticket 04 的三类 tracer 扩展到全类型
- [x] Export 使用同一记录含义生成现有文本/PDF 内容，输出格式保持
- [x] Care Summary、Timeline、Growth、Composer production code 均无 raw payload key reader
- [x] `payloadInt` / `payloadDouble` / `payloadBool`、重复 Regex 和失去 caller 的 parser 测试被删除
- [x] Room/outbox/sync adapter 的 raw JSON 使用有明确位置；未知字段和旧 schema 往返仍通过
- [x] 全仓搜索证明不存在第二套业务 payload interpretation

## Validation

- [x] Targeted：Search、Presentation、Export、payload compatibility、sync fake
- [x] Full Payload closure：`./gradlew test assembleDebug`
- [x] Static：`git diff --check`
- [x] Compatibility smoke：旧记录编辑后仍可搜索、汇总、展示与导出

## Out of scope

- Room schema 或同步协议升级
- 新字段、新 RecordType、新导出格式
- Record Time 迁移

## Comments

- Search/Presentation/Export/Care Summary/Timeline/Growth/Composer 均使用 typed business facts；旧 payload helpers/Regex 删除。raw JSON 白名单仅留 codec 与 Room/outbox/sync/格式化 adapter。
- 验证：typed visible search（含数值/中文）、Presentation/Export/payload/sync fake 自动化及 `./gradlew test assembleDebug -q`（exit 0）；`git diff --check` 通过。
- Documentation: N/A；旧 schema/未知字段兼容与导出格式保持。
