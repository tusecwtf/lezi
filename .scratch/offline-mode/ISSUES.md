# 离线模式 · 票索引

Spec: [spec.md](./spec.md)  
Status: complete  
Immediate ready-for-agent: **0**

## Dependency graph

```text
01 产品契约 (complete)
├── 02 引导「离线模式」入口 (complete) ──┬── 04 稍后加入家庭 (complete)
│                                        └── 05 稍后新建家庭 (complete)
└── 03 账户未加入态 (complete) ──────────────┐
                                             └── 06 验收收口 (complete)
```

## Tickets

| ID | Title | Blocked by | Status |
|----|-------|------------|--------|
| [01](./issues/01-product-contract-offline-mode.md) | 产品契约：离线模式 | — | complete |
| [02](./issues/02-onboarding-offline-mode-entry.md) | 引导增加「离线模式」并进主屏 | 01 | complete |
| [03](./issues/03-account-unjoined-offline-copy.md) | 账户：未加入时新建/加入家庭 | 01 | complete |
| [04](./issues/04-later-join-family-orphan-rebind.md) | 稍后加入：本机数据并入权威宝宝 | 02 | complete |
| [05](./issues/05-later-create-family-first-publish.md) | 稍后建家：本机数据首次发布 | 02 | complete |
| [06](./issues/06-end-to-end-acceptance.md) | 端到端验收与文档收口 | 03、04、05 | complete |

## Evidence

- Unit: `:feature:onboarding:testDebugUnitTest`, `:feature:family:testDebugUnitTest`, `RootRoutingPolicyTest`
- Device: API 35 emulator — clear data → 离线模式 → 建宝宝 → 主屏记录坞可见
- Later join/create: existing sync tests cover member orphan hold + owner first-create LocalWrite / baby+record push (`ReplicaSyncEngineTest`, `RealSyncPortTest`, `FamilySessionCoordinatorTest`)
