# Ticket 01 photo-plan fulfillment cancel smoke

- Date: 2026-07-30 (Asia/Shanghai)
- Installed code HEAD: `d321436f9a884b221379e34b337d06b9cddaa21e`
- Ticket 01 implementation: `671a8038b73b26cc2de3b0df51080feb588c7cab`
- Device: `emulator-5554`, AVD `lezi_api35`, Android API 35
- Package: `com.lezi.babylog.debug`, version `0.2.6-debug` (`versionCode=5`)

## Flow

1. Restored the historical family and opened Calendar for 2026-07-30.
2. Created a `尿尿` CarePlan scheduled for 03:59 and selected the valid 1024 x 1024 PNG
   fixture `ticket01-plan-photo.png` through Android Photo Picker.
3. Saved the plan, then opened its `完成护理计划` Composer. UI Automator exposed the
   prefilled `记录图片，点击预览` image and its `移除` action.
4. Tapped the visible `取消` action. Calendar again showed `7月30日 03:59 · 待执行`.
5. Reopened the same fulfillment Composer. The prefilled image and `移除` action were still
   present, proving the cancelled draft had not removed the plan-owned photo. Cancelled again
   and left the device on the pending-plan Calendar state.

## Durable receipts

The saved fixture was CarePlan `id=3`, client UUID
`885f7703-ef19-4e5a-8565-f934142214d2`, with MediaAsset `id=5`, client UUID
`52adccfb-1240-4b89-b058-4f051d161d82`.

Before the first fulfillment cancellation, the app-private file receipt was:

```text
f6101ea29087810a316f51e790a3675348b0cf04688a1532b51a506e0bde7f3d
379367 /data/user/0/com.lezi.babylog.debug/files/record-media/c1ab7121-b596-4fc3-90b1-47e2d9085f20.png
```

After cancelling, reopening, and cancelling again, the same receipt was returned:

```text
f6101ea29087810a316f51e790a3675348b0cf04688a1532b51a506e0bde7f3d
379367 /data/user/0/com.lezi.babylog.debug/files/record-media/c1ab7121-b596-4fc3-90b1-47e2d9085f20.png
```

The final read-only SQLite receipt showed:

- CarePlan `id=3` remained `pending`; `fulfilledRecordClientUuid`, `fulfilledAt`, and
  `deletedAt` remained null.
- MediaAsset `id=5` remained attached only to `carePlanId=3`; `recordId` and `deletedAt`
  remained null, and its local URI remained unchanged.
- `records` stayed at 3 before and after cancellation.
- No FulfillmentCandidate referenced the new plan (`matchingCandidateCount=0`); the restored
  database's unrelated candidate total stayed at 1.

## Scope limit

This closes Ticket 01's required real-device Debug smoke on one API 35 AVD. It does not claim a
Release APK gate or multi-device sync validation. The installed app version was observed only;
this closeout does not change version metadata.
