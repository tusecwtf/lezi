# 家庭事实无损因果同步

Status: local-partial — tree runtime landed; release acceptance residual on 09

Baseline: `5bc07bbb29806efe895babdfc37305cd48155f6c`

Target: Android / server `0.3.13`、versionCode `20`、Room `27`、server schema
`12`。这些值已按当前 0.3.12 / versionCode 19、Room 26、server schema 11
重新核对；实现与发版开始时仍须再次对照实时发布清单。

Authority: `CONTEXT.md` 的 Record、护理事实、家庭 wake、近邻重复与同步术语；
`docs/prd/` 的数据模型、可信同步、技术与汇总合同；ADR-0012、0013、0017、0018。
本规格取代未提交的 `sleep-wake-lossless-sync` 窄方案，并要求实施时修订其中已被本决策
废止的 LWW、近邻 tombstone、开放睡眠修复与 tombstone 永胜表述。

## Problem Statement

家庭成员会在多台手机、不同网络状态和不准确的设备墙钟下记录同一宝宝。当前家庭同步把
同 UUID 的可变实体整行交给 `updated_at` LWW：只要另一台设备的墙钟或修订更大，本机对备注、
剂量、时间、照片、删除状态乃至醒来事实的编辑就可能在 pull 或 commit 中静默消失。现有
head-by-UUID reconcile 能证明谁是当前服务器赢家，但它仍复用同一 LWW 规则，只能可靠终结
dirty，不能保证用户事实无损。

不同 UUID 也存在更严重的数据损失。当前服务器把跨成员、同类型、30 分钟内的近邻记录解释为
同一护理事件，按 Owner、事件时间和 UUID 自动选胜者，再永久 tombstone 其余记录。两位照护者
若实际记录的是两次独立护理，服务器仍可能在无人确认的情况下删除其中一条，备注和照片也不会
并入胜者。服务器因此越过认证与约束边界，替家庭判断了哪条护理事实“更真实”。

睡眠又把两个独立事实压进同一 Record 行：SleepStart 与实际醒来观察通过可空
`end_timestamp` 竞争。较新的 remote open 可以覆盖离线 wake；多个成员观察到不同醒来时间时
只能整行 LWW；多条开放睡眠还会被本机或 pull 修复逻辑自动改写为闭合，掩盖真实重叠。以
sleep open→closed 单向转换做窄修复，只保护一种字段组合，不能解决任何其它整行编辑、删除、
照片或跨 UUID 误判。

当前 LocalWrite 虽声明低延迟发布意图，`ReplicaSyncEngine` 仍无条件先 pull。直接移除 pull
会缩短窗口，却无法让覆盖变安全：在没有因果 base、三方合并和持久冲突分支之前，更快的 push
仍可能只是更快地丢失另一端事实。

家庭需要的不是更聪明的墙钟赢家，而是可解释的因果同步：无冲突修改自动收敛，真正并发的
修改完整保留，删除不能伪装成永恒真理，疑似重复只能提示和显式确认，任何“已同步”都必须有
稳定版本证据。

## Solution

把所有可变家庭原子根升级为服务器签发版本的因果合同。Baby、Record、CarePlan、CustomItem、
WakeObservation 及其原子媒体清单都携带 opaque `base_version`；客户端为一次冻结修改生成稳定、
幂等的 `mutation_id`。`updated_at` 继续作为用户可见编辑时间和审计内容，事件时间继续用于展示、
校验与业务计算，但两者都不再充当通用冲突顺序。FulfillmentCandidate 保持不可变候选证据模型。

服务器保存稳定快照、不可变版本、冲突分支和 resolution。reconcile 只回答当前冻结修改是已确认、
可发布、会形成冲突预览还是被拒绝；commit 原子地返回 accepted、merged 或 branched，并总是携带
稳定版本、完整稳定 root/media 与冲突引用。pull 只投递稳定投影、`version_id` 和冲突摘要；冲突
详情按需读取，作者或 Owner 用预期分支版本 CAS 解决。旧 `entities` 仍是快速稳定投影，普通调用方
不需要遍历版本图。

同 UUID 提交以共同 base 做三方合并。双方改动的 canonical JSON 路径和媒体成员互不相交时自动
合并；同一路径不同值、删除与并发编辑、同一媒体的删除与修改形成持久分支。分支创建、媒体字节
保存和冲突摘要必须与 commit 原子。冲突期间稳定快照保持最后已确认版本；所有分支、来源与照片
都保留。resolution 只要求选择真正冲突的字段，已经自动合并的字段不会被重新询问。

删除也服从因果关系。基于当前稳定版本的删除可直接成为稳定 tombstone；与同一旧 base 的离线
编辑是真并发，必须保留删除和编辑分支。已经稳定删除的普通旧 replay 不得自动复活或制造重复
分支，只有经授权的显式 conflict resolution 可以恢复同 UUID。现存且无法证明来源的历史 Record
tombstone 保持隐藏，不批量复活，也不新增历史恢复入口。

睡眠拆为 SleepStart 与 `WakeObservation`。新 Sleep wire 根不再同步 `end_timestamp`；每次醒来
记录都是关联 Sleep UUID 的独立观察，包含实际醒来时间、服务器盖章的观察者、备注和照片。多个
观察全部保留；未确认时，投影使用最早合法观察生成暂定区间并展示所有观察，Sleep 作者或 Owner
可以确定有效观察。观察者可撤回或修正自己的观察。新的重叠开放睡眠不再被自动闭合：最新开始
仍是醒来入口，较旧条目显示“重叠待确认”。

不同 UUID 的近邻记录不再由服务器自动落选。客户端沿用精确类型白名单、跨 membership 和 30
分钟规则生成“疑似重复组”，默认展开并保留全部原记录。未确认组的汇总对所有合法解释运行既有
聚合规则并显示上下界。作者只能声明自己的记录与另一来源相同；Owner 可解决整组。确认同一事件
后选择一个展示版本，其他来源、作者关系和照片永久保留，不进入普通 tombstone。

因果协议落地后才启用 LocalWrite 快速路径。前台、可信 endpoint、健康租约允许时，直接冻结当前
待对账原子单元并 reconcile/commit，不先 pull，也不推进 pull cursor。远端变化只能自动无损合并
或建立分支，不能覆盖本机。回前台、网络恢复、下拉刷新和常规周期仍执行完整 pull，保证其它家庭
变化最终到达。

这是一次强制原子协议切换。先产出并验证同签名 Release APK 与更新 metadata，再在经确认的维护
窗口停服和加密备份；受审计的离线迁移器把 server v11 复制迁移并验证为 v12，随后部署新服务并
把 minSupported 提到 versionCode 20。旧客户端在升级前不能护理同步。失败回滚只能恢复完整 v11
备份和旧镜像，旧二进制绝不能打开 v12。

## User Stories

1. As a caregiver, I want every offline edit to carry the version I actually saw, so that another phone's clock cannot silently erase my work.
2. As a caregiver, I want edits to different fields of the same fact to merge automatically, so that harmless concurrency needs no manual work.
3. As a caregiver, I want two different values for the same field to remain as two branches, so that neither observation is discarded.
4. As a caregiver, I want notes, amounts, timestamps and type-specific details to follow one conflict rule, so that safety does not depend on which editor I used.
5. As a caregiver, I want photos to participate in the same atomic decision as their record, so that metadata and media cannot resolve differently.
6. As a caregiver, I want independent photo additions to merge, so that family members can add evidence without overwriting one another.
7. As a caregiver, I want deleting a photo while another member edits that photo to create a conflict, so that bytes are not silently destroyed.
8. As a caregiver, I want a based-on-current delete to take effect normally, so that causal safety does not make ordinary deletion cumbersome.
9. As a caregiver, I want an offline edit concurrent with deletion to survive as a branch, so that the family can decide whether the fact should remain.
10. As a caregiver, I want a stale replay after a settled deletion not to resurrect the record, so that retries cannot undo a family decision.
11. As a caregiver, I want an explicit authorized resolution to be the only way to restore a deleted UUID, so that resurrection is deliberate.
12. As a caregiver, I want an already accepted mutation replay to return the same result, so that a lost response does not duplicate versions or media.
13. As a caregiver, I want an interrupted process to resume from durable versions and branches, so that crashes cannot convert uncertainty into data loss.
14. As a caregiver, I want the pending indicator to clear only when my exact mutation is confirmed, merged or retained as a visible conflict, so that “synced” is truthful.
15. As a caregiver, I want a conflict badge and summary without a blocking save dialog, so that recording care remains fast.
16. As a record author, I want to resolve conflicts on my own record, so that routine corrections do not always require the Owner.
17. As a family Owner, I want to resolve any family fact conflict, so that abandoned or unavailable authors do not block the family.
18. As a non-author member, I want existing edit ACLs to remain enforced, so that conflict preservation does not grant broader management rights.
19. As a resolver, I want already auto-merged fields excluded from the choice UI, so that I only decide genuine disagreements.
20. As a resolver, I want resolution to use expected branch versions, so that a newly arrived edit cannot be overwritten by an old screen.
21. As a family member, I want peers to pull only a stable projection plus a concise conflict summary, so that normal timelines do not depend on a version-graph client.
22. As a family member, I want conflict details loaded only when I open them, so that routine synchronization remains bounded.
23. As a caregiver, I want recording wake to create an independent observation, so that an open SleepStart cannot overwrite the fact that I saw the baby awake.
24. As a caregiver, I want a WakeObservation to keep its actual time, observer, note and photos, so that its provenance remains understandable.
25. As a caregiver, I want multiple wake observations retained, so that clock skew or differing observations do not force an arbitrary winner.
26. As a caregiver, I want an unconfirmed sleep interval to use the earliest legal wake observation, so that the temporary display and statistics are conservative and deterministic.
27. As a caregiver, I want to see every wake observation while the effective one is undecided, so that the projection is not mistaken for the only source.
28. As the wake observer, I want to correct or withdraw my own observation, so that an input mistake can be repaired without editing another member's fact.
29. As the Sleep author, I want to determine the effective wake observation, so that I can settle the interval I originally started.
30. As a family Owner, I want to determine the effective wake observation, so that the family can settle an abandoned sleep.
31. As a caregiver, I want an invalid wake before the SleepStart rejected, so that no projection produces a negative interval.
32. As a caregiver, I want creating another open sleep not to rewrite older SleepStart facts, so that real overlap remains visible.
33. As a caregiver, I want the latest open SleepStart to remain the wake shortcut target, so that the frequent path stays simple.
34. As a caregiver, I want older overlapping starts marked for confirmation, so that ambiguity is visible without blocking a new entry.
35. As an upgrading family, I want historical closed sleeps represented identically after migration, so that durations, notes, photos and authorship are preserved.
36. As a caregiver, I want two nearby records with different UUIDs to remain separate until a person confirms duplication, so that two real events are never deleted by a heuristic.
37. As a caregiver, I want suspected duplicates grouped and expanded, so that I can compare every original source.
38. As a caregiver, I want the 30-minute rule and exact wire-type whitelist retained as a hint, so that familiar likely duplicates are still discoverable.
39. As a caregiver, I want same-membership repeated entries excluded from cross-member grouping, so that one person's real sequence is not mislabeled.
40. As a record author, I want to declare my own record equivalent to another source, so that I can correct an accidental double entry.
41. As a family Owner, I want to resolve an entire suspected duplicate group, so that the ordinary timeline has one agreed display version.
42. As a family member, I want every non-displayed duplicate source and photo retained after resolution, so that consolidation is not deletion.
43. As a family member, I want a resolved duplicate source excluded from ordinary tombstone behavior, so that later history cannot misclassify why it is hidden.
44. As a caregiver, I want unresolved duplicate groups counted as a range, so that summaries do not claim false precision.
45. As a caregiver, I want a resolved group to return to one deterministic aggregate value, so that reports stabilize after family confirmation.
46. As a caregiver, I want saving locally to remain immediate when the NAS is offline, so that recording care never waits on synchronization.
47. As a caregiver on a healthy LAN, I want my changed atomic units published without a preliminary pull, so that family visibility is fast.
48. As a caregiver, I want the fast path to merge or branch against remote changes, so that low latency never weakens losslessness.
49. As a caregiver, I want the fast path not to advance the family pull cursor, so that other members' changes are still downloaded later.
50. As a caregiver, I want foreground and manual refresh cycles to keep doing a full pull, so that the fast path cannot starve remote facts.
51. As a caregiver, I want transport, capability and CAS failures to retain local pending state, so that failed publication stays recoverable.
52. As an operator, I want every mutable request bounded and idempotent, so that retries and frequent care entries remain safe for the home NAS.
53. As an operator, I want the server to enforce authentication, ACL, authorship and references without choosing which care observation is true, so that authority stays within its proper boundary.
54. As an operator, I want conflict and media retention included in backup and restore, so that disaster recovery preserves all family evidence.
55. As a release reviewer, I want old clients blocked before the new wire is accepted, so that mixed protocol generations cannot corrupt the family graph.
56. As a release reviewer, I want Room 26→27 to preserve all local rows, dirty work, media, session and endpoint trust, so that upgrading never requires reset or rejoin.
57. As a release reviewer, I want server v11→v12 migration to be copy-out, validated and reversible only by full backup restore, so that schema cutover fails closed.
58. As a release reviewer, I want two-client isolated-server evidence for merge, conflict, wake, duplicate and fast-path cases, so that unit tests cannot masquerade as family convergence.
59. As a family using the NAS, I want deployment to preserve the bootstrap secret, data bind, TLS certificate and SPKI, so that a sync upgrade does not change server identity.
60. As a family using the NAS, I want an explicit maintenance-window confirmation before stop/rm/replace, so that service interruption is deliberate.

## Implementation Decisions

- Canonical vocabulary:
  - **家庭事实** is a user-meaningful observation or management decision retained with provenance.
  - **稳定快照** is the last conflict-free or explicitly resolved atomic root visible to ordinary pull.
  - **版本** is a server-minted immutable snapshot identified by opaque `version_id`.
  - **因果基线** is the `base_version` the client read before forming a mutation.
  - **修改身份** is a client-generated `mutation_id` reused unchanged across retries.
  - **冲突分支** is a durable competing version that is preserved but not silently projected as the stable fact.
  - **来源关系** preserves original roots/media after explicit duplicate consolidation; it is not a tombstone.
  - **疑似重复组** is a client-visible heuristic group, not a server verdict.
- Server authority is limited to authentication, family ACL, immutable author/observer stamping, reference and media integrity, protocol bounds, synchronization ordering and explicit management operations. It must not infer which care value or different-UUID event is more true.
- Versioned mutable atomic roots are Baby+avatar, Record+record media, CarePlan+plan media, CustomItem, and WakeObservation+wake media. FulfillmentCandidate remains immutable evidence and is referenced, not three-way edited.
- `updated_at` remains editable/auditable content and may order presentation where already specified. It is not a concurrency token or generic winner rule. Event timestamps remain business facts only.
- Each client mutation contains the complete canonical atomic root/media desired state, its opaque `base_version`, and a stable `mutation_id`. A retry with the same mutation ID and content returns the original result; reuse with different content fails closed.
- Reconcile returns one of `confirmed`, `publish`, `conflict_preview`, or `rejected`. It is a bounded dry run and does not create stable versions or branches. Its authority proof is tied to generation, stable version and request hash.
- Commit revalidates the proof and returns `accepted`, `merged`, or `branched`, plus the stable `version_id`, complete stable root/media manifest and optional conflict references. A concurrent authority change causes safe re-evaluation or an explicit retry; never a successful no-op with an unrelated head.
- Pull returns stable atomic snapshots with `version_id` and a bounded conflict summary. Conflict branches and bytes are not included in ordinary pages; conflict detail is fetched through an authenticated bounded endpoint.
- Conflict resolution names the expected stable version and complete expected branch-version set. Author or Owner may resolve same-UUID Record/WakeObservation conflicts within existing ACL; Owner retains management authority for all family roots. CAS mismatch returns the latest summary without changing state.
- Three-way merge compares base→current stable and base→incoming changes at canonical JSON leaf paths. An identical resulting value is not a conflict. Object member changes on disjoint paths merge; arrays and scalar values are atomic unless that field has a separately documented canonical keyed representation.
- Media manifests merge by stable media UUID. Independent additions/removals on different UUIDs merge. Concurrent change and deletion of the same media UUID, association drift, byte/hash drift, or root/media reference disagreement branches the entire atomic root. All referenced bytes are retained before a branch is acknowledged.
- Automatic merge produces one new immutable version whose parents/provenance name both inputs. Only actual conflicting paths remain selectable during resolution; merged paths are frozen in the conflict base presented to the resolver.
- A current-base delete is accepted as a stable tombstone. Delete/edit from the same live base creates branches. If a tombstone is already stable, an exact or causally older ordinary live replay cannot restore it; a proven concurrent edit may be retained as a conflict branch, but stable visibility changes only through explicit resolution.
- Existing historical Record tombstones have no trustworthy cause classification. Migration preserves them as hidden stable tombstones, does not synthesize branches, and adds no bulk recovery surface.
- The server adds immutable version, conflict, branch, resolution/idempotency, and duplicate-source relation storage. The existing entity table remains the transactionally maintained stable projection and pull cursor owner.
- Server schema v12 is fresh-current at runtime. v11→v12 is supported only by the audited offline copy-out migrator; application startup continues to reject mismatched schemas.
- Android Room 27 stores the server base version on mutable roots, stable mutation identity for frozen dirty epochs, WakeObservation, conflict summaries/details needed offline, duplicate groups/source relations and media ownership/reference state.
- A local edit keeps the last acknowledged `baseVersion` while its local content revision changes. Only confirmed/accepted/merged/branched acknowledgement for the exact frozen mutation may advance the base and clear or reclassify pending state.
- `branched` is a successful lossless publication outcome, not a transport failure: the exact local branch is durable on the server, local pending becomes a visible unresolved-conflict state, and ordinary sync need not resend it.
- WakeObservation is a first-class mutable root keyed independently and references one Sleep Record UUID. The server stamps observer membership; the observer may edit/withdraw their observation, while the Sleep author or Owner may choose the effective observation.
- New Sleep Record wire roots contain SleepStart fields only and do not synchronize `end_timestamp`. Timeline, open-state and duration are projections over SleepStart, non-withdrawn WakeObservations and an optional effective-observation resolution.
- Before an effective observation is chosen, the earliest observation at or after the SleepStart is the deterministic provisional end. All valid observations remain visible; observations before the start are rejected and never create negative intervals.
- Creating or pulling another open SleepStart does not close, edit or tombstone an older one. The latest start is the default wake target; older overlapping starts remain visible with an unresolved-overlap affordance.
- Historical closed Sleep migration creates a deterministic WakeObservation identity from the Sleep UUID and legacy closed revision. It transfers the legacy wake time, note, photos and immutable author/observer provenance so the projected row remains user-equivalent. Historical open sleeps create only SleepStart versions.
- Server near-neighbor adjudication, Owner/earliest/UUID winner selection, neighbor-loser tombstones and `neighbor_losers` response semantics are removed from the new capability generation.
- The client reuses the exact-type whitelist, cross-membership requirement and inclusive 30-minute graph solely to create soft suspected-duplicate groups. No group operation mutates source records before an explicit authorized declaration/resolution.
- An author may submit equivalence only for a record they authored. Owner may resolve a complete group. A same-event resolution selects one display version and stores every other root/media as a source relation; it never converts those sources to ordinary record tombstones.
- Unresolved duplicate aggregates expose a metric-specific interval by evaluating the existing aggregation semantics across allowed interpretations from one selected display source through all sources being independent, then presenting the minimum and maximum. Resolved groups use the selected display version only.
- Existing deep façades remain: domain writes flow through CareLog; synchronization through SyncPort/RealSyncPort and ReplicaSyncEngine; server persistence and adjudication through Store. No screen, DAO or HTTP handler becomes a second merge-policy owner.
- LocalWrite remains a post-transaction notification. Under foreground/trusted-endpoint/health-lease gates it freezes current dirty atomic roots and performs causal reconcile/commit without pull. It does not advance the incremental pull cursor.
- Foreground entry, network recovery, pull-to-refresh and ordinary full cycles retain pull plus causal settlement. Background writes stay local. Failure or cancellation never rolls back the successful local care transaction.
- The protocol change is capability-gated and intentionally not dual-read. Android/server target 0.3.13 and versionCode 20 are the first generation that understands the new entity set and causal fields; the verified update channel must exist before minSupported becomes 20.
- Room 26→27 is an in-place migration preserving Room data, media files and paths, local dirty epochs, local-only facts, family session, credentials, endpoint origin and TLS/SPKI trust. No reset/full-resync may be used as a shortcut for migration correctness.
- Production cutover is stop-the-world for sync writes: validated signed APK/update pair first, encrypted credential/TLS backup before replacement, offline v11→v12 copy migration and validation, new server start, minSupported 20 enforcement, then joined-client smoke.
- Rollback restores the complete pre-cutover v11 database/data snapshot and deliberately selected old image. An old binary never opens v12, and a schema-12 write set is never copied into v11.

## Testing Decisions

- A good test asserts observable family behavior at a public seam: durable stable versions, retained branches/media, projected timeline, pending/conflict state, aggregate range and peer convergence. It must not assert private coroutine structure, SQL statement order, package layout or line counts.
- The primary acceptance seam is one CareLog write synchronized through the existing public sync façade against an isolated real lezi-sync, followed by pull on a second joined client. This single chain owns the release claim.
- Store behavior tests cover version creation, mutation idempotency, reconcile proofs, atomic accepted/merged/branched commit, pull summaries, conflict detail, resolution CAS, ACL, bounds and transactional media retention.
- Three-way merge tables cover disjoint fields, same field/same value, same field/different value, nested paths, atomic arrays, independent media, same-media delete/edit, base missing, stale base and authority races.
- Deletion tables cover current-base delete, concurrent delete/edit in both arrival orders, lost response, exact replay, causally stale live replay, conflict resolution to delete, explicit restore and historical tombstone preservation.
- Android Room migration device tests start from Room 26 fixtures containing every mutable root, photos, dirty rows, publication receipts, local-only facts, session and trusted endpoint; Room 27 must preserve them and backfill deterministic causal state without destructive fallback.
- ReplicaSyncEngine tests use the existing recording backend adapter to assert one frozen mutation ID/base, exact CAS settlement, branched-as-durable outcome, process restart, full-resync/generation recovery and cursor independence.
- CareLog tests cover WakeObservation creation, observer edit/withdraw, author/Owner selection, invalid pre-start time, multiple observations, provisional earliest projection and photo ownership.
- Existing open-sleep repair tests are inverted: multiple open SleepStarts remain unchanged; the newest is the wake target; older starts surface overlap state instead of receiving synthesized end times/anomaly edits.
- Timeline and Summary tests cover historical migration equivalence, open interval to now, provisional wake duration, multiple wake observations, confirmed effective observation and no double counting of observation sources.
- Duplicate-group tests cover exact whitelist membership, 30-minute inclusive edges, transitive components, same-membership exemption, no server tombstones, expanded source display, author declaration ACL and Owner full-group resolution.
- Summary aggregation tests compute deterministic lower/upper bounds for every affected count/amount metric using existing aggregation semantics, then prove a resolved group collapses to one value without deleting provenance.
- LocalWrite ordering tests assert reconcile→atomic commit for current frozen units with no pull and no pull-cursor change. Control tests cover background, unhealthy lease, cancellation, remote branch creation and a later full pull receiving unrelated family changes.
- Wire/API tests reject missing/unknown/duplicate causal fields, content drift under one mutation ID, incomplete stable snapshots, unbounded conflict requests, invalid version/branch references and media bytes in JSON responses.
- Prior art to extend is the Store reconciliation/bundle/neighbor/schema suites, ReplicaSyncEngine authority/conflict/push-replan suites, RealSyncPort atomic-media/push-pull suites, CareLog sleep/media suites, SummaryAggregation suites and local-data migration device matrix.
- Isolated two-client E2E covers: disjoint edit auto-merge; same-field dual branch; concurrent delete/edit; stale resurrection; atomic photo branch; lost response; process death; resolution CAS race; offline wake; multiple wake; overlapping starts; non-destructive near duplicates; aggregate bounds; and LocalWrite followed by a full pull.
- Release gates are relevant Android unit and instrumentation suites, complete JVM tests, lint, Debug and signed Release builds, Rust fmt/test/Clippy, server migration/rollback rehearsal, protocol E2E and released-contract upgrade preservation.
- NAS deployment is not part of spec publication. When implementation gates pass, the agent must propose the destructive CD window and wait for explicit confirmation. Only after confirmation may build/package/push/replace and joined-device smoke occur.

## Out of Scope

- General-purpose CRDT frameworks, peer-to-peer sync, public-cloud coordination or conflict-free rich-text editing.
- Using event time, `updated_at`, device priority, Owner priority, arrival order or record completeness as a hidden universal winner.
- Automatically resolving a genuine same-field conflict, delete/edit conflict, wake disagreement or cross-UUID duplicate.
- Restoring historical Record tombstones whose deletion cause cannot be proven.
- Reintroducing server-side near-neighbor tombstones under a different name.
- Treating source relations as ordinary deletions or discarding non-selected source photos.
- Portable closer privileges, device-authored observer identity, or broader member edit/delete ACLs.
- Blocking care entry on network success, adding a durable payload outbox, WorkManager polling, FCM or public-internet sync.
- Resetting local data, clearing media, forcing family rejoin, rotating credentials or replacing the NAS TLS identity.
- Online in-place server schema migration, allowing the old server to open v12, or rollback without the complete v11 backup.
- Running certificate mutation, TOFU-change or destructive cutover tests against the family NAS.

## Further Notes

- This spec intentionally supersedes the sleep-only proposal. LocalWrite no-pull is ordered after the causal protocol because latency reduction is not a correctness mechanism.
- ADR-0017 remains the reconciliation skeleton but its LWW verdict vocabulary must be revised. ADR-0018's automated neighbor winner and universal Record tombstone consequences are superseded for new causal data; historical tombstones remain preserved.
- The server may maintain fast projections and indexes, but immutable versions and branches are the recovery evidence. Persisted projection equality alone does not prove a mutation was retained.
- “Stable” does not mean “only truth.” During a conflict it is the ordinary projection; conflict summaries and sources must make competing facts discoverable and resolvable.
- A `branched` commit is successful lossless storage and should not retry forever. Product pending and conflict indicators must remain distinct.
- The release number and schema targets are planning values confirmed at this baseline. Ticket 09 must re-pin HEAD, versions, signed artifact metadata and live protocol immediately before release work.
