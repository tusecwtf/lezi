# 10 — 命令/DTO 秘密脱敏与固定长度 CT 比较

**What to build:** 所有携带根密码、bootstrap、pending secret、refresh 的 data class /
command 统一 `toString` 脱敏（与 `DeleteFamily` 一致）。服务端根密码比较改为固定长度
摘要比较，消除长度短路侧信道。

**Blocked by:** None.

**Status:** complete

**Severity:** Medium
**Blocks release:** no
**Review ID:** F-10

## Must

- [x] `CreateFamily`、`OwnerLogin` 及同类命令/DTO（含 `MemberLoginReceipt.pendingSecret`）
      覆盖 `toString` 红acted。
- [x] `constant_time_eq` 对根密码：hash/HMAC 到固定长度再比，或等价无长度 oracle。
- [x] 单测：toString 不含明文密码；可选 timing 不测，但代码审查可指出实现。

## Evidence paths

- `sync/.../FamilySessionCoordinator.kt` commands
- `sync/.../SyncBackend.kt` receipts
- `tools/lezi-sync/src/lib.rs` `constant_time_eq` / require_*

## Comments

- 与 02 限流互补；本票不替代限流。
