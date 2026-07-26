# 02 — 计时完成键撞软删：失败关闭

**Parent:** [../spec.md](../spec.md)

**What to build:** 母乳计时完成后用稳定完成键写入。对仍存在的同行保持幂等（不双记）。若同键记录已被用户软删，完成必须失败关闭——不得当成成功，也不得清空计时会话假装记过了；用户仍能看到计时数据以便改记或重试。文案短、不暴露内部标识。

**Blocked by:** None — can start immediately

**Status:** complete

## Acceptance criteria

- [x] 未删除的同完成键：再次完成 first-write-wins 幂等，不双记
- [x] 已软删的同键：完成失败关闭（不得返回已删 id 当成功）
- [x] 计时完成路径在软删冲突时不 clear 会话/不装成功；计时数据仍可用
- [x] 用户可见短文案（重试/改记意图），无 raw uuid
- [x] 接缝 S2 单测：live 幂等 + 软删失败；回归「已删 id 当成功」
- [x] 本票不拆计时 Service/Compose 文件（留给 09）

## Comments

- R2 定稿。
