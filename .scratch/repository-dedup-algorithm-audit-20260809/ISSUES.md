# 2026-08-09 全库重复、残留与算法审查 — issues

Status: in-progress — tickets 01–18 implemented; ticket 19 remains blocked

Spec: [`spec.md`](./spec.md)

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

## Graph

```text
post-0.3.13/01 implementation stabilizes
  ├─► 01 causal settlement transaction ─► 08 fulfillment settlement
  └─► 06 duplicate bounds ──────────────► 07 Wake read projection

02 canonical causal ingress ─► 03 causal media staging
04 lossless pull sidecars ───► 05 canonical source graph
                            └─► 12 admission ─► 17 head loader ─► 18 receipt/page ─► 19 retention
                                                                    ▲              ▲
                                                        hardening 01 ┘  hardening 04 ┘

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
| 02 | [Unify canonical causal ingress validation and replay ordering](./issues/02-unify-canonical-causal-ingress.md) | P1 | implemented | — |
| 03 | [Give causal media preimages a manifest-bound lifecycle](./issues/03-manifest-bound-causal-media-staging.md) | P1 | implemented | 02 |
| 04 | [Paginate complete pull sidecars without cursor loss](./issues/04-lossless-batched-pull-sidecars.md) | P1 | implemented | — |
| 05 | [Make source relations one bounded canonical graph](./issues/05-bounded-canonical-source-relations.md) | P1 | implemented | 04 + post-0.3.13/01 implementation stabilization |
| 06 | [Replace Cartesian duplicate bounds with one global bounded interpretation](./issues/06-bounded-global-duplicate-bounds.md) | P1 | implemented | post-0.3.13/01 implementation stabilization |
| 07 | [Batch and reactively observe the Wake read projection](./issues/07-batched-reactive-wake-projection.md) | P1 | implemented | 06 |
| 08 | [Share one atomic fulfillment-authority settlement](./issues/08-atomic-fulfillment-authority-settlement.md) | P2 | implemented | 01 |
| 09 | [Own and release camera capture temporary files](./issues/09-owned-camera-capture-sessions.md) | P2 | implemented | — |
| 10 | [Move babies through one live domain command](./issues/10-atomic-baby-move-command.md) | P2 | implemented | — |
| 11 | [Share one untrusted member-login QR policy](./issues/11-shared-member-login-qr-policy.md) | P2 | implemented | — |
| 12 | [Bound causal commit and open-branch admission](./issues/12-bounded-conflict-resources.md) | P2 | implemented | 04 + causal hardening 01 |
| 13 | [Bound disaster-restore keyed locks](./issues/13-bounded-disaster-restore-locks.md) | P2 | implemented | — |
| 14 | [Make TLS inspect-only genuinely read-only](./issues/14-read-only-tls-inspection.md) | P2 | implemented | — |
| 15 | [Delete proven-dead Android façades and compatibility residue](./issues/15-delete-proven-dead-android-residue.md) | P3 | implemented | 09 + 10 + 11 + post-0.3.13/02 cleanup |
| 16 | [Centralize structured-cancellation cause policy](./issues/16-shared-cancellation-cause-policy.md) | P3 | implemented | — |
| 17 | [Batch-load bounded conflict heads](./issues/17-bounded-conflict-head-loader.md) | P1 | implemented | 12 + causal hardening 01 |
| 18 | [Persist snapshot receipts with bounded pagination](./issues/18-conflict-snapshot-receipt-pagination.md) | P1 | implemented | 17 + causal hardening 01 |
| 19 | [Bound resolution queries and retain conflict metadata](./issues/19-resolution-metadata-retention.md) | P1 | blocked | 18 + causal hardening 04 |

## Frontier

本 tracker 暂无可执行 frontier：`19` 仍等待 causal hardening 04。跨 tracker 的下一张串行
frontier 是 causal hardening H03；一个 agent 不应并行领取图中共享同一箭头端点的票；已标
implemented 的 02/04/09/18 不是 frontier。

## Excluded as already owned

- `FamilyWakePrivilegeStore`、旧 open-sleep normalization、Rust `neighbor.rs` 与空
  `neighbor_losers`：`post-0.3.13/02`。
- Wake/conflict/duplicate 的最终产品呈现、bounds 不得取 max、设备截图与真实双客户端主链：
  `post-0.3.13/01`。
- 仅按相似测试名、文件大小或 package 行数提出的清理：不建票。
