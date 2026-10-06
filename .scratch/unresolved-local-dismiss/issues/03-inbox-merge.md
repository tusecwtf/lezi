# 03: 徽章 + 收件箱 + 裁决页合并

**What to build:** 一枚「待处理」徽章、一张底栏。branched 仍走 choice-only 裁决。
未对齐项走同皮肤本机裁决：「先留在本机」/「从本机去掉」。后果句固定为只从这台
手机去掉。撤掉可动作的未收下对话框。时间轴「家里没收下」进同一本机裁决。

**Blocked by:** 02

**Status:** done

- [x] `ConflictInboxItem.kind`；未对齐 `inboxId` 为 `local:…`，不伪造 `conflict_id`
- [x] 「N 项未收下」点进同一底栏；删除 `SkippedPullDetailDialog` 动作面
- [x] 本机裁决确认句：「只从这台手机去掉，不通知家里，其它手机不受影响。」
- [x] 时间轴「仅本机 · 家里没收下」打开本机裁决，不另做对话框
- [x] 徽章文案「待处理 N」；计数含冲突 + 未对齐

## Comments

`ConflictResolutionCoordinator.observeInbox` 合并 branched + skipped + rejected。
`LocalUnresolvedResolverRoute` 与 `UnresolvedInboxIds`；`MainActivity` 按 `local:` 分流。
已删 `SkippedPullDetailDialog`。

Ran: `./gradlew :domain:testDebugUnitTest --tests …ConflictInboxProjectionTest`；
`:feature:family:testDebugUnitTest --tests …FamilyConflictBadgePolicyTest`.
