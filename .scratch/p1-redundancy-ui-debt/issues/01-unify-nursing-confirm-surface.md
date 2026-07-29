# 01 — 统一母乳确认面（Composer + 计时完成）

**Parent:** [../spec.md](../spec.md)

**What to build:** 照护者在「快捷/记录 Composer 写母乳」与「计时结束后完成并记录」两条路径上，看到**同一套**母乳字段语义：喂养顺序全集（左、右、先左后右、先右后左）、校验规则与失败文案、确认按钮的可用/锁定 chrome。计时写入的单侧记录再进入编辑时，顺序选中态正确，不会误改写。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** L  
**Theme:** A（R1 / U2 / U7）  
**Seams:** 母乳确认表单 surface；`ComposerConfirmChrome` 级确认交互

## Acceptance criteria

- [x] 产品与领域顺序全集一致：`L` / `R` / `LR` / `RL`（或等价产品文案）在 Composer 与计时完成均可表达、可读回
- [x] 同一 payload 在两条路径间 round-trip：计时完成写入后从时间轴/列表再编辑，芯片选中与校验与首次一致
- [x] 校验失败时确认不可提交（或等价 Composer 级锁定）；成功路径可提交；两条路径错误文案语义对齐
- [x] 私有重复控件（选择条/整数格等）收敛为共享实现，禁止再维护两套互漂移的 ChoiceStrip
- [x] 既有母乳/计时相关自动化与目标模块测试通过；新增至少覆盖：四顺序可写可读、单侧再编辑选中、校验锁定
- [x] Documentation Gate：若 PRD/设置文案仍写「仅双侧顺序」或遗漏单侧，同票修正

## Implementation notes

- public `core.model.NursingConfirmInput` 只承载未确认的文本输入；`NursingPayload` /
  `RecordPayloadCodec` 继续是唯一持久化事实。共享 validator 统一 0–1440 分钟、至少一侧、
  `L` / `R` / `LR` / `RL` 与可选 1–999 ml，并保留护理计划 intent-only 的 0/0 例外。
- public `designsystem.LeziNursingConfirmFields` 同时服务 Record Composer 与计时完成面，统一
  左右分钟、四顺序芯片、可选奶量及 typed invalid-field 聚焦；计时页的私有
  `ChoiceStrip` / `IntegerField` 已删除。
- public generic confirm chrome reducer、appearance 与 reason card 成为两路径共用机械；
  Composer 保留原有灰钮重复点击清除、编辑/关闭/忙碌清除与具体失败原因无障碍语义。
- `QuickRecordDraft.fromRecord` 与 timer completion command 均回归四种顺序，单侧 payload
  不再在编辑 round-trip 中被改写。

## Validation evidence

- approved public seams 逐 slice 取得 unresolved seam、Composer 上限行为断言与重复私有
  surface contract 的有效 RED，随后最小 GREEN。
- `./gradlew :core:model:test :designsystem:testDebugUnitTest
  :feature:log:testDebugUnitTest :feature:timer:testDebugUnitTest
  :designsystem:lintDebug :feature:log:lintDebug :feature:timer:lintDebug
  :app:assembleDebug --no-daemon`：通过（618 tasks）。
- 完整回执见 `../evidence/01/validation.md`；未使用模拟器，未修改 CareLog、媒体同步或版本。

## Out of scope

- 改母乳领域计量（时长/侧别业务规则本身）以外的喂养类型
- 拆 `LogScreen` / `CareLog` 宿主（07 / 08）
