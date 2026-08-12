# 09 — 双客户端 E2E、强制协议切换、发版与验收

**What to build:** 固化 01–08 与因果冲突/传输硬化 43 的 0.4.0 跨层合同，完成 APK Room 28 与 server schema 13
非破坏性迁移、回滚演练、隔离双客户端和完整 gates。只有所有本地证据通过且用户
再次明确批准维护窗口后，才构建/打包/推送并 stop-rm-replace 家庭 NAS。

**Blocked by:** 02、03、04、05、06、07、08；
[`causal-sync-conflict-transport-hardening/43`](../../causal-sync-conflict-transport-hardening/issues/43-final-local-review-handoff.md)。

**Status:** local-partial — prior v1 cutover/API evidence retained; hardened wire, CareLog–SyncPort primary seam, Room connected matrix, full offline rollback image restore, and NAS CD remain open

- [x] 开始时重新 pin 当前 HEAD、git status、Android/server 版本、versionCode、Room schema、server user_version、release compatibility 与 app-update metadata
- [x] 历史 v1 证据目标曾为 Android/server 0.3.13、versionCode 20、Room 27、server schema 12；这些数字不自动成为 hardening 发版目标
- [ ] `causal-sync-conflict-transport-hardening/43` 完成；目标严格为 Android/server 0.4.0、code 21、Room 28、contract 5、server schema 13、floor 21
- [ ] 同签名 code 6 与当前 code 20 APK 原地升级到 code 21，保留 Room facts/tombstone/pending/conflict/media/spool/session/credentials/endpoint/TLS trust
- [x] live NAS preflight 只接受完整且 shape 匹配的 server user_version 11 或 12；其它 source 在 stop/rm 前中止并另立迁移（编排已从 attested rollback package 推导 0.3.12/11 或 0.3.13/12；生产 live 探针仍待维护窗）
- [ ] `offline-migrate` v11/v12→v13 copy-out/dry-run/migrate/validate 与隔离 rollback rehearsal receipts 全通过；source data root 零写入
- [x] 隔离真实 lezi-sync + 两 joined clients 覆盖不同字段自动 merge、同字段双 branch、作者/Owner resolution 与 peer stable/conflict 可见（HTTP `/v1/causal/*` 两 joined session；**非** CareLog→SyncPort 主缝）
- [x] E2E 覆盖 current causal delete、delete/edit **两到达顺序**、稳定 tombstone stale replay、lost-response idempotent replay、resolution CAS 失败闭合（stale expected + post-resolve）；process-death / 显式 restore 细表仍以 Store unit 为主 → 未宣称双端完整
- [x] E2E 覆盖独立媒体 addition merge、同媒体 delete/edit branch、字节保留（HTTP causal media preimage + commit/pull）
- [ ] E2E 覆盖 offline WakeObservation 全表（observer correction/withdraw、有效观察选择、重叠 SleepStart UI）；**已证** 两客户端多 wake 全保留
- [ ] E2E 覆盖软分组、汇总上下界、author declaration、Owner group resolution 全表；**已证** 不同 UUID 近邻双 live、无 neighbor_losers
- [ ] E2E 证明 LocalWrite 经 SyncPort/引擎 no-pull 且不推进 pull cursor（**未**）；仅有服务端独立 create + full pull peer 可见（不得单独作为 LocalWrite 验收）
- [x] 历史 Room 26→27 迁移实现已在 tree；不得替代 0.4.0 的 Room 24/25/26/27→28 与 signed APK device matrix
- [x] server v11→v12 offline migrator unit 覆盖；旧服务拒绝错 schema（unit）
- [ ] 回滚演练：完整 v11 database/data/image 备份 + 旧镜像恢复（本窗未跑；仅 unit/docs）
- [x] 同签名 Release APK + app-update minSupported=20 + catalog floor 20 + PROTOCOL_CUTOVER=20 启动闸
- [x] Android `./gradlew test` `lintDebug` `assembleRelease`；Rust fmt/test/clippy（connected/真机 smoke 未跑）
- [x] 发布前报告诚实未完成项；tracker **不** 标 complete
- [x] 提出 NAS CD 风险并 **等待确认**（未执行 build-image/push）
- [x] 未获确认前不得生产 stop/rm
- [ ] CD 前签名 0.4.0/code 21 APK、app-update minSupported=21、lezi-sync:0.4.0/schema13 package/image/inventory 全部一致
- [ ] 重新提出 schema-cutover 维护窗：说明停服、完整 data+credential rollback backup、copy-out/migrate/copy-back、旧 image/data rollback 与写入重新开放点，并等待明确确认
- [ ] 确认后取得 outer lease；记录 source schema/data inventory、old image/package、certificate SHA-256/SPKI；完成 off-repo encrypted credential 与完整 source data rollback backup
- [ ] 先验证 code 21 APK/install channel，再停服；执行 copy-out→v11/v12→v13 migrate→validate；只 promote validated staging root
- [ ] 启动 0.4.0 后要求 image/version/schema13/health/ready、TLS SHA/SPKI、secret、row/branch/conflict/media/session inventory 全部匹配
- [ ] 开放新写入前失败自动恢复旧 data root + 0.3.13 image/app-update pair；开放后不得静默回滚丢弃 0.4.0 writes
- [ ] joined code 21 clients 完成 create/pull/conflict/resolution/media smoke 后才结束维护窗
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

Requires hardening ticket 43 plus a new explicit **0.4.0 schema-cutover** maintenance-window confirmation.
Ordinary `push-and-deploy` cannot perform migration; use only the audited guarded cutover entry from hardening 29,
which delegates image/package replacement to the protected deploy workflow after offline validation.
