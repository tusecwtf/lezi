# 02 · Bundle media lock scope + server timeouts

Status: complete — absorbed by hang-fidelity tracker on `6b278242`

## Findings

- `src/handlers/media.rs:125-184`:`put_bundle_media` 先 `family_lock.lock().await` 再逐 chunk
  读请求体;全服务无任何 HTTP 读/写/整体超时(仅 healthcheck TCP 与 SQLite busy_timeout)。
  慢/停滞客户端无限期占 family 互斥锁,同 family 全部请求永久排队。
- `lib.rs:572-573`:只有 `DefaultBodyLimit` + `TraceLayer`,无 TimeoutLayer。

## Fix

- [x] `put_bundle_media`:先带上限读完 body 再取 family 锁;或锁内流读外包
   `tokio::time::timeout`(建议 120s,对齐客户端 read timeout)。选侵入小者。
- [x] 路由加 `tower_http::timeout::TimeoutLayer`(整体超时如 300s,勿误伤大上传;
   如 tower-http 版本/API 有差异以仓库现有依赖为准,不新增大依赖)。

## Validation

- [x] 同 01 的 cargo 门;`stalled_bundle_body_does_not_hold_the_family_lock_against_pull`
  覆盖 body 停滞时 pull 不被 family 锁阻塞。
