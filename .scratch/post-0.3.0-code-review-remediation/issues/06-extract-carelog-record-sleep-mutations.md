# 06 — 抽离 CareLog Record 与睡眠写 seam

**Parent:** [../spec.md](../spec.md)
**Audit ID:** `CR-20260730-P1-02`
**Severity:** P1 structural
**Status:** ready-for-agent
**Blocked by:** 05
**Activation:** satisfied — 05 complete; user requested all scratch issues
**Size:** L

## What to build

把 Record add/update/delete、护理计时完成、sleep confirm/down/up、Record→CarePlan 转换及对应照片
协调抽到 `RecordMutationCoordinator`（或等价 seam）。复用现有 `PhotoAttachmentReconciler`、
payload codec、睡眠 mutex 与同步回执规则；CareLog 只保留 façade。

## Acceptance criteria

- [ ] Record 与 sleep 写事务完整迁移且只有一个实现；开放睡眠互斥/修复规则不变。
- [ ] 照片 ownership、tombstone cleanup、Outbox 捕获和 requestLocalSync 顺序不变。
- [ ] Composer 仍确认后才写事实，转换计划不创建伪事实。
- [ ] cancellation、失败回滚、重复提交和跨日睡眠回归保持绿色。
- [ ] CareLog 行数继续下降，结构测试防止写算法回流。

## Validation

运行 domain Record/sleep/photo 全量测试、feature/log Composer/Timer 回归、sync atomic bundle 测试、
相关 lint 与 `:app:assembleDebug`。

## Documentation gate

保持 Record=已发生事实、CarePlan=未来意图及 0–3 图原子可见契约；本票不改变行为文档。
