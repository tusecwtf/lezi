# 15 — 删除有全树证据的 Android dead façade 与 compatibility residue

Status: implemented

Priority: P3

Audit baseline: `38cfbe7dbcf1f410237dce77b50ec1bd4854d8a6`

Blocked by: 09、10、11 与 `post-0.3.13-review-remediation/02` cleanup；最后做，先重新 pin clean HEAD。

## Findings

固定提交全树只有声明、自引用或“仅测试锁住生产 façade”的项目包括：

- `core/model/Models.kt:43-65`：`Family`、`LocalUser`、`MemberRole`、`MemberStatus`、`Membership`；
  runtime 已使用 Room entities、sync `FamilyRole` 与 server membership。
- `feature/family/FamilyModels.kt:33-88` 的 `FamilyUi/familyUiFromHosts`；唯一 consumer 是
  `FamilyHostReadModelTest.kt:93`，生产无调用。
- `wizard/WizardSessionDialogs.kt:600-685` 的 17 参数 1:1 QR middleman 与明确为 tests 保留的生产
  overload；`FamilyComponents.kt:53-117` 的 dead row/shallow alias。
- `core/ui/RecordPresentation.kt:65-89` deprecated custom glyph、`BabyMetaLine.kt:41` avatar size、
  `FamilyUiPolicy.kt:495` member label、`LocalLayoutEditPolicy.kt:192` slot count、
  `WidgetComposerContract.kt:61-65` deprecated `parse()` alias。
- `QuickRecordDraft.kt:525-526` unused validation helper、`SettingsScreen.kt:135-138,970` unused palette/
  swatch、`HttpSyncBackend.kt:64` 与 `ReplicaSyncEngine.kt:3491` unused constants。

这些项目不含 post-02 已拥有的 B1/open-sleep/neighbor/neighbor_losers；不得在本票重复删除它们。

## Interface boundary

直接删除没有消费者的类型/API/test，不建立 replacement façade。若某符号在开始时的新 clean HEAD 已
获得真实 production consumer，则从本票移除并记录证据，不能为了完成 checklist 删除在用行为。

## Acceptance

- [x] 对每个符号先在实施 HEAD 记录 production/test/reflection/serialization consumer inventory
- [x] 确认无 consumer 的 core family models 与 speculative `FamilyUi`/其只锁 façade 测试被删除
- [x] 生产源码不再保留 test-compatibility overload；QR Compose tests 走真实 typed model/surface
- [x] 逐符号删除 deprecated/unused helper、constant、shallow alias，并清理误导性 legacy 注释
- [x] 不改 Room schema/migration、wire JSON name、Compose semantics 或公开产品行为
- [x] 不新增 line-count/source-layout StructureTest；保留测试按独立可观察合同分类

## Validation

- [x] 删除后逐符号 `git grep` 为零或只剩明确 documented external contract
- [x] 受影响 module JVM/Compose tests、app assemble/lint 通过
- [x] 小批 deletion diff review 与 `git diff --check` 通过

## Implementation evidence

实施重新固定在 clean `master` `1ce7160ff64db3e0c1278b3bceaa1b4eebab1cbd`。在删除前对每个
候选执行全树 production/test、KSP/Room/Hilt、Kotlin serialization、Compose navigation、反射、
manifest、Gradle 与 generated-contract inventory：

- `Family`、`LocalUser`、`MemberRole`、`MemberStatus`、`Membership` 只有源码声明；PRD/CONTEXT 中
  同名概念是外部权威术语而非 Kotlin consumer，因此保留文档，只删除未注解且未持久化的模型。
- `FamilyUi/familyUiFromHosts` 只有 `FamilyHostReadModelTest` 锁住该 speculative façade；删除 façade
  与该单项测试，保留同一测试类中覆盖 host read-model 可观察行为的测试。
- 两个 `MemberLoginQrConfirmDialog` 都只是 compatibility middleman；生产与 androidTest 改为直接调用
  `MemberLoginQrConfirmSurface` typed seam。`FamilyDestructiveButton` 的三个 production consumer 直接改用
  等价的 `LeziDestructiveButton`；`FamilyGuideRow` 无 consumer。
- glyph/avatar/member-label/layout-count/widget-parse/interval-validation/palette-swatch/HTTP 与 pull-limit
  候选均只有声明或自引用；逐项删除，未建立 replacement façade。
- 没有候选参与 Room entity/schema/migration、JSON 字段、Hilt/KSP 绑定、manifest/Gradle 注册、导航 route、
  reflection keep rule 或 generated contract；既有 fixtures、device evidence 与权威文档均保留。

首个敏感批次先删除 QR compatibility overload，`:feature:family:compileDebugKotlin` 如预期因真实 consumer
仍引用旧入口而 RED；改为 typed `MemberLoginQrConfirmSurface` 后 main/androidTest compile GREEN。随后按 family
read model、family components、core presentation/model、log/widget/settings/sync 公共行为 seam 分批编译与测试。
删除后的 23 个精确源码声明全树 `rg` 均为零命中。

最终门禁：`./gradlew test`、`lintDebug`、`:app:assembleDebug`、签名的 `:app:assembleRelease`，以及
app/core database/core UI/family/log/settings/widget 的 `compileDebugAndroidTestKotlin` 均通过。Release
APK 通过 v2/v3 signature verification，certificate SHA-256 与 tracked signer pin 一致；同一最终 APK 的
SHA-256 已写入 `tools/lezi-sync/deploy/app-update.json`，且
`LEZI_PACKAGE_APP_UPDATE_CHECK_ONLY=1 ./deploy/test-package-nas-app-update.sh` 通过。`adb devices -l`
无已连接设备，因此本票没有伪称 instrumentation/device runtime evidence；Compose instrumentation 仅编译。
