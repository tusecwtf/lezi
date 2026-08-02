# 12 — Timer leave vs discard policy

**What to build:** Timer detail top-bar back and system back share **one** product
policy with explicit discard when the session has accumulated or running time.

**Product decision required before implement (pick one):**

- **A — Confirm discard:** back with hasTimerData uses the same confirm as 丢弃;
  confirm ends timer; cancel stays on detail.
- **B — Background intentional:** back leaves with explicit “仍在后台计时”
  feedback; FGS continues; 丢弃 remains the only kill path.

No silent leave that looks like full exit while FGS keeps running without
explanation. Zero/near-zero complete polish may ride along if cheap.

**Blocked by:** None — can start after policy A/B is chosen (document choice in
commit/PR).

**Status:** ready-for-agent

- [ ] Chosen policy A or B is written in the PR and implemented consistently for
      top-bar, system back, and discard.
- [ ] Never look fully exited while timer runs with no explanation.
- [ ] Feature tests: back-with-data and discard-with-data match the chosen policy
      only.
