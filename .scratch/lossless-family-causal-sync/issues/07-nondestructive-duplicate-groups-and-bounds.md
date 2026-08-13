# 07 — 非破坏性近邻分组、统计上下界与显式 duplicate resolution

**What to build:** 移除客户端对 `neighbor_losers` 的结果处理，把现有精确类型白名单、跨
membership、30 分钟连通规则降级为本机软分组。未确认时展开全部来源并让汇总显示上下界；作者
可声明自己的记录重复，Owner 可解决整组。解决只建立展示版本/来源关系，不 tombstone 原记录或照片。

**Blocked by:** 03 — 服务器来源/resolution 合同；04 — Room 来源状态；05 — 稳定投影。

**Status:** complete

- [x] 新 capability 的服务端 commit 对不同 UUID 近邻全部保留 live，不再 Owner/最早/UUID 选胜者、不写 neighbor tombstone、不返回 `neighbor_losers`
- [x] Android 删除 `neighbor_losers` toast/终结路径；普通 remote tombstone 不能被启发式解释成 duplicate
- [x] 软分组严格复用精确 wire 类型白名单、同 baby、不同有效 membership、`|Δtimestamp| ≤ 30min` 含边界和连通分量
- [x] 同 membership 多设备/连记不成组；sleep、成长、日记、就医、疫苗、自定义和未列类型不成组
- [x] 分组计算是本机纯投影，不写 Record tombstone/dirty，不改变 CarePlan fulfilled 状态或 FulfillmentCandidate 证据
- [x] 时间轴默认将疑似组作为一个可展开容器并明确“待确认”，展开后每条原始记录、作者、时间、备注、值和照片均可见
- [x] 未确认组不偷偷选 winner；普通详情/编辑仍针对明确 source UUID，避免把一条编辑广播给整组
- [x] 作者只能声明自己 authored 的 source 与另一条相同；不能隐藏或重写他人 source
- [x] Owner resolution 必须覆盖 CAS 时完整 group source/version set，并选择一个展示版本；新 source 到达使旧 resolution 请求冲突而非漏收
- [x] resolution 后其它 sources 进入独立 source relation，普通时间轴/统计不重复计数，但记录、作者、备注和照片可从来源详情永久访问
- [x] source relation 不使用 `deletedAt`，普通 delete/resurrection/conflict 逻辑不能物理或逻辑吞掉其 provenance
- [x] unresolved 汇总对每个受影响指标运行既有聚合语义的合法解释集合并给出 deterministic min/max；不能用单条隐式 winner 冒充精确值
- [x] resolved 组只用选择的展示版本聚合；撤销/改解（若合同允许）同样走显式 CAS 并保留历史 provenance
- [x] 表驱动测试覆盖白名单/非白名单、29/30/31 分钟、跨日、transitive chain、同作者豁免、计划履行来源、媒体和新增并发 source
- [x] Summary/Timeline UI tests 覆盖上下界文案、展开/收起、作者/Owner action、resolution 后单值和来源详情无障碍
- [x] Store/API、sync、domain、log、summary 相关测试与隔离两客户端 duplicate smoke 通过

