# 04 — Remaining designsystem chrome: next-feed + photo + QR

**What to build:** Next-feed plan prompts, photo preview actions, and member-
login QR confirm use Lezi chrome end-to-end so designsystem has no remaining
user-visible bare Material on production paths.

**Blocked by:** 03 — High-frequency chrome: dial + nursing.

**Status:** done

- [x] Next-feed plan dialog actions use Lezi buttons
- [x] Photo preview dismiss/navigation actions use Lezi chrome and stay readable on light photos
- [x] Member-login QR confirm fields/actions use Lezi chrome
- [x] Any other non-wrapper designsystem bare Material call sites found in the same pass are migrated
- [x] Product behavior of grants, plan events, and photo decode targets unchanged
- [x] `./gradlew :designsystem:test :app:assembleDebug` green
