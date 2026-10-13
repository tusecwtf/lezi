# Android 媒体摘要：有界内存与重复工作对比

## 变更与边界

- `MediaContentDigest.hex` 用固定十六进制字符表编码两个半字节，移除每字节一次
  `String.format`；保留 64 位小写 SHA-256、前导零和所有 unsigned byte 的输出。
- `CausalRoomUpgradeStep` 对保留的 sleep 媒体直接流式散列。`RetainedMediaDigest`
  从**同一个已打开流**探测非空，再把首字节放回参与散列；空内容/中途读取失败仍阻断升级。
  调用方仍用 `use` 关闭文件，不额外 `stat` 来判断空内容，不缓存路径摘要。
- `ReplicaSyncEngine` 页内下载采用本次内容校验返回的摘要；无 causal manifest 时由
  两次整数组 SHA-256 减为一次。历史补下载本来就采用此顺序。没有新增跨请求缓存，
  没有改变 wire、cursor、媒体所有权、保存错误或 APK 签名验证。

完整媒体 HTTP 下载到文件的流式管线**不包含在此次变更中**；现有 `getMedia` 返回
`ByteArray` 的边界保留，避免把所有权/锁/cursor 重构混入这次有界优化。

## 可复现运行

需要 JDK 21、Kotlin 2.1.0 CLI（`kotlinc`），无需 Android SDK：

```sh
scripts/benchmark-media-digest.sh 9e47fd6c9111eb5352cef585f4672a71fb578af4
```

参数省略时使用同一公开基线。浅 clone 若缺少基线，先 fetch 对应 commit；脚本会明确
报错，不会静默改用其它基线。也支持 `KOTLIN_COMPILER_CLASSPATH`，指向 Maven Central
的 Kotlin 2.1.0 `kotlin-compiler-embeddable` 与运行依赖、stdlib 的完整 classpath。
这一路直接调用 `K2JVMCompiler`，不安装/接受 Android SDK 许可。

脚本直接编译当前两个生产源文件，以及 `git show <baseline>:<path>` 取得的基线：
旧 `MediaContentDigest` 仅改 package 避免重名；旧升级散列在路径解析后的方法体逐字提取，
两侧都接收同一个合成文件。没有手写替身算法或发布基准开关到产品热路径。

- JVM：`-Xms256m -Xmx256m -XX:+UseSerialGC`；Kotlin JVM target 17
- 32-byte hex：固定 `byte[i] = (i * 31).toByte()`，每侧 warmup 4,000 次；30 轮，
  每轮每侧 10,000 次
- 保留文件：16 MiB，重复 64 KiB 合成块；每侧 warmup 8 次；30 轮，每轮每侧 8 次
- SHA-256：`3189c13d2813c19ae32f21999b9da085965e3c1e60ca835ee4631cd80c5adf56`
- 每轮交替先后顺序；先验证两侧输出完全一致；结果写入 volatile sink
- 计时用 `System.nanoTime`；分配用 JVM `ThreadMXBean` 的本线程累计分配字节差
- 保留全部 raw 轮次，中位数与 p25/p75 使用线性插值，另记 min/max

原始结果：[2026-10-13 30 轮输出](media-digest-benchmark-2026-10-13.txt)，含 JVM 版本、
输入哈希和当前生产源文件 SHA-256，可对照当前 checkout 复核。

| 操作 | 基线中位耗时 | 当前中位耗时 | 基线分配/次 | 当前分配/次 |
|---|---:|---:|---:|---:|
| 32-byte hex | 7,533.6 ns | 167.5 ns | 17,104 B | 208 B |
| 16 MiB 保留媒体摘要 | 14.780 ms | 12.956 ms | 16,798,392 B | 9,440 B |

文件摘要的 p25–p75：基线 14.272–15.850 ms，当前 12.553–13.644 ms。

这只是桌面 JVM 微基准。分配指标不是 RSS 或 peak heap；文件已 warmup，不是冷磁盘或
手机实机耗时；不能据此声称整 App 或整轮同步有相同倍数的提速。稳定的结构收益是：
升级散列不再分配与文件大小成正比的完整字节数组，编码不再构造每字节 formatter。

## 行为验证

本次云端直接编译 `core/common/src/main` 的全部生产 Kotlin 源文件、对应全部 JVM 测试，
以及 `RetainedMediaDigest` 生产源码和测试，使用 Kotlin 2.1.0/JDK 21/JUnit 4.13.2：
**45 项通过**。其中新增 8 项覆盖：全部 256 byte 编码、前导零/空 hex、短读/零读、
已知媒体摘要、单字节 0/128/255、空媒体 fail-closed 文案、有界 bulk read 与尾部、
中途 IO 错误原样传播、输入流 ownership。

另新增 `ReplicaSyncEnginePersistMediaSha256Test.laterDownloadForSameUuidUsesItsOwnBytesDigest`，
通过真实引擎 seam 检查同 UUID 的后续不同字节使用新摘要，保留原有因果清单不匹配不推进
cursor 等回归。采集本地证据时没有 Android SDK，**没有把纯 Kotlin 45 项称为 Gradle 全量
或 Android 设备通过**。集成后由正常 Android CI 跑完整 `./gradlew test`，包括该 sync 回归、
原有 checksum/游标/隔离服务测试；按仓库 C 类门还需当前树隔离服务的 sync/domain seam。

重点回归命令（不能替代完整 CI）：

```sh
./gradlew :core:common:testDebugUnitTest :app:testDebugUnitTest
./gradlew :sync:testDebugUnitTest \
  --tests com.lezi.babylog.sync.engine.ReplicaSyncEnginePersistMediaSha256Test \
  --tests com.lezi.babylog.sync.engine.ReplicaSyncEngineSkipGetByContentDigestTest
```
