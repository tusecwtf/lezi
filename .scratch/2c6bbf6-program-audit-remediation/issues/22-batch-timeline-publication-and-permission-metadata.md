# 22 — 批量生成时间轴发布与权限元数据

**What to build:** 为时间轴一次性批量生成根发布状态、照片齐备状态与用户权限元数据，消除逐行串行查询并保持每行操作/文案正确。

**Blocked by:** 16 — 记录根发布回执与真实同步文案

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 一个时间轴窗口的数据装配以有界批量查询取得根回执、媒体状态、作者/成员权限，不为每个条目发起独立串行查询。
- [x] 零照片、含照片、首次发布、旧版本可见和当前版本已发布的文案与 Ticket 16 状态一致。
- [x] 每行编辑、删除、履行或跳过能力根据同一批权限快照计算，不因优化而扩大权限。
- [x] 数据更新后批量快照原子替换，列表不会短暂混合新根状态与旧权限/媒体状态。
- [x] 切换宝宝、离开页面或新查询到达时，旧装配工作可取消且不会覆盖新结果。
- [x] 大时间轴夹具证明查询次数为固定/批次数量而非随行数线性增长，并验证优化前后用户输出等价。

## Validation

运行时间轴仓储、权限、同步文案与性能回归测试，以及应用编译和静态检查。

## Documentation Gate

记录时间轴聚合快照包含的状态及其一致性边界。

## Implementation disposition

- 新增 `TimelineWindowDao`：一个 invalidation query 触发同一 Room 事务内固定三次读取
  （Record roots、CarePlan roots、活跃日志媒体）；1 个与 500 个根均为 4 次 Room query，
  没有按根或照片发起查询。
- `TimelineWindowRepository` 一次发布同 revision 的根发布态、媒体本机齐备度、家庭受众、
  作者称呼与行级能力。计划 ACL 精确复用 creator/member/owner/pending-ack 规则，Record
  保持既有编辑/删除能力，履行与跳过不扩权。
- Log 页面只从该 snapshot 取得所选日记录、72h rail、开放睡眠、计划、作者称呼、发布
  文案与操作能力；缺失能力元数据 fail closed。零照片/含照片继续复用 Ticket 16 首次、
  上一完整版本与当前版文案。
- baby/family/window/refresh 通过 `flatMapLatest`/`mapLatest` 取消旧工作；页面无订阅时立即
  取消装配。Room schema 保持 v24，未新增 migration。

验证与明确未执行边界见 [`../evidence/22/timeline-window-snapshot.md`](../evidence/22/timeline-window-snapshot.md)。
