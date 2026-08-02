# 03 — 前台服务器可用性协调器

**Status:** complete

## Acceptance criteria

- [x] 匿名并行 health/ready/setup 探测总上限 8 秒，不携带凭证。
- [x] `FamilyServerAvailability` 公开结果态与最近成功时间，健康租约为 30 秒。
- [x] 失败按 30 秒/2 分钟/10 分钟退避；本地写事件合并且不打破退避。
- [x] 前台/网络恢复/下拉可立即探测，后台停止探测与同步。
