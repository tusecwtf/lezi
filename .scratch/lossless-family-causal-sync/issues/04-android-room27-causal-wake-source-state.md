# 04 — Android Room 27 因果、WakeObservation、冲突与来源状态

**What to build:** 将本机数据契约升级到 Room 27，在现有 capability/call-flow 子包中为可变原子根
保存 `baseVersion` 和稳定 mutation epoch，新增 WakeObservation、冲突摘要/详情缓存、疑似重复组与
来源关系/媒体引用状态。26→27 必须原地保留所有用户数据、dirty 意图、会话和 endpoint 信任。

**Blocked by:** 01 — 因果领域和 wire 必须先冻结。

**Status:** complete

- [x] Baby、Record、CarePlan、CustomItem、WakeObservation 及其媒体原子根可保存 opaque `baseVersion`；本机内容 revision 与服务器 base 明确分离
- [x] 一次 dirty 内容 epoch 有稳定 `mutationId`，同一内容重试不变；用户再次编辑才生成下一 mutation，且仍基于最后 acknowledged base
- [x] WakeObservation 本地实体包含稳定 UUID、Sleep 引用、实际醒来时间、observer membership、备注、withdrawn 状态、修订/pending/base 与 0–3 照片关系
- [x] 冲突摘要能离线驱动 badge/list；按需详情缓存含 stable/base/branch identities 和真实冲突字段，但不成为第二稳定事实
- [x] 疑似重复组、作者声明、Owner resolution 和 source relation 使用独立表/原因；不得借用 `syncDirty`、Record `deletedAt` 或普通媒体 tombstone
- [x] 媒体引用允许 stable、local mutation、conflict branch 与 duplicate source 共存；引用感知清理不得删除仍被任一分支/来源持有的字节
- [x] `branched` receipt 能把当前 mutation 从“待对账”原子地转为“已在服务器保留的未解决冲突”，不会无限重发或误显示已完全一致
- [x] Room 26→27 migration 对现有 live/tombstone/dirty/receipt/media/local-only 数据建立确定性初始状态，不清表、不 destructive fallback
- [x] 迁移保留 local user、family/membership/session、credentials 引用、endpoint origin、TLS/SPKI trust、Baby 顺序/主题、记录/计划/自定义项、媒体路径与 pending cleanup
- [x] 旧 closed Sleep 在本机迁移为投影等价的 SleepStart + deterministic WakeObservation；备注、照片、作者和 dirty 状态不丢失
- [x] 旧 open Sleep 不被迁移自动闭合；旧历史 tombstone 保持隐藏且无恢复入口
- [x] local-data contract/release compatibility 清单增加 versionCode 20 / Room 27 相邻迁移，并保持 versionCode 6 永久升级基线连续
- [x] DAO 的冻结读取、exact CAS ack、branch receipt、resolution apply 和媒体引用变更均有事务行为测试
- [x] 迁移 instrumentation 从真实 Room 26 schema/fixture 打开 27 并核对行、字节引用、dirty、会话与信任；不得只测手写简化表
- [x] 相关 database/model/sync 编译与 JVM/device migration tests 通过，`git diff --check` 无误

