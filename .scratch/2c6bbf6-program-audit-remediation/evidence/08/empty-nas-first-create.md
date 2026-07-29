# Ticket 08 empty-NAS first-create device smoke

- Date: 2026-07-30 (Asia/Shanghai)
- Code HEAD: `e6742f1a3bcde05443b68dcb170b83b16ab69ccb`
- Device: `emulator-5554`, Android API 35, SSID `AndroidWifi`
- APK: `/tmp/lezi-ticket21-device/app/build/outputs/apk/debug/app-debug.apk`
  - package: `com.lezi.babylog.debug`
  - version: `0.2.6-debug`
  - size: `28,257,340` bytes
  - SHA-256: `8b6cca08e1ddad2610ad769ecc7a4b4a0d8bf2ebbe9998bffe31ef9e7355fc2e`
- Server: Rust binary built from the same fixed code HEAD, empty temporary data root, port
  `18766`. The bootstrap value is intentionally omitted.

## Fresh-install flow

1. Clean-installed the fixed APK and granted the current Wi-Fi location permission.
2. The first screen exposed only `新建家庭` and `加入家庭`; it did not expose a third recovery
   action and the local Room database contained no Baby.
3. Submitted `新建家庭` against the empty server. The response followed the `reclaimed=false`
   branch and showed `家庭已建立，请创建第一个家庭宝宝。` instead of entering a blank state or
   recovery loop.
4. Confirmed the single Baby step with nickname `年年`. The app entered the Record page with
   `年年` as the active context and normal `记录 / 汇总 / 成长 / 账户 / 菜单` navigation.

## Durable client and server receipts

After the initial sync drained, read-only `run-as ... sqlite3` receipts showed:

- local Room: `families=1`, `babies=1`, `memberships=1`, `outbox=0`;
- Baby `年年` is live, has the same client UUID published by the server, and is `syncDirty=0`;
- the local user display name is `Owner`.

The fixed Rust server returned healthy and ready responses with version `0.2.6`. Its SQLite data
root contained exactly `families=1`, `memberships=1`, and one live `baby` entity at revision 1;
the server Baby UUID matched the local Room Baby UUID. This rules out a temporary or duplicate
Baby upload on the empty-NAS branch.

Finally, `am force-stop` followed by a cold launch returned directly to the Record page. UI
Automator again showed `年年` and the complete five-tab navigation, so the first-create result is
durable across process restart and does not resubmit create/reclaim.

## Ticket disposition and scope

This empty-NAS receipt covers the `reclaimed=false` complement to the historical-NAS
`reclaimed=true` smoke in
[`../07/fresh-install-current-head.md`](../07/fresh-install-current-head.md). Together they close
Ticket 08's two required fresh-install device branches.

This is one Android emulator and one local Rust server. It does not claim a second independent
Android receiver and does not close Ticket 06's separate two-device criterion.
