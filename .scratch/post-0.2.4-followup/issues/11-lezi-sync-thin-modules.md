# 11 — lezi-sync：限流 / 就绪 / 成员薄模块

**Parent:** [../spec.md](../spec.md)

**What to build:** 从家用 NAS 服务端入口抽出限流、就绪检查、成员列表投影（含 coalesce 与本地占位名过滤）薄模块。不重写 store 的 LWW/pull 依赖组语义。API 测试保持绿；占位名字符串与客户端常量对齐意图可注释互链。

**Blocked by:** None — can start immediately（与 Android 无编译依赖；建议不与 10 同批评审大 diff）

**Status:** complete

## Acceptance criteria

- [x] rate_limit / readiness / members 成独立模块边界
- [x] 成员投影、合并不提权、占位名过滤行为不变
- [x] 不改写 store pull 依赖组 / LWW 核心
- [x] `cargo test` / API 套件全绿
- [x] 与客户端占位名常量字符串一致（或文档说明唯一真源）

## Comments

- R2：无硬阻塞边；不硬挡 15。
