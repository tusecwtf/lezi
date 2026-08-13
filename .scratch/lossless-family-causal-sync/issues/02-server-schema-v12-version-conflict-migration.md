# 02 — lezi-sync schema v12 版本/冲突存储与 v11 离线迁移

**What to build:** 将 Store 新鲜 schema 提升到 v12，增加稳定快照所需的不可变版本、父版本、
mutation 幂等、冲突/分支、resolution、WakeObservation 和重复来源关系存储，同时保留 `entities`
作为事务一致的快速稳定投影。扩展现有离线 copy-out migrator，从 v11 构造可验证的 v12，禁止
运行时就地升级或让旧二进制打开 v12。

**Blocked by:** 01 — 因果领域和 wire 必须先冻结。

**Status:** complete

- [x] `DATABASE_SCHEMA_VERSION` 单调升为 12；fresh-current schema 包含不可变 root/media version、parent/provenance、mutation receipt、conflict/branch、resolution 与 source relation 约束
- [x] `entities` 继续只承载每个家庭/类型/UUID 的稳定投影和 pull `rev`；版本图不得要求普通 pull join 全历史
- [x] mutation ID 在家庭/principal/atomic root 边界唯一，保存请求内容哈希和原始 accepted/merged/branched 回执；同 ID 异内容 fail closed
- [x] 冲突行完整引用 base、稳定版本与分支版本；分支 root/media、字节所有权和摘要在一个 SQLite 事务中落定
- [x] WakeObservation 成为受约束的家庭实体，引用有效 Sleep UUID；observer membership 由认证 principal 盖章且不可由 payload 伪造
- [x] duplicate declaration/group resolution/source relation 有独立原因和 provenance，不复用 `deleted_at` 或普通 Record tombstone
- [x] v11→v12 migrator 为每个当前稳定 Baby/Record/CarePlan/CustomItem 及原子媒体建立确定性 base version，并保留 rev/cursor/作者/引用
- [x] 历史 closed Sleep 生成确定性 WakeObservation，保留 wake time、备注、照片与作者/观察者；投影前后用户可见区间和媒体集合等价
- [x] 历史 open Sleep 只生成 SleepStart；历史多 open 不在迁移器中自动闭合、删除或选择赢家
- [x] 历史 Record tombstone 原样成为隐藏稳定 tombstone，不推断删除原因、不创建 restore 分支、不复活媒体引用
- [x] 迁移保留所有有效媒体字节/哈希/引用；缺半边、关联漂移、无法验证的稳定事实以 authoritative failure 终止，不静默丢弃
- [x] source v11、目标非空、错误 user_version、schema 漂移、迁移中断、磁盘/媒体失败都保持原库不变并输出脱敏报告
- [x] v12 validate 能证明 schema、版本/投影一一对应、稳定引用闭包、冲突闭包、媒体闭包、cursor/rev 和 deterministic replay
- [x] schema/preflight/offline-migrate/rollback rehearsal 测试通过；运行时仍只接受 fresh/current v12
- [x] `cargo fmt --all -- --check`、`cargo test --locked`、`cargo clippy --all-targets --all-features -- -D warnings` 通过

