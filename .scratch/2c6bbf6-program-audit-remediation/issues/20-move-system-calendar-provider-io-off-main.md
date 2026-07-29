# 20 — 系统日历 Provider I/O 后台化

**What to build:** 把系统日历的查询、插入、更新和删除移到可取消的 I/O 执行环境，并将权限、Provider 与数据错误转换为不会阻塞 CarePlan 保存的明确结果。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] 所有系统日历 Provider 调用在非主线程执行；UI 发起投影时保持可响应。
- [ ] 页面或作用域取消会停止后续 Provider 工作与状态回调，不向已销毁 UI 发布结果。
- [ ] 缺少权限、Provider 不可用、SecurityException、无效游标和写失败被转换为可分类失败，不逃逸导致进程退出。
- [ ] CarePlan/家庭数据先按自身事务成功保存；日历投影失败只标记设备本地可重试状态，不回滚事实或计划。
- [ ] 重试保持单向投影和幂等更新，不创建重复系统日历事件。
- [ ] 测试使用可控 Provider 覆盖慢查询、取消、拒绝权限、异常、失败重试和重复调用。

## Validation

运行日历投影、CarePlan 与协程调度测试，以及应用编译和静态检查；设备上验证拒绝权限和慢 Provider 情况。

## Documentation Gate

继续明确系统日历是设备本地单向投影，并记录失败不阻塞 CarePlan/家庭保存。
