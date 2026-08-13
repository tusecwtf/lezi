# 0.3.10 近邻落选、记录墓碑永胜与同步 chrome

Status: ready-for-agent

Target release: **versionName `0.3.10`**（接续当前 `0.3.9` / versionCode `16`；实现时单调抬高
versionCode，具体整数以发版清单为准）。用户口头「3.10.0」一律记为产品 **0.3.10**。

Authority: [`CONTEXT.md`](../../CONTEXT.md) 近邻相关术语与同步状态（概览）；
[`docs/adr/0018-neighbor-duplicate-records-and-tombstone-wins.md`](../../docs/adr/0018-neighbor-duplicate-records-and-tombstone-wins.md)；
调研
[`docs/research/2026-08-05-offline-first-conflict-resolution.md`](../../docs/research/2026-08-05-offline-first-conflict-resolution.md)、
[`docs/research/2026-08-05-sync-conflict-and-duplicate-resolution.md`](../../docs/research/2026-08-05-sync-conflict-and-duplicate-resolution.md)；
浅状态 UI
[`docs/prd/sync-trusted-endpoint.md`](../../docs/prd/sync-trusted-endpoint.md) §7.3、
[`docs/design/2026-07-30-trusted-sync-onboarding-ui.md`](../../docs/design/2026-07-30-trusted-sync-onboarding-ui.md) §9。

## Problem Statement

家人在极短时间内各自记录「同一次」喂养、换尿布等温柔高频事件时，时间轴上会出现两条
独立护理记录。现有家庭同步只保证 **同 `client_uuid` 幂等 + `updated_at` LWW**，不同
UUID 的两条新建都是合法 first-create，协议层不会合并。

同时，护理记录在权威图上的软删 tombstone 仍可被「更高 `updated_at` 的 live」覆盖复活
（与护理计划、自定义项目、履行候选的禁复活策略不对称）。在离线编辑、时钟偏斜或删前
并发抬高修订时，管理员或近邻落选写上的删除语义可能被另一台设备推回 live。用户也会把
「删了 A 仍看见语义双记 B」误当成删除复活。

同一发版还要修两类同步呈现缺口：

1. **记录 / 汇总 / 成长** 三页各自手写浅同步文案；失败态 `暂时无法同步 · 下拉重试`
   **常驻内容区**，挤占主内容，与「浅提示不占长期卡片」不符。
2. **家庭成员与设备** 页只有授权设备行才有 `last_used_at`；普通成员看不到其他家人是否
   近期连过家庭服务器，也无法在名单上读到 **各成员上次同步时间**。

0.3.10 必须在不引入冲突对话框、不静默合并字段、不重写整棵 CRDT 的前提下，让白名单
类型在跨成员近邻时间窗内收敛为一条可见事实，让护理记录墓碑与既有实体一样永胜，并交付
上述同步 chrome 与成员上次同步可见性。升级不得要求清空 App、删除家庭或手工改 NAS 库；
须保留护理事实、照片、身份、TLS 与凭据。

## Solution

0.3.10 做 **四件** 可独立验收、同属本版产品合同的事：

1. **记录墓碑永胜**  
   家庭权威图一旦接受某护理记录 `client_uuid` 的软删 tombstone（手删或近邻落选），
   任何后续 live 不得清零 `deleted_at` 复活。需要事实时用户 **新记一条**（新 UUID）。
   权威 reconcile 与 atomic commit 对 record 与 plan/custom/fulfillment 对齐禁复活。

2. **家庭近邻裁决**  
   仅家庭服务器在权威 atomic commit 的 **同一数据库事务** 内，对 **本轮触达的**
   `(宝宝, 精确 RecordType)` 分片、在事件主时间近邻邻域内的 **live** 白名单护理记录，
   按跨 membership 连边取连通分量；组内按「当前 Owner 作者 → 最早主时间 →
   `client_uuid` 字典序」保留一条，其余写近邻落选墓碑。客户端不做乐观本地 tombstone。
   同 membership（含多设备）窗内连记豁免。固定 30 分钟窗，只比主时间 `timestamp`。
   本轮落选 UUID 通过 **显式** 信号返回；落选作者设备每同步周期最多一条轻提示。

3. **三数据页统一短暂浅同步 chrome**  
   记录 / 汇总 / 成长共用 **同一** 浅同步 Compose 元素（文案仍来自 `ShallowSyncLine`）。
   用户 **下拉刷新** 触发的同步：进行中可显示「正在同步…」；**结束后结果在内容区约 5 秒**，
   再动画 **缩入 root top bar**。内容区 **不再永久** 挂着 `暂时无法同步 · 下拉重试` 等整行。
   回前台 / 本机写 / 进页节流的静默同步不强制弹出同等内容区常驻行。**账户页** 家庭卡一句
   同步状态可仍常驻，不在本项改成 5 秒消失。

4. **成员上次同步时间（全角色）**  
   家庭成员与设备名单上，每位成员展示 **上次同步时间**（人话相对时间）。**Owner 与普通
   Member** 均可获取并看到 **所有成员** 的该字段。时间语义：该 membership 下全部 active
   设备 `last_used_at` 的 **最大值**（鉴权/refresh 已刷新）。无设备或从未连上 → 明确空态
   （如「尚未同步」）。**隐私**：普通成员仍不得因本项获得他人完整设备列表；优先在
   `MemberView` additive 字段（如 `last_sync_at`），不放开他人 `devices` 数组。

历史双条在对应分片再次被写路径触达时自然收敛；默认 **不是** 每次 push 全库扫描。
可选一次性分片回填仅作升级加速，不得代替触达分片可重入合同。

发版顺序沿用既有通道：Rust 门禁 → 签名 APK 与更新 metadata 可验证 →（若 wire 对旧端
不兼容）再抬 `min_supported` → 用户确认后 NAS CD，保留数据 bind、bootstrap secret、
TLS 身份。

## User Stories

1. As a caregiver, I want two family members who both log the same feed within thirty minutes to see only one timeline entry after sync, so that night-shift double-logging stops polluting history.
2. As a caregiver, I want exact record types kept separate (for example nursing versus formula), so that a true mixed feed is not silently collapsed.
3. As a caregiver, I want same-membership double entries within thirty minutes kept, so that real back-to-back feeds on my own devices remain countable.
4. As a family administrator, I want my authored whitelist record preferred over a member’s near-duplicate when both are live, so that household authority is reflected without a conflict dialog.
5. As a caregiver who is not the administrator, I want the earliest event time to win when no current Owner author is in the group, so that the first real care moment is kept.
6. As a caregiver, I want stable `client_uuid` order only as a last tie-break, so that two devices never oscillate on equal timestamps.
7. As a caregiver, I want loser records soft-deleted and synced as tombstones, so that every device converges on the same timeline without merging notes or photos into the winner.
8. As a caregiver, I want no keep-both or merge dialog, so that logging stays fast under stress.
9. As a caregiver whose record lost neighbor adjudication, I want a single non-blocking light hint after sync, so that I understand my entry was aligned with family time without being interrupted.
10. As a caregiver, I want at most one such hint per sync cycle even if several of my rows lost, so that backfill or multi-loser commits do not spam me.
11. As a caregiver, I want ordinary remote deletes of my records not to use the neighbor-alignment wording, so that admin deletion is not mislabeled as duplicate cleanup.
12. As a peer device that only observes someone else’s loss, I want no neighbor toast, so that only the losing author is notified.
13. As a caregiver offline with a temporary double entry, I want both rows visible until sync finishes, so that local-first capture is not delayed by optimistic hiding.
14. As a caregiver, I want the client not to invent local neighbor tombstones before the server answers, so that Owner view drift and weak networks cannot flip winners incorrectly.
15. As a family administrator, I want a soft-deleted care record to stay deleted on every device even if another phone still has a newer dirty live body, so that delete authority is trustworthy on flaky networks.
16. As a caregiver, I want the same no-resurrection rule for neighbor-loser tombstones and manual deletes, so that there is one deletion contract for records.
17. As a caregiver who mistyped, I want recovery by creating a new record rather than silent LWW restore of a tombstoned UUID, so that history does not resurrect removed identities.
18. As a caregiver logging sleep, growth, diary, hospital, vaccine, or custom items, I want neighbor dedupe not to apply, so that rare or high-stakes facts are never auto-collapsed.
19. As a caregiver logging bath, medicine, temperature, snacks, drinks, or baby food, I want them included in the whitelist, so that common double-logs beyond feeds and diapers are covered.
20. As a caregiver fulfilling a plan that creates a whitelist record, I want that record to participate in neighbor rules like a freehand log, so that fulfill-versus-hand-log races still converge.
21. As a caregiver, I want plan completion and fulfillment candidate evidence left intact when a fulfillment-created record loses neighbor adjudication, so that immutable fulfillment proof is not rewritten by dedupe.
22. As a caregiver, I want anonymous or membership-cleared author rows excluded from neighbor groups, so that migration leftovers are not silently tombstoned against living members.
23. As three caregivers logging the same type across a chain of thirty-minute overlaps, I want one connected component and a single survivor, so that multi-person night shifts do not leave residual pairs.
24. As a caregiver who lost once and immediately logs again inside the winner’s window, I want that new UUID to lose again if still a cross-membership neighbor, so that the rule cannot be gamed by re-inserting.
25. As a caregiver who needs a second real event, I want moving the main timestamp outside the thirty-minute window to keep both facts, so that genuine spaced care remains expressible.
26. As a family administrator who later soft-deletes the winning record, I want prior losers to stay tombstoned, so that delete does not resurrect rejected duplicates.
27. As a caregiver editing a live record’s main time into another member’s window, I want the server to re-run neighbor rules on that touched shard, so that edits cannot leave durable doubles.
28. As a caregiver, I want untouched baby-and-type shards not fully rescaned on every unrelated push, so that NAS work stays proportional to what this commit changed.
29. As a caregiver with historical doubles from before 0.3.10, I want them to collapse when that shard is next written (or via an optional one-time backfill if shipped), so that old night-shift mess eventually cleans without a destructive migration.
30. As a peer pulling after neighbor adjudication, I want to adopt the authoritative tombstones and single live winner, so that “已同步” means the same timeline for the whole family.
31. As a caregiver, I want record photos on a loser package to follow the tombstone visibility rules of the record sync package, so that partial photo-only ghosts do not appear.
32. As a family Owner after root-password takeover, I want current Owner membership to define admin preference at adjudication time, so that old admin authorship does not keep permanent priority.
33. As a developer, I want neighbor adjudication inside the same Store transaction as atomic commit, so that the public authority graph never briefly exposes two live neighbors after a successful commit.
34. As a developer, I want an explicit per-commit neighbor-loser identity set in the server response path, so that the client toast seam does not guess from timeline heuristics.
35. As a developer, I want authoritative reconcile to refuse record live-over-tombstone the same way other tombstoned entities do, so that head-by-UUID settlement cannot disagree with commit.
36. As an operator, I want 0.3.10 CD to keep the existing data bind, bootstrap secret, and TLS SPKI, so that dedupe shipping is not a certificate or secret rotation.
37. As a release reviewer, I want the signed 0.3.10 APK on the verified update channel before any forced minimum bump that rejects older clients, so that upgrades cannot deadlock.
38. As a release reviewer, I want Room care data and media preserved across in-place install, so that this release never requires clear-data.
39. As a release reviewer, I want one isolated dual-client acceptance path for neighbor collapse plus tombstone-wins, so that product proof is family-visible rather than only unit-green.
40. As a privacy-conscious operator, I want logs and diagnostics for neighbor outcomes to use opaque ids and reason codes without notes, names, or media bytes, so that incidents stay diagnosable without leaks.
41. As a caregiver on a non-whitelist type, I want behavior unchanged from 0.3.9, so that scope stays tight.
42. As a caregiver, I want open-sleep uniqueness rules to remain independent of neighbor dedupe, so that interval sleep facts are not mixed into the thirty-minute event rule.
43. As a member with only one device, I want same-membership exemption still meaning “my membership,” so that multi-device wording does not change single-device logging.
44. As a caregiver, I want summary and timeline aggregates to ignore neighbor-loser tombstones, so that counts match what the family sees.
45. As a support helper, I want “delete came back” incidents after 0.3.10 to be classifiable as either another UUID still live or a pre-upgrade client, so that debugging does not assume LWW restore still works for records.
46. As a caregiver on log, summary, or growth, I want one shared shallow-sync chrome element, so that the three pages never diverge in status copy or error coloring.
47. As a caregiver who pull-to-refreshes, I want the sync result line to stay in the content area only about five seconds and then collapse into the top bar, so that “暂时无法同步 · 下拉重试” never permanently occupies the list top.
48. As a caregiver, I want silent foreground / local-write / enter-page sync not to force the same long-lived content-area line as an intentional pull, so that automatic sync stays quiet.
49. As any joined family member, I want each roster row to show that membership’s last sync time, so that I can tell who has connected recently without asking.
50. As an ordinary member, I want to see other members’ last sync times without receiving their device names or device ids, so that activity awareness does not break the existing device ACL.

## Implementation Decisions

- Release identity is **0.3.10** product cut: Android versionName, lezi-sync package version, health version string, and app-update metadata stay in lockstep per existing release runbook. versionCode increments monotonically from the 0.3.9 baseline.
- Architecture authority is ADR-0018; domain terms live only in `CONTEXT.md`. PRD (`data-model`, sync-trusted-endpoint, tech) must be updated in the same release so wire and deletion contracts stop claiming record LWW restore.
- Two problem classes stay separate:
  - **Semantic doubles (A):** different `client_uuid`, neighbor rules.
  - **Identity deletion (B):** same `client_uuid`, record tombstone wins forever.
- **Record tombstone wins:** atomic commit and authoritative reconcile reject or ignore live payloads that clear `deleted_at` on an already-tombstoned record identity, aligned with care_plan / custom_item / fulfillment_candidate. No silent restore channel in 0.3.10.
- **Neighbor applicability whitelist (exact wire keys):**  
  `nursing`, `formula`, `pumped_feed`, `pump_express`, `baby_food`, `snack`, `drink`,
  `pee`, `poop`, `both_diaper`, `temperature`, `bath`, `medicine`.  
  Sleep, growth measures, diary, hospital, vaccine, custom, and unlisted symptom types are out.
- **Neighbor predicate:** same baby, exact type key, `|Δtimestamp| ≤ 30 minutes` inclusive, distinct non-empty author memberships. Payload similarity, notes, doses, and photos are ignored.
- **Grouping:** undirected edges between cross-membership pairs inside the window; connected components; at most one live survivor per component.
- **Adoption order:** author membership equals **current** sole Owner membership at adjudication time → else earliest main timestamp → else lexicographically smaller `client_uuid`.
- **Loser disposition:** soft-delete tombstone on the root record package; no field merge into winner; not modeled as 冲突未采纳履行.
- **Same-membership exemption:** multiple live rows from one membership inside the window are not neighbors to each other.
- **Fulfillment-created whitelist records** participate; losing does not roll back plan completed state or frozen fulfillment candidate evidence.
- **Winner later deleted:** losers do not auto-resurrect.
- **Re-insert after loss:** a new UUID from the loser author that still neighbors the winner may lose again; user must move main time outside the window or edit the winner.
- **Server-only adjudication:** no client optimistic neighbor tombstone; temporary offline doubles are allowed until pull/commit settlement.
- **Same SQLite transaction** as the triggering atomic commit writes neighbor losers; no post-commit async sweeper; no pull-only authority writes that leave dual live heads.
- **Compute scope (mandatory default):** only shards `(baby, RecordType)` touched by this commit’s whitelist live mutations (create/update of baby/type/timestamp/deleted state). Candidate load is the live neighborhood around touched event times (about `[t−30min, t+30min]` for loading; pairwise window remains 30 minutes). Untouched shards are skipped. Commits with no whitelist record live mutation skip neighbor entirely. This is **not** a full-family full-history rescan on every push.
- **Historical doubles:** converge when their shard is next touched. Optional one-time partitioned backfill may ship for upgrade snappiness but is not a substitute for re-entrant on-touch rules.
- **Neighbor-loser signal:** server returns an explicit set of `client_uuid` values tombstoned by neighbor rules in this commit (or equivalent generation-scoped signal). Clients must not infer neighbor toasts from “another live row nearby.”
- **Toast policy:** if the signal includes a uuid that was live locally, authored by the current membership, show one non-blocking light message per sync cycle; no dialog; no permanent timeline “merged” chrome.
- **Deep façades preserved:** lezi-sync Store remains the server rule owner; Android keeps SyncPort / RealSyncPort / engine settlement without a parallel sync engine or durable outbox revival.
- **Capabilities / gates:** if clients cannot safely interoperate with 0.3.9 servers (or vice versa) on record resurrection or neighbor signals, advertise a versioned capability and only raise `min_supported` after the signed 0.3.10 package is on the verified channel. Prefer fail-closed over mixed-mode silent divergence for tombstone-wins.
- **Schema:** prefer rule changes without SQLite user_version bump when possible. If durable schema is required, follow fresh-current / fail-closed startup policy; ordinary CD must not invent ad-hoc SQL migrations on the family NAS.
- **Indexes:** optional generated columns or indexes on baby/type/timestamp for neighborhood queries; correctness does not depend on a particular physical plan.
- **Logging:** entity type, opaque uuid, membership ids, reason codes only; no notes, display names, tokens, or media bytes.
- **Documentation deliverables in-release:** PRD sync/deletion sections, tech version table, release notes; ADR-0018 already accepted; shallow-status presentation (transient data-page chrome) and member `last_sync_at` visibility in trusted-sync / design docs.
- **Data-page shallow chrome (0.3.10 product surface):** one shared Compose element for log / summary / growth; copy continues to come from `ShallowSyncLine` / `projectShallowSyncLine` (including `暂时无法同步 · 下拉重试`). Change presentation lifecycle and layout only—do not rewrite the shallow state matrix in this release unless a bug forces it.
- **Transient display:** after a user **PullToRefresh** sync finishes, the result remains in the page content for **≈5 seconds**, then animates into the root top bar secondary slot. Content area must not keep a permanent failure/success row. Reduce-motion may shorten motion but must still collapse. If top bar is hidden on a route, use an equivalent chrome slot—never fall back to permanent content-area occupancy.
- **Silent triggers:** Foreground / LocalWrite / enter-page throttled sync must not force the same permanent content-area line as pull-to-refresh.
- **Account overview shallow line:** out of the transient-collapse rule; may remain a one-line status on the family card.
- **Member last sync (additive wire):** `GET /v1/family/members` exposes per-member `last_sync_at` (epoch seconds, max active-device `last_used_at`) to **all** authenticated family roles. Device array ACL unchanged (Owner: all devices; ordinary Member: self only). Client `FamilyMember` + `FamilyMemberRow` render human relative time; empty → explicit not-yet-synced copy.
- **last_used_at semantics:** continue treating auth/refresh path updates as “last talked to family server” for this product field; do not require a separate “only after successful full sync cycle” clock unless later product decision tightens it.

## Testing Decisions

- **What good tests assert:** public authority outcomes and user-visible convergence—live versus tombstone sets, winner identity under ordered rules, refusal of record resurrection, presence/absence of neighbor-loser signal, dual-client agreement, toast gating. Tests do **not** assert private helper names, SQL text, source layout, or line counts.
- **Primary acceptance seam (single product seam for authority):** authenticated atomic record-bundle **commit** against an isolated lezi-sync (Store and/or HTTP API). Seed multi-membership live whitelist records, commit, and assert in one transaction boundary:
  - cross-membership window → single live survivor and loser tombstones;
  - Owner-priority, earliest-timestamp, and `client_uuid` tie-break;
  - same-membership exemption;
  - connected component of three overlapping authors;
  - record live-over-tombstone rejected after manual or neighbor tombstone;
  - untouched `(baby, type)` shard not required to collapse until touched;
  - commit response carries explicit neighbor-loser identities when losers are written;
  - fulfillment-created whitelist loser does not unwind plan completion evidence;
  - non-whitelist types unchanged;
  - empty author excluded.
- **Secondary seam (family-visible + hint):** existing Android `SyncPort` / `RealSyncPort` dual-client cycle against the same isolated server. Prove both devices converge on the server winner/tombstones; losing author receives at most one light hint driven by the explicit signal; ordinary remote delete without neighbor signal does not use neighbor copy; higher local dirty live cannot resurrect a server record tombstone after settlement.
- **Prior art to extend, not replace:** Store/API tests for `*TombstoneResurrection` on non-record entities; atomic bundle commit; authoritative reconcile disposition tests; open-sleep domain uniqueness style invariants; RealSyncPort dual-client and CAS receipt tests; 0.3.9 protocol cutover release gates (fmt/test/clippy, assemble, signed APK channel, TLS-preserving CD).
- **Regression gates:** full Android unit/instrumentation suites required by the modules touched; Rust `fmt --check`, `test --locked`, `clippy -D warnings`; do not re-litigate the entire 0.3.8 disposition matrix unless a disposition mapping changes because of record tombstone-wins.
- **Negative / boundary tests:** equal thirty-minute boundary counts as neighbor; thirty minutes plus one millisecond does not; nursing versus formula not neighbors; commit with only care_plan/media skips neighbor; process-safe idempotent recommit does not flip stable winners without new live inputs.
- **Release acceptance:** version bump consistency, app-update metadata matches signed APK, optional min_supported raise only after channel proof, NAS CD only after explicit confirmation with pre/post SPKI equality. Live family NAS is not used for destructive resurrection or certificate experiments.
- **Optional backfill:** if implemented, one fixture proves a multi-double family collapses once under the backfill path without deleting non-neighbor history.
- **Shallow chrome tests:** shared component used by three pages; pull finish → content visible → after ~5s content gone / top-bar secondary present (injectable clock); silent sync does not force permanent content row; failure copy not permanently pinned in content.
- **Member last-sync tests:** lezi-sync members list projection for owner and ordinary member both include every member’s `last_sync_at`; ordinary member still has null/omitted `devices` for others; Android parse + roster row empty/non-empty copy.

## Out of Scope

- Conflict dialogs, manual merge UI, keep-both product flows, or silent payload merge into the winner.
- Client-side optimistic neighbor tombstones or pure multi-master CRDT rewrite (Automerge/Yjs).
- Full-table neighbor rescan on every push as the default algorithm.
- Changing open-sleep uniqueness, fulfillment winner ordering, immutable fulfillment evidence freeze, atomic media package rules, or membership hard-delete author blanking beyond excluding empty authors from neighbor groups.
- Raising neighbor window configurability, UI-category (feed/diaper mega-type) matching, or payload-similarity heuristics.
- Restoring tombstoned record UUIDs via any product “undelete” in this release.
- Rolling back plan completion when a fulfillment-created record loses neighbor adjudication.
- Background sync daemon, push notifications, or non-foreground sync redesign.
- TLS rotation, bootstrap secret rotation, disaster-restore redesign, or production NAS destructive certificate tests.
- Clearing Room, deleting the family, or hand-editing production SQLite as an upgrade step.
- Replacing SyncPort/Store deep façades with a second sync stack.
- Making the account-page shallow status transient (5s collapse) or adding a dedicated sync-center page.
- Exposing other members’ full device rosters to ordinary members; login-history or audit-grade second-precision activity logs.
- Background sync daemon, push notifications, or non-foreground sync redesign (already out; restated for chrome tickets).

## Further Notes

- Confirmed test seams for this tracker: (1) server commit/Store as the sole authority acceptance seam; (2) thin RealSyncPort dual-client + explicit-signal toast seam; (3) data-page shallow chrome + members `last_sync_at` product surfaces; (4) existing release/CD gates.
- User wording “3.10.0” / “3.10.0 的 spec” is product **0.3.10** (`0.3.9` → `0.3.10`).
- Tickets: `issues/01`–`06` under this directory; former standalone tracker
  `sync-chrome-and-member-last-sync` is merged here (do not re-open a parallel tracker).
- Success means: after joined clients on 0.3.10 complete sync against a 0.3.10 server, whitelist cross-member near-duplicates collapse per ADR-0018, record tombstones cannot be LWW-restored, losers’ authors may see one honest light hint, data pages use unified transient shallow chrome, and every role can read each member’s last sync time on the roster—without data wipe or identity rotation.
