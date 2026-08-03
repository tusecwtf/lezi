# 家庭同步抗卡死、数据正确性与交互硬化

Status: implementation-complete — live acceptance pending (`6b278242`)

Acceptance boundary: all code/static/automated Must items are accepted. Tickets
02, 19, 22, 25, 31, 33, and 36 retain one explicit isolation/NAS/live Owner/
multi-device checkbox each; those external gates are the only remaining work.

## Goal

家庭加入/放弃/检查、同步 apply/outbox、账户与数据页状态、以及若干跨设备不变量
在前后端交互中不再出现假忙死、僵尸状态、UI/后台不对照或静默丢数据；会话建立与
首次全量同步解耦；关键安全/ACL/单开睡眠与下次喂养保持家庭一致。

## Release boundary

- **Android-first** for tickets 01–18, 21–24, 26–30, 32–40 unless noted.
- **lezi-sync release** for 19, 20, 25, 31 (and server pieces of 33/36 as needed):
  no gratuitous `user_version` bump; keep 0.3.3 client wire compatible unless the
  ticket explicitly migrates.
- Do not wipe babies/records/outbox/media/endpoint or legal credentials to “fix UI”.
- 0.3.4 local-first abandon, request timeout, secure IO, account card cleanup are
  **baseline** — do not regress.

## Must (program)

- Abandon waiting: busy, idempotent empty pending, no zombie wait; reconnect cancel
  local-first.
- Join/create/claim UI finishes when session durable; first full sync not under
  submit chrome.
- Busy paths bound or cancel-clean; DR cancel not blocked by start busy.
- Account/data shallow status + reauth surface + product error copy.
- Outbox/apply LWW honesty (dirty must not shadow newer remote; soft-deleted baby
  must not brick push).
- Server record ACL matches care_plan/custom_item manage rules.
- Family-global single open sleep and single open next-feed after sync.
- Owner reconnect without ghost admin devices; re-TOFU forces reauth.
- Composer mid-save process death without duplicate facts; timer leave policy clear.
- Widget/alarm/package-replaced rehydrate; untrusted deep links confirm.

## Out of scope

- Global Android `syncMutex` split into session vs replica locks (separate tracker).
- Dual-family merge; nickname auto-align babies.
- Full visual redesign; search short-needle polish; export PDF EXIF/CJK; R8
  Serializable hygiene; dark-theme token polish; log minute re-observe perf
  (deferred — see ISSUES 已并入).

## Validation (program-level)

- Domain/sync JVM for abandon, join-before-sync, dirty LWW, soft-deleted baby
  push, open-sleep/next-feed heal where fixture-able.
- Feature/Compose for busy, card fidelity, destructive confirms.
- Server: Rust tests for 19/20/25/31; release gates before NAS CD (user confirm).
- Isolation smoke against 0.3.3 (or post-server version when 19–31 ship).

## Tracker layout

- Index: `ISSUES.md` (canonical table + dedup + soft serial)
- Tickets: `issues/01-….md` … `issues/40-….md`
