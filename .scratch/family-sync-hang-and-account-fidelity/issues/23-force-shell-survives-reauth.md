# 23 — Force update shell survives reauth and retry check

**What to build:** Active forced app-update shell (WithPackage or PackageUnknown)
is never cleared because credentials became reauthRequired or because
checkAppUpdate treats that session as NotJoined. Retry check under reauth does
not drop the force gate to a soft “connect family server” surface. Install/reauth
interlock is honest (user can re-login without permanently bypassing force).

**Blocked by:** None — can start immediately; pairs with 10 for reauth chrome.

**Status:** ready-for-agent

- [ ] checkAppUpdate / discover under reauthRequired does not null forced state.
- [ ] Force shell clears only on true leave/unconfigure or successful superseding
      force classification / install path product allows.
- [ ] Optional install-start does not permanently suppress banner as “稍后” before
      success (or explicit user dismiss).
- [ ] Tests: CUR shell + reauth + retry check still shows force shell.
