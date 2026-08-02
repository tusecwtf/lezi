# 家庭同步抗卡死、数据正确性与交互硬化

Status: ready-for-agent  
Last consolidation: 2026-08-03 (rounds 1–3 multi-scope audit + **dedup**)

## Dedup principle

- **One root cause → one ticket.** Later agents re-describing the same hole do not open a twin.
- **Soft serial** = same files / same busy machine; not the same acceptance criteria.
- **Folded backlog** listed under existing tickets or §「已并入」— do not re-ticket.

---

## Canonical tickets (after dedup)

| # | Title | Status | Blocked by | Code | Notes |
|---|-------|--------|------------|------|-------|
| 01 | Abandon-waiting honest | ready | — | partial | domain local-first done |
| 02 | Join before first full sync | ready | — | partial | recover still under barrier |
| 03 | Account card fidelity | ready | soft∥09/10 | open | rename A/B decision first |
| 04a | Bound **session-establish** busy | ready | **02** | open | was over-blocked as full 04 |
| 04b | Bound **roster/network** busy timeout | ready | soft←08 host | open | free of 01 |
| 05 | Gate baby-create busy | ready | — | open | |
| 06 | Busy destructive confirms | ready | — | partial | + rename/QR multi-tap single-flight |
| 07 | Network host busy hygiene (DR cancel + finally) | ready | soft∥04b | open | host launchBusy only |
| 08 | Wizard/sync cancel hygiene (Submitting + Syncing) | ready | soft after 01 | open | not network host |
| 09 | Shallow sync + outbox N | ready | soft before 10 | partial | owns status projector |
| 10 | Reauth product surface | ready | soft←09 | partial | CTA matrix; not force shell |
| 11 | Productize error copy | ready | soft DR after 07 | open | + probe twin maps / 根密码 wording |
| 12 | Timer leave vs discard | ready | **product A/B** | open | |
| 13 | Freeze composer write decision | ready | — | partial | |
| 14 | Timer post-save durability | ready | **12** | partial | |
| 15 | Local clear media epoch | ready | soft after 16 | open | |
| 16 | Media delete path safety | ready | — | partial | |
| 17 | Settings clear/custom durable busy | ready | — | open | Forced-about → **23** |
| 18 | Post-push full-resync + receipt CAS | ready | — | partial | |
| 19 | Server family lock not across upload | ready | — | open | + get_media lock |
| 20 | Server restore access TTL + staging GC | ready | — | open | approved-name split later |
| 21 | Claim `claimed` replay | ready | — | open | P0 auth |
| 22 | Refresh rotation crash-safe | ready | — | open | P0 auth |
| 23 | Force shell survives reauth (+ install busy/dismiss) | ready | — | open | P0; absorbs APK sessionMutex / optional dismiss |
| 24 | Availability Checking stuck + demote + debounce | ready | — | open | P0 |
| 25 | Server FC pull co-group | ready | — | open | P0 |
| 26 | Single pending-waiting authority | ready | soft after 01 | open | dual wizard / root unmount |
| 27 | Avatar display + prepareUpload OOM bounds | ready | — | open | P0 |
| 28 | Widget + alarm lifecycle | ready | — | open | requestCode; notif cancel; clear wipe; **+ MY_PACKAGE_REPLACED** |
| **29** | Soft-deleted baby must not brick outbox push | ready | — | open | **NEW P0** capture vs `babyDao.get` |
| **30** | Apply: dirty must not skip strictly newer remote | ready | — | open | **NEW P0** LWW/tombstone/full-resync |
| **31** | Server record manage ACL (ForbiddenRecord) | ready | — | open | **NEW P0** member edit any record |
| **32** | Multi-device open sleep: family-global wake | ready | — | open | **NEW P0** lost wake |
| **33** | Owner reconnect: no ghost devices + sticky login id | ready | — | open | **NEW P0** reconnectOwner |
| **34** | forgetEndpoint / re-TOFU clears credentials | ready | — | open | **NEW P1** PRD §10 |
| **35** | Composer mid-save process-death identity | ready | soft∥13 | open | **NEW P0** duplicate fact |
| **36** | Custom-item family invariants (cap/layout id/record create) | ready | soft∥17 | open | **NEW P1** multi-device |
| **37** | Calendar handoff dual-notify + boot/package rehydrate | ready | soft∥28 | open | **NEW P1**; package-replaced with 28 |
| **38** | Untrusted deep-link confirm (fulfill / composer / setCurrent) | ready | — | open | **NEW P1** exported MainActivity |
| **39** | Baby apply dirty-hold + merge avatar policy | ready | soft∥30 | open | **NEW P0/P1** clobber / immutable media |
| **40** | Dual open next-feed markers multi-device | ready | soft∥32 | open | **NEW P1** (saturation: only novel left) |

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
