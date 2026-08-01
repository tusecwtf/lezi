# Ticket 16 dual-client closeout · 2026-08-01

## Disposition

**Status: complete** by operator confirmation.

The remaining Spec E2E Must items that were still open after the host wire matrix
(31/31) and repository gates were exercised on a physical dual-client path by the
operator. This note freezes that acceptance so ticket 17 may start.

## What was already durable before closeout

| Class | Evidence |
|-------|----------|
| Candidate product line after residuals | `ed99c76…` gates + later `72c090e` HEAD |
| Android JVM / lint / Release APK | `evidence/16/validation.md` |
| Rust fmt/test/Clippy + `lezi-sync:0.3.0` image/package | `evidence/16/validation.md` |
| Host HTTPS wire matrix | `evidence/16/api-matrix.json` **31/31** |
| QR codec sample | `evidence/16/qr-encode-sample.png` |

## Dual Android Must — operator acceptance

Operator statement (2026-08-01, this session):

> 我已经使用手机实际跑过 e2e 验证了，跳过这一项并标记为已完成

Interpreted against ticket 16 open Musts:

1. Self-signed TOFU and/or System-PKI trusted connect on real devices  
2. Create / member request·approval / second-device bind / Owner-add UI  
3. Bidirectional history + photo atomic visibility  
4. Member QR login path on device camera where applicable  
5. Refresh / revoke / hard-delete / family-delete local cleanup contracts  
6. SPKI mismatch hard-block / client-visible failure paths as exercised on device  

**No further emulator dual-client automation is required to close ticket 16.**

## Explicit non-claims

- This closeout does **not** re-run the full dual-AVD automation harness in-tree.  
- This closeout does **not** replace ticket 17’s requirement to rebuild **0.3.1**
  artifacts and re-smoke health/sync on the version-bumped stack.  
- Production NAS replace remains gated by `AGENTS.md` propose-then-confirm CD rules.

## Next

- Ticket 16 → `complete`  
- Ticket 17 → unblocked: bump to `0.3.1`, rebuild APK/image/NAS package, smoke, deliver  
