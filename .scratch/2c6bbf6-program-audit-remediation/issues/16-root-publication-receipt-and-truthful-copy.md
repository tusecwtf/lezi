# 16 — 记录根发布回执与真实同步文案

**What to build:** 为 Record/CarePlan 根实体保存独立于照片的远端发布回执，使零照片根和后续编辑也能准确判断家庭看到的是首次发布、上一版本还是当前版本。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Size:** M

## Acceptance criteria

- [ ] 每个可同步根都能持久区分“从未远端发布”“曾发布但本地有新版本”“当前版本已发布”，不以照片 remote URI 代替根状态。
- [ ] 零照片 Record/CarePlan 首次待上传时使用准确文案，不再显示“等待照片同步”。
- [ ] 已发布的零照片根再次编辑时，明确提示其他成员仍看到上一版本，直到当前版本收到发布确认。
- [ ] 含照片根继续保持 0–3 张照片原子可见；根回执不能制造 metadata-first 暴露。
- [ ] 发布确认、失败重试与应用/服务重启后状态保持一致，过期回执不能确认较新的本地版本。
- [ ] 首次创建与后续更新的自动化测试覆盖零照片、含照片、离线编辑、重试和重启。
- [ ] 若持久化契约需要版本调整，fresh-current schema 与测试夹具同步更新，不新增旧 Room migration。

## Validation

运行数据层、同步状态、Outbox 与 UI 文案测试，并完成 current client/server 集成验证。

## Documentation Gate

更新同步状态模型和用户文案表，明确根回执与媒体上传状态的关系。
