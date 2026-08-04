# 本地记录照片加载契约

记录照片与计划照片的所有用户可达预览统一通过
`designsystem.rememberLocalPhoto(path, target)` 加载。Composer 公共备注区缩略图、冲突未采纳
审计缩略图和共享全屏 pager 不得直接调用 `BitmapFactory.decodeFile`，新增消费者也不得复制一条
独立解码路径。

本契约只覆盖 app-private Record/CarePlan 照片预览。宝宝头像在导入时已输出为 512×512，并使用
独立的后台头像加载/裁剪契约；PDF 导出是非交互渲染路径，也不复用 Compose 预览状态。

## 目标与内存边界

`core:model.RecordPhotoResourcePolicy` 是记录照片导入、预览和同步上传资源数值的单一配置源；
消费者和文档不得再声明同义私有常量。记录照片只接受 JPEG、PNG、WebP，单张源文件最大
16 MiB，源任一边最大 65,535 px、总像素最大 268,435,456；每批沿用领域常量
`MAX_RECORD_PHOTOS = 3`，因此合法批次为 0–3 张。

| `LocalPhotoTarget` | 最大宽高 | 最大解码像素 | 当前消费者 |
|---|---:|---:|---|
| `THUMBNAIL` | 256×256 px | 65,536 | Composer、冲突审计 72dp 缩略图 |
| `FULLSCREEN` | 2048×2048 px | 4,194,304 | `LeziPhotoPreviewDialog` |

加载器先用 `inJustDecodeBounds` 读取宽高和 EXIF 方向，再选择 power-of-two `inSampleSize`；禁止为
检查或解码把整文件读入 `ByteArray`。源 header 的任一边超过 65,535，或源像素超过
268,435,456，直接进入不可用状态而不分配 Bitmap。`inScaled=false` 禁止 density 自动放大；
实际 decode 与八方向 EXIF 变换完成后还会再次检查宽、高和像素预算，不能只相信 header 估算。

## 导入契约

- `ContentResolver` 声明的具体 MIME 在打开流前先校验；空值、空白和 `image/*` 只表示未知，
  必须继续检查实际文件。`image/jpg` 统一为 `image/jpeg`。
- 每个输入以 64 KiB buffer 复制到目标目录内的唯一临时文件；复制累计超过 16 MiB 立即失败，
  不调用无界 `readBytes`/`copyTo`。
- 复制后从文件签名识别实际 JPEG/PNG/WebP，并用 decoder bounds 校验宽高和像素；具体声明
  MIME 与实际格式不同视为伪装文件并拒绝。
- 同一批次只有全部检查成功才返回路径；任一项失败或取消会删除本批已产生的临时文件和成品，
  Composer 只有拿到完整成功结果后才更新草稿，因此旧草稿保持不变并可重试。
- 超限、不支持、伪装或损坏输入统一转成可理解的导入错误，不让异常终止 Composer 进程。

## 调度、取消与生命周期

- inspect、sampled decode 和方向变换都在最多两路并行的 `Dispatchers.IO` 视图执行，不占主线程。
- `produceState` 的 key 是完整 `LocalPhotoDecodeRequest(path, target)`；换图、快速滚动或离开
  composition 会取消旧 producer，旧结果不能覆盖新路径。
- `CancellationException` 原样向上传播；如果取消时已持有 Bitmap，先释放该 Bitmap。
- 其余 `Exception`、`OutOfMemoryError`、文件缺失、格式不支持、损坏 bounds 或 decoder `null`
  都收敛为 `LocalPhotoLoadResult.Unavailable`。缩略图显示稳定的“无法读取”，全屏显示
  “无法预览图片”，不让异常退出进程。

## 有界内存缓存

预览结果通过共享加载 API 背后的进程内 LRU 复用，**不**引入磁盘 cache 或第三方图片栈。
缓存键固定包含 path、target、源宽高与方向；源 identity 任一字段变化都视为未命中，
避免 EXIF/尺寸变化后继续展示陈旧 Bitmap。策略常量与 cache 类型仅在 designsystem 内部
（`LocalPhotoCachePolicy` / `LocalPhotoMemoryCache`），产品面只暴露
`rememberLocalPhoto` / `BoundedLocalPhotoLoader`。

| 维度 | 策略 |
|---|---|
| 缩略图条目 | 最多 24（`MAX_THUMBNAIL_ENTRIES`） |
| 全屏条目 | 最多 2（`MAX_FULLSCREEN_ENTRIES`） |
| 解码字节预算 | 共享最多 24 MiB（`MAX_DECODED_BYTES`，按 ARGB_8888 4 B/px 计） |
| 淘汰 | 仅淘汰 **unpin** 后的 LRU 条目，并在淘汰时 `recycle`/release Bitmap；仅某一类条目超 cap 时优先淘汰该类 peer |
| 钉住 | Compose/`rememberLocalPhoto` 在 Ready 期间持有 pin；离开 composition 时 unpin |
| 取消 | 进行中的 decode 取消不写入 cache；已 pin 但未把 Ready 交给调用方时必须 unpin |

双约束同时生效：条目上限与字节预算任一触顶即淘汰未钉住条目。**Soft cap under pin：**
条目/字节上限只作用于可淘汰（unpinned）条目；HorizontalPager 等同时组成的多个
`FULLSCREEN` `rememberLocalPhoto` 在仍处于 composition 时可短暂超过「最多 2 张全屏 /
24 MiB」稳态目标——产品「at most」指 unpin 后的稳态，不是 pinned 峰值。需要硬峰值时
应限制 offscreen page 组成，而不是在 pin 期间强制 recycle 仍在展示的 Bitmap。

Cache 只挂在 `BoundedLocalPhotoLoader` / `rememberLocalPhoto` 共享缝上；功能模块不得再
维护平行预览缓存。
