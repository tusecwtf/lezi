# 02 · Bundle media lock scope + server timeouts

Status: ready-for-agent

## Findings

- `src/handlers/media.rs:125-184`:`put_bundle_media` 先 `family_lock.lock().await` 再逐 chunk
  读请求体;全服务无任何 HTTP 读/写/整体超时(仅 healthcheck TCP 与 SQLite busy_timeout)。
  慢/停滞客户端无限期占 family 互斥锁,同 family 全部请求永久排队。
- `lib.rs:572-573`:只有 `DefaultBodyLimit` + `TraceLayer`,无 TimeoutLayer。

## Fix

1. `put_bundle_media`:先带上限读完 body 再取 family 锁;或锁内流读外包
   `tokio::time::timeout`(建议 120s,对齐客户端 read timeout)。选侵入小者。
2. 路由加 `tower_http::timeout::TimeoutLayer`(整体超时如 300s,勿误伤大上传;
   如 tower-http 版本/API 有差异以仓库现有依赖为准,不新增大依赖)。

## Validation

- 同 01 的 cargo 门;新增或调整测试覆盖「body 超时释放锁」可行则加,不可行在票内说明。
