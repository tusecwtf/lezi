# 加入家庭后宝宝管理权限上移 — 票索引

Spec: [spec.md](./spec.md)
Status: complete
Source: `/to-spec` → `/to-tickets` 2026-07-28
Seams: **CareLog** · **ReplicaSyncEngine**（+ NAS 护栏）

## 依赖图

```text
01 成员不 push 宝宝 + NAS 护栏 ──┬──► 03 单权威自动再绑定 ──► 04 多权威孤→权威合并
02 成员档案写禁（CareLog+UI） ──┘（02 与 01 可并行；04 不硬依赖 02，但 UI 入口应与 02 一致）
```

**可立即开工（frontier）：** 无（01–04 complete）

**纪律：**

- 一 PR 一票；`/implement` 一次一张，清 context 再开下一张。
- 01 与 02 可并行，但避免同 PR 混改无关模块。
- 03 必须在 01 之后（否则孤宝宝降级可能把 tombstone 推上 NAS）。
- 不做昵称启发式自动合并两个权威宝宝；不做管理员转移。
- 术语：家庭管理员、家庭 membership、本机孤宝宝、家庭权威宝宝 — 见 spec / `CONTEXT.md`。

## 票列表

| ID | 标题 | Blocked by | 状态 |
|----|------|------------|------|
| [01](./issues/01-member-never-publishes-babies.md) | 成员不同步宝宝实体（客户端 + NAS 护栏） | — | complete |
| [02](./issues/02-member-baby-profile-write-lock.md) | 成员禁止管理宝宝档案（CareLog 闸门 + UI） | — | complete |
| [03](./issues/03-single-authority-orphan-rebind.md) | 单权威宝宝时自动收敛本机孤宝宝 | 01 | complete |
| [04](./issues/04-multi-authority-orphan-merge.md) | 多权威宝宝时成员孤→权威显式合并 | 01, 03 | complete |

## 建议实施顺序

1. **01** 止血上行双宝宝（可与 02 并行）
2. **02** 权限上移写路径与 UI
3. **03** 单娃家庭加入后列表与数据收敛（主路径 demo）
4. **04** 多娃家庭成员数据安全网
