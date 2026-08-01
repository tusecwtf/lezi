# 05 — Join 会话耐久顺序（0.3.1 后复核）

**What to build:** （复核票，默认不实现）确认在 0.3.1 可信同步 cutover 之后，加入/创建家庭时**先耐久保存设备会话，再拉取并应用家庭副本**；进程在中途被杀不会留下「有家庭数据却未 joined」或「有凭证却无 family 会话」的不可恢复半态。

**Blocked by:** 0.3.1 发布（`trusted-sync-endpoint-auth` 程序闭合，尤其建家/会话票）

**Status:** planned

**Severity:** P1  
**Lane:** post-0.3.1-reverify  
**Implement before gate:** **禁止**

## Cutover 应已覆盖

- trusted-sync：根密码建家/设备会话「session 先耐久再首次 full pull」类要求。
- 目标：两阶段 join，失败可重试同步而不重跑建家。

## 0.3.0 残差（复核对照）

- 先 apply 初始实体再 saveSession；token 与 DataStore 非同一提交。

## Re-verify checklist（0.3.1 后执行）

- [ ] 对照 0.3.1 实现与测试：join/create 是否先持久会话再 apply 副本。
- [ ] 人为中断/单测是否覆盖「apply 中失败可恢复」且不会出现幽灵副本无会话。
- [ ] **若已满足：** `Status: cancelled`，Comments 注明对照票与证据，**不实现本票**。
- [ ] **若仍不满足：** 改写为本版 acceptance，`Status: ready-for-agent`，再开实现。
