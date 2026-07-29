# 07 — 接回会话后立即执行全量 pull

**What to build:** owner 成功接回家庭会话并持久化新凭证后，在当前前台流程立即触发一次 push + 从零游标 full pull，让历史家庭数据无需额外手势即可恢复。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] 接回 API 成功后先持久化家庭身份、凭证和必要会话状态，再开始同步。
- [ ] 首次同步计划明确包含待处理本地写入与 `cursor = 0` 的完整 pull，而不是仅请求 local-write push。
- [ ] 用户停留在接回完成流程即可看到旧宝宝、记录、计划、媒体、自定义项目与成员数据恢复，不需下拉刷新或切换前后台。
- [ ] full pull 不导入或覆盖四槽、显隐、排序等设备本地布局；fresh install 继续使用本机默认/用户后续编排。
- [ ] full pull 失败不会撤销已合法接回的身份；界面显示真实失败/重试状态并可再次触发恢复。
- [ ] 接回期间重复点击、配置变化或进程重启不会创建并行会话或跳过尚未完成的 full pull。
- [ ] 测试证明触发器映射、零游标、push/pull 顺序、失败重试和持久化恢复，不以提示文案代替同步证据。

## Validation

运行家庭会话、同步调度与 Replica 集成测试；使用装有历史数据的 NAS 做 fresh-install owner 接回 smoke。

## Documentation Gate

更新接回流程文档，明确成功、同步中、可重试失败和数据恢复完成四个状态。
