# 05 · Startup gate: single verify, off main thread

Status: ready-for-agent

## Findings

- `core/common/src/main/kotlin/com/lezi/babylog/core/common/LocalDataUpgrade.kt:163-172`:
  `retry()` 无 dispatcher 切换;`mutex` 内不重查 `Ready`。
- 调用方 `app/.../MainActivity.kt:178`(main dispatcher)与 `app/.../LeziApp.kt:50-53`(IO)
  冷启动并发 → 必跑两遍 `PRAGMA quick_check`(全库扫描)+ 快照 SHA-256 + fsync,
  其中一遍可能在主线程(ANR 风险);`Ready→Checking→Ready` 造成启动闪屏
  (`LocalDataRecoveryScreen.kt:76`)。

## Fix

1. `mutex.withLock` 内先重查 `state.value is Ready` 直接返回。
2. 环境 I/O(`environment.inspect()/verify/prepareSnapshot` 段)包
   `withContext(Dispatchers.IO)`。

## Validation

- `./gradlew :core:common:test`(或对应模块)+ 新增回归:并发两个 `ensureReady`
  只跑一次环境校验;`retry` 在 IO dispatcher 执行(可用 test dispatcher 断言)。
