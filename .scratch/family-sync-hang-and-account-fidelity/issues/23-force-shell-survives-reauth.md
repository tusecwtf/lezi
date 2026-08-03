# 23 — Force update shell survives reauth and retry check

**What to build:** Active forced app-update shell (WithPackage or PackageUnknown)
is never cleared because credentials became reauthRequired or because
checkAppUpdate treats that session as NotJoined. Retry check under reauth does
not drop the force gate to a soft “connect family server” surface. Install/reauth
interlock is honest (user can re-login without permanently bypassing force).

**Blocked by:** None — can start immediately; pairs with 10 for reauth chrome.

**Status:** complete — accepted on `6b278242`

- [x] checkAppUpdate / discover under reauthRequired does not null forced state.
- [x] Force shell clears only on true leave/unconfigure or successful superseding
      force classification / install path product allows.
- [x] Optional install-start does not permanently suppress banner as “稍后” before
      success (or explicit user dismiss).
- [x] Tests: CUR shell + reauth + retry check still shows force shell.
