# 26 — feature/log 按调用流分包

**What to build:** 时间轴、快捷坞、Composer、布局编辑和记录照片分别拥有可定位子包，
`feature/log` 根只保留跨流入口；Gradle 模块与产品行为不变。

**Source:** merged directory C1
**Blocked by:** 09、12、20、22 — 先闭合 Composer/layout 行为和结构测试删除
**Status:** done
**Size:** M–L

## Acceptance criteria

- [x] 建立 `timeline/`、`dock/`、`composer/`、`layout/`、`photo/`，按职责移动 main 文件。
- [x] 根 package 仅保留 `LogScreen`、`LogViewModel`、`LogDialogHost` 等跨流入口和真正共享类型。
- [x] main/test/androidTest 的 package 与 import 一致；Hilt/Compose preview/navigation 可编译。
- [x] 不新增 Gradle module，不添加一对一 wrapper/typealias 作为永久迁移层。
- [x] diff 只包含移动、package/import 与必要可见性调整；护理记录、常用记录、布局编辑态行为不变。

## Validation

运行 `:feature:log:compileDebugKotlin`、`:feature:log:testDebugUnitTest`、
`:app:assembleDebug` 与 `lintDebug`；抽查 git diff 证明无业务分支改写。

**Evidence (this worktree):**

- `:feature:log:compileDebugKotlin` green
- `:feature:log:testDebugUnitTest` green
- `:feature:log:compileDebugAndroidTestKotlin` green
- `:app:assembleDebug` green
- `:feature:log:lintDebug` green
- Root main: `LogScreen`/`LogRoute`, `LogViewModel`/`LogUiState`, `LogDialogHost`,
  `RecordScreenMinuteClock` (shared shell clock)
- Subpackages under `com.lezi.babylog.feature.log.{timeline,dock,composer,layout,photo}`
- External consumers updated (`MainActivity`, path-string contract tests); no typealias façade

## Public seams (TDD / design notes)

Contract is existing behavior tests + compile of public entry points (no new StructureTest):

| Seam | Location after partition |
|------|--------------------------|
| `LogRoute` / log shell | root |
| `RecordComposerHost` / `RecordComposerRequest` / `ComposerCreateIntent` / `TimerHandoffSession` | `composer` |
| `quickDockSnackbarBottomInset` | `dock` |
| Timeline list / axis / management actions | `timeline` |
| Layout edit / undo | `layout` |
| Photo import / store / chrome | `photo` |

## Documentation Gate

实际落地形状由 Ticket 33 汇总，不在本票把 `.scratch` 目标写进长期 PRD。

## Out of scope

不重做 Log UI，不改变四槽 + More、本机已删除或 Composer 确认后持久化合同。
