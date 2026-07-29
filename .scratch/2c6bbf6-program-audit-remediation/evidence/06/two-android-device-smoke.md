# Ticket 06 two-Android device smoke

Date: 2026-07-30 (Asia/Shanghai)

Scope: fixed Android debug APK and Rust server binary from `e6742f1`, both reporting the
pre-release `0.2.6` line. Later unrelated ticket commits were intentionally excluded from this
device receipt. No real TalkBack service was installed, so this is functional and semantics-tree
evidence, not a spoken TalkBack claim.

## Fixed artifacts

- APK: `app-debug.apk`, package `com.lezi.babylog.debug`, version `0.2.6-debug`
- APK size: `28,257,340` bytes
- APK SHA-256: `8b6cca08e1ddad2610ad769ecc7a4b4a0d8bf2ebbe9998bffe31ef9e7355fc2e`
- Server: current Rust binary from the same fixed worktree, temporary SQLite data root and port
  `18765`
- Android A: API 35 `emulator-5556`; Android B: API 35 `emulator-5554`

All family credentials, bootstrap secrets and the one-time invite code are intentionally omitted.

## Android A: edit, fulfill, push and acknowledge

Android A reclaimed the seeded owner through the public onboarding UI and immediately pulled the
family. The local Room snapshot contained one baby, the tombstoned custom definition, its existing
historical Record and pending CarePlan, with an empty Outbox.

The historical Record remained editable under the saved snapshot `历史抚触` / `零照片快照` after
the custom definition had been tombstoned. Editing its note to `历史记录 DeviceA` produced a normal
push/ack cycle; Room then held `syncDirty=0`, `outbox=0`, and the server stored the same note at
revision 11.

Android A then navigated to 2030-01-01 and opened the pending historical plan. The completion sheet
preserved `历史抚触`, `计划快照` and `既有计划`. Confirming completion created a fact and adopted
FulfillmentCandidate. After acknowledgement:

- plan `99999999-9999-4999-8999-999999999703` was `completed`, clean, and linked to fact
  `42bfce92-5832-4724-93b0-2dbbc8b4c41e`;
- the fact preserved the historical title/detail/note snapshot and was clean;
- candidate `ef7ffea8-80c3-4197-b42f-02fe92a8b70a` was `adopted` and clean;
- Android A Outbox count was zero.

The server assigned revisions in the required dependency order: completed CarePlan revision 12,
fact Record revision 13, FulfillmentCandidate revision 14. The custom definition remained a
tombstone at revision 10 rather than being resurrected.

## Android B: invite, full pull and cold restore

Android A created a one-time invitation through the public account UI. Android B was freshly
installed, app data was cleared, and it joined through the public onboarding wizard using
`10.0.2.2:18765` on `AndroidWifi` with display name `DeviceB`.

The first joined screen already showed baby `历史宝宝` and a record card whose semantics exposed
`编辑历史抚触`; visible detail was `历史抚触 · 计划快照 · 既有计划 · DeviceA`. A Room snapshot on
Android B then proved:

- historical Record note exactly `历史记录 DeviceA`, saved title `历史抚触`, detail
  `零照片快照`, clean;
- completed CarePlan linked to the same fact UUID, clean;
- adopted FulfillmentCandidate linked to the same plan and fact, clean;
- custom definition `99999999-9999-4999-8999-999999999701` retained `updatedAt=1030`,
  `deletedAt=1030`, `syncDirty=0`;
- counts were 3 Records, 2 CarePlans, 1 candidate, 2 seeded media rows, and 0 Outbox rows.

After force-stop and cold relaunch, Android B returned directly to `历史宝宝` and showed the same
historical fulfillment fact. It did not return to onboarding, lose the snapshot, or recreate the
tombstoned custom definition.

## Result

The two unchecked device criteria are closed: the current Ticket 06 client/server combination
pushes, acknowledges and drains the historical operations, and a second real Android process pulls
the same tombstone, edited snapshot and completed fulfillment chain. The separate current-wire
receipt remains the evidence for the two-photo atomic bundle and server-restart cases.
