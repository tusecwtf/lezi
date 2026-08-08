# 09 — 双客户端 E2E、强制协议切换、发版与验收

**What to build:** 固化 01–08 的跨层合同，完成 Room/server 迁移与回滚演练、隔离真实服务双客户端
矩阵、完整 Android/Rust/release gates，并准备 0.3.13 强制原子切换。只有所有本地证据通过且用户
再次明确批准维护窗口后，才构建/打包/推送并 stop-rm-replace 家庭 NAS。

**Blocked by:** 02、03、04、05、06、07、08。

**Status:** local-partial — cutover floor/API E2E/docs aligned; CareLog–SyncPort primary seam, Room connected matrix, full offline rollback image restore, and NAS CD remain open

- [x] 开始时重新 pin 当前 HEAD、git status、Android/server 版本、versionCode、Room schema、server user_version、release compatibility 与 app-update metadata
- [x] 若实时清单仍为当前基线下一版，统一目标为 Android/server 0.3.13、versionCode 20、Room 27、server schema 12；任何漂移先回到票 01 修订合同
- [x] 隔离真实 lezi-sync + 两 joined clients 覆盖不同字段自动 merge、同字段双 branch、作者/Owner resolution 与 peer stable/conflict 可见（HTTP `/v1/causal/*` 两 joined session；**非** CareLog→SyncPort 主缝）
- [x] E2E 覆盖 current causal delete、delete/edit **两到达顺序**、稳定 tombstone stale replay、lost-response idempotent replay、resolution CAS 失败闭合（stale expected + post-resolve）；process-death / 显式 restore 细表仍以 Store unit 为主 → 未宣称双端完整
- [x] E2E 覆盖独立媒体 addition merge、同媒体 delete/edit branch、字节保留（HTTP causal media preimage + commit/pull）
- [ ] E2E 覆盖 offline WakeObservation 全表（observer correction/withdraw、有效观察选择、重叠 SleepStart UI）；**已证** 两客户端多 wake 全保留
- [ ] E2E 覆盖软分组、汇总上下界、author declaration、Owner group resolution 全表；**已证** 不同 UUID 近邻双 live、无 neighbor_losers
- [ ] E2E 证明 LocalWrite 经 SyncPort/引擎 no-pull 且不推进 pull cursor（**未**）；仅有服务端独立 create + full pull peer 可见（不得单独作为 LocalWrite 验收）
- [ ] Room 26→27 真 APK/fixture 升级矩阵（本窗未跑 connected）
- [x] server v11→v12 offline migrator unit 覆盖；旧服务拒绝错 schema（unit）
- [ ] 回滚演练：完整 v11 database/data/image 备份 + 旧镜像恢复（本窗未跑；仅 unit/docs）
- [x] 同签名 Release APK + app-update minSupported=20 + catalog floor 20 + PROTOCOL_CUTOVER=20 启动闸
- [x] Android `./gradlew test` `lintDebug` `assembleRelease`；Rust fmt/test/clippy（connected/真机 smoke 未跑）
- [x] 发布前报告诚实未完成项；tracker **不** 标 complete
- [x] 提出 NAS CD 风险并 **等待确认**（未执行 build-image/push）
- [x] 未获确认前不得生产 stop/rm
- [ ] CD 前后 TLS pin / migrate / health / joined smoke（未确认）
- [ ] 全部 Must + 生产 smoke 后才 complete 01–09

## Evidence (local amend — 2026-08-09)

| Item | Result |
|------|--------|
| Product authority | PRD README/data-model/tech + ADR-0019/0020 与 tree 因果落地对齐；NAS 切割仍标 residual |
| PROTOCOL_CUTOVER | **20**; require_protocol_cutover rejects floor 16 |
| Neighbor | schema-12 bundle commit **never** mint neighbor_losers |
| Two-client HTTP | merge/branch/CAS, delete↔edit both orders, multi-wake, near-dup live, media merge+branch, catalog floor gate |
| Primary Spec seam | **open** — no CareLog→SyncPort→real lezi-sync two-client chain in this ticket |
| LocalWrite Spec | **open** — engine unit (08) only; HTTP peer pull is not no-pull proof |
| Room connected / rollback image / NAS CD | **open** |

### NAS CD proposal (blocked)

Requires explicit maintenance-window confirmation before `build-image` / `push-and-deploy`.
