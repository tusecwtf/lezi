# 15 — 删除有全树证据的 Android dead façade 与 compatibility residue

Status: ready-for-agent

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

- [ ] 对每个符号先在实施 HEAD 记录 production/test/reflection/serialization consumer inventory
- [ ] 确认无 consumer 的 core family models 与 speculative `FamilyUi`/其只锁 façade 测试被删除
- [ ] 生产源码不再保留 test-compatibility overload；QR Compose tests 走真实 typed model/surface
- [ ] 逐符号删除 deprecated/unused helper、constant、shallow alias，并清理误导性 legacy 注释
- [ ] 不改 Room schema/migration、wire JSON name、Compose semantics 或公开产品行为
- [ ] 不新增 line-count/source-layout StructureTest；保留测试按独立可观察合同分类

## Validation

- [ ] 删除后逐符号 `git grep` 为零或只剩明确 documented external contract
- [ ] 受影响 module JVM/Compose tests、app assemble/lint 通过
- [ ] 小批 deletion diff review 与 `git diff --check` 通过
