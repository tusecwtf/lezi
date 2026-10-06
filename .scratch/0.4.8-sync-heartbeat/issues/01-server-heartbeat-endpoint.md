# 01: 服务端：心跳探针端点、能力广播、限流与 wire 文档

**What to build:** 已加入设备带设备 token 调用 `GET /v1/sync/heartbeat`，一次拿到三个信号——
安装世代（generation）、家庭事实水位（head_rev）、目录摘要（directory_generation）——供客户端
比对决定是否同步；同时 `/v1/setup-status` 增量广播 `sync_heartbeat_v1` 能力。对老客户端零影响
（握手 capabilities 键集不变、无新调用）。完成后可用 curl + 设备 token 现场演示。

**Blocked by:** 无（心跳并入 0.4.8 发布；建议先把在飞三条 0.4.8 流收口提交再叠加心跳，避免同一脏树四流混提）。

**Status:** done

- [x] 认证必需：无/坏 token 拒绝；401 终态语义与既有同步端点一致（device_removed 等）
- [x] 响应为闭合三键 `{generation, head_rev, directory_generation}`，无多余字段
- [x] 锁纪律：探针不驻留 family 锁——并发提交期间的探针等待不超过单次提交粒度（锁内只做单行读+摘要重算，释放后再构造响应）
- [x] `wait` 查询参数接受但非零值一律按 0 处理（v1 忽略，为未来 long-poll 占位）
- [x] `/v1/setup-status` 增量广播 `sync_heartbeat_v1`；握手 capabilities 键集逐字节不变
- [x] 探针日志 DEBUG 级（不得在 info 级刷屏）；专属限流 scope 生效，超限 429
- [x] 路由测试：认证、三键闭合、锁纪律（探针与提交并发不互相卡死）、限流；既有测试全绿
- [x] wire 文档：新端点小节、§1 能力增量语义（setup-status 列表可增）、§7.1 认证探针可携带家庭元数据的许可注记
- [x] `cargo fmt --all -- --check`、`cargo test --locked`、`cargo clippy --all-targets --all-features -- -D warnings` 三门通过

## Parent

`.scratch/0.4.8-sync-heartbeat/spec.md`
