# 管理员卸载后接回并重同步 — 票索引

Spec: [spec.md](./spec.md)
Status: complete
Source: grill → ticket 2026-07-29
Ticket count: 2
Frontier count: 0

## 依赖图

```text
01 NAS create→reclaim（complete） ──► 02 Android 接回会话 + full-resync UX（complete）
```

**可立即开工（frontier）：** 无

## 票列表

| ID | 标题 | Blocked by | Size | 状态 |
|---|---|---|---|---|
| [01](./issues/01-server-create-reclaim-owner.md) | NAS：已有家庭时 create 接回同一 owner | — | M | complete |
| [02](./issues/02-client-reclaim-session-and-pull.md) | Android：同一建家入口接回并全量 pull | 01 | M | complete |

## 过程纪律

- 一票一 PR/提交序列；服务端契约变更与客户端消费尽量按 01→02。
- 每票含 Documentation Gate；wire/行为变更不得只改代码不改 PRD。
- 不引入按 `device_id` 运行期合并 membership；不给成员 bootstrap 抢权。
