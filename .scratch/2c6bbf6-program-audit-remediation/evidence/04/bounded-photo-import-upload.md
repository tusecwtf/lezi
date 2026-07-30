# Ticket 04 — 有界照片导入与流式上传证据

## 已锁定资源策略

`core:model.RecordPhotoResourcePolicy` 是唯一数值 authority：

| 约束 | 值 |
|---|---:|
| 格式 | JPEG / PNG / WebP |
| 每记录批次 | 0–3 张（复用 `MAX_RECORD_PHOTOS`） |
| 源文件 | 16 MiB |
| 源尺寸 | 单边 65,535 px；268,435,456 pixels |
| 上传输出 | JPEG quality 85；单边 1,600 px；2,560,000 pixels；8 MiB |
| 流 buffer | 64 KiB |

声明 MIME 的 null、blank、`image/*` 只作为未知值继续 sniff；`image/jpg` 规范为
`image/jpeg`。其余具体 MIME 先拒绝，具体声明与 JPEG/PNG/WebP 文件签名 sniff 不一致也拒绝。
Ticket 03 的预览源 edge/pixel cap 已改为引用同一 authority，没有复制上限。

## 导入原子性与错误面

`BoundedRecordPhotoImporter` 只消费可注入的 `openStream`、文件 sniff 与 app-private 临时目录。
每项通过 64 KiB buffer 复制，累计超过 16 MiB 会在写入超限块前失败。复制完成后才检查实际
签名、decoder bounds、声明/实际 MIME，再把同目录唯一 temp 原子 rename 成目标扩展名。

0 张直接成功；超过 3 张在打开任何流前失败。任一项异常或 `CancellationException` 都删除本批
全部 temp 和已经完成的目标文件；Composer 只有在完整成功列表返回后才更新 draft，所以原草稿
不产生部分图片状态。错误消息区分 16 MiB、JPEG/PNG/WebP、伪装格式、损坏图片与尺寸超限，
继续由现有 Composer `productUiError` 呈现并允许重试。

## 上传内存与提交时序

`AndroidSyncMediaFileStore.prepareUpload` 逐张执行：先校验源格式/16 MiB/edge/pixel，power-of-two
采样后处理 EXIF，并缩放为 1,600 px/2,560,000 pixels 内的 JPEG。压缩直接写 cache temp；
`CancellableBoundedOutputStream` 对每次 write 检查 coroutine cancellation 和 8 MiB 累计值。

可检查的 JVM/Bitmap payload 上界如下：

- 任一时刻只规范化一张；三图批次不会同时解码三张原图。
- sampled decode 最大 3,200×3,200 且 10,240,000 pixels；ARGB_8888 payload 最大约
  39.1 MiB。
- 极端 EXIF 变换可同时持有另一份同上限 bitmap，1,600×1,600 scaled bitmap 最大约
  9.8 MiB；三份像素 payload 合计上界约 87.9 MiB，加一个 64 KiB 输出 buffer 与平台 codec
  内部开销。该值不随源压缩字节或批次数增长。
- 完成后只留下最大 8 MiB 的 file-backed `PreparedMedia` handle；publisher 最多持有三份磁盘
  temp，不持有完整媒体 `ByteArray`。

HTTP PUT 使用精确 `Content-Length` 和 64 KiB buffer 逐块读取 `openStream()`，每块检查取消；
长度少于或多于声明都失败。publisher 在所有成功/失败/取消路径的 `finally` 关闭并删除全部
prepared temp。只有全部缺失媒体 PUT 且 bundle commit 成功后才写 media remote receipt；失败
保留 outbox，重试创建新 temp，不把已上传媒体误当成 root commit receipt。

生产 import/upload 路径静态扫描无 `readBytes()`、原图 `ByteArray`、`ByteArrayOutputStream` 或
无界 `copyTo`。`HttpSyncBackend` 保留的 bounded response `ByteArrayOutputStream` 属于独立的
下载/JSON 响应读取 seam，不是媒体上传 body。

## TDD 与自动化收据

- `RecordPhotoResourcePolicyTest`：首个 RED 为 authority unresolved；随后覆盖所有数值、MIME
  alias/unknown/unsupported，GREEN。
- `BoundedRecordPhotoImporterTest`：0/3、第二项 sniff 失败全批 rollback、16 MiB 边界/超 1 byte、
  edge/pixel 边界、MIME spoof、取消清理与 retry，GREEN（feature:log 单测 105 tasks）。
- `PreparedMediaStreamingTest`：旧 `bytes: ByteArray` 构造 RED；迁移后重复 open、精确长度与幂等
  close/delete，GREEN。
- `AtomicMediaBundlePublisherTest`：0 图、三图第二 PUT 失败、无 partial receipt/commit、finally
  cleanup、取消与 retry，全组 GREEN（46 tasks）。
- `HttpSyncBackendTest.stagePutAndCommitBundleFollowAtomicEndpoints`：真实 loopback HTTP 验证
  `Content-Length: 2` 且无 `Transfer-Encoding`，GREEN。
- `RealSyncPortTest.uploadedPhotoCannotCreatePartialReceiptBeforeFailedRootCommit`：历史 partial-receipt
  断言先 RED；修正为 commit 失败 media/root receipt 均 null、retry 成功后才写，GREEN（46 tasks）。

## API35 设备 smoke

设备 `lezi_api35(AVD)`，API 35：生成三张可由真实 `BitmapFactory` 解码的 JPEG，并以合法 JPEG
尾部 padding 扩展到每张 `16 MiB - 1 KiB`。三张一次导入后，逐张走真实
`AndroidSyncMediaFileStore` normalize、file source 64 KiB stream、`close()` cleanup；断言三张
导入文件保留精确近上限长度、输出在 1–8 MiB/1,600 px/2,560,000 pixels 内、streamed bytes
等于 `contentLength`，close 后 normalized temp 不存在。

```text
./gradlew :feature:log:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.lezi.babylog.feature.log.BoundedPhotoPipelineDeviceSmokeTest
Starting 1 tests on lezi_api35(AVD) - 15
Finished 1 tests on lezi_api35(AVD) - 15
BUILD SUCCESSFUL in 11s
201 actionable tasks: 9 executed, 192 up-to-date
```

## 完整门禁状态

Program04 自有 targeted/device gates 及稳定工作树上的完整组合门禁均已通过：

```text
./gradlew :core:model:test :designsystem:testDebugUnitTest :feature:log:testDebugUnitTest \
  :sync:testDebugUnitTest :designsystem:lintDebug :feature:log:lintDebug :sync:lintDebug \
  :app:assembleDebug :app:lintDebug
BUILD SUCCESSFUL in 24s
791 actionable tasks: 150 executed, 641 up-to-date
```
