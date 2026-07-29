# Ticket 07 / 08 fresh-install owner reclaim smoke

- Date: 2026-07-30 (Asia/Shanghai)
- Code HEAD: `e5d87723e93e0d1194de87797e7c243f89409a50`
- Device: `emulator-5554`, Android API 35, SSID `AndroidWifi`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
  - size: `28,464,221` bytes
  - SHA-256: `d1f4b8f8617f0bedf55ddf0e9218a144fdc62e63947f4def0229179d6a786725`
- Server: current Rust source binary, fresh SQLite data root, same data root restarted before
  Android reclaim.

## Fresh-install flow

1. Uninstalled `com.lezi.babylog.debug`, installed the APK above, and granted the location
   permission required for the current Wi-Fi gate.
2. The first screen exposed only `新建家庭` and `加入家庭`. No separate owner-recovery
   action and no local Baby existed.
3. Submitted the create flow against a NAS database containing one historical Baby, records,
   plans, custom items, a member, an avatar, log photos, a tombstoned custom definition, and an
   explicit fulfillment set.
4. The same foreground request returned from reclaim directly to the app's Record page. No
   refresh, background/foreground toggle, or second recovery tap was used.
5. UI Automator showed `历史宝宝` as the active context. After `am force-stop` and relaunch it
   again showed `历史宝宝` and the normal `记录 / 汇总 / 成长 / 账户 / 菜单` navigation.

## Durable replica receipt

Read-only `run-as ... sqlite3 -json databases/lezi.db` after force-stop/relaunch returned:

- `families=1`, `babies=1`, `records=3`, `care_plans=2`, `custom_items=2`,
  `media_assets=4`, `fulfillment_candidates=1`, `outbox=0`.
- Baby `1111...1111` is `历史宝宝`, clean, and points to the downloaded avatar.
- Records include the live formula history, the tombstoned historical custom record, and the
  live explicit-fulfillment fact; all are clean.
- Plans include the pending historical bath plan and completed historical custom plan linked to
  the fulfillment fact; both are clean.
- The historical custom definition remains tombstoned locally and is not resurrected.
- The fulfillment candidate is `adopted`, owner-stamped, and links the completed plan to its
  fact Record.
- Four media rows are clean; log media retain record-only local ownership (`babyId=NULL`).
  The three log files and avatar file exist in app-private storage and produced stable SHA-256
  values via `run-as ... sha256sum`.
- A recursive exact-value search of app-private files found no persisted bootstrap secret
  (`grep` exit 1, no matching path). The secret value itself is intentionally omitted here.

## Regression gates

- Media compatibility fix: `:sync:testDebugUnitTest`, `:sync:lintDebug`, and
  `:app:assembleDebug` passed.
- Empty-database scaffold fix: `:domain:testDebugUnitTest`, `:domain:lintDebug`, and
  `:app:assembleDebug` passed.
- TDD reproduced both device-only gaps before the fixes:
  - current NAS log media may carry historical `baby_client_uuid` metadata;
  - a fresh local database needs the Family scaffold before synchronous reclaim full-pull.

## Scope limit

This is one real Android receiver plus a current-wire API peer. It does not claim a second
independent Android device. Ticket 06's separate two-Android-device criterion remains open.
