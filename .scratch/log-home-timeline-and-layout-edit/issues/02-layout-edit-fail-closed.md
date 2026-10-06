# 02: 布局编辑态任何保存/焦点异常以「布局尚未保存 / 重试」呈现，不闪退

**What to build:** 编辑态里进入、拖拽、撤销、重试保存的任何失败都停留在编辑态并给出既有重试对话框，
不再有断言逃逸到 UI。按 spec.md 审计表 B1–B5：`requestFocus` 包 `runCatching`；
`DeviceLayoutSnapshotWriter.submit` 的版本不符与 `trySend` 失败改为 `Result.failure` 并置 `Failed`
状态；catalog `checkNotNull` / `!!` 改 `?:`；`LayoutCatalogScrollPosition` /
`LayoutEdgeAutoScrollPolicy` 的 `require` 改 `coerce`；`draggableLayoutSource` 回调用
`rememberUpdatedState`。

**Blocked by:** None (can start immediately)

**Status:** implemented

- [x] `DeviceLayoutSnapshotWriterTest`：`version != DEVICE_LAYOUT_SNAPSHOT_VERSION` 的 `submit` → `state` 为 `Failed`、receipt `result` 为 failure，不抛出
- [x] `LayoutEditMode` 进入首帧（AnimatedContent enter）不抛 focus 异常（device test 反复进入/退出 5 次）— 已写 `LayoutEditEnterFocusDeviceTest`，未跑 `connectedAndroidTest`（无设备）
- [x] B3/B4/B5 改完后现有 `feature/log` layout JVM 测试全绿（device 测试未跑）
- [ ] 真机：编辑 → 拖拽 → 撤销 → 完成 / 返回 / 退出重试 各走一遍，`adb logcat -b crash` 为空 — 未执行（无设备 / 未接 crash 缓冲）

## Comments

- **B1** `LayoutEditMode` 进入首帧 `doneFocusRequester.requestFocus()` 包进 `runCatching`，与 `QuickRecordSheet` 同模式。`LayoutEditEnterFocusDeviceTest` 用 `AnimatedContent` 进出编辑态 5 次；未跑 connectedAndroidTest。
- **B2** `submit()` 不再抛：版本不符与 Persist `trySend` 失败立刻 `Result.failure` + `Failed`。Barrier `trySend` 同样 fail-closed。writer 协程结束后关闭 channel，便于无抖动覆盖 closed-channel。`retryLatest()` 仍走 `submit`。
- **B3** catalog `checkNotNull(FocusRequesters[…])` 改为 `?: FocusRequester()`；`cell.key!!` 改为 `?: return@forEach`。
- **B4** `LayoutCatalogScrollPosition` 去掉 `require`，`valueFor` / 滚动快照 / `rememberScrollState` 用 `coerceAtLeast(0)`。`LayoutEdgeAutoScrollPolicy` 的 `edgeBandPx` / `maxStepPx` 改为 coerce。
- **B5** `draggableLayoutSource` 改为 `composed` + `rememberUpdatedState` 持有四个 drag 回调，`pointerInput` 仍只 key 在 `dragKey`。
