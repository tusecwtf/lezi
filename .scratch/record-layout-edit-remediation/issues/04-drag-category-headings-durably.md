# 04 — 分类标题拖动与可靠类别排序

**What to build:** 在最终四列编辑目录中显示可辨识、可拖动的分类标题，让用户调整类别顺序，并让完成、返回、日常目录和进程重建都可靠反映同一类别序。

**Blocked by:**
- 02 — 原子持久化 DeviceLayoutSnapshot
- 03 — 当前可见且互斥的拖放目标

**Status:** complete

## Acceptance criteria

- [x] 每个可见类别都有与“添加记录”一致的标题；标题自身形成明确拖动表面，不用拖类别内第一个项目代替。
- [x] 长按一个类别标题并释放到另一类别标题会产生单一类别排序 intent，支持移到首、中、末位置；拖回自身和越界均为 no-op。
- [x] 拖动中源标题、跟手反馈和目标标题高亮清晰，且高亮只来自 Ticket 03 的当前命中结果。
- [x] 类别排序只改变类别序，不改变项目所属领域分类；项目跨类别投放继续为 no-op。
- [x] 可见项目为 0 的普通类别不占空标题；自定义类别即使为空仍保留标题和管理/新增入口。
- [x] 日常“添加记录/更多”在退出编辑后立即按新类别序展示，不需要重新进入页面或切换前后台。
- [x] 每次类别移动通过 Ticket 02 的完整快照提交；完成、系统返回和 force-stop/relaunch 后顺序一致。
- [x] warm / journal 共用同一类别 intent 和持久化规则，不出现模板专属排序分支。
- [x] reducer、命中和 Compose 测试覆盖首/中/末移动、边界、空类、自定义空类、跨类 no-op、退出即更新和重启恢复。

## Validation

- 运行类别排序、目录展示、布局 reducer、快照持久化与 Compose 标题拖动测试。
- 运行 `:core:ui:testDebugUnitTest`、`:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 设备 smoke 一次“拖类别到首位 → 完成 → 打开更多 → force-stop/relaunch”，并分别核对编辑页与日常目录。
- 运行 `git diff --check`。

## Documentation Gate

在布局编辑 spec/UI PRD 中明确类别标题是排序入口、普通空类隐藏、自定义空类保留，以及类别序属于 `DeviceLayoutSnapshot` 的设备本地字段。

## Evidence

- 纯逻辑与 reducer 使用单一 `MoveCategoryToIndex`，字面 JSON 测试锁定首、中、末结果并断言只改变 `categoryOrderJson`。
- `LayoutDragSessionTest` 覆盖同域 heading 命中、self/outside、项目跨域 no-op 与当前节点注销；`MoreSheetCatalogTest` 锁定最新快照立即驱动日常目录。
- API 35 isolated Compose 生产画布真实长按矩阵首/中/末与 held-avatar 4/4 通过（0 failures、0 errors、0 skipped）。
- `:core:ui:testDebugUnitTest`、`:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、`:feature:log:lintDebug`、`:app:assembleDebug` 与 `git diff --check` 已通过。
- 实际应用将“排泄”长按拖到“喂养”之前后，编辑器立即显示 `排泄 → 喂养 → 日常`，Done 后 More 显示 `排泄 → 喂养`；force-stop 后 `LaunchState: COLD`，编辑器与 More 仍保持相同顺序。
- 验收使用 `0791eb73c621a9bd47414e478fecd91aebebcaf5` 的隔离构建，API 35 `lezi_api35(AVD) - 15`（1080×2400、420 dpi），APK SHA-256 为 `bb96dd5bc440b3a43be2781c591cf82d635ad7941cbdd88a14b23b72865615d9`；完整回执见 [`evidence/04`](../evidence/04/validation.md)。
