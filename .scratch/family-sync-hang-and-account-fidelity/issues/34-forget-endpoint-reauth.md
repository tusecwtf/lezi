# 34 — forgetEndpoint / re-TOFU forces reauth

**What to build:** Clearing or replacing TOFU pin for an origin that previously held trust clears device credentials (or forces reauth) so tokens are not reused under a newly trusted certificate without re-login—aligned with PRD trust boundary and network-settings reconnect.

**Blocked by:** None.

**Status:** complete — accepted on `6b278242`

- [x] Wizard CertificateChanged → forget → re-TOFU does not resume sync with old refresh.
- [x] Joined TrustChanged has a guided recovery CTA (not only generic offline).
- [x] Tests for forget then remember pin requires new session.
