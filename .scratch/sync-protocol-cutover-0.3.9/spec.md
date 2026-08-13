# 0.3.9 家庭同步协议校验、状态重置与历史收敛

Status: implementation complete; NAS deployed on `ff80edbf`; joined-client recovery acceptance blocked by unavailable valid retained session

## Problem Statement

0.3.8 已实现 Room 本地优先、pull-before-plan、批量家庭同步裁决、临时发布计划和 atomic
bundle，但服务器仍允许 completed CarePlan 在关联 Record 尚未提交时先进入家庭权威图。只要
计划提交后、Record 提交前发生进程终止、网络中断或旧版本状态遗漏，服务器就可能长期保存一条
引用不存在 Record 的 completed CarePlan。随后 pull 为了保证接收端只看到完整履行关系而加载
缺失依赖，最终把这个历史半状态转换为 HTTP 500。

从照护者视角，这会同时阻断新成员下载全部家庭内容，也阻断旧管理员在下一周期补传本机
Record，因为所有客户端都必须先 pull 才能进入对账和发布。服务器健康、容器 ready、APK 版本
正确都不能解除这个家庭级自锁。

0.3.9 升级不能要求用户清空 App、删除家庭或手工编辑 NAS SQLite。升级必须保留 Room、NAS
护理事实、照片、家庭身份、TLS 与凭据，只重置旧协议留下的 generation、cursor、本机发布回执
和待对账投影。服务器必须先校验现有家庭权威图，把可识别的旧协议半状态隔离为可恢复状态，
再声明新协议 ready。无法证明安全的数据不得静默删除、伪造或错误标记为已同步。

## Solution

0.3.9 作为一次明确的家庭同步协议切换发布。服务器启动时先对当前 NAS 数据执行只读语义校验，
确认每个公开 atomic unit 的 payload、引用和履行关系符合新协议。完整的历史事实原样保留；
completed CarePlan 引用缺失 Record 这类由旧协议允许产生、且仍有完整 bundle 证据的状态被识别为
“延后履行”，从公开家庭权威图中隔离，但保留其规范 payload、媒体证据和不可变履行绑定，等待
0.3.9 客户端重新提交关联 Record。其它无法安全分类或无法保留证据的存储损坏继续 fail closed，
服务器不得带病进入 ready。

服务器通过新的版本化 capability 与 generation 宣告校验已经完成。0.3.9 客户端第一次看到该
协议代际时，复用现有 full-resync 恢复边界：cursor 回到 0，失效旧根发布回执和媒体发布收据，
把当前 Room 家庭域原子单元重新置为待对账，再以完整本机事实进入批量权威裁决。该重置只影响
同步元数据，不改变 Record、CarePlan、Baby、CustomItem、FulfillmentCandidate、照片文件或
设备本地字段。

新协议不再把缺少关联 Record 的 completed CarePlan 暴露为公开实体。CarePlan 先提交时只形成
耐久、幂等、不可见的延后履行；关联 Record atomic bundle 到达后，服务器在同一家庭锁和 SQLite
事务中重新执行 ACL、LWW、tombstone custom-item 历史许可、不可变 pair 与媒体完整性校验，再让
CarePlan 和 Record 一起进入可 pull 的完整权威图。FulfillmentCandidate 仍在两者完整后发布。
因此未来即使再次在两个 bundle 之间终止进程，也只留下可重试的延后履行，不再阻断全家庭 pull。

0.3.9 的签名 APK 与更新 metadata 必须先在 NAS 安装通道可验证，再提高 `min_supported` 并启用
新协议强制门。旧客户端只负责进入既有强制升级流程，不允许继续按 0.3.8 语义读写已经切换的
权威图。升级完成后，管理员本机仍有缺失 Record 时会在全量重对账中补传；若所有客户端都已不再
拥有该 Record，服务器继续保留延后证据且其它家庭数据正常同步，不得伪造事实或再次全局 500。

## User Stories

1. As a family administrator, I want upgrading to 0.3.9 to preserve every local care fact, so that fixing synchronization never requires clearing the app.
2. As a family administrator, I want the NAS to validate its existing authority graph before declaring the new protocol ready, so that clients never enter a known-broken graph.
3. As a caregiver, I want a historical completed plan with a missing record to stop blocking unrelated family payloads, so that one partial fulfillment cannot disable the whole family.
4. As a caregiver, I want historical incomplete fulfillment evidence retained during upgrade, so that recovery does not silently erase a real user action.
5. As a caregiver, I want the server to avoid fabricating a missing record, so that family history contains only facts a user actually confirmed.
6. As a caregiver, I want complete historical records and plans to remain byte-for-byte and semantically unchanged, so that protocol repair does not rewrite valid history.
7. As a caregiver, I want local photos to survive the protocol reset, so that a sync repair cannot lose family media.
8. As a caregiver, I want NAS media evidence belonging to a deferred fulfillment to remain quarantined until its complete root set is valid, so that peers never see metadata without ready bytes.
9. As an existing administrator, I want the first 0.3.9 sync to reconsider all family-domain Room units, so that a missing NAS record can be republished even when an old receipt marked it clean.
10. As an existing administrator, I want old cursors and receipts invalidated without changing business data, so that the new protocol starts from evidence rather than stale success markers.
11. As a family member, I want joining after the server upgrade to download every complete family fact, so that old partial data cannot prevent initial synchronization.
12. As a family member, I want incomplete historical fulfillment to remain invisible until complete, so that reminders and timelines never expose a plan without its fact.
13. As a caregiver, I want unrelated new edits to continue synchronizing while an old fulfillment remains deferred, so that recovery is scoped rather than family-wide.
14. As a caregiver, I want a deferred plan to become visible automatically when its exact Record later arrives, so that no manual NAS repair is required.
15. As a caregiver, I want the completed plan and associated Record to become pullable as one complete relationship, so that peers cannot observe the commit gap.
16. As a caregiver, I want process death between plan and record publication to be retry-safe, so that the same production failure cannot recreate the incident.
17. As a caregiver, I want exact bundle retries to remain idempotent across restart, so that recovery never duplicates facts or photos.
18. As a caregiver, I want immutable fulfillment binding and candidate evidence preserved during recovery, so that a protocol reset cannot rebind historical proof.
19. As a custom-item user, I want fulfillment of a plan that references a tombstoned custom definition to retain its authorized history, so that the new protocol does not weaken existing domain rules.
20. As a family owner, I want owner/member ACLs revalidated when deferred data becomes public, so that old staged evidence cannot bypass current authority.
21. As a caregiver editing during the upgrade, I want newer local changes protected by revision CAS, so that reset and reconciliation cannot overwrite concurrent work.
22. As a caregiver, I want the app to avoid showing “已同步” until the reset snapshot has reached terminal authority, so that upgrade progress is honest.
23. As a caregiver whose devices no longer contain the missing Record, I want the remaining evidence preserved without blocking other data, so that irrecoverable absence is explicit rather than destructive.
24. As an operator, I want startup validation to distinguish known legacy partial fulfillment from unknown corruption, so that only safely classified states enter recovery.
25. As an operator, I want unknown or unparseable authority corruption to keep readiness failed, so that CD cannot silently publish an unsafe server.
26. As an operator, I want validation and recovery logs to contain entity types, opaque IDs, revisions and reason codes but no credentials or full payloads, so that incidents are diagnosable without leaking family data.
27. As an operator, I want the new capability exposed only after validation completes, so that clients cannot race server preparation.
28. As a release reviewer, I want the signed 0.3.9 APK available before the server rejects 0.3.8 clients, so that forced upgrade never deadlocks users.
29. As a release reviewer, I want existing APK upgrade, generation recovery and update-channel tests reused, so that this change adds only the missing protocol-cutover proof.
30. As a release reviewer, I want one 0.3.8-to-0.3.9 fixture containing both clean history and a legacy partial fulfillment, so that preservation and repair are demonstrated together.
31. As a release reviewer, I want a second client to pull the recovered canonical result, so that the upgrade is proven family-visible rather than only locally clean.
32. As a NAS operator, I want ordinary CD to preserve the existing data bind, bootstrap secret, TLS certificate and SPKI, so that synchronization repair does not change server identity.
33. As a NAS operator, I want no manual SQLite edits or deploy-script data rewrites, so that recovery remains repeatable and auditable.
34. As a developer, I want protocol reset to reuse the existing generation/full-resync boundary and deep SyncPort/Store façades, so that the fix does not create a parallel sync engine.
35. As a developer, I want complete CarePlan/Record visibility enforced at the server publication boundary, so that every current and future client receives the same invariant.

## Implementation Decisions

- 0.3.9 introduces one versioned family-sync capability representing a validated authority graph and the new deferred-fulfillment publication rule. Clients must not infer support from the image version string alone.
- The upgrade resets synchronization metadata, not user data. Reset scope includes server generation proof, client pull cursor, family root publication receipts, media publication receipts and derived pending state. It excludes Room care entities, NAS canonical complete entities, files, family identity, endpoint trust, membership credentials, TLS and bootstrap credentials.
- Server startup performs a semantic authority-graph validation before readiness. It validates stored payloads, entity references, completed CarePlan pairs, fulfillment candidates, media ownership and the ability to retain any incomplete unit as durable evidence.
- A known legacy completed CarePlan whose bound Record is absent is a recoverable deferred fulfillment only when its canonical plan payload and immutable pair remain available from authenticated family-owned storage evidence. It is not ordinary corruption and must not produce pull 500.
- Unknown invalid payloads, cross-family references, missing evidence required to preserve a meaningful unit, or filesystem/database failures remain fatal readiness errors. The implementation must not broadly swallow every unresolved reference.
- Deferred fulfillment is excluded from the public pull graph. It retains the exact CarePlan root, media manifest, immutable fulfillment pair and originating family/membership evidence needed for later commit-time validation.
- The server must use current schema-compatible durable evidence for the 0.3.9 ordinary-CD path. No ad-hoc deploy-script SQL mutation is allowed. If implementation discovers that a server schema change is unavoidable, it must be handled as a separately audited offline cutover and is not implicitly authorized by this spec.
- The 0.3.9 server generation changes after successful validation. Existing client generation mismatch handling resets to cursor 0 and enters the existing full-resync path.
- The 0.3.9 client performs a one-time family-domain re-reconciliation for the new protocol epoch. It invalidates stale publication receipts and freezes every current atomic unit, including rows previously considered clean; local-data CAS prevents the reset from clearing newer concurrent edits.
- The one-time reset must be idempotent across process death. Restart either resumes the same epoch reset or observes that its terminal checkpoint is already complete; it never repeats destructive local mutation.
- Room remains the durable local source. No durable payload outbox, duplicate family database or manually assembled recovery payload store is introduced.
- New completed CarePlan publication may be accepted durably before its Record only as an invisible deferred fulfillment. It must not advance the public family graph in a way that makes pull depend on a missing Record.
- When the exact Record arrives, the server resolves the deferred plan and record within one family serialization boundary and one SQLite transaction. It revalidates ACL, LWW, timestamps, immutable fulfillment binding, custom-item history permissions and media integrity against current authority.
- FulfillmentCandidate remains publishable only after its referenced CarePlan and Record are public and valid. Existing candidate winner rules do not change.
- A missing Record is never synthesized from CarePlan fields. If no upgraded client possesses it, the deferred evidence remains retained and excluded from family-visible completed history while all unrelated canonical data continues to synchronize.
- Pull pagination never advances a client cursor across a withheld deferred unit in a way that would permanently lose it. Resolution must make the complete unit reachable to clients that synchronized while it was deferred.
- The server exposes bounded structured diagnostics for validation summary, deferred-unit count, resolution and fatal reason codes. Logs exclude tokens, secrets, names, notes, media bytes and full JSON payloads.
- The signed 0.3.9 APK and verified app-update metadata are published before `min_supported` is raised. Existing `client_update_required` and LAN install recovery remain the only old-client upgrade path.
- Server capability deployment, APK publication, minimum-version activation and joined-client smoke are independently verifiable release steps. NAS CD still requires explicit user confirmation and preserves the existing data bind and TLS identity.
- Product and architecture documentation must replace the old claim that a completed CarePlan may become publicly persistent before its Record. Plan-first transport may remain internally, but public visibility is deferred until the relationship is complete.

## Testing Decisions

- The primary acceptance seam is one protocol-upgrade fixture, not a new exhaustive synchronization matrix. Start with released 0.3.8 client/server shapes containing ordinary complete family history plus one completed CarePlan whose linked Record exists only in the administrator Room replica.
- Upgrade the server and client to 0.3.9, assert server validation completes before the new capability/readiness is exposed, and assert the existing generation/full-resync path resets only synchronization metadata.
- After the first 0.3.9 cycle, assert the administrator republishes the missing Record, the server exposes a complete CarePlan/Record relationship, unrelated family payloads remain available, and a second client pulls the same canonical result.
- The same fixture proves clean historical entities, Room rows and media bytes are preserved. It also proves that the former partial state no longer causes HTTP 500.
- One negative variant removes the Record from every client: the incomplete evidence remains deferred, other family data synchronizes, and no synthetic Record or false completed relationship appears.
- Existing local-data migration, APK in-place installation, generation-change full resync, reconcile dispositions, atomic bundle, version gate, signed update channel and CD/TLS gates are prior art and remain regression gates; they are not duplicated as a new per-disposition acceptance matrix.
- Rust tests use the public HTTP/API plus Store boundary to prove startup validation, deferred visibility and resolution. Android tests use the existing RealSyncPort full-resync boundary to prove one-time receipt reset and whole-Room re-reconciliation.
- Release acceptance runs standard Android tests/lint/Release signing, Rust fmt/test/Clippy, the focused isolated protocol-upgrade fixture, APK install-over-existing-data smoke, and NAS health/ready/image/TLS verification after separately confirmed CD.
- Tests assert public state, preserved data and durable outcomes. They do not assert source layout, internal helper count, private SQL ordering or implementation line counts.

## Out of Scope

- Clearing Room, deleting the family, recreating memberships or manually editing the production NAS database.
- Fabricating a missing Record from CarePlan metadata or treating a reminder as proof that care occurred.
- Re-running the entire authoritative-reconciliation disposition matrix already covered by 0.3.8 tests.
- Changing fulfillment winner ordering, immutable evidence, LWW, ACL, tombstone semantics or CustomItem history policy.
- Replacing foreground-only synchronization with background polling, push notification or a permanent foreground service.
- General multi-root transaction APIs for unrelated entity types. This release only guarantees complete visibility for the existing CarePlan fulfillment relationship.
- Server schema changes through ordinary CD or deploy scripts.
- TLS certificate rotation, bootstrap-secret rotation, data-bind relocation or disaster-restore exercises.
- Repairing user data that no surviving authorized client or retained server evidence can reconstruct.

## Further Notes

- The incident proves that “允许前向引用” and “接收端必须完整可见” cannot be implemented as two independent best-effort commits in the public authority graph. 0.3.9 keeps transport retryability but moves visibility to the complete relationship boundary.
- Existing 0.3.6/0.3.8 work already proves Room/media preservation, APK install-over, generation recovery, authoritative dispositions, update-channel gating and basic peer visibility. The new acceptance is deliberately limited to the missing protocol-cutover state.
- A reset success means all safely classifiable 0.3.8 state has entered complete canonical authority or durable deferred recovery under the new generation. It does not mean unavailable user facts were invented or discarded.
- The live-family historical shape identified during diagnosis is acceptance input, not authorization to mutate production data before the 0.3.9 guarded implementation and CD are independently reviewed.
