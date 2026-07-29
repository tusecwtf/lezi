# 08 — CareLog 第一刀：展示 helper 与照片附件

**Parent:** [../spec.md](../spec.md)

**What to build:** 从超大 `CareLog` 中切出第一批深边界：**不该住在领域日志门面的展示 helper**（相对时间、奶量候选等）与**已参数化的照片附件**（依赖 05）迁到专用类型/文件，CareLog 保留记录/计划事实写入与协调。本票是 expand–contract 第一刀，不追求一次拆完 ~3k 行。

**Blocked by:** 05 — 照片 reconcile/tombstone 按所有者参数化

**Status:** complete

**Size:** L  
**Theme:** E（R10）  
**Seams:** domain CareLog 门面

## Acceptance criteria

- [x] 至少一类 UI/展示纯函数离开 CareLog 主文件，并由原 call site 使用新位置
- [x] 照片 reconcile API 以 05 的参数化结果为边界，CareLog 不再内嵌第二份算法体
- [x] 记录/计划 CRUD、履行、清理相关既有测试绿
- [x] CareLog 主文件行数可度量下降（相对本票基线），或公开成员数减少；在 PR 说明中写明前后对比

## Implementation notes

- 新增 public `CareLogPresentation` 作为宝宝年龄、相对时间、时钟、奶量候选与候选居中
  的唯一展示算法对象；固定日期/时间/时区参数与既有默认值、中文输出不变。
- 原 5 个 top-level public 签名移到专用文件并保留为一行 compatibility delegate；因此 app、
  family、log、search 与 domain 的所有 live call site 无需改写，仍统一执行新对象。
- `CareLog.kt` 从本票基线 2,884 行降到 2,818 行（−66）；主文件移除 5 个 public 展示
  helper 与 1 个 private 日历 helper，CareLog class 的 Record/CarePlan CRUD API 不变。
- Ticket 05 的 `PhotoAttachmentReconciler` 与 `PhotoAttachmentOwner.Record` / `.CarePlan` 边界
  原样保留；本票没有恢复 owner-specific reconcile/tombstone 算法。

## Validation evidence

- 5 个 approved public-seam tracer 逐条取得 unresolved-member RED 后最小 GREEN；既有
  `BabyAgeLabelTest` 继续锁 compatibility delegate 的完整日历边界。
- `./gradlew :domain:testDebugUnitTest :domain:lintDebug :app:assembleDebug --no-daemon`：
  `BUILD SUCCESSFUL in 23s`，518 actionable tasks（69 executed，449 up-to-date）。
- `git diff --check`：通过；本票未修改版本、UI、timer、Program 22 或 Layout 05 文件，
  且未使用模拟器。
- 完整回执见 `../evidence/08/validation.md`。

## Out of scope

- 重写同步入站引擎或把 wire mapping 全部搬进 domain
- 一次拆完宝宝档案/冲突审计/日历投影全部子域
