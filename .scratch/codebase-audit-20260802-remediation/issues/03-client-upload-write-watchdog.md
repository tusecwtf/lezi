# 03 · Client upload write watchdog

Status: ready-for-agent

## Findings

- `sync/src/main/kotlin/com/lezi/babylog/sync/backend/HttpSyncBackend.kt:1029-1047`:
  `requestJsonStream` 上传写循环直连 socket(fixedLengthStreamingMode),
  `HttpURLConnection` **无写超时**(`open()` 1079-1081 只设 connect/read timeout)。
  NAS 进程卡死但 TCP 尚存时 `output.write` 可阻塞至 tcp_retries2(~15 分钟),
  Dispatchers.IO 上协程取消无法中断;期间持有 `RealSyncPort.syncMutex` 与
  `RefreshingSyncBackend.sessionMutex` → 全同步与家庭操作连锁卡死。

## Fix

- 写循环分块写,每块后 `currentCoroutineContext().ensureActive()`;加停滞看门狗:
  最后一次成功 `write` 超过 N 秒无进展(建议 30s)即 `connection.disconnect()` 并抛
  `IOException`(走既有重试/失败语义)。实现限于 `HttpSyncBackend` 内部,不动调用链。
- 小 body 路径(`requestJson`/`requestBytes`)如可行同样受益,至少保证 connect/read
  timeout 已覆盖的现状不回退。

## Validation

- `./gradlew :sync:test`;补一条看门狗单测(假 source/连接或抽出可测接缝),
  不引入真实网络。
