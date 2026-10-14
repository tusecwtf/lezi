# PR 11 整合 CI 证据（2026-10-14）

## 源码与运行绑定

- [PR 11](https://github.com/tusecwtf/lezi/pull/11) 已发布、尚未合并；本报告核验的公开头为 [199b131624d54dce5e75a76b44c2ccb3d1d8d940](https://github.com/tusecwtf/lezi/commit/199b131624d54dce5e75a76b44c2ccb3d1d8d940)。
- CI实际checkout/GITHUB_SHA均为PR测试merge-ref [057d65172bb47aa4d4963422f0953d4f634e29ae](https://github.com/tusecwtf/lezi/commit/057d65172bb47aa4d4963422f0953d4f634e29ae)。它与公开头的tree均为 `9bde7bb2b326550e799f8bfc28ca2b0462de5749`；测试merge-ref不表示PR已经合并。
- [Android run 38536562501](https://github.com/tusecwtf/lezi/actions/runs/38536562501) 与 [Rust run 38536562564](https://github.com/tusecwtf/lezi/actions/runs/38536562564) 均为该公开头、attempt 1、completed/success。
- Android双侧测试使用本checkout构建的service，SHA-256：`b9e9d56d900ad5f85910e536057f91fa9f01158fdbb051b68c5cce85576bad21`。`CI_TEST_CONTEXT`及sampler输出记录同一源码/二进制绑定。
- 本报告与交接更新仅改文档，不把这两次run归给未来文档头。另两处instrumentation fixture类型修复尚待后续头CI；最终头及合并后master均须分别核验。

## 已执行结果

| 门禁 | 结果与范围 |
|---|---|
| Android JVM全套 | 802份JUnit XML，5,832项执行，failures=0、errors=0、skipped=0；含debug/release两变体，不能称5,832个独立测试方法 |
| JVM测试刷新 | 37个唯一JVM Test任务实际执行（36个debug/release任务及`core:model:test`），无`FROM-CACHE`/`UP-TO-DATE`；编译/资源缓存仍保留 |
| Android构建 | `./gradlew test :app:assembleDebug assembleDebugAndroidTest`成功，Gradle耗时10分27秒；app debug、KSP/Room及18个debug instrumentation APK目标编译/打包通过，无设备运行 |
| 搜索SQL | Python SQLite合同7项通过；不替代Android Room instrumentation |
| Rust | 368 library + 208 API + 1 golden corpus + 5 TLS = 582通过，0失败；6项timing-only测试按设计ignored |
| Rust其余门禁 | warnings-as-errors Clippy、build-context helper、NAS打包输入/发布回退身份/原子更新对、TLS只读及身份保护fixture均成功；这些是隔离smoke，没有访问或部署NAS |

Rust workflow没有`cargo fmt`步骤；格式及6项计时测试的另行执行凭据见[历史整合报告](../integrated-2026-10-13/integrated-server-report.md)，不改写为本次CI结果。

### 关键Android子集

以下均包含在5,832项内，不额外累加；每变体failures/errors/skipped均为0。

| 测试套件 | Debug / Release |
|---|---:|
| `OwnedMediaDownloadStoreTest` | 22 / 22 |
| `ReplicaSyncEngineOwnedMediaStreamingTest` | 25 / 25 |
| `ReplicaSyncEngineDispatcherTest` | 2 / 2 |
| `SyncMediaFileStoreDispatcherTest` | 1 / 1 |
| `RealServerQuietRoundSmokeTest` | 2 / 2 |
| `RealServerMediaReceiptFaultSeamTest` | 5 / 5 |
| 四组`CareLogRealServerSeam*` | 4 / 4 |
| `RealServerSyncPerformanceSampleTest` | 1 / 1，显式opt-in，未跳过 |

25项引擎流式测试包括旧文件delete失败后恢复、新lease release失败、precommit取消后保留marker、共享旧路径去重四项新增回归，debug/release均执行，共8次通过。7组isolated套件每变体12项、共24次执行（含sampler），均已计入总数。sampler每变体5轮预热+30轮测量，各有360条阶段原始记录且均`succeeded=1`，源码/二进制绑定一致。它使用合成无照片、内存DAO、真实引擎及loopback TLS服务，不能证明真实Room、照片解码/压缩、手机或NAS性能。

## 修复与warning边界

已由上述绿灯覆盖：

- 阻塞文件工作在文件store边界切换IO；引擎协调和reference-aware回调保留调用方调度上下文，测试scheduler不再因无关真实线程消耗虚拟deadline。非可取消释放、登记先于可取消返回、锁顺序和Room写lease外I/O合同保留。
- fault sentinel fixture保持跨协程身份；没有放宽异常身份或清理断言。
- 历史unpinned空媒体仍按原save-failed journal排队重试；新原子页仍拒绝发布，pinned内容完整性及传输/鉴权错误不降级。
- 计时器instrumentation fixture改用现有`tickState` API；原断言、回调及状态切换保留。
- CI仅强制JVM Test任务新执行，保留编译/资源缓存，并记录source/workflow/run/attempt及service哈希。

32处安全Kotlin warning修复（18处临时目录API、13处coroutine opt-in诊断、1处nullable File断言）使原对应编译范围的86条降至22条。该次完整instrumentation编译另有2条reified-intersection warning，因此该run实际共24条Kotlin warning，不能写成零warning或总计22。

- 保留的22条来自11处既有AndroidX `MasterKey`/`EncryptedSharedPreferences`弃用位置，各在debug/release出现一次。没有为消除提示改用明文存储、迁移现有加密数据、降级库或全局压制。
- 另2处位于`LocalDataContractMigrationDeviceTest`的SQL绑定fixture，已补显式`arrayOf<Any>`，保持绑定值及测试逻辑；这使安全修复位置合计34处，但两处新改动尚未由上述run覆盖。
- Mockito/Byte Buddy动态agent及OpenJDK CDS提示仍存在。D8有5条invalid-local-information INFO，构建成功；它们不计入上述Kotlin warning，也不能据此推断工具链或运行行为已经修复。未通过隐藏日志或升级整套依赖来冒充清零。

## 归档与剩余验收

Android诊断artifact [11881410999](https://github.com/tusecwtf/lezi/actions/runs/38536562501/artifacts/11881410999)名为`android-unit-test-diagnostics`，包含Gradle日志和XML/HTML报告，workflow保留3天，记录的到期时间为2026-10-17 08:50:39 UTC。所核验ZIP的SHA-256为 `1ab17d8adfb05a1e071df55190c0fe2348bc3d11d1d8e047c8941249f520dc87`。诊断artifact不含完整APK交付清单；打包成功不等于已签名release可部署。

仍待完成：后续最终头与合并后master CI、APK/runner/package/签名哈希清单、最终树的便携补丁重生及干净基线校验、私有源回流、用户手动设备/Room/PSS/fsync验收和NAS验收。未运行ADB，未做设备instrumentation，未部署。完整手动命令与15组状态见[交接](../../../handoff/2026-10-13-integration-acceptance.md)及[核对表](../../../handoff/2026-10-13-integration-checklist.md)。
