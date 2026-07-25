# 13 — Record Time → Growth / Calendar 与最终清理

**Parent:** [../spec.md](../spec.md)

**What to build:** 完成 Record Time epic：迁移 Growth 与 Calendar caller，删除 designsystem 中剩余的领域时间 helper 和 feature 重复校验，证明 Log、Composer、Growth、Calendar 使用同一时间语义。

**Blocked by:** [09-growth-caller-migration.md](./09-growth-caller-migration.md), [12-record-time-clock-log-composer.md](./12-record-time-clock-log-composer.md)

**Status:** done

**Dependency category:** in-process

## Seam and deletion test

- 四类 caller 共享 Record Time interface；各自只保留产品场景与呈现状态。
- designsystem 不再是领域时间 seam。
- 删除 Record Time module 时 DST、clamp、future 与 interval 规则会回流到四类 caller。

## Acceptance criteria

- [x] Growth 新增/编辑测量日期与时刻通过 Record Time interface
- [x] Calendar 事件日期/时刻合并、future 与 DST 行为通过同一 interface
- [x] Log、Composer、Growth、Calendar 在同一固定时区/时刻输入下得到一致决策
- [x] designsystem 不再导出持久化时间政策 helper；剩余 helper 仅用于 UI 格式化/交互
- [x] Growth/Calendar 的 `timestampOn*` alias、直接 `System.currentTimeMillis` 业务校验与重复 DST/clamp 规则被删除
- [x] 旧 Clock/Growth/Calendar 内部时间测试被 interface 测试替换；UI 测试保留可见事实
- [x] Timer、Root 今日导航与所有记录保存行为无回归

## Validation

- [x] Targeted：Record Time、feature:growth、feature:settings、designsystem、feature:log
- [x] Fixed-zone matrix：Asia/Shanghai + 至少一个有 DST 的时区
- [x] Full Time closure：`./gradlew test assembleDebug`
- [x] Static：`git diff --check`
- [ ] UI smoke：Log、Growth、Calendar 的历史日期、未来日期与 DST 场景

## Out of scope

- Timer boot identity / persistence 与 Root 午夜刷新重写
- 新时间选择器视觉
- 数据库 timestamp schema 变更

## Comments

- Growth/Calendar 迁移到 `RecordTime.selectDate/merge/pointError/futureEventError`；旧 `timestampOn*`、feature time alias 与 designsystem 领域时间 helper 删除。
- 验证：RecordTime + Growth/Settings/Clock/Log 定向测试，Shanghai/New York fixed-zone matrix，`./gradlew test assembleDebug -q`（exit 0）；`git diff --check` 通过。设备 UI smoke 未运行。
- Documentation: N/A；Timer/Root 与记录保存合同未改。
