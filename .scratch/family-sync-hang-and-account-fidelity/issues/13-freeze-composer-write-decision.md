# 13 — Freeze composer write decision at confirm

**What to build:** When the user confirms a composer save (or convert-to-plan),
the write decision and editable fields that feed it are frozen for that save.
Wall-clock time and mid-save time/note edits cannot flip CreateCarePlan ↔
AddRecord or Convert ↔ Update after confirm. Time/note/wake controls are
disabled while saving/deleting/handoff busy. Delete post-commit UI publish is as
cancellation-safe as save (no stuck “删除中…” after domain success).

**Blocked by:** None — can start immediately.

**Status:** complete — accepted on `6b278242`

- [x] `writeDecision` (and command snapshot) captured at confirm / save entry;
      post-import path reuses frozen decision or re-validates against it, not a
      fresh wall clock alone.
- [x] Time fields, notes, and sleep “同时记醒来” disabled while
      saving/deleting/handoffBusy (same as photo/confirm chrome).
- [x] Future→past flip during long photo import cannot silently change plan vs
      fact after confirm.
- [x] Delete success publishes onDeleted / clears commit lock under
      NonCancellable-equivalent path.
- [x] Unit tests for clock flip mid-save and convert flip; UI busy field lock.
