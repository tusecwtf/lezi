# Issues · p1-review-residuals

**Status:** ready-for-agent  
**Spec:** [spec.md](./spec.md)

## Dependency graph

```text
Immediate (0.3 independent)
  01 记录 ACL ─────────────┐
  02 未来事实时间 ──► 03 next-feed note
  04 Composer 串行 ────────┘  (并行)

Post-0.3.1 re-verify only (no implement before gate)
  05 Join 耐久
  06 401 / 吊销语义
  07 离开擦除与跨家庭
  08 被踢端收敛
       ▲
       └── gate: trusted-sync-endpoint-auth / 0.3.1 released
           then re-verify → cancelled if fixed, else ready-for-agent
```

## Tickets

| # | Ticket | Lane | Blocked by | Status |
|---|--------|------|------------|--------|
| [01](./issues/01-record-membership-acl.md) | 护理记录 membership ACL | immediate | — | done |
| [02](./issues/02-close-future-fact-writes.md) | 事实写入关闭未来时间 | immediate | — | ready-for-agent |
| [03](./issues/03-strip-next-feed-marker-on-fulfill.md) | 履行时剥离 next-feed note marker | immediate | 02 | ready-for-agent |
| [04](./issues/04-composer-import-save-serialization.md) | Composer 选图与保存串行 | immediate | — | ready-for-agent |
| [05](./issues/05-join-session-durability-reverify.md) | Join 会话耐久顺序 | post-0.3.1 | 0.3.1 发布 | planned |
| [06](./issues/06-auth-failure-taxonomy-reverify.md) | 鉴权失败与 leave 401 分类 | post-0.3.1 | 0.3.1 发布 | planned |
| [07](./issues/07-leave-wipe-cross-family-reverify.md) | 离开/删家擦除与跨家庭隔离 | post-0.3.1 | 0.3.1 发布 | planned |
| [08](./issues/08-removed-device-local-converge-reverify.md) | 被踢/撤设备本机收敛 | post-0.3.1 | 0.3.1 发布 | planned |

## Frontier

- **现在可领：** 02、04  
- **02 完成后：** 03  
- **不要领：** 05–08（直至 0.3.1 复核）

## Explicitly deferred elsewhere

| 主题 | 去向 |
|------|------|
| HTTPS / SPKI / 去 SSID / 短会话 token | trusted-sync-endpoint-auth 01–15 |
| 无 bootstrap 抢主 / 根密码模型 | trusted-sync 03/05 + 部署 fail-closed |
| trusted-sync **实现后** 审查残差（pending clobber、根密码限流、Member reauth 收据等） | [trusted-sync-review-residuals](../trusted-sync-review-residuals/ISSUES.md)（13 票；01–06 blocks 0.3.1） |
| custom_items.clientUuid UNIQUE | 未开票；下次 schema 叙事另议 |
