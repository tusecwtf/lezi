# Ticket 06 current-wire smoke

Date: 2026-07-30 (Asia/Shanghai)

Scope: current Rust server source plus the Ticket 06 Android wire/order implementation. This is
an API peer smoke, not evidence of a second physical Android device.

## Persistence and publication

- Used temporary data root `/tmp/lezi-reclaim-smoke.VpXcvl` and loopback port `18765`.
- Restarted the server from current source while preserving the SQLite database.
- Published an active custom definition, zero-photo historical Record and pending CarePlan.
- Tombstoned the definition, edited then deleted the historical Record, completed the plan,
  published the linked Record with two media objects, then published FulfillmentCandidate.
- Publication order was completed CarePlan → linked Record → FulfillmentCandidate.

Result:

```json
{"published":true,"family_id":"361ab3f1-4175-40e8-b6ad-84568c34a7fd","zero_photo_history":true,"two_photo_fulfillment":true}
```

## Restart and peer convergence

Restarted the current server a second time on the same data root, reclaimed the owner, joined a
new peer credential and pulled from cursor 0.

Result:

```json
{"restart_preserved":true,"peer_converged":true,"family_id":"361ab3f1-4175-40e8-b6ad-84568c34a7fd","history_deleted":true,"plan_completed":true,"photo_count":2,"tombstone_hidden_contract":true}
```

The verification asserted the peer-visible saved title `历史抚触`, edited note, Record tombstone,
completed plan linkage, fulfillment fact, candidate, and exactly two live media entities.
