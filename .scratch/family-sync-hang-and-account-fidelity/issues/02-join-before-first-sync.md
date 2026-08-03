# 02 — Finish join before first full sync

**What to build:** Creating a family, owner login, and member claim (check after
admin approval or equivalent QR claim) finish the “submitting / checking” user
path once the device session is durable. First full replica sync may continue
afterward but must not keep the wizard on an unbounded “正在创建 / 正在检查”
state for the whole pull/push. If first sync fails, the session remains joined and
the product points the user at an existing foreground retry path (e.g. pull on
log/summary/growth), without rolling back credentials.

**Blocked by:** None — can start immediately.

**Status:** implemented — awaiting live acceptance on `6b278242`

- [x] Create family and owner login return join success when the session is stored;
      first sync is not required inside the same user-visible submit wait.
- [x] Member approval check / claim path: after claim+session persist, waiting UI
      completes; first sync does not monopolize the checking state for a full
      multi-page media sync.
- [x] Outcome copy still honest: joined + syncing, joined + retryable sync failure,
      or complete — without implying join failed when only first sync failed.
- [x] JVM tests prove session is joined at command completion without requiring a
      successful full synchronize in the same call; failure of recovery leaves
      session and a retryable recovery signal.
- [ ] Minimal isolation smoke against 0.3.3 server: join/create returns promptly;
      later foreground sync still converges data.

**Residual:** code/JVM acceptance is complete; retain the isolation-server join/create
and later foreground-convergence smoke above as the only open gate.
