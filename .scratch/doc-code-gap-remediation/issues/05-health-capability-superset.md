# 05 — Health：capability 超集、忽略 version

**What to build:** Android `HealthStatus.isCurrentServerContract()` 仅要求 `ok` 且 capabilities **包含** `atomic_bundle` 与 `record_membership_author`（允许额外能力）；**不**要求 `version == APK 常量`。解析仍可读 version 供展示/日志。错误文案更新。

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] 修改 `HomeNetworkPolicy` 契约判定与 `ServerContractMismatchException` 文案
- [x] 单测：额外 capability 通过；缺必选失败；version 不同仍可通过
- [x] 与 01 文档 health 描述一致
