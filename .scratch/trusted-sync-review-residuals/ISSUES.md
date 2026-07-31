# Issues · trusted-sync-review-residuals

**Status:** complete

**Spec:** [spec.md](./spec.md)
**Findings archive:** [REVIEW.md](./REVIEW.md)
**Parent:** [trusted-sync-endpoint-auth](../trusted-sync-endpoint-auth/ISSUES.md)

本 tracker 将 2026-07-31 trusted-sync 实现后 code review 残差拆为可执行本地票。
状态以各 ticket 文件为准。

## Current-HEAD revalidation (2026-07-31)

- **Revalidated commit:** `8651a9a5a5b61c33b9242194a2f2e8ae67425c52`
- **Result:** 13/13 tickets still reproduce in committed code; no ticket changes status.
- **Release posture:** 01–06 remain 0.3.1 blockers; 07/08/11 remain preferred before
  release; 09/10/12/13 remain non-blocking follow-ups.
- Commits after the original `5f9aa3c` review point contain ticket-16 acceptance evidence
  and self-hosted app-update work. They do not close any residual here.
- Uncommitted self-hosted app-update work was inspected separately and is not counted as
  implementation evidence; it also does not alter these residual paths.

Current code evidence is recorded in [REVIEW.md](./REVIEW.md#current-head-revalidation-2026-07-31).

## Implementation acceptance (2026-07-31)

- **Implementation base:** `47828af072b1e85e47b91bf6c9533feffa94766c`
- **Result:** 13/13 ticket Must complete；01–06 release blockers closed.
- **Gates:** full JVM tests, Android lint, Debug APK, API 35 instrumentation,
  Rust fmt/test/Clippy and TLS test passed on the implementation tree.
- **Evidence:** [evidence/final/validation.md](./evidence/final/validation.md)
- Ticket 12 的浅状态文案为显式 optional Should；本次未扩展 outbox count/time projection。

## Dependency graph

```text
Immediate (parallel; 04↔05 prefer same owner):
  01 pending restore clobber     ──┐
  02 root rate-limit + oracle    ──┤
  03 member reauth receipts      ──┼──► release-candidate hygiene
  04 freeze origin after probe   ──┤
  05 trust remember only Ready   ──┤
  06 QR cancel + dataRecovery    ──┘
         │
         ▼
Preferred before 0.3.1 (17):
  07 claim ordering (after 03)
  08 internal health-only router
  11 terminal clear / saveSession order

Post-release OK if capacity tight:
  09 create/claim idempotency
  10 redaction + CT compare
  12 UI polish (silent check, delete-family name, copy)
  13 docs drift
```

## Tickets

| # | Ticket | Severity | Blocks 0.3.1 | Blocked by | Status |
|---|--------|----------|--------------|------------|--------|
| [01](./issues/01-wizard-pending-restore-clobber.md) | 向导 pending 恢复不得覆盖活跃/Completed 状态 | Critical | yes | — | complete |
| [02](./issues/02-root-password-rate-limit-deoracle.md) | 根密码失败限流并消除 create oracle | High | yes | — | complete |
| [03](./issues/03-member-reauth-receipt-preservation.md) | Member 同家庭 reauth 保留收据与 identity-skip | High | yes | — | complete |
| [04](./issues/04-freeze-origin-after-probe.md) | 探测/信任后冻结 origin，去掉可改 host 双路径 | High | yes | — | complete |
| [05](./issues/05-trust-remember-only-on-ready.md) | 仅 probe Ready 后 durable remember endpoint | High | yes | — | complete |
| [06](./issues/06-qr-verify-cancel-and-data-recovery.md) | QR 校验可取消并贯通 dataRecovery | High | yes | — | complete |
| [07](./issues/07-claim-reset-before-activate.md) | Claim 身份变更时 reset 与会话激活顺序 | Medium | preferred | 03 | complete |
| [08](./issues/08-internal-health-only-router.md) | 内部明文口仅 health/ready | Medium | preferred | — | complete |
| [09](./issues/09-create-claim-idempotency.md) | Create 多设备幂等与 claim lost-response 幂等 | Medium | no | — | complete |
| [10](./issues/10-secret-redaction-and-ct-compare.md) | 命令/DTO 秘密脱敏与固定长度 CT 比较 | Medium | no | — | complete |
| [11](./issues/11-terminal-clear-and-session-persist-order.md) | Terminal clear 屏障与 saveSession 持久化顺序 | Medium | preferred | — | complete |
| [12](./issues/12-ui-silent-check-delete-name-copy.md) | 检查结果静默、删家空名、相关文案 | Medium–Low | no | — | complete |
| [13](./issues/13-docs-ssid-and-health-drift.md) | SECURITY/AGENTS 等文档与 HTTPS 合同对齐 | Low | no | — | complete |

## Frontier

- 无；13/13 complete。Parent 16/17 仍须在新的候选 HEAD 上独立执行跨端验收与发版。

## Relationship to parent release tickets

| Parent ticket | Interaction |
|---------------|-------------|
| trusted-sync **16** 跨端验收 | 若本 tracker 01–06 在 16 固定 HEAD 之后修复，须刷新候选 HEAD 并重跑受影响门禁 |
| trusted-sync **17** 0.3.1 发版 | **不得**在 01–06 仍 open 时关闭；08/11 preferred |

## Explicitly not tickets (accepted residual)

- 首连 TOFU MITM、QR 肩窥、离线撤销延迟、LAN 8765 暴露、System PKI 公信模型
- Refresh 并发=theft 的严格服务端策略（客户端 single-flight 已部分缓解）
- 根密码 argon2 产品化拆分（方向见 REVIEW；本包只做限流+去 oracle）
