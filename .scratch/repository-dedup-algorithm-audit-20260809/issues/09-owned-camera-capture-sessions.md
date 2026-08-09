# 09 — 让 camera capture 临时文件具有显式 commit/release ownership

Status: implemented

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- `core/ui/CameraCapture.kt:28-35` 在 `cache/camera/capture_<millis>.jpg` 创建输出路径，只返回 Uri，
  没有 ownership token、delete/release 或过期清理；同毫秒命名也可能碰撞。
- `BabyCreateDialog.kt:86-124`、`BabyEditDialog.kt:112-153` 与
  `QuickRecordSheet.kt:143-193` 各自复制 permission/hardware/create/launch/pending callback。
- 取消 TakePicture、launcher 抛错、替换照片、dismiss dialog，乃至成功导入/裁剪后，都没有删除原
  cache capture。长期拍照/取消会持续积累到系统 cache pressure；外部 picker Uri 又不能被误删。

## Interface boundary

把 capture 建模为 app-owned session，而不是裸 Uri：Module 负责安全唯一文件、launcher 需要的 Uri、
成功 import 后 commit/release、失败/取消/supersede/dispose cleanup。Feature shell 只呈现 typed outcome；
外部 `PickVisualMedia` Uri 明确不归该 Module 所有。

## Acceptance

- [x] 每次 capture 使用无碰撞的安全 app-cache 文件，并返回可恢复的 owned token + Uri
- [x] permission denial、无相机、launcher error、TakePicture false、dialog dismiss、照片替换与
  composition dispose 都释放本次/被替代临时文件
- [x] 成功导入 durable record/avatar/crop 后释放 capture source；导入失败仍可重试或按明确策略回收
- [x] process recreation 后能恢复 pending ownership，或在确定无 launcher consumer 后安全 GC
- [x] 只删除 Module 创建且仍在允许 cache root 的普通文件；绝不删除 picker/content provider 外部 Uri
- [x] create/edit avatar 与 Record composer 复用同一 capture policy，错误文案可由各 shell 本地呈现

## Validation

- [x] fake filesystem/session tests 覆盖 success/cancel/failure/supersede/recreation、delete retry 与路径逃逸
- [x] 三入口 typed launcher seam 已覆盖；core-ui 以真实 remember launcher + fake registry owner 覆盖
  permission/TakePicture result、saveable pending restoration 与 dispose。Instrumentation 均已编译
- [ ] 真实 FileProvider/camera activity/device smoke 尚未执行（当前无设备）
- [x] Android full JVM、lint、Debug/Release assemble 通过

## Implementation evidence (validated worktree based on fixed HEAD `332f160d`)

- `core:ui` 的 `OwnedCameraCaptureSessions` 是唯一 filesystem ownership owner：UUID token、exclusive
  create、canonical-root ordinary-file 校验、幂等/fail-soft release、24 小时 orphan grace 与
  recover-before-GC 都在该 Module 内；picker/content-provider Uri 不进入其 interface。
- `rememberCameraCaptureLauncher` 统一 permission/hardware/activity-result 状态机。saveable pending token
  在配置重建时保留 lease、普通 dispose 时释放；typed `OwnedCameraCapture` 仅暴露 opaque token、Uri 与
  自身 release，真实内部 token 实现保持私有。ActivityResult 的 exactly-one ordered callback 与 Module
  single-flight/Discarding phase 共同保证回调对应当前 launch；controller 仍拒绝不匹配 token。
- Baby create/edit 只在裁剪已产出 JPEG bytes 后 dispose owned source；picker 替换、crop cancel、dialog
  dismiss 与 composition dispose 均清理 owned capture。Record Composer 用 durable import completion
  callback：草稿 attach 成功才回 `true`，失败/取消/被拒绝回 `false`，两者都最终 release source。
- `OwnedCameraCaptureSessionsTest` 通过 fake filesystem 覆盖 unique/collision、recover/recreation、
  success/cancel/failure、supersede、100 次取消、transient delete retry、path escape/symlink/directory 与
  external-looking picker 文件。pending process/config recreation 由 controller snapshot test 覆盖；
  Create/Edit/Record instrumentation 只通过既有 launcher adapter seam 验证 Cancelled release、Captured
  crop/import completion release、shell copy，以及完成态后的入口 restoration，不冒充真实 registry 恢复。
- Targeted `core:ui` JVM、Record photo import runner JVM、family/log Android-test Kotlin compile：pass
  （176 tasks，26s）。Instrumentation 未执行；当前无连接设备。
- `CameraCaptureRegistryDeviceTest` 使用真实 `rememberCameraCaptureLauncher`/ActivityResultRegistry 注册与
  result dispatch，内部 Module seam 仅替换 sessions、Uri、硬件/权限与 config-dispose probes；无 public
  测试 façade。`RecordComposerCapturedPhotoCallbackTest` 直接调用实际 ViewModel seam，覆盖 durable attach
  success、未 open 拒绝、empty failure、exception、cancellation 与 supersede，逐请求 callback exactly once。
- 最新合并 targeted JVM + core-ui/family/log Android-test Kotlin compile：pass（184 tasks，29s）。所有
  instrumentation 均为 compile-only；真实 camera device smoke 未执行。
- Independent fixed-point review：Spec 0 hard / 0 judgement；Standards 0 code hard / 1 judgement。
  judgement 为 capture filesystem create/recover/delete/orphan scan 仍在 Main 同步执行；本票不引入
  无生命周期保证的全局 IO scope。另有非本票 dirty WIP，最终提交必须严格按 ticket09 文件隔离。
- Final gates（专属 `TMPDIR` + test-worker `java.io.tmpdir`）：`./gradlew test` pass（872 tasks，
  1m43s）；`lintDebug`、app Debug/Release assemble、core-ui/family/log Android-test Kotlin compile pass
  （1482 tasks，2m29s），Release APK signature verified。门禁前后 ticket09 产品 diff SHA-256 指纹一致。
- `adb devices -l` 枚举为 0 台设备；registry/FileProvider/camera instrumentation 未执行，只完成编译，
  不作为真实设备证据。
- 第一次 full gate 因机器已有 `/tmp` 100% 满，使仓库既有 16 MiB boundary test 的 JUnit
  `TemporaryFolder` 报 `No space left on device` 而提前结束；未删除任何未知 `/tmp` 文件。最终门禁将以
  task-owned `/var/tmp` 同时设置 `TMPDIR` 与 test-worker `java.io.tmpdir` 后重跑。
