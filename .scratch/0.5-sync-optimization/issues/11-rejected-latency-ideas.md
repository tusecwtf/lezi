# 11: 同步时延目标下评估后否决的候选(决策记录)

**What:** 2026-09-26 同步时延目标(前后端通讯必要同步时间到最小)期间评估、但按行为保持/契约风险否决的候选项。逐条记录,避免"评估后否决"与"未考虑"不可区分。

**Status:** rejected(2026-09-26;重启任一项需 owner 点头并先补各自的证明基线)

- 手shake 缓存复用于 LocalWrite 轮(否决):轮次握手是 no-pull 发布的冻结能力门(`pullTransport`
  /能力精确匹配);复用陈旧握手意味着在未经本轮验证的能力集上推送——服务端能力变化时可能
  以 commit 成功的形式静默偏离,而不是既有的 `capability_mismatch` fail-closed。已接受的
  tip-skip 只覆盖"可证明无事可做"的静默轮,不适用于有写入的活跃轮。
- 丢弃 commit 后的 creator-ack 二次 `pullAllPages`(否决):削弱"必须在 head 观察到"的收敛
  保证;ack 的证据来源从服务端 head 回读改为 commit 响应是收敛语义变更,需产品决策而非纯优化。
- bundle stage→commit 拆分/并行(否决):fulfillment 候选行每轮通常 1-5 条,拆分仅省约 1.5 个
  RTT,却要给收据/abandoned-receipt 路径引入并发;收益/风险比不成立。
- widget onUpdate 30 分钟 tick 改为仅重绘快照(否决):进程被杀后该 tick 是跨午夜桶重置的唯一
  兜底刷新;仅重绘快照会让 widget 停留在昨日聚合直到 app 再启动(见 90f5700e 提交说明)。
- LocalWrite 排队轮的互斥优先级(搁置):写入到达时在途轮的剩余预算是其主导尾延迟;无安全的
  最小改法,需要轮次抢占语义设计。

已落地项见 `a5899561`(页内 staging 下载段并行,ticket 10)与同提交的握手/本地前置重叠;
其余安全项(定向 widget 刷新)落地后因 conflate 丢事件缺陷回退,见后续 fix 提交。
