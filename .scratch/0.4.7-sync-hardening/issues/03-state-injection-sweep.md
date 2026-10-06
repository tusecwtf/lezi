# 03: 状态注入穷举——从原因清单反推矩阵，追台架追不到的门

**What to build:** 维护者在 02 台架干净（真实三页无法复现）时，不再被动等待真机复发：把 01
原因清单里每个原因声明依赖的本机状态（如 versionId 缺失、回执残留、半初始化的因果簿记等）
逐项注入台架触发，任一复现即修掉该门并留合成语料回归。这是根治目标下的全力一击；02 已点名
真门时本票以证据记关闭、不执行。

**Blocked by:** 02: 真门重放（仅当 02 台架干净时执行；02 已点名则以证据记关闭）

**Status:** done

- [x] 注入矩阵从原因清单机械推导（每原因→其依赖的本机状态），矩阵完整覆盖已知原因
- [x] 逐状态在引擎台架触发；任一复现即修 + 合成语料回归测试
- [x] 全部干净时记录穷举证据，按定稿 contingency 交「复发即自证」（不出诊断装机轮次）
- [ ] 02 已点名情形下本票以证据记关闭

## Comments

- 2026-08-30：02 台架干净，本票执行。18 个 `DeferredGate` 矩阵 = 01 `ReplicaSyncEngineNamedApplyVerdictsTest`：16 门经 `synchronize` 产出具名回执；`MediaBytesUnstaged` 被同轮 `stageLogMediaDownloads` 覆盖（失败则整页抛错，apply 到不了该门）；`BabyLocalDirty` 被 `shouldApplyStablePull` 在 applyBaby 脏检查前短接到 Applied。无结构性误判可修。复发走具名回执自证，不出诊断装机轮次。
