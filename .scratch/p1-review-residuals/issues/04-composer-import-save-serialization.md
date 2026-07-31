# 04 — Composer 选图与保存串行（保存优先）

**What to build:** 在记录编辑器中，用户选图导入与保存/删除不再并发踩踏。点保存时以保存为准：取消仍在进行的导入；已写入磁盘但尚未挂到当前草稿的图片必须清理，避免「记录没图、磁盘有孤儿文件」或「保存了导入前的旧草稿」。

**Blocked by:** None — can start immediately.

**Status:** done

**Severity:** P1  
**Lane:** immediate

- [x] 导入进行中时，保存会取消该导入并清理未挂上草稿的导入产物，再提交当前草稿（保存优先）。
- [x] 保存或删除进行中时，新的导入被拒绝或排队策略与「保存优先」一致，且不留下孤儿文件。
- [x] 连续两次导入不会互相覆盖导致不确定草稿（后一次须可预期地取代或串行完成，行为写清并有测）。
- [x] 关闭/丢弃编辑器时，未完成导入与未提交导入文件被清理。
- [x] 回归测试或可重复的 ViewModel 级测试覆盖：import 中 save、save 中 import、双 import、取消后无孤儿路径。

## Implementation notes

**Public seams:**
- `RecordComposerImportSaveSerialization` — epoch + unattached-path bookkeeping; save-priority commit lock (Main/session thread).
- `runComposerPhotoImport` / `joinAndReclaimCancelledImport` — orchestrator for cancel-after-write reclaim and commit join.
- `BoundedRecordPhotoImporter.import(..., onPathCommitted)` — reports each absolute path as soon as it hits disk so prompt cancellation after `withContext` success cannot lose the path list.

**Behavior (locked):**
1. **Save/delete priority:** `beginExclusiveCommit()` → cancel `importJob` → `joinAndReclaimCancelledImport` → domain write.
2. **Import while commit active:** rejected (`allowImport()` / `beginImport()` null).
3. **Double import:** last-wins — prior epoch superseded; prior unattached paths returned for delete; only current epoch may `markAttached`.
4. **Close/open:** preempt + cancel import, **join** job (NonCancellable on close; start of load on open), drain late produce, then `reset()`; draft `cleanupAbandoned` for owned draft photos.
5. **Cancel after disk write:** `onPathCommitted` + CE handler NonCancellable-delete; does not depend solely on the discarded suspend return value.

**Tests:** `RecordComposerImportSaveSerializationTest` + `RecordComposerPhotoImportRunnerTest` (cancel-after-write, save preempt, close join, double-import).
