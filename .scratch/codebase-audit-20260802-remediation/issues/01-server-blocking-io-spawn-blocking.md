# 01 · Server blocking I/O off tokio workers

Status: ready-for-agent

## Findings

- `src/handlers/sync.rs:53,74,81`、`handlers/media.rs:63`、`handlers/app_update.rs:48-60`、
  `lib.rs:626-650`、`store/mod.rs:361-383`:所有 `Store`(rusqlite + 每次连接 3 次 chmod)
  与 `std::fs`(整文件 read、fsync、APK 每次下载全量 SHA-256)都在 async handler 内联执行。
  小 NAS 上几个慢操作即可占满 tokio worker,`/health` 都无响应 → 客户端表现为卡死。

## Fix

1. handler 内所有 `state.store.*` 调用与媒体文件读写包 `tokio::task::spawn_blocking`
   (store 同步签名不变,handler 侧包一层;注意 `?` 错误透传)。
2. `app_update.rs`:启动时缓存 APK 字节 + SHA-256,按文件 mtime 失效重建;不再每请求重算。
3. `lib.rs` `require_supported_client`:缓存 `app-update.json` 解析结果,同样 mtime 失效。

## Validation

- `cargo fmt --all -- --check && cargo test --locked && cargo clippy --all-targets --all-features -- -D warnings`
- 既有 handler/store 测试不改语义应全绿。
