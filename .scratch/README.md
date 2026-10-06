# Scratch · 工作项

进行中的规格与票单（约定见
[`docs/agents/issue-tracker.md`](../docs/agents/issue-tracker.md)）。

当前 active：

- [`0.5.4-data-safety-and-ux-polish/spec.md`](./0.5.4-data-safety-and-ux-polish/spec.md)（`Status: ready-for-agent` · 0.5.4 二合一规划（2026-09-13 两份审查合并，pre-merge 原稿在 `sources/`）：本机数据安全加固——dismiss 耐久化、宝宝合并×冻结信封、破坏性路径守卫+清理收据、APK 流式落盘、落库范围校验、测试门禁修复、三处文档修正（S1–S7，来源 [`2026-09-13-sync-conflict-best-practices-review.md`](./2026-09-13-sync-conflict-best-practices-review.md)）+ UX 信任面与动效收尾——冲突收件箱状态诚实、Summary 空态、导出归因、RecordRow 异常语义、计时器通知可操作、surfaceContainer 梯度、motion 票 02-04、触觉、Typography、Glance 主题（T1–T5/M1–M6，来源 [`2026-09-uiux-best-practice-review/REVIEW.md`](./2026-09-uiux-best-practice-review/REVIEW.md)）；零 schema/零 wire/零服务端代码；APK 单侧 0.5.4/34，NAS 停 0.5.3；2026-09-13）
- [`unresolved-local-dismiss/spec.md`](./unresolved-local-dismiss/spec.md)（`Status: done` · 未对齐项并入冲突收件箱：本机去掉不通知家里；普查投影 uuid + dismiss seam + 待处理徽章；2026-09-13）
- [`log-home-timeline-and-layout-edit/spec.md`](./log-home-timeline-and-layout-edit/spec.md)（`Status: implemented` · 记录页两组修复：一日时间条改连续绝对时间轴（换日零位移、粘性选中日 ≥1/4、今天不弹回、无天数上限；ADR-0024）+ 布局编辑「完成/返回」退出过渡 `checkNotNull` 崩溃到桌面与编辑态断言失败关闭；票 01–07 已合入 master；真机调参仍开放；2026-09-13）
- [`0.5.2-sync-stuck-hardening/spec.md`](./0.5.2-sync-stuck-hardening/spec.md)（`Status: ready-for-agent` · 2026-09-12 同步审查落地：履行候选/永久媒体码终态回执、超时不杀消费者、`Syncing`/锁收口、服务端 readiness；0.5.2 NAS+APK 同号升级）
- [`0.5.1-open-sleep-auto-align/spec.md`](./0.5.1-open-sleep-auto-align/spec.md)（`Status: ready-for-agent` · 投影开放 SleepStart 不进自动近邻；客户端醒来快捷对齐；0.5.1 NAS+APK 同步升级）
- [`ui-copy-hardening/spec.md`](./ui-copy-hardening/spec.md)（UI 文案质量加固：硬伤/原因透传/模板句/黑话/资源化）
- [`0.4.8-auto-near-neighbor/spec.md`](./0.4.8-auto-near-neighbor/spec.md)（近邻同型自动对齐；ADR-0023）
- [`0.4.8-generation-hot-resume/spec.md`](./0.4.8-generation-hot-resume/spec.md)（generation 热接续 + 前台续轮）

缓解已部署（非 active）：

- [`0.5-sync-optimization/spec.md`](./0.5-sync-optimization/spec.md)（`Status: done` · Tier 1 工单 01-09 全部落地并合入 master `dfdec921`；10 按 owner 挂起；仅剩 NAS 发布等 owner 确认，交接件 `/tmp/handoff-2026-09-06-0.5-sync-optimization.md`；2026-09-06）
- [`0.4.8-sync-heartbeat/spec.md`](./0.4.8-sync-heartbeat/spec.md)（`Status: done` · 代码票 01-05 与 smoke/发布 06-07 落地；08 的 NAS 部署并入 0.5.0 发布，VPS 部分随工具链删除放弃）
- [`docs-spec-restructure/spec.md`](./docs-spec-restructure/spec.md)（`Status: done` · 票 00-06 全部落地：docs/prd → docs/spec 全量重构 + 分层规格 + 连线表；2026-09-06）
- vps-public-authority 票树（`Status: abandoned` · 2026-09-06 owner 删除 VPS 工具链；票 02/03 永不执行）——含运维者私人基础设施信息，整树本地未跟踪留存，不入库
- [`0.4.7-sync-hardening/spec.md`](./0.4.7-sync-hardening/spec.md)（`Status: done`；NAS 0.4.7 已换，活集字节级未改；成员手机 点检与联调用户确认完成。VPS 见上条）
- [`0.4.5-sync-stuck-remediation/plan.md`](./0.4.5-sync-stuck-remediation/plan.md)（`Status: ready-for-human` · `缓解: 已部署`；后续见 0.4.7）
- [`2026-09-04-oppo-sleep-conflict-handoff/handoff.md`](./2026-09-04-oppo-sleep-conflict-handoff/handoff.md)（判断已清：整轮卡死=0.4.5 已拆；双睡=卡死窗口两边各自写，0.4.7 不埋这颗雷）
- [`2026-09-04-oppo-sleep-conflict-handoff/type-duplicate-audit.md`](./2026-09-04-oppo-sleep-conflict-handoff/type-duplicate-audit.md)（2026-09-05：全类型同机/多机重复审查；叠两条来自两次 UUID，不是同步引擎）

闭合票不归档；结论在 [`docs/spec/`](../docs/spec/)、
[`docs/adr/`](../docs/adr/)、[`CONTEXT.md`](../CONTEXT.md)，过程稿在 git 历史。

新工作：`.scratch/<feature-slug>/spec.md` + `issues/01-….md` + `ISSUES.md`。
