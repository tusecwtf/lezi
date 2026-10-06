# 01: 开放睡眠等闭合后再自动收口

**What to build:** 近邻窗内两条还没醒来的 SleepStart 不再被自动写成来源关系，两条都保持可
见、可分别醒来。等它们各自有合法 WakeObservation（或历史遗留闭合 end）之后，才按原近邻
规则收口。只醒一条、另一条仍开放则仍不收口。已闭合的近邻小睡、喂养和其它类型照旧立刻
收口。合同与这条行为一起改。

**Blocked by:** None (can start immediately)

**Status:** done

- [x] 两条近邻开放 SleepStart 在 commit 后不出现来源关系
- [x] 两条都写出合法醒来（或历史遗留 end）之后出现一条来源关系
- [x] 只醒一条、近邻另一条仍开放时仍不收口
- [x] 两条已闭合近邻小睡、以及非睡眠近邻类型，行为与现在相同
- [x] 同一 commit 里同时带上睡下与合法醒来的，按已闭合参与归组
- [x] ADR-0023 §4、CONTEXT「疑似重复输入」、data-model 近邻节写明：投影开放不进候选、
      闭合后收口、不拆存量关系
- [x] 不自动闭合另一条 SleepStart，不把 WakeObservation 改绑到展示版
