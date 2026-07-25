# 11 — Record Time 决策合同 → Clock tracer

**Parent:** [../spec.md](../spec.md)

**What to build:** 建立 deep Record Time module 的首条 in-process tracer：用固定时钟/时区表达历史日期、未来日期、分钟 snapping、DST gap/overlap 与区间起点平移决策，并让 Clock UI 的显式日期/时刻确认使用该 interface。

**Blocked by:** None — initial frontier

**Status:** done

**Dependency category:** in-process；固定时钟是 internal seam

## Seam and deletion test

- interface 表达 record time decision，不暴露 Compose 状态或测试替身。
- Clock UI 是第一条 caller；Log/Composer/Growth/Calendar 可暂留旧路径。
- 删除新 module 时，日期/时区/DST 策略会回流到 Clock caller。

## Acceptance criteria

- [x] 比较至少两个 interface 形状，避免 caller 通过布尔组合重建不同时间策略，并记录选择理由
- [x] 固定 `Clock` / `ZoneId` 测试覆盖普通日期、历史日期、未来日期与午夜
- [x] DST gap 对显式编辑可解释拒绝；新草稿仍按现有策略得到可用时刻
- [x] DST overlap 保留仍有效的既有 offset；无偏好时结果稳定
- [x] 1/5 分钟 snapping、00:00、12:00、23:59 与合法区间 duration 保持
- [x] Clock UI 的显式日期/时刻确认通过新 interface，DST gap 拒绝和 overlap offset 行为保持
- [x] designsystem 的 12/24h 展示、上午/下午布局与手势状态保持；其它 caller 的旧时间路径有明确临时标记

## Validation

- [x] Targeted：Record Time interface + designsystem Clock
- [x] Compile：designsystem 与 app debug
- [x] Static：`git diff --check`

## Out of scope

- Log、Composer、Growth、Calendar 全量迁移
- Timer boot identity / persistence
- 新时间选择器视觉

## Comments

- 选择 `RecordTimeDecision`/`RecordDateDecision` decision objects，而不是 nullable timestamp + boolean 组合；DST gap 可解释拒绝，overlap 保留 offset，fixed `Clock` 仅为 internal test seam。
- 验证：RecordTime/Clock 测试覆盖 Shanghai、New York DST、1/5 分钟及 00:00/12:00/23:59；`./gradlew test assembleDebug -q`（exit 0），`git diff --check` 通过。
- Documentation: N/A；Clock 呈现和交互合同未改。
