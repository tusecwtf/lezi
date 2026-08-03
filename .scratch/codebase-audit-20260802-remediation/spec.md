# 2026-08-02 全库诊断修复

Status: complete — implementation `6b278242`; ticket 07 regression `d160fe68`

## Goal

修复 2026-08-02 四路并行诊断(客户端同步层 / Rust 服务端 / app-domain-core 协程与状态 /
UI 与设计·后端对照)确认的全部问题:前后端交互卡死链条、正确性 bug、UI 不一致。

## Release boundary

- 不改 lezi-sync wire 协议、DB schema、`user_version`、`min_supported_version_code`。
- 服务端改动仅限内部执行方式(spawn_blocking、超时、缓存),端点与契约不变。
- 不动既有 TLS 身份与部署脚本逻辑;NAS CD 不在本 tracker 内,另行确认。
- 与 `family-sync-hang-and-account-fidelity` 重叠处(09 浅状态、10 reauth 面):
  本 tracker 只做最小对照修复(数据页提示、spinner 触发源),完整产品面仍归该 tracker。
- 0.3.4 已交付能力不得回退。

## Must

- [x] 服务端:任何阻塞 SQLite/文件 I/O 不占 tokio worker;bundle 媒体上传不再持 family 锁
  跨 body 流;请求有整体超时;APK 与 app-update.json 不再每请求重读重算。
- [x] 客户端:上传写路径有停滞看门狗,不再可能阻塞到 tcp_retries2;APK 下载不占
  `sessionMutex`;取消不被吞成 Error。
- [x] 正确性:冷启动门只校验一次且不在主线程;时间线在冲突解决后刷新;自定义项目
  更新/移动事务化;计时器恢复不在跨重启时误用 elapsedRealtime。
- [x] UI:数据页在 ReauthRequired 下有提示;冲调量/耗时在摘要可见;同页日期/体重/时长
  格式统一;typography ramp 补齐;无真实 NAS 地址占位符;死代码删除。

## Out of scope

- 全局 `syncMutex` 拆分(沿用既有 tracker 决定)。
- NAS 部署/CD、证书轮换、真机双端矩阵。
- 全面视觉 redesign。

## Validation (program-level)

- [x] `cd tools/lezi-sync && cargo fmt --all -- --check && cargo test --locked && cargo clippy --all-targets --all-features -- -D warnings`
- [x] `./gradlew test` 全量;05/08 及 07 已有事务/回滚回归均通过。
- [x] `./gradlew connectedDebugAndroidTest`;06 的 Room invalidation device 回归通过。
- [x] 07 在 `d160fe68` 补齐并通过“并发 update 同名仅一方成功”的回归测试。
- [x] `./gradlew :app:assembleDebug lintDebug`;UI 改动页核对。
