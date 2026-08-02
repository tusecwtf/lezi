# 10 · Data screens reauth + milk summary fields

Status: ready-for-agent

## Findings

1. `SyncStatus.ReauthRequired` 在三个数据页不可见:
   `feature/log/.../LogViewModel.kt:191`、`feature/summary/.../SummaryScreen.kt:310`、
   `feature/growth/.../GrowthScreen.kt:310` 只认 `SyncStatus.Error`。会话过期时页面
   显示陈旧数据且无任何提示。(与 family-sync-hang tracker 票 10 重叠,本票做最小
   对照:把 ReauthRequired 纳入失败提示。)
2. 冲调量/耗时采集、存储、同步齐全但不展示:契约 `docs/prd/data-model.md:225`
   (`prepared_ml?`/`duration_min?`),采集 `feature/log/.../QuickRecordPurposeFields.kt:301-317`,
   但共享摘要 seam `core/model/.../RecordSummary.kt:95` 的 `MilkPayload` 只渲染
   `amountMl` → 时间线/搜索/导出/小组件全部不可见。

## Fix

1. 三处失败判断纳入 `SyncStatus.ReauthRequired`,文案复用
   `feature/family/.../FamilyUiPolicy.kt:232` 的「登录已失效,请重新登录或申请」。
2. `MilkPayload` 摘要在有值时追加冲调量/耗时,格式与现有摘要风格一致
   (时间线/搜索/导出/小组件经同一 seam 自动生效)。

## Validation

- `./gradlew :core:model:test`(摘要新增用例)+ `lintDebug`。
