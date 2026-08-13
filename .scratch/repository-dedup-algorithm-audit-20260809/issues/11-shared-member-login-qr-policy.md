# 11 — 两个成员登录入口共享一个不可信 QR policy

Status: implemented

Priority: P2

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: None.

## Findings

- Family 在 `FamilyScreen.kt:268-283` 手写 trim/decode/expiry/copy；Onboarding 使用自己的
  `qr/OnboardingMemberLoginQrUi.kt:20-31` parser。相同凭据可能在过期等号边界或错误分类上漂移。
- 两入口又复制 CameraX/permission/ZXing options（`FamilyScreen.kt:284-310`、
  `OnboardingScreen.kt:182-215`）。这是认证输入与 scanner Adapter policy 的重复 owner。

## Interface boundary

在两个 feature 都可依赖的低层模块建立 typed `MemberLoginQrScanPolicy`，输入 raw + clock，输出
`Empty / Rejected(reason) / Ready(payload)`。共享 camera scanner Adapter 只集中硬件、权限与 decoder
options；各 feature 保留本地导航/文案，不得新增 feature→feature 依赖或 1:1 wrapper。

## Acceptance

- [x] 同一 raw/time 在 Family 与 Onboarding 得到完全相同 typed outcome
- [x] blank、malformed、wrong kind/version、expiry equality、expired、valid 都有表驱动合同
- [x] 无相机、权限拒绝/永久拒绝、取消、重复帧与 lifecycle stop 由一个 scanner policy 处理
- [x] codec/expiry 规则只有一个 owner；feature shell 不再自行 decode 或比较时间
- [x] 保持现有 TOFU/member-login session flow，不把 token/QR raw 写日志或 saved state

## Validation

- [x] shared policy unit tests 与两个入口的 shell JVM tests 通过
- [ ] 两个入口的真实 surface/Compose tests 与 fake analyzer registry test 已编译；当前无设备，未执行
- [x] Android full JVM、lint、Debug/Release assemble 与相关 androidTest Kotlin compile 通过
- [ ] 至少一个相机/图片 QR device smoke 未执行（`adb devices -l` 枚举为 0 台）

## Implementation evidence (validated worktree based on fixed HEAD `37ca81e2`)

- `sync/qr/MemberLoginQrScanPolicy` 是 untrusted raw、strict content codec 与 expiry equality 的唯一
  decision owner：只输出 `Empty / Rejected(reason) / Ready(payload)`；等于 `expires_at` 即判过期。
  表驱动 JVM 合同覆盖 blank、malformed、wrong kind/version、equality、expired 与 valid，且 Ready
  不改写 grant。
- `core:ui/memberloginqr` 的 shared Adapter 独占 camera hardware、permission/永久拒绝分类、QR-only
  ZXing options、single-flight 与 dispose/late-callback policy。saveable state 只有 pending phase；raw、grant
  与 payload 都不进入日志或 SavedState。Onboarding 删除自己的 ZXing dependency；没有新增 feature→feature
  依赖或 Gradle module。
- 真实 `rememberLauncherForActivityResult` + fake `ActivityResultRegistry` instrumentation 覆盖 permission、
  no-camera、permanent denial、cancel、重复 frame 只交付一次、dispose 后 late scan/permission 无回调、
  permission-flight SavedState 重建、launcher exception 后可重试，以及统一 ScanOptions。该测试只完成编译，
  不冒充物理 camera/image decoder smoke。
- Family 与 Onboarding production route 都调用各自 internal composable binding；其 default 是 shared real
  Adapter，internal factory 仅供测试替换硬件。两项 androidTest 真实渲染既有扫码 surface、点击真实按钮并
  投递 typed Ready/NoCamera，验证 verify payload 与各 shell 本地文案；同样因无设备仅完成编译。
- Targeted policy + 两 shell JVM、core-ui/family/onboarding androidTest Kotlin compile：pass。Final gate 使用
  task-owned `/var/tmp` 同时设置 `TMPDIR` 与 test-worker `java.io.tmpdir`：`./gradlew test`、`lintDebug`、
  app Debug/Release assemble 与三 module androidTest Kotlin compile 均 exit 0；Release 使用既有 gitignored
  本机签名配置的只读 Gradle property，临时只读 keystore symlink 已删除。
- `adb devices -l` 经只读枚举显示 0 台设备；Compose/registry instrumentation 与相机/图片 QR smoke 未运行。
  Independent serial fixed-point review：Standards 0 hard / 0 judgement；Spec 0 hard / 0 scope，唯一 partial
  是上述真实 device smoke。
