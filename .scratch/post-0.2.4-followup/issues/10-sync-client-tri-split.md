# 10 — 同步客户端：Fake / Http / 编排三分离

**Parent:** [../spec.md](../spec.md)

**What to build:** 将假后端、HTTP 后端（含限长读体等传输）与同步编排端口拆到独立编译单元/文件边界，便于单独测试传输与协议客户端。禁止借机深拆副本引擎/媒体流水线。行为与 P1 后一致；清除屏障与分页 fail-closed 测试不得削弱。

**Blocked by:** 01 — 清除屏障；03 — 分页 fail-closed

**Status:** complete

## Acceptance criteria

- [x] Fake 后端、HTTP 后端、编排端口边界清晰可独立打开
- [x] 无语义改动：push/pull/join/clear/media 既有测试全绿
- [x] S1/S3 相关断言不被删除或放宽
- [x] 明确不做：副本应用/媒体管线二次大拆（可登记债务）
- [x] DI/可见性调整不引入行为开关

## Comments

- R2：硬依赖 01+03（同文件冲突）；04 仅为软对齐。
