# 01 — 强制升级总能落到可安装包

**What to build:** When the family server rejects authoritative sync with `client_update_required` and app-update metadata is reachable with a `versionCode` higher than the installed app, the user always gets a **force surface that can install** that package—not a PackageUnknown shell that only offers “retry check” while a usable package sits on the server. PackageUnknown remains only when metadata or the download channel truly fails. Failed non-CUR sync still must not demote a force shell into a fake “up to date / optional” state.

**Blocked by:** None — can start immediately.

**Status:** done

- [x] After CUR, successful metadata with `versionCode > local` publishes installable forced state (install CTA), even if dual-tier classification would have been optional-only without CUR
- [x] Metadata or channel failure after CUR still yields PackageUnknown with retry, not silent idle
- [x] Existing installable forced package is not torn down by non-Forced metadata while the force shell is active
- [x] Failed sync (network/5xx, non-CUR) does not piggyback-clear a force shell into bare UpToDate/Optional
- [x] JVM coverage for CUR + newer-package → installable; CUR + metadata failure → PackageUnknown
