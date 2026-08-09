# 因果同步冲突与传输硬化

Status: ready-for-agent

Audit baseline: `332f160d79c439546c6c0e6f83b35a40fe04aa3a`

Authority: `CONTEXT.md` 的家庭事实、稳定快照、`version_id`、`base_version`、
`mutation_id`、冲突分支、来源关系、WakeObservation 与 LocalWrite 术语；
`docs/prd/causal-sync-wire.md`；ADR-0019、ADR-0020、ADR-0021。本文保留
ADR-0019 的服务端边界和 ADR-0021 的事实模型，但重新打开 ADR-0020 中“普通发表必须先
reconcile”以及“客户端同时提交选择和重建结果”的决定；实施票 01 必须先新增 ADR 明确取代范围。

Ownership: 本 tracker 独占固定 HEAD 上发现的冲突快照正确性、N 方合并、冲突恢复、普通发表协议
收缩、重试/握手/pull 传输与媒体一次读取。资源准入、head loader、snapshot receipt/page 与 resolution
retention 分别由现有 `repository-dedup-algorithm-audit-20260809/12`、`17`、`18`、`19` 独占；家庭 NAS 切割与生产双端 smoke 仍由
`lossless-family-causal-sync/09` 独占。

## Problem Statement

当前 tree 已经从墙钟 LWW 前进到因果 base、幂等 mutation、不可变版本、稳定投影、持久分支、
CAS resolution、因果删除、WakeObservation、来源关系和 LocalWrite no-pull。这些基础方向正确，
但固定 HEAD 的真实合同仍不能证明“所有并发事实都可恢复”。冲突详情没有把稳定快照和每个分支的
删除状态作为一等字段；删除标记只存在于 mutation envelope，Android 却从业务 root 读取它。
服务器构造 tombstone restore 时还会把同一路径同时列为冲突和自动合并，而客户端会过滤后一类。
因此删除/编辑分支看似存在，用户却可能没有可提交的恢复选择。

当前自动合并是按分支顺序进行的 pairwise union。多个分支在同一路径提出不同结果时，先写入的
“auto merged”值会被保留，后续不同值可能没有升级为真正冲突；branch UUID 的排序由此意外成为
事实赢家。这不是 N 方合并，也不满足“不用时间戳或偶然顺序裁决护理事实”的产品原则。

可空业务字段还有 wire 歧义：省略字段既可能表示“未改动”，也可能表示“明确移除”；冲突详情把
移除投影为 null，但自动合并没有对应移除表示。resolution 又允许客户端同时提交路径选择和完整
resolved root/media，服务端重建后没有经过与普通 commit 完全相同的 canonical root 校验。
客户端和服务端因此各自承担一半权威，既重复传输，也扩大了无效或分歧结果进入稳定投影的表面。

Android 冲突投影也会丢信息：自动合并的媒体没有进入 resolved media，离线缓存没有完整保存
stable media 与 auto-merged 结果，pull 收到空 conflict summary 时不会原子删除旧 summary/detail。
目前解决入口依附于可见 Record 时间线；tombstone、Baby、CarePlan、CustomItem 与非当前日期事实
没有统一冲突收件箱。即使底层分支保住了，用户仍可能看不到、看不懂或在离线重启后无法继续处理。

普通发表仍执行 reconcile 再 commit，两个请求携带大体相同的冻结 root/media，并分别处理 generation
和 cursor。丢包窗口、状态机和测试矩阵被扩大，却没有增加提交原子性；真正的安全性已经来自稳定
`mutation_id`、请求哈希、base 和 commit 终态。每轮同步还串行探测多个 setup/health 状态并完整拉取
members，pull 没有压缩或客户端页字节预算，429/临时错误也缺少服务端提示驱动的有界全抖动退避。

媒体发表会为计算哈希、上传和重试重复读取来源。服务端接收或 commit 时若在 family lock / SQLite
写事务附近做大字节哈希，慢存储会把一个媒体对象的成本扩散到全家庭同步。来源 URI 在重试之间还
可能改变，使同一 mutation 的字节、摘要和请求哈希不再来自同一不可变输入。

需要一次协议硬化，而不是引入 CRDT 或新的最终赢家：服务器必须对完整分支集执行确定性的 N 方
路径合并，只自动合并所有并发头一致的结果；冲突快照必须完整、可缓存、可分页且能表达删除和移除；
客户端只提交快照 token 下的选择，服务端独立重建并验证稳定结果。普通发表应收缩为媒体预备加一次
幂等 commit，并用单次能力握手、明确重试预算、压缩/字节上限与不可变媒体 spool 提升弱网稳定性。

## Solution

发布一个 fail-closed 的下一代因果能力合同。实现开始时重新 pin Android/server 版本、Room schema、
server schema、release floor 与生产协议；若当前未部署因果版本的前提仍成立，新客户端和服务端只实现
新能力，不保留双读双写。普通同步在完成 endpoint trust 和家庭会话后发起一次认证能力握手，获得协议
能力、服务就绪状态、当前 principal/role、目录 generation、分页/上传限制和服务器退避提示。运维
health/ready 可以保留，但不再成为每轮客户端同步的串行前置请求；成员目录只在 generation 改变或
用户显式刷新家庭管理时读取。

### 0.4.0 release and upgrade contract

本 hardening 作为一个原子版本交付：Android `versionName=0.4.0`、`versionCode=21`、Room `28`、
local-data contract `5`；`lezi-sync=0.4.0`、server `user_version=13`；verified app-update 与
`PROTOCOL_CUTOVER_CLIENT_VERSION_CODE`/`min_supported_version_code` 同时抬到 `21`。当前 tree 的
`0.3.13` / code 20 / Room 27 / server 12 是升级源，不是最终版本。

Android 必须以相邻 Room 27→28 migration 原地保留全部业务事实、tombstone、pending mutation、
frozen envelope、conflict snapshot、media/spool、family session、credentials、endpoint origin 与 TLS trust；
永久 versionCode 6 / Room 24 基线继续通过既有连续迁移链到 28，失败不得 destructive fallback 或强制 rejoin。

后端启动仍只接受精确 current schema 13，不在 startup 或 ordinary CD 自动改库。当前家庭数据库必须在
显式维护窗通过 ADR-0013 的 copy-out `offline-migrate` 升级：预检只接受明确支持并完整验证的 source
`user_version=11` 或 `12`，写入新的 staging data root，验证行数、关键事实、branch/conflict、media 引用、
identity/session 与完整性后才 copy-back。源 data root 与旧 image/package 保持为可复验 rollback；其它
source version、shape、WAL/SHM 不完整或媒体缺失一律在 stop/rm 前 fail closed。

`0.4.0` schema cutover 是受保护 CD 中单独授权的维护步骤，不改变 ordinary CD 禁止运行
`offline-migrate` 的规则。它必须在开发机完成 image/package/migrator，先发布可安装且签名匹配的 code 21
APK/metadata，再取得 NAS lease、完整数据与 credential rollback 备份、记录 TLS certificate/SPKI，停服、
copy-out、migrate、validate、copy-back、启动 schema 13、复核 TLS/数据/health/ready，最后才开放客户端写入。
任何失败在开放新写入前恢复原 schema 数据根与旧 image；生产执行仍需 `lossless-family-causal-sync/09`
和一次新的明确维护窗口确认。

把业务根 canonical 化：所有已知可空字段在 wire 上必须显式出现，值为具体值或 null；缺少已知字段、
未知字段、错误类型和不合法领域组合都 fail closed。业务字段 outcome 只有 typed set，其中清空可空字段
是 `set(null)`；`remove` 只用于媒体成员移除和 deletion transition，不能再让“字段缺失”兼任两个含义。普通 commit 和 conflict
resolution 共用同一个 canonical decoder、领域约束、媒体校验和稳定投影构建器。diff 先归一为不重叠
canonical leaf/subtree outcome；父对象 `set(null)` 与并发子字段编辑必须冲突，除非完整 canonical 子树
结果相同，不能因 JSON path 前缀不同而误判为互不相交。

新的 ConflictSnapshot 是一个完整、不可损压缩的事实视图。stable 与每个 branch 都携带 root、media、
deleted、version/base、mutation provenance 和服务端接收元数据；冲突路径与自动合并路径严格不相交。
每个真正冲突路径列出完整候选，每个候选包含 opaque choice ID、typed outcome 和来源集合。快照还携带
绑定 family/root、stable version、完整 open branch set、分页视图和合同版本的 opaque snapshot token。
该 token 是由服务端持久 receipt 支撑的随机标识，跨重复 detail、分页和服务重启保持可用，直到过期或
branch/stable 状态变化；它不是客户端自包含声明，也不是单次使用。分页不得允许客户端只针对局部分支解决。

服务端对稳定头和所有 open branch 一次计算 N 方 merge。每个头相对其因果 base 产生路径 outcome；
没有改变的头不反对改变。所有改变头只有一个 distinct outcome 时自动合并，存在两个或更多 distinct
outcome 时必须成为冲突；历史不可比较或不能完整加载时保守冲突或 fail closed。结果与 branch 枚举、
到达顺序、UUID 排序和设备墙钟无关。自动合并结果、冲突候选、媒体集合和删除状态由同一计算产生。

resolution 请求只携带 snapshot token、稳定的 resolution mutation ID，以及每个冲突路径的 choice ID。
服务端验证调用者 ACL、token 的完整 branch set、每个 choice 的成员关系和所有路径恰好选择一次，再从
快照独立重建 root/media/deleted，执行 canonical/领域/媒体验证并 CAS 提交。客户端不再传重复的
resolved root/media。纯 tombstone restore 只能选择 tombstone mutation 明确声明的直接 `base_version`，
且该 base 必须是完整 live root 及完整媒体；不得搜索祖先或猜测多 parent。parent 不完整时拒绝恢复，
恢复成功后如需改字段，用户再发起普通编辑。

Android Room 原子缓存完整 ConflictSnapshot、continuation 状态和 token，不再拆成会丢 stable media 或
auto-merged outcome 的弱投影。pull 的 stable root、summary 和 detail/cache 在同一事务收敛；服务器明确
表示无冲突时清掉旧 summary、detail、continuation 和不可复用 token。产品在家庭页同步状态行提供 badge
与全局冲突收件箱入口，并在 Record 时间线保留上下文入口；收件箱覆盖 Baby、Record、CarePlan、
CustomItem、WakeObservation 五类可变根及其 tombstone 状态，显示实体类型、宝宝、作者/设备、接收时间、删除/恢复、照片缩略
信息与字段级候选。离线可读但不可提交过期 token，回网后刷新再解决。

普通发表改为 commit-first。客户端从 Room 冻结一个原子根和稳定 mutation ID；若有媒体，先从来源一次
顺序读取到应用私有不可变 spool，同时计算 digest/length，再按摘要幂等预备上传。服务端流式写入临时
对象并在 family lock / 主写事务外计算摘要，完成后签发绑定家庭、principal、digest、length 和过期时间
的 durable receipt。commit 只引用 receipt 和 canonical media metadata；短事务验证 receipt 后原子接受、
合并或建立分支。响应丢失时以完全相同 mutation/request hash 重放，不重新读取来源。reconcile 不再是
新能力普通发表的一部分，也不推进 cursor；如未来需要只读预览，必须另立产品用例和合同。

传输仍使用 JSON，避免在一次局域网协议硬化中同时引入 Protobuf/CRDT。握手协商 gzip 和明确的 count/
byte/page 限制；pull 响应按页压缩，客户端同时执行解压后字节上限和 continuation 单调性检查。429/503
优先遵守合法 Retry-After，否则采用有上限的指数 full jitter；重试预算按操作类别区分，非幂等请求不
盲重试。认证、能力不匹配、canonical 错误、过期 token 和权限错误是终态，不伪装成弱网 pending。

资源有界性与本规格共同交付但不重复建票：外部资源票 12 定义 commit limiter 与每根 open branch cap，
17 定义 bounded branch/base batch loader，18 定义 ConflictSnapshot receipt、continuation、响应字节
上限与完整 branch-set token，19 定义 resolution 查询预算和 metadata retention。本 tracker 的
服务端与 Android 实现必须消费该合同。最终验收以唯一跨层 seam 为准：一端经 CareLog、SyncPort/
RealSyncPort、ReplicaSyncEngine 写入开发者拥有的隔离真实 lezi-sync，第二个 joined client pull、看到
稳定投影或冲突、完成 resolution 并由第一端再次收敛。Store、HTTP、Room 和录制后端测试是支撑证据，
不能替代该链。

## User Stories

1. As a caregiver, I want concurrent edits to different fields to merge without asking me, so that routine offline work stays low-friction.
2. As a caregiver, I want concurrent edits to the same field with different outcomes to remain visible, so that no arrival order silently chooses a family fact.
3. As a caregiver, I want three or more phones to be evaluated together, so that pairwise branch ordering cannot lose a later participant's intent.
4. As a caregiver, I want every conflicting candidate to show who and what produced it, so that I can make an informed choice.
5. As a caregiver, I want stable and branch photos to remain attached to their exact candidates, so that text resolution cannot discard media evidence.
6. As a caregiver, I want a deleted fact and an offline edit to appear as distinct choices, so that deletion does not silently win or revive.
7. As a caregiver, I want an explicit restore choice for a causally retained live parent, so that accidental deletion can be reversed safely.
8. As a caregiver, I want restore to fail when the original bytes or parent snapshot are incomplete, so that the app never invents a care fact.
9. As a caregiver, I want a restored fact to match its retained parent before I edit it again, so that restore and rewrite are auditable separate actions.
10. As a caregiver, I want optional values such as notes to distinguish empty, null, and unchanged, so that clearing a field survives sync.
11. As a caregiver, I want an automatically merged field clear to reach every device as set(null), so that omission is not mistaken for no change.
12. As a caregiver, I want a conflict resolution to cover exactly the branch set I reviewed, so that a new offline branch cannot be ignored mid-resolution.
13. As a caregiver, I want stale conflict details to disappear when the server says the conflict is resolved, so that I do not act on obsolete state.
14. As a caregiver, I want conflict details to survive an offline app restart without losing candidates, so that I can review them before reconnecting.
15. As a caregiver, I want the app to refresh an expired conflict snapshot before submission, so that offline review cannot overwrite newer facts.
16. As a caregiver, I want one conflict inbox across Records, Babies, CarePlans, CustomItems, wake observations, and tombstones, so that hidden entities are still resolvable.
17. As a caregiver, I want timeline conflict badges to open the same canonical detail as the inbox, so that there is one resolution model.
18. As a caregiver, I want deleted and restored candidates to use explicit language, so that I do not confuse null content with deletion.
19. As a caregiver, I want photo additions from compatible branches to auto-merge, so that I do not reselect harmless media changes.
20. As a caregiver, I want a photo delete/edit collision to remain a real conflict, so that an edited photo is not silently removed.
21. As a caregiver, I want conflict choices to show author, device, and server receipt context, so that generic branch labels are understandable.
22. As a caregiver, I want sync to preserve my existing Room data, media, family session, endpoint trust, and credentials, so that a protocol upgrade does not force rejoin or data loss.
23. As a caregiver, I want the app to remain honest about pending work after a timeout, so that a lost response is not reported as failure or success prematurely.
24. As a caregiver, I want an identical retry to settle to the original commit result, so that tapping retry cannot duplicate a mutation.
25. As a caregiver, I want a changed payload under the same mutation ID to be rejected, so that idempotency cannot hide inconsistent bytes.
26. As a caregiver, I want foreground LocalWrite to keep its no-pull latency advantage, so that hardening does not restore a blocking full pull.
27. As a caregiver, I want ordinary publish to use one final commit after media preparation, so that weak networks have fewer failure windows.
28. As a caregiver, I want remote changes discovered during commit to merge or branch safely, so that removing reconcile cannot reintroduce overwrite.
29. As a caregiver, I want pull cursor movement to remain independent of local publish, so that a commit response cannot skip remote changes.
30. As a caregiver, I want the app to honor server retry timing, so that a busy family service is not hammered by synchronized retries.
31. As a caregiver, I want retries to spread with bounded jitter, so that several family phones recover without a retry storm.
32. As a caregiver, I want authentication and protocol incompatibility to surface as actionable terminal states, so that the app does not spin forever.
33. As a caregiver, I want compressed pull pages with strict decoded-size checks, so that sync is efficient without risking unbounded memory.
34. As a caregiver, I want pagination to resume monotonically after interruption, so that records are neither skipped nor duplicated.
35. As a caregiver, I want a media source read once into immutable private storage, so that the uploaded bytes cannot change between hash, upload, and retry.
36. As a caregiver, I want a failed media upload to resume or replay by digest, so that large photos do not restart unnecessarily.
37. As a caregiver, I want a successful media commit to clean temporary spool data safely, so that reliability does not create unbounded device storage.
38. As a caregiver, I want unresolved spool data retained across process death while the mutation remains pending, so that a restart does not strand my fact.
39. As a family owner, I want author and Owner resolution permissions preserved, so that protocol simplification does not broaden who can decide family facts.
40. As a family owner, I want the member directory fetched only when it changes or I request it, so that every sync does not transfer unrelated account data.
41. As a family owner, I want a stable actor-ID fallback when a cached member name is stale, so that provenance remains honest without blocking conflict review.
42. As a family owner, I want conflict saturation to preserve every already durable branch, so that resource protection never becomes silent data loss.
43. As a family owner, I want a clear “too many open branches” state, so that the family can resolve before adding more concurrency.
44. As an operator, I want one authenticated sync handshake to advertise capabilities and limits, so that client behavior matches the running server.
45. As an operator, I want normal client sync decoupled from multiple health/setup probes, so that operational endpoints do not multiply user-visible failures.
46. As an operator, I want upload hashing outside the family critical section, so that one slow photo does not block unrelated facts.
47. As an operator, I want durable media receipts bound to family and principal, so that another session cannot attach staged bytes.
48. As an operator, I want expired or consumed staging objects collected by a bounded retention policy, so that retries do not leak storage.
49. As an operator, I want commit, detail, and resolution to have explicit rate, count, query, and byte budgets, so that hostile or accidental fan-out stays bounded.
50. As an operator, I want conflict pagination to retain a full branch-set CAS token, so that a partial page cannot authorize a partial resolution.
51. As an operator, I want protocol mismatch to fail before any mutation is sent, so that a rolling assumption cannot corrupt a fresh schema.
52. As an operator, I want the release gate to keep production replacement separate from local acceptance, so that passing tests does not imply NAS authorization.
53. As a developer, I want one canonical decoder and validator for ordinary commit and resolution, so that alternate write paths cannot persist different shapes.
54. As a developer, I want N-way merge output independent of branch insertion and enumeration order, so that tests can prove determinism.
55. As a developer, I want typed path outcomes for value/set(null), media membership, and deletion, so that every mutation has one meaning.
56. As a developer, I want conflict and auto-merged path sets to be disjoint by construction, so that clients never need filtering heuristics.
57. As a developer, I want opaque choice IDs instead of client-reconstructed values, so that the server remains the resolution authority.
58. As a developer, I want resolution mutation IDs to be replay-safe, so that a lost resolution response has one durable result.
59. As a developer, I want a shared cross-language golden corpus for canonical roots and ConflictSnapshots, so that Kotlin and Rust reject and accept the same wire.
60. As a developer, I want property tests over branch permutations and arrival orders, so that a lexical UUID winner cannot return unnoticed.
61. As a developer, I want process-death and cache-corruption tests for conflict state, so that offline fallback is evidence-backed.
62. As a developer, I want a real isolated two-client seam through the public app façades, so that Store-only tests cannot masquerade as product acceptance.
63. As a developer, I want transport fault tests for lost responses, truncation, gzip, Retry-After, and timeouts, so that retry policy is deterministic.
64. As a developer, I want media tests to prove the source is consumed once and the exact spool bytes are retried, so that digest stability is measurable.
65. As a developer, I want metrics to distinguish accepted, merged, branched, throttled, replayed, stale-token, and validation outcomes, so that field failures are diagnosable without logging family content.
66. As a developer, I want current version and schema numbers re-pinned before implementation, so that a planning-time target is never silently reused after drift.
67. As a release owner, I want every local gate and residual reported before asking for a maintenance window, so that production approval is informed.
68. As a release owner, I want the existing TLS identity, Room/media data, server data root, bootstrap secret, and APK signer invariants preserved, so that sync hardening cannot weaken release safety.
69. As a caregiver, I want clearing a nested object to conflict with a peer editing one of its children, so that path hierarchy cannot erase either intent.

## Implementation Decisions

1. Preserve ADR-0019's constraint-only server boundary and ADR-0021's WakeObservation/source-relation model. Add a new ADR before runtime changes that supersedes the mandatory reconcile-first and client-supplied resolved-result portions of ADR-0020, and narrowly supersedes ADR-0016 only to permit one durable immutable transport envelope per pending mutation; Room product facts remain the domain truth.
2. Re-pin the live tree and release state, then deliver this contract as Android/server 0.4.0, versionCode 21, Room 28, local-data contract 5, server schema 13 and protocol/min-supported floor 21. A different live source premise must amend this spec rather than silently changing these targets.
3. If the verified production state still has no deployed causal protocol, implement a single cutover shape with no dual-read, dual-write, or downgrade path. A changed premise returns to ticket 01 rather than being hidden in compatibility code.
4. Make every known optional canonical business field explicit with either a typed value or null. Missing required/known fields, unknown fields, wrong types, non-canonical numbers/times, and invalid domain combinations are rejected.
5. Represent every business path result as typed `set(value)` or `set(null)`; reserve `remove` for media membership removal and deletion transition. Normalize ancestor/descendant edits into non-overlapping canonical leaf/subtree outcomes; setting an ancestor object to null conflicts with a concurrent descendant edit unless the resulting canonical subtree is identical. Field absence never means both “unchanged” and “cleared.”
6. Define ConflictSnapshot as the lossless API and persistence unit. Stable and branch entries each contain canonical root, complete media metadata, explicit deleted state, version/base identity, mutation provenance, and bounded display metadata.
7. Require conflicting paths and automatically merged paths to be disjoint. Automatically merged outcomes include `set(null)`, deletion, and media results rather than only scalar JSON values.
8. Give every conflict candidate an opaque choice ID bound to its typed outcome and source set. Candidate labels are presentation; IDs are the only resolution input. An ID is stable for one snapshot/token across repeated detail, every page and server restart, but a refreshed snapshot may issue new IDs and the client may remap only from that new snapshot.
9. Use an opaque random snapshot token backed by a durable server receipt bound to family, root, stable version, complete open branch set, continuation view, contract version and expiry. It is reusable for detail paging and resolution until expiry or state change, survives restart, and is never trusted as a self-contained client claim. Paging never weakens full-set CAS.
10. Perform one deterministic N-way merge over every current head. A path auto-merges only when all changed heads propose one distinct outcome; two or more distinct outcomes conflict. Incomplete or incomparable history never falls back to branch order, UUID, timestamp, author, or Owner precedence.
11. Put merge, snapshot construction, resolution rebuilding, canonical validation, and stable projection behind one deep server conflict module while preserving Store as the façade. HTTP handlers translate/authenticate and do not reimplement merge policy.
12. Make resolution requests contain only snapshot token, stable resolution mutation ID, and exactly one choice ID per conflicting path. The server independently rebuilds root, media, and deleted state, validates them through the normal write pipeline, and commits with CAS.
13. Permit pure tombstone restore only from the tombstone mutation's declared direct `base_version`, which must contain a complete live root and media. Do not search ancestors or guess among multiple parents. Missing bytes or incomplete ancestry fail closed; post-restore edits are separate ordinary mutations.
14. Persist the whole canonical ConflictSnapshot and continuation state atomically on Android. Do not maintain a second lossy cache schema assembled from selected fields.
15. Clear stale conflict summary, detail, continuation, and token atomically when pull proves the root is no longer conflicted. Offline snapshots remain readable but an expired/stale token cannot be submitted.
16. Put a badge and entry on the family page sync-status row for one global conflict inbox covering the five mutable roots plus tombstone state, and keep contextual Record entry points. Both surfaces consume the same domain projection and expose provenance, deletion/restore, media, and freshness.
17. Replace ordinary reconcile-first publish with commit-first under the new capability. Stable mutation ID, request hash, base version, immutable frozen payload, and terminal commit result provide idempotency; local publish does not move the pull cursor.
18. Return one batch generation for a commit batch and remove redundant per-unit generation/cursor fields. Commit responses contain only state needed to settle each mutation plus the stable/conflict reference required for follow-up. An idempotent replay returns the original accepted/merged/branched terminal status plus a replay marker; `replayed` is not a fourth settlement state.
19. Use one authenticated sync handshake after endpoint trust/session establishment for protocol capability, readiness, principal role, directory generation, server limits, compression, and retry hints. Keep operational health endpoints for operators, not as a serial client sync state machine.
20. Stop downloading the full member directory on every sync. Refresh it only when its generation changes or family management explicitly requests it; provenance can fall back to stable actor identity.
21. Keep JSON and add negotiated gzip plus count, encoded-byte, decoded-byte, and page budgets. Clients reject oversized/decompression-bomb/truncated pages and require continuation monotonicity.
22. Classify retries by operation and idempotency. Honor valid Retry-After, otherwise use capped exponential full jitter and a finite foreground budget; authentication, capability, ACL, canonical, and stale-token failures are terminal until state changes.
23. Read each media source once into an application-private immutable spool while calculating digest and length. Every upload, retry, and commit for that frozen mutation uses the same bytes; cleanup follows durable settlement or bounded abandonment policy.
24. Stream uploads into server-owned staging, calculate and verify content outside the family/SQLite write critical section, then issue a durable bounded receipt bound to family, principal, digest, length, and expiry. Commit validates metadata/receipt without re-reading the large object under the main lock.
25. Leave branch cap/commit limiter under external ticket 12, bounded head loading under 17, conflict receipt/page/full-set continuation under 18, and resolution metadata retention under 19. This tracker must consume those contracts and cannot create competing shapes.
26. Preserve Room facts, local pending mutations, media, family session, endpoint trust, credentials, server data, TLS identity, and signer trust. Use the existing reset/full-resync seam only when the new capability explicitly requires rebuilding derived sync state; never clear product facts or force family rejoin.
27. Keep foreground-only sync scheduling in this scope. The protocol must be safe for a later scheduler, but this work does not introduce WorkManager, push notifications, continuous polling, or a new background policy.
28. Keep suspected-duplicate and WakeObservation semantics unchanged. Their roots participate in the hardened transport; this specification does not reopen their product meaning or aggregation rules.
29. Production NAS deployment, APK release, schema cutover, and joined-device production smoke remain blocked until every local ticket passes and the existing release ticket receives a new explicit maintenance-window confirmation. Ordinary CD never runs offline migration; the dedicated 0.4.0 maintenance flow uses copy-out/migrate/validate/copy-back with rollback before client writes reopen.
30. Advertise the new causal capability only after Android, server, media, conflict, migration and response-contraction paths are all complete through ticket 27. Earlier vertical slices remain fail-closed behind tests and must not claim wire availability.
31. Make 0.4.0 one atomic release identity: Android/server 0.4.0, versionCode 21, Room 28, local-data contract 5, server schema 13, protocol cutover and min-supported floor 21. Add 0.3.13/code 20 to released sources before making code 21 the catalog target.
32. Implement Android Room 27→28 as an adjacent non-destructive migration and keep the permanent code 6/Room 24 chain continuous through schemas 25/26/27/28. No destructive fallback, data clear, session reset, endpoint reset, or forced family rejoin is permitted.
33. Implement server schema 11/12→13 only in explicit copy-out `offline-migrate`. Store startup and ordinary CD remain exact-current/fail-closed; the migrator writes a new output root, proves the source unchanged, and promotes only after full validation.
34. Add a dedicated guarded 0.4.0 schema-cutover CD mode that reuses the outer lease, APK signer/image/package checks, encrypted credentials, TLS identity checks and explicit maintenance approval. It also requires an encrypted off-repo full data rollback bundle and old image/package pin. It is not an ordinary deploy flag left enabled between releases.

## Testing Decisions

1. Freeze a shared Kotlin/Rust golden corpus before implementation. It covers canonical field presence, `set(null)`, unknown fields, malformed types, deleted roots, media, ConflictSnapshot pages, choice IDs, tokens, and every terminal error shape.
2. Treat the primary acceptance seam as CareLog write through SyncPort/RealSyncPort and ReplicaSyncEngine into a developer-owned isolated real lezi-sync Store, followed by a second joined client pull, conflict review/resolution, and first-client convergence.
3. Keep Store unit, direct HTTP, Room DAO/migration, recording backend, and UI tests as supporting seams. None alone can close the cross-layer ticket.
4. Add N-way property/table tests with at least three branches and all branch enumeration/arrival permutations. Stable, auto-merged, conflicting, and provenance results must be byte-equivalent after canonical ordering.
5. Cover one changed outcome plus unchanged heads, all changed heads agreeing, two distinct outcomes, three distinct outcomes, incomparable ancestry, duplicate replay, and a branch arriving between detail and resolution.
6. Cover `set(null)` both as the sole auto-merged outcome and as a conflict against a concrete value. Cover ancestor-null versus descendant edit, sibling edits, and canonically identical subtree outcomes. Verify missing known fields and unknown fields fail closed in commit and resolution.
7. Cover delete/edit in both arrival orders, stable tombstone stale replay, delete/delete agreement, restore from complete immediate live parent, missing-parent refusal, missing-media refusal, and edit-after-restore as a separate mutation.
8. Assert conflicting and auto-merged path sets are disjoint for scalar, optional, deletion, and media paths. Test duplicate/missing/foreign choice IDs and incomplete path selections.
9. Verify resolution tamper cases: altered token, wrong family/root, expired token, partial paged branch set, stale stable version, new branch, unauthorized actor, changed request under the same resolution mutation ID, and lost-response replay.
10. Run one canonical validator suite against ordinary commit, auto-merge result, and resolution result. Invalid domain roots must never become stable through an alternate path.
11. Verify Android projection includes stable media, branch media, auto-merged media, explicit deleted choices, provenance, continuation, and token. No transport JSON is parsed directly by Compose.
12. Test process death before and after conflict detail persistence, offline inbox rendering, stale-token refresh, and atomic removal of stale summary/detail/cache when pull reports no conflict.
13. Add UI tests for the family sync-status badge/inbox and contextual timeline entry across Baby, Record, CarePlan, CustomItem, WakeObservation and tombstone states. Include accessible labels for provenance, deleted/restored state, photos, freshness, loading, paging, errors, and disabled submit.
14. Verify author/Owner ACL in both UI affordance and server enforcement; UI absence is not security evidence.
15. Prove commit-first sends no ordinary reconcile, freezes one mutation/request hash, preserves LocalWrite no-pull, never advances pull cursor, settles accepted/merged/branched, and replays an identical lost response safely.
16. Compare the removed and new response shapes with contract tests: one batch generation, no redundant per-unit cursor/generation, and enough stable/conflict state to settle every local mutation.
17. Exercise one authenticated handshake, health/ready independence, capability mismatch, server not-ready, directory generation unchanged/changed, explicit directory refresh, and stable actor fallback.
18. Use a deterministic fault server/proxy to cover connect timeout, response timeout after durable commit, truncated JSON, corrupt gzip, decompression over-budget, 429/503 with valid and invalid Retry-After, full-jitter bounds, budget exhaustion, and recovery.
19. Verify pull count/encoded/decoded-byte limits, monotonic continuation, replayed page, skipped page, duplicate item, and cursor persistence only after the page transaction commits.
20. Instrument the media source and assert it is opened/consumed once per frozen mutation. Retries must use byte-identical spool content even if the original URI changes or disappears.
21. Cover spool process death, upload interruption, digest mismatch, wrong length, receipt replay, wrong family/principal, expiry, lost prepare response, lost commit response, cleanup after every terminal result, and bounded abandoned-spool retention.
22. Measure that server streaming/hash work occurs outside the family write critical section and that concurrent small commits progress during a slow large upload. Query/statement and response budgets are asserted by external resource tickets 12/17/18/19.
23. Run Android JVM tests, lint, debug/release assembly, relevant connected/device tests, Rust format/test/clippy, isolated real-server fault/E2E suites, and release metadata checks in proportion to each ticket. Record commands and exact HEAD.
24. Do not run certificate mutation tests or production NAS replacement for implementation tickets. After all local evidence, hand off to the existing release ticket, re-probe actual HTTP/HTTPS protocol, and request a fresh maintenance-window confirmation before any CD.
25. Acceptance reports separate committed code, unrelated dirty WIP, local test evidence, unrun device/NAS gates, and the exact cross-tracker tickets still open.
26. Run Room 24/25/26/27→28 fixture migrations plus same-signer code 6→21 and current code 20→21 device upgrades. Compare facts, tombstones, pending/envelopes/conflicts, media/spool, session/credentials, endpoint/TLS trust and quick-check after restart.
27. Run server schema 11→13 and 12→13 dry-run/migrate/validate tests, source-tree hash checks, full data/media/identity inventories, and an isolated guarded CD success/failure/rollback matrix before asking for a production window.

## Out of Scope

- CRDTs, operation-log replication, timestamp/UUID/author/Owner automatic winners, or a new “smart” care-truth heuristic.
- Reopening WakeObservation, suspected-duplicate grouping, aggregation bounds, source-relation, or CarePlan fact-versus-future-intent semantics.
- Cloud relay, internet exposure, peer-to-peer transport, public push notifications, WorkManager, background continuous polling, or a new scheduler.
- Replacing JSON with Protobuf/CBOR, general HTTP/3 work, or optimizing unrelated app traffic.
- New identity, membership, ACL, invite, certificate-rotation, bootstrap-secret, or endpoint-trust models.
- Historical tombstone bulk resurrection, synthetic missing care facts, arbitrary restore-and-edit in one action, or automatic conflict resolution based on content plausibility.
- Duplicating external resource tickets 12/17/18/19 limiter, branch cap, bounded loader, conflict pagination, query budget, or retention ownership.
- Camera/Composer/family UI redesigns unrelated to the global conflict inbox and candidate presentation.
- Production NAS stop/rm/replace, release publication, TLS creation/rotation, or declaring the existing cutover ticket complete.

## Further Notes

- Publishing this tracker is planning only. It does not prove implementation, migration, release readiness, device behavior, or production deployment.
- Audit conclusions are pinned to `332f160d79c439546c6c0e6f83b35a40fe04aa3a`; every ticket must re-check live HEAD and status before changing code.
- Ticket 01 is the only initial frontier. It freezes the new ADR/wire/golden shapes before server, Android, resource-limit, or transport implementation can diverge.
- The current `0.3.13`/versionCode/Room/server-schema labels are historical planning values, not automatically reserved for this hardening. A non-deployed premise must be re-proven before choosing a single cutover.
- External resource tickets 12/17/18/19 may run only after their listed dependencies. Their ownership remains outside this tracker rather than being copied here.
- `lossless-family-causal-sync/09` remains the sole production 0.4.0 release/schema-cutover owner and gains this tracker's ticket 43 as a blocker.
- The primary seam is intentionally stricter than direct HTTP or Store tests: it must prove app-domain write intent, public sync façade behavior, real server persistence, a second joined client, resolution, and eventual convergence without using the family NAS.
