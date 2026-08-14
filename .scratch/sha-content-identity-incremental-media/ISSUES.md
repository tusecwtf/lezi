# Issues — SHA 内容身份增量媒体

Status: ready-for-agent — frontier = **01**

| # | File | Title | Status | Blocked by |
|---|------|-------|--------|------------|
| 01 | [issues/01-freeze-sha-identity-contract.md](./issues/01-freeze-sha-identity-contract.md) | 冻结 SHA 身份合同与非目标 | ready-for-agent | — |
| 02 | [issues/02-local-sha-skip-download.md](./issues/02-local-sha-skip-download.md) | Room 存 SHA，下行按 digest 跳过 GET | ready-for-agent | 01 |
| 03 | [issues/03-pull-media-entity-sha256.md](./issues/03-pull-media-entity-sha256.md) | 独立 media pull 实体带 sha256 | ready-for-agent | 01 |
| 04 | [issues/04-family-blob-bind-skip-put.md](./issues/04-family-blob-bind-skip-put.md) | 家庭内按 SHA 绑定，跳过同内容 PUT | ready-for-agent | 01、02 |
| 05 | [issues/05-docs-and-acceptance.md](./issues/05-docs-and-acceptance.md) | PRD、测试与双端验收 | ready-for-agent | 02；03/04 若做了则一并收 |
| 06 | [issues/06-handshake-tip-skip-empty-pull.md](./issues/06-handshake-tip-skip-empty-pull.md) | 握手比家庭 tip，跳过空 pull | ready-for-agent | 01；不阻塞 02–04 |

## Frontier

```text
01 合同
 ├─► 02 本机对象库：按 SHA ID 取缺失 blob     ← 主收益
 ├─► 03 独立 media 实体补 sha256               ← 仅补 02 盲区
 ├─► 04 家庭 blob 绑定 + 跳过 PUT              ← 上行增量
 │       └─► 05 文档与验收
 └─► 06 握手比 tip，跳过空 pull                ← 可选，Git ref 比对
```

Parent: [spec.md](./spec.md)
