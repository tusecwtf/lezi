# 01 — 冻结因果领域、wire、PRD 与 ADR

**What to build:** 先把“家庭事实、稳定快照、因果基线、修改身份、冲突分支、WakeObservation、
来源关系、疑似重复组”写入产品权威，并冻结 0.3.13 的破坏性 wire。明确服务器能验证约束和执行
显式管理操作，但不能用墙钟、Owner 身份或近邻启发式替家庭裁决护理事实。修订已被本规格取代的
睡眠、LWW、开放睡眠修复、近邻 tombstone 与 Record 墓碑永胜叙述。

**Blocked by:** None — can start immediately.

**Status:** complete

- [x] `CONTEXT.md` 增加且交叉区分家庭事实、稳定快照、版本/因果基线、修改身份、冲突分支、来源关系、WakeObservation 与疑似重复组；Avoid 项禁止把它们退化为 LWW/outbox/tombstone
- [x] 数据模型 PRD 明确 SleepStart 与 WakeObservation 分离、多个观察保留、暂定最早合法观察、有效观察 resolution 与重叠睡眠不改写
- [x] 同步 PRD 明确 versioned atomic root、`base_version`、`mutation_id`、三方路径合并、持久分支、稳定 pull 投影、冲突详情和 resolution CAS
- [x] 汇总/时间轴 PRD 明确疑似重复组展开全部来源、未确认上下界、resolution 后单展示版本以及来源/照片永久保留
- [x] 技术/更新 PRD 明确 0.3.13 / versionCode 20、Room 27、server schema 12 是规划目标，并要求发版前从实时清单重新核对
- [x] 新 ADR 冻结“服务器验证约束、不裁决护理真相”的边界，并记录拒绝墙钟 LWW、睡眠窄例外、通用事件时间赢家和全文 CRDT 的原因
- [x] 新 ADR 冻结稳定投影 + 不可变版本/分支架构、三方合并粒度、delete/edit 因果语义、媒体原子性与显式 resolution
- [x] 新 ADR 冻结 WakeObservation 与非破坏性疑似重复分组；明确它们取代 ADR-0018 的自动近邻落选，但历史 tombstone 不复活
- [x] ADR-0017/0018 与相关已完成专题标明 superseded 范围；历史实现事实与新目标不混写成已交付
- [x] wire 合同列全 reconcile `confirmed|publish|conflict_preview|rejected`、commit `accepted|merged|branched`、pull/version/conflict summary、detail/resolution CAS 的必填与 bounds
- [x] canonical JSON path、数组原子规则、media UUID 合并规则、相同 mutation ID 内容漂移、稳定 tombstone replay 与显式 restore 都有无歧义例子
- [x] FulfillmentCandidate 继续不可变候选证据，不被误纳入可变三方编辑；Baby/Record/CarePlan/CustomItem/WakeObservation 原子媒体边界完整
- [x] 文档明确 LocalWrite no-pull 只能在因果协议之后启用，且不推进 pull cursor
- [x] 文档链接、术语搜索与 `git diff --check` 通过；没有以 StructureTest 锁定文档布局/行数
