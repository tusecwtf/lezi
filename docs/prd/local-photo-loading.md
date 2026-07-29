# 本地记录照片加载契约

记录照片与计划照片的所有用户可达预览统一通过
`designsystem.rememberLocalPhoto(path, target)` 加载。Composer 公共备注区缩略图、冲突未采纳
审计缩略图和共享全屏 pager 不得直接调用 `BitmapFactory.decodeFile`，新增消费者也不得复制一条
独立解码路径。

本契约只覆盖 app-private Record/CarePlan 照片预览。宝宝头像在导入时已输出为 512×512，并使用
独立的后台头像加载/裁剪契约；PDF 导出是非交互渲染路径，也不复用 Compose 预览状态。

## 目标与内存边界

| `LocalPhotoTarget` | 最大宽高 | 最大解码像素 | 当前消费者 |
|---|---:|---:|---|
| `THUMBNAIL` | 256×256 px | 65,536 | Composer、冲突审计 72dp 缩略图 |
| `FULLSCREEN` | 2048×2048 px | 4,194,304 | `LeziPhotoPreviewDialog` |

加载器先用 `inJustDecodeBounds` 读取宽高和 EXIF 方向，再选择 power-of-two `inSampleSize`；禁止为
检查或解码把整文件读入 `ByteArray`。源 header 的任一边超过 65,535，或源像素超过
268,435,456，直接进入不可用状态而不分配 Bitmap。`inScaled=false` 禁止 density 自动放大；
实际 decode 与八方向 EXIF 变换完成后还会再次检查宽、高和像素预算，不能只相信 header 估算。

## 调度、取消与生命周期

- inspect、sampled decode 和方向变换都在最多两路并行的 `Dispatchers.IO` 视图执行，不占主线程。
- `produceState` 的 key 是完整 `LocalPhotoDecodeRequest(path, target)`；换图、快速滚动或离开
  composition 会取消旧 producer，旧结果不能覆盖新路径。
- `CancellationException` 原样向上传播；如果取消时已持有 Bitmap，先释放该 Bitmap。
- 其余 `Exception`、`OutOfMemoryError`、文件缺失、格式不支持、损坏 bounds 或 decoder `null`
  都收敛为 `LocalPhotoLoadResult.Unavailable`。缩略图显示稳定的“无法读取”，全屏显示
  “无法预览图片”，不让异常退出进程。

当前不设全局 Bitmap cache，避免无界驻留和替换泄漏。`LocalPhotoCacheKey` 固定包含 path、target、
源宽高与方向；若未来确有性能证据需要缓存，必须使用该 identity、明确容量，并在 eviction/替换时
释放 Bitmap。
