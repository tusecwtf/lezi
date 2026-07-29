# Ticket 09 shared family wizard device smoke

- Date: 2026-07-30 (Asia/Shanghai)
- Code HEAD: `e6742f1a3bcde05443b68dcb170b83b16ab69ccb`
- Device: `emulator-5554`, Android API 35, SSID `AndroidWifi`
- APK: `/tmp/lezi-ticket21-device/app/build/outputs/apk/debug/app-debug.apk`
  - package: `com.lezi.babylog.debug`
  - version: `0.2.6-debug`
  - size: `28,257,340` bytes
  - SHA-256: `8b6cca08e1ddad2610ad769ecc7a4b4a0d8bf2ebbe9998bffe31ef9e7355fc2e`

## Onboarding entry

The clean-install flow documented for Ticket 08 supplied the required first-use smoke on this
same fixed APK:

1. The first screen exposed exactly `新建家庭` and `加入家庭`.
2. `新建家庭` opened the network and identity flow, submitted against an empty current Rust
   server, followed the create-created result, and entered the single first-Baby step.
3. Creating `年年` entered the Record page once. A force-stop/cold launch returned to the same
   Baby and normal navigation without resubmitting create.

The server/Room receipts are preserved in
[`../08/empty-nas-first-create.md`](../08/empty-nas-first-create.md).

## Account entry

The owner permanently deleted only the isolated empty-NAS test family through the app's two-step
confirmation. This intentionally removed the disposable server fixture and cleared the joined
session while retaining the local Baby, making the Account entry observable without editing Room
or preferences by hand.

UI Automator then showed on the Account page:

- local Baby `年年（当前）` remained available;
- the family card truthfully read `还没和家人一起记`;
- the only family actions were `家庭网络设置`, `新建家庭`, and `加入家庭`;
- `新建家庭` opened `配置家庭网络` with the host, port, primary/secondary Wi-Fi fields and
  `下一步`;
- after a safe Back dismissal, `加入家庭` opened the same network step and fields, adding only
  the mode-specific `扫码填入邀请与家庭网络` action.

Both dialogs were dismissed without submission, so the Account smoke did not create another
family, join another family, or persist a secret. The full created/reclaimed/joined outcomes,
busy serialization, failure recovery, process restore, and consume-once navigation remain covered
by the shared controller and the two entry-adapter automated suites recorded in the ticket.

## Scope limit

This is one Android emulator. It verifies real Compose navigation and semantics for both entry
surfaces, but it does not claim a second Android receiver or spoken TalkBack output.
