# 04 — 抽离 CareLog 查询与摘要 seam

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-02`
**Severity:** P1 structural
**Status:** complete
**Activation:** satisfied — correctness 01/02 complete; user requested all scratch issues
**Size:** L

## What to build

从约 2,837 行 `CareLog.kt` 抽出只读查询/聚合协调器，覆盖 records/plans observation、day/week
summary、search、recent summary/milk/notes、measurements 与 surface filtering。`CareLog` 保留现有
public facade，通过单一 `CareLogQueries`（或等价深 seam）委托；不得复制 SQL 或聚合规则。

## Acceptance criteria

- [x] 所有指定只读入口委托到一个专用查询 seam，调用者 API 与 Flow 语义不变。
- [x] `CareAggregation`、`RecordSearch`、`ConflictAuditQueries` 等既有单源继续复用。
- [x] 时区、开放睡眠、履行 surface、权限与排序结果逐项等价。
- [x] CareLog 主文件显著下降并记录前后行数；查询实现不保留双份。
- [x] 添加 public-seam 结构/契约测试，防止查询逻辑回流。

## Validation

运行 domain 全量单测、summary/search/widget 调用模块测试、`:domain:lintDebug` 与
`:app:assembleDebug`；必要时对固定数据库 fixture 比较搬迁前后结果。

## Documentation gate

纯结构搬迁不改 PRD。不得改变数据真相源、时间窗口或 surface record 定义。

## Evidence

- [validation.md](../evidence/04/validation.md)
