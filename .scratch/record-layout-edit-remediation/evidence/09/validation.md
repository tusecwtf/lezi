# Ticket 09 validation

## Current disposition

清空常用槽与移入本机已删除在其完整 `after` 快照耐久保存成功后提供一次短时撤销；撤销恢复完整 `before` 快照。实现、故障/并发状态机、API 35 connected 设备测试、真实 Debug app smoke、lint 与 Debug APK 组装均已通过。

## Test-first receipts

1. `DeviceLayoutSnapshotWriterTest` 首个 RED 因 `submit` 没有可等待的 exact 回执而编译失败；GREEN 后每次 submit 返回自己的序号、规范化快照与 `Deferred<Result<Unit>>`，后来写入不改变旧回执，等待者取消也不会取消 FIFO writer。
2. `LayoutUndoStateTest` 逐步锁定只有 `ClearSlot` / `MoveToLocalDeleted` 的成功 exact 原写才发布 offer；连续高风险操作、任一后续 intent、当前 UI 不等于 `after`、旧 token、超时和退出都会使旧 offer 无效。
3. 撤销失败 RED 要求不得先恢复 UI 或播报成功；GREEN 后失败保持 `after` 和同 token `RestoreFailed`，重试成功才发布完整 `before` 与“布局已撤销”。
4. Compose 设备键盘 RED 证明 `SnackbarDuration.Short` 无法在完整 Tab 链中及时到达尾部 action；保留 Material 3 原生 Snackbar，并在有效 candidate 存在时于编辑器根加入标准 Ctrl+Z，交给同一 exact-token `onUndo`。无 candidate 不拦截。
5. 提交前审查发现撤销 `before` 回写在途时，后续 no-op intent 虽能使 token 失效，却可能因 `next == current` 没有补写而让耐久值停在 `before`。新增测试先以 unresolved compensation helper RED；GREEN 后仅在先前状态为 `Restoring` 且该 intent 已使 token 失效时，将 UI 仍显示的完整 `after` 排入 FIFO。补偿失败进入当前 `after` 对应的可见 Failed 状态，`retryLatest` 成功后耐久值与 UI 重新一致。

没有添加测试专用 writer、撤销回调旁路或第二套布局 reducer。Snackbar 点击、Ctrl+Z、失败重试最终都进入同一 token 化状态机和完整快照 writer。

## JVM, lint and build gates

```text
./gradlew :core:datastore:testDebugUnitTest \
  :feature:log:testDebugUnitTest \
  :feature:log:compileDebugAndroidTestKotlin \
  :feature:log:lintDebug \
  :app:assembleDebug
```

结果：`BUILD SUCCESSFUL in 28s`；594 tasks（92 executed，502 up-to-date）。lint HTML 写入 `feature/log/build/reports/lint-results-debug.html`，Debug APK 写入 `app/build/outputs/apk/debug/app-debug.apk`。

最后 no-op/in-flight 补偿加固的定向命令：

```text
./gradlew :feature:log:testDebugUnitTest \
  --tests '*LayoutUndoStateTest' \
  --tests '*DeviceLayoutSnapshotWriterTest'
```

结果：预期 RED 为 `shouldWriteLayoutIntentResult` unresolved reference，`BUILD FAILED in 2s`；最小实现后同命令 `BUILD SUCCESSFUL in 6s`，105 tasks（10 executed，95 up-to-date）。生产 Kotlin 与两组定向测试均在该最终代码上重新编译。

writer/reducer 定向覆盖包括：

- 每个 exact 回执独立完成、FIFO、失败后后续成功、等待者取消不取消 writer；
- 完整四槽/隐藏集合/类内序/类别序/版本快照恢复；
- 连续两个高风险 intent 只保留新 token，安全 intent 立即使旧 token 失效；
- 原写失败/current mismatch 不发布，撤销失败保持 `after`，同 token 重试成功；
- stale completion、超时、退出均为空操作或清除 offer。

## API 35 connected receipt

授权设备仅为 `emulator-5554`。最终无干扰命令：

```text
ANDROID_SERIAL=emulator-5554 ./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=\
com.lezi.babylog.feature.log.LayoutUndoDeviceTest
```

结果：`Starting 4 tests on lezi_api35(AVD) - 15`，`Finished 4 tests`，`BUILD SUCCESSFUL in 19s`；201 tasks（1 executed，200 up-to-date）。四项验证 active exact token 的 Ctrl+Z、替换后只使用最新 token、超时后 Ctrl+Z 不触发，以及退出立即移除 action。

此前一次全组被另一个并发 connected 任务安装/卸载测试包干扰，logcat 明确出现 `package.install`、测试包被移除及 `adbd` flush timeout，仅产生 `INSTRUMENTATION_FAILED Process crashed`，无产品断言堆栈；该轮不计为产品结果。上面的最终全组在 root 停止并发任务后唯一重跑并通过。

## Real Debug app smoke

安装本次门禁生成的 `app-debug.apk` 到同一 `emulator-5554`，保留应用数据。通过生产记录页日常 Dock 长按进入布局编辑器，并用 Android 原生 motionevent 的 DOWN → 1s → MOVE → UP 注入真实长按拖动：

1. 初始完整 UI 快照为 `[尿尿, 睡眠, 母乳, 配方奶]`、本机已删除 0。槽1拖出 Dock 后实际显示“常用槽1，空”“布局已保存”和原生“已清空常用槽 / 撤销”；点按撤销后槽1恢复尿尿并显示“布局已撤销”。
2. 将占用槽1的尿尿拖入本机已删除后立即点按撤销；结果为本机已删除 0、槽1尿尿和“布局已撤销”，证明隐藏集合与四槽一起恢复。
3. 再次隐藏尿尿后，把配方奶从喂养类第2位拖到第1位，再按 Ctrl+Z。最终配方奶 bounds 在 x49、母乳在 x304，本机已删除仍为 1、槽1仍为空，且没有撤销成功提示；后续排序已使旧 token 失效。
4. 点“完成”后日常 Dock 立即显示空槽1及未变的槽2–4。force-stop/relaunch 后长按槽2重进，仍恢复槽1空、槽2睡眠、槽3母乳、槽4配方奶、已删除1及配方奶在母乳前；旧 Snackbar 没有复活。

取证结束后又通过生产恢复/指派/排序动作把模拟器恢复到原始 `[尿尿, 睡眠, 母乳, 配方奶]`、本机已删除 0、母乳在配方奶前，未遗留 smoke 数据变化。

## Documentation and verification boundary

- `docs/prd/ui.md` 明确短时原生操作、Ctrl+Z、exact token、后续 intent/超时/退出失效、失败重试和设备本地边界。
- `docs/prd/data-model.md` 明确撤销是进程内单层写入协议，不序列化为历史，恢复仍走同一 FIFO 完整快照 writer。
- API 35 镜像没有 TalkBack package；本票验证原生 Snackbar 语义树/点击与真实设备键盘 Ctrl+Z，但不声称 spoken TalkBack 人工 smoke。
- 本票未修改版本、同步 wire、数据库 Record、家庭共享定义或 NAS 数据。
