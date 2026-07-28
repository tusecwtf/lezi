# Design Summary: 扫码加入一键预填家庭网络与邀请码

**Date:** 2026-07-28 (rev 3 after re-review polish)  
**Doc:** `/tmp/grok-1000/grok-design-doc-3a63c0f2.md`  
**Status:** Draft — implementation-ready

## Problem
Account-tab join after APK scan is not one-tap: scan only on Identity after Network save, invite field shows full QR JSON, network prefill is toast-only, `remember(ui.serverHost,…)` wipes invitation. Domain codec/prefill already works in unit tests.

## Solution (locked)

| ID | Decision |
|----|----------|
| K1 | Short invite code in draft; network on host/port/ssids |
| K2 | **openWizard initial step = today (prefs only)**; one-tap via **scan on Network** + `joinStepAfterInviteInput` |
| K3/K10 | Land Identity only if `hasJoinNetwork()`; plain code without SSID → Network; **Network Must show「邀请码已填」** |
| K4/K11 | Durable Identity summary; disable「加入」unless network ∧ code ∧ **非空称呼** |
| K5 | Overview「扫码加入」= Should (PR 3) |
| K6 | Join Network next does not write prefs; `persistJoin` on success |
| K7 | Stable draft + `wizardSessionActive` true for Wizard **and** Message/Guide with `resume=Wizard` |
| K9 | Progress ✓ only from `hasJoinNetwork()` |
| K12 | `applyInvitationInput` never throws; no partial JSON in field |
| K13 | Provenance: **ScannedFull** vs **ScannedHost** (Wi‑Fi not claimed from invite if local) |
| K14 | Owner empty-SSID soft-warn = Should |
| K15 | Partial prefill → Network + `joinNetworkPartialPrefillHint` (not Message) |

## Must vs Should
- **Must:** short code, Network-step scan + invite chip, routing, partial-prefill hint, honest progress/summary, confirm rules, draft lifecycle, Join skip mid-save, gate unchanged  
- **Should:** overview 扫码加入; Owner empty-SSID tip  

## PR plan
1. Domain helpers + pure tests  
2a. Draft stability + Join no mid-save + progress truth  
2b. Scan-on-Network, routing, ScannedFull/Host summary, confirmEnabled  
3. Overview 扫码加入 (Should)  
4. Onboarding/docs  

## Out of scope
NAS join API, invite crypto/TTL, cross-WAN, full account redesign, sync policy.
