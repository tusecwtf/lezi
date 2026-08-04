# 家庭同步权威裁决与 dirty 收敛

Status: implementation-complete — live acceptance pending

## Problem Statement

0.3.6 已经移除持久 outbox，并保证每个家庭同步周期先 pull/reconcile、再从 Room
生成临时发布计划。但浅层待同步数量仍直接统计六类 `syncDirty` 行，而发布阶段还会按
家庭角色、家庭权威宝宝、引用完整性和原子媒体包结构过滤候选。因此设备即使重新连上健康
后端，也可能保留永远不会进入发布计划的 dirty 行，持续显示「待同步 N 项」。

从照护者视角，重新联网应当产生明确结果：本机修改要么已被家庭服务器确认、要么应发布、
要么采用家庭服务器的权威版本、要么退出家庭同步域并保留为明确的本机内容、要么作为纯技术
残留被安全清理。健康探测成功或一次增量 pull 没有返回某 UUID，都不足以证明该实体应该清除
或发布。产品不得隐藏计数、按超时批量清 dirty，也不得为清零而静默删除用户拥有的护理事实。

现有 ADR-0016 锁定“不新增 head-by-UUID、全家庭快照或服务端协议”，无法对没有出现在增量
pull 中的 dirty UUID 证明远端存在或缺失。本规格经用户确认重新开放后端接口，部分取代该
限制，同时保留 Room 本地优先、先对账、临时发布计划和 atomic bundle 不变量。

## Solution

家庭同步新增经过认证的批量 head-by-UUID 权威裁决。客户端在增量 pull 后，把冻结的本机
待对账原子单元及其规范内容摘要提交给家庭服务器；服务端在同一 generation/cursor 快照上，
复用现有 LWW、ACL、tombstone、不可变履行证据与 atomic bundle 规则，为每个单元返回唯一
远端 verdict：已确认、应发布、采用远端、远端缺失且永久拒绝，或因暂时无法裁决而重试。
客户端再以本机内容性质把永久拒绝终结为保留本机内容或清理技术残留。generation 变化、响应
不完整或服务端明确要求时，客户端使用全量实体快照重新建立证明，
而不是猜测缺失。

客户端只从 `应发布` disposition 生成临时 atomic bundle 计划；`已确认` 用精确 CAS 清除待
对账，`采用远端` 原子覆盖本机家庭副本，`保留为本机内容` 退出家庭待同步计数但不删除用户
事实，`清理技术残留` 仅删除可证明无业务所有者的孤儿媒体、过期 tombstone 或纯同步标记。
发布 commit 后再写精确回执。一个经过认证且完整落库的收敛周期结束时，冻结快照不得仍有
未裁决 dirty；期间发生的新本机编辑因 CAS 不匹配进入下一周期。

浅层待同步数量改为统计尚未取得终态的原子同步单元，而不是六张表的原始 dirty 行数。
`/health`、`/ready` 只表示 transport/运维可用；只有经过认证、generation 有效、全部
disposition 已落库的周期才叫家庭同步已收敛。

## User Stories

1. As a caregiver, I want every offline edit to receive a definite result after reconnection, so that “待同步” does not remain forever without meaning.
2. As a caregiver, I want a record already present on the family server to become confirmed locally without being uploaded again, so that duplicate retry state disappears.
3. As a caregiver, I want a valid local record absent from the family server to be published, so that offline care facts reach my family.
4. As a caregiver, I want a newer family-server revision to replace my losing local revision, so that all devices converge on one fact.
5. As a caregiver, I want an equal-revision server winner to be adopted deterministically, so that two different bodies never remain “clean” on different devices.
6. As a caregiver, I want a newer authorized local revision to be published, so that reconnection never discards a legitimate offline edit merely to clear a badge.
7. As a caregiver, I want an incomplete network attempt to preserve unresolved local work, so that timeouts and 5xx responses cannot cause data loss.
8. As a caregiver, I want server reachability to be distinguished from completed reconciliation, so that a green health probe cannot falsely mark my data synced.
9. As a caregiver, I want “待同步 N 项” to count care units rather than internal rows, so that one record with photos is not presented as unrelated duplicate work.
10. As a caregiver, I want the pending count to reach zero after a quiet successful cycle, so that zero has a trustworthy meaning.
11. As a caregiver editing during synchronization, I want my newer edit to survive an older reconciliation result, so that concurrent work is never cleared by a stale acknowledgement.
12. As a family member, I want records under a family-authority baby to publish normally, so that role filtering does not strand valid facts.
13. As a family member with pre-join local facts, I want deterministic baby rebinding when there is exactly one valid target, so that those facts can join the family safely.
14. As a family member with multiple possible family babies, I want unmatched facts kept as explicit local-only content until I choose a target, so that they are not silently deleted or falsely shown as syncing.
15. As a family member when no family-authority baby exists, I want my local facts preserved outside the family pending count, so that an administrator can create a target without an endless dirty badge.
16. As a caregiver, I want an unauthorized edit of an existing family fact to adopt the server copy, so that a permanently forbidden delta does not retry forever.
17. As a caregiver, I want an unauthorized new family entity with no remote counterpart to leave the family replica, so that impossible creates do not poison later cycles.
18. As a caregiver, I want user-authored care facts distinguished from technical orphan rows, so that cleanup removes artifacts rather than meaningful history.
19. As a caregiver, I want media without an existing Record, CarePlan, or Baby owner cleaned safely, so that broken metadata cannot remain pending forever.
20. As a caregiver, I want a live avatar attached to a deleted baby repaired or discarded deterministically, so that an invalid avatar cannot block unrelated publication.
21. As a caregiver, I want missing photo bytes to produce a deterministic repair/disposition, so that a root package does not retry an impossible upload forever.
22. As a caregiver, I want record and plan photos reconciled with their root atomically, so that a per-media verdict cannot expose a partial family fact.
23. As a caregiver, I want stale local tombstones proven unnecessary before physical removal, so that deletion intent is not lost while unnecessary tombstones do not linger.
24. As a caregiver, I want CustomItem definitions and fulfillment evidence to use their existing ACL/freeze rules during reconciliation, so that settlement cannot bypass domain rules.
25. As a caregiver, I want a generation change to trigger a complete authoritative rebuild, so that absence is never inferred from an obsolete cursor.
26. As a caregiver, I want a process crash after receiving verdicts but before applying them to be safely retryable, so that restart converges without duplicate loss.
27. As a caregiver, I want a process crash after bundle commit but before local receipt to recognize the server’s committed head, so that the next cycle confirms instead of looping.
28. As a caregiver, I want another device to pull the same canonical result after settlement, so that “已同步” means family-visible rather than merely locally clean.
29. As a family administrator, I want Baby and avatar authority enforced by the server verdict, so that ordinary members cannot publish family-profile changes through reconciliation.
30. As an operator, I want reconciliation batches bounded and authenticated, so that a large dirty set cannot monopolize the NAS or expose family heads.
31. As an operator, I want reconciliation requests and logs free of credentials and media bytes, so that diagnosis does not leak secrets or family photos.
32. As a developer, I want the server verdict path to reuse commit-time LWW/ACL validation, so that head reconciliation and atomic commit cannot disagree.
33. As a developer, I want one complete-cycle acceptance seam, so that behavior is proved without coupling tests to every DAO implementation.
34. As a release reviewer, I want upgrade fixtures from 0.3.6 dirty shapes, so that the new contract settles existing devices without wiping Room or media.
35. As a release reviewer, I want the additive server endpoint deployed before clients require it, so that rollout remains recoverable.
36. As a release reviewer, I want a verified signed APK channel before any minimum-version increase, so that older clients are never forced into an unavailable update.
37. As a privacy-conscious caregiver, I want explicit local-only content to remain on my device and outside family reconciliation until I bind it, so that preservation is not confused with sharing.
38. As a caregiver, I want system-calendar projection failures excluded from family pending counts, so that device-local calendar state does not masquerade as family sync work.

## Implementation Decisions

- Canonical vocabulary:
  - **待对账修改** is a local family-domain revision without an authoritative server disposition.
  - **家庭同步裁决** is the server-backed decision for one frozen atomic unit.
  - **同步收敛周期** is an authenticated cycle in which every frozen unit reaches a terminal disposition.
  - **待发布** is no longer a synonym for every dirty row; it is only the `publish` verdict.
- This decision partially supersedes ADR-0016. Pull-before-planning, Room as the local source, ephemeral plans, CAS receipts, and atomic bundles remain. The prohibition on head-by-UUID/full snapshot protocol and raw dirty-derived pending counts is retired.
- The regular path uses an authenticated bounded batch head-by-UUID reconciliation endpoint. A request carries canonical root payload/metadata, local revision/tombstone, content/package hashes, dependency identities, media manifest metadata, and any known receipt; it does not upload media bytes.
- The response is a complete snapshot for every requested key and includes server generation, cursor, canonical remote head or authoritative absence, canonical hashes, a typed disposition, and a stable reason code. Missing, duplicate, extra, cross-family, or unknown-type results fail the whole response closed.
- The server computes dispositions inside a Store transaction and reuses the same canonicalization, LWW, ACL, creator stamping, fulfillment freeze, tombstone, reference, and atomic-package validation used by stage/commit. HTTP handlers do not duplicate those rules.
- Server authority verdicts are `confirmed`, `publish`, `adopt_remote`, `remote_absent_rejected`, and `retry_authority`. The client maps `remote_absent_rejected` to terminal `keep_local_only` for meaningful facts or `discard_technical` for proven artifacts. Only transport interruption, 5xx, generation change, or incomplete authoritative evidence may return/reach `retry_authority`.
- `confirmed` requires exact canonical equivalence, not merely equal `updatedAt`. Equal revision with a different body adopts the already-published NAS winner, matching existing server LWW behavior.
- `publish` is provisional until atomic commit. Stage/commit revalidates the current server head so concurrent server writes cannot be bypassed by an earlier reconcile response.
- `adopt_remote` replaces the family-domain fields with the canonical server head and clears the frozen local delta in one Room transaction. Device-local appearance, layout, file path, reminder, and calendar fields remain local.
- `keep_local_only` is a terminal family-sync disposition, not hidden dirty. It preserves meaningful user-authored facts that cannot yet enter the family authority graph, removes them from family pending counts, and returns them to `待对账修改` only after an explicit or deterministic rebind.
- `discard_technical` is restricted to facts proven not to contain independent user care content: orphan media metadata/files, stale sync receipts, impossible dangling package rows, and server-proven redundant tombstones. It must not silently delete an unmatched Record or CarePlan.
- Permanent ACL rejection adopts the remote head when one exists. When no remote head exists, meaningful care content becomes local-only; purely technical rows are discarded. Transient dependency order is repaired/replanned rather than discarded.
- Reconciliation operates on atomic publication units, not individual rows: Baby+avatar, Record+0–3 log media, CarePlan+0–3 plan media, CustomItem, and FulfillmentCandidate with its required roots. A unit receives one consistent disposition.
- Full-family snapshot is allowed as a fallback when generation changes, cursor proof is invalid, the server requests a rebuild, or the batch response cannot prove complete authority. It is not the default for every local write.
- A synchronization cycle freezes local units with `(clientUuid, localUpdatedAt, canonicalHash)`. Every local clear, overwrite, rebind, cleanup, and receipt write uses that CAS identity. Concurrent newer edits fail the CAS and enter the next cycle.
- A successful quiet cycle has a hard invariant: every frozen unit is terminal and the family pending count is zero. Newly committed concurrent edits may form the next cycle and do not make the completed snapshot dishonest.
- Room remains the durable offline source and no durable payload outbox returns. The existing dirty column may remain as an implementation marker, but its domain meaning becomes “待对账” and no filtered/ineligible row may remain dirty after a completed authoritative cycle.
- Pending status projects unresolved atomic units. It never directly sums six DAO tables and never counts local-only content, system-calendar projection, cleanup markers, duplicate media rows, or already-terminal verdicts.
- `/health` and `/ready` do not clear or settle data. “Backend connected” for this contract means trusted transport, authenticated membership, valid generation, complete authoritative response, and committed local disposition transaction.
- The server advertises a versioned reconciliation capability. Deployment order is server capability first, then signed Android client. If the behavior becomes mandatory for all joined clients, `min_supported` may advance only after the verified update channel contains the installable signed APK.
- Android changes remain behind the existing deep `SyncPort`/`RealSyncPort` façade and engine/backend/session capability packages. Server changes remain behind the deep Store façade and handler/store capability packages.
- The normal NAS protocol stays HTTPS and credential-scoped; requests cannot accept family, membership, device, or role authority from the body. Batch limits, payload limits, timeouts, and log redaction are fail-closed.

## Testing Decisions

- The primary product seam is one complete joined-client synchronization cycle through the public sync façade against an isolated real lezi-sync instance. Seed Room with mixed frozen units, run one cycle, and assert every unit’s disposition, zero pending at quiescence, canonical server state, and peer visibility.
- Existing RealSyncPort shared-backend tests are prior art for Owner/Member sequencing, process-style replan, CAS acknowledgements, full resync, and dual-client visibility. Extend this seam rather than testing a new coordinator per entity type.
- Existing Rust HTTP/API plus Store tests are prior art for authenticated endpoints, LWW, atomic bundle stage/commit, ACL, immutable fulfillment evidence, bounded pull pages, and exact idempotent retries.
- Server contract tests cover authoritative absence, exact equivalence, local winner, remote winner, equal-revision different body, tombstone, permanent ACL rejection, dependency conflict, response completeness, generation race, bounds, auth, and redaction.
- Android behavior tests cover all typed dispositions, atomic-unit grouping, local-only conversion/rebind, technical cleanup, missing media, stale commit receipt, process death, concurrent local edit CAS, full-snapshot fallback, and pending projection.
- Device migration tests start from the released 0.3.6/local-contract-3 shapes, including residual dirty rows that current role/structure filters cannot publish. Room care data and media bytes must survive; the first successful authoritative cycle must settle or explicitly localize every frozen unit.
- Negative tests prove `/health` success alone never clears dirty, an incomplete/malformed reconcile response clears nothing, and timeout/5xx/generation drift preserves unresolved user data.
- End-to-end acceptance uses two clients: A starts with offline changes, settles against an isolated server, and B pulls the canonical result. For adopt/discard cases, B and A must agree on the server winner.
- Changed server and Android modules run their full unit gates; Room migrations run instrumentation on API 35; Release build, lint, signer/hash/app-update metadata, Rust fmt/test/Clippy, isolated protocol smoke, and applicable deploy-helper regressions are release gates.
- Because runtime server/wire behavior changes, implementation acceptance must propose NAS CD after Rust gates and wait for explicit confirmation. After deploy, verify HTTPS health/ready/version, running image identity, TLS certificate/SPKI preservation, and a joined-client reconciliation smoke limited to this feature.
- Tests assert public behavior and durable outcomes. Source-layout, line-count, and per-DAO implementation tests are not acceptance seams.

## Out of Scope

- Background polling, FCM, permanent foreground synchronization, or changing the foreground-only trigger policy.
- Multi-family merge, two configured servers, P2P, SaaS/global account, or multi-master replication.
- Replacing existing LWW, CarePlan fulfillment winner, immutable candidate evidence, atomic photo bundles, or family ACL policy.
- Treating Android system-calendar projection as family synchronization.
- Keeping a durable payload outbox, request retry queue, or second copy of family truth.
- Silently deleting meaningful unmatched local care facts merely to reach zero pending.
- Exposing a general family-history query or administrative debugging API to clients.
- Running destructive certificate, restore, or reconciliation corruption tests against the family NAS.

## Further Notes

- This is a product-contract change, not a cosmetic pending-count fix. Changing only the UI projection would hide stranded data and is explicitly insufficient.
- Head-by-UUID is chosen for the normal path because the work set is bounded by local unresolved units. Full snapshot remains necessary as an authority-recovery path, not as a per-write tax.
- “Zero pending” is scoped to a frozen, quiescent snapshot. Continuous user edits may immediately create a new pending snapshot; the previous cycle remains valid because all writes are CAS-bound.
- Existing local-data preservation remains authoritative: meaningful local facts can become explicit local-only content, but they cannot be silently discarded as technical residue.
