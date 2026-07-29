# 01 — 履行草稿保护计划原图

**What to build:** 从含照片的 CarePlan 发起履行时，让 Composer 明确区分计划既有照片与本次新导入照片；取消履行、放弃草稿或移除预填照片都不能删除仍属于计划的原始文件。

**Blocked by:** None — can start immediately

**Status:** implemented-awaiting-device-smoke

**Size:** M

## Acceptance criteria

- [x] 含 1–3 张照片的计划进入履行 Composer 后，预览内容与顺序保持不变。
- [x] 用户取消、返回或显式放弃未保存的履行草稿时，计划照片的物理文件与活跃媒体引用仍然完整。
- [x] 用户在草稿中移除一张计划预填照片时，只改变待提交草稿；原计划照片不被物理删除。
- [x] 本次 Composer 新导入的照片在取消或移除后仍会清理，不产生孤立临时文件。
- [x] 成功履行后，原计划仍保留其原有全部照片；事实 Record 只引用用户确认时草稿中保留的计划照片和本次新导入照片，本次新导入照片不反向附加到原计划。
- [x] 保存失败时原计划及其原图不变，草稿和本次导入保留为可重试状态；用户随后明确放弃时只清理本次新导入且无其他引用的文件。
- [x] 进程重建后的取消、移除和确认行为与首次打开一致，不把计划照片误判为临时导入。
- [x] 回归测试使用真实临时文件覆盖取消、单张移除、确认和进程重建，并断言实际文件字节是否存在。

## Implementation notes

- `QuickRecordDraft` 显式保存三组有序来源：持久化实体的 `sourcePhotos`、履行时只借用的
  `borrowedPhotos`、本次草稿导入的 `ownedDraftPhotos`；整份草稿继续随
  `RecordComposerSavedState` 恢复。
- `RecordComposerPhotoLifecycle` 只从 draft-owned 路径生成 remove/abandon cleanup
  candidates，并 fail-closed 排除 source/borrowed 重复路径。确认后保留的导入路径已由
  `fulfillCarePlan` 事务建立 Record 媒体行，因此不进入清理候选。
- `CareLog` 事务测试固定原计划媒体集合不变、事实只引用确认顺序，以及媒体写入失败时
  Record/Candidate/计划状态与媒体行一起回滚。

## Validation

运行 Composer/CarePlan 相关单元与集成测试、应用编译及静态检查；至少在设备上 smoke 一次“含照片计划 → 履行 → 返回/取消”。

- `./gradlew :feature:log:testDebugUnitTest :domain:testDebugUnitTest --no-daemon`：通过。
- `./gradlew :feature:log:lintDebug :domain:lintDebug :app:assembleDebug --no-daemon`：通过。
- 真实临时文件覆盖 0–3 张、有序 borrowed refs、重复路径、移除、放弃、成功确认、失败后
  重建/重试；逐文件断言原始字节保留或 owned 临时文件被删除。
- 设备 smoke：等待共享 `emulator-5554` 释放后执行；完成前不把本票标记为 complete。

## Documentation Gate

若草稿照片所有权契约有变化，同步更新 Record/CarePlan 媒体生命周期文档。

## Out of scope

跨多个已持久化实体的共享文件最终回收由 Ticket 02 处理。
