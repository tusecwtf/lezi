# 2026-08-09 全库重复、残留与算法审查 — issues

Status: in-progress — ticket 01 implemented

Spec: [`spec.md`](./spec.md)

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

## Graph

```text
post-0.3.13/01 implementation stabilizes
  ├─► 01 causal settlement transaction ─► 08 fulfillment settlement
  └─► 06 duplicate bounds ──────────────► 07 Wake read projection

02 canonical causal ingress ─► 03 causal media staging
04 lossless pull sidecars ───► 05 canonical source graph
                            └─► 12 bounded conflict resources

09 capture ownership ─┐
10 baby move command ─┼─► 15 dead Android residue
11 member QR policy ──┘    (also waits for post-0.3.13/02 cleanup)

13 restore locks     14 read-only TLS inspect     16 cancellation policy
  (independent)        (independent)                (independent)
```

## Tickets

| # | Ticket | Priority | Status | Blocked by |
|---|--------|----------|--------|------------|
| 01 | [Harden causal settlement as one bounded proof transaction](./issues/01-harden-causal-settlement-transaction.md) | P1 | implemented | post-0.3.13/01 implementation stabilization |
| 02 | [Unify canonical causal ingress validation and replay ordering](./issues/02-unify-canonical-causal-ingress.md) | P1 | ready-for-agent | — |
| 03 | [Give causal media preimages a manifest-bound lifecycle](./issues/03-manifest-bound-causal-media-staging.md) | P1 | ready-for-agent | 02 |
| 04 | [Paginate complete pull sidecars without cursor loss](./issues/04-lossless-batched-pull-sidecars.md) | P1 | ready-for-agent | — |
| 05 | [Make source relations one bounded canonical graph](./issues/05-bounded-canonical-source-relations.md) | P1 | ready-for-agent | 04 + post-0.3.13/01 implementation stabilization |
| 06 | [Replace Cartesian duplicate bounds with one global bounded interpretation](./issues/06-bounded-global-duplicate-bounds.md) | P1 | ready-for-agent | post-0.3.13/01 implementation stabilization |
| 07 | [Batch and reactively observe the Wake read projection](./issues/07-batched-reactive-wake-projection.md) | P1 | ready-for-agent | 06 |
| 08 | [Share one atomic fulfillment-authority settlement](./issues/08-atomic-fulfillment-authority-settlement.md) | P2 | ready-for-agent | 01 |
| 09 | [Own and release camera capture temporary files](./issues/09-owned-camera-capture-sessions.md) | P2 | ready-for-agent | — |
| 10 | [Move babies through one live domain command](./issues/10-atomic-baby-move-command.md) | P2 | ready-for-agent | — |
| 11 | [Share one untrusted member-login QR policy](./issues/11-shared-member-login-qr-policy.md) | P2 | ready-for-agent | — |
| 12 | [Bound conflict branch, detail and resolution resources](./issues/12-bounded-conflict-resources.md) | P2 | ready-for-agent | 04 |
| 13 | [Bound disaster-restore keyed locks](./issues/13-bounded-disaster-restore-locks.md) | P2 | ready-for-agent | — |
| 14 | [Make TLS inspect-only genuinely read-only](./issues/14-read-only-tls-inspection.md) | P2 | ready-for-agent | — |
| 15 | [Delete proven-dead Android façades and compatibility residue](./issues/15-delete-proven-dead-android-residue.md) | P3 | ready-for-agent | 09 + 10 + 11 + post-0.3.13/02 cleanup |
| 16 | [Centralize structured-cancellation cause policy](./issues/16-shared-cancellation-cause-policy.md) | P3 | ready-for-agent | — |

## Frontier

在当前 post WIP 尚未固定提交时，可无文件冲突地先做 `02 ∥ 04 ∥ 09 ∥ 10 ∥ 11 ∥ 13 ∥ 14 ∥ 16`。
`01/05/06` 必须等待 post-01 的重叠实现稳定并重新 pin HEAD；`15` 最后做，防止删除正在被其它
cleanup/refactor 替换的唯一 seam。一个 agent 不应并行领取图中共享同一箭头端点的票。

## Excluded as already owned

- `FamilyWakePrivilegeStore`、旧 open-sleep normalization、Rust `neighbor.rs` 与空
  `neighbor_losers`：`post-0.3.13/02`。
- Wake/conflict/duplicate 的最终产品呈现、bounds 不得取 max、设备截图与真实双客户端主链：
  `post-0.3.13/01`。
- 仅按相似测试名、文件大小或 package 行数提出的清理：不建票。
