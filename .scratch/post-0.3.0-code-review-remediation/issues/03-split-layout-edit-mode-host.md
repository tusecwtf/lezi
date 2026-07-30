# 03 — 拆分 LayoutEditMode 目录、坞与替代输入宿主

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-02`
**Severity:** P1 structural
**Status:** complete
**Activation:** satisfied — correctness 01/02 complete; user requested all scratch issues
**Size:** L

## What to build

把约 1,655 行 `LayoutEditMode.kt` 按已存在的 reducer/session seam 拆为可审查单元：替代输入与
语义动作、目录/本机已删除表面、编辑 Dock、画布编排。只做逐段等价搬迁，保留单一
`LayoutEditCanvas` 入口和既有 `LayoutEditIntent`/原子快照 writer。

## Acceptance criteria

- [x] `LayoutEditMode.kt` 降到 1,000 行以内，新增单元均有单一职责且不复制 reducer/命中规则。
- [x] 触摸、TalkBack、键盘全部仍只发出既有 `LayoutEditIntent`。
- [x] 目录、类别标题、本机已删除、四槽与固定 More 的行为和语义不变。
- [x] 边缘滚动、撤销、动效/触觉、配置重建、首次帮助无回归。
- [x] 新增结构测试防止目录、Dock、替代输入重新回流宿主。

## Validation

运行布局 reducer、session、a11y、Compose 与 connected 回归，`:feature:log:testDebugUnitTest`、
`:feature:log:lintDebug`、`:app:assembleDebug`；记录拆分前后行数和设备 smoke 边界。

## Documentation gate

行为零变更则无需改 PRD；如发现文档与 live 行为不符，先暂停并重新 grill，不在结构票内修产品。

## Evidence

- [validation.md](../evidence/03/validation.md)
