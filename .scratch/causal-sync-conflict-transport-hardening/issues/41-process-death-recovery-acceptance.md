# 41 — 验收进程死亡恢复矩阵

**What to build:** 在 frozen commit、spool、paged snapshot 与 resolution 的明确边界杀进程，证明重启后继续或诚实终结且不丢事实。

**Blocked by:** 09、10、20、40

**Status:** ready-for-agent

## Contract slice

固定 kill points：envelope commit 后/HTTP 前；spool promote 后/Room manifest 前；page transaction 后/promote complete 前；resolution durable 后/response 前。

## Implementation sequence

1. 为四个 kill points 建立可重复 fixture。
2. 杀进程并重建 app/session。
3. 恢复 pending/page/selection 或 replay terminal result。
4. 比较 facts/cursor/version/spool/receipt。

## Acceptance

- [ ] 无 duplicate、cursor skip、orphan stable fact
- [ ] pending evidence 保留，terminal replay 收敛
- [ ] incomplete page 不可提交，完整旧 cache 可离线读

## Validation

- [ ] 四点 process-death matrix 通过
- [ ] exact kill point 与恢复 evidence 被记录

## Out of scope

不覆盖 device accessibility 或 server schema migration。
