# 07 — 接回会话后立即执行全量 pull

**What to build:** owner 成功接回家庭会话并持久化新凭证后，在当前前台流程立即触发一次 push + 从零游标 full pull，让历史家庭数据无需额外手势即可恢复。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 接回 API 成功后先持久化家庭身份、凭证和必要会话状态，再开始同步。
- [x] 首次同步计划明确包含待处理本地写入与 `cursor = 0` 的完整 pull，而不是仅请求 local-write push。
- [x] 用户停留在接回完成流程即可看到旧宝宝、记录、计划、媒体、自定义项目与成员数据恢复，不需下拉刷新或切换前后台。
- [x] full pull 不导入或覆盖四槽、显隐、排序等设备本地布局；fresh install 继续使用本机默认/用户后续编排。
- [x] full pull 失败不会撤销已合法接回的身份；界面显示真实失败/重试状态并可再次触发恢复。
- [x] 接回期间重复点击、配置变化或进程重启不会创建并行会话或跳过尚未完成的 full pull。
- [x] 测试证明触发器映射、零游标、push/pull 顺序、失败重试和持久化恢复，不以提示文案代替同步证据。

## Validation

运行家庭会话、同步调度与 Replica 集成测试；使用装有历史数据的 NAS 做 fresh-install owner 接回 smoke。

## Documentation Gate

更新接回流程文档，明确成功、同步中、可重试失败和数据恢复完成四个状态。

## Validation evidence

- 自动化覆盖会话先持久化、待处理 push → cursor-zero full pull、失败保留身份、重试、
  配置竞争与进程恢复。
- API35 fresh-install 在同一前台提交内恢复历史 Baby、Record、CarePlan、自定义定义、成员、
  avatar、三张 log photos 与 fulfillment candidate；无额外手势进入主界面。
- 强停重启后 `历史宝宝` 仍为当前上下文；Room 只读 receipt 为 family 1 / baby 1 /
  record 3 / plan 2 / custom 2 / media 4 / candidate 1 / outbox 0。
- 完整命令、APK 哈希与范围限制见
  [`../evidence/07/fresh-install-current-head.md`](../evidence/07/fresh-install-current-head.md)。
