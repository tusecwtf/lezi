# 家庭身份与账户概览 — 票索引

Spec: [spec.md](./spec.md)  
Status: partial  
Source: grill-with-docs → to-spec → to-tickets · 多 agent 审查修订（5 票）

## 依赖图

Fresh-deployment override（2026-07-27）：记录上传者只按 current `membership_id` 解析；
`createdByUserId`/`created_by_device_id` 与旧 NAS 字段省略兼容均已 superseded。Ticket 02 仍需
current `family_name` value/null 双设备收敛；Ticket 05 的旧 device resolver 实现/证据不得作为
Release 通过依据，fresh-only 负向检查由整改 tracker 01/02 收口。

```text
01 称呼可认（含解析源） ──┬──► 03 账户首屏骨架 ──► 04 向导 + 邀请主行动
                         └──► 05 时间轴上传者
02 共享家庭名 ────────────► 03
```

**可立即继续（frontier）：** 无实现票；02 等待最终候选双设备 UI/Docker smoke

**已完成：** 01 · 03 · 04 · 05  

**纪律：** 01 与 02 产品上可并行，但 **create/join 请求体与 SyncPort 签名** 易撞车；落地时先合契约 expand（服务端可先收 `family_name` / 称呼校验），再开另一票客户端，或串行。**不要**裸并行双改同一 create 签名。

## 票列表

| ID | 标题 | Blocked by | Size | 状态 |
|----|------|------------|------|------|
| [01](./issues/01-member-display-name.md) | 家庭称呼可认 | — | L | done |
| [02](./issues/02-shared-family-name.md) | 共享家庭名（current pull value/null） | — | M | partial |
| [03](./issues/03-account-overview-shell.md) | 账户首屏骨架 | 01, 02 | M–L | done |
| [04](./issues/04-family-wizard-and-invite.md) | 向导 + 邀请主行动 | 03 | M | done |
| [05](./issues/05-timeline-uploader.md) | 时间轴上传者 | 01 | M | done（current behavior）；legacy resolver evidence superseded |

## 过程纪律

- 一 PR 一票主题；用 `/implement` 一次一张，清 context 再开下一张。  
- 契约/wire 变更同 PR 更新文档与测试（S2/S4）。  
- 不改记录/汇总/成长全局顶栏；不做踢人；不引入照护者一等实体。  
- 术语见根目录 `CONTEXT.md` 与 ADR-0002。

## Reopen Notes

- 2026-07-26 只读审计：管理员改名只更新 NAS 与发起设备的本地 session；既有成员设备没有 GET、会话摘要或同步实体路径取得新名字，因此 02 重开为 `partial`。
