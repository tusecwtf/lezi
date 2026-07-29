# 01 — 统一母乳确认面（Composer + 计时完成）

**Parent:** [../spec.md](../spec.md)

**What to build:** 照护者在「快捷/记录 Composer 写母乳」与「计时结束后完成并记录」两条路径上，看到**同一套**母乳字段语义：喂养顺序全集（左、右、先左后右、先右后左）、校验规则与失败文案、确认按钮的可用/锁定 chrome。计时写入的单侧记录再进入编辑时，顺序选中态正确，不会误改写。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** L  
**Theme:** A（R1 / U2 / U7）  
**Seams:** 母乳确认表单 surface；`ComposerConfirmChrome` 级确认交互

## Acceptance criteria

- [ ] 产品与领域顺序全集一致：`L` / `R` / `LR` / `RL`（或等价产品文案）在 Composer 与计时完成均可表达、可读回
- [ ] 同一 payload 在两条路径间 round-trip：计时完成写入后从时间轴/列表再编辑，芯片选中与校验与首次一致
- [ ] 校验失败时确认不可提交（或等价 Composer 级锁定）；成功路径可提交；两条路径错误文案语义对齐
- [ ] 私有重复控件（选择条/整数格等）收敛为共享实现，禁止再维护两套互漂移的 ChoiceStrip
- [ ] 既有母乳/计时相关自动化与目标模块测试通过；新增至少覆盖：四顺序可写可读、单侧再编辑选中、校验锁定
- [ ] Documentation Gate：若 PRD/设置文案仍写「仅双侧顺序」或遗漏单侧，同票修正

## Out of scope

- 改母乳领域计量（时长/侧别业务规则本身）以外的喂养类型
- 拆 `LogScreen` / `CareLog` 宿主（07 / 08）
