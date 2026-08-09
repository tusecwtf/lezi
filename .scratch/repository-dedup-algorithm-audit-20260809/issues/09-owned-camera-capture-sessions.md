# 09 — 让 camera capture 临时文件具有显式 commit/release ownership

Status: ready-for-agent

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

- [ ] 每次 capture 使用无碰撞的安全 app-cache 文件，并返回可恢复的 owned token + Uri
- [ ] permission denial、无相机、launcher error、TakePicture false、dialog dismiss、照片替换与
  composition dispose 都释放本次/被替代临时文件
- [ ] 成功导入 durable record/avatar/crop 后释放 capture source；导入失败仍可重试或按明确策略回收
- [ ] process recreation 后能恢复 pending ownership，或在确定无 launcher consumer 后安全 GC
- [ ] 只删除 Module 创建且仍在允许 cache root 的普通文件；绝不删除 picker/content provider 外部 Uri
- [ ] create/edit avatar 与 Record composer 复用同一 capture policy，错误文案可由各 shell 本地呈现

## Validation

- [ ] fake filesystem/session tests 覆盖 success/cancel/failure/supersede/recreation 与路径逃逸
- [ ] Compose/activity-result tests 覆盖三个入口；cache inventory 在重复取消后保持有界
- [ ] Android JVM/lint/assemble 及相机设备 smoke 通过
