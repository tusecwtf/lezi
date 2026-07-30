# 离线模式 · 票索引

Spec: [spec.md](./spec.md)  
Status: active  
Immediate ready-for-agent: **01**

## Dependency graph

```text
01 产品契约
├── 02 引导「离线模式」入口 ──┬── 04 稍后加入家庭
│                           └── 05 稍后新建家庭
└── 03 账户未加入态 ──────────────┐
                                  └── 06 验收收口
```

## Tickets

| ID | Title | Blocked by | Status |
|----|-------|------------|--------|
| [01](./issues/01-product-contract-offline-mode.md) | 产品契约：离线模式 | — | ready-for-agent |
| [02](./issues/02-onboarding-offline-mode-entry.md) | 引导增加「离线模式」并进主屏 | 01 | blocked（01 完成后 ready） |
| [03](./issues/03-account-unjoined-offline-copy.md) | 账户：未加入时新建/加入家庭 | 01 | blocked（01 完成后 ready） |
| [04](./issues/04-later-join-family-orphan-rebind.md) | 稍后加入：本机数据并入权威宝宝 | 02 | blocked |
| [05](./issues/05-later-create-family-first-publish.md) | 稍后建家：本机数据首次发布 | 02 | blocked |
| [06](./issues/06-end-to-end-acceptance.md) | 端到端验收与文档收口 | 03、04、05 | blocked |

## Frontier

先做 **01**；契约落地后 **02** 与 **03** 可并行；**04** 与 **05** 在 **02** 后可并行；全部齐后 **06**。
