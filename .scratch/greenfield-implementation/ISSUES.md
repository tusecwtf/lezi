# greenfield-implementation · 票索引

**Status:** ready-for-agent · 覆盖完整 [blueprint](../greenfield-rewrite-blueprint/blueprint.md)  
**Tracker:** 本地 Markdown · `Status: ready-for-agent`

| # | Title | Blocked by | G* |
|---|--------|------------|-----|
| [01](./issues/01-greenfield-shell-isolation.md) | 绿场空壳、隔离与基础门禁 | — | |
| [02](./issues/02-module-skeleton-and-libs.md) | 模块命名、库级默认与竖切骨架 | 01 | |
| [03](./issues/03-app-shell-templates.md) | 五 Tab 壳与 warm/journal/深色 | 02 | |
| [04](./issues/04-offline-baby-unjoined.md) | 离线首启建宝宝与未加入账户壳 | 03 | |
| [05](./issues/05-confirm-log-day-view-g1.md) | 确认记账核心与日视图 | 04 | G1 |
| [06](./issues/06-all-builtin-record-types.md) | 全量内置记录类型与字段规则 | 05 | |
| [07](./issues/07-record-photos-local.md) | 记录照片本机 | 05 | |
| [08](./issues/08-edit-delete-clear-records.md) | 编辑删除、草稿放弃与清空记录 | 05 | |
| [09](./issues/09-nursing-timer-next-feed-g2.md) | 喂奶计时与下次喂养 | 05 | G2 |
| [10](./issues/10-local-care-plans-calendar.md) | 本机护理计划与乐记日历 | 05, 09 | |
| [11](./issues/11-reminders-system-calendar.md) | 本机提醒与系统日历投影 | 10 | |
| [12](./issues/12-custom-items-layout.md) | 自定义项目与布局编辑 | 05 | |
| [13](./issues/13-week-summary-charts.md) | 周汇总图 | 05 | |
| [14](./issues/14-growth-curves.md) | 成长曲线 | 05 | |
| [15](./issues/15-search-and-export.md) | 搜索与 TXT/PDF 导出 | 05 | |
| [16](./issues/16-home-widget.md) | 桌面小组件 | 05 | |
| [17](./issues/17-display-a11y-about-honesty.md) | 显示杂项、无障碍与未加入关于诚实 | 03 | |
| [18](./issues/18-wire-capability-setup.md) | Wire current + capability + setup 探活 | 02 | |
| [19](./issues/19-create-family-owner-g3.md) | 可信建家与 Owner | 18, 04 | G3 |
| [20](./issues/20-member-apply-join-g4.md) | 成员申请加入与拉历史 | 19, 05 | G4 |
| [21](./issues/21-owner-login-multidevice.md) | Owner 登录/接管与多设备 | 19 | |
| [22](./issues/22-foreground-sync-shallow-status.md) | 前台同步会话与浅状态 | 20 | |
| [23](./issues/23-reconcile-atomic-record-photos-g5.md) | 权威对账与记录+照片原子同步 | 22, 07 | G5 |
| [24](./issues/24-plan-sync-cross-fulfill-g6.md) | 计划包同步与跨端履行 | 23, 10 | G6 |
| [25](./issues/25-custom-def-sync-layout-local.md) | 自定义定义同步且布局不同步 | 23, 12 | |
| [26](./issues/26-family-authority-babies.md) | 家庭权威宝宝与本机孤宝宝 | 20, 04 | |
| [27](./issues/27-record-acl-g7.md) | 护理记录 ACL | 20 | G7 |
| [28](./issues/28-conflict-nonadopted-fulfill.md) | 冲突未采纳履行 | 24 | |
| [29](./issues/29-network-spki-block-g8.md) | 家庭网络改址与 SPKI 阻断 | 19 | G8 |
| [30](./issues/30-owner-empty-server-restore.md) | Owner 空服灾难恢复 | 23, 19 | |
| [31](./issues/31-app-update-shells-g9.md) | 可选/强制应用更新壳 | 19 | G9 |
| [32](./issues/32-exit-leave-delete-family-g10.md) | 退出设备/离开/删成员/删家庭 | 20 | G10 |
| [33](./issues/33-member-login-qr.md) | 成员登录 QR 单次授权 | 20 | |
| [34](./issues/34-lan-invite-install.md) | LAN 邀请首装页 | 18 | |
| [35](./issues/35-local-data-upgrade-gate.md) | 本地数据升级/恢复门禁 | 05 | |
| [36](./issues/36-optional-local-backup.md) | 可选本机 DB/JSON 备份 | 05 | |
| [37](./issues/37-capture-ui-baselines.md) | 采集旧栈 UI 基线位图 | — | L4 |
| [38](./issues/38-doc-drift-alignment.md) | 文档漂移对齐 | — | |
| [39](./issues/39-cutover-mapping-checklist-draft.md) | 切换映射表草案与清单填空 | 23 | §9 |
| [40](./issues/40-e2e-g1-g10-green.md) | G1–G10 本机金线全绿收口 | 05,09,19,20,23,24,27,29,31,32 | L3 |
| [41](./issues/41-dependency-boundary-scan.md) | 绿场依赖越界与架构扫描门禁 | 02 | |

## 本批不单列

- **offline-migrate** 运维工具形态（蓝图附录 B）— 另开  
- **生产 NAS CD / cutover 执行** — 独立努力；39 只填映射草案  
