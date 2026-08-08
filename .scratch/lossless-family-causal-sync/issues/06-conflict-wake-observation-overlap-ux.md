# 06 — 冲突解决、WakeObservation、重叠睡眠 UI 与领域写路径

**What to build:** 在 `CareLog` 统一记录入口和现有时间轴/Composer 上交付因果冲突与新睡眠模型。
醒来写独立 WakeObservation；多个观察、有效观察选择和重叠 SleepStart 都诚实显示。冲突 resolver
只呈现真实冲突字段，作者/Owner 以 branch CAS 解决，不阻断日常本地保存。

**Blocked by:** 03 — resolution API；04 — Room 27 状态；05 — 稳定投影/branch 消费。

**Status:** ready-for-agent

- [ ] `sleepUp`/Composer 闭合入口不再改写 Sleep Record `endTimestamp`，而是事务创建关联当前目标 Sleep 的 WakeObservation + 媒体并触发 LocalWrite
- [ ] 新 Sleep wire/root 只表示 SleepStart；读取旧/新本机投影的公共领域 seam 对调用方返回可展示区间，不让 UI 手工拼版本图
- [ ] 最新未结束 SleepStart 仍是醒来快捷入口；存在更旧 open 时不自动闭合，旧条显示重叠待确认并可进入处理面
- [ ] 多个非撤回合法 WakeObservation 全部显示 observer/时间/备注/照片；未选有效项时最早合法时间有“暂定”标识
- [ ] pre-start wake、负区间和越界时间在领域写入与服务端都拒绝；时钟偏差不通过改写 SleepStart 掩盖
- [ ] observer 可修正/撤回自己的 WakeObservation；Sleep 作者或 Owner 可选择有效观察；普通成员不能改他人观察或选择他人 Sleep 的有效项
- [ ] 有效观察 resolution 更新投影/统计但不删除其它观察、备注或照片；撤回有效项时重新进入明确未确认状态
- [ ] 旧 B1 纠错窗被 WakeObservation 自有编辑权取代，清除 wire closer 缺失造成的临时权限推断；Owner/原 Sleep 作者权限保持
- [ ] 时间轴记录卡显示未解决因果冲突摘要；打开后按需拉 detail，字段级并排展示 stable/branches 和来源作者
- [ ] resolver 不展示双方相同或已自动合并字段；媒体冲突展示两边 manifest/预览且选择不会先删未选字节
- [ ] resolution 提交带 expected stable/branch set；CAS race 保留用户选择草稿并刷新差异，不覆盖新分支
- [ ] 本地保存、离线 wake 和冲突分支均不等待网络、不弹阻断同步失败；pending/conflict 状态与“已同步”文案准确区分
- [ ] 迁移后的历史 closed Sleep 在时间轴、详情、编辑权限、照片和 Summary 上与升级前等价
- [ ] CareLog 行为测试覆盖 create/edit/withdraw/select、多观察、重叠 start、非法 wake、ACL、幂等和照片；删除/反转旧自动闭合断言
- [ ] Compose/UI tests 覆盖 badge、暂定/全部观察、重叠待确认、resolver CAS 刷新、离线状态与无障碍语义；提供真机/模拟器截图证据
- [ ] 相关 domain/log/summary/designsystem tests 与 `:app:assembleDebug` 通过

