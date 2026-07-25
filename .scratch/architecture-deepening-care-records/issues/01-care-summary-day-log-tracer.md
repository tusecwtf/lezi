# 01 — Care Summary 当日投影 → Log tracer

**Parent:** [../spec.md](../spec.md)

**What to build:** 建立 deep Care Summary module 的第一条端到端 tracer：以固定时区和当前时刻生成单个自然日的照护事实，并让 Log caller 使用该 interface。先证明当日窗口与睡眠语义，不迁移周/月 Summary 或 Widget。

**Blocked by:** None — initial frontier

**Status:** done

**Dependency category:** in-process

## Seam and deletion test

- interface 隐藏半开窗口、睡眠裁剪、payload 读取和 `now` 计算。
- Log caller 只消费当日事实，不重建汇总规则。
- 删除新 module 时，当日窗口与分类会回流到 Log；删除旧 `aggregateDaily` 不应再影响 Log。

## Acceptance criteria

- [x] 用两个候选 interface 形状比较 depth、locality 与 caller 负担，并在 Comments 记录选择理由；不锁定本票未用到的周/月能力
- [x] 固定 `ZoneId` / `now` 的 interface 测试覆盖普通当日、跨午夜睡眠和进行中睡眠
- [x] Formula、Pumped Feed、Nursing 的 ml / 分钟事实与现有 Log 一致
- [x] Pee、Poop、Both Diaper、Temperature、软删与非法负区间行为保持
- [x] LogViewModel/LogRoute 通过新 interface 得到当日摘要，不再直接调用旧日聚合规则
- [x] Log 的当日 chips、空态、时间轴与一键记录行为无可见变化
- [x] 旧日聚合可暂留供未迁移 caller 使用，但不得成为 Log 的平行路径

## Validation

- [x] Targeted：新 Care Summary interface 测试 + feature:log 相关测试
- [x] Compile：受影响 module 与 app debug 编译
- [x] Static：`git diff --check`

## Out of scope

- 7/30 日窗口、Summary UI、Widget
- 删除全部旧聚合 implementation
- payload schema 与时间选择策略重构

## Comments

- 选择返回稳定照护事实的 `CareAggregation.day`，而不是 Log 专用 facade；窗口、睡眠裁剪和 payload 含义集中，caller 仅负责呈现。
- 验证：Care Summary/Log 定向测试与 `./gradlew test assembleDebug -q`（exit 0）；`git diff --check` 通过。
- Documentation: N/A；用户可见行为和持久化/同步/导出合同未改变。
