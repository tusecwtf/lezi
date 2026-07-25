# 05 — Care Record Payload → Composer 全写路径

**Parent:** [../spec.md](../spec.md)

**What to build:** 将所有当前 RecordType 的新增/编辑编码与验证迁移到 Care Record Payload module，让 `QuickRecordDraft` / `RecordComposer` 继续拥有草稿生命周期，但不再拥有完整 JSON schema。

**Blocked by:** [04-care-payload-compatibility-tracer.md](./04-care-payload-compatibility-tracer.md)

**Status:** done

**Dependency category:** in-process；持久化写入使用现有 local adapter

## Seam and deletion test

- Composer caller 只提交记录含义与草稿结果，不学习字段 JSON 形状。
- `RecordComposerSessionGate` 保持内部 seam，不因 payload 迁移暴露。
- 删除 payload module 时，各 RecordType 编码/验证会回流到 QuickRecordDraft。

## Acceptance criteria

- [x] Formula、Nursing、Pumped Feed、Sleep、Diaper、Temperature、Growth、Memo/Diary、Food/Vaccine 等当前类型均使用新 module 编码
- [x] kg↔g、cm、ml、分钟、摄氏度、布尔与字符串字段的现有默认/缺失行为保持
- [x] 新增、编辑、醒来确认和已完成睡眠编辑通过原 RecordComposer 单一路径
- [x] 编辑旧记录时未知字段继续保留；修改已知字段只改变对应记录含义
- [x] QuickRecordDraft 不再维护私有顶层 JSON parser、patcher 或任意 key schema
- [x] 草稿校验、确认锁定、duration preview 与 session gate 测试保持
- [x] 搜索后编辑、删除、计时器跳转与提醒安排无行为回归

## Validation

- [x] Targeted：feature:log QuickRecordDraft/RecordComposer + payload interface
- [x] Compile：feature:log、domain 与 app debug 编译
- [x] Static：`git diff --check`
- [x] Smoke：新增、编辑、删除及旧记录未知字段往返

## Out of scope

- Summary/Timeline reader 迁移（Ticket 06）
- Growth 生命周期深化（Tickets 07–09）
- Search/Export reader 与全局 parser cleanup（Ticket 10）

## Comments

- `QuickRecordDraft` 的全部 `RecordType` 写路径改为 typed payload；私有 JSON parser/patcher 删除，`RecordComposer` New/Edit 与 session gate 保持单一路径。
- 验证：payload + QuickRecordDraft/RecordComposer 自动化往返、受影响编译及 `./gradlew test assembleDebug -q`（exit 0）；设备 smoke 未运行。
- Documentation: N/A；写入可见语义与 wire format 保持。
