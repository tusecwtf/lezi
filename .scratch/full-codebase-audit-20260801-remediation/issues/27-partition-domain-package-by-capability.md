# 27 — domain 按能力分包并保留 CareLog façade

**What to build:** domain 内部按护理记录、护理计划、家庭、时间轴等能力形成 locality，
同时保留 `CareLog.kt` 与 `DomainModule.kt` 根入口，不把用例拆成浅 Gradle module。

**Source:** merged directory C2
**Blocked by:** 05、06、07、08、13、22 — 先闭合同一 domain seam 的行为票
**Status:** ready-for-agent
**Size:** M–L

## Acceptance criteria

- [ ] 按实际职责建立 `carelog/`、`careplan/`、`family/`、`timeline/`、`growth/`、
  `catalog/`、`calendar/`、`localdata/`、`export/`；不为只有一个偶然类型强造层级。
- [ ] `CareLog` 继续作为深 façade 委托 coordinators；`DomainModule` 保持 DI 根入口。
- [ ] feature 仍只依赖 domain seam，不直接注入 DAO，也不新增 feature→feature 依赖。
- [ ] test package 镜像实现职责；现有 `CareLogTest` 等行为合同继续通过。
- [ ] diff 只包含移动、package/import 与必要可见性调整，无护理记录/计划/清理行为变化。

## Validation

运行 `:domain:compileDebugKotlin`、`:domain:test`、相关 feature tests、
`:app:assembleDebug` 与 `lintDebug`。

## Documentation Gate

实际落地形状由 Ticket 33 汇总。

## Out of scope

不拆 `CareLog` 公开 façade，不新增 UseCase Gradle module，不修改领域合同。
