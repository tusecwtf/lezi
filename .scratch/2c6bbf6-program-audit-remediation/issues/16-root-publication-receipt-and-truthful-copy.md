# 16 — 记录根发布回执与真实同步文案

**What to build:** 为 Record/CarePlan 根实体保存独立于照片的远端发布回执，使零照片根和后续编辑也能准确判断家庭看到的是首次发布、上一版本还是当前版本。

**Blocked by:** None — can start immediately

**Status:** complete

**Size:** M

## Acceptance criteria

- [x] 每个可同步根都能持久区分“从未远端发布”“曾发布但本地有新版本”“当前版本已发布”，不以照片 remote URI 代替根状态。
- [x] 零照片 Record/CarePlan 首次待上传时使用准确文案，不再显示“等待照片同步”。
- [x] 已发布的零照片根再次编辑时，明确提示其他成员仍看到上一版本，直到当前版本收到发布确认。
- [x] 含照片根继续保持 0–3 张照片原子可见；根回执不能制造 metadata-first 暴露。
- [x] 发布确认、失败重试与应用/服务重启后状态保持一致，过期回执不能确认较新的本地版本。
- [x] 首次创建与后续更新的自动化测试覆盖零照片、含照片、离线编辑、重试和重启。
- [x] 若持久化契约需要版本调整，fresh-current schema 与测试夹具同步更新，不新增旧 Room migration。

## Validation

运行数据层、同步状态、Outbox 与 UI 文案测试，并完成 current client/server 集成验证。

## Documentation Gate

更新同步状态模型和用户文案表，明确根回执与媒体上传状态的关系。

## Implementation disposition

- Record/CarePlan fresh-current Room 根新增本机 `familyPublishedUpdatedAt`；纯分类器只把正数
  同版回执判为 current，较小正数判为 previous，缺失、非正数与未来值均 fail closed。
- DAO 在事务内单调合并服务端 commit 回执：同版才清 `syncDirty`，过期回执只记录上一版，
  不确认并发本地编辑；跨家庭或同步回执失效时清空根回执。
- Outbox 只在 atomic commit 成功后确认根；媒体上传产生的 `remoteUri` 不再参与根状态。
  pull/apply 的已提交根以远端 `updatedAt` 写 current 回执。
- 记录页从根模型直接计算发布状态，删除按照片逐条查询的 N+1 代理。首次零照片文案为
  “等待家庭同步”，更新失败/等待明确说明家庭仍看到上一完整版本。
- Room current schema 从 23 提升到 24，仅更新 fresh-current schema 与夹具，没有新增 migration。

验证与明确未执行边界见 [`../evidence/16/root-publication-receipt.md`](../evidence/16/root-publication-receipt.md)。
