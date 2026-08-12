# 33 — 验收 delete、restore、race 与 ACL

**What to build:** 在主缝上证明 delete/edit、direct-base restore、detail 后新 branch race 与 author/Owner ACL 均 fail-safe 并最终收敛。

**Blocked by:** 32

**Status:** implemented

## Contract slice

Cases：delete/edit 两到达顺序；delete/delete；complete direct-base restore；missing bytes 拒绝；detail 后新 branch；author、Owner、other actor 三种 resolution。

## Implementation sequence

1. 运行固定 delete/restore fixtures 并核对 root/media/provenance。
2. 在 detail 与 submit 间注入新 branch，刷新后重选。
3. 运行三种 actor ACL case。
4. 所有 clients pull 并比较 stable deleted/root/media/version。

## Acceptance

- [x] delete/edit 无静默删除/复活
- [x] restore 只取 direct complete base，缺 bytes 拒绝
- [x] stale race 不提交旧选择
- [x] author/Owner allowed，other denied，最终收敛

## Validation

- [x] isolated main-seam case table 通过
- [x] server enforcement 与 UI affordance 结果一致

## Out of scope

不覆盖通用 N 方字段或媒体 upload faults。

## Evidence

- **HEAD:** *(pinned after commit)*
- **Schema / release pins:** server schema `13`; Android `0.4.0` / versionCode `21`; Room `28`; local-data contract `5`; protocol floor `21`
- **Fixture path:**
  - `domain/src/test/kotlin/com/lezi/babylog/domain/CareLogRealServerSeamDeleteRestoreAclTest.kt`
  - extends `CareLogRealServerSeamSupport.kt` (`joinExtraMember`, multi-client pull)
  - `CareLogRealServerSeamNwaySupport.kt` (`preferOutcomeByPath` on choice-only resolve)
  - H31 `IsolatedLeziSyncServer`
- **How to run:**
  ```bash
  (cd tools/lezi-sync && cargo build -p lezi-sync)
  ./gradlew :domain:testDebugUnitTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamDeleteRestoreAclTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamNwayMatrixTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamTest
  ```
- **Case coverage table:**

  | Case ID | Kind | Orders / actors | Assert |
  |---|---|---|---|
  | `C1-delete-edit-both-orders` | conflict | owner deletes + peer edits; settle owner→peer / peer→owner | `/_mutation.deleted` present; keep-live → live note; keep-deleted → tombstone (may mint pure restore handle); no silent revive |
  | `C2-delete-delete-converge` | delete_delete | owner↔peer dual delete both settle orders | root stays deleted; open handle if any still deleted tip; no silent revive |
  | `C3-complete-direct-base-restore` | restore | author delete → author restore | pure tombstone_restore; author UI Current; restore → original note/payload; all clients converge |
  | `C4-missing-base-reject` | reject | author delete → corrupt parent edge → restore | `missing_restore_base` / `incomplete_restore_base`; root stays deleted |
  | `C5-detail-then-new-branch-race` | race | author detail open; brancher same-base late edit without delete pull | stale restore → `RefreshRequired`; refresh+reselect live branch → Accepted; converge |
  | `C6-author-owner-other-acl` | acl | other / author / owner on concurrent note conflict | UI Forbidden + server Forbidden for other; author Accepted; owner Accepted; converge |

- **Public seam only:** CareLog.deleteRecord / updateRecord / loadConflictDetail / resolveConflict → RealSyncPort → HttpSyncBackend → isolated TLS lezi-sync.
- **ACL:** `joinExtraMember` for author/other; Owner via fixture owner; UI `ConflictResolverDraft` availability mirrors server `forbidden`.
- **Missing bytes:** no-media path uses contract base-completeness check (drop tombstone parent edge in isolated `lezi.db` via sqlite3).
- **Related green:** H31 `CareLogRealServerSeamTest`, H32 `CareLogRealServerSeamNwayMatrixTest`
- **Isolation:** no NAS/production certs; temp data root under process temp
- **Residuals:** media-byte restore faults remain H37/H38; generic N-way field matrix is H32.
