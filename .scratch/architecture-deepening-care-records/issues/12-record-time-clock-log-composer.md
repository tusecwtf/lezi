# 12 — Record Time → Clock UI / Log / Composer

**Parent:** [../spec.md](../spec.md)

**What to build:** 让 Clock UI、Log 与 Record Composer 通过 Ticket 11 的 Record Time interface 处理日期/时刻决策；designsystem 只呈现选择，删除 Log 的 shallow 转发和 Composer 侧重复 future/DST 规则。

**Blocked by:** [05-care-payload-composer-writes.md](./05-care-payload-composer-writes.md), [09-growth-caller-migration.md](./09-growth-caller-migration.md), [11-record-time-clock-tracer.md](./11-record-time-clock-tracer.md)

**Status:** done

**Dependency category:** in-process

## Seam and deletion test

- Clock UI 是 Record Time caller，不拥有持久化时间政策。
- Log 与 Composer 共享同一 record time seam；12/24h 呈现仍属于 designsystem。
- 删除新 module 时 DST/future/interval 规则会回流到三个 caller。

## Acceptance criteria

- [x] ClockDialog 的日期合并、minute snapping、DST 决策来自 Record Time interface
- [x] Log 新增、编辑起点与区间 duration 保持使用同一时间语义
- [x] Record Composer 的新增/编辑/睡下/醒来/完整区间 future 与顺序校验保持
- [x] `feature/log/RecordTime.kt` 的 shallow alias 与失去 caller 的 helper 被删除
- [x] designsystem 只保留 12/24h 展示、上午/下午布局、手势与 Compose 状态
- [x] DST interface 测试位于 Record Time；Clock UI 测试只断言呈现/交互事实
- [x] 现有 preferredHand、日期卡、跨天提示和 duration preview 无可见回归

## Validation

- [x] Targeted：Record Time、designsystem Clock、feature:log Composer/RecordClock
- [x] Compile：designsystem、feature:log 与 app debug
- [x] Static：`git diff --check`
- [ ] UI smoke：历史日期、跨夜睡眠、上午/下午切换

## Out of scope

- Growth/Calendar 迁移（Ticket 13）
- Timer 持久化
- Clock 视觉重设计

## Comments

- Clock/Log/Composer 统一调用 `RecordTime` 的 merge/snap/new draft/point/interval/shift decisions；删除 `feature/log/RecordTime.kt` shallow alias，designsystem 仅保留呈现与手势。
- 验证：RecordTime、Clock、RecordClock、QuickRecordDraft/Composer 定向测试及 `./gradlew test assembleDebug -q`（exit 0）。设备 UI smoke 未运行。
- Documentation: N/A；preferredHand、日期卡与跨天提示未改。
