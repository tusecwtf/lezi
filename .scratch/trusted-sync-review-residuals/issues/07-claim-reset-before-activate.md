# 07 — Claim 身份变更时 reset 与会话激活顺序

**What to build:** 成员 claim（批准 / QR）在 **家庭或 membership 身份变化** 时，须在
允许 push/同步前完成本地 receipt reset（或 durable pending-reset 门闩）。单次 claim
「先落会话防丢 grant」可保留，但不得在 identity 变化时以「已加入 + 旧收据」进入可推送状态。

**Blocked by:** 03 — Member reauth identity-skip 语义先稳定。

**Status:** complete

**Severity:** Medium
**Blocks release:** preferred
**Review ID:** F-07

## Must

- [x] 身份**未变**路径：与 03 一致，可不 reset。
- [x] 身份**变化**路径：reset 失败时不得允许 outbox push 携带上一家庭收据；
      或会话标记为 recovery-only 直到 reset 成功。
- [x] 与 Owner `persistJoin`（reset 再 save）语义文档化一致。
- [x] 测试：reset 失败后 claim 成功会话存在时，同步引擎拒绝跨家庭脏推或强制重试 reset。

## Evidence paths

- `FamilySessionCoordinator.kt` claim/grant
- `RealSyncPort` / replica barrier

## Comments

- 单次 grant 耐久与 boundary reset 的折中需写清，避免再引入「丢会话」回归。
