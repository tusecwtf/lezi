# 20 — 系统日历 Provider I/O 后台化

**What to build:** 把系统日历的查询、插入、更新和删除移到可取消的 I/O 执行环境，并将权限、Provider 与数据错误转换为不会阻塞 CarePlan 保存的明确结果。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 所有系统日历 Provider 调用在非主线程执行；UI 发起投影时保持可响应。
- [x] 页面或作用域取消会停止后续 Provider 工作与状态回调，不向已销毁 UI 发布结果。
- [x] 缺少权限、Provider 不可用、SecurityException、无效游标和写失败被转换为可分类失败，不逃逸导致进程退出。
- [x] CarePlan/家庭数据先按自身事务成功保存；日历投影失败只标记设备本地可重试状态，不回滚事实或计划。
- [x] 重试保持单向投影和幂等更新，不创建重复系统日历事件。
- [x] 测试使用可控 Provider 覆盖慢查询、取消、拒绝权限、异常、失败重试和重复调用。

## Implementation evidence

- `SystemCalendarProviderIo` 统一把查询与 CRUD 切到注入的 I/O dispatcher；查询绑定 Android `CancellationSignal`，协程取消会终止阻塞查询并阻止后续结果发布，写操作在返回后先检查作用域仍有效再继续下一次 Provider 工作。
- Provider 边界将权限拒绝/撤销、null Provider、无效游标和写失败分类为 `PERMISSION_DENIED`、`PROVIDER_UNAVAILABLE`、`INVALID_CURSOR`、`WRITE_FAILED`；`AndroidSystemCalendarPort` 将其保守映射为既有不可用/待恢复结果，不让异常越过 domain seam。
- CarePlan 创建、Record 转计划、下次喂养安排和计划更新均先完成事务并调用 `requestLocalSync()`，再执行设备本地日历/提醒投影；取消仍保留已提交计划和 durable pending hand-off，不继续发布提醒状态。
- 既有稳定 `UID_2445` + app package 查找、严格回读与重复事件收敛保持不变；Provider 结果不确定时不盲插，前台/重启重试仍更新同一事件。

## Validation evidence

- TDD red/green：`SystemCalendarProviderIoTest` 覆盖真实线程切换、慢查询取消、权限拒绝、Provider/null/异常、无效游标与写失败分类；`CareLogTest` 覆盖慢 Provider 不延迟已提交计划/家庭同步请求，以及取消后不发布提醒状态。
- `./gradlew :feature:settings:testDebugUnitTest :domain:testDebugUnitTest` — pass
- `./gradlew :feature:settings:lintDebug :domain:lintDebug :app:assembleDebug` — pass
- `./gradlew :feature:settings:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.settings.AndroidSystemCalendarPortSmokeTest` — pass（API 35 emulator，7 个真实 Calendar Provider CRUD、重复调用与幂等收敛 smoke）
- fresh-process permission denial smoke — pass（API 35 emulator；READ/WRITE_CALENDAR 均为 `granted=false`，1 test）

设备上的慢 Provider 无法由 AOSP Calendar Provider 稳定注入，故不声称真实设备慢响应已复现；该条件由可控 dispatcher/Provider 的取消测试验证。设备已覆盖真实 Provider CRUD/幂等与权限拒绝。

## Validation

运行日历投影、CarePlan 与协程调度测试，以及应用编译和静态检查；设备上验证拒绝权限和慢 Provider 情况。

## Documentation Gate

继续明确系统日历是设备本地单向投影，并记录失败不阻塞 CarePlan/家庭保存。
