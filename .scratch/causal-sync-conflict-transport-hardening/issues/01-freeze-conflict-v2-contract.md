# 01 — 冻结 conflict-v2 ADR、wire 与语料

**What to build:** 冻结 0.4.0 因果冲突与升级合同，使 Kotlin、Rust 和后续票对版本、Room/server migration、canonical root、ConflictSnapshot、choice、token、restore 与能力启用只有一种解释。

**Blocked by:** None

**Status:** ready-for-agent

## Contract slice

业务字段清空是 `set(null)`；`remove` 仅指媒体成员移除或 deletion transition。choice ID 在同一 snapshot 的重复 detail、分页和服务重启间稳定。snapshot token 是持久 receipt 支撑的随机标识。纯 restore 只使用 tombstone 声明的直接完整 live base。新 capability 在票 27 前不得 advertise。

## Implementation sequence

1. 重新 pin live HEAD 与生产 source schema；冻结目标 Android/server 0.4.0、code 21、Room 28、contract 5、server 13、floor 21。
2. 新增 ADR，精确取代 ADR-0020 的 mandatory reconcile-first/client-supplied result，并窄化取代 ADR-0016 对 durable pending envelope 的禁令：Room product facts 仍是领域真相，冻结 envelope 只是一个 mutation 的不可变传输真相。
3. 冻结 canonical、snapshot/token/choice、direct-base restore、terminal error 与 replay marker wire。
4. 发布语言无关的 golden fixtures 与期望接受/拒绝清单；运行时 parser conformance 留给各实现票。

## Acceptance

- [ ] optional field、unknown/missing/type/path-prefix 规则无歧义
- [ ] token 生命周期、choice 稳定性、full-set CAS 与 refresh 行为完整
- [ ] Room 27→28、server 11/12→13 offline-migrate 与 dedicated CD/rollback 边界无歧义
- [ ] capability 启用条件明确指向票 27，不可提前协商成功
- [ ] 外部资源票 12/17/18/19 与 release 09 的所有权交叉链接清楚

## Validation

- [ ] fixtures 可由 Kotlin/Rust 读取，schema 与期望结果完整且无运行时实现
- [ ] 文档链接、术语和 supersede 关系检查通过

## Out of scope

不实现 Kotlin/Rust parser conformance、server merge、Android UI、传输或生产发布。
