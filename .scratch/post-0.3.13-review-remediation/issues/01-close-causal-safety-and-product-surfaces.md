# 01 — 闭合因果升级安全与 Wake/conflict/duplicate 产品面

**What to build:** 让从 Room 26 升级、仍带有离线 dirty 家庭事实的用户安全进入 0.3.13
因果协议，并在同一条真实产品链上完整使用 WakeObservation、字段冲突和疑似重复组。第一次
full pull 不得覆盖本机 pending；保存仍立即本地成功，随后通过公开同步 façade 在真实隔离服务
上 accepted、merged 或 branched，第二客户端能看到稳定投影和诚实的冲突/来源摘要。时间轴、
详情和汇总必须消费现有领域状态，而不是只在 ViewModel 或纯 projection 测试中存在。

**Blocked by:** None — can start immediately.

**Status:** implemented — JVM/compile gates pass; device execution and isolated real-service
two-client acceptance remain pending

- [ ] Room 26→27 升级后的 Baby、Record、CarePlan、CustomItem、WakeObservation 及原子媒体，在 `baseVersion` / `mutationId` 尚未建立时仍保护所有 dirty 内容、删除意图、本机 revision 和媒体引用；第一次 full pull 不得用远端稳定投影清除或覆盖它们
- [ ] 升级根能获得可验证的因果基线并通过现有 reconcile/commit seam 终结；不得用 reset、full-resync、清表、强制 rejoin 或墙钟 LWW 绕过基线缺失
- [ ] 公共主验收链从一次 CareLog 写入开始，经 SyncPort/RealSyncPort 对接开发者隔离的真实 lezi-sync，再由第二个 joined client pull；覆盖升级 dirty 根、无害字段 merge、同字段 branch、媒体和稳定 pending/conflict 状态
- [ ] LocalWrite 在可信前台健康条件下不做 entity pull、不推进 pull cursor；第二客户端能看到发布结果，而原客户端下一次完整周期仍能取得另一成员的无关写入
- [ ] 时间轴与详情展示全部合法 WakeObservation 的 observer、时间、备注和照片；未选择时明确显示暂定最早合法观察，较旧重叠 SleepStart 保持事实不被自动闭合并提供可理解的处理入口
- [ ] observer 可修正/撤回自己的观察，Sleep 作者或 Owner 可选择有效观察；非法时间、跨身份修改和旧 B1 权限均在产品入口与服务端一致拒绝
- [ ] 点击冲突 badge 会按需取得真正的 stable/base/branches 详情，只展示实际冲突字段和媒体选择；expected stable/完整 branch set CAS 失败时刷新差异并保留用户选择草稿
- [ ] Timeline 默认展开疑似重复组，展示全部来源、作者、时间、值、备注和照片；作者声明与 Owner 整组 resolution 走显式 ACL/CAS，新来源到达不会被旧请求漏收
- [ ] Log 与 Summary 对未解决组显示指标级确定性下界–上界，不能偷偷取 max 或隐式 winner；解决后恢复单值但来源关系和照片仍永久可访问
- [ ] Compose/device tests 覆盖 wake 暂定与重叠、冲突 detail/resolver/CAS 刷新、疑似组展开/收起与 action、汇总上下界、离线/pending 文案及无障碍语义，并保存固定 HEAD 的截图或等价设备证据
- [ ] 回归测试明确覆盖“迁移后第一次真实同步”而非只证明数据库能打开；相关 Android JVM/instrumentation、Rust Store/API 与隔离双客户端测试通过，且无用户数据清除或 NAS 生产状态变更
- [ ] PRD、ADR 状态和原 `lossless-family-causal-sync` 验收说明只把有固定 HEAD 证据的行为写成已交付；未完成的生产 CD 继续保持 residual
