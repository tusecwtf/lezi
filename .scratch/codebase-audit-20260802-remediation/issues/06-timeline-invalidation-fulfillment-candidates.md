# 06 · Timeline invalidation observes fulfillment_candidates

Status: complete — accepted on `6b278242`

## Findings

- `core/database/src/main/kotlin/com/lezi/babylog/core/database/TimelineWindowDao.kt:25-33`:
  `observeInvalidations()` 只观察 records/care_plans/media_assets 三表,但窗口查询
  (`listRecordRoots` 40-44、`listActiveLogMedia` 94-98)用
  `NOT IN (SELECT ... FROM fulfillment_candidates WHERE adoptionStatus='conflict_not_adopted')`
  过滤。`CarePlanCoordinator.resolveFulfillmentAuthorityForPlan`
  (`domain/.../careplan/CarePlanCoordinator.kt:629-657`)只写 candidate 行时
  时间线 Flow 不失效 → 冲突解决后 UI 不刷新。

## Fix

- [x] `observeInvalidations()` count 查询追加 `+ (SELECT COUNT(*) FROM fulfillment_candidates)`。

## Validation

- [x] `./gradlew connectedDebugAndroidTest`;Room 回归只写 fulfillment_candidates 时
  `observeInvalidations` 发射新值。
