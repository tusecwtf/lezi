# 04 — Care Record Payload 兼容读写 tracer

**Parent:** [../spec.md](../spec.md)

**What to build:** 建立 deep Care Record Payload module 的首条兼容 tracer：以 Formula、Nursing、Sleep 样本证明记录含义可以编码、解码、保留未知字段，并通过一个 Presentation caller 呈现；暂不迁移全部 RecordType。

**Blocked by:** None — initial frontier

**Status:** done

**Dependency category:** in-process；JSON 编解码为存储 adapter implementation

## Seam and deletion test

- interface 表达记录含义、验证与兼容结果，不暴露任意 `String + key` 读取。
- 原始 JSON、schemaVersion 与未知字段保留留在 adapter/internal seam。
- 删除新 module 时，Formula/Nursing/Sleep 字段规则会回流到 caller。

## Acceptance criteria

- [x] 比较至少两个 interface 形状，以 depth、旧数据兼容和 caller 学习成本选择一个，并记录理由
- [x] 建立当前 Formula、Nursing、Sleep 的真实/测试 payload 兼容样本集
- [x] encode → raw JSON adapter → decode 往返保持金额/分钟、睡眠 nap/anomaly 与 note 外字段
- [x] 编辑往返保留未知标量、对象、数组字段及可识别的 `schemaVersion`
- [x] malformed/partial payload 安全降级，不崩溃、不伪造非零值
- [x] 一个 RecordPresentation tracer 通过新 interface 呈现，不再自行按 key/Regex 读取这三类记录
- [x] Room schema、outbox envelope、同步 wire format 与现有导出格式不变

## Validation

- [x] Targeted：新 payload interface + RecordPresentation 相关测试
- [x] Compile：core/ui、domain 与 app debug 编译
- [x] Static：`git diff --check`

## Out of scope

- 全 RecordType 写路径（Ticket 05）
- Summary/Timeline/Search/Export 全量迁移
- 数据库迁移或服务器协议改版

## Comments

- 选择 sealed typed `RecordPayloadDocument` + codec，而不是通用 `String/key` reader；typed caller 学习成本更低，未知扩展与 raw future schema 留在 adapter。
- 兼容样本覆盖 Formula/Nursing/Sleep、未知标量/对象/数组、malformed 与 future schema；`./gradlew test assembleDebug -q`（exit 0），`git diff --check` 通过。
- Documentation: N/A；Room/outbox/sync/export 合同未因本票改变。
