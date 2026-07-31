# Trusted-sync code review archive · 2026-07-31

**Range:** commits `e19c385` … `5f9aa3c` (product code fixed-point often
`a4dbe07` / `5f9aa3c`).
**Method:** four parallel read-only reviews (security, Android sync, Rust
server, domain/UI) plus primary verification of Critical/High items.
**Executable tickets:** [ISSUES.md](./ISSUES.md) · [spec.md](./spec.md)

This file is the durable findings archive. Tickets are the work units; do not
treat this document as a second tracker.

## Current-HEAD revalidation (2026-07-31)

Revalidated committed `HEAD=8651a9a5a5b61c33b9242194a2f2e8ae67425c52`.
The commits after the original `5f9aa3c` review point are ticket-16 acceptance
evidence plus self-hosted app-update work; neither closes these findings.
Uncommitted app-update work was inspected separately and excluded from committed
truth. It is also orthogonal to the residual paths below.

| Ticket | Current committed evidence | Result |
|--------|----------------------------|--------|
| 01 | `restorePendingMemberApproval` still protects only `Submitting`; both account and onboarding collectors can restore over other active/Completed states | remains |
| 02 | Root/bootstrap verification still runs without a failed-auth limiter; create records only correctly authenticated attempts and still distinguishes invalid secret (401) from configured family (409) | remains |
| 03 | Member request/QR claims still force `crossingFamilyBoundary=true`; media receipt ownership still requires `previous.isJoined` | remains |
| 04 | Account and onboarding still expose editable host/port after Ready; domain submit validates the mutable snapshot and does not require a matching verified profile | remains |
| 05 | `RealSyncPort.trustCertificate` still calls `rememberEndpoint` before the trusted probe returns Ready | remains |
| 06 | Account verification wires a no-op dismiss while `submitting=true`; onboarding also suppresses dismiss during verify; `claimMemberLoginQr` still returns only `SyncSession` and drops `dataRecovery` | remains |
| 07 | Both member claim paths still persist/publish the joined session before receipt reset | remains |
| 08 | `main.rs` still serves the cloned full application router on loopback HTTP 8766 | remains |
| 09 | Create replay still selects one active original session (`LIMIT 1`) and rejects after token/session drift; claimed request/grant replay still returns terminal conflict/used | remains |
| 10 | `CreateFamily` / `OwnerLogin` and QR payload commands retain generated secret-bearing `toString`; `constant_time_eq` still returns early on length mismatch | remains |
| 11 | Terminal domain/Room clear still begins outside `syncMutex`; `saveSession` still writes refresh/access material before DataStore identity | remains |
| 12 | Manual approval check still swallows failure back to Waiting; empty cached family name creates a disabled delete confirmation; rename placeholder still says empty has a fallback while validation rejects empty | remains |
| 13 | `SECURITY.md` still describes an SSID allowlist and `AGENTS.md` still gives cleartext public-port health commands | remains |

**Disposition:** keep all 13 tickets `ready-for-agent`. Tickets 01–06 continue to
block 0.3.1; exact-final APK validation does not waive these code defects.

---

## Executive summary

Main architecture is sound: production HTTPS + TOFU/SPKI, device sessions with
rotating refresh, owner root password, multi-device membership, legacy
SSID/invite/long-lived family token removed. No member→owner privilege
escalation found.

Highest residual defects: wizard pending-restore clobber; unbounded root-
password online guessing with create oracle; member reauth receipt wipe
(owner-only partial fix in `320ac7d`/`4ff0266`); post-probe host edit and
trust-before-probe remember; non-dismissable QR verify.

---

## Findings (severity)

### Critical / High → tickets 01–06

| ID | Severity | Title | Primary refs | Ticket |
|----|----------|-------|--------------|--------|
| F-01 | Critical | Pending restore overwrites any wizard state except Submitting | `FamilyViewModel.kt:108-125`, `FamilyWizardController.restorePendingMemberApproval`, onboarding collector | 01 |
| F-02 | High | Root/bootstrap password not rate-limited on failures; create is password oracle (401 vs 409) | `tools/lezi-sync/src/lib.rs` create / owner login / takeover / delete | 02 |
| F-03 | High | Member claim/grant always `crossingFamilyBoundary=true`; media receipts gated on `previous.isJoined` (false under reauth) | `FamilySessionCoordinator.kt` claim paths; `ReplicaSyncEngine.kt` resetLocalSyncReceipts | 03 |
| F-04 | High | Post-probe host/port re-edit bypasses trust/probe contract | `FamilyScreen.advanceWizardNetwork`, endpoint dialog, onboarding create fields, domain submit | 04 |
| F-05 | High | `trustCertificate` remembers endpoint before probe succeeds | `RealSyncPort.trustCertificate` | 05 |
| F-06 | High | QR verify dialog non-dismissable; `claimMemberLoginQr` drops `dataRecovery` | `FamilyScreen` VerifyingMemberLoginQr; `RealSyncPort.claimMemberLoginQr` | 06 |

### Medium → tickets 07–12

| ID | Severity | Title | Primary refs | Ticket |
|----|----------|-------|--------------|--------|
| F-07 | Medium | Member claim persists session before receipt reset (ordering hazard) | `FamilySessionCoordinator` claim/grant | 07 |
| F-08 | Medium | Internal loopback HTTP serves full app router (docs say readiness-only) | `tools/lezi-sync/src/main.rs` | 08 |
| F-09 | Medium | Create idempotency breaks after multi owner devices (`LIMIT 1`); claim not lost-response safe | `store.rs` create reclaim / claim_* | 09 |
| F-10 | Medium | Secret-bearing commands lack toString redaction; length-leaking constant_time_eq | `FamilySessionCoordinator` commands; `lib.rs` constant_time_eq | 10 |
| F-11 | Medium | Terminal identity clear races outside syncMutex; saveSession writes refresh before DataStore identity | `RealSyncPort`, `SyncPreferences.saveSession` | 11 |
| F-12 | Medium–Low | Silent member approval check failure; delete-family confirm disabled when familyName empty; rename/shallow-status copy drift | family wizard / UI policy | 12 |

### Low / Nit (docs + polish)

| ID | Severity | Title | Ticket |
|----|----------|-------|--------|
| F-13 | Low | SECURITY.md / AGENTS.md still describe SSID gate or HTTP health on public port | 13 |
| F-14 | Low | Owner grant mint unbounded; create rate-limit global not per-IP | accepted residual / optional follow-up in 02/09 |
| F-15 | Low | Refresh generation ignored on client; access expiry no skew | accepted residual |
| F-16 | Nit | `reclaimed` always false; dead `ensureDeviceId`; Dockerfile SNAT rate-limit collapse | accepted residual / note in 02 |

### Follow-up fix closure (prior commits)

| Commit | Intent | Closed? |
|--------|--------|---------|
| `9773359` enforce trusted TLS | Pin/resolve all prod HTTP | Yes for production inject path |
| `320ac7d` preserve receipts same-family | Owner boundary flag | Partial — member still hard cross |
| `4ff0266` skip reset unchanged identity | Only `persistJoin` | Partial — claim/grant unused |
| `a4dbe07` media ack full resync | Equal LWW re-ack | Mostly yes |
| `5f9aa3c` await terminal cleanup | Test flakiness | Yes for covered tests |

---

## Positive controls (no issue)

- Release cleartext off; debug loopback-only; contract tests for superseded surfaces
- HTTPS origin normalize; redirects disabled; access process-local; refresh encrypted
- Server hashed tokens; refresh IMMEDIATE + replay isolates device
- Owner-only admin APIs; grants cannot target owner membership
- setup-status minimized; legacy invite routes absent

---

## Accepted residual risk (product, not bugs)

1. TOFU first-use MITM (no out-of-band fingerprint).
2. Member QR = trust + 10-minute single-use grant (shoulder-surf).
3. Offline devices learn revoke only on next trusted connect.
4. System PKI trusts public CAs for hostname.
5. Stolen active refresh impersonates device until rotation/replay/revoke.
6. LAN bind `0.0.0.0:8765` depends on home perimeter.
7. Self-signed key on disk (`-nodes`, long validity) until forget + re-trust.

---

## Priority remediation (matches ISSUES frontier)

1. 01 pending restore clobber
2. 02 root rate-limit + de-oracle
3. 03 member reauth receipts
4. 04–05 freeze origin / remember only Ready
5. 06 QR cancel + dataRecovery
6. 07–08–11 preferred before release
7. 09–10–12–13 polish / docs

---

## Parallel review agents (session)

| Slice | Focus |
|-------|-------|
| Security TLS/auth | Pin, tokens, root password, QR, server middleware |
| Android sync client | Session coordinator, refresh, replica, follow-up commits |
| Rust lezi-sync | store sessions, grants, deploy TLS, rate limits |
| Domain/UI wizard | FamilyWizard, FamilyScreen, Onboarding, privilege UI |
