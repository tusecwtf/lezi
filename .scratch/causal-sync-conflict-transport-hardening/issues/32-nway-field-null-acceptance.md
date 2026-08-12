# 32 — 验收 N 方字段与 null 矩阵

**What to build:** 在主缝上以有限 case table 证明不同字段 merge、同字段 2/3 方 conflict 与 `set(null)` 不受 arrival/enumeration 顺序影响。

**Blocked by:** 31

**Status:** implemented

## Contract slice

Cases：A/B 不同 leaf；A/B 同 leaf 两值；A/B/C 同 leaf 三值；两 changed heads 同值加一 unchanged；concrete 对 null；ancestor-null 对 descendant edit。每例运行正序与反序，3 方运行三种 cyclic order。

## Implementation sequence

1. 建立上述固定 branch/arrival fixtures。
2. 固定输入 mutation IDs，并比较 semantic digest：归一 server-generated version/branch IDs、receipt/token、接收时间等实例字段，保留 outcome/path/deleted/media、来源对应关系与因果结构。
3. 经 choice-only resolution 选择候选。
4. 全 clients 再 pull 并比较 stable fact/version。

## Acceptance

- [x] 所列 case/order 的 semantic digest 一致且无静默赢家
- [x] auto/conflict path disjoint，provenance 完整
- [x] resolution 后全端收敛

## Validation

- [x] isolated main-seam matrix 与 property oracle 一致
- [x] 记录 case IDs/order/HEAD

## Out of scope

不覆盖 delete/restore/ACL 或 transport faults。

## Evidence

- **HEAD:** `527820b55128200246ec3fc3ffb021f45c53df1a`
- **Schema / release pins:** server schema `13`; Android `0.4.0` / versionCode `21`; Room `28`; local-data contract `5`; protocol floor `21`
- **Fixture path:**
  - `domain/src/test/kotlin/com/lezi/babylog/domain/CareLogRealServerSeamNwayMatrixTest.kt`
  - `domain/src/test/kotlin/com/lezi/babylog/domain/CareLogRealServerSeamNwaySupport.kt`
  - extends `CareLogRealServerSeamSupport.kt` (`joinExtraOwner`, multi-client pull) + H31 `IsolatedLeziSyncServer`
- **How to run:**
  ```bash
  (cd tools/lezi-sync && cargo build -p lezi-sync)
  ./gradlew :domain:testDebugUnitTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamNwayMatrixTest \
    --tests com.lezi.babylog.domain.CareLogRealServerSeamTest
  ```
- **Case coverage table:**

  | Case ID | Kind | Orders | Assert |
  |---|---|---|---|
  | `C1-different-leaves-auto-merge` | auto_merge | owner→owner-b ; owner-b→owner | `/note` + `/payload_json/amount_ml` both apply; no open conflict; all clients converge |
  | `C2-same-leaf-two-values-conflict` | conflict | owner→owner-b ; owner-b→owner | `/note` two candidates; digest equal; choice-only → `note-alpha`; all converge |
  | `C3-same-leaf-three-values-conflict` | conflict | owner→b→c ; b→c→owner ; c→owner→b | `/note` three candidates; digest equal across cyclic orders; resolve `note-b` |
  | `C4-two-same-one-unchanged-auto-merge` | auto_merge | a→b +unchanged c ; b→a +unchanged c | agreed note auto-merges; unchanged client pulls same stable |
  | `C5-concrete-vs-null-conflict` | conflict | owner→owner-b ; owner-b→owner | concrete vs `set(null)` both candidates; resolve null; converge |
  | `C6-ancestor-payload-vs-descendant-conflict` | conflict | owner→owner-b ; owner-b→owner | open branches via distinct `/note`; `/payload_json` conflicts (no silent `/payload_json/*` leaf); digest equal |

- **Semantic digest:** strips conflict_id/client_uuid/token/expiry/page metadata, version/base/mutation/received_at, and order-sensitive root stamps; folds stable+branches into ordered head multiset; keeps path/outcome/deleted/media and actor/device provenance. Mirrors Store `deterministic_snapshot_payload` intent for the live seam (where first accepted head becomes stable tip).
- **Public seam only:** CareLog.updateRecord / loadConflictDetail / resolveConflict → RealSyncPort → HttpSyncBackend → isolated TLS lezi-sync; multi-owner devices via `ownerLogin(takeover=false)` on H31 bootstrap secret.
- **Related green:** `CareLogRealServerSeamTest` (H31), `ConflictResolutionPresentationTest`, `CareLogRecordWriteTest`
- **Isolation:** no NAS/production certs; temp data root under process temp
- **Residuals:** pure payload-only ancestor/descendant without a concurrent scalar branch still leaf-merges at commit (existing three_way_merge leaf classifier); C6 forces open branches with distinct notes so snapshot path-prefix normalization is exercised on the main seam. Delete/restore/ACL remains H33.
