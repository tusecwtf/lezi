# Scratch · 工作项

本目录是 active 规格与票单的工作区；当前 worktree 同时承载 0.4.0 主线历史 tracker 与 Lezi 1.0.0 多租户重构 Wayfinder。

| Tracker | 说明 |
|---------|------|
| [`greenfield-rewrite-blueprint`](./greenfield-rewrite-blueprint/) | 决策地图 + [blueprint.md](./greenfield-rewrite-blueprint/blueprint.md) |
| [`greenfield-implementation`](./greenfield-implementation/) | 41 张实现竖切票 |
| [`causal-sync-conflict-transport-hardening`](./causal-sync-conflict-transport-hardening/) | **ready-for-agent** — **0.4.0**（code 21 / Room 28 / server schema 13）：43 张单-session 串行票覆盖无损 APK/后端升级、ConflictSnapshot、N 方合并、commit-first、传输、媒体、schema-cutover CD 与分段验收；frontier = **01**，资源合同复用审查票 12/17/18/19，生产切割仍归旧票 09 |
| [`repository-dedup-algorithm-audit-20260809`](./repository-dedup-algorithm-audit-20260809/) | **in-progress** — 固定 `38cfbe7` 的全库重复/残留/算法审查；19 个 ticket，其中资源所有权拆为 admission 12、head loader 17、snapshot receipt/page 18、resolution metadata retention 19；当前 frontier 见 tracker |
| [`post-0.3.13-review-remediation`](./post-0.3.13-review-remediation/) | **ready-for-agent** — 0.3.13 固定 HEAD 审查修复：票 01 闭合升级 dirty 因果安全及 Wake/conflict/duplicate 产品面；票 02 收缩旧路径、精简测试并重建预切割证据；frontier = **01** |
| [`lossless-family-causal-sync`](./lossless-family-causal-sync/) | **local-partial** — 因果基础 01–08 已落地，0.4.0 生产验收票 09 仍开放；现被 `causal-sync-conflict-transport-hardening/43` 与新的 schema-cutover 维护窗口确认阻塞 |
| [`continuous-timeline-navigation-0.3.12`](./continuous-timeline-navigation-0.3.12/) | **in-progress** — **0.3.12**：票 01–04 complete；签名升级安装矩阵已跑，票 05 等待携带完整家庭会话的 TLS/sync 夹具，票 06 仍需其通过及新的 CD 批准 |
| [`family-sleep-wake-acl`](./family-sleep-wake-acl/) | **ready-for-agent** — **0.3.11**：跨成员家庭 wake 可发布 + 本机 B1 受限纠错 + 发版联调；票 01 complete → frontier = **02** |
| [`neighbor-dedup-tombstone-0.3.10`](./neighbor-dedup-tombstone-0.3.10/) | **implementation-complete + released** — 0.3.10 近邻落选 + 墓碑永胜 + 同步 chrome（历史索引） |
| [`sync-authoritative-reconciliation`](./sync-authoritative-reconciliation/) | **implementation-complete** — 批量 head-by-UUID 权威裁决、dirty 终态收敛、local-only/技术清理与诚实 pending；等待双端真实服务与 NAS CD 验收 |
| [`ui-drawing-polish-20260804`](./ui-drawing-polish-20260804/) | **complete** — designsystem chrome 清零、warm/journal 密度表、Lazy 列表、motion/稳定性、有界照片 LRU、空态与截图矩阵（01–13；smoke 矩阵见 tracker `smoke/`） |
| [`sync-publish-reconcile-first`](./sync-publish-reconcile-first/) | **complete** — 客户端发表计划改为对账优先、临时 plan；Room 26 迁移保留旧 APK 数据与发布意图 |
| [`family-sync-hang-and-account-fidelity`](./family-sync-hang-and-account-fidelity/) | **implementation-complete** — 0.3.5 已从 `149b7ae6` 部署；7 项保留 live/NAS/多设备验收 |
| [`family-network-offline-disaster-recovery`](./family-network-offline-disaster-recovery/) | **acceptance — awaiting joined-device smoke** — live 已前进至 0.3.5；票 07 真机补传待完成 |
| [`codebase-audit-20260802-remediation`](./codebase-audit-20260802-remediation/) | **complete** — implementation `6b278242`; ticket 07 regression `d160fe68` |

产品权威仍在仓库根 `docs/prd/`、`docs/adr/`、`CONTEXT.md`。
