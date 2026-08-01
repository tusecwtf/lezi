# 22 — 删除无契约源码结构测试

**What to build:** 重构不再被读取源码字符串、行数或符号落点的 `*StructureTest` 阻挡；
护理记录、Composer 与布局编辑行为继续由可观察合同测试保护。

**Source:** merged readability 01
**Blocked by:** None — can start immediately
**Status:** done
**Size:** S

## Acceptance criteria

- [x] 删除现存七个 `*StructureTest`：domain 四个、feature/log 三个。
- [x] 不把它们改写成新的文件名、行数、源码字符串或反射结构守卫。
- [x] 仓库无 `StructureTest` 残留；真实 `CareLog`、Composer、layout 行为测试仍保留。
- [x] 若删除暴露出未覆盖产品合同，只补 public seam 的行为测试，不固定实现落点。

## design_notes (seams)

Public seams already covered by behavior tests (no replacement structure guards):

- domain: `CareLog` / coordinators → `CareLogTest`, `CareLogPresentationTest`, fulfillment/plan/aggregation/search tests
- feature/log Composer → `RecordComposer*Test`, `ComposerTimerHandoffTest`, device discard tests
- feature/log layout edit → `LayoutEdit*Test`, `LayoutUndo*Test`, layout device tests

Deleted pure source-string / line-count guards only; no product contract gap found requiring new seam tests.

## Validation

运行 `:domain:test`、`:feature:log:testDebugUnitTest`、`:app:assembleDebug` 与 `lintDebug`；
`rg StructureTest` 无结果（源码/测试树；tracker 文案可仍指称已删守卫）。

## Documentation Gate

无；长期约定由 Ticket 33 写入 AGENTS/tech。

## Out of scope

不移动 package（Tickets 26/27），不删除真实行为回归。
