# 家庭同步抗卡死、数据正确性与交互硬化

Status: implementation-complete — live acceptance pending (`6b278242`)
Last consolidation: 2026-08-03 (rounds 1–3 multi-scope audit + **dedup**)

## Dedup principle

- **One root cause → one ticket.** Later agents re-describing the same hole do not open a twin.
- **Soft serial** = same files / same busy machine; not the same acceptance criteria.
- **Folded backlog** listed under existing tickets or §「已并入」— do not re-ticket.

---

## Canonical tickets (after dedup)

| # | Title | Status | Blocked by | Code | Notes |
|---|-------|--------|------------|------|-------|
| 01 | Abandon-waiting honest | complete | — | done | `6b278242` |
| 02 | Join before first full sync | implemented | — | done | awaiting isolation join/create smoke |
| 03 | Account card fidelity | complete | soft∥09/10 | done | Path A · `6b278242` |
| 04a | Bound **session-establish** busy | complete | **02** | done | `6b278242` |
| 04b | Bound **roster/network** busy timeout | complete | soft←08 host | done | `6b278242` |
| 05 | Gate baby-create busy | complete | — | done | `6b278242` |
| 06 | Busy destructive confirms | complete | — | done | `6b278242` |
| 07 | Network host busy hygiene (DR cancel + finally) | complete | soft∥04b | done | `6b278242` |
| 08 | Wizard/sync cancel hygiene (Submitting + Syncing) | complete | soft after 01 | done | `6b278242` |
| 09 | Shallow sync + outbox N | complete | soft before 10 | done | `6b278242` |
| 10 | Reauth product surface | complete | soft←09 | done | `6b278242` |
| 11 | Productize error copy | complete | soft DR after 07 | done | `6b278242` |
| 12 | Timer leave vs discard | complete | **product B** | done | `6b278242` |
| 13 | Freeze composer write decision | complete | — | done | `6b278242` |
| 14 | Timer post-save durability | complete | **12** | done | `6b278242` |
| 15 | Local clear media epoch | complete | soft after 16 | done | `6b278242` |
| 16 | Media delete path safety | complete | — | done | `6b278242` |
| 17 | Settings clear/custom durable busy | complete | — | done | `6b278242` |
| 18 | Post-push full-resync + receipt CAS | complete | — | done | `6b278242` |
| 19 | Server family lock not across upload | implemented | — | done | awaiting NAS CD smoke |
| 20 | Server restore access TTL + staging GC | complete | — | done | `6b278242` |
| 21 | Claim `claimed` replay | complete | — | done | `6b278242` |
| 22 | Refresh rotation crash-safe | implemented | — | done | awaiting live refresh recovery |
| 23 | Force shell survives reauth (+ install busy/dismiss) | complete | — | done | `6b278242` |
| 24 | Availability Checking stuck + demote + debounce | complete | — | done | `6b278242` |
| 25 | Server FC pull co-group | implemented | — | done | awaiting NAS FC pull smoke |
| 26 | Single pending-waiting authority | complete | soft after 01 | done | `6b278242` |
| 27 | Avatar display + prepareUpload OOM bounds | complete | — | done | `6b278242` |
| 28 | Widget + alarm lifecycle | complete | — | done | `6b278242` |
| **29** | Soft-deleted baby must not brick outbox push | complete | — | done | `6b278242` |
| **30** | Apply: dirty must not skip strictly newer remote | complete | — | done | `6b278242` |
| **31** | Server record manage ACL (ForbiddenRecord) | implemented | — | done | awaiting NAS ACL smoke |
| **32** | Multi-device open sleep: family-global wake | complete | — | done | `6b278242` |
| **33** | Owner reconnect: no ghost devices + sticky login id | implemented | — | done | awaiting real owner reconnect |
| **34** | forgetEndpoint / re-TOFU clears credentials | complete | — | done | `6b278242` |
| **35** | Composer mid-save process-death identity | complete | soft∥13 | done | `6b278242` |
| **36** | Custom-item family invariants (cap/layout id/record create) | implemented | soft∥17 | done | awaiting multi-device custom smoke |
| **37** | Calendar handoff dual-notify + boot/package rehydrate | complete | soft∥28 | done | `6b278242` |
| **38** | Untrusted deep-link confirm (fulfill / composer / setCurrent) | complete | — | done | `6b278242` |
| **39** | Baby apply dirty-hold + merge avatar policy | complete | soft∥30 | done | `6b278242` |
| **40** | Dual open next-feed markers multi-device | complete | soft∥32 | done | `6b278242` |

## Acceptance evidence

All implementation/static/automated Must items are accepted on fixed HEAD
`6b2782425860d37f9357ea9c6ee9455171aad95c`. The only unchecked items are
external acceptance gates that cannot be proven by repository tests alone:

| Ticket | Residual acceptance |
|--------|---------------------|
| 02 | Isolated 0.3.3 join/create prompt-return plus later foreground convergence |
| 19 | NAS CD plus concurrent stalled/large upload and pull/commit smoke |
| 22 | Real refresh rotation interrupted between server accept and client handoff |
| 25 | Post-CD FC-only-page pull resolves the Android unresolved set |
| 31 | Post-CD real Member foreign-record/media ACL and same-membership allowance |
| 33 | Real Owner reconnect creates no ghost active Owner device |
| 36 | Two-device custom cap, wipe/rejoin layout identity, and tombstone smoke |

Issue files: `issues/01-*.md` …; **29–40** from consolidation + saturation. Former **08** file remains wizard/sync; network finally is **07**.

---

## 已并入（勿再开票）

| Finding (later audits) | Canonical ticket |
|------------------------|------------------|
| cancelMember local-first | 01 |
| recover under barrier / first sync | 02 |
| roster loading / rename card | 03 |
| launchBusy / DR cancel no-op / cancel sticky busy | **07** (host) + **08** (wizard/sync) |
| HTTP raw / recoveryStatus | 11 |
| leave/logout dismiss-then-async | 06 |
| force shell NotJoined clear / APK sessionMutex | **23** |
| Checking stuck / NetworkRecovered storm | **24** |
| FC co-group hang | 25 |
| dual FamilyWizardController | 26 |
| prepareUpload OOM / avatar full decode | 27 |
| alarm requestCode 16-bit / widget clear / notif cancel | **28** |
| delete baby + dirty children push fail | **29** |
| syncDirty skip newer tombstone / full-resync dirty | **30** |
| custom item dirty skip newer remote | **30** |
| member ForbiddenRecord missing | **31** |
| dual open sleep lost wake | **32** |
| reconnectOwner ghost Owner devices | **33** |
| sticky login_request_id / takeover flip | **33** |
| re-TOFU keeps tokens | **34** |
| composer mid-save no clientUuid resume | **35** |
| custom 10-cap server / custom:localId layout | **36** |
| calendar dual after edit / boot gaps | **37** + **28** package-replaced |
| intent spoof fulfill | **38** |
| applyBaby no dirty guard | **39** |
| mid-push body/epoch mix | **30** / follow-on AC of 30 |
| search short-needle noise | deferred polish (P1 product, not hang) |
| export silent photo / EXIF PDF | deferred polish (export tracker later) |
| R8 Serializable SavedState | deferred release hygiene |
| dark theme swipe onError | deferred designsystem |
| log minute re-observe thrash | deferred perf |

---

## Hard Blocked-by

```text
14 ← 12
04a ← 02
10 soft← 09
35 soft∥ 13 (same composer save path — one owner)
39 soft∥ 30 (apply policy wave)
37 soft∥ 28 (alarms/package-replaced)
```

## Soft serial (one agent per cluster)

| Cluster | Tickets |
|---------|---------|
| Wizard | 01, 02, 08, 26 |
| Network host | 04b, **07** |
| Account status | 03, 09, 10, 11 |
| Media/clear | 15, 16, 27, 29, 39 |
| Sync apply/outbox | 18, **29, 30, 31**, 33 |
| Timer | 12, 14 |
| Server release | 19, 20, 25, 31 |

## True parallel frontier (example)

`05, 06, 12*, 13, 16, 17, 18, 19, 21–25, 27–35, 36–39`  
+ **one of** `{01,02,26}` + **one of** `{03,09}` + **07** alone for host

---

## Saturation (2026-08-03 final pass)

- All listed historical **P0 themes map 1:1** to 02/19/21–33/35/28/39 (see agent map).
- Ruthless rescan of growth / DR cancel / rename / next-feed / cleartext / path-gate deadlock:
  - **Only novel P1:** dual open next-feed → **40**
  - Everything else **MAPS TO** existing tickets
- Further agent sweeps expected to rephrase 01–40 without new root causes (search/export/theme/R8/perf remain deferred polish).

## Cross-tracker (do not double-implement)

Sibling [`.scratch/codebase-audit-20260802-remediation/`](../codebase-audit-20260802-remediation/):

| That tracker | Prefer here |
|--------------|-------------|
| 02 server bundle media lock | **19** |
| 04 APK outside sessionMutex | **23** |
| 09/10 sync UX / reauth | **09 / 10 / 11** |
| 07 custom item transactions | stay there; family cap → **36** |
| 08 timer cross-boot elapsed | stay there; leave policy → **12/14** |

## Review sign-off (2026-08-03)

Tickets 01–40 are **reasonable** to keep: each has a distinct user-visible failure or
security/data root cause; soft serial prevents file thrash; product decisions called
out on **03** (rename path) and **12** (timer leave A/B). Deferred polish remains out
of this tracker.
