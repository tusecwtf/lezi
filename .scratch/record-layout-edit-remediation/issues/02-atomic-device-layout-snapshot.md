# 02 — 原子持久化 DeviceLayoutSnapshot

**What to build:** 将精确四槽、本机已删除集合、类内项目序、类别序和必要版本合并为一个设备本地 `DeviceLayoutSnapshot`，让每次布局意图只提交一个完整、可恢复的原子快照。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] 存储边界提供一个完整布局快照读写契约；生产写路径不再通过多个公开 setter 依次拼装同一次布局变更。
- [x] 一次布局意图只执行一个 DataStore 原子事务，观察者不会看到新槽位配旧隐藏集合或其他部分更新组合。
- [x] 快照始终规范为精确 4 个槽，槽内 key 唯一，允许任意数量空槽，且不会自动补位。
- [x] 快速连续的分配、换位、清空、隐藏、恢复、项目排序和类别排序按用户发出顺序串行归约；最后成功提交的完整快照获胜，较旧写入不得反向覆盖。
- [x] “完成”和系统返回会等待或可靠排空最后一份待提交快照；不得在最后写入尚未耐久时对用户伪装为保存成功。
- [x] 写入失败保留上一份有效快照，编辑界面得到明确、可重试的错误结果；取消 coroutine 或进程中断不会留下半份快照。
- [x] 应用重启后恢复最后成功的完整快照；未知未来版本 fail-safe，不得静默清空用户槽位或隐藏集合。
- [x] 快照只存在设备本地设置中，不新增家庭同步字段、Outbox 实体或 NAS wire 数据。
- [x] 并发/故障注入测试覆盖连续意图、旧写晚到、事务失败、取消、重启、空槽和唯一性不变量。

## Validation evidence

- `DeviceLayoutSnapshot`、DataStore 单事务权威 JSON 与同事务 legacy 镜像已落地；未来版本可读但拒写。
- FIFO writer 串行完整快照，`submit` 与 `flush` barrier 共用入队锁；失败状态保留最新完整快照供重试，完成/返回仅在 flush 成功后退出。
- `:core:model:test :core:datastore:testDebugUnitTest :feature:log:testDebugUnitTest :feature:log:lintDebug :app:assembleDebug` 通过，`git diff --check` 与四 setter 连续写负向搜索通过。
- `lezi_api35` Debug APK smoke：槽 1/2 换位后完成，强停并冷启动（`LaunchState: COLD`）仍恢复 `睡眠、尿尿、母乳、配方奶`；测试后已换回原始顺序。
- `docs/prd/data-model.md`、`docs/prd/ui.md` 与布局设计文档已记录字段、不变量、版本/失败语义和设备本地边界。

## Validation

- 运行 core model、DataStore、布局 reducer 和 Log ViewModel 的定向测试。
- 运行 `:core:datastore:testDebugUnitTest`、`:feature:log:testDebugUnitTest`、`:feature:log:lintDebug` 与 `:app:assembleDebug`。
- 做一次 force-stop/relaunch smoke，确认槽、隐藏集合、类内序和类别序来自同一最后快照。
- 运行 `git diff --check`，并用负向搜索确认布局生产写入不再连续调用四个独立 setter。

## Documentation Gate

在数据/技术文档记录 `DeviceLayoutSnapshot` 的字段、不变量、版本策略、失败语义和设备本地边界；UI PRD 明确完成/返回只在最后快照可靠提交后结束编辑。
