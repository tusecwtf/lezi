# 02 — 强制壳下可恢复会话并完成安装

**What to build:** While a force-update shell is up, the user can still recover family identity (re-login / re-trust) when the session is no longer usable, then download and install the package. A near-expiry access token that 401s on APK download must refresh once and retry the download once. If authenticated update is impossible, the UI gives a clear LAN invite-install (port 8767) sideload path using the known server origin. Default product rule stays: no “稍后” that bypasses the force surface for main features; optional “local-only care logging” is out of scope unless product reopens it.

**Blocked by:** 01 — 强制升级总能落到可安装包

**Status:** done

- [x] Force shell retained under reauth still allows completing re-login / re-trust without abandoning the force path
- [x] After session is joined again, install download proceeds for an installable forced package
- [x] APK download that hits access 401 refreshes then retries download once; persistent auth failure maps to recoverable reauth under the shell
- [x] When authenticated channel cannot supply a package, user sees LAN invite download guidance when origin is known
- [x] Coverage for reauth × force install and download 401 → single refresh retry
